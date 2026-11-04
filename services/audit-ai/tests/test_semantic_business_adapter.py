"""Native business adapter: immutable relations must not be provider fields."""
import copy
import importlib.util
import json

import pytest
from audit_ai.errors import AuditAiError
from test_final_frozen_evidence import build
from test_semantic_hierarchy import candidate
from audit_ai.model.semantic_hierarchy import final_source, group_digest


def adapter():
    name='audit_ai.model.semantic_business_adapter'
    assert importlib.util.find_spec(name) is not None, 'business adapter not implemented'
    return __import__(name,fromlist=['registry'])


def fixture():
    bundle=build()
    groups=[candidate(bundle,g) for g in bundle['groups']]
    source=json.loads(final_source(bundle,[group_digest(g,a,bundle) for g,a in zip(groups,bundle['groups'])]))
    return bundle,source


def valid(source):
    a=adapter();issues=a.registry(source)
    return {'protocol':a.PROTOCOL,'summary':'依据冻结材料核查。','decisions':[
        {'issueId':key,'findings':[copy.deepcopy(row['hint'])],'reason':'保留本事项及其原依据。'}
        for key,row in issues.items()], 'additionalFindings':[]}


def test_business_wire_has_stable_issues_not_program_resolution_tables():
    a=adapter();_,source=fixture(); wire=a.provider_source(source)
    assert set(wire['issues'][0])=={'issueId','original','canKeepOriginal'}
    assert wire['issues'][0]['issueId'].startswith('I')
    assert not ({'groups','leafRegistry','lineageColumns','inheritanceEligibility','enums'} & set(wire))
    assert a.provider_source(copy.deepcopy(source))==wire


def test_all_decisions_required_and_unknown_issue_never_reassigned():
    a=adapter();_,source=fixture();payload=valid(source)
    assert len(a.decode(payload,source)['resolutions'])==len(a.registry(source))
    for mutation in ('missing','unknown','duplicate','derived'):
        bad=copy.deepcopy(payload)
        if mutation=='missing':bad['decisions'].pop()
        if mutation=='unknown':bad['decisions'][0]['issueId']='I'+'f'*32
        if mutation=='duplicate':bad['decisions'].append(bad['decisions'][0])
        if mutation=='derived':bad['decisions'][0]['derived']=True
        with pytest.raises(AuditAiError):a.decode(bad,source)


def test_provider_basis_fifty_per_field_does_not_cap_program_union_at_fifty():
    a=adapter();_,source=fixture();payload=valid(source)
    ids=['C:S'+str(i) for i in range(1,52)]
    source['evidenceRegistry']=ids
    source['evidence']=[[eid,0,0,1,True,'真实原文'] for eid in ids]
    payload=valid(source)
    for row in payload['decisions']:
        row['findings'][0].update(problemEvidenceIds=ids[:50],comparisonEvidenceIds=ids[50:])
    expanded=a.decode(payload,source)
    assert expanded['resolutions'][0]['basisEvidenceIds']==ids
    too_many=copy.deepcopy(payload);too_many['decisions'][0]['findings'][0]['problemEvidenceIds']=ids
    with pytest.raises(AuditAiError,match='BUSINESS_FINDING_INVALID'):a.decode(too_many,source)


def test_expanded_total_counts_decisions_and_additions_before_publication():
    a=adapter();_,source=fixture();payload=valid(source)
    seed=copy.deepcopy(payload['decisions'][0]['findings'][0])
    payload['additionalFindings']=[{**seed,'title':'独立事项'+str(i)} for i in range(100-len(a.registry(source)))]
    assert len(a.decode(payload,source)['hints'])==100
    payload['additionalFindings'].append({**seed,'title':'第101项尾部风险'})
    with pytest.raises(AuditAiError,match='BUSINESS_RESULT_CAPACITY'):a.decode(payload,source)


def test_no_model_scope_or_relation_forgery_is_silently_repaired():
    a=adapter();_,source=fixture();payload=valid(source)
    payload['decisions'][0]['observation']=[0,0]
    with pytest.raises(AuditAiError):a.decode(payload,source)


def test_invisible_reference_rejected_even_when_in_complete_registry():
    a=adapter();_,source=fixture();payload=valid(source)
    source['evidenceRegistry'].append('PRIVATE:UNSEEN')
    payload['decisions'][0]['findings'][0]['comparisonEvidenceIds']=['PRIVATE:UNSEEN']
    with pytest.raises(AuditAiError,match='BUSINESS_EVIDENCE_DOMAIN'):a.decode(payload,source)


def test_derived_basis_independent_bound_never_truncates_tail():
    a=adapter()
    assert a.checked_closure([['e'+str(i) for i in range(51)]])[-1]=='e50'
    with pytest.raises(AuditAiError,match='BUSINESS_SOURCE_CLOSURE_BOUND'):
        a.checked_closure([['e'+str(i) for i in range(6401)]])


def test_business_model_output_still_rejects_old_mechanical_protocol():
    a=adapter();_,source=fixture()
    with pytest.raises(AuditAiError):a.decode({'protocol':a.PROTOCOL,'summary':'正常','newHints':[],'resolutions':[]},source)


def test_internal_derived_closure_is_distinct_from_provider_resolution_limit():
    from audit_ai.model.semantic_quality import parse_scoped_draft,reconcile_observations,QualityFailure
    a=adapter();_,source=fixture();payload=valid(source)
    ids=['C:S'+str(i) for i in range(1,52)]
    source['evidenceRegistry']=ids;source['evidence']=[[e,0,0,1,True,'真实原文'] for e in ids]
    payload=valid(source)
    for row in payload['decisions']:
        row['findings'][0].update(problemEvidenceIds=ids[:50],comparisonEvidenceIds=ids[50:])
    value=a.decode(payload,source)
    for hint in value['hints']:hint['claimKind']='AMBIGUOUS_CLAUSE'
    assert 'server_derived_basis' in __import__('inspect').signature(parse_scoped_draft).parameters
    normalized,meta=parse_scoped_draft(value,synthesis=True,allow_process_resolution=True,server_derived_basis=True)
    assert len(meta['resolutions'][0]['basisEvidenceIds'])==51
    with pytest.raises(QualityFailure):parse_scoped_draft(value,synthesis=True,allow_process_resolution=True)


def test_native_worker_uses_versioned_adapter_and_rederives_checkpoint_relations(monkeypatch):
    from dataclasses import replace
    from test_semantic_hierarchy_execution import setup
    from audit_ai.model.router import ContractAnalysisRequest
    from audit_ai.model.semantic_work import ContractSemanticWorkService,checkpoint_hash
    from test_final_frozen_evidence_execution import Provider
    a=adapter();monkeypatch.setenv('AUDIT_CONTRACT_SEMANTIC_BUSINESS_ADAPTER','v1')
    _,_,settings,old=setup(repeat=8)
    data=old.model_dump(by_alias=True);data['semanticHierarchyProtocol']='contract-bounded-hierarchy-v2'
    request=ContractAnalysisRequest.model_validate(data)
    class BusinessProvider(Provider):
        def analyze_contract(self,operation):
            if operation.hierarchy_role in {'GROUP','FINAL'}:
                source=json.loads(operation.source_text)
                assert source.get('businessAdapterProtocol')==a.PROTOCOL
                # Deliberate provider double only; all downstream native gates run.
                from audit_ai.model.gateway import ModelCompletion
                completion=super().analyze_contract(operation)
                return replace(completion,payload=valid(source))
            return super().analyze_contract(operation)
    provider=BusinessProvider('FINAL',invalid=False,money=False);service=ContractSemanticWorkService(settings,provider)
    plan=service.plan(request)
    assert plan['policy'].get('businessAdapterProtocol')==a.PROTOCOL
    assert plan['policy']['hierarchyProviderDraftProtocol']==a.PROTOCOL
    packets=[];expected=plan['next']['operationFingerprint']
    for _ in range(plan['callLimit']):
        last=service.advance(request,plan['planFingerprint'],packets,expected,remaining_ms=800000,execute=True)
        if last['checkpoint'] is not None:packets.append(last['checkpoint'])
        if last['result'] is not None or last['next'] is None:break
        expected=last['next']['operationFingerprint']
    assert last['result'] is not None
    assert packets[-1]['quality']['businessAdapter']['protocol']==a.PROTOCOL
    before=len(provider.calls)
    reopened=ContractSemanticWorkService(settings,provider).advance(request,plan['planFingerprint'],packets,None,remaining_ms=700000,execute=False)
    assert reopened['result']==last['result'] and len(provider.calls)==before
    tampered=copy.deepcopy(packets)
    tampered[-1]['quality']['businessAdapter']['issueIds'].pop()
    tampered[-1]['checkpointHash']=checkpoint_hash({k:v for k,v in tampered[-1].items() if k!='checkpointHash'})
    with pytest.raises(AuditAiError):
        service.advance(request,plan['planFingerprint'],tampered,None,remaining_ms=600000,execute=False)


def test_invalid_business_finding_has_exact_safe_provider_coordinate():
    a=adapter();_,source=fixture();payload=valid(source)
    payload['decisions'][0]['findings'][0]['category']='PRIVATE_UNKNOWN_CATEGORY'
    with pytest.raises(AuditAiError) as caught:a.decode(payload,source)
    assert getattr(caught.value,'field_path',None)=='decisions[0].findings[0].category'
    assert 'PRIVATE_UNKNOWN_CATEGORY' not in str(caught.value)


def test_business_repair_preserves_complete_candidate_without_new_compression():
    from audit_ai.model.semantic_quality import build_regeneration_context,decode_regeneration_context
    from audit_ai.model.contract_draft import ProviderValidationIssue
    a=adapter();_,source=fixture();payload=valid(source)
    payload['summary']='合成接口完整回放。'*700
    issue=ProviderValidationIssue('decisions',None,'BUSINESS_PROTOCOL_INVALID','INVALID_SCOPED_CLAIM')
    context=build_regeneration_context(payload,(issue,),'a'*64,hierarchy=True,business_adapter=True)
    assert len(context.encode())>8192
    assert decode_regeneration_context(context)['candidate']==payload
    assert json.loads(context)['candidateEncoding']=='FULL'


def test_actual_primary_and_repair_use_business_wire_and_original_transport():
    from dataclasses import replace
    from audit_ai.model.gateway import ContractTextOperation
    from audit_ai.model.semantic_quality import build_regeneration_context
    from audit_ai.model.contract_draft import ProviderValidationIssue
    from test_semantic_regeneration_context_wire import capture
    a=adapter();_,source=fixture();source['businessAdapterProtocol']=a.PROTOCOL
    op=ContractTextOperation(project_id='synthetic-only',chunk_index=3,chunk_count=3,
        source_text=json.dumps(source,ensure_ascii=False),purpose='SYNTHESIZE',generation_policy='contract-semantic-work-v3',
        quality_protocol='contract-semantic-quality-v1',hierarchy_protocol='contract-bounded-hierarchy-v2',hierarchy_role='FINAL',
        hierarchy_logical_key='b'*64,grouping_fingerprint=source['groupingFingerprint'])
    issue=ProviderValidationIssue('decisions',None,'BUSINESS_PROTOCOL_INVALID','INVALID_SCOPED_CLAIM')
    context=build_regeneration_context(valid(source),(issue,),'a'*64,hierarchy=True,business_adapter=True)
    for operation in (op,replace(op,attempt=2,max_attempts=2,validation_feedback=(issue,),regeneration_context_json=context)):
        body=json.loads(capture(operation).body)
        data=json.loads(body['messages'][1]['content'])
        assert len(data['source']['issues'])==len(a.registry(source))
        assert 'resolutions' not in body['messages'][0]['content']
        assert body['response_format']=={'type':'json_object'} and body['max_tokens']==6144
        assert body['reasoning_effort']=='none' and body.get('stream',False)is False
        if operation.attempt==2:
            assert data['regenerationContext']['rejectedCandidateSha256']==json.loads(context)['rejectedCandidateSha256']


def test_business_repair_keeps_full_source_but_does_not_repeat_rejected_business_text():
    from dataclasses import replace
    from audit_ai.model.gateway import ContractTextOperation,contract_hierarchy_wire
    from audit_ai.model.semantic_quality import build_regeneration_context
    from audit_ai.model.contract_draft import ProviderValidationIssue
    a=adapter();_,source=fixture();source['businessAdapterProtocol']=a.PROTOCOL
    op=ContractTextOperation(project_id='synthetic-only',chunk_index=3,chunk_count=3,source_text=json.dumps(source),
        purpose='SYNTHESIZE',generation_policy='contract-semantic-work-v3',quality_protocol='contract-semantic-quality-v1',
        hierarchy_protocol='contract-bounded-hierarchy-v2',hierarchy_role='FINAL',
        hierarchy_logical_key='b'*64,grouping_fingerprint=source['groupingFingerprint'])
    issue=ProviderValidationIssue('decisions',None,'BUSINESS_PROTOCOL_INVALID','INVALID_SCOPED_CLAIM')
    context=build_regeneration_context(valid(source),(issue,),'a'*64,hierarchy=True,business_adapter=True)
    primary=json.loads(contract_hierarchy_wire(op)['user_content'])
    repair=json.loads(contract_hierarchy_wire(replace(op,attempt=2,max_attempts=2,validation_feedback=(issue,),regeneration_context_json=context))['user_content'])
    assert repair['source']==primary['source']
    assert 'candidate' not in repair['regenerationContext']
    assert repair['regenerationContext']['issues']==json.loads(context)['issues']


def test_explicit_unchanged_does_not_mean_missing_or_local_observation_is_closed():
    a=adapter();_,source=fixture();payload=valid(source)
    # This fixture is deliberately a LOCAL observation: no unchanged-final
    # shorthand can publish it or pretend the program has processed it.
    payload['decisions'][0].update(findings=None,reason=None)
    with pytest.raises(AuditAiError,match='BUSINESS_UNCHANGED_INELIGIBLE'):a.decode(payload,source)
    source['groups'][0]['claims'][0][4]=source['enums']['claimKinds'].index('CLAUSE_PRESENT')
    source['groups'][0]['claims'][0][1]=source['enums']['attentions'].index('INFO')
    source['groups'][0]['claims'][0][6]=[]
    payload=valid(source);payload['decisions'][0].update(findings=None,reason=None)
    expanded=a.decode(payload,source)
    assert expanded['hints'][0]==next(iter(a.registry(source).values()))['hint']
    assert expanded['resolutions'][0]['basisEvidenceIds']==expanded['hints'][0]['comparisonEvidenceIds']
    bad=copy.deepcopy(payload);del bad['decisions'][0]['findings']
    with pytest.raises(AuditAiError):a.decode(bad,source)


def test_real_cross_file_comparison_is_not_a_blanket_ban_but_anchor_loss_is_rejected():
    from audit_ai.model.semantic_quality import reconcile_observations,QualityFailure
    a=adapter();_,source=fixture();hint=next(iter(a.registry(source).values()))['hint']
    hint.update(claimKind='CROSS_MATERIAL_CONFLICT',attention='REVIEW',problemEvidenceIds=['C:S1'],comparisonEvidenceIds=['B:S1'])
    scope={'topic':'DELIVERY','responsibleParty':'PARTY_B','trigger':'交付'};hint['obligationScope']=scope
    row={'observation':[0,0],'disposition':'RETAINED','finalHintIndexes':[0],
        'basisEvidenceIds':['C:S1','B:S1'],'reason':'两份合法材料的同事项存在差异。'}
    payload={'summary':'核对差异','hints':[copy.deepcopy(hint)],'resolutions':[row]}
    assert reconcile_observations([{'hints':[hint]}],payload,allowed_evidence_ids={'C:S1','B:S1'})==[row]
    payload['hints'][0]['problemEvidenceIds']=[]
    with pytest.raises(QualityFailure,match='RESOLUTION_EVIDENCE|OBSERVATION_ANCHOR_LOST'):
        reconcile_observations([{'hints':[hint]}],payload,allowed_evidence_ids={'C:S1','B:S1'})


def test_program_maintains_business_paths_for_existing_native_quality_feedback():
    a=adapter();_,source=fixture();payload=valid(source)
    assert 'with_provenance' in __import__('inspect').signature(a.decode).parameters
    expanded,paths=a.decode(payload,source,with_provenance=True)
    assert len(paths)==len(expanded['hints'])
    assert paths[0]=='decisions[0].findings[0]'
