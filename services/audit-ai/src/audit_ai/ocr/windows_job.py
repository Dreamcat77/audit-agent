"""Private Windows child lifetime boundary; no process enumeration or PID lookup."""
from __future__ import annotations

import ctypes as C
from ctypes import wintypes as W
import sys
import time


class _BasicLimits(C.Structure):
    _fields_ = [
        ("PerProcessUserTimeLimit", C.c_int64), ("PerJobUserTimeLimit", C.c_int64),
        ("LimitFlags", W.DWORD), ("MinimumWorkingSetSize", C.c_size_t),
        ("MaximumWorkingSetSize", C.c_size_t), ("ActiveProcessLimit", W.DWORD),
        ("Affinity", C.c_size_t), ("PriorityClass", W.DWORD), ("SchedulingClass", W.DWORD),
    ]


class _IoCounters(C.Structure):
    _fields_ = [(name, C.c_uint64) for name in (
        "ReadOperationCount", "WriteOperationCount", "OtherOperationCount",
        "ReadTransferCount", "WriteTransferCount", "OtherTransferCount",
    )]


class _ExtendedLimits(C.Structure):
    _fields_ = [
        ("BasicLimitInformation", _BasicLimits), ("IoInfo", _IoCounters),
        ("ProcessMemoryLimit", C.c_size_t), ("JobMemoryLimit", C.c_size_t),
        ("PeakProcessMemoryUsed", C.c_size_t), ("PeakJobMemoryUsed", C.c_size_t),
    ]


class _Accounting(C.Structure):
    _fields_ = [(name, C.c_int64) for name in (
        "TotalUserTime", "TotalKernelTime", "ThisPeriodTotalUserTime", "ThisPeriodTotalKernelTime",
    )] + [(name, W.DWORD) for name in (
        "TotalPageFaultCount", "TotalProcesses", "ActiveProcesses", "TotalTerminatedProcesses",
    )]


class WindowsChildJob:
    """Only accepts the retained handle of our own multiprocessing spawn child.

    No breakaway flags, no inheritable/named Job handle. Parent OS-handle close
    also kills descendants, even if their Python/native thread cannot cooperate.
    A child must wait at a no-compute barrier until assign() succeeds.
    """

    def __init__(self) -> None:
        if sys.platform != "win32":
            raise RuntimeError("Windows OCR lifetime boundary unavailable")
        self._kernel = C.WinDLL("kernel32", use_last_error=True)
        signatures = {
            "CreateJobObjectW": ([C.c_void_p, W.LPCWSTR], W.HANDLE),
            "SetHandleInformation": ([W.HANDLE, W.DWORD, W.DWORD], W.BOOL),
            "SetInformationJobObject": ([W.HANDLE, C.c_int, C.c_void_p, W.DWORD], W.BOOL),
            "QueryInformationJobObject": ([W.HANDLE, C.c_int, C.c_void_p, W.DWORD, C.c_void_p], W.BOOL),
            "AssignProcessToJobObject": ([W.HANDLE, W.HANDLE], W.BOOL),
            "IsProcessInJob": ([W.HANDLE, W.HANDLE, C.POINTER(W.BOOL)], W.BOOL),
            "TerminateJobObject": ([W.HANDLE, W.UINT], W.BOOL),
            "CloseHandle": ([W.HANDLE], W.BOOL),
        }
        for name, (args, result) in signatures.items():
            function = getattr(self._kernel, name)
            function.argtypes, function.restype = args, result
        self._handle = self._kernel.CreateJobObjectW(None, None)
        if not self._handle:
            raise RuntimeError("Windows OCR Job creation unproven")
        try:
            info = _ExtendedLimits()
            info.BasicLimitInformation.LimitFlags = 0x2000  # KILL_ON_JOB_CLOSE
            if not self._kernel.SetHandleInformation(self._handle, 1, 0):
                raise RuntimeError("Windows OCR Job inheritance unproven")
            if not self._kernel.SetInformationJobObject(self._handle, 9, C.byref(info), C.sizeof(info)):
                raise RuntimeError("Windows OCR Job limit unproven")
            read = _ExtendedLimits()
            if not self._kernel.QueryInformationJobObject(self._handle, 9, C.byref(read), C.sizeof(read), None):
                raise RuntimeError("Windows OCR Job verification unproven")
            if read.BasicLimitInformation.LimitFlags != 0x2000:
                raise RuntimeError("Windows OCR Job limit mismatch")
        except BaseException:
            self.close()
            raise

    def assign(self, child) -> None:
        # sentinel on Windows is multiprocessing's retained own-process HANDLE,
        # not a PID that could be reused or resolve to another application's child.
        handle = int(child.sentinel)
        if not self._kernel.AssignProcessToJobObject(self._handle, handle):
            raise RuntimeError("Windows OCR child assignment unproven")
        belongs = W.BOOL()
        if not self._kernel.IsProcessInJob(handle, self._handle, C.byref(belongs)) or not belongs.value:
            raise RuntimeError("Windows OCR child membership unproven")

    def close(self) -> None:
        if self._handle:
            # Close-on-owner-death is the OS fallback. During normal cleanup,
            # prove the WHOLE Job empty before permitting upload removal, not
            # just the main child (native descendants also inherit this Job).
            if not self._kernel.TerminateJobObject(self._handle, 1):
                raise RuntimeError("Windows OCR Job termination unproven")
            deadline = time.monotonic() + 5
            while True:
                accounting = _Accounting()
                if not self._kernel.QueryInformationJobObject(self._handle, 1, C.byref(accounting), C.sizeof(accounting), None):
                    raise RuntimeError("Windows OCR Job accounting unproven")
                if accounting.ActiveProcesses == 0:
                    break
                if time.monotonic() >= deadline:
                    raise RuntimeError("Windows OCR Job still active")
                time.sleep(.01)
            if not self._kernel.CloseHandle(self._handle):
                raise RuntimeError("Windows OCR Job close unproven")
            self._handle = None
