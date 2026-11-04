"""Versioned bounded context is a policy change, never an old-plan resume."""
import copy
import hashlib
import importlib
from pathlib import Path

import pytest

from audit_ai.errors import AuditAiError
from audit_ai.model.semantic_work import checkpoint_hash
from test_semantic_regeneration_context import setup


def test_policy_freezes_codec_executable_identity_and_rejects_old_plan_without_dispatch():
    service, request, wires, _ = setup()
    plan = service.plan(request)
    policy = plan["policy"]
    assert policy["regenerationContextProtocol"] == "contract-semantic-regeneration-context-v2"
    assert policy["regenerationContextMaxBytes"] == 8192
    assert policy.get("regenerationCandidatePacking") == "contract-regeneration-candidate-columns-v1"
    module = importlib.import_module("audit_ai.model.semantic_regeneration_packing")
    assert hashlib.sha256(Path(module.__file__).read_bytes()).hexdigest() in policy["sourceCodeHashes"]
    assert policy["automaticTransportRetries"] == 0
    assert policy["reasoningEffort"] == "none"
    assert policy["maxOutputTokens"] == 6144
    assert policy["budgetSeconds"] <= 900
    old = copy.deepcopy(plan)
    old.pop("next")
    old.pop("planFingerprint")
    old["policy"]["regenerationContextProtocol"] = "contract-semantic-regeneration-context-v1"
    old["policy"].pop("regenerationCandidatePacking")
    old["policyFingerprint"] = checkpoint_hash(old["policy"])
    with pytest.raises(AuditAiError):
        service.advance(request, checkpoint_hash(old), [], None, remaining_ms=800000, execute=False)
    assert wires == []
