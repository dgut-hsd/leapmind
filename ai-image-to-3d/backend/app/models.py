from __future__ import annotations

import enum
import uuid
from datetime import datetime, timezone
from typing import Any

from sqlalchemy import JSON, DateTime, Enum, Float, ForeignKey, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.core.database import Base


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


class JobStatus(str, enum.Enum):
    queued = "queued"
    generating_image = "generating_image"
    generating_3d = "generating_3d"
    processing = "processing"
    completed = "completed"
    failed = "failed"


ALLOWED_TRANSITIONS: dict[JobStatus, set[JobStatus]] = {
    JobStatus.queued: {
        JobStatus.generating_image,
        JobStatus.generating_3d,
        JobStatus.processing,
        JobStatus.failed,
    },
    JobStatus.generating_image: {
        JobStatus.generating_3d,
        JobStatus.completed,
        JobStatus.failed,
    },
    JobStatus.generating_3d: {
        JobStatus.processing,
        JobStatus.completed,
        JobStatus.failed,
    },
    JobStatus.processing: {JobStatus.completed, JobStatus.failed},
    JobStatus.completed: {JobStatus.processing},
    JobStatus.failed: set(),
}


class Job(Base):
    __tablename__ = "jobs"

    id: Mapped[str] = mapped_column(
        String(36), primary_key=True, default=lambda: str(uuid.uuid4())
    )
    kind: Mapped[str] = mapped_column(String(40), index=True)
    status: Mapped[JobStatus] = mapped_column(
        Enum(JobStatus, native_enum=False), default=JobStatus.queued, index=True
    )
    progress: Mapped[float] = mapped_column(Float, default=0)
    provider_task_id: Mapped[str | None] = mapped_column(String(255))
    input_payload: Mapped[dict[str, Any]] = mapped_column(JSON, default=dict)
    result: Mapped[dict[str, Any]] = mapped_column(JSON, default=dict)
    error: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utcnow
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utcnow, onupdate=utcnow
    )

    def transition(self, status: JobStatus, *, progress: float | None = None) -> None:
        if status != self.status and status not in ALLOWED_TRANSITIONS[self.status]:
            raise ValueError(f"Invalid job transition: {self.status} -> {status}")
        self.status = status
        if progress is not None:
            self.progress = max(0, min(100, progress))


class ImageAsset(Base):
    __tablename__ = "image_assets"

    id: Mapped[str] = mapped_column(
        String(36), primary_key=True, default=lambda: str(uuid.uuid4())
    )
    job_id: Mapped[str | None] = mapped_column(ForeignKey("jobs.id"), index=True)
    source_type: Mapped[str] = mapped_column(String(20))
    view: Mapped[str] = mapped_column(String(30), default="front")
    original_path: Mapped[str | None] = mapped_column(Text)
    processed_path: Mapped[str] = mapped_column(Text)
    mime_type: Mapped[str] = mapped_column(String(100))
    width: Mapped[int] = mapped_column(Integer)
    height: Mapped[int] = mapped_column(Integer)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utcnow
    )

    job: Mapped[Job | None] = relationship()


class ModelAsset(Base):
    __tablename__ = "model_assets"

    id: Mapped[str] = mapped_column(
        String(36), primary_key=True, default=lambda: str(uuid.uuid4())
    )
    job_id: Mapped[str] = mapped_column(ForeignKey("jobs.id"), index=True)
    provider: Mapped[str] = mapped_column(String(30))
    provider_task_id: Mapped[str | None] = mapped_column(String(255), index=True)
    status: Mapped[JobStatus] = mapped_column(
        Enum(JobStatus, native_enum=False), default=JobStatus.queued
    )
    raw_model_path: Mapped[str | None] = mapped_column(Text)
    processed_model_path: Mapped[str | None] = mapped_column(Text)
    texture_dir: Mapped[str | None] = mapped_column(Text)
    provider_payload: Mapped[dict[str, Any]] = mapped_column(JSON, default=dict)
    error: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utcnow
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utcnow, onupdate=utcnow
    )

    job: Mapped[Job] = relationship()

