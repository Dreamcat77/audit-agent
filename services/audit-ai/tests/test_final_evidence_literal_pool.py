"""Lossless source factoring, not natural-language summarization."""
import copy
import pytest

from audit_ai.errors import AuditAiError
from audit_ai.model.semantic_final_evidence import pack_view, unpack_view


def fixture():
    line = "同一冻结材料的重复原文，甲乙双方的完整义务必须保留。\n" * 4
    return {"protocol": "contract-final-frozen-evidence-v1", "files": [["file", "CONTRACT_BODY"]],
        "evidenceColumns": ["evidenceId", "fileIndex", "sourceStart", "sourceEnd", "citable", "exactText"],
        "evidence": [[f"C:S{i+1}", 0, i*len(line), (i+1)*len(line), True, line] for i in range(3)],
        "coverageColumns": ["fileId", "materialCategory", "sourceLength", "textSha256"], "coverage": []}


def test_literal_pool_independent_inverse_retains_every_character_id_range_and_order():
    view = fixture(); packed = pack_view(view)
    assert "sourceTextPool" in packed
    assert unpack_view(packed) == view
    # Independent inverse does not call production unpack_view.
    restored = [[*row[:-1], "".join(packed["sourceTextPool"][v] if type(v) is int else v for v in row[-1])]
                for row in packed["evidence"]]
    assert restored == view["evidence"]
    assert pack_view(view) == packed and view == fixture()


@pytest.mark.parametrize("kind", ["unknown_index", "bool_index", "unused_pool", "duplicate_pool", "missing_part"])
def test_invalid_pool_cannot_hide_source_or_append_unconsumed_poison(kind):
    packed = pack_view(fixture())
    if kind == "unknown_index": packed["evidence"][0][-1] = [999999]
    if kind == "bool_index": packed["evidence"][0][-1] = [True]
    if kind == "unused_pool": packed["sourceTextPool"].append("未消费的外部文字。")
    if kind == "duplicate_pool": packed["sourceTextPool"].append(packed["sourceTextPool"][0])
    if kind == "missing_part": packed["evidence"][0][-1] = []
    with pytest.raises(AuditAiError): unpack_view(packed)
