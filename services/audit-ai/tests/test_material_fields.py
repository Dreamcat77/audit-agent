import json
import threading
from concurrent.futures import ThreadPoolExecutor
import httpx
import pytest
from fastapi.testclient import TestClient
from audit_ai.config import Settings
from audit_ai.main import create_app
from audit_ai.model.gateway import ModelCompletion, ModelGatewayFailure, OpenAiCompatibleModelGateway


@pytest.mark.parametrize('field', ['requirementDescription', 'productDescription'])
def test_description_sentence_linebreaks_restore_exact_contiguous_source(field):
    from audit_ai.model.material_fields import Fact, restore_description_sentence_breaks
    original='必须使用星桥品牌。\r\n  投标单位须为本地注册企业。\n保修三年。'
    candidate='必须使用星桥品牌。投标单位须为本地注册企业。保修三年。'
    fact=Fact(field=field,value=candidate,excerpt=candidate,locator='page:1')
    restored=restore_description_sentence_breaks(fact,original)
    assert restored.value==restored.excerpt==original
    assert fact.value==candidate


@pytest.mark.parametrize('field,source,candidate', [
    ('budgetAmount','金额12\n000元','12000元'),
    ('supplierName','青禾。\n公司','青禾。公司'),
    ('requirementDescription','金额12\n000元','12000元'),
    ('requirementDescription','必须使用星桥。\n另有条件。\n保修三年。','必须使用星桥。保修三年。'),
    ('requirementDescription','品牌星桥。\n保修三年。','品牌其他。保修三年。'),
    ('requirementDescription','品牌星桥。\n保修三年。\n品牌星桥。\n保修三年。','品牌星桥。保修三年。'),
    ('requirementDescription','。\n。\n。','。。'),
])
def test_sentence_break_repair_never_guesses_amount_identity_foreign_or_ambiguous_source(field,source,candidate):
    from audit_ai.model.material_fields import Fact, restore_description_sentence_breaks
    fact=Fact(field=field,value=candidate,excerpt=candidate,locator='page:1')
    assert restore_description_sentence_breaks(fact,source)==fact


def test_procurement_description_newlines_are_source_exact_and_foreign_claim_still_fails():
    class Gateway:
        invalid=False
        def extract_material_fields(self,kind,source,request_id,*,timeout_seconds=180):
            value='品牌星桥。'+('虚构条款。' if self.invalid else '')+'保修三年。'
            return ModelCompletion(payload={'rows':[{'rowKey':'one','facts':[dict(field='requirementDescription',
                value=value,excerpt=value,locator='p1')]}]},model='model',duration_ms=1)
    request=body();request['businessType']='PROCUREMENT_REVIEW'
    request['segments']=[dict(locator='p1',text='品牌星桥。\n保修三年。')]
    gateway=Gateway()
    with TestClient(create_app(Settings(),model_gateway=gateway)) as client:
        response=client.post('/ai/v1/materials/extract-fields',json=request)
        assert response.status_code==200
        assert response.json()['data']['rows'][0]['facts'][0]['excerpt']=='品牌星桥。\n保修三年。'
        gateway.invalid=True;request['materialId']='different-synthetic-operation'
        response=client.post('/ai/v1/materials/extract-fields',json=request)
        assert response.status_code==200
        assert response.json()['data']['rows']==[]

def test_irregular_procurement_review_accepts_grounded_vendor_prices_without_merging_rows():
    class ReviewGateway:
        def extract_material_fields(self,kind,source,request_id,*,timeout_seconds=60):
            assert kind=='PROCUREMENT_REVIEW'
            segments=json.loads(source)
            return ModelCompletion(payload={'rows':[
                {'rowKey':'same-model-local-key','facts':[
                    {'field':'supplierName','value':s['text'].split(' | ')[0],'excerpt':s['text'].split(' | ')[0],'locator':s['locator']},
                    {'field':'quoteAmount','value':s['text'].split(' | ')[1],'excerpt':s['text'].split(' | ')[1],'locator':s['locator']}]}
                for s in segments]},model='model',duration_ms=1)
    request=body();request['businessType']='PROCUREMENT_REVIEW'
    request['segments']=[{'locator':f'excel/sheet:1/row:{i+1}','text':f'供应商{i} | 壹万元整'} for i in range(12)]
    with TestClient(create_app(Settings(),model_gateway=ReviewGateway())) as client:
        response=client.post('/ai/v1/materials/extract-fields',json=request)
    assert response.status_code==200
    assert len(response.json()['data']['rows'])==12
    assert response.json()['data']['promptVersion']=='procurement-review-fields-v2'

def test_procurement_review_keeps_grounded_fields_and_omits_foreign_candidates():
    class ReviewGateway:
        invalid=False
        def extract_material_fields(self,kind,source,request_id,*,timeout_seconds=60):
            return ModelCompletion(payload={'rows':[{'rowKey':'one','facts':[
                {'field':'projectName','value':'设备更新','excerpt':'设备更新','locator':'foreign' if self.invalid else 'p1'}]}]},model='model',duration_ms=1)
    gateway=ReviewGateway();request=body();request['businessType']='PROCUREMENT_REVIEW'
    request['segments']=[{'locator':'p1','text':'项目名称：设备更新；申报金额31200，批复参考金额32760'}]
    with TestClient(create_app(Settings(),model_gateway=gateway)) as client:
        response=client.post('/ai/v1/materials/extract-fields',json=request)
        assert response.status_code==200
        assert [f['field'] for r in response.json()['data']['rows'] for f in r['facts']]==['projectName']
        gateway.invalid=True
        request['materialId']='different-operation'
        response=client.post('/ai/v1/materials/extract-fields',json=request)
        assert response.status_code==200
        assert response.json()['data']['rows']==[]

def test_procurement_review_extracts_tender_owner_and_keeps_budget_ceiling_and_quote_roles_distinct():
    class ReviewGateway:
        def extract_material_fields(self,kind,source,request_id,*,timeout_seconds=60):
            return ModelCompletion(payload={'rows':[{'rowKey':'tender','facts':[
                {'field':'purchaserName','value':'市政服务中心','excerpt':'采购人：市政服务中心','locator':'p1'},
                {'field':'agencyName','value':'远达招标代理有限公司','excerpt':'招标代理机构：远达招标代理有限公司','locator':'p1'},
                {'field':'budgetAmount','value':'100万元','excerpt':'项目预算金额：100万元','locator':'p1'},
                {'field':'ceilingAmount','value':'95万元','excerpt':'最高限价：95万元','locator':'p1'},
                {'field':'quoteAmount','value':'88万元','excerpt':'投标报价：88万元','locator':'p2'}]}]},model='model',duration_ms=1)
    request=body();request['businessType']='PROCUREMENT_REVIEW'
    request['segments']=[{'locator':'p1','text':'采购人：市政服务中心\n招标代理机构：远达招标代理有限公司\n项目预算金额：100万元\n最高限价：95万元'},
                        {'locator':'p2','text':'投标报价：88万元'}]
    with TestClient(create_app(Settings(),model_gateway=ReviewGateway())) as client:
        response=client.post('/ai/v1/materials/extract-fields',json=request)
    assert response.status_code==200
    facts={fact['field']:fact['value'] for row in response.json()['data']['rows'] for fact in row['facts']}
    assert facts=={'purchaserName':'市政服务中心','agencyName':'远达招标代理有限公司',
                   'budgetAmount':'100万元','ceilingAmount':'95万元','quoteAmount':'88万元'}

def test_provider_truncation_repartitions_only_failed_chunks_and_keeps_all_tail_evidence():
    class TruncatedGateway:
        def __init__(self): self.calls=[];self.accepted=[]
        def extract_material_fields(self,kind,source,request_id,*,timeout_seconds=60):
            segments=json.loads(source);self.calls.append(segments)
            if len(segments)>2:
                error=ModelGatewayFailure(reason='INVALID_PROVIDER_JSON',retryable=True,duration_ms=1)
                error.finish_reason='LENGTH';raise error
            self.accepted.extend(segments)
            return ModelCompletion(payload={'rows':[{'rowKey':'local','facts':[
                {'field':'requirementDescription','value':s['text'],'excerpt':s['text'],'locator':s['locator']} for s in segments]}]},model='model',duration_ms=1)
    gateway=TruncatedGateway();request=body();request['segments']=[{'locator':f'p{i}','text':f'设备{i}需要完成验收'} for i in range(12)]
    with TestClient(create_app(Settings(),model_gateway=gateway)) as client:
        response=client.post('/ai/v1/materials/extract-fields',json=request)
    assert response.status_code==200
    assert gateway.accepted==request['segments']
    assert len(response.json()['data']['rows'][0]['facts'])==12

def test_truncated_completion_with_valid_json_is_split_and_partial_facts_are_never_committed():
    class TruncatedGateway:
        def __init__(self): self.calls=0
        def extract_material_fields(self,kind,source,request_id,*,timeout_seconds=60):
            self.calls+=1;segments=json.loads(source)
            partial={'rows':[{'rowKey':'partial','facts':[{'field':'projectName','value':'未完成响应','excerpt':'未完成响应','locator':'foreign'}]}]}
            return ModelCompletion(payload=partial if len(segments)>1 else {'rows':[]},model='model',duration_ms=1,finish_reason='LENGTH' if len(segments)>1 else 'STOP')
    gateway=TruncatedGateway();request=body();request['segments']=[{'locator':f'p{i}','text':'设备验收'} for i in range(8)]
    with TestClient(create_app(Settings(),model_gateway=gateway)) as client:
        response=client.post('/ai/v1/materials/extract-fields',json=request)
    assert response.status_code==200 and 8<gateway.calls<=15
    assert response.json()['data']['rows']==[]

def test_truncated_single_segment_is_split_without_changing_locator_or_dropping_tail():
    class TextGateway:
        def __init__(self):self.accepted=[]
        def extract_material_fields(self,kind,source,request_id,*,timeout_seconds=60):
            segments=json.loads(source)
            if len(segments[0]['text'])>500:return ModelCompletion(payload={'rows':[]},model='model',duration_ms=1,finish_reason='LENGTH')
            self.accepted.extend(segments);return ModelCompletion(payload={'rows':[]},model='model',duration_ms=1)
    gateway=TextGateway();request=body();request['segments']=[{'locator':'original','text':'完整文字'*200+'尾部证据'}]
    with TestClient(create_app(Settings(),model_gateway=gateway)) as client:
        assert client.post('/ai/v1/materials/extract-fields',json=request).status_code==200
    assert ''.join(s['text'] for s in gateway.accepted)==request['segments'][0]['text']
    assert all(s['locator']=='original' for s in gateway.accepted)

def test_many_short_paragraphs_are_bounded_by_source_count_not_only_character_count():
    class DenseGateway:
        def __init__(self):self.chunks=[]
        def extract_material_fields(self,kind,source,request_id,*,timeout_seconds=60):
            self.chunks.append(json.loads(source));return ModelCompletion(payload={'rows':[]},model='model',duration_ms=1)
    gateway=DenseGateway();request=body();request['segments']=[{'locator':f'p{i}','text':f'设备{i}验收要求'} for i in range(120)]
    with TestClient(create_app(Settings(),model_gateway=gateway)) as client:
        assert client.post('/ai/v1/materials/extract-fields',json=request).status_code==200
    assert max(map(len,gateway.chunks))<=6
    assert [s for chunk in gateway.chunks for s in chunk]==request['segments']

def test_exact_empty_object_in_a_chunk_does_not_stop_later_grounded_fields():
    class EmptyThenFields(Gateway):
        def extract_material_fields(self,kind,source,request_id,*,timeout_seconds=60):
            segments=json.loads(source)
            if '设备更新' not in segments[0]['text']:
                self.calls.append((kind,source,request_id));return ModelCompletion(payload={},model='model',duration_ms=1)
            return super().extract_material_fields(kind,source,request_id,timeout_seconds=timeout_seconds)
    gateway=EmptyThenFields();request=body();request['segments']=[{'locator':f'empty{i}','text':'无关补充说明'} for i in range(6)]+request['segments']
    with TestClient(create_app(Settings(),model_gateway=gateway)) as client:
        response=client.post('/ai/v1/materials/extract-fields',json=request)
    assert response.status_code==200 and len(gateway.calls)==2
    assert response.json()['data']['rows'][0]['facts'][0]['value']=='设备更新'

def test_all_empty_objects_remain_no_fields_and_nonempty_invalid_objects_fail_closed():
    class ShapeGateway:
        def __init__(self,payload):self.payload=payload
        def extract_material_fields(self,*args,**kwargs):return ModelCompletion(payload=self.payload,model='model',duration_ms=1)
    with TestClient(create_app(Settings(),model_gateway=ShapeGateway({}))) as client:
        response=client.post('/ai/v1/materials/extract-fields',json=body())
        assert response.status_code==200 and response.json()['data']['rows']==[]
    for payload in ({'rows':None},{'unknown':'value'},{'rows':[{'rowKey':'one','facts':None}]}):
        with TestClient(create_app(Settings(),model_gateway=ShapeGateway(payload))) as client:
            response=client.post('/ai/v1/materials/extract-fields',json=body())
            assert response.status_code==502 and response.json()['code']=='MATERIAL_AI_INVALID_RESULT'

def test_auth_failure_and_invalid_json_without_length_are_not_repeated_as_smaller_calls():
    class FailedGateway:
        def __init__(self,reason):self.calls=0;self.reason=reason
        def extract_material_fields(self,*args,**kwargs):
            self.calls+=1;raise ModelGatewayFailure(reason=self.reason,retryable=True,duration_ms=1)
    for reason in ('INVALID_PROVIDER_JSON','PROVIDER_401','PROVIDER_429'):
        gateway=FailedGateway(reason)
        with TestClient(create_app(Settings(),model_gateway=gateway)) as client:
            assert client.post('/ai/v1/materials/extract-fields',json=body()).status_code==502
        assert gateway.calls==1

def test_truncation_has_a_finite_minimum_size_and_releases_capacity():
    class AlwaysTruncated:
        def __init__(self):self.calls=0
        def extract_material_fields(self,*args,**kwargs):
            self.calls+=1;return ModelCompletion(payload={'rows':[]},model='model',duration_ms=1,finish_reason='LENGTH')
    gateway=AlwaysTruncated()
    with TestClient(create_app(Settings(),model_gateway=gateway)) as client:
        for _ in range(3):
            response=client.post('/ai/v1/materials/extract-fields',json=body())
            assert response.status_code==502 and response.json()['code']=='MATERIAL_AI_TRUNCATED'
    assert gateway.calls==3

def test_real_gateway_preserves_length_signal_from_invalid_json_for_smaller_recovery():
    requests=[]
    def transport(request):
        payload=json.loads(request.content);segments=json.loads(payload['messages'][1]['content'].split('segments=',1)[1]);requests.append(segments)
        content='{"rows":[' if len(segments)>1 else '{"rows":[]}'
        return httpx.Response(200,json={'model':'test-model','choices':[{'finish_reason':'length' if len(segments)>1 else 'stop','message':{'content':content}}]})
    gateway=OpenAiCompatibleModelGateway(Settings(model_base_url='https://example.invalid',model_api_key='test-placeholder',model_name='test-model'),transport=httpx.MockTransport(transport))
    request=body();request['segments']=[{'locator':f'p{i}','text':'设备验收'} for i in range(4)]
    with TestClient(create_app(Settings(),model_gateway=gateway)) as client:
        response=client.post('/ai/v1/materials/extract-fields',json=request)
    assert response.status_code==200 and len(requests)==7
    assert [row[0] for row in requests if len(row)==1]==request['segments']

class Gateway:
    def __init__(self):
        self.calls = []
        self.bad = False
        self.entered = threading.Event()
        self.release = None
    def extract_material_fields(self, kind, source, request_id, *, timeout_seconds=60):
        self.calls.append((kind, source, request_id))
        self.entered.set()
        if self.release is not None:
            assert self.release.wait(5)
        segment = json.loads(source)[0]
        return ModelCompletion(payload={'rows': [{'rowKey': 'one', 'facts': [{
            'field': 'projectName', 'value': '编造' if self.bad else '设备更新',
            'locator': segment['locator'], 'excerpt': segment['text']}]}]}, model='model', duration_ms=1)

def body():
    return {'businessType': 'PROCUREMENT', 'materialId': '00000000-0000-0000-0000-000000000001',
        'sha256': 'a' * 64, 'segments': [{'locator': 'p1', 'text': '项目名称：设备更新'}]}

def test_chunk_local_rows_without_project_identity_are_not_multiple_projects():
    class SupplementalGateway:
        def __init__(self): self.calls = 0
        def extract_material_fields(self, kind, source, request_id, *, timeout_seconds=60):
            self.calls += 1
            segments = json.loads(source)
            facts = [{'field':'departmentName', 'value':'信息中心', 'locator':s['locator'], 'excerpt':'信息中心'}
                     for s in segments if '信息中心' in s['text']]
            return ModelCompletion(payload={'rows':[{'rowKey':f'chunk-{self.calls}', 'facts':facts}] if facts else []},
                model='model',duration_ms=1)
    gateway = SupplementalGateway()
    request = body(); request['segments'] = [{'locator':f'p{i}', 'text':'信息中心' + '补充说明'*450} for i in range(3)]
    with TestClient(create_app(Settings(), model_gateway=gateway)) as client:
        response=client.post('/ai/v1/materials/extract-fields', json=request)
    assert response.status_code == 200
    assert gateway.calls > 1
    assert len(response.json()['data']['rows']) == 1
    assert len(response.json()['data']['rows'][0]['facts']) == 3

def test_two_grounded_project_identities_are_not_collapsed_into_one_demand():
    class ProjectsGateway:
        def extract_material_fields(self, kind, source, request_id, *, timeout_seconds=60):
            segment=json.loads(source)[0]
            return ModelCompletion(payload={'rows':[
                {'rowKey':'one','facts':[{'field':'projectName','value':'设备采购','locator':segment['locator'],'excerpt':'设备采购'}]},
                {'rowKey':'two','facts':[{'field':'projectName','value':'工程改造','locator':segment['locator'],'excerpt':'工程改造'}]},
            ]},model='model',duration_ms=1)
    request=body();request['segments'][0]['text']='项目名称：设备采购；项目名称：工程改造'
    with TestClient(create_app(Settings(),model_gateway=ProjectsGateway())) as client:
        response=client.post('/ai/v1/materials/extract-fields',json=request)
    assert response.status_code==200
    assert len(response.json()['data']['rows'])==2

def test_material_extraction_is_grounded_and_does_not_invent_missing_fields():
    gateway = Gateway()
    with TestClient(create_app(Settings(), model_gateway=gateway)) as client:
        response = client.post('/ai/v1/materials/extract-fields', json=body())
    assert response.status_code == 200
    data = response.json()['data']
    assert data['model'] == 'model'
    assert data['promptVersion'] == 'business-material-fields-v5'
    assert len(data['rows'][0]['facts']) == 1

def test_hallucinated_fact_is_discarded_and_capacity_is_released():
    gateway = Gateway(); gateway.bad = True
    with TestClient(create_app(Settings(), model_gateway=gateway)) as client:
        for _ in range(3):
            response = client.post('/ai/v1/materials/extract-fields', json=body())
            assert response.status_code == 200
            assert response.json()['data']['rows'] == []
        gateway.bad = False
        assert client.post('/ai/v1/materials/extract-fields', json=body()).status_code == 200

def test_unsupported_business_entry_and_duplicate_locator_are_rejected():
    gateway = Gateway()
    with TestClient(create_app(Settings(), model_gateway=gateway)) as client:
        request = body(); request['businessType'] = 'CONTRACT'
        assert client.post('/ai/v1/materials/extract-fields', json=request).status_code == 422
        request = body(); request['segments'] *= 2
        assert client.post('/ai/v1/materials/extract-fields', json=request).status_code == 422
    assert not gateway.calls

def test_capacity_is_fail_fast_and_only_one_provider_call():
    gateway = Gateway(); gateway.release = threading.Event()
    with TestClient(create_app(Settings(), model_gateway=gateway)) as client, ThreadPoolExecutor(2) as pool:
        first = pool.submit(client.post, '/ai/v1/materials/extract-fields', json=body())
        assert gateway.entered.wait(5)
        gateway.entered.clear()
        second = pool.submit(client.post, '/ai/v1/materials/extract-fields', json=body())
        try:
            assert second.result(5).status_code == 503
            assert client.post('/ai/v1/materials/extract-fields', json=body()).status_code == 503
            assert len(gateway.calls) == 1
        finally:
            gateway.release.set()
        assert first.result(5).status_code == 200
        assert len(gateway.calls) == 1

def test_real_gateway_uses_dedicated_extraction_prompt_and_strict_json():
    captured = []
    def transport(request):
        captured.append(json.loads(request.content))
        return httpx.Response(200, json={'model': 'test-model', 'choices': [{'finish_reason': 'stop',
            'message': {'content': json.dumps({'rows': []})}}]})
    gateway = OpenAiCompatibleModelGateway(Settings(model_base_url='https://example.invalid',
        model_api_key='test-placeholder', model_name='test-model'), transport=httpx.MockTransport(transport))
    result = gateway.extract_material_fields('PROCUREMENT', '[]', 'test-material')
    assert result.payload == {'rows': []}
    assert captured[0]['max_tokens'] <= 4096
    assert '不猜测' in captured[0]['messages'][0]['content']

def test_long_material_is_partitioned_without_losing_tail_or_locator():
    class PartitionGateway:
        def __init__(self): self.seen = []
        def extract_material_fields(self, kind, source, request_id, *, timeout_seconds=60):
            assert 0 < timeout_seconds <= 900
            assert len(source) <= 3200
            segments = json.loads(source)
            self.seen.extend(segments)
            return ModelCompletion(payload={'rows': []}, model='model', duration_ms=1)
    gateway = PartitionGateway()
    request = body()
    request['segments'] = [{'locator': f'p{i}', 'text': ('正文'+str(i)) * 600 + '尾部'} for i in range(30)]
    with TestClient(create_app(Settings(), model_gateway=gateway)) as client:
        response = client.post('/ai/v1/materials/extract-fields', json=request)
    assert response.status_code == 200
    for segment in request['segments']:
        assert ''.join(s['text'] for s in gateway.seen if s['locator']==segment['locator']) == segment['text']

def test_oversized_material_is_rejected_before_any_provider_call():
    gateway=Gateway()
    request=body()
    request['segments']=[{'locator':f'p{i}','text':'字'*6000} for i in range(33)]
    with TestClient(create_app(Settings(),model_gateway=gateway)) as client:
        response=client.post('/ai/v1/materials/extract-fields',json=request)
    assert response.status_code==413
    assert not gateway.calls

def test_numbered_requirements_are_kept_verbatim_without_model_echoing_them():
    gateway=Gateway()
    request=body()
    requirements=[{'locator':f'r{i}','text':f'第{i}条技术要求：设备{i}应保存验收记录。'} for i in range(1,121)]
    request['segments']+=requirements
    with TestClient(create_app(Settings(),model_gateway=gateway)) as client:
        response=client.post('/ai/v1/materials/extract-fields',json=request)
    assert response.status_code==200
    assert len(gateway.calls)==1
    assert '设备120' not in gateway.calls[0][1]
    facts=[f for row in response.json()['data']['rows'] for f in row['facts']]
    assert any(f['locator']=='r120' and '设备120' in f['value'] for f in facts)
    assert len(response.json()['data']['rows'])==1

def test_requirement_paragraph_with_identity_labels_still_goes_to_ai():
    gateway=Gateway()
    request=body()
    request['segments'].append({'locator':'r1','text':'第1条技术要求：设备更新；项目编号：第二项目'})
    with TestClient(create_app(Settings(),model_gateway=gateway)) as client:
        assert client.post('/ai/v1/materials/extract-fields',json=request).status_code==200
    assert '第二项目' in gateway.calls[0][1]


def test_numbered_requirement_lines_in_one_text_span_keep_every_line_and_original_locator():
    class SupplementGateway(Gateway):
        def extract_material_fields(self,kind,source,request_id,*,timeout_seconds=60):
            if not any(segment['locator']=='p1' for segment in json.loads(source)):
                self.calls.append((kind,source,request_id))
                return ModelCompletion(payload={'rows':[]},model='model',duration_ms=1)
            return super().extract_material_fields(kind,source,request_id,timeout_seconds=timeout_seconds)
    gateway=SupplementGateway()
    request=body()
    lines=[f'第{i}条技术要求：设备{i}应保存验收记录。\r\n' for i in range(1,121)]
    source='开头补充说明\r\n'+''.join(lines[:60])+'中间补充说明\r\n'+''.join(lines[60:])+'最后尾部说明'
    request['segments'].append({'locator':'text/body','text':source})
    with TestClient(create_app(Settings(),model_gateway=gateway)) as client:
        response=client.post('/ai/v1/materials/extract-fields',json=request)
    assert response.status_code==200
    facts=[fact for row in response.json()['data']['rows'] for fact in row['facts']]
    requirements=[fact for fact in facts if fact['field']=='requirementDescription']
    assert len(requirements)==120
    assert all(fact['locator']=='text/body' and fact['excerpt'] in source and fact['value'] in fact['excerpt'] for fact in requirements)
    sent=[segment for call in gateway.calls for segment in json.loads(call[1]) if segment['locator']=='text/body']
    assert [segment['text'] for segment in sent]==['开头补充说明\r\n','中间补充说明\r\n','最后尾部说明']
    assert '设备120' not in ''.join(call[1] for call in gateway.calls)


def test_multiline_requirement_source_keeps_identity_bearing_lines_in_model_input():
    gateway=Gateway()
    request=body()
    source='第1条技术要求：普通设备应验收。\n第2条技术要求：设备更新；项目编号：第二项目\n原文尾部'
    request['segments'].append({'locator':'text/body','text':source})
    with TestClient(create_app(Settings(),model_gateway=gateway)) as client:
        response=client.post('/ai/v1/materials/extract-fields',json=request)
    assert response.status_code==200
    sent=''.join(call[1] for call in gateway.calls)
    assert '第二项目' in sent and '原文尾部' in sent
    facts=[fact for row in response.json()['data']['rows'] for fact in row['facts']]
    requirements=[fact for fact in facts if fact['field']=='requirementDescription']
    assert len(requirements)==1 and requirements[0]['excerpt'] in source


def test_budget_rows_from_different_chunks_are_never_merged_by_model_local_row_key():
    class BudgetGateway:
        def extract_material_fields(self,kind,source,request_id,**kwargs):
            assert kind == 'BUDGET'
            return ModelCompletion(payload={'rows':[
                {'rowKey':str(i),'facts':[
                    {'field':'subjectName','value':s['text'].split(' ')[0],'excerpt':s['text'],'locator':s['locator']},
                    {'field':'declaredAmount','value':'31200','excerpt':'申报金额31200','locator':s['locator']}
                ]} for i,s in enumerate(json.loads(source))]},model='model',duration_ms=1)
    request=body();request['businessType']='BUDGET'
    request['segments']=[{'locator':f'budget/row:{i}','text':f'设备{i} 申报金额31200 批复参考金额32760'} for i in range(12)]
    with TestClient(create_app(Settings(),model_gateway=BudgetGateway())) as client:
        response=client.post('/ai/v1/materials/extract-fields',json=request)
    assert response.status_code == 200
    rows=response.json()['data']['rows']
    assert len(rows)==12 and len({row['rowKey'] for row in rows})==12
    assert {f['value'] for row in rows for f in row['facts'] if f['field']=='subjectName'}=={f'设备{i}' for i in range(12)}


def test_budget_foreign_fields_and_ungrounded_amounts_are_excluded_without_empty_clean_success():
    class BadBudgetGateway:
        def __init__(self,field,value):self.field=field;self.value=value
        def extract_material_fields(self,*args,**kwargs):
            return ModelCompletion(payload={'rows':[{'rowKey':'one','facts':[
                {'field':self.field,'value':self.value,'excerpt':'申报金额31200','locator':'budget/row:1'}]}]},model='model',duration_ms=1)
    request=body();request['businessType']='BUDGET';request['segments']=[{'locator':'budget/row:1','text':'申报金额31200'}]
    for field,value in [('budgetAmount','31200'),('declaredAmount','99999')]:
        with TestClient(create_app(Settings(),model_gateway=BadBudgetGateway(field,value))) as client:
            response=client.post('/ai/v1/materials/extract-fields',json=request)
            assert response.status_code==200
            assert response.json()['data']['rows']==[]
            assert response.json()['data']['issues'][0]['reasonCode']=='EVIDENCE_UNVERIFIED'


def test_budget_partial_approval_and_heading_survive_all_chunks_without_declaration():
    class ApprovalGateway:
        def extract_material_fields(self,kind,source,request_id,**kwargs):
            assert kind=='BUDGET'
            return ModelCompletion(payload={'rows':[{'rowKey':str(i),'facts':[
                {'field':'approvalAmount','value':'32760元','excerpt':'批复32760元','locator':s['locator']},
                {'field':'projectName','value':'设备项目','excerpt':'设备项目','locator':s['locator']}
            ]} for i,s in enumerate(json.loads(source))]},model='model',duration_ms=1)
    request=body();request['businessType']='BUDGET'
    request['segments']=[{'locator':f'approval/{i}','text':'设备项目批复32760元'} for i in range(12)]
    with TestClient(create_app(Settings(),model_gateway=ApprovalGateway())) as client:
        response=client.post('/ai/v1/materials/extract-fields',json=request)
    assert response.status_code==200
    rows=response.json()['data']['rows']
    assert len(rows)==12
    assert all({f['field'] for f in row['facts']}=={'approvalAmount','projectName'} for row in rows)
