import logging

from fastapi import FastAPI
from fastapi.testclient import TestClient

from audit_ai.errors import AuditAiError
from audit_ai.main import create_app


def test_known_error_has_stable_safe_contract_and_request_diagnostics(caplog) -> None:
    caplog.set_level(logging.INFO, logger="audit_ai.http")
    app = create_app()

    @app.get("/_test/known-error")
    def raise_known_error() -> None:
        raise AuditAiError(413, "UPLOAD_TOO_LARGE", "upload exceeds configured limit")

    response = TestClient(app).get(
        "/_test/known-error", headers={"X-Request-Id": "req-known-1"}
    )

    assert response.status_code == 413
    assert response.json() == {
        "code": "UPLOAD_TOO_LARGE",
        "message": "upload exceeds configured limit",
        "data": None,
        "requestId": "req-known-1",
    }
    record = next(
        item for item in caplog.records
        if item.getMessage().startswith("AI_HTTP_COMPLETED")
    )
    assert record.audit_fields == {
        "diagnostic_schema_version": 1,
        "module": "AUDIT_AI",
        "layer": "HTTP",
        "operation": "HTTP_REQUEST",
        "stage": "AI_HTTP",
        "outcome": "HTTP_RESPONSE_RECEIVED",
        "reason_code": "UPLOAD_TOO_LARGE",
        "request_id": "req-known-1",
        "method": "GET",
        "route_template": "/_test/known-error",
        "status_code": 413,
        "duration_ms": record.audit_fields["duration_ms"],
    }
    assert isinstance(record.audit_fields["duration_ms"], int)


def test_unexpected_error_does_not_expose_exception_text(caplog) -> None:
    caplog.set_level(logging.INFO, logger="audit_ai.http")
    app: FastAPI = create_app()

    @app.get("/_test/unexpected-error")
    def raise_unexpected_error() -> None:
        raise RuntimeError("secret-token-value")

    response = TestClient(app, raise_server_exceptions=False).get(
        "/_test/unexpected-error"
    )

    assert response.status_code == 500
    assert response.json()["code"] == "INTERNAL_ERROR"
    assert "secret-token-value" not in response.text
    assert "secret-token-value" not in caplog.text
    assert response.json()["requestId"] == response.headers["X-Request-Id"]
    record = next(
        item for item in caplog.records
        if item.getMessage().startswith("AI_HTTP_COMPLETED")
    )
    assert record.audit_fields["reason_code"] == "INTERNAL_ERROR"
    assert record.audit_fields["status_code"] == 500


def test_unknown_public_error_code_does_not_break_response_or_logging(caplog) -> None:
    caplog.set_level(logging.INFO, logger="audit_ai.http")
    app = create_app()

    @app.get("/_test/future-error")
    def raise_future_error() -> None:
        raise AuditAiError(409, "FUTURE_PUBLIC_CODE", "safe public message")

    response = TestClient(app).get("/_test/future-error")

    assert response.status_code == 409
    assert response.json()["code"] == "FUTURE_PUBLIC_CODE"
    record = next(
        item for item in caplog.records
        if item.getMessage().startswith("AI_HTTP_COMPLETED")
    )
    assert record.audit_fields["reason_code"] == "INTERNAL_ERROR"
