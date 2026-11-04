import pytest

from audit_ai.model.chunker import BoundedChunker, ModelInputTooLarge


def test_chunker_prefers_deterministic_text_boundaries() -> None:
    chunker = BoundedChunker(max_chars=11, max_chunks=3)

    assert chunker.split("alpha beta\ngamma delta") == (
        "alpha beta",
        "gamma delta",
    )


def test_chunker_rejects_input_beyond_bounded_chunk_count() -> None:
    chunker = BoundedChunker(max_chars=5, max_chunks=2)

    with pytest.raises(ModelInputTooLarge):
        chunker.split("aaaaa bbbbb ccccc")


def test_chunker_never_emits_empty_chunks() -> None:
    chunker = BoundedChunker(max_chars=8, max_chunks=2)

    assert chunker.split("  \n\t  ") == ()


def test_chunker_exact_spans_cover_source_without_loss_or_overlap() -> None:
    source = "alpha beta\ngamma delta"
    chunker = BoundedChunker(max_chars=11, max_chunks=3)

    spans = chunker.split_spans(source)

    assert spans[0][0] == 0
    assert spans[-1][1] == len(source)
    assert all(left[1] == right[0] for left, right in zip(spans, spans[1:]))
    assert "".join(source[start:end] for start, end in spans) == source
    assert all(end - start <= 11 for start, end in spans)
