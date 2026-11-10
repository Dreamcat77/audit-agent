"""OCR-only persistent single compute child. No external queue or business state."""
from __future__ import annotations

from dataclasses import asdict
from collections import deque
from concurrent.futures import ThreadPoolExecutor
import json
import logging
import math
import multiprocessing
import os
from pathlib import Path
import sys
import threading
import time

from audit_ai.config import Settings
from audit_ai.logging import log_event
from audit_ai.ocr.paddle_adapter import PaddleOcrAdapter, OcrPredictionInvalid, OcrRuntimeUnavailable
from audit_ai.ocr.port import OcrExtraction, OcrPage, OcrPort
from audit_ai.ocr.windows_job import WindowsChildJob

_PACKET = 32 * 1024
_RESULT_LIMIT = 4 * 1024 * 1024
_COMMAND_LIMIT = 8192
_MAX_RELAYED_STAGES = 2048  # bounded for 1024 pages plus initialization/request events
_STAGES = frozenset({"OCR_INITIALIZATION_WAIT", "OCR_INITIALIZATION_START", "OCR_INITIALIZATION_END",
                     "OCR_PREDICTION_WAIT", "OCR_PREDICT_START", "OCR_PREDICT_END",
                     "OCR_ORIENTATION_SELECTED", "OCR_ACCURACY_FALLBACK", "OCR_PAGE_GEOMETRY"})
_LOGGER = logging.getLogger(__name__)


class OcrWorkerOutcomeUnknown(OcrRuntimeUnavailable):
    """Dispatch happened, but its outcome cannot be proved. Never replay it here."""


class OcrWorkerReapUnproven(OcrWorkerOutcomeUnknown):
    """The upload must be retained, not removed while a child might still use it."""


def _paddle(settings: Settings) -> OcrPort:
    return PaddleOcrAdapter(settings)


def _encoded(value: object, limit: int) -> bytes:
    data = json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode("utf-8")
    if len(data) > limit:
        raise OcrPredictionInvalid("OCR worker payload exceeds bound")
    return data


class _StageRelay(logging.Handler):
    def __init__(self, pipe):
        super().__init__(logging.INFO)
        self.pipe = pipe

    def emit(self, record):
        if not isinstance(record.args, tuple) or not record.args or record.args[0] not in _STAGES:
            return
        # Only the adapter's existing allowlisted events, no provider messages,
        # paths, text, exceptions, model objects or stdout capture cross this IPC.
        fields = getattr(record, "audit_fields", {})
        fields = {key: value for key, value in fields.items()
                  if key in {"stage", "outcome", "duration_ms", "page_count", "rotation_degrees",
                             "deskew_millidegrees", "input_chars", "orientation_candidates",
                             "orientation_quality_milli", "classifier_confidence_milli", "ocr_page_count", "item_count", "validation_error_count"}}
        self.pipe.send_bytes(_encoded({"kind": "STAGE", "event": record.args[0], "fields": fields}, _PACKET))


def _silence_worker_output() -> None:
    with open(os.devnull, "w") as silent:
        os.dup2(silent.fileno(), 1)
        os.dup2(silent.fileno(), 2)
    if sys.platform == "win32":
        import ctypes
        from ctypes import wintypes
        import msvcrt
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.SetStdHandle.argtypes = [wintypes.DWORD, wintypes.HANDLE]
        kernel.SetStdHandle.restype = wintypes.BOOL
        # Native stdout/stderr may be separate handles, not CRT fd1/fd2 aliases.
        for stream, descriptor in ((-11, 1), (-12, 2)):
            if not kernel.SetStdHandle(stream, msvcrt.get_osfhandle(descriptor)):
                raise RuntimeError("OCR native output isolation unproven")


def _compute(pipe, settings: Settings, factory, owner_pid=None) -> None:
    try:
        if owner_pid is not None:
            # Install before native initialization and prove the owner survived
            # the fork/spawn-to-prctl window. No unguarded Linux fallback.
            import ctypes
            import signal
            libc = ctypes.CDLL(None, use_errno=True)
            if (os.getppid() != owner_pid or libc.prctl(1, signal.SIGKILL, 0, 0, 0) != 0
                    or os.getppid() != owner_pid):
                return
        # Native/provider diagnostics must not escape into shared service logs.
        # Approved stage metadata is relayed explicitly through the private pipe.
        _silence_worker_output()
        # No pipeline construction or command consumption before parent Job proof.
        pipe.send_bytes(b'{"kind":"BARRIER"}')
        if not pipe.poll(10) or pipe.recv_bytes(_COMMAND_LIMIT) != b"JOB_ASSIGNED":
            return
        logger = logging.getLogger("audit_ai")
        logger.handlers = [_StageRelay(pipe)]
        logger.setLevel(logging.INFO)
        logger.propagate = False
        port = factory(settings)
        while True:
            raw = pipe.recv_bytes(_COMMAND_LIMIT)
            command = json.loads(raw)
            if command == {"kind": "STOP"}:
                return
            if not isinstance(command, dict) or set(command) != {"kind", "path"} or command["kind"] != "EXTRACT" or not isinstance(command["path"], str):
                return
            try:
                result = port.extract(Path(command["path"]))
                if not isinstance(result, OcrExtraction) or len(result.pages) > 1024 or len(result.text) > _RESULT_LIMIT:
                    raise OcrPredictionInvalid("OCR worker result exceeds bound")
                if sum(len(page.text) for page in result.pages) > _RESULT_LIMIT:
                    raise OcrPredictionInvalid("OCR worker pages exceed bound")
                payload = _encoded(asdict(result), _RESULT_LIMIT)
                pipe.send_bytes(_encoded({"kind": "RESULT", "bytes": len(payload)}, _PACKET))
                for offset in range(0, len(payload), _PACKET):
                    pipe.send_bytes(payload[offset:offset + _PACKET])
            except OcrPredictionInvalid:
                pipe.send_bytes(b'{"kind":"ERROR","code":"INVALID"}')
            except Exception:
                pipe.send_bytes(b'{"kind":"ERROR","code":"UNAVAILABLE"}')
    except BaseException:
        # No raw provider traceback/message/path/body crosses the process boundary.
        return
    finally:
        pipe.close()


def _result(payload: bytes) -> OcrExtraction:
    value = json.loads(payload)
    if not isinstance(value, dict) or set(value) != {"text", "confidence", "pages", "engine", "duration_ms"}:
        raise OcrPredictionInvalid("OCR worker response schema invalid")
    if not isinstance(value["pages"], list) or len(value["pages"]) > 1024 or not isinstance(value["text"], str):
        raise OcrPredictionInvalid("OCR worker response shape invalid")
    if not isinstance(value["duration_ms"], int) or isinstance(value["duration_ms"], bool):
        raise OcrPredictionInvalid("OCR worker duration invalid")
    pages = []
    for page in value["pages"]:
        if not isinstance(page, dict) or not {"page_num", "text", "confidence"} <= set(page) or set(page)-{"page_num", "text", "confidence", "regions", "width", "height"} or not isinstance(page["text"], str) or not isinstance(page["page_num"], int) or isinstance(page["page_num"], bool):
            raise OcrPredictionInvalid("OCR worker page invalid")
        from audit_ai.ocr.geometry import OcrRegion
        regions=page.get("regions",[])
        if not isinstance(regions,list) or len(regions)>10000:
            raise OcrPredictionInvalid("OCR worker regions invalid")
        pages.append(OcrPage(**{**page,"regions":tuple(OcrRegion(**r) for r in regions)}))
    return OcrExtraction(**{**value, "pages": tuple(pages)})


class ProcessOcrAdapter(OcrPort):
    """One owner, one spawn child, one effective slot, one bounded request at a time.

    Calls remain synchronous for the existing file-owning service worker. IPC and
    joins never run on FastAPI's event loop. A failed dispatched call poisons this
    adapter until service replacement; no automatic retry/reload of uncertain work.
    """

    def __init__(self, settings: Settings, *, timeout_seconds: float = 1800,
                 factory=_paddle, job_factory=None) -> None:
        if not math.isfinite(timeout_seconds) or timeout_seconds <= 0:
            raise ValueError("OCR process timeout must be finite and positive")
        # Do not copy model API credentials/configuration into the OCR child.
        self._settings = Settings(ocr_cpu_threads=settings.ocr_cpu_threads,
            ocr_enable_mkldnn=settings.ocr_enable_mkldnn, max_upload_bytes=settings.max_upload_bytes,
            ocr_orientation_enabled=settings.ocr_orientation_enabled,
            ocr_orientation_model_dir=settings.ocr_orientation_model_dir,
            ocr_fast_enabled=settings.ocr_fast_enabled,ocr_fast_model_dir=settings.ocr_fast_model_dir,
            ocr_temp_root=settings.ocr_temp_root)
        self._timeout, self._factory = timeout_seconds, factory
        self._job_factory = job_factory or (WindowsChildJob if sys.platform == 'win32' else LinuxChildOwner)
        self._slot, self._state = threading.Lock(), threading.RLock()
        self._child = self._pipe = self._job = None
        self._closed = self._broken = False
        # Linux PR_SET_PDEATHSIG tracks the spawning OS thread. ASGI pool
        # threads retire between requests, so they cannot own the persistent
        # child. This one thread lives until close; no inference runs in it.
        self._starter = (ThreadPoolExecutor(max_workers=1, thread_name_prefix="ocr-process-owner")
                         if sys.platform == "linux" else None)

    def _receive(self, pipe, deadline: float, maximum: int = _PACKET) -> bytes:
        left = deadline - time.monotonic()
        if left <= 0 or not pipe.poll(left):
            raise OcrWorkerOutcomeUnknown("OCR worker deadline outcome unknown; not retried")
        return pipe.recv_bytes(maximum)

    def _start(self, deadline: float):
        if self._starter is not None:
            with self._state:
                if self._closed or self._broken:
                    raise OcrRuntimeUnavailable("OCR worker is closed or outcome unknown")
                startup = self._starter.submit(self._start_owned, deadline)
            return startup.result()
        return self._start_owned(deadline)

    def _start_owned(self, deadline: float):
        with self._state:
            if self._closed or self._broken:
                raise OcrRuntimeUnavailable("OCR worker is closed or outcome unknown")
            if self._child is not None:
                if not self._child.is_alive():
                    self._broken = True
                    raise OcrWorkerOutcomeUnknown("OCR worker exited; not retried")
                return self._pipe
            self._job = self._job_factory()
            ctx = multiprocessing.get_context("spawn")
            parent, child = ctx.Pipe(duplex=True)
            self._pipe = parent
            self._child = ctx.Process(target=_compute, args=(child, self._settings, self._factory,
                os.getpid() if sys.platform == 'linux' else None), name="audit-ocr-compute", daemon=True)
            try:
                self._child.start()
                child.close()
                barrier = json.loads(self._receive(parent, min(deadline, time.monotonic() + 10)))
                if barrier != {"kind": "BARRIER"}:
                    raise OcrRuntimeUnavailable("OCR child startup unproven")
                self._job.assign(self._child)
                parent.send_bytes(b"JOB_ASSIGNED")
                return parent
            except BaseException:
                child.close()
                self._broken = True
                self._stop()
                raise

    def _stop(self) -> None:
        with self._state:
            child = self._child
            job_error = None
            if self._job is not None:
                try:
                    self._job.close()  # Terminates only members of our private Job.
                except Exception as error:
                    job_error = error
            if child is not None and child.pid is not None:
                try:
                    child.join(2)
                    if child.is_alive():
                        child.terminate()
                        child.join(5)
                    if child.is_alive():
                        raise OcrWorkerReapUnproven("OCR child exit unproven; retain upload")
                except Exception:
                    # A failed terminate/join/is_alive call is not evidence of
                    # death. Keep all own handles and poison the adapter, so the
                    # service quarantines input rather than deleting it.
                    raise OcrWorkerReapUnproven("OCR child exit unproven; retain upload") from None
            if self._pipe is not None:
                self._pipe.close()
            if child is not None and child.pid is not None:
                child.close()
            self._child = self._pipe = None
            if job_error is None:
                self._job = None
            if job_error is not None:
                raise OcrWorkerReapUnproven("OCR child Job cleanup unproven; retain upload") from None

    def extract(self, path: Path) -> OcrExtraction:
        deadline = time.monotonic() + self._timeout
        if not self._slot.acquire(timeout=self._timeout):
            raise OcrRuntimeUnavailable("OCR single slot deadline reached before dispatch")
        try:
            pipe = self._start(deadline)
            command = _encoded({"kind": "EXTRACT", "path": str(path)}, _COMMAND_LIMIT)
            if time.monotonic() >= deadline:
                raise OcrRuntimeUnavailable("OCR deadline reached before dispatch")
            pipe.send_bytes(command)
            stages = 0
            while True:
                message = json.loads(self._receive(pipe, deadline))
                if message.get("kind") == "STAGE":
                    stages += 1
                    if stages > _MAX_RELAYED_STAGES or message.get("event") not in _STAGES or not isinstance(message.get("fields"), dict):
                        raise OcrPredictionInvalid("OCR worker stage protocol invalid")
                    log_event(_LOGGER, message["event"], **message["fields"])
                elif message == {"kind": "ERROR", "code": "INVALID"}:
                    raise OcrPredictionInvalid("OCR worker prediction invalid")
                elif message == {"kind": "ERROR", "code": "UNAVAILABLE"}:
                    raise OcrRuntimeUnavailable("OCR worker runtime unavailable")
                elif message.get("kind") == "RESULT" and set(message) == {"kind", "bytes"}:
                    size = message["bytes"]
                    if not isinstance(size, int) or isinstance(size, bool) or not 0 < size <= _RESULT_LIMIT:
                        raise OcrPredictionInvalid("OCR worker response exceeds bound")
                    payload = bytearray()
                    while len(payload) < size:
                        chunk = self._receive(pipe, deadline)
                        if not chunk or len(payload) + len(chunk) > size:
                            raise OcrPredictionInvalid("OCR worker chunk invalid")
                        payload.extend(chunk)
                    return _result(payload)
                else:
                    raise OcrPredictionInvalid("OCR worker protocol invalid")
        except BaseException as error:
            self._broken = True
            self._stop()
            if isinstance(error, (OcrRuntimeUnavailable, OcrPredictionInvalid)):
                raise
            raise OcrWorkerOutcomeUnknown("OCR worker outcome unknown; not retried") from None
        finally:
            self._slot.release()

    def close(self) -> None:
        try:
            with self._state:
                self._closed = True
                self._stop()
        finally:
            # Release the state lock before joining the owner: a submitted
            # startup may still need it to observe _closed and stop safely.
            if self._starter is not None:
                self._starter.shutdown(wait=True, cancel_futures=True)


class LinuxChildOwner:
    """Own only the exact multiprocessing child; prctl guards abrupt parent exit.

    This OCR worker does not spawn subprocesses. Native libraries use threads;
    unlike Windows Job containment this does not promise descendant containment.
    """
    def __init__(self):
        if sys.platform != 'linux':
            raise OcrRuntimeUnavailable('Linux process isolation is unavailable')
        self.child = None

    def assign(self, child):
        if child.pid is None or not child.is_alive():
            raise OcrRuntimeUnavailable('OCR child ownership unproven')
        self.child = child

    def close(self):
        if self.child is not None and self.child.is_alive():
            self.child.kill()


class ProcessOcrPool(OcrPort):
    """Bounded independent pipelines; never shares a Paddle instance across threads.

    An uncertain/failed slot is not replaced or replayed. Existing requests drain,
    new requests stop until service replacement. close() waits for file owners.
    """

    def __init__(self, settings: Settings, *, worker_factory=ProcessOcrAdapter,
                 wait_seconds: float = 1800, resource_reader=None) -> None:
        if not math.isfinite(wait_seconds) or wait_seconds <= 0:
            raise ValueError("OCR pool wait must be finite and positive")
        from audit_ai.ocr.resources import capacity, linux_resources
        resources = (resource_reader or linux_resources)()
        self.worker_count = capacity(settings.ocr_worker_processes, *resources,
                                     settings.ocr_memory_budget_mb, settings.ocr_memory_reserve_mb,
                                     settings.ocr_worker_memory_mb)
        self._workers = []
        try:
            for _ in range(self.worker_count):
                self._workers.append(worker_factory(settings))
        except BaseException:
            for worker in self._workers:
                worker.close()
            raise
        self._available = list(self._workers)
        self._condition = threading.Condition()
        self._closed = self._broken = False
        self._active = 0
        self._wait_seconds = wait_seconds
        self._tickets = deque()
        log_event(_LOGGER,"OCR_POOL_CONFIGURED",stage="OCR",outcome="READY",
                  worker_count=self.worker_count,requested_workers=settings.ocr_worker_processes,
                  memory_budget_mb=settings.ocr_memory_budget_mb)

    def extract(self, path: Path) -> OcrExtraction:
        deadline = time.monotonic() + self._wait_seconds
        with self._condition:
            ticket=object();self._tickets.append(ticket)
            try:
                while not self._closed and not self._broken:
                    if self._tickets[0] is ticket and self._available:
                        break
                    left = deadline - time.monotonic()
                    if left <= 0:
                        raise OcrRuntimeUnavailable("OCR pool capacity deadline before dispatch")
                    self._condition.wait(left)
                if self._closed or self._broken:
                    raise OcrRuntimeUnavailable("OCR pool is closed or outcome unknown")
                worker = self._available.pop(0)
                self._active += 1
            finally:
                self._tickets.remove(ticket);self._condition.notify_all()
        try:
            return worker.extract(path)
        except BaseException:
            with self._condition:
                self._broken = True
                self._condition.notify_all()
            raise
        finally:
            with self._condition:
                self._active -= 1
                self._available.append(worker)
                self._condition.notify_all()

    def close(self) -> None:
        with self._condition:
            self._closed = True
            self._condition.notify_all()
            while self._active:
                self._condition.wait()
        first_error = None
        for worker in self._workers:
            try:
                worker.close()
            except BaseException as error:
                if first_error is None:
                    first_error = error
        if first_error is not None:
            raise first_error


def create_ocr_port(settings: Settings) -> OcrPort:
    # Linux29 retains its independently verified legacy runtime/configuration.
    # No unproved cross-platform parent-death fallback, no Windows in-process retry.
    if settings.ocr_worker_processes > 1:
        return ProcessOcrPool(settings)
    return ProcessOcrAdapter(settings) if (sys.platform == "win32" or settings.ocr_process_isolation) else PaddleOcrAdapter(settings)
