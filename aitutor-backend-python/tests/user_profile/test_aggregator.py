"""用户画像多源数据汇总测试。"""

import unittest
from datetime import datetime, timezone

from landppt.user_profile.aggregator import (
  aggregateUserEvents,
  calculateMastery,
)
from landppt.user_profile.models import (
  EventSource,
  EventType,
  KnowledgeEvidence,
  LearningEvent,
  MasteryTrend,
  ProfileStatus,
)


class LearningEventValidationTest(unittest.TestCase):
  """验证统一学习事件的基础约束。"""

  def testAcceptsValidLearningEvent(self):
    """合法事件应完整保留归一化字段。"""
    event = LearningEvent(
      eventId="event-1",
      userId=1001,
      source=EventSource.USER_ANSWERS,
      eventType=EventType.ANSWER_SUBMITTED,
      occurredAt=datetime(2026, 7, 23, 9, 0, tzinfo=timezone.utc),
      knowledgePointKey="导数定义",
      data={"isCorrect": True},
    )

    self.assertEqual("event-1", event.eventId)
    self.assertEqual(1001, event.userId)
    self.assertEqual("导数定义", event.knowledgePointKey)

  def testRejectsBlankKnowledgePointKey(self):
    """空白知识点键应被拒绝。"""
    with self.assertRaisesRegex(ValueError, "知识点键不能为空"):
      LearningEvent(
        eventId="event-1",
        userId=1001,
        source=EventSource.USER_ANSWERS,
        eventType=EventType.ANSWER_SUBMITTED,
        occurredAt=datetime(2026, 7, 23, 9, 0, tzinfo=timezone.utc),
        knowledgePointKey=" ",
      )

  def testRejectsBlankEventId(self):
    """空事件 ID 应被拒绝。"""
    with self.assertRaisesRegex(ValueError, "事件 ID 不能为空"):
      LearningEvent(
        eventId=" ",
        userId=1001,
        source=EventSource.USER_ANSWERS,
        eventType=EventType.ANSWER_SUBMITTED,
        occurredAt=datetime(2026, 7, 23, 9, 0, tzinfo=timezone.utc),
      )

  def testRejectsNonPositiveUserId(self):
    """非正数用户 ID 应被拒绝。"""
    with self.assertRaisesRegex(ValueError, "用户 ID 必须为正整数"):
      LearningEvent(
        eventId="event-1",
        userId=0,
        source=EventSource.USER_ANSWERS,
        eventType=EventType.ANSWER_SUBMITTED,
        occurredAt=datetime(2026, 7, 23, 9, 0, tzinfo=timezone.utc),
      )

  def testRejectsNaiveOccurredAt(self):
    """无时区的事件时间应被拒绝，避免跨服务时间歧义。"""
    with self.assertRaisesRegex(ValueError, "事件发生时间必须包含时区"):
      LearningEvent(
        eventId="event-1",
        userId=1001,
        source=EventSource.USER_ANSWERS,
        eventType=EventType.ANSWER_SUBMITTED,
        occurredAt=datetime(2026, 7, 23, 9, 0),
      )


class AggregateUserEventsTest(unittest.TestCase):
  """验证多来源事件去重和按知识点汇总。"""

  def makeEvent(
    self,
    eventId,
    source,
    eventType,
    knowledgePointKey="导数定义",
    userId=1001,
    data=None,
  ):
    """构造测试事件。"""
    return LearningEvent(
      eventId=eventId,
      userId=userId,
      source=source,
      eventType=eventType,
      occurredAt=datetime(2026, 7, 23, 9, 0, tzinfo=timezone.utc),
      knowledgePointKey=knowledgePointKey,
      data=data or {},
    )

  def testAggregatesFourSourcesAndDeduplicatesEvents(self):
    """四类来源应独立计数，重复事件不得重复影响汇总。"""
    answer = self.makeEvent(
      "answer-1",
      EventSource.USER_ANSWERS,
      EventType.ANSWER_SUBMITTED,
      data={"isCorrect": True},
    )
    events = [
      answer,
      answer,
      self.makeEvent(
        "wrong-1",
        EventSource.WRONG_QUESTION_BOOK,
        EventType.WRONG_QUESTION_ADDED,
      ),
      self.makeEvent(
        "message-1",
        EventSource.CONVERSATION_MESSAGES,
        EventType.CONVERSATION_MESSAGE,
      ),
      self.makeEvent(
        "weak-1",
        EventSource.USER_WEAK_POINTS,
        EventType.WEAK_POINT_UPDATED,
      ),
      self.makeEvent(
        "message-unmapped",
        EventSource.CONVERSATION_MESSAGES,
        EventType.CONVERSATION_MESSAGE,
        knowledgePointKey=None,
      ),
    ]

    result = aggregateUserEvents(events, userId=1001)

    self.assertEqual(5, result.totalEventCount)
    self.assertEqual(1, result.duplicateEventCount)
    self.assertEqual(1, result.unmappedEventCount)
    self.assertEqual(1, result.sourceRecordCounts[EventSource.USER_ANSWERS])
    self.assertEqual(
      2,
      result.sourceRecordCounts[EventSource.CONVERSATION_MESSAGES],
    )

    evidence = result.evidenceByKnowledgePoint["导数定义"]
    self.assertEqual(1, evidence.answerCount)
    self.assertEqual(1, evidence.correctAnswerCount)
    self.assertEqual(1, evidence.wrongQuestionCount)
    self.assertEqual(1, evidence.conversationMessageCount)
    self.assertEqual(1, evidence.weakPointCount)

  def testRejectsEventsBelongingToAnotherUser(self):
    """目标用户汇总中混入其他用户事件时应立即失败。"""
    events = [
      self.makeEvent(
        "answer-1",
        EventSource.USER_ANSWERS,
        EventType.ANSWER_SUBMITTED,
        userId=2002,
      ),
    ]

    with self.assertRaisesRegex(ValueError, "事件用户与目标用户不一致"):
      aggregateUserEvents(events, userId=1001)


class CalculateMasteryTest(unittest.TestCase):
  """验证擅长知识点判定的阈值和可解释结果。"""

  def testReturnsInsufficientDataForExactlyTenAnswers(self):
    """答题数必须大于 10，等于 10 时仍应标记数据不足。"""
    evidence = KnowledgeEvidence(
      knowledgePointKey="导数定义",
      answerCount=10,
      correctAnswerCount=10,
    )

    mastery = calculateMastery(evidence)

    self.assertEqual(ProfileStatus.INSUFFICIENT_DATA, mastery.status)
    self.assertEqual(10, mastery.sampleCount)
    self.assertEqual(1.0, mastery.correctRate)
    self.assertIsNotNone(mastery.bayesianScore)
    self.assertIsNotNone(mastery.confidence)
    self.assertIsNone(mastery.trend)

  def testMarksMasteredAboveBothThresholds(self):
    """11 次答题且正确率超过 80% 时应判定掌握。"""
    evidence = KnowledgeEvidence(
      knowledgePointKey="导数定义",
      answerCount=11,
      correctAnswerCount=9,
    )

    mastery = calculateMastery(evidence)

    self.assertEqual(ProfileStatus.MASTERED, mastery.status)
    self.assertAlmostEqual(9 / 11, mastery.correctRate)
    self.assertEqual(10, mastery.minimumAnswerCount)
    self.assertEqual(0.8, mastery.masteryThreshold)
    self.assertTrue(mastery.algorithmVersion)
    self.assertAlmostEqual(0.7333, mastery.bayesianScore, places=4)
    self.assertIsNotNone(mastery.confidence)
    self.assertIsNone(mastery.trend)

  def testKeepsLearningWhenCorrectRateDoesNotExceedThreshold(self):
    """样本充足但正确率未超过 80% 时应保持学习中。"""
    evidence = KnowledgeEvidence(
      knowledgePointKey="导数定义",
      answerCount=11,
      correctAnswerCount=8,
    )

    mastery = calculateMastery(evidence)

    self.assertEqual(ProfileStatus.LEARNING, mastery.status)
    self.assertAlmostEqual(8 / 11, mastery.correctRate)


class BayesianMasteryTest(unittest.TestCase):
  """验证贝叶斯后验评分、Wilson 置信度和趋势判定。"""

  def testBayesianScoreShrinksTowardPriorForSmallSamples(self):
    """小样本时贝叶斯评分应向先验中心 0.5 收缩。

    1 对 / 1 总的裸正确率为 1.0，但贝叶斯后验 = (2+1)/(2+2+1) = 0.6。
    """
    evidence = KnowledgeEvidence(
      knowledgePointKey="导数定义",
      answerCount=1,
      correctAnswerCount=1,
    )

    mastery = calculateMastery(evidence)

    self.assertEqual(1.0, mastery.correctRate)
    self.assertAlmostEqual(0.6, mastery.bayesianScore, places=4)

  def testWilsonConfidenceIncreasesWithSampleSize(self):
    """相同正确率下样本量越大置信度应越高。"""
    smallEvidence = KnowledgeEvidence(
      knowledgePointKey="导数定义",
      answerCount=11,
      correctAnswerCount=9,
    )
    largeEvidence = KnowledgeEvidence(
      knowledgePointKey="导数定义",
      answerCount=110,
      correctAnswerCount=90,
    )

    smallMastery = calculateMastery(smallEvidence)
    largeMastery = calculateMastery(largeEvidence)

    self.assertAlmostEqual(9 / 11, smallMastery.correctRate)
    self.assertAlmostEqual(90 / 110, largeMastery.correctRate)
    self.assertLess(smallMastery.confidence, largeMastery.confidence)

  def testTrendIsNoneForFirstCalculation(self):
    """首次计算无历史数据时趋势应为 None。"""
    evidence = KnowledgeEvidence(
      knowledgePointKey="导数定义",
      answerCount=11,
      correctAnswerCount=9,
    )

    mastery = calculateMastery(evidence)

    self.assertIsNone(mastery.trend)

  def testTrendImprovingWhenScoreIncreases(self):
    """当前评分显著高于历史时应判定为提升。

    传入 previousBayesianScore=0.3，当前后验 ≈ 0.7333（11答9对），
    差值远大于 0.05 阈值。
    """
    evidence = KnowledgeEvidence(
      knowledgePointKey="导数定义",
      answerCount=11,
      correctAnswerCount=9,
    )

    mastery = calculateMastery(evidence, previousBayesianScore=0.3)

    self.assertAlmostEqual(0.7333, mastery.bayesianScore, places=4)
    self.assertEqual(MasteryTrend.IMPROVING, mastery.trend)

  def testTrendStableWhenScoreChangeIsSmall(self):
    """评分变化小于阈值时应判定为稳定。"""
    evidence = KnowledgeEvidence(
      knowledgePointKey="导数定义",
      answerCount=11,
      correctAnswerCount=9,
    )

    mastery = calculateMastery(evidence, previousBayesianScore=0.72)

    self.assertAlmostEqual(0.7333, mastery.bayesianScore, places=4)
    self.assertEqual(MasteryTrend.STABLE, mastery.trend)

  def testTrendDecliningWhenScoreDecreases(self):
    """当前评分显著低于历史时应判定为下降。"""
    evidence = KnowledgeEvidence(
      knowledgePointKey="导数定义",
      answerCount=11,
      correctAnswerCount=9,
    )

    mastery = calculateMastery(evidence, previousBayesianScore=0.9)

    self.assertAlmostEqual(0.7333, mastery.bayesianScore, places=4)
    self.assertEqual(MasteryTrend.DECLINING, mastery.trend)


if __name__ == "__main__":
  unittest.main()
