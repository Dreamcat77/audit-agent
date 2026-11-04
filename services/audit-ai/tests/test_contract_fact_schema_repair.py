"""Fail closed on malformed CORE envelopes; only actual corrected output can be empty."""

import logging
import json

import httpx
import pytest

from audit_ai.config import Settings
from audit_ai.model.contract_fact import ContractFactRequestV2, ContractFactServiceV2
from audit_ai.model.gateway import ModelCompletion, ModelGatewayFailure, OpenAiCompatibleModelGateway
from test_contract_fact_candidate_route import request_body_v2, v2_candidate


class Responses:
    def __init__(self, *payloads):
        self.payloads = iter(payloads)
        self.operations = []

    def extract_contract_facts(self, operation):
        self.operations.append(operation)
        payload = next(self.payloads)
        if isinstance(payload, Exception):
            raise payload
        return ModelCompletion(payload=payload, model="synthetic-fact-model", duration_ms=7,
                               finish_reason="STOP", prompt_tokens=20, completion_tokens=2,
                               total_tokens=22)


def extract(gateway, *, remaining=900000):
    body = request_body_v2("CORE")
    body.update(batchOrdinal=5, batchCount=7, remainingBudgetMs=remaining)
    return ContractFactServiceV2(Settings(contract_fact_extraction_enabled=True,
                                        model_base_url="http://synthetic.invalid/v1",
                                        model_name="synthetic-fact-model"), gateway).extract(
        ContractFactRequestV2.model_validate(body), "schema-repair-offline"
    ).data


@pytest.mark.parametrize("payload", [
    {},
    {"candidates": None}, {"candidates": {}}, {"candidates": "synthetic-private-value"},
    {"candidates": [], "synthetic-private-field": "synthetic-private-value"},
    {"candidates": [], "lineItems": None}, {"lineItems": []},
])
def test_malformed_top_level_is_repaired_once_from_actual_provider_output(payload):
    gateway = Responses(payload, {"candidates": []})
    result = extract(gateway)
    assert result.batch_status == "EMPTY"
    assert result.candidates == []
    assert [row.reason_code for row in result.rejections] == ["INVALID_RESPONSE_SCHEMA"]
    assert [(call.ordinal, call.phase, call.outcome, call.safe_reason) for call in result.model_calls] == [
        (1, "TRANSPORT", "FAILED", "INVALID_RESPONSE_SCHEMA"),
        (2, "REPAIR", "SUCCEEDED", None),
    ]
    assert result.duration_ms == 14
    assert len(gateway.operations) == 2
    first, repair = gateway.operations
    assert repair.transport_retry_reason == "INVALID_RESPONSE_SCHEMA"
    assert (repair.scope, repair.batch_ordinal, repair.batch_count) == ("CORE", 5, 7)
    assert repair.source_text == first.source_text
    assert repair.parse_result_id == first.parse_result_id


@pytest.mark.parametrize("payload,path,error_type,actual_type", [
    ({}, "candidates", "missing", "dict"),
    ({"candidates": None}, "candidates", "list_type", "NoneType"),
    ({"candidates": [] , "synthetic-private-field": "synthetic-private-value"},
     "unknown", "extra_forbidden", "str"),
])
def test_invalid_repair_stays_failed_and_logs_only_safe_field_paths(
    payload, path, error_type, actual_type, caplog,
):
    gateway = Responses(payload, payload)
    with caplog.at_level(logging.INFO, logger="audit_ai"):
        result = extract(gateway)
    assert result.batch_status == "FAILED"
    assert result.candidates == []
    assert len(gateway.operations) == 2
    assert [call.safe_reason for call in result.model_calls] == [
        "INVALID_RESPONSE_SCHEMA", "INVALID_RESPONSE_SCHEMA",
    ]
    assert f"validation_error_loc={path}" in caplog.text
    assert f"validation_error_type={error_type}" in caplog.text
    assert f"validation_actual_type={actual_type}" in caplog.text
    assert "chunk_index=5" in caplog.text
    assert "chunk_count=7" in caplog.text
    assert "synthetic-private" not in caplog.text


def test_schema_repair_can_return_grounded_candidates_but_never_complete():
    gateway = Responses({}, {"candidates": [v2_candidate()]})
    result = extract(gateway)
    assert result.batch_status == "PARTIAL"
    assert [candidate.value_decimal for candidate in result.candidates] == ["67000.00"]
    assert [call.outcome for call in result.model_calls] == ["FAILED", "SUCCEEDED"]


def test_schema_repair_with_one_invalid_candidate_cannot_publish_other_candidates():
    gateway = Responses({}, {"candidates": [v2_candidate(), v2_candidate(evidenceId="invented")]})
    result = extract(gateway)
    assert result.batch_status == "FAILED"
    assert result.candidates == []
    assert len(gateway.operations) == 2
    assert result.model_calls[-1].safe_reason == "UNKNOWN_EVIDENCE_ID"


def test_schema_repair_with_length_finish_does_not_repeat_original_batch():
    sent = []

    def provider(request):
        sent.append(request)
        return httpx.Response(200, json={
            "model": "synthetic-fact-model", "choices": [{
                "finish_reason": "stop" if len(sent) == 1 else "length",
                "message": {"content": "{}" if len(sent) == 1 else '{"candidates":[]}'},
            }],
            "usage": {"prompt_tokens": 20, "completion_tokens": 4096, "total_tokens": 4116},
        })

    settings = Settings(contract_fact_extraction_enabled=True,
                        model_base_url="http://synthetic.invalid/v1",
                        model_api_key="synthetic-key", model_name="synthetic-fact-model")
    result = extract(OpenAiCompatibleModelGateway(settings, transport=httpx.MockTransport(provider)))
    assert result.batch_status == "FAILED"
    assert result.candidates == []
    assert len(sent) == 2
    assert result.model_calls[-1].safe_reason == "OUTPUT_TOKEN_LIMIT"
    assert result.model_calls[-1].finish_reason == "LENGTH"


def test_transport_json_repair_and_schema_repair_share_two_call_limit():
    gateway = Responses(ModelGatewayFailure(reason="INVALID_PROVIDER_JSON", retryable=True,
                                           duration_ms=5), {})
    result = extract(gateway)
    assert result.batch_status == "FAILED"
    assert result.candidates == []
    assert len(gateway.operations) == 2
    assert [call.safe_reason for call in result.model_calls] == [
        "INVALID_PROVIDER_JSON", "INVALID_RESPONSE_SCHEMA",
    ]


def test_schema_repair_uses_remaining_original_budget(monkeypatch):
    elapsed = [0.0]
    monkeypatch.setattr("audit_ai.model.contract_fact.time.monotonic", lambda: elapsed[0])

    class TimedResponses(Responses):
        def extract_contract_facts(self, operation):
            completion = super().extract_contract_facts(operation)
            elapsed[0] += 0.6
            return completion

    gateway = TimedResponses({}, {"candidates": []})
    result = extract(gateway, remaining=1000)
    assert result.batch_status == "EMPTY"
    assert [operation.provider_timeout_seconds for operation in gateway.operations] == [1.0, 0.4]


def test_no_schema_repair_is_dispatched_after_total_budget_expires(monkeypatch):
    elapsed = [0.0]
    monkeypatch.setattr("audit_ai.model.contract_fact.time.monotonic", lambda: elapsed[0])

    class ExpiredResponses(Responses):
        def extract_contract_facts(self, operation):
            completion = super().extract_contract_facts(operation)
            elapsed[0] = 1.0
            return completion

    gateway = ExpiredResponses({})
    result = extract(gateway, remaining=1000)
    assert result.batch_status == "UNKNOWN"
    assert result.candidates == []
    assert len(gateway.operations) == 1
    assert [call.safe_reason for call in result.model_calls] == [
        "INVALID_RESPONSE_SCHEMA", "TOTAL_BUDGET_EXCEEDED",
    ]


@pytest.mark.parametrize("reason", ["READ_TIMEOUT", "TRANSPORT_OUTCOME_UNKNOWN", "PROVIDER_5XX"])
def test_schema_repair_with_uncertain_outcome_never_publishes_candidates(reason):
    gateway = Responses({}, ModelGatewayFailure(reason=reason, retryable=True, duration_ms=9))
    result = extract(gateway)
    assert result.batch_status == "UNKNOWN"
    assert result.candidates == []
    assert len(gateway.operations) == 2
    assert result.model_calls[-1].safe_reason == reason


@pytest.mark.parametrize("payload", [{"candidates": []}, {"candidates": [], "lineItems": []}])
def test_explicit_valid_empty_scope_does_not_spend_repair_budget(payload):
    gateway = Responses(payload)
    result = extract(gateway)
    assert result.batch_status == "EMPTY"
    assert result.rejections == []
    assert len(gateway.operations) == 1


@pytest.mark.parametrize("payload", [[], None, "synthetic-private-root", 0, True])
def test_non_object_json_root_is_rejected_at_real_gateway_boundary(payload):
    sent = []

    def provider(request):
        sent.append(request)
        return httpx.Response(200, json={
            "model": "synthetic-fact-model", "choices": [{"finish_reason": "stop",
            "message": {"content": json.dumps(payload)}}],
            "usage": {"prompt_tokens": 20, "completion_tokens": 2, "total_tokens": 22},
        })

    settings = Settings(contract_fact_extraction_enabled=True,
                        model_base_url="http://synthetic.invalid/v1",
                        model_api_key="synthetic-key", model_name="synthetic-fact-model")
    gateway = OpenAiCompatibleModelGateway(settings, transport=httpx.MockTransport(provider))
    result = extract(gateway)
    assert result.batch_status == "FAILED"
    assert result.candidates == []
    assert len(sent) == 1
    assert result.model_calls[0].safe_reason == "INVALID_PROVIDER_RESPONSE"
