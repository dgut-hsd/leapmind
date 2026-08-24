from __future__ import annotations

import logging
from pathlib import Path

from celery import shared_task
from sqlalchemy import select

from app.core.config import get_settings
from app.core.database import SessionLocal
from app.models import ImageAsset, Job, JobStatus, ModelAsset
from app.providers.three_d.base import ProviderTask
from app.services.model_pipeline import ModelPipeline, build_three_d_provider

logger = logging.getLogger(__name__)


@shared_task(
    bind=True,
    autoretry_for=(ConnectionError, TimeoutError),
    retry_backoff=True,
    retry_jitter=True,
    max_retries=3,
)
def generate_model_task(self, model_id: str, image_ids: list[str], options: dict):
    settings = get_settings()
    with SessionLocal() as db:
        model = db.get(ModelAsset, model_id)
        if not model:
            raise ValueError(f"Model {model_id} does not exist")
        job = db.get(Job, model.job_id)
        if not job:
            raise ValueError(f"Job {model.job_id} does not exist")
        unordered_images = list(
            db.scalars(select(ImageAsset).where(ImageAsset.id.in_(image_ids)))
        )
        if len(unordered_images) != len(set(image_ids)):
            raise ValueError("One or more image assets do not exist")
        images_by_id = {image.id: image for image in unordered_images}
        images = [images_by_id[image_id] for image_id in image_ids]
        image_paths = [Path(image.processed_path) for image in images]
        provider = build_three_d_provider(model.provider, settings)
        pipeline = ModelPipeline(provider, settings)
        destination = settings.models_dir / model.id

        def on_update(task: ProviderTask) -> None:
            model.provider_task_id = task.id
            model.provider_payload = task.raw
            job.provider_task_id = task.id
            job.transition(JobStatus.generating_3d, progress=max(5, task.progress * 0.9))
            model.status = JobStatus.generating_3d
            db.commit()

        try:
            task_id, downloaded, raw = pipeline.run(
                image_paths, destination, options=options, on_update=on_update
            )
            model.provider_task_id = task_id
            model.provider_payload = raw
            model.raw_model_path = str(downloaded.model_path)
            if downloaded.texture_paths:
                model.texture_dir = str(downloaded.texture_paths[0].parent)
            model.status = JobStatus.completed
            job.transition(JobStatus.completed, progress=100)
            job.result = {"model_id": model.id}
            db.commit()
            return {"model_id": model.id}
        except Exception as exc:
            logger.exception("3D generation failed for model %s", model.id)
            message = str(exc)[:4000]
            model.status = JobStatus.failed
            model.error = message
            job.transition(JobStatus.failed)
            job.error = message
            db.commit()
            raise
