"""Server-owned evidence segmentation for contract semantic analysis."""

from __future__ import annotations

from dataclasses import dataclass, field
from types import MappingProxyType
from typing import Callable, Mapping, Sequence
import re
import unicodedata

from audit_ai.model.chunker import BoundedChunker, ModelInputTooLarge


_MASK_TOKEN = "[确定性数值字段已移除]"
_CATEGORY_PREFIXES = {
    "CONTRACT_BODY": "C",
    "BID_RESPONSE": "B",
    "TENDER_DOCUMENT": "T",
    "AWARD_NOTICE": "A",
    "SUPPLEMENT": "S",
}


@dataclass(frozen=True, slots=True)
class EvidenceSource:
    file_id: str
    material_category: str
    text: str

    def __post_init__(self) -> None:
        if not self.file_id.strip():
            raise ValueError("evidence source file_id must not be blank")
        if not self.material_category.strip():
            raise ValueError("evidence source material_category must not be blank")


@dataclass(frozen=True, slots=True)
class EvidenceSegment:
    evidence_id: str
    file_id: str
    material_category: str
    source_start: int
    source_end: int
    exact_text: str
    citable: bool

    def __post_init__(self) -> None:
        if self.source_start < 0 or self.source_end < self.source_start:
            raise ValueError("evidence source span must be ordered and non-negative")


@dataclass(frozen=True, slots=True)
class EvidenceCatalog:
    segments: tuple[EvidenceSegment, ...]
    by_id: Mapping[str, EvidenceSegment]
    # Unknown for legacy/manual catalogs. Never infer completeness from the
    # largest observed end offset: an unobserved tail could still exist.
    source_lengths: Mapping[str, int]

    def __init__(self, segments: Sequence[EvidenceSegment], *,
                 source_lengths: Mapping[str, int] | None = None) -> None:
        immutable_segments = tuple(segments)
        by_id = {segment.evidence_id: segment for segment in immutable_segments}
        if len(by_id) != len(immutable_segments):
            raise ValueError("evidence IDs must be request-wide unique")
        object.__setattr__(self, "segments", immutable_segments)
        object.__setattr__(self, "by_id", MappingProxyType(by_id))
        lengths = dict(source_lengths or {})
        if any(not isinstance(key, str) or not key.strip()
               or type(value) is not int or value < 0 for key, value in lengths.items()):
            raise ValueError("source lengths must identify complete non-negative source bounds")
        object.__setattr__(self, "source_lengths", MappingProxyType(lengths))


@dataclass(frozen=True, slots=True)
class EvidenceBatch:
    source_text: str = field(repr=False)
    evidence_ids: tuple[str, ...]


def plan_evidence_batch_members(
    catalog: EvidenceCatalog,
    *,
    max_chars: int,
    max_batches: int,
) -> tuple[EvidenceBatch, ...]:
    """Regroup complete catalog entries without changing their IDs or spans.

    Bounds include identity headers and separators. An entry that cannot fit
    fails closed: splitting it here would change the established evidence
    identity. This plan is request-local and provides no durable checkpoints.
    """

    if max_chars <= 0 or max_batches <= 0:
        raise ValueError("evidence batch bounds must be positive")
    batches: list[EvidenceBatch] = []
    current = ""
    current_ids: list[str] = []
    for segment in catalog.segments:
        block = (
            f"[evidenceId={segment.evidence_id};fileId={segment.file_id};"
            f"category={segment.material_category};"
            f"citable={'true' if segment.citable else 'false'}]"
            f"\n{segment.exact_text}"
        )
        if len(block) > max_chars:
            raise ModelInputTooLarge(
                "evidence identity header exceeds configured chunk bound"
            )
        candidate = f"{current}\n\n{block}" if current else block
        if len(candidate) <= max_chars:
            current = candidate
            current_ids.append(segment.evidence_id)
            continue
        batches.append(EvidenceBatch(current, tuple(current_ids)))
        if len(batches) >= max_batches:
            raise ModelInputTooLarge("model input exceeds configured chunk bound")
        current = block
        current_ids = [segment.evidence_id]
    if current:
        batches.append(EvidenceBatch(current, tuple(current_ids)))
    return tuple(batches)


def plan_evidence_batches(
    catalog: EvidenceCatalog, *, max_chars: int, max_batches: int,
) -> tuple[str, ...]:
    """Compatibility view; authority stays with the structured batch members."""
    return tuple(batch.source_text for batch in plan_evidence_batch_members(
        catalog, max_chars=max_chars, max_batches=max_batches
    ))


def normalize_visible_text(value: str) -> str:
    """Normalize presentation whitespace without changing business characters."""

    return re.sub(r"\s+", "", unicodedata.normalize("NFKC", value))


def _natural_source_spans(source: str) -> tuple[tuple[int, int], ...]:
    """Partition source exactly around masked values before bounded splitting."""

    if not source:
        return ()

    boundaries = {0, len(source)}
    for match in re.finditer(re.escape(_MASK_TOKEN), source):
        boundaries.add(match.start())
        boundaries.add(match.end())
    ordered = sorted(boundaries)
    return tuple(
        (start, end)
        for start, end in zip(ordered, ordered[1:])
        if start < end
    )


def _bounded_source_spans(source: str, max_chars: int) -> tuple[tuple[int, int], ...]:
    bounded: list[tuple[int, int]] = []
    for natural_start, natural_end in _natural_source_spans(source):
        natural_text = source[natural_start:natural_end]
        local_spans = BoundedChunker(
            max_chars=max_chars,
            max_chunks=max(1, len(natural_text) + 1),
        ).split_spans(natural_text)
        bounded.extend(
            (natural_start + local_start, natural_start + local_end)
            for local_start, local_end in local_spans
        )
    return tuple(bounded)


def segment_evidence_sources(
    sources: Sequence[EvidenceSource],
    *,
    max_segment_chars: int | Callable[[EvidenceSource], int],
) -> EvidenceCatalog:
    """Assign deterministic IDs to every visible source segment in one request."""

    if isinstance(max_segment_chars, int) and max_segment_chars <= 0:
        raise ValueError("max_segment_chars must be positive")

    segments: list[EvidenceSegment] = []
    source_lengths: dict[str, int] = {}
    seen_files: set[str] = set()
    colliding_files: set[str] = set()
    global_index = 0
    for source in sources:
        if source.file_id in seen_files:
            colliding_files.add(source.file_id)
            source_lengths.pop(source.file_id, None)
        elif source.file_id not in colliding_files:
            source_lengths[source.file_id] = len(source.text)
        seen_files.add(source.file_id)
        source_limit = (
            max_segment_chars(source)
            if callable(max_segment_chars)
            else max_segment_chars
        )
        if source_limit <= 0:
            raise ValueError("max_segment_chars must be positive")
        source_spans = _bounded_source_spans(source.text, source_limit)
        prefix = _CATEGORY_PREFIXES.get(source.material_category, "M")
        for source_start, source_end in source_spans:
            exact_text = source.text[source_start:source_end]
            global_index += 1
            segments.append(
                EvidenceSegment(
                    evidence_id=f"{prefix}:S{global_index}",
                    file_id=source.file_id,
                    material_category=source.material_category,
                    source_start=source_start,
                    source_end=source_end,
                    exact_text=exact_text,
                    citable=(
                        bool(normalize_visible_text(exact_text))
                        and _MASK_TOKEN not in exact_text
                    ),
                )
            )
    return EvidenceCatalog(segments, source_lengths=source_lengths)
