"""
薄弱度计算引擎 — 核心算法

计算公式：
    weaknessScore = w1 × errorRate + w2 × (1 - recentCorrectRate) + w3 × confusionFrequency

权重默认值：
    w1 = 0.4, w2 = 0.4, w3 = 0.2
"""
from datetime import datetime
from typing import Optional

import numpy as np
import pandas as pd

# ── 默认权重（可通过传参调整） ──
DEFAULT_WEIGHTS = {"w1": 0.4, "w2": 0.4, "w3": 0.2}


def calculate_weakness_scores(
    user_answers: list[dict],
    conversation_messages: list[dict],
    knowledge_points: list[dict],
    weights: Optional[dict[str, float]] = None,
) -> list[dict]:
    """
    计算用户各知识点的薄弱度

    公式：weaknessScore = w1×errorRate + w2×(1-recentCorrectRate) + w3×confusionFrequency

    参数
    ----
    user_answers : list[dict]
        用户答题记录（M1），每项包含 kp_id, is_correct, created_at
    conversation_messages : list[dict]
        用户提问记录（M7），每项包含 kp_id
    knowledge_points : list[dict]
        知识点列表（基础数据），每项包含 id, name, parent_id
    weights : dict | None
        权重，默认 {w1: 0.4, w2: 0.4, w3: 0.2}

    返回
    ----
    list[dict] : 按 weakness_score 降序排列的薄弱点列表
    """
    w = weights or DEFAULT_WEIGHTS.copy()

    # ── 转为 DataFrame 方便计算 ──
    df_answers = pd.DataFrame(user_answers)
    if df_answers.empty:
        return []

    # ── 1. 按知识点统计 ──
    grouped = df_answers.groupby("kp_id")

    # 总答题数 & 错题数
    stats = grouped.agg(
        total_attempts=("is_correct", "count"),
        error_count=("is_correct", lambda x: (x == 0).sum()),
    )
    stats["error_rate"] = (stats["error_count"] / stats["total_attempts"]).round(3)

    # ── 2. 最近 10 次正确率 ──
    # 按用户答题时间降序排列后取每个 kp 的最新 10 条
    df_answers_sorted = df_answers.sort_values("created_at", ascending=False)

    def recent_10_correct_rate(group):
        recent = group.head(10)
        return recent["is_correct"].mean() if len(recent) > 0 else 0.0

    recent_correct = df_answers_sorted.groupby("kp_id").apply(recent_10_correct_rate)
    recent_correct.name = "recent_correct_rate"

    stats = stats.join(recent_correct)

    # ── 3. 提问困惑频率 ──
    df_msgs = pd.DataFrame(conversation_messages)
    if not df_msgs.empty:
        confusion_counts = df_msgs.groupby("kp_id").size()
        confusion_counts.name = "confusion_count"
        stats = stats.join(confusion_counts, how="left")
    else:
        stats["confusion_count"] = 0

    stats["confusion_count"] = stats["confusion_count"].fillna(0).astype(int)

    # 困惑频率 = count / (max_count + 1)，归一化到 0-1 之间
    max_confusion = stats["confusion_count"].max()
    stats["confusion_frequency"] = (
        stats["confusion_count"] / (max_confusion + 1)
    ).round(3)

    # ── 4. 计算综合薄弱度 ──
    # 公式：weaknessScore = w1×errorRate + w2×(1-recentCorrectRate) + w3×confusionFrequency
    stats["weakness_score"] = (
        w["w1"] * stats["error_rate"]
        + w["w2"] * (1 - stats["recent_correct_rate"])
        + w["w3"] * stats["confusion_frequency"]
    ).round(3)

    # 确保不超过 1.0
    stats["weakness_score"] = stats["weakness_score"].clip(upper=1.0)

    # ── 5. 最近错题时间 ──
    error_answers = df_answers[df_answers["is_correct"] == 0]
    if not error_answers.empty:
        last_error = (
            error_answers.groupby("kp_id")["created_at"].max().to_dict()
        )
    else:
        last_error = {}

    stats["last_error_at"] = stats.index.map(
        lambda kp: last_error.get(kp, None)
    )

    # ── 6. 构造返回结果 ──
    kp_map = {kp["id"]: kp["name"] for kp in knowledge_points}
    kp_parent_map = {kp["id"]: kp["parent_id"] for kp in knowledge_points}

    results = []
    for kp_id, row in stats.iterrows():
        results.append({
            "kp_id": int(kp_id),
            "kp_name": kp_map.get(int(kp_id), f"未知知识点({kp_id})"),
            "weakness_score": float(row["weakness_score"]),
            "error_count": int(row["error_count"]),
            "total_attempts": int(row["total_attempts"]),
            "error_rate": float(row["error_rate"]),
            "recent_correct_rate": float(row["recent_correct_rate"]),
            "confusion_count": int(row["confusion_count"]),
            "trend": "stable",  # 趋势由 trend_analysis 模块填充
            "last_error_at": row["last_error_at"],
            "parent_id": kp_parent_map.get(int(kp_id)),
        })

    # 按薄弱度降序排列
    results.sort(key=lambda x: x["weakness_score"], reverse=True)

    return results
