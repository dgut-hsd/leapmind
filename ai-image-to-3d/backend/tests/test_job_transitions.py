import pytest

from app.models import Job, JobStatus


def test_valid_job_transition():
    job = Job(kind="test", status=JobStatus.queued)
    job.transition(JobStatus.generating_3d, progress=25)
    job.transition(JobStatus.completed, progress=100)
    assert job.status is JobStatus.completed
    assert job.progress == 100


def test_invalid_job_transition_is_rejected():
    job = Job(kind="test", status=JobStatus.failed)
    with pytest.raises(ValueError, match="Invalid job transition"):
        job.transition(JobStatus.completed)


def test_progress_is_clamped():
    job = Job(kind="test", status=JobStatus.queued)
    job.transition(JobStatus.generating_image, progress=120)
    assert job.progress == 100

