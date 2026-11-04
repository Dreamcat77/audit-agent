"""Real Windows spawn/Job/IPC; synthetic native work, never Paddle/model/network."""
import asyncio
from concurrent.futures import ThreadPoolExecutor
import ctypes
import logging
import os
import json
import subprocess
from pathlib import Path
import sys
import time
from uuid import uuid4

import httpx
import pytest

from audit_ai.config import Settings
from audit_ai.logging import log_event, diagnostic_context
from audit_ai.main import create_app
from audit_ai.ocr.paddle_adapter import PaddleOcrAdapter, OcrRuntimeUnavailable
from audit_ai.ocr.port import OcrExtraction, OcrPage
from audit_ai.ocr.process_adapter import ProcessOcrAdapter, OcrWorkerOutcomeUnknown, OcrWorkerReapUnproven, create_ocr_port, _result


def _event_api():
    from ctypes import wintypes as W
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    for name, arguments, result in (
        ("CreateEventW", [ctypes.c_void_p, W.BOOL, W.BOOL, W.LPCWSTR], W.HANDLE),
        ("OpenEventW", [W.DWORD, W.BOOL, W.LPCWSTR], W.HANDLE),
        ("SetEvent", [W.HANDLE], W.BOOL),
        ("WaitForSingleObject", [W.HANDLE, W.DWORD], W.DWORD),
        ("CloseHandle", [W.HANDLE], W.BOOL),
    ):
        function = getattr(kernel, name)
        function.argtypes, function.restype = arguments, result
    return kernel


class SyntheticPort:
    def __init__(self, settings):
        assert settings.model_api_key is None
        self.calls = 0

    def extract(self, path):
        content = path.read_bytes()
        self.calls += 1
        if content == b"MANY_STAGES":
            for page in range(1, 17):
                log_event(logging.getLogger("audit_ai.synthetic"), "OCR_ORIENTATION_SELECTED",
                          stage="OCR", outcome="SUCCESS", page_count=page, rotation_degrees=0,
                          deskew_millidegrees=0, input_chars=120, orientation_candidates=4,
                          duration_ms=250, orientation_quality_milli=900,
                          classifier_confidence_milli=0, ocr_page_count=page)
        if content == b"NATIVE_LOG":
            from ctypes import wintypes as W
            kernel=ctypes.WinDLL("kernel32",use_last_error=True)
            kernel.GetStdHandle.argtypes=[W.DWORD];kernel.GetStdHandle.restype=W.HANDLE
            kernel.WriteFile.argtypes=[W.HANDLE,ctypes.c_void_p,W.DWORD,ctypes.POINTER(W.DWORD),ctypes.c_void_p];kernel.WriteFile.restype=W.BOOL
            canary=b"SYNTHETIC_PRIVATE_NATIVE_CANARY\n"
            for stream in (-11,-12):
                written=W.DWORD()
                assert kernel.WriteFile(kernel.GetStdHandle(stream),canary,len(canary),ctypes.byref(written),None)
        if content in (b"GIL", b"HANG"):
            path.with_suffix(".entered").write_text("SYNTHETIC_ENTERED")
            log_event(logging.getLogger("audit_ai.synthetic"), "OCR_PREDICT_START", stage="OCR", outcome="START")
            sleep = ctypes.PyDLL("kernel32").Sleep
            sleep.argtypes, sleep.restype = [ctypes.c_uint32], None
            sleep(600 if content == b"GIL" else 10000)
            assert path.exists(), "upload removed during child computation"
            log_event(logging.getLogger("audit_ai.synthetic"), "OCR_PREDICT_END", stage="OCR", outcome="SUCCESS", duration_ms=600)
        if content.startswith(b"GIL_GATE:"):
            from ctypes import wintypes as W
            name = content.removeprefix(b"GIL_GATE:").decode("ascii")
            suffix = name.removeprefix("Local\\audit-ocr-native-")
            assert name.startswith("Local\\audit-ocr-native-") and len(suffix) == 32
            assert all(character in "0123456789abcdef" for character in suffix)
            kernel = _event_api()
            release = kernel.OpenEventW(0x100000, False, name + "-release")
            entered = kernel.OpenEventW(0x2, False, name + "-entered")
            try:
                assert release and entered, "parent-created native gate unavailable"
                wait = ctypes.PyDLL("kernel32").SignalObjectAndWait
                wait.argtypes, wait.restype = [W.HANDLE, W.HANDLE, W.DWORD, W.BOOL], W.DWORD
                log_event(logging.getLogger("audit_ai.synthetic"), "OCR_PREDICT_START", stage="OCR", outcome="START")
                started = time.monotonic()
                # The ready signal originates INSIDE the GIL-holding native call.
                # Parent controls completion; this finite watchdog only prevents leaks.
                assert wait(entered, release, 30000, False) == 0, "bounded native gate was not released"
                assert path.exists(), "upload removed during child computation"
                log_event(logging.getLogger("audit_ai.synthetic"), "OCR_PREDICT_END", stage="OCR", outcome="SUCCESS",
                          duration_ms=int((time.monotonic() - started) * 1000))
            finally:
                if entered: assert kernel.CloseHandle(entered)
                if release: assert kernel.CloseHandle(release)
        if content == b"CRASH":
            path.with_suffix(".entered").write_text("SYNTHETIC_ENTERED")
            os._exit(7)
        if content == b"BIG":
            text = "x" * (4 * 1024 * 1024 + 1)
        elif content == b"DESC":
            descendant=subprocess.Popen([sys.executable,"-c","import time; time.sleep(30)"],
                                        stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            text=str(descendant.pid)
        else:
            text = f"{os.getpid()}:{self.calls}"
        return OcrExtraction(text, 1., (OcrPage(1, text, 1.),), "SYNTHETIC", 1)


def synthetic(settings):
    return SyntheticPort(settings)


windows = pytest.mark.skipif(sys.platform != "win32", reason="Windows Job lifecycle")


@pytest.mark.skipif(sys.platform != 'linux', reason='Linux PDEATHSIG thread ownership')
def test_linux_request_thread_retirement_keeps_persistent_child_and_close_reaps_it(tmp_path):
    path=tmp_path/'small.png';path.write_bytes(b'small')
    port=ProcessOcrAdapter(Settings(),factory=synthetic)
    try:
        with ThreadPoolExecutor(max_workers=1) as pool:
            first=pool.submit(port.extract,path).result(timeout=15)
        child=port._child
        time.sleep(.2)
        assert child.is_alive(), 'retiring request thread killed OCR compute child'
        with ThreadPoolExecutor(max_workers=1) as pool:
            second=pool.submit(port.extract,path).result(timeout=15)
        assert first.text.split(':')[0]==second.text.split(':')[0]
        assert second.text.endswith(':2')
        port.close()
        assert port._child is None
        with pytest.raises(OcrRuntimeUnavailable):port.extract(path)
    finally:port.close()


def test_factory_keeps_linux_legacy_boundary_without_loading_paddle(monkeypatch):
    monkeypatch.setattr("audit_ai.ocr.process_adapter.sys.platform", "linux")
    assert isinstance(create_ocr_port(Settings()), PaddleOcrAdapter)
    isolated=create_ocr_port(Settings(ocr_process_isolation=True))
    assert isinstance(isolated,ProcessOcrAdapter)
    isolated.close()


def test_process_protocol_accepts_page_diagnostics_for_long_scans(tmp_path):
    path=tmp_path/'many-pages.png';path.write_bytes(b'MANY_STAGES')
    port=ProcessOcrAdapter(Settings(),factory=synthetic,timeout_seconds=15)
    try:
        assert port.extract(path).text.endswith(':1')
    finally:
        port.close()


def test_failed_termination_is_explicitly_unproven_instead_of_claiming_safe_cleanup():
    class Unreaped:
        pid = 123  # Synthetic object; no actual process lookup or termination.
        def join(self, seconds): pass
        def is_alive(self): return True
        def terminate(self): raise OSError("synthetic access refusal")
    port = ProcessOcrAdapter(Settings(), factory=synthetic)
    port._child = Unreaped()
    with pytest.raises(OcrWorkerReapUnproven): port.close()


def test_job_cleanup_failure_retains_input_even_when_main_child_has_exited():
    class DeadChild:
        pid=123
        def join(self, seconds):pass
        def is_alive(self):return False
        def close(self):pass
    class FailedJob:
        def close(self):raise RuntimeError("synthetic descendants still unknown")
    port=ProcessOcrAdapter(Settings(),factory=synthetic)
    port._child=DeadChild();port._job=FailedJob()
    with pytest.raises(OcrWorkerReapUnproven):port.close()
    assert port._job is not None


def test_expired_startup_budget_never_dispatches_compute(monkeypatch):
    class Pipe:
        sends=0
        def send_bytes(self, value):self.sends+=1
    pipe=Pipe();port=ProcessOcrAdapter(Settings(),factory=synthetic,timeout_seconds=1)
    monkeypatch.setattr(port,"_start",lambda deadline:pipe)
    clock=iter([0.,2.,2.]);monkeypatch.setattr("audit_ai.ocr.process_adapter.time.monotonic",lambda:next(clock))
    with pytest.raises(OcrRuntimeUnavailable):port.extract(Path("synthetic-input.png"))
    assert pipe.sends==0


def test_unproven_reap_retains_upload_instead_of_automatic_temp_cleanup(tmp_path):
    from io import BytesIO
    from starlette.datastructures import Headers, UploadFile
    from audit_ai.ocr.service import OcrApplicationService
    from audit_ai.errors import AuditAiError
    class UnprovenPort:
        def extract(self, path): raise OcrWorkerReapUnproven("synthetic unproved child")
    async def scenario():
        upload=UploadFile(BytesIO(b"retained"),headers=Headers({"content-type":"image/png"}))
        service=OcrApplicationService(Settings(ocr_temp_root=str(tmp_path)),UnprovenPort())
        with pytest.raises(AuditAiError):await service.extract(upload)
        assert upload.file.closed
        retained=list(tmp_path.glob("audit-ai-ocr-*/upload.png"))
        assert len(retained)==1 and retained[0].read_bytes()==b"retained"
    asyncio.run(scenario())


@windows
def test_windows_factory_is_import_safe_lazy_and_reuses_one_pipeline_and_slot(tmp_path):
    port = ProcessOcrAdapter(Settings(model_api_key="private-test-secret"), factory=synthetic)
    assert port._child is None
    path = tmp_path / "input.png"; path.write_bytes(b"small")
    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda _: port.extract(path), range(2)))
        pids = [r.text.split(":")[0] for r in results]
        assert pids[0] == pids[1] and int(pids[0]) != os.getpid()
        assert {r.text.split(":")[1] for r in results} == {"1", "2"}
        child = port._child
        port.close()
        assert port._child is None and path.exists()
        with pytest.raises(OcrRuntimeUnavailable): port.extract(path)
    finally:
        port.close()


@windows
@pytest.mark.parametrize("mode", [b"HANG", b"CRASH"])
def test_dispatched_unknown_is_reaped_and_never_automatically_replayed(tmp_path, mode):
    port = ProcessOcrAdapter(Settings(), factory=synthetic, timeout_seconds=1.5)
    path = tmp_path / "input.png"; path.write_bytes(mode)
    try:
        # Test the dispatched phase, not Windows cold-spawn latency under an
        # unrelated build. Startup has its own bounded pre-dispatch regression.
        # No EXTRACT is sent by _start; keep the actual request budget at 1.5s.
        port._start(time.monotonic() + 10)
        started = time.monotonic()
        with pytest.raises(OcrWorkerOutcomeUnknown): port.extract(path)
        assert time.monotonic() - started < 8
        assert path.with_suffix(".entered").read_text() == "SYNTHETIC_ENTERED"
        assert port._child is None and path.exists()
        path.write_bytes(b"small")
        with pytest.raises(OcrRuntimeUnavailable): port.extract(path)
        assert port._child is None
    finally: port.close()


@windows
def test_output_is_bounded_and_does_not_return_provider_objects(tmp_path):
    from audit_ai.ocr.paddle_adapter import OcrPredictionInvalid
    port = ProcessOcrAdapter(Settings(), factory=synthetic)
    path = tmp_path / "input.png"; path.write_bytes(b"BIG")
    try:
        with pytest.raises(OcrPredictionInvalid): port.extract(path)
        assert port._child is None
    finally: port.close()


@windows
def test_job_assignment_failure_never_allows_compute(tmp_path):
    class RefusedJob:
        def assign(self, child): raise RuntimeError("synthetic assignment refusal")
        def close(self): pass
    port = ProcessOcrAdapter(Settings(), factory=synthetic, job_factory=RefusedJob)
    path = tmp_path / "input.png"; path.write_bytes(b"small")
    try:
        with pytest.raises(OcrWorkerOutcomeUnknown): port.extract(path)
        assert port._child is None
    finally: port.close()


@windows
def test_native_gil_compute_keeps_real_health_handler_and_loop_responsive(tmp_path, caplog):
    caplog.set_level(logging.INFO)
    port = ProcessOcrAdapter(Settings(), factory=synthetic)
    kernel = _event_api()
    event_name = "Local\\audit-ocr-native-" + uuid4().hex
    gates = {}
    async def scenario():
        app = create_app(Settings(ocr_temp_root=str(tmp_path)), ocr_port=port)
        lag = []; health = []; done = False; native_samples = 0
        async def heartbeat():
            while not done:
                at = time.perf_counter(); await asyncio.sleep(.01)
                lag.append(time.perf_counter() - at - .01)
        async with app.router.lifespan_context(app):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://local") as upload:
                sample = asyncio.create_task(heartbeat())
                payload = ("GIL_GATE:" + event_name).encode("ascii")
                compute = asyncio.create_task(upload.post("/ai/v1/ocr/extract", files={"file": ("synthetic.png", payload, "image/png")}, headers={"X-Request-Id": "isolated-compute"}))
                def assert_native_active():
                    assert not compute.done(), "synthetic native work finished before parent released its gate"
                    assert port._child is not None and port._child.is_alive()
                    assert kernel.WaitForSingleObject(gates["release"], 0) == 258
                    assert "OCR_PREDICT_END" not in caplog.text
                try:
                    async with asyncio.timeout(5):
                        while kernel.WaitForSingleObject(gates["entered"], 0) != 0:
                            assert not compute.done(), "synthetic child did not enter the native gate"
                            await asyncio.sleep(.005)
                    assert_native_active()
                    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://local") as control:
                        for _ in range(30):
                            assert_native_active()
                            at = time.perf_counter(); response = await control.get("/health")
                            health.append((time.perf_counter() - at)*1000)
                            assert response.status_code == 200
                            assert response.json() == {"status": "UP", "service": "audit-ai"}
                            assert_native_active(); native_samples += 1
                            await asyncio.sleep(.01)
                    assert_native_active()
                    assert native_samples == 30
                    assert kernel.SetEvent(gates["release"])
                    assert (await compute).status_code == 200
                finally:
                    assert kernel.SetEvent(gates["release"])
                    done = True; await sample; await compute
        assert len(health) == 30 and max(health) < 200 and max(lag) < .2
        assert "request_id=isolated-compute" in caplog.text
        assert list(tmp_path.iterdir()) == [] and port._child is None
        print(f"isolated_health=30 native_overlap_samples={native_samples} max_health_ms={max(health):.1f} max_loop_lag_ms={max(lag)*1000:.1f}")
    try:
        for suffix in ("release", "entered"):
            ctypes.set_last_error(0)
            gates[suffix] = kernel.CreateEventW(None, True, False, event_name + "-" + suffix)
            assert gates[suffix] and ctypes.get_last_error() != 183, "native gate must be uniquely created"
        asyncio.run(scenario())
    finally:
        if gates.get("release"): kernel.SetEvent(gates["release"])
        port.close()
        for handle in gates.values():
            if handle: assert kernel.CloseHandle(handle)
        for suffix in gates:
            ctypes.set_last_error(0)
            remaining = kernel.OpenEventW(0x100000, False, event_name + "-" + suffix)
            error = ctypes.get_last_error()
            if remaining: kernel.CloseHandle(remaining)
            assert not remaining and error == 2, "synthetic native gate handle leaked"


@windows
def test_cancelled_upload_waits_for_child_before_temp_removal(tmp_path, caplog):
    from io import BytesIO
    from starlette.datastructures import Headers, UploadFile
    from audit_ai.ocr.service import OcrApplicationService
    caplog.set_level(logging.INFO)
    port=ProcessOcrAdapter(Settings(),factory=synthetic)
    async def scenario():
        upload=UploadFile(BytesIO(b"GIL"),headers=Headers({"content-type":"image/png"}))
        service=OcrApplicationService(Settings(ocr_temp_root=str(tmp_path)),port)
        task=asyncio.create_task(service.extract(upload))
        try:
            async with asyncio.timeout(5):
                while "OCR_PREDICT_START" not in caplog.text:await asyncio.sleep(.005)
            task.cancel();await asyncio.sleep(.03);task.cancel();await asyncio.sleep(.03)
            assert not task.done() and not upload.file.closed
            assert len(list(tmp_path.glob("audit-ai-ocr-*/upload.png")))==1
            with pytest.raises(asyncio.CancelledError):await task
            assert upload.file.closed and list(tmp_path.iterdir())==[]
        finally:
            await asyncio.to_thread(port.close)
    asyncio.run(scenario())


@windows
@pytest.mark.parametrize("phase",["native","preassignment"])
def test_abrupt_owner_exit_kills_production_child_even_during_native_gil_hold(tmp_path,phase):
    from ctypes import wintypes as W
    helper=Path(__file__).with_name("ocr_process_owner_fixture.py")
    # Child fixture imports this worktree's src, never an installed sibling tree.
    env={**os.environ,"PYTHONPATH":os.pathsep.join([str(helper.parents[1]/"src"),str(helper.parent)])}
    owner=subprocess.Popen([sys.executable,str(helper),str(tmp_path),phase],stdin=subprocess.PIPE,
                           stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,env=env)
    kernel=ctypes.WinDLL("kernel32",use_last_error=True)
    kernel.OpenProcess.argtypes=[W.DWORD,W.BOOL,W.DWORD];kernel.OpenProcess.restype=W.HANDLE
    kernel.WaitForSingleObject.argtypes=[W.HANDLE,W.DWORD];kernel.WaitForSingleObject.restype=W.DWORD
    kernel.CloseHandle.argtypes=[W.HANDLE];kernel.CloseHandle.restype=W.BOOL
    handle=None
    try:
        ready=tmp_path/"owner-ready.json";deadline=time.monotonic()+10
        while not ready.exists():
            assert owner.poll() is None,"offline fixture owner failed"
            assert time.monotonic()<deadline,"offline owner startup unproven"
            time.sleep(.01)
        record=json.loads(ready.read_text())
        handle=kernel.OpenProcess(0x100000,False,record["childPid"])
        assert handle,"exact live fixture child handle unavailable"
        owner.stdin.write(b"exit\n");owner.stdin.flush()
        assert owner.wait(5)==0
        assert kernel.WaitForSingleObject(handle,5000)==0,"production child orphaned after owner exit"
    finally:
        if owner.poll() is None:owner.kill();owner.wait(5)
        if handle:kernel.CloseHandle(handle)
        owner.stdin.close();owner.stderr.close()


@windows
def test_native_win32_provider_output_never_reaches_parent_streams(tmp_path):
    helper=Path(__file__).with_name("ocr_process_owner_fixture.py")
    env={**os.environ,"PYTHONPATH":os.pathsep.join([str(helper.parents[1]/"src"),str(helper.parent)])}
    result=subprocess.run([sys.executable,str(helper),str(tmp_path),"canary"],capture_output=True,env=env,timeout=15)
    assert result.returncode==0
    assert b"SYNTHETIC_SAFE_DONE" in result.stdout
    assert b"SYNTHETIC_PRIVATE_NATIVE_CANARY" not in result.stdout+result.stderr


@windows
def test_native_std_handles_not_aliasing_crt_are_also_silenced(tmp_path):
    helper=Path(__file__).with_name("ocr_process_owner_fixture.py")
    env={**os.environ,"PYTHONPATH":os.pathsep.join([str(helper.parents[1]/"src"),str(helper.parent)])}
    control=subprocess.run([sys.executable,str(helper),str(tmp_path),"native-direct-unmuted"],capture_output=True,env=env,timeout=10)
    assert control.returncode==0 and b"SYNTHETIC_PRIVATE_NATIVE_CANARY" in control.stdout+control.stderr
    result=subprocess.run([sys.executable,str(helper),str(tmp_path),"native-direct"],capture_output=True,env=env,timeout=10)
    assert result.returncode==0
    assert b"SYNTHETIC_PRIVATE_NATIVE_CANARY" not in result.stdout+result.stderr


@windows
def test_normal_close_proves_job_descendant_also_terminated(tmp_path):
    from ctypes import wintypes as W
    kernel=ctypes.WinDLL("kernel32",use_last_error=True)
    kernel.OpenProcess.argtypes=[W.DWORD,W.BOOL,W.DWORD];kernel.OpenProcess.restype=W.HANDLE
    kernel.WaitForSingleObject.argtypes=[W.HANDLE,W.DWORD];kernel.WaitForSingleObject.restype=W.DWORD
    kernel.CloseHandle.argtypes=[W.HANDLE];kernel.CloseHandle.restype=W.BOOL
    path=tmp_path/"input.png";path.write_bytes(b"DESC")
    port=ProcessOcrAdapter(Settings(),factory=synthetic);handle=None
    try:
        result=port.extract(path)
        handle=kernel.OpenProcess(0x100000,False,int(result.text));assert handle
        port.close()
        assert kernel.WaitForSingleObject(handle,0)==0,"Job descendant survived normal cleanup"
    finally:
        port.close()
        if handle:kernel.CloseHandle(handle)
