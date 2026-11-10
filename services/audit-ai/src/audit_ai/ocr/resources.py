"""Conservative OCR admission limits; counts only, no credentials or business data."""
from __future__ import annotations

import math
import os
from pathlib import Path

MIB = 1024 * 1024


def capacity(requested: int, cpu: float | None, available: int | None,
             budget_mb: int, reserve_mb: int, worker_mb: int) -> int:
    if cpu is None or available is None:
        return 1  # Unknown resource accounting never authorizes expansion.
    return max(1, min(requested, int(cpu), budget_mb // worker_mb,
                      max(0, available // MIB - reserve_mb) // worker_mb))


def linux_resources(root: Path = Path('/')) -> tuple[float | None, int | None]:
    """Respect affinity AND every visible cgroup-v2 ancestor quota/headroom."""
    try:
        cpu = float(len(os.sched_getaffinity(0)))
        available = next(int(line.split()[1]) * 1024
                         for line in (root/'proc/meminfo').read_text().splitlines()
                         if line.startswith('MemAvailable:'))
        groups = (root/'proc/self/cgroup').read_text().splitlines()
        unified = next((line[3:] for line in groups if line.startswith('0::')), None)
        if unified is None:
            return None, None  # v1 or unknown mount layout: keep single-slot behavior.
        base = (root/'sys/fs/cgroup').resolve()
        candidate = base / unified.lstrip('/')
        # A namespaced cgroup root is reported as '/'. An unavailable or
        # escaping process path must not fall back to a less restricted parent.
        if not candidate.is_dir() or not candidate.resolve().is_relative_to(base):
            return None, None
        current = candidate
        cpu_seen = memory_seen = False
        while True:
            # Controllers can be absent on descendants and inherited from an
            # ancestor. Only ENOENT means absent; permission/read errors remain
            # unknown and must not authorize additional native processes.
            try:
                quota = (current/'cpu.max').read_text().split()
            except FileNotFoundError:
                quota = None
            if quota is not None:
                cpu_seen = True
                if len(quota) != 2 or int(quota[1]) <= 0:
                    return None, None
                if quota[0] != 'max':
                    value = int(quota[0]) / int(quota[1])
                    if not math.isfinite(value) or value <= 0:
                        return None, None
                    cpu = min(cpu, value)
            try:
                limit = (current/'memory.max').read_text().strip()
            except FileNotFoundError:
                limit = None
            if limit is not None:
                memory_seen = True
                if limit != 'max':
                    ceiling = int(limit)
                    used = int((current/'memory.current').read_text())
                    if ceiling < 0 or used < 0:
                        return None, None
                    available = min(available, max(0, ceiling - used))
            if current == base:
                break
            current = current.parent
        return (cpu, available) if cpu_seen and memory_seen else (None, None)
    except (OSError, ValueError, StopIteration, AttributeError, ZeroDivisionError):
        return None, None
