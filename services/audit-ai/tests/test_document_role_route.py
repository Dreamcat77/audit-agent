from fastapi.testclient import TestClient

from audit_ai.config import Settings
from audit_ai.main import create_app
from audit_ai.model.gateway import DocumentRoleOperation, ModelCompletion


class DocumentRoleGateway:
    def __init__(self) -> None:
        self.operations: list[DocumentRoleOperation] = []

    def analyze_document_role(self, operation: DocumentRoleOperation) -> ModelCompletion:
        self.operations.append(operation)
        return ModelCompletion(
            payload={
                "proposedRole": "CONTRACT_BODY",
                "confidence": 0.91,
                "candidateRoles": [
                    {
                        "role": "CONTRACT_BODY",
                        "confidence": 0.91,
                        "reasonCode": "CONTRACT_TERMS",
                    }
                ],
                "reasonCodes": ["CONTRACT_TERMS"],
                "evidenceSegmentIds": ["segment-1"],
            },
            model="qwen-test",
            duration_ms=7,
        )


def request_body() -> dict:
    return {
        "fileId": "00000000-0000-0000-0000-000000000101",
        "parseResultId": "00000000-0000-0000-0000-000000000102",
        "parseVersion": 1,
        "canonicalTextSha256": "a" * 64,
        "segments": [
            {
                "segmentId": "segment-1",
                "sourceLocator": "parse-result:00000000-0000-0000-0000-000000000102:canonical-codepoint:0-4",
                "textSha256": "b" * 64,
                "text": "采购合同",
            }
        ],
    }


def test_document_role_is_disabled_by_default() -> None:
    response = TestClient(create_app(Settings(), model_gateway=DocumentRoleGateway())).post(
        "/ai/v1/agent/document-role", json=request_body()
    )

    assert response.status_code == 503
    assert response.json()["code"] == "DOCUMENT_ROLE_DISABLED"


def test_document_role_uses_existing_gateway_and_returns_validated_provenance() -> None:
    gateway = DocumentRoleGateway()
    app = create_app(
        Settings(document_role_enabled=True), model_gateway=gateway
    )

    response = TestClient(app).post(
        "/ai/v1/agent/document-role",
        headers={"X-Request-Id": "document-role-test"},
        json=request_body(),
    )

    assert response.status_code == 200
    data = response.json()["data"]
    assert data["proposedRole"] == "CONTRACT_BODY"
    assert data["evidenceSegmentIds"] == ["segment-1"]
    assert data["schemaVersion"] == "document-role-v1"
    assert data["promptVersion"] == "document-role-prompt-v1"
    assert data["modelProvider"] == "OPENAI_COMPATIBLE"
    assert data["modelName"] == "qwen-test"
    assert data["modelRevision"] == "NOT_DECLARED_BY_PROVIDER"
    assert gateway.operations[0].request_id == "document-role-test"


def test_document_role_rejects_provider_created_evidence_identity() -> None:
    gateway = DocumentRoleGateway()
    original = gateway.analyze_document_role

    def invalid(_operation: DocumentRoleOperation) -> ModelCompletion:
        result = original(_operation)
        return ModelCompletion(
            payload={**result.payload, "evidenceSegmentIds": ["invented"]},
            model=result.model,
            duration_ms=result.duration_ms,
        )

    gateway.analyze_document_role = invalid  # type: ignore[method-assign]
    response = TestClient(
        create_app(Settings(document_role_enabled=True), model_gateway=gateway)
    ).post("/ai/v1/agent/document-role", json=request_body())

    assert response.status_code == 502
    assert response.json()["code"] == "DOCUMENT_ROLE_INVALID_RESPONSE"
