"""Versioned, bounded fixture governance shared by Java and Python evaluators."""
import hashlib
import json
import re

CAPABILITIES = frozenset({"PARSE", "OCR", "DOCUMENT_ROLE", "PROJECT_IDENTITY", "CONTRACT_CANDIDATE",
                          "F02", "SEMANTIC", "BREACH", "TERM", "EVIDENCE_GROUNDING"})
_FORBIDDEN = re.compile(r"BEGIN [A-Z ]*PRIVATE KEY|Bearer\s|sk-[A-Za-z0-9]{16,}|客户原件|保密|(?:file|https?)://|[A-Z]:\\", re.I)


def canonical_json(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def digest(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def validate_fixture_set(value):
    if not isinstance(value, dict) or set(value) != {"schemaVersion", "fixtureSet", "version", "groundTruthVersion", "source", "provenance", "cases"}:
        raise ValueError("invalid fixture manifest schema")
    if value["schemaVersion"] != 1 or any(not isinstance(value[k], str) or not re.fullmatch(r"v[1-9][0-9]*", value[k]) for k in ("version", "groundTruthVersion")):
        raise ValueError("fixture and ground truth versions must be immutable numbered versions")
    source, provenance = value["source"], value["provenance"]
    if source not in {"SYNTHETIC", "PUBLIC", "CUSTOMER_APPROVED_SANITIZED"} or not isinstance(provenance, dict):
        raise ValueError("fixture source not permitted")
    required = {"reviewer", "reason", "createdAt"}
    if source == "PUBLIC": required |= {"license", "sourceReference"}
    if source == "CUSTOMER_APPROVED_SANITIZED": required |= {"approvalIdentity", "redactionVersion"}
    if any(not isinstance(provenance.get(k), str) or not provenance[k].strip() for k in required):
        raise ValueError("fixture provenance is incomplete")
    if not isinstance(value["cases"], list) or not 1 <= len(value["cases"]) <= 10000:
        raise ValueError("fixture count outside bound")
    ids = set()
    for case in value["cases"]:
        if not isinstance(case, dict) or set(case) != {"id", "capability", "input", "inputSha256", "truth"}:
            raise ValueError("invalid fixture case schema")
        if not isinstance(case["id"], str) or not re.fullmatch(r"[a-z0-9-]{1,80}", case["id"]) or case["id"] in ids:
            raise ValueError("invalid or duplicate fixture id")
        ids.add(case["id"])
        if case["capability"] not in CAPABILITIES or not isinstance(case["input"], str) or len(case["input"]) > 100000:
            raise ValueError("invalid capability or input bound")
        if digest(case["input"]) != case["inputSha256"]:
            raise ValueError("fixture input digest mismatch")
        truth = case["truth"]
        if not isinstance(truth, dict) or truth.get("schemaVersion") != 1 or not set(truth) <= {"schemaVersion", "text", "label", "claims", "amount"}:
            raise ValueError("invalid ground truth schema")
        if not any(k in truth for k in ("text", "label", "claims", "amount")):
            raise ValueError("ground truth cannot be empty")
        for key in ("text", "label", "amount"):
            if key in truth and not isinstance(truth[key], str): raise ValueError("ground truth must use typed strings")
        if "claims" in truth and (not isinstance(truth["claims"], list) or any(not isinstance(c, str) for c in truth["claims"])):
            raise ValueError("invalid ground truth claims")
        codes = ([truth["label"]] if "label" in truth else []) + truth.get("claims", [])
        if any(not re.fullmatch(r"[A-Z0-9_:-]{1,80}", code) for code in codes):
            raise ValueError("only bounded ground truth codes are allowed")
        if _FORBIDDEN.search(canonical_json(case)):
            raise ValueError("confidential/credential fixture pattern rejected")
    return value
