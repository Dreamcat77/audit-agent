"""Contract metadata only; the independent inverse never calls production decoding."""
import base64
import copy
from dataclasses import replace
import hashlib
import importlib
import json

import pytest

from audit_ai.config import Settings
from audit_ai.errors import AuditAiError
from audit_ai.model.evidence import EvidenceCatalog, EvidenceSegment
from audit_ai.model.router import ContractChunkResult, ContractHint
from audit_ai.model.semantic_work import _JournaledApplication, _work_scope, checkpoint_hash
from test_semantic_regeneration_mode import _gateway, _operation, _draft, _hint


PROTOCOL = "contract-synthesis-metadata-packing-v1"


def compact(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False)


def pack(value):
    try:
        module = importlib.import_module("audit_ai.model.semantic_synthesis_packing")
    except ModuleNotFoundError:
        pytest.fail("complete contract metadata fallback is missing")
    return module.pack_synthesis_metadata(value)


def inverse(encoded, keep=0):
    """Independent statement of the versioned wire, not a production helper."""
    value = copy.deepcopy(encoded)
    marker = value.pop("metadataPacking", None)
    if marker is None:
        return value
    assert marker["protocol"] == PROTOCOL
    stages, pools = marker["stages"], marker["pools"]
    assert stages == ["enums", "scopes", "files", "hashes", "ranges"][:len(stages)]
    if keep == len(stages):
        return copy.deepcopy(encoded)
    quality = value["qualityRegistry"]
    if "ranges" in stages and keep < 5:
        assert marker["rangeEncoding"] == "per-unit-local-file-end-delta-v1"
        assert value["workScopeRegistry"]["rangeColumns"] == ["fileIndex", "startDelta", "length", "citableBit", "segmentCount"]
        for unit in value["workScopeRegistry"]["units"]:
            ends = {}
            for row in unit[2]:
                file, delta, length, bit, count = row
                assert type(bit) is int and bit in (0, 1)
                start = ends.get(file, 0) + delta
                ends[file] = start + length
                row[:] = [file, start, start + length, bool(bit), count]
        value["workScopeRegistry"]["rangeColumns"] = ["fileIndex", "sourceStart", "sourceEnd", "citable", "segmentCount"]
    if "hashes" in stages and keep < 4:
        assert marker["hashEncoding"] == "sha256-base64url-no-pad"
        def hex_digest(text):
            assert len(text) == 43 and "=" not in text
            raw = base64.b64decode(text + "=", altchars=b"-_", validate=True)
            assert len(raw) == 32 and base64.urlsafe_b64encode(raw).decode().rstrip("=") == text
            return raw.hex()
        columns = value["evidenceCatalog"]["columns"]
        at = columns.index("exactTextSha256B64")
        columns[at] = "exactTextSha256"
        for row in value["evidenceCatalog"]["rows"]:
            row[at] = hex_digest(row[at])
        units = value["workScopeRegistry"]
        at = units["unitColumns"].index("scopeHashB64")
        units["unitColumns"][at] = "scopeHash"
        for row in units["units"]:
            row[at] = hex_digest(row[at])
    if "files" in stages and keep < 3:
        table = value.pop("sharedFileRegistry")
        assert table["columns"] == ["fileId", "materialCategory"]
        shared = [dict(zip(table["columns"], row, strict=True)) for row in table["rows"]]
        for registry in (value, value["workScopeRegistry"]):
            registry["fileRegistry"] = [copy.deepcopy(shared[index]) for index in registry["fileRegistry"]]
    if "scopes" in stages and keep < 2:
        table = quality["obligationScopes"]
        quality["obligationScopes"] = [dict(zip(table["columns"], row, strict=True)) for row in table["rows"]]
    if keep:
        marker["stages"] = stages[:keep]
        if keep < 4: marker.pop("hashEncoding", None)
        if keep < 5: marker.pop("rangeEncoding", None)
        value["metadataPacking"] = marker
        return value
    for partial in value["partialResults"]:
        for row in partial["hints"]:
            layout = value["hintLayouts"][row[0]]
            for field in ("category", "attention"):
                at = layout.index(field) + 1
                row[at] = pools[field][row[at]]
    for scope in quality["obligationScopes"]:
        for field in ("topic", "responsibleParty"):
            scope[field] = pools[field][scope[field]]
    at = value["workScopeRegistry"]["unitColumns"].index("scopeStatus")
    for row in value["workScopeRegistry"]["units"]:
        row[at] = pools["scopeStatus"][row[at]]
    return value


def dense_fixture():
    segments = []
    for i in range(125):
        text = f"合成来源第{i:03d}段完整文本与否定尾部😀"
        segments.append(EvidenceSegment(f"C:S{i+1}", f"00000000-0000-4000-8000-{i % 8:012d}",
            "CONTRACT_BODY" if i % 2 == 0 else "SUPPLEMENT", i * 100, i * 100 + len(text), text, True))
    catalog = EvidenceCatalog(segments)
    results, metadata, cursor = [], [], 0
    scopes = [{"topic": "DELIVERY" if i % 2 == 0 else "ACCEPTANCE",
               "responsibleParty": "PARTY_B" if i % 3 else "BOTH",
               "trigger": None if i == 0 else f"合成第{i:02d}项交付材料核对条件，不代表实际履约事实"} for i in range(27)]
    for unit, count in enumerate([5, 4, 9, 5, 3, 1, 3, 11]):
        hints, typed = [], []
        for _ in range(count):
            hints.append(ContractHint(category="SCOPE_AND_SUBCONTRACTING" if cursor % 2 else "TERM_TERMINATION",
                attention="REVIEW", title=f"合成观察{cursor}保留", description=f"逐项核对交付责任及验收边界，当前结论不得替代正式履约确认。完整否定尾部{cursor}😀。",
                evidence_refs=[], problem_evidence_ids=(f"C:S{cursor % 27 + 1}",), comparison_evidence_ids=()))
            typed.append({"claimKind": "CLAUSE_PRESENT", "obligationScope": scopes[cursor % 27]})
            cursor += 1
        results.append(ContractChunkResult(summary=f"第{unit+1}块合成检查摘要，不删任何关注或尾部😀。", hints=hints))
        metadata.append(typed)
    app = _JournaledApplication(Settings(model_name="synthetic-model"), object(), "contract-semantic-quality-v1")
    app.quality_results = metadata
    app.work_scopes = [_work_scope(catalog, {s.evidence_id for s in segments[u*16:(u+1)*16]}, "CHECKED_WITH_FINDINGS") for u in range(8)]
    app.work_observations = [[] for _ in range(8)]
    return app, results, catalog


def input_value():
    app, results, catalog = dense_fixture()
    # Independent pre-packing value including the already existing table layers.
    files, rows, scopes, partials = [], [], [], []
    references = list(dict.fromkeys(eid for r in results for h in r.hints for eid in h.problem_evidence_ids))
    for eid in references:
        s = catalog.by_id[eid]; identity = {"fileId": s.file_id, "materialCategory": s.material_category}
        if identity not in files:
            files.append(identity)
        rows.append([eid, files.index(identity), True, s.source_start, s.source_end, hashlib.sha256(s.exact_text.encode()).hexdigest()])
    kinds = ["CLAUSE_PRESENT", "LOCAL_NOT_SEEN", "GLOBAL_CLAUSE_NOT_FOUND", "MATERIAL_MISSING", "AMBIGUOUS_CLAUSE", "CROSS_MATERIAL_CONFLICT"]
    for result, typed in zip(results, app.quality_results, strict=True):
        hints = []
        for hint, quality in zip(result.hints, typed, strict=True):
            scope = quality["obligationScope"]
            if scope not in scopes:
                scopes.append(copy.deepcopy(scope))
            hints.append([0, hint.category, hint.attention, hint.title, hint.description, list(hint.problem_evidence_ids), [], [0, scopes.index(scope)]])
        partials.append({"summary": result.summary, "hints": hints})
    units = [[s["scopeHash"], s["scopeStatus"], [[files.index({"fileId": e["fileId"], "materialCategory": e["category"]}), e["start"], e["end"], e["citable"], 1] for e in s["evidence"]]] for s in app.work_scopes]
    return {"inputFormat": "contract-synthesis-evidence-table-v1", "partialResults": partials,
        "fileRegistry": files, "evidenceCatalog": {"columns": ["evidenceId", "fileIndex", "citable", "sourceStart", "sourceEnd", "exactTextSha256"], "rows": rows},
        "omittedHintCount": 0, "qualityRegistry": {"claimKinds": kinds, "obligationScopes": scopes},
        "workScopeRegistry": {"protocol": "contract-work-scope-witness-v1", "fileRegistry": copy.deepcopy(files),
            "unitColumns": ["scopeHash", "scopeStatus", "ranges"], "rangeColumns": ["fileIndex", "sourceStart", "sourceEnd", "citable", "segmentCount"], "units": units},
        "qualityHintEncoding": "contract-quality-hint-table-v1", "hintLayouts": [["category", "attention", "title", "description", "problemEvidenceIds", "comparisonEvidenceIds", "q"]]}


def test_dense_complete_input_is_smaller_and_independently_reversible_without_mutation():
    value = input_value(); before = copy.deepcopy(value)
    assert sum(len(p["hints"]) for p in value["partialResults"]) == 41
    assert len(value["evidenceCatalog"]["rows"]) == 27
    assert sum(len(u[2]) for u in value["workScopeRegistry"]["units"]) == 125
    assert len(compact(value)) > 12000
    encoded = pack(value)
    assert len(compact(encoded)) <= 12000
    assert inverse(encoded) == before and value == before
    assert pack(value) == encoded


def test_first_fitting_stage_counts_its_entire_overhead_and_every_layer_reverses():
    seen = set()
    for short in (1, 10, None):
        for padding in (0, 50, 800, 2000):
            value = input_value()
            if short is not None:
                for partial in value["partialResults"]:
                    for row in partial["hints"]:
                        row[3] = "原"
                        row[4] = row[4][:short]
            value["partialResults"][0]["summary"] += "U" * padding
            encoded = pack(value)
            assert inverse(encoded) == value
            marker = encoded.get("metadataPacking")
            if marker is None:
                assert len(compact(value)) <= 12000
                continue
            count = len(marker["stages"]); seen.add(count)
            prior_sizes = [len(compact(inverse(encoded, keep))) for keep in range(count)]
            if len(compact(encoded)) <= 12000:
                assert all(size > 12000 for size in prior_sizes)
            else:
                assert len(compact(encoded)) == min([len(compact(encoded)), *prior_sizes])
            for keep in range(1, count):
                assert inverse(inverse(encoded, keep)) == value
    assert seen == {1, 2, 3, 4, 5}


def test_small_input_unchanged_and_unknown_nonstructural_fields_are_not_lost():
    value = input_value()
    value["extraPrivateMetadata"] = {"literal": [None, False, "原值😀"]}
    value["qualityRegistry"]["extraPrivateMetadata"] = [None, "不能遗漏"]
    assert inverse(pack(value)) == value
    small = input_value()
    for partial in small["partialResults"]:
        for row in partial["hints"]: row[3] = row[4] = "原值"
    # This separate small fixture has one checked range per unit.
    for scope in small["qualityRegistry"]["obligationScopes"]: scope["trigger"] = None
    for unit in small["workScopeRegistry"]["units"]: unit[2] = unit[2][:1]
    assert len(compact(small)) <= 12000
    assert pack(small) == small and "metadataPacking" not in pack(small)


def test_real_synthesis_constructor_gets_complete_dense_input_through_original_guard():
    app, results, catalog = dense_fixture()
    try:
        source, chars = app._contract_synthesis_source(results, catalog)
    except AuditAiError as failure:
        pytest.fail(f"complete 41/27/125 synthesis still rejected: {failure.code}")
    assert chars == len(source) <= 12000
    assert inverse(json.loads(source)) == input_value()


def test_registry_order_checked_only_file_roles_and_explicit_null_survive():
    value = input_value()
    extra = {"fileId": value["fileRegistry"][0]["fileId"], "materialCategory": "BID_RESPONSE"}
    registry = value["workScopeRegistry"]
    registry["fileRegistry"] = [extra, *reversed(registry["fileRegistry"])]
    for unit in registry["units"]:
        for row in unit[2]: row[0] = 8 - row[0]
    registry["units"][0][2].append([0, 0, 4, False, 1])
    layout = value["hintLayouts"][0]; layout.append("unavailableMaterialCategory")
    for partial in value["partialResults"]:
        for row in partial["hints"]: row.append(None)
    assert inverse(pack(value)) == value


def test_delta_layer_retains_negative_gaps_zero_coordinates_and_uncitable_ranges():
    value = input_value()
    value["partialResults"][0]["summary"] += "U" * 2500
    value["workScopeRegistry"]["units"][0][2] = [[0, 0, 4, False, 2], [0, 9, 11, True, 1], [1, 0, 3, False, 1], [0, 1, 5, False, 1]]
    encoded = pack(value)
    assert "ranges" in encoded["metadataPacking"]["stages"]
    assert inverse(encoded) == value
    assert encoded["workScopeRegistry"]["units"][0][2] == [[0, 0, 4, 0, 2], [0, 5, 2, 1, 1], [1, 0, 3, 0, 1], [0, -10, 4, 0, 1]]


def test_hash_encoding_only_touches_named_metadata_and_restores_all_256_bits():
    value = input_value(); identity = "a" * 64
    value["evidenceCatalog"]["rows"][0][0] = identity
    for partial in value["partialResults"]:
        for row in partial["hints"]:
            row[5] = [identity if eid == "C:S1" else eid for eid in row[5]]
    value["evidenceCatalog"]["rows"][0][5] = "00" * 32
    value["workScopeRegistry"]["units"][0][0] = "ff" * 32
    value["partialResults"][0]["summary"] += "U" * 900
    encoded = pack(value)
    assert "hashes" in encoded["metadataPacking"]["stages"]
    assert encoded["evidenceCatalog"]["rows"][0][0] == identity
    assert encoded["evidenceCatalog"]["rows"][0][5] == "A" * 43
    assert encoded["workScopeRegistry"]["units"][0][0] == "_" * 42 + "8"
    assert inverse(encoded) == value


@pytest.mark.parametrize("defect", ["index", "bool_index", "hash", "scope_hash", "flag", "span", "q", "missing_id", "enum", "surrogate"])
def test_malformed_metadata_is_rejected_not_dropped_or_guessed(defect):
    value = input_value()
    if defect == "index": value["evidenceCatalog"]["rows"][0][1] = 99
    elif defect == "bool_index": value["evidenceCatalog"]["rows"][0][1] = True
    elif defect == "hash": value["evidenceCatalog"]["rows"][0][5] = "X" * 64
    elif defect == "scope_hash": value["workScopeRegistry"]["units"][0][0] = None
    elif defect == "flag": value["workScopeRegistry"]["units"][0][2][0][3] = 1
    elif defect == "span": value["workScopeRegistry"]["units"][0][2][0][2] = -1
    elif defect == "q": value["partialResults"][0]["hints"][0][-1][1] = 99
    elif defect == "missing_id": value["partialResults"][0]["hints"][0][5] = ["FOREIGN"]
    elif defect == "enum": value["partialResults"][0]["hints"][0][2] = "NORMAL"
    else: value["partialResults"][0]["summary"] = "\ud800"
    with pytest.raises(AuditAiError) as rejected:
        pack(value)
    assert rejected.value.code == "SEMANTIC_SYNTHESIS_PACKING_INVALID"


def test_uncompressible_full_text_still_hits_same_guard():
    app, results, catalog = dense_fixture()
    results = [ContractChunkResult(summary="完整不删" * 950, hints=result.hints) for result in results]
    before = copy.deepcopy(results)
    with pytest.raises(AuditAiError) as rejected:
        app._contract_synthesis_source(results, catalog)
    assert rejected.value.code == "SEMANTIC_SYNTHESIS_INPUT_BOUND" and results == before


def test_actual_gateway_only_explains_new_marker_on_durable_synthesis():
    value = pack(input_value()); wires = []
    _gateway(wires, _draft("SYNTHESIZE", _hint())).analyze_contract(_operation("SYNTHESIZE", source=compact(value)))
    assert PROTOCOL in wires[0]["messages"][0]["content"]
    assert "sharedFileRegistry" in wires[0]["messages"][0]["content"]
    assert json.loads(wires[0]["messages"][1]["content"].split("source:\n", 1)[1]) == value
    for operation in (_operation("ANALYZE", source=compact(value)), replace(_operation("SYNTHESIZE", source=compact(value)), generation_policy=None)):
        wires = []; _gateway(wires, _draft(operation.purpose, _hint())).analyze_contract(operation)
        assert PROTOCOL not in wires[0]["messages"][0]["content"]


def test_policy_binds_packing_module_and_old_plans_are_incompatible_without_dispatch():
    from test_semantic_regeneration_context import setup
    service, request, wires, _ = setup()
    plan = service.plan(request)
    assert plan["policy"]["synthesisMetadataPacking"] == PROTOCOL
    module = importlib.import_module("audit_ai.model.semantic_synthesis_packing")
    from pathlib import Path
    assert hashlib.sha256(Path(module.__file__).read_bytes()).hexdigest() in plan["policy"]["sourceCodeHashes"]
    legacy = copy.deepcopy(plan); legacy.pop("next"); legacy.pop("planFingerprint")
    legacy["policy"].pop("synthesisMetadataPacking")
    legacy["policyFingerprint"] = checkpoint_hash(legacy["policy"])
    with pytest.raises(AuditAiError):
        service.advance(request, checkpoint_hash(legacy), [], None, remaining_ms=800000, execute=False)
    assert not wires
