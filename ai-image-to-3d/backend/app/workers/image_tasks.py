from __future__ import annotations

import logging
import uuid

from celery import shared_task

from app.core.config import get_settings
from app.core.database import SessionLocal
from app.models import ImageAsset, Job, JobStatus
from app.providers.image.openai_provider import OpenAIImageProvider
from app.services.image_preprocessor import ImagePreprocessor

logger = logging.getLogger(__name__)


@shared_task(
    bind=True,
    autoretry_for=(ConnectionError, TimeoutError),
    retry_backoff=True,
    retry_jitter=True,
    max_retries=3,
)
def generate_reference_images_task(self, job_id: str):
    settings = get_settings()
    with SessionLocal() as db:
        job = db.get(Job, job_id)
        if not job:
            raise ValueError(f"Job {job_id} does not exist")
        try:
            job.transition(JobStatus.generating_image, progress=1)
            db.commit()
            provider = OpenAIImageProvider(settings)
            prompt = str(job.input_payload["prompt"])
            views = list(job.input_payload.get("views") or ["front"])
            preprocessor = ImagePreprocessor(
                max_bytes=50 * 1024 * 1024,
                target_size=settings.image_target_size,
                remove_background=settings.remove_background,
            )
            image_ids: list[str] = []
            for index, view in enumerate(views):
                generated = provider.generate_reference(prompt, view)
                image_id = str(uuid.uuid4())
                original_path = settings.generated_dir / f"{image_id}-original.png"
                processed_path = settings.generated_dir / f"{image_id}.png"
                original_path.write_bytes(generated.content)
                processed = preprocessor.process_bytes(
                    generated.content, processed_path
                )
                asset = ImageAsset(
                    id=image_id,
                    job_id=job.id,
                    source_type="generated",
                    view=view,
                    original_path=str(original_path),
                    processed_path=str(processed.path),
                    mime_type=processed.mime_type,
                    width=processed.width,
                    height=processed.height,
                )
                db.add(asset)
                image_ids.append(image_id)
                job.progress = 5 + ((index + 1) / len(views)) * 90
                db.commit()
            job.transition(JobStatus.completed, progress=100)
            job.result = {"image_ids": image_ids}
            db.commit()
            return job.result
        except Exception as exc:
            logger.exception("Image generation failed for job %s", job.id)
            job.transition(JobStatus.failed)
            job.error = str(exc)[:4000]
            db.commit()
            raise

