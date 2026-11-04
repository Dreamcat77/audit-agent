"""Offline normal semantic-work path; each provider below is in-memory only."""
import copy
import json
import re

import pytest

from audit_ai.config import Settings
from audit_ai.errors import AuditAiError
from audit_ai.model.gateway import ModelCompletion
from audit_ai.model.router import ContractAnalysisRequest
from audit_ai.model.semantic_work import ContractSemanticWorkService, checkpoint_hash

PROTOCOL = "contract-bounded-hierarchy-v1"


def decode_claim(row, source):
    values = dict(zip(source["claimColumns"], row, strict=True))
    for name, pool in (("category", "categories"), ("attention", "attentions"), ("claimKind", "claimKinds")):
        values[name] = source["enums"][pool][values.pop(name + "Index")]
    scope = dict(zip(source["scopeColumns"], source["scopes"][values.pop("scopeIndex")], strict=True))
    values["obligationScope"] = {"topic": source["enums"]["scopeTopics"][scope["topicIndex"]],
        "responsibleParty": source["enums"]["scopeParties"][scope["responsiblePartyIndex"]], "trigger": scope["trigger"]}
    # GROUP rows carry an explicit global index; FINAL has an ordered complete table.
    ids = {r[0]: r[1] for r in source["evidence"]} if source["role"] == "GROUP" else {
        i: r[0] for i, r in enumerate(source["evidence"])}
    for field in ("problem", "comparison"):
        values[field + "EvidenceIds"] = [ids[i] for i in values.pop(field + "EvidenceIndexes")]
    return values


class Provider:
    def __init__(self, *, invalid_group=False, invalid_final=False):
        self.calls = []
        self.invalid_group, self.invalid_final = invalid_group, invalid_final

    def analyze_contract(self, op):
        self.calls.append(op)
        if getattr(op, "hierarchy_role", None) == "GROUP":
            source = json.loads(op.source_text)
            hints, lineage = [], []
            for analysis in source["analyses"]:
                for leaf in analysis["leaves"]:
                    h = decode_claim(leaf[1:], source)
                    hints.append(h)
                    lineage.append([leaf[0], "KEEP", len(hints) - 1,
                        h["problemEvidenceIds"] + h["comparisonEvidenceIds"], "同一范围保留原观察"])
            payload = {"summary": "保留本组全部观察。", "hints": hints, "lineage": lineage,
                "scopeStatus": "CHECKED_WITH_FINDINGS" if hints else "CHECKED_NO_RELEVANT_FINDINGS"}
            if self.invalid_group:
                payload["lineage"] = []
        elif getattr(op, "hierarchy_role", None) == "FINAL":
            source = json.loads(op.source_text)
            hints, resolutions = [], []
            for gi, group in enumerate(source["groups"]):
                for hi, row in enumerate(group["claims"]):
                    h = decode_claim(row, source)
                    hints.append(h)
                    resolutions.append({"observation": [gi, hi], "disposition": "RETAINED",
                        "finalHintIndexes": [len(hints) - 1],
                        "basisEvidenceIds": h["problemEvidenceIds"] + h["comparisonEvidenceIds"], "reason": "保留完整来源观察"})
            payload = {"summary": "全量观察已按来源核对。", "hints": hints, "resolutions": resolutions}
            if self.invalid_final: payload["resolutions"] = []
        else:
            eid = re.findall(r"\[evidenceId=([^;]+);", op.source_text)[0]
            payload = {"summary": "本段可见约定已核对。", "scopeStatus": "CHECKED_WITH_FINDINGS", "hints": [{
                "category": "TERM_TERMINATION", "attention": "INFO", "title": "本段期限核对",
                "description": "仅核对本段可见交付约定。", "problemEvidenceIds": [], "comparisonEvidenceIds": [eid],
                "unavailableMaterialCategory": None, "claimKind": "CLAUSE_PRESENT",
                "obligationScope": {"topic": "DELIVERY", "responsibleParty": "PARTY_B", "trigger": "交付"}}]}
        return ModelCompletion(payload=payload, model="unit-model", duration_ms=10, finish_reason="STOP")


def setup(*, repeat=120, opt=True, **provider_kwargs):
    provider = Provider(**provider_kwargs)
    settings = Settings(model_name="unit-model", model_chunk_chars=650, model_max_chunks=100,
        contract_semantic_money_mode="OBSERVE", contract_semantic_observe_project_ids=("hierarchy-project",))
    data = {"projectId": "hierarchy-project", "semanticMoneyMode": "OBSERVE",
        "semanticQualityProtocol": "contract-semantic-quality-v1", "materials": [
            {"fileId": "supplement", "category": "SUPPLEMENT", "text": "交付与期限按双方约定执行。\n" * repeat}]}
    if opt: data["semanticHierarchyProtocol"] = PROTOCOL
    return ContractSemanticWorkService(settings, provider), provider, settings, ContractAnalysisRequest.model_validate(data)


def drive(service, provider, request, *, stop_after_group=False):
    from hierarchy_reference_fixture import ReferenceFixtureGateway
    if not isinstance(service.gateway.delegate, ReferenceFixtureGateway):
        service.gateway.delegate = ReferenceFixtureGateway(service.gateway.delegate)
    plan = service.plan(request)
    packets, expected, last = [], plan["next"]["operationFingerprint"], None
    for _ in range(plan["callLimit"]):
        before = len(provider.calls)
        last = service.advance(request, plan["planFingerprint"], packets, expected,
                               remaining_ms=800000, execute=True)
        assert len(provider.calls) == before + 1
        assert last["checkpoint"] is not None
        packets.append(last["checkpoint"])
        if last["result"] is not None or last["next"] is None: break
        expected = last["next"]["operationFingerprint"]
        if stop_after_group and last["checkpoint"].get("role") == "GROUP": break
    return plan, packets, last


def test_opt_in_manifest_exact_roles_dependencies_and_shared_budget_without_dispatch():
    service, provider, _, request = setup()
    plan = service.plan(request)
    analyses = [u for u in plan["units"] if u["role"] == "ANALYSIS"]
    groups = [u for u in plan["units"] if u["role"] == "GROUP"]
    final = [u for u in plan["units"] if u["role"] == "FINAL"]
    assert 1 <= len(analyses) <= 16 and len(groups) == (len(analyses) + 5) // 6
    assert len(final) == 1 and final[0]["logicalKey"] == plan["hierarchy"]["finalLogicalKey"]
    assert plan["hierarchy"]["groupLogicalKeys"] == [u["logicalKey"] for u in groups]
    assert all(set(g["dependencies"]) == {a["logicalKey"] for a in analyses} for g in groups)
    assert set(final[0]["dependencies"]) == {u["logicalKey"] for u in analyses + groups}
    assert plan["callLimit"] == len(plan["units"]) + 2 <= 22
    assert plan["policy"]["version"] == "contract-semantic-work-v3"
    assert plan["policy"]["hierarchyProtocol"] == PROTOCOL
    assert not provider.calls


def test_legacy_absent_opt_in_stays_single_synthesis():
    service, provider, _, request = setup(opt=False)
    plan = service.plan(request)
    assert "hierarchy" not in plan and "hierarchyProtocol" not in plan["policy"]
    assert sum(u["purpose"] == "SYNTHESIZE" for u in plan["units"]) == 1
    assert not provider.calls


def test_a17_preflight_rejects_without_dispatch_and_strict_opt_in_rejected():
    service, provider, _, request = setup(repeat=2000)
    with pytest.raises(AuditAiError) as failure: service.plan(request)
    assert failure.value.code == "SEMANTIC_HIERARCHY_ANALYSIS_BOUND"
    assert not provider.calls
    service, provider, _, request = setup()
    request.semantic_money_mode = "STRICT"
    with pytest.raises(AuditAiError) as failure: service.plan(request)
    assert failure.value.code == "SEMANTIC_HIERARCHY_POLICY_INCOMPATIBLE"
    assert not provider.calls


def test_one_dispatch_groups_no_publication_then_unique_final_full_original_reconcile():
    service, provider, _, request = setup()
    plan, packets, last = drive(service, provider, request)
    assert last["result"] is not None and last["result"]["available"]
    assert packets[-1]["logicalKey"] == plan["hierarchy"]["finalLogicalKey"]
    assert packets[-1]["role"] == "FINAL"
    assert all("finalResult" not in p for p in packets[:-1])
    assert len(provider.calls) == len(plan["units"])
    analyses = [p for p in packets if p["role"] == "ANALYSIS"]
    assert len(packets[-1]["quality"]["hierarchyLineage"]) == len(analyses)
    assert len(packets[-1]["quality"]["resolutions"]) == len(analyses)
    assert all(p["groupingFingerprint"] == packets[-1]["groupingFingerprint"]
               for p in packets if p["role"] != "ANALYSIS")


def test_restart_after_group_revalidates_without_provider_call_and_tampering_refused():
    service, provider, settings, request = setup()
    plan, packets, last = drive(service, provider, request, stop_after_group=True)
    assert packets[-1]["role"] == "GROUP" and last["result"] is None
    new_provider = Provider()
    restarted = ContractSemanticWorkService(settings, new_provider)
    result = restarted.advance(request, plan["planFingerprint"], packets, None, remaining_ms=700000, execute=False)
    assert result["next"] == last["next"] and not new_provider.calls
    bad = copy.deepcopy(packets)
    bad[-1]["quality"]["groupLineage"][0][0] = "A999:H1"
    bad[-1]["checkpointHash"] = checkpoint_hash({k: v for k, v in bad[-1].items() if k != "checkpointHash"})
    with pytest.raises(AuditAiError): restarted.advance(request, plan["planFingerprint"], bad, None, remaining_ms=700000, execute=False)
    assert not new_provider.calls


@pytest.mark.parametrize("defect", ["invalid_group", "invalid_final"])
def test_invalid_stage_gets_only_one_repair_and_never_publishes(defect):
    service, provider, _, request = setup(**{defect: True})
    plan, packets, last = drive(service, provider, request)
    assert last["result"] is None and last["next"] is None
    assert packets[-1]["status"] == "FAILED" and packets[-1]["purpose"] == "REPAIR"
    stage = "GROUP" if defect == "invalid_group" else "FINAL"
    assert sum(p["role"] == stage for p in packets) == 2
    assert len(provider.calls) <= plan["callLimit"]


def test_real_gateway_group_wire_is_private_scoped_and_generation_unchanged():
    from test_semantic_regeneration_context_wire import capture
    from audit_ai.model.gateway import ContractTextOperation
    from audit_ai.model.semantic_hierarchy import canonical
    source = canonical({"hierarchyProtocol": PROTOCOL, "role": "GROUP", "analyses": [{"leaves": []}],
        "digestMaxChars": 2000, "groupingFingerprint": "a" * 64})
    op = ContractTextOperation(project_id="hierarchy-project", chunk_index=4, chunk_count=5,
        source_text=source, purpose="SYNTHESIZE", generation_policy="contract-semantic-work-v3",
        quality_protocol="contract-semantic-quality-v1", hierarchy_protocol=PROTOCOL,
        hierarchy_role="GROUP", hierarchy_logical_key="b" * 64, grouping_fingerprint="a" * 64,
        globally_available_material_categories=("SUPPLEMENT",), current_operation_material_categories=("SUPPLEMENT",))
    wire = json.loads(capture(op).body)
    system = wire["messages"][0]["content"]
    schema = json.loads(system.split("AUTHORITATIVE_PROVIDER_DRAFT_SCHEMA=", 1)[1].split("\n", 1)[0])
    assert "lineage" in schema["properties"] and "newHints" in schema["properties"]
    assert schema["properties"]["protocol"]["const"] == 'contract-hierarchy-provider-references-v1'
    assert "scopeStatus" not in schema["properties"]
    assert "resolutions" not in schema["properties"]
    assert "GLOBAL_CLAUSE_NOT_FOUND" not in schema["$defs"]["QualityHint"]["properties"]["claimKind"]["enum"]
    assert "LOCAL_NOT_SEEN" in schema["$defs"]["QualityHint"]["properties"]["claimKind"]["enum"]
    assert "GROUP_PRIVATE_NOT_GLOBAL" in system and "PARTIAL_PROOF_NOT_LOSSLESS" in system
    assert wire["max_tokens"] == 6144 and wire["reasoning_effort"] == "none"
    assert wire["response_format"] == {"type": "json_object"}
    assert wire["temperature"] == 0
    assert wire["messages"][1]["content"].endswith("source:\n" + source)


def test_capacity_after_last_valid_analysis_preserves_its_successful_call(monkeypatch):
    from audit_ai.model import semantic_hierarchy
    def no_fit(*args, **kwargs):
        raise AuditAiError(413, "SEMANTIC_HIERARCHY_GROUP_INPUT_BOUND", "synthetic complete group overflow")
    monkeypatch.setattr(semantic_hierarchy, "build_hierarchy_inputs", no_fit)
    service, provider, _, request = setup()
    plan, packets, last = drive(service, provider, request)
    assert last["failureCode"] == "SEMANTIC_HIERARCHY_GROUP_INPUT_BOUND"
    assert last["result"] is None
    assert all(p["status"] == "VERIFIED" and p["call"]["outcome"] == "SUCCEEDED" for p in packets)
    assert len(provider.calls) == len([u for u in plan["units"] if u["role"] == "ANALYSIS"])


def test_two_analysis_repairs_exhaust_shared_corrections_before_group():
    class SharedProvider(Provider):
        def analyze_contract(self, op):
            completion = super().analyze_contract(op)
            if op.purpose == "ANALYZE" and op.chunk_index <= 2 and op.attempt == 1:
                completion.payload["hints"][0]["attention"] = "INVALID_ATTENTION"
            if op.hierarchy_role == "GROUP": completion.payload["lineage"] = []
            return completion
    service, _, settings, request = setup()
    provider = SharedProvider()
    service = ContractSemanticWorkService(settings, provider)
    plan, packets, last = drive(service, provider, request)
    assert sum(p["purpose"] == "REPAIR" for p in packets) == 2
    assert packets[-1]["role"] == "GROUP" and packets[-1]["purpose"] == "SYNTHESIZE"
    assert packets[-1]["status"] == "FAILED" and last["result"] is None and last["next"] is None
    assert len(provider.calls) <= plan["callLimit"]


def test_observe_warnings_survive_analysis_group_final_in_work_references():
    class MoneyProvider(Provider):
        def analyze_contract(self, op):
            completion = super().analyze_contract(op)
            for h in completion.payload["hints"]:
                h["description"] = "合同金额高于中标金额，需人工复核。"
            return completion
    _, _, settings, request = setup()
    provider = MoneyProvider()
    service = ContractSemanticWorkService(settings, provider)
    _, packets, last = drive(service, provider, request)
    assert last["result"] is not None
    assert {p["role"] for p in packets if p["monetaryObservation"]["warnings"]} == {"ANALYSIS", "GROUP", "FINAL"}
    references = last["result"]["monetaryObservation"]["workWarnings"]
    assert {r["logicalKey"] for r in references} == {p["logicalKey"] for p in packets}
    assert all(h["description"] == "合同金额高于中标金额，需人工复核。" for h in last["result"]["hints"])


def test_group_transport_unknown_is_retained_and_cannot_resume_or_retry():
    from audit_ai.model.gateway import ModelGatewayFailure
    class UnknownProvider(Provider):
        def analyze_contract(self, op):
            if op.hierarchy_role == "GROUP":
                self.calls.append(op)
                raise ModelGatewayFailure(reason="TRANSPORT_OUTCOME_UNKNOWN", retryable=True, duration_ms=10)
            return super().analyze_contract(op)
    _, _, settings, request = setup()
    provider = UnknownProvider()
    service = ContractSemanticWorkService(settings, provider)
    plan, packets, last = drive(service, provider, request)
    assert packets[-1]["status"] == "UNKNOWN" and last["next"] is None and last["result"] is None
    before = len(provider.calls)
    with pytest.raises(AuditAiError):
        service.advance(request, plan["planFingerprint"], packets, None, remaining_ms=700000, execute=True)
    assert len(provider.calls) == before


def test_group_cannot_smuggle_a_final_result_even_with_recomputed_outer_hash():
    service, provider, _, request = setup()
    plan, packets, last = drive(service, provider, request, stop_after_group=True)
    packets[-1]["finalResult"] = {"available": True, "hints": []}
    packets[-1]["checkpointHash"] = checkpoint_hash({k: v for k, v in packets[-1].items() if k != "checkpointHash"})
    before = len(provider.calls)
    with pytest.raises(AuditAiError):
        service.advance(request, plan["planFingerprint"], packets, None, remaining_ms=700000, execute=False)
    assert len(provider.calls) == before


def test_unresolved_local_observations_are_private_in_groups_and_not_public_final():
    class LocalProvider(Provider):
        def analyze_contract(self, op):
            completion = super().analyze_contract(op)
            if op.purpose == "ANALYZE":
                for h in completion.payload["hints"]:
                    h.update(attention="INSUFFICIENT", claimKind="LOCAL_NOT_SEEN", title="本段未见交付期限",
                        description="仅当前片段未能确认交付期限，需核对全部约定。")
            return completion
    _, _, settings, request = setup()
    provider = LocalProvider()
    service = ContractSemanticWorkService(settings, provider)
    _, packets, last = drive(service, provider, request)
    assert all(p["status"] == "VERIFIED" for p in packets if p["role"] in {"ANALYSIS", "GROUP"}), [p.get("validationIssues") for p in packets]
    assert all(p["quality"]["workObservations"] for p in packets if p["role"] == "GROUP")
    assert packets[-1]["role"] == "FINAL" and packets[-1]["status"] == "FAILED"
    assert last["result"] is None


def test_group_irreducible_repair_context_stops_after_actual_first_failure():
    class DenseInvalidProvider(Provider):
        def analyze_contract(self, op):
            completion = super().analyze_contract(op)
            if op.hierarchy_role == "GROUP":
                # Preserve the whole received candidate. It is invalid (reason
                # cap), not a legal input for a truncated repair context.
                completion.payload["lineage"][0][4] = "完整未验证候选" * 2000
            return completion
    _, _, settings, request = setup()
    provider = DenseInvalidProvider()
    service = ContractSemanticWorkService(settings, provider)
    _, packets, last = drive(service, provider, request)
    assert packets[-1]["role"] == "GROUP" and packets[-1]["status"] == "FAILED"
    assert sum(p["role"] == "GROUP" for p in packets) == 1
    assert last["failureCode"] == "SEMANTIC_REGENERATION_CONTEXT_BOUND"
    assert last["next"] is None and last["result"] is None
    assert len(packets[-1]["completion"]["payload"]["lineage"][0][4]) == 14000


def test_group_and_final_all_use_actual_complete_wire_builder_including_repair():
    from audit_ai.model.gateway import contract_hierarchy_wire
    from test_semantic_regeneration_context_wire import capture
    service, provider, _, request = setup(invalid_final=True)
    _, packets, _ = drive(service, provider, request)
    operations = [op for op in provider.calls if op.hierarchy_role]
    assert {op.hierarchy_role for op in operations} == {"GROUP", "FINAL"}
    assert any(op.attempt == 2 for op in operations)
    for op in operations:
        built = contract_hierarchy_wire(op)
        actual = json.loads(capture(op).body)
        assert actual["messages"] == [{"role": "system", "content": built["system_prompt"]},
                                       {"role": "user", "content": built["user_content"]}]
        assert len(op.source_text) <= 12000
        if op.regeneration_context_json:
            assert len(op.regeneration_context_json.encode("utf-8")) <= 8192
            assert op.regeneration_context_json in actual["messages"][1]["content"]


@pytest.mark.parametrize("stage", ["GROUP", "FINAL"])
def test_actual_stage_rejects_changed_critical_fact_despite_same_original_id(stage):
    class ChangedFactProvider(Provider):
        def analyze_contract(self, op):
            completion = super().analyze_contract(op)
            if op.hierarchy_role == stage:
                completion.payload["hints"][0]["description"] = "乙方应于2026年11月20日交付。"
            return completion
    _, _, settings, request = setup()
    provider = ChangedFactProvider()
    service = ContractSemanticWorkService(settings, provider)
    _, packets, last = drive(service, provider, request)
    assert last["result"] is None and last["next"] is None
    assert packets[-1]["status"] == "FAILED" and packets[-1]["role"] == stage
    assert packets[-1]["validationIssues"][0]["errorKind"] == "CRITICAL_FACT_UNSUPPORTED"


def test_legal_groups_cannot_cancel_cross_group_risk_with_other_party_counterevidence():
    class FalseCounterProvider(Provider):
        def analyze_contract(self, op):
            completion = super().analyze_contract(op)
            if op.purpose == "ANALYZE" and op.chunk_index == 1:
                h = completion.payload["hints"][0]
                h.update(attention="REVIEW", claimKind="AMBIGUOUS_CLAUSE", title="验收边界待核实",
                    description="需核对甲方验收边界。", problemEvidenceIds=h["comparisonEvidenceIds"], comparisonEvidenceIds=[])
                h["obligationScope"].update(responsibleParty="PARTY_A", trigger="验收")
            if op.hierarchy_role == "FINAL":
                target = next(i for i, h in enumerate(completion.payload["hints"]) if h["attention"] == "INFO")
                for row in completion.payload["resolutions"]:
                    if completion.payload["hints"][row["finalHintIndexes"][0]]["attention"] == "REVIEW":
                        row.update(disposition="COUNTEREVIDENCE", finalHintIndexes=[target],
                            basisEvidenceIds=completion.payload["hints"][target]["comparisonEvidenceIds"])
            return completion
    _, _, settings, request = setup(repeat=360)
    provider = FalseCounterProvider()
    service = ContractSemanticWorkService(settings, provider)
    plan, packets, last = drive(service, provider, request)
    assert len(plan["hierarchy"]["groupLogicalKeys"]) >= 2
    assert all(p["status"] == "VERIFIED" for p in packets if p["role"] == "GROUP")
    assert packets[-1]["role"] == "FINAL" and packets[-1]["status"] == "FAILED"
    assert last["result"] is None


def test_final_cannot_claim_evidence_known_to_catalog_but_absent_from_group_projection():
    class UnprojectedProvider(Provider):
        unseen_id = None
        def analyze_contract(self, op):
            completion = super().analyze_contract(op)
            if op.hierarchy_role == "FINAL":
                # A valid syntax alone cannot grant a new source identity.
                completion.payload["hints"][0]["comparisonEvidenceIds"] = [self.unseen_id]
            return completion
    _, _, settings, request = setup()
    provider = UnprojectedProvider()
    request.materials.append(request.materials[0].model_copy(update={"file_id": "extra", "text": "额外未引用的独立材料。"}))
    service = ContractSemanticWorkService(settings, provider)
    # This is a REAL ID in the same frozen catalog, but each A cites only its
    # first entry. Pick an uncited segment inside a multi-evidence A batch.
    plan = service.plan(request)
    analyses = [u for u in plan["units"] if u["role"] == "ANALYSIS"]
    provider.unseen_id = next(eid for u in analyses for eid in u["evidenceIds"][1:])
    assert provider.unseen_id in {e["evidenceId"] for e in plan["evidence"]}
    _, packets, last = drive(service, provider, request)
    assert last["result"] is None and packets[-1]["role"] == "FINAL"
    assert packets[-1]["validationIssues"][0]["errorKind"] == "EVIDENCE_SCOPE"


@pytest.mark.parametrize("defect", ["duplicate_final", "final_not_last", "wrong_final_key", "group_missing_analysis_dependency", "final_missing_group", "boolean_version", "boolean_ordinal"])
def test_manifest_unique_last_final_authority_is_explicitly_validated(defect):
    from audit_ai.model.semantic_hierarchy import validate_hierarchy_manifest
    service, _, _, request = setup()
    plan = service.plan(request)
    assert validate_hierarchy_manifest(plan) == plan["hierarchy"]["finalLogicalKey"]
    if defect == "duplicate_final": plan["units"][-2]["role"] = "FINAL"
    if defect == "final_not_last": plan["units"][-2], plan["units"][-1] = plan["units"][-1], plan["units"][-2]
    if defect == "wrong_final_key": plan["hierarchy"]["finalLogicalKey"] = "f" * 64
    if defect == "group_missing_analysis_dependency": plan["units"][-2]["dependencies"].pop()
    if defect == "final_missing_group": plan["units"][-1]["dependencies"].pop()
    if defect == "boolean_version": plan["hierarchy"]["version"] = True
    if defect == "boolean_ordinal": plan["units"][0]["ordinal"] = True
    with pytest.raises(AuditAiError): validate_hierarchy_manifest(plan)


def test_replay_final_result_must_match_rederived_result_not_only_outer_hash():
    service, provider, _, request = setup()
    plan, packets, last = drive(service, provider, request)
    assert last["result"]
    packets[-1]["finalResult"]["summaries"] = ["伪造最终意见"]
    packets[-1]["checkpointHash"] = checkpoint_hash({k: v for k, v in packets[-1].items() if k != "checkpointHash"})
    before = len(provider.calls)
    with pytest.raises(AuditAiError):
        service.advance(request, plan["planFingerprint"], packets, None, remaining_ms=600000, execute=False)
    assert len(provider.calls) == before


def test_final_capacity_after_last_group_preserves_group_verified_call(monkeypatch):
    from audit_ai.model import semantic_hierarchy
    def no_final(*args, **kwargs):
        raise AuditAiError(413, "SEMANTIC_HIERARCHY_FINAL_INPUT_BOUND", "synthetic complete final bound")
    monkeypatch.setattr(semantic_hierarchy, "final_source", no_final)
    service, provider, _, request = setup()
    _, packets, last = drive(service, provider, request)
    assert last["failureCode"] == "SEMANTIC_HIERARCHY_FINAL_INPUT_BOUND"
    assert packets[-1]["role"] == "GROUP" and packets[-1]["status"] == "VERIFIED"
    assert packets[-1]["call"]["outcome"] == "SUCCEEDED" and last["result"] is None


def test_non_durable_entrypoint_rejects_hierarchy_before_any_analysis_dispatch():
    from audit_ai.model.router import ModelApplicationService
    _, provider, settings, request = setup()
    application = ModelApplicationService(settings, provider, require_synthesis=True)
    with pytest.raises(AuditAiError) as failure:
        ModelApplicationService.analyze_contract.__wrapped__(application, request, "offline")
    assert failure.value.code == "SEMANTIC_HIERARCHY_POLICY_INCOMPATIBLE"
    assert not provider.calls
