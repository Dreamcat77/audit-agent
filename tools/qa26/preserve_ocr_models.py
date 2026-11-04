"""Carry installed offline OCR weights into a source-only test release."""
import hashlib
from pathlib import Path
import shutil


def preserve(previous: Path, release: Path) -> int:
    source = previous / "services/audit-ai/models"
    target = release / "services/audit-ai/models"
    if not source.is_dir():
        return 0
    shutil.copytree(source, target, dirs_exist_ok=True)
    copied = 0
    for path in source.rglob("*"):
        if path.is_file():
            destination = target / path.relative_to(source)
            assert hashlib.sha256(path.read_bytes()).digest() == hashlib.sha256(destination.read_bytes()).digest()
            copied += 1
    return copied
