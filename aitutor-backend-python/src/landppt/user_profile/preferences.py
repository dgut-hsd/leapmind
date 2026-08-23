"""四维学习偏好向量计算。

v2 升级：
- 增加 Shannon 熵度量偏好强度（低熵=强偏好，高熵=均衡）
- 基于熵值计算置信度，偏好越集中置信度越高
- 版本升级至 learning-preference-entropy-v2
"""

import math
from collections.abc import Iterable

from .models import (
  DataQualityWarning,
  LearningEvent,
  LearningMode,
  LearningPreferenceStatus,
  LearningPreferenceVector,
)

MINIMUM_PREFERENCE_EVIDENCE_COUNT = 10
LEARNING_PREFERENCE_ALGORITHM_VERSION = "learning-preference-entropy-v2"

# 四维模式的最大 Shannon 熵：log2(4) = 2.0
MAX_ENTROPY = math.log2(len(LearningMode))


def calculateLearningPreference(
  events: Iterable[LearningEvent],
) -> LearningPreferenceVector:
  """按唯一学习行为事件生成归一化四维偏好向量。

  v2 升级：
  - 计算 Shannon 熵 H = -Σ p_i * log2(p_i)
  - 归一化熵 H_norm = H / log2(N)，范围 [0, 1]
  - 偏好强度 = 1 - H_norm（0=完全均衡，1=绝对偏好）
  - 置信度 = 偏好强度 * 数据充分性因子

  Args:
    events: 学习事件迭代器

  Returns:
    包含熵度量置信度和偏好强度的 LearningPreferenceVector
  """
  counts = {mode: 0 for mode in LearningMode}
  seenEventIds = set()
  ignoredEvidenceCount = 0
  warnings = []

  for event in events:
    if event.eventId in seenEventIds:
      continue
    seenEventIds.add(event.eventId)

    rawMode = event.data.get("learningMode")
    if rawMode is None:
      ignoredEvidenceCount += 1
      continue

    try:
      learningMode = LearningMode(rawMode)
    except (TypeError, ValueError):
      ignoredEvidenceCount += 1
      warnings.append(
        DataQualityWarning(
          code="INVALID_LEARNING_MODE",
          message="学习模式取值不受支持，已跳过偏好计算",
          eventId=event.eventId,
        ),
      )
      continue

    counts[learningMode] += 1

  evidenceCount = sum(counts.values())
  scores = _normalizeScores(counts, evidenceCount)
  status = (
    LearningPreferenceStatus.READY
    if evidenceCount >= MINIMUM_PREFERENCE_EVIDENCE_COUNT
    else LearningPreferenceStatus.INSUFFICIENT_DATA
  )
  dominantDimensions = _findDominantDimensions(scores, status)

  # v2：Shannon 熵与偏好强度
  preferenceStrength = _calculatePreferenceStrength(scores)
  confidence = _calculateConfidence(
    preferenceStrength,
    evidenceCount,
    MINIMUM_PREFERENCE_EVIDENCE_COUNT,
  )

  return LearningPreferenceVector(
    status=status,
    scores=scores,
    evidenceCount=evidenceCount,
    ignoredEvidenceCount=ignoredEvidenceCount,
    dominantDimensions=dominantDimensions,
    minimumEvidenceCount=MINIMUM_PREFERENCE_EVIDENCE_COUNT,
    algorithmVersion=LEARNING_PREFERENCE_ALGORITHM_VERSION,
    dataQualityWarnings=tuple(warnings),
    confidence=confidence,
    preferenceStrength=preferenceStrength,
  )


def _normalizeScores(
  counts: dict[LearningMode, int],
  evidenceCount: int,
) -> dict[LearningMode, float]:
  """将四维计数归一化为和为一的向量。"""
  if evidenceCount == 0:
    return {mode: 0.0 for mode in LearningMode}
  return {
    mode: counts[mode] / evidenceCount
    for mode in LearningMode
  }


def _findDominantDimensions(
  scores: dict[LearningMode, float],
  status: LearningPreferenceStatus,
) -> tuple[LearningMode, ...]:
  """数据充足时返回所有并列最高的维度。"""
  if status != LearningPreferenceStatus.READY:
    return ()

  maximumScore = max(scores.values())
  return tuple(
    mode
    for mode in LearningMode
    if scores[mode] == maximumScore
  )


def _calculatePreferenceStrength(
  scores: dict[LearningMode, float],
) -> float:
  """计算偏好强度（基于归一化 Shannon 熵）。

  Shannon 熵 H = -Σ p_i * log2(p_i)，最大值为 log2(N)。
  归一化熵 H_norm = H / log2(N)，范围 [0, 1]。
  偏好强度 = 1 - H_norm：
  - 0.0 = 完全均衡（各模式等概率）
  - 1.0 = 绝对偏好（所有证据集中在一个模式）

  Args:
    scores: 归一化偏好分数

  Returns:
    偏好强度，范围 [0, 1]
  """
  nonzeroScores = [s for s in scores.values() if s > 0]
  if not nonzeroScores:
    return 0.0

  entropy = -sum(s * math.log2(s) for s in nonzeroScores)
  normalizedEntropy = entropy / MAX_ENTROPY if MAX_ENTROPY > 0 else 0.0
  strength = 1.0 - normalizedEntropy

  return round(strength, 4)


def _calculateConfidence(
  preferenceStrength: float,
  evidenceCount: int,
  minimumEvidenceCount: int,
) -> float:
  """计算偏好置信度。

  置信度 = 偏好强度 × 数据充分性因子
  数据充分性因子 = min(1.0, evidenceCount / (minimumEvidenceCount * 2))
  当证据数达到最低要求的 2 倍时，数据充分性因子为 1.0。

  Args:
    preferenceStrength: 偏好强度 [0, 1]
    evidenceCount: 实际证据数
    minimumEvidenceCount: 最低证据要求

  Returns:
    置信度评分，范围 [0, 1]
  """
  if evidenceCount <= 0:
    return 0.0

  sufficiencyFactor = min(
    1.0,
    evidenceCount / (minimumEvidenceCount * 2),
  )
  confidence = preferenceStrength * sufficiencyFactor

  return round(confidence, 4)
