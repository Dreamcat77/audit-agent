from audit_ai.model.evidence import (
    EvidenceCatalog,
    EvidenceSource,
    normalize_visible_text,
    segment_evidence_sources,
)


MASK = "[确定性数值字段已移除]"


def assert_exact_source_partition(
    source: str, catalog: EvidenceCatalog, file_id: str
) -> None:
    segments = [item for item in catalog.segments if item.file_id == file_id]
    assert segments
    assert segments[0].source_start == 0
    assert segments[-1].source_end == len(source)
    assert all(
        left.source_end == right.source_start
        for left, right in zip(segments, segments[1:])
    )
    assert all(
        item.exact_text == source[item.source_start:item.source_end]
        for item in segments
    )
    assert "".join(item.exact_text for item in segments) == source


def test_evidence_ids_are_stable_and_request_wide_unique() -> None:
    catalog = segment_evidence_sources(
        (
            EvidenceSource("contract-1", "CONTRACT_BODY", "合同违约责任条款。"),
            EvidenceSource("bid-1", "BID_RESPONSE", "响应文件服务承诺。"),
            EvidenceSource("tender-1", "TENDER_DOCUMENT", "采购文件验收要求。"),
        ),
        max_segment_chars=100,
    )

    assert tuple(segment.evidence_id for segment in catalog.segments) == (
        "C:S1",
        "B:S2",
        "T:S3",
    )
    assert len(catalog.by_id) == len(catalog.segments)
    assert catalog.by_id["B:S2"].file_id == "bid-1"
    assert catalog.by_id["B:S2"].exact_text == "响应文件服务承诺。"


def test_segmentation_preserves_all_normalized_visible_business_text() -> None:
    source_text = "第一条 交付要求。\n第二条 质保要求。\n第三条 违约责任。"

    catalog = segment_evidence_sources(
        (EvidenceSource("contract-1", "CONTRACT_BODY", source_text),),
        max_segment_chars=12,
    )

    segmented_text = "".join(segment.exact_text for segment in catalog.segments)
    assert normalize_visible_text(segmented_text) == normalize_visible_text(source_text)
    assert len({segment.evidence_id for segment in catalog.segments}) == len(
        catalog.segments
    )


def test_segment_exactly_at_public_excerpt_limit_stays_single_and_complete() -> None:
    source = "甲" * 1000

    catalog = segment_evidence_sources(
        (EvidenceSource("contract", "CONTRACT_BODY", source),),
        max_segment_chars=1000,
    )

    assert [len(segment.exact_text) for segment in catalog.segments] == [1000]
    assert_exact_source_partition(source, catalog, "contract")


def test_punctuation_near_boundary_stays_within_public_excerpt_limit() -> None:
    source = "甲" * 999 + "。" + "乙" * 25

    catalog = segment_evidence_sources(
        (EvidenceSource("contract", "CONTRACT_BODY", source),),
        max_segment_chars=1000,
    )

    assert [len(segment.exact_text) for segment in catalog.segments] == [1000, 25]
    assert catalog.segments[0].exact_text.endswith("。")
    assert_exact_source_partition(source, catalog, "contract")


def test_punctuation_just_past_boundary_never_creates_a_1001_character_segment() -> None:
    source = "甲" * 1000 + "。" + "乙" * 25

    catalog = segment_evidence_sources(
        (EvidenceSource("contract", "CONTRACT_BODY", source),),
        max_segment_chars=1000,
    )

    assert all(len(segment.exact_text) <= 1000 for segment in catalog.segments)
    assert_exact_source_partition(source, catalog, "contract")


def test_masked_text_remains_visible_but_is_not_citable() -> None:
    masked_text = MASK

    catalog = segment_evidence_sources(
        (EvidenceSource("contract-1", "CONTRACT_BODY", masked_text),),
        max_segment_chars=100,
    )

    assert len(catalog.segments) == 1
    assert catalog.segments[0].exact_text == masked_text
    assert catalog.segments[0].citable is False


def test_mask_span_does_not_poison_adjacent_breach_clause() -> None:
    source = f"合同金额：{MASK}。逾期交付按合同金额1%/周计算，累计上限10%。"

    catalog = segment_evidence_sources(
        (EvidenceSource("contract", "CONTRACT_BODY", source),),
        max_segment_chars=1000,
    )

    assert_exact_source_partition(source, catalog, "contract")
    assert any(not item.citable and MASK in item.exact_text for item in catalog.segments)
    assert any(
        item.citable and "逾期交付按合同金额1%/周计算" in item.exact_text
        for item in catalog.segments
    )


def test_short_masked_material_retains_a_citable_safe_review_span() -> None:
    source = f"采购预算：{MASK}。\n服务响应偏离：承诺四小时，合同要求八小时。"
    assert len(source) < 1000

    catalog = segment_evidence_sources(
        (EvidenceSource("bid", "BID_RESPONSE", source),),
        max_segment_chars=1000,
    )

    assert_exact_source_partition(source, catalog, "bid")
    assert len(catalog.segments) > 1
    assert any(
        item.citable and "服务响应偏离" in item.exact_text
        for item in catalog.segments
    )


def test_multiple_masks_leave_later_contract_breach_text_citable() -> None:
    masked_context = "\n".join(
        f"确定性金额字段{i}：{MASK}。" for i in range(1, 12)
    )
    breach = "违约责任：逾期交付按合同金额1%/周计算，累计上限合同金额10%。"
    source = f"{masked_context}\n{breach}"

    catalog = segment_evidence_sources(
        (EvidenceSource("contract", "CONTRACT_BODY", source),),
        max_segment_chars=1000,
    )

    assert_exact_source_partition(source, catalog, "contract")
    assert any(item.citable and breach in item.exact_text for item in catalog.segments)


def test_pm44_03_shaped_materials_each_have_citable_breach_candidate() -> None:
    sources = (
        EvidenceSource(
            "contract",
            "CONTRACT_BODY",
            "\n".join(
                [f"合同金额字段{i}：{MASK}。" for i in range(1, 12)]
                + ["违约责任：逾期交付按合同金额1%/周计算，累计上限10%。"]
            ),
        ),
        EvidenceSource(
            "tender",
            "TENDER_DOCUMENT",
            f"采购预算：{MASK}。\n违约责任：逾期交付按合同金额1%/周计算，累计上限10%。",
        ),
        EvidenceSource(
            "bid",
            "BID_RESPONSE",
            f"投标总价：{MASK}。\n违约责任：逾期交付按合同金额1%/周计算，累计上限10%。",
        ),
    )

    catalog = segment_evidence_sources(sources, max_segment_chars=1000)

    for source in sources:
        assert_exact_source_partition(source.text, catalog, source.file_id)
        assert any(
            item.file_id == source.file_id
            and item.citable
            and "1%/周" in item.exact_text
            and "10%" in item.exact_text
            for item in catalog.segments
        )
