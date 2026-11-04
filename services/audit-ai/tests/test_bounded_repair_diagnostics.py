"""Public synthetic mixed defects only: no private material/model/DB traffic."""
import copy
import json

import httpx
import pytest

from test_money_repair_guidance import setup
from audit_ai.model import semantic_quality as Q
from audit_ai.model.contract_draft import ProviderValidationIssue
from audit_ai.model.semantic_work import _JournaledApplication


@pytest.mark.parametrize('repeat',[False,True])
def test_first_typed_work_failure_keeps_primary_and_reports_independent_money_defect_in_same_repair(repeat):
    service,request,wires,_,counts=setup('ANALYZE',repeat)
    original=service.gateway.delegate._transport
    injected=[]
    def provider(req):
        response=original.handle_request(req)
        body=response.json()
        if counts['ANALYZE']==1:
            payload=json.loads(body['choices'][0]['message']['content'])
            local=copy.deepcopy(payload['hints'][1])
            local.update(title='当前块待全局核验',description='仅当前块未见对应约定，需在完整冻结来源中核对。',
                         claimKind='LOCAL_NOT_SEEN',attention='INFO')
            payload['hints'].append(local)
            injected.append(copy.deepcopy(payload))
            body['choices'][0]['message']['content']=json.dumps(payload,ensure_ascii=False)
        return httpx.Response(200,json=body)
    service.gateway.delegate._transport=httpx.MockTransport(provider)
    plan=service.plan(request)
    response=service.advance(request,plan['planFingerprint'],[],plan['next']['operationFingerprint'],remaining_ms=800000,execute=True)
    packet=response['checkpoint']
    assert packet['status']=='REPAIR_REQUIRED'
    assert packet['call']['safeReason']=='INVALID_TYPED_SCHEMA'
    issues=packet['validationIssues']
    assert [(i['errorKind'],i['fieldPath'])for i in issues]==[
        ('WORK_OBSERVATION_TYPE','hints[3].attention'),
        ('MONETARY_CALCULATION','hints[2].description'),
    ],'a primary typed error must not hide another independently provable safety error from the one bounded repair'
    assert packet['completion']['payload']==injected[0]
    assert response['next']is not None and response['result']is None
    second=service.advance(request,plan['planFingerprint'],[packet],response['next']['operationFingerprint'],remaining_ms=700000,execute=True)
    repaired=second['checkpoint']
    assert counts['ANALYZE']==2
    assert all(token in wires[1]['messages'][0]['content']for token in (
        'REGENERATE_WITH_STAGE_VALID_CLAIM_AND_EVIDENCE_TYPE','REGENERATE_WITHOUT_MONETARY_VERDICTS'))
    if repeat:
        assert repaired['status']=='FAILED' and repaired['call']['outcome']=='FAILED'
        assert repaired['call']['safeReason']=='INVALID_TYPED_SCHEMA'
        assert repaired['call']['totalTokens']==300
        assert second['next']is None and second['result']is None
    else:
        assert repaired['status']=='VERIFIED' and repaired['call']['outcome']=='SUCCEEDED'
        assert any(h['attention']=='REVIEW' and h['title']=='交付安排待核实'for h in repaired['validatedResult']['hints'])
    assert packet['completion']['payload']==injected[0]


def test_secondary_diagnostics_are_bounded_deterministic_and_never_mutate_or_accept_a_candidate():
    app=object.__new__(_JournaledApplication)
    primary=ProviderValidationIssue('hints[9].attention',9,'WORK_OBSERVATION_TYPE','INVALID_SCOPED_CLAIM',('INSUFFICIENT',),category='CONTRACT_STRUCTURE')
    raw={'category':'BREACH_LIABILITY','attention':'INFO','title':'金额核对',
        'description':'合同金额与中标金额相等。','problemEvidenceIds':[],
        'comparisonEvidenceIds':['synthetic-id'],'unavailableMaterialCategory':None}
    payload={'hints':[copy.deepcopy(raw)for _ in range(12)]};before=copy.deepcopy(payload)
    issues=app._independent_money_repair_issues(payload,(primary,))
    assert issues[0]is primary
    assert len(issues)==8
    assert [x.field_path for x in issues[1:]]==[f'hints[{i}].description'for i in range(7)]
    assert all(x.error_kind=='MONETARY_CALCULATION'for x in issues[1:])
    assert all(x.category=='BREACH_LIABILITY'for x in issues[1:])
    assert payload==before
    assert issues==app._independent_money_repair_issues(payload,(primary,))
    for broken in (None,[],{'hints':{}},{'hints':[{'title':'PRIVATE_CANARY','category':'PRIVATE_ENUM'}]}):
        assert app._independent_money_repair_issues(broken,(primary,))==(primary,)


def test_only_new_diagnostics_policy_changes_no_initial_transport_or_quality_contract():
    service,request,wires,_,_=setup('ANALYZE',False)
    plan=service.plan(request)
    assert plan['policy']['repairDiagnosticsProtocol']=='contract-bounded-repair-diagnostics-v1'
    assert plan['policy']['maximumRepairDiagnostics']==8
    assert plan['policy']['qualityProtocol']==Q.PROTOCOL
    legacy=service.plan(request.model_copy(update={'semantic_quality_protocol':None}))
    assert 'repairDiagnosticsProtocol'not in legacy['policy']
    assert 'maximumRepairDiagnostics'not in legacy['policy']
    assert plan['callLimit']==len(plan['units'])+2
    assert plan['timeLimitMs']==900000 and plan['unitTimeLimitMs']==300000
    assert plan['maxOutputTokensPerCall']==6144
