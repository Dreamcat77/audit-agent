"""Literal work/final evidence boundary regressions, no live provider or database."""
import copy
import json
from types import SimpleNamespace

import httpx
import pytest

from audit_ai.config import Settings
from audit_ai.model.contract_draft import ProviderValidationIssue
from audit_ai.model.gateway import ContractTextOperation, OpenAiCompatibleModelGateway
from audit_ai.model.router import ContractAnalysisRequest, ModelApplicationService
from audit_ai.model.semantic_quality import QualityFailure, parse_scoped_draft, quality_schema
from audit_ai.model.semantic_work import _JournaledApplication


def observation(*, attention="INSUFFICIENT", unavailable=None, kind="LOCAL_NOT_SEEN"):
    return {"summary": "已检查本块，尚需全量核对。", "scopeStatus": "CHECKED_WITH_FINDINGS", "hints": [{
        "category": "CONTRACT_STRUCTURE", "attention": attention, "title": "本块结构尚待全量核对",
        "description": "本块只有清单，未能确认完整正文的条款。", "problemEvidenceIds": [],
        "comparisonEvidenceIds": ["C:S1"], "unavailableMaterialCategory": unavailable,
        "claimKind": kind, "obligationScope": {"topic": "STRUCTURE", "responsibleParty": "UNSPECIFIED", "trigger": None}}]}


@pytest.mark.parametrize("attention", ["INFO", "REVIEW"])
def test_local_observation_is_not_normal_or_review_and_reports_exact_attention(attention):
    value = observation(attention=attention)
    before = copy.deepcopy(value)
    with pytest.raises(QualityFailure) as caught:
        parse_scoped_draft(value, synthesis=False)
    error = caught.value
    assert error.code == "WORK_OBSERVATION_TYPE"
    assert error.field_path == "hints[0].attention" and error.index == 0
    assert error.safe_issues[0].allowed_values == ("INSUFFICIENT",)
    assert error.safe_issues[0].null_allowed is False
    assert value == before  # no relabelling to make an invalid response pass


def test_local_observation_cannot_claim_a_globally_missing_material():
    value = observation(unavailable="SUPPLEMENT")
    with pytest.raises(QualityFailure) as caught:
        parse_scoped_draft(value, synthesis=False)
    assert caught.value.code == "WORK_OBSERVATION_TYPE"
    assert caught.value.field_path == "hints[0].unavailableMaterialCategory"
    assert caught.value.safe_issues[0].null_allowed is True


def test_material_missing_cannot_have_a_null_material_identity():
    with pytest.raises(QualityFailure) as caught:
        parse_scoped_draft(observation(kind="MATERIAL_MISSING"), synthesis=False)
    assert caught.value.code == "MATERIAL_ABSENCE_TYPE"
    assert caught.value.field_path == "hints[0].unavailableMaterialCategory"
    assert caught.value.safe_issues[0].null_allowed is False


def test_valid_local_observation_preserves_insufficient_and_scope_witness_not_problem_quote():
    settings = Settings(model_name="offline-model")
    request = ContractAnalysisRequest.model_validate({"projectId": "offline-project",
        "semanticQualityProtocol": "contract-semantic-quality-v1", "materials": [{"fileId": "body",
        "category": "CONTRACT_BODY", "text": "采购清单\n序号\t名称\t数量\t单价\nMX-101\t合成部件\t若干\t待核对。"}]})
    catalog = ModelApplicationService(settings, object())._contract_evidence_catalog(request)
    probe = SimpleNamespace(operation=SimpleNamespace(purpose="ANALYZE"), quality=None,
                            validation_issues=[], normalized=None)
    app = _JournaledApplication(settings, probe, "contract-semantic-quality-v1")
    value = observation()
    before = copy.deepcopy(value)
    result = app._normalize_contract_result(value, {}, {"CONTRACT_BODY"}, catalog, {"C:S1"},
        strict_machine_contract=True, operation_key="offline-operation", request_id="offline-request")
    assert value == before
    assert result[1] == 0 and not result[2]
    hint = result[0].hints[0]
    assert hint.attention == "INSUFFICIENT" and hint.evidence_refs == []
    assert hint.problem_evidence_ids == () and hint.comparison_evidence_ids == ("C:S1",)
    assert probe.quality["workObservations"][0]["type"] == "SCOPE_WITNESS_NOT_FINAL_EVIDENCE"


def test_final_synthesis_still_rejects_local_observation_instead_of_publishing_it():
    value = observation()
    value.pop("scopeStatus")
    value["resolutions"] = []
    with pytest.raises(QualityFailure, match="UNRESOLVED_LOCAL_OBSERVATION"):
        parse_scoped_draft(value, synthesis=True)


def wire(*, purpose="ANALYZE", attempt=1, quality=True, feedback=()):
    requests = []
    def respond(request):
        requests.append(json.loads(request.content))
        return httpx.Response(200, json={"model": "offline-model", "choices": [{"finish_reason": "stop",
            "message": {"content": '{"summary":"已检查。","hints":[]}'}}]})
    gateway = OpenAiCompatibleModelGateway(Settings(model_name="offline-model",
        model_base_url="https://offline.invalid/v1", model_api_key="synthetic-key"),
        transport=httpx.MockTransport(respond))
    gateway.analyze_contract(ContractTextOperation(project_id="offline-project", chunk_index=1,
        chunk_count=7, source_text="immutable-synthetic-source", purpose=purpose, attempt=attempt,
        max_attempts=2, generation_policy="contract-semantic-work-v3",
        quality_protocol="contract-semantic-quality-v1" if quality else None,
        globally_available_material_categories=("CONTRACT_BODY", "AWARD_NOTICE", "BID_RESPONSE", "TENDER_DOCUMENT"),
        current_operation_material_categories=("AWARD_NOTICE",), validation_feedback=feedback))
    assert len(requests) == 1
    return requests[0]


def decode_marker(text, marker):
    assert marker in text, "actual provider request must encode the stage-valid evidence contract"
    return json.JSONDecoder().raw_decode(text.split(marker, 1)[1])[0]


@pytest.mark.parametrize("attempt", [1, 2])
def test_actual_analysis_and_repair_wire_select_private_evidence_contract_without_final_only_rule(attempt):
    request = wire(attempt=attempt)
    system = request["messages"][0]["content"]
    contract = decode_marker(system, "ANALYSIS_WORK_OBSERVATION_CONTRACT=")
    assert contract["version"] == "contract-work-claim-type-v1"
    assert contract["LOCAL_NOT_SEEN"] == {"attention": "INSUFFICIENT", "unavailableMaterialCategory": None,
        "problemEvidenceIds": "EXISTING_REAL_PROBLEM_ANCHORS_ONLY", "comparisonEvidenceIds": "CURRENT_CITABLE_SCOPE_WITNESS",
        "publication": "WORK_ONLY_REQUIRES_GLOBAL_RESOLUTION"}
    assert contract["MATERIAL_MISSING"]["scope"] == "GLOBAL_FROZEN_MATERIAL_SET"
    assert "Only when an entire required material category is absent may an INSUFFICIENT hint leave problemEvidenceIds empty" not in system
    assert request["messages"][1]["content"].endswith("immutable-synthetic-source")
    assert request["model"] == "offline-model" and request["max_tokens"] == 6144
    assert request["reasoning_effort"] == "none" and request["response_format"] == {"type": "json_object"}
    assert request.get("stream", False) is False


@pytest.mark.parametrize("purpose,quality", [("ANALYZE", False), ("SYNTHESIZE", True), ("SYNTHESIZE", False)])
def test_final_or_legacy_wire_does_not_gain_private_scope_witness_permission(purpose, quality):
    system = wire(purpose=purpose, quality=quality)["messages"][0]["content"]
    assert "ANALYSIS_WORK_OBSERVATION_CONTRACT=" not in system
    assert "problemEvidenceIds" in system and "INSUFFICIENT" in system


def test_original_evidence_invalid_receives_bounded_type_aware_regeneration_feedback():
    issue = ProviderValidationIssue("hints[5].problemEvidenceIds", 5, "EVIDENCE_SUPPORT_INVALID",
        "INVALID_SCOPED_CLAIM", category="CONTRACT_STRUCTURE", schema_version="contract-semantic-quality-v1")
    system = wire(attempt=2, feedback=(issue,))["messages"][0]["content"]
    advice = decode_marker(system, "QUALITY_REPAIR_GUIDANCE=")
    assert advice[0]["fieldPath"] == "hints[5].problemEvidenceIds"
    assert advice[0]["errorKind"] == "EVIDENCE_SUPPORT_INVALID"
    assert advice[0]["action"] == "REGENERATE_WITH_STAGE_VALID_CLAIM_AND_EVIDENCE_TYPE"
    assert advice[0]["scope"] == "CURRENT_OPERATION_EVIDENCE"


def test_analysis_schema_encodes_same_local_attention_and_null_cross_field_rule():
    schema = quality_schema(synthesis=False)
    rules = schema["$defs"]["QualityHint"].get("allOf", [])
    assert any(rule["if"]["properties"]["claimKind"] == {"const": "LOCAL_NOT_SEEN"}
        and rule["then"]["properties"]["attention"] == {"const": "INSUFFICIENT"}
        and rule["then"]["properties"]["unavailableMaterialCategory"] == {"type": "null"} for rule in rules)


@pytest.mark.parametrize("attempt", [1, 2])
def test_global_required_missing_material_retains_comparison_only_exception_in_wire_and_runtime(attempt):
    system = wire(attempt=attempt)["messages"][0]["content"]
    contract = decode_marker(system, "ANALYSIS_WORK_OBSERVATION_CONTRACT=")
    assert contract["MATERIAL_MISSING"]["problemEvidenceIds"] == "EMPTY_ALLOWED_FOR_GENUINE_GLOBAL_MATERIAL_ABSENCE"
    assert "非局部INSUFFICIENT必须有真实problem" not in system
    settings = Settings(model_name="offline-model")
    request = ContractAnalysisRequest.model_validate({"projectId": "offline-project",
        "semanticQualityProtocol": "contract-semantic-quality-v1", "materials": [{"fileId": "tender",
        "category": "TENDER_DOCUMENT", "text": "采购要求：供应商须提供补充材料。"}]})
    catalog = ModelApplicationService(settings, object())._contract_evidence_catalog(request)
    probe = SimpleNamespace(operation=SimpleNamespace(purpose="ANALYZE"), quality=None,
                            validation_issues=[], normalized=None)
    app = _JournaledApplication(settings, probe, "contract-semantic-quality-v1")
    value = observation(kind="MATERIAL_MISSING", unavailable="SUPPLEMENT")
    value["hints"][0].update(title="补充材料缺失", description="未提供补充材料，需核对采购文件要求。",
        comparisonEvidenceIds=["T:S1"])
    result = app._normalize_contract_result(value, {}, {"TENDER_DOCUMENT"}, catalog, {"T:S1"},
        strict_machine_contract=True, operation_key="offline-operation", request_id="offline-request")
    hint = result[0].hints[0]
    assert hint.attention == "INSUFFICIENT" and hint.problem_evidence_ids == ()
    assert hint.comparison_evidence_ids == ("T:S1",) and not result[1] and not result[2]


def test_final_local_rejection_feedback_matches_nonnullable_final_claim_enum():
    value = observation()
    value.pop("scopeStatus")
    value["resolutions"] = []
    with pytest.raises(QualityFailure) as caught:
        parse_scoped_draft(value, synthesis=True)
    issue, = caught.value.safe_issues
    assert issue.field_path == "hints[0].claimKind" and issue.null_allowed is False
    assert set(issue.allowed_values) == set(quality_schema(synthesis=True)["$defs"]["QualityHint"]["properties"]["claimKind"]["enum"])
    assert "LOCAL_NOT_SEEN" not in issue.allowed_values
