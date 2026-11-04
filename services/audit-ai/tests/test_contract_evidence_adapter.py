import json

import httpx

from audit_ai.config import Settings
from audit_ai.model.contract_draft import (
    ModelHintDraft,
    reconstruct_public_hint,
)
from audit_ai.model.evidence import (
    EvidenceCatalog,
    EvidenceSegment,
    EvidenceSource,
    segment_evidence_sources,
)
from audit_ai.model.gateway import (
    ContractTextOperation,
    OpenAiCompatibleModelGateway,
)
from audit_ai.model.router import ContractEvidenceItem, ModelApplicationService


def evidence_catalog():
    return segment_evidence_sources(
        (
            EvidenceSource("contract", "CONTRACT_BODY", "合同约定逾期每日承担违约责任。"),
            EvidenceSource("bid", "BID_RESPONSE", "响应文件承诺提供三年质保。"),
            EvidenceSource("tender", "TENDER_DOCUMENT", "采购文件要求提供五年质保。"),
        ),
        max_segment_chars=100,
    )


def test_internal_ids_reconstruct_the_existing_public_contract() -> None:
    projection = reconstruct_public_hint(
        ModelHintDraft(
            category="SERVICE_WARRANTY",
            attention="REVIEW",
            title="质保期限存在差异",
            description="合同、响应文件与采购文件需要交叉复核。",
            problem_evidence_ids=("C:S1",),
            comparison_evidence_ids=("B:S2", "T:S3"),
        ),
        evidence_catalog(),
        available_material_categories={
            "CONTRACT_BODY",
            "BID_RESPONSE",
            "TENDER_DOCUMENT",
        },
    )

    assert projection.evidence_valid is True
    assert projection.as_public_dict() == {
        "category": "SERVICE_WARRANTY",
        "attention": "REVIEW",
        "title": "质保期限存在差异",
        "description": "合同、响应文件与采购文件需要交叉复核。",
        "evidenceRefs": ["contract", "bid", "tender"],
        "evidenceItems": [
            {
                "fileId": "contract",
                "status": "REVIEW",
                "excerpt": "合同约定逾期每日承担违约责任。",
            },
            {
                "fileId": "bid",
                "status": "NORMAL",
                "excerpt": "响应文件承诺提供三年质保。",
            },
            {
                "fileId": "tender",
                "status": "NORMAL",
                "excerpt": "采购文件要求提供五年质保。",
            },
        ],
    }


def test_maximum_legal_segment_reconstructs_into_frozen_public_dto() -> None:
    source = "质" * 1000
    catalog = segment_evidence_sources(
        (EvidenceSource("contract", "CONTRACT_BODY", source),),
        max_segment_chars=1000,
    )

    projection = reconstruct_public_hint(
        ModelHintDraft(
            category="SERVICE_WARRANTY",
            attention="INFO",
            title="质保条款",
            description="保留来源材料供人工复核。",
            comparison_evidence_ids=("C:S1",),
        ),
        catalog,
        available_material_categories={"CONTRACT_BODY"},
    )

    item = ContractEvidenceItem.model_validate(projection.evidence_items[0])

    assert projection.evidence_valid is True
    assert catalog.segments[0].exact_text.startswith(item.excerpt)
    assert len(item.excerpt.encode("utf-8")) <= 1000


def test_thirteen_hint_provider_json_reconstructs_boundary_evidence_into_public_dto() -> None:
    source = "甲" * 1000 + "。" + "乙" * 25
    catalog = segment_evidence_sources(
        (EvidenceSource("contract", "CONTRACT_BODY", source),),
        max_segment_chars=1000,
    )
    boundary_segment = max(catalog.segments, key=lambda item: len(item.exact_text))
    categories = (
        "CONTRACT_STRUCTURE",
        "PROJECT_IDENTITY",
        "SUPPLIER_IDENTITY",
        "DELIVERY_TERMS",
        "SERVICE_WARRANTY",
        "SCOPE_AND_SUBCONTRACTING",
        "BREACH_LIABILITY",
        "TERM_TERMINATION",
        "OTHER_TEXT",
        "SERVICE_WARRANTY",
        "DELIVERY_TERMS",
        "CONTRACT_STRUCTURE",
        "OTHER_TEXT",
    )
    provider_draft = {
        "summary": "Synthetic provider result for evidence reconstruction.",
        "hints": [
            {
                "category": category,
                "attention": "INFO",
                "title": f"Synthetic hint {index}",
                "description": "Retain the cited source for human review.",
                "comparisonEvidenceIds": [boundary_segment.evidence_id],
            }
            for index, category in enumerate(categories, start=1)
        ],
    }

    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "model": "deepseek-synthetic-fixture",
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(provider_draft)
                        }
                    }
                ],
            },
        )

    gateway = OpenAiCompatibleModelGateway(
        Settings(
            model_base_url="http://synthetic.invalid/v1",
            model_api_key="synthetic-key",
            model_name="deepseek-synthetic-fixture",
        ),
        transport=httpx.MockTransport(handler),
    )
    completion = gateway.analyze_contract(
        ContractTextOperation(
            project_id="synthetic-project",
            chunk_index=1,
            chunk_count=1,
            source_text="synthetic evidence input",
        )
    )
    result, filtered_count, repair_ids, _diagnostics = (
        ModelApplicationService._normalize_contract_result(
            completion.payload,
            {"contract": source},
            {"CONTRACT_BODY"},
            catalog,
            {boundary_segment.evidence_id},
            operation_key="A1",
        )
    )

    assert len(boundary_segment.exact_text) == 1000
    assert filtered_count == 0
    assert repair_ids == ()
    assert len(result.hints) == 13
    assert all(
        boundary_segment.exact_text.startswith(
            ContractEvidenceItem.model_validate(hint.evidence_items[0]).excerpt
        )
        and len(
            ContractEvidenceItem.model_validate(hint.evidence_items[0])
            .excerpt.encode("utf-8")
        ) <= 1000
        for hint in result.hints
    )


def test_multiple_internal_segments_from_one_file_project_to_one_public_item() -> None:
    catalog = EvidenceCatalog(
        (
            EvidenceSegment(
                "CONTRACT:S1", "CONTRACT", "CONTRACT_BODY", 0, 5,
                "第一处问题", True,
            ),
            EvidenceSegment(
                "CONTRACT:S2", "CONTRACT", "CONTRACT_BODY", 5, 10,
                "第二处问题", True,
            ),
            EvidenceSegment(
                "TENDER:S5", "TENDER", "TENDER_DOCUMENT", 0, 6,
                "采购文件条款", True,
            ),
        )
    )

    projection = reconstruct_public_hint(
        ModelHintDraft(
            category="OTHER_TEXT",
            attention="REVIEW",
            title="多处问题",
            description="需要分别复核。",
            problem_evidence_ids=("CONTRACT:S2", "CONTRACT:S1"),
            comparison_evidence_ids=("TENDER:S5",),
        ),
        catalog,
        available_material_categories={"CONTRACT_BODY", "TENDER_DOCUMENT"},
    )

    assert projection.evidence_valid is True
    assert projection.evidence_refs == ("CONTRACT", "TENDER")
    assert projection.evidence_items == (
        {
            "fileId": "CONTRACT",
            "status": "REVIEW",
            "excerpt": "第一处问题",
        },
        {
            "fileId": "TENDER",
            "status": "NORMAL",
            "excerpt": "采购文件条款",
        },
    )


def test_problem_segment_wins_over_earlier_normal_segment_from_same_file() -> None:
    catalog = EvidenceCatalog(
        (
            EvidenceSegment(
                "CONTRACT:S1", "CONTRACT", "CONTRACT_BODY", 0, 4,
                "对照条款", True,
            ),
            EvidenceSegment(
                "CONTRACT:S2", "CONTRACT", "CONTRACT_BODY", 4, 8,
                "问题条款", True,
            ),
        )
    )

    projection = reconstruct_public_hint(
        ModelHintDraft(
            category="OTHER_TEXT",
            attention="REVIEW",
            title="条款冲突",
            description="需要复核问题条款。",
            problem_evidence_ids=("CONTRACT:S2",),
            comparison_evidence_ids=("CONTRACT:S1",),
        ),
        catalog,
        available_material_categories={"CONTRACT_BODY"},
    )

    assert projection.evidence_valid is True
    assert projection.evidence_refs == ("CONTRACT",)
    assert projection.evidence_items == (
        {
            "fileId": "CONTRACT",
            "status": "REVIEW",
            "excerpt": "问题条款",
        },
    )


def test_wrong_or_non_citable_problem_evidence_id_is_rejected() -> None:
    catalog = segment_evidence_sources(
        (EvidenceSource("contract", "CONTRACT_BODY", "[确定性数值字段已移除]"),),
        max_segment_chars=100,
    )

    wrong_id = reconstruct_public_hint(
        ModelHintDraft(
            category="BREACH_LIABILITY",
            attention="REVIEW",
            title="违约责任",
            description="需要复核。",
            problem_evidence_ids=("C:S999",),
        ),
        catalog,
        available_material_categories={"CONTRACT_BODY"},
    )
    masked_segment = reconstruct_public_hint(
        ModelHintDraft(
            category="BREACH_LIABILITY",
            attention="REVIEW",
            title="违约责任",
            description="需要复核。",
            problem_evidence_ids=("C:S1",),
        ),
        catalog,
        available_material_categories={"CONTRACT_BODY"},
    )

    assert wrong_id.evidence_valid is False
    assert masked_segment.evidence_valid is False


def test_request_valid_id_is_rejected_when_not_visible_to_the_operation() -> None:
    projection = reconstruct_public_hint(
        ModelHintDraft(
            category="SERVICE_WARRANTY",
            attention="REVIEW",
            title="质保期限",
            description="需要复核。",
            problem_evidence_ids=("B:S2",),
        ),
        evidence_catalog(),
        available_material_categories={
            "CONTRACT_BODY",
            "BID_RESPONSE",
            "TENDER_DOCUMENT",
        },
        allowed_evidence_ids={"C:S1"},
    )

    assert projection.evidence_valid is False


def test_absent_material_reconstructs_existing_insufficient_status_without_excerpt() -> None:
    projection = reconstruct_public_hint(
        ModelHintDraft(
            category="CONTRACT_STRUCTURE",
            attention="INSUFFICIENT",
            title="缺少补充协议",
            description="未提供补充协议，无法核验补充约定。",
            comparison_evidence_ids=("C:S1",),
            unavailable_material_category="SUPPLEMENT",
        ),
        evidence_catalog(),
        available_material_categories={
            "CONTRACT_BODY",
            "BID_RESPONSE",
            "TENDER_DOCUMENT",
        },
    )

    assert projection.evidence_valid is True
    assert projection.evidence_items == (
        {
            "fileId": "contract",
            "status": "INSUFFICIENT",
            "excerpt": None,
            "unavailableMaterialCategory": "SUPPLEMENT",
        },
    )


def test_unavailable_category_is_rejected_when_the_material_is_present() -> None:
    projection = reconstruct_public_hint(
        ModelHintDraft(
            category="CONTRACT_STRUCTURE",
            attention="INSUFFICIENT",
            title="缺少采购文件",
            description="无法核验。",
            comparison_evidence_ids=("C:S1",),
            unavailable_material_category="TENDER_DOCUMENT",
        ),
        evidence_catalog(),
        available_material_categories={
            "CONTRACT_BODY",
            "BID_RESPONSE",
            "TENDER_DOCUMENT",
        },
    )

    assert projection.evidence_valid is False
