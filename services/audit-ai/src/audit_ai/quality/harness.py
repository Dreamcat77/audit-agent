"""Evaluate observed capability outputs using one stable comparison/result manifest."""
import math
import re
from .manifest import canonical_json, digest, validate_fixture_set
from .metrics import METRIC_VERSION, measure

_CONTEXT = {"codeSha", "provider", "model", "revision", "policyVersion", "schemaVersion", "promptVersion",
            "thresholdProfile", "executionMode", "command", "environment"}
_OBSERVATION = {"caseId", "text", "label", "claims", "amount", "durationMs", "schemaValid", "bindingValid",
                "locatorHit", "supported", "retries", "failure", "timeout"}


def evaluate(fixtures, observations, context):
    validate_fixture_set(fixtures)
    if set(context) != _CONTEXT or any(not isinstance(v, str) or not v.strip() or len(v) > 300 for v in context.values()):
        raise ValueError("invalid evaluation context")
    if not re.fullmatch(r"[a-f0-9]{40}", context["codeSha"]): raise ValueError("exact code SHA required")
    local = context["executionMode"] == "LOCAL_SYNTHETIC" and fixtures["source"] == "SYNTHETIC"
    if not local and context["executionMode"] != "OFFLINE_OBSERVATIONS":
        raise ValueError("real provider/customer verification is not available in this local runner")
    if context["thresholdProfile"] != "synthetic-regression-v1":
        raise ValueError("unknown threshold profile")
    by_id = {}
    for observed in observations:
        if not isinstance(observed, dict) or not set(observed) <= _OBSERVATION or observed.get("caseId") in by_id:
            raise ValueError("invalid or duplicate observation")
        if any(type(observed.get(k)) is not bool for k in ("schemaValid", "bindingValid", "locatorHit", "supported", "failure", "timeout")):
            raise ValueError("observation evidence flags required")
        if type(observed.get("retries")) is not int or not 0 <= observed["retries"] <= 100:
            raise ValueError("retry count invalid")
        if type(observed.get("durationMs")) not in (float, int) or not math.isfinite(observed["durationMs"]) or not 0 <= observed["durationMs"] <= 3600000:
            raise ValueError("duration invalid")
        for key in ("text", "label", "amount"):
            if key in observed and (not isinstance(observed[key], str) or len(observed[key]) > 100000): raise ValueError("invalid typed output")
        if "claims" in observed and (not isinstance(observed["claims"], list) or any(not isinstance(c, str) for c in observed["claims"])):
            raise ValueError("invalid typed claims")
        codes = ([observed["label"]] if "label" in observed else []) + observed.get("claims", [])
        if any(not re.fullmatch(r"[A-Z0-9_:-]{1,80}", code) for code in codes):
            raise ValueError("only bounded observation codes are allowed")
        by_id[observed["caseId"]] = observed
    if set(by_id) != {case["id"] for case in fixtures["cases"]}: raise ValueError("observation set does not match fixture set")
    comparison = {key: context[key] for key in ("policyVersion", "schemaVersion", "promptVersion", "thresholdProfile")}
    comparison.update(fixtureSet=fixtures["fixtureSet"], fixtureVersion=fixtures["version"], groundTruthVersion=fixtures["groundTruthVersion"],
                      fixtureManifestSha256=digest(canonical_json(fixtures)), metricImplementationVersion=METRIC_VERSION)
    results = []
    for capability in sorted({case["capability"] for case in fixtures["cases"]}):
        cases = sorted((case for case in fixtures["cases"] if case["capability"] == capability), key=lambda case: case["id"])
        metrics = measure(cases, [by_id[case["id"]] for case in cases])
        counts, rates = metrics["rawCounts"], metrics["metrics"]
        passed = (counts["failures"] == counts["timeouts"] == counts["falsePositive"] == counts["falseNegative"] == 0
                  and counts["correct"] == counts["classified"] and counts["characterEdits"] == 0
                  and counts["amountMatches"] == counts["amountCases"] and counts["schemaValid"] == counts["cases"]
                  and counts["supported"] == counts["cases"] and counts["bindingValid"] == counts["cases"])
        results.append({"capability": capability, **metrics, "passed": passed})
    return {"resultSchemaVersion": 1, **comparison, "comparisonIdentity": digest(canonical_json(comparison)),
            "codeSha": context["codeSha"], "provider": context["provider"], "model": context["model"], "revision": context["revision"],
            "results": results, "passed": all(r["passed"] for r in results), "acceptanceStatus": "MEASURED_NOT_ACCEPTED",
            "verificationLabels": ["LOCAL_SYNTHETIC_VERIFIED"] if local else [],
            "verificationScope": "LOCAL_METRIC_EVALUATION_OF_SYNTHETIC_OBSERVATIONS" if local else "OFFLINE_NO_EXECUTION_ATTESTATION",
            "executionEvidence": {key: context[key] for key in ("command", "environment", "executionMode")}}
