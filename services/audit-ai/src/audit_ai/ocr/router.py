"""OCR HTTP route and response mapping."""

from __future__ import annotations

from fastapi import APIRouter, File, UploadFile, Request
import asyncio
from threading import Event
from pydantic import BaseModel, ConfigDict, Field

from audit_ai.config import Settings
from audit_ai.ocr.port import OcrExtraction, OcrPort
from audit_ai.ocr.service import OcrApplicationService


class OcrPageResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    page_num: int = Field(alias="pageNum")
    text: str
    confidence: float
    regions: list[dict] | None = None
    width: int | None = None
    height: int | None = None


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
                    regions=[{"start":len(page.text[:r.start].encode("utf-16-le"))//2,
                              "end":len(page.text[:r.end].encode("utf-16-le"))//2,
                              "polygon":r.polygon,"confidence":r.confidence,"verification":r.verification}
                             for r in page.regions] if page.regions and page.width and page.height else None,
                    width=page.width or None, height=page.height or None,
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

    @router.post("/ai/v1/ocr/extract", response_model=OcrEnvelope, response_model_exclude_none=True)
    async def extract_ocr(request: Request, file: UploadFile = File(...)) -> OcrEnvelope:
        from audit_ai.ocr.cancellation import CURRENT
        event = Event()
        token = CURRENT.set(event)
        async def observe_disconnect():
            while not event.is_set():
                # Multipart has already been consumed. Await the disconnect
                # message: is_disconnected() polls inside an immediately
                # cancelled scope, which cannot traverse BaseHTTPMiddleware's
                # asynchronous receive wrapper reliably.
                message = await request.receive()
                if message['type'] == 'http.disconnect':
                    event.set()
                    return
        observer = asyncio.create_task(observe_disconnect())
        try:
            return _response(await service.extract(file))
        finally:
            observer.cancel()
            try: await observer
            except asyncio.CancelledError: pass
            CURRENT.reset(token)

    return router
