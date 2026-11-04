"""Finite scoped quality checks, not a universal semantic-support oracle.

Only a new explicitly frozen quality request uses this private protocol. The
existing strict draft/evidence/money validators still run afterwards. Final
publication needs a complete typed disposition of the immutable observations;
source-content acceptance independently checks whether the stated reasons hold.
"""
from __future__ import annotations

import re
import hashlib
import json
from typing import Any, Literal, Mapping, get_args

from pydantic import BaseModel, ConfigDict, Field, ValidationError
from audit_ai.errors import AuditAiError

from audit_ai.model.contract_draft import (ProviderContractDraft, ProviderHintDraft,
    ProviderDraftValidationFailure, ProviderValidationIssue, parse_provider_contract_draft)

PROTOCOL = "contract-semantic-quality-v1"
WORK_CLAIM_TYPE_PROTOCOL = "contract-work-claim-type-v1"
LEGACY_REGENERATION_CONTEXT_PROTOCOL = "contract-semantic-regeneration-context-v1"
REGENERATION_CONTEXT_PROTOCOL = "contract-semantic-regeneration-context-v2"
MAX_REGENERATION_CONTEXT_BYTES = 8192
BUSINESS_REGENERATION_CONTEXT_PROTOCOL='contract-semantic-regeneration-business-v1'
MAX_BUSINESS_REGENERATION_CONTEXT_BYTES=24576
MONEY_REPAIR_GUIDANCE_PROTOCOL = "contract-money-regeneration-guidance-v1"
_STRICT = ConfigDict(extra="forbid", strict=True, populate_by_name=True)
ClaimKind = Literal["CLAUSE_PRESENT", "LOCAL_NOT_SEEN", "GLOBAL_CLAUSE_NOT_FOUND",
                    "MATERIAL_MISSING", "AMBIGUOUS_CLAUSE", "CROSS_MATERIAL_CONFLICT"]


def _context_identity_error():
    return AuditAiError(409, "SEMANTIC_REGENERATION_CONTEXT_IDENTITY", "regeneration context identity is unproven")


def _context_json(value):
    try:
        result = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)
        result.encode("utf-8")
        return result
    except (TypeError, ValueError, UnicodeError):
        raise _context_identity_error() from None


def _decode_v1_regeneration_context(value):
    """Validate immutable server metadata, not the rejected candidate's validity."""
    if not isinstance(value, str):
        raise _context_identity_error()
    try:
        size = len(value.encode("utf-8"))
    except UnicodeError:
        raise _context_identity_error() from None
    if size > MAX_REGENERATION_CONTEXT_BYTES:
        raise AuditAiError(413, "SEMANTIC_REGENERATION_CONTEXT_BOUND", "complete regeneration context exceeds bound")
    try:
        result = json.loads(value)
    except (ValueError, TypeError):
        raise _context_identity_error() from None
    if (not isinstance(result, dict) or set(result) != {"protocol", "rejectedOperationFingerprint",
            "rejectedCandidateSha256", "candidate", "focusedOldIndexes", "issues"}
            or result["protocol"] != LEGACY_REGENERATION_CONTEXT_PROTOCOL or _context_json(result) != value
            or any(not isinstance(result.get(key), str) or not re.fullmatch(r"[0-9a-f]{64}", result[key])
                   for key in ("rejectedOperationFingerprint", "rejectedCandidateSha256"))
            or result["rejectedCandidateSha256"] != hashlib.sha256(_context_json(result["candidate"]).encode("utf-8")).hexdigest()
            or not isinstance(result["issues"], list) or not 1 <= len(result["issues"]) <= 8
            or not isinstance(result["focusedOldIndexes"], list)):
        raise _context_identity_error()
    indexes = result["focusedOldIndexes"]
    hints = result["candidate"].get("hints") if isinstance(result["candidate"], dict) else None
    if (any(type(index) is not int or index < 0 or not isinstance(hints, list) or index >= len(hints) for index in indexes)
            or indexes != sorted(set(indexes))):
        raise _context_identity_error()
    return result


def _context_issues(issues):
    required = {"schemaVersion", "fieldPath", "itemIndex", "errorKind", "actualClass",
                "allowedValues", "nullAllowed", "errorId"}
    if not isinstance(issues, list) or not 1 <= len(issues) <= 8:
        raise _context_identity_error()
    for issue in issues:
        if (type(issue) is not dict or not required.issubset(issue) or set(issue) - required - {"category"}
                or any(type(issue.get(key)) is not str for key in ("schemaVersion", "fieldPath", "errorKind", "actualClass", "errorId"))
                or type(issue["nullAllowed"]) is not bool or type(issue["allowedValues"]) is not list
                or any(type(item) is not str for item in issue["allowedValues"])
                or (issue["itemIndex"] is not None and (type(issue["itemIndex"]) is not int or issue["itemIndex"] < 0))
                or ("category" in issue and type(issue["category"]) is not str)):
            raise _context_identity_error()
        # This is exactly ProviderValidationIssue.as_dict's immutable error ID.
        unsigned = {key: item for key, item in issue.items() if key != "errorId"}
        expected = hashlib.sha256(json.dumps(unsigned, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        if issue["errorId"] != expected:
            raise _context_identity_error()
    return issues


def _context_coordinates(candidate, issues):
    coordinates = []
    for issue in issues:
        path = issue["fieldPath"]
        if not path or not re.fullmatch(r"[A-Za-z_][A-Za-z_0-9]*(?:\[[0-9]+\]|\.[A-Za-z_][A-Za-z_0-9]*)*", path):
            raise _context_identity_error()
        matched = re.match(r"^(hints|resolutions)\[(0|[1-9][0-9]*)\](?=$|\.|\[)", path)
        if matched:
            try:
                field, index = matched[1], int(matched[2])
            except ValueError:
                raise _context_identity_error() from None
            array = candidate.get(field) if isinstance(candidate, dict) else None
            if (not isinstance(array, list) or index >= len(array)
                    or (issue["itemIndex"] is not None and issue["itemIndex"] != index)):
                raise _context_identity_error()
            coordinates.append({"domain": "HINT" if field == "hints" else "RESOLUTION", "fieldPath": path, "index": index})
        elif path.startswith(("hints[", "resolutions[")):
            raise _context_identity_error()
        else:
            # A Pydantic itemIndex without an explicit array coordinate is not
            # evidence that the issue belongs to the hints collection.
            coordinates.append({"domain": "FIELD", "fieldPath": path, "index": None})
    return coordinates


def decode_regeneration_context(value):
    """Validate the bounded wire; return its complete candidate for validation."""
    if type(value) is not str:
        raise _context_identity_error()
    try:
        size = len(value.encode("utf-8"))
    except UnicodeError:
        raise _context_identity_error() from None
    if size > MAX_BUSINESS_REGENERATION_CONTEXT_BYTES:
        raise AuditAiError(413, "SEMANTIC_REGENERATION_CONTEXT_BOUND", "complete regeneration context exceeds bound")
    try:
        encoded = json.loads(value)
    except (ValueError, TypeError):
        raise _context_identity_error() from None
    business=type(encoded)is dict and encoded.get('protocol')==BUSINESS_REGENERATION_CONTEXT_PROTOCOL
    if size>MAX_REGENERATION_CONTEXT_BYTES and not business:
        raise AuditAiError(413,'SEMANTIC_REGENERATION_CONTEXT_BOUND','complete regeneration context exceeds bound')
    if isinstance(encoded, dict) and encoded.get("protocol") == LEGACY_REGENERATION_CONTEXT_PROTOCOL:
        return _decode_v1_regeneration_context(value)
    if (type(encoded) is not dict or set(encoded) != {"protocol", "rejectedOperationFingerprint", "rejectedCandidateSha256",
            "candidate", "candidateEncoding", "focusedOldIndexes", "focusedOldCoordinates", "issues"}
            or encoded["protocol"] not in {REGENERATION_CONTEXT_PROTOCOL,BUSINESS_REGENERATION_CONTEXT_PROTOCOL} or _context_json(encoded) != value
            or any(type(encoded.get(key)) is not str or not re.fullmatch(r"[0-9a-f]{64}", encoded[key])
                   for key in ("rejectedOperationFingerprint", "rejectedCandidateSha256"))):
        raise _context_identity_error()
    from audit_ai.model.semantic_regeneration_packing import unpack_candidate
    if business and (encoded['candidateEncoding']!='FULL' or type(encoded['candidate'])is not dict
            or encoded['candidate'].get('protocol')!='contract-semantic-business-issues-v1'):
        raise _context_identity_error()
    try:
        candidate = unpack_candidate(encoded["candidateEncoding"], encoded["candidate"])
    except (ValueError, TypeError, UnicodeError):
        raise _context_identity_error() from None
    if hashlib.sha256(_context_json(candidate).encode("utf-8")).hexdigest() != encoded["rejectedCandidateSha256"]:
        raise _context_identity_error()
    issues = _context_issues(encoded["issues"])
    coordinates = _context_coordinates(candidate, issues)
    indexes = sorted({row["index"] for row in coordinates if row["domain"] == "HINT"})
    if (_context_json(coordinates) != _context_json(encoded["focusedOldCoordinates"])
            or _context_json(indexes) != _context_json(encoded["focusedOldIndexes"])):
        raise _context_identity_error()
    return {**encoded, "candidate": candidate}


def provider_regeneration_context_json(value):
    """Keep the verified encoded wire; never expand it past the 8192-byte cap."""
    decode_regeneration_context(value)
    return value


def build_regeneration_context(payload, issues, rejected_operation_fingerprint, *, hierarchy=False,business_adapter=False):
    """Complete candidate first, then lossless readable arrays if actually needed."""
    if (not isinstance(rejected_operation_fingerprint, str)
            or not re.fullmatch(r"[0-9a-f]{64}", rejected_operation_fingerprint)
            or not 1 <= len(issues) <= 8 or any(not isinstance(issue, ProviderValidationIssue) for issue in issues)):
        raise _context_identity_error()
    raw = _context_json(payload)
    safe_issues = _context_issues([issue.as_dict() for issue in issues])
    coordinates = _context_coordinates(payload, safe_issues)
    envelope = {"protocol": BUSINESS_REGENERATION_CONTEXT_PROTOCOL if business_adapter else REGENERATION_CONTEXT_PROTOCOL,
        "rejectedOperationFingerprint": rejected_operation_fingerprint,
        "rejectedCandidateSha256": hashlib.sha256(raw.encode("utf-8")).hexdigest(),
        "candidate": payload, "candidateEncoding": "FULL",
        "focusedOldIndexes": sorted({row["index"] for row in coordinates if row["domain"] == "HINT"}),
        "focusedOldCoordinates": coordinates, "issues": safe_issues}
    value = _context_json(envelope)
    limit=MAX_BUSINESS_REGENERATION_CONTEXT_BYTES if business_adapter else MAX_REGENERATION_CONTEXT_BYTES
    if business_adapter and len(value.encode('utf-8'))>limit:
        raise AuditAiError(413,'SEMANTIC_REGENERATION_CONTEXT_BOUND','complete regeneration context exceeds bound')
    if len(value.encode("utf-8")) > limit:
        from audit_ai.model.semantic_regeneration_packing import pack_candidate, pack_hierarchy_candidate
        try:
            encoding, candidate = (pack_hierarchy_candidate if hierarchy else pack_candidate)(payload)
        except (ValueError, TypeError, UnicodeError):
            raise _context_identity_error() from None
        value = _context_json({**envelope, "candidateEncoding": encoding, "candidate": candidate})
    decode_regeneration_context(value)
    return value


class ObligationScope(BaseModel):
    model_config = _STRICT
    topic: Literal["STRUCTURE", "PROJECT_IDENTITY", "SUPPLIER_IDENTITY", "DELIVERY", "ACCEPTANCE",
        "SERVICE_RESPONSE", "WARRANTY", "DELAY_LIABILITY", "DEFECT_LIABILITY", "OTHER_LIABILITY",
        "SIGNING", "EFFECTIVENESS", "PERFORMANCE_PERIOD", "TERMINATION", "SUBCONTRACTING", "OTHER"]
    responsible_party: Literal["PARTY_A", "PARTY_B", "BOTH", "UNSPECIFIED"] = Field(alias="responsibleParty")
    trigger: str | None = Field(max_length=100)


class QualityHint(ProviderHintDraft):
    claim_kind: ClaimKind = Field(alias="claimKind")
    obligation_scope: ObligationScope = Field(alias="obligationScope")


class QualityFailure(ValueError):
    def __init__(self, code: str, field_path: str = "hints", index: int | None = None, category: str | None = None,
                 *, allowed_values=(), safe_issues=(), audit_reason="INVALID_TYPED_SCHEMA"):
        super().__init__(code)
        self.code, self.field_path, self.index = code, field_path, index
        self.category = category
        self.allowed_values, self.safe_issues, self.audit_reason = tuple(allowed_values), tuple(safe_issues), audit_reason


class ScopedDraft(ProviderContractDraft):
    hints: list[QualityHint] = Field(max_length=100)
    scope_status: Literal["CHECKED_WITH_FINDINGS", "CHECKED_NO_RELEVANT_FINDINGS"] = Field(alias="scopeStatus")


class Resolution(BaseModel):
    model_config = _STRICT
    observation: list[int] = Field(min_length=2, max_length=2)
    disposition: Literal["RETAINED", "SCOPED_REPLACEMENT", "COUNTEREVIDENCE", "NEEDS_VERIFICATION"]
    final_hint_indexes: list[int] = Field(alias="finalHintIndexes", min_length=1, max_length=100)
    basis_evidence_ids: list[str] = Field(alias="basisEvidenceIds", min_length=1, max_length=50)
    reason: str = Field(min_length=1, max_length=300)


class SynthesisDraft(ProviderContractDraft):
    hints: list[QualityHint] = Field(max_length=100)
    resolutions: list[Resolution] = Field(max_length=6400)


class ProcessResolution(Resolution):
    disposition: Literal["RETAINED", "SCOPED_REPLACEMENT", "COUNTEREVIDENCE", "NEEDS_VERIFICATION", "PROCESS_RESOLVED", "SCOPED_COUNTEREVIDENCE"]
    final_hint_indexes: list[int] = Field(alias="finalHintIndexes", min_length=0, max_length=100)


class ProcessSynthesisDraft(SynthesisDraft):
    resolutions: list[ProcessResolution] = Field(max_length=6400)


class ServerDerivedResolution(ProcessResolution):
    """Internal only: a rederived union, never a provider-controlled exemption."""
    basis_evidence_ids: list[str] = Field(alias="basisEvidenceIds", min_length=1, max_length=6400)


class ServerDerivedSynthesisDraft(ProcessSynthesisDraft):
    resolutions: list[ServerDerivedResolution] = Field(max_length=6400)


def quality_schema(*, synthesis: bool) -> dict[str, Any]:
    schema = (SynthesisDraft if synthesis else ScopedDraft).model_json_schema(by_alias=True)
    hint = schema["$defs"]["QualityHint"]
    if synthesis:
        hint["properties"]["claimKind"]["enum"].remove("LOCAL_NOT_SEEN")
    hint["allOf"] = [
        {"if": {"properties": {"claimKind": {"const": "LOCAL_NOT_SEEN"}}, "required": ["claimKind"]},
         "then": {"properties": {"attention": {"const": "INSUFFICIENT"},
                                  "unavailableMaterialCategory": {"type": "null"}}}},
        {"if": {"properties": {"claimKind": {"const": "MATERIAL_MISSING"}}, "required": ["claimKind"]},
         "then": {"properties": {"attention": {"const": "INSUFFICIENT"},
                                  "unavailableMaterialCategory": {"not": {"type": "null"}}}}},
    ]
    return schema


def analysis_work_claim_contract():
    """Provider-facing rules for the existing private (not final) carrier."""
    return {"version": WORK_CLAIM_TYPE_PROTOCOL,
        "LOCAL_NOT_SEEN": {"attention": "INSUFFICIENT", "unavailableMaterialCategory": None,
            "problemEvidenceIds": "EXISTING_REAL_PROBLEM_ANCHORS_ONLY",
            "comparisonEvidenceIds": "CURRENT_CITABLE_SCOPE_WITNESS",
            "publication": "WORK_ONLY_REQUIRES_GLOBAL_RESOLUTION"},
        "MATERIAL_MISSING": {"attention": "INSUFFICIENT", "scope": "GLOBAL_FROZEN_MATERIAL_SET",
            "unavailableMaterialCategory": "EXPLICIT_ACTUALLY_ABSENT_REQUIRED_CATEGORY",
            "problemEvidenceIds": "EMPTY_ALLOWED_FOR_GENUINE_GLOBAL_MATERIAL_ABSENCE",
            "title": "EXPLICIT_SAME_MISSING_MATERIAL_ONLY", "comparisonEvidenceIds": "CURRENT_CITABLE_REQUIRED_SOURCE"},
        "OTHER_CLAIMS": {"evidence": "UNCHANGED_FINAL_FINDING_RULES"}}


def _check_claim_types(value, *, synthesis):
    for index, hint in enumerate(value["hints"]):
        kind = hint["claimKind"]
        field, allowed, nullable = None, (), False
        code = "WORK_OBSERVATION_TYPE" if kind == "LOCAL_NOT_SEEN" else "MATERIAL_ABSENCE_TYPE"
        if kind == "LOCAL_NOT_SEEN":
            if synthesis:
                path = f"hints[{index}].claimKind"
                allowed = tuple(kind for kind in get_args(ClaimKind) if kind != "LOCAL_NOT_SEEN")
                issues = tuple(ProviderValidationIssue(f"hints[{i}].claimKind", i,
                    "UNRESOLVED_LOCAL_OBSERVATION", "INVALID_SCOPED_CLAIM", allowed, False,
                    category=other["category"], schema_version=PROTOCOL)
                    for i, other in enumerate(value["hints"]) if other["claimKind"] == "LOCAL_NOT_SEEN")[:8]
                raise QualityFailure("UNRESOLVED_LOCAL_OBSERVATION", path, index, hint["category"],
                    allowed_values=allowed, safe_issues=issues)
            if hint["attention"] != "INSUFFICIENT":
                field, allowed = "attention", ("INSUFFICIENT",)
            elif hint["unavailableMaterialCategory"] is not None:
                field, nullable = "unavailableMaterialCategory", True
        elif kind == "MATERIAL_MISSING":
            if hint["attention"] != "INSUFFICIENT":
                field, allowed = "attention", ("INSUFFICIENT",)
            elif hint["unavailableMaterialCategory"] is None:
                field = "unavailableMaterialCategory"
                allowed = ("CONTRACT_BODY", "AWARD_NOTICE", "BID_RESPONSE", "TENDER_DOCUMENT", "SUPPLEMENT")
        if field:
            path = f"hints[{index}].{field}"
            issue = ProviderValidationIssue(path, index, code, "INVALID_SCOPED_CLAIM", allowed,
                nullable, category=hint["category"], schema_version=PROTOCOL)
            raise QualityFailure(code, path, index, hint["category"], allowed_values=allowed, safe_issues=(issue,))


def parse_scoped_draft(payload: Mapping[str, Any], *, synthesis: bool, allow_process_resolution=False, server_derived_basis=False):
    # First preserve the old authority's safe field coordinates for ALL its
    # ordinary fields. Remove only the explicitly versioned quality fields.
    base = {key: value for key, value in payload.items() if key not in {"scopeStatus", "resolutions"}}
    if isinstance(base.get("hints"), list):
        base["hints"] = [{key: value for key, value in hint.items() if key not in {"claimKind", "obligationScope"}}
                         if isinstance(hint, dict) else hint for hint in base["hints"]]
    try:
        parse_provider_contract_draft(base)
    except ProviderDraftValidationFailure as error:
        first = error.safe_issues[0]
        raise QualityFailure(error.reason.value, first.field_path, first.item_index,
            allowed_values=first.allowed_values, safe_issues=error.safe_issues, audit_reason=error.reason.value) from None
    try:
        if server_derived_basis:
            from audit_ai.model.semantic_business_adapter import checked_closure
            for row in payload.get('resolutions',[]):checked_closure([row['basisEvidenceIds']])
        parsed = (ServerDerivedSynthesisDraft if synthesis and allow_process_resolution and server_derived_basis else
                  ProcessSynthesisDraft if synthesis and allow_process_resolution else
                  SynthesisDraft if synthesis else ScopedDraft).model_validate(payload)
    except ValidationError as error:
        issues = _quality_validation_issues(error, quality_schema(synthesis=synthesis))
        first = issues[0]
        code = "SCOPE_STATUS" if first.field_path == "scopeStatus" else "QUALITY_SCHEMA"
        raise QualityFailure(code, first.field_path, first.item_index, allowed_values=first.allowed_values,
                             safe_issues=issues) from None
    value = parsed.model_dump(by_alias=True)
    if synthesis:
        for row in value["resolutions"]:
            if bool(row["finalHintIndexes"]) == (row["disposition"] == "PROCESS_RESOLVED"):
                raise QualityFailure("RESOLUTION_TARGET", "resolutions")
    _check_claim_types(value, synthesis=synthesis)
    if not synthesis and ((value["scopeStatus"] == "CHECKED_WITH_FINDINGS") != bool(value["hints"])):
        raise QualityFailure("SCOPE_STATUS", "scopeStatus")
    metadata = ({"resolutions": value.pop("resolutions")} if synthesis
                else {"scopeStatus": value.pop("scopeStatus")})
    metadata["hintMetadata"] = [{key: hint.pop(key) for key in ("claimKind", "obligationScope")} for hint in value["hints"]]
    return value, metadata


def _quality_validation_issues(error, schema):
    """Known schema paths/enums only; unknown names and values never escape."""
    issues = []
    names = {"scopeStatus", "resolutions", "hints", "claimKind", "obligationScope", "topic", "responsibleParty", "trigger",
             "observation", "disposition", "finalHintIndexes", "basisEvidenceIds", "reason"}
    for entry in error.errors(include_input=False, include_context=False, include_url=False)[:8]:
        node, parts, index = schema, [], None
        for part in entry["loc"][:6]:
            if "$ref" in node:
                node = schema["$defs"][node["$ref"].split("/")[-1]]
            if type(part) is int and 0 <= part < 6400:
                parts.append(f"[{part}]")
                if index is None: index = part
                node = node.get("items", {})
            else:
                name = part if part in names else "OTHER"
                parts.append(("." if parts else "") + name)
                node = node.get("properties", {}).get(name, {})
        allowed = node.get("enum", ())
        issues.append(ProviderValidationIssue("".join(parts), index, "TYPE_OR_BOUND", "INVALID_TYPED_QUALITY",
            tuple(allowed), any(row.get("type") == "null" for row in node.get("anyOf", [])), schema_version=PROTOCOL))
    return tuple(issues)


def reconcile_observations(partials, payload, *, allowed_evidence_ids: set[str], catalog=None,
                           allow_process_resolution=False, process_scope_hashes=(), final_evidence_proof=None,
                           scope_refinement_proofs=None, server_derived_basis=False):
    finals = payload["hints"]
    for absent in finals:
        if absent.get("claimKind") == "LOCAL_NOT_SEEN":
            raise QualityFailure("UNRESOLVED_LOCAL_OBSERVATION", "hints")
        if absent.get("claimKind") == "GLOBAL_CLAUSE_NOT_FOUND":
            scope = absent.get("obligationScope")
            if not scope or scope.get("responsibleParty") == "UNSPECIFIED":
                raise QualityFailure("OBLIGATION_SCOPE", "hints")
            if any(present.get("claimKind") == "CLAUSE_PRESENT" and present.get("obligationScope") == scope for present in finals):
                raise QualityFailure("FINAL_CLAIM_CONTRADICTION", "hints")
    expected = {(p, h): hint for p, result in enumerate(partials)
                for h, hint in enumerate(result["hints"])}
    try:
        if server_derived_basis:
            from audit_ai.model.semantic_business_adapter import checked_closure
            for row in payload.get('resolutions',[]):checked_closure([row['basisEvidenceIds']])
        rows = [(ServerDerivedResolution if server_derived_basis and allow_process_resolution else
                 ProcessResolution if allow_process_resolution else Resolution).model_validate(row)
                for row in payload.get("resolutions", [])]
    except ValidationError:
        raise QualityFailure("RESOLUTION_SCHEMA", "resolutions") from None
    coordinates = [tuple(row.observation) for row in rows]
    if len(set(coordinates)) != len(coordinates) or set(coordinates) != set(expected):
        raise QualityFailure("OBSERVATION_COVERAGE", "resolutions")
    for index, row in enumerate(rows):
        original = expected[tuple(row.observation)]
        if row.disposition == 'SCOPED_COUNTEREVIDENCE':
            from audit_ai.model.semantic_scope_refinement import validate
            proof=validate(original,row,finals,catalog,allowed_evidence_ids,process_scope_hashes,final_evidence_proof,index)
            if scope_refinement_proofs is not None:scope_refinement_proofs.append(proof)
            continue
        if row.disposition == "PROCESS_RESOLVED":
            _validate_process_resolution(original, row, finals, catalog, allowed_evidence_ids,
                                         process_scope_hashes, final_evidence_proof, index)
            continue
        if not row.final_hint_indexes:
            raise QualityFailure("RESOLUTION_TARGET", "resolutions", index)
        if (len(set(row.final_hint_indexes)) != len(row.final_hint_indexes)
                or any(type(i) is not int or i < 0 or i >= len(finals) for i in row.final_hint_indexes)):
            raise QualityFailure("RESOLUTION_TARGET", "resolutions", index)
        targets = [finals[i] for i in row.final_hint_indexes]
        if any(target["category"] != original["category"] for target in targets):
            raise QualityFailure("OBLIGATION_SCOPE", "resolutions", index)
        original_scope = original.get("obligationScope")
        if row.disposition in {"RETAINED", "NEEDS_VERIFICATION"} and any(
                target.get("obligationScope") != original_scope for target in targets):
            raise QualityFailure("OBLIGATION_SCOPE", "resolutions", index)
        if row.disposition == "SCOPED_REPLACEMENT":
            if not original_scope or any(
                target.get("obligationScope", {}).get("topic") != original_scope.get("topic")
                or (original_scope.get("responsibleParty") != "UNSPECIFIED"
                    and target.get("obligationScope", {}).get("responsibleParty") != original_scope.get("responsibleParty"))
                or (original_scope.get("trigger") is not None
                    and target.get("obligationScope", {}).get("trigger") != original_scope.get("trigger"))
                for target in targets):
                raise QualityFailure("OBLIGATION_SCOPE", "resolutions", index)
        basis = set(row.basis_evidence_ids)
        target_ids = {eid for target in targets for eid in (
            *target["problemEvidenceIds"], *target["comparisonEvidenceIds"])}
        if not basis.issubset(allowed_evidence_ids & target_ids):
            raise QualityFailure("RESOLUTION_EVIDENCE", "resolutions", index)
        original_ids = set((*original["problemEvidenceIds"], *original["comparisonEvidenceIds"]))
        witness = original.get("workScopeWitness", {})
        private_local = (original.get("claimKind") == "LOCAL_NOT_SEEN"
            and witness.get("protocol") == "contract-work-scope-witness-v1"
            and witness.get("type") == "SCOPE_WITNESS_NOT_FINAL_EVIDENCE"
            and witness.get("hintIndex") == row.observation[1]
            and re.fullmatch(r"[a-f0-9]{64}", witness.get("scopeHash", ""))
            and set(witness.get("witnessEvidenceIds", ())) == original_ids)
        retained_ids = set(original["problemEvidenceIds"]) if private_local else original_ids
        if (original["attention"] != "INFO" and row.disposition in {"RETAINED", "NEEDS_VERIFICATION", "SCOPED_REPLACEMENT"}
                and not retained_ids.issubset(target_ids)):
            raise QualityFailure("OBSERVATION_ANCHOR_LOST", "resolutions", index)
        # A real concern cannot become normal simply because another block is
        # positive. A narrowly local 'not seen' observation can be superseded
        # only with distinct final source evidence and a recorded reason.
        if original["attention"] != "INFO" and all(t["attention"] == "INFO" for t in targets):
            local_unseen = (original["attention"] == "INSUFFICIENT" and (private_local or re.search(
                r"(?:本块|本段|当前片段|当前范围|局部).{0,16}(?:未见|未显示|未展示|未能确认)",
                original["title"] + original["description"])))
            if (row.disposition != "COUNTEREVIDENCE" or not local_unseen
                    or not (basis - original_ids)):
                raise QualityFailure("RISK_DOWNGRADE", "resolutions", index)
            scope = original.get("obligationScope")
            if (original.get("claimKind") != "LOCAL_NOT_SEEN" or not scope
                    or scope.get("responsibleParty") == "UNSPECIFIED" or not scope.get("trigger")
                    or any(target.get("obligationScope") != scope or target.get("claimKind") != "CLAUSE_PRESENT" for target in targets)):
                raise QualityFailure("OBLIGATION_SCOPE", "resolutions", index)
        elif row.disposition == "COUNTEREVIDENCE":
            raise QualityFailure("COUNTEREVIDENCE_STATE", "resolutions", index)
        if row.disposition == "NEEDS_VERIFICATION" and not any(t["attention"] != "INFO" for t in targets):
            raise QualityFailure("RISK_DOWNGRADE", "resolutions", index)
    return [row.model_dump(by_alias=True) for row in rows]


def _validate_process_resolution(original, row, finals, catalog, allowed, scope_hashes, proof, index):
    from audit_ai.model.evidence import normalize_visible_text
    from audit_ai.model.semantic_final_evidence import verify_proof
    def reject(): raise QualityFailure("PROCESS_RESOLUTION_UNPROVEN", "resolutions", index)
    scope, witness = original.get("obligationScope", {}), original.get("workScopeWitness", {})
    refs = set(original["comparisonEvidenceIds"])
    if (row.final_hint_indexes or original.get("claimKind") != "LOCAL_NOT_SEEN"
            or original["attention"] != "INSUFFICIENT" or original["problemEvidenceIds"]
            or original.get("unavailableMaterialCategory") is not None
            or scope.get("responsibleParty") not in {"PARTY_A", "PARTY_B", "BOTH"}
            or not isinstance(scope.get("trigger"), str) or not normalize_visible_text(scope["trigger"])
            or witness.get("protocol") != "contract-work-scope-witness-v1"
            or witness.get("type") != "SCOPE_WITNESS_NOT_FINAL_EVIDENCE"
            or witness.get("hintIndex") != row.observation[1]
            or witness.get("scopeHash") not in scope_hashes
            or not refs or set(witness.get("witnessEvidenceIds", ())) != refs
            or not verify_proof(proof, catalog)):
        reject()
    basis = set(row.basis_evidence_ids)
    if (len(basis) != len(row.basis_evidence_ids) or not basis or not basis.issubset(allowed)
            or not refs.issubset(catalog.by_id) or not basis.issubset(catalog.by_id)
            or not basis.isdisjoint(refs) or any(not catalog.by_id[e].citable for e in basis)
            or {catalog.by_id[e].file_id for e in basis} != {catalog.by_id[e].file_id for e in refs}):
        reject()
    # Conservative explicit scope eligibility, NOT universal entailment.
    parties = {"PARTY_A": ["甲方"], "PARTY_B": ["乙方"], "BOTH": ["甲方", "乙方"]}[scope["responsibleParty"]]
    from audit_ai.model.semantic_final_evidence import visible_catalog
    visible = visible_catalog(proof, catalog)
    if not basis.issubset(visible.by_id): reject()
    if not any(all(p in s.exact_text for p in parties)
               and normalize_visible_text(scope["trigger"]) in normalize_visible_text(s.exact_text)
               for e in basis for s in [visible.by_id[e]]):
        reject()
    # A process row does not manufacture a public INFO or weaken its parser.
    if not any(h.get("claimKind") == "CLAUSE_PRESENT" and h["attention"] == "INFO"
               and h["category"] == original["category"] and h.get("obligationScope") == scope
               and not h["problemEvidenceIds"] and basis.issubset(set(h["comparisonEvidenceIds"])) for h in finals):
        reject()


def quality_repair_guidance(issues, *, synthesis, complete_local_source_refs=None):
    """Server-owned finite actions, never echo arbitrary provider error text."""
    guidance = []
    for issue in issues:
        if issue.error_kind in {"MONETARY_CALCULATION", "MONETARY_VALUE"}:
            guidance.append({"protocol": MONEY_REPAIR_GUIDANCE_PROTOCOL,
                "fieldPath": issue.field_path, "itemIndex": issue.item_index,
                "category": issue.category, "errorKind": issue.error_kind,
                "scope": "GLOBAL_CHECKED_FROZEN_SOURCE" if synthesis else "CURRENT_OPERATION_EVIDENCE",
                "validationScope": "TITLE_LF_DESCRIPTION",
                "action": "REGENERATE_WITHOUT_MONETARY_VERDICTS",
                "instruction": "原坐标定位旧完整候选；实际金额安全校验范围是title + LF + description组合，不仅是description，"
                    "不固定新候选排序。数值为空、已脱敏或未输出数字，也不能对报价/金额作一致、相等、高于、低于或计算结论。"
                    "尾随F02声明不能使前述金额判断变为合法；AI不替代F02的确定性金额校验。"
                    "明确的条目编号、名称、型号或结构文字核对必须点明对象，不得借此作报价/金额结论。"
                    "按同一完整候选、原材料、原evidence IDs和requiredCategories完整重新生成，不能换来源、借无关证据、"
                    "删掉必需类别或全部有效风险来通过。保留全部有真实来源支持的有效风险及其责任方、触发条件和证据；"
                    "只纠正不被允许的金额主张，不虚构数值、改为正常或声称F02已验证。"})
            continue
        if issue.error_kind == "UNRESOLVED_LOCAL_OBSERVATION" and synthesis:
            matched = re.fullmatch(r"SOURCE_G([1-3])_H([1-9][0-9]?|100)_INVALID_SCOPED_CLAIM", issue.actual_class)
            alias = f"G{matched[1]}:H{matched[2]}" if matched and issue.field_path == "inheritedHints" else None
            multiple = re.fullmatch(r"SOURCES_(G[1-3]_H(?:[1-9][0-9]?|100)(?:__G[1-3]_H(?:[1-9][0-9]?|100)){1,7})_INVALID_SCOPED_CLAIM", issue.actual_class)
            aliases = multiple[1].split("__") if multiple and issue.field_path == "inheritedHints" else []
            aliases = [ref.replace("_", ":") for ref in aliases] if len(set(aliases)) == len(aliases) else []
            reported = aliases or ([alias] if alias else [])
            complete = complete_local_source_refs if reported and complete_local_source_refs is not None else None
            if complete is not None and (type(complete) not in (tuple, list) or len(complete) > 300
                    or any(type(ref) is not str or not re.fullmatch(r"G[1-3]:H(?:[1-9][0-9]?|100)", ref) for ref in complete)
                    or len(set(complete)) != len(complete) or not set(reported).issubset(complete)):
                raise ValueError("HIERARCHY_LOCAL_REFERENCE_SCOPE_INVALID")
            selected = complete if complete is not None else reported
            entry = {"fieldPath": issue.field_path, "itemIndex": issue.item_index,
                "category": issue.category, "errorKind": issue.error_kind,
                "scope": "GLOBAL_CHECKED_FROZEN_SOURCE", "action": "RESOLVE_LOCAL_OBSERVATION_WITH_GLOBAL_EVIDENCE",
                "instruction": (",".join(selected) + "：" if selected else "")
                    + "局部观察不是可发布结论；不得在targetRefs继续继承它，即使NEEDS_VERIFICATION。"
                    "按全量冻结来源核实同一文件/义务/主体/触发条件，形成有真实problem/comparison锚点的完整newHints，"
                    "用N目标、显式basis/reason交代全部原叶及有效证据的去向。"
                    "不得直接改LOCAL_NOT_SEEN类型、改INFO、假造全局缺失、借无关证据或删风险通过；无法支持正式意见则保持失败。"}
            if alias is not None:
                entry["sourceRef"] = alias
            if aliases:
                entry["sourceRefs"] = aliases
                entry["instruction"] += "逐别名核实各自义务，首category仅指首错；最多8个已定位项、非穷尽，仍须核对全部候选。"
            if complete is not None:
                entry["allLocalSourceRefs"] = list(complete)
                entry["instruction"] += "以上别名全集派生自完整groups，须全部处理，不限于诊断前8项。"
            guidance.append(entry)
            continue
        if issue.error_kind in {"EVIDENCE_SUPPORT_INVALID", "WORK_OBSERVATION_TYPE", "MATERIAL_ABSENCE_TYPE"}:
            guidance.append({"fieldPath": issue.field_path, "itemIndex": issue.item_index,
                "category": issue.category, "errorKind": issue.error_kind,
                "scope": "GLOBAL_CHECKED_FROZEN_SOURCE" if synthesis else "CURRENT_OPERATION_EVIDENCE",
                "action": "REGENERATE_WITH_STAGE_VALID_CLAIM_AND_EVIDENCE_TYPE",
                "instruction": ("综合结果不得保留LOCAL_NOT_SEEN，必须依据完整范围形成有真实问题或对照锚点的正式意见。"
                    if synthesis else "仅本块未见是LOCAL_NOT_SEEN私有工作观察，attention必须INSUFFICIENT，unavailableMaterialCategory必须null；"
                    "引用当前可引用comparisonEvidenceIds只见证已检查范围，不伪造缺失条款的问题原文，不改成INFO。")
                    + "MATERIAL_MISSING仅适用于冻结全局确实缺少的必要材料，必须有明确非null材料类别、标题明确同一缺失材料及其真实对照依据。"
                    "其他实际风险仍须原有真实问题锚点。按同一完整输入重新核对，不改来源、借无关锚点、删除有效风险或伪装通过。"})
            continue
        if issue.error_kind != "EVIDENCE_SUPPORT_SCOPE":
            continue
        guidance.append({"fieldPath": issue.field_path, "itemIndex": issue.item_index,
            "category": issue.category, "errorKind": issue.error_kind,
            "scope": "GLOBAL_CHECKED_FROZEN_SOURCE" if synthesis else "CURRENT_OPERATION_EVIDENCE",
            "action": "NARROW_CLAIM_TO_SUPPORTED_SCOPE_OR_GROUND_WHOLE_CLAIM",
            "instruction": "此坐标指原候选hint的title与description组合主张，不固定新候选排序。"
                "当前结构完整性主张只引用了含明细的局部摘录，不能据此判断合同正文或响应文件整体完整。"
                "重新核对同一允许来源：若只核对清单列，应明确写本块清单字段及可核对的表头，不作全文结论；"
                "整体主张必须有对应范围的真实条款证据。修正不是evidenceRepairs，不存在必须保留的错误工作主张；"
                "材料、证据身份和有效风险不可变，不能只换任意锚点、删除有效关注或改成正常来通过。"})
    return guidance


def _supported_local_table_columns(text, excerpts):
    # A bounded column observation is not a whole-contract completeness claim.
    # Its actual header must exist; a data row mentioning quantity is not enough.
    if not re.search(r"(?:本块|本段|当前片段|当前范围|局部).{0,16}(?:明细|清单|表格).{0,12}(?:结构|列|字段).{0,6}(?:完整|齐全|完备)", text):
        return False
    whole = r"(?:全文|整份合同|整个合同|合同|全部条款|响应文件|采购文件).{0,8}(?:完整|齐全|完备)"
    for match in re.finditer(whole, text):
        prefix = text[max(0, match.start() - 8):match.start()]
        if not re.search(r"(?:不代表|不意味着|不能证明|未判断)\s*$", prefix):
            return False
    for excerpt in excerpts:
        for line in excerpt.splitlines():
            cells = [re.sub(r"\s+", "", cell) for cell in re.split(r"\t|\|", line)]
            if len(cells) < 3 or "MX" in line:
                continue
            name = any(re.fullmatch(r"(?:项目|货物|服务|设备)?名称|品名|项目内容|服务内容", cell) for cell in cells)
            quantity = any(re.fullmatch(r"数量(?:[（(][^()（）\t]{1,8}[）)])?", cell) for cell in cells)
            price = any(re.fullmatch(r"(?:含税|不含税|综合)?单价(?:[（(][^()（）\t]{1,8}[）)])?", cell) for cell in cells)
            if name and quantity and price:
                return True
    return False


def _only_scoped_supplement_completeness(text):
    # Bind each assertion to its own subject, not a preceding title or negated
    # assertion. In particular, a greedy match must not consume the next subject
    # in "不代表合同缺失但合同完整". This remains a finite negative gate, not
    # a general Chinese entailment classifier.
    subject = re.compile(
        r"(?P<supplement>(?:全部|所有)?(?:附件|补充材料|补充文件)(?:正文|内容|全文|整体)?)"
        r"|(?P<global>整份合同|整个合同|合同(?:全文|正文|整体|全部内容)?"
        r"|响应文件(?:全文|正文|整体)?|采购文件(?:全文|正文|整体)?"
        r"|全部条款|全部内容|全文|正文|文档)")
    complete = re.compile(r"完整|齐全|完备|无缺失|(?:均|都)已(?:提供|补齐)")
    for clause in re.split(r"[。！？!?；;，,\n]", text):
        subjects = list(subject.finditer(clause))
        previous_end = 0
        for predicate in complete.finditer(clause):
            owners = [row for row in subjects if previous_end <= row.start() and row.end() <= predicate.start()]
            previous_end = predicate.end()
            # A conjunct or parenthesized attachment cannot hide the explicit
            # contract subject sharing this same completeness assertion.
            if any(row.lastgroup == "global" and not re.search(
                    r"(?:不代表|不意味着|不能证明|未判断)\s*$", clause[:row.start()]) for row in owners):
                return False
            owner = owners[-1] if owners else None
            if owner is None:
                return False
            prefix = clause[:owner.start()]
            if re.search(r"(?:不代表|不意味着|不能证明|未判断)\s*$", prefix):
                continue
            if owner.lastgroup != "supplement":
                return False
            between = clause[owner.end():predicate.start()]
            content_scope = re.search(r"结构|章节|正文|内容|字段|表头", owner.group() + between)
            if (re.search(r"全部|所有", owner.group())
                    or re.search(r"全部|所有|均|都", between)
                    or (predicate.group() != "完整" and not content_scope)
                    or predicate.group().startswith(("均已", "都已"))):
                # Content of cited files is not proof that the attachment
                # inventory is complete, even when every cited file is whole.
                return False
    return True


def _supported_complete_supplement_scope(text, catalog, ids):
    """A complete cited supplement is not merely a quotation/table row.

    This only rules out that finite rejection. It proves neither general
    entailment, template compliance nor completeness of the attachment inventory.
    """
    if not re.search(r"附件|补充材料|补充文件", text) or not ids:
        return False
    if not _only_scoped_supplement_completeness(text):
        return False
    cited = [catalog.by_id[eid] for eid in ids if eid in catalog.by_id]
    if len(cited) != len(ids) or any(not row.citable or row.material_category != "SUPPLEMENT" for row in cited):
        return False
    for file_id in {row.file_id for row in cited}:
        bound = catalog.source_lengths.get(file_id)
        rows = [row for row in catalog.segments if row.file_id == file_id]
        if bound is None or not rows or {row.evidence_id for row in rows} != {row.evidence_id for row in cited if row.file_id == file_id}:
            return False
        cursor = 0
        for row in rows:
            if row.source_start != cursor or row.source_end - row.source_start != len(row.exact_text):
                return False
            cursor = row.source_end
        if cursor != bound:
            return False
        # Even a complete one-row file is still only a row. Require separate
        # substantive, non-tabular content; headers/blank lines do not suffice.
        if not any("\t" not in line and "|" not in line and len(line.strip()) >= 12
                   and not re.search(r"MX[-－]?\d+|序号|数量.{0,16}单价|单价.{0,16}数量", line, re.IGNORECASE)
                   for line in "".join(row.exact_text for row in rows).splitlines()):
            return False
    return True


def check_claims(payload, *, catalog, available_categories, allowed_evidence_ids, synthesis,
                 required_global_source_files=None):
    """Reject finite provable contradictions; do not claim general entailment."""
    for index, hint in enumerate(payload["hints"]):
        if (hint.get('claimKind')=='GLOBAL_CLAUSE_NOT_FOUND' and required_global_source_files is not None
            and not set(required_global_source_files).issubset(catalog.source_lengths)):
            raise QualityFailure('GLOBAL_ABSENCE_NOT_VISIBLE',f'hints[{index}].claimKind',index,hint['category'])
        if not synthesis and hint.get("claimKind") == "GLOBAL_CLAUSE_NOT_FOUND":
            raise QualityFailure("LOCAL_CLAIM_SCOPE", f"hints[{index}].claimKind", index, hint["category"])
        absent = hint["unavailableMaterialCategory"]
        if absent is not None and absent in available_categories:
            raise QualityFailure("GLOBAL_MATERIAL_PRESENT", f"hints[{index}].unavailableMaterialCategory", index, hint["category"])
        text = hint["title"] + "。" + hint["description"]
        if not re.search(r"[\u3400-\u9fff]", text):
            raise QualityFailure("CHINESE_BUSINESS_TEXT", f"hints[{index}].description", index)
        for match in re.finditer(r"([A-Za-z]+)[-－]?(\d+)\s*(?:至|到|[-—])\s*\1[-－]?(\d+).{0,6}共\s*(\d+)\s*项", text):
            if int(match[3]) < int(match[2]) or int(match[4]) != int(match[3]) - int(match[2]) + 1:
                raise QualityFailure("CLAIM_COUNT_CONTRADICTION", f"hints[{index}].description", index)
        ids = set((*hint["problemEvidenceIds"], *hint["comparisonEvidenceIds"]))
        if not ids.issubset(allowed_evidence_ids):
            raise QualityFailure("EVIDENCE_SCOPE", f"hints[{index}].problemEvidenceIds", index)
        excerpts = [catalog.by_id[eid].exact_text for eid in ids if eid in catalog.by_id]
        broad_structure = (hint["category"] == "CONTRACT_STRUCTURE" and hint["attention"] == "INFO"
                           and re.search(r"(?:结构|全文|全部|内容).{0,8}(?:完整|齐全|完备)", text))
        if (broad_structure and excerpts and all(re.search(r"(?:MX|明细|数量|单价)", t) for t in excerpts)
                and not _supported_local_table_columns(text, excerpts)
                and not _supported_complete_supplement_scope(text, catalog, ids)):
            raise QualityFailure("EVIDENCE_SUPPORT_SCOPE", f"hints[{index}]", index, hint["category"])
        # Analysis can describe a bounded absence, never assert a document-wide
        # clause absence based solely on its current slice.
        if (not synthesis and hint["attention"] == "INSUFFICIENT" and absent is None
                and re.search(r"(?:(?:缺少|缺失|未约定|未载明).{0,10}(?:交付|终止|解除|违约|期限)|(?:交付|终止|解除|违约|期限).{0,10}(?:缺失|缺少|未约定|未载明))", text)
                and not re.search(r"本块|本段|当前片段|当前范围|局部", text)):
            raise QualityFailure("LOCAL_CLAIM_SCOPE", f"hints[{index}].description", index)
