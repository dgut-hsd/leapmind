import logging
from fastapi import APIRouter
from ..models.schemas import OCRProcessRequest, OCRProcessResponse
from ..services.ocr_processor import OCRProcessor

logger = logging.getLogger(__name__)
router = APIRouter(tags=["LeapMind - OCR"])
ocr_processor = OCRProcessor()


@router.post("/api/internal/ai/ocr/process", response_model=OCRProcessResponse)
async def process_ocr(request: OCRProcessRequest):
    logger.info(f"OCR process request received, rawText length: {len(request.rawText)}")
    result = ocr_processor.process(request.rawText, request.subject)
    return result
