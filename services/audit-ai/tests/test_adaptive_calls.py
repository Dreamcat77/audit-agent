import pytest
from audit_ai.errors import AuditAiError
from audit_ai.model.adaptive_calls import adaptive_calls
from audit_ai.model.gateway import ModelCompletion,ModelGatewayFailure


def test_total_budget_exhaustion_prevents_the_next_call():
    ticks=iter([0,0,2]);calls=[]
    def invoke(*args):calls.append(args);return ModelCompletion(payload={},model='test',duration_ms=1)
    with pytest.raises(AuditAiError) as failure:
        list(adaptive_calls([[1],[2]],invoke,budget_seconds=1,clock=lambda:next(ticks)))
    assert len(calls)==1
    assert failure.value.code=='MODEL_TOTAL_BUDGET_EXHAUSTED'


def test_call_budget_is_finite_even_for_repeated_proven_limits():
    calls=[]
    def invoke(*args):
        calls.append(args)
        raise ModelGatewayFailure(reason='PROVIDER_413',retryable=False,duration_ms=1)
    with pytest.raises(AuditAiError):
        list(adaptive_calls([list(range(128))],invoke))
    assert len(calls)<=6


def test_access_failure_with_length_signal_is_not_retried():
    calls=[]
    def invoke(*args):
        calls.append(args)
        failure=ModelGatewayFailure(reason='PROVIDER_401',retryable=False,duration_ms=1)
        failure.finish_reason='LENGTH';raise failure
    with pytest.raises(ModelGatewayFailure):list(adaptive_calls([[1,2]],invoke))
    assert len(calls)==1


def test_remaining_budget_is_passed_to_the_provider_instead_of_restarting_a_full_timeout():
    ticks=iter([0,2,3,4,5]);remaining=[]
    def invoke(units,index,count,seconds):
        remaining.append(seconds);return ModelCompletion(payload={},model='test',duration_ms=1)
    list(adaptive_calls([[1],[2]],invoke,budget_seconds=10,clock=lambda:next(ticks),pass_remaining_budget=True))
    assert remaining==[8,6]
