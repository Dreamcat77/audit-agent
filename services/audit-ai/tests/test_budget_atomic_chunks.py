import json
import pytest
from audit_ai.config import Settings
from audit_ai.errors import AuditAiError
from audit_ai.model.gateway import ModelCompletion, ModelGatewayFailure
from audit_ai.model.router import ModelApplicationService, BudgetAnalysisRequest

class AtomicGateway:
    def __init__(self, bad=None): self.calls=[]; self.bad=bad
    def analyze_budget(self, operation):
        rows=[json.loads(line) for line in operation.source_text.splitlines()]
        self.calls.append(rows)
        items=[{'itemId':row['itemId'],'suggestedCategory':'办公设备','hint':'请人工核对'} for row in rows]
        if self.bad=='omit': items=[]
        if self.bad=='duplicate': items=items+items
        if self.bad=='foreign': items[0]['itemId']='outside-this-chunk'
        return ModelCompletion(payload={'items':items},model='test',duration_ms=1)

def request():
    return BudgetAnalysisRequest(sourceId='synthetic',items=[
        {'itemId':f'item-{i}','description':'办公设备维护说明。'*45} for i in range(12)])

def test_rows_remain_intact_and_each_identity_is_processed_exactly_once():
    gateway=AtomicGateway()
    service=ModelApplicationService(Settings(model_chunk_chars=1000,model_max_chunks=20),gateway)
    result=service.analyze_budget(request(),'test')
    assert len(gateway.calls)>1
    rows=[row for chunk in gateway.calls for row in chunk]
    assert [r['itemId'] for r in rows]==[f'item-{i}' for i in range(12)]
    assert len(result.data.items)==12

@pytest.mark.parametrize('bad',['omit','duplicate','foreign'])
def test_missing_duplicate_and_cross_chunk_identities_never_become_success(bad):
    gateway=AtomicGateway(bad)
    service=ModelApplicationService(Settings(model_chunk_chars=1000,model_max_chunks=20),gateway)
    with pytest.raises(AuditAiError): service.analyze_budget(request(),'test')

def test_one_oversized_row_rejects_whole_operation_before_any_provider_call():
    gateway=AtomicGateway()
    service=ModelApplicationService(Settings(model_chunk_chars=1000,model_max_chunks=20),gateway)
    data=request()
    data.items[-1].description='字'*2000
    with pytest.raises(AuditAiError): service.analyze_budget(data,'test')
    assert gateway.calls==[]

def test_short_rows_still_have_an_output_cardinality_bound():
    gateway=AtomicGateway()
    service=ModelApplicationService(Settings(),gateway)
    data=BudgetAnalysisRequest(sourceId='synthetic',items=[{'itemId':f'item-{i}','description':'设备'} for i in range(25)])
    result=service.analyze_budget(data,'test')
    assert max(len(c) for c in gateway.calls)<=8
    assert len(result.data.items)==25


@pytest.mark.parametrize('limit',['input','output'])
def test_semantic_recovery_keeps_each_intact_item_and_rejects_truncated_partial_results(limit):
    class Limited(AtomicGateway):
        def analyze_budget(self,operation):
            rows=[json.loads(line) for line in operation.source_text.splitlines()]
            if len(rows)>2:
                self.calls.append(rows)
                if limit=='input':
                    failure=ModelGatewayFailure(reason='PROVIDER_400',retryable=False,duration_ms=1)
                    failure.provider_error_kind='CONTEXT_LIMIT';raise failure
                return ModelCompletion(payload={'items':[]},model='test',duration_ms=1,finish_reason='LENGTH')
            return super().analyze_budget(operation)
    gateway=Limited();service=ModelApplicationService(Settings(),gateway)
    result=service.analyze_budget(request(),'test')
    assert [item.item_id for item in result.data.items]==[f'item-{i}' for i in range(12)]
    assert len(result.data.items)==12 and len(gateway.calls)>6


def test_one_atomic_semantic_row_cannot_be_silently_truncated_to_fit():
    class Limited(AtomicGateway):
        def analyze_budget(self,operation):
            failure=ModelGatewayFailure(reason='PROVIDER_400',retryable=False,duration_ms=1)
            failure.provider_error_kind='CONTEXT_LIMIT';raise failure
    service=ModelApplicationService(Settings(),Limited())
    with pytest.raises(AuditAiError) as failure:service.analyze_budget(request(),'test')
    assert failure.value.code=='MODEL_INPUT_TOO_LARGE'
