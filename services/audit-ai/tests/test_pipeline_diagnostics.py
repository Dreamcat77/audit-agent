import json
import logging
from concurrent.futures import ThreadPoolExecutor
from uuid import UUID

import httpx
import pytest
from fastapi.testclient import TestClient

from audit_ai.config import Settings
from audit_ai.main import create_app
from audit_ai.logging import diagnostic_context, log_event
from audit_ai.model.gateway import OpenAiCompatibleModelGateway, ModelGatewayFailure


def gateway(handler):
    return OpenAiCompatibleModelGateway(Settings(model_base_url="https://PRIVATE_HOST_SENTINEL.invalid/v1",
        model_name="synthetic", model_api_key="KEY_SENTINEL"), transport=httpx.MockTransport(handler))


@pytest.mark.parametrize("status,body,kind", [
    (400, {"error":{"message":"maximum context length BUSINESS_SENTINEL"}}, "CONTEXT_LIMIT"),
    (401, {"error":{"message":"invalid api key KEY_SENTINEL"}}, "AUTHENTICATION"),
    (429, {"error":{"message":"rate limit KEY_SENTINEL"}}, "RATE_LIMIT"),
])
def test_provider_rejection_has_sizes_status_and_safe_category(caplog, status, body, kind):
    caplog.set_level(logging.INFO, logger="audit_ai")
    with diagnostic_context(chunk_index=2, chunk_count=3):
        with pytest.raises(ModelGatewayFailure):
            gateway(lambda r: httpx.Response(status, json=body)).extract_material_fields("PROCUREMENT", "BUSINESS_SENTINEL", "safe-request")
    assert f"provider_error_kind={kind}" in caplog.text
    assert f"http_status={status}" in caplog.text
    assert "PROVIDER_HTTP_SEND" in caplog.text and "input_bytes=" in caplog.text
    assert "read_timeout_ms=" in caplog.text and "chunk_index=2" in caplog.text
    for value in ("KEY_SENTINEL", "BUSINESS_SENTINEL", "PRIVATE_HOST_SENTINEL"):
        assert value not in caplog.text
    record = next(r for r in caplog.records if r.getMessage().startswith("PROVIDER_CALL_END"))
    assert UUID(record.audit_fields["call_id"])


def test_truncated_inner_json_retains_finish_reason_and_token_usage(caplog):
    caplog.set_level(logging.INFO, logger="audit_ai")
    response = {"choices":[{"finish_reason":"length","message":{"content":'{"rows":"BUSINESS_SENTINEL'}}],
                "usage":{"prompt_tokens":15000,"completion_tokens":1536,"total_tokens":16536}}
    with pytest.raises(ModelGatewayFailure):
        gateway(lambda r: httpx.Response(200,json=response)).extract_material_fields("PROCUREMENT", "BUSINESS_SENTINEL", "safe-request")
    assert "provider_finish_reason=LENGTH" in caplog.text
    assert "prompt_tokens=15000" in caplog.text
    assert "json_error_kind=UNTERMINATED_STRING" in caplog.text
    assert "BUSINESS_SENTINEL" not in caplog.text


def test_request_context_isolated_and_worker_pool_preserves_request_id(caplog):
    caplog.set_level(logging.INFO, logger="audit_ai")
    def handler(r):
        return httpx.Response(200,json={"choices":[{"finish_reason":"stop","message":{"content":json.dumps({"rows":[]})}}]})
    def send(index):
        # Separate app instances isolate the logging test from single-slot admission.
        app=create_app(Settings(),model_gateway=gateway(handler))
        with TestClient(app) as client:
            return client.post("/ai/v1/materials/extract-fields",headers={"X-Request-Id":f"synthetic-{index}"},json={
                "businessType":"PROCUREMENT","materialId":f"material-{index}","sha256":"a"*64,
                "segments":[{"locator":"docx/p:1","text":"BUSINESS_SENTINEL"}]}).status_code
    with ThreadPoolExecutor(max_workers=2) as pool:
        assert list(pool.map(send,[1,2]))==[200,200]
    ends=[r.audit_fields for r in caplog.records if r.getMessage().startswith("PROVIDER_CALL_END")]
    assert {e["request_id"] for e in ends}=={"synthetic-1","synthetic-2"}
    assert len({e["call_id"] for e in ends})==2
    log_event(logging.getLogger("audit_ai.test"),"AFTER_REQUEST")
    assert "request_id" not in caplog.records[-1].audit_fields
    assert "BUSINESS_SENTINEL" not in caplog.text


def test_schema_validation_logs_count_without_echoing_invalid_input(caplog):
    caplog.set_level(logging.INFO,logger="audit_ai")
    with TestClient(create_app(Settings())) as client:
        response=client.post("/ai/v1/materials/extract-fields",headers={"X-Request-Id":"invalid-request"},
                             json={"businessType":"KEY_SENTINEL"})
    assert response.status_code==422
    assert "REQUEST_SCHEMA_REJECTED" in caplog.text and "validation_error_count=" in caplog.text
    assert "KEY_SENTINEL" not in caplog.text
