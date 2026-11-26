"""Conservative OCR admission limits; counts only, no credentials or business data."""
from __future__ import annotations

import math
import os
from pathlib import Path, PurePosixPath

MIB = 1024 * 1024


def capacity(requested: int, cpu: float | None, available: int | None,
             budget_mb: int, reserve_mb: int, worker_mb: int) -> int:
    if cpu is None or available is None:
        return 1  # Unknown resource accounting never authorizes expansion.
    return max(1, min(requested, int(cpu), budget_mb // worker_mb,
                      max(0, available // MIB - reserve_mb) // worker_mb))


def _unescape_mount_path(value: str) -> str:
    return value.replace("\\040", " ").replace("\\011", "\t").replace("\\012", "\n").replace("\\134", "\\")


def _v1_membership(lines: list[str], controller: str) -> str | None:
    matches = []
    for line in lines:
        parts = line.split(":", 2)
        if len(parts) != 3:
            return None
        controllers = set(filter(None, parts[1].split(",")))
        if controller in controllers:
            matches.append(parts[2])
    return matches[0] if len(matches) == 1 else None


def _v1_mount(root: Path, controller: str) -> tuple[Path, PurePosixPath] | None:
    matches = []
    for line in (root / "proc/self/mountinfo").read_text().splitlines():
        fields = line.split(" - ", 1)
        if len(fields) != 2:
            return None
        before, after = fields[0].split(), fields[1].split()
        if len(before) < 6 or len(after) < 3 or after[0] != "cgroup":
            continue
        options = set(before[5].split(",")) | set(after[2].split(","))
        if controller in options:
            mount_root = PurePosixPath(_unescape_mount_path(before[3]))
            mount_point = Path(_unescape_mount_path(before[4]))
            matches.append((mount_point, str(mount_root)))
    if len(matches) != 1:
        return None
    mount_point, mount_root = matches[0]
    return root / Path(*mount_point.parts[1:]), mount_root


def _v1_controller_path(root: Path, lines: list[str], controller: str) -> tuple[Path, Path] | None:
    membership = _v1_membership(lines, controller)
    mount = _v1_mount(root, controller)
    if membership is None or mount is None:
        return None
    mount_point, mount_root = mount
    group = PurePosixPath(membership)
    hierarchy = mount_root
    if group == hierarchy:
        relative = PurePosixPath(".")
    elif hierarchy == PurePosixPath("/") and group.is_absolute():
        relative = group.relative_to("/")
    elif group.is_absolute() and group.is_relative_to(hierarchy):
        relative = group.relative_to(hierarchy)
    else:
        return None
    candidate = (mount_point / Path(*relative.parts)).resolve()
    base = mount_point.resolve()
    if not candidate.is_dir() or not candidate.is_relative_to(base):
        return None
    return base, candidate


def _v1_resources(root: Path, lines: list[str], cpu: float,
                  available: int) -> tuple[float | None, int | None]:
    cpu_paths = _v1_controller_path(root, lines, "cpu")
    memory_paths = _v1_controller_path(root, lines, "memory")
    if cpu_paths is None or memory_paths is None:
        return None, None
    cpu_base, cpu_current = cpu_paths
    memory_base, memory_current = memory_paths
    cpu_seen = memory_seen = False
    while True:
        quota_path = cpu_current / "cpu.cfs_quota_us"
        period_path = cpu_current / "cpu.cfs_period_us"
        try:
            quota_text = quota_path.read_text().strip()
        except FileNotFoundError:
            quota_text = None
        try:
            period_text = period_path.read_text().strip()
        except FileNotFoundError:
            period_text = None
        if (quota_text is None) != (period_text is None):
            return None, None
        if quota_text is not None:
            quota, period = int(quota_text), int(period_text)
            cpu_seen = True
            if period is None or period <= 0 or quota == 0 or quota < -1:
                return None, None
            if quota != -1:
                cpu = min(cpu, quota / period)
        if cpu_current == cpu_base:
            break
        cpu_current = cpu_current.parent
    while True:
        limit_path = memory_current / "memory.limit_in_bytes"
        usage_path = memory_current / "memory.usage_in_bytes"
        try:
            limit_text = limit_path.read_text().strip()
        except FileNotFoundError:
            limit_text = None
        try:
            used_text = usage_path.read_text().strip()
        except FileNotFoundError:
            used_text = None
        if (limit_text is None) != (used_text is None):
            return None, None
        if limit_text is not None:
            limit, used = int(limit_text), int(used_text)
            memory_seen = True
            if limit <= 0 or used < 0:
                return None, None
            if limit < (1 << 60):
                available = min(available, max(0, limit - used))
        if memory_current == memory_base:
            break
        memory_current = memory_current.parent
    return (cpu, available) if cpu_seen and memory_seen else (None, None)


def linux_resources(root: Path = Path('/')) -> tuple[float | None, int | None]:
    """Respect affinity and every visible cgroup-v1/v2 ancestor limit."""
    try:
        cpu = float(len(os.sched_getaffinity(0)))
        available = next(int(line.split()[1]) * 1024
                         for line in (root/'proc/meminfo').read_text().splitlines()
                         if line.startswith('MemAvailable:'))
        groups = (root/'proc/self/cgroup').read_text().splitlines()
        unified = next((line[3:] for line in groups if line.startswith('0::')), None)
        if unified is None:
            return _v1_resources(root, groups, cpu, available)
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
