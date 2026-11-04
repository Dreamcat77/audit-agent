"""Contract-only bounded hierarchy; complete private proof stays in checkpoints.

The model projection is deliberately NOT self-contained/lossless. Literal
claims and references are retained on the wire; source hashes/checked ranges
remain in the independently replayed original analysis checkpoints. Nothing
here owns persistence, dispatch, retry, a deadline or a second workflow.
"""
from __future__ import annotations

from copy import deepcopy
import hashlib
import json
import re
from datetime import date
from decimal import Decimal, InvalidOperation
from typing import get_args

from audit_ai.errors import AuditAiError
from audit_ai.model.contract_draft import ContractCategory, Attention
from audit_ai.model.semantic_quality import ClaimKind, ObligationScope

PROTOCOL = "contract-bounded-hierarchy-v1"
MEMBERSHIP_STRATEGY = "scope-file-size-v1"
MAX_SOURCE_CHARS = 12_000
MAX_ANALYSES = 16
MAX_GROUPS = 3
MAX_REPAIRS = 2
CLAIM_COLUMNS = ["categoryIndex", "attentionIndex", "title", "description", "claimKindIndex", "scopeIndex",
                 "problemEvidenceIndexes", "comparisonEvidenceIndexes", "unavailableMaterialCategory"]
LINEAGE_COLUMNS = ["leafId", "actionIndex", "targetHintIndex", "basisEvidenceIndexes", "reason"]
_ENUMS = {"categories": list(get_args(ContractCategory)), "attentions": list(get_args(Attention)),
          "claimKinds": list(get_args(ClaimKind)), "actions": ["KEEP", "MERGE", "EXPLAIN", "UNRESOLVED"],
          "scopeTopics": list(get_args(ObligationScope.model_fields["topic"].annotation)),
          "scopeParties": list(get_args(ObligationScope.model_fields["responsible_party"].annotation))}
_ZERO_HASH = "0" * 64


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def fingerprint(value):
    return hashlib.sha256(canonical(value).encode("utf-8")).hexdigest()


def _fail(code="SEMANTIC_HIERARCHY_LINEAGE_INVALID", status=422, data=None):
    raise AuditAiError(status, code, "bounded contract hierarchy validation failed", data)


def _lineage_identity_issues(payload, group, bundle):
    """Bounded public coordinates only; no source values or identity coercion."""
    issues = []
    for index, row in enumerate(payload["lineage"]):
        if type(row) is not list or len(row) != 5:
            continue
        leaf, action, target, basis, _ = row
        if (type(leaf) is not str or leaf not in group["leafIds"]
                or type(target) is not int or not 0 <= target < len(payload["hints"])):
            continue
        original, result = bundle["leaves"][leaf]["hint"], payload["hints"][target]
        if type(result) is not dict:
            continue
        changed = (result.get("category") != original["category"]
                   or result.get("obligationScope") != original["obligationScope"])
        if changed:
            issues.append({"fieldPath": f"lineage[{index}]", "errorKind":
                "HIERARCHY_MERGE_IDENTITY_MISMATCH" if action == "MERGE" else "HIERARCHY_LEAF_IDENTITY_MISMATCH"})
        if len(issues) == 8:
            break
    return issues


def admit_analysis_count(count):
    if type(count) is not int or not 1 <= count <= MAX_ANALYSES:
        _fail("SEMANTIC_HIERARCHY_ANALYSIS_BOUND", 413)
    groups = (count + 5) // 6
    initial = count + groups + 1
    # Explicit admission, never a clamped budget or omitted repair slot.
    if initial > 20 or initial + MAX_REPAIRS > 22:
        _fail("SEMANTIC_HIERARCHY_ANALYSIS_BOUND", 413)
    return {"analysisCount": count, "groupCount": groups, "initialCalls": initial, "callLimit": initial + MAX_REPAIRS}


def validate_hierarchy_manifest(manifest):
    """Explicit unique-FINAL/last and fixed graph, not a guessed S position."""
    hierarchy = manifest.get("hierarchy")
    if hierarchy is None: return None
    units = manifest.get("units")
    if (type(units) is not list or not units or type(hierarchy) is not dict
            or any(type(u) is not dict for u in units)
            or any(type(hierarchy.get(key)) is not int for key in ("version", "maximumGroups", "groupInputMaxChars", "finalInputMaxChars"))
            or any(type(u.get("ordinal")) is not int for u in units)):
        _fail("SEMANTIC_HIERARCHY_MANIFEST_INVALID", 409)
    analyses = [u for u in units if u.get("role") == "ANALYSIS"]
    groups = [u for u in units if u.get("role") == "GROUP"]
    finals = [u for u in units if u.get("role") == "FINAL"]
    admission = admit_analysis_count(len(analyses))
    keys = [u.get("logicalKey") for u in units]
    expected_hierarchy = {"version": 1, "groupLogicalKeys": [u["logicalKey"] for u in groups],
        "finalLogicalKey": finals[0]["logicalKey"] if len(finals) == 1 else None,
        "membershipStrategy": MEMBERSHIP_STRATEGY, "maximumGroups": 3,
        "leafIdentity": "analysis-logical-key+hint-ordinal", "decisionAfter": "ALL_ANALYSES_VERIFIED",
        "groupInputMaxChars": 12000, "finalInputMaxChars": 12000}
    from audit_ai.model.semantic_final_evidence import HIERARCHY_PROTOCOL, PROTOCOL as view_protocol, MAX_CHARS, MAX_BYTES
    protocol = manifest.get("policy", {}).get("hierarchyProtocol")
    if protocol == HIERARCHY_PROTOCOL:
        expected_hierarchy.update(version=2, finalInputMaxChars=MAX_CHARS, finalInputMaxBytes=MAX_BYTES,
                                  finalEvidenceProtocol=view_protocol)
    if (len(finals) != 1 or units != analyses + groups + finals or len(groups) != admission["groupCount"]
            or len(set(keys)) != len(keys) or any(not re.fullmatch(r"[a-f0-9]{64}", k or "") for k in keys)
            or hierarchy != expected_hierarchy or manifest.get("callLimit") != admission["callLimit"]
            or manifest.get("repairLimit") != 2 or [u.get("ordinal") for u in units] != list(range(1, len(units) + 1))
            or protocol not in {PROTOCOL, HIERARCHY_PROTOCOL}):
        _fail("SEMANTIC_HIERARCHY_MANIFEST_INVALID", 409)
    for i, unit in enumerate(analyses):
        if unit["purpose"] != "ANALYZE" or unit["dependencies"] != ([analyses[i - 1]["logicalKey"]] if i else []):
            _fail("SEMANTIC_HIERARCHY_MANIFEST_INVALID", 409)
    for unit in groups:
        if unit["purpose"] != "SYNTHESIZE" or unit["dependencies"] != [a["logicalKey"] for a in analyses]:
            _fail("SEMANTIC_HIERARCHY_MANIFEST_INVALID", 409)
    if finals[0]["purpose"] != "SYNTHESIZE" or finals[0]["dependencies"] != [u["logicalKey"] for u in analyses + groups]:
        _fail("SEMANTIC_HIERARCHY_MANIFEST_INVALID", 409)
    return finals[0]["logicalKey"]


def _refs(hint):
    return [*hint["problemEvidenceIds"], *hint["comparisonEvidenceIds"]]


def _scope_key(hint):
    return canonical(hint["obligationScope"])


def _known_scope(hint):
    scope = hint["obligationScope"]
    return scope["responsibleParty"] != "UNSPECIFIED" and bool(scope.get("trigger"))


def build_hierarchy_inputs(analyses, evidence_catalog, *, material_states, protocol=PROTOCOL, frozen_catalog=None):
    """Freeze a deterministic all-analysis partition and the dense final bound.

    `analyses` are ordered full validated quality partials plus logicalKey and
    checkpointHash. They are not accepted as execution checkpoints. Actual
    dispatch replay supplies these from the immutable original A packets.
    """
    if protocol != PROTOCOL:
        from audit_ai.model.semantic_final_evidence import HIERARCHY_PROTOCOL, enrich_bundle
        if protocol != HIERARCHY_PROTOCOL: _fail()
        base = build_hierarchy_inputs(analyses, evidence_catalog, material_states=material_states)
        return enrich_bundle(base, evidence_catalog, frozen_catalog)
    admission = admit_analysis_count(len(analyses))
    original = deepcopy(analyses)
    keys = [a.get("logicalKey") for a in original]
    if len(set(keys)) != len(keys) or any(type(k) is not str or not k for k in keys): _fail()
    if any(not re.fullmatch(r"[a-f0-9]{64}", a.get("checkpointHash", "")) for a in original): _fail()
    by_id = {}
    for entry in evidence_catalog:
        eid = entry["evidenceId"]
        if eid in by_id: _fail()
        by_id[eid] = entry
    referenced = {eid for a in original for h in a["hints"] for eid in _refs(h)}
    if not referenced.issubset(by_id) or any(not by_id[eid]["citable"] for eid in referenced): _fail()
    files, evidence_rows, evidence_ids = [], [], []
    for entry in evidence_catalog:
        if entry["evidenceId"] not in referenced: continue
        file = [entry["fileId"], entry["materialCategory"]]
        if file not in files: files.append(file)
        evidence_ids.append(entry["evidenceId"])
        evidence_rows.append([entry["evidenceId"], files.index(file), entry["sourceStart"], entry["sourceEnd"], entry["citable"]])
    indexes = {eid: i for i, eid in enumerate(evidence_ids)}
    scopes, leaves, leaf_rows = [], {}, []
    for ai, analysis in enumerate(original):
        for hi, hint in enumerate(analysis["hints"]):
            scope = hint["obligationScope"]
            if scope not in scopes: scopes.append(scope)
            leaf_id = f"A{ai + 1}:H{hi + 1}"
            leaves[leaf_id] = {"coordinate": [ai, hi], "analysisLogicalKey": analysis["logicalKey"],
                "checkpointHash": analysis["checkpointHash"], "hint": hint}
            leaf_rows.append([leaf_id, hint["category"], hint["attention"], scopes.index(scope),
                [indexes[eid] for eid in hint["problemEvidenceIds"]],
                [indexes[eid] for eid in hint["comparisonEvidenceIds"]]])
    def claim_row(hint):
        return [_ENUMS["categories"].index(hint["category"]), _ENUMS["attentions"].index(hint["attention"]),
            hint["title"], hint["description"], _ENUMS["claimKinds"].index(hint["claimKind"]),
            scopes.index(hint["obligationScope"]), [indexes[eid] for eid in hint["problemEvidenceIds"]],
            [indexes[eid] for eid in hint["comparisonEvidenceIds"]], hint.get("unavailableMaterialCategory")]
    common = {"hierarchyProtocol": PROTOCOL, "groupingFingerprint": _ZERO_HASH,
        "selfContainedProof": False, "outputEvidenceIds": "USE_COMPLETE_ORIGINAL_IDS_NOT_INDEXES",
        "fileColumns": ["fileId", "materialCategory"], "files": files,
        "evidenceColumns": ["evidenceId", "fileIndex", "sourceStart", "sourceEnd", "citable"],
        "evidence": evidence_rows, "enums": deepcopy(_ENUMS), "scopeColumns": ["topicIndex", "responsiblePartyIndex", "trigger"],
        "scopes": [[_ENUMS["scopeTopics"].index(s["topic"]), _ENUMS["scopeParties"].index(s["responsibleParty"]), s.get("trigger")]
                   for s in scopes], "claimColumns": CLAIM_COLUMNS,
        "lineageColumns": LINEAGE_COLUMNS, "materialStates": deepcopy(material_states)}
    # FINAL does not duplicate original category/scope/ref metadata already
    # retained by A checkpoints and the server's complete original reconcile.
    shell = {**common, "role": "FINAL", "leafRegistry": list(leaves),
        "evidenceColumns": ["evidenceId", "fileIndex"], "evidence": [row[:2] for row in evidence_rows],
        "groups": [None] * admission["groupCount"]}
    fixed = len(canonical(shell)) - 4 * admission["groupCount"]
    available = MAX_SOURCE_CHARS - fixed
    if available < admission["groupCount"] * 64:
        _fail("SEMANTIC_HIERARCHY_FINAL_INPUT_BOUND", 413)
    budgets = [available // admission["groupCount"] + int(i < available % admission["groupCount"])
               for i in range(admission["groupCount"])]
    def source(members, ordinal):
        # No proof hashes/ranges, duplicated evidence excerpts or hidden truncation.
        local_refs = {eid for i in members for hint in original[i]["hints"] for eid in _refs(hint)}
        return canonical({**common, "role": "GROUP", "groupOrdinal": ordinal, "localScopeOnly": True,
            "evidenceColumns": ["evidenceIndex", *common["evidenceColumns"]],
            "evidence": [[i, *row] for i, row in enumerate(evidence_rows) if row[0] in local_refs],
            "digestMaxChars": budgets[ordinal - 1],
            "analyses": [{"analysisOrdinal": i + 1, "summary": original[i]["summary"],
                "leaves": [[f"A{i + 1}:H{j + 1}", *claim_row(h)] for j, h in enumerate(original[i]["hints"])]}
                for i in sorted(members)]})
    affinities = [{(_scope_key(h), tuple(sorted({by_id[eid]["fileId"] for eid in _refs(h)}))) for h in a["hints"]}
                  for a in original]
    weights = [sum(len(canonical(claim_row(h))) + 40 for h in a["hints"]) for a in original]
    order = sorted(range(len(original)), key=lambda i: (-len(source([i], 1)), i))
    groups = [[] for _ in budgets]
    for position, ai in enumerate(order):
        empty = [i for i, members in enumerate(groups) if not members]
        candidates = empty if len(order) - position == len(empty) else list(range(len(groups)))
        choices = []
        for gi in candidates:
            size = len(source([*groups[gi], ai], gi + 1))
            if size <= MAX_SOURCE_CHARS:
                affinity = sum(len(affinities[ai] & affinities[other]) for other in groups[gi])
                # Balance complete output workload first; exact scope/file
                # affinity then size/ordinal resolve deterministic ties. The
                # prior best-fit packed 37 leaves into G1 and none into G3.
                choices.append((sum(weights[j] for j in groups[gi]) + weights[ai], -affinity, -size, gi))
        if not choices: _fail("SEMANTIC_HIERARCHY_GROUP_INPUT_BOUND", 413)
        gi = min(choices)[3]
        groups[gi].append(ai)
    memberships = [sorted(group) for group in groups]
    minimums = []
    for gi, members in enumerate(memberships):
        # Necessary finite typed skeleton, NOT semantic/model quality proof.
        # Only known exact category/scope/file sets may share a MERGE target;
        # unknown scopes retain separate targets. All original directional
        # references and every leaf's actual stable ID remain represented.
        classes = {}
        group_leaves = [(lid, leaf["hint"]) for lid, leaf in leaves.items() if leaf["coordinate"][0] in members]
        for lid, hint in group_leaves:
            identity = (hint["category"], _scope_key(hint), tuple(sorted({by_id[e]["fileId"] for e in _refs(hint)})),
                hint.get("unavailableMaterialCategory"), None if _known_scope(hint) else lid)
            classes.setdefault(identity, []).append((lid, hint))
        claims, lineage = [], []
        for parents in classes.values():
            h = deepcopy(parents[0][1])
            h.update(title="核", description="核")
            for field in ("problemEvidenceIds", "comparisonEvidenceIds"):
                h[field] = list(dict.fromkeys(eid for _, parent in parents for eid in parent[field]))
            if any(parent["attention"] == "REVIEW" for _, parent in parents): h["attention"] = "REVIEW"
            elif any(parent["attention"] == "INSUFFICIENT" for _, parent in parents): h["attention"] = "INSUFFICIENT"
            if len({parent["claimKind"] for _, parent in parents}) > 1: h["claimKind"] = "AMBIGUOUS_CLAUSE"
            target = len(claims)
            claims.append(claim_row(h))
            for lid, parent in parents:
                refs = _refs(parent)
                if not refs: _fail("SEMANTIC_HIERARCHY_DIGEST_ALLOCATION_BOUND", 413)
                lineage.append([lid, _ENUMS["actions"].index("MERGE" if len(parents) > 1 else "KEEP"), target, [indexes[refs[0]]], "核"])
        if len(claims) > 100: _fail("SEMANTIC_HIERARCHY_DIGEST_ALLOCATION_BOUND", 413)
        # Reserve even the longest allowed group summary; other literal text
        # has no universal semantic lower bound and MUST pass the real Bi gate.
        minimums.append(len(canonical({"groupOrdinal": gi + 1, "summary": "核" * 240, "claims": claims, "lineage": lineage})))
    remaining = available - sum(minimums)
    if remaining < 0:
        counts = [sum(len(original[i]["hints"]) for i in members) for members in memberships]
        data = {"finalFixedChars": fixed, "minimumSum": sum(minimums), "availableChars": available,
                "groupLeafCounts": counts, "minimums": minimums}
        raise AuditAiError(413, "SEMANTIC_HIERARCHY_DIGEST_ALLOCATION_BOUND",
            f"hierarchy allocation H={fixed} minimumSum={sum(minimums)} available={available} groupLeafCounts={canonical(counts)}",
            data=data)
    group_weights = [sum(weights[i] for i in members) for members in memberships]
    if not sum(group_weights): group_weights = [1] * len(memberships)
    total_weight = sum(group_weights)
    shares = [remaining * weight // total_weight for weight in group_weights]
    residue = remaining - sum(shares)
    for gi in sorted(range(len(shares)), key=lambda i: (-(remaining * group_weights[i] % total_weight), i))[:residue]: shares[gi] += 1
    budgets = [minimum + share for minimum, share in zip(minimums, shares, strict=True)]
    if any(len(source(members, gi + 1)) > MAX_SOURCE_CHARS for gi, members in enumerate(memberships)):
        _fail("SEMANTIC_HIERARCHY_GROUP_INPUT_BOUND", 413)
    binding = {"protocol": PROTOCOL, "strategy": MEMBERSHIP_STRATEGY,
        "analyses": [[a["logicalKey"], a["checkpointHash"], fingerprint(a)] for a in original],
        "members": memberships, "sources": [fingerprint(source(m, i + 1)) for i, m in enumerate(memberships)],
        "finalShell": fingerprint(shell), "digestBudgets": budgets}
    grouping_fp = fingerprint(binding)
    shell["groupingFingerprint"] = grouping_fp
    result_groups = []
    for gi, members in enumerate(memberships):
        value = json.loads(source(members, gi + 1))
        value["groupingFingerprint"] = grouping_fp
        result_groups.append({"ordinal": gi + 1, "members": members, "digestMaxChars": budgets[gi],
            "minimumStructuralDigestChars": minimums[gi], "allocationWeight": group_weights[gi],
            "leafIds": [lid for lid, leaf in leaves.items() if leaf["coordinate"][0] in members], "source": canonical(value)})
    return {**admission, "protocol": PROTOCOL, "groupingFingerprint": grouping_fp, "groups": result_groups,
        "finalShell": shell, "finalFixedChars": fixed, "leaves": leaves, "analyses": original,
        "evidenceIds": evidence_ids, "evidenceCatalog": deepcopy(evidence_catalog), "scopes": scopes,
        "originalLeafRegistry": leaf_rows, "enums": deepcopy(_ENUMS)}


def validate_group_lineage(payload, group, bundle):
    """Validate private GROUP lineage, independently of claimed model coverage.

    Existing scoped parser/evidence/money validators must also run at execution.
    This pure gate adds no public finding permission and forbids global absence.
    """
    if type(payload) is not dict or set(payload) != {"summary", "scopeStatus", "hints", "lineage"}: _fail()
    hints, rows = payload["hints"], payload["lineage"]
    if (type(payload["summary"]) is not str or not 1 <= len(payload["summary"]) <= 240
            or type(hints) is not list or len(hints) > min(100, len(group["leafIds"]))
            or type(rows) is not list or len(rows) != len(group["leafIds"])): _fail()
    if payload["scopeStatus"] != ("CHECKED_WITH_FINDINGS" if hints else "CHECKED_NO_RELEVANT_FINDINGS"): _fail()
    if any(h["claimKind"] == "GLOBAL_CLAUSE_NOT_FOUND" for h in hints): _fail()
    seen, targets = set(), {}
    allowed = set(bundle["evidenceIds"])
    by_id = {entry["evidenceId"]: entry for entry in bundle["evidenceCatalog"]}
    for row in rows:
        if type(row) is not list or len(row) != 5: _fail()
        leaf_id, action, target_index, basis, reason = row
        if (type(leaf_id) is not str or leaf_id not in group["leafIds"] or leaf_id in seen
                or action not in {"KEEP", "MERGE", "EXPLAIN", "UNRESOLVED"}
                or type(target_index) is not int or not 0 <= target_index < len(hints)
                or type(basis) is not list or not basis or any(type(eid) is not str for eid in basis)
                or len(set(basis)) != len(basis) or not set(basis).issubset(allowed)
                or type(reason) is not str or not 1 <= len(reason) <= 160): _fail()
        seen.add(leaf_id)
        original = bundle["leaves"][leaf_id]["hint"]
        target = hints[target_index]
        targets.setdefault(target_index, []).append(row)
        if (target["category"] != original["category"] or target["obligationScope"] != original["obligationScope"]
                or not set(basis).issubset(_refs(target))):
            details = _lineage_identity_issues(payload, group, bundle)
            _fail(data={"lineageIdentityIssues": details} if details else None)
        original_ids, target_ids = set(_refs(original)), set(_refs(target))
        if not target_ids.issubset(allowed): _fail()
        # Keep every original anchor except the narrowly authorized explained
        # local range witness; its complete original identity remains private.
        retained = set(original["problemEvidenceIds"]) if original["claimKind"] == "LOCAL_NOT_SEEN" else original_ids
        if action != "EXPLAIN" and not retained.issubset(target_ids): _fail()
        if action == "MERGE" and (not _known_scope(original) or not _known_scope(target)): _fail()
        if action == "UNRESOLVED" and target["attention"] == "INFO": _fail()
        downgrade = original["attention"] != "INFO" and target["attention"] == "INFO"
        if action == "EXPLAIN":
            if (not downgrade or original["claimKind"] != "LOCAL_NOT_SEEN" or not _known_scope(original)
                    or target["claimKind"] != "CLAUSE_PRESENT" or not (set(basis) - original_ids)):
                _fail()
            old_files = {by_id[eid]["fileId"] for eid in original_ids}
            if {by_id[eid]["fileId"] for eid in target_ids} != old_files: _fail()
        elif downgrade: _fail()
    if seen != set(group["leafIds"]) or set(targets) != set(range(len(hints))): _fail()
    for parent_rows in targets.values():
        if len(parent_rows) > 1:
            if any(row[1] != "MERGE" for row in parent_rows): _fail()
            parents = [bundle["leaves"][row[0]]["hint"] for row in parent_rows]
            file_sets = [{by_id[eid]["fileId"] for eid in _refs(parent)} for parent in parents]
            if any(files != file_sets[0] for files in file_sets[1:]): _fail()
            target = hints[parent_rows[0][2]]
            if {by_id[eid]["fileId"] for eid in _refs(target)} != file_sets[0]: _fail()
        elif parent_rows[0][1] == "MERGE": _fail()
    return deepcopy(payload)


GROUP_DIGEST_REASON_ENCODING = "contract-lineage-reason-pool-v1"


def expand_group_digest(digest):
    """Strict private metadata inverse; never summarize or authorize a claim."""
    if type(digest) is not dict:
        _fail()
    encoding = digest.get("lineageReasonEncoding")
    if "lineageReasonEncoding" not in digest:
        if set(digest) != {"groupOrdinal", "summary", "claims", "lineage"}:
            _fail()
        return deepcopy(digest)
    if (encoding != GROUP_DIGEST_REASON_ENCODING or set(digest) != {
            "groupOrdinal", "summary", "claims", "lineage", "lineageReasonEncoding", "lineageReasonPool"}
            or type(digest.get("lineage")) is not list or type(digest.get("lineageReasonPool")) is not list):
        _fail()
    pool = digest["lineageReasonPool"]
    if (not pool or any(type(reason) is not str or not reason for reason in pool)
            or len(set(pool)) != len(pool)):
        _fail()
    result = {key: deepcopy(value) for key, value in digest.items()
              if key not in {"lineageReasonEncoding", "lineageReasonPool"}}
    seen = []
    for row in result["lineage"]:
        if (type(row) is not list or len(row) != 5 or type(row[-1]) is not int
                or not 0 <= row[-1] < len(pool)):
            _fail()
        index = row[-1]
        if index not in seen:
            seen.append(index)
        row[-1] = pool[index]
    if seen != list(range(len(pool))):
        _fail()
    return result


def _pool_group_reasons(digest):
    pool, rows = [], []
    for row in digest["lineage"]:
        reason = row[-1]
        if reason not in pool:
            pool.append(reason)
        rows.append([*row[:-1], pool.index(reason)])
    if not pool:
        return digest
    packed = {**digest, "lineage": rows, "lineageReasonEncoding": GROUP_DIGEST_REASON_ENCODING,
              "lineageReasonPool": pool}
    if len(canonical(packed)) >= len(canonical(digest)):
        return digest
    if expand_group_digest(packed) != digest:
        _fail()
    return packed


def group_digest(validated, group, bundle):
    # Always recheck lineage. No trusted caller or hash-only shortcut.
    validated = validate_group_lineage(validated, group, bundle)
    index = {eid: i for i, eid in enumerate(bundle["evidenceIds"])}
    rows = [[bundle["enums"]["categories"].index(h["category"]), bundle["enums"]["attentions"].index(h["attention"]),
        h["title"], h["description"], bundle["enums"]["claimKinds"].index(h["claimKind"]),
        bundle["scopes"].index(h["obligationScope"]), [index[e] for e in h["problemEvidenceIds"]],
        [index[e] for e in h["comparisonEvidenceIds"]], h.get("unavailableMaterialCategory")] for h in validated["hints"]]
    digest = {"groupOrdinal": group["ordinal"], "summary": validated["summary"], "claims": rows,
          "lineage": [[lid, bundle["enums"]["actions"].index(action), target, [index[e] for e in basis], reason]
                      for lid, action, target, basis, reason in validated["lineage"]]}
    digest = _pool_group_reasons(digest)
    if len(canonical(digest)) > group["digestMaxChars"]: _fail("SEMANTIC_HIERARCHY_DIGEST_BOUND", 413)
    return digest


def final_inheritance_eligibility(source):
    """Explicit target candidates, never a substitute for source validation.

    A registry ID or partly visible range cannot make its complete old claim
    inheritable. Every original group row still needs a final disposition.
    """
    try:
        registry=source['evidenceRegistry'];full=set(source['fullyVisibleEvidenceIds'])
        citable={row[0] for row in source['evidence'] if row[4] is True}
        eligible=[]
        for gi,group in enumerate(source['groups']):
            for hi,row in enumerate(group['claims']):
                if type(row)is not list or len(row)!=9 or type(row[4])is not int:raise ValueError()
                if not 0<=row[4]<len(source['enums']['claimKinds']):raise ValueError()
                indexes=row[6]+row[7]
                if any(type(i)is not int or not 0<=i<len(registry) for i in indexes):raise ValueError()
                ids={registry[i] for i in indexes}
                if source['enums']['claimKinds'][row[4]]!='LOCAL_NOT_SEEN' and ids and ids.issubset(full & citable):
                    eligible.append(f'G{gi+1}:H{hi+1}')
        return {'protocol':'contract-final-inheritance-eligibility-v1','directTargetRefs':eligible,
                'otherObservationsStillRequireDisposition':True,'notAClaimValidationProof':True}
    except (KeyError,IndexError,TypeError,ValueError):
        raise ValueError('invalid inheritance eligibility source') from None


def final_source(bundle, digests):
    if len(digests) != bundle["groupCount"]: _fail()
    for group, digest in zip(bundle["groups"], digests, strict=True):
        expand_group_digest(digest)
        if digest.get("groupOrdinal") != group["ordinal"] or len(canonical(digest)) > group["digestMaxChars"]:
            _fail("SEMANTIC_HIERARCHY_DIGEST_BOUND", 413)
    value={**bundle['finalShell'],'groups':digests}
    if bundle['protocol']=='contract-bounded-hierarchy-v2':
        value['inheritanceEligibility']=final_inheritance_eligibility(value)
    source = canonical(value)
    if (len(source) > bundle.get("maxFinalChars", MAX_SOURCE_CHARS)
            or len(source.encode("utf-8")) > bundle.get("maxFinalBytes", 4 * MAX_SOURCE_CHARS)):
        _fail("SEMANTIC_HIERARCHY_FINAL_INPUT_BOUND", 413)
    return source


def _private_scope_witness_ids(hint, hint_index):
    """Classify an already authenticated original checkpoint, not provider text.

    Reconciliation still owns business acceptance. Only a pure process witness
    may stay in the private chain; actual problem/material-gap evidence never
    acquires this exception from a keyword, a summary, or an unknown scope.
    """
    witness = hint.get('workScopeWitness', {})
    refs = hint.get('comparisonEvidenceIds')
    if (hint.get('claimKind') != 'LOCAL_NOT_SEEN' or hint.get('attention') != 'INSUFFICIENT'
            or hint.get('problemEvidenceIds') != [] or hint.get('unavailableMaterialCategory') is not None
            or type(refs) is not list or not refs
            or witness.get('protocol') != 'contract-work-scope-witness-v1'
            or witness.get('type') != 'SCOPE_WITNESS_NOT_FINAL_EVIDENCE'
            or type(witness.get('hintIndex')) is not int or witness['hintIndex'] != hint_index
            or not re.fullmatch(r'[a-f0-9]{64}', witness.get('scopeHash', ''))
            or set(witness.get('witnessEvidenceIds', ())) != set(refs)):
        return set()
    return set(refs)


def compose_final_resolutions(bundle, groups, payload):
    """Compose existing final resolutions, never infer omitted leaf decisions.

    The caller MUST run the existing reconcile_observations on group claims
    first, and again on original full A observations with these composed rows.
    """
    if len(groups) != bundle["groupCount"]: _fail()
    expected = {(gi, hi) for gi, g in enumerate(groups) for hi in range(len(g["hints"]))}
    rows = payload.get("resolutions")
    if type(rows) is not list: _fail()
    resolutions = {}
    for row in rows:
        coordinate = row.get("observation")
        if (type(coordinate) is not list or len(coordinate) != 2
                or any(type(i) is not int for i in coordinate)): _fail()
        key = tuple(coordinate)
        if key in resolutions or key not in expected: _fail()
        resolutions[key] = row
    if set(resolutions) != expected: _fail()
    composed, chain = [], []
    for gi, (group, assignment) in enumerate(zip(groups, bundle["groups"], strict=True)):
        validate_group_lineage(group, assignment, bundle)
        for lid, action, target, basis, reason in group["lineage"]:
            resolution = resolutions[(gi, target)]
            final_indexes = resolution["finalHintIndexes"]
            if resolution["disposition"] in {"PROCESS_RESOLVED", "SCOPED_COUNTEREVIDENCE"}:
                from audit_ai.model.semantic_final_evidence import HIERARCHY_PROTOCOL
                if (bundle["protocol"] != HIERARCHY_PROTOCOL or
                        (resolution['disposition']=='PROCESS_RESOLVED' and final_indexes != [])): _fail()
                # Both ordinary group reconcile and every original A-leaf
                # reconcile independently validate this narrow private proof.
                composed.append({**deepcopy(resolution), "observation": bundle["leaves"][lid]["coordinate"]})
                chain.append({"leafId": lid, "analysisLogicalKey": bundle["leaves"][lid]["analysisLogicalKey"],
                    "analysisCheckpointHash": bundle["leaves"][lid]["checkpointHash"], "groupOrdinal": gi + 1,
                    "groupHintIndex": target, "groupAction": action, "groupReason": reason,
                    "groupBasisEvidenceIds": basis, "finalResolution": deepcopy(resolution)})
                continue
            if (type(final_indexes) is not list or not final_indexes or len(set(final_indexes)) != len(final_indexes)
                    or any(type(i) is not int or not 0 <= i < len(payload["hints"]) for i in final_indexes)): _fail()
            disposition = resolution["disposition"]
            if action == "EXPLAIN":
                # GROUP's provisional explanation does not freeze an INFO
                # outcome. FINAL may restore a real same-scope concern; the
                # original complete source/scope/anchor gate remains final.
                disposition = ("COUNTEREVIDENCE" if all(payload["hints"][i]["attention"] == "INFO" for i in final_indexes)
                               else "RETAINED")
            elif action == "UNRESOLVED" and disposition == "RETAINED": disposition = "NEEDS_VERIFICATION"
            target_refs = {eid for i in final_indexes for eid in _refs(payload["hints"][i])}
            private_witness_ids = set()
            group_hint = group['hints'][target]
            if (bundle['protocol'] == 'contract-bounded-hierarchy-v2'
                    and group_hint.get('claimKind') == 'LOCAL_NOT_SEEN'
                    and group_hint.get('attention') == 'INSUFFICIENT'
                    and group_hint.get('problemEvidenceIds') == []
                    and group_hint.get('unavailableMaterialCategory') is None):
                original_leaf = bundle['leaves'][lid]
                witness_ids = _private_scope_witness_ids(original_leaf['hint'],original_leaf['coordinate'][1])
                frozen_by_id = {entry['evidenceId']: entry for entry in bundle['evidenceCatalog']}
                # This narrow exception cannot move the question to another
                # material. Retain the ordinary basis rule if file identity is
                # unproven or the final target cites a different file set.
                if (witness_ids and witness_ids.issubset(frozen_by_id) and target_refs.issubset(frozen_by_id)
                        and {frozen_by_id[eid]['fileId'] for eid in witness_ids}
                        == {frozen_by_id[eid]['fileId'] for eid in target_refs}):
                    private_witness_ids = (witness_ids & set(basis)) - target_refs
            public_group_basis = [eid for eid in basis if eid not in private_witness_ids]
            combined_basis = list(dict.fromkeys([*public_group_basis, *resolution["basisEvidenceIds"]]))
            # Basis identity cannot silently disappear during stage composition.
            if not set(combined_basis).issubset(target_refs): _fail()
            if disposition == "COUNTEREVIDENCE":
                by_id = {entry["evidenceId"]: entry for entry in bundle["evidenceCatalog"]}
                original_ids = set(_refs(bundle["leaves"][lid]["hint"]))
                if not original_ids.issubset(by_id) or not target_refs.issubset(by_id): _fail()
                if {by_id[eid]["fileId"] for eid in original_ids} != {by_id[eid]["fileId"] for eid in target_refs}:
                    _fail()
            composed.append({"observation": bundle["leaves"][lid]["coordinate"], "disposition": disposition,
                "finalHintIndexes": final_indexes, "basisEvidenceIds": combined_basis,
                "reason": resolution["reason"]})
            chain.append({"leafId": lid, "analysisLogicalKey": bundle["leaves"][lid]["analysisLogicalKey"],
                "analysisCheckpointHash": bundle["leaves"][lid]["checkpointHash"], "groupOrdinal": gi + 1,
                "groupHintIndex": target, "groupAction": action, "groupReason": reason,
                "groupBasisEvidenceIds": basis, "finalResolution": deepcopy(resolution),
                **({'privateScopeWitnessEvidenceIds': [eid for eid in basis if eid in private_witness_ids]}
                   if private_witness_ids else {})})
    composed.sort(key=lambda row: row["observation"])
    if len(composed) != len(bundle["leaves"]): _fail()
    return composed, chain


_INTEGER = r"[0-9〇零一二三四五六七八九十百千两]+"
_NUMBER = r"[0-9〇零一二三四五六七八九十百千两]+(?:[.点][0-9〇零一二三四五六七八九]+)?"
_DATE = re.compile(rf"({_INTEGER})\s*年\s*({_INTEGER})\s*月\s*({_INTEGER})\s*[日号]?|(?<!\d)(\d{{4}})[-/.](\d{{1,2}})[-/.](\d{{1,2}})(?!\d)")
_DURATION = re.compile(rf"({_NUMBER})\s*(?:个)?(工作日|自然日|小时|个月|天|日|月|年)")
_RATE = re.compile(rf"({_NUMBER})\s*([%％‰])|((?:百分之|千分之))\s*({_NUMBER})")
_ACTOR_ACTION = re.compile(r"(甲方|乙方)\s*(?:应当|必须|负责|承担|应|须)([^。；;，,\n]{0,40}?)(交付|供货|验收|支付|付款|赔偿|修复|维修|退还|退货|解除|终止)")


def _decimal_number(raw):
    digits = {"〇": "0", "零": "0", "一": "1", "二": "2", "两": "2", "三": "3", "四": "4",
              "五": "5", "六": "6", "七": "7", "八": "8", "九": "9"}
    if re.fullmatch(r"[0-9]+(?:\.[0-9]+)?", raw): return Decimal(raw)
    integer, separator, fraction = raw.partition("点")
    if all(c in digits or c.isascii() and c.isdigit() for c in integer):
        whole = int("".join(digits.get(c, c) for c in integer))
    else:
        whole, current = 0, 0
        for char in integer:
            if char in digits: current = int(digits[char])
            elif char in {"十", "百", "千"}:
                whole += (current or 1) * {"十": 10, "百": 100, "千": 1000}[char]
                current = 0
            else: raise ValueError("unsupported finite numeric form")
        whole += current
    return Decimal(str(whole) + ("." + "".join(digits.get(c, c) for c in fraction) if separator else ""))


def _critical_tokens(text):
    """Finite literal normal forms; no financial calculation or inferred fact."""
    tokens, dates = set(), []
    for match in _DATE.finditer(text):
        y, m, d = match.groups()[:3] if match[1] is not None else match.groups()[3:]
        numbers = tuple(int(_decimal_number(value)) for value in (y, m, d))
        try:
            value = date(*numbers)
        except (ValueError, OverflowError, InvalidOperation): value = None
        tokens.add(("DATE", value.isoformat()) if value else ("INVALID_DATE", *numbers))
        dates.append(match.span())
    for match in _DURATION.finditer(text):
        if any(start < match.end() and match.start() < end for start, end in dates): continue
        try: number = _decimal_number(match[1])
        except (ValueError, InvalidOperation): continue
        unit = {"天": "日", "自然日": "日", "个月": "月"}.get(match[2], match[2])
        tokens.add(("DURATION", number, unit))
    for match in _RATE.finditer(text):
        number = match[1] if match[1] is not None else match[4]
        try: number = _decimal_number(number)
        except (ValueError, InvalidOperation): continue
        unit = "PER_MILLE" if match[2] == "‰" or match[3] == "千分之" else "PERCENT"
        tokens.add((unit, number))
    return tokens


def _actor_actions(text):
    result = set()
    for match in _ACTOR_ACTION.finditer(text):
        # A party requesting another party's act is NOT itself the obligor.
        # Only the bounded explicit subject/modal/action shape is checked.
        if re.search(r"甲方|乙方|要求|督促|协助|促使", match[2]): continue
        result.add((match[1], match[3]))
    return result


def check_critical_anchors(payload, *, catalog):
    """Hierarchy-only finite cited-source mismatch guard, not entailment.

    Check what an output actually says; source dates need not reappear in a
    legitimate concise digest. Common literal formats normalize exactly.
    Unsupported amounts remain the original OBSERVE monetary warning domain;
    nothing here grants financial authority or replaces deterministic F02.
    """
    from audit_ai.model.semantic_quality import QualityFailure
    def check(text, ids, path, index=None, category=None):
        if not isinstance(text, str): return
        segments = [catalog.by_id[eid] for eid in dict.fromkeys(ids) if eid in catalog.by_id]
        # Join only adjacent cited spans of the SAME file, never arbitrary
        # fragments or different parties/files into a fabricated source fact.
        spans = []
        for segment in sorted(segments, key=lambda e: (e.file_id, e.source_start, e.source_end)):
            if spans and spans[-1][0] == segment.file_id and spans[-1][2] == segment.source_start:
                spans[-1][2] = segment.source_end
                spans[-1][3] += segment.exact_text
            else: spans.append([segment.file_id, segment.source_start, segment.source_end, segment.exact_text])
        source_tokens = set().union(*(_critical_tokens(span[3]) for span in spans)) if spans else set()
        if not _critical_tokens(text).issubset(source_tokens):
            raise QualityFailure("CRITICAL_FACT_UNSUPPORTED", path, index, category)
        source_actors = set().union(*(_actor_actions(span[3]) for span in spans)) if spans else set()
        for actor, action in _actor_actions(text):
            # Reject a PROVABLE explicit role inversion, not a mere mention
            # of the counterparty or an unsupported general-language guess.
            if (actor, action) not in source_actors and any(a != actor and v == action for a, v in source_actors):
                raise QualityFailure("CRITICAL_FACT_UNSUPPORTED", path, index, category)
    all_ids = []
    for index, hint in enumerate(payload["hints"]):
        ids = _refs(hint)
        all_ids.extend(ids)
        check(hint["title"] + "\n" + hint["description"], ids, f"hints[{index}].description", index, hint["category"])
    check(payload.get("summary"), all_ids, "summary")
    for index, row in enumerate(payload.get("resolutions", [])):
        check(row["reason"], row["basisEvidenceIds"], f"resolutions[{index}].reason", index)
    for index, row in enumerate(payload.get("lineage", [])):
        if type(row) is list and len(row) == 5 and type(row[3]) is list and all(type(eid) is str for eid in row[3]):
            check(row[4], row[3], f"lineage[{index}].reason", index)
