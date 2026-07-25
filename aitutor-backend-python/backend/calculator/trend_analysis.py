"""
趋势分析 — 比较近7天 vs 前7天的错误率变化

规则：
    - 近7天错误率 > 前7天错误率 + 0.05 → "declining"（恶化）
    - 近7天错误率 < 前7天错误率 - 0.05 → "improving"（进步）
    - 其余 → "stable"（稳定）
"""
from datetime import datetime, timedelta

import pandas as pd


def analyze_trends(
    user_answers: list[dict],
    weak_points: list[dict],
    now: datetime = None,
) -> list[dict]:
    """
    为薄弱点列表补充趋势信息

    参数
    ----
    user_answers : list[dict]
        用户答题记录，每项含 kp_id, is_correct, created_at
    weak_points : list[dict]
        weakness_score.py 返回的薄弱点列表
    now : datetime | None
        当前时间，默认 datetime.now()

    返回
    ----
    list[dict] : 补充了 trend 字段的薄弱点列表
    """
    if now is None:
        now = datetime.now()

    df_answers = pd.DataFrame(user_answers)
    if df_answers.empty:
        return weak_points

    df_answers["created_at"] = pd.to_datetime(df_answers["created_at"])

    # 定义时间窗口
    period2_start = now - timedelta(days=14)  # 前7天的起始
    period1_start = now - timedelta(days=7)   # 近7天的起始

    # 为每个知识点计算趋势
    kp_trends = {}

    for kp_id in {wp["kp_id"] for wp in weak_points}:
        kp_answers = df_answers[df_answers["kp_id"] == kp_id]

        # 近7天
        recent = kp_answers[kp_answers["created_at"] >= period1_start]
        # 前7天（第8~14天）
        previous = kp_answers[
            (kp_answers["created_at"] >= period2_start)
            & (kp_answers["created_at"] < period1_start)
        ]

        recent_error_rate = (
            (recent["is_correct"] == 0).mean() if len(recent) > 0 else None
        )
        previous_error_rate = (
            (previous["is_correct"] == 0).mean() if len(previous) > 0 else None
        )

        # 判断趋势
        if recent_error_rate is None or previous_error_rate is None:
            trend = "stable"
        elif recent_error_rate > previous_error_rate + 0.05:
            trend = "declining"
        elif recent_error_rate < previous_error_rate - 0.05:
            trend = "improving"
        else:
            trend = "stable"

        kp_trends[kp_id] = trend

    # 写入结果
    for wp in weak_points:
        wp["trend"] = kp_trends.get(wp["kp_id"], "stable")

    return weak_points
