"""Typed dispatch behind per-call approval, preserving downstream schema/evidence validation.

This seam cannot approve business findings or allocate/persist Java-owned identities.
Adapters supplied directly to the constructor are trusted assembly/test seams, not
user configuration. Production assembly uses config.build_provider_router only.
"""

from dataclasses import dataclass
from pathlib import Path
from types import MappingProxyType

from audit_ai.capabilities.approval import ApprovalGate, ProductionRouteDenied
from audit_ai.capabilities.config import ProviderRoutingConfiguration
from audit_ai.capabilities.contracts import Capability, RouteBinding
from audit_ai.model.gateway import BudgetSemanticOperation, ContractTextOperation, ModelCompletion, ModelGatewayPort
from audit_ai.ocr.port import OcrExtraction, OcrPort


class CapabilityProviderRouter:
    def __init__(self, *, configuration: ProviderRoutingConfiguration, gate: ApprovalGate,
                 model_gateway: ModelGatewayPort | None = None, ocr_port: OcrPort | None = None) -> None:
        self._routes = MappingProxyType({route.capability: route for route in configuration.routes})
        self._gate = gate
        self._model = model_gateway
        self._ocr = ocr_port

    def describe_route(self, capability: Capability) -> RouteBinding | None:
        """Configured provenance only, never an approval/verification claim."""
        return self._routes.get(capability)

    def model_gateway(self) -> ModelGatewayPort:
        return _ApprovedModelGateway(self)

    def ocr_port(self) -> OcrPort:
        return _ApprovedOcrPort(self)

    def _approved(self, capability: Capability) -> RouteBinding:
        binding = self._routes.get(capability)
        if binding is None:
            raise ProductionRouteDenied()
        self._gate.require_approved(binding)
        return binding

    def _contract(self, operation: ContractTextOperation) -> ModelCompletion:
        binding = self._approved(Capability.CONTRACT_SEMANTIC)
        if self._model is None or not isinstance(operation, ContractTextOperation):
            raise ProductionRouteDenied()
        return _matching_completion(binding, self._model.analyze_contract(operation))

    def _budget(self, operation: BudgetSemanticOperation) -> ModelCompletion:
        binding = self._approved(Capability.TEXT_SEMANTIC)
        if self._model is None or not isinstance(operation, BudgetSemanticOperation):
            raise ProductionRouteDenied()
        return _matching_completion(binding, self._model.analyze_budget(operation))

    def _extract(self, path: Path) -> OcrExtraction:
        binding = self._approved(Capability.OCR)
        if self._ocr is None:
            raise ProductionRouteDenied()
        result = self._ocr.extract(path)
        if not isinstance(result, OcrExtraction) or result.engine != binding.model:
            raise ProductionRouteDenied()
        return result


def _matching_completion(binding: RouteBinding, result: ModelCompletion) -> ModelCompletion:
    if (not isinstance(result, ModelCompletion) or result.model != binding.model
            or result.revision is None or result.revision != binding.revision):
        raise ProductionRouteDenied()
    return result


@dataclass(frozen=True, slots=True)
class _ApprovedModelGateway:
    router: CapabilityProviderRouter

    def analyze_contract(self, operation: ContractTextOperation) -> ModelCompletion:
        return self.router._contract(operation)

    def analyze_budget(self, operation: BudgetSemanticOperation) -> ModelCompletion:
        return self.router._budget(operation)


@dataclass(frozen=True, slots=True)
class _ApprovedOcrPort:
    router: CapabilityProviderRouter

    def extract(self, path: Path) -> OcrExtraction:
        return self.router._extract(path)
