"""对话困惑表达规则提取。

v2 升级：
- 增加否定模式排除（如 "不为什么" 不应触发 "为什么" 模式）
- 增加置信度评分，基于命中模式数量综合计算
- 扩展困惑表达模式覆盖面
"""

import re
from dataclasses import dataclass

CONFUSION_ALGORITHM_VERSION = "confusion-pattern-v2"

# 困惑表达正向模式（标签, 正则）
CONFUSION_PATTERNS = (
  ("为什么", re.compile(r"为什么")),
  ("不懂", re.compile(r"不懂|不明白|没听懂|看不懂|不理解")),
  ("什么意思", re.compile(r"什么意思|什么含义")),
  ("不会", re.compile(r"不会|不知道怎么|怎么理解")),
  ("混淆", re.compile(r"搞混|容易混淆|分不清|区别在哪")),
  ("再讲", re.compile(r"再讲一遍|再说一次|重新讲|没讲清楚")),
)

# 否定前缀模式：当困惑关键词前面紧跟否定词时，排除该命中
# 例如 "不为什么" 不应触发 "为什么"，"不是不懂" 不应触发 "不懂"
NEGATION_PREFIX = re.compile(r"(?:不|没|并非|并不是|并不是说)$")

# 每个命中模式的置信度贡献
PATTERN_CONFIDENCE_WEIGHT = 0.35
# 否定排除的惩罚
NEGATION_PENALTY = 0.15
# 置信度上限
MAX_CONFIDENCE = 1.0


@dataclass(frozen=True)
class ConfusionExtraction:
  """单条对话的可解释困惑提取结果。

  v2 新增 confidence 字段：基于命中模式数量和否定排除综合计算。
  """

  isConfused: bool
  matchedPatterns: tuple[str, ...]
  normalizedText: str
  confidence: float | None = None


def extractConfusionSignals(text: str) -> ConfusionExtraction:
  """匹配中文显式困惑表达，并返回命中模式和置信度。

  v2 升级：
  - 检查命中位置前方是否有否定词，排除否定表达
  - 基于有效命中数量计算置信度评分
  - 扩展模式覆盖面（混淆、再讲等）

  Args:
    text: 对话文本

  Returns:
    包含命中模式、置信度的困惑提取结果

  Raises:
    ValueError: 当文本为空或非字符串时
  """
  if not isinstance(text, str) or not text.strip():
    raise ValueError("对话文本不能为空")

  normalizedText = re.sub(r"\s+", " ", text.strip())
  rawMatches = []
  negatedCount = 0

  for label, pattern in CONFUSION_PATTERNS:
    for match in pattern.finditer(normalizedText):
      # 检查匹配位置前方是否有否定前缀
      prefix = normalizedText[: match.start()]
      if NEGATION_PREFIX.search(prefix):
        negatedCount += 1
        continue
      rawMatches.append((match.start(), label))

  rawMatches.sort(key=lambda item: item[0])
  matchedPatterns = tuple(label for _, label in rawMatches)

  # v2 置信度计算
  if matchedPatterns:
    baseConfidence = min(
      MAX_CONFIDENCE,
      len(matchedPatterns) * PATTERN_CONFIDENCE_WEIGHT,
    )
    # 否定排除降低置信度
    confidence = max(0.0, baseConfidence - negatedCount * NEGATION_PENALTY)
    confidence = round(confidence, 4)
  else:
    confidence = 0.0

  return ConfusionExtraction(
    isConfused=bool(matchedPatterns),
    matchedPatterns=matchedPatterns,
    normalizedText=normalizedText,
    confidence=confidence,
  )
