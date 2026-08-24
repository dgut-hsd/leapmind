"""掌握度置信度与趋势计算。

v2 新增模块，提供：
- Wilson 得分区间：基于样本量和正确率计算置信区间
- 贝叶斯 Beta-Binomial 后验：对小样本更鲁棒的掌握度估计
- 趋势判定：对比当前与历史掌握度后验均值
"""

import math
from typing import Literal

# Beta 先验参数：Beta(α=2, β=2) 是一个弱信息先验，
# 中心在 0.5，提供适度正则化，避免极小样本下的极端估计。
BETA_PRIOR_ALPHA = 2.0
BETA_PRIOR_BETA = 2.0

# Wilson 区间 z 值（95% 置信水平）
WILSON_Z = 1.96

# 趋势判定阈值：后验均值变化超过此值才认为有趋势
TREND_THRESHOLD = 0.05

TrendLabel = Literal["IMPROVING", "STABLE", "DECLINING"]


def bayesianPosteriorMean(
  correct: int,
  total: int,
  alpha: float = BETA_PRIOR_ALPHA,
  beta: float = BETA_PRIOR_BETA,
) -> float:
  """计算 Beta-Binomial 模型的后验均值。

  后验分布为 Beta(α + correct, β + incorrect)，
  其均值为 (α + correct) / (α + β + total)。

  相比裸正确率，后验均值在样本量小时向先验中心 0.5 收缩，
  避免少量答题即判定掌握/未掌握的过度自信问题。

  Args:
    correct: 正确答题数
    total: 总答题数
    alpha: Beta 先验 α 参数
    beta: Beta 先验 β 参数

  Returns:
    后验均值，范围 [0, 1]
  """
  if total <= 0:
    return alpha / (alpha + beta)
  incorrect = total - correct
  return (alpha + correct) / (alpha + beta + total)


def wilsonConfidence(correct: int, total: int, z: float = WILSON_Z) -> float:
  """计算 Wilson 得分区间的置信度评分。

  Wilson 区间宽度反映了估计的不确定性：
  - 样本量大 → 区间窄 → 置信度高
  - 样本量小 → 区间宽 → 置信度低

  置信度评分 = 1 - (区间宽度 / 最大可能宽度)

  Args:
    correct: 正确答题数
    total: 总答题数
    z: 标准正态分布分位数（默认 1.96 对应 95% CI）

  Returns:
    置信度评分，范围 [0, 1]
  """
  if total <= 0:
    return 0.0

  p = correct / total
  n = total
  z2 = z * z

  denominator = 1 + z2 / n
  center = (p + z2 / (2 * n)) / denominator
  margin = z * math.sqrt(p * (1 - p) / n + z2 / (4 * n * n)) / denominator

  intervalWidth = 2 * margin
  # Wilson 区间最大宽度接近 2*z/denominator（当 p=0.5, n→0 时），
  # 使用 1 - width/2 作为归一化（width 最大约为 1.0）
  confidence = max(0.0, min(1.0, 1.0 - intervalWidth))

  return round(confidence, 4)


def determineTrend(
  currentScore: float,
  previousScore: float | None,
  threshold: float = TREND_THRESHOLD,
) -> TrendLabel | None:
  """对比当前与历史贝叶斯后验均值，判定掌握趋势。

  Args:
    currentScore: 当前画像的后验均值
    previousScore: 上一版本画像的后验均值（首次计算时为 None）
    threshold: 趋势判定阈值

  Returns:
    "IMPROVING" / "STABLE" / "DECLINING" / None（无历史数据时）
  """
  if previousScore is None:
    return None

  delta = currentScore - previousScore
  if delta > threshold:
    return "IMPROVING"
  if delta < -threshold:
    return "DECLINING"
  return "STABLE"
