"""Public exception types and FastAPI error mapping."""

from __future__ import annotations

import logging
from fastapi.exceptions import RequestValidationError

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from audit_ai.logging import log_event, safe_reason_code
from audit_ai.schemas import ErrorEnvelope


LOGGER = logging.getLogger("audit_ai.errors")


class AuditAiError(RuntimeError):
    """An expected service error with a stable, safe external representation."""

    def __init__(self, status_code: int, code: str, public_message: str,
                 data: object | None = None) -> None:
        super().__init__(code)
        self.status_code = status_code
        self.code = code
        self.public_message = public_message
        self.data = data


def _request_id(request: Request) -> str:
    return getattr(request.state, "request_id", "unavailable")


def _route_template(request: Request) -> str:
    return getattr(request.scope.get("route"), "path", "UNMATCHED")


def _response(
    *, status_code: int, code: str, message: str, request_id: str,
    data: object | None = None,
) -> JSONResponse:
    envelope = ErrorEnvelope(
        code=code, message=message, data=data, requestId=request_id
    )
    return JSONResponse(
        status_code=status_code,
        content=envelope.model_dump(by_alias=True),
        headers={"X-Request-Id": request_id},
    )


def install_error_handlers(app: FastAPI) -> None:
    """Install expected and fail-closed unexpected exception handlers."""

    @app.exception_handler(AuditAiError)
    async def audit_ai_error_handler(
        request: Request, error: AuditAiError
    ) -> JSONResponse:
        request_id = _request_id(request)
        request.state.safe_reason_code = safe_reason_code(error.code)
        log_event(
            LOGGER,
            "request rejected",
            request_id=request_id,
            operation=_route_template(request),
            outcome=error.code,
            http_status=error.status_code,
        )
        return _response(
            status_code=error.status_code,
            code=error.code,
            message=error.public_message,
            request_id=request_id,
            data=error.data,
        )

    @app.exception_handler(Exception)
    async def unexpected_error_handler(
        request: Request, _error: Exception
    ) -> JSONResponse:
        request_id = _request_id(request)
        request.state.safe_reason_code = "INTERNAL_ERROR"
        log_event(
            LOGGER,
            "request failed",
            request_id=request_id,
            operation=_route_template(request),
            outcome="INTERNAL_ERROR",
            error_class=type(_error).__name__,
        )
        return _response(
            status_code=500,
            code="INTERNAL_ERROR",
            message="internal service error",
            request_id=request_id,
        )
    @app.exception_handler(RequestValidationError)
    async def validation_error_handler(request: Request, error: RequestValidationError) -> JSONResponse:
        request.state.safe_reason_code = safe_reason_code("REQUEST_VALIDATION_FAILED")
        log_event(LOGGER, "REQUEST_SCHEMA_REJECTED", request_id=_request_id(request),
                  stage="HTTP_VALIDATION", outcome="REQUEST_VALIDATION_FAILED",
                  validation_error_count=len(error.errors()), http_status=422)
        # FastAPI's default errors include the input values; never echo submitted
        # business text in error responses collected by deployment diagnostics.
        return _response(status_code=422, code="REQUEST_VALIDATION_FAILED",
                         message="request schema validation failed", request_id=_request_id(request))
