"""Adversarial limits, boundary evidence, privacy and disposable restart reuse."""
import json
import logging
import os
from pathlib import Path
import re
import subprocess
import sys

import httpx
import pytest
from fastapi.testclient import TestClient

from audit_ai.config import Settings
from audit_ai.main import create_app
from audit_ai.model.gateway import ModelCompletion, ModelGatewayFailure, OpenAiCompatibleModelGateway
from audit_ai.model.material_checkpoints import MaterialCheckpoints, digest
from audit_ai.model.material_chunks import bounded_chunks, render
from audit_ai.model.material_fields import Segment, text_parts


def request(kind='BUDGET',count=12):
    return {'businessType':kind,'materialId':'synthetic-material','sha256':'a'*64,
            'segments':[{'locator':f'page:{i}','text':f'科目{i}申报金额：100元。'+('办公说明。'*90)} for i in range(count)]}


def joined(segment):
    return segment.get('contextBefore','')+segment['text']+segment.get('contextAfter','')


def reply(content,reason='stop'):
    return httpx.Response(200,json={'choices':[{'finish_reason':reason,'message':{'content':json.dumps(content,ensure_ascii=False)}}]})


def gateway(handler,**options):
    settings=Settings(model_base_url='https://synthetic.invalid/v1',model_name='synthetic-model',
                      model_api_key='SECRET_SENTINEL',**options)
    return OpenAiCompatibleModelGateway(settings,transport=httpx.MockTransport(handler))


@pytest.mark.parametrize('kind',['BUDGET','PROCUREMENT'])
@pytest.mark.parametrize('status',[400,413,422,200])
def test_actual_gateway_input_rejection_repartitions_and_keeps_all_12_sources(kind,status,caplog):
    accepted=[];rejected=[]
    def provider(http_request):
        payload=json.loads(http_request.content)
        segments=json.loads(payload['messages'][1]['content'].split('segments=',1)[1])
        if sum(len(s['text']) for s in segments)>600:
            rejected.append(segments)
            error={'success':False,'error':{'message':'maximum context length BUSINESS_SENTINEL'}}
            return httpx.Response(status,json=error)
        accepted.extend(segments)
        field='subjectName' if kind=='BUDGET' else 'requirementDescription'
        return reply({'rows':[{'rowKey':str(i),'facts':[{'field':field,'value':s['text'],
                    'excerpt':s['text'],'locator':s['locator']}]} for i,s in enumerate(segments)]})
    caplog.set_level(logging.INFO,logger='audit_ai')
    data=request(kind)
    with TestClient(create_app(Settings(),model_gateway=gateway(provider))) as client:
        response=client.post('/ai/v1/materials/extract-fields',json=data)
    assert response.status_code==200,response.text
    assert rejected and len(accepted)>=12
    for source in data['segments']:
        assert ''.join(s['text'] for s in accepted if s['locator']==source['locator'])==source['text']
    assert 'reason_code=INPUT_LIMIT' in caplog.text
    assert 'SECRET_SENTINEL' not in caplog.text and 'BUSINESS_SENTINEL' not in caplog.text
    assert '办公说明' not in caplog.text


@pytest.mark.parametrize('status,error',[
    (200,{'code':10016,'success':False,'msg':'不在可访问IP列表内，maximum context length'}),
    (200,{'success':False,'error':{'message':'invalid api key; maximum context length'}}),
    (401,{'error':{'message':'maximum context length'}}),
    (403,{'error':{'message':'maximum context length'}}),
    (429,{'error':{'message':'too many requests'}}),
    (400,{'error':{'message':'unsupported response_format'}}),
    (500,{'error':{'message':'maximum context length'}}),
    (200,{'choices':[]}),
])
def test_permissions_protocol_and_server_errors_are_not_smaller_retries(status,error):
    calls=[]
    def provider(r):calls.append(r);return httpx.Response(status,json=error)
    with TestClient(create_app(Settings(),model_gateway=gateway(provider))) as client:
        response=client.post('/ai/v1/materials/extract-fields',json=request(count=2))
    assert response.status_code==502
    assert len(calls)==1
    assert 'data' not in response.json() or not response.json()['data']


def test_budgets_bound_unicode_and_json_overhead_without_dropping_any_character():
    settings=Settings(material_chunk_chars=700,material_chunk_bytes=1400)
    originals=[Segment(locator='office/行:'+str(i)+'x'*200,text='原文😀汉字'*500+'尾部金额100元') for i in range(12)]
    chunks=bounded_chunks(originals,settings,text_parts)
    for chunk in chunks:
        source=render(chunk,settings.material_context_chars)
        assert len(source)<=700 and len(source.encode('utf-8'))<=1400
    for source in originals:
        assert ''.join(p.text for c in chunks for p in c if p.locator==source.locator)==source.text


def test_cross_boundary_money_label_and_unit_remain_grounded_once():
    # Force the field label across the 1800-character core boundary.
    text='说明'*897+'申报金额：12345.67万元；'+'材料说明'*450+'最后证据'
    seen=[]
    def provider(r):
        segments=json.loads(json.loads(r.content)['messages'][1]['content'].split('segments=',1)[1]);seen.extend(segments)
        rows=[]
        for s in segments:
            m=re.search(r'申报金额：([0-9.]+万元)',joined(s))
            if m:rows.append({'rowKey':'row','facts':[{'field':'declaredAmount','value':m[1],
                            'excerpt':m[0],'locator':s['locator']}]})
        return reply({'rows':rows})
    data=request(count=1);data['segments']=[{'locator':'original/page:1','text':text}]
    with TestClient(create_app(Settings(),model_gateway=gateway(provider))) as client:
        response=client.post('/ai/v1/materials/extract-fields',json=data)
    assert response.status_code==200,response.text
    assert ''.join(s['text'] for s in seen)==text
    facts=[f for r in response.json()['data']['rows'] for f in r['facts']]
    assert len(facts)==1 and facts[0]['value']=='12345.67万元'
    assert facts[0]['excerpt'] in text and facts[0]['locator']=='original/page:1'
    assert any('contextAfter' in s for s in seen)


def test_same_amount_in_two_real_budget_rows_is_not_deduplicated():
    text='设备申报100元；维修申报100元'
    def provider(r):return reply({'rows':[{'rowKey':str(i),'facts':[
        {'field':'subjectName','value':subject,'excerpt':subject,'locator':'row'},
        {'field':'declaredAmount','value':'100元','excerpt':'申报100元','locator':'row'}]}
        for i,subject in enumerate(['设备','维修'])]})
    data=request(count=1);data['segments']=[{'locator':'row','text':text}]
    with TestClient(create_app(Settings(),model_gateway=gateway(provider))) as client:
        response=client.post('/ai/v1/materials/extract-fields',json=data)
    assert response.status_code==200
    assert len(response.json()['data']['rows'])==2
    assert all(any(f['field']=='declaredAmount' for f in row['facts']) for row in response.json()['data']['rows'])


def test_two_projects_with_the_same_model_local_key_remain_separate_after_input_splits():
    def provider(r):
        segments=json.loads(json.loads(r.content)['messages'][1]['content'].split('segments=',1)[1])
        if len(segments)>1:return httpx.Response(413,json={'error':'request too large'})
        s=segments[0];name='设备项目' if s['locator']=='p1' else '工程项目'
        return reply({'rows':[{'rowKey':'same','facts':[{'field':'projectName','value':name,
                            'excerpt':name,'locator':s['locator']}]}]})
    data=request('PROCUREMENT',count=1);data['segments']=[{'locator':'p1','text':'设备项目'}, {'locator':'p2','text':'工程项目'}]
    with TestClient(create_app(Settings(),model_gateway=gateway(provider))) as client:
        response=client.post('/ai/v1/materials/extract-fields',json=data)
    assert response.status_code==200 and len(response.json()['data']['rows'])==2
    assert {f['value'] for row in response.json()['data']['rows'] for f in row['facts']}=={'设备项目','工程项目'}


def test_provider_model_change_between_segments_does_not_produce_mixed_complete_result():
    class ChangedModel:
        def __init__(self):self.count=0
        def extract_material_fields(self,*args,**kwargs):
            self.count+=1
            return ModelCompletion(payload={'rows':[]},model=f'model-{self.count}',duration_ms=1)
    with TestClient(create_app(Settings(),model_gateway=ChangedModel())) as client:
        response=client.post('/ai/v1/materials/extract-fields',json=request())
    assert response.status_code==502 and response.json()['code']=='MATERIAL_AI_INVALID_RESULT'


class RestartGateway:
    def __init__(self,fail=False):self.fail=fail;self.calls=[]
    def extract_material_fields(self,kind,source,request_id,**kwargs):
        segments=json.loads(source);self.calls.append(segments)
        if sum(len(s['text']) for s in segments)>600:
            failure=ModelGatewayFailure(reason='PROVIDER_400',retryable=False,duration_ms=1)
            failure.provider_error_kind='CONTEXT_LIMIT';raise failure
        if self.fail and any(s['locator']=='page:7' for s in segments):
            # Non-timeout interruption retains independently validated cache units.
            # Timeout abandonment and cache removal have their own regression test.
            raise ModelGatewayFailure(reason='CONNECT_ERROR',retryable=True,duration_ms=1)
        field='subjectName' if kind=='BUDGET' else 'requirementDescription'
        return ModelCompletion(payload={'rows':[{'rowKey':str(i),'facts':[{'field':field,
            'value':s['text'],'excerpt':s['text'],'locator':s['locator']}]} for i,s in enumerate(segments)]},model='synthetic',duration_ms=1)


@pytest.mark.parametrize('kind',['BUDGET','PROCUREMENT'])
def test_late_failure_then_new_app_reuses_only_revalidated_completed_chunks(tmp_path,kind,caplog):
    root=tmp_path/'private';settings=Settings(material_checkpoint_root=str(root))
    data=request(kind);first=RestartGateway(fail=True)
    with TestClient(create_app(settings,model_gateway=first)) as client:
        failed=client.post('/ai/v1/materials/extract-fields',json=data)
    assert failed.status_code==502
    assert root.exists() and list(root.glob('*.json'))
    if os.name=='posix':assert root.stat().st_mode&0o077==0
    # Simulate a service restart: reconstruct both router and cache from disk.
    second=RestartGateway()
    caplog.set_level(logging.INFO,logger='audit_ai')
    with TestClient(create_app(settings,model_gateway=second)) as client:
        response=client.post('/ai/v1/materials/extract-fields',json=data)
    assert response.status_code==200,response.text
    assert not any(s['locator']=='page:0' for call in second.calls for s in call)
    facts=[f for row in response.json()['data']['rows'] for f in row['facts']]
    assert {f['locator'] for f in facts}=={f'page:{i}' for i in range(12)}
    assert 'MATERIAL_CHECKPOINT_REUSED' in caplog.text
    assert list(root.iterdir())==[], 'Successful operation clears temporary candidates'


def test_checkpoint_survives_an_actual_interpreter_restart_and_expires(tmp_path):
    root=tmp_path/'private';store=MaterialCheckpoints(str(root),clock=lambda:100)
    operation,key=digest('operation'),digest('chunk')
    assert store.put(operation,key,{'kind':'complete','payload':{'rows':[]},'model':'test'})
    env={**os.environ,'PYTHONPATH':str(Path(__file__).resolve().parents[1]/'src')}
    code="from audit_ai.model.material_checkpoints import MaterialCheckpoints; import sys; c=MaterialCheckpoints(sys.argv[1],clock=lambda:101); assert c.get(sys.argv[2],sys.argv[3])['model']=='test'; print('REOPEN_PASS')"
    result=subprocess.run([sys.executable,'-c',code,str(root),operation,key],env=env,capture_output=True,text=True,check=True)
    assert 'REOPEN_PASS' in result.stdout
    assert MaterialCheckpoints(str(root),clock=lambda:1001).get(operation,key) is None


def test_corrupt_cache_and_changed_material_never_turn_into_empty_success(tmp_path):
    settings=Settings(material_checkpoint_root=str(tmp_path/'private'));data=request()
    with TestClient(create_app(settings,model_gateway=RestartGateway(fail=True))) as client:
        assert client.post('/ai/v1/materials/extract-fields',json=data).status_code==502
    for path in (tmp_path/'private').glob('*.json'):path.write_text('{broken',encoding='utf-8')
    second=RestartGateway()
    with TestClient(create_app(settings,model_gateway=second)) as client:
        result=client.post('/ai/v1/materials/extract-fields',json=data)
    assert result.status_code==200
    assert any(s['locator']=='page:0' for c in second.calls for s in c)


@pytest.mark.parametrize('change',['sha256','materialId','businessType','source','locator'])
def test_reuse_identity_includes_all_source_and_operation_boundaries(tmp_path,change):
    settings=Settings(material_checkpoint_root=str(tmp_path/'private'));data=request()
    with TestClient(create_app(settings,model_gateway=RestartGateway(fail=True))) as client:
        assert client.post('/ai/v1/materials/extract-fields',json=data).status_code==502
    if change=='sha256':data['sha256']='b'*64
    elif change=='materialId':data['materialId']='another-material'
    elif change=='businessType':data['businessType']='PROCUREMENT'
    elif change=='source':data['segments'][0]['text']+='更新内容'
    else:data['segments'][0]['locator']='new-location'
    second=RestartGateway()
    with TestClient(create_app(settings,model_gateway=second)) as client:
        assert client.post('/ai/v1/materials/extract-fields',json=data).status_code==200
    assert any(s['locator']==data['segments'][0]['locator'] for c in second.calls for s in c)


def test_cache_is_bounded_and_never_follows_a_symlink(tmp_path):
    root=tmp_path/'private';store=MaterialCheckpoints(str(root));store.MAX_ENTRIES=2
    operation=digest('operation')
    for i in range(5):assert store.put(operation,digest(i),{'kind':'split'})
    assert len(list(root.glob('*.json')))==2
    victim=tmp_path/'unrelated';victim.write_text('PRESERVE',encoding='utf-8')
    key=digest('link');link=root/store._name(operation,key)
    try:link.symlink_to(victim)
    except OSError:pytest.skip('OS account cannot create symlinks')
    assert store.get(operation,key) is None
    store.discard(operation)
    assert victim.read_text(encoding='utf-8')=='PRESERVE'


def test_valid_json_cache_corruption_requires_recomputation(tmp_path):
    store=MaterialCheckpoints(str(tmp_path/'private'))
    operation,key=digest('operation'),digest('chunk')
    assert store.put(operation,key,{'kind':'complete','payload':{'rows':[{'rowKey':'original','facts':[]}]},'model':'test'})
    path=store.root/store._name(operation,key)
    record=json.loads(path.read_text(encoding='utf-8'));record['value']['payload']={'rows':[]}
    path.write_text(json.dumps(record),encoding='utf-8')
    assert store.get(operation,key) is None


def test_context_only_facts_do_not_create_an_extra_budget_row():
    text='材料说明'*448+'\n科目名称：办公设备；申报金额：100元。'+'后续说明'*300
    seen=[]
    def provider(r):
        segments=json.loads(json.loads(r.content)['messages'][1]['content'].split('segments=',1)[1]);seen.extend(segments)
        rows=[]
        for s in segments:
            if '办公设备' in joined(s):rows.append({'rowKey':'row','facts':[
                {'field':'subjectName','value':'办公设备','excerpt':'办公设备','locator':s['locator']}]})
        return reply({'rows':rows})
    data=request(count=1);data['segments']=[{'locator':'page:1','text':text}]
    with TestClient(create_app(Settings(),model_gateway=gateway(provider))) as client:
        response=client.post('/ai/v1/materials/extract-fields',json=data)
    assert response.status_code==200
    assert len(response.json()['data']['rows'])==1


def test_resume_keys_are_invalidated_by_gateway_configuration_changes():
    common=dict(model_base_url='https://synthetic.invalid/v1',model_name='model',model_api_key='one')
    first=OpenAiCompatibleModelGateway(Settings(**common)).material_cache_namespace()
    for field,value in [('model_api_key','two'),('model_name','other'),('model_base_url','https://other.invalid/v1'),('material_output_tokens',512)]:
        changed={**common,field:value}
        assert OpenAiCompatibleModelGateway(Settings(**changed)).material_cache_namespace()!=first


def test_input_too_small_to_split_fails_explicitly_and_releases_capacity():
    calls=[]
    def provider(r):calls.append(r);return httpx.Response(400,json={'error':{'message':'maximum context length'}})
    data=request(count=1);data['segments'][0]['text']='申报100元'
    with TestClient(create_app(Settings(),model_gateway=gateway(provider))) as client:
        for _ in range(3):
            response=client.post('/ai/v1/materials/extract-fields',json=data)
            assert response.status_code==502 and response.json()['code']=='MATERIAL_AI_CONTEXT_LIMIT'
    assert len(calls)==3


def test_output_cap_is_configurable_without_changing_endpoint_key_or_model():
    seen=[]
    def provider(r):seen.append(json.loads(r.content));return reply({'rows':[]})
    gateway(provider,material_output_tokens=512).extract_material_fields('BUDGET','[]','test')
    assert seen[0]['max_tokens']==512 and seen[0]['model']=='synthetic-model'


@pytest.mark.parametrize('option,value',[
    ('material_chunk_chars',0),('material_chunk_bytes',999999),('material_context_chars',-1),
    ('material_output_tokens',0),('material_checkpoint_ttl_seconds',86400)])
def test_unsafe_settings_reject_at_startup(option,value):
    with pytest.raises(ValueError):Settings(**{option:value})
