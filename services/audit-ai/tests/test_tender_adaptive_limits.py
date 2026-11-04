import json
import httpx
import pytest
from fastapi.testclient import TestClient
from audit_ai.config import Settings
from audit_ai.main import create_app
from audit_ai.model.gateway import OpenAiCompatibleModelGateway


def data():
    segments=[{'sourceLocator':f'pdf/page:{i+1}','text':'说明。'*150+f'条款{i}必须采用特定品牌。','pageNumber':i+1,'confidence':1} for i in range(12)]
    return {'projectId':'synthetic','reviewId':'review','segments':segments,
            'fullText':'\n'.join(s['text'] for s in segments),'ruleFindings':[]}


def test_tender_above_twenty_chunks_keeps_every_character_with_its_own_bounded_budget():
    from dataclasses import replace
    from audit_ai.model.router import TenderComplianceRequest, text_parts
    from audit_ai.model.tender_chunks import prepare_tender
    from audit_ai.errors import AuditAiError
    body=data()
    body['segments']=[{'sourceLocator':f'page:{i}', 'text':'全文核对。'*400, 'pageNumber':i+1} for i in range(30)]
    body['fullText']='\n'.join(s['text'] for s in body['segments'])
    request=TenderComplianceRequest.model_validate(body)
    settings=Settings()
    chunks=prepare_tender(request,settings,text_parts)
    assert len(chunks)>20
    assert ''.join(u.piece.text for c in chunks for u in c)==''.join(s['text'] for s in body['segments'])
    with pytest.raises(AuditAiError) as failure:
        prepare_tender(request,replace(settings,tender_max_chunks=20),text_parts)
    assert failure.value.code=='MODEL_INPUT_TOO_LARGE'


def test_distant_conflicting_delivery_clauses_are_seen_together_with_verbatim_evidence():
    from audit_ai.model.gateway import ModelCompletion
    calls=[]
    class Gateway:
        def analyze_tender_compliance(self,operation):
            source=json.loads(operation.source_text);calls.append(source)
            text=' '.join(s['text'] for s in source['segments'])
            findings=[]
            if '交付期限90天' in text and '交付期限30天' in text:
                findings=[{'matchedText':'交付期限30天','sourceLocator':'p8','pageNumber':8,
                    'riskCategory':'期限不一致','riskLevel':'WARNING','rationale':'前后期限不同，建议核实。',
                    'remediationSuggestion':'统一交付期限。','confidence':0.9}]
            return ModelCompletion(payload={'findings':findings},model='test',duration_ms=1)
    segments=[{'sourceLocator':f'p{i}', 'text':'交付期限90天。' if i==1 else '交付期限30天。' if i==8 else '一般说明。'*350,'pageNumber':i} for i in range(1,9)]
    body={'projectId':'synthetic','reviewId':'review','segments':segments,'fullText':'\n'.join(s['text'] for s in segments),'ruleFindings':[]}
    with TestClient(create_app(Settings(),model_gateway=Gateway())) as client:
        response=client.post('/ai/v1/agent/procurement-tender-compliance',json=body)
    assert response.status_code==200,response.text
    assert any(c.get('reviewFocus')=='CROSS_SOURCE_QUANTITATIVE_COMPARISON'
               and {'p1','p8'} <= {s['sourceLocator'] for s in c['segments']} for c in calls)
    assert response.json()['data']['findings'][0]['sourceLocator']=='p8'


def test_comparison_pair_cannot_be_split_into_unrelated_clean_results_when_provider_rejects_it():
    from audit_ai.model.gateway import ModelCompletion, ModelGatewayFailure
    comparisons=[]
    class Gateway:
        def analyze_tender_compliance(self,operation):
            source=json.loads(operation.source_text)
            if source.get('reviewFocus')=='CROSS_SOURCE_QUANTITATIVE_COMPARISON':
                comparisons.append(source)
                failure=ModelGatewayFailure(reason='PROVIDER_400',retryable=False,duration_ms=1)
                failure.provider_error_kind='CONTEXT_LIMIT'
                raise failure
            return ModelCompletion(payload={'findings':[]},model='test',duration_ms=1)
    segments=[{'sourceLocator':'p1','text':'交付期限90天。','pageNumber':1},
              {'sourceLocator':'p12','text':'交付期限30天。','pageNumber':12}]
    body={'projectId':'synthetic','reviewId':'synthetic','segments':segments,
          'fullText':'\n'.join(s['text'] for s in segments),'ruleFindings':[]}
    with TestClient(create_app(Settings(),model_gateway=Gateway())) as client:
        response=client.post('/ai/v1/agent/procurement-tender-compliance',json=body)
    assert response.status_code==413
    assert response.json()['code']=='MODEL_INPUT_TOO_LARGE'
    assert len(comparisons)==1 and len(comparisons[0]['segments'])==2


@pytest.mark.parametrize('status',[400,200])
def test_tender_json_is_always_complete_and_last_page_survives_provider_limit(status):
    seen=[];rejections=[]
    def provider(request):
        body=json.loads(request.content)
        source=json.loads(body['messages'][1]['content'].split('source:\n',1)[1])
        assert body['max_tokens']==1536
        if sum(len(s['text']) for s in source['segments'])>350:
            rejections.append(source)
            return httpx.Response(status,json={'success':False,'error':{'message':'maximum context length'}})
        seen.extend(source['segments'])
        findings=[]
        for s in source['segments']:
            text=s.get('contextBefore','')+s['text']+s.get('contextAfter','')
            if '必须采用特定品牌' in text:
                findings.append({'matchedText':'必须采用特定品牌','sourceLocator':s['sourceLocator'],
                    'pageNumber':s['pageNumber'],'riskCategory':'品牌倾向','riskLevel':'WARNING',
                    'rationale':'存在品牌指向，建议人工复核。','remediationSuggestion':'核对是否可使用通用参数。','confidence':0.9})
        return httpx.Response(200,json={'choices':[{'finish_reason':'stop','message':{'content':json.dumps({'findings':findings})}}]})
    settings=Settings(model_base_url='https://synthetic.invalid/v1',model_api_key='synthetic',model_name='model')
    gateway=OpenAiCompatibleModelGateway(settings,transport=httpx.MockTransport(provider));body=data()
    with TestClient(create_app(settings,model_gateway=gateway)) as client:
        response=client.post('/ai/v1/agent/procurement-tender-compliance',json=body)
    assert response.status_code==200,response.text
    assert rejections
    assert {f['sourceLocator'] for f in response.json()['data']['findings']}=={s['sourceLocator'] for s in body['segments']}
    for original in body['segments']:
        assert ''.join(s['text'] for s in seen if s['sourceLocator']==original['sourceLocator'])==original['text']


def test_tender_foreign_chunk_evidence_is_rejected_even_if_in_original_document():
    body=data()
    def provider(request):
        source=json.loads(json.loads(request.content)['messages'][1]['content'].split('source:\n',1)[1])
        assert not any(s['sourceLocator']=='pdf/page:12' for s in source['segments'])
        return httpx.Response(200,json={'choices':[{'message':{'content':json.dumps({'findings':[{
            'matchedText':'必须采用特定品牌','sourceLocator':'pdf/page:12','pageNumber':12,
            'riskCategory':'品牌倾向','riskLevel':'WARNING','rationale':'测试','remediationSuggestion':'核对','confidence':0.9}]})}}]})
    settings=Settings(model_base_url='https://synthetic.invalid/v1',model_api_key='synthetic',model_name='model')
    with TestClient(create_app(settings,model_gateway=OpenAiCompatibleModelGateway(settings,transport=httpx.MockTransport(provider)))) as client:
        assert client.post('/ai/v1/agent/procurement-tender-compliance',json=body).status_code==502


def test_fulltext_not_covered_by_segments_is_preserved_as_non_citable_context():
    body=data();body['fullText']+='附加说明不可遗漏'
    seen=[]
    def provider(request):
        source=json.loads(json.loads(request.content)['messages'][1]['content'].split('source:\n',1)[1]);seen.append(source)
        return httpx.Response(200,json={'choices':[{'message':{'content':'{"findings":[]}'}}]})
    settings=Settings(model_max_chunks=40,model_base_url='https://synthetic.invalid/v1',model_api_key='synthetic',model_name='model')
    with TestClient(create_app(settings,model_gateway=OpenAiCompatibleModelGateway(settings,transport=httpx.MockTransport(provider)))) as client:
        assert client.post('/ai/v1/agent/procurement-tender-compliance',json=body).status_code==200
    assert ''.join(s['text'] for c in seen for s in c['supplementaryText'])==body['fullText']
    assert all('sourceLocator' not in s for c in seen for s in c['supplementaryText'])
