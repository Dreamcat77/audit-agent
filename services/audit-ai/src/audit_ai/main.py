"""Import-safe FastAPI application factory."""

from __future__ import annotations

import re
import logging
import time
from uuid import uuid4
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from starlette.concurrency import run_in_threadpool

from audit_ai.config import Settings
from audit_ai.errors import install_error_handlers
from audit_ai.logging import configure_runtime_logging, diagnostic_context, log_event
from audit_ai.model.gateway import ModelGatewayPort, OpenAiCompatibleModelGateway
from audit_ai.model.router import create_model_router
from audit_ai.model.document_role import create_document_role_router
from audit_ai.model.contract_fact import create_contract_fact_router
from audit_ai.model.material_fields import create_material_fields_router
from audit_ai.model.case_review import create_case_review_router
from audit_ai.model.contract_analysis import create_contract_analysis_router
from audit_ai.ocr.process_adapter import ProcessOcrAdapter, create_ocr_port
from audit_ai.ocr.port import OcrPort
from audit_ai.ocr.router import create_ocr_router


REQUEST_ID_HEADER = "X-Request-Id"
_SAFE_REQUEST_ID = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
LOGGER = logging.getLogger("audit_ai.http")


def _request_id(candidate: str | None) -> str:
    if candidate is not None and _SAFE_REQUEST_ID.fullmatch(candidate):
        return candidate
    return str(uuid4())


def create_app(
    settings: Settings | None = None,
    *,
    ocr_port: OcrPort | None = None,
    model_gateway: ModelGatewayPort | None = None,
) -> FastAPI:
    """Create the application without importing optional Paddle/model packages."""

    configure_runtime_logging()
    effective_settings = settings or Settings.from_env()
    effective_ocr = ocr_port if ocr_port is not None else create_ocr_port(effective_settings)

    @asynccontextmanager
    async def lifespan(_app):
        try:
            yield
        finally:
            if isinstance(effective_ocr, ProcessOcrAdapter):
                await run_in_threadpool(effective_ocr.close)

    app = FastAPI(title="audit-ai", version="0.1.0", lifespan=lifespan)
    app.state.settings = effective_settings
    app.state.ocr_port = effective_ocr

    @app.middleware("http")
    async def request_id_middleware(request: Request, call_next):
        started = time.perf_counter_ns()
        request_id = _request_id(request.headers.get(REQUEST_ID_HEADER))
        request.state.request_id = request_id
        # Do not record raw paths/query strings, headers, cookies or multipart names.
        safe_operation = request.url.path if request.url.path in {
            "/health", "/ai/v1/ocr/extract", "/ai/v1/materials/extract-fields",
            "/ai/v1/agent/contract-text-analysis", "/ai/v1/agent/budget-semantic-analysis",
            "/ai/v1/agent/document-role", "/ai/v1/agent/contract-fact-candidates",
        } else "OTHER_ROUTE"
        with diagnostic_context(request_id=request_id):
            if safe_operation != "/health":
                log_event(
                    LOGGER, "AI_HTTP_IN", stage="HTTP", operation=safe_operation,
                    outcome="START",
                )
            try:
                response = await call_next(request)
            except Exception as error:
                duration_ms = max(
                    0, (time.perf_counter_ns() - started) // 1_000_000
                )
                log_event(
                    LOGGER, "AI_HTTP_OUT", level=logging.WARNING, stage="HTTP",
                    operation=safe_operation, outcome="INTERNAL_ERROR",
                    http_status=500, error_class=type(error).__name__,
                    duration_ms=duration_ms,
                )
                route = request.scope.get("route")
                route_template = getattr(route, "path", "UNMATCHED")
                log_event(
                    LOGGER,
                    "AI_HTTP_COMPLETED",
                    level=logging.WARNING,
                    diagnostic_schema_version=1,
                    module="AUDIT_AI",
                    layer="HTTP",
                    operation="HTTP_REQUEST",
                    stage="AI_HTTP",
                    outcome="RESULT_INVALID",
                    reason_code="INTERNAL_ERROR",
                    request_id=request_id,
                    method=request.method,
                    route_template=route_template,
                    status_code=500,
                    duration_ms=duration_ms,
                )
                # Do not let Uvicorn print an unredacted chained exception.
                return JSONResponse(
                    status_code=500,
                    content={
                        "code": "INTERNAL_ERROR",
                        "message": "internal service error",
                        "data": None,
                        "requestId": request_id,
                    },
                    headers={REQUEST_ID_HEADER: request_id},
                )

            duration_ms = max(
                0, (time.perf_counter_ns() - started) // 1_000_000
            )
            if safe_operation != "/health":
                log_event(
                    LOGGER, "AI_HTTP_OUT",
                    level=logging.WARNING if response.status_code >= 500 else logging.INFO,
                    stage="HTTP", operation=safe_operation,
                    outcome="SUCCESS" if response.status_code < 400 else "FAILED",
                    http_status=response.status_code, duration_ms=duration_ms,
                )
            response.headers[REQUEST_ID_HEADER] = request_id
            route = request.scope.get("route")
            route_template = getattr(route, "path", "UNMATCHED")
            reason_code = getattr(request.state, "safe_reason_code", "NONE")
            log_event(
                LOGGER,
                "AI_HTTP_COMPLETED",
                level=logging.WARNING if response.status_code >= 500 else logging.INFO,
                diagnostic_schema_version=1,
                module="AUDIT_AI",
                layer="HTTP",
                operation="HTTP_REQUEST",
                stage="AI_HTTP",
                outcome="HTTP_RESPONSE_RECEIVED",
                reason_code=reason_code,
                request_id=request_id,
                method=request.method,
                route_template=route_template,
                status_code=response.status_code,
                duration_ms=duration_ms,
            )
            return response

    install_error_handlers(app)
    app.include_router(
        create_ocr_router(
            effective_settings, effective_ocr
        )
    )
    effective_gateway = model_gateway or OpenAiCompatibleModelGateway(effective_settings)
    app.include_router(create_model_router(effective_settings, effective_gateway))
    app.include_router(create_document_role_router(effective_settings, effective_gateway))
    app.include_router(create_contract_fact_router(effective_settings, effective_gateway))
    app.include_router(create_material_fields_router(effective_gateway, effective_settings))
    app.include_router(create_case_review_router(effective_gateway))
    app.include_router(create_contract_analysis_router(effective_gateway))

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "UP", "service": "audit-ai"}

    return app

app = create_app()
