"""
LeapMind AI Service - Standalone FastAPI application
Runs on port 8001, only accessible internally (Java backend calls this)
"""

import logging
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from .api.ocr_process import router as ocr_router
from .api.ai_generate_stream import router as ai_router

# Force reload AI config from .env on startup
from ..core.config import reload_ai_config
from ..ai.providers import _provider_manager
reload_ai_config()
_provider_manager.clear_cache()

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(
    title="LeapMind AI Service",
    description="AI微服务 - 提供OCR后处理、AI答疑、AI讲题等能力",
    version="0.1.0",
    docs_url="/docs",
    redoc_url="/redoc"
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(ocr_router)
app.include_router(ai_router)


@app.get("/health")
async def health_check():
    return {"status": "healthy", "service": "LeapMind AI Service"}


@app.get("/")
async def root():
    return {
        "service": "LeapMind AI Service",
        "version": "0.1.0",
        "docs": "/docs"
    }
