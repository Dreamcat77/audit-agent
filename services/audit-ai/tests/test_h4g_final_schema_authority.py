"""Real request-builder schema authority; synthetic wire only, no provider."""
import json

import pytest

from audit_ai.model.gateway import ContractTextOperation, contract_hierarchy_wire


def wire(role):
    source = {"hierarchyProtocol": "contract-bounded-hierarchy-v1", "role": role,
        "groupingFingerprint": "a" * 64, "analyses": [{"leaves": []}], "groups": [{"claims": []}]}
    operation = ContractTextOperation(project_id="synthetic-only", chunk_index=1, chunk_count=1,
        source_text=json.dumps(source), purpose="SYNTHESIZE", generation_policy="contract-semantic-work-v3",
        quality_protocol="contract-semantic-quality-v1", hierarchy_protocol="contract-bounded-hierarchy-v1",
        hierarchy_role=role, hierarchy_logical_key="b" * 64, grouping_fingerprint="a" * 64)
    return contract_hierarchy_wire(operation)


@pytest.mark.parametrize("role", ["GROUP", "FINAL"])
def test_hierarchy_request_schema_matches_existing_stage_claim_type_authority(role):
    system = wire(role)["system_prompt"]
    schema = json.loads(system.split("AUTHORITATIVE_PROVIDER_DRAFT_SCHEMA=", 1)[1].splitlines()[0])
    values = schema["$defs"]["QualityHint"]["properties"]["claimKind"]["enum"]
    assert ("LOCAL_NOT_SEEN" in values) is (role == "GROUP")
    assert ("GLOBAL_CLAUSE_NOT_FOUND" in values) is (role == "FINAL")


def test_final_primary_explains_that_inherited_local_alias_is_not_a_publishable_target():
    system = wire("FINAL")["system_prompt"]
    assert "FINAL_LOCAL_OBSERVATION_RULE" in system
    for literal in ("LOCAL_NOT_SEEN", "targetRefs", "NEEDS_VERIFICATION", "newHints", "basis", "reason"):
        assert literal in system.split("FINAL_LOCAL_OBSERVATION_RULE", 1)[1].splitlines()[0]
    assert "FINAL_LOCAL_OBSERVATION_RULE" not in wire("GROUP")["system_prompt"]
