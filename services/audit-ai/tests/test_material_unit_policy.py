import json
from fastapi.testclient import TestClient
from audit_ai.config import Settings
from audit_ai.main import create_app
from audit_ai.model.gateway import ModelCompletion


class Gateway:
    def __init__(self):self.calls=0
    def material_cache_namespace(self):return 'synthetic-policy'
    def extract_material_fields(self,kind,source,request_id,**kwargs):
        self.calls+=1
        return ModelCompletion(payload={'rows':[]},model='test',duration_ms=1)


def test_opaque_policy_drift_is_rejected_before_provider_and_unit_usage_is_returned():
    gateway=Gateway()
    with TestClient(create_app(Settings(),model_gateway=gateway)) as client:
        policy=client.get('/ai/v1/materials/policy').json()
        assert set(policy)=={'policyId'} and len(policy['policyId'])==64
        request=dict(businessType='BUDGET',materialId='synthetic',sha256='a'*64,
                     segments=[dict(locator='p1',text='预算材料说明')],policyId='b'*64,callLimit=1,timeLimitMillis=10000)
        rejected=client.post('/ai/v1/materials/extract-fields',json=request)
        assert rejected.status_code==409 and gateway.calls==0
        request['policyId']=policy['policyId']
        response=client.post('/ai/v1/materials/extract-fields',json=request)
        assert response.status_code==200,response.text
        assert response.json()['data']['callCount']==1 and gateway.calls==1


def test_one_remaining_call_cannot_become_two_calls_after_context_rejection():
    from audit_ai.model.gateway import ModelGatewayFailure
    class Limited(Gateway):
        def extract_material_fields(self,*args,**kwargs):
            self.calls+=1
            failure=ModelGatewayFailure(reason='PROVIDER_413',retryable=False,duration_ms=1)
            failure.provider_error_kind='CONTEXT_LIMIT'
            raise failure
    gateway=Limited()
    with TestClient(create_app(Settings(),model_gateway=gateway)) as client:
        response=client.post('/ai/v1/materials/extract-fields',json=dict(businessType='BUDGET',materialId='synthetic',
            sha256='a'*64,segments=[dict(locator='p1',text='预算说明'*100)],callLimit=1))
    assert response.status_code==413 and response.json()['code']=='MATERIAL_AI_CALL_LIMIT'
    assert gateway.calls==1
