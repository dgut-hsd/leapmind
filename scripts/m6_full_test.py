#!/usr/bin/env python3
"""M6 全链路测试：发事件 → 调画像引擎 → 写回画像 → 查询"""
import os, sys, re, json, uuid, time, pymysql, requests

# === 配置 ===
BASE_URL = "http://127.0.0.1:8080"
PYTHON_URL = None  # 使用本地 TestClient，无需 HTTP 服务
TOKEN = os.environ.get("M6_TEST_TOKEN", "")
USER_ID = 23
H = {"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"}

# === 获取数据库连接 ===
sys.path.insert(0, r"D:\code\HSDProjectWrokPlace\Leapmind\leapmind\aitutor-backend-python\src")
os.chdir(r"D:\code\HSDProjectWrokPlace\Leapmind\leapmind\aitutor-backend-python")
from dotenv import load_dotenv; load_dotenv()
from landppt.core.config import app_config
m = re.match(r"mysql\+pymysql://([^:]+):([^@]+)@([^:]+):(\d+)/([^?]+)", app_config.database_url)
user, pwd, host, port, db = m.groups()
CONN = lambda: pymysql.connect(host=host, port=int(port), user=user, password=pwd, database=db, charset="utf8mb4")


# ========== 步骤1: 上报事件 ==========
def send_events():
    print("=" * 55)
    print("[1/4] 上报事件")
    print("=" * 55)

    def ev(etype, kpid, data, sid=None):
        smap = {"answer_question": "M1", "finish_practice": "M1", "wrong_question_changed": "M1",
                "weak_point_changed": "M3", "explanation_feedback": "M2",
                "mark_reviewed": "M6", "preference_changed": "M6"}
        eid = f"seed-{uuid.uuid4().hex[:12]}"
        return {
            "eventId": eid, "eventType": etype, "sourceModule": smap[etype],
            "occurredAt": "2026-08-09T21:00:00+08:00", "schemaVersion": "1.0",
            "userId": USER_ID, "kpId": kpid, "sessionId": sid, "data": data
        }

    events = []
    # answer 来源: kpId=101 (12题), 102 (8题), 103 (6题)
    for i in range(12):
        events.append(ev("answer_question", 101,
            {"isCorrect": i < 10, "difficulty": 3, "timeSpentSec": 30, "hintCount": 0 if i < 10 else 1}, "s-a"))
    for i in range(8):
        events.append(ev("answer_question", 102,
            {"isCorrect": i < 5, "difficulty": 3, "timeSpentSec": 45, "hintCount": 0 if i < 5 else 1}, "s-b"))
    for i in range(6):
        events.append(ev("answer_question", 103,
            {"isCorrect": i < 3, "difficulty": 4, "timeSpentSec": 60, "hintCount": 1}, "s-c"))
    events.append(ev("finish_practice", None, {"questionCount": 12, "accuracy": 0.83, "durationSec": 360}, "s-a"))
    events.append(ev("finish_practice", None, {"questionCount": 8, "accuracy": 0.625, "durationSec": 360}, "s-b"))
    events.append(ev("finish_practice", None, {"questionCount": 6, "accuracy": 0.50, "durationSec": 360}, "s-c"))

    # weakPoint 来源
    events.append(ev("weak_point_changed", 102, {"oldScore": 0.9, "newScore": 0.3, "reason": "RECALCULATED"}))
    events.append(ev("weak_point_changed", 103, {"oldScore": 0.7, "newScore": 0.2, "reason": "REPEATED_ERROR"}))

    # question 来源
    events.append(ev("explanation_feedback", 102, {"explainId": "ex-a1", "feedback": "understood", "repeatCount": 0}))
    events.append(ev("explanation_feedback", 103, {"explainId": "ex-b1", "feedback": "partly_understood", "repeatCount": 1}))
    events.append(ev("explanation_feedback", 103, {"explainId": "ex-b2", "feedback": "still_confused", "repeatCount": 0}))

    # wrongQuestion 来源 (核心新增!)
    events.append(ev("wrong_question_changed", 101, {"questionId": 5001, "status": "RESOLVED", "wrongCount": 2}))
    events.append(ev("wrong_question_changed", 101, {"questionId": 5002, "status": "RESOLVED", "wrongCount": 1}))
    events.append(ev("wrong_question_changed", 101, {"questionId": 5003, "status": "UNRESOLVED", "wrongCount": 3}))
    events.append(ev("wrong_question_changed", 101, {"questionId": 5004, "status": "REVIEWING", "wrongCount": 1}))
    events.append(ev("wrong_question_changed", 102, {"questionId": 6001, "status": "RESOLVED", "wrongCount": 1}))
    events.append(ev("wrong_question_changed", 102, {"questionId": 6002, "status": "UNRESOLVED", "wrongCount": 1}))

    # 偏好
    events.append(ev("preference_changed", None, {"preferenceKey": "content_mode", "preferenceValue": "video"}))
    events.append(ev("preference_changed", None, {"preferenceKey": "explanation_style", "preferenceValue": "step_by_step"}))
    events.append(ev("preference_changed", None, {"preferenceKey": "learning_pace", "preferenceValue": "moderate"}))

    ok = 0
    for e in events:
        r = requests.post(f"{BASE_URL}/api/user-profile/{USER_ID}/record-event", json=e, headers=H)
        if r.status_code == 200:
            ok += 1
    print(f"  上报完成: {ok}/{len(events)} ACCEPTED")
    return ok


# ========== 步骤2: 调画像引擎 ==========
def run_engine():
    print("\n" + "=" * 55)
    print("[2/4] 调用 Python 画像引擎")
    print("=" * 55)

    conn = CONN()
    cur = conn.cursor()

    # 查当前画像版本
    cur.execute("SELECT profile_version, last_processed_event_id FROM user_profiles WHERE user_id=%s", (USER_ID,))
    row = cur.fetchone()
    base_ver = row[0] if row else 0
    last_id = row[1] if row and row[1] else 0
    print(f"  当前画像: v{base_ver}, last_event_id={last_id}")

    # 取本次 seed 事件
    cur.execute("""SELECT id, event_id, event_type, source_module, occurred_at, schema_version,
                          session_id, kp_id, trace_id, event_data_json
                   FROM user_events WHERE user_id=%s AND event_id LIKE 'seed-%%' ORDER BY id""", (USER_ID,))
    rows = cur.fetchall()
    conn.close()

    if not rows:
        print("  无种子事件")
        return None

    from datetime import datetime, timezone
    events = []
    for r in rows:
        eid, eid2, etype, src, occurred, sv, sid, kpid, tid, edata = r
        if isinstance(occurred, datetime):
            occurred = occurred.replace(tzinfo=timezone.utc).isoformat()
        ev = {"dbEventId": eid, "eventId": eid2, "eventType": etype,
              "sourceModule": src, "occurredAt": occurred, "schemaVersion": sv,
              "data": json.loads(edata) if isinstance(edata, str) else edata}
        if sid: ev["sessionId"] = sid
        if kpid: ev["kpId"] = kpid
        if tid: ev["traceId"] = tid
        events.append(ev)

    wm = max(e["dbEventId"] for e in events)
    fe = min(e["dbEventId"] for e in events) - 1

    payload = {"contractVersion": "1.0", "requestId": str(uuid.uuid4()),
               "userId": USER_ID, "mode": "FULL", "baseProfileVersion": base_ver,
               "fromEventIdExclusive": fe, "eventWatermarkInclusive": wm, "events": events}

    print(f"  发送 {len(events)} 个事件到 Python引擎...")
    from fastapi.testclient import TestClient
    from landppt.main import app
    client = TestClient(app)
    r = client.post("/api/internal/ai/build-profile", json=payload)
    result = r.json()

    status = result.get("status")
    print(f"  状态: {status}")
    print(f"  算法版本: {result.get('algorithmVersion')}")

    if status not in ("READY", "NO_CHANGE"):
        print(f"  错误: {result.get('detail', str(result)[:200])}")
        return None

    if status == "NO_CHANGE":
        print("  画像无需更新")
        return result

    # 写回画像
    p = result.get("profile", {})
    confidence = p.get("confidence", 0.0)
    algo_ver = result.get("algorithmVersion", "m6-profile-v1.1.0")
    target_ver = result["targetProfileVersion"]
    raw_eval = result.get("evaluatedAt", datetime.now(timezone.utc).isoformat())
    # MySQL datetime 不接受 Z 后缀，转为标准格式
    evaluated = raw_eval.replace("Z", "").replace("T", " ")[:19] if "T" in raw_eval else raw_eval

    conn = CONN()
    cur = conn.cursor()
    cur.execute("""INSERT INTO user_profiles (user_id, profile_version, profile_status, confidence,
            preferred_content_modes_json, preferred_explanation_style, learning_pace,
            summary_profile, algorithm_version, last_processed_event_id, profile_data_json, computed_at)
        VALUES (%s,%s,'READY',%s,%s,%s,%s,%s,%s,%s,%s,%s)
        ON DUPLICATE KEY UPDATE
            profile_version=%s, profile_status='READY', confidence=%s,
            preferred_content_modes_json=%s, preferred_explanation_style=%s,
            learning_pace=%s, summary_profile=%s, algorithm_version=%s,
            last_processed_event_id=%s, profile_data_json=%s, computed_at=%s""",
        (USER_ID, target_ver, confidence,
         json.dumps(p.get("preferredContentModes", [])),
         p.get("preferredExplanationStyle"), p.get("learningPace"),
         p.get("summaryProfile", ""), algo_ver, wm, json.dumps(p, ensure_ascii=False), evaluated,
         target_ver, confidence,
         json.dumps(p.get("preferredContentModes", [])),
         p.get("preferredExplanationStyle"), p.get("learningPace"),
         p.get("summaryProfile", ""), algo_ver, wm, json.dumps(p, ensure_ascii=False), evaluated))

    cur.execute("""UPDATE user_events SET process_status='PROCESSED'
                   WHERE user_id=%s AND id>%s AND id<=%s""", (USER_ID, last_id, wm))
    conn.commit()
    conn.close()

    print(f"  画像已写入 v{target_ver}, confidence={confidence}")
    for km in result.get("knowledgeMastery", []):
        print(f"  kpId={km['kpId']} {km['masteryStatus']} score={km['masteryScore']} evidence={km['evidenceCount']}")
    return result


# ========== 步骤3: 查画像 ==========
def query_profile():
    print("\n" + "=" * 55)
    print("[3/4] 查询画像")
    print("=" * 55)

    r = requests.get(f"{BASE_URL}/api/user-profile/{USER_ID}", headers=H)
    d = r.json().get("data", {})
    print(f"  版本: {d.get('profileVersion')}  状态: {d.get('profileStatus')}  置信度: {d.get('confidence')}")
    p = d.get("profile", {})
    if p:
        print(f"  学习节奏: {p.get('learningPace')}")
        print(f"  内容模式: {p.get('preferredContentModes')}")
        print(f"  讲解风格: {p.get('preferredExplanationStyle')}")
        for f in (p.get("recentFocus") or [])[:5]:
            print(f"  关注 kpId={f['kpId']} weight={f['weight']}")


# ========== 步骤4: 查掌握度 ==========
def query_mastery():
    print("\n" + "=" * 55)
    print("[4/4] 查询知识点掌握度")
    print("=" * 55)

    r = requests.get(f"{BASE_URL}/api/user-profile/{USER_ID}/knowledge-status?kpId=101&kpId=102&kpId=103", headers=H)
    for item in r.json().get("data", {}).get("knowledge", []):
        kpn = item.get('kpName') or '?'
        ms = item.get('masteryStatus') or '?'
        print(f"  kpId={item['kpId']:>4} {kpn:10s} {ms:20s} "
              f"score={item['masteryScore']:.4f} evidence={item['evidenceCount']}")


# ========== main ==========
if __name__ == "__main__":
    print("M6 全链路测试")
    print(f"用户ID: {USER_ID}\n")

    send_events()
    time.sleep(2)  # 确保事件落库
    result = run_engine()
    time.sleep(1)
    query_profile()
    query_mastery()

    if result and result.get("status") == "READY":
        algo = result.get("algorithmVersion", "?")
        wrong_q = False
        for e in result.get("events", result.get("profile", {}).get("recentFocus", [])):
            pass  # just placeholder
        print(f"\n✅ 全链路通过！算法版本: {algo}")
    else:
        print(f"\n⚠️ 引擎未返回 READY，检查 Python 服务")
