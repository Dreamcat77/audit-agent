"""Real FINAL repair wire must not mistake eight diagnostics for all sources."""
import copy
from dataclasses import replace
import json

import pytest

from audit_ai.model.contract_draft import ProviderValidationIssue
from audit_ai.model.gateway import ContractTextOperation, contract_hierarchy_wire


def operation():
    source = {"hierarchyProtocol": "contract-bounded-hierarchy-v1", "role": "FINAL",
        "groupingFingerprint": "a" * 64, "claimColumns": ["categoryIndex", "attentionIndex", "title",
        "description", "claimKindIndex", "scopeIndex", "problemEvidenceIndexes", "comparisonEvidenceIndexes", "unavailableMaterialCategory"],
        "enums": {"claimKinds": ["CLAUSE_PRESENT", "LOCAL_NOT_SEEN"]},
        "groups": [{"claims": [[0, 0, "待核实", "仅本段观察", 1, 0, [], [], None] for _ in range(11)]}]}
    issue = ProviderValidationIssue("inheritedHints", None, "UNRESOLVED_LOCAL_OBSERVATION",
        "SOURCE_G1_H1_INVALID_SCOPED_CLAIM", category="CONTRACT_STRUCTURE")
    return ContractTextOperation(project_id="synthetic-only", chunk_index=1, chunk_count=1,
        source_text=json.dumps(source, ensure_ascii=False), purpose="SYNTHESIZE",
        generation_policy="contract-semantic-work-v3", quality_protocol="contract-semantic-quality-v1",
        hierarchy_protocol="contract-bounded-hierarchy-v1", hierarchy_role="FINAL",
        hierarchy_logical_key="b" * 64, grouping_fingerprint="a" * 64, validation_feedback=(issue,))


def test_repair_names_all_eleven_local_sources_not_only_eight_diagnostics():
    op = operation(); original = copy.deepcopy(json.loads(op.source_text))
    system = contract_hierarchy_wire(op)["system_prompt"]
    instructions = json.loads(system.split("QUALITY_REPAIR_GUIDANCE=", 1)[1].splitlines()[0])
    assert all(ref in instructions[0] for ref in [f"G1:H{i}" for i in range(1, 12)])
    assert "G1:H12" not in instructions[0]
    assert json.loads(op.source_text) == original


def test_complete_reference_enumeration_does_not_treat_nonlocal_claims_as_local():
    op = operation(); source = json.loads(op.source_text)
    source["groups"][0]["claims"][-1][4] = 0
    op = replace(op, source_text=json.dumps(source, ensure_ascii=False))
    instructions = json.loads(contract_hierarchy_wire(op)["system_prompt"].split("QUALITY_REPAIR_GUIDANCE=", 1)[1].splitlines()[0])
    assert "G1:H10" in instructions[0] and "G1:H11" not in instructions[0]


def test_primary_request_stays_without_repair_instructions():
    op = replace(operation(), validation_feedback=())
    assert "QUALITY_REPAIR_GUIDANCE=" not in contract_hierarchy_wire(op)["system_prompt"]


@pytest.mark.parametrize("defect", ["duplicate-kind", "duplicate-column", "non-string-kind", "non-string-column"])
def test_ambiguous_metadata_never_receives_complete_source_authority(defect):
    op = operation(); source = json.loads(op.source_text)
    if defect == "duplicate-kind":
        source["enums"]["claimKinds"].append("LOCAL_NOT_SEEN")
        source["groups"][0]["claims"][-1][4] = 2
    elif defect == "duplicate-column":
        source["claimColumns"].append("claimKindIndex")
        for row in source["groups"][0]["claims"]: row.append(1)
    elif defect == "non-string-kind": source["enums"]["claimKinds"].append(None)
    else:
        source["claimColumns"].append(None)
        for row in source["groups"][0]["claims"]: row.append(None)
    with pytest.raises(ValueError, match="HIERARCHY_LOCAL_REFERENCE_SCOPE_INVALID"):
        contract_hierarchy_wire(replace(op, source_text=json.dumps(source, ensure_ascii=False)))
