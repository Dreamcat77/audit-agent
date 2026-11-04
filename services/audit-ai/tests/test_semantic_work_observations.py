"""A scope witness is private work, never invented final problem evidence."""
import copy
import json
import re
from types import SimpleNamespace

import pytest

from audit_ai.config import Settings
from audit_ai.model.evidence import EvidenceCatalog, EvidenceSegment
from audit_ai.model.gateway import ContractTextOperation
from audit_ai.model.router import ModelApplicationService, UnprocessableContractResult
from audit_ai.model.semantic_work import _JournaledApplication


def fixture(*, purpose="ANALYZE", attempt=1):
    source = "采购文件约定验收材料应齐全。"
    catalog = EvidenceCatalog([
        EvidenceSegment("T1", "tender", "TENDER_DOCUMENT", 0, len(source), source, True),
        EvidenceSegment("C1", "body", "CONTRACT_BODY", 0, 12, "乙方延期交付四周甲方有权解除。", True),
        EvidenceSegment("X1", "tender", "TENDER_DOCUMENT", len(source), len(source) + 2, "  ", False),
    ])
    operation = ContractTextOperation(project_id="synthetic-project", chunk_index=1, chunk_count=2,
        source_text="synthetic-frozen-scope", purpose=purpose, attempt=attempt, max_attempts=2)
    replay = SimpleNamespace(operation=operation, quality=None, normalized=None, validation_issues=[])
    app = _JournaledApplication(Settings(model_name="unit-model"), replay, "contract-semantic-quality-v1")
    hint = {"category": "TERM_TERMINATION", "attention": "INSUFFICIENT", "title": "本块未见解除条件",
        "description": "仅当前采购文件片段未展示解除条件，需综合合同正文核对。",
        "problemEvidenceIds": [], "comparisonEvidenceIds": ["T1"], "unavailableMaterialCategory": None,
        "claimKind": "LOCAL_NOT_SEEN", "obligationScope": {
            "topic": "TERMINATION", "responsibleParty": "PARTY_A", "trigger": "延期交付四周"}}
    return app, catalog, hint


def normalize(app, catalog, hint):
    payload = {"summary": "核对当前范围。", "hints": [hint]}
    if app.replay.operation.purpose == "SYNTHESIZE":
        payload["resolutions"] = []
    else:
        payload["scopeStatus"] = "CHECKED_WITH_FINDINGS"
    return app._normalize_contract_result(payload, {}, {"CONTRACT_BODY", "TENDER_DOCUMENT"},
        catalog, {"T1", "X1"} if app.replay.operation.purpose == "ANALYZE" else {"T1", "X1", "C1"},
        operation_key="A1" if app.replay.operation.purpose == "ANALYZE" else "S1")


@pytest.mark.parametrize("attempt", [1, 2])
def test_comparison_only_local_observation_is_verified_private_work_without_faking_problem_anchor(attempt):
    app, catalog, hint = fixture(attempt=attempt)
    before = copy.deepcopy(hint)
    result, filtered, repairs, _ = normalize(app, catalog, hint)
    assert filtered == 0 and repairs == ()
    assert result.hints[0].attention == "INSUFFICIENT"
    assert result.hints[0].problem_evidence_ids == ()
    assert result.hints[0].comparison_evidence_ids == ("T1",)
    assert type(result.hints[0]).__name__ == "WorkScopeObservation"
    assert hint == before
    scope = app.replay.quality["workScope"]
    assert scope["protocol"] == "contract-work-scope-witness-v1"
    assert scope["checkedEvidenceIds"] == ["T1", "X1"]
    assert len(scope["scopeHash"]) == 64
    assert app.replay.quality["workObservations"] == [{"hintIndex": 0,
        "type": "SCOPE_WITNESS_NOT_FINAL_EVIDENCE", "scopeHash": scope["scopeHash"],
        "witnessEvidenceIds": ["T1"]}]
    # The public final authority must continue rejecting the same comparison-only insufficient claim.
    base = {key: value for key, value in hint.items() if key not in {"claimKind", "obligationScope"}}
    public = ModelApplicationService._normalize_contract_result({"summary": "核对。", "hints": [base]}, {},
        {"CONTRACT_BODY", "TENDER_DOCUMENT"}, catalog, {"T1"}, operation_key="S1", strict_machine_contract=True)
    assert public[2] == ("S1:H1",)


@pytest.mark.parametrize("changes", [
    {"comparisonEvidenceIds": []}, {"comparisonEvidenceIds": ["C1"]},
    {"comparisonEvidenceIds": ["X1"]}, {"comparisonEvidenceIds": ["FOREIGN"]},
    {"attention": "INFO"}, {"unavailableMaterialCategory": "BID_RESPONSE"},
    {"title": " \t "}, {"description": " \t "},
    {"description": "本块应支付违约金100元。"}, {"description": "本块合同合法有效。"},
])
def test_private_work_type_is_not_a_safety_or_identity_escape(changes):
    app, catalog, hint = fixture()
    hint.update(changes)
    with pytest.raises(UnprocessableContractResult):
        normalize(app, catalog, hint)


def test_nonlocal_comparison_only_insufficient_still_requires_real_problem_support():
    app, catalog, hint = fixture()
    hint["claimKind"] = "AMBIGUOUS_CLAUSE"
    with pytest.raises(UnprocessableContractResult):
        normalize(app, catalog, hint)


@pytest.mark.parametrize("attempt", [1, 2])
def test_final_synthesis_and_its_repair_cannot_publish_local_work_observation(attempt):
    app, catalog, hint = fixture(purpose="SYNTHESIZE", attempt=attempt)
    with pytest.raises(UnprocessableContractResult):
        normalize(app, catalog, hint)


def test_local_scope_witness_need_not_be_promoted_to_final_problem_evidence():
    from audit_ai.model.semantic_quality import reconcile_observations
    app, catalog, original = fixture()
    normalize(app, catalog, original)
    from audit_ai.model.semantic_work import WORK_SCOPE_PROTOCOL
    original["workScopeWitness"] = {"protocol": WORK_SCOPE_PROTOCOL, **app.replay.quality["workObservations"][0]}
    final = copy.deepcopy(original)
    final.pop("workScopeWitness")
    final.update(claimKind="AMBIGUOUS_CLAUSE", title="解除条件需核对",
        description="合同正文条款与所需条件的对应关系需确认。", problemEvidenceIds=["C1"], comparisonEvidenceIds=[])
    proof = reconcile_observations([{"hints": [original]}], {"hints": [final], "resolutions": [{
        "observation": [0, 0], "disposition": "NEEDS_VERIFICATION", "finalHintIndexes": [0],
        "basisEvidenceIds": ["C1"], "reason": "原采购文件范围见证留工作轨迹，最终结论引用实际待核实条款。"}]},
        allowed_evidence_ids={"T1", "C1"}, catalog=catalog)
    assert proof[0]["basisEvidenceIds"] == ["C1"]
    assert original["comparisonEvidenceIds"] == ["T1"]


@pytest.mark.parametrize("change", [None, "party", "topic", "trigger", "unknown"])
def test_verified_private_local_type_not_wording_controls_same_obligation_counterevidence(change):
    from audit_ai.model.semantic_quality import QualityFailure, reconcile_observations
    from audit_ai.model.semantic_work import WORK_SCOPE_PROTOCOL
    app, catalog, original = fixture()
    original.update(title="解除条件待全局核对", description="采购要求需结合合同正文综合核对解除约定。")
    normalize(app, catalog, original)
    original["workScopeWitness"] = {"protocol": WORK_SCOPE_PROTOCOL, **app.replay.quality["workObservations"][0]}
    final = copy.deepcopy(original)
    final.pop("workScopeWitness")
    final.update(claimKind="CLAUSE_PRESENT", attention="INFO", title="解除条件已载明",
        description="合同正文约定乙方延期交付四周后甲方有权解除。", comparisonEvidenceIds=["C1"])
    if change == "party": final["obligationScope"]["responsibleParty"] = "PARTY_B"
    elif change == "topic": final["obligationScope"]["topic"] = "ACCEPTANCE"
    elif change == "trigger": final["obligationScope"]["trigger"] = "质量缺陷"
    elif change == "unknown":
        original["obligationScope"]["responsibleParty"] = final["obligationScope"]["responsibleParty"] = "UNSPECIFIED"
    payload = {"hints": [final], "resolutions": [{"observation": [0, 0], "disposition": "COUNTEREVIDENCE",
        "finalHintIndexes": [0], "basisEvidenceIds": ["C1"], "reason": "同一义务的真实正文条款反证。"}]}
    if change is None:
        assert reconcile_observations([{"hints": [original]}], payload,
            allowed_evidence_ids={"T1", "C1"}, catalog=catalog)[0]["disposition"] == "COUNTEREVIDENCE"
    else:
        with pytest.raises(QualityFailure, match="OBLIGATION_SCOPE"):
            reconcile_observations([{"hints": [original]}], payload,
                allowed_evidence_ids={"T1", "C1"}, catalog=catalog)


def service_fixture(*, initial_invalid=False, invalid_synthesis=False):
    from audit_ai.model.gateway import ModelCompletion
    from audit_ai.model.router import ContractAnalysisRequest
    from audit_ai.model.semantic_work import ContractSemanticWorkService
    class Provider:
        def __init__(self):
            self.calls = []
        def analyze_contract(self, operation):
            self.calls.append(operation)
            _, _, local = fixture()
            if operation.purpose == "ANALYZE":
                ids = re.findall(r"\[evidenceId=([^;]+);", operation.source_text)
                local["comparisonEvidenceIds"] = ids[:1]
                if operation.chunk_index != 1:
                    local.update(claimKind="CLAUSE_PRESENT", attention="INFO", title="解除条件已载明",
                        description="合同正文可见乙方延期交付四周后甲方有权解除。")
                if initial_invalid and len(self.calls) == 1:
                    local.update(claimKind="MATERIAL_MISSING", unavailableMaterialCategory="CONTRACT_BODY")
                payload = {"summary": "检查当前全部片段。", "hints": [local], "scopeStatus": "CHECKED_WITH_FINDINGS"}
            else:
                source = json.loads(operation.source_text)
                assert source["workScopeRegistry"]["protocol"] == "contract-work-scope-witness-v1"
                assert len(source["workScopeRegistry"]["units"]) == len(source["partialResults"])
                entries = source["evidenceCatalog"]
                assert isinstance(entries, list)
                body = next(row["evidenceId"] for row in entries if row["fileId"] == "body")
                hints = []
                for category in operation.required_categories:
                    item = copy.deepcopy(local)
                    item.update(category=category, claimKind="CLAUSE_PRESENT", attention="INFO",
                        title="可见条款核对", description="按冻结原文核对本次约定。", comparisonEvidenceIds=[body])
                    hints.append(item)
                target = next(i for i, item in enumerate(hints) if item["category"] == "TERM_TERMINATION")
                if invalid_synthesis:
                    hints[target].update(claimKind="LOCAL_NOT_SEEN", attention="INSUFFICIENT")
                payload = {"summary": "按全部检查范围综合。", "hints": hints, "resolutions": [
                    {"observation": [p, h], "disposition": "COUNTEREVIDENCE" if p == 0 else "RETAINED",
                     "finalHintIndexes": [target], "basisEvidenceIds": [body], "reason": "同一明确义务在合同正文有依据。"}
                    for p, partial in enumerate(source["partialResults"]) for h, _ in enumerate(partial["hints"])]}
            return ModelCompletion(payload=payload, model="unit-model", duration_ms=7, finish_reason="STOP")
    provider = Provider()
    settings = Settings(model_name="unit-model", model_chunk_chars=650, model_max_chunks=30)
    # Two distinct batches; no fixture truth, foreign Run or changed evidence identity.
    request = ContractAnalysisRequest.model_validate({"projectId": "synthetic-project",
        "semanticQualityProtocol": "contract-semantic-quality-v1", "materials": [
            {"fileId": "tender", "category": "TENDER_DOCUMENT", "text": "采购文件约定验收材料应齐全。\n" * 25},
            {"fileId": "body", "category": "CONTRACT_BODY", "text": "乙方延期交付四周甲方有权解除。\n" * 25}]})
    service = ContractSemanticWorkService(settings, provider)
    return service, provider, request, service.plan(request)


@pytest.mark.parametrize("initial_invalid", [False, True])
def test_real_work_chain_keeps_scope_after_reopen_and_only_publishes_strict_synthesis(initial_invalid):
    service, provider, request, plan = service_fixture(initial_invalid=initial_invalid)
    checkpoints, expected = [], plan["next"]["operationFingerprint"]
    for _ in range(plan["callLimit"]):
        result = service.advance(request, plan["planFingerprint"], checkpoints, expected,
            remaining_ms=800_000, execute=True)
        packet = result["checkpoint"]
        assert packet["status"] in {"VERIFIED", "REPAIR_REQUIRED"}, packet.get("validationIssues")
        checkpoints.append(packet)
        if result["result"] is not None:
            assert all(hint["attention"] == "INFO" for hint in result["result"]["hints"])
            assert all("workScope" not in str(hint) for hint in result["result"]["hints"])
            assert checkpoints[-1]["quality"]["resolutions"][0]["disposition"] == "COUNTEREVIDENCE"
            assert len(provider.calls) == len(plan["units"]) + int(initial_invalid)
            break
        assert result["result"] is None
        expected = result["next"]["operationFingerprint"]
        calls = len(provider.calls)
        reopened = service.advance(request, plan["planFingerprint"], checkpoints, expected,
            remaining_ms=790_000, execute=False)
        assert reopened["next"]["operationFingerprint"] == expected
        assert len(provider.calls) == calls
    else:
        pytest.fail("complete source chain did not publish within the original call limit")
    first = checkpoints[int(initial_invalid)]
    assert first["quality"]["checkedEvidenceIds"] == first["quality"]["workScope"]["checkedEvidenceIds"]
    assert first["validatedResult"]["hints"][0]["attention"] == "INSUFFICIENT"
    if initial_invalid:
        assert checkpoints[0]["call"]["outcome"] == "FAILED"
        assert checkpoints[0]["validationIssues"][0]["errorKind"] == "GLOBAL_MATERIAL_PRESENT"
        assert first["purpose"] == "REPAIR" and first["call"]["outcome"] == "SUCCEEDED"


@pytest.mark.parametrize("tamper", ["scope", "checked", "witness", "type", "range"])
def test_rehashing_private_scope_proof_cannot_make_it_valid_or_trigger_another_call(tamper):
    from audit_ai.errors import AuditAiError
    from audit_ai.model.semantic_work import checkpoint_hash
    service, provider, request, plan = service_fixture()
    result = service.advance(request, plan["planFingerprint"], [], plan["next"]["operationFingerprint"],
        remaining_ms=800_000, execute=True)
    packet = copy.deepcopy(result["checkpoint"])
    assert packet["status"] == "VERIFIED"
    if tamper == "scope": packet["quality"]["workScope"]["scopeHash"] = "0" * 64
    elif tamper == "checked": packet["quality"]["checkedEvidenceIds"] = ["FOREIGN"]
    elif tamper == "witness": packet["quality"]["workObservations"][0]["witnessEvidenceIds"] = ["FOREIGN"]
    elif tamper == "type": packet["quality"]["workObservations"][0]["type"] = "FINAL_FINDING"
    else: packet["quality"]["workScope"]["evidence"][0]["end"] += 1
    packet["checkpointHash"] = checkpoint_hash({key: value for key, value in packet.items() if key != "checkpointHash"})
    with pytest.raises(AuditAiError, match="SEMANTIC_CHECKPOINT_INVALID"):
        service.advance(request, plan["planFingerprint"], [packet], result["next"]["operationFingerprint"],
            remaining_ms=790_000, execute=False)
    assert len(provider.calls) == 1


def test_synthesis_local_failure_and_its_one_repair_stop_without_final_publication():
    service, provider, request, plan = service_fixture(invalid_synthesis=True)
    checkpoints, expected = [], plan["next"]["operationFingerprint"]
    for _ in range(plan["callLimit"]):
        result = service.advance(request, plan["planFingerprint"], checkpoints, expected,
            remaining_ms=800_000, execute=True)
        checkpoints.append(result["checkpoint"])
        assert result["result"] is None
        if result["checkpoint"]["status"] == "FAILED":
            assert result["checkpoint"]["purpose"] == "REPAIR"
            assert result["checkpoint"]["validationIssues"][0]["errorKind"] == "UNRESOLVED_LOCAL_OBSERVATION"
            assert result["next"] is None and len(provider.calls) == len(plan["units"]) + 1
            break
        expected = result["next"]["operationFingerprint"]
    else:
        pytest.fail("invalid synthesis must not continue until budget exhaustion")


def test_schema_valid_whitespace_summary_keeps_original_fallback_and_completed_call_receipt():
    from dataclasses import replace
    service, provider, request, plan = service_fixture()
    original = provider.analyze_contract
    def whitespace_summary(operation):
        completion = original(operation)
        return replace(completion, payload={**completion.payload, "summary": " \t "})
    provider.analyze_contract = whitespace_summary
    result = service.advance(request, plan["planFingerprint"], [], plan["next"]["operationFingerprint"],
        remaining_ms=800_000, execute=True)
    assert result["checkpoint"]["status"] == "VERIFIED"
    assert result["checkpoint"]["validatedResult"]["summary"] == "已完成合同文字语义分析。"
    assert result["checkpoint"]["call"]["outcome"] == "SUCCEEDED"
    assert result["checkpoint"]["call"]["durationMs"] == 7 and len(provider.calls) == 1
    assert result["result"] is None


def test_hint_key_encoding_preserves_absent_versus_null_full_values_and_distinct_tail_observations():
    from audit_ai.model.semantic_work import _compact_quality_hints
    first = {"category": "TERM_TERMINATION", "attention": "INSUFFICIENT", "title": "同名观察",
        "description": "不含解除条件的范围见证😀", "problemEvidenceIds": [], "comparisonEvidenceIds": ["T1"], "q": [1, 0]}
    second = {**first, "comparisonEvidenceIds": ["T2"], "unavailableMaterialCategory": None}
    original = {"partialResults": [{"summary": "完整末尾😀", "hints": [first, second]}],
        "evidenceCatalog": [], "omittedHintCount": 0, "qualityRegistry": {"claimKinds": ["CLAUSE_PRESENT", "LOCAL_NOT_SEEN"]},
        "workScopeRegistry": {"protocol": "contract-work-scope-witness-v1", "units": []}}
    before = copy.deepcopy(original)
    encoded = _compact_quality_hints(original)
    decoded = {key: copy.deepcopy(value) for key, value in encoded.items() if key not in {"qualityHintEncoding", "hintLayouts"}}
    for partial in decoded["partialResults"]:
        partial["hints"] = [dict(zip(encoded["hintLayouts"][row[0]], row[1:], strict=True)) for row in partial["hints"]]
    assert original == before and decoded == before
    assert list(decoded["partialResults"][0]["hints"][0]) == list(first)
    assert list(decoded["partialResults"][0]["hints"][1]) == list(second)
    assert "unavailableMaterialCategory" not in decoded["partialResults"][0]["hints"][0]
    assert decoded["partialResults"][0]["hints"][1]["unavailableMaterialCategory"] is None
    assert [hint["comparisonEvidenceIds"] for hint in decoded["partialResults"][0]["hints"]] == [["T1"], ["T2"]]
