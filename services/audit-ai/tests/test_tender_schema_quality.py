import json
import httpx
import pytest
from fastapi.testclient import TestClient
from audit_ai.config import Settings
from audit_ai.main import create_app
from audit_ai.model.gateway import ModelCompletion, OpenAiCompatibleModelGateway


def body():
    return {'projectId':'synthetic', 'reviewId':'synthetic', 'segments':[
        {'sourceLocator':'p1','text':'必须使用某品牌；交付期限存在歧义。'}],
        'fullText':'必须使用某品牌；交付期限存在歧义。', 'ruleFindings':[]}


@pytest.mark.parametrize('repair_succeeds',[True,False])
def test_invalid_empty_object_is_repaired_once_but_never_treated_as_clean(repair_succeeds):
    calls=[]
    def provider(request):
        data=json.loads(request.content);calls.append(data)
        assert '{"findings":[]}' in data['messages'][0]['content']
        payload={'findings':[]} if len(calls)>1 and repair_succeeds else {}
        return httpx.Response(200,json={'choices':[{'finish_reason':'stop','message':{'content':json.dumps(payload)}}]})
    settings=Settings(model_base_url='https://synthetic.invalid/v1',model_api_key='synthetic',model_name='model')
    with TestClient(create_app(settings,model_gateway=OpenAiCompatibleModelGateway(settings,transport=httpx.MockTransport(provider)))) as client:
        response=client.post('/ai/v1/agent/procurement-tender-compliance',json=body())
    assert response.status_code==(200 if repair_succeeds else 502)
    assert len(calls)==2
    assert '上次响应结构' in calls[1]['messages'][1]['content']


def test_distinct_risk_categories_on_same_evidence_are_preserved():
    class Gateway:
        def analyze_tender_compliance(self, operation):
            findings=[{'matchedText':body()['fullText'],'sourceLocator':'p1','riskCategory':category,
                'riskLevel':'WARNING','rationale':'需核对原文。','remediationSuggestion':'请核对相关条款。','confidence':0.9}
                for category in ['指定品牌','工期要求']]
            return ModelCompletion(payload={'findings':findings},model='test',duration_ms=1)
    with TestClient(create_app(Settings(),model_gateway=Gateway())) as client:
        response=client.post('/ai/v1/agent/procurement-tender-compliance',json=body())
    assert response.status_code==200,response.text
    assert {f['riskCategory'] for f in response.json()['data']['findings']}=={'指定品牌','工期要求'}
