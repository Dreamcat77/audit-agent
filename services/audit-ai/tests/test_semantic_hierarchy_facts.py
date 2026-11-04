"""Finite cited-source facts, not a general semantic-entailment assertion."""
import copy

import pytest

from audit_ai.model.evidence import EvidenceCatalog, EvidenceSegment
from audit_ai.model.semantic_quality import QualityFailure
from audit_ai.model.semantic_hierarchy import check_critical_anchors


def catalog(text):
    return EvidenceCatalog([EvidenceSegment("C1", "body", "CONTRACT_BODY", 0, len(text), text, True)])


def payload(description):
    return {"summary": "核对约定。", "hints": [{"category": "DELIVERY_TERMS", "attention": "REVIEW",
        "title": "交付约定核对", "description": description,
        "problemEvidenceIds": ["C1"], "comparisonEvidenceIds": [], "unavailableMaterialCategory": None,
        "claimKind": "AMBIGUOUS_CLAUSE", "obligationScope": {"topic": "DELIVERY", "responsibleParty": "PARTY_B", "trigger": "交付"}}]}


@pytest.mark.parametrize("description", ["乙方应于2026年11月20日交付。", "乙方应在三十一日内交付。",
    "乙方应按合同约定的百分之六承担责任。", "甲方应交付材料。"])
def test_same_evidence_id_does_not_authorize_changed_date_duration_rate_or_actor(description):
    c = catalog("乙方应于2026年10月20日交付，在三十日内提交；乙方应按合同约定的百分之五承担责任。")
    with pytest.raises(QualityFailure, match="CRITICAL_FACT_UNSUPPORTED"):
        check_critical_anchors(payload(description), catalog=c)


@pytest.mark.parametrize("description", ["乙方应于2026-10-20交付。", "乙方应在30天内提交。",
    "乙方应按合同约定的5%承担责任。", "核对交付约定的责任边界。", "甲方要求乙方交付材料。"])
def test_common_equivalent_formats_and_no_date_compression_are_allowed(description):
    c = catalog("乙方应于二〇二六年十月二十日交付，在三十日内提交；乙方应按合同约定的百分之五承担责任。甲方要求乙方交付材料。")
    original = payload(description)
    before = copy.deepcopy(original)
    check_critical_anchors(original, catalog=c)
    assert original == before


def test_only_actually_cited_source_supports_fact_and_workdays_not_calendar_days():
    c = catalog("乙方应于2026年10月20日交付，并在30工作日内提交。")
    other = "乙方应于2026年11月20日交付。"
    c = EvidenceCatalog([*c.segments, EvidenceSegment("C2", "other", "SUPPLEMENT", 0, len(other), other, True)])
    for text in ["乙方应于2026年11月20日交付。", "乙方应在30日内提交。"]:
        with pytest.raises(QualityFailure): check_critical_anchors(payload(text), catalog=c)


def test_observe_money_is_not_replaced_by_new_amount_decision_guard():
    # The existing OBSERVE monetary checker, not this finite non-money gate,
    # retains and warns on this literal; F02 remains the only money authority.
    p = payload("合同金额2000元高于中标金额1000元，需人工复核。")
    check_critical_anchors(p, catalog=catalog("原合同条款。"))


def test_different_invalid_dates_do_not_collapse_to_one_supported_value():
    c = catalog("来源载明2026-02-30，该日期需核对。")
    with pytest.raises(QualityFailure, match="CRITICAL_FACT_UNSUPPORTED"):
        check_critical_anchors(payload("来源载明2026-13-31，该日期需核对。"), catalog=c)
    # An honest mention of the SAME invalid source date remains possible; the
    # checker does not rewrite it into a fabricated valid calendar date.
    check_critical_anchors(payload("来源载明2026年2月30日，该日期需核对。"), catalog=c)


def test_untrusted_cited_oversized_year_is_not_an_internal_error_or_mandatory_copy():
    c = catalog("9" * 5000 + "年10月20日")
    check_critical_anchors(payload("核对交付约定。"), catalog=c)
