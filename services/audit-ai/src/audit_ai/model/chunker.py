"""Deterministic bounded text chunking."""

from __future__ import annotations


class ModelInputTooLarge(ValueError):
    """The normalized source cannot fit within the configured chunk bound."""


class BoundedChunker:
    def __init__(self, *, max_chars: int, max_chunks: int) -> None:
        if max_chars <= 0 or max_chunks <= 0:
            raise ValueError("chunk bounds must be positive")
        self._max_chars = max_chars
        self._max_chunks = max_chunks

    def split(self, source: str) -> tuple[str, ...]:
        remaining = source.strip()
        chunks: list[str] = []
        while remaining:
            if len(chunks) >= self._max_chunks:
                raise ModelInputTooLarge("model input exceeds configured chunk bound")
            if len(remaining) <= self._max_chars:
                chunks.append(remaining)
                break
            cut = self._boundary(remaining)
            chunk = remaining[:cut].strip()
            if not chunk:
                cut = self._max_chars
                chunk = remaining[:cut]
            chunks.append(chunk)
            remaining = remaining[cut:].lstrip()
        return tuple(chunks)

    def split_spans(self, source: str) -> tuple[tuple[int, int], ...]:
        """Return an exact, ordered partition of ``source`` within the bound."""

        if not source:
            return ()

        spans: list[tuple[int, int]] = []
        start = 0
        while start < len(source):
            if len(spans) >= self._max_chunks:
                raise ModelInputTooLarge("model input exceeds configured chunk bound")
            remaining = source[start:]
            if len(remaining) <= self._max_chars:
                end = len(source)
            else:
                cut = self._span_boundary(remaining)
                if cut <= 0:
                    cut = self._max_chars
                end = start + cut
            spans.append((start, end))
            start = end
        return tuple(spans)

    def _span_boundary(self, source: str) -> int:
        punctuation = max(
            source.rfind(token, 0, self._max_chars)
            for token in "。！？；!?;"
        )
        if punctuation >= max(0, self._max_chars // 2 - 1):
            return punctuation + 1
        return self._boundary(source)

    def _boundary(self, source: str) -> int:
        newline = source.rfind("\n", 0, self._max_chars + 1)
        space = source.rfind(" ", 0, self._max_chars + 1)
        boundary = max(newline, space)
        if boundary >= max(1, self._max_chars // 2):
            return boundary
        return self._max_chars
