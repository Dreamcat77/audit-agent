"""Case endpoint protocol checks; these use a Mock gateway, never claim real semantics."""
from uuid import uuid4
from fastapi.testclient import TestClient
from audit_ai.main import create_app
from audit_ai.model.gateway import ModelCompletion
from audit_ai.model.case_review import VERSION, SYSTEM

def request_body():
    doc = str(uuid4())
    return {"caseId": str(uuid4()), "module": "TENDER_COMPLIANCE", "inputDigest": "b"*64, "version": VERSION,
        "uploadedDocuments": [{"documentId": doc, "fileName": "technical.pdf", "role": "TECHNICAL", "status": "EXTRACTED", "readable": True}],
        "documents": [{"documentId": doc, "fileName": "technical.pdf", "role": "TECHNICAL", "sourceChars": 20, "suppliedChars": 20}],
        "evidence": [{"id": "E-"+"a"*24, "documentId": doc, "fileName": "technical.pdf", "sha256": "c"*64, "locator": "p1", "excerpt": "内存不低于16GB，固态512GB"}],
        "facts": [], "checks": [], "ruleSignals": []}

class MockGateway:
    def analyze_case_review(self, source_text, request_id, system_prompt):
        assert "16GB" in source_text
        assert system_prompt == SYSTEM
        return ModelCompletion(payload={"complete": False, "findings": [], "observations": []}, model="MOCK", duration_ms=1)

def test_scope_basis_is_preserved_and_bad_basis_cannot_erase_other_results():
    from audit_ai.model.case_review import validated_projection
    finding={'basis':'MATERIAL_SCOPE','code':'PROCEDURAL_EVIDENCE','level':'WARNING','title':'未上传后续材料',
             'explanation':'仅有投标文件','suggestion':'补充中标通知书','evidenceIds':['E-synthetic']}
    observation={'code':'DATE_AND_PLAN','explanation':'投标承诺180天交付','evidenceIds':['E-synthetic']}
    parsed=validated_projection({'complete':True,'findings':[finding,{**finding,'basis':'INVENTED'}],'observations':[observation]},'qa')
    assert len(parsed.findings)==1 and parsed.findings[0].basis=='MATERIAL_SCOPE'
    assert parsed.observations[0].explanation=='投标承诺180天交付'
    assert parsed.complete is False

def test_invalid_item_does_not_discard_valid_sibling_or_claim_complete(caplog):
    from audit_ai.model.case_review import validated_projection
    raw={'complete':True,'findings':[
        {'code':'RESTRICTIVE_CONDITION','level':'WARNING','title':'限制条件','explanation':'需关注',
         'suggestion':'调整条件','evidenceIds':['E-valid']},
        {'code':'INVALID','level':'WARNING','title':'private-synthetic-title','explanation':'private-synthetic-text',
         'suggestion':'','evidenceIds':[]}],
         'observations':[{'code':'IDENTITY','explanation':'项目资料已核对','evidenceIds':['E-other']}]}
    result=validated_projection(raw,'synthetic-request')
    assert result.complete is False
    assert len(result.findings)==1 and result.findings[0].evidence_ids==['E-valid']
    assert len(result.observations)==1
    assert 'private-synthetic' not in caplog.text

def test_invalid_case_envelope_and_unbounded_output_are_still_rejected():
    import pytest
    from pydantic import ValidationError
    from audit_ai.model.case_review import validated_projection
    for raw in ({'complete':True,'findings':[]}, {'complete':True,'findings':[{}]*41,'observations':[]}):
        with pytest.raises(ValidationError):validated_projection(raw,'synthetic-request')

def test_case_review_mock_endpoint():
    with TestClient(create_app(model_gateway=MockGateway())) as client:
        r = client.post("/ai/v1/cases/review", json=request_body())
        assert r.status_code == 200, r.text
        assert r.json()["model"] == "MOCK"
        assert r.json()["version"] == VERSION

def test_module_review_plan_preserves_source_driven_topics_without_repeating_other_module():
    import json
    from audit_ai.model.case_review import CaseRequest, model_context
    body=request_body();body['requiredTopics']=['TECHNICAL_REQUIREMENTS']
    value=json.loads(model_context(CaseRequest.model_validate(body)))
    assert value['requiredTopics']==['TECHNICAL_REQUIREMENTS']
    assert '不要重复项目名称' in value['reviewInstructions']
    assert '更正公告' in value['reviewInstructions']
    assert '只能说明评分构成' in SYSTEM
    body['module']='AGENT_PRECHECK';body['requiredTopics']=['IDENTITY']
    value=json.loads(model_context(CaseRequest.model_validate(body)))
    assert '不重复评审评分' in value['reviewInstructions']

def test_reject_evidence_outside_case_and_oversized_context():
    with TestClient(create_app(model_gateway=MockGateway())) as client:
        body = request_body(); body["evidence"][0]["documentId"] = str(uuid4())
        assert client.post("/ai/v1/cases/review", json=body).status_code == 422
        body = request_body(); body["evidence"][0]["excerpt"] = "x"*1601
        assert client.post("/ai/v1/cases/review", json=body).status_code == 422

def test_no_source_is_not_a_successful_review():
    with TestClient(create_app(model_gateway=MockGateway())) as client:
        body = request_body(); body["evidence"] = []
        r = client.post("/ai/v1/cases/review", json=body)
        assert r.status_code == 422

def test_case_timeout_is_bounded_and_isolated_from_legacy_timeout():
    import json, httpx
    from audit_ai.config import Settings
    from audit_ai.model.gateway import OpenAiCompatibleModelGateway
    seen=[]
    def transport(request):
        seen.append((request.extensions['timeout']['read'],json.loads(request.content)['max_tokens']))
        return httpx.Response(200,json={'choices':[{'finish_reason':'stop','message':{'content':'{"complete":false,"findings":[],"observations":[]}'}}]})
    settings=Settings(model_base_url='https://provider.invalid/v1',model_api_key='synthetic',model_name='synthetic')
    gateway=OpenAiCompatibleModelGateway(settings,transport=httpx.MockTransport(transport))
    gateway.analyze_case_review('{}','mock-timeout-contract',SYSTEM)
    assert seen==[(150.0,4096)]
    assert settings.model_read_timeout_seconds==60.0

def test_model_context_does_not_repeat_raw_evidence_in_checks():
    import json
    from audit_ai.model.case_review import CaseRequest, model_context
    body=request_body()
    body['checks']=[{'code':'TEST','status':'CONSISTENT_IN_OBSERVED','explanation':'Mock check','evidence':[body['evidence'][0]]}]*4
    compact=model_context(CaseRequest.model_validate(body))
    assert compact.count(body['evidence'][0]['excerpt'])==1
    parsed=json.loads(compact)
    assert parsed['checks'][0]['evidenceIds']==['EV001']
    assert parsed['evidence'][0]['id']=='EV001'
    assert body['evidence'][0]['id'] not in compact
    assert parsed['evidence'][0]['document']=='D1'

def test_evidence_alias_round_trip_preserves_order_and_keeps_unknown_references_invalid():
    import json
    body=request_body()
    first=body['evidence'][0]
    second={**first,'id':'E-'+'d'*24,'locator':'p2','excerpt':'本市注册要求'}
    body['evidence'].append(second)

    class AliasedGateway:
        def analyze_case_review(self, source_text, request_id, system_prompt):
            context=json.loads(source_text)
            first_alias,second_alias=[e['id'] for e in context['evidence']]
            finding={'code':'RESTRICTIVE_CONDITION','level':'WARNING','title':'地域限制',
                     'explanation':'注册地限制竞争','suggestion':'核对资格要求'}
            return ModelCompletion(payload={'complete':True,'findings':[
                {**finding,'evidenceIds':[second_alias,first_alias]},
                {**finding,'evidenceIds':[first_alias,'EV999']},
                {**finding,'evidenceIds':[first['id']]}],
                'observations':[{'code':'TECHNICAL_REQUIREMENTS','explanation':'技术约定','evidenceIds':[first_alias]}]},
                model='MOCK',duration_ms=1)

    with TestClient(create_app(model_gateway=AliasedGateway())) as client:
        response=client.post("/ai/v1/cases/review",json=body)
    assert response.status_code==200
    payload=response.json()
    assert payload['findings'][0]['evidenceIds']==[second['id'],first['id']]
    assert payload['findings'][1]['evidenceIds']==[first['id'],'UNRESOLVED_EVIDENCE_ID']
    assert payload['findings'][2]['evidenceIds']==['UNRESOLVED_EVIDENCE_ID']
    assert payload['observations'][0]['evidenceIds']==[first['id']]

def test_unknown_system_checks_do_not_seed_empty_or_null_business_conclusions():
    import json
    from audit_ai.model.case_review import CaseRequest, model_context
    body=request_body()
    body['checks']=[{'code':'QUOTE_BUDGET','status':'UNKNOWN','explanation':'预算上限 null','evidence':[]},
                    {'code':'CONFIRMED','status':'PASS','explanation':'已核实的采购内容','evidence':[body['evidence'][0]]}]
    parsed=json.loads(model_context(CaseRequest.model_validate(body)))
    assert [c['code'] for c in parsed['checks']]==['CONFIRMED']
    assert 'null' not in json.dumps(parsed['checks'])

def test_full_upload_inventory_does_not_expand_selected_raw_context():
    import json
    from audit_ai.model.case_review import CaseRequest, model_context
    body=request_body()
    body['uploadedDocuments'].append({'documentId':str(uuid4()),'fileName':'报价单.pdf','role':'QUOTATION','status':'EXTRACTED','readable':True})
    parsed=json.loads(model_context(CaseRequest.model_validate(body)))
    assert len(parsed['documents'])==1
    assert len(parsed['uploadedDocuments'])==2
    assert parsed['uploadedDocuments'][1]['selectedForThisModule'] is False
    assert len(parsed['evidence'])==1

def test_unknown_or_conflicting_inventory_is_rejected_before_call():
    import pytest
    from pydantic import ValidationError
    from audit_ai.model.case_review import CaseRequest
    for change in ['missing','duplicate','role','name']:
        body=request_body()
        if change=='missing':body['uploadedDocuments'][0]['documentId']=str(uuid4())
        elif change=='duplicate':body['uploadedDocuments'].append(body['uploadedDocuments'][0].copy())
        elif change=='role':body['uploadedDocuments'][0]['role']='QUOTATION'
        else:body['uploadedDocuments'][0]['fileName']='other.pdf'
        with pytest.raises(ValidationError):CaseRequest.model_validate(body)

if __name__ == "__main__":
    test_case_review_mock_endpoint()
    test_reject_evidence_outside_case_and_oversized_context()
    test_no_source_is_not_a_successful_review()
    print("CASE_REVIEW_PYTHON_MOCK_PASS tests=3")


def test_java_current_context_version_is_accepted_by_live_endpoint():
    # This crosses the Java/Python protocol boundary so changing only one side
    # cannot make the unit suites green while every deployed model call fails.
    import re
    from pathlib import Path
    java = Path(__file__).resolve().parents[3] / "backend/src/main/java/com/fuquan/audit/procurement/autocase/CaseReviewContext.java"
    version = re.search(r'VERSION="([^"]+)"', java.read_text(encoding="utf-8")).group(1)
    with TestClient(create_app(model_gateway=MockGateway())) as client:
        body = request_body()
        body["version"] = version
        result = client.post("/ai/v1/cases/review", json=body)
        assert result.status_code == 200
        assert result.json()["version"] == version
