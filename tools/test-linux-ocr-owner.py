"""Real Linux spawn, parent death, timeout and native GIL isolation checks."""
import ctypes
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time

from audit_ai.config import Settings
from audit_ai.ocr.port import OcrExtraction, OcrPage
from audit_ai.ocr.process_adapter import ProcessOcrAdapter, OcrWorkerOutcomeUnknown


class NativePort:
    def __init__(self, settings):
        assert settings.model_api_key is None
    def extract(self, path):
        path.with_suffix('.entered').write_text(str(os.getpid()))
        if path.read_text() == 'CRASH': os._exit(7)
        libc=ctypes.PyDLL(None)
        libc.usleep.argtypes=[ctypes.c_uint]
        libc.usleep(3_000_000)
        text='合成预算120000元'
        return OcrExtraction(text,1.,(OcrPage(1,text,1.),),'SYNTHETIC',1)


def factory(settings): return NativePort(settings)


def wait_entered(path):
    deadline=time.monotonic()+10
    while not path.with_suffix('.entered').exists():
        assert time.monotonic()<deadline,'CHILD_ENTRY_TIMEOUT'
        time.sleep(.01)
    return int(path.with_suffix('.entered').read_text())


def alive(pid):
    status=Path('/proc')/str(pid)/'stat'
    return status.exists() and status.read_text().split()[2]!='Z'


def main():
    if len(sys.argv)>1 and sys.argv[1]=='owner':
        path=Path(sys.argv[2]);port=ProcessOcrAdapter(Settings(),factory=factory)
        threading.Thread(target=lambda:port.extract(path),daemon=True).start()
        print(json.dumps({'child':wait_entered(path)}),flush=True)
        sys.stdin.readline();os._exit(0)
    assert sys.platform=='linux'
    with tempfile.TemporaryDirectory(prefix='audit-synthetic-owner-') as folder:
        root=Path(folder);path=root/'normal.png';path.write_text('NORMAL')
        port=ProcessOcrAdapter(Settings(model_api_key='synthetic-not-a-real-key'),factory=factory)
        result=[];worker=threading.Thread(target=lambda:result.append(port.extract(path)))
        worker.start();pid=wait_entered(path)
        start=time.monotonic();time.sleep(.05)
        assert time.monotonic()-start<.5,'PARENT_GIL_BLOCKED'
        worker.join(10);assert len(result)==1 and '120000' in result[0].text
        # Linux PDEATHSIG follows the creating OS thread, not just its process.
        # An ASGI request pool thread may retire after the first successful OCR.
        time.sleep(.2)
        assert alive(pid),'OCR_REQUEST_THREAD_EXIT_KILLED_CHILD'
        followup=[]
        next_worker=threading.Thread(target=lambda:followup.append(port.extract(path)))
        next_worker.start();next_worker.join(10)
        assert len(followup)==1 and port._child.pid==pid,'OCR_RETIRED_THREAD_PIPELINE_NOT_REUSED'
        port.close();assert not alive(pid),'CLOSE_CHILD_SURVIVED'
        for kind in ('TIMEOUT','CRASH'):
            path=root/(kind+'.png');path.write_text(kind)
            port=ProcessOcrAdapter(Settings(),factory=factory,timeout_seconds=.7 if kind=='TIMEOUT' else 10)
            try:
                try:port.extract(path)
                except OcrWorkerOutcomeUnknown:pass
                else:raise AssertionError('UNKNOWN_OUTCOME_ACCEPTED')
                pid=wait_entered(path);assert not alive(pid),'FAILED_CHILD_SURVIVED'
                try:port.extract(path)
                except Exception:pass
                else:raise AssertionError('UNCERTAIN_CALL_REPLAYED')
            finally:port.close()
        path=root/'parent.png';path.write_text('NORMAL')
        owner=subprocess.Popen([sys.executable,__file__,'owner',str(path)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True)
        try:
            pid=json.loads(owner.stdout.readline())['child'];assert alive(pid)
            owner.stdin.write('exit\n');owner.stdin.flush();assert owner.wait(5)==0
            deadline=time.monotonic()+5
            while alive(pid) and time.monotonic()<deadline:time.sleep(.01)
            assert not alive(pid),'ABRUPT_OWNER_EXIT_ORPHANED_CHILD'
        finally:
            if owner.poll() is None:owner.kill();owner.wait(5)
            owner.stdin.close();owner.stdout.close()
    print('LINUX_OCR_NATIVE_GIL_CLOSE_TIMEOUT_CRASH_PARENT_DEATH_NO_REPLAY=PASS')


if __name__=='__main__':main()
