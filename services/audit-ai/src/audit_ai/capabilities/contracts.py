"""Small immutable quality-result interchange contract, independent of a harness implementation.

Only a trusted server-side read adapter may supply reviewed results. These values are
not HTTP request DTOs, credentials, business facts, or proof of actual deployment.
"""

from dataclasses import dataclass
from enum import Enum
import re


class Capability(Enum):
    OCR = "OCR"
    HANDWRITING_OCR = "HANDWRITING_OCR"
    CONTRACT_SEMANTIC = "CONTRACT_SEMANTIC"
    TEXT_SEMANTIC = "TEXT_SEMANTIC"
    DOCUMENT_ROLE = "DOCUMENT_ROLE"


class Provider(Enum):
    QWEN_COMPATIBLE = "QWEN_COMPATIBLE"
    PADDLE_OCR = "PADDLE_OCR"


class ApprovalState(Enum):
    NOT_APPROVED_FOR_PRODUCTION_ROUTE = "NOT_APPROVED_FOR_PRODUCTION_ROUTE"
    APPROVED_FOR_PRODUCTION_ROUTE = "APPROVED_FOR_PRODUCTION_ROUTE"


class VerificationLevel(Enum):
    NOT_VERIFIED = "NOT_VERIFIED"
    LOCAL_CONTRACT_VERIFIED = "LOCAL_CONTRACT_VERIFIED"
    REAL_PROVIDER_VERIFIED = "REAL_PROVIDER_VERIFIED"


def _identifier(value: str) -> None:
    if not isinstance(value, str) or re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}", value) is None:
        raise ValueError("invalid capability metadata identifier")


@dataclass(frozen=True, slots=True)
class RouteBinding:
    capability: Capability
    provider: Provider
    model: str
    revision: str
    fixture_version: str
    metric_version: str
    threshold_profile: str
    schema_version: str
    prompt_version: str
    policy_version: str

    def __post_init__(self) -> None:
        if not isinstance(self.capability, Capability) or not isinstance(self.provider, Provider):
            raise ValueError("unknown capability or provider")
        for value in (self.model, self.revision, self.fixture_version, self.metric_version,
                      self.threshold_profile, self.schema_version, self.prompt_version, self.policy_version):
            _identifier(value)


@dataclass(frozen=True, slots=True)
class QualityResultManifest:
    manifest_version: int
    result_id: str
    binding: RouteBinding
    passed: bool
    verification_level: VerificationLevel

    def __post_init__(self) -> None:
        _identifier(self.result_id)
        if (type(self.manifest_version) is not int or self.manifest_version < 1
                or not isinstance(self.binding, RouteBinding) or type(self.passed) is not bool
                or not isinstance(self.verification_level, VerificationLevel)):
            raise ValueError("invalid quality result contract")


@dataclass(frozen=True, slots=True)
class ProductionApproval:
    approval_id: str
    approved_by: str
    quality_result_id: str
    binding: RouteBinding
    state: ApprovalState

    def __post_init__(self) -> None:
        for value in (self.approval_id, self.approved_by, self.quality_result_id):
            _identifier(value)
        if not isinstance(self.binding, RouteBinding) or not isinstance(self.state, ApprovalState):
            raise ValueError("invalid production approval contract")


@dataclass(frozen=True, slots=True)
class ReviewedQualityResult:
    """One consistent authoritative read; a missing part is never approval."""

    quality: QualityResultManifest | None
    approval: ProductionApproval | None

    def __post_init__(self) -> None:
        if (self.quality is not None and not isinstance(self.quality, QualityResultManifest)) or (
                self.approval is not None and not isinstance(self.approval, ProductionApproval)):
            raise ValueError("invalid reviewed quality result")
