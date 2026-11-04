"""Public synthetic regressions, never V2 truth or private material fixtures."""
import copy
import hashlib
import json

import pytest

from audit_ai.errors import AuditAiError
from audit_ai.model.semantic_hierarchy import build_hierarchy_inputs, canonical, final_source
from audit_ai.model.semantic_hierarchy_provider import decode_provider
from audit_ai.model.semantic_quality import QualityFailure, parse_scoped_draft, reconcile_observations

V2 = "contract-bounded-hierarchy-v2"
P2 = "contract-hierarchy-provider-references-v2"


def fixture():
    from audit_ai.model.evidence import EvidenceCatalog, EvidenceSegment
    from audit_ai.model.semantic_work import _work_scope
    texts = ["乙方的当前交付说明。", "尾部条款：乙方交付延期应承担违约责任。"]
    segments, cursor = [], 0
    for index, text in enumerate(texts):
        segments.append(EvidenceSegment(f"C:S{index + 1}", "frozen-contract", "CONTRACT_BODY",
                                        cursor, cursor + len(text), text, True))
        cursor += len(text)
    catalog = EvidenceCatalog(segments, source_lengths={"frozen-contract": cursor})
    work = _work_scope(catalog, {"C:S1"}, "CHECKED_WITH_FINDINGS")
    hint = {"category": "BREACH_LIABILITY", "attention": "INSUFFICIENT", "title": "当前片段未见延期责任",
        "description": "当前片段未见乙方交付延期责任，须结合其他条款核对。",
        "problemEvidenceIds": [], "comparisonEvidenceIds": ["C:S1"], "unavailableMaterialCategory": None,
        "claimKind": "LOCAL_NOT_SEEN", "obligationScope": {"topic": "DELAY_LIABILITY",
            "responsibleParty": "PARTY_B", "trigger": "交付延期"},
        "workScopeWitness": {"protocol": "contract-work-scope-witness-v1", "hintIndex": 0,
            "type": "SCOPE_WITNESS_NOT_FINAL_EVIDENCE", "scopeHash": work["scopeHash"], "witnessEvidenceIds": ["C:S1"]}}
    analyses = [{"logicalKey": "original-analysis", "checkpointHash": "a" * 64, "summary": "当前片段核对。",
                 "hints": [hint], "workScope": work}]
    rows = [{"evidenceId": s.evidence_id, "fileId": s.file_id, "materialCategory": s.material_category,
        "sourceStart": s.source_start, "sourceEnd": s.source_end, "citable": s.citable, "exactText": s.exact_text}
        for s in segments]
    return analyses, rows, catalog


def build():
    a, rows, catalog = fixture()
    return build_hierarchy_inputs(a, rows, material_states=[{"category": "CONTRACT_BODY", "present": True}],
                                 protocol=V2, frozen_catalog=catalog)


def test_complete_frozen_view_includes_tail_not_referenced_by_any_hint():
    b = build()
    assert b["evidenceIds"] == ["C:S1", "C:S2"]
    assert b["finalShell"]["evidenceColumns"][-1] == "exactText"
    assert b["finalShell"]["evidence"][1][-1] == fixture()[1][1]["exactText"]
    assert b["finalEvidenceProof"]["complete"] is True
    assert b["finalEvidenceProof"]["segmentCount"] == 2
    assert b["finalFixedChars"] + sum(g["digestMaxChars"] for g in b["groups"]) <= 48000
    assert b["groupingFingerprint"] != build_hierarchy_inputs(*fixture()[:2], material_states=[])["groupingFingerprint"]


@pytest.mark.parametrize("kind", ["tail", "gap", "overlap", "duplicate", "text_size", "unknown_file", "category"])
def test_partial_or_forged_source_coverage_rejected_before_dispatch(kind):
    a, rows, catalog = fixture()
    if kind == "tail": rows.pop()
    if kind == "gap": rows[1]["sourceStart"] += 1
    if kind == "overlap": rows[1]["sourceStart"] -= 1
    if kind == "duplicate": rows.append(copy.deepcopy(rows[-1]))
    if kind == "text_size": rows[1]["exactText"] += "伪造尾部"
    if kind == "unknown_file": rows[1]["fileId"] = "another-project"
    if kind == "category": rows[1]["materialCategory"] = "BID_RESPONSE"
    with pytest.raises(AuditAiError):
        build_hierarchy_inputs(a, rows, material_states=[], protocol=V2, frozen_catalog=catalog)


def test_dense_evidence_bound_fails_without_cutting_tail():
    a, rows, catalog = fixture()
    rows[-1]["exactText"] = "乙方交付延期：" + "真实完整尾部" * 10000
    rows[-1]["sourceEnd"] = rows[-1]["sourceStart"] + len(rows[-1]["exactText"])
    from audit_ai.model.evidence import EvidenceCatalog, EvidenceSegment
    dense = EvidenceSegment("C:S2", "frozen-contract", "CONTRACT_BODY", rows[-1]["sourceStart"],
                            rows[-1]["sourceEnd"], rows[-1]["exactText"], True)
    catalog = EvidenceCatalog([catalog.segments[0], dense], source_lengths={"frozen-contract": dense.source_end})
    with pytest.raises(AuditAiError) as err:
        build_hierarchy_inputs(a, rows, material_states=[], protocol=V2,
                              frozen_catalog=catalog)
    assert err.value.code == "SEMANTIC_FINAL_EVIDENCE_BOUND"
    assert rows[-1]["exactText"].endswith("真实完整尾部")


def process_payload():
    h = copy.deepcopy(fixture()[0][0]["hints"][0]); h.pop("workScopeWitness")
    h.update(attention="INFO", claimKind="CLAUSE_PRESENT", title="交付延期责任有约定",
             description="尾部条款明确乙方交付延期责任。", comparisonEvidenceIds=["C:S2"])
    return {"protocol": P2, "summary": "已根据完整冻结条款核对局部观察。", "newHints": [h],
            "resolutions": [[0, 0, "PROCESS_RESOLVED", [], [1], "尾部条款载明乙方交付延期责任，本片段未见已获解释。"]]}


def test_process_resolution_private_not_fake_public_info_and_old_protocol_rejects():
    a, _, catalog = fixture()
    b = build()
    value = decode_provider(process_payload(), role="FINAL", source_hints={"G1:H1": a[0]["hints"][0]},
                            evidence_ids=b["evidenceIds"], protocol=P2)
    base, quality = parse_scoped_draft(value, synthesis=True, allow_process_resolution=True)
    assert len(base["hints"]) == 1 and base["hints"][0]["title"] == "交付延期责任有约定"
    rows = reconcile_observations(a, {**value}, allowed_evidence_ids=set(b["evidenceIds"]), catalog=catalog,
        allow_process_resolution=True, process_scope_hashes={a[0]["workScope"]["scopeHash"]},
        final_evidence_proof=b["finalEvidenceProof"])
    assert rows[0]["disposition"] == "PROCESS_RESOLVED" and rows[0]["finalHintIndexes"] == []
    with pytest.raises(AuditAiError):
        decode_provider(process_payload(), role="FINAL", source_hints={"G1:H1": a[0]["hints"][0]},
                        evidence_ids=b["evidenceIds"])
    with pytest.raises(QualityFailure): parse_scoped_draft(value, synthesis=True)


@pytest.mark.parametrize("kind", ["risk", "missing_attachment", "unknown_party", "unknown_trigger", "fake_witness", "same_witness", "wrong_file", "no_coverage", "unrelated", "different_party", "no_supported_claim"])
def test_process_resolution_never_sweeps_real_risk_or_unproven_scope(kind):
    a, _, catalog = fixture()
    b = build(); original = a[0]["hints"][0]
    hashes = {a[0]["workScope"]["scopeHash"]}; proof = b["finalEvidenceProof"]
    row = {"observation": [0, 0], "disposition": "PROCESS_RESOLVED", "finalHintIndexes": [],
           "basisEvidenceIds": ["C:S2"], "reason": "同一延期责任条款解释局部观察。"}
    if kind == "risk": original["problemEvidenceIds"] = ["C:S1"]
    if kind == "missing_attachment": original["claimKind"] = "AMBIGUOUS_CLAUSE"
    if kind == "unknown_party": original["obligationScope"]["responsibleParty"] = "UNSPECIFIED"
    if kind == "unknown_trigger": original["obligationScope"]["trigger"] = None
    if kind == "fake_witness": original["workScopeWitness"]["scopeHash"] = "b" * 64
    if kind == "same_witness": row["basisEvidenceIds"] = ["C:S1"]
    if kind == "wrong_file":
        from audit_ai.model.evidence import EvidenceCatalog, EvidenceSegment
        other = EvidenceSegment("C:S2", "foreign", "CONTRACT_BODY", 0, 1, "异", True)
        catalog = EvidenceCatalog([catalog.segments[0], other],
                                  source_lengths={"frozen-contract": catalog.segments[0].source_end, "foreign": 1})
    if kind == "no_coverage": proof = None
    hints = process_payload()["newHints"]
    if kind in {"unrelated", "different_party"}:
        from audit_ai.model.evidence import EvidenceCatalog, EvidenceSegment
        text = "乙方付款期限另行约定。" if kind == "unrelated" else "甲方交付延期承担责任。"
        old = catalog.segments[1]
        catalog = EvidenceCatalog([catalog.segments[0], EvidenceSegment(old.evidence_id, old.file_id,
            old.material_category, old.source_start, old.source_start + len(text), text, True)],
            source_lengths={"frozen-contract": old.source_start + len(text)})
    if kind == "no_supported_claim": hints = []
    if kind in {"unrelated", "different_party", "wrong_file"}:
        from audit_ai.model.semantic_final_evidence import catalog_rows, verify_proof
        proof = build_hierarchy_inputs(a, catalog_rows(catalog), material_states=[], protocol=V2,
                                      frozen_catalog=catalog)["finalEvidenceProof"]
        assert verify_proof(proof, catalog), "targeted scope gate must not be masked by invalid coverage"
    with pytest.raises(QualityFailure):
        reconcile_observations(a, {"hints": hints, "resolutions": [row]}, allowed_evidence_ids={"C:S1", "C:S2"},
            catalog=catalog, allow_process_resolution=True, process_scope_hashes=hashes, final_evidence_proof=proof)


@pytest.mark.parametrize("disposition", ["RETAINED", "SCOPED_REPLACEMENT", "COUNTEREVIDENCE", "NEEDS_VERIFICATION"])
def test_all_non_process_dispositions_still_require_nonempty_targets(disposition):
    a, _, _ = fixture(); payload = process_payload(); payload["resolutions"][0][2] = disposition
    with pytest.raises(AuditAiError):
        decode_provider(payload, role="FINAL", source_hints={"G1:H1": a[0]["hints"][0]},
                        evidence_ids=["C:S1", "C:S2"], protocol=P2)


@pytest.mark.parametrize("field,value", [("exactText", "乙方的伪造交付说明。"), ("citable", False),
                                         ("materialCategory", "SUPPLEMENT")])
def test_self_consistent_rehashed_view_is_not_authority(field, value):
    a, rows, catalog = fixture(); rows[0][field] = value
    with pytest.raises(AuditAiError):
        build_hierarchy_inputs(a, rows, material_states=[], protocol=V2, frozen_catalog=catalog)


def test_utf8_capacity_accounts_for_four_byte_code_points_and_exact_edge():
    from audit_ai.model.evidence import EvidenceCatalog, EvidenceSegment
    def attempt(count):
        a, rows, catalog = fixture(); text = "乙方交付延期：" + "𠀀" * count
        rows[-1].update(exactText=text, sourceEnd=rows[-1]["sourceStart"] + len(text))
        tail = EvidenceSegment("C:S2", "frozen-contract", "CONTRACT_BODY", rows[-1]["sourceStart"],
                               rows[-1]["sourceEnd"], text, True)
        trusted = EvidenceCatalog([catalog.segments[0], tail], source_lengths={"frozen-contract": tail.source_end})
        return build_hierarchy_inputs(a, rows, material_states=[], protocol=V2, frozen_catalog=trusted)
    lower, upper = 1, 48000
    while lower + 1 < upper:
        mid = (lower + upper) // 2
        try: attempt(mid); lower = mid
        except AuditAiError: upper = mid
    b = attempt(lower)
    assert b["finalFixedBytes"] + 4 * sum(g["digestMaxChars"] for g in b["groups"]) <= 144000
    assert b["finalFixedChars"] + sum(g["digestMaxChars"] for g in b["groups"]) < 48000
    with pytest.raises(AuditAiError): attempt(upper)


@pytest.mark.parametrize("trigger", ["   ", "\u3000\t\n", "\u00a0"])
def test_normalized_empty_trigger_is_unknown_not_a_source_wildcard(trigger):
    a, _, catalog = fixture(); b = build()
    a[0]["hints"][0]["obligationScope"]["trigger"] = trigger
    positive = process_payload()["newHints"][0]; positive["obligationScope"]["trigger"] = trigger
    row = {"observation": [0, 0], "disposition": "PROCESS_RESOLVED", "finalHintIndexes": [],
           "basisEvidenceIds": ["C:S2"], "reason": "原文对应的过程观察已核对。"}
    with pytest.raises(QualityFailure):
        reconcile_observations(a, {"hints": [positive], "resolutions": [row]}, catalog=catalog,
            allowed_evidence_ids={"C:S1", "C:S2"}, allow_process_resolution=True,
            process_scope_hashes={a[0]["workScope"]["scopeHash"]}, final_evidence_proof=b["finalEvidenceProof"])
