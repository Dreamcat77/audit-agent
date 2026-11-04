"""Framework-free OCR port and immutable result types."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Protocol


def _confidence(value: float) -> float:
    if isinstance(value, bool) or not 0.0 <= value <= 1.0:
        raise ValueError("confidence must be between zero and one")
    return float(value)


@dataclass(frozen=True, slots=True)
class OcrPage:
    page_num: int
    text: str
    confidence: float

    def __post_init__(self) -> None:
        if self.page_num < 1:
            raise ValueError("page_num must be positive")
        if self.text is None:
            raise ValueError("text must not be None")
        object.__setattr__(self, "confidence", _confidence(self.confidence))


@dataclass(frozen=True, slots=True)
class OcrExtraction:
    text: str
    confidence: float
    pages: tuple[OcrPage, ...]
    engine: str
    duration_ms: int

    def __post_init__(self) -> None:
        if self.text is None:
            raise ValueError("text must not be None")
        object.__setattr__(self, "confidence", _confidence(self.confidence))
        object.__setattr__(self, "pages", tuple(self.pages))
        if not self.engine or not self.engine.strip():
            raise ValueError("engine must not be blank")
        if self.duration_ms < 0:
            raise ValueError("duration_ms must be non-negative")


class OcrPort(Protocol):
    def extract(self, path: Path) -> OcrExtraction:
        """Extract text from one bounded local image or PDF path."""
