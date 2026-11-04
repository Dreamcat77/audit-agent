"""Real gateway wires distinguish mutable draft regeneration from frozen deltas."""
import copy
import hashlib
import json
import re
from dataclasses import replace

import httpx
import pytest
from pydantic import ValidationError

from audit_ai.config import Settings
from audit_ai.model.contract_correction import (
    ContractCorrectionPlan, CorrectionMode, EvidenceRepairTarget,
    parse_contract_correction, render_correction_source,
)
from audit_ai.model.contract_draft import ProviderValidationIssue, parse_provider_contract_draft
from audit_ai.model.evidence import EvidenceCatalog, EvidenceSegment
from audit_ai.model.gateway import ContractTextOperation, OpenAiCompatibleModelGateway
from audit_ai.model.router import ContractAnalysisRequest, ContractChunkResult, ContractHint, ModelApplicationService
from audit_ai.model.semantic_quality import (
    QualityFailure, check_claims, parse_scoped_draft, reconcile_observations,
)
from audit_ai.model.semantic_work import ContractSemanticWorkService


TABLE = "序号\t名称\t数量\t单价\nMX-071\t合成设备\t2\t数值已移除"
SOURCE = "[evidenceId=C1;fileId=body;category=CONTRACT_BODY;citable=true]\n" + TABLE
FROZEN_TARGET_RULES = (
    "change a frozen hint to make a repair pass",
    "For evidenceRepairs, follow the target's immutable attention",
    "If the immutable hint is unsupported, do not fabricate evidence; the step must fail.",
)


def _hint(*, broad=False, evidence_id="C1"):
    return {"category": "CONTRACT_STRUCTURE", "attention": "INFO",
        "title": "合同正文结构完整" if broad else "本块表格列结构完整",
        "description": "已核对整份合同内容完整。" if broad else "本块清单含名称、数量及单价字段，不代表合同正文结构完整。",
        "problemEvidenceIds": [], "comparisonEvidenceIds": [evidence_id], "unavailableMaterialCategory": None,
        "claimKind": "CLAUSE_PRESENT", "obligationScope": {"topic": "STRUCTURE", "responsibleParty": "BOTH", "trigger": None}}


def _draft(purpose, hint):
    return {"summary": "核对合成材料范围。", "hints": [hint], **(
        {"resolutions": [{"observation": [0, 0], "disposition": "SCOPED_REPLACEMENT", "finalHintIndexes": [0],
            "basisEvidenceIds": ["C1"], "reason": "同一表格范围仅核对已有列，不外推全文。"}]} if purpose == "SYNTHESIZE"
        else {"scopeStatus": "CHECKED_WITH_FINDINGS"})}


def _issue():
    return ProviderValidationIssue("hints[0].description", 0, "EVIDENCE_SUPPORT_SCOPE", "INVALID_SCOPED_CLAIM",
        category="CONTRACT_STRUCTURE", schema_version="contract-semantic-quality-v1")


def _gateway(wires, payload):
    def provider(request):
        wires.append(json.loads(request.content))
        value = payload(wires[-1]) if callable(payload) else payload
        return httpx.Response(200, json={"model": "offline", "choices": [{"finish_reason": "stop",
            "message": {"content": json.dumps(value, ensure_ascii=False)}}]})
    return OpenAiCompatibleModelGateway(Settings(model_name="offline", model_base_url="https://provider.invalid/v1",
        model_api_key="synthetic-test-key"), transport=httpx.MockTransport(provider))


def _operation(purpose, attempt=1, *, quality="contract-semantic-quality-v1", source=SOURCE):
    return ContractTextOperation(project_id="synthetic", chunk_index=1, chunk_count=8,
        source_text=source, purpose=purpose, attempt=attempt, max_attempts=2,
        required_categories=("CONTRACT_STRUCTURE",), generation_policy="contract-semantic-work-v3",
        quality_protocol=quality, globally_available_material_categories=("CONTRACT_BODY",),
        current_operation_material_categories=("CONTRACT_BODY",),
        validation_feedback=(_issue(),) if attempt == 2 and quality else ())


def _schema(system, marker):
    return json.JSONDecoder().raw_decode(system.split(marker, 1)[1])[0]


def _catalog():
    return EvidenceCatalog([EvidenceSegment("C1", "body", "CONTRACT_BODY", 0, len(TABLE), TABLE, True)])


@pytest.mark.parametrize("purpose", ["ANALYZE", "SYNTHESIZE"])
@pytest.mark.parametrize("attempt", [1, 2])
def test_quality_whole_response_wire_does_not_freeze_the_rejected_candidate(purpose, attempt):
    wires = []; gateway = _gateway(wires, _draft(purpose, _hint()))
    completion = gateway.analyze_contract(_operation(purpose, attempt))
    wire = wires[0]; system = wire["messages"][0]["content"]
    # This checks the actual serialized request, not gateway source or a new marker.
    assert not any(rule in system for rule in FROZEN_TARGET_RULES), "full regeneration received frozen-delta instructions"
    schema = _schema(system, "AUTHORITATIVE_PROVIDER_DRAFT_SCHEMA=")
    assert set(schema["required"]) == {"summary", "hints", "resolutions" if purpose == "SYNTHESIZE" else "scopeStatus"}
    assert {"category", "attention", "title", "description"} <= set(schema["$defs"]["QualityHint"]["properties"])
    assert wire["messages"][1]["content"] == (
        "operation=CONTRACT_TEXT_ANALYSIS\n" + f"purpose={purpose}\n" + "projectId=synthetic\nchunk=1/8\n"
        "requiredCategories=CONTRACT_STRUCTURE\nallowedCategories=CONTRACT_STRUCTURE,PROJECT_IDENTITY,SUPPLIER_IDENTITY,"
        "DELIVERY_TERMS,SERVICE_WARRANTY,SCOPE_AND_SUBCONTRACTING,BREACH_LIABILITY,TERM_TERMINATION,OTHER_TEXT\n"
        'frozenMaterialScope={"version":"contract-material-scope-v1","globallyAvailableCategories":["CONTRACT_BODY"],'
        '"currentOperationCategories":["CONTRACT_BODY"],"absenceScope":"GLOBAL_FROZEN_MATERIAL_SET"}\nsource:\n' + SOURCE)
    assert wire["max_tokens"] == 6144 and wire["reasoning_effort"] == "none"
    assert wire["response_format"] == {"type": "json_object"}
    assert "absoluteMonetaryValues=FORBIDDEN" in system and "strongVerdicts=FORBIDDEN" in system
    assert "Never claim that the global frozen material set lacks" in system
    assert ("FULL_REGENERATION:" in system) is (attempt == 2)
    if attempt == 2:
        advice = _schema(system, "QUALITY_REPAIR_GUIDANCE=")
        assert advice[0]["fieldPath"] == "hints[0].description"
        assert advice[0]["action"] == "NARROW_CLAIM_TO_SUPPORTED_SCOPE_OR_GROUND_WHOLE_CLAIM"
    parsed, _ = parse_scoped_draft(completion.payload, synthesis=purpose == "SYNTHESIZE")
    assert parsed["hints"][0]["description"] == _hint()["description"]


@pytest.mark.parametrize("purpose", ["ANALYZE", "SYNTHESIZE"])
def test_legacy_nonquality_whole_response_wire_keeps_existing_rules(purpose):
    value = {"summary": "合成范围核对。", "hints": [{k: v for k, v in _hint().items() if k not in {"claimKind", "obligationScope"}}]}
    wires = []; completion = _gateway(wires, value).analyze_contract(_operation(purpose, quality=None))
    system = wires[0]["messages"][0]["content"]
    assert all(rule in system for rule in FROZEN_TARGET_RULES)
    assert set(_schema(system, "AUTHORITATIVE_PROVIDER_DRAFT_SCHEMA=")["required"]) == {"summary", "hints"}
    assert parse_provider_contract_draft(completion.payload).hints[0].description == value["hints"][0]["description"]


@pytest.mark.parametrize("mode", list(CorrectionMode))
def test_delta_wire_and_real_merge_keep_original_frozen_semantics(mode):
    hint = ContractHint(category="CONTRACT_STRUCTURE", attention="INFO", title="原合成观察", description="仅核对已提供条款。",
        evidenceRefs=[], evidenceItems=[], hint_id="A1:H1")
    targets = () if mode is CorrectionMode.SEMANTIC_COMPLETENESS_CORRECTION else (
        EvidenceRepairTarget(hint_id="A1:H1", category=hint.category, attention=hint.attention, title=hint.title, description=hint.description),)
    missing = () if mode is CorrectionMode.EVIDENCE_REPAIR else ("BREACH_LIABILITY",)
    plan = ContractCorrectionPlan(missing_required_categories=missing, evidence_repair_targets=targets)
    source = render_correction_source(plan, [hint], original_operation_source=SOURCE)
    payload = {}
    if targets: payload["evidenceRepairs"] = [{"hintId": "A1:H1", "comparisonEvidenceIds": ["C1"]}]
    if missing:
        payload["missingCategoryHints"] = [{"category": "BREACH_LIABILITY", "attention": "INFO", "title": "合成违约条款核对",
            "description": "依据已提供条款核对责任。", "comparisonEvidenceIds": ["C1"]}]
    wires = []; operation = replace(_operation(mode.value, 2, quality=None, source=source), required_categories=missing)
    completion = _gateway(wires, payload).analyze_contract(operation)
    system = wires[0]["messages"][0]["content"]
    assert all(rule in system for rule in FROZEN_TARGET_RULES) and "FULL_REGENERATION:" not in system
    schema = _schema(system, "AUTHORITATIVE_CORRECTION_SCHEMA=")
    assert set(schema["required"]) == set(payload)
    assert parse_contract_correction(completion.payload, mode=mode)
    supplied = json.loads(wires[0]["messages"][1]["content"].split("source:\n", 1)[1])
    assert supplied["originalOperationSource"] == SOURCE
    assert supplied["frozenHints"] == [{"hintId": "A1:H1", "category": "CONTRACT_STRUCTURE", "attention": "INFO",
        "title": "原合成观察", "description": "仅核对已提供条款。"}]
    first = ContractChunkResult(summary="原合成摘要。", hints=[hint]); before = first.model_dump()
    merged = ModelApplicationService._merge_contract_correction(first_result=first, payload=completion.payload, plan=plan,
        operation_key="A1", evidence_catalog=_catalog(), allowed_evidence_ids={"C1"}, available_material_categories={"CONTRACT_BODY"})
    assert first.model_dump() == before and merged.summary == first.summary
    assert [(h.category, h.attention, h.title, h.description) for h in merged.hints[:1]] == [
        ("CONTRACT_STRUCTURE", "INFO", "原合成观察", "仅核对已提供条款。")]
    illegal = copy.deepcopy(payload)
    illegal["evidenceRepairs"] = [{"hintId": "A1:H1", "description": "试图重写冻结意见", "comparisonEvidenceIds": ["C1"]}]
    with pytest.raises(ValidationError): parse_contract_correction(illegal, mode=mode)


def test_valid_analysis_regeneration_changes_candidate_not_original_checkpoint_or_source():
    wires = []
    def payload(wire):
        source = wire["messages"][1]["content"].split("source:\n", 1)[1]
        evidence_id = re.findall(r"\[evidenceId=([^;]+);", source)[0]
        return _draft("ANALYZE", _hint(broad=len(wires) == 1, evidence_id=evidence_id))
    settings = Settings(model_name="offline", model_chunk_chars=650, model_max_chunks=30)
    gateway = _gateway(wires, payload); service = ContractSemanticWorkService(settings, gateway)
    request = ContractAnalysisRequest.model_validate({"projectId": "synthetic", "semanticQualityProtocol": "contract-semantic-quality-v1",
        "materials": [{"fileId": "body", "category": "CONTRACT_BODY", "text": (TABLE + "\n") * 30}]})
    plan = service.plan(request)
    first = service.advance(request, plan["planFingerprint"], [], plan["next"]["operationFingerprint"], remaining_ms=800000, execute=True)
    assert first["checkpoint"]["status"] == "REPAIR_REQUIRED"
    assert first["checkpoint"]["validationIssues"][0]["errorKind"] == "EVIDENCE_SUPPORT_SCOPE"
    before = copy.deepcopy(first["checkpoint"])
    second = service.advance(request, plan["planFingerprint"], [first["checkpoint"]], first["next"]["operationFingerprint"],
        remaining_ms=700000, execute=True)
    assert second["checkpoint"]["status"] == "VERIFIED"
    assert first["checkpoint"] == before and len(wires) == 2
    assert wires[0]["messages"][1]["content"].split("source:\n", 1)[1] == wires[1]["messages"][1]["content"].split("source:\n", 1)[1]
    assert "regenerationContext=" in wires[1]["messages"][1]["content"]
    assert second["checkpoint"]["validatedResult"]["hints"][0]["description"] == _hint()["description"]


def test_synthesis_can_regenerate_invalid_final_candidate_without_rewriting_checked_partials():
    partials = [{"summary": "合成表格范围。", "hints": [_hint()]}]; before = copy.deepcopy(partials)
    source = json.dumps({"partialResults": partials, "evidenceCatalog": [{"evidenceId": "C1", "fileId": "body",
        "materialCategory": "CONTRACT_BODY", "citable": True, "sourceStart": 0, "sourceEnd": len(TABLE),
        "exactTextSha256": hashlib.sha256(TABLE.encode()).hexdigest()}]}, ensure_ascii=False)
    wires = []
    gateway = _gateway(wires, lambda _: _draft("SYNTHESIZE", _hint(broad=len(wires) == 1)))
    operation = _operation("SYNTHESIZE", source=source)
    first = gateway.analyze_contract(operation)
    parse_scoped_draft(first.payload, synthesis=True)
    with pytest.raises(QualityFailure, match="EVIDENCE_SUPPORT_SCOPE"):
        check_claims(first.payload, catalog=_catalog(), available_categories={"CONTRACT_BODY"}, allowed_evidence_ids={"C1"}, synthesis=True)
    second = gateway.analyze_contract(replace(operation, attempt=2, validation_feedback=(_issue(),)))
    parse_scoped_draft(second.payload, synthesis=True)
    check_claims(second.payload, catalog=_catalog(), available_categories={"CONTRACT_BODY"}, allowed_evidence_ids={"C1"}, synthesis=True)
    assert reconcile_observations(partials, second.payload, allowed_evidence_ids={"C1"}, catalog=_catalog())[0]["disposition"] == "SCOPED_REPLACEMENT"
    assert partials == before and wires[0]["messages"][1] == wires[1]["messages"][1]


@pytest.mark.parametrize("defect,code", [("whole_document", "EVIDENCE_SUPPORT_SCOPE"), ("unseen_anchor", "EVIDENCE_SCOPE"),
    ("present_material_missing", "GLOBAL_MATERIAL_PRESENT")])
def test_regeneration_never_relaxes_global_support_or_evidence_authority(defect, code):
    hint = _hint(broad=defect == "whole_document")
    if defect == "unseen_anchor": hint["comparisonEvidenceIds"] = ["UNSEEN"]
    if defect == "present_material_missing": hint["unavailableMaterialCategory"] = "CONTRACT_BODY"
    with pytest.raises(QualityFailure, match=code):
        check_claims({"hints": [hint]}, catalog=_catalog(), available_categories={"CONTRACT_BODY"}, allowed_evidence_ids={"C1"}, synthesis=True)
    assert ModelApplicationService._hint_text_filter_reason("合同金额为67000元。") is not None
    assert ModelApplicationService._hint_text_filter_reason("合同合法有效，不存在风险。") is not None
