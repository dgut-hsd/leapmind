from __future__ import annotations

import uuid
from pathlib import Path

from fastapi import APIRouter, Depends, File, HTTPException, Query, UploadFile
from fastapi.responses import FileResponse
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.config import Settings, get_settings
from app.core.database import get_db
from app.models import ImageAsset, Job, JobStatus, ModelAsset
from app.schemas import (
    GenerateImageRequest,
    GenerateModelRequest,
    ImageAssetResponse,
    JobResponse,
    ModelResponse,
    ProcessModelRequest,
)
from app.services.image_preprocessor import ImagePreprocessor, ImageValidationError
from app.services.storage import safe_filename
from app.workers.tasks import generate_model_task

router = APIRouter(prefix="/api")


def _image_response(asset: ImageAsset, settings: Settings) -> ImageAssetResponse:
    return ImageAssetResponse(
        id=asset.id,
        view=asset.view,
        mime_type=asset.mime_type,
        width=asset.width,
        height=asset.height,
        url=f"{settings.api_base_url}/api/images/{asset.id}",
    )


def _model_response(model: ModelAsset, settings: Settings) -> ModelResponse:
    raw_url = (
        f"{settings.api_base_url}/api/models/{model.id}/download?variant=raw"
        if model.raw_model_path
        else None
    )
    processed_url = (
        f"{settings.api_base_url}/api/models/{model.id}/download?variant=processed"
        if model.processed_model_path
        else None
    )
    return ModelResponse(
        id=model.id,
        job_id=model.job_id,
        provider=model.provider,
        provider_task_id=model.provider_task_id,
        status=model.status,
        has_raw_model=bool(model.raw_model_path),
        has_processed_model=bool(model.processed_model_path),
        raw_download_url=raw_url,
        processed_download_url=processed_url,
        preview_url=processed_url or raw_url,
        error=model.error,
    )


@router.post("/images/generate", response_model=JobResponse, status_code=202)
def generate_images(
    payload: GenerateImageRequest,
    db: Session = Depends(get_db),
):
    job = Job(
        kind="generate_images",
        status=JobStatus.queued,
        input_payload=payload.model_dump(mode="json"),
    )
    db.add(job)
    db.commit()
    db.refresh(job)
    from app.workers.image_tasks import generate_reference_images_task

    generate_reference_images_task.delay(job.id)
    return job


@router.post("/images/upload", response_model=list[ImageAssetResponse])
async def upload_images(
    files: list[UploadFile] = File(...),
    views: str = Query(default="front"),
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
):
    if not 1 <= len(files) <= 4:
        raise HTTPException(400, "Upload between one and four images")
    view_names = [item.strip() for item in views.split(",") if item.strip()]
    allowed_views = {"front", "left", "back", "three-quarter"}
    if len(view_names) not in {1, len(files)} or not set(view_names) <= allowed_views:
        raise HTTPException(400, "Views must match files and use supported names")
    if len(view_names) == 1 and len(files) > 1:
        view_names = ["front", "left", "back", "three-quarter"][: len(files)]
    preprocessor = ImagePreprocessor(
        max_bytes=settings.max_upload_bytes,
        target_size=settings.image_target_size,
        remove_background=settings.remove_background,
    )
    assets: list[ImageAsset] = []
    for upload, view in zip(files, view_names, strict=True):
        data = await upload.read(settings.max_upload_bytes + 1)
        asset_id = str(uuid.uuid4())
        original_name = safe_filename(upload.filename or "upload")
        original_path = settings.uploads_dir / f"{asset_id}-{original_name}"
        processed_path = settings.uploads_dir / f"{asset_id}.png"
        try:
            original_path.write_bytes(data)
            result = preprocessor.process_bytes(data, processed_path)
        except ImageValidationError as exc:
            original_path.unlink(missing_ok=True)
            raise HTTPException(400, str(exc)) from exc
        except RuntimeError as exc:
            original_path.unlink(missing_ok=True)
            raise HTTPException(503, str(exc)) from exc
        asset = ImageAsset(
            id=asset_id,
            source_type="upload",
            view=view,
            original_path=str(original_path),
            processed_path=str(result.path),
            mime_type=result.mime_type,
            width=result.width,
            height=result.height,
        )
        db.add(asset)
        assets.append(asset)
    db.commit()
    return [_image_response(asset, settings) for asset in assets]


@router.get("/images/{image_id}")
def get_image(
    image_id: str,
    db: Session = Depends(get_db),
):
    image = db.get(ImageAsset, image_id)
    if not image or not Path(image.processed_path).is_file():
        raise HTTPException(404, "Image not found")
    return FileResponse(image.processed_path, media_type=image.mime_type)


@router.post("/models/generate", response_model=ModelResponse, status_code=202)
def generate_model(
    payload: GenerateModelRequest,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
):
    found = list(
        db.scalars(select(ImageAsset.id).where(ImageAsset.id.in_(payload.image_ids)))
    )
    if len(found) != len(set(payload.image_ids)):
        raise HTTPException(404, "One or more images were not found")
    provider = (payload.provider or settings.three_d_provider).lower()
    if provider not in {"mock", "meshy"}:
        raise HTTPException(400, "Provider must be mock or meshy")
    job = Job(
        kind="generate_3d",
        status=JobStatus.queued,
        input_payload=payload.model_dump(mode="json"),
    )
    db.add(job)
    db.flush()
    model = ModelAsset(job_id=job.id, provider=provider)
    db.add(model)
    db.commit()
    db.refresh(model)
    options = {
        "pose_mode": payload.pose_mode,
        "target_polycount": payload.target_polycount,
        "should_texture": payload.should_texture,
        "enable_pbr": payload.enable_pbr,
    }
    generate_model_task.delay(model.id, payload.image_ids, options)
    return _model_response(model, settings)


@router.get("/jobs/{job_id}", response_model=JobResponse)
def get_job(job_id: str, db: Session = Depends(get_db)):
    job = db.get(Job, job_id)
    if not job:
        raise HTTPException(404, "Job not found")
    return job


@router.get("/models/{model_id}", response_model=ModelResponse)
def get_model(
    model_id: str,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
):
    model = db.get(ModelAsset, model_id)
    if not model:
        raise HTTPException(404, "Model not found")
    return _model_response(model, settings)


@router.get("/models/{model_id}/download")
def download_model(
    model_id: str,
    variant: str = Query(default="processed", pattern="^(raw|processed)$"),
    db: Session = Depends(get_db),
):
    model = db.get(ModelAsset, model_id)
    if not model:
        raise HTTPException(404, "Model not found")
    selected = (
        model.raw_model_path if variant == "raw" else model.processed_model_path
    )
    if not selected or not Path(selected).is_file():
        raise HTTPException(404, f"{variant.title()} model is not available")
    return FileResponse(
        selected,
        media_type="model/gltf-binary",
        filename=f"{model.id}-{variant}.glb",
    )


@router.post("/models/{model_id}/process", response_model=JobResponse, status_code=202)
def process_model(
    model_id: str,
    payload: ProcessModelRequest,
    db: Session = Depends(get_db),
):
    model = db.get(ModelAsset, model_id)
    if not model or not model.raw_model_path:
        raise HTTPException(404, "Raw model is not available")
    job = Job(
        kind="process_model",
        status=JobStatus.queued,
        input_payload={"model_id": model_id, **payload.model_dump()},
    )
    db.add(job)
    db.commit()
    db.refresh(job)
    from app.workers.blender_tasks import process_model_task

    process_model_task.delay(job.id, model_id, payload.model_dump())
    return job

