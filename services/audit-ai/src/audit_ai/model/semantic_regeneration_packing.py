"""Lossless readable tables for two private regeneration candidate arrays only.

Unknown fields and heterogeneous/non-object arrays remain ordinary JSON. This
codec never validates or repairs a provider's candidate or drops an observation.
"""
from __future__ import annotations

import copy
import itertools
import json

PROTOCOL = "contract-regeneration-candidate-columns-v1"
FULL = "FULL"
_COLUMNS = {
    "hints": frozenset({"category", "attention", "title", "description", "problemEvidenceIds",
        "comparisonEvidenceIds", "unavailableMaterialCategory", "claimKind", "obligationScope"}),
    "resolutions": frozenset({"observation", "disposition", "finalHintIndexes", "basisEvidenceIds", "reason"}),
}


def _json(value):
    # JSON primitive types are part of the identity: tuples/Decimal/etc cannot
    # be silently converted into a different candidate type by a round-trip.
    def check(item):
        if type(item) is dict:
            if any(type(key) is not str for key in item):
                raise ValueError("candidate object keys must be strings")
            for child in item.values(): check(child)
        elif type(item) is list:
            for child in item: check(child)
        elif item is not None and type(item) not in (str, bool, int, float):
            raise ValueError("candidate must contain JSON values")
    check(value)
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")


def pack_candidate(candidate):
    """Return (encoding, payload), with no mutation and deterministic columns."""
    original = _json(candidate)
    if type(candidate) is not dict:
        return FULL, copy.deepcopy(candidate)
    eligible = {}
    for name, allowed in _COLUMNS.items():
        rows = candidate.get(name)
        if type(rows) is not list or not rows or any(type(row) is not dict for row in rows):
            continue
        columns = sorted(rows[0])
        if (not set(columns).issubset(allowed)
                or any(set(row) != set(columns) for row in rows)):
            continue
        eligible[name] = {"columns": columns,
            "rows": [[copy.deepcopy(row[column]) for column in columns] for row in rows]}
    best, best_size = None, len(original)
    # At most three complete representations, not a generic JSON compressor.
    for count in range(1, len(eligible) + 1):
        for names in itertools.combinations(eligible, count):
            value = {"root": {key: copy.deepcopy(item) for key, item in candidate.items() if key not in names},
                     "tables": {name: eligible[name] for name in names}}
            size = len(_json(value))
            if size < best_size:
                best, best_size = value, size
    return (FULL, copy.deepcopy(candidate)) if best is None else (PROTOCOL, best)


def unpack_candidate(encoding, payload):
    """Strictly restore fields, presence, original values/types and list order."""
    _json(payload)
    if encoding == HIERARCHY_PROTOCOL:
        return _unpack_hierarchy_candidate(payload)
    if encoding == FULL:
        return copy.deepcopy(payload)
    if (encoding != PROTOCOL or type(payload) is not dict or set(payload) != {"root", "tables"}
            or type(payload["root"]) is not dict or type(payload["tables"]) is not dict
            or not payload["tables"] or not set(payload["tables"]).issubset(_COLUMNS)):
        raise ValueError("invalid candidate table envelope")
    result = copy.deepcopy(payload["root"])
    for name, table in payload["tables"].items():
        if name in result or type(table) is not dict or set(table) != {"columns", "rows"}:
            raise ValueError("candidate table overlaps original fields")
        columns, rows = table["columns"], table["rows"]
        if (type(columns) is not list or any(type(column) is not str for column in columns)
                or len(set(columns)) != len(columns) or not set(columns).issubset(_COLUMNS[name])
                or type(rows) is not list
                or any(type(row) is not list or len(row) != len(columns) for row in rows)):
            raise ValueError("invalid candidate table columns or rows")
        result[name] = [dict(zip(columns, copy.deepcopy(row), strict=True)) for row in rows]
    return result


# Opt-in only: the original FULL/columns-v1 selector above is unchanged.
HIERARCHY_PROTOCOL = "contract-regeneration-hierarchy-columns-v1"
_POOL_COLUMNS = {
    "hints": frozenset({"category", "attention", "claimKind", "unavailableMaterialCategory",
        "obligationScope", "problemEvidenceIds", "comparisonEvidenceIds"}),
    "resolutions": frozenset({"disposition", "basisEvidenceIds"}),
}
_SCOPE_COLUMNS = frozenset({"topic", "responsibleParty", "trigger"})
_MAX_METADATA_ROWS = 1024


def _scope_pool(values):
    if (values and all(type(value) is dict for value in values)
            and set(values[0]).issubset(_SCOPE_COLUMNS)
            and all(set(value) == set(values[0]) for value in values)):
        columns = sorted(values[0])
        table = {"columns": columns, "rows": [[value[key] for key in columns] for value in values]}
        if len(_json(table)) < len(_json(values)):
            return table
    return values


def pack_hierarchy_candidate(candidate):
    """Opt-in readable metadata pools; never alter prose or validate a claim.

    A pools key means every cell in that named table column is a zero-based
    integer into that pool. Scope values may themselves be a readable table.
    JSON type-sensitive identity preserves invalid values, null and list order.
    The caller still owns the complete 8192-byte envelope bound and candidate
    SHA verification; returning a representation is not a capacity assertion.
    """
    original = _json(candidate)
    fallback = pack_candidate(candidate)
    if type(candidate) is not dict:
        return fallback
    tables = {}
    for name, allowed in _COLUMNS.items():
        rows = candidate.get(name)
        if (type(rows) is not list or not rows or len(rows) > _MAX_METADATA_ROWS
                or any(type(row) is not dict for row in rows)):
            continue
        columns = sorted(rows[0])
        if (not set(columns).issubset(allowed)
                or any(set(row) != set(columns) for row in rows)):
            continue
        tables[name] = {"columns": columns,
            "rows": [[copy.deepcopy(row[column]) for column in columns] for row in rows]}
    packed = {"root": {key: copy.deepcopy(value) for key, value in candidate.items() if key not in tables},
              "tables": tables, "pools": {}}
    for name, table in tables.items():
        for column in sorted(_POOL_COLUMNS[name].intersection(table["columns"])):
            position = table["columns"].index(column)
            values, indexes, seen = [], [], {}
            for row in table["rows"]:
                identity = _json(row[position])
                if identity not in seen:
                    seen[identity] = len(values)
                    values.append(copy.deepcopy(row[position]))
                indexes.append(seen[identity])
            pool = _scope_pool(values) if column == "obligationScope" else values
            trial = copy.deepcopy(packed)
            trial["pools"][column] = pool
            for row, index in zip(trial["tables"][name]["rows"], indexes, strict=True):
                row[position] = index
            if len(_json(trial)) < len(_json(packed)):
                packed = trial
                table = packed["tables"][name]
    if not packed["pools"] or len(_json(packed)) >= len(_json(fallback[1])):
        return fallback
    # Local invariant, not a business validator or a substitute for envelope hash.
    if _json(_unpack_hierarchy_candidate(packed)) != original:
        raise ValueError("invalid hierarchy metadata identity")
    return HIERARCHY_PROTOCOL, packed


def _unpack_hierarchy_candidate(payload):
    def invalid():
        raise ValueError("invalid hierarchy metadata")
    if (type(payload) is not dict or set(payload) != {"root", "tables", "pools"}
            or type(payload["pools"]) is not dict or not payload["pools"]):
        invalid()
    # Reuse the strict original columns grammar, including root overlap checks.
    result = unpack_candidate(PROTOCOL, {"root": payload["root"], "tables": payload["tables"]})
    for name, table in payload["tables"].items():
        if len(table["rows"]) > _MAX_METADATA_ROWS:
            invalid()
    for column, pool in payload["pools"].items():
        owners = [name for name, allowed in _POOL_COLUMNS.items() if column in allowed]
        if len(owners) != 1 or owners[0] not in payload["tables"]:
            invalid()
        name = owners[0]
        if column not in payload["tables"][name]["columns"]:
            invalid()
        if type(pool) is dict:
            if (column != "obligationScope" or set(pool) != {"columns", "rows"}
                    or type(pool["columns"]) is not list
                    or any(type(key) is not str for key in pool["columns"])
                    or len(set(pool["columns"])) != len(pool["columns"])
                    or not set(pool["columns"]).issubset(_SCOPE_COLUMNS)
                    or type(pool["rows"]) is not list
                    or any(type(row) is not list or len(row) != len(pool["columns"]) for row in pool["rows"])):
                invalid()
            values = [dict(zip(pool["columns"], copy.deepcopy(row), strict=True)) for row in pool["rows"]]
        elif type(pool) is list:
            values = copy.deepcopy(pool)
        else:
            invalid()
        if (not values or len(values) > _MAX_METADATA_ROWS
                or len({_json(value) for value in values}) != len(values)):
            invalid()
        used = set()
        for row in result[name]:
            index = row[column]
            if type(index) is not int or not 0 <= index < len(values):
                invalid()
            used.add(index)
            row[column] = copy.deepcopy(values[index])
        if used != set(range(len(values))):
            invalid()
    return result
