"""Synthetic seam tests only; no network provider or production activation."""

from dataclasses import FrozenInstanceError, replace
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient

from audit_ai.capabilities.approval import ApprovalGate, ProductionRouteDenied
from audit_ai.capabilities.config import ProviderRoutingConfiguration, build_provider_router
from audit_ai.capabilities.contracts import Capability, Provider
from audit_ai.capabilities.router import CapabilityProviderRouter
from audit_ai.config import Settings
from audit_ai.main import create_app
from audit_ai.model.gateway import (
    BudgetSemanticOperation, ContractTextOperation, ModelCompletion, OpenAiCompatibleModelGateway,
)
from audit_ai.ocr.port import OcrExtraction
from test_capability_approval import SyntheticReviewSource, synthetic_review
from test_capability_contracts import synthetic_binding
from test_model_routes import contract_request, safe_contract_payload_for, with_contract_structure


class SyntheticGateway:
    def __init__(self):
        self.operations = []
        self.result_model = "synthetic-model"
        self.result_revision = "synthetic-revision-1"

    def analyze_contract(self, operation):
        self.operations.append(operation)
        return ModelCompletion(with_contract_structure(safe_contract_payload_for(operation)),
                               self.result_model, 7, revision=self.result_revision)

    def analyze_budget(self, operation):
        self.operations.append(operation)
        return ModelCompletion({"items": []}, self.result_model, 3, revision=self.result_revision)


class SyntheticOcr:
    def __init__(self):
        self.paths = []

    def extract(self, path):
        self.paths.append(path)
        return OcrExtraction("合成文字", 1.0, (), "PADDLEOCR_3.7.0", 2)


def synthetic_router(binding=None, source=None, gateway=None, ocr=None):
    binding = binding or synthetic_binding()
    return CapabilityProviderRouter(
        configuration=ProviderRoutingConfiguration((binding,)),
        gate=ApprovalGate(source), model_gateway=gateway, ocr_port=ocr,
    )


def test_configuration_is_immutable_and_copies_route_sequence():
    values = [synthetic_binding()]
    config = ProviderRoutingConfiguration(values)
    values.clear()
    assert config.routes == (synthetic_binding(),)
    with pytest.raises(FrozenInstanceError):
        config.routes = ()


def test_duplicate_capability_routes_are_rejected_even_for_different_revisions():
    with pytest.raises(ValueError):
        ProviderRoutingConfiguration((synthetic_binding(), synthetic_binding(revision="other")))


@pytest.mark.parametrize("capability,provider", [
    (Capability.HANDWRITING_OCR, Provider.PADDLE_OCR),
    (Capability.DOCUMENT_ROLE, Provider.QWEN_COMPATIBLE),
    (Capability.OCR, Provider.QWEN_COMPATIBLE),
    (Capability.CONTRACT_SEMANTIC, Provider.PADDLE_OCR),
])
def test_only_compiled_supported_capability_provider_pairs_can_be_configured(capability, provider):
    with pytest.raises(ValueError):
        ProviderRoutingConfiguration((synthetic_binding(capability=capability, provider=provider),))


def test_unknown_provider_or_arbitrary_untyped_configuration_is_rejected():
    with pytest.raises(ValueError):
        ProviderRoutingConfiguration(({"provider": "load_arbitrary_plugin"},))


def test_configuration_alone_never_calls_a_provider():
    gateway = SyntheticGateway()
    router = synthetic_router(gateway=gateway)
    with pytest.raises(ProductionRouteDenied):
        router.model_gateway().analyze_contract(ContractTextOperation("synthetic-project", 1, 1, "合成"))
    assert gateway.operations == []


def test_exact_synthetic_approval_forwards_typed_operation_and_safe_completion_unchanged():
    gateway = SyntheticGateway()
    source = SyntheticReviewSource(synthetic_review())
    router = synthetic_router(gateway=gateway, source=source)
    operation = ContractTextOperation("synthetic-project", 1, 1, "[evidenceId=C:S1;]合成")
    result = router.model_gateway().analyze_contract(operation)
    assert gateway.operations == [operation]
    assert result.model == "synthetic-model"
    assert result.revision == "synthetic-revision-1"
    assert result.duration_ms == 7
    assert router.describe_route(Capability.CONTRACT_SEMANTIC) == synthetic_binding()
    source.result = None
    with pytest.raises(ProductionRouteDenied):
        router.model_gateway().analyze_contract(operation)
    assert gateway.operations == [operation]


def test_an_approved_contract_route_cannot_authorize_budget_or_ocr():
    gateway = SyntheticGateway()
    router = synthetic_router(gateway=gateway, source=SyntheticReviewSource(synthetic_review()))
    with pytest.raises(ProductionRouteDenied):
        router.model_gateway().analyze_budget(BudgetSemanticOperation("synthetic-source", 1, 1, "合成"))
    with pytest.raises(ProductionRouteDenied):
        router.ocr_port().extract(Path("synthetic.png"))
    assert gateway.operations == []


def test_text_semantic_route_forwards_existing_typed_budget_operation():
    binding = synthetic_binding(capability=Capability.TEXT_SEMANTIC)
    gateway = SyntheticGateway()
    router = synthetic_router(binding, SyntheticReviewSource(synthetic_review(binding)), gateway)
    operation = BudgetSemanticOperation("synthetic-source", 1, 1, "合成")
    assert router.model_gateway().analyze_budget(operation).payload == {"items": []}
    assert gateway.operations == [operation]


def test_response_model_drift_is_rejected_even_after_valid_approval():
    gateway = SyntheticGateway()
    gateway.result_model = "unapproved-model"
    router = synthetic_router(gateway=gateway, source=SyntheticReviewSource(synthetic_review()))
    with pytest.raises(ProductionRouteDenied):
        router.model_gateway().analyze_contract(ContractTextOperation("synthetic-project", 1, 1, "合成"))


def test_legacy_three_argument_completion_has_no_runtime_revision_proof():
    completion = ModelCompletion({"items": []}, "synthetic-model", 1)
    assert hasattr(completion, "revision")
    assert completion.revision is None


@pytest.mark.parametrize("capability", [Capability.CONTRACT_SEMANTIC, Capability.TEXT_SEMANTIC])
def test_matching_model_without_runtime_revision_is_denied(capability):
    gateway = SyntheticGateway()
    gateway.result_revision = None
    binding = synthetic_binding(capability=capability)
    router = synthetic_router(binding, SyntheticReviewSource(synthetic_review(binding)), gateway)
    with pytest.raises(ProductionRouteDenied):
        if capability is Capability.CONTRACT_SEMANTIC:
            router.model_gateway().analyze_contract(ContractTextOperation("synthetic-project", 1, 1, "synthetic"))
        else:
            router.model_gateway().analyze_budget(BudgetSemanticOperation("synthetic-source", 1, 1, "synthetic"))


@pytest.mark.parametrize("capability", [Capability.CONTRACT_SEMANTIC, Capability.TEXT_SEMANTIC])
def test_runtime_revision_drift_denied_even_when_model_stays_the_same(capability):
    gateway = SyntheticGateway()
    gateway.result_revision = "synthetic-revision-2"
    binding = synthetic_binding(capability=capability)
    router = synthetic_router(binding, SyntheticReviewSource(synthetic_review(binding)), gateway)
    with pytest.raises(ProductionRouteDenied):
        if capability is Capability.CONTRACT_SEMANTIC:
            router.model_gateway().analyze_contract(ContractTextOperation("synthetic-project", 1, 1, "synthetic"))
        else:
            router.model_gateway().analyze_budget(BudgetSemanticOperation("synthetic-source", 1, 1, "synthetic"))


def test_factory_real_gateway_cannot_turn_unproven_metadata_into_approved_revision(monkeypatch):
    binding = synthetic_binding()
    settings = Settings(model_name="synthetic-model", model_api_key="synthetic-key",
                        model_base_url="https://synthetic.invalid/v1")
    transport = httpx.MockTransport(lambda request: httpx.Response(200, json={
        "model": "synthetic-model", "id": "synthetic-revision-1",
        "system_fingerprint": "synthetic-revision-1", "created": 1,
        "revision": "synthetic-revision-1",
        "choices": [{"message": {"content": '{"items": [], "revision": "synthetic-revision-1"}'}}],
    }))
    # Preserve the real gateway/parser and factory; replace only external HTTP transport.
    real_client = httpx.Client
    monkeypatch.setattr(httpx, "Client", lambda **kwargs: real_client(**{**kwargs, "transport": transport}))
    legacy = OpenAiCompatibleModelGateway(settings).analyze_contract(
        ContractTextOperation("synthetic-project", 1, 1, "synthetic"))
    assert legacy.model == "synthetic-model"
    assert legacy.payload["items"] == []
    assert legacy.revision is None
    router = build_provider_router(settings, ProviderRoutingConfiguration((binding,)),
                                   SyntheticReviewSource(synthetic_review(binding)))
    with pytest.raises(ProductionRouteDenied):
        router.model_gateway().analyze_contract(ContractTextOperation("synthetic-project", 1, 1, "synthetic"))


def test_changed_approval_revision_without_new_quality_result_denies_before_dispatch():
    binding = synthetic_binding(revision="synthetic-revision-2")
    review = synthetic_review()
    review = replace(review, approval=replace(review.approval, binding=binding))
    gateway = SyntheticGateway()
    router = synthetic_router(binding, SyntheticReviewSource(review), gateway)
    with pytest.raises(ProductionRouteDenied):
        router.model_gateway().analyze_contract(ContractTextOperation("synthetic-project", 1, 1, "synthetic"))
    assert gateway.operations == []


def test_exact_synthetic_ocr_approval_uses_only_ocr_and_keeps_provenance():
    binding = synthetic_binding(capability=Capability.OCR, provider=Provider.PADDLE_OCR,
                                model="PADDLEOCR_3.7.0", revision="3.7.0")
    port = SyntheticOcr()
    source = SyntheticReviewSource(synthetic_review(binding))
    router = synthetic_router(binding, source, ocr=port)
    path = Path("synthetic.png")
    result = router.ocr_port().extract(path)
    assert result.engine == binding.model
    assert result.duration_ms == 2
    assert port.paths == [path]
    source.result = None
    with pytest.raises(ProductionRouteDenied):
        router.ocr_port().extract(path)
    assert port.paths == [path]


def test_factory_defaults_remain_denied_without_importing_or_contacting_a_provider():
    router = build_provider_router(Settings())
    with pytest.raises(ProductionRouteDenied):
        router.model_gateway().analyze_contract(ContractTextOperation("synthetic-project", 1, 1, "合成"))
    with pytest.raises(ProductionRouteDenied):
        router.ocr_port().extract(Path("synthetic.png"))


def test_factory_rejects_model_config_drift_before_adapter_dispatch():
    with pytest.raises(ValueError):
        build_provider_router(Settings(model_name="different-model"),
                              ProviderRoutingConfiguration((synthetic_binding(),)))


def test_factory_checks_paddle_runtime_binding_without_claiming_handwriting():
    binding = synthetic_binding(capability=Capability.OCR, provider=Provider.PADDLE_OCR,
                                model="PADDLEOCR_3.7.0", revision="unexpected")
    with pytest.raises(ValueError):
        build_provider_router(Settings(), ProviderRoutingConfiguration((binding,)))


def test_request_cannot_supply_production_approval_or_bypass_the_gate():
    gateway = SyntheticGateway()
    router = synthetic_router(gateway=gateway)
    client = TestClient(create_app(Settings(), model_gateway=router.model_gateway()))
    body = contract_request()
    body["productionApproval"] = {"approved": True, "provider": "QWEN_COMPATIBLE"}
    response = client.post("/ai/v1/agent/contract-text-analysis", json=body)
    assert response.status_code >= 400
    assert gateway.operations == []


def test_synthetic_approved_route_still_uses_existing_schema_and_evidence_validation():
    gateway = SyntheticGateway()
    router = synthetic_router(gateway=gateway, source=SyntheticReviewSource(synthetic_review()))
    client = TestClient(create_app(Settings(), model_gateway=router.model_gateway()))
    response = client.post("/ai/v1/agent/contract-text-analysis", json=contract_request())
    assert response.status_code == 200
    data = response.json()["data"]
    assert "taskId" not in data and "reviewRunId" not in data and "approvedBy" not in data
    assert gateway.operations
    invalid = contract_request()
    invalid["materials"] = []
    count = len(gateway.operations)
    rejected = client.post("/ai/v1/agent/contract-text-analysis", json=invalid)
    assert rejected.status_code >= 400
    assert len(gateway.operations) == count


def test_provider_cannot_inject_java_business_identity_or_fabricated_evidence():
    class MaliciousSyntheticGateway(SyntheticGateway):
        def analyze_contract(self, operation):
            result = super().analyze_contract(operation)
            payload = dict(result.payload)
            payload["taskId"] = "provider-invented-task"
            payload["approvedBy"] = "provider-invented-actor"
            payload["hints"] = [*payload["hints"], {
                "category": "OTHER_TEXT", "attention": "REVIEW", "title": "合成提示",
                "description": "合成提示", "problemEvidenceIds": ["invented-evidence"],
                "fileId": "provider-invented-file", "locatorAvailable": True,
            }]
            return ModelCompletion(payload, result.model, result.duration_ms, revision=result.revision)

    router = synthetic_router(gateway=MaliciousSyntheticGateway(),
                              source=SyntheticReviewSource(synthetic_review()))
    client = TestClient(create_app(Settings(), model_gateway=router.model_gateway()))
    response = client.post("/ai/v1/agent/contract-text-analysis", json=contract_request())
    assert response.status_code == 502
    assert response.json()["code"] == "MODEL_INVALID_RESULT"
    assert "provider-invented" not in response.text
    assert "invented-evidence" not in response.text
    assert "taskId" not in response.text and "approvedBy" not in response.text


def test_missing_compiled_adapter_cannot_be_bypassed_by_synthetic_approval():
    router = synthetic_router(source=SyntheticReviewSource(synthetic_review()))
    with pytest.raises(ProductionRouteDenied):
        router.model_gateway().analyze_contract(ContractTextOperation("synthetic-project", 1, 1, "合成"))


def test_ocr_runtime_drift_is_rejected_after_synthetic_approval():
    class ChangedOcr(SyntheticOcr):
        def extract(self, path):
            return OcrExtraction("合成", 1.0, (), "UNAPPROVED_ENGINE", 1)

    binding = synthetic_binding(capability=Capability.OCR, provider=Provider.PADDLE_OCR,
                                model="PADDLEOCR_3.7.0", revision="3.7.0")
    router = synthetic_router(binding, SyntheticReviewSource(synthetic_review(binding)), ocr=ChangedOcr())
    with pytest.raises(ProductionRouteDenied):
        router.ocr_port().extract(Path("synthetic.png"))
