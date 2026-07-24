"""
AI 服务 — 调用 AI API 生成个性化学习建议

支持配置多种 AI 后端（默认用 DeepSeek 风格 API）：
    AI_ENDPOINT — API 地址
    AI_API_KEY — API 密钥
    AI_MODEL — 模型名

如果未配置或调用失败，自动降级为模板生成。
"""
import json
import os
from typing import Optional

from dotenv import load_dotenv

load_dotenv()

# ── AI 配置（通过环境变量或 .env 文件） ──
AI_ENDPOINT = os.getenv("AI_ENDPOINT", "https://api.deepseek.com/v1/chat/completions")
AI_API_KEY = os.getenv("AI_API_KEY", "")
AI_MODEL = os.getenv("AI_MODEL", "deepseek-chat")


def generate_assessment(
    weak_points: list[dict],
    overall_stats: Optional[dict] = None,
) -> str:
    """
    生成个性化学习评估建议

    优先调用 AI API，失败时降级为模板生成

    参数
    ----
    weak_points : list[dict]
        薄弱点列表（已按 weakness_score 降序）
    overall_stats : dict | None
        整体统计信息，如 {total_kps, avg_score, ...}

    返回
    ----
    str : 自然语言评估文本
    """
    # 尝试 AI 生成
    if AI_API_KEY:
        try:
            return _call_ai(weak_points, overall_stats)
        except Exception:
            pass  # 降级到模板

    # 降级：模板生成
    return _template_assessment(weak_points)


def _call_ai(
    weak_points: list[dict],
    overall_stats: Optional[dict] = None,
) -> str:
    """调用 AI API"""
    import urllib.request
    import urllib.error

    top = weak_points[:5]
    top_text = "\n".join(
        f"- {wp['kp_name']}: 薄弱度 {wp['weakness_score']:.2f}, "
        f"错误率 {wp['error_rate']:.0%}, "
        f"趋势 {wp['trend']}"
        for wp in top
    )

    prompt = f"""你是一位数学学习规划师。以下是一个学生的学习薄弱点数据，请生成一段简短（100字以内）的中文学习建议，包含：1)最需要优先巩固的知识点 2)学习建议。

薄弱点数据（按紧迫程度排序）：
{top_text}

请用中文给出清晰、鼓励性的建议。"""

    payload = json.dumps({
        "model": AI_MODEL,
        "messages": [{"role": "user", "content": prompt}],
        "max_tokens": 300,
        "temperature": 0.7,
    }).encode("utf-8")

    req = urllib.request.Request(
        AI_ENDPOINT,
        data=payload,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {AI_API_KEY}",
        },
        method="POST",
    )

    with urllib.request.urlopen(req, timeout=15) as resp:
        result = json.loads(resp.read().decode("utf-8"))
        content = result["choices"][0]["message"]["content"].strip()
        return content


def _template_assessment(weak_points: list[dict]) -> str:
    """模板降级 — 生成简明评估"""
    if not weak_points:
        return "暂无薄弱点数据。"

    high_risk = [wp for wp in weak_points if wp["weakness_score"] >= 0.7]
    medium_risk = [
        wp for wp in weak_points if 0.4 <= wp["weakness_score"] < 0.7
    ]
    declining = [wp for wp in weak_points if wp["trend"] == "declining"]

    parts = []
    if high_risk:
        names = "、".join(wp["kp_name"] for wp in high_risk[:3])
        parts.append(f"{names}等知识点薄弱程度较高，建议优先巩固。")
    if medium_risk:
        names = "、".join(wp["kp_name"] for wp in medium_risk[:3])
        parts.append(f"{names}等知识点存在薄弱风险，建议加强练习。")
    if declining:
        names = "、".join(wp["kp_name"] for wp in declining[:3])
        parts.append(f"注意：{names}近期错误率上升，需重点复习。")

    return " ".join(parts) if parts else "当前薄弱点处于可控范围，建议保持定期练习。"
