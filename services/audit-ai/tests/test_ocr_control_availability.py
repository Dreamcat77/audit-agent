"""OCR file IO must not block the control-plane event loop."""

import asyncio
from io import BytesIO
from pathlib import Path
import tempfile
import threading
import time

import httpx
import pytest
from starlette.datastructures import Headers, UploadFile

from audit_ai.config import Settings
from audit_ai.main import create_app
from audit_ai.ocr.port import OcrExtraction, OcrPage
from audit_ai.ocr.service import OcrApplicationService


class LocalOcr:
    def extract(self, path: Path) -> OcrExtraction:
        assert path.read_bytes() == b"x" * (4 * 64 * 1024)
        return OcrExtraction("page", 1.0, (OcrPage(1, "page", 1.0),), "TEST", 1)


@pytest.mark.parametrize("slow_stage", ["write", "cleanup"])
def test_health_and_loop_remain_responsive_during_ocr_file_io(
    tmp_path, monkeypatch, slow_stage
):
    # Removing the worker boundary around file writes or cleanup must fail this
    # test. Small in-memory multipart files reproduce UploadFile.read's no-yield
    # path; slow storage releases the GIL and isolates event-loop blocking.
    original_open = Path.open
    original_cleanup = tempfile.TemporaryDirectory.cleanup

    class SlowDestination:
        def __init__(self, delegate):
            self.delegate = delegate

        def __enter__(self):
            self.delegate.__enter__()
            return self

        def __exit__(self, *args):
            return self.delegate.__exit__(*args)

        def write(self, chunk):
            time.sleep(0.1)
            return self.delegate.write(chunk)

    def open_file(path, *args, **kwargs):
        opened = original_open(path, *args, **kwargs)
        if slow_stage == "write" and path.name == "upload.png" and args == ("xb",):
            return SlowDestination(opened)
        return opened

    def cleanup(directory):
        if slow_stage == "cleanup" and Path(directory.name).name.startswith("audit-ai-ocr-"):
            time.sleep(0.4)
        return original_cleanup(directory)

    monkeypatch.setattr(Path, "open", open_file)
    monkeypatch.setattr(tempfile.TemporaryDirectory, "cleanup", cleanup)

    async def scenario():
        app = create_app(Settings(ocr_temp_root=str(tmp_path)), ocr_port=LocalOcr())
        lags = []
        health_ms = []
        finished = asyncio.Event()

        async def sample_loop():
            while not finished.is_set():
                started = time.perf_counter()
                await asyncio.sleep(0.01)
                lags.append(time.perf_counter() - started - 0.01)

        async def query_health():
            # Use an independent ASGI client; this is an event-loop regression,
            # not a substitute for the real independent TCP acceptance probe.
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://local") as client:
                for _ in range(30):
                    started = time.perf_counter()
                    response = await client.get("/health")
                    health_ms.append((time.perf_counter() - started) * 1000)
                    assert response.status_code == 200
                    await asyncio.sleep(0.01)

        sampler = asyncio.create_task(sample_loop())
        control = asyncio.create_task(query_health())
        try:
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://local") as client:
                response = await client.post("/ai/v1/ocr/extract", files={"file": ("scan.png", b"x" * (4 * 64 * 1024), "image/png")})
            await control
            assert response.status_code == 200
        finally:
            finished.set()
            await sampler
            await control
        print(f"stage={slow_stage} independent_health=30 max_loop_lag_ms={max(lags)*1000:.1f} max_health_ms={max(health_ms):.1f}")
        assert max(lags) < 0.2, "OCR file IO blocked the event loop"
        assert max(health_ms) < 200, "OCR file IO delayed the independent health client"
        assert list(tmp_path.iterdir()) == []

    asyncio.run(scenario())


@pytest.mark.parametrize("repeat_cancel", [False, True])
def test_cancellation_waits_for_file_owner_before_closing_upload(tmp_path, repeat_cancel):
    # A cancelled HTTP task must not close its upload source while the worker
    # is still copying it, or remove the OCR path while prediction uses it.
    entered = threading.Event()
    release = threading.Event()

    class PausedSource(BytesIO):
        def read(self, size=-1):
            entered.set()
            assert release.wait(3), "test worker did not receive release"
            return super().read(size)

    async def scenario():
        source = PausedSource(b"x" * (4 * 64 * 1024))
        upload = UploadFile(source, headers=Headers({"content-type": "image/png"}))
        service = OcrApplicationService(Settings(ocr_temp_root=str(tmp_path)), LocalOcr())
        task = asyncio.create_task(service.extract(upload))
        try:
            async with asyncio.timeout(2):
                while not entered.is_set():
                    await asyncio.sleep(0.005)
            task.cancel()
            await asyncio.sleep(0.03)
            if repeat_cancel:
                task.cancel()
                await asyncio.sleep(0.03)
            assert not task.done(), "cancellation abandoned the file-owning worker"
            assert not source.closed
            assert len(list(tmp_path.iterdir())) == 1
        finally:
            release.set()
            with pytest.raises(asyncio.CancelledError):
                await task
        assert source.closed
        assert list(tmp_path.iterdir()) == []

    asyncio.run(scenario())


@pytest.mark.parametrize("failure", [ValueError("private source"), None])
def test_failed_or_empty_upload_closes_source_and_cleans_temp_files(tmp_path, failure):
    class FailingOcr:
        def extract(self, path):
            raise failure

    async def scenario():
        source = BytesIO(b"data" if failure else b"")
        upload = UploadFile(source, headers=Headers({"content-type": "image/png"}))
        service = OcrApplicationService(Settings(ocr_temp_root=str(tmp_path)), FailingOcr())
        from audit_ai.errors import AuditAiError

        with pytest.raises(AuditAiError) as raised:
            await service.extract(upload)
        assert raised.value.code == ("OCR_EXECUTION_FAILED" if failure else "EMPTY_UPLOAD")
        assert source.closed
        assert list(tmp_path.iterdir()) == []

    asyncio.run(scenario())
