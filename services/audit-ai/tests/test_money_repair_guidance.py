"""Actual synthetic provider wires; no external service or business materials."""
import copy
from dataclasses import replace
import hashlib
import json
import re

import httpx
import pytest

from audit_ai.config import Settings
from audit_ai.model import semantic_quality as Q
from audit_ai.model.contract_draft import ProviderValidationIssue
from audit_ai.model.gateway import OpenAiCompatibleModelGateway
from audit_ai.model.router import ContractAnalysisRequest
from audit_ai.model.semantic_work import ContractSemanticWorkService,checkpoint_hash
from test_semantic_regeneration_mode import TABLE,_hint,_operation,_gateway,_draft
from test_semantic_regeneration_context import normalized_source,context_from_wire

VERSION='contract-money-regeneration-guidance-v1'


@pytest.mark.parametrize('purpose',['ANALYZE','SYNTHESIZE'])
@pytest.mark.parametrize('reason',['MONETARY_CALCULATION','MONETARY_VALUE'])
def test_actual_repair_wire_explains_combined_money_scope_and_cannot_hide_a_verdict_with_f02(purpose,reason):
    issue=ProviderValidationIssue('hints[0].description',0,reason,'INVALID_SCOPED_CLAIM',
        category='CONTRACT_STRUCTURE',schema_version=Q.PROTOCOL)
    wires=[];operation=replace(_operation(purpose,2),validation_feedback=(issue,))
    _gateway(wires,_draft(purpose,_hint())).analyze_contract(operation)
    system=wires[0]['messages'][0]['content']
    marker='QUALITY_REPAIR_GUIDANCE='
    assert marker in system,'money failure has no precise provider repair guidance'
    guidance=json.JSONDecoder().raw_decode(system.split(marker,1)[1])[0][0]
    assert guidance['protocol']==VERSION and guidance['errorKind']==reason
    assert guidance['validationScope']=='TITLE_LF_DESCRIPTION'
    assert guidance['scope']==('GLOBAL_CHECKED_FROZEN_SOURCE' if purpose=='SYNTHESIZE' else 'CURRENT_OPERATION_EVIDENCE')
    for required in ('title + LF + description','数值为空','尾随F02声明','requiredCategories','完整候选','有效风险','不替代F02'):
        assert required in guidance['instruction']
    assert wires[0]['max_tokens']==6144 and wires[0]['reasoning_effort']=='none'


@pytest.mark.parametrize('purpose,expected',[
    ('ANALYZE','f4447b37da625d2384158765dd9a7e3bcd50a3e379e6e28d9ece4960e1cfac82'),
    ('SYNTHESIZE','cc5477b2dff0578fcde6ed83627565e85dddeaeb7bdaff1a11a7d2849a53fb3b')])
def test_first_provider_body_stays_exactly_frozen_without_repair_feedback(purpose,expected):
    wires=[];_gateway(wires,_draft(purpose,_hint())).analyze_contract(_operation(purpose,1))
    assert checkpoint_hash(wires[0])==expected


def setup(stage,repeat):
    wires=[];payloads=[];counts={'ANALYZE':0,'SYNTHESIZE':0}
    def provider(request):
        wire=json.loads(request.content);wires.append(wire);user=wire['messages'][1]['content']
        purpose=re.search(r'^purpose=(.+)$',user,re.MULTILINE)[1];counts[purpose]+=1;source=user.split('source:\n',1)[1]
        if purpose=='ANALYZE':
            ids=re.findall(r'\[evidenceId=([^;]+);',source)
            neutral=_hint(evidence_id=ids[0]);neutral.update(title='当前来源核对',description='仅核对当前材料中的约定。')
            risk={**copy.deepcopy(neutral),'category':'DELIVERY_TERMS','attention':'REVIEW','title':'交付安排待核实',
                'description':'当前材料交付责任约定不清晰，需核实乙方交付安排。','claimKind':'AMBIGUOUS_CLAUSE',
                'problemEvidenceIds':ids[:1],'comparisonEvidenceIds':[],
                'obligationScope':{'topic':'DELIVERY','responsibleParty':'PARTY_B','trigger':'交付'}}
            payload={'summary':'核对当前合成材料。','scopeStatus':'CHECKED_WITH_FINDINGS','hints':[risk,neutral]}
        else:
            partials,ids=normalized_source(source);hints=[copy.deepcopy(h)for p in partials for h in p];hints.reverse()
            resolutions=[{'observation':[p,h],'disposition':'RETAINED','finalHintIndexes':[hints.index(hint)],
                'basisEvidenceIds':[*hint['problemEvidenceIds'],*hint['comparisonEvidenceIds']],
                'reason':'保留同一合成来源的观察。'}for p,partial in enumerate(partials)for h,hint in enumerate(partial)]
            payload={'summary':'综合当前合成材料。','hints':hints,'resolutions':resolutions}
        if purpose==stage and (counts[purpose]==1 or repeat):
            bad=_hint(evidence_id=ids[0]);bad.update(title='合同金额核对',
                description='合同金额与中标金额一致，后续交由F02确定性金额规则核对。')
            payload['hints'].append(bad)
        payloads.append(copy.deepcopy(payload))
        return httpx.Response(200,json={'model':'offline','choices':[{'finish_reason':'stop','message':{'content':json.dumps(payload,ensure_ascii=False)}}],
            'usage':{'prompt_tokens':100,'completion_tokens':200,'total_tokens':300}})
    settings=Settings(model_name='offline',model_base_url='https://provider.invalid/v1',model_api_key='synthetic',model_chunk_chars=650,model_max_chunks=30)
    service=ContractSemanticWorkService(settings,OpenAiCompatibleModelGateway(settings,transport=httpx.MockTransport(provider)))
    request=ContractAnalysisRequest.model_validate({'projectId':'synthetic-money-feedback','semanticQualityProtocol':Q.PROTOCOL,
        'materials':[{'fileId':'body','category':'SUPPLEMENT','text':(TABLE+'\n交付责任约定不清晰，需核实乙方交付安排。\n')*16}]})
    return service,request,wires,payloads,counts


@pytest.mark.parametrize('stage',['ANALYZE','SYNTHESIZE'])
@pytest.mark.parametrize('repeat',[False,True])
def test_actual_bounded_regeneration_retains_risks_or_stops_repeated_money_without_third_call(stage,repeat):
    service,request,wires,payloads,counts=setup(stage,repeat);plan=service.plan(request);prefix=[];expected=plan['next']['operationFingerprint'];rejected=None
    for _ in range(plan['callLimit']):
        before=copy.deepcopy(prefix)
        response=service.advance(request,plan['planFingerprint'],prefix,expected,remaining_ms=800000,execute=True)
        assert prefix==before;packet=response['checkpoint']
        if packet['status']=='REPAIR_REQUIRED':
            rejected=copy.deepcopy(packet)
            assert packet['validationIssues'][0]['errorKind']=='MONETARY_CALCULATION'
        if rejected is not None and packet['purpose']=='REPAIR':
            system=wires[-1]['messages'][0]['content'];assert 'QUALITY_REPAIR_GUIDANCE=' in system
            assert VERSION in system
            context=Q.decode_regeneration_context(json.dumps(context_from_wire(wires[-1]),ensure_ascii=False,sort_keys=True,separators=(',',':')))
            assert context['rejectedOperationFingerprint']==rejected['operationFingerprint']
            assert context['candidate']==rejected['completion']['payload']
            assert counts[stage]==2 and wires[-1]['messages'][1]['content'].split('source:\n',1)[1]==wires[-2]['messages'][1]['content'].split('source:\n',1)[1]
            if repeat:
                assert packet['status']=='FAILED' and packet['call']['safeReason']=='INVALID_TYPED_SCHEMA'
                assert packet['call']['totalTokens']==300 and response['next']is None and response['result']is None
            else:
                assert packet['status']=='VERIFIED'
                result=packet.get('finalResult')or packet['validatedResult']
                assert any(h['title']=='交付安排待核实'and h['attention']=='REVIEW'for h in result['hints'])
            return
        assert response['next']is not None
        prefix.append(packet);expected=response['next']['operationFingerprint']
    pytest.fail('target regeneration did not occur within original call budget')


def test_money_guidance_version_is_frozen_only_in_quality_policy():
    service,request,_,_,_=setup('ANALYZE',True);plan=service.plan(request)
    assert plan['policy'].get('moneyRepairGuidanceProtocol')==VERSION
    assert plan['policy']['qualityProtocol']==Q.PROTOCOL
    assert hashlib.sha256(__import__('pathlib').Path(Q.__file__).read_bytes()).hexdigest()in plan['policy']['sourceCodeHashes']
    assert 'moneyRepairGuidanceProtocol'not in service.plan(request.model_copy(update={'semantic_quality_protocol':None}))['policy']
