"""Bounded multipart upload handling for OCR extraction."""

from __future__ import annotations

import asyncio
from pathlib import Path
import tempfile
import logging
import time
from typing import BinaryIO

from anyio import CancelScope
from fastapi import UploadFile
from starlette.concurrency import run_in_threadpool

from audit_ai.config import Settings
from audit_ai.errors import AuditAiError
from audit_ai.logging import log_event
from audit_ai.ocr.paddle_adapter import (
    OcrPredictionInvalid,
    OcrRuntimeUnavailable,
)
from audit_ai.ocr.port import OcrExtraction, OcrPort
from audit_ai.ocr.process_adapter import OcrWorkerReapUnproven


_CHUNK_BYTES = 64 * 1024
_MEDIA_SUFFIX = {
    "application/pdf": ".pdf",
    "image/png": ".png",
    "image/jpeg": ".jpg",
}


class OcrApplicationService:
    def __init__(self, settings: Settings, port: OcrPort) -> None:
        self._settings = settings
        self._port = port

    async def extract(self, upload: UploadFile) -> OcrExtraction:
        started = time.monotonic_ns()
        logger = logging.getLogger("audit_ai.ocr")
        media_type = (upload.content_type or "").split(";", 1)[0].strip().lower()
        suffix = _MEDIA_SUFFIX.get(media_type)
        if suffix is None:
            await upload.close()
            raise AuditAiError(
                415,
                "OCR_UNSUPPORTED_MEDIA_TYPE",
                "only PDF, PNG, JPG, and JPEG are supported",
            )
        try:
            # Multipart parsing is complete before this service is entered.
            # Keep the entire blocking file lifecycle off the event loop,
            # including slow storage writes and temporary-directory removal.
            # Shield the actual worker task as well as AnyIO cancellation:
            # raw asyncio Task.cancel() otherwise abandons the worker and
            # closes its upload source while it is still reading it.
            worker = asyncio.create_task(
                run_in_threadpool(
                    self._extract_uploaded, upload.file, suffix, started, logger
                )
            )
            try:
                return await asyncio.shield(worker)
            except asyncio.CancelledError:
                with CancelScope(shield=True):
                    while not worker.done():
                        try:
                            await asyncio.shield(worker)
                        except asyncio.CancelledError:
                            continue
                if not worker.cancelled():
                    worker.exception()  # Retrieve any failure before propagating cancellation.
                raise
        finally:
            with CancelScope(shield=True):
                await upload.close()

    def _extract_uploaded(
        self, source: BinaryIO, suffix: str, started: int, logger: logging.Logger
    ) -> OcrExtraction:
        try:
            log_event(logger, "OCR_START", stage="OCR", outcome="START", media_kind=suffix[1:],
                      byte_limit=self._settings.max_upload_bytes)
            owner = tempfile.TemporaryDirectory(
                prefix="audit-ai-ocr-", dir=self._settings.ocr_temp_root
            )
            cleanup_safe = True
            try:
                path = Path(owner.name) / f"upload{suffix}"
                self._copy_bounded(source, path)
                log_event(logger, "OCR_UPLOAD_READY", stage="OCR_STORAGE", input_bytes=path.stat().st_size)
                try:
                    result = self._port.extract(path)
                    empty_pages = sum(not page.text.strip() for page in result.pages)
                    log_event(logger, "OCR_END", stage="OCR", outcome="SUCCESS", page_count=len(result.pages),
                              input_chars=len(result.text), empty_page_count=empty_pages,
                              duration_ms=(time.monotonic_ns()-started)//1_000_000)
                    return result
                except OcrWorkerReapUnproven as failure:
                    # An unproved live child must never lose its input. Quarantine
                    # this private transient directory; no automatic replay/cleanup.
                    # Python3.11 TemporaryDirectory has no public delete=False.
                    cleanup_safe = False
                    owner._finalizer.detach()
                    raise AuditAiError(
                        503, "OCR_RUNTIME_UNAVAILABLE", "OCR worker exit is unproven"
                    ) from failure
                except OcrPredictionInvalid as failure:
                    raise AuditAiError(
                        502, "OCR_INVALID_RESULT", "OCR service returned an invalid result"
                    ) from failure
                except OcrRuntimeUnavailable as failure:
                    raise AuditAiError(
                        503, "OCR_RUNTIME_UNAVAILABLE", "OCR runtime is unavailable"
                    ) from failure
                except AuditAiError:
                    raise
                except Exception as failure:
                    raise AuditAiError(
                        502, "OCR_EXECUTION_FAILED", "OCR execution failed"
                    ) from failure
            finally:
                if cleanup_safe:
                    owner.cleanup()
        except Exception as error:
            log_event(logger, "OCR_END", stage="OCR", outcome=getattr(error,"code","INTERNAL_ERROR"),
                      error_class=type(error).__name__, duration_ms=(time.monotonic_ns()-started)//1_000_000)
            raise

    def _copy_bounded(self, source: BinaryIO, path: Path) -> None:
        total = 0
        with path.open("xb") as destination:
            while True:
                chunk = source.read(_CHUNK_BYTES)
                if not chunk:
                    break
                total += len(chunk)
                if total > self._settings.max_upload_bytes:
                    raise AuditAiError(
                        413,
                        "UPLOAD_TOO_LARGE",
                        "upload exceeds configured limit",
                    )
                destination.write(chunk)
        if total == 0:
            raise AuditAiError(400, "EMPTY_UPLOAD", "upload must not be empty")
