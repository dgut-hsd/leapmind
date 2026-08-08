from fastapi import APIRouter
from models.schemas import NLPAnalysisRequest, NLPAnalysisResponse
from services.nlp_analyzer import analyzer

router = APIRouter()

@router.post("/api/internal/ai/analyze-sentiment",
             response_model=NLPAnalysisResponse)
async def analyze_sentiment(request: NLPAnalysisRequest):
    """
    文本情感分析接口（Java 内部调用）
    → 返回表情序列 + 手势序列

    调用示例：
    POST /api/internal/ai/analyze-sentiment
    {
        "text": "这道题非常重要，一定要注意！",
        "scene_type": "lecturing",
        "base_emotion": null
    }
    """
    result = analyzer.analyze(
        text=request.text,
        scene_type=request.scene_type,
        base_emotion=request.base_emotion,
    )
    return NLPAnalysisResponse(**result)