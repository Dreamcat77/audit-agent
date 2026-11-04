import json
from fastapi.testclient import TestClient
from audit_ai.config import Settings
from audit_ai.main import create_app
from audit_ai.model.gateway import ModelCompletion, ModelGatewayFailure


def test_recovery_reuses_validated_partial_chunk_without_losing_warning_or_resending(tmp_path):
    class Gateway:
        def __init__(self):self.calls=[];self.fail=True
        def extract_material_fields(self,kind,source,request_id,**kwargs):
            rows=json.loads(source);self.calls.append(rows[0]['locator'])
            if rows[0]['locator']=='p6' and self.fail:
                raise ModelGatewayFailure(reason='PROVIDER_401',retryable=False,duration_ms=1)
            return ModelCompletion(payload={'rows':[{'rowKey':'a','facts':[
                {'field':'subjectName','value':'设备','locator':rows[0]['locator'],'excerpt':'设备'},
                {'field':'approvalAmount','value':'999','locator':rows[0]['locator'],'excerpt':'999'}]}]},model='test',duration_ms=1)
    gateway=Gateway()
    request=dict(businessType='BUDGET',materialId='synthetic',sha256='a'*64,
                 segments=[dict(locator=f'p{i}',text='设备申请50元') for i in range(7)])
    with TestClient(create_app(Settings(material_checkpoint_root=str(tmp_path)),model_gateway=gateway)) as client:
        assert client.post('/ai/v1/materials/extract-fields',json=request).status_code==502
        gateway.fail=False
        response=client.post('/ai/v1/materials/extract-fields',json=request)
    assert response.status_code==200
    assert gateway.calls==['p0','p6','p6']
    assert len(response.json()['data']['issues'])==2
    assert all(f['field']!='approvalAmount' for r in response.json()['data']['rows'] for f in r['facts'])
