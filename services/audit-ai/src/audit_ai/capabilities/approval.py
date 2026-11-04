"""Read-only production eligibility gate. No configuration flag grants approval."""

from typing import Protocol

from audit_ai.capabilities.contracts import (
    ApprovalState, ProductionApproval, ReviewedQualityResult, RouteBinding, VerificationLevel,
)
from audit_ai.model.gateway import ModelNotConfigured


class ProductionRouteDenied(ModelNotConfigured):
    def __init__(self) -> None:
        super().__init__("capability route is not approved for production")


class ReviewedQualityResultPort(Protocol):
    def find_reviewed_result(self, binding: RouteBinding) -> ReviewedQualityResult | None:
        """Read current, consistent quality + approval facts, including revocation.

        Integration must implement an authoritative server-side read after reviewed
        Track C evidence exists. Never source this from request bodies or environment
        approval flags. Result IDs refer to immutable quality manifests. Errors or
        unknown state must return no approval or raise, not replay a stale cache.
        """


class NoReviewedQualityResults:
    def find_reviewed_result(self, binding: RouteBinding) -> None:
        return None


class ApprovalGate:
    def __init__(self, source: ReviewedQualityResultPort | None = None) -> None:
        self._source = source if source is not None else NoReviewedQualityResults()

    def require_approved(self, binding: RouteBinding) -> ProductionApproval:
        if not isinstance(binding, RouteBinding):
            raise ProductionRouteDenied()
        try:
            result = self._source.find_reviewed_result(binding)
        except Exception:
            # Do not expose upstream exception text through HTTP errors or tracebacks.
            raise ProductionRouteDenied() from None
        if not isinstance(result, ReviewedQualityResult):
            raise ProductionRouteDenied()
        quality, approval = result.quality, result.approval
        if (quality is None or approval is None or quality.manifest_version != 1
                or quality.passed is not True
                or quality.verification_level is not VerificationLevel.REAL_PROVIDER_VERIFIED
                or approval.state is not ApprovalState.APPROVED_FOR_PRODUCTION_ROUTE
                or quality.binding != binding or approval.binding != binding
                or approval.quality_result_id != quality.result_id):
            raise ProductionRouteDenied()
        return approval
