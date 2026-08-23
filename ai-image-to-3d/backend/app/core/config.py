from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env", env_file_encoding="utf-8", extra="ignore"
    )

    app_env: str = "development"
    secret_key: str = "development-only-change-me"
    api_base_url: str = "http://localhost:8000"
    cors_origins: str = "http://localhost:3000"
    data_dir: Path = Path("./data")
    database_url: str = "sqlite:///./data/app.db"

    celery_broker_url: str = "redis://localhost:6379/0"
    celery_result_backend: str = "redis://localhost:6379/1"
    celery_task_always_eager: bool = False

    openai_api_key: str | None = None
    openai_image_model: str = "gpt-image-2"
    openai_image_quality: str = "medium"
    openai_image_size: str = "1024x1024"
    openai_timeout_seconds: float = 120
    openai_max_retries: int = 3

    three_d_provider: str = "mock"
    meshy_api_key: str | None = None
    meshy_base_url: str = "https://api.meshy.ai"
    meshy_timeout_seconds: float = 60
    meshy_poll_interval_seconds: float = 10
    meshy_max_wait_seconds: int = 1800

    max_upload_bytes: int = 10 * 1024 * 1024
    image_target_size: int = 1024
    remove_background: bool = True

    blender_executable: str = "blender"
    blender_timeout_seconds: int = 900
    blender_min_fragment_ratio: float = 0.001
    blender_decimate_face_count: int = 0
    blender_triangulate: bool = False

    @property
    def cors_origin_list(self) -> list[str]:
        return [item.strip() for item in self.cors_origins.split(",") if item.strip()]

    @property
    def uploads_dir(self) -> Path:
        return self.data_dir / "uploads"

    @property
    def generated_dir(self) -> Path:
        return self.data_dir / "generated"

    @property
    def models_dir(self) -> Path:
        return self.data_dir / "models"

    def ensure_directories(self) -> None:
        for path in (
            self.data_dir,
            self.uploads_dir,
            self.generated_dir,
            self.models_dir,
        ):
            path.mkdir(parents=True, exist_ok=True)


@lru_cache
def get_settings() -> Settings:
    settings = Settings()
    settings.ensure_directories()
    return settings
