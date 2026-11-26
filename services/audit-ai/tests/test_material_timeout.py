import json
import httpx
import pytest
from fastapi.testclient import TestClient
from audit_ai.config import Settings
from audit_ai.main import create_app
from audit_ai.model.gateway import ModelCompletion, ModelGatewayFailure, OpenAiCompatibleModelGateway


def test_material_call_uses_180_seconds_independent_from_other_modules_and_caps_remaining():
    seen=[]
    def transport(request):
        seen.append(request.extensions['timeout']['read'])
        return httpx.Response(200,json={'choices':[{'finish_reason':'stop','message':{'content':'{"rows":[]}'}}]})
    gateway=OpenAiCompatibleModelGateway(Settings(model_base_url='https://example.invalid',model_api_key='synthetic',model_name='synthetic'),transport=httpx.MockTransport(transport))
    gateway.extract_material_fields('BUDGET','[]','synthetic')
    gateway.extract_material_fields('PROCUREMENT','[]','synthetic',timeout_seconds=7)
    assert seen==[180.0,7]


def test_material_timeout_is_configurable_but_finite(monkeypatch):
    monkeypatch.setenv('AUDIT_MATERIAL_MODEL_READ_TIMEOUT_SECONDS','120')
    assert Settings.from_env().material_model_read_timeout_seconds==120
    for invalid in (0,181,float('inf'),float('nan')):
        with pytest.raises(ValueError):Settings(material_model_read_timeout_seconds=invalid)


def test_read_timeout_publishes_completed_rows_releases_slot_and_does_not_retry(tmp_path):
    class Gateway:
        calls=0
        def extract_material_fields(self,kind,source,request_id,*,timeout_seconds):
            self.calls+=1
            if self.calls==2:raise ModelGatewayFailure(reason='READ_TIMEOUT',retryable=True,duration_ms=180000)
            return ModelCompletion(payload={'rows':[{'rowKey':'budget-1','facts':[{
                'field':'projectName','value':'测试项目','locator':'p1','excerpt':'项目名称：测试项目'}]}]},model='synthetic',duration_ms=1)
    gateway=Gateway()
    request={'businessType':'PROCUREMENT','materialId':'synthetic','sha256':'a'*64,
            'segments':[{'locator':'p1','text':'项目名称：测试项目\n'+'甲'*1780},{'locator':'p2','text':'乙'*1800}]}
    root=tmp_path/'checkpoints';root.mkdir(mode=0o700, parents=True)
    with TestClient(create_app(Settings(material_checkpoint_root=str(root)),model_gateway=gateway)) as client:
        response=client.post('/ai/v1/materials/extract-fields',json=request)
        assert response.status_code==200 and response.json()['code']==200
        assert response.json()['data']['rows'][0]['facts'][0]['value']=='测试项目'
        assert response.json()['data']['partial'] is True
        assert gateway.calls==2
        assert list(root.iterdir())==[]
        request['materialId']='synthetic-next';request['segments']=request['segments'][:1]
        assert client.post('/ai/v1/materials/extract-fields',json=request).status_code==200


def test_whole_request_deadline_rejects_late_results_without_publishing(monkeypatch):
    import audit_ai.model.material_fields as module
    now=[0.0]
    monkeypatch.setattr(module.time,'monotonic',lambda:now[0])
    class Gateway:
        calls=0
        def extract_material_fields(self,kind,source,request_id,*,timeout_seconds):
            self.calls+=1;now[0]+=2
            return ModelCompletion(payload={'rows':[]},model='synthetic',duration_ms=2000)
    gateway=Gateway()
    with TestClient(create_app(Settings(),model_gateway=gateway)) as client:
        response=client.post('/ai/v1/materials/extract-fields',json={'businessType':'PROCUREMENT','materialId':'synthetic','sha256':'a'*64,
            'timeLimitMillis':1000,'segments':[{'locator':'p1','text':'合成文字'}]})
    assert response.status_code==200 and response.json()['code']==200
    assert response.json()['data']['partial'] is True
    assert gateway.calls==1


@pytest.mark.parametrize('timeout_reason', ['READ_TIMEOUT', 'CONNECT_TIMEOUT'])
def test_timeout_keeps_explicit_budget_and_procurement_labels_without_model_rows(tmp_path, timeout_reason):
    class Gateway:
        def extract_material_fields(self, *args, **kwargs):
            raise ModelGatewayFailure(reason=timeout_reason, retryable=True, duration_ms=180000)

    for kind, text in (
        ('BUDGET', '项目名称：办公楼修缮项目\n预算金额：2200000元\n申报金额：2200000元'),
        ('PROCUREMENT_REVIEW', '项目名称：办公楼修缮项目\n采购人：上海市公安局\n预算金额：2200000元'),
    ):
        root = tmp_path / kind
        root.mkdir(mode=0o700, parents=True)
        with TestClient(create_app(Settings(material_checkpoint_root=str(root)), model_gateway=Gateway())) as client:
            response = client.post('/ai/v1/materials/extract-fields', json={
                'businessType': kind, 'materialId': kind, 'sha256': 'a' * 64,
                'segments': [{'locator': 'p1', 'text': text}],
            })
        assert response.status_code == 200
        facts = [fact for row in response.json()['data']['rows'] for fact in row['facts']]
        assert {fact['field'] for fact in facts} >= {'projectName'}
        assert response.json()['data']['partial'] is True
