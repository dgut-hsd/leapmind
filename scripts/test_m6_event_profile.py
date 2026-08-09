#!/usr/bin/env python3
"""M6 事件上报 → 画像生成 联调测试脚本"""
import os, requests, json, time, uuid

BASE = "http://127.0.0.1:8080"
PYTHON = "http://127.0.0.1:8000"
TOKEN = os.environ.get("M6_TEST_TOKEN", "")
USER_ID = 23
HEADERS = {"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"}

def send_event(event_type, kp_id, data, session_id=None):
    """上报单条学习事件"""
    payload = {
        "eventId": f"test-{uuid.uuid4().hex[:12]}",
        "eventType": event_type,
        "sourceModule": {"answer_question": "M1", "finish_practice": "M1",
                         "wrong_question_changed": "M1", "weak_point_changed": "M3",
                         "ask_doubt": "M7", "explanation_feedback": "M2",
                         "mark_reviewed": "M6", "preference_changed": "M6"}.get(event_type, "M1"),
        "occurredAt": "2026-08-09T20:30:00+08:00",
        "schemaVersion": "1.0",
        "userId": USER_ID,
        "kpId": kp_id,
        "sessionId": session_id,
        "traceId": None,
        "data": data
    }
    url = f"{BASE}/api/user-profile/{USER_ID}/record-event"
    r = requests.post(url, json=payload, headers=HEADERS)
    result = r.json()
    status = result.get("data", {}).get("eventStatus", "?")
    print(f"  [{status}] {event_type} kpId={kp_id} data={data}")
    return r.status_code == 200

def query_profile():
    """查询画像"""
    r = requests.get(f"{BASE}/api/user-profile/{USER_ID}", headers=HEADERS)
    if r.status_code == 200:
        data = r.json().get("data", {})
        print(f"\n  画像版本: {data.get('profileVersion')}")
        print(f"  画像状态: {data.get('profileStatus')}")
        print(f"  置信度: {data.get('confidence')}")
        p = data.get("profile", {})
        if p:
            print(f"  学习节奏: {p.get('learningPace')}")
            print(f"  近期关注: {p.get('recentFocus', [])[:3]}")
        return data
    else:
        print(f"  查询失败: {r.status_code} {r.text[:100]}")
        return None

def query_mastery():
    """查询知识点掌握度"""
    r = requests.get(f"{BASE}/api/user-profile/{USER_ID}/knowledge-status?kpId=101&kpId=102&kpId=103", headers=HEADERS)
    if r.status_code == 200:
        items = r.json().get("data", {}).get("items", [])
        for item in items:
            print(f"  kpId={item.get('kpId')} status={item.get('masteryStatus')} score={item.get('masteryScore')} evidence={item.get('evidenceCount')}")

def query_events():
    """查询最近的用户事件"""
    r = requests.get(f"{PYTHON}/api/user-profile/{USER_ID}/timeline", headers=HEADERS)
    if r.status_code == 200:
        items = r.json().get("data", [])
        print(f"  最近事件: {len(items)} 条")
        for item in items[:5]:
            print(f"    {item.get('eventType')} kpId={item.get('kpId')} status={item.get('processStatus')}")

def main():
    print("=" * 60)
    print("M6 事件上报 → 画像生成 测试")
    print("=" * 60)

    # 步骤1: 上报答题事件 (kpId=101)
    print("\n[1] 上报 M1 答题事件...")
    for i in range(8):
        send_event("answer_question", 101,
                   {"isCorrect": i < 6, "difficulty": 3, "timeSpentSec": 30, "hintCount": 0 if i < 6 else 1},
                   session_id=f"test-session-{i//4}")

    # 步骤2: 上报练习总结
    print("\n[2] 上报 M1 练习总结...")
    send_event("finish_practice", None, {"questionCount": 4, "accuracy": 0.75, "durationSec": 120}, session_id="test-session-0")
    send_event("finish_practice", None, {"questionCount": 4, "accuracy": 0.75, "durationSec": 140}, session_id="test-session-1")

    # 步骤3: 上报 M3 薄弱点变更 (kpId=102)
    print("\n[3] 上报 M3 薄弱点变更...")
    send_event("weak_point_changed", 102, {"oldScore": 0.9, "newScore": 0.5, "reason": "ACCURACY_DROP"})
    send_event("weak_point_changed", 103, {"oldScore": 0.8, "newScore": 0.3, "reason": "REPEATED_ERROR"})

    # 步骤4: 上报 M2 讲解反馈
    print("\n[4] 上报 M2 讲解反馈...")
    send_event("explanation_feedback", 102, {"explainId": "explain.test.1", "feedback": "partly_understood", "repeatCount": 1})

    # 步骤5: 上报 M1 错题本变更 (本次新增!)
    print("\n[5] 上报 M1 错题本变更 (wrong_question_changed)...")
    send_event("wrong_question_changed", 101, {"questionId": 5001, "status": "RESOLVED", "wrongCount": 2})
    send_event("wrong_question_changed", 101, {"questionId": 5002, "status": "UNRESOLVED", "wrongCount": 1})
    send_event("wrong_question_changed", 101, {"questionId": 5003, "status": "REVIEWING", "wrongCount": 3})
    # 同一题再变更，测试是否取最新状态
    send_event("wrong_question_changed", 101, {"questionId": 5002, "status": "RESOLVED", "wrongCount": 1})

    # 步骤6: 上报偏好
    print("\n[6] 上报 M6 偏好变更...")
    send_event("preference_changed", None, {"preferenceKey": "learning_pace", "preferenceValue": "moderate"})

    print("\n" + "=" * 60)
    print("等待投影周期（约 30-60 秒）...")
    print("=" * 60)

    # 等待 Java 定时投影消费事件
    time.sleep(35)

    # 步骤7: 查询结果
    print("\n[7] 查询用户事件状态...")
    query_events()

    print("\n[8] 查询用户画像...")
    query_profile()

    print("\n[9] 查询知识点掌握度...")
    query_mastery()

    print("\n" + "=" * 60)
    print("测试完成")
    print("=" * 60)

if __name__ == "__main__":
    main()
