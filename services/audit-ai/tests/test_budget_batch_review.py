import json
from uuid import uuid4
import httpx
from fastapi.testclient import TestClient
from audit_ai.main import create_app
from audit_ai.model.gateway import ModelCompletion, OpenAiCompatibleModelGateway
from audit_ai.config import Settings
from audit_ai.model.budget_review import SYSTEM

QUOTE='批准用途为设备购置，申请却列宴请费。'
EID='E-'+'a'*24
def body():
    return {'batchId':str(uuid4()),'inputDigest':'b'*64,'scope':'FRAGMENT','checks':[],
        'evidence':[{'id':EID,'taskId':str(uuid4()),'fileName':'合成请示.pdf','locator':'pdf/page:1/ocr','text':QUOTE}]}
def item():
    return {'code':'PURPOSE_SCOPE','level':'WARNING','basis':'CLAUSE_ISSUE','title':'用途不一致','explanation':'申请用途与批准用途不同。',
        'suggestion':'按批准用途调整。','evidenceIds':[EID],'excerpts':{EID:QUOTE}}
class Gateway:
    def __init__(self,items):self.items=items
    def analyze_budget_batch(self,source_text,request_id,system_prompt):
        assert system_prompt==SYSTEM and json.loads(source_text)['scope']=='FRAGMENT'
        return ModelCompletion(payload={'complete':True,'items':self.items},model='MOCK',duration_ms=1)

def test_budget_raw_text_and_exact_quotes_are_preserved():
    with TestClient(create_app(model_gateway=Gateway([item()]))) as client:
        result=client.post('/ai/v1/budgets/review-batch',json=body())
        assert result.status_code==200,result.text
        assert result.json()['complete'] is True and result.json()['items'][0]['excerpts'][EID]==QUOTE

def test_bad_item_does_not_destroy_good_sibling_or_leak_content(caplog):
    for bad in [{**item(),'excerpts':{EID:'PRIVATE-SYNTHETIC-NOT-IN-SOURCE'}},{**item(),'evidenceIds':['E-'+'f'*24]}, {**item(),'code':'BAD'}]:
        with TestClient(create_app(model_gateway=Gateway([item(),bad]))) as client:
            result=client.post('/ai/v1/budgets/review-batch',json=body())
            assert result.status_code==200,result.text
            assert result.json()['complete'] is False and len(result.json()['items'])==1
    assert QUOTE not in caplog.text and 'PRIVATE-SYNTHETIC' not in caplog.text

def test_context_limits_and_duplicates_are_rejected_before_model():
    with TestClient(create_app(model_gateway=Gateway([]))) as client:
        data=body();data['evidence']*=2
        assert client.post('/ai/v1/budgets/review-batch',json=data).status_code==422
        data=body();data['evidence'][0]['text']='x'*3001
        assert client.post('/ai/v1/budgets/review-batch',json=data).status_code==422

def test_budget_wait_is_180_seconds_and_one_attempt(caplog):
    seen=[]
    def transport(request):
        seen.append(request.extensions['timeout']['read'])
        return httpx.Response(200,json={'choices':[{'finish_reason':'stop','message':{'content':'{"complete":true,"items":[]}'}}]})
    settings=Settings(model_base_url='https://synthetic.invalid/v1',model_api_key='synthetic',model_name='synthetic')
    gateway=OpenAiCompatibleModelGateway(settings,transport=httpx.MockTransport(transport))
    gateway.analyze_budget_batch(QUOTE,'synthetic-budget',SYSTEM)
    assert seen==[180.0]
    assert QUOTE not in caplog.text

def test_budget_scope_survives_material_endpoint_not_just_model_payload():
    class ExtractionGateway:
        def extract_material_fields(self,kind,source_text,request_id,*,timeout_seconds=180):
            return ModelCompletion(payload={'rows':[{'rowKey':'r','facts':[
                {'field':'projectBudgetAmount','value':'壹佰贰拾万元','excerpt':'经费盘子按人民币壹佰贰拾万元控制','locator':'p1','subjectScope':'CURRENT_PROJECT','amountScope':'TOTAL'},
                {'field':'projectBudgetAmount','value':'叁拾万元','excerpt':'历史项目叁拾万元','locator':'p1','subjectScope':'REFERENCE','amountScope':'TOTAL'}]}]},model='MOCK',duration_ms=1)
    data={'businessType':'BUDGET','materialId':str(uuid4()),'sha256':'d'*64,'segments':[{'locator':'p1','text':'经费盘子按人民币壹佰贰拾万元控制。历史项目叁拾万元。'}]}
    with TestClient(create_app(Settings(),model_gateway=ExtractionGateway())) as client:
        response=client.post('/ai/v1/materials/extract-fields',json=data)
        assert response.status_code==200,response.text
        facts=[f for r in response.json()['data']['rows'] for f in r['facts']]
        assert [(f['subjectScope'],f['amountScope']) for f in facts]==[('CURRENT_PROJECT','TOTAL'),('REFERENCE','TOTAL')]
