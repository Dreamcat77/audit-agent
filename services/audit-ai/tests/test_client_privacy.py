import base64
import json
from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4

import httpx
import pytest
from fastapi.testclient import TestClient
from audit_ai.client_privacy import ClientIdentity, CURRENT, HEADER, party
from audit_ai.config import Settings
from audit_ai.main import create_app
from audit_ai.model.gateway import OpenAiCompatibleModelGateway


NAMES = ('北辰市公安局', '警务保障处')

def header(names=NAMES):
    return base64.b64encode(json.dumps({'version':1,'names':list(names)},ensure_ascii=False).encode()).decode()


@pytest.mark.parametrize('text,expected',[
    ('甲方（采购人）：北辰市公安局\n乙方：远景公司','PARTY_A'),
    ('甲方：远景公司\n乙方（委托方）：警务保障处','PARTY_B'),
    ('甲方：北辰市公安局\n乙方：警务保障处','AMBIGUOUS'),
    ('甲方：北辰市公安局后勤服务有限公司\n抄送北辰市公安局','UNMATCHED'),
    ('项目名称：北辰市公安局设备采购\n甲方：其他单位','UNMATCHED'),
    ('甲方（盖章）：北 辰 市 公 安 局\n住所：某地','PARTY_A'),
])
def test_party_uses_whole_field_not_mentions(text,expected):
    assert party(text,NAMES)==expected


def test_masks_nested_escaped_json_and_preserves_unicode_offsets():
    name='北辰𠀀市局'
    identity=ClientIdentity((name,), (('file','PARTY_B'),))
    text='乙方：'+name+'，应交付。'
    body={'model':'fixture','messages':[{'role':'system','content':'仅JSON'},
        {'role':'user','content':json.dumps({'text':text})}],
        'response_format':{'enum':[name]}}
    boundary=identity.boundary(body)
    wire=json.dumps(boundary.body,ensure_ascii=False)
    assert name not in wire and '\\ud840' not in wire
    masked=json.loads(boundary.body['messages'][1]['content'])['text']
    assert len(masked)==len(text)
    assert len(masked.encode('utf-16-le'))==len(text.encode('utf-16-le'))
    assert boundary.restore({'citation':masked})=={'citation':text}
    assert body['messages'][1]['content']==json.dumps({'text':text})
    assert 'PARTY_B' in boundary.body['messages'][0]['content']


def test_unrecognized_unicode_encoded_source_fails_before_send():
    identity=ClientIdentity(NAMES,())
    with pytest.raises(ValueError,match='REDACTION_UNPROVEN'):
        identity.boundary({'messages':[{'content':'rules'},{'content':'prefix='+json.dumps({'name':NAMES[0]})}]})


def test_actual_http_boundary_hides_names_and_restores_exact_citation(caplog):
    doc=str(uuid4());original='乙方：北辰市公安局\n验收合格后付款。'
    sent=[]
    def provider(request):
        sent.append(request.content)
        content=json.loads(json.loads(request.content)['messages'][1]['content'])['documents'][0]['text']
        assert all(n not in request.content.decode() for n in NAMES)
        result={'role':'CONTRACT','profile':'SERVICE','complete':True,'facts':[],
                'clauses':[{'topic':'PAYMENT','summary':'验收后付款','citations':[{'document':doc,'text':content}]}], 'findings':[]}
        return httpx.Response(200,json={'model':'fixture','choices':[{'finish_reason':'stop','message':{'content':json.dumps(result,ensure_ascii=False)}}]})
    gateway=OpenAiCompatibleModelGateway(Settings(model_base_url='https://provider.invalid/v1',
        model_api_key='fixture-only',model_name='fixture'),transport=httpx.MockTransport(provider))
    payload={'caseId':str(uuid4()),'inputDigest':'a'*64,'scope':'FRAGMENT',
        'documents':[{'id':doc,'text':original}], 'reviewParties':{doc:'PARTY_B'}}
    with TestClient(create_app(model_gateway=gateway)) as client:
        response=client.post('/ai/v1/contracts/analyze-case',json=payload,headers={HEADER:header()})
    assert response.status_code==200,response.text
    assert response.json()['clauses'][0]['citations'][0]['text']==original
    assert len(sent)==1
    assert 'PARTY_B' in sent[0].decode() and all(n not in caplog.text for n in NAMES)
    assert CURRENT.get() is None


def test_context_does_not_cross_requests_or_threads():
    def worker(name):
        ctx=ClientIdentity((name,),())
        token=CURRENT.set(ctx)
        try:
            body={'messages':[{'content':'JSON'},{'content':name}]}
            assert name not in str(CURRENT.get().boundary(body).body)
            return CURRENT.get().names
        finally:CURRENT.reset(token)
    with ThreadPoolExecutor(max_workers=2) as pool:
        assert list(pool.map(worker,['甲市警务处','乙市警务处']))==[('甲市警务处',),('乙市警务处',)]
    assert CURRENT.get() is None


def test_bad_header_fails_without_echoing_or_calling_model():
    with TestClient(create_app()) as client:
        response=client.post('/ai/v1/contracts/analyze-case',json={},headers={HEADER:'not-base64'})
    assert response.status_code==422 and response.json()=={'code':'CLIENT_IDENTITY_INVALID'}


def test_production_switch_rejects_headerless_calls_but_keeps_health_available():
    with TestClient(create_app(settings=Settings(client_identity_required=True))) as client:
        response=client.post('/ai/v1/contracts/analyze-case',json={})
        assert response.status_code==503 and response.json()['code']=='CLIENT_IDENTITY_REQUIRED'
        assert client.get('/health').status_code==200


def test_all_provider_calls_including_repair_and_summary_remask_restored_names():
    sent=[]
    def provider(request):
        body=json.loads(request.content);sent.append(body)
        assert NAMES[0] not in request.content.decode()
        return httpx.Response(200,json={'model':'fixture','choices':[{'finish_reason':'stop',
            'message':{'content':json.dumps({'summary':body['messages'][1]['content']},ensure_ascii=False)}}]})
    gateway=OpenAiCompatibleModelGateway(Settings(model_base_url='https://provider.invalid/v1',
        model_api_key='fixture-only',model_name='fixture',client_identity_required=True),transport=httpx.MockTransport(provider))
    token=CURRENT.set(ClientIdentity(NAMES,(('document','PARTY_A'),)))
    try:
        content=NAMES[0]+'按约定验收'
        for purpose in ['ANALYZE','EVIDENCE_REPAIR','SYNTHESIZE']:
            result=gateway._complete(operation='CONTRACT_TEXT_ANALYSIS',purpose=purpose,
                system_prompt='仅返回JSON',user_content=content,request_id='privacy-replay',attempt=1,max_attempts=1,
                provider_timeout_seconds=None)
            content=result.payload['summary']
            assert content==NAMES[0]+'按约定验收'
    finally:CURRENT.reset(token)
    assert len(sent)==3


def test_fullwidth_client_name_matches_and_masks_before_provider():
    identity=ClientIdentity(('北辰市公安局(警务保障处)',),())
    original='甲方：北辰市公安局（警务保障处）'
    assert party(original,identity.names)=='PARTY_A'
    boundary=identity.boundary({'messages':[{'content':'JSON'},{'content':original}]})
    assert '北辰' not in str(boundary.body)
    assert boundary.restore(boundary.body['messages'][1]['content'])==original
