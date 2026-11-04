import logging
from typing import Any

from fastapi.testclient import TestClient
import pytest

from audit_ai.config import Settings
from audit_ai.main import create_app
from audit_ai.model.gateway import (
    BudgetSemanticOperation,
    ContractTextOperation,
    ModelCompletion,
    ModelGatewayPort,
)


FIXED_4_1_REQUIRED = (
    "CONTRACT_STRUCTURE",
    "PROJECT_IDENTITY",
    "SUPPLIER_IDENTITY",
    "DELIVERY_TERMS",
    "SERVICE_WARRANTY",
    "SCOPE_AND_SUBCONTRACTING",
)
QUANTIFIED_BREACH_SOURCE = (
    "合同正文包含合同组成、签章页和附件目录。"
    "违约责任：逾期交付按合同金额1%/周计算，累计上限10%。"
)
GENERAL_CONTRACT_SOURCE = "合同正文包含合同组成、项目名称、供应商、交付、服务和分包条款。"
SAFE_REVIEW_TITLE = "违约责任条款需复核"
SAFE_REVIEW_DESCRIPTION = "已识别违约责任相关条款或跨材料差异，请结合来源材料人工复核。"
SAFE_INFO_TITLE = "违约责任条款"
SAFE_INFO_DESCRIPTION = "已识别违约责任相关条款，请结合来源材料核对。"


class SequencedGateway(ModelGatewayPort):
    def __init__(self, payloads: list[dict[str, Any]]) -> None:
        self.payloads = list(payloads)
        self.operations: list[ContractTextOperation] = []

    def analyze_contract(self, operation: ContractTextOperation) -> ModelCompletion:
        self.operations.append(operation)
        if not self.payloads:
            raise AssertionError("unexpected provider call")
        return ModelCompletion(
            payload=self.payloads.pop(0), model="synthetic-model", duration_ms=3
        )

    def analyze_budget(self, _operation: BudgetSemanticOperation) -> ModelCompletion:
        raise AssertionError("budget operation was not expected")


def info_hint(category: str, *, evidence_id: str = "C:S1") -> dict[str, Any]:
    return {
        "category": category,
        "attention": "INFO",
        "title": f"{category}条款",
        "description": "可见来源材料支持该项语义核对。",
        "comparisonEvidenceIds": [evidence_id],
    }


def fixed_hints(*, exclude: tuple[str, ...] = ()) -> list[dict[str, Any]]:
    return [
        info_hint(category)
        for category in FIXED_4_1_REQUIRED
        if category not in exclude
    ]


def contract_body_hints(*, exclude: tuple[str, ...] = ()) -> list[dict[str, Any]]:
    hints = fixed_hints(exclude=exclude)
    if "TERM_TERMINATION" not in exclude:
        hints.append(info_hint("TERM_TERMINATION"))
    return hints


def review_breach(
    description: str,
    *,
    evidence_id: str = "C:S1",
    title: str = "违约责任比例差异",
) -> dict[str, Any]:
    return {
        "category": "BREACH_LIABILITY",
        "attention": "REVIEW",
        "title": title,
        "description": description,
        "problemEvidenceIds": [evidence_id],
    }


def info_breach(description: str) -> dict[str, Any]:
    return {
        "category": "BREACH_LIABILITY",
        "attention": "INFO",
        "title": "违约责任金额核对",
        "description": description,
        "comparisonEvidenceIds": ["C:S1"],
    }


def completion(*hints: dict[str, Any]) -> dict[str, Any]:
    return {"summary": "已完成合同语义分析。", "hints": list(hints)}


def correction(*hints: dict[str, Any]) -> dict[str, Any]:
    return {"missingCategoryHints": list(hints)}


def post_contract(
    gateway: ModelGatewayPort,
    *,
    demo: bool,
    source: str = QUANTIFIED_BREACH_SOURCE,
    request_id: str = "demo-breach-test",
):
    client = TestClient(
        create_app(
            settings=Settings(
                model_chunk_chars=2000,
                demo_relax_breach_monetary=demo,
            ),
            model_gateway=gateway,
        )
    )
    return client.post(
        "/ai/v1/agent/contract-text-analysis",
        headers={"X-Request-Id": request_id},
        json={
            "projectId": "synthetic-demo-project",
            "materials": [
                {
                    "fileId": "contract",
                    "category": "CONTRACT_BODY",
                    "text": source,
                }
            ],
        },
    )


def test_strict_mode_keeps_existing_monetary_rejection_for_breach() -> None:
    monetary = review_breach(
        "合同未约定累计上限，而招标文件约定累计违约金不超过合同金额10%，建议复核。"
    )
    gateway = SequencedGateway(
        [completion(monetary, *contract_body_hints()), correction(monetary)]
    )

    response = post_contract(gateway, demo=False)

    assert response.status_code == 502
    assert len(gateway.operations) == 2
    assert gateway.operations[1].purpose == "SEMANTIC_COMPLETENESS_CORRECTION"


def test_demo_analyze_sanitizes_monetary_breach_without_missing_category_retry(
    caplog,
) -> None:
    provider_title = "违约金上限10%差异"
    provider_description = (
        "合同未约定累计上限，而招标文件约定累计违约金不超过合同金额10%，建议复核。"
    )
    gateway = SequencedGateway(
        [
            completion(
                review_breach(provider_description, title=provider_title),
                *contract_body_hints(),
            )
        ]
    )
    caplog.set_level(logging.INFO, logger="audit_ai.model.router")

    response = post_contract(
        gateway,
        demo=True,
        request_id="demo-analyze-sanitize",
    )

    assert response.status_code == 200
    assert len(gateway.operations) == 1
    breach = next(
        hint
        for hint in response.json()["data"]["hints"]
        if hint["category"] == "BREACH_LIABILITY"
    )
    assert breach["attention"] == "REVIEW"
    assert breach["title"] == SAFE_REVIEW_TITLE
    assert breach["description"] == SAFE_REVIEW_DESCRIPTION
    assert provider_title not in response.text
    assert provider_description not in response.text
    assert not any(
        operation.purpose == "SEMANTIC_COMPLETENESS_CORRECTION"
        for operation in gateway.operations
    )
    active = next(
        record.audit_fields
        for record in caplog.records
        if record.getMessage().startswith("contract demo relaxation active")
    )
    assert active == {
        "operation": "CONTRACT_TEXT_ANALYSIS",
        "request_id": "demo-analyze-sanitize",
        "demo_relaxation": "BREACH_MONETARY_FAIL_SOFT",
    }
    sanitized = next(
        record.audit_fields
        for record in caplog.records
        if record.getMessage().startswith(
            "contract demo breach monetary text sanitized"
        )
    )
    assert sanitized == {
        "operation": "CONTRACT_TEXT_ANALYSIS",
        "request_id": "demo-analyze-sanitize",
        "category": "BREACH_LIABILITY",
        "attention": "REVIEW",
        "original_rejection_reason": "MONETARY_CALCULATION",
    }
    for forbidden in (
        provider_title,
        provider_description,
        "10%",
        "C:S1",
        QUANTIFIED_BREACH_SOURCE,
    ):
        assert forbidden not in caplog.text


def test_demo_correction_sanitizes_monetary_missing_breach_delta() -> None:
    gateway = SequencedGateway(
        [
            completion(*contract_body_hints()),
            correction(
                review_breach(
                    "采购文件约定违约金比例为合同金额10%，该差异需要复核。"
                )
            ),
        ]
    )

    response = post_contract(gateway, demo=True)

    assert response.status_code == 200
    assert len(gateway.operations) == 2
    assert gateway.operations[1].purpose == "SEMANTIC_COMPLETENESS_CORRECTION"
    breach = next(
        hint
        for hint in response.json()["data"]["hints"]
        if hint["category"] == "BREACH_LIABILITY"
    )
    assert breach["attention"] == "REVIEW"
    assert breach["title"] == SAFE_REVIEW_TITLE
    assert breach["description"] == SAFE_REVIEW_DESCRIPTION


def test_demo_info_attention_is_preserved_and_absolute_amounts_never_escape() -> None:
    gateway = SequencedGateway(
        [
            completion(
                info_breach("合同金额407000元的1%为4070元。"),
                *contract_body_hints(),
            )
        ]
    )

    response = post_contract(gateway, demo=True)

    assert response.status_code == 200
    assert len(gateway.operations) == 1
    breach = next(
        hint
        for hint in response.json()["data"]["hints"]
        if hint["category"] == "BREACH_LIABILITY"
    )
    assert breach["attention"] == "INFO"
    assert breach["title"] == SAFE_INFO_TITLE
    assert breach["description"] == SAFE_INFO_DESCRIPTION
    assert "407000" not in response.text
    assert "4070" not in response.text


def test_demo_does_not_relax_strong_verdict_filter() -> None:
    strong = review_breach("该合同完全合规，无任何风险。")
    gateway = SequencedGateway(
        [
            completion(strong, *contract_body_hints()),
            correction(review_breach("违约责任条款存在差异，建议人工复核。")),
        ]
    )

    response = post_contract(gateway, demo=True)

    assert response.status_code == 200
    assert len(gateway.operations) == 2
    assert gateway.operations[1].purpose == "SEMANTIC_COMPLETENESS_CORRECTION"
    assert "完全合规" not in response.text
    assert "无任何风险" not in response.text


def test_demo_does_not_relax_invalid_problem_evidence() -> None:
    gateway = SequencedGateway(
        [
            completion(
                review_breach(
                    "合同金额407000元的1%为4070元，建议人工复核。",
                    evidence_id="C:S999",
                ),
                *contract_body_hints(),
            ),
            {
                "evidenceRepairs": [
                    {
                        "hintId": "A1:H1",
                        "problemEvidenceIds": ["C:S999"],
                        "comparisonEvidenceIds": [],
                    }
                ]
            },
        ]
    )

    response = post_contract(gateway, demo=True)

    assert response.status_code == 502
    assert len(gateway.operations) == 2
    assert gateway.operations[1].purpose == "EVIDENCE_REPAIR"


def test_demo_does_not_relax_non_breach_monetary_hint() -> None:
    unsafe_service = {
        "category": "SERVICE_WARRANTY",
        "attention": "REVIEW",
        "title": "服务金额计算",
        "description": "合同金额407000元的1%为4070元，建议复核服务责任。",
        "problemEvidenceIds": ["C:S1"],
    }
    gateway = SequencedGateway(
        [
            completion(unsafe_service, *contract_body_hints(exclude=("SERVICE_WARRANTY",))),
            correction(
                {
                    "category": "SERVICE_WARRANTY",
                    "attention": "REVIEW",
                    "title": "服务责任需复核",
                    "description": "可见材料中的服务责任需要人工复核。",
                    "problemEvidenceIds": ["C:S1"],
                }
            ),
        ]
    )

    response = post_contract(
        gateway,
        demo=True,
        source=GENERAL_CONTRACT_SOURCE,
    )

    assert response.status_code == 200
    assert len(gateway.operations) == 2
    assert gateway.operations[1].purpose == "SEMANTIC_COMPLETENESS_CORRECTION"
    assert "407000" not in response.text
    assert "4070" not in response.text


@pytest.mark.parametrize("category", ["BREACH", "BREACH_TERMS", "PENALTY_TERMS"])
def test_demo_does_not_relax_breach_category_aliases(category: str) -> None:
    aliased = review_breach("amount: 407000; 1 * 407000 = 407000")
    aliased["category"] = category
    gateway = SequencedGateway(
        [
            completion(aliased, *contract_body_hints()),
            correction(
                review_breach("The breach clause requires manual review.")
            ),
        ]
    )

    response = post_contract(gateway, demo=True)

    assert response.status_code == 200
    assert len(gateway.operations) == 2
    assert gateway.operations[1].purpose == "SEMANTIC_COMPLETENESS_CORRECTION"


@pytest.mark.parametrize("attention", ["PASS", "NORMAL", "WARNING", "WARN"])
def test_demo_does_not_relax_attention_aliases(attention: str) -> None:
    aliased = (
        info_breach("amount: 407000; 1 * 407000 = 407000")
        if attention in {"PASS", "NORMAL"}
        else review_breach("amount: 407000; 1 * 407000 = 407000")
    )
    aliased["attention"] = attention
    gateway = SequencedGateway(
        [
            completion(aliased, *contract_body_hints()),
            correction(
                review_breach("The breach clause requires manual review.")
            ),
        ]
    )

    response = post_contract(gateway, demo=True)

    assert response.status_code == 200
    assert len(gateway.operations) == 2
    assert gateway.operations[1].purpose == "SEMANTIC_COMPLETENESS_CORRECTION"
