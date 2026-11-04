import json
import pytest
from fastapi.testclient import TestClient
from audit_ai.config import Settings
from audit_ai.main import create_app
from audit_ai.model.gateway import ModelCompletion


def test_budget_excludes_unverified_field_retains_other_rows_and_reports_incomplete(tmp_path):
    class Gateway:
        def extract_material_fields(self, kind, source, request_id, **kwargs):
            return ModelCompletion(payload={'rows':[
                {'rowKey':'a','facts':[
                    {'field':'subjectName','value':'设备','locator':'p1','excerpt':'设备'},
                    {'field':'declaredAmount','value':'120元','locator':'p1','excerpt':'申请120元'},
                    {'field':'approvalAmount','value':'100元','locator':'p1','excerpt':'批复100元'}]},
                {'rowKey':'b','facts':[
                    {'field':'subjectName','value':'培训','locator':'p1','excerpt':'培训'},
                    {'field':'declaredAmount','value':'999元','locator':'p1','excerpt':'999元'}]}]},model='test',duration_ms=1)
    data={'businessType':'BUDGET','materialId':'synthetic','sha256':'a'*64,
          'segments':[{'locator':'p1','text':'设备申请120元，批复100元。培训申请50元。'}]}
    with TestClient(create_app(Settings(material_checkpoint_root=str(tmp_path)),model_gateway=Gateway())) as client:
        response=client.post('/ai/v1/materials/extract-fields',json=data)
    assert response.status_code==200
    result=response.json()['data']
    assert len(result['rows'])==2
    assert all(f['value']!='999元' for r in result['rows'] for f in r['facts'])
    assert result['issues']==[{'field':'declaredAmount','locator':'p1','reasonCode':'EVIDENCE_UNVERIFIED'}]


def test_unverified_identity_cannot_leave_an_amount_to_be_attached_to_another_project(tmp_path):
    class Gateway:
        def extract_material_fields(self, *args, **kwargs):
            return ModelCompletion(payload={'rows':[{'rowKey':'a','facts':[
                {'field':'projectName','value':'编造项目','locator':'p1','excerpt':'编造项目'},
                {'field':'declaredAmount','value':'50元','locator':'p1','excerpt':'申请50元'}]}]},model='test',duration_ms=1)
    data={'businessType':'BUDGET','materialId':'synthetic','sha256':'a'*64,
          'segments':[{'locator':'p1','text':'真实项目申请50元'}]}
    with TestClient(create_app(Settings(material_checkpoint_root=str(tmp_path)),model_gateway=Gateway())) as client:
        result=client.post('/ai/v1/materials/extract-fields',json=data).json()['data']
    assert result['rows']==[] and result['issues'][0]['field']=='projectName'


@pytest.mark.parametrize('identity', ['projectName', 'projectCode', 'subjectName', 'subjectCode'])
def test_invalid_row_identity_does_not_orphan_money_and_checkpoint_keeps_other_rows(tmp_path, identity):
    class Gateway:
        calls = 0
        def extract_material_fields(self, *args, **kwargs):
            self.calls += 1
            return ModelCompletion(payload={'rows':[
                {'rowKey':'bad','facts':[
                    {'field':identity,'value':'虚构身份','locator':'p1','excerpt':'虚构身份'},
                    {'field':'declaredAmount','value':'50元','locator':'p1','excerpt':'申请50元'}]},
                {'rowKey':'good','facts':[
                    {'field':'subjectName','value':'设备','locator':'p1','excerpt':'设备'},
                    {'field':'declaredAmount','value':'120元','locator':'p1','excerpt':'申请120元'}]}]},
                model='test',duration_ms=1)
    gateway=Gateway()
    data={'businessType':'BUDGET','materialId':'synthetic','sha256':'a'*64,
          'segments':[{'locator':'p1','text':'真实项目申请50元。设备申请120元。'}]}
    with TestClient(create_app(Settings(material_checkpoint_root=str(tmp_path)),model_gateway=gateway)) as client:
        first=client.post('/ai/v1/materials/extract-fields',json=data).json()['data']
        again=client.post('/ai/v1/materials/extract-fields',json=data).json()['data']
    for result in (first,again):
        assert len(result['rows'])==1
        assert {fact['value'] for row in result['rows'] for fact in row['facts']}=={'设备','120元'}
    assert gateway.calls==1
