import re

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from audit_ai.config import Settings
from audit_ai.main import create_app
from audit_ai.model.contract_correction import (
    CombinedCorrectionDraft,
    CorrectionMode,
    assign_server_hint_ids,
    build_correction_plan,
)
from audit_ai.model.router import ContractHint
from audit_ai.model.gateway import (
    BudgetSemanticOperation,
    ContractTextOperation,
    ModelCompletion,
    ModelGatewayPort,
)


def hint(*, category: str = "SERVICE_WARRANTY", title: str = "质保差异") -> ContractHint:
    return ContractHint(
        category=category,
        attention="REVIEW",
        title=title,
        description="需要复核材料中的条款差异。",
        evidenceRefs=["contract"],
        evidenceItems=[
            {
                "fileId": "contract",
                "status": "REVIEW",
                "excerpt": "合同约定提供三年质保。",
            }
        ],
        problem_evidence_ids=("C:S1",),
        comparison_evidence_ids=("B:S2",),
    )


def structure_info_hint(evidence_id: str = "C:S1") -> dict:
    return {
        "category": "CONTRACT_STRUCTURE",
        "attention": "INFO",
        "title": "合同结构",
        "description": "保留可见合同结构语义判断。",
        "comparisonEvidenceIds": [evidence_id],
    }


def fixed_4_1_info_hints(
    evidence_id: str = "C:S1",
    *,
    exclude: tuple[str, ...] = (),
) -> list[dict]:
    categories = (
        "CONTRACT_STRUCTURE",
        "PROJECT_IDENTITY",
        "SUPPLIER_IDENTITY",
        "DELIVERY_TERMS",
        "SERVICE_WARRANTY",
        "SCOPE_AND_SUBCONTRACTING",
    )
    return [
        {
            "category": category,
            "attention": "INFO",
            "title": f"{category} observation",
            "description": "The visible source supports this bounded observation.",
            "comparisonEvidenceIds": [evidence_id],
        }
        for category in categories
        if category not in exclude
    ]


def contract_body_info_hints(
    evidence_id: str = "C:S1",
    *,
    exclude: tuple[str, ...] = (),
) -> list[dict]:
    hints = fixed_4_1_info_hints(evidence_id, exclude=exclude)
    if "TERM_TERMINATION" not in exclude:
        hints.append({
            "category": "TERM_TERMINATION",
            "attention": "INFO",
            "title": "TERM_TERMINATION observation",
            "description": "The visible source supports this bounded observation.",
            "comparisonEvidenceIds": [evidence_id],
        })
    return hints


def test_server_assigns_stable_hint_ids_that_never_serialize_publicly() -> None:
    assigned = assign_server_hint_ids(
        (hint(title="第一项"), hint(category="OTHER_TEXT", title="第二项")),
        operation_key="A1",
    )

    assert tuple(item.hint_id for item in assigned) == ("A1:H1", "A1:H2")
    public = [item.model_dump(by_alias=True, exclude_none=True) for item in assigned]
    assert all("hintId" not in item and "hint_id" not in item for item in public)


def test_strict_combined_delta_accepts_only_missing_hints_and_evidence_repairs() -> None:
    draft = CombinedCorrectionDraft.model_validate(
        {
            "missingCategoryHints": [
                {
                    "category": "BREACH_LIABILITY",
                    "attention": "REVIEW",
                    "title": "违约责任比例",
                    "description": "需要复核计取比例和累计上限。",
                    "problemEvidenceIds": ["C:S4"],
                    "comparisonEvidenceIds": ["T:S8", "B:S10"],
                }
            ],
            "evidenceRepairs": [
                {
                    "hintId": "A1:H1",
                    "problemEvidenceIds": ["C:S5"],
                    "comparisonEvidenceIds": ["B:S7", "T:S9"],
                    "unavailableMaterialCategory": None,
                }
            ],
        }
    )

    assert draft.missing_category_hints[0].category == "BREACH_LIABILITY"
    assert draft.evidence_repairs[0].hint_id == "A1:H1"
    assert draft.evidence_repairs[0].comparison_evidence_ids == ("B:S7", "T:S9")


@pytest.mark.parametrize("semantic_field", ["category", "attention", "title", "description"])
def test_evidence_repair_schema_forbids_semantic_mutation(semantic_field: str) -> None:
    repair = {
        "hintId": "A1:H1",
        "problemEvidenceIds": ["C:S1"],
        "comparisonEvidenceIds": [],
        semantic_field: "forbidden",
    }

    with pytest.raises(ValidationError):
        CombinedCorrectionDraft.model_validate(
            {"missingCategoryHints": [], "evidenceRepairs": [repair]}
        )


def test_correction_plan_selects_modes_and_freezes_hint_semantics() -> None:
    assigned = assign_server_hint_ids((hint(),), operation_key="A1")

    evidence_only = build_correction_plan(
        assigned,
        missing_required_categories=(),
        evidence_repair_hint_ids=("A1:H1",),
    )
    category_only = build_correction_plan(
        assigned,
        missing_required_categories=("BREACH_LIABILITY",),
        evidence_repair_hint_ids=(),
    )
    combined = build_correction_plan(
        assigned,
        missing_required_categories=("BREACH_LIABILITY",),
        evidence_repair_hint_ids=("A1:H1",),
    )

    assert evidence_only.mode is CorrectionMode.EVIDENCE_REPAIR
    assert category_only.mode is CorrectionMode.SEMANTIC_COMPLETENESS_CORRECTION
    assert combined.mode is CorrectionMode.COMBINED_CORRECTION
    target = combined.evidence_repair_targets[0]
    assert (
        target.category,
        target.attention,
        target.title,
        target.description,
    ) == (
        "SERVICE_WARRANTY",
        "REVIEW",
        "质保差异",
        "需要复核材料中的条款差异。",
    )


def test_correction_plan_rejects_unknown_or_duplicate_hint_targets() -> None:
    assigned = assign_server_hint_ids((hint(),), operation_key="A1")

    with pytest.raises(ValueError, match="unknown hintId"):
        build_correction_plan(
            assigned,
            missing_required_categories=(),
            evidence_repair_hint_ids=("A1:H999",),
        )
    with pytest.raises(ValueError, match="duplicate hintId"):
        build_correction_plan(
            assigned,
            missing_required_categories=(),
            evidence_repair_hint_ids=("A1:H1", "A1:H1"),
        )


class SequencedCorrectionGateway(ModelGatewayPort):
    def __init__(self, payloads: list[dict]) -> None:
        self.payloads = list(payloads)
        self.operations: list[ContractTextOperation] = []

    def analyze_contract(self, operation: ContractTextOperation) -> ModelCompletion:
        self.operations.append(operation)
        return ModelCompletion(
            payload=self.payloads.pop(0), model="qwen-test", duration_ms=3
        )

    def analyze_budget(self, _operation: BudgetSemanticOperation) -> ModelCompletion:
        raise AssertionError("budget operation was not expected")


def post_contract(gateway: ModelGatewayPort, text: str):
    return TestClient(
        create_app(
            settings=Settings(model_chunk_chars=2000), model_gateway=gateway
        )
    ).post(
        "/ai/v1/agent/contract-text-analysis",
        json={
            "projectId": "project-correction",
            "materials": [
                {
                    "fileId": "contract",
                    "category": "CONTRACT_BODY",
                    "text": text,
                }
            ],
        },
    )


def test_evidence_only_repair_cannot_rewrite_first_semantic_result() -> None:
    first_hint = {
        "category": "SERVICE_WARRANTY",
        "attention": "REVIEW",
        "title": "服务时限差异",
        "description": "需要复核合同中的服务时限。",
        "problemEvidenceIds": ["C:S999"],
        "comparisonEvidenceIds": [],
    }
    gateway = SequencedCorrectionGateway(
        [
            {
                "summary": "已核对服务条款。",
                "hints": [
                    first_hint,
                    *contract_body_info_hints(exclude=("SERVICE_WARRANTY",)),
                ],
            },
            {
                "evidenceRepairs": [
                    {
                        "hintId": "A1:H1",
                        "problemEvidenceIds": ["C:S1"],
                    }
                ],
            },
        ]
    )

    response = post_contract(gateway, "服务承诺：重大故障四小时内响应。")

    assert response.status_code == 200
    assert len(gateway.operations) == 2
    assert gateway.operations[1].purpose == "EVIDENCE_REPAIR"
    assert gateway.operations[0].document_redaction_provenance is True
    assert gateway.operations[1].document_redaction_provenance is True
    assert '"hintId":"A1:H1"' in gateway.operations[1].source_text
    result = response.json()["data"]["hints"][0]
    assert {key: result[key] for key in ("category", "attention", "title", "description")} == {
        key: first_hint[key] for key in ("category", "attention", "title", "description")
    }
    assert result["evidenceItems"][0]["excerpt"] == "服务承诺：重大故障四小时内响应。"
    assert not any(
        internal in response.text
        for internal in ("hintId", "evidenceId", "correctionPlan")
    )


def test_missing_category_correction_only_appends_the_required_category() -> None:
    gateway = SequencedCorrectionGateway(
        [
            {
                "summary": "已核对可见条款。",
                "hints": [
                    {
                        "category": "OTHER_TEXT",
                        "attention": "INFO",
                        "title": "可见条款",
                        "description": "保留已完成的语义分析。",
                        "problemEvidenceIds": [],
                        "comparisonEvidenceIds": ["C:S1"],
                    },
                    *contract_body_info_hints(),
                ],
            },
            {
                "missingCategoryHints": [
                    {
                        "category": "BREACH_LIABILITY",
                        "attention": "REVIEW",
                        "title": "违约责任比例",
                        "description": "需要复核计取比例和累计上限。",
                        "problemEvidenceIds": ["C:S1"],
                    }
                ],
            },
        ]
    )

    response = post_contract(
        gateway, "违约责任：逾期交付按合同金额1%/周计算，累计上限10%。"
    )

    assert response.status_code == 200
    assert gateway.operations[1].purpose == "SEMANTIC_COMPLETENESS_CORRECTION"
    assert [item["category"] for item in response.json()["data"]["hints"]] == [
        "OTHER_TEXT",
        "CONTRACT_STRUCTURE",
        "PROJECT_IDENTITY",
        "SUPPLIER_IDENTITY",
        "DELIVERY_TERMS",
        "SERVICE_WARRANTY",
        "SCOPE_AND_SUBCONTRACTING",
        "TERM_TERMINATION",
        "BREACH_LIABILITY",
    ]


def test_combined_correction_adds_missing_category_and_repairs_only_known_hint() -> None:
    first_hint = {
        "category": "SERVICE_WARRANTY",
        "attention": "REVIEW",
        "title": "服务承诺",
        "description": "需要复核服务响应条款。",
        "problemEvidenceIds": ["C:S999"],
        "comparisonEvidenceIds": [],
    }
    gateway = SequencedCorrectionGateway(
        [
            {
                "summary": "已核对合同。",
                "hints": [
                    first_hint,
                    *contract_body_info_hints(exclude=("SERVICE_WARRANTY",)),
                ],
            },
            {
                "missingCategoryHints": [
                    {
                        "category": "BREACH_LIABILITY",
                        "attention": "REVIEW",
                        "title": "违约责任比例",
                        "description": "需要复核计取比例和累计上限。",
                        "problemEvidenceIds": ["C:S1"],
                        "comparisonEvidenceIds": [],
                    }
                ],
                "evidenceRepairs": [
                    {
                        "hintId": "A1:H1",
                        "problemEvidenceIds": ["C:S1"],
                        "comparisonEvidenceIds": [],
                    }
                ],
            },
        ]
    )
    source = (
        "服务承诺：重大故障四小时内响应。"
        "违约责任：逾期交付按合同金额1%/周计算，累计上限10%。"
    )

    response = post_contract(gateway, source)

    assert response.status_code == 200
    assert gateway.operations[1].purpose == "COMBINED_CORRECTION"
    result_hints = response.json()["data"]["hints"]
    assert [item["category"] for item in result_hints] == [
        "SERVICE_WARRANTY",
        "CONTRACT_STRUCTURE",
        "PROJECT_IDENTITY",
        "SUPPLIER_IDENTITY",
        "DELIVERY_TERMS",
        "SCOPE_AND_SUBCONTRACTING",
        "TERM_TERMINATION",
        "BREACH_LIABILITY",
    ]
    assert result_hints[0]["title"] == first_hint["title"]
    assert result_hints[0]["description"] == first_hint["description"]


def test_repair_payload_with_semantic_field_fails_closed() -> None:
    gateway = SequencedCorrectionGateway(
        [
            {
                "summary": "已核对服务条款。",
                "hints": [
                    {
                        "category": "SERVICE_WARRANTY",
                        "attention": "REVIEW",
                        "title": "服务时限差异",
                        "description": "需要复核合同中的服务时限。",
                        "problemEvidenceIds": ["C:S999"],
                        "comparisonEvidenceIds": [],
                    },
                    *contract_body_info_hints(exclude=("SERVICE_WARRANTY",)),
                ],
            },
            {
                "missingCategoryHints": [],
                "evidenceRepairs": [
                    {
                        "hintId": "A1:H1",
                        "problemEvidenceIds": ["C:S1"],
                        "comparisonEvidenceIds": [],
                        "category": "OTHER_TEXT",
                    }
                ],
            },
        ]
    )

    response = post_contract(gateway, "服务承诺：重大故障四小时内响应。")

    assert response.status_code == 502
    assert response.json()["code"] == "MODEL_INVALID_RESULT"
    assert len(gateway.operations) == 2


def test_minimal_missing_category_without_problem_evidence_still_fails_closed(
    caplog,
) -> None:
    gateway = SequencedCorrectionGateway(
        [
            {
                "summary": "Visible delivery terms reviewed.",
                "hints": [
                    {
                        "category": "DELIVERY_TERMS",
                        "attention": "INFO",
                        "title": "Delivery term",
                        "description": "The visible delivery term was retained.",
                        "comparisonEvidenceIds": ["C:S1"],
                    },
                    *contract_body_info_hints(exclude=("DELIVERY_TERMS",)),
                ],
            },
            {
                "missingCategoryHints": [
                    {
                        "category": "BREACH_LIABILITY",
                        "attention": "REVIEW",
                        "title": "Breach rate and cap",
                        "description": "Review the rate and cumulative cap.",
                    }
                ]
            },
        ]
    )

    caplog.set_level("INFO", logger="audit_ai.model.router")
    response = post_contract(
        gateway,
        "Breach penalty is contract amount 1% per week, capped at 10%.",
    )

    assert response.status_code == 502
    assert response.json()["code"] == "MODEL_INVALID_RESULT"
    assert len(gateway.operations) == 2
    rejected = next(
        record.audit_fields
        for record in caplog.records
        if record.getMessage().startswith("contract correction result rejected")
    )
    assert rejected["reason"] == "CORRECTION_EVIDENCE_INVALID"


@pytest.mark.parametrize(
    "returned_categories",
    [
        ("SERVICE_WARRANTY",),
        ("BREACH_LIABILITY", "OTHER_TEXT"),
    ],
)
def test_completeness_delta_rejects_wrong_or_unrequested_categories(
    returned_categories: tuple[str, ...],
    caplog,
) -> None:
    missing_hints = [
        {
            "category": category,
            "attention": "REVIEW",
            "title": f"Synthetic {category}",
            "description": "Review the visible synthetic clause.",
            "problemEvidenceIds": ["C:S1"],
        }
        for category in returned_categories
    ]
    gateway = SequencedCorrectionGateway(
        [
            {
                "summary": "Visible text reviewed.",
                "hints": contract_body_info_hints(),
            },
            {"missingCategoryHints": missing_hints},
        ]
    )

    caplog.set_level("INFO", logger="audit_ai.model.router")
    response = post_contract(
        gateway,
        "Breach penalty is contract amount 1% per week, capped at 10%.",
    )

    assert response.status_code == 502
    assert response.json()["code"] == "MODEL_INVALID_RESULT"
    rejected = next(
        record.audit_fields
        for record in caplog.records
        if record.getMessage().startswith("contract correction result rejected")
    )
    assert rejected["reason"] == "CORRECTION_CATEGORY_SET_MISMATCH"


class Pm44ShapedCorrectionGateway(ModelGatewayPort):
    def __init__(self) -> None:
        self.operations: list[ContractTextOperation] = []
        self.problem_evidence_id: str | None = None

    def analyze_contract(self, operation: ContractTextOperation) -> ModelCompletion:
        self.operations.append(operation)
        if len(self.operations) == 1:
            segments = re.findall(
                r"\[evidenceId=([^;]+);[^\]]*citable=true\]\n([^\r\n]*)",
                operation.source_text,
            )
            self.problem_evidence_id = next(
                evidence_id
                for evidence_id, text in segments
                if "no cumulative cap" in text
            )
            comparison_id = next(
                evidence_id
                for evidence_id, text in segments
                if "project identity" in text
            )
            return ModelCompletion(
                payload={
                    "summary": "Visible project identity reviewed.",
                    "hints": [
                        {
                            "category": "PROJECT_IDENTITY",
                            "attention": "INFO",
                            "title": "Project identity",
                            "description": "The visible identity text was retained.",
                            "comparisonEvidenceIds": [comparison_id],
                        },
                        *contract_body_info_hints(
                            comparison_id, exclude=("PROJECT_IDENTITY",)
                        ),
                    ],
                },
                model="synthetic-model",
                duration_ms=5,
            )
        return ModelCompletion(
            payload={
                "missingCategoryHints": [
                    {
                        "category": "BREACH_LIABILITY",
                        "attention": "REVIEW",
                        "title": "Breach cap differs",
                        "description": "Review the visible cumulative-cap difference.",
                        "problemEvidenceIds": [self.problem_evidence_id],
                    }
                ]
            },
            model="synthetic-model",
            duration_ms=7,
        )

    def analyze_budget(self, _operation: BudgetSemanticOperation) -> ModelCompletion:
        raise AssertionError("budget operation was not expected")


def test_pm44_03_shaped_minimal_completeness_delta_returns_grounded_public_review(
    caplog,
) -> None:
    mask = "[确定性数值字段已移除]"
    gateway = Pm44ShapedCorrectionGateway()
    caplog.set_level("INFO", logger="audit_ai.model.router")
    response = TestClient(
        create_app(
            settings=Settings(model_chunk_chars=6000),
            model_gateway=gateway,
        )
    ).post(
        "/ai/v1/agent/contract-text-analysis",
        json={
            "projectId": "pm44-03-shaped",
            "materials": [
                {
                    "fileId": "contract",
                    "category": "CONTRACT_BODY",
                    "text": (
                        f"Contract amount: {mask}. Contract project identity is synthetic. "
                        "Breach liability: overdue delivery is charged at contract amount "
                        "1% per week, with no cumulative cap."
                    ),
                },
                {
                    "fileId": "tender",
                    "category": "TENDER_DOCUMENT",
                    "text": (
                        f"Tender budget: {mask}. Breach penalty is contract amount "
                        "1% per week, capped at 10%."
                    ),
                },
                {
                    "fileId": "bid",
                    "category": "BID_RESPONSE",
                    "text": (
                        f"Bid price: {mask}. Breach penalty is contract amount "
                        "1% per week, capped at 10%."
                    ),
                },
                {
                    "fileId": "award",
                    "category": "AWARD_NOTICE",
                    "text": "Award notice identifies the synthetic supplier.",
                },
            ],
        },
    )

    assert response.status_code == 200
    assert len(gateway.operations) == 2
    assert [operation.attempt for operation in gateway.operations] == [1, 2]
    assert gateway.operations[1].purpose == "SEMANTIC_COMPLETENESS_CORRECTION"
    breach = next(
        hint
        for hint in response.json()["data"]["hints"]
        if hint["category"] == "BREACH_LIABILITY"
    )
    assert breach["attention"] == "REVIEW"
    assert breach["evidenceItems"][0]["status"] == "REVIEW"
    assert "no cumulative cap" in breach["evidenceItems"][0]["excerpt"]
    evidence_item_file_ids = [item["fileId"] for item in breach["evidenceItems"]]
    assert len(evidence_item_file_ids) == len(set(evidence_item_file_ids))
    completed = next(
        record.audit_fields
        for record in caplog.records
        if record.getMessage().startswith("contract semantic analysis completed")
    )
    assert completed["provider_call_count"] == 2
    assert completed["semantic_retry_count"] == 1
    assert not any(
        internal in response.text
        for internal in ("hintId", "evidenceId", "correctionPlan")
    )


def test_correction_schema_rejection_logs_only_safe_location_and_type(caplog) -> None:
    title_sentinel = "CORRECTION-TITLE-SENTINEL-DO-NOT-LOG"
    description_sentinel = "CORRECTION-DESCRIPTION-SENTINEL-DO-NOT-LOG"
    gateway = SequencedCorrectionGateway(
        [
            {
                "summary": "Visible service term reviewed.",
                "hints": [
                    {
                        "category": "SERVICE_WARRANTY",
                        "attention": "REVIEW",
                        "title": title_sentinel,
                        "description": description_sentinel,
                        "problemEvidenceIds": ["C:S999"],
                    },
                    *contract_body_info_hints(exclude=("SERVICE_WARRANTY",)),
                ],
            },
            {
                "evidenceRepairs": [
                    {
                        "hintId": "A1:H1",
                        "problemEvidenceIds": ["C:S1"],
                        "category": "OTHER_TEXT",
                    }
                ]
            },
        ]
    )
    caplog.set_level("INFO", logger="audit_ai.model.router")

    response = post_contract(
        gateway,
        "Service commitment: critical incidents receive a four-hour response.",
    )

    assert response.status_code == 502
    rejected = next(
        record.audit_fields
        for record in caplog.records
        if record.getMessage().startswith("correction validation rejected")
    )
    assert rejected["reason"] == "CORRECTION_SCHEMA_INVALID"
    assert rejected["correction_mode"] == "EVIDENCE_REPAIR"
    assert rejected["validation_error_count"] == 1
    detail = next(
        record.audit_fields
        for record in caplog.records
        if record.getMessage().startswith("correction validation error observed")
    )
    assert detail["validation_error_index"] == 1
    assert detail["validation_error_loc"] == "evidenceRepairs.0.category"
    assert detail["validation_error_type"] == "extra_forbidden"
    assert title_sentinel not in caplog.text
    assert description_sentinel not in caplog.text
    assert "OTHER_TEXT" not in caplog.text


def test_correction_schema_rejection_logs_allowlisted_optional_enum_sentinel(
    caplog,
) -> None:
    title_sentinel = "OPTIONAL-ENUM-TITLE-DO-NOT-LOG"
    description_sentinel = "OPTIONAL-ENUM-DESCRIPTION-DO-NOT-LOG"
    gateway = SequencedCorrectionGateway(
        [
            {
                "summary": "Visible service term reviewed.",
                "hints": [
                    {
                        "category": "SERVICE_WARRANTY",
                        "attention": "REVIEW",
                        "title": title_sentinel,
                        "description": description_sentinel,
                        "problemEvidenceIds": ["C:S999"],
                    },
                    *contract_body_info_hints(exclude=("SERVICE_WARRANTY",)),
                ],
            },
            {
                "evidenceRepairs": [
                    {
                        "hintId": "A1:H1",
                        "problemEvidenceIds": ["C:S1"],
                        "unavailableMaterialCategory": "NONE",
                    }
                ]
            },
        ]
    )
    caplog.set_level("INFO", logger="audit_ai.model.router")

    response = post_contract(
        gateway,
        "Service commitment: critical incidents receive a four-hour response.",
    )

    assert response.status_code == 502
    detail = next(
        record.audit_fields
        for record in caplog.records
        if record.getMessage().startswith("correction validation error observed")
    )
    assert detail["validation_error_loc"] == (
        "evidenceRepairs.0.unavailableMaterialCategory"
    )
    assert detail["validation_error_type"] == "literal_error"
    assert detail["validation_actual_type"] == "str"
    assert detail["validation_actual_enum"] == "NONE"
    assert title_sentinel not in caplog.text
    assert description_sentinel not in caplog.text


def test_correction_schema_rejection_logs_top_level_collection_type(caplog) -> None:
    gateway = SequencedCorrectionGateway(
        [
            {
                "summary": "Visible service term reviewed.",
                "hints": [
                    {
                        "category": "SERVICE_WARRANTY",
                        "attention": "REVIEW",
                        "title": "Synthetic service term",
                        "description": "Synthetic evidence requires repair.",
                        "problemEvidenceIds": ["C:S999"],
                    },
                    *contract_body_info_hints(exclude=("SERVICE_WARRANTY",)),
                ],
            },
            {
                "evidenceRepairs": {
                    "hintId": "A1:H1",
                    "problemEvidenceIds": ["C:S1"],
                }
            },
        ]
    )
    caplog.set_level("INFO", logger="audit_ai.model.router")

    response = post_contract(
        gateway,
        "Service commitment: critical incidents receive a four-hour response.",
    )

    assert response.status_code == 502
    detail = next(
        record.audit_fields
        for record in caplog.records
        if record.getMessage().startswith("correction validation error observed")
    )
    assert detail["validation_error_loc"] == "evidenceRepairs"
    assert detail["validation_error_type"] == "tuple_type"
    assert detail["validation_actual_type"] == "dict"
