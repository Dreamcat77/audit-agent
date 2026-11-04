from typing import Any

from fastapi.testclient import TestClient

from audit_ai.config import Settings
from audit_ai.main import create_app
from audit_ai.model.gateway import (
    BudgetSemanticOperation,
    ContractTextOperation,
    ModelCompletion,
    ModelGatewayPort,
    SpreadsheetHeaderMappingOperation,
    TenderComplianceOperation,
)


class TenderComplianceGateway(ModelGatewayPort):
    def __init__(self, payload: dict[str, Any]) -> None:
        self.payload = payload
        self.operations: list[TenderComplianceOperation] = []

    def analyze_contract(self, operation: ContractTextOperation) -> ModelCompletion:
        raise AssertionError("contract operation was not expected")

    def analyze_budget(self, operation: BudgetSemanticOperation) -> ModelCompletion:
        raise AssertionError("budget operation was not expected")

    def analyze_tender_compliance(
        self, operation: TenderComplianceOperation
    ) -> ModelCompletion:
        self.operations.append(operation)
        return ModelCompletion(payload=self.payload, model="qwen-test", duration_ms=12)

    def analyze_header_mapping(
        self, operation: SpreadsheetHeaderMappingOperation
    ) -> ModelCompletion:
        raise AssertionError("header mapping operation was not expected")


def request_body() -> dict[str, Any]:
    return {
        "projectId": "project-1",
        "reviewId": "review-1",
        "fullText": "必须采用某系列产品。",
        "segments": [
            {
                "sourceLocator": "pdf/page:1",
                "text": "必须采用某系列产品。",
                "pageNumber": 1,
                "confidence": 1.0,
            }
        ],
        "ruleFindings": [],
    }


def test_tender_compliance_returns_structured_findings_and_preserves_locator() -> None:
    gateway = TenderComplianceGateway(
        {
            "findings": [
                {
                    "matchedText": "必须采用某系列产品",
                    "sourceLocator": "pdf/page:1",
                    "pageNumber": 1,
                    "riskCategory": "技术参数倾向",
                    "riskLevel": "WARNING",
                    "rationale": "可能指向特定产品系列",
                    "remediationSuggestion": "改为功能性描述",
                    "confidence": 0.91,
                }
            ]
        }
    )
    client = TestClient(create_app(settings=Settings(), model_gateway=gateway))

    response = client.post(
        "/ai/v1/agent/procurement-tender-compliance", json=request_body()
    )

    assert response.status_code == 200
    finding = response.json()["data"]["findings"][0]
    assert finding["sourceLocator"] == "pdf/page:1"
    assert finding["riskLevel"] == "WARNING"
    assert gateway.operations[0].project_id == "project-1"
    assert gateway.operations[0].review_id == "review-1"


def test_tender_compliance_rejects_finding_with_unknown_locator() -> None:
    gateway = TenderComplianceGateway(
        {
            "findings": [
                {
                    "matchedText": "必须采用某系列产品",
                    "sourceLocator": "pdf/page:99",
                    "pageNumber": 99,
                    "riskCategory": "技术参数倾向",
                    "riskLevel": "WARNING",
                    "rationale": "可能指向特定产品系列",
                    "remediationSuggestion": "改为功能性描述",
                    "confidence": 0.91,
                }
            ]
        }
    )
    client = TestClient(create_app(settings=Settings(), model_gateway=gateway))

    response = client.post(
        "/ai/v1/agent/procurement-tender-compliance", json=request_body()
    )

    assert response.status_code == 502


def test_tender_compliance_rejects_matched_text_not_in_source_segment() -> None:
    gateway = TenderComplianceGateway(
        {
            "findings": [
                {
                    "matchedText": "编造的风险条款",
                    "sourceLocator": "pdf/page:1",
                    "pageNumber": 1,
                    "riskCategory": "其他",
                    "riskLevel": "INFO",
                    "rationale": "输入原文不存在该片段",
                    "remediationSuggestion": "请人工复核",
                    "confidence": 0.5,
                }
            ]
        }
    )
    client = TestClient(create_app(settings=Settings(), model_gateway=gateway))

    response = client.post(
        "/ai/v1/agent/procurement-tender-compliance", json=request_body()
    )

    assert response.status_code == 502
