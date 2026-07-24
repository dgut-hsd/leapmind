"""
测试脚本 — 不依赖数据库，使用模拟数据测试核心算法

运行方式：
    cd backend
    python -m tests.test_weakness
"""
import sys
from datetime import datetime, timedelta
from pathlib import Path

# 将 backend 目录加入 sys.path，以便直接运行
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from calculator.weakness_score import calculate_weakness_scores
from calculator.trend_analysis import analyze_trends
from models.schemas import WeakPointsResponse


def make_answers(
    user_id: int,
    kp_id: int,
    correct_count: int,
    wrong_count: int,
    base_time: datetime,
    start_offset_hours: int = 0,
):
    """生成模拟答题数据

    start_offset_hours: 第一条数据的偏移小时数（用于把数据放到过去更远的位置）
    """
    answers = []
    total = correct_count + wrong_count
    for i in range(total):
        is_correct = 1 if i < correct_count else 0
        answers.append({
            "kp_id": kp_id,
            "is_correct": is_correct,
            "created_at": base_time - timedelta(hours=start_offset_hours + i),
        })
    return answers


def test_weakness_calculation():
    """测试薄弱度计算核心逻辑"""

    now = datetime.now()

    # ── 模拟知识点 ──
    knowledge_points = [
        {"id": 10, "name": "勾股定理", "parent_id": 1},
        {"id": 11, "name": "相似三角形", "parent_id": 1},
        {"id": 20, "name": "一元二次方程", "parent_id": 2},
    ]

    # ── 模拟答题数据 ──
    # 用 start_offset_hours=200（约8.3天前），确保数据落进"前7天"窗口(7-14天)
    user_answers = []
    # 勾股定理：10次对，20次错 → error_rate=0.667，薄弱
    user_answers.extend(make_answers(1, 10, 10, 20, now, start_offset_hours=200))
    # 相似三角形：15次对，5次错 → error_rate=0.25，较好
    user_answers.extend(make_answers(1, 11, 15, 5, now, start_offset_hours=200))
    # 一元二次方程：20次对，10次错 → error_rate=0.333，中等
    user_answers.extend(make_answers(1, 20, 20, 10, now, start_offset_hours=200))

    # ── 模拟对话记录 ──
    conversation_messages = [
        {"kp_id": 10, "content": "勾股定理怎么证明？"},
        {"kp_id": 10, "content": "勾股数有哪些？"},
        {"kp_id": 10, "content": "勾股定理逆定理是什么？"},
        {"kp_id": 10, "content": "为什么 a²+b²=c²？"},
        {"kp_id": 20, "content": "一元二次方程求根公式"},
    ]

    # ── 执行计算 ──
    results = calculate_weakness_scores(
        user_answers, conversation_messages, knowledge_points
    )

    # ── 验证结果 ──
    print("=" * 60)
    print("测试：薄弱度计算")
    print("=" * 60)

    assert len(results) == 3, f"期望 3 个结果，实际 {len(results)}"

    # 按薄弱度降序，勾股定理应该排第一
    assert results[0]["kp_id"] == 10, "勾股定理应该是最薄弱的知识点"
    assert results[0]["weakness_score"] > results[1]["weakness_score"], "薄弱度应该降序排列"

    for r in results:
        print(
            f"  {r['kp_name']:12s}  "
            f"weakness={r['weakness_score']:.3f}  "
            f"error_rate={r['error_rate']:.3f}  "
            f"recent_correct={r['recent_correct_rate']:.3f}  "
            f"confusion={r['confusion_count']}"
        )

    print("  [OK] 薄弱度计算通过\n")
    return user_answers, results


def test_trend_analysis(user_answers, weak_points):
    """测试趋势分析"""

    # 为勾股定理模拟近7天高错误率数据
    now = datetime.now()
    for i in range(10):
        # 近3天的都错 → declining
        user_answers.append({
            "kp_id": 10,
            "is_correct": 0,
            "created_at": now - timedelta(hours=i),
        })

    # 为相似三角形模拟近7天全对数据 → improving
    for i in range(10):
        user_answers.append({
            "kp_id": 11,
            "is_correct": 1,
            "created_at": now - timedelta(hours=i),
        })

    # ── 执行 ──
    results = analyze_trends(user_answers, weak_points)

    print("=" * 60)
    print("测试：趋势分析")
    print("=" * 60)

    trend_map = {r["kp_id"]: r["trend"] for r in results}
    print(f"  勾股定理趋势：{trend_map.get(10)}")
    print(f"  相似三角形趋势：{trend_map.get(11)}")

    assert trend_map.get(10) == "declining", "勾股定理近期高错误率，应为 declining"
    assert trend_map.get(11) in ("improving", "stable"), "相似三角形近期表现好"

    print("  [OK] 趋势分析通过\n")


def test_full_pipeline():
    """测试完整流程：计算 → 趋势 → 生成响应"""

    print("=" * 60)
    print("测试：完整流程")
    print("=" * 60)

    now = datetime.now()
    knowledge_points = [
        {"id": 10, "name": "勾股定理", "parent_id": 1},
    ]

    user_answers = [
        {"kp_id": 10, "is_correct": 0, "created_at": now - timedelta(days=1)},
        {"kp_id": 10, "is_correct": 0, "created_at": now - timedelta(days=2)},
        {"kp_id": 10, "is_correct": 1, "created_at": now - timedelta(days=3)},
        {"kp_id": 10, "is_correct": 0, "created_at": now - timedelta(days=4)},
    ]

    conversation_messages = [
        {"kp_id": 10, "content": "勾股定理不懂"},
    ]

    # 计算
    results = calculate_weakness_scores(
        user_answers, conversation_messages, knowledge_points
    )
    results = analyze_trends(user_answers, results)

    assert len(results) == 1
    wp = results[0]
    print(f"  知识点：{wp['kp_name']}")
    print(f"  薄弱度：{wp['weakness_score']:.3f}")
    print(f"  错题数：{wp['error_count']}")
    print(f"  总答题：{wp['total_attempts']}")
    print(f"  错误率：{wp['error_rate']:.3f}")
    print(f"  趋势：{wp['trend']}")

    assert wp["weakness_score"] > 0, "薄弱度应大于 0"
    assert wp["error_count"] == 3
    assert wp["total_attempts"] == 4
    assert wp["error_rate"] == 0.75

    print("  [OK] 完整流程通过\n")


def test_incremental_filter():
    """测试增量更新逻辑：全量计算后仅返回指定知识点"""

    print("=" * 60)
    print("测试：增量更新（指定知识点过滤）")
    print("=" * 60)

    now = datetime.now()
    knowledge_points = [
        {"id": 10, "name": "勾股定理", "parent_id": 1},
        {"id": 11, "name": "相似三角形", "parent_id": 1},
        {"id": 20, "name": "一元二次方程", "parent_id": 2},
    ]

    user_answers = []
    user_answers.extend(make_answers(1, 10, 10, 20, now, start_offset_hours=200))
    user_answers.extend(make_answers(1, 11, 15, 5, now, start_offset_hours=200))
    user_answers.extend(make_answers(1, 20, 20, 10, now, start_offset_hours=200))

    conversation_messages = [
        {"kp_id": 10, "content": "勾股定理怎么证明？"},
    ]

    # 全量计算
    all_results = calculate_weakness_scores(
        user_answers, conversation_messages, knowledge_points
    )
    all_results = analyze_trends(user_answers, all_results)

    # 增量：仅保留指定知识点（模拟 run_incremental_update 的过滤逻辑）
    target_kp_ids = [10, 20]
    filtered = [wp for wp in all_results if wp["kp_id"] in target_kp_ids]

    assert len(filtered) == 2, f"应过滤出2个知识点，实际 {len(filtered)}"
    filtered_ids = {wp["kp_id"] for wp in filtered}
    assert filtered_ids == {10, 20}, f"过滤结果应为 {{10, 20}}，实际 {filtered_ids}"

    print(f"  全量计算: {len(all_results)} 个知识点")
    print(f"  增量过滤: {len(filtered)} 个知识点 ({[wp['kp_id'] for wp in filtered]})")
    for wp in filtered:
        print(f"    kp_id={wp['kp_id']}  {wp['kp_name']:12s}  score={wp['weakness_score']:.3f}  trend={wp['trend']}")

    print("  [OK] 增量更新逻辑通过\n")


if __name__ == "__main__":
    print()
    print("开始测试 M3 薄弱点计算引擎\n")

    try:
        ua, wp = test_weakness_calculation()
        test_trend_analysis(ua, wp)
        test_full_pipeline()
        test_incremental_filter()

        print("=" * 60)
        print("所有测试通过！")
        print("=" * 60)
    except AssertionError as e:
        print(f"  [FAIL] 测试失败：{e}")
        sys.exit(1)
    except Exception as e:
        print(f"  [ERROR] 异常：{type(e).__name__}: {e}")
        sys.exit(1)
