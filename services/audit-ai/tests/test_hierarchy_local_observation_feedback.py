"""Synthetic native service -> finite issue -> actual gateway bytes, no sockets."""
import copy
import hashlib
import json
from pathlib import Path

import pytest

SELECTED_SOURCE=Path(__file__).resolve().parents[1]/'src'
from audit_ai.model import gateway, semantic_quality as quality
from audit_ai.model.contract_draft import ProviderValidationIssue
from audit_ai.model.gateway import ModelCompletion
from audit_ai.model.semantic_work import ContractSemanticWorkService
from test_semantic_hierarchy_execution import Provider, decode_claim, setup
from test_semantic_regeneration_context_wire import capture, context_on_wire

PROTOCOL='contract-hierarchy-provider-references-v1'

def canonical(value):return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'))

class LocalProvider(Provider):
    def __init__(self,*,replace_local=False):
        super().__init__();self.replace_local=replace_local;self.final_wires=[]

    def analyze_contract(self,op):
        if op.hierarchy_role is None:
            result=super().analyze_contract(op)
            present=result.payload['hints'][0]
            result.payload['hints']=[dict(copy.deepcopy(present),title=f'交付核查{i+1}') for i in range(7)]
            result.payload['hints'].append({'category':'BREACH_LIABILITY','attention':'INSUFFICIENT',
                'title':'本段责任核查','description':'本段未见违约责任安排。','problemEvidenceIds':[],
                'comparisonEvidenceIds':present['comparisonEvidenceIds'][:], 'unavailableMaterialCategory':None,
                'claimKind':'LOCAL_NOT_SEEN','obligationScope':{'topic':'OTHER_LIABILITY','responsibleParty':'PARTY_B','trigger':'违约'}})
            return result
        self.calls.append(op);source=json.loads(op.source_text)
        if op.hierarchy_role=='GROUP':
            leaves=[leaf for analysis in source['analyses'] for leaf in analysis['leaves']]
            raw={'protocol':PROTOCOL,'summary':'保留本组全部观察。','newHints':[],
                 'lineage':[[leaf[0],'KEEP',leaf[0],None,None] for leaf in leaves]}
        else:
            raw={'protocol':PROTOCOL,'summary':'按完整来源核对全部观察。','newHints':[],
                 'resolutions':[[gi,hi,'NEEDS_VERIFICATION' if decode_claim(row,source)['claimKind']=='LOCAL_NOT_SEEN' else 'RETAINED',
                                 [f'G{gi+1}:H{hi+1}'],None,None]
                                for gi,group in enumerate(source['groups']) for hi,row in enumerate(group['claims'])]}
            if self.replace_local and op.attempt==2:
                original=decode_claim(source['groups'][0]['claims'][7],source)
                replacement=copy.deepcopy(original)
                replacement.update(claimKind='AMBIGUOUS_CLAUSE',title='违约责任待核实',description='违约责任安排需结合原件核查。',
                                   problemEvidenceIds=original['comparisonEvidenceIds'][:],comparisonEvidenceIds=[])
                if self.replace_local=='bad-scope':replacement['obligationScope']['responsibleParty']='PARTY_A'
                if self.replace_local=='no-evidence':replacement['problemEvidenceIds']=[]
                if self.replace_local=='fake-info':
                    replacement.update(claimKind='CLAUSE_PRESENT',attention='INFO',problemEvidenceIds=[],
                                       comparisonEvidenceIds=original['comparisonEvidenceIds'][:])
                raw['newHints']=[replacement]
                ids={row[0]:i for i,row in enumerate(source['evidence'])}
                raw['resolutions'][7]=[0,7,'NEEDS_VERIFICATION',['N0'],
                    [ids[e] for e in replacement['problemEvidenceIds']],'依据完整来源保留同一责任范围的待核实事项。']
            self.final_wires.append(copy.deepcopy(raw))
        return ModelCompletion(payload=raw,model='unit-model',duration_ms=10,finish_reason='STOP',
                               prompt_tokens=100,completion_tokens=200,total_tokens=300)

def drive(*,replace_local=False):
    _,_,settings,request=setup(repeat=10)
    request.materials[0].text='乙方违约后应承担责任；交付期限按双方约定执行。'
    provider=LocalProvider(replace_local=replace_local);service=ContractSemanticWorkService(settings,provider)
    plan=service.plan(request);packets=[];expected=plan['next']['operationFingerprint']
    for _ in range(plan['callLimit']):
        last=service.advance(request,plan['planFingerprint'],packets,expected,remaining_ms=800000,execute=True)
        packets.append(last['checkpoint'])
        if last['next'] is None:break
        expected=last['next']['operationFingerprint']
    return provider,plan,packets,last

def schema(body):
    text=body['messages'][0]['content'];raw=text.split('AUTHORITATIVE_PROVIDER_DRAFT_SCHEMA=',1)[1]
    return json.JSONDecoder().raw_decode(raw)[0]

def test_only_selected_application_modules_are_used():
    assert Path(gateway.__file__).resolve().is_relative_to(SELECTED_SOURCE.resolve())
    assert Path(quality.__file__).resolve().is_relative_to(SELECTED_SOURCE.resolve())

def test_actual_final_schema_excludes_local_but_group_preserves_private_local():
    provider,_,packets,_=drive()
    for role in ('GROUP','FINAL'):
        op=next(op for op in provider.calls if op.hierarchy_role==role and op.attempt==1)
        body=json.loads(capture(op).body)
        kinds=schema(body)['$defs']['QualityHint']['properties']['claimKind']['enum']
        assert ('LOCAL_NOT_SEEN' in kinds)==(role=='GROUP')
        assert body['max_tokens']==6144 and body['temperature']==0
    assert next(p for p in packets if p['role']=='GROUP')['status']=='VERIFIED'

def test_actual_final_primary_explicitly_forbids_inheriting_local_even_needs_verification():
    provider,_,_,_=drive()
    op=next(op for op in provider.calls if op.hierarchy_role=='FINAL' and op.attempt==1)
    system=json.loads(capture(op).body)['messages'][0]['content']
    assert 'FINAL_LOCAL_OBSERVATION_RULE' in system
    rule=system.split('FINAL_LOCAL_OBSERVATION_RULE',1)[1]
    for word in ('LOCAL_NOT_SEEN','targetRefs','NEEDS_VERIFICATION','newHints','basis','reason','INFO'):
        assert word in rule

def test_native_inherited_issue_reaches_actual_gateway_repair_with_exact_source_and_candidate():
    provider,plan,packets,last=drive()
    final=[p for p in packets if p['role']=='FINAL']
    assert [p['status'] for p in final]==['REPAIR_REQUIRED','FAILED']
    issue=final[0]['validationIssues'][0]
    assert issue['fieldPath']=='inheritedHints' and issue['itemIndex'] is None
    assert issue['errorKind']=='UNRESOLVED_LOCAL_OBSERVATION'
    assert issue['actualClass']=='SOURCE_G1_H8_INVALID_SCOPED_CLAIM'
    op=next(op for op in provider.calls if op.hierarchy_role=='FINAL' and op.attempt==2)
    body=json.loads(capture(op).body);system=body['messages'][0]['content']
    assert 'G1:H8' in system and 'LOCAL_NOT_SEEN' in system
    assert 'newHints' in system and 'basis' in system and 'reason' in system
    decoded=quality.decode_regeneration_context(context_on_wire(canonical(body).encode()))
    assert decoded['candidate']==provider.final_wires[0]
    assert decoded['issues'][0]==issue
    assert decoded['rejectedOperationFingerprint']==final[0]['operationFingerprint']
    assert decoded['rejectedCandidateSha256']==hashlib.sha256(canonical(provider.final_wires[0]).encode()).hexdigest()
    assert len(op.regeneration_context_json.encode())<=8192
    assert provider.final_wires[0]==provider.final_wires[1]
    assert last['result'] is None and last['next'] is None
    assert sum(o.hierarchy_role=='FINAL' for o in provider.calls)==2
    assert len(provider.calls)<=plan['callLimit']<=22
    assert all(p.get('finalResult') is None for p in final)

def test_same_scope_grounded_replacement_retains_risk_and_all_original_observation_destinations():
    provider,_,packets,last=drive(replace_local=True)
    assert packets[-1]['status']=='VERIFIED'
    assert last['result'] is not None and last['result']['available']
    raw=provider.final_wires[-1]
    assert len(raw['resolutions'])==8 and raw['resolutions'][7][3]==['N0']
    hint=raw['newHints'][0]
    assert hint['category']=='BREACH_LIABILITY' and hint['attention']=='INSUFFICIENT'
    assert hint['claimKind']=='AMBIGUOUS_CLAUSE' and hint['problemEvidenceIds']
    assert hint['obligationScope']=={'topic':'OTHER_LIABILITY','responsibleParty':'PARTY_B','trigger':'违约'}

@pytest.mark.parametrize('defect',['bad-scope','no-evidence','fake-info'])
def test_new_target_cannot_launder_wrong_scope_missing_anchor_or_info_downgrade(defect):
    provider,_,packets,last=drive(replace_local=defect)
    assert packets[-1]['role']=='FINAL' and packets[-1]['status']=='FAILED'
    assert last['result'] is None and last['next'] is None
    assert len(provider.final_wires)==2

@pytest.mark.parametrize('actual',['SOURCE_G0_H8_INVALID_SCOPED_CLAIM','SOURCE_G4_H8_INVALID_SCOPED_CLAIM',
    'SOURCE_G1_H0_INVALID_SCOPED_CLAIM','SOURCE_G1_H101_INVALID_SCOPED_CLAIM',
    'SOURCE_G1_H8_INVALID_SCOPED_CLAIM PRIVATE_CANARY','PRIVATE_CANARY'])
def test_bad_source_class_never_echoed_or_fabricates_source_ref(actual):
    issue=ProviderValidationIssue('inheritedHints',None,'UNRESOLVED_LOCAL_OBSERVATION',actual,
                                  category='BREACH_LIABILITY',schema_version=quality.PROTOCOL)
    guidance=quality.quality_repair_guidance((issue,),synthesis=True)
    assert actual not in canonical(guidance) and 'PRIVATE_CANARY' not in canonical(guidance)
    assert all(not item.get('sourceRef') for item in guidance)

@pytest.mark.parametrize('actual,ref',[('SOURCE_G1_H8_INVALID_SCOPED_CLAIM','G1:H8'),
                                     ('SOURCE_G3_H100_INVALID_SCOPED_CLAIM','G3:H100')])
def test_exact_source_ref_guidance_is_global_only_and_unindexed_hints_remain_generic(actual,ref):
    issue=ProviderValidationIssue('inheritedHints',None,'UNRESOLVED_LOCAL_OBSERVATION',actual,
                                  category='BREACH_LIABILITY',schema_version=quality.PROTOCOL)
    guidance=quality.quality_repair_guidance((issue,),synthesis=True)
    assert len(guidance)==1 and guidance[0]['sourceRef']==ref
    assert not quality.quality_repair_guidance((issue,),synthesis=False)
    generic=ProviderValidationIssue('hints[0].claimKind',0,'UNRESOLVED_LOCAL_OBSERVATION','INVALID_SCOPED_CLAIM')
    guidance=quality.quality_repair_guidance((generic,),synthesis=True)
    assert guidance and all(not g.get('sourceRef') for g in guidance)
    unrelated=ProviderValidationIssue('newHints[0].claimKind',None,'UNRESOLVED_LOCAL_OBSERVATION',actual)
    guidance=quality.quality_repair_guidance((unrelated,),synthesis=True)
    assert all(not g.get('sourceRef') for g in guidance)
