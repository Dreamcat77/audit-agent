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


def test_read_timeout_discards_unpublished_checkpoints_releases_slot_and_does_not_retry(tmp_path):
    class Gateway:
        calls=0
        def extract_material_fields(self,kind,source,request_id,*,timeout_seconds):
            self.calls+=1
            if self.calls==2:raise ModelGatewayFailure(reason='READ_TIMEOUT',retryable=True,duration_ms=180000)
            return ModelCompletion(payload={'rows':[]},model='synthetic',duration_ms=1)
    gateway=Gateway()
    request={'businessType':'BUDGET','materialId':'synthetic','sha256':'a'*64,
        'segments':[{'locator':'p1','text':'甲'*1800},{'locator':'p2','text':'乙'*1800}]}
    root=tmp_path/'checkpoints';root.mkdir(mode=0o700)
    with TestClient(create_app(Settings(material_checkpoint_root=str(root)),model_gateway=gateway)) as client:
        response=client.post('/ai/v1/materials/extract-fields',json=request)
        assert response.status_code==504 and response.json()['code']=='MATERIAL_AI_CALL_TIMEOUT'
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
    assert response.status_code==504 and response.json()['code']=='MATERIAL_AI_TIME_LIMIT'
    assert gateway.calls==1
