"""Offline abrupt-owner-exit fixture. Never imported by production code."""
import json
import os
from pathlib import Path
import sys
import threading
import time
import ctypes
from ctypes import wintypes as W

from audit_ai.config import Settings
from audit_ai.ocr.process_adapter import ProcessOcrAdapter, _silence_worker_output
from audit_ai.ocr.windows_job import WindowsChildJob
from test_ocr_process_adapter import synthetic


def main():
    root=Path(sys.argv[1]);path=root/"owner-input.png"
    if len(sys.argv)>2 and sys.argv[2] in ("native-direct","native-direct-unmuted"):
        kernel=ctypes.WinDLL("kernel32",use_last_error=True)
        kernel.GetStdHandle.argtypes=[W.DWORD];kernel.GetStdHandle.restype=W.HANDLE
        kernel.SetStdHandle.argtypes=[W.DWORD,W.HANDLE];kernel.SetStdHandle.restype=W.BOOL
        kernel.GetCurrentProcess.restype=W.HANDLE
        kernel.DuplicateHandle.argtypes=[W.HANDLE,W.HANDLE,W.HANDLE,ctypes.POINTER(W.HANDLE),W.DWORD,W.BOOL,W.DWORD];kernel.DuplicateHandle.restype=W.BOOL
        kernel.WriteFile.argtypes=[W.HANDLE,ctypes.c_void_p,W.DWORD,ctypes.POINTER(W.DWORD),ctypes.c_void_p];kernel.WriteFile.restype=W.BOOL
        # Win32 std handles need not alias CRT fd1/fd2. Preserve a separate
        # legitimate duplicate to expose a CRT-only redirection's stale handle.
        for stream in (-11,-12):
            duplicate=W.HANDLE();process=kernel.GetCurrentProcess()
            assert kernel.DuplicateHandle(process,kernel.GetStdHandle(stream),process,ctypes.byref(duplicate),0,False,2)
            assert kernel.SetStdHandle(stream,duplicate)
        if sys.argv[2]!="native-direct-unmuted":_silence_worker_output()
        canary=b"SYNTHETIC_PRIVATE_NATIVE_CANARY\n"
        for stream in (-11,-12):
            written=W.DWORD();assert kernel.WriteFile(kernel.GetStdHandle(stream),canary,len(canary),ctypes.byref(written),None)
        return
    class BarrierJob(WindowsChildJob):
        def assign(self, child):
            (root/"owner-ready.json").write_text(json.dumps({"childPid":child.pid}))
            time.sleep(30)
    barrier=len(sys.argv)>2 and sys.argv[2]=="preassignment"
    port=ProcessOcrAdapter(Settings(),factory=synthetic,job_factory=BarrierJob if barrier else WindowsChildJob)
    try:
        if len(sys.argv)>2 and sys.argv[2]=="canary":
            path.write_bytes(b"NATIVE_LOG");port.extract(path);port.close()
            print("SYNTHETIC_SAFE_DONE",flush=True)
            return
        if barrier:
            path.write_bytes(b"small")
            threading.Thread(target=port.extract,args=(path,),daemon=True).start()
            while not (root/"owner-ready.json").exists():time.sleep(.01)
            sys.stdin.readline();os._exit(0)
        path.write_bytes(b"small");port.extract(path)
        pid=port._child.pid
        path.write_bytes(b"HANG")
        threading.Thread(target=port.extract,args=(path,),daemon=True).start()
        deadline=time.monotonic()+5
        while not path.with_suffix(".entered").exists():
            if time.monotonic()>=deadline:raise RuntimeError("SYNTHETIC_COMPUTE_NOT_ENTERED")
            time.sleep(.01)
        (root/"owner-ready.json").write_text(json.dumps({"childPid":pid}))
        sys.stdin.readline()
        os._exit(0)  # No finally/close: the production Job must kill native child.
    finally:port.close()


if __name__=="__main__":main()
