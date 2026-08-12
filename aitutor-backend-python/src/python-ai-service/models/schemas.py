from enum import Enum
from typing import List, Optional
from pydantic import BaseModel

class ExpressionType(str, Enum):
    """表情类型 —— 与前端 VRM blendshape 一一对应"""
    NEUTRAL = "neutral"
    HAPPY = "happy"
    SAD = "sad"
    ANGRY = "angry"
    SURPRISED = "surprised"
    RELAXED = "relaxed"
    # 以下为复合/自定义表情，前端映射到 blendshape 组合
    SERIOUS = "serious"          # → angry(0.3) + neutral
    ENCOURAGING = "encouraging"  # → happy(0.8)
    CURIOUS = "curious"          # → surprised(0.3)
    EXPLAINING = "explaining"    # → neutral + headMotion
    THINKING = "thinking"        # → neutral + lookUp

class GestureType(str, Enum):
    """手势类型 —— 映射到前端 .vrma 动画文件"""
    NONE = "none"
    POINTING = "pointing"       # → VRMA_01
    EMPHASIZING = "emphasizing" # → VRMA_02
    OPEN_ARMS = "open_arms"     # → VRMA_03
    THUMBS_UP = "thumbs_up"     # → VRMA_04
    COUNTING = "counting"       # → VRMA_05
    WRITING = "writing"         # → VRMA_06
    NODDING = "nodding"         # → headMotionController
    SHAKING_HEAD = "shaking"    # → headMotionController

class ExpressionSegment(BaseModel):
    expression: ExpressionType
    start_ms: int
    end_ms: int
    intensity: float = 1.0      # 0.0~1.0

class GestureSegment(BaseModel):
    gesture: GestureType
    start_ms: int
    end_ms: int

class NLPAnalysisRequest(BaseModel):
    text: str
    scene_type: str = "lecturing"    # lecturing / answering / encouraging / qa
    base_emotion: Optional[str] = None  # 手动指定情绪，覆盖自动推理

class NLPAnalysisResponse(BaseModel):
    expressions: List[ExpressionSegment]
    gestures: List[GestureSegment]
    sentiment_score: float            # -1.0 ~ 1.0
    dominant_emotion: str
    keywords: List[str]