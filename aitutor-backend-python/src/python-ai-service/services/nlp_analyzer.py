import re
import jieba
from snownlp import SnowNLP
from typing import List, Tuple
from services.expression_mapper import EMOTION_RULES, GESTURE_RULES


class NLPAnalyzer:
    """文本情感分析 → 表情序列 + 手势序列"""

    def analyze(self, text: str, scene_type: str = "lecturing",
                base_emotion: str = None) -> dict:
        """
        输入：讲课/对话文本
        输出：dict { expressions, gestures, sentiment_score, dominant_emotion, keywords }
        """
        words = list(jieba.cut(text))
        sentiment = SnowNLP(text).sentiments  # 0.0~1.0
        keywords = self._extract_keywords(words)

        expressions = self._match_expressions(text, sentiment, base_emotion)
        gestures = self._match_gestures(text)

        # 确定主导情绪
        dominant = base_emotion or self._dominant_emotion(sentiment, expressions)

        return {
            "expressions": expressions,
            "gestures": gestures,
            "sentiment_score": round(sentiment, 3),
            "dominant_emotion": dominant,
            "keywords": keywords,
        }

    # ─── 表情匹配 ───

    def _match_expressions(self, text: str, sentiment: float,
                           base_emotion: str = None) -> list:
        """关键词匹配 → 带时间轴的表情序列"""
        segments = []

        # 用户手动指定情绪时，全程使用该情绪
        if base_emotion and base_emotion != "neutral":
            duration = self._estimate_duration(text)
            return [{"expression": base_emotion, "start_ms": 0,
                     "end_ms": duration, "intensity": 0.8}]

        # 规则匹配
        for keywords, emotion, _ in EMOTION_RULES:
            for kw in keywords:
                idx = text.find(kw)
                if idx != -1:
                    start_ms = self._char_to_ms(idx) - 300
                    segments.append({
                        "expression": emotion,
                        "start_ms": max(0, start_ms),
                        "end_ms": start_ms + 2000,
                        "intensity": 0.7,
                    })
                    break  # 每类关键词只触发一次

        # 无匹配 → 按情感得分给默认表情
        if not segments:
            duration = self._estimate_duration(text)
            if sentiment > 0.7:
                emo = "encouraging" if "答对" in text or "正确" in text else "happy"
            elif sentiment < 0.3:
                emo = "serious"
            else:
                emo = "neutral"
            segments.append({"expression": emo, "start_ms": 0,
                             "end_ms": duration, "intensity": 0.5})

        return self._merge_adjacent(segments)

    # ─── 手势匹配 ───

    def _match_gestures(self, text: str) -> list:
        """正则模式匹配 → 手势序列"""
        gestures = []
        for pattern, gesture in GESTURE_RULES:
            for m in pattern.finditer(text):
                start_ms = self._char_to_ms(m.start())
                gestures.append({
                    "gesture": gesture,
                    "start_ms": start_ms,
                    "end_ms": start_ms + 1500,
                })

        # 去重 + 限制手势频率（每 2.5 秒最多 1 个）
        return self._filter_gestures(gestures)

    # ─── 辅助方法 ───

    def _estimate_duration(self, text: str) -> int:
        """估算朗读时长：中文约 4 字/秒"""
        return max(int(len(text) / 4 * 1000), 1000)

    def _char_to_ms(self, char_index: int) -> int:
        """字符位置 → 毫秒偏移"""
        return int(char_index / 4 * 1000)

    def _dominant_emotion(self, sentiment: float,
                          expressions: list) -> str:
        """根据情感得分推断主导情绪"""
        if sentiment > 0.8:
            return "encouraging"
        elif sentiment > 0.6:
            return "happy"
        elif sentiment < 0.2:
            return "serious"
        elif sentiment < 0.4:
            return "thinking"
        return "neutral"

    def _merge_adjacent(self, segments: list) -> list:
        """合并 500ms 内相邻的相同表情"""
        if not segments:
            return []
        segments.sort(key=lambda x: x["start_ms"])
        merged = [segments[0]]
        for s in segments[1:]:
            prev = merged[-1]
            if s["expression"] == prev["expression"] \
               and s["start_ms"] - prev["end_ms"] < 500:
                prev["end_ms"] = s["end_ms"]
                prev["intensity"] = max(prev["intensity"], s["intensity"])
            else:
                merged.append(s)
        return merged

    def _filter_gestures(self, gestures: list):
        """去重：合并同类重叠手势 + 限制密度"""
        if not gestures:
            return []
        gestures.sort(key=lambda x: x["start_ms"])
        filtered = []
        for g in gestures:
            if filtered and filtered[-1]["gesture"] == g["gesture"] \
               and g["start_ms"] < filtered[-1]["end_ms"]:
                filtered[-1]["end_ms"] = max(filtered[-1]["end_ms"], g["end_ms"])
                continue
            # 距离上一个手势 < 2.5s 则跳过
            if filtered and g["start_ms"] - filtered[-1]["start_ms"] < 2500:
                continue
            filtered.append(g)
        return filtered

    def _extract_keywords(self, words: list) -> list:
        """提取教学关键词（去停用词、去重、取前5）"""
        stop = {"的", "了", "是", "在", "我", "你", "他", "她", "它",
                "和", "与", "或", "但", "而", "就", "也", "都", "很",
                "这", "那", "吗", "呢", "吧", "啊", "哦", "嗯", "哈",
                "一个", "这个", "那个", "什么", "怎么", "为什么"}
        seen = set()
        result = []
        for w in words:
            if len(w) >= 2 and w not in stop and w not in seen:
                seen.add(w)
                result.append(w)
                if len(result) >= 5:
                    break
        return result
# 全局单例
analyzer = NLPAnalyzer()