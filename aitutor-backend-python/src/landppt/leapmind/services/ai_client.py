import json
import logging
from typing import AsyncGenerator, Optional
from ...ai import get_ai_provider, AIMessage, MessageRole
from ...core.config import ai_config

logger = logging.getLogger(__name__)


class LeapMindAIClient:
    def __init__(self, provider_name: Optional[str] = None):
        self.provider_name = provider_name or ai_config.default_ai_provider

    @property
    def ai_provider(self):
        return get_ai_provider(self.provider_name)

    async def stream_generate(self, prompt: str, **kwargs) -> AsyncGenerator[dict, None]:
        try:
            messages = [AIMessage(role=MessageRole.USER, content=prompt)]
            buffer = ""

            async for token in self.ai_provider.stream_chat_completion(
                messages=messages,
                max_tokens=kwargs.get("max_tokens", ai_config.max_tokens),
                temperature=kwargs.get("temperature", ai_config.temperature),
            ):
                if not token:
                    continue
                buffer += token
                while "\n" in buffer:
                    line, buffer = buffer.split("\n", 1)
                    line = line.strip()
                    if line.startswith("{") and line.endswith("}"):
                        try:
                            yield json.loads(line)
                        except json.JSONDecodeError:
                            continue
                    elif line:
                        yield {"type": "content", "content": line}

            if buffer.strip():
                line = buffer.strip()
                if line.startswith("{") and line.endswith("}"):
                    try:
                        yield json.loads(line)
                    except json.JSONDecodeError:
                        yield {"type": "content", "content": line}
                elif line:
                    yield {"type": "content", "content": line}

        except Exception as e:
            logger.error(f"AI stream generation error: {e}")
            yield {"type": "error", "content": f"AI 生成失败: {str(e)}"}

    async def generate(self, prompt: str, **kwargs) -> str:
        try:
            messages = [AIMessage(role=MessageRole.USER, content=prompt)]
            response = await self.ai_provider.chat_completion(
                messages=messages,
                max_tokens=kwargs.get("max_tokens", min(ai_config.max_tokens, 4096)),
                temperature=kwargs.get("temperature", 0.3),
            )
            return response.content
        except Exception as e:
            logger.error(f"AI generate error: {e}")
            raise
