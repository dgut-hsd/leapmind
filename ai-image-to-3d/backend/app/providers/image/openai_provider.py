from __future__ import annotations

import base64
from dataclasses import dataclass

from openai import APIConnectionError, APIStatusError, APITimeoutError, OpenAI

from app.core.config import Settings


class ImageProviderError(RuntimeError):
    pass


VIEW_INSTRUCTIONS = {
    "front": "exact orthographic front view, looking straight at the camera",
    "left": "exact orthographic left-side profile, facing the left edge",
    "back": "exact orthographic back view, no face visible",
    "three-quarter": "front-left 45 degree three-quarter view",
}


@dataclass(slots=True)
class GeneratedImage:
    content: bytes
    mime_type: str = "image/png"


class OpenAIImageProvider:
    def __init__(self, settings: Settings) -> None:
        if not settings.openai_api_key:
            raise ImageProviderError("OPENAI_API_KEY is not configured")
        self.settings = settings
        self.client = OpenAI(
            api_key=settings.openai_api_key,
            timeout=settings.openai_timeout_seconds,
            max_retries=settings.openai_max_retries,
        )

    def generate_reference(self, subject_prompt: str, view: str) -> GeneratedImage:
        if view not in VIEW_INSTRUCTIONS:
            raise ValueError(f"Unsupported view: {view}")
        prompt = self._build_prompt(subject_prompt, view)
        try:
            response = self.client.images.generate(
                model=self.settings.openai_image_model,
                prompt=prompt,
                size=self.settings.openai_image_size,
                quality=self.settings.openai_image_quality,
                output_format="png",
                background="opaque",
                n=1,
            )
        except (APIConnectionError, APITimeoutError) as exc:
            raise ImageProviderError("OpenAI Image API network request failed") from exc
        except APIStatusError as exc:
            request_id = getattr(exc, "request_id", None)
            suffix = f" (request id: {request_id})" if request_id else ""
            raise ImageProviderError(
                f"OpenAI Image API returned HTTP {exc.status_code}{suffix}"
            ) from exc
        if not response.data or not response.data[0].b64_json:
            raise ImageProviderError("OpenAI Image API returned no image data")
        try:
            return GeneratedImage(base64.b64decode(response.data[0].b64_json))
        except (ValueError, TypeError) as exc:
            raise ImageProviderError("OpenAI returned invalid base64 image data") from exc

    @staticmethod
    def _build_prompt(subject_prompt: str, view: str) -> str:
        return f"""
Create a clean reference image specifically for image-to-3D reconstruction.

Subject: {subject_prompt.strip()}
Camera: {VIEW_INSTRUCTIONS[view]}.

Requirements:
- exactly one subject
- complete subject fully visible with generous margin
- centered and unobstructed
- neutral pose appropriate for reconstruction
- consistent proportions and physically coherent details
- no props unless explicitly part of the subject
- no floor stand, text, labels, watermark, frame, inset or additional views
- even studio lighting with minimal shadow
- solid pure white background
- sharp silhouette and clear material boundaries

This image is one view in a multi-view turntable, so do not redesign the subject.
""".strip()

