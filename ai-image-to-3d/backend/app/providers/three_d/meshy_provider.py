from __future__ import annotations

import base64
import mimetypes
from pathlib import Path
from typing import Any

import httpx
from tenacity import (
    retry,
    retry_if_exception_type,
    stop_after_attempt,
    wait_exponential_jitter,
)

from app.core.config import Settings
from app.providers.three_d.base import (
    DownloadedResult,
    ProviderStatus,
    ProviderTask,
    ThreeDProvider,
)


class MeshyProviderError(RuntimeError):
    pass


STATUS_MAP = {
    "PENDING": ProviderStatus.queued,
    "IN_PROGRESS": ProviderStatus.running,
    "SUCCEEDED": ProviderStatus.succeeded,
    "FAILED": ProviderStatus.failed,
    "CANCELED": ProviderStatus.cancelled,
    "CANCELLED": ProviderStatus.cancelled,
}


class MeshyProvider(ThreeDProvider):
    def __init__(
        self,
        *,
        api_key: str,
        base_url: str,
        timeout_seconds: float,
    ) -> None:
        if not api_key:
            raise MeshyProviderError("MESHY_API_KEY is not configured")
        self.client = httpx.Client(
            base_url=base_url.rstrip("/"),
            headers={"Authorization": f"Bearer {api_key}"},
            timeout=httpx.Timeout(timeout_seconds, connect=min(15, timeout_seconds)),
            follow_redirects=True,
        )

    @classmethod
    def from_settings(cls, settings: Settings) -> "MeshyProvider":
        return cls(
            api_key=settings.meshy_api_key or "",
            base_url=settings.meshy_base_url,
            timeout_seconds=settings.meshy_timeout_seconds,
        )

    @retry(
        retry=retry_if_exception_type((httpx.TimeoutException, httpx.NetworkError)),
        wait=wait_exponential_jitter(initial=1, max=10),
        stop=stop_after_attempt(4),
        reraise=True,
    )
    def _request(self, method: str, url: str, **kwargs) -> httpx.Response:
        response = self.client.request(method, url, **kwargs)
        if response.status_code == 429 or response.status_code >= 500:
            raise httpx.NetworkError(
                f"Transient Meshy response: HTTP {response.status_code}"
            )
        try:
            response.raise_for_status()
        except httpx.HTTPStatusError as exc:
            detail = response.text[:1000]
            raise MeshyProviderError(
                f"Meshy API returned HTTP {response.status_code}: {detail}"
            ) from exc
        return response

    def create_task(
        self, image_paths: list[Path], *, options: dict[str, Any] | None = None
    ) -> ProviderTask:
        if not image_paths:
            raise ValueError("At least one image is required")
        # Meshy's /image-to-3d endpoint accepts one image. Multi-view references
        # remain available to future providers without changing this interface.
        primary = image_paths[0]
        mime = mimetypes.guess_type(primary.name)[0] or "image/png"
        image_url = (
            f"data:{mime};base64,"
            + base64.b64encode(primary.read_bytes()).decode("ascii")
        )
        options = options or {}
        payload = {
            "image_url": image_url,
            "should_remesh": True,
            "target_polycount": int(options.get("target_polycount", 50_000)),
            "should_texture": bool(options.get("should_texture", True)),
            "enable_pbr": bool(options.get("enable_pbr", True)),
            "pose_mode": options.get("pose_mode", "a-pose"),
            "target_formats": ["glb"],
        }
        response = self._request(
            "POST", "/openapi/v1/image-to-3d", json=payload
        ).json()
        task_id = response.get("result")
        if not task_id:
            raise MeshyProviderError("Meshy create response did not include result id")
        return ProviderTask(
            id=str(task_id),
            status=ProviderStatus.queued,
            progress=0,
            raw={"submitted_views": len(image_paths)},
        )

    def get_task_status(self, task_id: str) -> ProviderTask:
        payload = self._request(
            "GET", f"/openapi/v1/image-to-3d/{task_id}"
        ).json()
        provider_status = str(payload.get("status", "")).upper()
        status = STATUS_MAP.get(provider_status)
        if not status:
            raise MeshyProviderError(f"Unknown Meshy task status: {provider_status}")
        task_error = payload.get("task_error") or {}
        return ProviderTask(
            id=str(payload.get("id") or task_id),
            status=status,
            progress=float(payload.get("progress") or 0),
            model_urls=dict(payload.get("model_urls") or {}),
            texture_urls=list(payload.get("texture_urls") or []),
            error=task_error.get("message") or None,
            raw=payload,
        )

    def download_result(
        self, task: ProviderTask, destination: Path
    ) -> DownloadedResult:
        if task.status != ProviderStatus.succeeded:
            raise MeshyProviderError("Meshy task is not complete")
        glb_url = task.model_urls.get("glb")
        if not glb_url:
            raise MeshyProviderError("Meshy task has no GLB download URL")
        destination.mkdir(parents=True, exist_ok=True)
        model_path = destination / "raw.glb"
        self._download_file(glb_url, model_path, max_bytes=500 * 1024 * 1024)
        texture_dir = destination / "textures"
        texture_paths: list[Path] = []
        for index, texture_set in enumerate(task.texture_urls):
            for kind, url in texture_set.items():
                if not url:
                    continue
                suffix = Path(httpx.URL(url).path).suffix or ".png"
                texture_path = texture_dir / f"{index}-{kind}{suffix}"
                self._download_file(url, texture_path, max_bytes=100 * 1024 * 1024)
                texture_paths.append(texture_path)
        return DownloadedResult(model_path=model_path, texture_paths=texture_paths)

    def _download_file(self, url: str, destination: Path, *, max_bytes: int) -> None:
        response = self._request("GET", url)
        content_length = int(response.headers.get("content-length", 0) or 0)
        if content_length > max_bytes:
            raise MeshyProviderError("Provider download exceeds size limit")
        content = response.content
        if len(content) > max_bytes:
            raise MeshyProviderError("Provider download exceeds size limit")
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(content)

    def cancel_task(self, task_id: str) -> None:
        self._request("DELETE", f"/openapi/v1/image-to-3d/{task_id}")

