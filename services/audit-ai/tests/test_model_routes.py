import json
import logging
from typing import Any

import httpx
import pytest
from fastapi.testclient import TestClient

from audit_ai.config import Settings
from audit_ai.errors import AuditAiError
from audit_ai.main import create_app
from audit_ai.model.gateway import (
    BudgetSemanticOperation,
    ContractTextOperation,
    ModelCompletion,
    ModelGatewayFailure,
    ModelNotConfigured,
    ModelGatewayPort,
    OpenAiCompatibleModelGateway,
)
from audit_ai.model.router import ContractChunkResult, ModelApplicationService


FIXED_4_1_CATEGORIES = (
    "CONTRACT_STRUCTURE",
    "PROJECT_IDENTITY",
    "SUPPLIER_IDENTITY",
    "DELIVERY_TERMS",
    "SERVICE_WARRANTY",
    "SCOPE_AND_SUBCONTRACTING",
)
CONTRACT_BODY_REQUIRED_CATEGORIES = (
    *FIXED_4_1_CATEGORIES,
    "TERM_TERMINATION",
)


class RecordingModelGateway(ModelGatewayPort):
    def __init__(self) -> None:
        self.contract_operations: list[ContractTextOperation] = []
        self.budget_operations: list[BudgetSemanticOperation] = []
        self.invalid_contract_result = False
        self.contract_payload: dict[str, Any] | None = None

    def analyze_contract(self, operation: ContractTextOperation) -> ModelCompletion:
        self.contract_operations.append(operation)
        payload: dict[str, Any]
        if self.contract_payload is not None:
            payload = self.contract_payload
        elif self.invalid_contract_result:
            payload = {"summary": "文字核对", "hints": "not-a-list"}
        else:
            payload = {
                "summary": "合同范围与工期条款已识别",
                "hints": [
                    {
                        "category": "DELIVERY_TERMS",
                        "attention": "INFO",
                        "title": "工期表述",
                        "description": "建议人工核对开工条件",
                        "comparisonEvidenceIds": ["C:S1"],
                    }
                ],
            }
        return ModelCompletion(
            payload=with_contract_structure(payload),
            model="qwen-test",
            duration_ms=7,
        )

    def analyze_budget(self, operation: BudgetSemanticOperation) -> ModelCompletion:
        self.budget_operations.append(operation)
        return ModelCompletion(
            payload={
                "items": [
                    {
                        "itemId": "item-1",
                        "suggestedCategory": "办公设备",
                        "hint": "描述包含打印设备",
                    }
                ]
            },
            model="qwen-test",
            duration_ms=5,
        )


class SequencedContractGateway(ModelGatewayPort):
    def __init__(self, outcomes: list[ModelCompletion | Exception]) -> None:
        self.outcomes = list(outcomes)
        self.contract_operations: list[ContractTextOperation] = []

    def analyze_contract(self, operation: ContractTextOperation) -> ModelCompletion:
        self.contract_operations.append(operation)
        outcome = self.outcomes.pop(0)
        if isinstance(outcome, Exception):
            raise outcome
        if operation.purpose == "ANALYZE":
            return ModelCompletion(
                payload=with_contract_structure(dict(outcome.payload)),
                model=outcome.model,
                duration_ms=outcome.duration_ms,
            )
        return outcome

    def analyze_budget(self, _operation: BudgetSemanticOperation) -> ModelCompletion:
        raise AssertionError("budget operation was not expected")


def contract_completion(payload: dict[str, Any], duration_ms: int) -> ModelCompletion:
    return ModelCompletion(payload=payload, model="qwen-test", duration_ms=duration_ms)


def with_contract_structure(payload: dict[str, Any]) -> dict[str, Any]:
    """Keep legacy route fixtures complete under the contract-body coverage gate."""
    hints = payload.get("hints")
    if not isinstance(hints, list):
        return payload
    category_aliases = {
        "STRUCTURE": "CONTRACT_STRUCTURE",
        "TERM": "TERM_TERMINATION",
        "CONTRACT_TERM": "TERM_TERMINATION",
        "TERMINATION": "TERM_TERMINATION",
    }

    def has_usable_fixture_evidence(hint: dict[str, Any]) -> bool:
        if (
            hint.get("problemEvidenceIds")
            or hint.get("comparisonEvidenceIds")
            or hint.get("unavailableMaterialCategory")
        ):
            return True
        refs = hint.get("evidenceRefs") or []
        items = hint.get("evidenceItems") or []
        return any(ref != "invented-file" for ref in refs) or any(
            item.get("fileId") != "invented-file"
            for item in items
            if isinstance(item, dict)
        )

    existing_categories = {
        category_aliases.get(category, category)
        for hint in hints
        if isinstance(hint, dict)
        and has_usable_fixture_evidence(hint)
        and (category := str(hint.get("category", "")).upper())
    }
    return {
        **payload,
        "hints": [
            *hints,
            *({
                "category": category,
                "attention": "INFO",
                "title": f"{category} coverage",
                "description": "The contract body supports this bounded observation.",
                "comparisonEvidenceIds": ["C:S1"],
            } for category in CONTRACT_BODY_REQUIRED_CATEGORIES
            if category not in existing_categories),
        ],
    }


def safe_contract_payload() -> dict[str, Any]:
    return with_contract_structure({
        "summary": "已完成合同文字条款语义核对",
        "hints": [
            {
                "category": "DELIVERY_TERMS",
                "attention": "REVIEW",
                "title": "交付期限",
                "description": "交付期限存在表述差异，建议结合原始材料复核。",
                "evidenceRefs": ["file-1"],
                "evidenceItems": [{
                    "fileId": "file-1", "status": "REVIEW", "excerpt": "交付",
                }],
            }
        ],
    })


def safe_contract_payload_with_draft_evidence(
    evidence_id: str,
) -> dict[str, Any]:
    payload = safe_contract_payload()
    delivery_hint = payload["hints"][0]
    delivery_hint.pop("evidenceRefs", None)
    delivery_hint.pop("evidenceItems", None)
    delivery_hint["problemEvidenceIds"] = [evidence_id]
    for hint in payload["hints"][1:]:
        hint["comparisonEvidenceIds"] = [evidence_id]
    return payload


def safe_contract_payload_for(
    operation: ContractTextOperation,
) -> dict[str, Any]:
    """Give multi-chunk mocks only coverage owned by the current operation."""
    evidence_id = "C:S1"
    if operation.purpose == "ANALYZE":
        first_line = operation.source_text.splitlines()[0]
        if first_line.startswith("[evidenceId="):
            evidence_id = first_line.removeprefix("[evidenceId=").split(";", 1)[0]
    elif operation.purpose == "SYNTHESIZE":
        compact = json.loads(operation.source_text)
        evidence_id = compact["evidenceCatalog"][0]["evidenceId"]
    payload = safe_contract_payload_with_draft_evidence(evidence_id)
    if operation.purpose == "ANALYZE":
        payload["hints"] = payload["hints"][:1]
    return payload


def mixed_safe_and_monetary_contract_payload() -> dict[str, Any]:
    return {
        "summary": "已完成合同文字条款语义核对",
        "hints": [
            {
                "category": "PROJECT_IDENTITY",
                "attention": "INFO",
                "title": "项目标识",
                "description": "项目编号与项目名称已识别。",
                "evidenceRefs": ["file-1"],
            },
            {
                "category": "SUPPLIER_IDENTITY",
                "attention": "INFO",
                "title": "供应商信息",
                "description": "供应商名称与统一社会信用代码已识别。",
                "evidenceRefs": ["file-2"],
            },
            {
                "category": "DELIVERY_TERMS",
                "attention": "REVIEW",
                "title": "交付期限",
                "description": "建议复核不同材料中的交付期限表述。",
                "evidenceRefs": ["file-1", "file-3"],
                "evidenceItems": [{
                    "fileId": "file-3", "status": "REVIEW", "excerpt": "条款",
                }],
            },
            {
                "category": "SERVICE_WARRANTY",
                "attention": "INFO",
                "title": "服务时间",
                "description": "供应商提供7×24小时技术支持。",
                "evidenceRefs": ["file-1"],
            },
            {
                "category": "SCOPE_AND_SUBCONTRACTING",
                "attention": "REVIEW",
                "title": "分包限制",
                "description": "建议结合补充材料复核分包限制。",
                "evidenceRefs": ["file-4"],
                "evidenceItems": [{
                    "fileId": "file-4", "status": "REVIEW", "excerpt": "分包条款",
                }],
            },
            {
                "category": "OTHER_TEXT",
                "attention": "REVIEW",
                "title": "报价结论",
                "description": "报价高于预算20%。",
                "evidenceRefs": ["file-2"],
            },
        ],
    }


def contract_request() -> dict[str, Any]:
    return {
        "projectId": "project-1",
        "materials": [
            {
                "fileId": "file-1",
                "category": "CONTRACT_BODY",
                "text": "交付期限为20个工作日，提供7×24小时技术支持。",
            }
        ],
    }


def assert_only_contract_structure(response) -> None:
    assert [
        hint["category"] for hint in response.json()["data"]["hints"]
    ] == list(CONTRACT_BODY_REQUIRED_CATEGORIES)


def test_contract_provider_schema_drift_is_normalized_per_hint_without_retry() -> None:
    payload = {
        "summary": "合同审核通过，不存在风险。",
        "providerTrace": "ignored-extra-field",
        "hints": [
            {
                "category": "delivery_terms",
                "attention": "warn",
                "title": "交付期限表述差异",
                "description": "建议复核不同材料中的交付期限表述。",
                "evidenceRefs": ["file-1", "invented-file", "file-1"],
                "evidenceItems": [
                    {
                        "fileId": "file-1",
                        "status": "warning",
                        "excerpt": "交付期限为20个工作日",
                        "locatorAvailable": True,
                        "providerPage": 99,
                    },
                    {"fileId": "invented-file", "status": "REVIEW"},
                ],
                "reasoning": "ignored-extra-field",
            },
            {
                "category": "provider_new_category",
                "attention": "PASS",
                "title": "服务要求",
                "description": "未发现明显文字冲突。",
                "evidenceRefs": ["file-1"],
            },
            {
                "category": "OTHER_TEXT",
                "attention": "REVIEW",
                "title": "强结论",
                "description": "合同合法有效。",
                "evidenceRefs": ["file-1"],
            },
            {
                "category": "OTHER_TEXT",
                "attention": "REVIEW",
                "title": "金额结论",
                "description": "合同金额为67000元。",
                "evidenceRefs": ["file-1"],
            },
            {
                "category": "OTHER_TEXT",
                "attention": "provider-new-status",
                "title": "未知状态",
                "description": "不应展示。",
                "evidenceRefs": ["file-1"],
            },
            "malformed-hint",
            {
                "category": "OTHER_TEXT",
                "attention": "INFO",
                "title": "无真实来源",
                "description": "不应展示。",
                "evidenceRefs": ["invented-file"],
            },
        ],
    }
    gateway = SequencedContractGateway([
        contract_completion(payload, 13),
        contract_completion(payload, 13),
    ])
    response = TestClient(create_app(settings=settings(), model_gateway=gateway)).post(
        "/ai/v1/agent/contract-text-analysis", json=contract_request()
    )

    assert response.status_code == 200
    assert len(gateway.contract_operations) == 1
    data = response.json()["data"]
    assert data["summaries"] == [
        "已完成合同文字语义分析，请结合来源材料进行人工复核。"
    ]
    assert [(hint["category"], hint["attention"]) for hint in data["hints"]] == [
        ("DELIVERY_TERMS", "REVIEW"),
        ("OTHER_TEXT", "INFO"),
        ("CONTRACT_STRUCTURE", "INFO"),
        ("PROJECT_IDENTITY", "INFO"),
        ("SUPPLIER_IDENTITY", "INFO"),
        ("SERVICE_WARRANTY", "INFO"),
        ("SCOPE_AND_SUBCONTRACTING", "INFO"),
        ("TERM_TERMINATION", "INFO"),
    ]
    assert data["hints"][0]["evidenceRefs"] == ["file-1"]
    assert data["hints"][0]["evidenceItems"] == [{
        "fileId": "file-1",
        "status": "REVIEW",
        "excerpt": "交付期限为20个工作日",
    }]
    assert "locatorAvailable" not in response.text
    assert "invented-file" not in response.text
    assert "67000" not in response.text


@pytest.mark.parametrize(
    ("provider_category", "expected_category"),
    [
        ("CONTRACT_STRUCTURE", "CONTRACT_STRUCTURE"),
        ("STRUCTURE", "CONTRACT_STRUCTURE"),
        ("BREACH_LIABILITY", "BREACH_LIABILITY"),
        ("BREACH", "BREACH_LIABILITY"),
        ("BREACH_TERMS", "BREACH_LIABILITY"),
        ("PENALTY_TERMS", "BREACH_LIABILITY"),
        ("TERM_TERMINATION", "TERM_TERMINATION"),
        ("TERM", "TERM_TERMINATION"),
        ("CONTRACT_TERM", "TERM_TERMINATION"),
        ("TERMINATION", "TERM_TERMINATION"),
    ],
)
def test_contract_pm44_categories_and_provider_aliases_are_normalized_without_retry(
    provider_category: str, expected_category: str
) -> None:
    gateway = RecordingModelGateway()
    gateway.contract_payload = {
        "summary": "已完成合同文字语义核对",
        "hints": [{
            "category": provider_category,
            "attention": "REVIEW",
            "title": "条款提示",
            "description": "存在差异，建议复核。",
            "evidenceRefs": ["file-1"],
            "evidenceItems": [{
                "fileId": "file-1", "status": "REVIEW",
                "excerpt": "交付期限为20个工作日",
            }],
        }],
    }
    client = TestClient(create_app(settings=settings(), model_gateway=gateway))

    response = client.post(
        "/ai/v1/agent/contract-text-analysis", json=contract_request()
    )

    assert response.status_code == 200
    assert response.json()["data"]["hints"][0]["category"] == expected_category
    assert len(gateway.contract_operations) == 1


def test_contract_unprocessable_top_level_hints_uses_single_retry() -> None:
    gateway = SequencedContractGateway([
        contract_completion({"summary": "文字核对", "hints": "not-a-list"}, 5),
        contract_completion(safe_contract_payload(), 7),
    ])

    response = TestClient(create_app(settings=settings(), model_gateway=gateway)).post(
        "/ai/v1/agent/contract-text-analysis", json=contract_request()
    )

    assert response.status_code == 200
    assert len(gateway.contract_operations) == 2
    assert gateway.contract_operations[1].retry_reason == "UNPROCESSABLE_TOP_LEVEL"


def test_contract_terminal_unprocessable_top_level_logs_safe_final_reason(
    caplog,
) -> None:
    source_sentinel = "SOURCE-TEXT-SENTINEL-DO-NOT-LOG"
    provider_content_sentinel = "PROVIDER-CONTENT-SENTINEL-DO-NOT-LOG"
    malformed = {
        "summary": provider_content_sentinel,
        "hints": provider_content_sentinel,
    }
    gateway = SequencedContractGateway([
        contract_completion(malformed, 5),
        contract_completion(malformed, 7),
    ])
    caplog.set_level(logging.INFO, logger="audit_ai.model.router")
    request = contract_request()
    request["materials"][0]["text"] += source_sentinel

    response = TestClient(
        create_app(settings=settings(), model_gateway=gateway)
    ).post("/ai/v1/agent/contract-text-analysis", json=request)

    assert response.status_code == 502
    rejected = [
        record.audit_fields
        for record in caplog.records
        if record.getMessage().startswith("contract semantic result rejected")
    ]
    assert [item["reason"] for item in rejected] == [
        "UNPROCESSABLE_TOP_LEVEL",
        "UNPROCESSABLE_TOP_LEVEL",
    ]
    assert rejected[-1]["outcome"] == "REJECTED"
    assert rejected[-1]["purpose"] == "ANALYZE"
    assert rejected[-1]["raw_hint_count"] == 0
    assert rejected[-1]["final_model_rejection_reason"] == (
        "UNPROCESSABLE_TOP_LEVEL"
    )
    assert source_sentinel not in caplog.text
    assert provider_content_sentinel not in caplog.text


def settings(**overrides: Any) -> Settings:
    return Settings(**overrides)


def test_contract_and_budget_routes_build_distinct_typed_operations() -> None:
    gateway = RecordingModelGateway()
    client = TestClient(create_app(settings=settings(), model_gateway=gateway))

    contract_response = client.post(
        "/ai/v1/agent/contract-text-analysis",
        json={
            "projectId": "project-1",
            "materials": [
                {
                    "fileId": "file-1",
                    "category": "CONTRACT_BODY",
                    "text": "建设工程施工合同，约定工期。",
                }
            ],
        },
    )
    budget_response = client.post(
        "/ai/v1/agent/budget-semantic-analysis",
        json={
            "sourceId": "budget-1",
            "items": [
                {
                    "itemId": "item-1",
                    "description": "彩色打印设备",
                    "currentCategory": "设备",
                }
            ],
        },
    )

    assert contract_response.status_code == 200
    contract_data = contract_response.json()["data"]
    assert contract_data["operation"] == "CONTRACT_TEXT_ANALYSIS"
    assert contract_data["summaries"] == ["合同范围与工期条款已识别"]
    assert [hint["category"] for hint in contract_data["hints"]] == [
        "DELIVERY_TERMS",
        "CONTRACT_STRUCTURE",
        "PROJECT_IDENTITY",
        "SUPPLIER_IDENTITY",
        "SERVICE_WARRANTY",
        "SCOPE_AND_SUBCONTRACTING",
        "TERM_TERMINATION",
    ]
    assert contract_data["hints"][0] == {
        "category": "DELIVERY_TERMS",
        "attention": "INFO",
        "title": "工期表述",
        "description": "建议人工核对开工条件",
        "evidenceRefs": ["file-1"],
        "evidenceItems": [{
            "fileId": "file-1",
            "status": "NORMAL",
            "excerpt": gateway.contract_operations[0].source_text.splitlines()[-1],
        }],
    }
    assert contract_data["model"] == "qwen-test"
    assert contract_data["durationMs"] == 7
    assert budget_response.status_code == 200
    assert budget_response.json()["data"] == {
        "operation": "BUDGET_SEMANTIC_ANALYSIS",
        "items": [
            {
                "itemId": "item-1",
                "suggestedCategory": "办公设备",
                "hint": "描述包含打印设备",
            }
        ],
        "model": "qwen-test",
        "durationMs": 5,
    }
    assert len(gateway.contract_operations) == 1
    assert len(gateway.budget_operations) == 1
    assert gateway.contract_operations[0].project_id == "project-1"
    assert "建设工程施工合同" in gateway.contract_operations[0].source_text
    assert gateway.budget_operations[0].source_id == "budget-1"
    assert "彩色打印设备" in gateway.budget_operations[0].source_text


def test_invalid_contract_model_json_maps_to_stable_external_error() -> None:
    gateway = RecordingModelGateway()
    gateway.invalid_contract_result = True
    client = TestClient(create_app(settings=settings(), model_gateway=gateway))

    response = client.post(
        "/ai/v1/agent/contract-text-analysis",
        json={
            "projectId": "project-1",
            "materials": [
                {"fileId": "file-1", "category": "CONTRACT_BODY", "text": "正文"}
            ],
        },
    )

    assert response.status_code == 502
    assert response.json()["code"] == "MODEL_INVALID_RESULT"
    assert "正文" not in response.text


def test_contract_route_filters_invalid_evidence_verdict_and_monetary_output_per_hint() -> None:
    unsafe_payloads = [
        ({
            "summary": "交付条款已提取",
            "hints": [{
                "category": "DELIVERY_TERMS", "attention": "INFO",
                "title": "交付期", "description": "文本载明交付期",
                "evidenceRefs": ["invented-file"],
            }],
        }, 200),
        ({
            "summary": "合同审核通过，不存在风险",
            "hints": [],
        }, 200),
        ({
            "summary": "交付条款已提取",
            "hints": [{
                "category": "OTHER_TEXT", "attention": "REVIEW",
                "title": "金额结论", "description": "合同金额与预算一致",
                "evidenceRefs": ["file-1"],
            }],
        }, 200),
        ({
            "summary": "交付条款已提取",
            "hints": [{
                "category": "OTHER_TEXT", "attention": "REVIEW",
                "title": "推测值", "description": "被遮蔽值应为500000.00元",
                "evidenceRefs": ["file-1"],
            }],
        }, 200),
    ]
    for payload, expected_status in unsafe_payloads:
        gateway = RecordingModelGateway()
        gateway.contract_payload = payload
        client = TestClient(create_app(settings=settings(), model_gateway=gateway))
        response = client.post(
            "/ai/v1/agent/contract-text-analysis",
            json={
                "projectId": "project-1",
                "materials": [{
                    "fileId": "file-1", "category": "CONTRACT_BODY",
                    "text": "交付期20个工作日，[确定性数值字段已移除]。",
                }],
            },
        )
        assert response.status_code == expected_status
        assert_only_contract_structure(response)
        assert "500000.00" not in response.text


def test_contract_route_accepts_safe_reference_to_deterministic_amount_boundary() -> None:
    gateway = RecordingModelGateway()
    gateway.contract_payload = {
        "summary": "已完成文字条款语义核对",
        "hints": [{
            "category": "DELIVERY_TERMS",
            "attention": "INFO",
            "title": "交付期限",
            "description": "交付期限已经明确。本项不涉及金额判断。",
            "evidenceRefs": ["file-1"],
        }],
    }
    client = TestClient(create_app(settings=settings(), model_gateway=gateway))

    response = client.post(
        "/ai/v1/agent/contract-text-analysis",
        json={
            "projectId": "project-1",
            "materials": [{
                "fileId": "file-1",
                "category": "CONTRACT_BODY",
                "text": "交付期限为20个工作日。[确定性数值字段已移除]",
            }],
        },
    )

    assert response.status_code == 200
    assert response.json()["data"]["hints"][0]["description"] == (
        "交付期限已经明确。本项不涉及金额判断。"
    )


def test_contract_evidence_items_leave_locator_validation_to_java() -> None:
    gateway = RecordingModelGateway()
    gateway.contract_payload = {
        "summary": "已完成供应商文字信息核对",
        "hints": [{
            "category": "SUPPLIER_IDENTITY", "attention": "REVIEW",
            "title": "供应商信息", "description": "建议复核供应商名称。",
            "evidenceRefs": ["file-1"],
            "evidenceItems": [{
                "fileId": "file-1", "status": "REVIEW",
                "excerpt": "中标供应商: 测试科技有限公司",
            }],
        }],
    }
    client = TestClient(create_app(settings=settings(), model_gateway=gateway))

    response = client.post("/ai/v1/agent/contract-text-analysis", json={
        "projectId": "project-1",
        "materials": [{
            "fileId": "file-1", "category": "AWARD_NOTICE",
            "text": "中标供应商：  测试科技有限公司",
        }],
    })

    assert response.status_code == 200
    assert response.json()["data"]["hints"][0]["evidenceItems"] == [{
        "fileId": "file-1", "status": "REVIEW",
        "excerpt": "中标供应商: 测试科技有限公司",
    }]


def test_contract_review_missing_problem_evidence_retries_once_then_accepts_exact_07_excerpts() -> None:
    first = {
        "summary": "服务响应存在差异",
        "hints": [{
            "category": "SERVICE_WARRANTY", "attention": "REVIEW",
            "title": "服务响应差异", "description": "建议复核响应与到场时限。",
            "evidenceRefs": ["contract", "bid"],
        }],
    }
    second = {
        "evidenceRepairs": [{
            "hintId": "A1:H1",
            "problemEvidenceIds": ["C:S1"],
            "comparisonEvidenceIds": ["B:S2"],
        }],
    }
    gateway = SequencedContractGateway([
        contract_completion(first, 10), contract_completion(second, 12),
    ])
    request = {"projectId": "project-07", "materials": [
        {"fileId": "contract", "category": "CONTRACT_BODY",
         "text": "服务时间：技术支持时间为工作日9:00-17:00；重大故障4小时内响应，48小时内到场"},
        {"fileId": "bid", "category": "BID_RESPONSE",
         "text": "服务响应：承诺7×24小时故障受理；重大故障1小时内响应，24小时内到场"},
    ]}

    response = TestClient(create_app(settings=settings(), model_gateway=gateway)).post(
        "/ai/v1/agent/contract-text-analysis", json=request
    )

    assert response.status_code == 200
    assert response.json()["data"]["hints"][0]["evidenceItems"] == [
        {
            "fileId": "contract",
            "status": "REVIEW",
            "excerpt": request["materials"][0]["text"],
        },
        {
            "fileId": "bid",
            "status": "NORMAL",
            "excerpt": request["materials"][1]["text"],
        },
    ]
    assert len(gateway.contract_operations) == 2
    assert gateway.contract_operations[1].retry_reason == "MISSING_OR_UNVERIFIED_PROBLEM_EVIDENCE"
    assert gateway.contract_operations[1].purpose == "EVIDENCE_REPAIR"
    assert [operation.attempt for operation in gateway.contract_operations] == [1, 2]


def test_contract_invented_problem_excerpt_fails_closed_after_exactly_two_attempts() -> None:
    invalid = {
        "summary": "供应商文字信息存在差异",
        "hints": [{
            "category": "SUPPLIER_IDENTITY", "attention": "REVIEW",
            "title": "供应商信息", "description": "建议复核供应商名称。",
            "evidenceRefs": ["file-1"],
            "evidenceItems": [{
                "fileId": "file-1", "status": "REVIEW", "excerpt": "模型编造的原文",
            }],
        }],
    }
    gateway = SequencedContractGateway([
        contract_completion(invalid, 10), contract_completion(invalid, 12),
    ])
    request = {"projectId": "project-1", "materials": [{
        "fileId": "file-1", "category": "CONTRACT_BODY", "text": "合同真实文字",
    }]}

    response = TestClient(create_app(settings=settings(), model_gateway=gateway)).post(
        "/ai/v1/agent/contract-text-analysis", json=request
    )

    assert response.status_code == 502
    assert response.json()["code"] == "MODEL_INVALID_RESULT"
    assert len(gateway.contract_operations) == 2
    assert gateway.contract_operations[1].retry_reason == "MISSING_OR_UNVERIFIED_PROBLEM_EVIDENCE"


def test_contract_explicit_unavailable_insufficient_evidence_does_not_invent_excerpt_or_retry() -> None:
    payload = {
        "summary": "必要响应材料缺失",
        "hints": [{
            "category": "SERVICE_WARRANTY", "attention": "INSUFFICIENT",
            "title": "缺少响应材料", "description": "未提供可用于核对服务承诺的响应材料。",
            "evidenceRefs": ["contract"],
            "evidenceItems": [{
                "fileId": "contract", "status": "INSUFFICIENT", "excerpt": None,
                "unavailableMaterialCategory": "BID_RESPONSE",
            }],
        }],
    }
    gateway = SequencedContractGateway([contract_completion(payload, 10)])
    request = {"projectId": "project-missing", "materials": [{
        "fileId": "contract", "category": "CONTRACT_BODY", "text": "合同未附投标响应材料",
    }]}

    response = TestClient(create_app(settings=settings(), model_gateway=gateway)).post(
        "/ai/v1/agent/contract-text-analysis", json=request
    )

    assert response.status_code == 200
    assert response.json()["data"]["hints"][0]["evidenceItems"][0].get("excerpt") is None
    assert len(gateway.contract_operations) == 1


def test_contract_visible_insufficient_anomaly_cannot_claim_unavailable_locator() -> None:
    invalid = {
        "summary": "签约日期未填写",
        "hints": [{
            "category": "TERM_TERMINATION", "attention": "INSUFFICIENT",
            "title": "签约日期缺失", "description": "签约日期栏为空。",
            "evidenceRefs": ["contract"],
            "evidenceItems": [{
                "fileId": "contract", "status": "INSUFFICIENT", "excerpt": None,
            }],
        }],
    }
    gateway = SequencedContractGateway([
        contract_completion(invalid, 10), contract_completion(invalid, 12),
    ])
    request = {"projectId": "project-visible-blank", "materials": [{
        "fileId": "contract", "category": "CONTRACT_BODY",
        "text": "签约日期：    年  月  日",
    }]}

    response = TestClient(create_app(settings=settings(), model_gateway=gateway)).post(
        "/ai/v1/agent/contract-text-analysis", json=request
    )

    assert response.status_code == 502
    assert response.json()["code"] == "MODEL_INVALID_RESULT"
    assert len(gateway.contract_operations) == 2
    assert gateway.contract_operations[1].retry_reason == "MISSING_OR_UNVERIFIED_PROBLEM_EVIDENCE"


@pytest.mark.parametrize(("title", "description"), [
    ("签约日期缺失", "签约日期栏为空，建议结合投标响应复核。"),
    ("投标响应并非缺失", "签约日期栏为空。"),
])
def test_contract_visible_anomaly_cannot_borrow_an_unrelated_absent_material_category(
    title: str, description: str,
) -> None:
    invalid = {
        "summary": "签约日期未填写",
        "hints": [{
            "category": "TERM_TERMINATION", "attention": "INSUFFICIENT",
            "title": title, "description": description,
            "evidenceRefs": ["contract"],
            "evidenceItems": [{
                "fileId": "contract", "status": "INSUFFICIENT", "excerpt": None,
                "unavailableMaterialCategory": "BID_RESPONSE",
            }],
        }],
    }
    gateway = SequencedContractGateway([
        contract_completion(invalid, 10), contract_completion(invalid, 12),
    ])
    request = {"projectId": "project-visible-blank", "materials": [{
        "fileId": "contract", "category": "CONTRACT_BODY",
        "text": "签约日期：    年  月  日",
    }]}

    response = TestClient(create_app(settings=settings(), model_gateway=gateway)).post(
        "/ai/v1/agent/contract-text-analysis", json=request
    )

    assert response.status_code == 502
    assert response.json()["code"] == "MODEL_INVALID_RESULT"
    assert len(gateway.contract_operations) == 2
    assert gateway.contract_operations[1].retry_reason == "MISSING_OR_UNVERIFIED_PROBLEM_EVIDENCE"


def test_contract_info_invented_excerpt_is_removed_without_problem_evidence_retry() -> None:
    payload = {
        "summary": "已识别服务条款",
        "hints": [{
            "category": "SERVICE_WARRANTY", "attention": "INFO",
            "title": "服务条款", "description": "已识别服务响应文字。",
            "evidenceRefs": ["contract"],
            "evidenceItems": [{
                "fileId": "contract", "status": "NORMAL", "excerpt": "模型编造的服务原文",
            }],
        }],
    }
    gateway = SequencedContractGateway([contract_completion(payload, 10)])
    request = {"projectId": "project-info", "materials": [{
        "fileId": "contract", "category": "CONTRACT_BODY", "text": "真实服务条款",
    }]}

    response = TestClient(create_app(settings=settings(), model_gateway=gateway)).post(
        "/ai/v1/agent/contract-text-analysis", json=request
    )

    assert response.status_code == 200
    assert response.json()["data"]["hints"][0]["evidenceItems"] == []
    assert len(gateway.contract_operations) == 1


def test_contract_breach_completeness_consumes_shared_retry_before_problem_evidence() -> None:
    missing_breach_and_evidence = {
        "summary": "服务条款待复核",
        "hints": [{
            "category": "SERVICE_WARRANTY", "attention": "REVIEW",
            "title": "服务条款", "description": "建议复核。", "evidenceRefs": ["file-1"],
        }],
    }
    breach_without_evidence = {
        "summary": "违约责任待复核",
        "hints": [{
            "category": "BREACH_LIABILITY", "attention": "REVIEW",
            "title": "违约责任", "description": "合同约定按周计取违约金。",
            "evidenceRefs": ["file-1"],
        }],
    }
    gateway = SequencedContractGateway([
        contract_completion(missing_breach_and_evidence, 10),
        contract_completion(breach_without_evidence, 12),
    ])
    request = {"projectId": "project-breach", "materials": [{
        "fileId": "file-1", "category": "CONTRACT_BODY",
        "text": "逾期交付按合同金额1%/周支付违约金。",
    }]}

    response = TestClient(create_app(settings=settings(), model_gateway=gateway)).post(
        "/ai/v1/agent/contract-text-analysis", json=request
    )

    assert response.status_code == 502
    assert response.json()["code"] == "MODEL_INVALID_RESULT"
    assert len(gateway.contract_operations) == 2
    assert gateway.contract_operations[1].retry_reason == "MISSING_REQUIRED_BREACH_CATEGORY"


def test_contract_later_chunk_reports_one_attempt_after_shared_retry_is_consumed() -> None:
    class FailOnceThenSafeGateway(ModelGatewayPort):
        def __init__(self) -> None:
            self.operations: list[ContractTextOperation] = []

        def analyze_contract(self, operation: ContractTextOperation) -> ModelCompletion:
            self.operations.append(operation)
            if len(self.operations) == 1:
                raise ModelGatewayFailure(
                    reason="READ_TIMEOUT", retryable=True, duration_ms=60_000
                )
            return contract_completion(safe_contract_payload_for(operation), 7)

        def analyze_budget(self, _operation: BudgetSemanticOperation) -> ModelCompletion:
            raise AssertionError("budget operation was not expected")

    gateway = FailOnceThenSafeGateway()
    request = contract_request()
    request["materials"][0]["text"] *= 20

    response = TestClient(create_app(
        settings=settings(model_chunk_chars=120, model_max_chunks=20),
        model_gateway=gateway,
    )).post("/ai/v1/agent/contract-text-analysis", json=request)

    assert response.status_code == 200
    analyze_operations = [
        operation for operation in gateway.operations if operation.purpose == "ANALYZE"
    ]
    assert len(analyze_operations) > 2
    assert [
        (
            operation.attempt,
            operation.transport_attempt,
            operation.max_transport_attempts,
        )
        for operation in analyze_operations[:2]
    ] == [
        (1, 1, 2), (1, 2, 2),
    ]
    assert all(operation.max_attempts == 2 for operation in analyze_operations[2:])
    assert all(
        operation.max_transport_attempts == 1
        for operation in analyze_operations[2:]
    )


def test_contract_route_allows_non_monetary_arithmetic_and_percentages() -> None:
    allowed_descriptions = [
        "供应商提供7×24小时技术支持。",
        "工作日提供5×8小时现场支持。",
        "设备要求100%兼容现有系统。",
        "系统目标可用性为99.9%。",
        "采用3-2-1备份策略。",
        "部署采用2+1冗余架构。",
        "合同约定按合同金额1%/周支付违约金。",
        "按合同金额1%支付违约金。",
        "合同金额1%/周。",
        "合同累计违约金上限20%。",
        "累计违约金最高不超过合同金额20%。",
        "累计不超过合同金额20%。",
        "合同约定累计违约金最高不超过合同金额20%。",
        "累计违约金上限为合同金额20%。",
        "以合同金额20%为违约金累计上限。",
        "以合同金额20%为累计上限。",
        "违约金为合同金额20%。",
        "违约金按合同金额20%计算。",
        "赔偿金按合同金额20%。",
        "合同金额20%作为违约金。",
        "合同约定1%，招标文件约定2%，存在差异，建议复核。",
        "延期超过1个月可以解除合同。",
        "违约比例为5‰/日。",
    ]

    for description in allowed_descriptions:
        gateway = RecordingModelGateway()
        gateway.contract_payload = {
            "summary": "已完成文字条款语义核对",
            "hints": [{
                "category": "SERVICE_WARRANTY",
                "attention": "INFO",
                "title": "服务要求",
                "description": description,
                "evidenceRefs": ["file-1"],
            }],
        }
        client = TestClient(create_app(settings=settings(), model_gateway=gateway))
        response = client.post(
            "/ai/v1/agent/contract-text-analysis",
            json={
                "projectId": "project-1",
                "materials": [{
                    "fileId": "file-1",
                    "category": "CONTRACT_BODY",
                    "text": "服务与技术条款",
                }],
            },
        )
        assert response.status_code == 200, description
        assert response.json()["data"]["hints"][0]["description"] == description


def test_contract_breach_cap_percentage_is_not_filtered_as_monetary_conclusion() -> None:
    payload = {
        "summary": "已完成违约责任文字条款核对",
        "hints": [{
            "category": "BREACH_LIABILITY",
            "attention": "INFO",
            "title": "累计违约金上限",
            "description": "累计违约金最高不超过合同金额20%。",
            "evidenceRefs": ["file-1"],
            "evidenceItems": [{
                "fileId": "file-1",
                "status": "NORMAL",
                "excerpt": "累计违约金最高不超过合同金额20%。",
            }],
        }],
    }
    gateway = RecordingModelGateway()
    gateway.contract_payload = payload
    client = TestClient(create_app(settings=settings(), model_gateway=gateway))
    request = contract_request()
    request["materials"][0]["text"] += "\n累计违约金最高不超过合同金额20%。"

    response = client.post(
        "/ai/v1/agent/contract-text-analysis", json=request
    )

    assert response.status_code == 200
    assert response.json()["data"]["hints"][0] == payload["hints"][0]
    assert [hint["category"] for hint in response.json()["data"]["hints"]] == [
        "BREACH_LIABILITY",
        *CONTRACT_BODY_REQUIRED_CATEGORIES,
    ]


@pytest.mark.parametrize("location", ["description", "excerpt"])
def test_contract_breach_cap_percentage_is_safe_in_description_and_excerpt(
    location: str,
) -> None:
    safe_clause = "累计违约金最高不超过合同金额20%。"
    hint = {
        "category": "BREACH_LIABILITY",
        "attention": "INFO",
        "title": "违约责任条款",
        "description": "建议结合来源材料复核违约责任条款。",
        "evidenceRefs": ["file-1"],
        "evidenceItems": [{
            "fileId": "file-1", "status": "NORMAL", "excerpt": "违约责任条款",
        }],
    }
    if location == "description":
        hint["description"] = safe_clause
    else:
        hint["evidenceItems"][0]["excerpt"] = safe_clause
    gateway = RecordingModelGateway()
    gateway.contract_payload = {"summary": "违约责任条款已识别", "hints": [hint]}

    response = TestClient(
        create_app(settings=settings(), model_gateway=gateway)
    ).post("/ai/v1/agent/contract-text-analysis", json=contract_request())

    assert response.status_code == 200
    assert len(response.json()["data"]["hints"]) == (
        1 + len(CONTRACT_BODY_REQUIRED_CATEGORIES)
    )


def test_contract_breach_cap_percentage_summary_is_retained() -> None:
    summary = "合同违约责任约定累计上限为合同金额20%，建议结合来源材料复核。"
    payload = safe_contract_payload()
    payload["summary"] = summary
    gateway = RecordingModelGateway()
    gateway.contract_payload = payload

    response = TestClient(
        create_app(settings=settings(), model_gateway=gateway)
    ).post("/ai/v1/agent/contract-text-analysis", json=contract_request())

    assert response.status_code == 200
    assert response.json()["data"]["summaries"] == [summary]


@pytest.mark.parametrize("location", ["title", "description", "excerpt"])
def test_contract_comma_separated_monetary_field_comparison_is_filtered(
    location: str,
) -> None:
    comparison = "合同金额与中标金额，二者一致。"
    hint = {
        "category": "OTHER_TEXT",
        "attention": "REVIEW",
        "title": "金额字段复核",
        "description": "建议结合确定性金额校验结果复核。",
        "evidenceRefs": ["file-1"],
        "evidenceItems": [{
            "fileId": "file-1", "status": "REVIEW", "excerpt": "金额字段",
        }],
    }
    if location == "excerpt":
        hint["evidenceItems"][0]["excerpt"] = comparison
    else:
        hint[location] = comparison
    gateway = RecordingModelGateway()
    gateway.contract_payload = {"summary": "文字条款已识别", "hints": [hint]}

    response = TestClient(
        create_app(settings=settings(), model_gateway=gateway)
    ).post("/ai/v1/agent/contract-text-analysis", json=contract_request())

    assert response.status_code == 200
    assert_only_contract_structure(response)
    assert comparison not in response.text


def test_contract_monetary_alias_rate_comparison_is_filtered() -> None:
    comparison = "合同金额20%高于中标价。"
    gateway = RecordingModelGateway()
    gateway.contract_payload = {
        "summary": "文字条款已识别",
        "hints": [{
            "category": "OTHER_TEXT",
            "attention": "REVIEW",
            "title": "金额比例比较",
            "description": comparison,
            "evidenceRefs": ["file-1"],
        }],
    }

    response = TestClient(
        create_app(settings=settings(), model_gateway=gateway)
    ).post("/ai/v1/agent/contract-text-analysis", json=contract_request())

    assert response.status_code == 200
    assert_only_contract_structure(response)
    assert comparison not in response.text


def test_contract_comma_separated_monetary_summary_is_neutralized() -> None:
    comparison = "合同金额与中标金额，二者一致。"
    payload = safe_contract_payload()
    payload["summary"] = comparison
    gateway = RecordingModelGateway()
    gateway.contract_payload = payload

    response = TestClient(
        create_app(settings=settings(), model_gateway=gateway)
    ).post("/ai/v1/agent/contract-text-analysis", json=contract_request())

    assert response.status_code == 200
    assert response.json()["data"]["summaries"] == [
        "已完成合同文字语义分析，请结合来源材料进行人工复核。"
    ]
    assert comparison not in response.text


def test_contract_route_filters_concrete_money_calculations_and_amount_conclusions_after_retry() -> None:
    unsafe_descriptions = [
        "12.5万元",
        "500万元",
        "5,000.00元",
        "5,000元/台",
        "￥5,000",
        "¥5,000",
        "人民币500万元",
        "人民币伍佰万元整",
        "100×5000=500000元",
        "合同金额由100×5000计算得到。",
        "合同金额按100×5000计算得到。",
        "单价5000元×数量100。",
        "单价5000元，数量100，总价500000元。",
        "报价高于预算20%。",
        "节资率为36.4%。",
        "合同金额与中标金额差额为233000元。",
        "预算金额为640000元。",
        "中标金额为640000元。",
        "预算金额高于合同金额。",
        "差额为233000元。",
        "报价超过阈值。",
        "人民币肆拾万柒仟元整",
        "合同金额67000元。",
        "合同金额：67000。",
        "对应金额670元/周。",
        "实际违约金13400元。",
        "合同金额与中标金额一致。",
        "合同金额高于中标金额。",
        "合同金额较中标金额高20%。",
        "合同金额20%高于市场价并应支付违约金。",
        "合同金额20%高于基准价并构成违约。",
        "按合同金额20%支付违约金并高于市场价。",
        "累计违约金不超过合同金额20%且高于基准价。",
        "预算金额低于合同金额。",
        "应支付670元/周。",
        "67000×1%=670元。",
        "违约金实际金额13400元。",
    ]
    for description in unsafe_descriptions:
        gateway = RecordingModelGateway()
        gateway.contract_payload = {
            "summary": "已完成文字条款语义核对",
            "hints": [{
                "category": "OTHER_TEXT",
                "attention": "REVIEW",
                "title": "需人工复核",
                "description": description,
                "evidenceRefs": ["file-1"],
            }],
        }
        client = TestClient(create_app(settings=settings(), model_gateway=gateway))
        response = client.post(
            "/ai/v1/agent/contract-text-analysis",
            json={
                "projectId": "project-1",
                "materials": [{
                    "fileId": "file-1",
                    "category": "CONTRACT_BODY",
                    "text": "合同文字条款",
                }],
            },
        )
        assert response.status_code == 200, description
        assert_only_contract_structure(response)
        assert description not in response.text


def test_contract_route_filters_strong_audit_and_legal_verdicts_but_allows_advisory_language() -> None:
    strong_verdicts = [
        "确定违法", "确定违规", "认定违法", "认定违规", "存在串标", "涉嫌串标",
        "必须驳回", "应当驳回", "审核通过", "审查通过", "合同合法有效", "无风险", "不存在风险",
        "未发现违法违规问题", "不存在违法违规情形", "合同符合相关法律法规",
    ]
    allowed_phrases = ["未发现明显文字冲突", "建议关注", "建议复核", "材料依据不足"]

    for description in strong_verdicts + allowed_phrases:
        gateway = RecordingModelGateway()
        gateway.contract_payload = {
            "summary": "已完成文字条款语义核对",
            "hints": [{
                "category": "OTHER_TEXT",
                "attention": "REVIEW",
                "title": "文字语义提示",
                "description": description,
                "evidenceRefs": ["file-1"],
                "evidenceItems": [{
                    "fileId": "file-1",
                    "status": "REVIEW",
                    "excerpt": "合同文字条款",
                }],
            }],
        }
        client = TestClient(create_app(settings=settings(), model_gateway=gateway))
        response = client.post(
            "/ai/v1/agent/contract-text-analysis",
            json={
                "projectId": "project-1",
                "materials": [{
                    "fileId": "file-1", "category": "CONTRACT_BODY", "text": "合同文字条款",
                }],
            },
        )
        assert response.status_code == 200, description
        expected_count = (
            len(CONTRACT_BODY_REQUIRED_CATEGORIES)
            if description in strong_verdicts
            else 1 + len(CONTRACT_BODY_REQUIRED_CATEGORIES)
        )
        assert len(response.json()["data"]["hints"]) == expected_count


def test_contract_route_neutralizes_negated_regulatory_verdict_summary() -> None:
    gateway = RecordingModelGateway()
    gateway.contract_payload = {
        "summary": "经审查，未发现违法违规问题，合同符合相关法律法规。",
        "hints": safe_contract_payload()["hints"],
    }
    client = TestClient(create_app(settings=settings(), model_gateway=gateway))

    response = client.post(
        "/ai/v1/agent/contract-text-analysis", json=contract_request()
    )

    assert response.status_code == 200
    assert response.json()["data"]["summaries"] == [
        "已完成合同文字语义分析，请结合来源材料进行人工复核。"
    ]
    assert len(response.json()["data"]["hints"]) == len(
        CONTRACT_BODY_REQUIRED_CATEGORIES
    )


def test_contract_route_handles_four_short_materials_and_multi_chunk_typed_results() -> None:
    materials = [
        {"fileId": f"file-{index}", "category": "CONTRACT_BODY", "text": f"第{index}份交付条款"}
        for index in range(1, 5)
    ]
    short_gateway = RecordingModelGateway()
    short_client = TestClient(create_app(settings=settings(), model_gateway=short_gateway))
    short_response = short_client.post(
        "/ai/v1/agent/contract-text-analysis",
        json={"projectId": "project-1", "materials": materials},
    )
    assert short_response.status_code == 200
    assert len(short_gateway.contract_operations) == 1

    chunk_gateway = RecordingModelGateway()
    chunk_client = TestClient(create_app(
        settings=settings(model_chunk_chars=70, model_max_chunks=20),
        model_gateway=chunk_gateway,
    ))
    chunk_response = chunk_client.post(
        "/ai/v1/agent/contract-text-analysis",
        json={"projectId": "project-1", "materials": materials},
    )
    assert chunk_response.status_code == 200
    assert len(chunk_gateway.contract_operations) > 1
    analyze_operations = [
        operation for operation in chunk_gateway.contract_operations
        if operation.purpose == "ANALYZE"
    ]
    synthesis_operations = [
        operation for operation in chunk_gateway.contract_operations
        if operation.purpose == "SYNTHESIZE"
    ]
    assert len(chunk_response.json()["data"]["summaries"]) == 1
    assert len(synthesis_operations) == 1
    assert all(operation.chunk_count == len(analyze_operations)
               for operation in analyze_operations)


def test_contract_every_analysis_chunk_retains_its_material_identity_header() -> None:
    gateway = RecordingModelGateway()
    client = TestClient(create_app(
        settings=settings(model_chunk_chars=80, model_max_chunks=20),
        model_gateway=gateway,
    ))

    response = client.post("/ai/v1/agent/contract-text-analysis", json={
        "projectId": "project-1",
        "materials": [
            {"fileId": "contract", "category": "CONTRACT_BODY",
             "text": "合同违约与期限条款。" * 16},
            {"fileId": "tender", "category": "TENDER_DOCUMENT",
             "text": "招标违约与期限条款。" * 16},
        ],
    })

    assert response.status_code == 200
    analysis_operations = [
        operation for operation in gateway.contract_operations
        if operation.purpose == "ANALYZE"
    ]
    assert len(analysis_operations) > 2
    assert all(operation.source_text.startswith("[evidenceId=")
               for operation in analysis_operations)
    assert all(";fileId=" in operation.source_text.splitlines()[0]
               for operation in analysis_operations)
    assert all(";category=" in operation.source_text.splitlines()[0]
               for operation in analysis_operations)


def test_contract_multi_chunk_uses_one_safe_final_synthesis_for_cross_file_conflict() -> None:
    class CrossChunkGateway(ModelGatewayPort):
        def __init__(self) -> None:
            self.operations: list[ContractTextOperation] = []

        def analyze_contract(self, operation: ContractTextOperation) -> ModelCompletion:
            self.operations.append(operation)
            if getattr(operation, "purpose", "ANALYZE") == "SYNTHESIZE":
                synthesis_input = json.loads(operation.source_text)
                assert {
                    item["evidenceId"]
                    for item in synthesis_input["evidenceCatalog"]
                } == {"C:S1", "T:S2"}
                assert all(
                    "evidenceItems" not in hint
                    for partial in synthesis_input["partialResults"]
                    for hint in partial["hints"]
                )
                return contract_completion(with_contract_structure({
                    "summary": "违约责任比例存在差异，建议复核。",
                    "hints": [{
                        "category": "BREACH_LIABILITY",
                        "attention": "REVIEW",
                        "title": "逾期交付违约比例存在差异",
                        "description": "合同约定1%/周，招标文件约定2%/周。",
                        "evidenceRefs": ["contract", "tender"],
                        "evidenceItems": [
                            {"fileId": "contract", "status": "NORMAL",
                             "excerpt": "逾期交付按合同金额1%/周支付违约金"},
                            {"fileId": "tender", "status": "REVIEW",
                             "excerpt": "逾期交付按合同金额2%/周支付违约金"},
                        ],
                    }],
                }), 12)
            if "fileId=contract" in operation.source_text:
                file_id, rate = "contract", "1%"
            else:
                file_id, rate = "tender", "2%"
            return contract_completion({
                "summary": f"{file_id}违约条款已识别",
                "hints": [{
                    "category": "BREACH_LIABILITY",
                    "attention": "INFO",
                    "title": "逾期交付违约责任",
                    "description": f"约定比例为{rate}/周。",
                    "evidenceRefs": [file_id],
                    "evidenceItems": [{
                        "fileId": file_id,
                        "status": "NORMAL",
                        "excerpt": f"逾期交付按合同金额{rate}/周支付违约金",
                    }],
                }],
            }, 7)

        def analyze_budget(self, _operation: BudgetSemanticOperation) -> ModelCompletion:
            raise AssertionError("budget operation was not expected")

    gateway = CrossChunkGateway()
    client = TestClient(create_app(
        settings=settings(model_chunk_chars=120, model_max_chunks=20),
        model_gateway=gateway,
    ))
    response = client.post("/ai/v1/agent/contract-text-analysis", json={
        "projectId": "project-1",
        "materials": [
            {"fileId": "contract", "category": "CONTRACT_BODY",
             "text": "RAW-CONTRACT-SENTINEL。逾期交付按合同金额1%/周支付违约金。"},
            {"fileId": "tender", "category": "TENDER_DOCUMENT",
             "text": "RAW-TENDER-SENTINEL。逾期交付按合同金额2%/周支付违约金。"},
        ],
    })

    assert response.status_code == 200
    assert response.json()["data"]["hints"][0] == {
        "category": "BREACH_LIABILITY",
        "attention": "REVIEW",
        "title": "逾期交付违约比例存在差异",
        "description": "合同约定1%/周，招标文件约定2%/周。",
        "evidenceRefs": ["contract", "tender"],
        "evidenceItems": [
            {"fileId": "contract", "status": "NORMAL",
             "excerpt": "逾期交付按合同金额1%/周支付违约金"},
            {"fileId": "tender", "status": "REVIEW",
             "excerpt": "逾期交付按合同金额2%/周支付违约金"},
        ],
    }
    assert [hint["category"] for hint in response.json()["data"]["hints"]] == [
        "BREACH_LIABILITY",
        *CONTRACT_BODY_REQUIRED_CATEGORIES,
    ]
    analyze_operations = [
        operation for operation in gateway.operations
        if getattr(operation, "purpose", "ANALYZE") == "ANALYZE"
    ]
    synthesis_operations = [
        operation for operation in gateway.operations
        if getattr(operation, "purpose", "ANALYZE") == "SYNTHESIZE"
    ]
    assert len(analyze_operations) > 1
    assert len(synthesis_operations) == 1
    assert len(gateway.operations) == len(analyze_operations) + 1


def test_contract_mask_token_is_never_returned_as_an_evidence_excerpt(caplog) -> None:
    first_payload = {
        "summary": "已完成合同文字语义核对",
        "hints": [{
            "category": "BREACH_LIABILITY",
            "attention": "REVIEW",
            "title": "违约责任",
            "description": "建议结合原始材料复核。",
            "evidenceRefs": ["file-1"],
            "evidenceItems": [{
                "fileId": "file-1",
                "status": "REVIEW",
                "excerpt": "[确定性数值字段已移除]",
            }],
        }],
    }
    recovered_payload = {
        "evidenceRepairs": [{
            "hintId": "A1:H1",
            "problemEvidenceIds": ["C:S1"],
        }],
    }
    gateway = SequencedContractGateway([
        contract_completion(first_payload, 7),
        contract_completion(recovered_payload, 8),
    ])
    caplog.set_level(logging.INFO, logger="audit_ai.model.router")
    client = TestClient(create_app(settings=settings(), model_gateway=gateway))

    response = client.post(
        "/ai/v1/agent/contract-text-analysis", json=contract_request()
    )

    assert response.status_code == 200
    assert len(gateway.contract_operations) == 2
    assert gateway.contract_operations[1].retry_reason == (
        "MISSING_OR_UNVERIFIED_PROBLEM_EVIDENCE"
    )
    normalization = next(
        record.audit_fields
        for record in caplog.records
        if record.getMessage().startswith(
            "contract semantic hint normalization completed"
        )
    )
    assert normalization["evidence_repair_target_count"] == 1
    assert response.json()["data"]["hints"][0]["evidenceItems"] == [{
        "fileId": "file-1",
        "status": "REVIEW",
        "excerpt": contract_request()["materials"][0]["text"],
    }]
    assert "[确定性数值字段已移除]" not in response.text


def test_contract_filter_log_records_only_safe_reason_and_runtime_metadata(caplog) -> None:
    source_sentinel = "SOURCE-SENTINEL-DO-NOT-LOG"
    response_sentinel = "RESPONSE-SENTINEL-DO-NOT-LOG"
    gateway = RecordingModelGateway()
    gateway.contract_payload = {
        "summary": "已完成文字核对",
        "hints": [{
            "category": "OTHER_TEXT", "attention": "REVIEW", "title": "建议复核",
            "description": response_sentinel, "evidenceRefs": ["invented-file"],
        }],
    }
    caplog.set_level(logging.INFO, logger="audit_ai.model.router")
    client = TestClient(create_app(settings=settings(), model_gateway=gateway))

    response = client.post(
        "/ai/v1/agent/contract-text-analysis",
        headers={"X-Request-Id": "rejection-log-request"},
        json={
            "projectId": "project-1",
            "materials": [{
                "fileId": "file-1", "category": "CONTRACT_BODY", "text": source_sentinel,
            }],
        },
    )

    assert response.status_code == 200
    assert_only_contract_structure(response)
    filtered = next(record for record in caplog.records
                    if record.getMessage().startswith("contract semantic result filtered"))
    assert filtered.audit_fields == {
        "operation": "CONTRACT_TEXT_ANALYSIS",
        "request_id": "rejection-log-request",
        "reason": "UNSAFE_OR_INVALID_HINT",
        "outcome": "FILTERED",
        "attempt": 1,
        "max_attempts": 2,
        "chunk_index": 1,
        "chunk_count": 1,
        "model": "qwen-test",
        "duration_ms": 7,
        "filtered_hint_count": 1,
    }
    assert source_sentinel not in caplog.text
    assert response_sentinel not in caplog.text


def test_contract_filter_log_uses_one_allowlisted_reason_for_per_hint_fail_safe(caplog) -> None:
    cases = [
        "确定违规",
        "合同金额为407000元",
        "报价超过阈值",
    ]
    caplog.set_level(logging.INFO, logger="audit_ai.model.router")

    for description in cases:
        caplog.clear()
        gateway = RecordingModelGateway()
        gateway.contract_payload = {
            "summary": "已完成文字条款语义核对",
            "hints": [{
                "category": "OTHER_TEXT", "attention": "REVIEW",
                "title": "待复核事项", "description": description,
                "evidenceRefs": ["file-1"],
            }],
        }
        client = TestClient(create_app(settings=settings(), model_gateway=gateway))
        response = client.post(
            "/ai/v1/agent/contract-text-analysis",
            json={
                "projectId": "project-1",
                "materials": [{
                    "fileId": "file-1", "category": "CONTRACT_BODY", "text": "合同文字条款",
                }],
            },
        )
        assert response.status_code == 200
        filtered = next(
            record for record in caplog.records
            if record.getMessage().startswith("contract semantic result filtered")
        )
        assert filtered.audit_fields["reason"] == "UNSAFE_OR_INVALID_HINT"
        assert filtered.audit_fields["outcome"] == "FILTERED"


def test_contract_hint_filter_diagnostics_identify_invalid_evidence_without_changing_public_result(
    caplog,
) -> None:
    source_sentinel = "source-secret-value"
    response_sentinel = "description-secret-value"
    gateway = RecordingModelGateway()
    gateway.contract_payload = {
        "summary": "已完成文字核对",
        "hints": [
            {
                "category": "BREACH_LIABILITY",
                "attention": "REVIEW",
                "title": "违约责任",
                "description": "建议核对违约条款。",
                "evidenceRefs": ["file-1"],
                "evidenceItems": [{
                    "fileId": "file-1",
                    "status": "REVIEW",
                    "excerpt": "交付期限为20个工作日",
                }],
            },
            {
                "category": "SERVICE_WARRANTY",
                "attention": "REVIEW",
                "title": "title-secret-value",
                "description": response_sentinel,
                "evidenceRefs": ["invented-file"],
                "evidenceItems": [{
                    "fileId": "invented-file",
                    "status": "REVIEW",
                    "excerpt": "excerpt-secret-value",
                }],
            },
        ],
    }
    caplog.set_level(logging.INFO, logger="audit_ai.model.router")
    client = TestClient(create_app(settings=settings(), model_gateway=gateway))

    request_payload = contract_request()
    request_payload["materials"][0]["text"] += source_sentinel
    response = client.post(
        "/ai/v1/agent/contract-text-analysis",
        headers={"X-Request-Id": "hint-filter-diagnostic"},
        json=request_payload,
    )

    assert response.status_code == 200
    data = response.json()["data"]
    assert data["operation"] == "CONTRACT_TEXT_ANALYSIS"
    assert data["summaries"] == ["已完成文字核对"]
    assert [hint["category"] for hint in data["hints"]] == [
        "BREACH_LIABILITY",
        *CONTRACT_BODY_REQUIRED_CATEGORIES,
    ]
    assert data["hints"][0] == {
        "category": "BREACH_LIABILITY",
        "attention": "REVIEW",
        "title": "违约责任",
        "description": "建议核对违约条款。",
        "evidenceRefs": ["file-1"],
        "evidenceItems": [{
            "fileId": "file-1",
            "status": "REVIEW",
            "excerpt": "交付期限为20个工作日",
        }],
    }
    assert data["model"] == "qwen-test"
    assert data["durationMs"] == 7
    filtered = next(
        record.audit_fields
        for record in caplog.records
        if record.getMessage().startswith("contract semantic hint filtered")
    )
    assert filtered == {
        "operation": "CONTRACT_TEXT_ANALYSIS",
        "request_id": "hint-filter-diagnostic",
        "attempt": 1,
        "purpose": "ANALYZE",
        "chunk_index": 1,
        "item_index": 2,
        "category": "SERVICE_WARRANTY",
        "attention": "REVIEW",
        "filter_reason": "INVALID_EVIDENCE_REF",
    }
    summary = next(
        record.audit_fields
        for record in caplog.records
        if record.getMessage().startswith(
            "contract semantic hint normalization completed"
        )
    )
    assert summary == {
        "operation": "CONTRACT_TEXT_ANALYSIS",
        "request_id": "hint-filter-diagnostic",
        "attempt": 1,
        "purpose": "ANALYZE",
        "input_hint_count": 9,
        "raw_hint_count": 9,
        "accepted_hint_count": 8,
        "normalized_hint_count": 8,
        "filtered_hint_count": 1,
        "filter_reason_counts": "INVALID_EVIDENCE_REF:1",
        "accepted_categories": (
            "BREACH_LIABILITY,CONTRACT_STRUCTURE,PROJECT_IDENTITY,"
            "SUPPLIER_IDENTITY,DELIVERY_TERMS,SERVICE_WARRANTY,"
            "SCOPE_AND_SUBCONTRACTING,TERM_TERMINATION"
        ),
        "filtered_categories": "SERVICE_WARRANTY",
        "returned_categories": (
            "BREACH_LIABILITY,CONTRACT_STRUCTURE,PROJECT_IDENTITY,"
            "SUPPLIER_IDENTITY,DELIVERY_TERMS,SERVICE_WARRANTY,"
            "SCOPE_AND_SUBCONTRACTING,TERM_TERMINATION"
        ),
        "evidence_repair_target_count": 0,
    }
    for forbidden in (
        source_sentinel,
        response_sentinel,
        "title-secret-value",
        "excerpt-secret-value",
        "invented-file",
    ):
        assert forbidden not in caplog.text


@pytest.mark.parametrize(
    ("description", "expected_reason"),
    [
        ("确定违规", "STRONG_VERDICT"),
        ("合同金额为407000元", "MONETARY_VALUE"),
        ("价格按2×3计算", "MONETARY_CALCULATION"),
    ],
)
def test_contract_hint_filter_diagnostics_distinguish_safety_reasons(
    caplog, description: str, expected_reason: str,
) -> None:
    gateway = RecordingModelGateway()
    gateway.contract_payload = {
        "summary": "已完成文字条款核对",
        "hints": [{
            "category": "OTHER_TEXT",
            "attention": "REVIEW",
            "title": "待复核事项",
            "description": description,
            "evidenceRefs": ["file-1"],
        }],
    }
    caplog.set_level(logging.INFO, logger="audit_ai.model.router")

    response = TestClient(
        create_app(settings=settings(), model_gateway=gateway)
    ).post(
        "/ai/v1/agent/contract-text-analysis",
        headers={"X-Request-Id": f"filter-{expected_reason.lower()}"},
        json=contract_request(),
    )

    assert response.status_code == 200
    assert_only_contract_structure(response)
    filtered = next(
        record.audit_fields
        for record in caplog.records
        if record.getMessage().startswith("contract semantic hint filtered")
    )
    assert filtered["category"] == "OTHER_TEXT"
    assert filtered["attention"] == "REVIEW"
    assert filtered["filter_reason"] == expected_reason


def test_contract_normal_hint_logs_normalization_without_filtered_hint_event(caplog) -> None:
    gateway = RecordingModelGateway()
    gateway.contract_payload = safe_contract_payload()
    caplog.set_level(logging.INFO, logger="audit_ai.model.router")

    response = TestClient(
        create_app(settings=settings(), model_gateway=gateway)
    ).post(
        "/ai/v1/agent/contract-text-analysis",
        headers={"X-Request-Id": "normal-hint-diagnostic"},
        json=contract_request(),
    )

    assert response.status_code == 200
    assert not any(
        record.getMessage().startswith("contract semantic hint filtered")
        for record in caplog.records
    )
    summary = next(
        record.audit_fields
        for record in caplog.records
        if record.getMessage().startswith(
            "contract semantic hint normalization completed"
        )
    )
    assert summary["input_hint_count"] == len(CONTRACT_BODY_REQUIRED_CATEGORIES)
    assert summary["accepted_hint_count"] == len(CONTRACT_BODY_REQUIRED_CATEGORIES)
    assert summary["filtered_hint_count"] == 0
    assert summary["accepted_categories"] == (
        "DELIVERY_TERMS,CONTRACT_STRUCTURE,PROJECT_IDENTITY,SUPPLIER_IDENTITY,"
        "SERVICE_WARRANTY,SCOPE_AND_SUBCONTRACTING,TERM_TERMINATION"
    )
    assert summary["filtered_categories"] == "NONE"


def test_missing_model_configuration_is_explicitly_unavailable() -> None:
    client = TestClient(create_app(settings=settings()))

    response = client.post(
        "/ai/v1/agent/budget-semantic-analysis",
        json={
            "sourceId": "budget-1",
            "items": [{"itemId": "item-1", "description": "设备"}],
        },
    )

    assert response.status_code == 503
    assert response.json()["code"] == "MODEL_NOT_CONFIGURED"


def test_budget_route_rejects_amount_fields_without_calling_model() -> None:
    gateway = RecordingModelGateway()
    client = TestClient(create_app(settings=settings(), model_gateway=gateway))

    response = client.post(
        "/ai/v1/agent/budget-semantic-analysis",
        json={
            "sourceId": "budget-1",
            "items": [
                {
                    "itemId": "item-1",
                    "description": "设备",
                    "amount": "999.99",
                }
            ],
        },
    )

    assert response.status_code == 422
    assert gateway.budget_operations == []


def test_openai_gateway_posts_bounded_typed_contract_request() -> None:
    observed: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        observed["url"] = str(request.url)
        observed["authorization"] = request.headers["Authorization"]
        observed["body"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "model": "qwen-server-version",
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(
                                {"summary": "摘要", "hints": []},
                                ensure_ascii=False,
                            )
                        }
                    }
                ],
            },
        )

    configured = settings(
        model_base_url="https://model.example.invalid/v1",
        model_api_key="unit-test-key",
        model_name="qwen-model",
    )
    gateway = OpenAiCompatibleModelGateway(
        configured, transport=httpx.MockTransport(handler)
    )

    completion = gateway.analyze_contract(
        ContractTextOperation(
            project_id="project-1",
            chunk_index=1,
            chunk_count=1,
            source_text="合同正文",
            required_categories=(
                "CONTRACT_STRUCTURE",
                "PROJECT_IDENTITY",
                "SUPPLIER_IDENTITY",
                "DELIVERY_TERMS",
                "SERVICE_WARRANTY",
                "SCOPE_AND_SUBCONTRACTING",
            ),
        )
    )

    assert observed["url"] == "https://model.example.invalid/v1/chat/completions"
    assert observed["authorization"] == "Bearer unit-test-key"
    assert observed["body"]["model"] == "qwen-model"
    assert observed["body"]["temperature"] == 0
    assert observed["body"]["response_format"] == {"type": "json_object"}
    assert "金额" in observed["body"]["messages"][0]["content"]
    assert "数值字段已由程序移除" in observed["body"]["messages"][0]["content"]
    assert "DELIVERY_TERMS" in observed["body"]["messages"][0]["content"]
    assert "CONTRACT_STRUCTURE" in observed["body"]["messages"][0]["content"]
    assert "BREACH_LIABILITY" in observed["body"]["messages"][0]["content"]
    assert "TERM_TERMINATION" in observed["body"]["messages"][0]["content"]
    assert "百分比" in observed["body"]["messages"][0]["content"]
    assert "liable party" in observed["body"]["messages"][0]["content"]
    assert "effective date or event" in observed["body"]["messages"][0]["content"]
    assert "specific date conflict" in observed["body"]["messages"][0]["content"]
    assert "problemEvidenceIds" in observed["body"]["messages"][0]["content"]
    assert "comparisonEvidenceIds" in observed["body"]["messages"][0]["content"]
    assert "server-owned exact text" in observed["body"]["messages"][0]["content"]
    assert "unavailableMaterialCategory" in observed["body"]["messages"][0]["content"]
    assert (
        "Never stop after summary or before the final closing brace"
        in observed["body"]["messages"][0]["content"]
    )
    assert "Keep summary, title, and description concise" in observed["body"]["messages"][0]["content"]
    assert "Never borrow an unrelated absent category" in observed["body"]["messages"][0]["content"]
    assert (
        "Every category listed in requiredCategories MUST appear at least once"
        in observed["body"]["messages"][0]["content"]
    )
    assert (
        "Do not omit a required category merely because no risk was found"
        in observed["body"]["messages"][0]["content"]
    )
    assert (
        "expressed only as REVIEW or INSUFFICIENT"
        not in observed["body"]["messages"][0]["content"]
    )
    assert "requiredCategories=CONTRACT_STRUCTURE,PROJECT_IDENTITY,SUPPLIER_IDENTITY,DELIVERY_TERMS,SERVICE_WARRANTY,SCOPE_AND_SUBCONTRACTING" in observed["body"]["messages"][1]["content"]
    assert completion.payload == {"summary": "摘要", "hints": []}
    assert completion.model == "qwen-server-version"


def test_synthesis_prompt_enforces_required_category_coverage_contract() -> None:
    observed: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        observed["body"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "model": "qwen-test",
                "choices": [{
                    "message": {
                        "content": json.dumps(
                            {"summary": "summary", "hints": []}
                        )
                    }
                }],
            },
        )

    configured = settings(
        model_base_url="https://model.example.invalid/v1",
        model_api_key="unit-test-key",
        model_name="qwen-test",
    )
    gateway = OpenAiCompatibleModelGateway(
        configured, transport=httpx.MockTransport(handler)
    )

    gateway.analyze_contract(ContractTextOperation(
        project_id="project-1",
        chunk_index=2,
        chunk_count=2,
        source_text='{"partialResults": [], "evidenceCatalog": []}',
        purpose="SYNTHESIZE",
        required_categories=(
            "CONTRACT_STRUCTURE",
            "PROJECT_IDENTITY",
            "SUPPLIER_IDENTITY",
            "DELIVERY_TERMS",
            "SERVICE_WARRANTY",
            "SCOPE_AND_SUBCONTRACTING",
        ),
    ))

    system_prompt = observed["body"]["messages"][0]["content"]
    assert observed["body"]["response_format"] == {"type": "json_object"}
    assert "json" in system_prompt.casefold()
    assert (
        "Every category listed in requiredCategories MUST appear at least once"
        in system_prompt
    )
    assert "Never fabricate a hint merely to satisfy coverage" in system_prompt


@pytest.mark.parametrize("finish_reason,expected_calls", [("stop", 2), ("length", 1)])
def test_invalid_openai_content_is_bounded_by_finish_reason(
    caplog, finish_reason: str, expected_calls: int
) -> None:
    calls = 0
    response_sentinel = "PROVIDER-JSON-SENTINEL-DO-NOT-LOG"

    def handler(_request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(
            200,
            json={
                "model": "qwen-test",
                "choices": [{
                    "finish_reason": finish_reason,
                    "message": {
                        "content": '{"summary":"' + response_sentinel,
                    },
                }],
            },
        )

    configured = settings(
        model_base_url="https://model.example.invalid/v1",
        model_api_key="unit-test-key",
        model_name="qwen-test",
    )
    gateway = OpenAiCompatibleModelGateway(
        configured, transport=httpx.MockTransport(handler)
    )
    caplog.set_level(logging.INFO, logger="audit_ai.model.gateway")
    client = TestClient(create_app(settings=configured, model_gateway=gateway))

    response = client.post(
        "/ai/v1/agent/contract-text-analysis",
        json={
            "projectId": "project-1",
            "materials": [
                {"fileId": "file-1", "category": "CONTRACT_BODY", "text": "正文"}
            ],
        },
    )

    assert response.status_code == 502
    assert response.json()["code"] == "MODEL_GATEWAY_ERROR"
    assert calls == expected_calls
    diagnostics = [
        record.audit_fields
        for record in caplog.records
        if record.getMessage().startswith("model provider JSON rejected")
    ]
    if finish_reason == "stop":
        assert diagnostics
        diagnostic = diagnostics[0]
        assert diagnostic["provider_finish_reason"] == "STOP"
        assert diagnostic["provider_content_shape"] == "OBJECT_TRUNCATED"
        assert diagnostic["json_error_kind"] == "UNTERMINATED_STRING"
        assert diagnostic["provider_content_chars"] > 0
        assert diagnostic["json_error_position"] > 0
    else:
        # A token-limited response is rejected before JSON repair: retrying the
        # same output capacity must not consume another provider call.
        assert not diagnostics
        assert "OUTPUT_TOKEN_LIMIT" in caplog.text
    assert response_sentinel not in response.text
    assert response_sentinel not in caplog.text


def test_contract_monetary_violation_filters_only_unsafe_hint_without_retry(
    caplog,
) -> None:
    source_sentinel = "SOURCE-SENTINEL-DO-NOT-LOG"
    unsafe_response_sentinel = "报价高于预算20%。"
    payload = mixed_safe_and_monetary_contract_payload()
    gateway = SequencedContractGateway([contract_completion(payload, 100)])
    caplog.set_level(logging.INFO, logger="audit_ai.model.router")
    client = TestClient(create_app(settings=settings(), model_gateway=gateway))

    response = client.post(
        "/ai/v1/agent/contract-text-analysis",
        headers={"X-Request-Id": "filtered-risk-fixture"},
        json={
            "projectId": "project-risk-fixture",
            "materials": [
                {"fileId": "file-1", "category": "CONTRACT_BODY", "text": source_sentinel},
                {"fileId": "file-2", "category": "AWARD_NOTICE", "text": "供应商材料"},
                {"fileId": "file-3", "category": "TENDER_DOCUMENT", "text": "交付条款"},
                {"fileId": "file-4", "category": "SUPPLEMENT", "text": "分包条款"},
            ],
        },
    )

    assert response.status_code == 200
    data = response.json()["data"]
    assert data["durationMs"] == 100
    assert len(data["hints"]) == len(CONTRACT_BODY_REQUIRED_CATEGORIES)
    assert [hint["title"] for hint in data["hints"][:-2]] == [
        "项目标识", "供应商信息", "交付期限", "服务时间", "分包限制",
    ]
    assert [hint["category"] for hint in data["hints"][-2:]] == [
        "CONTRACT_STRUCTURE",
        "TERM_TERMINATION",
    ]
    assert "报价结论" not in response.text
    assert unsafe_response_sentinel not in response.text
    assert len(gateway.contract_operations) == 1

    filtered = next(
        record for record in caplog.records
        if record.getMessage().startswith("contract semantic result filtered")
    )
    assert filtered.audit_fields == {
        "operation": "CONTRACT_TEXT_ANALYSIS",
        "request_id": "filtered-risk-fixture",
        "reason": "UNSAFE_OR_INVALID_HINT",
        "outcome": "FILTERED",
        "attempt": 1,
        "max_attempts": 2,
        "chunk_index": 1,
        "chunk_count": 1,
        "model": "qwen-test",
        "duration_ms": 100,
        "filtered_hint_count": 1,
    }
    assert source_sentinel not in caplog.text
    assert unsafe_response_sentinel not in caplog.text


def test_contract_monetary_result_after_transient_retry_is_filtered_to_200(
    caplog,
) -> None:
    payload = mixed_safe_and_monetary_contract_payload()
    gateway = SequencedContractGateway([
        ModelGatewayFailure(
            reason="READ_TIMEOUT", retryable=True, duration_ms=60_000
        ),
        contract_completion(payload, 14_272),
    ])
    caplog.set_level(logging.INFO, logger="audit_ai.model.router")
    client = TestClient(create_app(settings=settings(), model_gateway=gateway))

    response = client.post(
        "/ai/v1/agent/contract-text-analysis",
        headers={"X-Request-Id": "transient-then-filtered"},
        json={
            "projectId": "project-risk-fixture",
            "materials": [
                {"fileId": "file-1", "category": "CONTRACT_BODY", "text": "交付条款"},
                {"fileId": "file-2", "category": "AWARD_NOTICE", "text": "供应商材料"},
                {"fileId": "file-3", "category": "TENDER_DOCUMENT", "text": "工期条款"},
                {"fileId": "file-4", "category": "SUPPLEMENT", "text": "分包条款"},
            ],
        },
    )

    assert response.status_code == 200
    data = response.json()["data"]
    assert data["durationMs"] == 74_272
    assert len(data["hints"]) == len(CONTRACT_BODY_REQUIRED_CATEGORIES)
    assert [hint["title"] for hint in data["hints"][:-2]] == [
        "项目标识", "供应商信息", "交付期限", "服务时间", "分包限制",
    ]
    assert [hint["category"] for hint in data["hints"][-2:]] == [
        "CONTRACT_STRUCTURE",
        "TERM_TERMINATION",
    ]
    assert len(gateway.contract_operations) == 2
    assert gateway.contract_operations[1].retry_reason is None
    assert gateway.contract_operations[1].transport_attempt == 2
    assert gateway.contract_operations[1].transport_retry_reason == "READ_TIMEOUT"
    filtered = next(
        record for record in caplog.records
        if record.getMessage().startswith("contract semantic result filtered")
    )
    assert filtered.audit_fields["reason"] == "UNSAFE_OR_INVALID_HINT"
    assert filtered.audit_fields["outcome"] == "FILTERED"
    assert filtered.audit_fields["filtered_hint_count"] == 1


def test_contract_unprocessable_top_level_after_transient_retry_returns_502() -> None:
    gateway = SequencedContractGateway([
        ModelGatewayFailure(
            reason="READ_TIMEOUT", retryable=True, duration_ms=60_000
        ),
        contract_completion({"summary": "文字核对", "hints": "bad"}, 1),
        contract_completion({"summary": "文字核对", "hints": "bad"}, 1),
    ])
    client = TestClient(create_app(settings=settings(), model_gateway=gateway))

    response = client.post(
        "/ai/v1/agent/contract-text-analysis", json=contract_request()
    )

    assert response.status_code == 502
    assert response.json()["code"] == "MODEL_INVALID_RESULT"
    assert len(gateway.contract_operations) == 3
    assert [operation.attempt for operation in gateway.contract_operations] == [
        1, 1, 2,
    ]
    assert [
        operation.transport_attempt for operation in gateway.contract_operations
    ] == [1, 2, 1]


def test_contract_monetary_summary_is_neutralized_without_retry_and_keeps_safe_hints() -> None:
    payload = safe_contract_payload()
    payload["summary"] = "合同金额为407000元"
    gateway = SequencedContractGateway([contract_completion(payload, 10)])
    client = TestClient(create_app(settings=settings(), model_gateway=gateway))

    response = client.post(
        "/ai/v1/agent/contract-text-analysis", json=contract_request()
    )

    assert response.status_code == 200
    assert response.json()["data"]["summaries"] == [
        "已完成合同文字语义分析，请结合来源材料进行人工复核。"
    ]
    assert response.json()["data"]["hints"][0] == safe_contract_payload()["hints"][0]
    assert [hint["category"] for hint in response.json()["data"]["hints"]] == [
        "DELIVERY_TERMS",
        "CONTRACT_STRUCTURE",
        "PROJECT_IDENTITY",
        "SUPPLIER_IDENTITY",
        "SERVICE_WARRANTY",
        "SCOPE_AND_SUBCONTRACTING",
        "TERM_TERMINATION",
    ]
    assert "407000" not in response.text
    assert len(gateway.contract_operations) == 1


def test_contract_monetary_hint_is_removed_and_safe_summary_is_retained() -> None:
    payload = {
        "summary": "已完成文字条款语义核对",
        "hints": [{
            "category": "OTHER_TEXT",
            "attention": "REVIEW",
            "title": "金额提示",
            "description": "合同金额按100×5000计算得到。",
            "evidenceRefs": ["file-1"],
        }],
    }
    gateway = SequencedContractGateway([contract_completion(payload, 10)])
    client = TestClient(create_app(settings=settings(), model_gateway=gateway))

    response = client.post(
        "/ai/v1/agent/contract-text-analysis", json=contract_request()
    )

    assert response.status_code == 200
    assert response.json()["data"]["summaries"] == [payload["summary"]]
    assert_only_contract_structure(response)
    assert "100×5000" not in response.text


def test_contract_second_unprocessable_top_level_fails_closed() -> None:
    payload = {"summary": "文字核对", "hints": {"not": "a-list"}}
    gateway = SequencedContractGateway(
        [contract_completion(payload, 10), contract_completion(payload, 20)]
    )
    client = TestClient(create_app(settings=settings(), model_gateway=gateway))

    response = client.post(
        "/ai/v1/agent/contract-text-analysis", json=contract_request()
    )

    assert response.status_code == 502
    assert response.json()["code"] == "MODEL_INVALID_RESULT"
    assert len(gateway.contract_operations) == 2


def test_contract_multi_chunk_safe_filtering_does_not_consume_retry_budget() -> None:
    class OneRejectedChunkGateway(ModelGatewayPort):
        def __init__(self) -> None:
            self.operations: list[ContractTextOperation] = []

        def analyze_contract(self, operation: ContractTextOperation) -> ModelCompletion:
            self.operations.append(operation)
            first_chunk_calls = sum(
                item.chunk_index == 1 for item in self.operations
            )
            if operation.chunk_index == 1 and first_chunk_calls == 1:
                return contract_completion(
                    {"summary": "报价高于预算20%。", "hints": []}, 10
                )
            return contract_completion(safe_contract_payload_for(operation), 10)

        def analyze_budget(self, _operation: BudgetSemanticOperation) -> ModelCompletion:
            raise AssertionError("budget operation was not expected")

    gateway = OneRejectedChunkGateway()
    client = TestClient(
        create_app(
            settings=settings(model_chunk_chars=80, model_max_chunks=20),
            model_gateway=gateway,
        )
    )
    request = contract_request()
    request["materials"][0]["text"] = "交付与质保文字条款。" * 20

    response = client.post("/ai/v1/agent/contract-text-analysis", json=request)

    assert response.status_code == 200
    chunk_count = gateway.operations[0].chunk_count
    assert chunk_count > 1
    analyze_operations = [
        operation for operation in gateway.operations
        if operation.purpose == "ANALYZE"
    ]
    synthesis_operations = [
        operation for operation in gateway.operations
        if operation.purpose == "SYNTHESIZE"
    ]
    assert len(analyze_operations) == chunk_count
    assert len(synthesis_operations) == 1
    assert sum(operation.attempt == 2 for operation in analyze_operations) == 0


def test_contract_multi_chunk_call_bound_is_n_plus_two_with_the_shared_retry() -> None:
    class RetryThenSynthesizeGateway(ModelGatewayPort):
        def __init__(self) -> None:
            self.operations: list[ContractTextOperation] = []

        def analyze_contract(self, operation: ContractTextOperation) -> ModelCompletion:
            self.operations.append(operation)
            if len(self.operations) == 1:
                raise ModelGatewayFailure(
                    reason="READ_TIMEOUT", retryable=True, duration_ms=10
                )
            return contract_completion(safe_contract_payload_for(operation), 10)

        def analyze_budget(self, _operation: BudgetSemanticOperation) -> ModelCompletion:
            raise AssertionError("budget operation was not expected")

    gateway = RetryThenSynthesizeGateway()
    client = TestClient(create_app(
        settings=settings(model_chunk_chars=80, model_max_chunks=20),
        model_gateway=gateway,
    ))
    request = contract_request()
    request["materials"][0]["text"] = "交付、质保与违约责任文字条款。" * 20

    response = client.post("/ai/v1/agent/contract-text-analysis", json=request)

    assert response.status_code == 200
    chunk_count = gateway.operations[0].chunk_count
    assert chunk_count > 1
    assert len(gateway.operations) == chunk_count + 2
    assert sum(operation.purpose == "SYNTHESIZE" for operation in gateway.operations) == 1
    assert sum(operation.transport_attempt == 2 for operation in gateway.operations) == 1
    assert all(operation.attempt == 1 for operation in gateway.operations)


@pytest.mark.parametrize("synthesis_failure", [
    ModelGatewayFailure(reason="READ_TIMEOUT", retryable=True, duration_ms=10),
    None,
])
def test_contract_multi_chunk_synthesis_uses_the_unused_shared_retry(
    synthesis_failure: ModelGatewayFailure | None,
) -> None:
    class RetrySynthesisGateway(ModelGatewayPort):
        def __init__(self) -> None:
            self.operations: list[ContractTextOperation] = []
            self.failed = False

        def analyze_contract(self, operation: ContractTextOperation) -> ModelCompletion:
            self.operations.append(operation)
            if operation.purpose == "SYNTHESIZE" and not self.failed:
                self.failed = True
                if synthesis_failure is not None:
                    raise synthesis_failure
                return contract_completion({"summary": "文字核对", "hints": "bad"}, 10)
            return contract_completion(safe_contract_payload_for(operation), 10)

        def analyze_budget(self, _operation: BudgetSemanticOperation) -> ModelCompletion:
            raise AssertionError("budget operation was not expected")

    gateway = RetrySynthesisGateway()
    client = TestClient(create_app(
        settings=settings(model_chunk_chars=80, model_max_chunks=20),
        model_gateway=gateway,
    ))
    request = contract_request()
    request["materials"][0]["text"] = "交付、质保与违约责任文字条款。" * 20

    response = client.post("/ai/v1/agent/contract-text-analysis", json=request)

    assert response.status_code == 200
    synthesis_operations = [
        operation for operation in gateway.operations
        if operation.purpose == "SYNTHESIZE"
    ]
    if synthesis_failure is not None:
        assert [operation.attempt for operation in synthesis_operations] == [1, 1]
        assert [
            operation.transport_attempt for operation in synthesis_operations
        ] == [1, 2]
        assert synthesis_operations[1].transport_retry_reason == "READ_TIMEOUT"
    else:
        assert [operation.attempt for operation in synthesis_operations] == [1, 2]
        assert [
            operation.transport_attempt for operation in synthesis_operations
        ] == [1, 1]
        assert synthesis_operations[1].retry_reason == "UNPROCESSABLE_TOP_LEVEL"
    assert all(operation.max_attempts == 2 for operation in synthesis_operations)
    chunk_count = gateway.operations[0].chunk_count
    assert len(gateway.operations) == chunk_count + 2


def test_contract_synthesis_cannot_retry_after_analysis_consumes_shared_retry() -> None:
    class ExhaustedRetryGateway(ModelGatewayPort):
        def __init__(self) -> None:
            self.operations: list[ContractTextOperation] = []
            self.analysis_failed = False

        def analyze_contract(self, operation: ContractTextOperation) -> ModelCompletion:
            self.operations.append(operation)
            if operation.purpose == "ANALYZE" and not self.analysis_failed:
                self.analysis_failed = True
                raise ModelGatewayFailure(
                    reason="READ_TIMEOUT", retryable=True, duration_ms=10
                )
            if operation.purpose == "SYNTHESIZE":
                raise ModelGatewayFailure(
                    reason="READ_TIMEOUT", retryable=True, duration_ms=10
                )
            return contract_completion(safe_contract_payload_for(operation), 10)

        def analyze_budget(self, _operation: BudgetSemanticOperation) -> ModelCompletion:
            raise AssertionError("budget operation was not expected")

    gateway = ExhaustedRetryGateway()
    client = TestClient(create_app(
        settings=settings(model_chunk_chars=80, model_max_chunks=20),
        model_gateway=gateway,
    ))
    request = contract_request()
    request["materials"][0]["text"] = "交付、质保与违约责任文字条款。" * 20

    response = client.post("/ai/v1/agent/contract-text-analysis", json=request)

    assert response.status_code == 502
    chunk_count = gateway.operations[0].chunk_count
    assert len(gateway.operations) == chunk_count + 2
    synthesis_operations = [
        operation for operation in gateway.operations
        if operation.purpose == "SYNTHESIZE"
    ]
    assert len(synthesis_operations) == 1
    assert synthesis_operations[0].max_attempts == 2
    assert synthesis_operations[0].max_transport_attempts == 1


def test_contract_synthesis_compacts_all_safe_hints_before_one_model_call() -> None:
    long_description = "交付与验收要求建议结合来源材料人工复核。" * 45
    long_excerpt = "交付验收原文片段" + ("甲" * 450)

    class LargePartialResultGateway(ModelGatewayPort):
        def __init__(self) -> None:
            self.operations: list[ContractTextOperation] = []

        def analyze_contract(self, operation: ContractTextOperation) -> ModelCompletion:
            self.operations.append(operation)
            if operation.purpose == "SYNTHESIZE":
                assert len(operation.source_text) <= 6000
                compact = json.loads(operation.source_text)
                compact_hints = [
                    hint
                    for partial in compact["partialResults"]
                    for hint in partial["hints"]
                ]
                assert len(compact_hints) == 6
                assert compact["omittedHintCount"] == 0
                assert all(len(hint["title"]) <= 140 for hint in compact_hints)
                assert all(len(hint["description"]) <= 400 for hint in compact_hints)
                assert all("evidenceItems" not in hint for hint in compact_hints)
                assert all("problemEvidenceIds" in hint for hint in compact_hints)
                assert all("comparisonEvidenceIds" in hint for hint in compact_hints)
                return contract_completion(safe_contract_payload_for(operation), 10)
            return contract_completion({
                "summary": "建议人工复核合同文字条款。" * 100,
                "hints": [{
                    "category": "DELIVERY_TERMS",
                    "attention": "REVIEW",
                    "title": f"交付条款{index}",
                    "description": long_description,
                    "evidenceRefs": ["contract"],
                    "evidenceItems": [{
                        "fileId": "contract",
                        "status": "REVIEW",
                        "excerpt": long_excerpt,
                    }],
                } for index in range(3)],
            }, 10)

        def analyze_budget(self, _operation: BudgetSemanticOperation) -> ModelCompletion:
            raise AssertionError("budget operation was not expected")

    gateway = LargePartialResultGateway()
    client = TestClient(create_app(
        settings=settings(model_chunk_chars=700, model_max_chunks=20),
        model_gateway=gateway,
    ))
    request = {
        "projectId": "project-1",
        "materials": [
            {"fileId": "contract", "category": "CONTRACT_BODY",
             "text": long_excerpt},
            {"fileId": "tender", "category": "TENDER_DOCUMENT",
             "text": "招标交付验收条款。" * 50},
        ],
    }

    response = client.post("/ai/v1/agent/contract-text-analysis", json=request)

    assert response.status_code == 200
    assert sum(operation.purpose == "SYNTHESIZE" for operation in gateway.operations) == 1


def test_contract_long_single_safe_hint_falls_back_instead_of_returning_502() -> None:
    file_ids = [f"file-{index}" for index in range(25)]
    long_hint = {
        "category": "DELIVERY_TERMS",
        "attention": "REVIEW",
        "title": "交付与验收要求",
        "description": "建议结合全部来源材料人工复核交付与验收要求。",
        "evidenceRefs": file_ids,
        "evidenceItems": [{
            "fileId": file_id,
            "status": "REVIEW",
            "excerpt": f"{file_id}交付验收原文" + ("甲" * 300),
        } for file_id in file_ids],
    }

    class LongHintGateway(ModelGatewayPort):
        def __init__(self) -> None:
            self.operations: list[ContractTextOperation] = []

        def analyze_contract(self, operation: ContractTextOperation) -> ModelCompletion:
            self.operations.append(operation)
            if operation.purpose == "SYNTHESIZE":
                raise AssertionError("oversized minimum representation must use fallback")
            return contract_completion({"summary": "文字条款已识别", "hints": [long_hint]}, 1)

        def analyze_budget(self, _operation: BudgetSemanticOperation) -> ModelCompletion:
            raise AssertionError("budget operation was not expected")

    gateway = LongHintGateway()
    client = TestClient(create_app(
        settings=settings(model_chunk_chars=250, model_max_chunks=50),
        model_gateway=gateway,
    ))
    response = client.post("/ai/v1/agent/contract-text-analysis", json={
        "projectId": "project-1",
        "materials": [{
            "fileId": file_id,
            "category": "CONTRACT_BODY",
            "text": f"{file_id}交付验收原文" + ("甲" * 300),
        } for file_id in file_ids],
    })

    assert response.status_code == 200
    assert response.json()["data"]["summaries"] == [
        "已完成分段合同文字语义分析；由于材料较长，请结合各项来源材料进行人工复核。"
    ]
    assert response.json()["data"]["hints"] == [long_hint]
    assert all(operation.purpose == "ANALYZE" for operation in gateway.operations)
    assert len(gateway.operations) == gateway.operations[0].chunk_count


def test_contract_synthesis_bound_fallback_preserves_only_distinct_safe_hints(
    caplog,
) -> None:
    source_sentinel = "FALLBACK-SOURCE-SENTINEL"
    response_sentinel = "FALLBACK-RESPONSE-SENTINEL"
    safe_hints = []
    for index in range(10):
        contract_excerpt = (
            "[确定性数值字段已移除]"
            if index == 0
            else f"合同交付验收原文{index}" + ("甲" * 500)
        )
        safe_hints.append({
            "category": "DELIVERY_TERMS",
            "attention": "REVIEW",
            "title": f"交付验收要求{index}",
            "description": f"交付验收要求{index}。" + ("建议结合来源材料人工复核。" * 60),
            "evidenceRefs": ["contract", "tender"],
            "evidenceItems": [
                {"fileId": "contract", "status": "NORMAL", "excerpt": contract_excerpt},
                {"fileId": "tender", "status": "REVIEW",
                 "excerpt": f"招标交付验收原文{index}" + ("乙" * 500)},
            ],
        })
    unsafe_hints = [
        {"category": "OTHER_TEXT", "attention": "REVIEW", "title": "金额比较",
         "description": "合同金额与中标金额一致。", "evidenceRefs": ["contract"]},
        {"category": "BREACH_LIABILITY", "attention": "REVIEW", "title": "实际违约金",
         "description": "实际违约金13400元。", "evidenceRefs": ["contract"]},
        {"category": "OTHER_TEXT", "attention": "REVIEW", "title": "编造来源",
         "description": response_sentinel, "evidenceRefs": ["invented-file"]},
    ]

    class FallbackGateway(ModelGatewayPort):
        def __init__(self) -> None:
            self.operations: list[ContractTextOperation] = []

        def analyze_contract(self, operation: ContractTextOperation) -> ModelCompletion:
            self.operations.append(operation)
            if operation.purpose == "SYNTHESIZE":
                raise AssertionError("oversized minimum representation must use fallback")
            return contract_completion({
                "summary": "交付验收文字条款已识别",
                "hints": [*safe_hints, *unsafe_hints],
            }, 2)

        def analyze_budget(self, _operation: BudgetSemanticOperation) -> ModelCompletion:
            raise AssertionError("budget operation was not expected")

    gateway = FallbackGateway()
    caplog.set_level(logging.INFO, logger="audit_ai.model.router")
    response = TestClient(create_app(
        settings=settings(model_chunk_chars=500, model_max_chunks=40),
        model_gateway=gateway,
    )).post("/ai/v1/agent/contract-text-analysis", json={
        "projectId": "project-1",
        "materials": [
            {"fileId": "contract", "category": "CONTRACT_BODY",
             "text": source_sentinel + "\n" + "\n".join(
                 f"合同交付验收原文{index}" + ("甲" * 500)
                 for index in range(1, 10)
             )},
            {"fileId": "tender", "category": "TENDER_DOCUMENT",
             "text": "\n".join(
                 f"招标交付验收原文{index}" + ("乙" * 500)
                 for index in range(10)
             )},
        ],
    })

    assert response.status_code == 200
    data = response.json()["data"]
    assert data["summaries"] == [
        "已完成分段合同文字语义分析；由于材料较长，请结合各项来源材料进行人工复核。"
    ]
    assert len(data["hints"]) == 10
    assert [hint["title"] for hint in data["hints"]] == [
        f"交付验收要求{index}" for index in range(10)
    ]
    assert "合同金额与中标金额一致" not in response.text
    assert "13400" not in response.text
    assert "invented-file" not in response.text
    assert "[确定性数值字段已移除]" not in response.text
    assert data["hints"][0]["evidenceItems"][0] == {
        "fileId": "contract", "status": "NORMAL",
    }
    assert all(operation.purpose == "ANALYZE" for operation in gateway.operations)
    fallback = next(
        record for record in caplog.records
        if record.getMessage().startswith("contract synthesis input fallback")
    )
    assert fallback.audit_fields["operation"] == "CONTRACT_TEXT_ANALYSIS"
    assert fallback.audit_fields["outcome"] == "FALLBACK"
    assert fallback.audit_fields["reason"] == "SYNTHESIS_INPUT_BOUND"
    assert fallback.audit_fields["request_id"]
    assert fallback.audit_fields["chunk_count"] == gateway.operations[0].chunk_count
    assert fallback.audit_fields["safe_hint_count"] == 10
    assert fallback.audit_fields["compact_chars"] > 6000
    assert source_sentinel not in caplog.text
    assert response_sentinel not in caplog.text


def test_contract_synthesis_minimum_representation_reports_fallback_without_502() -> None:
    gateway = RecordingModelGateway()
    service = ModelApplicationService(
        settings(model_chunk_chars=80, model_max_chunks=500), gateway
    )
    results = [
        ContractChunkResult(summary="安全摘要", hints=[])
        for _ in range(1000)
    ]

    source, compact_chars = service._contract_synthesis_source(results)

    assert source is None
    assert compact_chars > service._synthesis_max_chars


@pytest.mark.parametrize(
    "failure_kind",
    [
        "CONNECT_TIMEOUT",
        "READ_TIMEOUT",
        "CONNECT_ERROR",
        "PROVIDER_429",
        "PROVIDER_5XX",
    ],
)
def test_retryable_gateway_failure_is_retried_once(failure_kind: str) -> None:
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        if calls == 1:
            if failure_kind == "CONNECT_TIMEOUT":
                raise httpx.ConnectTimeout("connect sentinel", request=request)
            if failure_kind == "READ_TIMEOUT":
                raise httpx.ReadTimeout("read sentinel", request=request)
            if failure_kind == "CONNECT_ERROR":
                raise httpx.ConnectError("connect sentinel", request=request)
            if failure_kind == "PROVIDER_429":
                return httpx.Response(429, json={"error": "provider sentinel"})
            return httpx.Response(503, json={"error": "provider sentinel"})
        return httpx.Response(
            200,
            json={
                "model": "qwen-test",
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(
                                safe_contract_payload(), ensure_ascii=False
                            )
                        }
                    }
                ],
            },
        )

    configured = settings(
        model_base_url="https://model.example.invalid/v1",
        model_api_key="unit-test-key",
        model_name="qwen-test",
    )
    gateway = OpenAiCompatibleModelGateway(
        configured, transport=httpx.MockTransport(handler)
    )
    client = TestClient(create_app(settings=configured, model_gateway=gateway))

    response = client.post(
        "/ai/v1/agent/contract-text-analysis", json=contract_request()
    )

    assert response.status_code == 200
    assert calls == 2


@pytest.mark.parametrize("status_code", [400, 401, 403, 404])
def test_explicit_provider_4xx_is_not_retried(status_code: int) -> None:
    calls = 0

    def handler(_request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(status_code, json={"error": "provider sentinel"})

    configured = settings(
        model_base_url="https://model.example.invalid/v1",
        model_api_key="unit-test-key",
        model_name="qwen-test",
    )
    gateway = OpenAiCompatibleModelGateway(
        configured, transport=httpx.MockTransport(handler)
    )
    response = TestClient(
        create_app(settings=configured, model_gateway=gateway)
    ).post("/ai/v1/agent/contract-text-analysis", json=contract_request())

    assert response.status_code == 502
    assert response.json()["code"] == "MODEL_GATEWAY_ERROR"
    assert calls == 1


def test_model_not_configured_and_oversized_input_never_retry() -> None:
    class NotConfiguredGateway(ModelGatewayPort):
        def __init__(self) -> None:
            self.calls = 0

        def analyze_contract(self, _operation: ContractTextOperation) -> ModelCompletion:
            self.calls += 1
            raise ModelNotConfigured("not configured")

        def analyze_budget(self, _operation: BudgetSemanticOperation) -> ModelCompletion:
            raise AssertionError("budget operation was not expected")

    missing = NotConfiguredGateway()
    missing_response = TestClient(
        create_app(settings=settings(), model_gateway=missing)
    ).post("/ai/v1/agent/contract-text-analysis", json=contract_request())
    assert missing_response.status_code == 503
    assert missing_response.json()["code"] == "MODEL_NOT_CONFIGURED"
    assert missing.calls == 1

    oversized = SequencedContractGateway(
        [contract_completion(safe_contract_payload(), 1)]
    )
    oversized_request = contract_request()
    oversized_request["materials"][0]["text"] = "很长的合同文字" * 10
    oversized_response = TestClient(
        create_app(
            settings=settings(model_chunk_chars=10, model_max_chunks=1),
            model_gateway=oversized,
        )
    ).post(
        "/ai/v1/agent/contract-text-analysis", json=oversized_request
    )
    assert oversized_response.status_code == 413
    assert oversized_response.json()["code"] == "MODEL_INPUT_TOO_LARGE"
    assert oversized.contract_operations == []


def test_invalid_provider_json_and_envelope_each_use_the_single_retry() -> None:
    invalid_first_responses = [
        httpx.Response(200, content=b"invalid-provider-json"),
        httpx.Response(200, json={"model": "qwen-test", "choices": []}),
    ]
    for invalid_first in invalid_first_responses:
        calls = 0

        def handler(_request: httpx.Request) -> httpx.Response:
            nonlocal calls
            calls += 1
            if calls == 1:
                return invalid_first
            return httpx.Response(
                200,
                json={
                    "model": "qwen-test",
                    "choices": [
                        {
                            "message": {
                                "content": json.dumps(
                                    safe_contract_payload(), ensure_ascii=False
                                )
                            }
                        }
                    ],
                },
            )

        configured = settings(
            model_base_url="https://model.example.invalid/v1",
            model_api_key="unit-test-key",
            model_name="qwen-test",
        )
        response = TestClient(
            create_app(
                settings=configured,
                model_gateway=OpenAiCompatibleModelGateway(
                    configured, transport=httpx.MockTransport(handler)
                ),
            )
        ).post("/ai/v1/agent/contract-text-analysis", json=contract_request())
        assert response.status_code == 200
        assert calls == 2


def test_safety_filter_does_not_retry_or_log_rejected_output(
    caplog,
) -> None:
    response_sentinel = "REJECTED-RESPONSE-SENTINEL"
    source_sentinel = "SOURCE-SENTINEL-DO-NOT-LOG"
    secret_sentinel = "SECRET-SENTINEL-DO-NOT-LOG"
    request_bodies: list[dict[str, Any]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        request_bodies.append(json.loads(request.content))
        payload = {"summary": f"合同金额为500000元 {response_sentinel}", "hints": []}
        return httpx.Response(
            200,
            json={
                "model": "qwen-test",
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(
                                with_contract_structure(payload), ensure_ascii=False
                            )
                        }
                    }
                ],
            },
        )

    configured = settings(
        model_base_url="https://model.example.invalid/v1",
        model_api_key=secret_sentinel,
        model_name="qwen-test",
    )
    caplog.set_level(logging.INFO)
    request = contract_request()
    request["materials"][0]["text"] = source_sentinel
    response = TestClient(
        create_app(
            settings=configured,
            model_gateway=OpenAiCompatibleModelGateway(
                configured, transport=httpx.MockTransport(handler)
            ),
        )
    ).post(
        "/ai/v1/agent/contract-text-analysis",
        headers={"X-Request-Id": "correlated-request-1"},
        json=request,
    )

    assert response.status_code == 200
    assert len(request_bodies) == 1
    gateway_records = [
        record.audit_fields
        for record in caplog.records
        if record.getMessage().startswith("AI_HTTP_COMPLETED")
        and record.audit_fields.get("operation") == "CONTRACT_TEXT_ANALYSIS"
    ]
    assert [(record["attempt"], record["max_attempts"]) for record in gateway_records] == [
        (1, 2),
    ]
    assert all(
        record["request_id"] == "correlated-request-1"
        for record in gateway_records
    )
    assert not any(
        record.getMessage().startswith("contract semantic result rejected")
        for record in caplog.records
    )
    completed_record = next(
        record.audit_fields
        for record in caplog.records
        if record.getMessage().startswith("contract semantic analysis completed")
    )
    assert completed_record["request_id"] == "correlated-request-1"
    assert source_sentinel not in caplog.text
    assert response_sentinel not in caplog.text
    assert secret_sentinel not in caplog.text


def quantified_breach_request() -> dict[str, Any]:
    return {
        "projectId": "project-breach-completeness",
        "materials": [{
            "fileId": "contract-03",
            "category": "CONTRACT_BODY",
            "text": "违约金按合同金额的1%/日计算，累计不超过合同金额20%。",
        }],
    }


def quantified_breach_payload() -> dict[str, Any]:
    return {
        "summary": "已识别量化违约责任条款，建议结合合同原文复核。",
        "hints": [{
            "category": "BREACH_LIABILITY",
            "attention": "REVIEW",
            "title": "违约金计取比例",
            "description": "违约金按合同金额的1%/日计取。",
            "evidenceRefs": ["contract-03"],
            "evidenceItems": [{
                "fileId": "contract-03",
                "status": "REVIEW",
                "excerpt": "违约金按合同金额的1%/日",
            }],
        }, {
            "category": "BREACH_LIABILITY",
            "attention": "REVIEW",
            "title": "累计违约金上限",
            "description": "累计违约金最高不超过合同金额20%。",
            "evidenceRefs": ["contract-03"],
            "evidenceItems": [{
                "fileId": "contract-03",
                "status": "REVIEW",
                "excerpt": "累计不超过合同金额20%",
            }],
        }],
    }


def quantified_breach_correction_payload() -> dict[str, Any]:
    return {
        "missingCategoryHints": [{
            "category": "BREACH_LIABILITY",
            "attention": "REVIEW",
            "title": "违约金计取比例",
            "description": "违约金按合同金额的1%/日计取。",
            "problemEvidenceIds": ["C:S1"],
            "comparisonEvidenceIds": [],
        }, {
            "category": "BREACH_LIABILITY",
            "attention": "REVIEW",
            "title": "累计违约金上限",
            "description": "累计违约金最高不超过合同金额20%。",
            "problemEvidenceIds": ["C:S1"],
        }],
    }


def test_quantified_breach_category_omission_uses_one_semantic_completeness_retry(
    caplog,
) -> None:
    gateway = SequencedContractGateway([
        contract_completion(safe_contract_payload_with_draft_evidence("C:S1"), 7),
        contract_completion(quantified_breach_correction_payload(), 8),
    ])
    caplog.set_level(logging.INFO, logger="audit_ai.model.router")
    response = TestClient(create_app(settings=settings(), model_gateway=gateway)).post(
        "/ai/v1/agent/contract-text-analysis", json=quantified_breach_request()
    )

    assert response.status_code == 200
    assert len(gateway.contract_operations) == 2
    assert gateway.contract_operations[1].retry_reason == "MISSING_REQUIRED_BREACH_CATEGORY"
    assert gateway.contract_operations[1].purpose == "SEMANTIC_COMPLETENESS_CORRECTION"
    assert any(
        hint["category"] == "BREACH_LIABILITY"
        for hint in response.json()["data"]["hints"]
    )
    rejection = next(
        record.audit_fields
        for record in caplog.records
        if record.getMessage().startswith("contract semantic result rejected")
    )
    assert rejection["reason"] == "MISSING_REQUIRED_BREACH_CATEGORY"
    assert rejection["outcome"] == "RETRYING"


def test_second_quantified_breach_category_omission_fails_closed() -> None:
    gateway = SequencedContractGateway([
        contract_completion(safe_contract_payload_with_draft_evidence("C:S1"), 7),
        contract_completion(safe_contract_payload(), 8),
    ])
    response = TestClient(create_app(settings=settings(), model_gateway=gateway)).post(
        "/ai/v1/agent/contract-text-analysis", json=quantified_breach_request()
    )

    assert response.status_code == 502
    assert response.json()["code"] == "MODEL_INVALID_RESULT"
    assert len(gateway.contract_operations) == 2
    assert gateway.contract_operations[1].retry_reason == "MISSING_REQUIRED_BREACH_CATEGORY"


def test_unrelated_payment_percentage_does_not_create_a_breach_completeness_retry() -> None:
    payload = safe_contract_payload()
    payload["hints"][0]["evidenceItems"][0]["excerpt"] = "违约责任"
    gateway = SequencedContractGateway([
        contract_completion(payload, 7),
    ])
    request = contract_request()
    request["materials"][0]["text"] = (
        "违约责任由双方依法承担。付款进度按合同金额的20%执行。"
    )

    response = TestClient(create_app(settings=settings(), model_gateway=gateway)).post(
        "/ai/v1/agent/contract-text-analysis", json=request
    )

    assert response.status_code == 200
    assert len(gateway.contract_operations) == 1


def test_synthesis_cannot_drop_a_quantified_breach_category() -> None:
    class BreachSynthesisGateway(ModelGatewayPort):
        def __init__(self) -> None:
            self.operations: list[ContractTextOperation] = []

        def analyze_contract(self, operation: ContractTextOperation) -> ModelCompletion:
            self.operations.append(operation)
            if operation.purpose == "SYNTHESIZE" and operation.attempt == 1:
                return contract_completion(safe_contract_payload_for(operation), 7)
            if operation.purpose == "SEMANTIC_COMPLETENESS_CORRECTION":
                return contract_completion(quantified_breach_correction_payload(), 8)
            return contract_completion(quantified_breach_payload(), 8)

        def analyze_budget(self, _operation: BudgetSemanticOperation) -> ModelCompletion:
            raise AssertionError("budget operation was not expected")

    gateway = BreachSynthesisGateway()
    request = quantified_breach_request()
    request["materials"][0]["text"] *= 8
    response = TestClient(create_app(
        settings=settings(model_chunk_chars=120, model_max_chunks=20),
        model_gateway=gateway,
    )).post("/ai/v1/agent/contract-text-analysis", json=request)

    assert response.status_code == 200
    synthesis_operations = [
        operation for operation in gateway.operations
        if operation.purpose == "SYNTHESIZE"
    ]
    correction_operations = [
        operation for operation in gateway.operations
        if operation.purpose == "SEMANTIC_COMPLETENESS_CORRECTION"
    ]
    assert [operation.attempt for operation in synthesis_operations] == [1]
    assert [operation.attempt for operation in correction_operations] == [2]
    assert correction_operations[0].retry_reason == "MISSING_REQUIRED_BREACH_CATEGORY"
    assert {
        hint["category"] for hint in response.json()["data"]["hints"]
    } == {"BREACH_LIABILITY", *CONTRACT_BODY_REQUIRED_CATEGORIES}
