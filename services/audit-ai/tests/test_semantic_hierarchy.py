"""Pure hierarchy regressions: no provider, filesystem fixture or private text."""
import copy
import hashlib
import json

import pytest

from audit_ai.errors import AuditAiError
from audit_ai.model.semantic_hierarchy import (
    PROTOCOL, admit_analysis_count, build_hierarchy_inputs, canonical,
    compose_final_resolutions, final_source, group_digest, validate_group_lineage,
)


def hint(eid, *, party="PARTY_B", trigger="验收", attention="REVIEW", kind="AMBIGUOUS_CLAUSE"):
    return {"category": "BREACH_LIABILITY", "attention": attention, "title": "责任条款待核对",
        "description": "责任条款边界需核对。", "problemEvidenceIds": [eid], "comparisonEvidenceIds": [],
        "unavailableMaterialCategory": None, "claimKind": kind,
        "obligationScope": {"topic": "OTHER_LIABILITY", "responsibleParty": party, "trigger": trigger}}


def fixture(n=16, *, hints_per_analysis=3, chars=20):
    analyses, catalog = [], []
    for i in range(n):
        eid = f"evidence-original-{i}"
        catalog.append({"evidenceId": eid, "fileId": f"file-{i % 3}", "materialCategory": "CONTRACT_BODY",
            "sourceStart": i * 100, "sourceEnd": i * 100 + 90, "citable": True})
        hs = [dict(hint(eid), title=f"责任条款{i}-{j}", description="原始语义" + "字" * chars)
              for j in range(hints_per_analysis)]
        analyses.append({"logicalKey": f"analysis-{i + 1}", "checkpointHash": hashlib.sha256(str(i).encode()).hexdigest(),
            "summary": f"完整分析{i}", "hints": hs})
    return analyses, catalog


def bundle(n=16, **kwargs):
    return build_hierarchy_inputs(*fixture(n, **kwargs), material_states=[{"category": "CONTRACT_BODY", "present": True}])


def candidate(b, g):
    hints, lineage = [], []
    for leaf_id in g["leafIds"]:
        original = b["leaves"][leaf_id]["hint"]
        hints.append(copy.deepcopy(original))
        lineage.append([leaf_id, "KEEP", len(hints) - 1,
                        original["problemEvidenceIds"] + original["comparisonEvidenceIds"], "保留原观察"])
    return {"summary": "逐条保留本组观察。", "scopeStatus": "CHECKED_WITH_FINDINGS" if hints else "CHECKED_NO_RELEVANT_FINDINGS",
            "hints": hints, "lineage": lineage}


@pytest.mark.parametrize("n,g,limit", [(1, 1, 5), (7, 2, 12), (16, 3, 22)])
def test_admission_counts_and_all_analysis_consumed_once(n, g, limit):
    assert admit_analysis_count(n) == {"analysisCount": n, "groupCount": g, "initialCalls": n + g + 1, "callLimit": limit}
    b = bundle(n)
    assert len(b["groups"]) == g
    assert sorted(i for group in b["groups"] for i in group["members"]) == list(range(n))
    assert all(group["members"] and len(group["source"]) <= 12000 for group in b["groups"])
    assert len(b["leaves"]) == n * 3
    assert len(set(b["leaves"])) == n * 3
    assert b["groups"] == bundle(n)["groups"]


@pytest.mark.parametrize("n", [0, 17, True, 1.0, -1])
def test_a17_and_invalid_admission_rejected_not_clamped(n):
    with pytest.raises(AuditAiError) as error:
        admit_analysis_count(n)
    assert error.value.code == "SEMANTIC_HIERARCHY_ANALYSIS_BOUND"


def test_complete_text_no_machine_ranges_and_exact_capacity_boundary():
    b = bundle(1, hints_per_analysis=1)
    g = b["groups"][0]
    assert "原始语义" in g["source"] and "evidence-original-0" in g["source"]
    assert "checkpointHash" not in g["source"] and "checkedEvidenceIds" not in g["source"]
    analyses, catalog = fixture(1, hints_per_analysis=1, chars=20 + 12000 - len(g["source"]))
    exact = build_hierarchy_inputs(analyses, catalog, material_states=[{"category": "CONTRACT_BODY", "present": True}])
    assert len(exact["groups"][0]["source"]) == 12000
    analyses[0]["hints"][0]["description"] += "尾"
    with pytest.raises(AuditAiError) as error:
        build_hierarchy_inputs(analyses, catalog, material_states=[{"category": "CONTRACT_BODY", "present": True}])
    assert error.value.code == "SEMANTIC_HIERARCHY_GROUP_INPUT_BOUND"


def test_dense_final_proved_by_allocations_and_overflow_not_truncated():
    b = bundle(hints_per_analysis=1)
    assert b["finalFixedChars"] + sum(g["digestMaxChars"] for g in b["groups"]) == 12000
    validated = [validate_group_lineage(candidate(b, g), g, b) for g in b["groups"]]
    digests = [group_digest(v, g, b) for v, g in zip(validated, b["groups"], strict=True)]
    assert len(final_source(b, digests)) <= 12000
    enlarged = copy.deepcopy(validated[0])
    enlarged["hints"][0]["description"] = "密" * b["groups"][0]["digestMaxChars"]
    with pytest.raises(AuditAiError) as error:
        group_digest(enlarged, b["groups"][0], b)
    assert error.value.code == "SEMANTIC_HIERARCHY_DIGEST_BOUND"
    assert enlarged["hints"][0]["description"].startswith("密")


@pytest.mark.parametrize("tamper", ["missing", "duplicate", "foreign", "bool_target", "forged_ref", "invented_hint", "global"])
def test_lineage_rejects_lost_forged_duplicate_or_global_observations(tamper):
    b = bundle(1, hints_per_analysis=2)
    g = b["groups"][0]
    p = candidate(b, g)
    if tamper == "missing": p["lineage"].pop()
    if tamper == "duplicate": p["lineage"][1][0] = p["lineage"][0][0]
    if tamper == "foreign": p["lineage"][0][0] = "A99:H0"
    if tamper == "bool_target": p["lineage"][0][2] = True
    if tamper == "forged_ref": p["lineage"][0][3] = ["outside-id"]
    if tamper == "invented_hint": p["hints"].append(copy.deepcopy(p["hints"][0]))
    if tamper == "global": p["hints"][0]["claimKind"] = "GLOBAL_CLAUSE_NOT_FOUND"
    with pytest.raises(AuditAiError): validate_group_lineage(p, g, b)


@pytest.mark.parametrize("party,trigger", [("UNSPECIFIED", "验收"), ("PARTY_B", None), ("PARTY_A", "另一事件")])
def test_merge_requires_known_exact_party_trigger_and_file(party, trigger):
    b = bundle(1, hints_per_analysis=2)
    g = b["groups"][0]
    p = candidate(b, g)
    p["hints"] = p["hints"][:1]
    p["hints"][0]["obligationScope"].update(responsibleParty=party, trigger=trigger)
    for row in p["lineage"]: row[1:3] = ["MERGE", 0]
    with pytest.raises(AuditAiError): validate_group_lineage(p, g, b)


def test_final_composition_covers_original_coordinates_and_retains_full_chain():
    b = bundle(7, hints_per_analysis=1)
    gs = [validate_group_lineage(candidate(b, g), g, b) for g in b["groups"]]
    hints, rows = [], []
    for gi, group in enumerate(gs):
        for hi, h in enumerate(group["hints"]):
            hints.append(h)
            rows.append({"observation": [gi, hi], "disposition": "RETAINED", "finalHintIndexes": [len(hints) - 1],
                         "basisEvidenceIds": h["problemEvidenceIds"], "reason": "保留完整原观察"})
    final = {"hints": hints, "resolutions": rows}
    composed, chain = compose_final_resolutions(b, gs, final)
    assert {tuple(row["observation"]) for row in composed} == {(i, 0) for i in range(7)}
    assert len(chain) == 7
    assert all(row["leafId"] in b["leaves"] for row in chain)
    assert all(row["groupAction"] == "KEEP" for row in chain)
    final["resolutions"].pop()
    with pytest.raises(AuditAiError): compose_final_resolutions(b, gs, final)


def test_grouping_binds_order_checkpoint_and_evidence_identity():
    analyses, catalog = fixture(7)
    a = build_hierarchy_inputs(analyses, catalog, material_states=[])
    analyses[6]["checkpointHash"] = "f" * 64
    b = build_hierarchy_inputs(analyses, catalog, material_states=[])
    assert a["groupingFingerprint"] != b["groupingFingerprint"]
    catalog[0]["sourceEnd"] += 1
    c = build_hierarchy_inputs(analyses, catalog, material_states=[])
    assert b["groupingFingerprint"] != c["groupingFingerprint"]
    assert json.loads(c["groups"][0]["source"])["hierarchyProtocol"] == PROTOCOL


def test_group_does_not_repeat_other_groups_evidence_rows_or_machine_proof():
    b = bundle(7, hints_per_analysis=1)
    for g in b["groups"]:
        wire = json.loads(g["source"])
        expected = {eid for ai in g["members"] for h in b["analyses"][ai]["hints"]
                    for eid in h["problemEvidenceIds"] + h["comparisonEvidenceIds"]}
        assert wire["evidenceColumns"][0] == "evidenceIndex"
        assert {row[1] for row in wire["evidence"]} == expected
        assert {row[0] for row in wire["evidence"]} == {b["evidenceIds"].index(eid) for eid in expected}


def test_merge_cannot_add_foreign_file_as_same_obligation():
    b = bundle(2, hints_per_analysis=2)
    g = b["groups"][0]
    p = candidate(b, g)
    p["hints"] = [p["hints"][0], *p["hints"][2:]]
    p["hints"][0]["comparisonEvidenceIds"] = ["evidence-original-1"]
    for i, row in enumerate(p["lineage"]):
        row[1:3] = ["MERGE", 0] if i < 2 else ["KEEP", i - 1]
    with pytest.raises(AuditAiError): validate_group_lineage(p, g, b)


def test_empty_group_uses_actual_existing_scoped_draft_enum():
    from audit_ai.model.semantic_quality import ScopedDraft
    b = bundle(1, hints_per_analysis=0)
    g = b["groups"][0]
    payload = {"summary": "已检查本组全部范围，无相关观察。", "scopeStatus": "CHECKED_NO_RELEVANT_FINDINGS",
               "hints": [], "lineage": []}
    actual = ScopedDraft.model_validate({k: v for k, v in payload.items() if k != "lineage"})
    assert validate_group_lineage(payload, g, b)["scopeStatus"] == actual.scope_status


@pytest.mark.parametrize("n,leaf_count,reference_count", [(16, 53, 87), (7, 58, 75)])
def test_dense_real_shape_has_feasible_weighted_lineage_allocations_not_equal_empty_slots(n, leaf_count, reference_count):
    analyses, _ = fixture(n, hints_per_analysis=0)
    catalog = [{"evidenceId": f"M{i % 6 + 1}:S{i + 1}", "fileId": f"00000000-0000-0000-0000-{i % 6:012d}",
        "materialCategory": "CONTRACT_BODY", "sourceStart": i * 240, "sourceEnd": i * 240 + 240, "citable": True}
        for i in range(reference_count)]
    for i in range(leaf_count):
        eid = catalog[i]["evidenceId"]
        h = hint(eid, party="PARTY_B", trigger=f"明确触发条件{i % 16}")
        h["description"] = "保留本事项具体责任、条件与原始来源，须核对约定边界。" * 2
        if i + leaf_count < reference_count:
            h["comparisonEvidenceIds"] = [catalog[i + leaf_count]["evidenceId"]]
        analyses[i % n]["hints"].append(h)
    b = build_hierarchy_inputs(analyses, catalog, material_states=[])
    assert b["finalShell"]["leafRegistry"] == list(b["leaves"])
    assert b["finalShell"]["scopeColumns"] == ["topicIndex", "responsiblePartyIndex", "trigger"]
    assert all(type(row) is list and len(row) == 3 for row in b["finalShell"]["scopes"])
    assert max(len(g["leafIds"]) for g in b["groups"]) - min(len(g["leafIds"]) for g in b["groups"]) <= (leaf_count + n - 1) // n
    assert all(g["digestMaxChars"] >= g["minimumStructuralDigestChars"] for g in b["groups"])
    assert b["finalFixedChars"] + sum(g["digestMaxChars"] for g in b["groups"]) == 12000
    assert all(len(g["source"]) <= 12000 for g in b["groups"])
    # All full original metadata remains server-side, not erased or represented
    # as a self-contained/lossless final model value.
    assert all(b["leaves"][lid]["hint"]["obligationScope"] for lid in b["leaves"])


def test_digest_enum_indexes_have_complete_explicit_original_tables_and_independent_decode():
    b = bundle(7, hints_per_analysis=1)
    for g in b["groups"]:
        original = candidate(b, g)
        digest = group_digest(original, g, b)
        pools = b["finalShell"]["enums"]
        for row, hint in zip(digest["claims"], original["hints"], strict=True):
            assert pools["categories"][row[0]] == hint["category"]
            assert pools["attentions"][row[1]] == hint["attention"]
            assert pools["claimKinds"][row[4]] == hint["claimKind"]
            assert row[2:4] == [hint["title"], hint["description"]]
        for row, lineage in zip(digest["lineage"], original["lineage"], strict=True):
            assert pools["actions"][row[1]] == lineage[1]
            assert row[0] == lineage[0] and row[4] == lineage[4]
        assert b["finalShell"]["claimColumns"][:2] == ["categoryIndex", "attentionIndex"]
        assert b["finalShell"]["lineageColumns"][1] == "actionIndex"


def test_allocation_failure_contains_only_exact_safe_capacity_counts():
    analyses, catalog = fixture(16, hints_per_analysis=10)
    for a in analyses:
        for h in a["hints"]: h["obligationScope"]["trigger"] = None
    # Long literal identities stay complete, and count against the actual shell.
    for a, e in zip(analyses, catalog, strict=True):
        eid = e["evidenceId"] + "x" * 150
        e["evidenceId"] = eid
        for h in a["hints"]: h["problemEvidenceIds"] = [eid]
    with pytest.raises(AuditAiError) as failure:
        build_hierarchy_inputs(analyses, catalog, material_states=[])
    assert failure.value.code == "SEMANTIC_HIERARCHY_DIGEST_ALLOCATION_BOUND"
    data = failure.value.data
    assert set(data) == {"finalFixedChars", "minimumSum", "availableChars", "groupLeafCounts", "minimums"}
    assert data["minimumSum"] == sum(data["minimums"])
    assert data["availableChars"] == 12000 - data["finalFixedChars"]
    assert sum(data["groupLeafCounts"]) == 160
    assert all(type(v) is int for key in ("groupLeafCounts", "minimums") for v in data[key])


def test_final_does_not_repeat_machine_spans_but_group_and_private_catalog_keep_exact_values():
    b = bundle(7, hints_per_analysis=2)
    final = b["finalShell"]
    assert final["evidenceColumns"] == ["evidenceId", "fileIndex"]
    assert all(len(row) == 2 for row in final["evidence"])
    full = {e["evidenceId"]: e for e in b["evidenceCatalog"]}
    for g in b["groups"]:
        source = json.loads(g["source"])
        assert source["evidenceColumns"] == ["evidenceIndex", "evidenceId", "fileIndex", "sourceStart", "sourceEnd", "citable"]
        for row in source["evidence"]:
            assert row[3:] == [full[row[1]]["sourceStart"], full[row[1]]["sourceEnd"], full[row[1]]["citable"]]
            assert final["evidence"][row[0]] == [row[1], row[2]]
    restored = [{"topic": final["enums"]["scopeTopics"][row[0]],
                 "responsibleParty": final["enums"]["scopeParties"][row[1]], "trigger": row[2]} for row in final["scopes"]]
    assert restored == b["scopes"]


@pytest.mark.parametrize("final_attention", ["REVIEW", "INFO"])
def test_explained_local_leaf_can_end_as_real_noninfo_concern_after_final_review(final_attention):
    from audit_ai.model.semantic_quality import reconcile_observations
    from audit_ai.model.evidence import EvidenceCatalog, EvidenceSegment
    scope = {"topic": "DELIVERY", "responsibleParty": "PARTY_B", "trigger": "交付"}
    local = {"category": "DELIVERY_TERMS", "attention": "INSUFFICIENT", "title": "本段未见交付期限",
        "description": "仅当前范围未显示期限，需核对后续约定。", "claimKind": "LOCAL_NOT_SEEN",
        "obligationScope": scope, "problemEvidenceIds": [], "comparisonEvidenceIds": ["e0"],
        "unavailableMaterialCategory": None, "workScopeWitness": {"protocol": "contract-work-scope-witness-v1",
            "type": "SCOPE_WITNESS_NOT_FINAL_EVIDENCE", "hintIndex": 0, "scopeHash": "a" * 64, "witnessEvidenceIds": ["e0"]}}
    info = {**local, "attention": "INFO", "claimKind": "CLAUSE_PRESENT", "title": "后段可见交付约定",
            "description": "按约定日期交付。", "comparisonEvidenceIds": ["e1"]}
    info.pop("workScopeWitness")
    analyses = [{"logicalKey": "a1", "checkpointHash": "a" * 64, "summary": "前段检查。", "hints": [local]},
                {"logicalKey": "a2", "checkpointHash": "b" * 64, "summary": "后段检查。", "hints": [info]}]
    catalog_rows = [{"evidenceId": eid, "fileId": "body", "materialCategory": "CONTRACT_BODY",
        "sourceStart": i * 20, "sourceEnd": i * 20 + 20, "citable": True} for i, eid in enumerate(("e0", "e1"))]
    b = build_hierarchy_inputs(analyses, catalog_rows, material_states=[])
    group = {"summary": "本组范围已核对。", "scopeStatus": "CHECKED_WITH_FINDINGS", "hints": [info, info],
        "lineage": [["A1:H1", "EXPLAIN", 0, ["e1"], "同一义务后段有具体约定"],
                    ["A2:H1", "KEEP", 1, ["e1"], "保留原约定观察"]]}
    validate_group_lineage(group, b["groups"][0], b)
    final = {**info, "attention": "REVIEW", "claimKind": "AMBIGUOUS_CLAUSE", "title": "交付条件仍需核对",
             "description": "需结合两处条款复核交付条件边界。", "problemEvidenceIds": ["e0"]}
    if final_attention == "INFO": final.update(attention="INFO", claimKind="CLAUSE_PRESENT", problemEvidenceIds=[])
    payload = {"hints": [final], "resolutions": [{"observation": [0, i], "disposition": "RETAINED",
        "finalHintIndexes": [0], "basisEvidenceIds": ["e1"], "reason": "保留同一义务关注"} for i in range(2)]}
    catalog = EvidenceCatalog([EvidenceSegment(r["evidenceId"], "body", "CONTRACT_BODY", r["sourceStart"], r["sourceEnd"],
        "乙方按约定交付材料，交付条件需结合条款核对。", True) for r in catalog_rows])
    reconcile_observations([{"hints": group["hints"]}], payload, allowed_evidence_ids={"e0", "e1"}, catalog=catalog)
    composed, chain = compose_final_resolutions(b, [group], payload)
    actual = reconcile_observations(analyses, {**payload, "resolutions": composed},
        allowed_evidence_ids={"e0", "e1"}, catalog=catalog)
    assert actual[0]["disposition"] == ("RETAINED" if final_attention == "REVIEW" else "COUNTEREVIDENCE")
    assert chain[0]["groupAction"] == "EXPLAIN"


@pytest.mark.parametrize("same_file", [False, True])
def test_final_counterevidence_requires_exact_original_file_not_only_known_scope(same_file):
    from audit_ai.model.semantic_quality import reconcile_observations
    analyses, rows = fixture(2, hints_per_analysis=1)
    if same_file: rows[1]["fileId"] = rows[0]["fileId"]
    local, positive = analyses[0]["hints"][0], analyses[1]["hints"][0]
    local.update(attention="INSUFFICIENT", claimKind="LOCAL_NOT_SEEN", title="本段未见期限",
        description="仅当前范围未显示期限，需核对后续约定。", problemEvidenceIds=[], comparisonEvidenceIds=[rows[0]["evidenceId"]])
    local["workScopeWitness"] = {"protocol": "contract-work-scope-witness-v1", "type": "SCOPE_WITNESS_NOT_FINAL_EVIDENCE",
        "hintIndex": 0, "scopeHash": "a" * 64, "witnessEvidenceIds": local["comparisonEvidenceIds"]}
    positive.update(attention="INFO", claimKind="CLAUSE_PRESENT", problemEvidenceIds=[], comparisonEvidenceIds=[rows[1]["evidenceId"]])
    b = build_hierarchy_inputs(analyses, rows, material_states=[])
    g = candidate(b, b["groups"][0])
    validate_group_lineage(g, b["groups"][0], b)
    final = {**positive, "comparisonEvidenceIds": [r["evidenceId"] for r in rows]}
    payload = {"hints": [final], "resolutions": [{"observation": [0, i],
        "disposition": "COUNTEREVIDENCE" if i == 0 else "RETAINED", "finalHintIndexes": [0],
        "basisEvidenceIds": positive["comparisonEvidenceIds"], "reason": "核对同一义务后段约定"} for i in range(2)]}
    allowed = {r["evidenceId"] for r in rows}
    # The old base checker deliberately remains unchanged. The new hierarchy
    # adds its exact-file contract when composing original observations.
    reconcile_observations([{"hints": g["hints"]}], payload, allowed_evidence_ids=allowed)
    if same_file:
        composed, _ = compose_final_resolutions(b, [g], payload)
        reconcile_observations(analyses, {**payload, "resolutions": composed}, allowed_evidence_ids=allowed)
    else:
        with pytest.raises(AuditAiError): compose_final_resolutions(b, [g], payload)
