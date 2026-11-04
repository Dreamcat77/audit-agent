"""OCR HTTP route and response mapping."""

from __future__ import annotations

from fastapi import APIRouter, File, UploadFile
from pydantic import BaseModel, ConfigDict, Field

from audit_ai.config import Settings
from audit_ai.ocr.port import OcrExtraction, OcrPort
from audit_ai.ocr.service import OcrApplicationService


class OcrPageResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    page_num: int = Field(alias="pageNum")
    text: str
    confidence: float


class OcrDataResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    text: str
    confidence: float
    pages: list[OcrPageResponse]
    engine: str
    duration_ms: int = Field(alias="durationMs")


class OcrEnvelope(BaseModel):
    code: int
    message: str
    data: OcrDataResponse


def _response(extraction: OcrExtraction) -> OcrEnvelope:
    return OcrEnvelope(
        code=200,
        message="success",
        data=OcrDataResponse(
            text=extraction.text,
            confidence=extraction.confidence,
            pages=[
                OcrPageResponse(
                    pageNum=page.page_num,
                    text=page.text,
                    confidence=page.confidence,
                )
                for page in extraction.pages
            ],
            engine=extraction.engine,
            durationMs=extraction.duration_ms,
        ),
    )


def create_ocr_router(settings: Settings, port: OcrPort) -> APIRouter:
    router = APIRouter()
    service = OcrApplicationService(settings, port)

    @router.post("/ai/v1/ocr/extract", response_model=OcrEnvelope)
    async def extract_ocr(file: UploadFile = File(...)) -> OcrEnvelope:
        return _response(await service.extract(file))

    return router
