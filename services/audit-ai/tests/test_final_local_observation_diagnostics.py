"""Bounded simultaneous LOCAL diagnostics, real native service and wire feedback."""
import copy
import json

import pytest

from audit_ai.model import semantic_quality as quality
from audit_ai.model.gateway import ModelCompletion
from audit_ai.model.contract_draft import ProviderValidationIssue
from audit_ai.model.semantic_work import ContractSemanticWorkService
from test_hierarchy_local_observation_feedback import LocalProvider, canonical
from test_semantic_hierarchy_execution import setup, decode_claim
from test_semantic_regeneration_context_wire import capture, context_on_wire

LOCALS=(1,3,7)
IDENTITIES=(('DELIVERY_TERMS','DELIVERY'),('SERVICE_WARRANTY','WARRANTY'),('BREACH_LIABILITY','OTHER_LIABILITY'))

def local_hint(index=0):
    category,topic=IDENTITIES[index%3]
    return {'category':category,'attention':'INSUFFICIENT','title':f'本段核查{index}',
        'description':'本段未见相关安排。','problemEvidenceIds':[], 'comparisonEvidenceIds':['e0'],
        'unavailableMaterialCategory':None,'claimKind':'LOCAL_NOT_SEEN',
        'obligationScope':{'topic':topic,'responsibleParty':'PARTY_B','trigger':'履行'}}

def raw_failure(count=3):
    raw={'summary':'完整观察保留。','hints':[local_hint(i) for i in range(count)],'resolutions':[]}
    with pytest.raises(quality.QualityFailure) as failed:quality.parse_scoped_draft(raw,synthesis=True)
    return raw,failed.value

@pytest.mark.parametrize('count',[3,8,10])
def test_all_same_class_local_coordinates_are_collected_up_to_eight_without_changing_first_failure(count):
    original,error=raw_failure(count)
    issues=error.safe_issues
    assert len(issues)==min(count,8)
    assert [i.field_path for i in issues]==[f'hints[{i}].claimKind' for i in range(min(count,8))]
    assert [i.item_index for i in issues]==list(range(min(count,8)))
    assert [i.category for i in issues]==[original['hints'][i]['category'] for i in range(min(count,8))]
    assert all(i.error_kind=='UNRESOLVED_LOCAL_OBSERVATION' for i in issues)
    assert error.code=='UNRESOLVED_LOCAL_OBSERVATION' and error.field_path=='hints[0].claimKind'
    assert error.category==original['hints'][0]['category']
    assert error.audit_reason=='INVALID_TYPED_SCHEMA'
    assert all('LOCAL_NOT_SEEN' not in i.allowed_values for i in issues)

def test_an_earlier_different_failure_is_not_reordered_or_relabelled_by_collection():
    first=local_hint();first.update(claimKind='MATERIAL_MISSING',unavailableMaterialCategory=None)
    raw={'summary':'全部候选保留。','hints':[first,local_hint(1)],'resolutions':[]}
    with pytest.raises(quality.QualityFailure) as failed:quality.parse_scoped_draft(raw,synthesis=True)
    assert failed.value.code=='MATERIAL_ABSENCE_TYPE'
    assert failed.value.safe_issues[0].field_path=='hints[0].unavailableMaterialCategory'

class MultiLocalProvider(LocalProvider):
    def __init__(self,*,repair='unchanged',mixed=False):
        super().__init__();self.repair_mode=repair;self.mixed=mixed

    def analyze_contract(self,op):
        result=super().analyze_contract(op)
        if op.hierarchy_role is None:
            eid=result.payload['hints'][0]['comparisonEvidenceIds'][0]
            for j,index in enumerate(LOCALS):
                hint=local_hint(j);hint['comparisonEvidenceIds']=[eid]
                result.payload['hints'][index]=hint
            return result
        if op.hierarchy_role=='GROUP':return result
        source=json.loads(op.source_text);raw=result.payload
        ids={row[0]:i for i,row in enumerate(source['evidence'])}
        if self.mixed and op.attempt==1:
            hint=decode_claim(source['groups'][0]['claims'][LOCALS[0]],source)
            raw['newHints']=[copy.deepcopy(hint)]
            raw['resolutions'][LOCALS[0]]=[0,LOCALS[0],'NEEDS_VERIFICATION',['N0'],
                [ids[e] for e in hint['comparisonEvidenceIds']],'保留当前来源的待核实观察。']
        if op.attempt==2 and self.repair_mode!='unchanged':
            for index in LOCALS:
                original=decode_claim(source['groups'][0]['claims'][index],source)
                replacement=copy.deepcopy(original)
                replacement.update(claimKind='AMBIGUOUS_CLAUSE',title='原义务安排待核实',
                    description='履行安排需结合原件进一步核查。',problemEvidenceIds=original['comparisonEvidenceIds'][:],comparisonEvidenceIds=[])
                if self.repair_mode=='scope':replacement['obligationScope']['responsibleParty']='PARTY_A'
                elif self.repair_mode=='no-evidence':replacement['problemEvidenceIds']=[]
                elif self.repair_mode=='fake-info':
                    replacement.update(attention='INFO',claimKind='CLAUSE_PRESENT',problemEvidenceIds=[],
                                       comparisonEvidenceIds=original['comparisonEvidenceIds'][:])
                target='N'+str(len(raw['newHints']));raw['newHints'].append(replacement)
                basis=replacement['problemEvidenceIds']+replacement['comparisonEvidenceIds']
                raw['resolutions'][index]=[0,index,'NEEDS_VERIFICATION',[target],[ids[e] for e in basis],
                    '按完整来源保留同一义务主体和触发范围的待核实事项。']
        self.final_wires[-1]=copy.deepcopy(raw)
        return result

def drive(*,repair='unchanged',mixed=False):
    _,_,settings,request=setup(repeat=10)
    request.materials[0].text='乙方履行交付、保修及责任约定，均按双方原文核查。'
    provider=MultiLocalProvider(repair=repair,mixed=mixed)
    service=ContractSemanticWorkService(settings,provider);plan=service.plan(request)
    packets=[];expected=plan['next']['operationFingerprint']
    for _ in range(plan['callLimit']):
        last=service.advance(request,plan['planFingerprint'],packets,expected,remaining_ms=800000,execute=True)
        packets.append(last['checkpoint'])
        if last['next'] is None:break
        expected=last['next']['operationFingerprint']
    return provider,plan,packets,last

def feedback(packet):
    return tuple(ProviderValidationIssue(i['fieldPath'],i['itemIndex'],i['errorKind'],i['actualClass'],
        tuple(i['allowedValues']),i['nullAllowed'],category=i.get('category'),schema_version=i['schemaVersion'])
        for i in packet['validationIssues'])

def references(items):
    return [ref for item in items for ref in ([item['sourceRef']] if item.get('sourceRef') else item.get('sourceRefs',[]))]

def test_actual_native_service_reports_all_three_inherited_refs_once_and_repeated_candidate_still_fails():
    provider,plan,packets,last=drive()
    finals=[p for p in packets if p['role']=='FINAL']
    assert [p['status'] for p in finals]==['REPAIR_REQUIRED','FAILED']
    assert next(p for p in packets if p['role']=='GROUP')['status']=='VERIFIED'
    guidance=quality.quality_repair_guidance(feedback(finals[0]),synthesis=True)
    assert references(guidance)==['G1:H2','G1:H4','G1:H8']
    assert finals[0]['call']['safeReason']=='INVALID_TYPED_SCHEMA'
    assert finals[0]['validationIssues'][0]['errorKind']=='UNRESOLVED_LOCAL_OBSERVATION'
    assert finals[0]['validationIssues'][0]['itemIndex'] is None
    assert len(finals[0]['validationIssues'])==1
    assert finals[0]['validationIssues'][0]['actualClass']=='SOURCES_G1_H2__G1_H4__G1_H8_INVALID_SCOPED_CLAIM'
    assert finals[0]['validationIssues'][0]['category']=='DELIVERY_TERMS' # Primary only, not all sources.
    assert provider.final_wires[0]==provider.final_wires[1]
    assert last['next'] is None and last['result'] is None
    assert len(provider.calls)<=plan['callLimit']<=22
    op=next(o for o in provider.calls if o.hierarchy_role=='FINAL' and o.attempt==2)
    body=capture(op).body;system=json.loads(body)['messages'][0]['content']
    request=json.loads(body)
    assert request['max_tokens']==6144 and request['temperature']==0 and request['reasoning_effort']=='none'
    assert request['response_format']=={'type':'json_object'}
    assert plan['policy']['automaticTransportRetries']==0 and plan['policy']['budgetSeconds']==900
    assert plan['policy']['maxOutputTokens']==6144
    assert all(ref in system for ref in ('G1:H2','G1:H4','G1:H8'))
    decoded=quality.decode_regeneration_context(context_on_wire(body))
    assert decoded['candidate']==provider.final_wires[0]
    assert len(op.regeneration_context_json.encode())<=8192

def test_native_new_hint_and_inherited_refs_keep_distinct_coordinate_domains():
    _,_,packets,_=drive(mixed=True)
    first=next(p for p in packets if p['role']=='FINAL')
    issues=first['validationIssues']
    assert issues[0]['fieldPath']=='newHints[0].claimKind' and issues[0]['itemIndex'] is None
    assert issues[0]['category']=='DELIVERY_TERMS'
    assert len(issues)==3 and [i['fieldPath'] for i in issues[1:]]==['inheritedHints','inheritedHints']
    refs=references(quality.quality_repair_guidance(feedback(first),synthesis=True))
    assert refs==['G1:H4','G1:H8'] and 'G1:H2' not in refs
    assert all('hints[3]' not in i['fieldPath'] and 'hints[7]' not in i['fieldPath'] for i in issues)

def test_one_full_same_scope_repair_of_all_locals_passes_all_original_final_checks():
    provider,_,packets,last=drive(repair='valid')
    assert packets[-1]['status']=='VERIFIED' and last['result'] is not None and last['result']['available']
    raw=provider.final_wires[-1]
    assert len(raw['newHints'])==3 and len(raw['resolutions'])==8
    assert len({tuple(r[:2]) for r in raw['resolutions']})==8
    assert {h['category'] for h in raw['newHints']}=={c for c,_ in IDENTITIES}
    assert all(h['attention']=='INSUFFICIENT' and h['problemEvidenceIds'] and h['claimKind']=='AMBIGUOUS_CLAUSE'
               for h in raw['newHints'])

@pytest.mark.parametrize('defect',['scope','no-evidence','fake-info'])
def test_collection_never_accepts_retyped_fake_info_changed_scope_or_missing_evidence(defect):
    provider,_,packets,last=drive(repair=defect)
    assert packets[-1]['role']=='FINAL' and packets[-1]['status']=='FAILED'
    assert last['next'] is None and last['result'] is None and len(provider.final_wires)==2

def test_group_claim_parser_still_allows_private_local_observations():
    raw={'summary':'本组私有观察。','hints':[local_hint(i) for i in range(3)],'scopeStatus':'CHECKED_WITH_FINDINGS'}
    parsed,metadata=quality.parse_scoped_draft(raw,synthesis=False)
    assert len(parsed['hints'])==3 and [h['claimKind'] for h in metadata['hintMetadata']]==['LOCAL_NOT_SEEN']*3

def aggregate_issue(refs):
    return ProviderValidationIssue('inheritedHints',None,'UNRESOLVED_LOCAL_OBSERVATION',
        'SOURCES_'+'__'.join(refs)+'_INVALID_SCOPED_CLAIM',category='DELIVERY_TERMS',schema_version=quality.PROTOCOL)

def test_finite_aggregate_preserves_order_unique_coordinates_and_eight_item_bound():
    tokens=['G3_H100','G1_H2','G2_H30','G1_H40','G2_H50','G1_H60','G3_H70','G1_H80']
    issue=aggregate_issue(tokens)
    guidance=quality.quality_repair_guidance((issue,),synthesis=True)
    assert len(guidance)==1
    assert references(guidance)==[t.replace('_',':') for t in tokens]
    assert len(set(references(guidance)))==8
    assert not quality.quality_repair_guidance((issue,),synthesis=False)

@pytest.mark.parametrize('tokens',[
    ['G1_H2','G1_H2'],['G1_H2'],['G1_H2','G4_H1'],['G0_H2','G1_H8'],
    ['G1_H0','G1_H8'],['G1_H101','G1_H8'],['G1_H2','G1_H8 PRIVATE_CANARY'],
    ['G1_H'+str(i) for i in range(1,10)],['N0','G1_H8']])
def test_malformed_or_injected_aggregate_never_produces_source_authority_or_echoes(tokens):
    issue=aggregate_issue(tokens)
    guidance=quality.quality_repair_guidance((issue,),synthesis=True)
    assert not references(guidance)
    assert issue.actual_class not in canonical(guidance) and 'PRIVATE_CANARY' not in canonical(guidance)
