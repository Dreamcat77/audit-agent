"""Opt-in server-owned configuration; legacy Settings/create_app behavior is unchanged."""

from dataclasses import dataclass
from typing import TYPE_CHECKING

from audit_ai.capabilities.approval import ApprovalGate, ReviewedQualityResultPort
from audit_ai.capabilities.contracts import Capability, Provider, RouteBinding
from audit_ai.config import Settings
from audit_ai.model.gateway import OpenAiCompatibleModelGateway
from audit_ai.ocr.paddle_adapter import PaddleOcrAdapter

if TYPE_CHECKING:
    from audit_ai.capabilities.router import CapabilityProviderRouter


@dataclass(frozen=True, slots=True)
class ProviderRoutingConfiguration:
    routes: tuple[RouteBinding, ...] = ()

    def __post_init__(self) -> None:
        routes = tuple(self.routes)
        seen = set()
        supported = {
            (Capability.CONTRACT_SEMANTIC, Provider.QWEN_COMPATIBLE),
            (Capability.TEXT_SEMANTIC, Provider.QWEN_COMPATIBLE),
            (Capability.OCR, Provider.PADDLE_OCR),
        }
        for route in routes:
            if (not isinstance(route, RouteBinding)
                    or (route.capability, route.provider) not in supported
                    or route.capability in seen):
                raise ValueError("unsupported or duplicate capability provider route")
            seen.add(route.capability)
        object.__setattr__(self, "routes", routes)


def build_provider_router(
    settings: Settings,
    configuration: ProviderRoutingConfiguration | None = None,
    reviewed_results: ReviewedQualityResultPort | None = None,
) -> "CapabilityProviderRouter":
    """Assemble compiled adapters, never discover plugins or create approval records.

    The caller must supply reviewed quality/approval facts from an authoritative
    read adapter. The default has no routes and no approvals. Credentials stay in
    existing Settings and never enter binding/approval metadata.
    """
    from audit_ai.capabilities.router import CapabilityProviderRouter

    configuration = configuration if configuration is not None else ProviderRoutingConfiguration()
    for route in configuration.routes:
        if route.provider is Provider.QWEN_COMPATIBLE and route.model != settings.model_name:
            raise ValueError("configured model does not match capability route")
        if route.provider is Provider.PADDLE_OCR and (
                route.model != "PADDLEOCR_3.7.0" or route.revision != "3.7.0"):
            raise ValueError("configured OCR runtime does not match capability route")
    return CapabilityProviderRouter(
        configuration=configuration, gate=ApprovalGate(reviewed_results),
        model_gateway=OpenAiCompatibleModelGateway(settings), ocr_port=PaddleOcrAdapter(settings),
    )
