from __future__ import annotations

from datetime import datetime
from enum import Enum

from pydantic import BaseModel, Field

from app.models import JobStatus


class ViewName(str, Enum):
    front = "front"
    left = "left"
    back = "back"
    three_quarter = "three-quarter"


class GenerateImageRequest(BaseModel):
    prompt: str = Field(min_length=3, max_length=2000)
    views: list[ViewName] = Field(default_factory=lambda: [ViewName.front])


class GenerateModelRequest(BaseModel):
    image_ids: list[str] = Field(min_length=1, max_length=4)
    provider: str | None = None
    pose_mode: str = "a-pose"
    target_polycount: int = Field(default=50_000, ge=1_000, le=300_000)
    should_texture: bool = True
    enable_pbr: bool = True


class ProcessModelRequest(BaseModel):
    decimate_face_count: int = Field(default=0, ge=0)
    triangulate: bool = False
    min_fragment_ratio: float = Field(default=0.001, ge=0, le=0.2)


class ImageAssetResponse(BaseModel):
    id: str
    view: str
    mime_type: str
    width: int
    height: int
    url: str


class JobResponse(BaseModel):
    id: str
    kind: str
    status: JobStatus
    progress: float
    provider_task_id: str | None
    result: dict
    error: str | None
    created_at: datetime
    updated_at: datetime

    model_config = {"from_attributes": True}


class ModelResponse(BaseModel):
    id: str
    job_id: str
    provider: str
    provider_task_id: str | None
    status: JobStatus
    has_raw_model: bool
    has_processed_model: bool
    raw_download_url: str | None
    processed_download_url: str | None
    preview_url: str | None
    error: str | None

