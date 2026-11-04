"""Pure contract hierarchy reference-wire inverse, not a validation shortcut.

The caller must run the original scoped/type, evidence, lineage, money and
reconciliation gates on the complete returned candidate before using it.
Only already frozen input aliases can inherit literal claims and metadata.
"""
from __future__ import annotations

from copy import deepcopy
import json
import re

from audit_ai.errors import AuditAiError

PROTOCOL = "contract-hierarchy-provider-references-v1"
_ACTIONS = frozenset({"KEEP", "MERGE", "EXPLAIN", "UNRESOLVED"})
_DISPOSITIONS = frozenset({"RETAINED", "SCOPED_REPLACEMENT", "COUNTEREVIDENCE", "NEEDS_VERIFICATION"})
_RETAIN_REASON = "保留原观察及其证据。"
_UNRESOLVED_REASON = "保留待核实观察及其证据。"
_MAX_ROWS = 6400


class ProviderReferenceFailure(AuditAiError):
    """A finite server-authored coordinate, never a provider value or text."""
    def __init__(self, field_path):
        if not re.fullmatch(r"lineage\[[0-9]{1,4}\]\.target", field_path):
            raise ValueError("invalid internal reference coordinate")
        super().__init__(422, "SEMANTIC_HIERARCHY_PROVIDER_INVALID",
                         "bounded hierarchy provider references invalid")
        self.field_path = field_path
        self.actual_class = "NEW_HINT_TARGET_REQUIRED"


class ProviderInheritedVisibilityFailure(AuditAiError):
    def __init__(self,alias):
        if not re.fullmatch(r'G[1-9][0-9]*:H[1-9][0-9]*',alias):raise ValueError('invalid internal reference')
        super().__init__(422,'SEMANTIC_INHERITED_EVIDENCE_NOT_VISIBLE','inherited source range is not fully visible')
        self.field_path='inheritedHints'
        self.actual_class='SOURCE_'+alias.replace(':','_')+'_NEW_VISIBLE_TARGET_REQUIRED'


class ProviderFinalProtocolFailure(AuditAiError):
    """Finite v2 coordinates only: never echo a provider key/value or body."""
    def __init__(self,path,classification):
        allowed={'FINAL_ENVELOPE_FIELDS','FINAL_PROTOCOL_LITERAL','FINAL_SUMMARY_TYPE',
            'FINAL_NEW_HINT_SHAPE','FINAL_RESOLUTION_LIST_BOUND','FINAL_RESOLUTION_ROW_SHAPE',
            'FINAL_OBSERVATION_COORDINATE','FINAL_DISPOSITION_LITERAL','FINAL_TARGET_REFERENCE_TYPE',
            'FINAL_TARGET_CARDINALITY','FINAL_DUPLICATE_TARGET_REFERENCE','FINAL_OBSERVATION_UNKNOWN',
            'FINAL_DUPLICATE_OBSERVATION','FINAL_TARGET_REFERENCE_UNKNOWN','FINAL_NEW_HINT_INDEX_DOMAIN',
            'FINAL_BASIS_REQUIRED','FINAL_BASIS_INDEX_DOMAIN','FINAL_DUPLICATE_BASIS_INDEX',
            'FINAL_REASON_REQUIRED_OR_TYPE','FINAL_OBSERVATION_COVERAGE'}
        if (classification not in allowed or not re.fullmatch(
                r'(?:response|protocol|summary|newHints|resolutions)(?:\[[0-9]{1,4}\])?'
                r'(?:\.(?:observation|disposition|targetRefs|basisIndexes|reason))?',path)):
            raise ValueError('invalid internal protocol diagnostic')
        super().__init__(422,'SEMANTIC_HIERARCHY_PROVIDER_INVALID','bounded hierarchy provider references invalid')
        self.field_path=path;self.actual_class=classification


def _fail():
    raise AuditAiError(422, "SEMANTIC_HIERARCHY_PROVIDER_INVALID",
                       "bounded hierarchy provider references invalid")


def _json_values(value):
    if type(value) is dict:
        if any(type(key) is not str for key in value):
            _fail()
        for child in value.values():
            _json_values(child)
    elif type(value) is list:
        for child in value:
            _json_values(child)
    elif value is not None and type(value) not in (str, int, float, bool):
        _fail()


def decode_provider(payload, *, role, source_hints, evidence_ids, protocol=PROTOCOL, fully_visible_evidence_ids=None):
    """Restore a GROUP ScopedDraft+lineage or FINAL SynthesisDraft exactly.

    Alias identity is never deduplicated by claim content. Output hint indices
    follow target references' first-consumption order. Unreferenced FINAL new
    hints remain present for the original final/global validation gates.
    No parser, model invocation, text shortening or business authorization lives
    here. Unknown fields inside a new hint are retained for the old parser.
    """
    try:
        _json_values(payload)
        _json_values(source_hints)
        _json_values(evidence_ids)
        # Reject non-finite numbers and unencodable input, never substitute text.
        json.dumps([payload, source_hints, evidence_ids], ensure_ascii=False,
                   allow_nan=False, separators=(",", ":")).encode("utf-8")
        return _decode(payload, role, source_hints, evidence_ids, protocol, fully_visible_evidence_ids)
    except (ValueError, TypeError, UnicodeError, RecursionError, OverflowError):
        _fail()


def _decode(payload, role, source_hints, evidence_ids, protocol, fully_visible_evidence_ids):
    from audit_ai.model.semantic_final_evidence import PROVIDER_PROTOCOL
    if protocol not in {PROTOCOL, PROVIDER_PROTOCOL}: _fail()
    if type(role) is not str or role not in {"GROUP", "FINAL"}:
        _fail()
    field = "lineage" if role == "GROUP" else "resolutions"
    row_path=field
    def fail(path,classification):
        if role=='FINAL' and protocol==PROVIDER_PROTOCOL:raise ProviderFinalProtocolFailure(path,classification)
        _fail()
    if type(payload) is not dict or set(payload) != {'protocol','summary','newHints',field}:
        fail('response','FINAL_ENVELOPE_FIELDS')
    if payload['protocol']!=protocol:fail('protocol','FINAL_PROTOCOL_LITERAL')
    if type(payload['summary']) is not str:fail('summary','FINAL_SUMMARY_TYPE')
    if (type(payload['newHints']) is not list or len(payload['newHints'])>100
            or any(type(hint) is not dict for hint in payload['newHints'])):
        fail('newHints','FINAL_NEW_HINT_SHAPE')
    if type(payload[field]) is not list or len(payload[field])>_MAX_ROWS:
        fail('resolutions','FINAL_RESOLUTION_LIST_BOUND')
    if (type(source_hints) is not dict or len(source_hints) > _MAX_ROWS
            or type(evidence_ids) is not list
            or any(type(eid) is not str or not eid for eid in evidence_ids)
            or len(set(evidence_ids)) != len(evidence_ids)):
        _fail()
    prefix = "A" if role == "GROUP" else "G"
    if any(type(alias) is not str or not re.fullmatch(prefix + r"[1-9][0-9]*:H[1-9][0-9]*", alias)
           or type(hint) is not dict for alias, hint in source_hints.items()):
        _fail()
    new = payload["newHints"]
    hints, consumed, positions = [], set(), {}
    allowed_evidence = set(evidence_ids)
    full=set()
    if fully_visible_evidence_ids is not None:
        if (type(fully_visible_evidence_ids)is not list or any(type(e)is not str for e in fully_visible_evidence_ids)
            or len(fully_visible_evidence_ids)!=len(set(fully_visible_evidence_ids))
            or not set(fully_visible_evidence_ids).issubset(allowed_evidence)):_fail()
        full=set(fully_visible_evidence_ids)

    def reference(ref):
        if type(ref) is not str:
            fail(row_path+'.targetRefs','FINAL_TARGET_REFERENCE_TYPE')
        if ref in source_hints:
            if role=='FINAL' and protocol==PROVIDER_PROTOCOL:
                original=source_hints[ref]
                citations=original.get('problemEvidenceIds',[])+original.get('comparisonEvidenceIds',[])
                if not citations or not set(citations).issubset(full):raise ProviderInheritedVisibilityFailure(ref)
            return source_hints[ref]
        if not re.fullmatch(r"N(?:0|[1-9][0-9]*)", ref):
            fail(row_path+'.targetRefs','FINAL_TARGET_REFERENCE_UNKNOWN')
        try:
            index = int(ref[1:])
        except ValueError:
            fail(row_path+'.targetRefs','FINAL_NEW_HINT_INDEX_DOMAIN')
        if not 0 <= index < len(new):
            fail(row_path+'.targetRefs','FINAL_NEW_HINT_INDEX_DOMAIN')
        return new[index]

    def consume(ref):
        hint = reference(ref)
        if ref not in positions:
            positions[ref] = len(hints)
            hints.append(deepcopy(hint))
        consumed.add(ref)
        return positions[ref]

    def basis(indices, alias, inherit):
        if indices is None:
            if not inherit:
                fail(row_path+'.basisIndexes','FINAL_BASIS_REQUIRED')
            hint = source_hints[alias]
            problem, comparison = hint.get("problemEvidenceIds"), hint.get("comparisonEvidenceIds")
            if (type(problem) is not list or type(comparison) is not list
                    or any(type(eid) is not str for eid in problem + comparison)):
                _fail()
            ids = list(dict.fromkeys(problem + comparison))
            if not ids or not set(ids).issubset(allowed_evidence):
                _fail()
            return ids
        if type(indices) is not list or not indices:fail(row_path+'.basisIndexes','FINAL_BASIS_REQUIRED')
        if any(type(index) is not int or not 0<=index<len(evidence_ids) for index in indices):
            fail(row_path+'.basisIndexes','FINAL_BASIS_INDEX_DOMAIN')
        if len(set(indices))!=len(indices):fail(row_path+'.basisIndexes','FINAL_DUPLICATE_BASIS_INDEX')
        return [evidence_ids[index] for index in indices]

    def reason(value, inherit, unresolved):
        if value is None:
            if not inherit:
                fail(row_path+'.reason','FINAL_REASON_REQUIRED_OR_TYPE')
            return _UNRESOLVED_REASON if unresolved else _RETAIN_REASON
        if type(value) is not str or not value:
            fail(row_path+'.reason','FINAL_REASON_REQUIRED_OR_TYPE')
        return value

    rows, seen = [], set()
    if role == "GROUP":
        for row_index, row in enumerate(payload[field]):
            if type(row) is not list or len(row) != 5:
                _fail()
            alias, action, target, indices, explanation = row
            if (type(alias) is not str or alias not in source_hints or alias in seen
                    or type(action) is not str or action not in _ACTIONS or type(target) is not str):
                _fail()
            target_hint = reference(target)
            is_new = target not in source_hints
            if action in {"MERGE", "EXPLAIN"}:
                if not is_new:
                    raise ProviderReferenceFailure(f"lineage[{row_index}].target")
            elif target != alias and not is_new:
                _fail()
            inherit = target == alias and action in {"KEEP", "UNRESOLVED"}
            ids = basis(indices, alias, inherit)
            text = reason(explanation, inherit, action == "UNRESOLVED")
            rows.append([alias, action, consume(target), ids, text])
            seen.add(alias)
        if seen != set(source_hints) or any(f"N{i}" not in consumed for i in range(len(new))):
            _fail()
        return {"summary": payload["summary"], "scopeStatus": "CHECKED_WITH_FINDINGS" if hints else "CHECKED_NO_RELEVANT_FINDINGS",
                "hints": hints, "lineage": rows}

    for row_index,row in enumerate(payload[field]):
        row_path=f'resolutions[{row_index}]'
        if type(row) is not list or len(row) != 6:
            fail(row_path,'FINAL_RESOLUTION_ROW_SHAPE')
        group, hint, disposition, targets, indices, explanation = row
        process = protocol == PROVIDER_PROTOCOL and disposition == "PROCESS_RESOLVED"
        if type(group) is not int or group<0 or type(hint) is not int or hint<0:
            fail(row_path+'.observation','FINAL_OBSERVATION_COORDINATE')
        if type(disposition) is not str or not (disposition in _DISPOSITIONS or process
                or (protocol==PROVIDER_PROTOCOL and disposition=='SCOPED_COUNTEREVIDENCE')):
            fail(row_path+'.disposition','FINAL_DISPOSITION_LITERAL')
        if type(targets) is not list:fail(row_path+'.targetRefs','FINAL_TARGET_REFERENCE_TYPE')
        if bool(targets)==process:fail(row_path+'.targetRefs','FINAL_TARGET_CARDINALITY')
        if any(type(target) is not str for target in targets):fail(row_path+'.targetRefs','FINAL_TARGET_REFERENCE_TYPE')
        if len(set(targets))!=len(targets):fail(row_path+'.targetRefs','FINAL_DUPLICATE_TARGET_REFERENCE')
        alias = f"G{group + 1}:H{hint + 1}"
        if alias not in source_hints:fail(row_path+'.observation','FINAL_OBSERVATION_UNKNOWN')
        if alias in seen:fail(row_path+'.observation','FINAL_DUPLICATE_OBSERVATION')
        inherit = targets == [alias] and disposition in {"RETAINED", "NEEDS_VERIFICATION"}
        ids = basis(indices, alias, inherit)
        text = reason(explanation, inherit, disposition == "NEEDS_VERIFICATION")
        rows.append({"observation": [group, hint], "disposition": disposition,
                     "finalHintIndexes": [consume(target) for target in targets],
                     "basisEvidenceIds": ids, "reason": text})
        seen.add(alias)
    if seen != set(source_hints):
        fail('resolutions','FINAL_OBSERVATION_COVERAGE')
    for index in range(len(new)):
        consume(f"N{index}")
    return {"summary": payload["summary"], "hints": hints, "resolutions": rows}
