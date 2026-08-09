#!/usr/bin/env python3
"""注入完整画像测试数据，确保四来源全部覆盖"""
import os, requests, json, uuid

BASE = "http://127.0.0.1:8080"
TOKEN = os.environ.get("M6_TEST_TOKEN", "")
USER_ID = 23
HEADERS = {"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"}

def event(etype, kpid, data, sid=None, t="2026-08-09T21:00:00+08:00"):
    return {
        "eventId": f"seed-{uuid.uuid4().hex[:12]}",
        "eventType": etype,
        "sourceModule": {"answer_question":"M1","finish_practice":"M1","wrong_question_changed":"M1",
                         "weak_point_changed":"M3","explanation_feedback":"M2",
                         "mark_reviewed":"M6","preference_changed":"M6"}[etype],
        "occurredAt": t, "schemaVersion": "1.0",
        "userId": USER_ID, "kpId": kpid, "sessionId": sid, "traceId": None, "data": data
    }

def send(events):
    payload = {"events": [json.dumps(e) for e in events]}
    # 逐条发送确保落库
    count = 0
    for e in events:
        r = requests.post(f"{BASE}/api/user-profile/{USER_ID}/record-event", json=e, headers=HEADERS)
        if r.status_code == 200:
            count += 1
        else:
            print(f"  FAIL: {e['eventType']} - {r.status_code}")
    print(f"  {count}/{len(events)} ACCEPTED")
    return count

print("=" * 60)
print("注入完整画像测试数据")
print("=" * 60)

# ---- 一、answer 来源 (kpId=101,102,103) ----
print("\n[1] M1 答题 — kpId=101/102/103 + 练习总结")
events = []
# kpId=101: 12次答题 (10正确+2错误) → MASTERED
for i in range(12):
    events.append(event("answer_question", 101,
        {"isCorrect": i < 10, "difficulty": 3, "timeSpentSec": 30, "hintCount": 0 if i < 10 else 1},
        sid="session-a"))
# kpId=102: 8次答题 (5正确+3错误) → BASIC_MASTERY
for i in range(8):
    events.append(event("answer_question", 102,
        {"isCorrect": i < 5, "difficulty": 3, "timeSpentSec": 45, "hintCount": 0 if i < 5 else 1},
        sid="session-b"))
# kpId=103: 6次 (3正确+3错误) → CONSOLIDATING
for i in range(6):
    events.append(event("answer_question", 103,
        {"isCorrect": i < 3, "difficulty": 4, "timeSpentSec": 60, "hintCount": 1},
        sid="session-c"))
# 练习总结
events.append(event("finish_practice", None, {"questionCount": 12, "accuracy": 0.83, "durationSec": 360}, sid="session-a"))
events.append(event("finish_practice", None, {"questionCount": 8, "accuracy": 0.625, "durationSec": 360}, sid="session-b"))
events.append(event("finish_practice", None, {"questionCount": 6, "accuracy": 0.5, "durationSec": 360}, sid="session-c"))
send(events)

# ---- 二、weakPoint 来源 (kpId=102,103) ----
print("\n[2] M3 薄弱点变更 — kpId=102/103")
events = [
    event("weak_point_changed", 102, {"oldScore": 0.9, "newScore": 0.4, "reason": "ACCURACY_DROP"}),
    event("weak_point_changed", 103, {"oldScore": 0.7, "newScore": 0.2, "reason": "REPEATED_ERROR"}),
    # 同一个kpId多次变更，只取最新
    event("weak_point_changed", 102, {"oldScore": 0.4, "newScore": 0.3, "reason": "RECALCULATED"}),
]
send(events)

# ---- 三、question 来源 ----
print("\n[3] M2 讲解反馈 — kpId=102/103")
events = [
    event("explanation_feedback", 102, {"explainId": "explain.a.1", "feedback": "understood", "repeatCount": 0}),
    event("explanation_feedback", 103, {"explainId": "explain.b.1", "feedback": "partly_understood", "repeatCount": 1}),
    event("explanation_feedback", 103, {"explainId": "explain.b.2", "feedback": "still_confused", "repeatCount": 0}),
]
send(events)

# ---- 四、wrongQuestion 来源 (核心新增!) ----
print("\n[4] M1 错题本变更 — kpId=101/102")
events = [
    # kpId=101: 4道错题，3解决+1复习中 → 得分 (1+1+0+0.5)/4 = 0.625
    event("wrong_question_changed", 101, {"questionId": 5001, "status": "RESOLVED", "wrongCount": 2}),
    event("wrong_question_changed", 101, {"questionId": 5002, "status": "RESOLVED", "wrongCount": 1}),
    event("wrong_question_changed", 101, {"questionId": 5003, "status": "UNRESOLVED", "wrongCount": 3}),
    event("wrong_question_changed", 101, {"questionId": 5004, "status": "REVIEWING", "wrongCount": 1}),
    # kpId=102: 2道错题，1解决+1未解决 → 得分 (1+0)/2 = 0.5
    event("wrong_question_changed", 102, {"questionId": 6001, "status": "RESOLVED", "wrongCount": 1}),
    event("wrong_question_changed", 102, {"questionId": 6002, "status": "UNRESOLVED", "wrongCount": 1}),
]
send(events)

# ---- 五、偏好 ----
print("\n[5] M6 偏好设置")
events = [
    event("preference_changed", None, {"preferenceKey": "content_mode", "preferenceValue": "video"}),
    event("preference_changed", None, {"preferenceKey": "explanation_style", "preferenceValue": "step_by_step"}),
    event("preference_changed", None, {"preferenceKey": "learning_pace", "preferenceValue": "moderate"}),
]
send(events)

print("\n" + "=" * 60)
print("数据注入完成！等待投影（约 30-60 秒）后查询画像。")
print("=" * 60)
