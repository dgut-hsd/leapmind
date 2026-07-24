import json
import logging
from fastapi import APIRouter
from fastapi.responses import StreamingResponse
from ..models.schemas import AIGenerateRequest
from ..services.prompt_manager import PromptManager
from ..services.ai_client import LeapMindAIClient

logger = logging.getLogger(__name__)
router = APIRouter(tags=["LeapMind - AI Generate"])
prompt_manager = PromptManager()
ai_client = LeapMindAIClient()


@router.post("/api/internal/ai/generate")
async def generate(request: AIGenerateRequest):
    """Non-streaming AI generation"""
    prompt = _build_prompt(request)
    try:
        result = await ai_client.generate(prompt, max_tokens=request.maxTokens, temperature=request.temperature)
        return {"content": result}
    except Exception as e:
        logger.error(f"AI generate failed: {e}")
        return {"error": str(e)}


@router.post("/api/internal/ai/generate/stream")
async def generate_stream(request: AIGenerateRequest):
    """SSE streaming AI generation"""
    prompt = _build_prompt(request)

    async def event_stream():
        yield f"data: {json.dumps({'type': 'thinking', 'content': ''}, ensure_ascii=False)}\n\n"
        async for chunk in ai_client.stream_generate(prompt, max_tokens=request.maxTokens, temperature=request.temperature):
            yield f"data: {json.dumps(chunk, ensure_ascii=False)}\n\n"
        yield f"data: {json.dumps({'type': 'done', 'content': ''}, ensure_ascii=False)}\n\n"

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream"
    )


def _build_prompt(request: AIGenerateRequest) -> str:
    params = request.params or {}
    user_profile = params.get("userProfile")

    if request.module == "explain":
        if request.scene == "photo_qa":
            question = params.get("question", {})
            return prompt_manager.build_photo_qa_prompt(question, user_profile)
        elif request.scene == "explain_wrong":
            question = params.get("question", {})
            user_answer = params.get("userAnswer", {})
            wrong_reason_tag = params.get("wrongReasonTag", "")
            knowledge_points = params.get("knowledgePoints", [])
            return prompt_manager.build_explain_wrong_prompt(
                question, user_answer, wrong_reason_tag, knowledge_points, user_profile
            )
    elif request.module == "lesson_prep" and request.scene == "generate":
        return _build_lesson_prep_prompt(params)

    return request.prompt or "请提供有效的 module 和 scene 参数"


def _build_lesson_prep_prompt(params: dict) -> str:
    title = params.get("title", "未命名")
    subject = params.get("subject", "未知")
    grade = params.get("grade", "未知年级")
    knowledge_points = params.get("knowledgePointIds", [])
    teaching_goals = params.get("teachingGoals", [])
    total_hours = params.get("totalHours", 1)
    style = params.get("style", "standard")

    prompt = f"""你是一位经验丰富的教师，请根据以下信息生成结构化备课内容。

## 基本信息
标题：{title}
科目：{subject}
年级：{grade}
课时数：{total_hours}
风格：{style}

## 教学目标
{chr(10).join(f'- {g}' for g in teaching_goals)}

## 知识点ID
{[str(kp) for kp in knowledge_points]}

请按课时生成完整的教案，每课时包含：教学目标、重难点、教学过程、板书设计、课后作业。
"""
    return prompt
