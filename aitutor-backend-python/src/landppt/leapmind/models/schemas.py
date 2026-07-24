from pydantic import BaseModel
from typing import Optional, List
from enum import Enum


class QuestionType(str, Enum):
    SINGLE_CHOICE = "single_choice"
    MULTI_CHOICE = "multi_choice"
    FILL_BLANK = "fill_blank"
    SHORT_ANSWER = "short_answer"
    ESSAY = "essay"


class StructuredQuestion(BaseModel):
    stem: str
    options: Optional[List[str]] = None
    type: QuestionType
    subject: str


class OCRProcessRequest(BaseModel):
    rawText: str
    subject: Optional[str] = None
    imageUrl: Optional[str] = None


class OCRProcessResponse(BaseModel):
    recognizedText: str
    structuredQuestion: StructuredQuestion
    confidence: float


class AIGenerateRequest(BaseModel):
    module: str
    scene: str
    params: dict = {}
    prompt: Optional[str] = None
    modelName: Optional[str] = None
    maxTokens: Optional[int] = None
    temperature: Optional[float] = None


class AIGenerateStreamEvent(BaseModel):
    type: str
    content: str
    stepNumber: Optional[int] = None
    title: Optional[str] = None
    contentId: Optional[int] = None
    explainId: Optional[int] = None
    prepId: Optional[int] = None
    index: Optional[int] = None
