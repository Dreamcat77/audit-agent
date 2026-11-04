"""Product protocol tests: safe coordinates, real wire feedback and restart replay."""
import copy
import json
from dataclasses import replace

import httpx
import pytest

from audit_ai.config import Settings
from audit_ai.model.contract_draft import (
    ProviderDraftValidationFailure, parse_provider_contract_draft,
    provider_contract_json_schema,
)
from audit_ai.model.gateway import ContractTextOperation, OpenAiCompatibleModelGateway
from audit_ai.model.router import ContractAnalysisRequest
from audit_ai.model.semantic_work import ContractSemanticWorkService


def payload():
    return {"summary": "核对可见约定。", "hints": [{
        "category": "CONTRACT_STRUCTURE", "attention": "INFO", "title": "结构核对",
        "description": "按可见条款核对约定。", "problemEvidenceIds": [],
        "comparisonEvidenceIds": ["C:S1"], "unavailableMaterialCategory": None,
    }]}


@pytest.mark.parametrize("field,bad,classification", [
    ("category", "PRIVATE_MATERIAL_CANARY", "UNRECOGNIZED_STRING"),
    ("attention", "正常", "UNRECOGNIZED_STRING"),
    ("unavailableMaterialCategory", "null", "NULL_SENTINEL_STRING"),
    ("category", None, "NULL"),
    ("attention", 3, "INTEGER"),
    ("problemEvidenceIds", "PRIVATE_MATERIAL_CANARY", "STRING"),
])
def test_safe_feedback_retains_location_and_authoritative_allowed_values(field, bad, classification):
    value = payload()
    value["hints"][0][field] = bad
    with pytest.raises(ProviderDraftValidationFailure) as rejected:
        parse_provider_contract_draft(value)
    issues = rejected.value.safe_issues
    assert len(issues) == 1
    issue = issues[0].as_dict()
    assert issue["fieldPath"] == "hints[0]." + field
    assert issue["itemIndex"] == 0
    assert issue["actualClass"] == classification
    assert issue["schemaVersion"] == "contract-provider-draft-v2"
    assert len(issue["errorId"]) == 64
    schema = provider_contract_json_schema()["$defs"]["ProviderHintDraft"]["properties"][field]
    expected = schema.get("enum", next((part.get("enum", []) for part in schema.get("anyOf", []) if "enum" in part), []))
    assert issue["allowedValues"] == expected
    assert issue["nullAllowed"] is (field == "unavailableMaterialCategory")
    assert "PRIVATE_MATERIAL_CANARY" not in json.dumps(issue)
    assert "正常" not in json.dumps(issue, ensure_ascii=False)


def test_diagnostics_are_bounded_and_do_not_echo_unknown_property_names():
    value = payload()
    value["hints"] *= 15
    value["hints"] = copy.deepcopy(value["hints"])
    for hint in value["hints"]:
        hint["PRIVATE_PROPERTY_CANARY"] = "PRIVATE_VALUE_CANARY"
    with pytest.raises(ProviderDraftValidationFailure) as rejected:
        parse_provider_contract_draft(value)
    assert rejected.value.error_count == 15
    assert len(rejected.value.safe_issues) == 8
    rendered = json.dumps([issue.as_dict() for issue in rejected.value.safe_issues])
    assert "PRIVATE" not in rendered
    assert all(issue.field_path.endswith("OTHER") for issue in rejected.value.safe_issues)


@pytest.mark.parametrize("purpose", ["ANALYZE", "SYNTHESIZE"])
def test_actual_wire_regeneration_contains_specific_safe_feedback_not_only_attempt(purpose):
    bodies = []
    def handler(request):
        bodies.append(json.loads(request.content))
        return httpx.Response(200, json={"model": "unit-model", "choices": [{
            "finish_reason": "stop", "message": {"content": json.dumps(payload())}}]})
    settings = Settings(model_name="unit-model", model_base_url="https://provider.invalid/v1", model_api_key="unit-key")
    gateway = OpenAiCompatibleModelGateway(settings, transport=httpx.MockTransport(handler))
    invalid = payload()
    invalid["hints"][0]["attention"] = "PRIVATE_INVALID_VALUE"
    with pytest.raises(ProviderDraftValidationFailure) as rejected:
        parse_provider_contract_draft(invalid)
    initial = ContractTextOperation(project_id="project", chunk_index=1, chunk_count=1,
        source_text="immutable-source-and-evidence", purpose=purpose,
        generation_policy="contract-semantic-work-v3", max_attempts=2)
    corrected = replace(initial, attempt=2, retry_reason="ENUM_VALUE_INVALID",
                        validation_feedback=rejected.value.safe_issues)
    gateway.analyze_contract(initial)
    gateway.analyze_contract(corrected)
    assert bodies[0] != bodies[1]
    assert bodies[0]["messages"][1] == bodies[1]["messages"][1]
    for body in bodies:
        assert body["response_format"] == {"type": "json_object"}
        assert body["max_tokens"] == 6144
        assert body["reasoning_effort"] == "none"
        assert body.get("stream", False) is False  # Original OpenAI non-stream default, unchanged.
    initial_system = bodies[0]["messages"][0]["content"]
    repaired_system = bodies[1]["messages"][0]["content"]
    assert "hints[0].attention" in repaired_system
    assert "PRIVATE_INVALID_VALUE" not in repaired_system
    assert "FULL_REGENERATION" in repaired_system
    authoritative = provider_contract_json_schema()["$defs"]["ProviderHintDraft"]["properties"]
    for field in ("category", "attention", "unavailableMaterialCategory"):
        prop = authoritative[field]
        values = prop.get("enum", next((part["enum"] for part in prop.get("anyOf", []) if "enum" in part), []))
        assert all(json.dumps(token) in initial_system for token in values)


def test_persisted_rejection_reconstructs_same_feedback_and_does_not_recall_first_attempt():
    from test_semantic_work_units import Gateway
    class InvalidThenValid(Gateway):
        def analyze_contract(self, operation):
            result = super().analyze_contract(operation)
            if operation.attempt == 1:
                value = copy.deepcopy(result.payload)
                value["hints"][0]["attention"] = "PRIVATE_INVALID_VALUE"
                return replace(result, payload=value)
            assert operation.validation_feedback[0].field_path == "hints[0].attention"
            return result
    settings = Settings(model_name="unit-model", model_chunk_chars=650, model_max_chunks=30)
    request = ContractAnalysisRequest.model_validate({"projectId": "unit-project", "materials": [
        {"fileId": "body-file", "category": "CONTRACT_BODY", "text": "交付、服务和验收按双方约定执行。"}]})
    first_gateway = InvalidThenValid()
    service = ContractSemanticWorkService(settings, first_gateway)
    plan = service.plan(request)
    first = service.advance(request, plan["planFingerprint"], [], plan["next"]["operationFingerprint"],
                            remaining_ms=800_000, execute=True)
    assert first["checkpoint"]["status"] == "REPAIR_REQUIRED"
    assert first["checkpoint"]["validationIssues"][0]["fieldPath"] == "hints[0].attention"
    assert first["checkpoint"]["call"]["safeReason"] == "ENUM_VALUE_INVALID"
    restarted_gateway = InvalidThenValid()
    restarted = ContractSemanticWorkService(settings, restarted_gateway)
    second = restarted.advance(request, plan["planFingerprint"], [first["checkpoint"]],
        first["next"]["operationFingerprint"], remaining_ms=700_000, execute=True)
    assert second["checkpoint"]["status"] == "VERIFIED"
    assert len(first_gateway.operations) == 1 and len(restarted_gateway.operations) == 1
    assert restarted_gateway.operations[0].attempt == 2


def test_changed_safe_feedback_cannot_be_replayed_even_with_recomputed_checkpoint_hash():
    from audit_ai.errors import AuditAiError
    from audit_ai.model.semantic_work import checkpoint_hash
    from test_semantic_work_units import Gateway, setup, advance
    service, gateway, _, request = setup()
    original = gateway.analyze_contract
    def invalid(operation):
        completion = original(operation)
        value = copy.deepcopy(completion.payload)
        value["hints"][0]["attention"] = "INVALID"
        return replace(completion, payload=value)
    gateway.analyze_contract = invalid
    plan = service.plan(request)
    first = advance(service, request, plan, [])
    packet = copy.deepcopy(first["checkpoint"])
    packet["validationIssues"][0]["fieldPath"] = "hints[0].category"
    packet["checkpointHash"] = checkpoint_hash({k: v for k, v in packet.items() if k != "checkpointHash"})
    with pytest.raises(AuditAiError):
        advance(service, request, plan, [packet], first["next"]["operationFingerprint"], execute=False)
    assert len(gateway.operations) == 1


def test_continued_invalid_result_stops_with_two_distinct_wire_requests_and_safe_audits(caplog):
    caplog.set_level("INFO")
    from test_semantic_work_units import setup, advance
    _, _, settings, request = setup()
    bodies = []
    def handler(wire):
        bodies.append(json.loads(wire.content))
        value = payload()
        value["hints"][0]["unavailableMaterialCategory"] = "null"
        return httpx.Response(200, json={"model": "unit-model", "choices": [{
            "finish_reason": "stop", "message": {"content": json.dumps(value)}}],
            "usage": {"prompt_tokens": 100, "completion_tokens": 150, "total_tokens": 250}})
    settings = replace(settings, model_base_url="https://provider.invalid/v1", model_api_key="unit-key")
    gateway = OpenAiCompatibleModelGateway(settings, transport=httpx.MockTransport(handler))
    service = ContractSemanticWorkService(settings, gateway)
    plan = service.plan(request)
    first = advance(service, request, plan, [])
    second = advance(service, request, plan, [first["checkpoint"]], first["next"]["operationFingerprint"])
    assert len(bodies) == 2 and bodies[0] != bodies[1]
    assert second["next"] is None and second["result"] is None
    assert second["checkpoint"]["status"] == "FAILED"
    for packet in (first["checkpoint"], second["checkpoint"]):
        assert packet["call"]["safeReason"] == "ENUM_VALUE_INVALID"
        assert packet["call"]["finishReason"] == "STOP"
        assert packet["call"]["completionTokens"] == 150
        assert packet["validationIssues"][0]["actualClass"] == "NULL_SENTINEL_STRING"
    assert "hints[0].unavailableMaterialCategory" in bodies[1]["messages"][0]["content"]
    assert "NULL_SENTINEL_STRING" in caplog.text


@pytest.mark.parametrize("purpose", ["EVIDENCE_REPAIR", "SEMANTIC_COMPLETENESS_CORRECTION", "COMBINED_CORRECTION"])
def test_delta_requests_keep_their_original_protocol_and_shared_enum_authority(purpose):
    from audit_ai.model.contract_correction import contract_correction_json_schema
    observed = []
    def handler(wire):
        observed.append(json.loads(wire.content))
        return httpx.Response(200, json={"model": "unit-model", "choices": [{
            "finish_reason": "stop", "message": {"content": "{}"}}]})
    gateway = OpenAiCompatibleModelGateway(Settings(model_name="unit-model", model_api_key="unit-key",
        model_base_url="https://provider.invalid/v1"), transport=httpx.MockTransport(handler))
    gateway.analyze_contract(ContractTextOperation(project_id="unit", chunk_index=1, chunk_count=1,
        source_text="bounded-original-correction-protocol", purpose=purpose, generation_policy="contract-semantic-work-v3"))
    body = observed[0]
    assert body["reasoning_effort"] == "none" and body["max_tokens"] == 6144
    assert body["response_format"] == {"type": "json_object"}
    system = body["messages"][0]["content"]
    assert "FULL_REGENERATION" not in system
    assert "AUTHORITATIVE_CORRECTION_SCHEMA=" in system
    assert json.loads(system.split("AUTHORITATIVE_CORRECTION_SCHEMA=", 1)[1].split("\n", 1)[0]) == contract_correction_json_schema(purpose)
