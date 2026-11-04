"""Versioned production work path and real request builder, no real inference."""
import copy
from dataclasses import replace
import json

import pytest

from audit_ai.errors import AuditAiError
from audit_ai.model.gateway import ContractTextOperation, contract_hierarchy_wire
from audit_ai.model.router import ContractAnalysisRequest
from audit_ai.model.semantic_work import ContractSemanticWorkService, checkpoint_hash
from test_semantic_hierarchy_execution import setup
from test_semantic_hierarchy_reference_observe import NativeReferenceProvider
from test_final_frozen_evidence import V2, P2, build


class Provider(NativeReferenceProvider):
    def analyze_contract(self, operation):
        completion = super().analyze_contract(operation)
        if operation.hierarchy_protocol == V2:
            completion.payload["protocol"] = P2
        return completion


def run(*, invalid=False, stop_group=False, mutate_final=None):
    _, _, settings, old = setup(repeat=8)
    data = old.model_dump(by_alias=True); data["semanticHierarchyProtocol"] = V2
    request = ContractAnalysisRequest.model_validate(data)
    class MutatedProvider(Provider):
        def analyze_contract(self,operation):
            completion=super().analyze_contract(operation)
            if operation.hierarchy_role=='FINAL' and mutate_final is not None:mutate_final(completion.payload)
            return completion
    provider = MutatedProvider("FINAL", invalid=invalid, money=invalid)
    service = ContractSemanticWorkService(settings, provider); plan = service.plan(request)
    packets, expected = [], plan["next"]["operationFingerprint"]
    for _ in range(plan["callLimit"]):
        last = service.advance(request, plan["planFingerprint"], packets, expected, remaining_ms=800000, execute=True)
        packets.append(last["checkpoint"])
        if last["result"] is not None or last["next"] is None: break
        expected = last["next"]["operationFingerprint"]
        if stop_group and packets[-1]["role"] == "GROUP": break
    return service, provider, settings, request, plan, packets, last


def test_normal_v2_work_path_persists_view_proof_no_extra_calls_or_public_process_fields():
    _, provider, _, _, plan, packets, last = run()
    assert last["result"] is not None
    assert plan["hierarchy"]["version"] == 2 and plan["hierarchy"]["finalInputMaxChars"] == 48000
    assert plan["hierarchy"]["finalInputMaxBytes"] == 144000
    assert plan["policy"]["finalEvidenceProtocol"] == "contract-final-frozen-evidence-v1"
    assert len(provider.calls) == len(plan["units"]) and plan["callLimit"] <= 22
    assert plan["timeLimitMs"] == 900000 and plan["unitTimeLimitMs"] == 300000
    proof = packets[-1]["quality"]["finalEvidenceProof"]
    assert proof["complete"] and proof["segmentCount"] == len(plan["evidence"])
    assert "finalEvidenceProof" not in last["result"]


@pytest.mark.parametrize('mutation,path,classification',[
    (lambda value:value.update(protocol='PRIVATE_UNTRUSTED_SENTINEL'),'protocol','FINAL_PROTOCOL_LITERAL'),
    (lambda value:value['resolutions'][0][3].__setitem__(0,'PRIVATE_UNTRUSTED_SENTINEL'),
        'resolutions[0].targetRefs','FINAL_TARGET_REFERENCE_UNKNOWN'),
    (lambda value:value.update(PRIVATE_UNTRUSTED_SENTINEL=True),'response','FINAL_ENVELOPE_FIELDS'),
    (lambda value:value['resolutions'][0].__setitem__(4,[-1]),
        'resolutions[0].basisIndexes','FINAL_BASIS_INDEX_DOMAIN'),
    (lambda value:value['resolutions'][0].__setitem__(2,'PRIVATE_UNTRUSTED_SENTINEL'),
        'resolutions[0].disposition','FINAL_DISPOSITION_LITERAL'),
    (lambda value:value['resolutions'][0].__setitem__(3,[]),
        'resolutions[0].targetRefs','FINAL_TARGET_CARDINALITY'),
])
def test_native_failed_provider_protocol_has_safe_specific_coordinate_without_echoing_literals(mutation,path,classification):
    _,_,_,_,_,packets,last=run(mutate_final=mutation)
    assert last['result'] is None and last['next'] is None
    issue=packets[-1]['validationIssues'][0]
    assert issue['errorKind']=='SEMANTIC_HIERARCHY_PROVIDER_INVALID'
    assert issue['fieldPath']==path and issue['actualClass']==classification
    assert 'PRIVATE_UNTRUSTED_SENTINEL' not in json.dumps(packets[-1]['validationIssues'])
    assert packets[-1]['call']['safeReason']=='INVALID_TYPED_SCHEMA'
    assert all('finalResult' not in p for p in packets)


def test_actual_final_and_repair_gateway_wire_contain_same_exact_tail_source():
    from audit_ai.model.semantic_hierarchy import final_source, group_digest
    from test_semantic_hierarchy import candidate
    from test_semantic_regeneration_context_wire import capture
    b = build(); digests = [group_digest(candidate(b, g), g, b) for g in b["groups"]]
    source = final_source(b, digests)
    op = ContractTextOperation(project_id="synthetic-only", chunk_index=3, chunk_count=3,
        source_text=source, purpose="SYNTHESIZE", generation_policy="contract-semantic-work-v3",
        quality_protocol="contract-semantic-quality-v1", hierarchy_protocol=V2, hierarchy_role="FINAL",
        hierarchy_logical_key="b" * 64, grouping_fingerprint=b["groupingFingerprint"],
        globally_available_material_categories=("CONTRACT_BODY",), current_operation_material_categories=("CONTRACT_BODY",))
    from audit_ai.model.contract_draft import ProviderValidationIssue
    from audit_ai.model.semantic_quality import build_regeneration_context, decode_regeneration_context
    from test_final_frozen_evidence import process_payload
    invalid = process_payload(); invalid["newHints"][0]["claimKind"] = "LOCAL_NOT_SEEN"
    issue = ProviderValidationIssue("newHints[0].claimKind", 0, "UNRESOLVED_LOCAL_OBSERVATION", "INVALID_SCOPED_CLAIM")
    context = build_regeneration_context(invalid, (issue,), "a" * 64, hierarchy=True)
    repaired = replace(op, attempt=2, max_attempts=2, validation_feedback=(issue,), regeneration_context_json=context)
    for operation in [op, repaired]:
        wire = json.loads(capture(operation).body)
        actual = json.loads(wire["messages"][1]["content"].split("source:\n", 1)[1])
        assert actual["evidence"][1][-1] == "尾部条款：乙方交付延期应承担违约责任。"
        assert "C:S2" in actual["evidence"][1]
        assert wire["reasoning_effort"] == "none" and wire["max_tokens"] == 6144
        assert wire["response_format"] == {"type": "json_object"}
        assert wire.get("stream", False) is False
        assert P2 in wire["messages"][0]["content"]
        schema = json.loads(wire["messages"][0]["content"].split("AUTHORITATIVE_PROVIDER_DRAFT_SCHEMA=", 1)[1].splitlines()[0])
        condition = schema["properties"]["resolutions"]["items"]["allOf"][0]
        assert condition["if"]["prefixItems"][2] == {"const": "PROCESS_RESOLVED"}
        assert condition["then"]["prefixItems"][3]["maxItems"] == 0
        assert condition["else"]["prefixItems"][3]["minItems"] == 1
        if operation.attempt == 2:
            wire_context = wire["messages"][1]["content"].split("regenerationContext=", 1)[1].splitlines()[0]
            assert wire_context == context and len(wire_context.encode()) <= 8192
            assert decode_regeneration_context(wire_context)["candidate"] == invalid


def test_v2_raw_money_warnings_survive_decode_failure_without_result_publication():
    _, _, _, _, _, packets, last = run(invalid=True)
    assert last["result"] is None and last["next"] is None
    failed = [p for p in packets if p["role"] == "FINAL"]
    assert [p["status"] for p in failed] == ["REPAIR_REQUIRED", "FAILED"]
    assert all(any(w["fieldPath"] == "newHints[0].description" for w in p["monetaryObservation"]["warnings"])
               for p in failed)


def test_restart_after_group_reconstructs_same_evidence_and_rejects_tamper():
    _, _, settings, request, plan, packets, last = run(stop_group=True)
    provider = Provider("FINAL", invalid=False, money=False)
    service = ContractSemanticWorkService(settings, provider)
    described = service.advance(request, plan["planFingerprint"], packets, None, remaining_ms=700000, execute=False)
    assert described["next"] == last["next"] and provider.calls == []
    bad = copy.deepcopy(packets); bad[-1]["groupingFingerprint"] = "f" * 64
    bad[-1]["checkpointHash"] = checkpoint_hash({k: v for k, v in bad[-1].items() if k != "checkpointHash"})
    with pytest.raises(AuditAiError):
        service.advance(request, plan["planFingerprint"], bad, None, remaining_ms=700000, execute=False)
    assert provider.calls == []


def test_completed_final_nested_view_proof_cannot_be_resigned_into_a_new_fact():
    _, _, settings, request, plan, packets, _ = run()
    packets = copy.deepcopy(packets)
    packets[-1]["quality"]["finalEvidenceProof"]["viewSha256"] = "f" * 64
    packets[-1]["checkpointHash"] = checkpoint_hash({k: v for k, v in packets[-1].items() if k != "checkpointHash"})
    provider = Provider("FINAL", invalid=False, money=False)
    service = ContractSemanticWorkService(settings, provider)
    with pytest.raises(AuditAiError):
        service.advance(request, plan["planFingerprint"], packets, None, remaining_ms=700000, execute=False)
    assert provider.calls == []


def run_private_process(*, merge_real_risk=False, partial_inherited=False, scope_refinement=False, context=False,
                        visible_replacement=False, invented_tail_fact=False, local_risk_replacement=False,
                        separate_file_positive=False):
    from audit_ai.model.gateway import ModelCompletion
    from test_semantic_hierarchy_execution import decode_claim
    import re
    class ProcessProvider:
        def __init__(self): self.calls = []
        def analyze_contract(self, op):
            self.calls.append(op)
            if not op.hierarchy_role:
                eid = re.findall(r"\[evidenceId=([^;]+);", op.source_text)[0]
                local = op.chunk_index == (2 if merge_real_risk else 1)
                risk = merge_real_risk and op.chunk_index == 1
                h = {"category": "BREACH_LIABILITY", "attention": "REVIEW" if risk else "INSUFFICIENT" if local else "INFO",
                    "title": "当前片段未见" if local else "延期责任约定", "description": "当前片段未见，需结合完整来源。" if local else "乙方交付延期责任有约定。",
                    "claimKind": "AMBIGUOUS_CLAUSE" if risk else "LOCAL_NOT_SEEN" if local else "CLAUSE_PRESENT", "problemEvidenceIds": [eid] if risk else [],
                    "comparisonEvidenceIds": [] if risk else [eid], "unavailableMaterialCategory": None,
                    "obligationScope": {"topic": "DELAY_LIABILITY", "responsibleParty": "PARTY_B", "trigger": "交付延期"}}
                if partial_inherited:
                    h.update(category="DELIVERY_TERMS", attention="INFO", title="验收与记录约定",
                        description="原文规定组织验收与书面审批记录。", claimKind="CLAUSE_PRESENT",
                        problemEvidenceIds=[], comparisonEvidenceIds=[eid],
                        obligationScope={"topic":"ACCEPTANCE","responsibleParty":"BOTH","trigger":None})
                if scope_refinement:
                    h.update(category='PROJECT_IDENTITY',attention='INSUFFICIENT',title='当前片段未见项目标识',
                        description='当前片段未见项目名称及项目编号。',claimKind='LOCAL_NOT_SEEN',
                        problemEvidenceIds=[],comparisonEvidenceIds=[eid],
                        obligationScope={'topic':'PROJECT_IDENTITY','responsibleParty':'UNSPECIFIED','trigger':None})
                payload = {"summary": "本段核对。", "scopeStatus": "CHECKED_WITH_FINDINGS", "hints": [h]}
            else:
                s = json.loads(op.source_text)
                if op.hierarchy_role == "GROUP":
                    payload = {"protocol": P2, "summary": "保留本组观察。", "newHints": [],
                        "lineage": [[leaf[0], "KEEP", leaf[0], None, None] for a in s["analyses"] for leaf in a["leaves"]]}
                    if merge_real_risk:
                        leaves = [leaf for a in s["analyses"] for leaf in a["leaves"]]
                        pair = leaves[:2]; originals = [decode_claim(leaf[1:], s) for leaf in pair]
                        merged = copy.deepcopy(originals[0]); merged.update(attention="INSUFFICIENT", claimKind="LOCAL_NOT_SEEN",
                            title="当前片段未见", description="当前片段未见，需结合完整来源。", problemEvidenceIds=[],
                            comparisonEvidenceIds=list(dict.fromkeys(e for h in originals for e in h["problemEvidenceIds"] + h["comparisonEvidenceIds"])))
                        payload["newHints"] = [merged]
                        indexes = {r[1]: r[0] for r in s["evidence"]}
                        payload["lineage"][:2] = [[leaf[0], "MERGE", "N0", [indexes[e] for e in h["problemEvidenceIds"] + h["comparisonEvidenceIds"]],
                            "保留原始锚点的组内合并，最终须逐叶核对。"] for leaf, h in zip(pair, originals, strict=True)]
                else:
                    # Decode upstream claim identity from the explicit registry,
                    # not from this call's separately selected visible rows.
                    # This grants no citation authority; native validators still
                    # enforce the actual view on every returned target/basis.
                    claim_source={**s,'evidence':[[eid] for eid in s['evidenceRegistry']]} if 'evidenceRegistry' in s else s
                    hs = [(gi, hi, decode_claim(row, claim_source)) for gi, g in enumerate(s["groups"]) for hi, row in enumerate(g["claims"])]
                    if partial_inherited and visible_replacement:
                        target=copy.deepcopy(hs[0][2]);target.pop('workScopeWitness',None)
                        target.update(title='组织验收约定',description=('当前可见条款规定组织验收。' if not invented_tail_fact
                            else '当前可见条款规定组织验收，书面审批记录在2099年完成。'))
                        eid=target['comparisonEvidenceIds'][0];index=s['evidenceRegistry'].index(eid)
                        return ModelCompletion(payload={'protocol':P2,'summary':'仅核对实际可见的验收条款。','newHints':[target],
                            'resolutions':[[gi,hi,'SCOPED_REPLACEMENT',['N0'],[index],
                            '以本次实际可见原文核对验收安排；不据未展示部分推断条款缺失。'] for gi,hi,_ in hs]},
                            model='unit-model',duration_ms=10,finish_reason='STOP')
                    if scope_refinement:
                        positive=copy.deepcopy(hs[0][2]);positive.pop('workScopeWitness',None)
                        positive.update(attention='INFO',claimKind='CLAUSE_PRESENT',title='项目标识已有记载',
                            description='原文列明项目名称与项目编号；本项仅确认书面标识。',
                            obligationScope={'topic':'PROJECT_IDENTITY','responsibleParty':'BOTH','trigger':None})
                        eid=positive['comparisonEvidenceIds'][0];ix=s['evidenceRegistry'].index(eid)
                        return ModelCompletion(payload={'protocol':P2,'summary':'项目标识已有原文记载。',
                            'newHints':[positive],'resolutions':[[gi,hi,'SCOPED_COUNTEREVIDENCE',['N0'],[ix],
                            '同一冻结原文明确列示所问标识；原未知范围及观察保留，不判断其他身份事项。'] for gi,hi,_ in hs]},
                            model='unit-model',duration_ms=10,finish_reason='STOP')
                    positive = next(h for _, _, h in hs if h["claimKind"] == "CLAUSE_PRESENT")
                    if local_risk_replacement:
                        risk=copy.deepcopy(positive)
                        risk.update(attention='REVIEW',claimKind='AMBIGUOUS_CLAUSE',title='延期责任适用范围待核对',
                            description='交付延期责任已有条款，其具体适用范围尚需结合履行条件核对。',
                            problemEvidenceIds=list(positive['comparisonEvidenceIds']),comparisonEvidenceIds=[])
                        indexes=[s['evidenceRegistry'].index(e) for e in risk['problemEvidenceIds']]
                        return ModelCompletion(payload={'protocol':P2,'summary':'依据实际条款保留需核对事项。',
                            'newHints':[risk],'resolutions':[
                                [gi,hi,'SCOPED_REPLACEMENT',['N0'],indexes,'范围见证保留在私有轨迹，实际条款支持本项关注。']
                                if h['claimKind']=='LOCAL_NOT_SEEN' else
                                [gi,hi,'RETAINED',[f'G{gi+1}:H{hi+1}'],None,None] for gi,hi,h in hs]},
                            model='unit-model',duration_ms=10,finish_reason='STOP')
                    basis = [i for i, e in enumerate(s["evidence"]) if e[0] in positive["comparisonEvidenceIds"]]
                    payload = {"protocol": P2, "summary": "乙方交付延期责任有约定。", "newHints": [], "resolutions": [
                        [gi, hi, "PROCESS_RESOLVED", [], basis, "同文件原文明确乙方交付延期责任，局部观察已经解释。"]
                        if h["claimKind"] == "LOCAL_NOT_SEEN" else [gi, hi, "RETAINED", [f"G{gi+1}:H{hi+1}"], None, None]
                        for gi, hi, h in hs]}
            return ModelCompletion(payload=payload, model="unit-model", duration_ms=10, finish_reason="STOP")
    _, _, settings, old = setup(repeat=80)
    data = old.model_dump(by_alias=True); data["semanticHierarchyProtocol"] = V2
    data["materials"][0]["text"] = ("1. 组织验收。\n2. 书面审批记录在2099年完成。\n" if partial_inherited
        else '项目名称：合成服务项目\n项目编号：SYNTH-1\n' if scope_refinement
        else "乙方交付延期应承担违约责任。\n" * 80)
    if separate_file_positive:
        data['materials'][0]['text']='乙方当前交付说明。'
        data['materials'].append({'fileId':'another-frozen-contract','category':data['materials'][0]['category'],
            'text':'乙方交付延期应承担违约责任。\n'*80})
    request = ContractAnalysisRequest.model_validate(data); provider = ProcessProvider()
    service = ContractSemanticWorkService(settings, provider); plan = service.plan(request)
    packets = []; expected = plan["next"]["operationFingerprint"]
    for _ in range(plan["callLimit"]):
        last = service.advance(request, plan["planFingerprint"], packets, expected, remaining_ms=800000, execute=True)
        packets.append(last["checkpoint"])
        if last["next"] is None: break
        expected = last["next"]["operationFingerprint"]
    return (service,provider,settings,request,plan,packets,last) if context else (packets,last)


def test_private_process_is_composed_per_original_leaf_in_normal_publication_path():
    packets, last = run_private_process()
    assert last["result"] is not None
    q = packets[-1]["quality"]
    assert q["resolutions"][0]["disposition"] == "PROCESS_RESOLVED"
    assert q["resolutions"][0]["finalHintIndexes"] == []
    assert len(q["hierarchyLineage"]) == len([p for p in packets if p["role"] == "ANALYSIS"])
    assert all(h["attention"] == "INFO" for h in last["result"]["hints"])
    assert len(last["result"]["hints"]) == len(q["hierarchyLineage"]) - 1
    assert "PROCESS_RESOLVED" not in json.dumps(last["result"])


def test_group_process_cannot_hide_one_real_risk_in_original_merged_leaves(monkeypatch):
    from audit_ai.model import semantic_work
    original = semantic_work.reconcile_observations
    seen = []
    def checking(partials, payload, **kwargs):
        seen.append(any(h["attention"] == "REVIEW" for p in partials for h in p["hints"]))
        return original(partials, payload, **kwargs)
    monkeypatch.setattr(semantic_work, "reconcile_observations", checking)
    packets, last = run_private_process(merge_real_risk=True)
    assert any(p["role"] == "GROUP" and p["status"] == "VERIFIED" for p in packets)
    assert False in seen and True in seen, "must reach group and then original-A reconciliation"
    assert last["result"] is None and last["next"] is None
    assert packets[-1]["status"] == "FAILED" and packets[-1]["role"] == "FINAL"
    assert all("finalResult" not in p for p in packets)


def test_partial_same_id_cannot_publish_unseen_plain_fact_by_inherited_alias():
    packets,last=run_private_process(partial_inherited=True)
    assert last['result'] is None, 'same evidence ID is not proof the full original claim was visible'
    assert packets[-1]['role']=='FINAL' and packets[-1]['status']=='FAILED'
    assert all('finalResult' not in p for p in packets)


def test_subrange_new_hint_with_actual_visible_basis_can_publish_without_fake_material_absence():
    packets,last=run_private_process(partial_inherited=True,visible_replacement=True)
    assert last['result'] is not None
    assert packets[-1]['status']=='VERIFIED'
    assert all('2099' not in h['description'] and h['attention']=='INFO' for h in last['result']['hints'])
    assert all('不足' not in h['description'] and '未提供' not in h['description'] for h in last['result']['hints'])


def test_subrange_new_hint_cannot_use_critical_fact_from_unseen_remainder_under_same_id():
    packets,last=run_private_process(partial_inherited=True,visible_replacement=True,invented_tail_fact=True)
    assert last['result'] is None and packets[-1]['status']=='FAILED'
    assert all('finalResult' not in p for p in packets)


def test_native_two_level_publication_does_not_force_private_scope_witness_into_new_real_concern():
    _,provider,settings,request,plan,packets,last=run_private_process(local_risk_replacement=True,context=True)
    assert last['result'] is not None, packets[-1].get('validationIssues')
    assert packets[-1]['status']=='VERIFIED'
    quality=packets[-1]['quality']
    private=[entry for entry in quality['hierarchyLineage'] if entry.get('privateScopeWitnessEvidenceIds')]
    assert private and quality['resolutions'][0]['disposition']=='SCOPED_REPLACEMENT'
    assert private[0]['groupBasisEvidenceIds'] and private[0]['privateScopeWitnessEvidenceIds']
    assert all('privateScopeWitnessEvidenceIds' not in h for h in last['result']['hints'])
    # Restart/reopen must rederive exactly the same private/full provenance,
    # never call a model again or trust a newly signed outer hash alone.
    calls=len(provider.calls)
    restarted=ContractSemanticWorkService(settings,provider)
    reopened=restarted.advance(request,plan['planFingerprint'],packets,None,remaining_ms=700000,execute=False)
    assert reopened['result']==last['result'] and len(provider.calls)==calls
    changed=copy.deepcopy(packets)
    entry=next(entry for entry in changed[-1]['quality']['hierarchyLineage'] if entry.get('privateScopeWitnessEvidenceIds'))
    entry['privateScopeWitnessEvidenceIds'].append('invented-private-id')
    changed[-1]['checkpointHash']=checkpoint_hash({k:v for k,v in changed[-1].items() if k!='checkpointHash'})
    with pytest.raises(AuditAiError):
        restarted.advance(request,plan['planFingerprint'],changed,None,remaining_ms=700000,execute=False)
    assert len(provider.calls)==calls


def test_private_witness_exception_cannot_replace_one_file_question_with_other_file_problem():
    packets,last=run_private_process(local_risk_replacement=True,separate_file_positive=True)
    assert last['result'] is None, 'a pure private witness is not authority to substitute another file'
    assert packets[-1]['status']=='FAILED'
    assert packets[-1]['validationIssues'][0]['errorKind']=='SEMANTIC_HIERARCHY_LINEAGE_INVALID'


def test_scoped_refinement_is_persisted_for_groups_originals_and_reused_without_call():
    _,provider,settings,request,plan,packets,last=run_private_process(scope_refinement=True,context=True)
    assert last['result'] is not None
    quality=packets[-1]['quality']
    assert quality['groupScopeRefinements'][0]['originalScope']['responsibleParty']=='UNSPECIFIED'
    assert quality['scopeRefinements'][0]['originalScope']['responsibleParty']=='UNSPECIFIED'
    assert quality['scopeRefinements'][0]['facets']==['PROJECT_NAME','PROJECT_NUMBER']
    calls=len(provider.calls)
    fresh=ContractSemanticWorkService(settings,provider)
    replay=fresh.advance(request,plan['planFingerprint'],packets,None,remaining_ms=700000,execute=False)
    assert replay['result']==last['result'] and len(provider.calls)==calls
    assert 'SCOPED_COUNTEREVIDENCE' not in json.dumps(last['result'])
    bad=copy.deepcopy(packets)
    bad[-1]['quality']['scopeRefinements'][0]['targetScopes'][0]['responsibleParty']='PARTY_A'
    bad[-1]['checkpointHash']=checkpoint_hash({k:v for k,v in bad[-1].items() if k!='checkpointHash'})
    with pytest.raises(AuditAiError):
        fresh.advance(request,plan['planFingerprint'],bad,None,remaining_ms=700000,execute=False)
    assert len(provider.calls)==calls
