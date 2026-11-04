"""Quality is source-scoped reconciliation, not accepting any parseable JSON."""
import copy
import json
import re

import pytest

from audit_ai.model.router import ContractAnalysisRequest


def test_quality_request_is_explicit_and_bound_to_input_not_an_environment_guess():
    request = ContractAnalysisRequest.model_validate({
        "projectId": "quality-project", "semanticQualityProtocol": "contract-semantic-quality-v1",
        "materials": [{"fileId": "body", "category": "CONTRACT_BODY", "text": "按约定交付。"}],
    })
    assert request.model_dump(by_alias=True)["semanticQualityProtocol"] == "contract-semantic-quality-v1"


def _hint(category="TERM_TERMINATION", attention="INSUFFICIENT", ids=("C1",)):
    return {"category": category, "attention": attention, "title": "本段未见交付日期",
            "description": "仅当前片段未显示日期，须结合全量约定核对。",
            "problemEvidenceIds": list(ids), "comparisonEvidenceIds": [], "unavailableMaterialCategory": None,
            "claimKind": "LOCAL_NOT_SEEN" if attention == "INSUFFICIENT" else "CLAUSE_PRESENT" if attention == "INFO" else "AMBIGUOUS_CLAUSE",
            "obligationScope": {"topic": {"TERM_TERMINATION": "DELIVERY", "DELIVERY_TERMS": "DELIVERY",
                "BREACH_LIABILITY": "DELAY_LIABILITY", "CONTRACT_STRUCTURE": "STRUCTURE"}.get(category, "OTHER"),
                "responsibleParty": "PARTY_B", "trigger": "交付"}}


def test_local_missing_observation_needs_explicit_counterevidence_resolution_not_union():
    from audit_ai.model.semantic_quality import reconcile_observations
    partials = [{"hints": [_hint()]}]
    final = _hint(attention="INFO", ids=())
    final.update(title="交付期限已约定", description="约定乙方于指定日期交付。", comparisonEvidenceIds=["C2"])
    payload = {"summary": "核对交付期限。", "hints": [final], "resolutions": [
        {"observation": [0, 0], "disposition": "COUNTEREVIDENCE", "finalHintIndexes": [0],
         "basisEvidenceIds": ["C2"], "reason": "同一主体和交付义务在后文有明确期限。"}]}
    before = copy.deepcopy(partials)
    proof = reconcile_observations(partials, payload, allowed_evidence_ids={"C1", "C2"})
    assert proof[0]["observation"] == [0, 0]
    assert proof[0]["disposition"] == "COUNTEREVIDENCE"
    assert partials == before  # old observations remain in immutable checkpoints
    assert len(payload["hints"]) == 1 and payload["hints"][0]["attention"] == "INFO"


def test_every_observation_accounted_and_unrelated_category_cannot_erase_risk():
    from audit_ai.model.semantic_quality import QualityFailure, reconcile_observations
    partials = [{"hints": [_hint(), _hint("BREACH_LIABILITY", "REVIEW")]}]
    final = _hint("TERM_TERMINATION", "INFO", ())
    final["comparisonEvidenceIds"] = ["C2"]
    payload = {"summary": "核对。", "hints": [final], "resolutions": [
        {"observation": [0, 0], "disposition": "COUNTEREVIDENCE", "finalHintIndexes": [0],
         "basisEvidenceIds": ["C2"], "reason": "后文同一交付义务。"}]}
    with pytest.raises(QualityFailure, match="OBSERVATION_COVERAGE"):
        reconcile_observations(partials, payload, allowed_evidence_ids={"C1", "C2"})
    payload["resolutions"].append({**payload["resolutions"][0], "observation": [0, 1]})
    with pytest.raises(QualityFailure, match="OBLIGATION_SCOPE"):
        reconcile_observations(partials, payload, allowed_evidence_ids={"C1", "C2"})


def test_same_category_other_party_is_not_counterevidence_and_retained_risk_keeps_original_anchor():
    from audit_ai.model.evidence import EvidenceCatalog, EvidenceSegment
    from audit_ai.model.semantic_quality import QualityFailure, reconcile_observations
    catalog = EvidenceCatalog([
        EvidenceSegment("C1", "body", "CONTRACT_BODY", 0, 11, "甲方验收期限由双方另行明确。", True),
        EvidenceSegment("C2", "body", "CONTRACT_BODY", 11, 24, "乙方应于约定日期完成交付。", True)])
    original = _hint()
    original["obligationScope"]["responsibleParty"] = "PARTY_A"
    final = _hint(attention="INFO", ids=())
    final["comparisonEvidenceIds"] = ["C2"]
    payload = {"hints": [final], "resolutions": [{"observation": [0, 0], "disposition": "COUNTEREVIDENCE",
        "finalHintIndexes": [0], "basisEvidenceIds": ["C2"], "reason": "另一条款有期限。"}]}
    with pytest.raises(QualityFailure, match="OBLIGATION_SCOPE"):
        reconcile_observations([{"hints": [original]}], payload, allowed_evidence_ids={"C1", "C2"}, catalog=catalog)
    original["attention"] = final["attention"] = "REVIEW"
    final["obligationScope"] = copy.deepcopy(original["obligationScope"])
    final["problemEvidenceIds"] = ["C2"]; final["comparisonEvidenceIds"] = []
    payload["resolutions"][0]["disposition"] = "RETAINED"
    with pytest.raises(QualityFailure, match="OBSERVATION_ANCHOR_LOST"):
        reconcile_observations([{"hints": [original]}], payload, allowed_evidence_ids={"C1", "C2"}, catalog=catalog)


def test_empty_analysis_requires_explicit_checked_scope_and_no_hidden_failure():
    from audit_ai.model.semantic_quality import QualityFailure, parse_scoped_draft
    value = {"summary": "已核对本块，仅含明细表，无相关条款观察。", "hints": []}
    with pytest.raises(QualityFailure, match="SCOPE_STATUS"):
        parse_scoped_draft(value, synthesis=False)
    value["scopeStatus"] = "CHECKED_NO_RELEVANT_FINDINGS"
    draft, metadata = parse_scoped_draft(value, synthesis=False)
    assert draft["hints"] == [] and metadata["scopeStatus"] == "CHECKED_NO_RELEVANT_FINDINGS"
    value["hints"] = [_hint()]
    with pytest.raises(QualityFailure, match="SCOPE_STATUS"):
        parse_scoped_draft(value, synthesis=False)


@pytest.mark.parametrize("contradictory_final", [False, True])
def test_real_work_service_publishes_only_reconciled_hints_and_preserves_scope_checkpoint(contradictory_final):
    from audit_ai.config import Settings
    from audit_ai.model.gateway import ModelCompletion
    from audit_ai.model.semantic_work import ContractSemanticWorkService
    class Provider:
        def __init__(self): self.calls = []
        def analyze_contract(self, operation):
            self.calls.append(operation)
            if operation.purpose == "SYNTHESIZE":
                source = json.loads(operation.source_text)
                ids = [entry["evidenceId"] for entry in source["evidenceCatalog"]]
                hints = []
                for category in operation.required_categories:
                    hint = _hint(category, "INFO", ())
                    hint.update(title="可见条款核对", description="按冻结证据核对本次约定。", comparisonEvidenceIds=ids[:1])
                    hints.append(hint)
                target = next(i for i, h in enumerate(hints) if h["category"] == "TERM_TERMINATION")
                payload = {"summary": "全量条款已核对。", "hints": hints,
                    "resolutions": [{"observation": [p, h], "disposition": "RETAINED",
                        "finalHintIndexes": [target], "basisEvidenceIds": ids[:1], "reason": "同一交付范围汇总。"}
                        for p, partial in enumerate(source["partialResults"]) for h, _ in enumerate(partial["hints"])]}
                if contradictory_final:
                    missing = _hint("DELIVERY_TERMS")
                    missing.update(title="交付条款缺失", description="提供的证据片段中未包含具体条款。",
                        claimKind="GLOBAL_CLAUSE_NOT_FOUND", problemEvidenceIds=ids[:1])
                    hints.append(missing)
            else:
                ids = re.findall(r"\[evidenceId=([^;]+);", operation.source_text)
                hint = _hint("TERM_TERMINATION", "INFO", ())
                hint.update(title="本段期限核对", description="仅核对本段可见交付约定。", comparisonEvidenceIds=ids[:1])
                payload = {"summary": "本段条款已检查。", "hints": [hint], "scopeStatus": "CHECKED_WITH_FINDINGS"}
            return ModelCompletion(payload=payload, model="unit-model", duration_ms=10, finish_reason="STOP")
    provider = Provider()
    service = ContractSemanticWorkService(Settings(model_name="unit-model", model_chunk_chars=650, model_max_chunks=30), provider)
    request = ContractAnalysisRequest.model_validate({"projectId": "quality-project", "semanticQualityProtocol": "contract-semantic-quality-v1",
        "materials": [{"fileId": "body", "category": "CONTRACT_BODY", "text": "交付与期限按双方约定执行。\n" * 55}]})
    plan = service.plan(request); checkpoints = []; expected = plan["next"]["operationFingerprint"]
    while True:
        packet = service.advance(request, plan["planFingerprint"], checkpoints, expected, remaining_ms=800_000, execute=True)
        if contradictory_final and packet["checkpoint"]["status"] == "FAILED":
            assert packet["result"] is None
            assert packet["checkpoint"]["validationIssues"][0]["errorKind"] == "FINAL_CLAIM_CONTRADICTION"
            assert len(provider.calls) == len(plan["units"]) + 1
            return
        assert packet["checkpoint"]["status"] in ({"VERIFIED", "REPAIR_REQUIRED"} if contradictory_final else {"VERIFIED"}), packet["checkpoint"].get("call")
        checkpoints.append(packet["checkpoint"])
        if packet["result"] is not None: break
        expected = packet["next"]["operationFingerprint"]
        if len(checkpoints) == 1:
            from audit_ai.errors import AuditAiError
            from audit_ai.model.semantic_work import checkpoint_hash
            before = len(provider.calls)
            reopened = service.advance(request, plan["planFingerprint"], checkpoints, expected,
                                       remaining_ms=790_000, execute=False)
            assert reopened["next"]["operationFingerprint"] == expected and len(provider.calls) == before
            tampered = copy.deepcopy(checkpoints)
            tampered[0]["quality"]["hintMetadata"][0]["obligationScope"]["trigger"] = "篡改条件"
            tampered[0]["checkpointHash"] = checkpoint_hash({key: value for key, value in tampered[0].items() if key != "checkpointHash"})
            with pytest.raises(AuditAiError):
                service.advance(request, plan["planFingerprint"], tampered, expected, remaining_ms=790_000, execute=False)
            assert len(provider.calls) == before
    assert len(provider.calls) == len(plan["units"])
    assert len(packet["result"]["hints"]) == len(provider.calls[-1].required_categories)
    assert checkpoints[0]["quality"]["scopeStatus"] == "CHECKED_WITH_FINDINGS"
    assert checkpoints[-1]["quality"]["resolutions"]
    assert plan["policy"]["qualityProtocol"] == "contract-semantic-quality-v1"
    source = json.loads(provider.calls[-1].source_text)
    # Independently expand positional metadata; original partial text/IDs are not rewritten.
    for p, partial in enumerate(source["partialResults"]):
        for h, hint in enumerate(partial["hints"]):
            kind, scope = hint["q"]
            expanded = {"claimKind": source["qualityRegistry"]["claimKinds"][kind],
                        "obligationScope": source["qualityRegistry"]["obligationScopes"][scope]}
            assert expanded == checkpoints[p]["quality"]["hintMetadata"][h]
    responsibilities = plan["coveragePlan"]
    assert responsibilities["version"] == "contract-obligation-coverage-v1"
    assert all(row["responsibleUnitKeys"] == [plan["units"][-1]["logicalKey"]]
               for row in responsibilities["globalChecks"])
    assert {e for row in responsibilities["localScopes"] for e in row["checkedEvidenceIds"]} == {
        e["evidenceId"] for e in plan["evidence"]}


@pytest.mark.parametrize("bad_kind", ["PRESENT_BODY", "MONETARY_BREACH", "TWO_SEPARATE_UNITS"])
def test_real_correction_regenerates_original_scope_with_specific_feedback_and_keeps_failed_audit(bad_kind):
    from audit_ai.config import Settings
    from audit_ai.model.gateway import ModelCompletion
    from audit_ai.model.semantic_work import ContractSemanticWorkService
    class Provider:
        def __init__(self): self.calls = []
        def analyze_contract(self, operation):
            self.calls.append(operation)
            if operation.purpose == "SYNTHESIZE":
                source = json.loads(operation.source_text)
                entries = source["evidenceCatalog"]
                ids = [row["evidenceId"] for row in entries]
            else:
                ids = re.findall(r"\[evidenceId=([^;]+);", operation.source_text)
            categories = operation.required_categories or ("TERM_TERMINATION",)
            hints = []
            for category in categories:
                hint = _hint(category, "INFO", ())
                hint.update(title="可见条款核对", description="延期每周按约定比例承担责任，累计不超过10%。",
                            comparisonEvidenceIds=ids[:1])
                hints.append(hint)
            payload = {"summary": "核对当前条款。", "hints": hints, "scopeStatus": "CHECKED_WITH_FINDINGS"}
            if len(self.calls) == 1 or (bad_kind == "TWO_SEPARATE_UNITS" and operation.purpose == "ANALYZE"
                                       and operation.chunk_index == 2 and operation.attempt == 1):
                if bad_kind != "MONETARY_BREACH":
                    hints[0].update(category="CONTRACT_STRUCTURE", attention="INSUFFICIENT",
                        title="合同正文缺失", description="合同正文未提供。", unavailableMaterialCategory="CONTRACT_BODY")
                else:
                    hints[0].update(category="BREACH_LIABILITY", title="违约金核算", description="应支付违约金100元。")
            if operation.purpose == "SYNTHESIZE":
                payload.pop("scopeStatus")
                payload["resolutions"] = []
                for p, partial in enumerate(source["partialResults"]):
                    for h, observation in enumerate(partial["hints"]):
                        target = next(i for i, hint in enumerate(hints) if hint["category"] == observation["category"])
                        payload["resolutions"].append({"observation": [p, h], "disposition": "RETAINED",
                            "finalHintIndexes": [target], "basisEvidenceIds": ids[:1], "reason": "同一义务和条件汇总。"})
            return ModelCompletion(payload=payload, model="unit-model", duration_ms=12, finish_reason="STOP")
    gateway = Provider()
    service = ContractSemanticWorkService(Settings(model_name="unit-model", model_chunk_chars=650, model_max_chunks=30), gateway)
    request = ContractAnalysisRequest.model_validate({"projectId": "quality-project", "semanticQualityProtocol": "contract-semantic-quality-v1",
        "materials": [{"fileId": "body", "category": "CONTRACT_BODY", "text":
            "延期违约责任按合同总价的2%每周承担，累计不超过10%。\n" * 35}]})
    plan = service.plan(request); packets = []; expected = plan["next"]["operationFingerprint"]
    while True:
        value = service.advance(request, plan["planFingerprint"], packets, expected, remaining_ms=800_000, execute=True)
        packets.append(value["checkpoint"])
        assert value["checkpoint"]["status"] in {"VERIFIED", "REPAIR_REQUIRED"}, value["checkpoint"].get("call")
        if value["result"] is not None: break
        expected = value["next"]["operationFingerprint"]
    assert len(gateway.calls) == len(plan["units"]) + (2 if bad_kind == "TWO_SEPARATE_UNITS" else 1)
    assert packets[0]["call"]["outcome"] == "FAILED" and packets[1]["call"]["outcome"] == "SUCCEEDED"
    first, repair = gateway.calls[:2]
    assert first.source_text == repair.source_text
    assert repair.purpose == "ANALYZE" and repair.attempt == 2 and repair.quality_protocol
    assert repair.validation_feedback
    assert repair.validation_feedback[0].error_kind == (
        "MONETARY_VALUE" if bad_kind == "MONETARY_BREACH" else "GLOBAL_MATERIAL_PRESENT")
    assert repair.validation_feedback[0].field_path.startswith("hints[0].")
    assert repair.validation_feedback[0].category == ("BREACH_LIABILITY" if bad_kind == "MONETARY_BREACH" else "CONTRACT_STRUCTURE")


@pytest.mark.parametrize("purpose,attempt", [("ANALYZE", 1), ("ANALYZE", 2), ("SYNTHESIZE", 1), ("SYNTHESIZE", 2)])
def test_actual_quality_wire_uses_same_bounded_transport_and_stage_specific_runtime_schema(purpose, attempt):
    import httpx
    from audit_ai.config import Settings
    from audit_ai.model.gateway import ContractTextOperation, OpenAiCompatibleModelGateway
    from audit_ai.model.contract_draft import ProviderValidationIssue
    wires = []
    def provider(request):
        wires.append(json.loads(request.content))
        return httpx.Response(200, json={"model": "unit-model", "choices": [{"finish_reason": "stop", "message": {
            "content": '{"summary":"核对。","hints":[]}'}}]})
    gateway = OpenAiCompatibleModelGateway(Settings(model_name="unit-model", model_base_url="https://provider.invalid/v1", model_api_key="synthetic-test-key"),
        transport=httpx.MockTransport(provider))
    gateway.analyze_contract(ContractTextOperation(project_id="quality-project", chunk_index=1, chunk_count=2,
        source_text="immutable-synthetic-source", purpose=purpose, attempt=attempt, max_attempts=2,
        generation_policy="contract-semantic-work-v3", quality_protocol="contract-semantic-quality-v1",
        globally_available_material_categories=("CONTRACT_BODY",), current_operation_material_categories=("CONTRACT_BODY",),
        validation_feedback=() if attempt == 1 else (ProviderValidationIssue(
            "hints[0].unavailableMaterialCategory", 0, "GLOBAL_MATERIAL_PRESENT", "INVALID_SCOPED_CLAIM"),)))
    wire = wires[0]
    assert wire["model"] == "unit-model" and wire["max_tokens"] == 6144 and wire["reasoning_effort"] == "none"
    assert wire["response_format"] == {"type": "json_object"}
    assert wire.get("stream", False) is False
    system = wire["messages"][0]["content"]
    marker = "AUTHORITATIVE_PROVIDER_DRAFT_SCHEMA="
    schema = json.JSONDecoder().raw_decode(system.split(marker, 1)[1])[0]
    assert ("resolutions" in schema["required"]) is (purpose == "SYNTHESIZE")
    assert ("scopeStatus" in schema["required"]) is (purpose == "ANALYZE")
    assert "immutable-synthetic-source" in wire["messages"][1]["content"]
    if attempt == 2:
        assert "GLOBAL_MATERIAL_PRESENT" in system and "hints[0].unavailableMaterialCategory" in system


def test_exact_source_anchor_alone_does_not_prove_whole_document_structure_or_wrong_count():
    from audit_ai.model.evidence import EvidenceCatalog, EvidenceSegment
    from audit_ai.model.semantic_quality import QualityFailure, check_claims
    catalog = EvidenceCatalog([EvidenceSegment("C1", "body", "CONTRACT_BODY", 0, 19,
        "MX-009\t某设备\t数量2\t单价已移除", True)])
    hint = _hint("CONTRACT_STRUCTURE", "INFO", ())
    hint.update(title="响应文件结构完整", description="已核对全文内容完整。", comparisonEvidenceIds=["C1"])
    with pytest.raises(QualityFailure, match="EVIDENCE_SUPPORT_SCOPE"):
        check_claims({"hints": [hint]}, catalog=catalog, available_categories={"CONTRACT_BODY"}, allowed_evidence_ids={"C1"}, synthesis=True)
    hint.update(title="明细范围", description="MX-003至MX-012共12项。", category="OTHER_TEXT")
    with pytest.raises(QualityFailure, match="CLAIM_COUNT_CONTRADICTION"):
        check_claims({"hints": [hint]}, catalog=catalog, available_categories={"CONTRACT_BODY"}, allowed_evidence_ids={"C1"}, synthesis=True)


def test_explicit_local_table_columns_are_not_a_whole_document_completeness_claim():
    from audit_ai.model.evidence import EvidenceCatalog, EvidenceSegment
    from audit_ai.model.semantic_quality import QualityFailure, check_claims
    source = "采购清单\n序号\t名称\t数量\t单价\t金额\nMX-021\t设备乙\t2\t数值已移除\n"
    catalog = EvidenceCatalog([EvidenceSegment("C1", "body", "CONTRACT_BODY", 0, len(source), source, True)])
    hint = _hint("CONTRACT_STRUCTURE", "INFO", ())
    hint.update(title="本块清单列结构完整", description="本块表格包含名称、数量及单价字段，不代表整份合同条款齐全。", comparisonEvidenceIds=["C1"])
    check_claims({"hints": [hint]}, catalog=catalog, available_categories={"CONTRACT_BODY"}, allowed_evidence_ids={"C1"}, synthesis=False)
    row = "MX-021\t设备乙\t数量2\t单价数值已移除"
    row_only = EvidenceCatalog([EvidenceSegment("C1", "body", "CONTRACT_BODY", 0, len(row), row, True)])
    with pytest.raises(QualityFailure, match="EVIDENCE_SUPPORT_SCOPE"):
        check_claims({"hints": [hint]}, catalog=row_only, available_categories={"CONTRACT_BODY"}, allowed_evidence_ids={"C1"}, synthesis=False)
    hint["description"] += "合同正文结构完整。"
    with pytest.raises(QualityFailure, match="EVIDENCE_SUPPORT_SCOPE"):
        check_claims({"hints": [hint]}, catalog=catalog, available_categories={"CONTRACT_BODY"}, allowed_evidence_ids={"C1"}, synthesis=False)


@pytest.mark.parametrize("purpose", ["ANALYZE", "SYNTHESIZE"])
def test_actual_quality_repair_wire_explains_support_scope_without_lowering_coverage_or_identity(purpose):
    import httpx
    from audit_ai.config import Settings
    from audit_ai.model.gateway import ContractTextOperation, OpenAiCompatibleModelGateway
    from audit_ai.model.contract_draft import ProviderValidationIssue
    wires = []
    def provider(request):
        wires.append(json.loads(request.content))
        return httpx.Response(200, json={"model":"unit-model","choices":[{"finish_reason":"stop","message":{"content":'{"summary":"核对。","hints":[]}'}}]})
    gateway = OpenAiCompatibleModelGateway(Settings(model_name="unit-model",model_base_url="https://provider.invalid/v1",model_api_key="synthetic-test-key"),transport=httpx.MockTransport(provider))
    operation = ContractTextOperation(project_id="quality-project",chunk_index=1,chunk_count=8,source_text="immutable-synthetic-table-source",purpose=purpose,attempt=2,max_attempts=2,
        generation_policy="contract-semantic-work-v3",quality_protocol="contract-semantic-quality-v1",required_categories=("CONTRACT_STRUCTURE",),
        validation_feedback=(ProviderValidationIssue("hints[2].description",2,"EVIDENCE_SUPPORT_SCOPE","INVALID_SCOPED_CLAIM",category="CONTRACT_STRUCTURE",schema_version="contract-semantic-quality-v1"),))
    gateway.analyze_contract(operation)
    wire = wires[0]; system = wire["messages"][0]["content"]
    assert "QUALITY_REPAIR_GUIDANCE=" in system
    advice = json.JSONDecoder().raw_decode(system.split("QUALITY_REPAIR_GUIDANCE=",1)[1])[0]
    assert advice[0]["fieldPath"] == "hints[2].description" and advice[0]["category"] == "CONTRACT_STRUCTURE"
    assert advice[0]["action"] == "NARROW_CLAIM_TO_SUPPORTED_SCOPE_OR_GROUND_WHOLE_CLAIM"
    assert "不是evidenceRepairs" in system and "allowedCategories不是每块必填清单" in system
    assert "immutable-synthetic-table-source" in wire["messages"][1]["content"]
    assert "requiredCategories=CONTRACT_STRUCTURE\n" in wire["messages"][1]["content"]
    assert wire["max_tokens"] == 6144 and wire["reasoning_effort"] == "none" and wire["response_format"] == {"type":"json_object"}


@pytest.mark.parametrize("suffix,source", [
    ("合同结构完整。", "序号\t名称\t数量\t单价\nMX-031\t设备丙\t3\t数值已移除"),
    ("未对外披露；合同正文结构完整。", "序号\t名称\t数量\t单价\nMX-031\t设备丙\t3\t数值已移除"),
    ("", "采购要求：清单不得缺少名称、数量和单价字段。"),
])
def test_local_column_exception_cannot_hide_global_claim_or_mistake_prose_for_header(suffix, source):
    from audit_ai.model.evidence import EvidenceCatalog, EvidenceSegment
    from audit_ai.model.semantic_quality import QualityFailure, check_claims
    catalog = EvidenceCatalog([EvidenceSegment("C1", "body", "CONTRACT_BODY", 0, len(source), source, True)])
    hint = _hint("CONTRACT_STRUCTURE", "INFO", ())
    hint.update(title="本块清单列结构完整",description="本块表格列结构齐全。"+suffix,comparisonEvidenceIds=["C1"])
    with pytest.raises(QualityFailure, match="EVIDENCE_SUPPORT_SCOPE"):
        check_claims({"hints":[hint]},catalog=catalog,available_categories={"CONTRACT_BODY"},allowed_evidence_ids={"C1"},synthesis=False)


def test_review_actual_missing_word_order_is_a_local_global_type_error():
    from audit_ai.model.evidence import EvidenceCatalog, EvidenceSegment
    from audit_ai.model.semantic_quality import QualityFailure, check_claims
    hint = _hint("DELIVERY_TERMS")
    hint.update(title="交付条款缺失", description="提供的证据片段中未包含具体条款。",
        claimKind="GLOBAL_CLAUSE_NOT_FOUND", obligationScope={"topic": "DELIVERY", "responsibleParty": "PARTY_B", "trigger": "交付"})
    catalog = EvidenceCatalog([EvidenceSegment("C1", "body", "CONTRACT_BODY", 0, 12, "甲乙双方约定采购项目。", True)])
    with pytest.raises(QualityFailure, match="LOCAL_CLAIM_SCOPE"):
        check_claims({"hints": [hint]}, catalog=catalog, available_categories={"CONTRACT_BODY"}, allowed_evidence_ids={"C1"}, synthesis=False)


def test_review_two_present_parties_in_both_anchors_do_not_prove_same_obligation():
    from audit_ai.model.evidence import EvidenceCatalog, EvidenceSegment
    from audit_ai.model.semantic_quality import QualityFailure, reconcile_observations
    first = _hint()
    first.update(claimKind="LOCAL_NOT_SEEN", obligationScope={"topic": "ACCEPTANCE", "responsibleParty": "PARTY_A", "trigger": "验收"})
    final = _hint(attention="INFO", ids=())
    final.update(title="交付日期已明确", comparisonEvidenceIds=["C2"], claimKind="CLAUSE_PRESENT",
        obligationScope={"topic": "DELIVERY", "responsibleParty": "PARTY_B", "trigger": "交付"})
    catalog = EvidenceCatalog([
        EvidenceSegment("C1", "body", "CONTRACT_BODY", 0, 20, "甲方验收期限由双方另行明确。乙方应提供验收资料。", True),
        EvidenceSegment("C2", "body", "CONTRACT_BODY", 20, 40, "乙方应于约定日期完成交付。甲方提供环境。", True)])
    payload = {"hints": [final], "resolutions": [{"observation": [0, 0], "disposition": "COUNTEREVIDENCE",
        "finalHintIndexes": [0], "basisEvidenceIds": ["C2"], "reason": "有对应日期。"}]}
    with pytest.raises(QualityFailure, match="OBLIGATION_SCOPE"):
        reconcile_observations([{"hints": [first]}], payload, allowed_evidence_ids={"C1", "C2"}, catalog=catalog)


def test_review_same_scope_global_missing_and_present_cannot_both_be_final():
    from audit_ai.model.semantic_quality import QualityFailure, reconcile_observations
    scope = {"topic": "DELIVERY", "responsibleParty": "PARTY_B", "trigger": "交付"}
    missing = {**_hint("DELIVERY_TERMS"), "claimKind": "GLOBAL_CLAUSE_NOT_FOUND", "obligationScope": scope}
    present = {**_hint("DELIVERY_TERMS", "INFO", ()), "comparisonEvidenceIds": ["C2"],
               "claimKind": "CLAUSE_PRESENT", "obligationScope": scope}
    payload = {"hints": [missing, present], "resolutions": []}
    with pytest.raises(QualityFailure, match="FINAL_CLAIM_CONTRADICTION"):
        reconcile_observations([], payload, allowed_evidence_ids={"C1", "C2"})


def test_review_quality_wrapper_retains_original_safe_field_and_enum_values():
    from audit_ai.model.semantic_quality import QualityFailure, parse_scoped_draft
    invalid = _hint(); invalid["attention"] = "INVALID_ATTENTION"
    with pytest.raises(QualityFailure) as observed:
        parse_scoped_draft({"summary": "核对。", "hints": [invalid], "scopeStatus": "CHECKED_WITH_FINDINGS"}, synthesis=False)
    assert observed.value.field_path == "hints[0].attention"
    assert observed.value.allowed_values == ("INFO", "REVIEW", "INSUFFICIENT")


def test_retained_concern_cannot_move_to_other_party_while_carrying_old_anchor():
    from audit_ai.model.semantic_quality import QualityFailure, reconcile_observations
    original = _hint("BREACH_LIABILITY", "REVIEW")
    final = copy.deepcopy(original)
    final["obligationScope"]["responsibleParty"] = "PARTY_A"
    payload = {"hints": [final], "resolutions": [{"observation": [0, 0], "disposition": "RETAINED",
        "finalHintIndexes": [0], "basisEvidenceIds": ["C1"], "reason": "保留该问题。"}]}
    with pytest.raises(QualityFailure, match="OBLIGATION_SCOPE"):
        reconcile_observations([{"hints": [original]}], payload, allowed_evidence_ids={"C1"})


def test_dense_complete_private_quality_metadata_stays_lossless_within_same_synthesis_bound():
    from audit_ai.config import Settings
    from audit_ai.model.semantic_work import _JournaledApplication, _work_scope
    from test_semantic_synthesis_compaction import fixture, expected_value
    results, catalog = fixture()
    application = _JournaledApplication(Settings(model_name="unit-model"), object(), "contract-semantic-quality-v1")
    metadata = {"claimKind": "AMBIGUOUS_CLAUSE", "obligationScope": {
        "topic": "DELIVERY", "responsibleParty": "PARTY_B", "trigger": "交付"}}
    application.quality_results = [[copy.deepcopy(metadata) for _ in result.hints] for result in results]
    # Complete scope fixtures, not a missing-field private DTO approximation.
    segments = list(catalog.segments)
    application.work_scopes = [_work_scope(catalog, {item.evidence_id for item in segments[p::len(results)]},
        "CHECKED_WITH_FINDINGS") for p in range(len(results))]
    application.work_observations = [[] for _ in results]
    source, chars = application._contract_synthesis_source(results, catalog)
    assert chars <= 12_000
    value = json.loads(source)
    if "hintLayouts" in value:
        assert value.pop("qualityHintEncoding") == "contract-quality-hint-table-v1"
        layouts = value.pop("hintLayouts")
        for partial in value["partialResults"]:
            partial["hints"] = [dict(zip(layouts[row[0]], row[1:], strict=True)) for row in partial["hints"]]
    work = value.pop("workScopeRegistry")
    assert work["protocol"] == "contract-work-scope-witness-v1"
    assert len(work["units"]) == 8
    assert sum(row[4] for unit in work["units"] for row in unit[2]) == 39
    registry = value.pop("qualityRegistry")
    for partial in value["partialResults"]:
        for hint in partial["hints"]:
            kind, scope = hint.pop("q")
            assert registry["claimKinds"][kind] == "AMBIGUOUS_CLAUSE"
            assert registry["obligationScopes"][scope] == metadata["obligationScope"]
    restored = []
    for row in value["evidenceCatalog"]["rows"]:
        item = dict(zip(value["evidenceCatalog"]["columns"], row, strict=True))
        item.update(value["fileRegistry"][item.pop("fileIndex")])
        restored.append(item)
    assert {"partialResults": value["partialResults"], "evidenceCatalog": restored,
            "omittedHintCount": value["omittedHintCount"]} == expected_value(results, catalog)
