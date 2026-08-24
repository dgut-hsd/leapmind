from __future__ import annotations

import time
from pathlib import Path
from typing import Any

from app.core.config import Settings
from app.providers.three_d.base import DownloadedResult, ProviderStatus, ThreeDProvider
from app.providers.three_d.mock_provider import MockProvider


class ModelPipelineError(RuntimeError):
    pass


def build_three_d_provider(name: str, settings: Settings) -> ThreeDProvider:
    if name == "mock":
        return MockProvider()
    if name == "meshy":
        from app.providers.three_d.meshy_provider import MeshyProvider

        return MeshyProvider.from_settings(settings)
    raise ValueError(f"Unsupported 3D provider: {name}")


class ModelPipeline:
    def __init__(self, provider: ThreeDProvider, settings: Settings) -> None:
        self.provider = provider
        self.settings = settings

    def run(
        self,
        image_paths: list[Path],
        destination: Path,
        *,
        options: dict[str, Any],
        on_update=None,
    ) -> tuple[str, DownloadedResult, dict[str, Any]]:
        task = self.provider.create_task(image_paths, options=options)
        if on_update:
            on_update(task)
        deadline = time.monotonic() + self.settings.meshy_max_wait_seconds
        while task.status in {ProviderStatus.queued, ProviderStatus.running}:
            if time.monotonic() >= deadline:
                self.provider.cancel_task(task.id)
                raise ModelPipelineError("3D provider task timed out")
            time.sleep(self.settings.meshy_poll_interval_seconds)
            task = self.provider.get_task_status(task.id)
            if on_update:
                on_update(task)
        if task.status != ProviderStatus.succeeded:
            raise ModelPipelineError(task.error or f"3D task ended as {task.status}")
        downloaded = self.provider.download_result(task, destination)
        return task.id, downloaded, task.raw

