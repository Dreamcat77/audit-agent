from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import threading
import time

import pytest

from audit_ai.config import Settings
from audit_ai.ocr.port import OcrExtraction,OcrPage
from audit_ai.ocr.process_adapter import ProcessOcrPool,ProcessOcrAdapter,OcrWorkerOutcomeUnknown
from audit_ai.ocr.paddle_adapter import OcrRuntimeUnavailable
from audit_ai.ocr.resources import capacity,linux_resources,MIB


def abundant():return 16.,32*1024*MIB


def test_resource_budget_never_exceeds_cpu_memory_or_unknown_accounting():
    assert capacity(4,16,32000*MIB,10240,3072,2560)==4
    assert capacity(4,2,32000*MIB,10240,3072,2560)==2
    assert capacity(4,16,6000*MIB,10240,3072,2560)==1
    assert capacity(4,16,32000*MIB,5120,3072,2560)==2
    assert capacity(4,None,32000*MIB,10240,3072,2560)==1
    assert capacity(4,16,None,10240,3072,2560)==1


def test_visible_parent_cgroup_quota_and_headroom_are_respected(tmp_path,monkeypatch):
    monkeypatch.setattr('audit_ai.ocr.resources.os.sched_getaffinity',lambda _:set(range(16)),raising=False)
    (tmp_path/'proc/self').mkdir(parents=True)
    (tmp_path/'proc/meminfo').write_text('MemAvailable: 32000000 kB\n')
    (tmp_path/'proc/self/cgroup').write_text('0::/parent/child\n')
    for name,quota,limit,used in [('', 'max 100000','max','0'),('parent','200000 100000',str(8*1024*MIB),str(2*1024*MIB)),('parent/child','max 100000','max','0')]:
        p=tmp_path/'sys/fs/cgroup'/name;p.mkdir(parents=True,exist_ok=True)
        for file,value in [('cpu.max',quota),('memory.max',limit),('memory.current',used)]: (p/file).write_text(value)
    assert linux_resources(tmp_path)==(2.,6*1024*MIB)
    (tmp_path/'sys/fs/cgroup/parent/cpu.max').write_text('broken')
    assert linux_resources(tmp_path)==(None,None)


def test_missing_descendant_controllers_inherit_parent_limits(tmp_path,monkeypatch):
    monkeypatch.setattr('audit_ai.ocr.resources.os.sched_getaffinity',lambda _:set(range(16)),raising=False)
    (tmp_path/'proc/self').mkdir(parents=True)
    (tmp_path/'proc/meminfo').write_text('MemAvailable: 32000000 kB\n')
    (tmp_path/'proc/self/cgroup').write_text('0::/parent/child\n')
    base=tmp_path/'sys/fs/cgroup'
    (base/'parent/child').mkdir(parents=True)
    (base/'cpu.max').write_text('200000 100000')
    (base/'memory.max').write_text(str(8*1024*MIB))
    (base/'memory.current').write_text(str(2*1024*MIB))
    assert linux_resources(tmp_path)==(2.,6*1024*MIB)
    (tmp_path/'proc/self/cgroup').write_text('0::/missing/process\n')
    assert linux_resources(tmp_path)==(None,None)
    (tmp_path/'proc/self/cgroup').write_text('0::/parent/child\n')
    (base/'cpu.max').unlink()
    assert linux_resources(tmp_path)==(None,None)
    (base/'cpu.max').mkdir()  # present but unreadable as a controller file
    assert linux_resources(tmp_path)==(None,None)


@pytest.mark.parametrize('value',[0,5,True,1.5])
def test_invalid_pool_cap_is_rejected(value):
    with pytest.raises(ValueError,match='ocr_worker_processes'):Settings(ocr_worker_processes=value)


def test_pool_is_globally_bounded_and_close_waits_for_actual_file_owners(tmp_path):
    gate=threading.Event();two=threading.Event();lock=threading.Lock();active=0;maximum=0;closed=[]
    class Worker:
        def __init__(self,_):pass
        def extract(self,path):
            nonlocal active,maximum
            with lock:
                active+=1;maximum=max(maximum,active)
                if active==2:two.set()
            assert gate.wait(10)
            assert path.read_text()=='private-test'
            with lock:active-=1
            return OcrExtraction('ok',1.,(OcrPage(1,'ok',1.),),'TEST',1)
        def close(self):
            assert active==0;closed.append(self)
    p=tmp_path/'input';p.write_text('private-test')
    pool=ProcessOcrPool(Settings(ocr_worker_processes=2),worker_factory=Worker,resource_reader=abundant)
    with ThreadPoolExecutor(max_workers=4) as threads:
        running=[threads.submit(pool.extract,p) for _ in range(2)]
        assert two.wait(5)
        waiting=threads.submit(pool.extract,p)
        stopped=threads.submit(pool.close)
        with pytest.raises(OcrRuntimeUnavailable):waiting.result(timeout=5)
        assert not stopped.done()
        gate.set()
        assert [f.result(timeout=5).text for f in running]==['ok','ok']
        stopped.result(timeout=5)
    assert maximum==2 and len(closed)==2


def test_unknown_dispatch_is_not_replayed_or_replaced(tmp_path):
    calls=[]
    class Worker:
        def __init__(self,_):pass
        def extract(self,path):calls.append(path);raise OcrWorkerOutcomeUnknown('synthetic')
        def close(self):pass
    pool=ProcessOcrPool(Settings(ocr_worker_processes=2),worker_factory=Worker,resource_reader=abundant)
    with pytest.raises(OcrWorkerOutcomeUnknown):pool.extract(tmp_path/'x')
    with pytest.raises(OcrRuntimeUnavailable):pool.extract(tmp_path/'x')
    assert len(calls)==1
    pool.close()


def test_wait_timeout_does_not_dispatch_or_poison_healthy_slot(tmp_path):
    entered=threading.Event();release=threading.Event();calls=[]
    class Worker:
        def __init__(self,_):pass
        def extract(self,path):
            calls.append(path);entered.set();assert release.wait(5)
            return OcrExtraction('ok',1.,(OcrPage(1,'ok',1.),),'TEST',1)
        def close(self):pass
    pool=ProcessOcrPool(Settings(),worker_factory=Worker,resource_reader=abundant,wait_seconds=.1)
    with ThreadPoolExecutor(max_workers=1) as t:
        first=t.submit(pool.extract,tmp_path/'first');assert entered.wait(5)
        with pytest.raises(OcrRuntimeUnavailable,match='before dispatch'):pool.extract(tmp_path/'second')
        release.set();first.result(timeout=5)
    assert pool.extract(tmp_path/'third').text=='ok' and len(calls)==2
    pool.close()


class NativeSynthetic:
    def __init__(self,_):self.calls=0
    def extract(self,path):
        self.calls+=1;text=path.read_text()
        return OcrExtraction(text,1.,(OcrPage(1,text,1.),),'TEST',1)


def synthetic_factory(settings):return NativeSynthetic(settings)


def test_real_spawn_children_have_separate_pipelines_and_are_all_reaped(tmp_path):
    p=tmp_path/'x';p.write_text('two native owners')
    def factory(settings):return ProcessOcrAdapter(settings,factory=synthetic_factory,timeout_seconds=15)
    pool=ProcessOcrPool(Settings(ocr_worker_processes=2),worker_factory=factory,resource_reader=abundant)
    try:
        with ThreadPoolExecutor(max_workers=2) as threads:
            assert [f.result(timeout=20).text for f in [threads.submit(pool.extract,p) for _ in range(2)]]==['two native owners']*2
        children=[w._child for w in pool._workers]
        assert len({c.pid for c in children})==2 and all(c.is_alive() for c in children)
    finally:pool.close()
    assert all(w._child is None for w in pool._workers)
