"""Framework-free OCR port and immutable result types."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Protocol
from audit_ai.ocr.geometry import OcrRegion


def _confidence(value: float) -> float:
    if isinstance(value, bool) or not 0.0 <= value <= 1.0:
        raise ValueError("confidence must be between zero and one")
    return float(value)


@dataclass(frozen=True, slots=True)
class OcrPage:
    page_num: int
    text: str
    confidence: float
    regions: tuple[OcrRegion, ...] = ()
    width: int = 0
    height: int = 0

    def __post_init__(self) -> None:
        if self.page_num < 1:
            raise ValueError("page_num must be positive")
        if self.text is None:
            raise ValueError("text must not be None")
        object.__setattr__(self, "confidence", _confidence(self.confidence))
        object.__setattr__(self, "regions", tuple(self.regions))
        if self.width < 0 or self.height < 0 or len(self.regions) > 10000:
            raise ValueError("OCR geometry bound invalid")
        if any(region.end > len(self.text) for region in self.regions):
            raise ValueError("OCR region outside text")


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
