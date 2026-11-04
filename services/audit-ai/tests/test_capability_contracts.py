"""Synthetic value-contract tests; no actual provider approval evidence."""

from dataclasses import FrozenInstanceError, replace

import pytest

from audit_ai.capabilities.contracts import Capability, Provider, RouteBinding


def synthetic_binding(**changes) -> RouteBinding:
    return replace(RouteBinding(
        capability=Capability.CONTRACT_SEMANTIC,
        provider=Provider.QWEN_COMPATIBLE,
        model="synthetic-model", revision="synthetic-revision-1",
        fixture_version="synthetic-fixture-v1", metric_version="synthetic-metric-v1",
        threshold_profile="synthetic-threshold-v1", schema_version="schema-v1",
        prompt_version="prompt-v1", policy_version="policy-v1",
    ), **changes)


def test_route_binding_is_immutable_and_every_version_participates_in_identity():
    binding = synthetic_binding()
    with pytest.raises(FrozenInstanceError):
        binding.revision = "other"
    assert synthetic_binding(revision="other") != binding
    assert hash(binding) == hash(synthetic_binding())


@pytest.mark.parametrize("value", ["", " ", "secret\nvalue", "https://unsafe.invalid", "a" * 129, None])
def test_binding_rejects_unbounded_or_unsafe_metadata(value):
    with pytest.raises(ValueError):
        synthetic_binding(revision=value)


@pytest.mark.parametrize("changes", [{"provider": "unknown"}, {"capability": "OCR"}])
def test_binding_requires_closed_typed_capabilities_and_providers(changes):
    with pytest.raises(ValueError):
        synthetic_binding(**changes)
