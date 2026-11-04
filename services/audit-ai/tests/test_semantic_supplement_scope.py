"""Finite table-only scope gate regressions, not general attachment entailment.

All texts are synthetic. Passing this gate does not replace the strict draft,
evidence, monetary, risk, coverage or final-publication validators.
"""
import copy
from dataclasses import replace
import inspect

import pytest

from audit_ai.model.evidence import (
    EvidenceCatalog,
    EvidenceSegment,
    EvidenceSource,
    segment_evidence_sources,
)
from audit_ai.model.semantic_quality import QualityFailure, check_claims


SUPPLEMENT = (
    "数量说明。\n补充材料：交付要求。\n项目名称：合成设备。\n数量按所引清单核对。\n"
    "乙方应按约定交付，并提供安装服务说明。"
)


def catalog_for(text=SUPPLEMENT, *, limit=1000, role="SUPPLEMENT", file="supplement"):
    return segment_evidence_sources((EvidenceSource(file, role, text),), max_segment_chars=limit)


def observation(ids, *, description="仅核对所引补充材料的项目名称、数量及交付说明。"):
    return {"hints": [{"category": "CONTRACT_STRUCTURE", "attention": "INFO",
        "title": "附件结构完整", "description": description,
        "problemEvidenceIds": [], "comparisonEvidenceIds": list(ids),
        "unavailableMaterialCategory": None, "claimKind": "CLAUSE_PRESENT",
        "obligationScope": {"topic": "STRUCTURE", "responsibleParty": "UNSPECIFIED", "trigger": None}}]}


def run_scope_gate(catalog, value, *, allowed=None):
    before = copy.deepcopy(value)
    try:
        return check_claims(value, catalog=catalog,
            available_categories={row.material_category for row in catalog.segments},
            allowed_evidence_ids=set(catalog.by_id) if allowed is None else set(allowed), synthesis=False)
    finally:
        assert value == before  # no dropped hint, changed attention, swapped ID or rewritten claim


def rejects_scope(catalog, value):
    with pytest.raises(QualityFailure) as caught:
        run_scope_gate(catalog, value)
    assert caught.value.code == "EVIDENCE_SUPPORT_SCOPE"
    assert caught.value.field_path == "hints[0]" and caught.value.index == 0


def with_source_lengths(rows, lengths):
    # An API-shape RED is separate from the real check_claims business RED below.
    assert "source_lengths" in inspect.signature(EvidenceCatalog).parameters, "SERVER_SOURCE_LENGTH_PROOF_API_MISSING"
    return EvidenceCatalog(rows, source_lengths=lengths)


@pytest.mark.parametrize("limit", [1000, 15])
def test_complete_server_bounded_supplement_is_not_a_table_row_merely_because_it_mentions_quantity(limit):
    catalog = catalog_for(limit=limit)
    value = observation(catalog.by_id)
    assert run_scope_gate(catalog, value) is None


def test_all_segments_of_two_explicit_supplements_can_pass_only_this_finite_negative_gate():
    catalog = segment_evidence_sources((EvidenceSource("first", "SUPPLEMENT", SUPPLEMENT),
        EvidenceSource("second", "SUPPLEMENT", "补充文件列明数量核对方式，乙方应提供售后服务说明。")), max_segment_chars=20)
    assert run_scope_gate(catalog, observation(catalog.by_id)) is None


@pytest.mark.parametrize("description", [
    "所引附件结构完整，不代表合同全文完整。",
    "所引附件结构完整，不能证明响应文件全文完整。",
    "所引附件结构完整，不代表全部附件齐全。",
])
def test_negated_global_or_inventory_conclusion_does_not_erase_local_supplement_scope(description):
    catalog = catalog_for()
    assert run_scope_gate(catalog, observation(catalog.by_id, description=description)) is None


@pytest.mark.parametrize("description", ["所引附件的章节结构完备。", "所引补充材料正文内容齐全。"])
def test_explicit_file_content_scope_is_not_attachment_inventory_even_with_another_completeness_word(description):
    catalog = catalog_for()
    assert run_scope_gate(catalog, observation(catalog.by_id, description=description)) is None


def test_single_complete_mx_row_remains_table_only_even_with_full_supplement_file_bounds():
    catalog = catalog_for("MX-001\t合成设备\t数量\t单价")
    rejects_scope(catalog, observation(catalog.by_id))


def test_legacy_catalog_without_server_bounds_does_not_infer_full_length_from_max_end():
    generated = catalog_for()
    unknown = EvidenceCatalog(generated.segments)
    rejects_scope(unknown, observation(unknown.by_id))


def test_known_full_length_with_missing_tail_segment_does_not_get_waived():
    text = "数量说明。" * 12
    generated = catalog_for(text, limit=15)
    catalog = with_source_lengths(generated.segments[:-1], {"supplement": len(text)})
    rejects_scope(catalog, observation(catalog.by_id))


def test_known_complete_catalog_does_not_waive_hint_that_cites_only_first_segment():
    catalog = catalog_for(limit=15)
    rejects_scope(catalog, observation([catalog.segments[0].evidence_id]))


def test_gap_in_cited_file_spans_cannot_be_mistaken_for_full_source_coverage():
    text = "数量说明。占数量交付。"
    rows = [EvidenceSegment("S1", "supplement", "SUPPLEMENT", 0, 5, text[:5], True),
        EvidenceSegment("S2", "supplement", "SUPPLEMENT", 6, len(text), text[6:], True)]
    catalog = with_source_lengths(rows, {"supplement": len(text)})
    rejects_scope(catalog, observation(catalog.by_id))


def test_uncitable_supplement_segment_does_not_gain_scope_exception_from_known_bounds():
    generated = catalog_for()
    rows = [replace(generated.segments[0], citable=False)]
    catalog = with_source_lengths(rows, {"supplement": len(SUPPLEMENT)})
    rejects_scope(catalog, observation(catalog.by_id))


@pytest.mark.parametrize("description", [
    "合同全文完整。", "合同正文结构完整。", "响应文件全文完整。",
    "响应文件整体结构完整。", "所引附件结构完整，因此合同全部内容齐全。",
    "不代表合同全文完整。另经判断，合同全文完整。",
    "不能证明响应文件全文完整。响应文件全文完整。",
])
def test_supplement_identity_cannot_support_positive_contract_or_response_whole_claim(description):
    catalog = catalog_for()
    rejects_scope(catalog, observation(catalog.by_id, description=description))


@pytest.mark.parametrize("description", [
    "全部附件齐全。", "所有补充材料均完整，无缺失。", "附件无缺失。",
    "补充材料全部齐全。", "不代表合同全文完整，但全部附件齐全。",
    "不代表全部附件齐全。另经判断，全部附件齐全。",
])
def test_complete_content_of_cited_files_does_not_prove_attachment_inventory_is_complete(description):
    catalog = catalog_for()
    rejects_scope(catalog, observation(catalog.by_id, description=description))


@pytest.mark.parametrize("role", ["CONTRACT_BODY", "BID_RESPONSE", "TENDER_DOCUMENT"])
def test_other_material_role_cannot_borrow_supplement_only_exception(role):
    catalog = catalog_for(role=role)
    rejects_scope(catalog, observation(catalog.by_id))


def test_mixed_roles_do_not_become_supplement_only_because_one_cited_file_is_supplement():
    catalog = segment_evidence_sources((EvidenceSource("first", "SUPPLEMENT", SUPPLEMENT),
        EvidenceSource("other", "BID_RESPONSE", "数量及响应说明。")), max_segment_chars=1000)
    rejects_scope(catalog, observation(catalog.by_id))


@pytest.mark.parametrize("other_role", ["SUPPLEMENT", "BID_RESPONSE"])
def test_duplicate_source_file_identity_cannot_issue_an_authoritative_complete_file_proof(other_role):
    catalog = segment_evidence_sources((EvidenceSource("same-file", "SUPPLEMENT", SUPPLEMENT),
        EvidenceSource("same-file", other_role, SUPPLEMENT)), max_segment_chars=1000)
    # Citing only the first same-ID source must not hide the colliding source.
    rejects_scope(catalog, observation([catalog.segments[0].evidence_id]))


def test_same_text_different_file_evidence_cannot_replace_allowed_identity():
    catalog = segment_evidence_sources((EvidenceSource("first", "SUPPLEMENT", SUPPLEMENT),
        EvidenceSource("second", "SUPPLEMENT", SUPPLEMENT)), max_segment_chars=1000)
    first, second = catalog.segments
    with pytest.raises(QualityFailure) as caught:
        run_scope_gate(catalog, observation([second.evidence_id]), allowed={first.evidence_id})
    assert caught.value.code == "EVIDENCE_SCOPE"
    assert caught.value.field_path == "hints[0].problemEvidenceIds"


def test_source_length_proof_is_copied_and_read_only_not_mutable_into_a_waiver():
    generated = catalog_for()
    lengths = {"supplement": len(SUPPLEMENT) + 1}
    catalog = with_source_lengths(generated.segments, lengths)
    lengths["supplement"] = len(SUPPLEMENT)
    rejects_scope(catalog, observation(catalog.by_id))
    with pytest.raises(TypeError):
        catalog.source_lengths["supplement"] = len(SUPPLEMENT)


@pytest.mark.parametrize("row", [
    "MX-001 合成设备 名称 数量 单价 字段尚待核对。",
    "序号 名称 数量 计量单位 含税单价 合计金额。",
])
def test_plain_text_row_or_header_is_not_substantive_document_body(row):
    catalog = catalog_for(row)
    rejects_scope(catalog, observation(catalog.by_id))


@pytest.mark.parametrize("description", ["附件齐全。", "附件均已提供。"])
def test_bare_attachment_inventory_assertion_is_not_proven_by_its_cited_files(description):
    catalog = catalog_for()
    rejects_scope(catalog, observation(catalog.by_id, description=description))


@pytest.mark.parametrize("description", [
    "不代表合同完整。合同完整。",
    "不代表全部附件齐全。全部附件齐全。",
    "不代表合同完整，合同完整。",
    "不代表合同缺少条款但合同完整。",
    "不代表全部附件缺失但全部附件齐全。",
    "合同的付款、履约、验收、终止等全部内容均完整。",
    "附件只是引用依据，正文结构完整。",
    "附件仅供对照，文档内容完整。",
    "合同正文及附件结构完整。",
    "合同全文（含附件）结构完整。",
])
def test_each_positive_assertion_needs_its_own_supplement_scope_not_an_earlier_negation(description):
    catalog = catalog_for()
    rejects_scope(catalog, observation(catalog.by_id, description=description))


@pytest.mark.parametrize("limit", [15, 20, 1000])
def test_evidence_chunk_boundaries_cannot_turn_a_single_plain_mx_row_into_document_prose(limit):
    catalog = catalog_for("MX-001 合成设备名称 数量计划数量要求数量规格数量说明数量字段数量待核对。", limit=limit)
    rejects_scope(catalog, observation(catalog.by_id))
