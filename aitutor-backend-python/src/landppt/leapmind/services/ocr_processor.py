import re
import json
import logging
from typing import Optional
from ..models.schemas import StructuredQuestion, QuestionType

logger = logging.getLogger(__name__)


class OCRProcessor:
    def __init__(self, ai_client=None):
        self.ai_client = ai_client

    def process(self, raw_text: str, subject: str = None) -> dict:
        cleaned_text = self._denoise(raw_text)
        structured = self._recognize_structure(cleaned_text, subject)
        confidence = self._evaluate_confidence(cleaned_text, structured)

        if confidence < 0.7 and self.ai_client:
            try:
                structured = self._ai_correction(cleaned_text, subject)
                confidence = max(confidence, 0.85)
            except Exception as e:
                logger.warning(f"AI correction failed: {e}")

        return {
            "recognizedText": cleaned_text,
            "structuredQuestion": structured,
            "confidence": confidence
        }

    def _denoise(self, text: str) -> str:
        text = re.sub(r'\s+', ' ', text).strip()
        noise = re.compile(r'[#@$%^&*~{}`_=+|\\:;\"\'<>]')
        text = noise.sub('', text)
        return text

    def _recognize_structure(self, text: str, subject: str) -> StructuredQuestion:
        options = None
        q_type = QuestionType.SHORT_ANSWER
        stem = text

        option_items = self._extract_options(text)
        if option_items:
            options = [item['text'] for item in option_items]
            stem = text[:option_items[0]['start']].strip()
            if not stem:
                stem = text
            q_type = QuestionType.SINGLE_CHOICE if len(options) <= 6 else QuestionType.MULTI_CHOICE
        elif re.search(r'[（(]\s*[　\s]*[）)]|_{3,}|__+', text):
            q_type = QuestionType.FILL_BLANK

        return StructuredQuestion(
            stem=stem,
            options=options,
            type=q_type,
            subject=subject or "unknown"
        )

    def _extract_options(self, text: str) -> list:
        items = []
        for m in re.finditer(r'(?:^|\s)([A-Da-d])\s*[.、)）]\s*([^\n]{1,60}?)(?=\s+[A-Da-d]\s*[.、)）]|\s*$)', text):
            items.append({
                'label': m.group(1).upper(),
                'text': m.group(2).strip(),
                'start': m.start()
            })
        return items

    def _evaluate_confidence(self, text: str, structured: StructuredQuestion) -> float:
        confidence = 0.5
        if 5 <= len(structured.stem) <= 500:
            confidence += 0.2
        if structured.options and len(structured.options) >= 2:
            confidence += 0.15
        if structured.subject == "math" and re.search(r'[∠°√∑∏∫π=<>⊥∥△]', text):
            confidence += 0.1
        garbage = re.search(r'[#@$%^&*~{}]', text)
        if not garbage:
            confidence += 0.05
        return min(confidence, 1.0)

    def _ai_correction(self, text: str, subject: str) -> StructuredQuestion:
        prompt = f"""Please organize the following OCR text into a structured question.

OCR text: {text}
Subject: {subject or 'unknown'}

Return JSON format:
{{
  "stem": "question content",
  "options": ["option A content", "option B content", "option C content", "option D content"],
  "type": "single_choice|multi_choice|fill_blank|short_answer|essay",
  "subject": "{subject or 'unknown'}"
}}

Rules:
1. Correct OCR recognition errors
2. Use standard math symbols
3. If no options, set options to null
4. Return ONLY valid JSON, no other text
"""
        raw = self.ai_client.generate(prompt)
        cleaned = re.sub(r'^```(?:json)?\s*|\s*```$', '', raw.strip())
        data = json.loads(cleaned)
        return StructuredQuestion(**data)
