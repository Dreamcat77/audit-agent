"""All manifests/approvals in this module are synthetic simulations, not real attestations."""

from dataclasses import replace

import pytest

from audit_ai.capabilities.approval import ApprovalGate, ProductionRouteDenied
from audit_ai.capabilities.contracts import (
    ApprovalState, Capability, ProductionApproval, Provider, QualityResultManifest,
    ReviewedQualityResult, VerificationLevel,
)
from test_capability_contracts import synthetic_binding


def synthetic_review(binding=None):
    binding = binding or synthetic_binding()
    quality = QualityResultManifest(
        manifest_version=1, result_id="synthetic-result", binding=binding, passed=True,
        verification_level=VerificationLevel.REAL_PROVIDER_VERIFIED,
    )
    approval = ProductionApproval(
        approval_id="synthetic-approval", approved_by="synthetic-reviewer",
        quality_result_id=quality.result_id, binding=binding,
        state=ApprovalState.APPROVED_FOR_PRODUCTION_ROUTE,
    )
    return ReviewedQualityResult(quality=quality, approval=approval)


class SyntheticReviewSource:
    def __init__(self, result):
        self.result = result
        self.calls = 0

    def find_reviewed_result(self, binding):
        self.calls += 1
        return self.result


def test_default_source_does_not_approve_configuration():
    with pytest.raises(ProductionRouteDenied, match="not approved"):
        ApprovalGate().require_approved(synthetic_binding())


def test_exact_synthetic_review_returns_its_approval_and_revocation_is_not_cached():
    source = SyntheticReviewSource(synthetic_review())
    gate = ApprovalGate(source)
    assert gate.require_approved(synthetic_binding()) == source.result.approval
    source.result = None
    with pytest.raises(ProductionRouteDenied):
        gate.require_approved(synthetic_binding())
    assert source.calls == 2


@pytest.mark.parametrize("field,value", [
    ("capability", Capability.TEXT_SEMANTIC), ("provider", Provider.PADDLE_OCR),
    ("model", "other-model"), ("revision", "other-revision"),
    ("fixture_version", "other-fixture"), ("metric_version", "other-metric"),
    ("threshold_profile", "other-threshold"), ("schema_version", "other-schema"),
    ("prompt_version", "other-prompt"), ("policy_version", "other-policy"),
])
@pytest.mark.parametrize("target", ["quality", "approval", "requested-route"])
def test_every_binding_dimension_must_match_quality_approval_and_route(field, value, target):
    review = synthetic_review()
    requested = synthetic_binding()
    changed = replace(requested, **{field: value})
    if target == "requested-route":
        requested = changed
    else:
        review = replace(review, **{target: replace(getattr(review, target), binding=changed)})
    with pytest.raises(ProductionRouteDenied):
        ApprovalGate(SyntheticReviewSource(review)).require_approved(requested)


@pytest.mark.parametrize("changes", [
    {"manifest_version": 2}, {"passed": False},
    {"verification_level": VerificationLevel.LOCAL_CONTRACT_VERIFIED},
    {"verification_level": VerificationLevel.NOT_VERIFIED},
])
def test_unaccepted_or_wrong_version_quality_cannot_approve(changes):
    review = synthetic_review()
    review = replace(review, quality=replace(review.quality, **changes))
    with pytest.raises(ProductionRouteDenied):
        ApprovalGate(SyntheticReviewSource(review)).require_approved(synthetic_binding())


@pytest.mark.parametrize("changes", [
    {"quality_result_id": "different-result"},
    {"state": ApprovalState.NOT_APPROVED_FOR_PRODUCTION_ROUTE},
])
def test_approval_must_be_explicit_and_reference_exact_quality_result(changes):
    review = synthetic_review()
    review = replace(review, approval=replace(review.approval, **changes))
    with pytest.raises(ProductionRouteDenied):
        ApprovalGate(SyntheticReviewSource(review)).require_approved(synthetic_binding())


@pytest.mark.parametrize("field", ["approval_id", "approved_by"])
def test_approval_identity_is_required(field):
    with pytest.raises(ValueError):
        replace(synthetic_review().approval, **{field: ""})


def test_missing_quality_or_approval_is_denied():
    for review in (ReviewedQualityResult(None, None),
                   ReviewedQualityResult(synthetic_review().quality, None)):
        with pytest.raises(ProductionRouteDenied):
            ApprovalGate(SyntheticReviewSource(review)).require_approved(synthetic_binding())


def test_unknown_source_state_and_exception_fail_closed_with_safe_message():
    class BrokenSource:
        def find_reviewed_result(self, binding):
            raise RuntimeError("confidential upstream response")

    with pytest.raises(ProductionRouteDenied) as failure:
        ApprovalGate(BrokenSource()).require_approved(synthetic_binding())
    assert "confidential" not in str(failure.value)
    assert failure.value.__suppress_context__
    with pytest.raises(ProductionRouteDenied):
        ApprovalGate(SyntheticReviewSource({"approved": True})).require_approved(synthetic_binding())
