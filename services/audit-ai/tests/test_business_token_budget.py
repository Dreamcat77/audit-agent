import importlib.util
import pytest

def module():
    name='audit_ai.model.semantic_token_budget'
    assert importlib.util.find_spec(name) is not None,'live business context admission missing'
    return __import__(name,fromlist=['admit'])

def test_full_primary_or_repair_cannot_dispatch_beyond_32k_with_same_output_reserve():
    from audit_ai.model.gateway import ModelGatewayFailure
    m=module()
    class Counter:
        def count(self,text):return {'system':2000,'primary':20000,'repair':22577}[text]
    assert m.admit('system','primary',counter=Counter())['estimatedTotalWithReservations']==30192
    with pytest.raises(ModelGatewayFailure) as failure:
        m.admit('system','repair',counter=Counter())
    assert failure.value.reason=='INPUT_CONTEXT_BUDGET_EXCEEDED'

def test_missing_tokenizer_identity_fails_before_network(monkeypatch):
    from audit_ai.model.gateway import ModelGatewayFailure
    m=module();monkeypatch.delenv('AUDIT_CONTRACT_BUSINESS_TOKENIZER_METADATA',raising=False)
    monkeypatch.delenv('AUDIT_CONTRACT_BUSINESS_TOKENIZER_SHA256',raising=False)
    with pytest.raises(ModelGatewayFailure):m.admit('system','primary')
