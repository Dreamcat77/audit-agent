import hashlib
import json
import logging

import httpx
import pytest
from fastapi.testclient import TestClient

from audit_ai.config import Settings
from audit_ai.main import create_app
from audit_ai.model.gateway import (
    ContractFactOperation,
    ModelCompletion,
    ModelGatewayFailure,
    OpenAiCompatibleModelGateway,
)


TEXT = "合同编号：HT-2026-001，合同总金额人民币67000.00元。"
PROVIDER_BASE_URL = "http://provider.internal:11434/v1"


def sha256(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def request_body() -> dict:
    return {
        "projectId": "00000000-0000-0000-0000-000000000100",
        "fileId": "00000000-0000-0000-0000-000000000101",
        "fileSha256": "a" * 64,
        "parseResultId": "00000000-0000-0000-0000-000000000102",
        "parseVersion": 3,
        "canonicalTextSha256": "b" * 64,
        "segments": [{
            "segmentId": "segment-1",
            "sourceLocator": "parse:102:canonical-codepoint:0-30",
            "textSha256": sha256(TEXT),
            "startOffset": 0,
            "endOffset": len(TEXT),
            "text": TEXT,
        }],
    }


def request_body_v2(scope: str = "CORE") -> dict:
    segment = request_body()["segments"][0]
    return {
        "projectId": request_body()["projectId"],
        "fileId": request_body()["fileId"],
        "fileSha256": request_body()["fileSha256"],
        "parseResultId": request_body()["parseResultId"],
        "parseVersion": request_body()["parseVersion"],
        "canonicalTextSha256": request_body()["canonicalTextSha256"],
        "scope": scope,
        "evidenceCatalog": [{
            "evidenceId": "evidence-1",
            "sourceLocator": segment["sourceLocator"],
            "textSha256": segment["textSha256"],
            "startOffset": segment["startOffset"],
            "endOffset": segment["endOffset"],
            "text": segment["text"],
        }],
    }


@pytest.mark.parametrize("content", ['{"candidates":[]}', '{"candidates":[{"factType":"LINE_'])
def test_length_finished_fact_response_is_failed_once_with_usage_even_if_json_is_valid(content: str) -> None:
    requests = []

    def provider(request: httpx.Request) -> httpx.Response:
        requests.append(json.loads(request.content))
        return httpx.Response(200, json={
            "model": "fact-model-test", "choices": [{"finish_reason": "length", "message": {"content": content}}],
            "usage": {"prompt_tokens": 3873, "completion_tokens": 4096, "total_tokens": 7969},
        })

    settings = Settings(contract_fact_extraction_enabled=True, model_base_url=PROVIDER_BASE_URL,
                        model_api_key="synthetic-test-key", model_name="fact-model-test")
    response = TestClient(create_app(settings, model_gateway=OpenAiCompatibleModelGateway(
        settings, transport=httpx.MockTransport(provider),
    ))).post("/ai/v2/agent/contract-fact-candidates", json=request_body_v2("LINE_ITEMS"))

    assert response.status_code == 200
    data = response.json()["data"]
    assert data["batchStatus"] == "FAILED"
    assert data["candidates"] == []
    assert len(requests) == 1  # Repeating an identical truncated batch cannot add capacity.
    assert requests[0]["max_tokens"] == 4096
    assert data["modelCalls"][0]["safeReason"] == "OUTPUT_TOKEN_LIMIT"
    assert data["modelCalls"][0]["finishReason"] == "LENGTH"
    assert data["modelCalls"][0]["completionTokens"] == 4096


def test_fact_output_budget_batches_can_reach_tail_beyond_the_old_eight_batch_limit() -> None:
    body = request_body_v2()
    body.update(batchOrdinal=24, batchCount=24)
    gateway = ContractFactV2Gateway([])
    response = TestClient(create_app(
        Settings(contract_fact_extraction_enabled=True, model_base_url=PROVIDER_BASE_URL), model_gateway=gateway,
    )).post("/ai/v2/agent/contract-fact-candidates", json=body)
    assert response.status_code == 200
    assert response.json()["data"]["batchOrdinal"] == 24
    assert gateway.operations[0].batch_count == 24


def test_v2_provider_contract_declares_the_same_context_enums_as_the_strict_validator() -> None:
    captured = []
    def provider(request: httpx.Request) -> httpx.Response:
        captured.append(json.loads(request.content))
        return httpx.Response(200, json={"model": "fact-model-test", "choices": [{
            "finish_reason": "stop", "message": {"content": '{"candidates":[]}'},
        }]})
    settings = Settings(contract_fact_extraction_enabled=True, model_base_url=PROVIDER_BASE_URL,
                        model_api_key="synthetic-test-key", model_name="fact-model-test")
    response = TestClient(create_app(settings, model_gateway=OpenAiCompatibleModelGateway(
        settings, transport=httpx.MockTransport(provider),
    ))).post("/ai/v2/agent/contract-fact-candidates", json=request_body_v2("LINE_ITEMS"))
    assert response.status_code == 200
    # This is the actual outbound wire contract, independently enumerated here.
    message = captured[0]["messages"][1]["content"]
    mapping_line = next((line for line in message.splitlines() if line.startswith("factContextCodes=")), "")
    assert mapping_line, "provider was never told the exact contextCode enum contract"
    mapping = json.loads(mapping_line.split("=", 1)[1])
    assert mapping["LINE_ITEM_NAME"] == "CONTRACT_LINE_ITEM"
    assert mapping["LINE_ITEM_QUANTITY"] == "CONTRACT_LINE_ITEM"
    assert mapping["LINE_ITEM_UNIT_PRICE"] == "CONTRACT_LINE_ITEM"
    assert mapping["LINE_ITEM_DECLARED_AMOUNT"] == "CONTRACT_LINE_ITEM"
    assert mapping["CONTRACT_NO"] == "CONTRACT_HEADER"
    assert mapping["PARTY_B"] == "CONTRACT_PARTIES"
    assert mapping["AWARD_AMOUNT"] == "AWARD_FINAL_RESULT"
    assert response.json()["data"]["promptVersion"] == "contract-fact-prompt-v2.3"


class ContractFactV2Gateway:
    def __init__(self, rows: list[dict]) -> None:
        self.operations: list[ContractFactOperation] = []
        self.rows = rows

    def extract_contract_facts(self, operation: ContractFactOperation) -> ModelCompletion:
        self.operations.append(operation)
        return ModelCompletion(
            payload={"candidates": self.rows},
            model="fact-model-test",
            revision="revision-test",
            duration_ms=9,
            finish_reason="STOP",
            prompt_tokens=120,
            completion_tokens=40,
            total_tokens=160,
        )


def v2_candidate(**overrides: object) -> dict:
    value = {
        "factType": "CONTRACT_AMOUNT",
        "groupKey": None,
        "valueText": None,
        "valueDecimal": "67000.00",
        "evidenceId": "evidence-1",
        "contextCode": "CONTRACT_AMOUNT",
        "confidence": 0.96,
    }
    value.update(overrides)
    return value


def test_server_row_identity_survives_different_evidence_and_provider_groups_across_batches() -> None:
    responses = []
    for batch, evidence_id, provider_group in [(1, "fragment-1", "model-row-a"), (2, "fragment-2", "model-row-b")]:
        body = request_body_v2("LINE_ITEMS")
        body.update(batchOrdinal=batch, batchCount=2)
        evidence = body["evidenceCatalog"][0]
        evidence.update(evidenceId=evidence_id, rowIdentity="row:" + "a" * 64)
        gateway = ContractFactV2Gateway([v2_candidate(
            factType="LINE_ITEM_DECLARED_AMOUNT", contextCode="CONTRACT_LINE_ITEM",
            evidenceId=evidence_id, groupKey=provider_group,
        )])
        response = TestClient(create_app(
            Settings(contract_fact_extraction_enabled=True, model_base_url=PROVIDER_BASE_URL),
            model_gateway=gateway,
        )).post("/ai/v2/agent/contract-fact-candidates", json=body)
        assert response.status_code == 200
        data = response.json()["data"]
        assert data["batchOrdinal"] == batch
        assert data["batchCount"] == 2
        responses.append(data["candidates"][0])
    assert responses[0]["groupKey"] == responses[1]["groupKey"]
    assert responses[0]["candidateKey"] != responses[1]["candidateKey"]


def test_same_row_fragments_with_different_model_groups_merge_within_one_batch() -> None:
    body = request_body_v2("LINE_ITEMS")
    first = body["evidenceCatalog"][0]
    first.update(evidenceId="fragment-1", rowIdentity="row:" + "a" * 64)
    body["evidenceCatalog"].append({**first, "evidenceId": "fragment-2", "startOffset": len(TEXT),
                                    "endOffset": len(TEXT) * 2})
    gateway = ContractFactV2Gateway([
        v2_candidate(factType="LINE_ITEM_QUANTITY", contextCode="CONTRACT_LINE_ITEM",
                     evidenceId="fragment-1", groupKey="model-fragment-a", valueDecimal="2"),
        v2_candidate(factType="LINE_ITEM_DECLARED_AMOUNT", contextCode="CONTRACT_LINE_ITEM",
                     evidenceId="fragment-2", groupKey="model-fragment-b"),
    ])
    response = TestClient(create_app(
        Settings(contract_fact_extraction_enabled=True, model_base_url=PROVIDER_BASE_URL), model_gateway=gateway,
    )).post("/ai/v2/agent/contract-fact-candidates", json=body)
    data = response.json()["data"]
    assert len(data["candidates"]) == 2
    assert len({row["groupKey"] for row in data["candidates"]}) == 1
    assert data["rejections"] == []


def test_multiple_provider_groups_cannot_invent_distinct_rows_for_one_server_row() -> None:
    body = request_body_v2("LINE_ITEMS")
    body["evidenceCatalog"][0]["rowIdentity"] = "row:" + "a" * 64
    gateway = ContractFactV2Gateway([
        v2_candidate(factType="LINE_ITEM_DECLARED_AMOUNT", contextCode="CONTRACT_LINE_ITEM", groupKey="one"),
        v2_candidate(factType="LINE_ITEM_QUANTITY", contextCode="CONTRACT_LINE_ITEM", groupKey="two"),
    ])
    response = TestClient(create_app(
        Settings(contract_fact_extraction_enabled=True, model_base_url=PROVIDER_BASE_URL), model_gateway=gateway,
    )).post("/ai/v2/agent/contract-fact-candidates", json=body)
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["candidates"] == []
    assert {row["reasonCode"] for row in data["rejections"]} == {"ROW_IDENTITY_UNPROVEN"}


def test_contract_fact_read_timeout_has_unknown_outcome_instead_of_failed() -> None:
    class TimedOutGateway:
        def extract_contract_facts(self, operation: ContractFactOperation) -> ModelCompletion:
            raise ModelGatewayFailure(reason="READ_TIMEOUT", retryable=False, duration_ms=12)

    response = TestClient(create_app(
        Settings(contract_fact_extraction_enabled=True, model_base_url=PROVIDER_BASE_URL, model_name="fact-model-test"),
        model_gateway=TimedOutGateway(),
    )).post("/ai/v2/agent/contract-fact-candidates", json=request_body_v2())
    assert response.status_code == 200
    assert response.json()["data"]["batchStatus"] == "UNKNOWN"


def test_http200_provider_refusal_is_business_failure_with_safe_reason() -> None:
    def provider(_: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"model": "fact-model-test", "choices": [{
            "finish_reason": "content_filter", "message": {"content": None, "refusal": "private-provider-text"},
        }]})
    settings = Settings(contract_fact_extraction_enabled=True, model_base_url=PROVIDER_BASE_URL,
                        model_api_key="synthetic-test-key", model_name="fact-model-test")
    response = TestClient(create_app(settings, model_gateway=OpenAiCompatibleModelGateway(
        settings, transport=httpx.MockTransport(provider),
    ))).post("/ai/v2/agent/contract-fact-candidates", json=request_body_v2())
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["batchStatus"] == "FAILED"
    assert data["modelCalls"][0]["safeReason"] == "MODEL_REFUSED"
    assert "private-provider-text" not in response.text


def test_per_request_evidence_budget_is_32_by_240_unicode_codepoints() -> None:
    body = request_body_v2()
    text = "𠀀" + "a" * 239
    body["evidenceCatalog"] = [{
        "evidenceId": f"evidence-{index}", "sourceLocator": "bounded-synthetic",
        "textSha256": sha256(text), "startOffset": index * 240, "endOffset": (index + 1) * 240,
        "text": text,
    } for index in range(32)]
    gateway = ContractFactV2Gateway([])
    client = TestClient(create_app(
        Settings(contract_fact_extraction_enabled=True, model_base_url=PROVIDER_BASE_URL), model_gateway=gateway,
    ))
    assert client.post("/ai/v2/agent/contract-fact-candidates", json=body).status_code == 200
    body["evidenceCatalog"].append({**body["evidenceCatalog"][-1], "evidenceId": "evidence-33"})
    assert client.post("/ai/v2/agent/contract-fact-candidates", json=body).status_code == 422
    body["evidenceCatalog"] = [{**body["evidenceCatalog"][0], "text": text + "a",
                                "textSha256": sha256(text + "a"), "endOffset": 241}]
    assert client.post("/ai/v2/agent/contract-fact-candidates", json=body).status_code == 422
    assert len(gateway.operations) == 1


def test_retry_spends_the_same_remaining_document_budget(monkeypatch) -> None:
    elapsed = [0.0]
    monkeypatch.setattr("audit_ai.model.contract_fact.time.monotonic", lambda: elapsed[0])

    class RetriedGateway(ContractFactV2Gateway):
        def extract_contract_facts(self, operation: ContractFactOperation) -> ModelCompletion:
            if not self.operations:
                self.operations.append(operation)
                elapsed[0] = 0.6
                raise ModelGatewayFailure(reason="INVALID_PROVIDER_JSON", retryable=True, duration_ms=600)
            return super().extract_contract_facts(operation)

    gateway = RetriedGateway([])
    body = request_body_v2()
    body["remainingBudgetMs"] = 1000
    response = TestClient(create_app(
        Settings(contract_fact_extraction_enabled=True, model_base_url=PROVIDER_BASE_URL), model_gateway=gateway,
    )).post("/ai/v2/agent/contract-fact-candidates", json=body)
    assert response.status_code == 200
    assert gateway.operations[0].provider_timeout_seconds == 1.0
    assert gateway.operations[1].provider_timeout_seconds == 0.4


def test_model_connection_reset_after_request_has_unknown_outcome_and_is_not_retried() -> None:
    sent = []
    def provider(request: httpx.Request) -> httpx.Response:
        sent.append(request)
        raise httpx.ReadError("synthetic response reset", request=request)
    settings = Settings(contract_fact_extraction_enabled=True, model_base_url=PROVIDER_BASE_URL,
                        model_api_key="synthetic-test-key", model_name="fact-model-test")
    response = TestClient(create_app(settings, model_gateway=OpenAiCompatibleModelGateway(
        settings, transport=httpx.MockTransport(provider),
    ))).post("/ai/v2/agent/contract-fact-candidates", json=request_body_v2())
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["batchStatus"] == "UNKNOWN"
    assert data["modelCalls"][0]["safeReason"] == "TRANSPORT_OUTCOME_UNKNOWN"
    assert len(sent) == 1


@pytest.mark.parametrize("status", [500, 502, 503, 504, 599])
def test_dispatched_provider_5xx_has_unknown_fact_outcome_without_retry(status: int) -> None:
    sent = []
    def provider(request: httpx.Request) -> httpx.Response:
        sent.append(request)
        return httpx.Response(status, json={"error": "synthetic unproven provider outcome"})
    settings = Settings(contract_fact_extraction_enabled=True, model_base_url=PROVIDER_BASE_URL,
                        model_api_key="synthetic-test-key", model_name="fact-model-test")
    response = TestClient(create_app(settings, model_gateway=OpenAiCompatibleModelGateway(
        settings, transport=httpx.MockTransport(provider),
    ))).post("/ai/v2/agent/contract-fact-candidates", json=request_body_v2())
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["batchStatus"] == "UNKNOWN"
    assert data["candidates"] == []
    assert data["modelCalls"][0]["safeReason"] == "TRANSPORT_OUTCOME_UNKNOWN"
    assert len(sent) == 1


def test_contract_fact_v2_resolves_server_evidence_and_returns_safe_call_audit() -> None:
    gateway = ContractFactV2Gateway([v2_candidate()])

    response = TestClient(create_app(
        Settings(contract_fact_extraction_enabled=True, model_base_url=PROVIDER_BASE_URL),
        model_gateway=gateway,
    )).post("/ai/v2/agent/contract-fact-candidates", json=request_body_v2())

    assert response.status_code == 200
    data = response.json()["data"]
    assert data["schemaVersion"] == "contract-fact-candidates-v2"
    assert data["scope"] == "CORE"
    assert data["batchStatus"] == "PARTIAL"
    assert data["rejections"] == []
    candidate = data["candidates"][0]
    assert candidate["candidateKey"].startswith("cfc-v2-")
    assert candidate["segmentId"] == "evidence-1"
    assert candidate["sourceLocator"] == request_body_v2()["evidenceCatalog"][0]["sourceLocator"]
    assert candidate["excerpt"] == TEXT
    assert candidate["excerptStart"] == 0
    assert candidate["excerptEnd"] == len(TEXT)
    assert data["modelCalls"] == [{
        "ordinal": 1,
        "phase": "TRANSPORT",
        "outcome": "SUCCEEDED",
        "safeReason": None,
        "durationMs": 9,
        "finishReason": "STOP",
        "promptTokens": 120,
        "completionTokens": 40,
        "totalTokens": 160,
    }]


def test_contract_fact_v2_keeps_valid_rows_and_reports_invalid_rows() -> None:
    gateway = ContractFactV2Gateway([
        v2_candidate(),
        v2_candidate(valueDecimal=None, valueText="invalid-money"),
        v2_candidate(evidenceId="invented-evidence"),
    ])

    response = TestClient(create_app(
        Settings(contract_fact_extraction_enabled=True, model_base_url=PROVIDER_BASE_URL),
        model_gateway=gateway,
    )).post("/ai/v2/agent/contract-fact-candidates", json=request_body_v2())

    assert response.status_code == 200
    data = response.json()["data"]
    assert len(data["candidates"]) == 1
    assert [(row["providerOrdinal"], row["stage"], row["reasonCode"])
            for row in data["rejections"]] == [
        (2, "TYPED_SCHEMA", "INVALID_TYPED_SCHEMA"),
        (3, "EVIDENCE", "UNKNOWN_EVIDENCE_ID"),
    ]
    assert all(set(row) == {"providerOrdinal", "stage", "reasonCode", "safeShapeHash"}
               for row in data["rejections"])
    assert len(gateway.operations) == 1


def test_contract_fact_v2_does_not_report_all_rejected_rows_as_partial_success() -> None:
    response = TestClient(create_app(
        Settings(contract_fact_extraction_enabled=True, model_base_url=PROVIDER_BASE_URL),
        model_gateway=ContractFactV2Gateway([
            v2_candidate(valueDecimal=None, valueText="invalid-money"),
        ]),
    )).post("/ai/v2/agent/contract-fact-candidates", json=request_body_v2())

    assert response.status_code == 200
    data = response.json()["data"]
    assert data["candidates"] == []
    assert data["batchStatus"] == "FAILED"
    assert data["rejections"][0]["reasonCode"] == "INVALID_TYPED_SCHEMA"


def test_contract_fact_v2_repairs_an_all_typed_schema_rejection_once() -> None:
    class RepairingGateway(ContractFactV2Gateway):
        def __init__(self) -> None:
            super().__init__([])

        def extract_contract_facts(self, operation: ContractFactOperation) -> ModelCompletion:
            self.operations.append(operation)
            rows = ([{
                **v2_candidate(),
                "factType": "WINNING_AMOUNT",
                "contextCode": "AWARD_FINAL_RESULT",
            }] if len(self.operations) == 1 else [{
                **v2_candidate(),
                "factType": "AWARD_AMOUNT",
                "contextCode": "AWARD_FINAL_RESULT",
            }])
            return ModelCompletion(
                payload={"candidates": rows}, model="fact-model-test",
                revision="revision-test", duration_ms=9,
                finish_reason="STOP", prompt_tokens=120,
                completion_tokens=40, total_tokens=160,
            )

    gateway = RepairingGateway()
    response = TestClient(create_app(
        Settings(contract_fact_extraction_enabled=True, model_base_url=PROVIDER_BASE_URL),
        model_gateway=gateway,
    )).post("/ai/v2/agent/contract-fact-candidates", json=request_body_v2("AWARD"))

    assert response.status_code == 200
    data = response.json()["data"]
    assert data["batchStatus"] == "PARTIAL"
    assert [row["factType"] for row in data["candidates"]] == ["AWARD_AMOUNT"]
    assert [row["reasonCode"] for row in data["rejections"]] == [
        "INVALID_TYPED_SCHEMA"
    ]
    assert len(gateway.operations) == 2
    assert gateway.operations[0].transport_retry_reason is None
    assert gateway.operations[1].transport_retry_reason == "INVALID_TYPED_SCHEMA"
    assert [(call["ordinal"], call["phase"], call["outcome"], call["safeReason"])
            for call in data["modelCalls"]] == [
        (1, "TRANSPORT", "FAILED", "INVALID_TYPED_SCHEMA"),
        (2, "REPAIR", "SUCCEEDED", None),
    ]


def test_contract_fact_v2_stops_after_one_invalid_typed_schema_repair() -> None:
    gateway = ContractFactV2Gateway([{
        **v2_candidate(),
        "factType": "WINNING_AMOUNT",
        "contextCode": "AWARD_FINAL_RESULT",
    }])

    response = TestClient(create_app(
        Settings(contract_fact_extraction_enabled=True, model_base_url=PROVIDER_BASE_URL),
        model_gateway=gateway,
    )).post("/ai/v2/agent/contract-fact-candidates", json=request_body_v2("AWARD"))

    assert response.status_code == 200
    data = response.json()["data"]
    assert data["batchStatus"] == "FAILED"
    assert data["candidates"] == []
    assert [row["reasonCode"] for row in data["rejections"]] == [
        "INVALID_TYPED_SCHEMA"
    ]
    assert len(gateway.operations) == 2
    assert gateway.operations[1].transport_retry_reason == "INVALID_TYPED_SCHEMA"
    assert [(call["ordinal"], call["phase"], call["outcome"], call["safeReason"])
            for call in data["modelCalls"]] == [
        (1, "TRANSPORT", "FAILED", "INVALID_TYPED_SCHEMA"),
        (2, "REPAIR", "FAILED", "INVALID_TYPED_SCHEMA"),
    ]


def test_contract_fact_v2_rejects_cross_scope_candidate_without_losing_valid_row() -> None:
    gateway = ContractFactV2Gateway([
        v2_candidate(),
        v2_candidate(factType="AWARD_AMOUNT", contextCode="AWARD_FINAL_RESULT"),
    ])

    response = TestClient(create_app(
        Settings(contract_fact_extraction_enabled=True, model_base_url=PROVIDER_BASE_URL),
        model_gateway=gateway,
    )).post("/ai/v2/agent/contract-fact-candidates", json=request_body_v2("CORE"))

    assert response.status_code == 200
    data = response.json()["data"]
    assert len(data["candidates"]) == 1
    assert data["rejections"][0]["reasonCode"] == "FACT_OUTSIDE_SCOPE"


def test_contract_fact_v2_empty_result_is_not_reported_as_complete() -> None:
    response = TestClient(create_app(
        Settings(contract_fact_extraction_enabled=True, model_base_url=PROVIDER_BASE_URL),
        model_gateway=ContractFactV2Gateway([]),
    )).post("/ai/v2/agent/contract-fact-candidates", json=request_body_v2("LINE_ITEMS"))

    assert response.status_code == 200
    assert response.json()["data"]["batchStatus"] == "EMPTY"


def test_contract_fact_v2_returns_failed_batch_with_both_transport_attempts() -> None:
    class FailingGateway(ContractFactV2Gateway):
        def __init__(self) -> None:
            super().__init__([])

        def extract_contract_facts(self, operation: ContractFactOperation) -> ModelCompletion:
            self.operations.append(operation)
            raise ModelGatewayFailure(
                reason="INVALID_PROVIDER_JSON", retryable=True, duration_ms=7
            )

    gateway = FailingGateway()
    response = TestClient(create_app(
        Settings(
            contract_fact_extraction_enabled=True,
            model_base_url=PROVIDER_BASE_URL,
            model_name="fact-model-test",
        ),
        model_gateway=gateway,
    )).post("/ai/v2/agent/contract-fact-candidates", json=request_body_v2())

    assert response.status_code == 200
    data = response.json()["data"]
    assert data["batchStatus"] == "FAILED"
    assert data["candidates"] == []
    assert data["rejections"] == []
    assert [(call["ordinal"], call["phase"], call["outcome"], call["safeReason"])
            for call in data["modelCalls"]] == [
        (1, "TRANSPORT", "FAILED", "INVALID_PROVIDER_JSON"),
        (2, "REPAIR", "FAILED", "INVALID_PROVIDER_JSON"),
    ]
    assert data["durationMs"] == 14
    assert len(gateway.operations) == 2


def test_contract_fact_v2_returns_failed_batch_for_invalid_top_level_schema() -> None:
    class InvalidSchemaGateway(ContractFactV2Gateway):
        def extract_contract_facts(self, operation: ContractFactOperation) -> ModelCompletion:
            self.operations.append(operation)
            return ModelCompletion(
                payload={"unexpected": []}, model="fact-model-test",
                revision="revision-test", duration_ms=5,
            )

    response = TestClient(create_app(
        Settings(contract_fact_extraction_enabled=True, model_base_url=PROVIDER_BASE_URL),
        model_gateway=InvalidSchemaGateway([]),
    )).post("/ai/v2/agent/contract-fact-candidates", json=request_body_v2())

    assert response.status_code == 200
    data = response.json()["data"]
    assert data["batchStatus"] == "FAILED"
    assert data["candidates"] == []
    assert [(row["providerOrdinal"], row["stage"], row["reasonCode"])
            for row in data["rejections"]] == [
        (1, "TYPED_SCHEMA", "INVALID_RESPONSE_SCHEMA")
    ]
    assert [(call["outcome"], call["safeReason"]) for call in data["modelCalls"]] == [
        ("FAILED", "INVALID_RESPONSE_SCHEMA"), ("FAILED", "INVALID_RESPONSE_SCHEMA"),
    ]


class ContractFactGateway:
    def __init__(self) -> None:
        self.operations: list[ContractFactOperation] = []

    def extract_contract_facts(self, operation: ContractFactOperation) -> ModelCompletion:
        self.operations.append(operation)
        excerpt = "67000.00"
        start = TEXT.index(excerpt)
        return ModelCompletion(
            payload={"candidates": [{
                "factType": "CONTRACT_AMOUNT",
                "groupKey": None,
                "valueText": None,
                "valueDecimal": excerpt,
                "segmentId": "segment-1",
                "sourceLocator": "parse:102:canonical-codepoint:0-30",
                "excerpt": excerpt,
                "excerptStart": start,
                "excerptEnd": start + len(excerpt),
                "contextCode": "CONTRACT_AMOUNT",
                "confidence": 0.96,
            }]},
            model="fact-model-test",
            revision="revision-test",
            duration_ms=9,
        )


def test_contract_fact_extraction_is_disabled_by_default() -> None:
    response = TestClient(create_app(Settings(), model_gateway=ContractFactGateway())).post(
        "/ai/v1/agent/contract-fact-candidates", json=request_body()
    )

    assert response.status_code == 503
    assert response.json()["code"] == "CONTRACT_FACT_EXTRACTION_DISABLED"


def test_contract_fact_route_returns_server_validated_candidate_and_provenance() -> None:
    gateway = ContractFactGateway()
    response = TestClient(create_app(
        Settings(contract_fact_extraction_enabled=True, model_base_url=PROVIDER_BASE_URL), model_gateway=gateway
    )).post(
        "/ai/v1/agent/contract-fact-candidates",
        headers={"X-Request-Id": "contract-fact-test"},
        json=request_body(),
    )

    assert response.status_code == 200
    data = response.json()["data"]
    assert data["schemaVersion"] == "contract-fact-candidates-v1"
    assert data["promptVersion"] == "contract-fact-prompt-v1"
    assert data["modelProvider"].startswith("OPENAI_COMPATIBLE_ENDPOINT_SHA256:")
    assert data["modelName"] == "fact-model-test"
    assert data["modelRevision"] == "revision-test"
    assert data["durationMs"] == 9
    candidate = data["candidates"][0]
    assert candidate["candidateKey"].startswith("cfc-v1-")
    assert candidate["factType"] == "CONTRACT_AMOUNT"
    assert candidate["valueDecimal"] == "67000.00"
    assert candidate["excerpt"] == "67000.00"
    assert gateway.operations[0].request_id == "contract-fact-test"
    assert gateway.operations[0].project_id == request_body()["projectId"]


def test_contract_fact_route_retries_malformed_provider_json_once() -> None:
    class RetryingGateway(ContractFactGateway):
        def extract_contract_facts(self, operation: ContractFactOperation) -> ModelCompletion:
            if not self.operations:
                self.operations.append(operation)
                raise ModelGatewayFailure(
                    reason="INVALID_PROVIDER_JSON", retryable=True, duration_ms=7
                )
            return super().extract_contract_facts(operation)

    gateway = RetryingGateway()
    response = TestClient(create_app(
        Settings(contract_fact_extraction_enabled=True, model_base_url=PROVIDER_BASE_URL),
        model_gateway=gateway,
    )).post("/ai/v1/agent/contract-fact-candidates", json=request_body())

    assert response.status_code == 200
    assert len(gateway.operations) == 2
    assert gateway.operations[0].transport_attempt == 1
    assert gateway.operations[0].max_transport_attempts == 2
    assert gateway.operations[0].transport_retry_reason is None
    assert gateway.operations[1].transport_attempt == 2
    assert gateway.operations[1].max_transport_attempts == 2
    assert gateway.operations[1].transport_retry_reason == "INVALID_PROVIDER_JSON"


def test_contract_fact_route_stops_after_one_malformed_json_retry() -> None:
    class FailingGateway(ContractFactGateway):
        def extract_contract_facts(self, operation: ContractFactOperation) -> ModelCompletion:
            self.operations.append(operation)
            raise ModelGatewayFailure(
                reason="INVALID_PROVIDER_JSON", retryable=True, duration_ms=7
            )

    gateway = FailingGateway()
    response = TestClient(create_app(
        Settings(contract_fact_extraction_enabled=True, model_base_url=PROVIDER_BASE_URL),
        model_gateway=gateway,
    )).post("/ai/v1/agent/contract-fact-candidates", json=request_body())

    assert response.status_code == 503
    assert response.json()["code"] == "CONTRACT_FACT_UNAVAILABLE"
    assert len(gateway.operations) == 2


def test_contract_fact_malformed_json_retry_uses_compact_non_thinking_request() -> None:
    captured: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured.update(json.loads(request.content))
        return httpx.Response(200, json={
            "model": "fact-model-test",
            "choices": [{"message": {"content": '{"candidates":[]}'}}],
        })

    gateway = OpenAiCompatibleModelGateway(
        Settings(
            model_base_url=PROVIDER_BASE_URL,
            model_api_key="test-only-key",
            model_name="fact-model-test",
        ),
        transport=httpx.MockTransport(handler),
    )
    gateway.extract_contract_facts(ContractFactOperation(
        project_id=request_body()["projectId"],
        file_id=request_body()["fileId"],
        file_sha256="a" * 64,
        parse_result_id=request_body()["parseResultId"],
        parse_version=3,
        canonical_text_sha256="b" * 64,
        source_text="[]",
        request_id="fact-retry-test",
        transport_attempt=2,
        max_transport_attempts=2,
        transport_retry_reason="INVALID_PROVIDER_JSON",
    ))

    system_prompt = captured["messages"][0]["content"]
    assert "prior provider response was incomplete or malformed JSON" in system_prompt
    assert "at most one best-supported candidate" in system_prompt
    assert captured["temperature"] == 0
    assert captured["max_tokens"] == 4096
    assert captured["reasoning_effort"] == "none"
    assert "chat_template_kwargs" not in captured


def test_contract_fact_typed_repair_names_exact_scope_literals_and_shapes() -> None:
    captured: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured.update(json.loads(request.content))
        return httpx.Response(200, json={
            "model": "fact-model-test",
            "choices": [{"message": {"content": '{"candidates":[]}'}}],
        })

    gateway = OpenAiCompatibleModelGateway(
        Settings(
            model_base_url=PROVIDER_BASE_URL,
            model_api_key="test-only-key",
            model_name="fact-model-test",
        ),
        transport=httpx.MockTransport(handler),
    )
    gateway.extract_contract_facts(ContractFactOperation(
        project_id=request_body_v2("AWARD")["projectId"],
        file_id=request_body_v2("AWARD")["fileId"],
        file_sha256="a" * 64,
        parse_result_id=request_body_v2("AWARD")["parseResultId"],
        parse_version=3,
        canonical_text_sha256="b" * 64,
        source_text="[]",
        request_id="fact-typed-repair-test",
        transport_attempt=2,
        max_transport_attempts=2,
        transport_retry_reason="INVALID_TYPED_SCHEMA",
        scope="AWARD",
        protocol_version=2,
    ))

    system_prompt = captured["messages"][0]["content"]
    assert '"factType":"AWARD_AMOUNT"' in system_prompt
    assert '"factType":"AWARD_SUPPLIER"' in system_prompt
    assert "WINNING_AMOUNT" in system_prompt
    assert "WINNING_BIDDER" in system_prompt
    assert "must never be returned" in system_prompt
    assert '"factType":"CONTRACT_NO"' in system_prompt
    assert '"factType":"CONTRACT_AMOUNT"' in system_prompt
    assert '"contextCode":"CONTRACT_HEADER"' in system_prompt
    assert '"contextCode":"CONTRACT_AMOUNT"' in system_prompt
    assert '"factType":"LINE_ITEM_NAME"' in system_prompt
    assert '"factType":"LINE_ITEM_DECLARED_AMOUNT"' in system_prompt
    assert '"groupKey":"line-1"' in system_prompt


def test_contract_fact_route_rejects_fabricated_excerpt_and_locator() -> None:
    for field, value in (("excerpt", "69000.00"), ("sourceLocator", "invented")):
        gateway = ContractFactGateway()
        original = gateway.extract_contract_facts

        def invalid(operation: ContractFactOperation, *, field=field, value=value) -> ModelCompletion:
            completion = original(operation)
            candidate = dict(completion.payload["candidates"][0])
            candidate[field] = value
            return ModelCompletion(
                payload={"candidates": [candidate]}, model=completion.model,
                revision=completion.revision, duration_ms=completion.duration_ms,
            )

        gateway.extract_contract_facts = invalid  # type: ignore[method-assign]
        response = TestClient(create_app(
            Settings(contract_fact_extraction_enabled=True, model_base_url=PROVIDER_BASE_URL), model_gateway=gateway
        )).post("/ai/v1/agent/contract-fact-candidates", json=request_body())

        assert response.status_code == 502
        assert response.json()["code"] == "CONTRACT_FACT_INVALID_RESPONSE"


def test_contract_fact_route_rejects_unknown_or_incoherent_typed_fact() -> None:
    for change in (
        {"factType": "UNAPPROVED_FACT"},
        {"valueText": "67000.00", "valueDecimal": None},
        {"contextCode": "AWARD_FINAL_RESULT"},
    ):
        gateway = ContractFactGateway()
        original = gateway.extract_contract_facts

        def invalid(operation: ContractFactOperation, *, change=change) -> ModelCompletion:
            completion = original(operation)
            candidate = {**completion.payload["candidates"][0], **change}
            return ModelCompletion(
                payload={"candidates": [candidate]}, model=completion.model,
                revision=completion.revision, duration_ms=completion.duration_ms,
            )

        gateway.extract_contract_facts = invalid  # type: ignore[method-assign]
        response = TestClient(create_app(
            Settings(contract_fact_extraction_enabled=True, model_base_url=PROVIDER_BASE_URL), model_gateway=gateway
        )).post("/ai/v1/agent/contract-fact-candidates", json=request_body())

        assert response.status_code == 502
        assert response.json()["code"] == "CONTRACT_FACT_INVALID_RESPONSE"


def test_contract_fact_rejection_logs_only_safe_schema_shape(caplog) -> None:
    sentinel = "PROVIDER-CONTENT-SENTINEL-DO-NOT-LOG"
    gateway = ContractFactGateway()

    def invalid(operation: ContractFactOperation) -> ModelCompletion:
        gateway.operations.append(operation)
        return ModelCompletion(
            payload={"candidates": [{"factType": sentinel}]},
            model="fact-model-test", revision="revision-test", duration_ms=3,
        )

    gateway.extract_contract_facts = invalid  # type: ignore[method-assign]
    with caplog.at_level(logging.INFO, logger="audit_ai.model.contract_fact"):
        response = TestClient(create_app(
            Settings(contract_fact_extraction_enabled=True, model_base_url=PROVIDER_BASE_URL),
            model_gateway=gateway,
        )).post("/ai/v1/agent/contract-fact-candidates", json=request_body())

    assert response.status_code == 502
    assert "contract fact response rejected" in caplog.text
    assert "reason=INVALID_TYPED_SCHEMA" in caplog.text
    assert "validation_error_count=" in caplog.text
    assert sentinel not in caplog.text


def test_contract_fact_route_rejects_whole_response_when_one_provider_row_is_invalid(caplog) -> None:
    sentinel = "PROVIDER-CONTENT-SENTINEL-DO-NOT-LOG"
    gateway = ContractFactGateway()
    original = gateway.extract_contract_facts

    def mixed(operation: ContractFactOperation) -> ModelCompletion:
        completion = original(operation)
        invalid = {**completion.payload["candidates"][0], "factType": sentinel}
        return ModelCompletion(
            payload={"candidates": [completion.payload["candidates"][0], invalid]},
            model=completion.model, revision=completion.revision,
            duration_ms=completion.duration_ms,
        )

    gateway.extract_contract_facts = mixed  # type: ignore[method-assign]
    with caplog.at_level(logging.INFO, logger="audit_ai.model.contract_fact"):
        response = TestClient(create_app(
            Settings(contract_fact_extraction_enabled=True, model_base_url=PROVIDER_BASE_URL),
            model_gateway=gateway,
        )).post("/ai/v1/agent/contract-fact-candidates", json=request_body())

    assert response.status_code == 502
    assert response.json()["code"] == "CONTRACT_FACT_INVALID_RESPONSE"
    assert "reason=INVALID_TYPED_SCHEMA" in caplog.text
    assert sentinel not in caplog.text


def test_contract_fact_route_rejects_whole_response_when_one_candidate_has_invalid_evidence(caplog) -> None:
    gateway = ContractFactGateway()
    original = gateway.extract_contract_facts

    def mixed(operation: ContractFactOperation) -> ModelCompletion:
        completion = original(operation)
        exact = completion.payload["candidates"][0]
        invalid = {
            **exact,
            "excerpt": "not-present-in-source",
            "excerptStart": 0,
            "excerptEnd": len("not-present-in-source"),
        }
        return ModelCompletion(
            payload={"candidates": [exact, invalid]},
            model=completion.model,
            revision=completion.revision,
            duration_ms=completion.duration_ms,
        )

    gateway.extract_contract_facts = mixed  # type: ignore[method-assign]
    with caplog.at_level(logging.INFO, logger="audit_ai.model.contract_fact"):
        response = TestClient(create_app(
            Settings(contract_fact_extraction_enabled=True, model_base_url=PROVIDER_BASE_URL),
            model_gateway=gateway,
        )).post("/ai/v1/agent/contract-fact-candidates", json=request_body())

    assert response.status_code == 502
    assert response.json()["code"] == "CONTRACT_FACT_INVALID_RESPONSE"
    assert "reason=INVALID_EVIDENCE_REF" in caplog.text


def test_contract_fact_request_rejects_wrong_segment_hash_before_provider_call() -> None:
    gateway = ContractFactGateway()
    body = request_body()
    body["segments"][0]["textSha256"] = "c" * 64

    response = TestClient(create_app(
        Settings(contract_fact_extraction_enabled=True, model_base_url=PROVIDER_BASE_URL), model_gateway=gateway
    )).post("/ai/v1/agent/contract-fact-candidates", json=body)

    assert response.status_code == 422
    assert gateway.operations == []


def test_two_line_groups_in_one_segment_remain_distinct_and_stable() -> None:
    text = "服务A 200；服务B 150"
    body = request_body()
    body["segments"][0].update({
        "text": text, "textSha256": sha256(text), "endOffset": len(text),
        "sourceLocator": "parse:102:canonical-codepoint:0-15",
    })

    class TwoLineGateway(ContractFactGateway):
        def extract_contract_facts(self, operation: ContractFactOperation) -> ModelCompletion:
            self.operations.append(operation)
            rows = []
            for group, value in (("provider-row-a", "200"), ("provider-row-b", "150")):
                start = text.index(value)
                rows.append({
                    "factType": "LINE_ITEM_DECLARED_AMOUNT", "groupKey": group,
                    "valueText": None, "valueDecimal": value, "segmentId": "segment-1",
                    "sourceLocator": "parse:102:canonical-codepoint:0-15", "excerpt": value,
                    "excerptStart": start, "excerptEnd": start + len(value),
                    "contextCode": "CONTRACT_LINE_ITEM", "confidence": 0.93,
                })
            return ModelCompletion(payload={"candidates": rows}, model="fact-model-test",
                                   revision="revision-test", duration_ms=4)

    response = TestClient(create_app(
        Settings(contract_fact_extraction_enabled=True, model_base_url=PROVIDER_BASE_URL),
        model_gateway=TwoLineGateway(),
    )).post("/ai/v1/agent/contract-fact-candidates", json=body)

    assert response.status_code == 200
    groups = [row["groupKey"] for row in response.json()["data"]["candidates"]]
    assert groups[0].startswith("line:")
    assert groups[0] != groups[1]
