from types import SimpleNamespace
import pytest
from audit_ai.model.project_names import clean_name, review_names


def test_suffix_cleaning_preserves_real_name():
    assert clean_name('建国中路30号部分区域修缮项目的。') == '建国中路30号部分区域修缮项目'
    assert clean_name('目的地服务项目') == '目的地服务项目'


def test_review_excerpt_is_centered_on_a_name_late_in_the_source():
    from audit_ai.model.project_names import name_excerpt
    candidate={'value':'通南路修缮项目的','excerpt':'背景。'*400+'通南路修缮项目的预算为测试数据。'}
    assert candidate['value'] in name_excerpt(candidate)
    assert len(name_excerpt(candidate))<=600


class Gateway:
    def __init__(self, names): self.names=names
    def verify_project_names(self, *args, **kwargs):
        return SimpleNamespace(payload={'names':self.names},finish_reason='STOP')


def test_model_second_look_accepts_only_cited_substring():
    candidate={'value':'建国中路修缮项目的','excerpt':'本次建国中路修缮项目的预算'}
    result,calls=review_names(Gateway([{'index':0,'value':'建国中路修缮项目'}]),[candidate],'test')
    assert result==['建国中路修缮项目'] and calls==1
    result,_=review_names(Gateway([{'index':0,'value':'另一个新项目'}]),[candidate],'test')
    assert result==['建国中路修缮项目']


def test_unavailable_optional_review_does_not_lose_other_analysis():
    class Broken:
        def verify_project_names(self,*a,**kw): raise TimeoutError('private content')
    assert review_names(Broken(),[{'value':'测试项目的','excerpt':'测试项目的材料'}],'test')[0]==['测试项目']
    assert review_names(Gateway([]),[],'test')==([],0)


def test_model_can_reject_document_category_and_cannot_duplicate_indices():
    candidates=[{'value':'委托协议书','excerpt':'委托协议书'}]
    assert review_names(Gateway([{'index':0,'value':None}]),candidates,'test')[0]==[None]
    candidates.append({'value':'试验项目','excerpt':'试验项目'})
    assert review_names(Gateway([{'index':0,'value':None},{'index':0,'value':None}]),candidates,'test')[0]==['委托协议书','试验项目']


def cached_gateway():
    from audit_ai.config import Settings
    from audit_ai.model.gateway import OpenAiCompatibleModelGateway
    return OpenAiCompatibleModelGateway(Settings(model_name='synthetic'))


def test_exact_grounded_name_review_reuses_valid_response_but_context_changes_do_not(monkeypatch):
    g=cached_gateway();calls=[]
    def verify(text,*args,**kwargs):
        calls.append(text)
        return type('Completion',(),{'payload':{'names':[{'index':0,'value':'青禾维修项目'}]},'finish_reason':'STOP'})()
    monkeypatch.setattr(g,'verify_project_names',verify)
    candidate={'value':'青禾维修项目的','excerpt':'项目名称：青禾维修项目的'}
    assert review_names(g,[candidate],'one')==(['青禾维修项目'],1)
    assert review_names(g,[candidate],'two')==(['青禾维修项目'],0)
    assert len(calls)==1
    assert review_names(g,[{**candidate,'excerpt':'历史参考：青禾维修项目的'}],'three')[1]==1
    assert len(calls)==2


def test_name_cache_is_separate_for_client_identity(monkeypatch):
    from audit_ai.client_privacy import CURRENT,ClientIdentity
    g=cached_gateway();calls=[]
    monkeypatch.setattr(g,'verify_project_names',lambda *a,**kw:(calls.append(1) or
        type('Completion',(),{'payload':{'names':[{'index':0,'value':'测试项目'}]},'finish_reason':'STOP'})()))
    candidate={'value':'测试项目','excerpt':'项目名称：测试项目'}
    token=CURRENT.set(ClientIdentity(('测试单位甲',),()))
    try:
        review_names(g,[candidate],'one')
        CURRENT.set(ClientIdentity(('测试单位乙',),()))
        review_names(g,[candidate],'two')
        assert len(calls)==2
    finally:CURRENT.reset(token)


def test_same_name_requests_coalesce_while_another_waiter_can_time_out(monkeypatch):
    from concurrent.futures import ThreadPoolExecutor
    from threading import Event
    g=cached_gateway();entered=Event();release=Event();calls=[]
    def verify(*args,**kwargs):
        calls.append(1);entered.set();assert release.wait(2)
        return type('Completion',(),{'payload':{'names':[{'index':0,'value':'测试项目'}]},'finish_reason':'STOP'})()
    monkeypatch.setattr(g,'verify_project_names',verify)
    candidate={'value':'测试项目','excerpt':'项目名称：测试项目'}
    with ThreadPoolExecutor(max_workers=2) as pool:
        first=pool.submit(review_names,g,[candidate],'one')
        assert entered.wait(1)
        # The public minimum deadline is one second; a coalesced waiter must not
        # start a second inference when its own wait expires.
        assert review_names(g,[candidate],'two',timeout_seconds=1)==(['测试项目'],0)
        assert len(calls)==1
        release.set();assert first.result()==(['测试项目'],1)
    assert review_names(g,[candidate],'three')==(['测试项目'],0)


def test_failed_name_check_is_reused_as_fallback_without_claiming_model_validation(monkeypatch):
    g=cached_gateway();calls=[]
    def failed(*args,**kwargs):calls.append(1);raise TimeoutError()
    monkeypatch.setattr(g,'verify_project_names',failed)
    candidate={'value':'测试项目的','excerpt':'项目名称：测试项目的'}
    assert review_names(g,[candidate],'one')==(['测试项目'],1)
    assert review_names(g,[candidate],'two')==(['测试项目'],0)
    assert len(calls)==1
