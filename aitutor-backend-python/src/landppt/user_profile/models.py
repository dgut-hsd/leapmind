"""用户画像领域模型。"""

from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Any, Mapping


class EventSource(str, Enum):
  """M6 当前支持的数据来源。"""

  USER_ANSWERS = "user_answers"
  WRONG_QUESTION_BOOK = "wrong_question_book"
  CONVERSATION_MESSAGES = "conversation_messages"
  USER_WEAK_POINTS = "user_weak_points"


class EventType(str, Enum):
  """统一学习事件类型。"""

  ANSWER_SUBMITTED = "answer_submitted"
  WRONG_QUESTION_ADDED = "wrong_question_added"
  CONVERSATION_MESSAGE = "conversation_message"
  WEAK_POINT_UPDATED = "weak_point_updated"


class ProfileStatus(str, Enum):
  """画像计算状态。"""

  INSUFFICIENT_DATA = "insufficient_data"
  LEARNING = "learning"
  MASTERED = "mastered"


class MasteryTrend(str, Enum):
  """知识点掌握趋势。"""

  IMPROVING = "IMPROVING"
  STABLE = "STABLE"
  DECLINING = "DECLINING"


class ProfileBuildStatus(str, Enum):
  """整份用户画像的生成状态。"""

  INSUFFICIENT_DATA = "insufficient_data"
  PARTIAL = "partial"
  READY = "ready"


class LearningMode(str, Enum):
  """四维学习行为模式。"""

  VISUAL = "visual"
  AUDITORY = "auditory"
  READING = "reading"
  KINESTHETIC = "kinesthetic"


class LearningPreferenceStatus(str, Enum):
  """学习偏好向量计算状态。"""

  INSUFFICIENT_DATA = "insufficient_data"
  READY = "ready"


@dataclass(frozen=True)
class LearningEvent:
  """跨模块归一化后的单条学习事件。"""

  eventId: str
  userId: int
  source: EventSource
  eventType: EventType
  occurredAt: datetime
  knowledgePointKey: str | None = None
  data: Mapping[str, Any] = field(default_factory=dict)

  def __post_init__(self):
    """校验跨服务事件契约中的必要字段。"""
    if not self.eventId.strip():
      raise ValueError("事件 ID 不能为空")
    if self.userId <= 0:
      raise ValueError("用户 ID 必须为正整数")
    if not isinstance(self.source, EventSource):
      raise ValueError("事件来源不受支持")
    if not isinstance(self.eventType, EventType):
      raise ValueError("事件类型不受支持")
    if self.occurredAt.tzinfo is None or self.occurredAt.utcoffset() is None:
      raise ValueError("事件发生时间必须包含时区")
    if (
      self.knowledgePointKey is not None
      and not self.knowledgePointKey.strip()
    ):
      raise ValueError("知识点键不能为空")


@dataclass
class KnowledgeEvidence:
  """单个知识点的多源证据汇总。"""

  knowledgePointKey: str
  answerCount: int = 0
  correctAnswerCount: int = 0
  wrongQuestionCount: int = 0
  conversationMessageCount: int = 0
  weakPointCount: int = 0
  lastEventAt: datetime | None = None


@dataclass
class AggregationResult:
  """指定用户的多源数据汇总结果。"""

  userId: int
  totalEventCount: int
  duplicateEventCount: int
  unmappedEventCount: int
  sourceRecordCounts: Mapping[EventSource, int]
  evidenceByKnowledgePoint: Mapping[str, KnowledgeEvidence]


@dataclass(frozen=True)
class KnowledgeMastery:
  """单个知识点的可解释掌握判定。

  v2 升级：新增贝叶斯后验均值、Wilson 置信区间和趋势判定，
  使掌握度评估对小样本更鲁棒，并提供可解释的置信度度量。
  """

  knowledgePointKey: str
  status: ProfileStatus
  sampleCount: int
  correctRate: float
  minimumAnswerCount: int
  masteryThreshold: float
  algorithmVersion: str
  # v2 新增字段
  bayesianScore: float | None = None
  confidence: float | None = None
  trend: MasteryTrend | None = None


@dataclass(frozen=True)
class ConfusionPoint:
  """从对话事件中提取出的单个困惑点。

  v2 升级：新增置信度评分，基于命中模式数量和否定排除综合计算。
  """

  eventId: str
  knowledgePointKey: str | None
  occurredAt: datetime
  matchedPatterns: tuple[str, ...]
  textExcerpt: str
  # v2 新增字段
  confidence: float | None = None


@dataclass(frozen=True)
class DataQualityWarning:
  """画像生成过程中发现的非阻断数据问题。"""

  code: str
  message: str
  eventId: str | None = None


@dataclass(frozen=True)
class LearningPreferenceVector:
  """基于学习行为证据生成的四维偏好向量。

  v2 升级：新增 Shannon 熵度量的偏好强度，低熵表示强偏好，高熵表示均衡。
  """

  status: LearningPreferenceStatus
  scores: Mapping[LearningMode, float]
  evidenceCount: int
  ignoredEvidenceCount: int
  dominantDimensions: tuple[LearningMode, ...]
  minimumEvidenceCount: int
  algorithmVersion: str
  dataQualityWarnings: tuple[DataQualityWarning, ...]
  # v2 新增字段
  confidence: float | None = None
  preferenceStrength: float | None = None


@dataclass(frozen=True)
class UserProfileMetadata:
  """画像结果的时间范围和算法版本信息。"""

  generatedAt: datetime
  firstEventAt: datetime | None
  lastEventAt: datetime | None
  algorithmVersions: Mapping[str, str]


@dataclass(frozen=True)
class UserProfileResult:
  """画像引擎生成的强类型结果。"""

  userId: int
  overallStatus: ProfileBuildStatus
  aggregation: AggregationResult
  masteryByKnowledgePoint: Mapping[str, KnowledgeMastery]
  confusionPoints: tuple[ConfusionPoint, ...]
  missingSources: tuple[EventSource, ...]
  dataQualityWarnings: tuple[DataQualityWarning, ...]
  learningPreference: LearningPreferenceVector
  metadata: UserProfileMetadata
