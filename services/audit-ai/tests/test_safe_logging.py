import io
import json
import logging
from uuid import UUID

import httpx
import pytest

from audit_ai.config import Settings
from audit_ai.logging import configure_runtime_logging, log_event
from audit_ai.model.gateway import (
    ContractTextOperation,
    ModelGatewayFailure,
    ModelNotConfigured,
    OpenAiCompatibleModelGateway,
)


def test_runtime_logging_reuses_uvicorn_handler_at_info_idempotently() -> None:
    audit_logger = logging.getLogger("audit_ai")
    uvicorn_logger = logging.getLogger("uvicorn")
    original_audit = (
        list(audit_logger.handlers),
        audit_logger.level,
        audit_logger.propagate,
    )
    original_uvicorn = (
        list(uvicorn_logger.handlers),
        uvicorn_logger.level,
        uvicorn_logger.propagate,
    )
    stream = io.StringIO()
    handler = logging.StreamHandler(stream)
    try:
        audit_logger.handlers.clear()
        audit_logger.setLevel(logging.NOTSET)
        audit_logger.propagate = True
        uvicorn_logger.handlers[:] = [handler]
        uvicorn_logger.setLevel(logging.INFO)
        uvicorn_logger.propagate = False

        configure_runtime_logging()
        configure_runtime_logging()
        logging.getLogger("audit_ai.runtime").info("runtime-info-visible")

        assert audit_logger.level == logging.INFO
        assert audit_logger.propagate is False
        assert audit_logger.handlers == [handler]
        assert stream.getvalue().count("runtime-info-visible") == 1
    finally:
        audit_logger.handlers[:] = original_audit[0]
        audit_logger.setLevel(original_audit[1])
        audit_logger.propagate = original_audit[2]
        uvicorn_logger.handlers[:] = original_uvicorn[0]
        uvicorn_logger.setLevel(original_uvicorn[1])
        uvicorn_logger.propagate = original_uvicorn[2]


def test_p0_secret_canary_matrix_never_reaches_technical_logs(caplog) -> None:
    sentinels = {
        "db_password": "DB-PASSWORD-CANARY-DO-NOT-LOG",
        "api_key": "API-KEY-CANARY-DO-NOT-LOG",
        "authorization": "AUTHORIZATION-CANARY-DO-NOT-LOG",
        "cookie": "COOKIE-CANARY-DO-NOT-LOG",
        "csrf": "CSRF-CANARY-DO-NOT-LOG",
        "contract": "RAW-CONTRACT-CANARY-DO-NOT-LOG",
        "prompt": "PROMPT-CANARY-DO-NOT-LOG",
        "completion": "RAW-COMPLETION-CANARY-DO-NOT-LOG",
        "sql_parameter": "SQL-PARAMETER-CANARY-DO-NOT-LOG",
        "provider_body": "PROVIDER-BODY-CANARY-DO-NOT-LOG",
    }

    def successful_provider(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "model": "qwen-safe-test",
                "choices": [{
                    "message": {
                        "content": json.dumps({
                            "summary": sentinels["completion"],
                            "hints": [],
                        })
                    }
                }],
            },
        )

    caplog.set_level(logging.INFO, logger="audit_ai")
    settings = Settings(
        model_base_url="https://model.example.invalid/v1",
        model_api_key=sentinels["api_key"],
        model_name="qwen-safe-test",
    )
    source = " ".join(
        value for key, value in sentinels.items()
        if key not in {"api_key", "provider_body", "completion"}
    )
    OpenAiCompatibleModelGateway(
        settings, transport=httpx.MockTransport(successful_provider),
    ).analyze_contract(ContractTextOperation(
        project_id="project-p0-canary",
        chunk_index=1,
        chunk_count=1,
        source_text=source,
        request_id="p0-canary-success",
    ))

    def failed_provider(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, text=sentinels["provider_body"])

    with pytest.raises(ModelGatewayFailure):
        OpenAiCompatibleModelGateway(
            settings, transport=httpx.MockTransport(failed_provider),
        ).analyze_contract(ContractTextOperation(
            project_id="project-p0-provider-canary",
            chunk_index=1,
            chunk_count=1,
            source_text=sentinels["contract"],
            request_id="p0-canary-provider-failure",
        ))

    for sentinel in sentinels.values():
        assert sentinel not in caplog.text


def test_model_logs_exclude_source_prompt_and_api_key(caplog) -> None:
    source_sentinel = "SOURCE-SENTINEL-DO-NOT-LOG"
    api_key = "KEY-SENTINEL-DO-NOT-LOG"

    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "model": "qwen-safe-test",
                "choices": [
                    {
                        "message": {
                            "content": json.dumps({"summary": "ok", "hints": []})
                        }
                    }
                ],
            },
        )

    settings = Settings(
        model_base_url="https://model.example.invalid/v1",
        model_api_key=api_key,
        model_name="qwen-safe-test",
    )
    gateway = OpenAiCompatibleModelGateway(
        settings, transport=httpx.MockTransport(handler)
    )
    caplog.set_level(logging.INFO)

    operation = ContractTextOperation(
        project_id="project-safe",
        chunk_index=1,
        chunk_count=1,
        source_text=source_sentinel,
    )
    gateway.analyze_contract(operation)

    assert source_sentinel not in caplog.text
    assert api_key not in caplog.text
    assert "Analyze textual" not in caplog.text
    assert "CONTRACT_TEXT_ANALYSIS" in caplog.text
    assert api_key not in repr(settings)
    assert source_sentinel not in repr(operation)


def test_count_metadata_is_numeric_only_and_unsafe_fields_stay_rejected(caplog) -> None:
    logger = logging.getLogger("audit_ai.safe-count-test")
    caplog.set_level(logging.INFO)
    log_event(
        logger,
        "semantic analysis completed",
        request_id="request-safe-1",
        operation="CONTRACT_TEXT_ANALYSIS",
        reason="INVALID_EVIDENCE_REF",
        gateway_reason="READ_TIMEOUT",
        attempt=1,
        max_attempts=2,
        material_count=3,
        chunk_index=1,
        chunk_count=2,
        input_chars=1200,
        output_hint_count=4,
        filtered_hint_count=1,
        evidence_segment_count=8,
        citable_segment_count=6,
        non_citable_segment_count=2,
        required_breach_segment_count=2,
        required_breach_citable_segment_count=2,
        semantic_defect_types="MISSING_REQUIRED_BREACH_CATEGORY",
    )
    assert "material_count=3" in caplog.text
    record = caplog.records[-1]
    assert record.audit_fields["output_hint_count"] == 4
    assert record.audit_fields["reason"] == "INVALID_EVIDENCE_REF"
    assert record.audit_fields["gateway_reason"] == "READ_TIMEOUT"
    assert record.audit_fields["attempt"] == 1
    assert record.audit_fields["max_attempts"] == 2
    assert record.audit_fields["chunk_index"] == 1
    assert record.audit_fields["filtered_hint_count"] == 1
    assert record.audit_fields["evidence_segment_count"] == 8
    assert record.audit_fields["citable_segment_count"] == 6
    assert record.audit_fields["non_citable_segment_count"] == 2
    assert record.audit_fields["required_breach_segment_count"] == 2
    assert record.audit_fields["required_breach_citable_segment_count"] == 2
    assert (
        record.audit_fields["semantic_defect_types"]
        == "MISSING_REQUIRED_BREACH_CATEGORY"
    )

    for field, value in [
        ("material_count", "3"),
        ("chunk_index", -1),
        ("chunk_count", -1),
        ("input_chars", True),
        ("attempt", -1),
        ("max_attempts", "2"),
        ("filtered_hint_count", -1),
        ("filtered_hint_count", "1"),
        ("filtered_hint_count", True),
        ("reason", "ARBITRARY_REASON"),
        ("gateway_reason", "ARBITRARY_REASON"),
        ("source_text", "secret"),
        ("prompt", "secret"),
        ("model_response_body", "secret"),
        ("provider_payload", "secret"),
        ("exact_text", "secret"),
        ("excerpt", "secret"),
        ("credentials", "secret"),
        ("api_key", "secret"),
        ("reason_code", "ARBITRARY_REASON"),
        ("status_code", "503"),
        ("diagnostic_schema_version", 0),
    ]:
        try:
            log_event(logger, "rejected", **{field: value})
        except ValueError:
            pass
        else:
            raise AssertionError(f"unsafe logging field accepted: {field}")


def test_gateway_logs_started_and_completed_without_request_content(caplog) -> None:
    source_sentinel = "RAW-CONTRACT-CANARY-DO-NOT-LOG"

    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "model": "qwen-safe-test",
                "choices": [{"message": {"content": json.dumps({"summary": "ok", "hints": []})}}],
            },
        )

    gateway = OpenAiCompatibleModelGateway(
        Settings(
            model_base_url="https://model.example.invalid/v1",
            model_api_key="API-KEY-CANARY-DO-NOT-LOG",
            model_name="qwen-safe-test",
        ),
        transport=httpx.MockTransport(handler),
    )
    caplog.set_level(logging.INFO, logger="audit_ai.model.gateway")

    gateway.analyze_contract(ContractTextOperation(
        project_id="project-safe",
        chunk_index=1,
        chunk_count=1,
        source_text=source_sentinel,
        request_id="gateway-request-lifecycle",
    ))

    lifecycle = [
        record.audit_fields for record in caplog.records
        if record.getMessage().startswith(("AI_HTTP_STARTED", "AI_HTTP_COMPLETED"))
    ]
    assert [event["outcome"] for event in lifecycle] == [
        "REQUEST_STARTED", "HTTP_RESPONSE_RECEIVED"
    ]
    assert all(event["stage"] == "MODEL_HTTP" for event in lifecycle)
    assert all(event["request_id"] == "gateway-request-lifecycle" for event in lifecycle)
    assert source_sentinel not in caplog.text
    assert "API-KEY-CANARY-DO-NOT-LOG" not in caplog.text


def test_gateway_configuration_failure_is_explicitly_not_called(caplog) -> None:
    gateway = OpenAiCompatibleModelGateway(Settings(
        model_base_url="", model_api_key="", model_name=""
    ))
    caplog.set_level(logging.INFO, logger="audit_ai.model.gateway")

    with pytest.raises(ModelNotConfigured):
        gateway.analyze_contract(ContractTextOperation(
            project_id="project-safe",
            chunk_index=1,
            chunk_count=1,
            source_text="safe",
            request_id="not-called-request",
        ))

    event = next(
        record.audit_fields for record in caplog.records
        if record.getMessage().startswith("AI_HTTP_COMPLETED")
    )
    assert event["outcome"] == "NOT_CALLED"
    assert event["reason_code"] == "MODEL_NOT_CONFIGURED"
    assert event["request_id"] == "not-called-request"


def test_gateway_failure_log_is_correlated_and_contains_only_classification(caplog) -> None:
    source_sentinel = "TIMEOUT-SOURCE-SENTINEL"
    secret_sentinel = "TIMEOUT-SECRET-SENTINEL"

    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectTimeout("provider-body-sentinel", request=request)

    gateway = OpenAiCompatibleModelGateway(
        Settings(
            model_base_url="https://model.example.invalid/v1",
            model_api_key=secret_sentinel,
            model_name="qwen-safe-test",
        ),
        transport=httpx.MockTransport(handler),
    )
    caplog.set_level(logging.INFO, logger="audit_ai.model.gateway")

    with pytest.raises(ModelGatewayFailure) as caught:
        gateway.analyze_contract(
            ContractTextOperation(
                project_id="project-safe",
                chunk_index=1,
                chunk_count=1,
                source_text=source_sentinel,
                request_id="gateway-request-1",
                attempt=1,
                max_attempts=2,
            )
        )

    assert caught.value.reason == "CONNECT_TIMEOUT"
    record = next(
        item
        for item in caplog.records
        if item.getMessage().startswith("AI_HTTP_COMPLETED")
    )
    assert record.audit_fields["request_id"] == "gateway-request-1"
    assert record.audit_fields["gateway_reason"] == "CONNECT_TIMEOUT"
    assert record.audit_fields["attempt"] == 1
    assert record.audit_fields["max_attempts"] == 2
    assert source_sentinel not in caplog.text
    assert secret_sentinel not in caplog.text
    assert "provider-body-sentinel" not in caplog.text


def test_model_response_shape_is_visible_at_info_without_provider_values(caplog) -> None:
    title_sentinel = "TITLE-SENTINEL-DO-NOT-LOG"
    description_sentinel = "DESCRIPTION-SENTINEL-DO-NOT-LOG"
    evidence_sentinel = "EVIDENCE-ID-SENTINEL-DO-NOT-LOG"

    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "model": "qwen-safe-test",
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(
                                {
                                    "missingCategoryHints": [
                                        {
                                            "category": "BREACH_LIABILITY",
                                            "attention": "REVIEW",
                                            "title": title_sentinel,
                                            "description": description_sentinel,
                                            "problemEvidenceIds": [evidence_sentinel],
                                        }
                                    ]
                                }
                            )
                        }
                    }
                ],
            },
        )

    gateway = OpenAiCompatibleModelGateway(
        Settings(
            model_base_url="https://model.example.invalid/v1",
            model_api_key="shape-key-sentinel",
            model_name="qwen-safe-test",
        ),
        transport=httpx.MockTransport(handler),
    )
    caplog.set_level(logging.INFO, logger="audit_ai")

    gateway.analyze_contract(
        ContractTextOperation(
            project_id="project-shape",
            chunk_index=1,
            chunk_count=1,
            source_text="SOURCE-SENTINEL-DO-NOT-LOG",
            request_id="shape-request-1",
            attempt=2,
            max_attempts=2,
            purpose="SEMANTIC_COMPLETENESS_CORRECTION",
        )
    )

    shape = next(
        record.audit_fields
        for record in caplog.records
        if record.getMessage().startswith("model response shape observed")
    )
    assert shape == {
        "call_id": str(UUID(shape["call_id"])),
        "request_id": "shape-request-1",
        "operation": "CONTRACT_TEXT_ANALYSIS",
        "model": "qwen-safe-test",
        "attempt": 2,
        "purpose": "SEMANTIC_COMPLETENESS_CORRECTION",
        "top_level_keys": "missingCategoryHints",
        "summary_present": "NO",
        "hints_present": "NO",
        "hint_count": 0,
        "missing_category_hints_present": "YES",
        "missing_category_hint_count": 1,
        "evidence_repairs_present": "NO",
        "evidence_repair_count": 0,
    }
    item = next(
        record.audit_fields
        for record in caplog.records
        if record.getMessage().startswith("model correction item shape observed")
    )
    assert item["item_index"] == 1
    assert item["category"] == "BREACH_LIABILITY"
    assert item["attention"] == "REVIEW"
    assert item["has_title"] == "YES"
    assert item["has_description"] == "YES"
    assert item["has_problem_evidence_ids"] == "YES"
    assert item["problem_evidence_count"] == 1
    assert item["has_comparison_evidence_ids"] == "NO"
    assert item["comparison_evidence_count"] == 0
    assert item["has_unavailable_material_category"] == "NO"
    assert item["has_hint_id"] == "NO"
    assert title_sentinel not in caplog.text
    assert description_sentinel not in caplog.text
    assert evidence_sentinel not in caplog.text
    assert "shape-key-sentinel" not in caplog.text
    assert "SOURCE-SENTINEL-DO-NOT-LOG" not in caplog.text


def test_shape_logging_does_not_raise_or_leak_non_scalar_provider_values(caplog) -> None:
    malformed_sentinel = "MALFORMED-SENTINEL-DO-NOT-LOG"

    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "model": "qwen-safe-test",
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(
                                {
                                    "missingCategoryHints": [
                                        {
                                            "category": [malformed_sentinel],
                                            "attention": {"value": malformed_sentinel},
                                            "problemEvidenceIds": malformed_sentinel,
                                        }
                                    ]
                                }
                            )
                        }
                    }
                ],
            },
        )

    gateway = OpenAiCompatibleModelGateway(
        Settings(
            model_base_url="https://model.example.invalid/v1",
            model_api_key="safe-key",
            model_name="qwen-safe-test",
        ),
        transport=httpx.MockTransport(handler),
    )
    caplog.set_level(logging.INFO, logger="audit_ai")

    completion = gateway.analyze_contract(
        ContractTextOperation(
            project_id="project-malformed-shape",
            chunk_index=1,
            chunk_count=1,
            source_text="safe source",
            request_id="shape-request-malformed",
            attempt=2,
            max_attempts=2,
            purpose="SEMANTIC_COMPLETENESS_CORRECTION",
        )
    )

    assert completion.payload["missingCategoryHints"]
    item = next(
        record.audit_fields
        for record in caplog.records
        if record.getMessage().startswith("model correction item shape observed")
    )
    assert item["category"] == "NONE"
    assert item["attention"] == "NONE"
    assert item["problem_evidence_count"] == 0
    assert malformed_sentinel not in caplog.text


@pytest.mark.parametrize("purpose", ["ANALYZE", "SYNTHESIZE"])
def test_analyze_response_logs_each_hint_shape_without_provider_values(
    caplog, purpose: str,
) -> None:
    forbidden_values = (
        "title-secret-value",
        "description-secret-value",
        "source-secret-value",
        "excerpt-secret-value",
        "C:S999",
        "API-key-secret-value",
        "Bearer-secret-value",
        "token-secret-value",
        "cookie-secret-value",
    )

    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "model": "qwen-safe-test",
                "choices": [{
                    "message": {
                        "content": json.dumps({
                            "summary": "token-secret-value",
                            "hints": [
                                {
                                    "category": "BREACH_LIABILITY",
                                    "attention": "REVIEW",
                                    "title": "title-secret-value",
                                    "description": "description-secret-value",
                                    "problemEvidenceIds": ["C:S999"],
                                    "comparisonEvidenceIds": ["T:S3"],
                                    "excerpt": "excerpt-secret-value",
                                },
                                {
                                    "category": "SERVICE_WARRANTY",
                                    "attention": "INSUFFICIENT",
                                    "title": "Bearer-secret-value",
                                    "description": "cookie-secret-value",
                                    "comparisonEvidenceIds": ["B:S2"],
                                    "unavailableMaterialCategory": "BID_RESPONSE",
                                },
                            ],
                        })
                    }
                }],
            },
        )

    gateway = OpenAiCompatibleModelGateway(
        Settings(
            model_base_url="https://model.example.invalid/v1",
            model_api_key="API-key-secret-value",
            model_name="qwen-safe-test",
        ),
        transport=httpx.MockTransport(handler),
    )
    caplog.set_level(logging.INFO, logger="audit_ai")

    gateway.analyze_contract(ContractTextOperation(
        project_id="project-analyze-shape",
        chunk_index=1,
        chunk_count=1,
        source_text="source-secret-value",
        request_id=f"{purpose.lower()}-shape-request",
        attempt=1,
        max_attempts=2,
        purpose=purpose,
    ))

    items = [
        record.audit_fields
        for record in caplog.records
        if record.getMessage().startswith("model hint shape observed")
    ]
    assert items == [
        {
            "call_id": str(UUID(items[0]["call_id"])),
            "request_id": f"{purpose.lower()}-shape-request",
            "operation": "CONTRACT_TEXT_ANALYSIS",
            "model": "qwen-safe-test",
            "attempt": 1,
            "purpose": purpose,
            "item_index": 1,
            "category_valid_shape": "YES",
            "attention_valid_shape": "YES",
            "category": "BREACH_LIABILITY",
            "attention": "REVIEW",
            "has_title": "YES",
            "has_description": "YES",
            "has_problem_evidence_ids": "YES",
            "problem_evidence_count": 1,
            "has_comparison_evidence_ids": "YES",
            "comparison_evidence_count": 1,
            "has_unavailable_material_category": "NO",
        },
        {
            "call_id": str(UUID(items[0]["call_id"])),
            "request_id": f"{purpose.lower()}-shape-request",
            "operation": "CONTRACT_TEXT_ANALYSIS",
            "model": "qwen-safe-test",
            "attempt": 1,
            "purpose": purpose,
            "item_index": 2,
            "category_valid_shape": "YES",
            "attention_valid_shape": "YES",
            "category": "SERVICE_WARRANTY",
            "attention": "INSUFFICIENT",
            "has_title": "YES",
            "has_description": "YES",
            "has_problem_evidence_ids": "NO",
            "problem_evidence_count": 0,
            "has_comparison_evidence_ids": "YES",
            "comparison_evidence_count": 1,
            "has_unavailable_material_category": "YES",
        },
    ]
    for forbidden in forbidden_values:
        assert forbidden not in caplog.text


def test_analyze_hint_shape_logs_only_validity_for_unsafe_enum_shapes(caplog) -> None:
    malformed_sentinel = "MALFORMED-ANALYZE-SENTINEL-DO-NOT-LOG"

    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "model": "qwen-safe-test",
                "choices": [{
                    "message": {
                        "content": json.dumps({
                            "summary": "safe summary",
                            "hints": [{
                                "category": [malformed_sentinel],
                                "attention": {"value": malformed_sentinel},
                                "title": malformed_sentinel,
                                "description": malformed_sentinel,
                            }],
                        })
                    }
                }],
            },
        )

    gateway = OpenAiCompatibleModelGateway(
        Settings(
            model_base_url="https://model.example.invalid/v1",
            model_api_key="safe-key",
            model_name="qwen-safe-test",
        ),
        transport=httpx.MockTransport(handler),
    )
    caplog.set_level(logging.INFO, logger="audit_ai")

    gateway.analyze_contract(ContractTextOperation(
        project_id="project-malformed-analyze-shape",
        chunk_index=1,
        chunk_count=1,
        source_text="safe source",
        request_id="malformed-analyze-shape-request",
        purpose="ANALYZE",
    ))

    item = next(
        record.audit_fields
        for record in caplog.records
        if record.getMessage().startswith("model hint shape observed")
    )
    assert item["category_valid_shape"] == "NO"
    assert item["attention_valid_shape"] == "NO"
    assert "category" not in item
    assert "attention" not in item
    assert malformed_sentinel not in caplog.text
