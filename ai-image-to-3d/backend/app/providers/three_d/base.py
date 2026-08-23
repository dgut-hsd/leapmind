from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Any


class ProviderStatus(str, Enum):
    queued = "queued"
    running = "running"
    succeeded = "succeeded"
    failed = "failed"
    cancelled = "cancelled"


@dataclass(slots=True)
class ProviderTask:
    id: str
    status: ProviderStatus
    progress: float = 0
    model_urls: dict[str, str] = field(default_factory=dict)
    texture_urls: list[dict[str, str]] = field(default_factory=list)
    error: str | None = None
    raw: dict[str, Any] = field(default_factory=dict)


@dataclass(slots=True)
class DownloadedResult:
    model_path: Path
    texture_paths: list[Path] = field(default_factory=list)


class ThreeDProvider(ABC):
    """Normalized interface for all image-to-3D vendors."""

    @abstractmethod
    def create_task(
        self, image_paths: list[Path], *, options: dict[str, Any] | None = None
    ) -> ProviderTask:
        raise NotImplementedError

    @abstractmethod
    def get_task_status(self, task_id: str) -> ProviderTask:
        raise NotImplementedError

    @abstractmethod
    def download_result(
        self, task: ProviderTask, destination: Path
    ) -> DownloadedResult:
        raise NotImplementedError

    @abstractmethod
    def cancel_task(self, task_id: str) -> None:
        raise NotImplementedError

