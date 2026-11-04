"""Readable, contract-only metadata factoring; never compress finding text.

The input is the existing evidence-table + hint-layout representation. Outputs
are independent copies. All index spaces remain distinct and every original
registry order is retained. There is deliberately no general JSON-tree codec.
"""
from __future__ import annotations

import base64
from copy import deepcopy
import json
import re
from typing import get_args

from audit_ai.errors import AuditAiError
from audit_ai.model.contract_draft import Attention, ContractCategory, MaterialCategory
from audit_ai.model.semantic_quality import ClaimKind, ObligationScope


PROTOCOL = "contract-synthesis-metadata-packing-v1"
MAX_SYNTHESIS_CHARS = 12_000
_EVIDENCE_COLUMNS = ["evidenceId", "fileIndex", "citable", "sourceStart", "sourceEnd", "exactTextSha256"]
_UNIT_COLUMNS = ["scopeHash", "scopeStatus", "ranges"]
_RANGE_COLUMNS = ["fileIndex", "sourceStart", "sourceEnd", "citable", "segmentCount"]
_SCOPE_COLUMNS = ["topic", "responsibleParty", "trigger"]
_ENUMS = {"category": get_args(ContractCategory), "attention": get_args(Attention),
          "topic": get_args(ObligationScope.model_fields["topic"].annotation),
          "responsibleParty": get_args(ObligationScope.model_fields["responsible_party"].annotation),
          "scopeStatus": ("CHECKED_WITH_FINDINGS", "CHECKED_NO_RELEVANT_FINDINGS")}


def _require(condition):
    if not condition:
        raise AuditAiError(409, "SEMANTIC_SYNTHESIS_PACKING_INVALID", "contract synthesis metadata is incompatible")


def _json(value):
    try:
        text = json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
        text.encode("utf-8")
        return text
    except (TypeError, ValueError, UnicodeError):
        _require(False)


def _index(value, count):
    _require(type(value) is int and 0 <= value < count)


def _hash(value):
    _require(type(value) is str and re.fullmatch(r"[0-9a-f]{64}", value) is not None)


def _file_registry(value):
    _require(type(value) is list)
    for file in value:
        _require(type(file) is dict and set(file) == {"fileId", "materialCategory"}
                 and type(file["fileId"]) is str and bool(file["fileId"].strip())
                 and file["materialCategory"] in get_args(MaterialCategory))


def _span(row):
    _require(type(row[1]) is int and type(row[2]) is int and 0 <= row[1] < row[2]
             and type(row[3]) is bool)


def _validate(value):
    _json(value)
    _require(type(value) is dict and "metadataPacking" not in value and "sharedFileRegistry" not in value
             and value.get("inputFormat") == "contract-synthesis-evidence-table-v1"
             and value.get("qualityHintEncoding") == "contract-quality-hint-table-v1"
             and type(value.get("omittedHintCount")) is int and value["omittedHintCount"] == 0)
    evidence, quality, work = (value.get(key) for key in ("evidenceCatalog", "qualityRegistry", "workScopeRegistry"))
    _require(type(evidence) is dict and evidence.get("columns") == _EVIDENCE_COLUMNS and type(evidence.get("rows")) is list)
    _file_registry(value.get("fileRegistry"))
    identities = []
    for row in evidence["rows"]:
        _require(type(row) is list and len(row) == 6 and type(row[0]) is str and bool(row[0]))
        _index(row[1], len(value["fileRegistry"]))
        _span([row[1], row[3], row[4], row[2]])
        _hash(row[5]); identities.append(row[0])
    _require(len(set(identities)) == len(identities))
    _require(type(quality) is dict and type(quality.get("claimKinds")) is list
             and all(type(k) is str and k in get_args(ClaimKind) for k in quality["claimKinds"])
             and len(set(quality["claimKinds"])) == len(quality["claimKinds"])
             and type(quality.get("obligationScopes")) is list)
    for scope in quality["obligationScopes"]:
        _require(type(scope) is dict and set(scope) == set(_SCOPE_COLUMNS)
                 and scope["topic"] in _ENUMS["topic"] and scope["responsibleParty"] in _ENUMS["responsibleParty"]
                 and (scope["trigger"] is None or type(scope["trigger"]) is str))
    layouts = value.get("hintLayouts")
    _require(type(layouts) is list)
    required = {"category", "attention", "title", "description", "problemEvidenceIds", "comparisonEvidenceIds", "q"}
    for layout in layouts:
        _require(type(layout) is list and all(type(k) is str for k in layout) and len(set(layout)) == len(layout)
                 and required <= set(layout) <= required | {"unavailableMaterialCategory"})
    partials = value.get("partialResults")
    _require(type(partials) is list)
    referenced = set()
    for partial in partials:
        _require(type(partial) is dict and type(partial.get("summary")) is str and type(partial.get("hints")) is list)
        for row in partial["hints"]:
            _require(type(row) is list and len(row) > 0)
            _index(row[0], len(layouts)); layout = layouts[row[0]]
            _require(len(row) == len(layout) + 1)
            hint = dict(zip(layout, row[1:], strict=True))
            for field in ("category", "attention"):
                _require(type(hint[field]) is str and hint[field] in _ENUMS[field])
            _require(type(hint["title"]) is str and type(hint["description"]) is str)
            for field in ("problemEvidenceIds", "comparisonEvidenceIds"):
                _require(type(hint[field]) is list and all(type(i) is str and i in identities for i in hint[field]))
                referenced.update(hint[field])
            if "unavailableMaterialCategory" in hint:
                _require(hint["unavailableMaterialCategory"] is None or hint["unavailableMaterialCategory"] in get_args(MaterialCategory))
            q = hint["q"]
            _require(type(q) is list and len(q) == 2)
            _index(q[0], len(quality["claimKinds"])); _index(q[1], len(quality["obligationScopes"]))
    _require(referenced == set(identities))
    _require(type(work) is dict and work.get("protocol") == "contract-work-scope-witness-v1"
             and work.get("unitColumns") == _UNIT_COLUMNS and work.get("rangeColumns") == _RANGE_COLUMNS
             and type(work.get("units")) is list and len(work["units"]) == len(partials))
    _file_registry(work.get("fileRegistry"))
    for unit in work["units"]:
        _require(type(unit) is list and len(unit) == 3 and unit[1] in _ENUMS["scopeStatus"] and type(unit[2]) is list)
        _hash(unit[0])
        for row in unit[2]:
            _require(type(row) is list and len(row) == 5)
            _index(row[0], len(work["fileRegistry"])); _span(row)
            _require(type(row[4]) is int and row[4] > 0)


def _enums(value):
    pools = {key: [] for key in _ENUMS}
    def index(field, token):
        if token not in pools[field]:
            pools[field].append(token)
        return pools[field].index(token)
    for partial in value["partialResults"]:
        for row in partial["hints"]:
            layout = value["hintLayouts"][row[0]]
            for field in ("category", "attention"):
                at = layout.index(field) + 1
                row[at] = index(field, row[at])
    for scope in value["qualityRegistry"]["obligationScopes"]:
        for field in ("topic", "responsibleParty"):
            scope[field] = index(field, scope[field])
    for unit in value["workScopeRegistry"]["units"]:
        unit[1] = index("scopeStatus", unit[1])
    value["metadataPacking"] = {"protocol": PROTOCOL, "stages": [], "pools": pools}


def _scopes(value):
    scopes = value["qualityRegistry"]["obligationScopes"]
    value["qualityRegistry"]["obligationScopes"] = {"columns": list(_SCOPE_COLUMNS),
        "rows": [[scope[key] for key in _SCOPE_COLUMNS] for scope in scopes]}


def _files(value):
    shared = []
    for registry in (value, value["workScopeRegistry"]):
        indexes = []
        for file in registry["fileRegistry"]:
            row = [file["fileId"], file["materialCategory"]]
            if row not in shared:
                shared.append(row)
            indexes.append(shared.index(row))
        registry["fileRegistry"] = indexes
    value["sharedFileRegistry"] = {"columns": ["fileId", "materialCategory"], "rows": shared}


def _hashes(value):
    def encode(token):
        return base64.urlsafe_b64encode(bytes.fromhex(token)).decode("ascii").rstrip("=")
    evidence, work = value["evidenceCatalog"], value["workScopeRegistry"]
    evidence["columns"][5] = "exactTextSha256B64"
    for row in evidence["rows"]:
        row[5] = encode(row[5])
    work["unitColumns"][0] = "scopeHashB64"
    for unit in work["units"]:
        unit[0] = encode(unit[0])
    value["metadataPacking"]["hashEncoding"] = "sha256-base64url-no-pad"


def _ranges(value):
    work = value["workScopeRegistry"]
    for unit in work["units"]:
        ends = {}
        for row in unit[2]:
            file, start, end, citable, count = row
            row[:] = [file, start - ends.get(file, 0), end - start, int(citable), count]
            ends[file] = end
    work["rangeColumns"] = ["fileIndex", "startDelta", "length", "citableBit", "segmentCount"]
    value["metadataPacking"]["rangeEncoding"] = "per-unit-local-file-end-delta-v1"


def pack_synthesis_metadata(value: dict) -> dict:
    """First full candidate <=12000 chars, otherwise smallest full candidate.

    All sizes include marker/pool/table overhead. The caller retains the original
    hard guard. Unknown non-structural fields are copied unchanged, not dropped;
    incompatible known structures fail closed without logging their contents.
    """
    _validate(value)
    working = deepcopy(value)
    best, best_size = deepcopy(value), len(_json(value))
    if best_size <= MAX_SYNTHESIS_CHARS:
        return best
    for name, transform in (("enums", _enums), ("scopes", _scopes), ("files", _files), ("hashes", _hashes), ("ranges", _ranges)):
        transform(working)
        working["metadataPacking"]["stages"].append(name)
        size = len(_json(working))
        if size <= MAX_SYNTHESIS_CHARS:
            return working
        if size < best_size:
            best, best_size = deepcopy(working), size
    return best
