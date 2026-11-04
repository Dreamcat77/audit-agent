"""Provider error capture must never disclose or infer the provider's message."""
import hashlib
import json
import logging

import httpx
import pytest
from fastapi.testclient import TestClient

from audit_ai.config import Settings
from audit_ai.main import create_app
from audit_ai.model.gateway import ModelGatewayFailure, OpenAiCompatibleModelGateway


REPEAT = "prediction aborted, token repeat limit reached"


@pytest.mark.parametrize("body,kind", [
    (json.dumps({"error": {"message": REPEAT}}).encode(), "TOKEN_REPEAT_LIMIT"),
    (json.dumps({"error": {"message": REPEAT + " BUSINESS_CANARY"}}).encode(), "OTHER"),
    (json.dumps({"note": REPEAT, "error": {"message": "BUSINESS_CANARY"}}).encode(), "OTHER"),
    (json.dumps({"error": REPEAT}).encode(), "OTHER"),
    (b'{"error":{"message":"' + REPEAT.encode() + b'"', "OTHER"),
    (json.dumps({"error": {"message": REPEAT}, "padding": "x" * 32768}).encode(), "OTHER"),
], ids=["exact", "suffix-not-equal", "wrong-path", "wrong-shape", "truncated", "over-inspection-limit"])
def test_rejection_records_exact_bounded_message_class_and_body_digest(caplog, body, kind):
    caplog.set_level(logging.INFO, logger="audit_ai")
    gateway = OpenAiCompatibleModelGateway(
        Settings(model_base_url="https://private.invalid/v1", model_name="synthetic", model_api_key="KEY_CANARY"),
        transport=httpx.MockTransport(lambda request: httpx.Response(500, content=body)),
    )
    with pytest.raises(ModelGatewayFailure):
        gateway.extract_material_fields("PROCUREMENT", "BUSINESS_CANARY", "capture-request")
    record = next(r.audit_fields for r in caplog.records if r.getMessage().startswith("PROVIDER_HTTP_REJECTED"))
    assert record["provider_error_kind"] == kind
    assert record["output_bytes"] == len(body)
    assert record["response_body_sha256"] == hashlib.sha256(body).hexdigest()
    assert record["http_status"] == 500
    assert record["duration_ms"] >= 0
    assert REPEAT not in caplog.text
    assert "BUSINESS_CANARY" not in caplog.text
    assert "KEY_CANARY" not in caplog.text
    assert "private.invalid" not in caplog.text


def test_real_semantic_gateway_stops_after_first_received_500():
    calls = []

    def provider(request):
        calls.append(request)
        return httpx.Response(500, json={"error": {"message": REPEAT}})

    gateway = OpenAiCompatibleModelGateway(
        Settings(model_base_url="https://private.invalid/v1", model_name="synthetic", model_api_key="KEY_CANARY"),
        transport=httpx.MockTransport(provider),
    )
    with TestClient(create_app(Settings(), model_gateway=gateway)) as client:
        response = client.post("/ai/v1/agent/contract-text-analysis", json={
            "projectId": "safe-test", "materials": [{"fileId": "contract", "category": "CONTRACT_BODY",
            "text": "Delivery requires acceptance; warranty starts after acceptance."}],
        })
    assert response.status_code == 502
    assert len(calls) == 1
    assert len(response.json()["data"]["modelCalls"]) == 1
