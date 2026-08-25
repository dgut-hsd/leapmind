from __future__ import annotations

import logging
import subprocess
from pathlib import Path

from celery import shared_task

from app.core.config import get_settings
from app.core.database import SessionLocal
from app.models import Job, JobStatus, ModelAsset

logger = logging.getLogger(__name__)


@shared_task(bind=True)
def process_model_task(self, job_id: str, model_id: str, options: dict):
    settings = get_settings()
    with SessionLocal() as db:
        job = db.get(Job, job_id)
        model = db.get(ModelAsset, model_id)
        if not job or not model or not model.raw_model_path:
            raise ValueError("Processing job, model or raw model is missing")
        job.transition(JobStatus.processing, progress=10)
        model.status = JobStatus.processing
        db.commit()
        output_path = settings.models_dir / model.id / "processed.glb"
        script_path = Path(__file__).resolve().parents[2] / "blender" / "cleanup_model.py"
        command = [
            settings.blender_executable,
            "--background",
            "--python",
            str(script_path),
            "--",
            "--input",
            str(Path(model.raw_model_path).resolve()),
            "--output",
            str(output_path.resolve()),
            "--min-fragment-ratio",
            str(options.get("min_fragment_ratio", settings.blender_min_fragment_ratio)),
            "--decimate-face-count",
            str(options.get("decimate_face_count", settings.blender_decimate_face_count)),
        ]
        if options.get("triangulate", settings.blender_triangulate):
            command.append("--triangulate")
        try:
            completed = subprocess.run(
                command,
                check=True,
                capture_output=True,
                text=True,
                timeout=settings.blender_timeout_seconds,
            )
            if not output_path.is_file():
                raise RuntimeError("Blender completed without producing a GLB")
            model.processed_model_path = str(output_path)
            model.status = JobStatus.completed
            job.transition(JobStatus.completed, progress=100)
            job.result = {
                "model_id": model.id,
                "processed_model_path": str(output_path),
                "blender_output": completed.stdout[-4000:],
            }
            db.commit()
            return job.result
        except Exception as exc:
            logger.exception("Blender processing failed for model %s", model.id)
            message = str(exc)[:4000]
            model.status = JobStatus.failed
            model.error = message
            job.transition(JobStatus.failed)
            job.error = message
            db.commit()
            raise

