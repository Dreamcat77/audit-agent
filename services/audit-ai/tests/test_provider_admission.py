"""One model gateway shared by all routes: bounded real sends, no hidden retry."""
from concurrent.futures import ThreadPoolExecutor
from threading import Event, Lock
import time
import httpx
import pytest
from audit_ai.config import Settings
from audit_ai.model.gateway import OpenAiCompatibleModelGateway, ModelGatewayFailure


def gateway(handler, capacity=2):
    return OpenAiCompatibleModelGateway(Settings(model_base_url='https://synthetic.invalid/v1',
        model_api_key='synthetic',model_name='offline',model_provider_concurrency=capacity),
        transport=httpx.MockTransport(handler))


def invoke(g, index=0, timeout=2):
    return g._complete(operation='MATERIAL_FIELD_EXTRACTION',purpose='EXTRACT',
        system_prompt='synthetic',user_content='synthetic',request_id=f'offline-{index}',
        attempt=1,max_attempts=1,provider_timeout_seconds=timeout)


def response():
    return httpx.Response(200,json={'choices':[{'finish_reason':'stop','message':{'content':'{}'}}]})


def test_three_routes_share_two_actual_provider_slots_and_wait_counts_in_duration():
    entered=Event(); release=Event(); lock=Lock(); active=0; peak=0; calls=[]
    def provider(request):
        nonlocal active,peak
        with lock:
            active+=1;peak=max(active,peak);calls.append(request)
            if len(calls)==2: entered.set()
        assert release.wait(2)
        with lock: active-=1
        return response()
    g=gateway(provider)
    with ThreadPoolExecutor(max_workers=3) as pool:
        futures=[pool.submit(invoke,g,i) for i in range(3)]
        assert entered.wait(1)
        time.sleep(.08)
        assert len(calls)==2
        release.set()
        results=[f.result() for f in futures]
    assert peak==2 and len(calls)==3
    assert max(r.duration_ms for r in results)>=70


def test_expired_wait_never_dispatches_and_slot_is_released_after_transport_error():
    entered=Event(); release=Event(); calls=[]
    def provider(request):
        calls.append(request); entered.set()
        assert release.wait(2)
        if len(calls)==1: raise httpx.ConnectError('synthetic')
        return response()
    g=gateway(provider,1)
    with ThreadPoolExecutor(max_workers=2) as pool:
        first=pool.submit(invoke,g)
        assert entered.wait(1)
        with pytest.raises(ModelGatewayFailure) as failure: invoke(g,1,.03)
        assert failure.value.reason=='PROVIDER_DISPATCH_NOT_STARTED'
        assert not failure.value.retryable and len(calls)==1
        release.set()
        with pytest.raises(ModelGatewayFailure): first.result()
    assert invoke(g,2).payload=={} and len(calls)==2


def test_queue_wait_reduces_the_existing_provider_timeout():
    entered=Event();release=Event();timeouts=[]
    def provider(request):
        timeouts.append(request.extensions['timeout']['read'])
        entered.set();assert release.wait(2)
        return response()
    g=gateway(provider,1)
    with ThreadPoolExecutor(max_workers=2) as pool:
        one=pool.submit(invoke,g,0,1)
        assert entered.wait(1)
        two=pool.submit(invoke,g,1,1)
        time.sleep(.08);release.set();one.result();two.result()
    assert timeouts[1]<timeouts[0]-.05


@pytest.mark.parametrize('value',[0,5,True,1.5])
def test_capacity_must_be_a_bounded_integer(value):
    with pytest.raises(ValueError): Settings(model_provider_concurrency=value)


def test_cancelled_waiter_is_removed_without_releasing_someone_elses_slot(monkeypatch):
    from audit_ai.model.provider_admission import ProviderAdmission
    admission=ProviderAdmission(1)
    with admission.slot(1):
        original=admission._condition.wait
        def interrupted(_): raise KeyboardInterrupt()
        monkeypatch.setattr(admission._condition,'wait',interrupted)
        with pytest.raises(KeyboardInterrupt):
            with admission.slot(1): pytest.fail('cancelled waiter acquired')
        assert admission._active==1 and not admission._waiting
        monkeypatch.setattr(admission._condition,'wait',original)
    with admission.slot(1): assert admission._active==1
    assert admission._active==0
