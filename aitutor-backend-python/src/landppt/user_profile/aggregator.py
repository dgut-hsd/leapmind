"""多源学习事件汇总服务。

v2 升级：掌握度计算集成贝叶斯 Beta-Binomial 后验和 Wilson 置信区间，
使评估对小样本更鲁棒，并提供可解释的置信度度量。
"""

from collections.abc import Iterable

from .confidence import bayesianPosteriorMean, determineTrend, wilsonConfidence
from .models import (
  AggregationResult,
  EventSource,
  KnowledgeEvidence,
  KnowledgeMastery,
  LearningEvent,
  MasteryTrend,
  ProfileStatus,
)

MINIMUM_ANSWER_COUNT = 10
MASTERY_THRESHOLD = 0.8
MASTERY_ALGORITHM_VERSION = "mastery-bayesian-v2"


def aggregateUserEvents(
  events: Iterable[LearningEvent],
  userId: int,
) -> AggregationResult:
  """去重并按知识点汇总指定用户的学习事件。"""
  if userId <= 0:
    raise ValueError("目标用户 ID 必须为正整数")

  seenEventIds = set()
  duplicateEventCount = 0
  unmappedEventCount = 0
  sourceRecordCounts = {source: 0 for source in EventSource}
  evidenceByKnowledgePoint = {}

  for event in events:
    if event.userId != userId:
      raise ValueError("事件用户与目标用户不一致")
    if event.eventId in seenEventIds:
      duplicateEventCount += 1
      continue

    seenEventIds.add(event.eventId)
    sourceRecordCounts[event.source] += 1

    if event.knowledgePointKey is None:
      unmappedEventCount += 1
      continue

    evidence = evidenceByKnowledgePoint.setdefault(
      event.knowledgePointKey,
      KnowledgeEvidence(knowledgePointKey=event.knowledgePointKey),
    )
    _updateEvidence(evidence, event)

  return AggregationResult(
    userId=userId,
    totalEventCount=len(seenEventIds),
    duplicateEventCount=duplicateEventCount,
    unmappedEventCount=unmappedEventCount,
    sourceRecordCounts=sourceRecordCounts,
    evidenceByKnowledgePoint=evidenceByKnowledgePoint,
  )


def _updateEvidence(
  evidence: KnowledgeEvidence,
  event: LearningEvent,
):
  """将单条事件累加到知识点证据中。"""
  if event.source == EventSource.USER_ANSWERS:
    isCorrect = event.data.get("isCorrect")
    if not isinstance(isCorrect, bool):
      raise ValueError("答题事件的 isCorrect 必须为布尔值")
    evidence.answerCount += 1
    if isCorrect:
      evidence.correctAnswerCount += 1
  elif event.source == EventSource.WRONG_QUESTION_BOOK:
    evidence.wrongQuestionCount += 1
  elif event.source == EventSource.CONVERSATION_MESSAGES:
    evidence.conversationMessageCount += 1
  elif event.source == EventSource.USER_WEAK_POINTS:
    evidence.weakPointCount += 1

  if evidence.lastEventAt is None or event.occurredAt > evidence.lastEventAt:
    evidence.lastEventAt = event.occurredAt


def calculateMastery(
  evidence: KnowledgeEvidence,
  previousBayesianScore: float | None = None,
) -> KnowledgeMastery:
  """根据答题样本量和正确率计算知识点掌握状态。

  v2 升级：
  - 集成贝叶斯 Beta(2,2) 先验的后验均值，对小样本收缩更鲁棒
  - 计算 Wilson 得分区间置信度，量化评估可靠性
  - 对比历史后验均值判定掌握趋势（IMPROVING / STABLE / DECLINING）

  Args:
    evidence: 知识点证据汇总
    previousBayesianScore: 上一版本画像的贝叶斯后验均值（首次计算时为 None）

  Returns:
    包含贝叶斯评分、置信度和趋势的 KnowledgeMastery
  """
  if evidence.answerCount < 0 or evidence.correctAnswerCount < 0:
    raise ValueError("答题统计不能为负数")
  if evidence.correctAnswerCount > evidence.answerCount:
    raise ValueError("正确答题数不能超过总答题数")

  correctRate = (
    evidence.correctAnswerCount / evidence.answerCount
    if evidence.answerCount
    else 0.0
  )

  # v2：贝叶斯后验均值（Beta(2,2) 先验）
  bayesianScore = bayesianPosteriorMean(
    evidence.correctAnswerCount,
    evidence.answerCount,
  )

  # v2：Wilson 置信度
  confidence = wilsonConfidence(
    evidence.correctAnswerCount,
    evidence.answerCount,
  )

  # v2：趋势判定
  trend = determineTrend(bayesianScore, previousBayesianScore)

  if evidence.answerCount <= MINIMUM_ANSWER_COUNT:
    status = ProfileStatus.INSUFFICIENT_DATA
  elif correctRate > MASTERY_THRESHOLD:
    status = ProfileStatus.MASTERED
  else:
    status = ProfileStatus.LEARNING

  return KnowledgeMastery(
    knowledgePointKey=evidence.knowledgePointKey,
    status=status,
    sampleCount=evidence.answerCount,
    correctRate=correctRate,
    minimumAnswerCount=MINIMUM_ANSWER_COUNT,
    masteryThreshold=MASTERY_THRESHOLD,
    algorithmVersion=MASTERY_ALGORITHM_VERSION,
    bayesianScore=round(bayesianScore, 4),
    confidence=confidence,
    trend=MasteryTrend(trend) if trend else None,
  )
