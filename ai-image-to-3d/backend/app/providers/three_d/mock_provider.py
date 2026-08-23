from __future__ import annotations

import json
import struct
import uuid
from pathlib import Path
from typing import Any

from app.providers.three_d.base import (
    DownloadedResult,
    ProviderStatus,
    ProviderTask,
    ThreeDProvider,
)


def _minimal_glb() -> bytes:
    document = {
        "asset": {"version": "2.0", "generator": "MockProvider"},
        "scene": 0,
        "scenes": [{"nodes": []}],
        "nodes": [],
    }
    payload = json.dumps(document, separators=(",", ":")).encode()
    payload += b" " * ((4 - len(payload) % 4) % 4)
    total_length = 12 + 8 + len(payload)
    return (
        struct.pack("<4sII", b"glTF", 2, total_length)
        + struct.pack("<I4s", len(payload), b"JSON")
        + payload
    )


class MockProvider(ThreeDProvider):
    def __init__(self) -> None:
        self._cancelled: set[str] = set()

    def create_task(
        self, image_paths: list[Path], *, options: dict[str, Any] | None = None
    ) -> ProviderTask:
        if not image_paths:
            raise ValueError("At least one image is required")
        return ProviderTask(
            id=f"mock-{uuid.uuid4()}",
            status=ProviderStatus.succeeded,
            progress=100,
            model_urls={"glb": "mock://model.glb"},
            raw={"input_count": len(image_paths), "options": options or {}},
        )

    def get_task_status(self, task_id: str) -> ProviderTask:
        if task_id in self._cancelled:
            return ProviderTask(task_id, ProviderStatus.cancelled)
        return ProviderTask(
            id=task_id,
            status=ProviderStatus.succeeded,
            progress=100,
            model_urls={"glb": "mock://model.glb"},
        )

    def download_result(
        self, task: ProviderTask, destination: Path
    ) -> DownloadedResult:
        if task.status != ProviderStatus.succeeded:
            raise RuntimeError("Mock task is not complete")
        destination.mkdir(parents=True, exist_ok=True)
        model_path = destination / "raw.glb"
        model_path.write_bytes(_minimal_glb())
        return DownloadedResult(model_path=model_path)

    def cancel_task(self, task_id: str) -> None:
        self._cancelled.add(task_id)

