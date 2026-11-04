"""Whitespace in a cited calendar date is presentation, not a duration claim.

Synthetic finite-format checks plus the native ANALYZE path. No date/business
rule, calendar rollover, citation expansion or model output is synthesized.
"""
from decimal import Decimal

import pytest

from audit_ai.model.evidence import EvidenceCatalog, EvidenceSegment
from audit_ai.model.semantic_hierarchy import _critical_tokens, check_critical_anchors
from audit_ai.model.semantic_quality import QualityFailure
from test_hierarchy_analysis_critical_facts import first_analysis
from test_semantic_hierarchy_facts import catalog, payload


SPACES = [" ", "\u00a0", "\t", "\n", " \t\u00a0\n"]
SPACE_IDS = ["ascii", "nbsp", "tab", "newline", "mixed"]
NORMALIZED = "乙方应于2026年12月20日交付材料。"


def spaced_date(space):
    return f"乙方应于2026{space}年{space}12{space}月{space}20{space}日交付材料。"


@pytest.mark.parametrize("space", SPACES, ids=SPACE_IDS)
def test_same_calendar_date_with_unit_whitespace_is_one_date_not_three_durations(space):
    assert _critical_tokens(spaced_date(space)) == {("DATE", "2026-12-20")}
    check_critical_anchors(payload(NORMALIZED), catalog=catalog(spaced_date(space)))


@pytest.mark.parametrize("space", SPACES, ids=SPACE_IDS)
def test_native_analysis_accepts_normalized_date_from_exact_cited_whitespace_source(space):
    provider, _, packet = first_analysis(NORMALIZED, source_text=spaced_date(space))
    assert packet["status"] == "VERIFIED" and packet["call"]["outcome"] == "SUCCEEDED"
    assert packet["validatedResult"]["hints"][0]["description"] == NORMALIZED
    assert packet["completion"]["payload"] == provider.sent[0]
    assert len(provider.calls) == 1 and packet["monetaryObservation"]["mode"] == "OBSERVE"


def test_chinese_numerals_and_whitespace_preserve_the_same_calendar_date():
    source = "乙方应于二〇二六 年 十二 月 二十 日交付材料。"
    assert _critical_tokens(source) == {("DATE", "2026-12-20")}
    check_critical_anchors(payload(NORMALIZED), catalog=catalog(source))
    check_critical_anchors(payload("乙方应于二〇二六年十二月二十日交付材料。"),
                           catalog=catalog("乙方应于2026年12月20日交付材料。"))


def test_only_adjacent_cited_spans_of_same_file_may_join_a_spaced_date():
    parts = ["乙方应于2026 ", "年 12 ", "月 20 日交付材料。"]
    offset, segments = 0, []
    for index, part in enumerate(parts):
        segments.append(EvidenceSegment(f"C{index + 1}", "body", "CONTRACT_BODY",
                                        offset, offset + len(part), part, True))
        offset += len(part)
    p = payload(NORMALIZED)
    p["hints"][0]["problemEvidenceIds"] = ["C1", "C2", "C3"]
    check_critical_anchors(p, catalog=EvidenceCatalog(segments))


@pytest.mark.parametrize("kind", ["uncited", "cross-file", "same-file-gap"])
def test_uncited_or_noncontiguous_source_cannot_supply_or_assemble_a_date(kind):
    if kind == "uncited":
        first, second = "本段未列日期。", "乙方应于2026年12月20日交付材料。"
        c = EvidenceCatalog([EvidenceSegment("C1", "body", "CONTRACT_BODY", 0, len(first), first, True),
                             EvidenceSegment("C2", "elsewhere", "SUPPLEMENT", 0, len(second), second, True)])
        p = payload(NORMALIZED)  # Only C1; do not auto-repair to global C2.
    else:
        first, second = "乙方应于2026年", "12月20日交付材料。"
        other_file = "elsewhere" if kind == "cross-file" else "body"
        start = 0 if kind == "cross-file" else len(first) + 1
        c = EvidenceCatalog([EvidenceSegment("C1", "body", "CONTRACT_BODY", 0, len(first), first, True),
                             EvidenceSegment("C2", other_file, "CONTRACT_BODY", start, start + len(second), second, True)])
        p = payload(NORMALIZED)
        p["hints"][0]["problemEvidenceIds"] = ["C1", "C2"]
    with pytest.raises(QualityFailure, match="CRITICAL_FACT_UNSUPPORTED"):
        check_critical_anchors(p, catalog=c)


@pytest.mark.parametrize("source", [
    "乙方应于12 月 20 日交付材料。", "乙方应于2026 年 12 月交付材料。",
    "乙方应于26 年 12 月 20 日交付材料。", "乙方应于2025 年 12 月 20 日交付材料。",
    "乙方应于2026 年 11 月 20 日交付材料。", "乙方应于2026 年 13 月 20 日交付材料。",
    "乙方应于2026 年 2 月 30 日交付材料。",
], ids=["missing-year", "missing-day", "different-year-width", "different-year", "different-month", "invalid-month", "invalid-day"])
def test_spacing_never_fills_missing_date_fields_or_rolls_invalid_dates_into_valid_date(source):
    with pytest.raises(QualityFailure, match="CRITICAL_FACT_UNSUPPORTED"):
        check_critical_anchors(payload(NORMALIZED), catalog=catalog(source))


@pytest.mark.parametrize("description", ["甲方应于2026年12月20日交付材料。",
                                        "乙方应于2026年12月20日交付材料，责任比例为6%。"])
def test_date_format_support_does_not_authorize_party_inversion_or_changed_rate(description):
    source = "乙方应于2026年12月20日交付材料，责任比例为5%。"
    with pytest.raises(QualityFailure, match="CRITICAL_FACT_UNSUPPORTED"):
        check_critical_anchors(payload(description), catalog=catalog(source))


def test_real_durations_remain_durations_without_year_month_conversion():
    assert _critical_tokens("期限为30 日，另有36 个月。") == {
        ("DURATION", Decimal(30), "日"), ("DURATION", Decimal(36), "月")}
    with pytest.raises(QualityFailure, match="CRITICAL_FACT_UNSUPPORTED"):
        check_critical_anchors(payload("履行期为3年。"), catalog=catalog("履行期为36个月。"))


def test_observe_amount_warning_remains_diagnostic_and_literal_with_spaced_date_source():
    description = "合同金额100元，需人工复核。"
    _, _, packet = first_analysis(description, source_text=spaced_date("\u00a0"))
    assert packet["status"] == "VERIFIED" and packet["validatedResult"]["hints"][0]["description"] == description
    observation = packet["monetaryObservation"]
    assert observation["mode"] == "OBSERVE"
    assert [(warning["fieldPath"], warning["errorKind"]) for warning in observation["warnings"]] == [
        ("hints[0].description", "MONETARY_VALUE")]
