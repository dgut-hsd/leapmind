#!/usr/bin/env python3
"""手动投射：读 user_events PENDING 事件 → 调用 Python 画像引擎 → 写回画像"""
import os, sys, re, pymysql, requests, json
from datetime import datetime, timezone

sys.path.insert(0, r'D:\code\HSDProjectWrokPlace\Leapmind\leapmind\aitutor-backend-python\src')
os.chdir(r'D:\code\HSDProjectWrokPlace\Leapmind\leapmind\aitutor-backend-python')
from dotenv import load_dotenv; load_dotenv()
from landppt.core.config import app_config

USER_ID = 23
PYTHON_URL = "http://127.0.0.1:8001/api/internal/ai/build-profile"

# 解析数据库连接
url = app_config.database_url
m = re.match(r'mysql\+pymysql://([^:]+):([^@]+)@([^:]+):(\d+)/([^?]+)', url)
user, pwd, host, port, db = m.groups()
conn = pymysql.connect(host=host, port=int(port), user=user, password=pwd, database=db, charset='utf8mb4')
cur = conn.cursor()

# 1. 查当前画像版本
cur.execute("SELECT profile_version, last_processed_event_id FROM user_profiles WHERE user_id=%s", (USER_ID,))
row = cur.fetchone()
base_version = row[0] if row else 0
last_event_id = row[1] if row and row[1] else 0
print(f"当前画像版本: {base_version}, 上次处理事件ID: {last_event_id}")

# 2. 拉取未处理事件
cur.execute("""
    SELECT id, event_id, event_type, source_module, occurred_at, schema_version,
           session_id, kp_id, trace_id, event_data_json
    FROM user_events
    WHERE user_id=%s AND id > %s AND process_status='PENDING'
    ORDER BY id
    LIMIT 1000
""", (USER_ID, last_event_id))
rows = cur.fetchall()
print(f"PENDING 事件: {len(rows)} 条")

if not rows:
    print("没有待处理事件")
    conn.close()
    sys.exit(0)

# 3. 构造 ProfileEvent 列表
events = []
for i, r in enumerate(rows):
    eid, event_id, event_type, src, occurred, sv, sid, kpid, tid, edata = r
    if isinstance(occurred, datetime):
        occurred = occurred.replace(tzinfo=timezone.utc).isoformat()
    ev = {
        "dbEventId": eid,
        "eventId": event_id,
        "eventType": event_type,
        "sourceModule": src,
        "occurredAt": occurred,
        "schemaVersion": sv,
        "data": json.loads(edata) if isinstance(edata, str) else edata,
    }
    if sid: ev["sessionId"] = sid
    if kpid: ev["kpId"] = kpid
    if tid: ev["traceId"] = tid
    events.append(ev)

watermark = max(e["dbEventId"] for e in events)

# 4. 调用 Python 画像引擎
payload = {
    "contractVersion": "1.0",
    "requestId": "manual-projection-" + datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S"),
    "userId": USER_ID,
    "mode": "INCREMENTAL" if base_version > 0 else "FULL",
    "baseProfileVersion": base_version,
    "fromEventIdExclusive": last_event_id,
    "eventWatermarkInclusive": watermark,
    "events": events,
}

print(f"调用 Python 画像引擎: {len(events)} 个事件...")
r = requests.post(PYTHON_URL, json=payload, timeout=60)
result = r.json()
print(f"状态: {result.get('status')}")
print(f"算法版本: {result.get('algorithmVersion')}")
print(f"目标版本: {result.get('targetProfileVersion')}")

if result.get('status') == 'READY':
    p = result.get('profile', {})
    print(f"置信度: {p.get('confidence')}")
    print(f"学习节奏: {p.get('learningPace')}")
    print(f"内容模式: {p.get('preferredContentModes')}")
    print(f"近期关注: {len(p.get('recentFocus', []))} 个知识点")
    print(f"近期困惑: {len(p.get('recentConfusions', []))} 条")
    for f in p.get('recentFocus', [])[:5]:
        print(f"  kpId={f['kpId']} weight={f['weight']}")

    # 5. 写回画像
    target_ver = result['targetProfileVersion']
    profile_json = json.dumps(p, ensure_ascii=False)
    confidence = p.get('confidence', 0.0)
    learning_pace = p.get('learningPace')
    preferred_modes = json.dumps(p.get('preferredContentModes', []))
    explanation_style = p.get('preferredExplanationStyle')
    summary = p.get('summaryProfile', '')
    algo_ver = result.get('algorithmVersion', 'm6-profile-v1.1.0')
    evaluated_at = result.get('evaluatedAt', datetime.now(timezone.utc).isoformat())

    cur.execute("""
        INSERT INTO user_profiles (user_id, profile_version, profile_status, confidence,
            preferred_content_modes_json, preferred_explanation_style, learning_pace,
            summary_profile, algorithm_version, last_processed_event_id, profile_data_json, computed_at)
        VALUES (%s,%s,'READY',%s,%s,%s,%s,%s,%s,%s,%s,%s)
        ON DUPLICATE KEY UPDATE
            profile_version=%s, profile_status='READY', confidence=%s,
            preferred_content_modes_json=%s, preferred_explanation_style=%s,
            learning_pace=%s, summary_profile=%s, algorithm_version=%s,
            last_processed_event_id=%s, profile_data_json=%s, computed_at=%s
    """, (USER_ID, target_ver, confidence, preferred_modes, explanation_style,
          learning_pace, summary, algo_ver, watermark, profile_json, evaluated_at,
          target_ver, confidence, preferred_modes, explanation_style,
          learning_pace, summary, algo_ver, watermark, profile_json, evaluated_at))

    # 6. 更新事件状态
    cur.execute("""
        UPDATE user_events SET process_status='PROCESSED', processed_at=NOW()
        WHERE user_id=%s AND id > %s AND id <= %s
    """, (USER_ID, last_event_id, watermark))

    conn.commit()
    print(f"\n画像已写入 v{target_ver}，{cur.rowcount} 个事件标记为 PROCESSED")

    # 7. 写掌握度
    mastery_list = result.get('knowledgeMastery', [])
    for km in mastery_list:
        cur.execute("""
            INSERT INTO user_knowledge_mastery
                (user_id, kp_id, profile_version, mastery_score, confidence,
                 mastery_status, evidence_count, trend, algorithm_version,
                 window_start, window_end)
            VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
            ON DUPLICATE KEY UPDATE
                profile_version=%s, mastery_score=%s, confidence=%s,
                mastery_status=%s, evidence_count=%s, trend=%s,
                algorithm_version=%s, window_start=%s, window_end=%s
        """, (
            USER_ID, km['kpId'], target_ver, km['masteryScore'], km['confidence'],
            km['masteryStatus'], km['evidenceCount'], km.get('trend'), algo_ver,
            km.get('windowStart'), km.get('windowEnd'),
            target_ver, km['masteryScore'], km['confidence'],
            km['masteryStatus'], km['evidenceCount'], km.get('trend'),
            algo_ver, km.get('windowStart'), km.get('windowEnd'),
        ))
    conn.commit()
    print(f"{len(mastery_list)} 个知识点掌握度已写入")
    for km in mastery_list:
        print(f"  kpId={km['kpId']} {km['masteryStatus']} score={km['masteryScore']} evidence={km['evidenceCount']}")

elif result.get('status') == 'NO_CHANGE':
    print("画像无需更新")
elif result.get('status') == 'INSUFFICIENT_DATA':
    print("数据不足")

conn.close()
print("\n完成！")
