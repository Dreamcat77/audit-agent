import json
import httpx
import pytest
from audit_ai.config import Settings
from audit_ai.model.gateway import BudgetSemanticOperation, ModelGatewayFailure, OpenAiCompatibleModelGateway


def gateway(handler):
    return OpenAiCompatibleModelGateway(Settings(model_base_url="https://provider.invalid/v1",
        model_api_key="synthetic-test-key",model_name="synthetic-model"),transport=httpx.MockTransport(handler))


def test_budget_structured_analysis_disables_thinking_and_keeps_bounded_json_contract():
    def handler(request):
        body=json.loads(request.content)
        assert body['reasoning_effort']=='none'
        assert body['max_tokens']==2048
        assert body['response_format']=={'type':'json_object'}
        assert body['model']=='synthetic-model'
        return httpx.Response(200,json={'choices':[{'finish_reason':'stop','message':{'content':'{"items":[]}'}}]})
    assert gateway(handler).analyze_budget(BudgetSemanticOperation('synthetic-source',1,1,'synthetic')).payload=={'items':[]}


def test_budget_provider_failure_is_not_converted_to_success_or_retried_with_unbounded_settings():
    calls=[]
    def handler(request):
        calls.append(request)
        return httpx.Response(500,json={'error':'synthetic failure'})
    with pytest.raises(ModelGatewayFailure):
        gateway(handler).analyze_budget(BudgetSemanticOperation('synthetic-source',1,1,'synthetic'))
    assert len(calls)==1


def test_budget_field_prompt_preserves_partial_approval_and_project_scope_without_computing_money():
    def handler(request):
        body=json.loads(request.content)
        prompt=body['messages'][0]['content']
        assert 'declaredAmount' in prompt
        assert 'projectName' in prompt
        assert 'approvalAmount' in prompt
        assert '不得因缺少申报金额而丢弃批复资料' in prompt
        assert '不计算、不猜测' in prompt
        assert '项目预算总额' in prompt
        return httpx.Response(200,json={'choices':[{'finish_reason':'stop','message':{'content':'{"rows":[]}'}}]})
    gateway(handler).extract_material_fields('BUDGET','[]','synthetic')


def test_material_chunking_keeps_normal_rows_together_and_preserves_long_tail():
    from audit_ai.model.material_fields import text_parts
    text=('科目名称：办公设备；申报金额：31200元；备注：'+'材料'*100+'\n')*40+'TAIL_COMPLETE'
    parts=list(text_parts(text))
    assert ''.join(parts)==text
    assert all(len(p)<=1800 for p in parts)
    assert all(p.endswith('\n') for p in parts[:-1])
    single='无换行'*2000+'TAIL_COMPLETE'
    assert ''.join(text_parts(single))==single
