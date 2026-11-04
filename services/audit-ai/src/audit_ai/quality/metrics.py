"""Versioned pure metrics; decimal strings are evaluation data, never monetary decisions."""
from collections import Counter
from decimal import Decimal, InvalidOperation
import math
import unicodedata

METRIC_VERSION = "contract-quality-metrics-v1"


def edit_distance(expected, actual):
    previous = list(range(len(actual) + 1))
    for index, token in enumerate(expected, 1):
        current = [index]
        for col, observed in enumerate(actual, 1):
            current.append(min(current[-1] + 1, previous[col] + 1, previous[col - 1] + (token != observed)))
        previous = current
    return previous[-1]


def ratio(numerator, denominator):
    return numerator / denominator if denominator else None


def normalized(value):
    return " ".join(unicodedata.normalize("NFKC", value).split())


def measure(cases, observations):
    counts = Counter({key: 0 for key in (
        "cases", "exactMatches", "normalizedMatches", "textCases", "characterEdits", "characters", "wordEdits", "words",
        "pages", "coveredPages", "emptyPages", "amountCases", "amountMatches", "classified", "correct",
        "conflicts", "missedConflicts", "nonOwned", "falseOwnership", "locatorHits", "bindingValid", "supported",
        "schemaValid", "retriedCases", "retries", "failures", "timeouts", "truePositive", "falsePositive", "falseNegative")})
    confusion, duration = {}, []
    for case, observed in zip(cases, observations, strict=True):
        truth = case["truth"]
        counts["cases"] += 1
        if "text" in truth:
            expected, actual = truth["text"], observed.get("text", "")
            counts["textCases"] += 1
            counts["exactMatches"] += expected == actual
            counts["normalizedMatches"] += normalized(expected) == normalized(actual)
            counts["characters"] += len(expected)
            counts["characterEdits"] += edit_distance(expected, actual)
            counts["words"] += len(expected.split())
            counts["wordEdits"] += edit_distance(expected.split(), actual.split())
            counts["pages"] += 1
            counts["coveredPages"] += bool(actual.strip())
            counts["emptyPages"] += not actual.strip()
        if "amount" in truth:
            counts["amountCases"] += 1
            try:
                expected, actual = Decimal(truth["amount"]), Decimal(observed.get("amount", "NaN"))
                counts["amountMatches"] += expected.is_finite() and actual.is_finite() and expected == actual
            except InvalidOperation:
                pass
        if "label" in truth:
            expected, actual = truth["label"], observed.get("label", "UNKNOWN")
            confusion.setdefault(expected, {})[actual] = confusion.setdefault(expected, {}).get(actual, 0) + 1
            counts["classified"] += 1
            counts["correct"] += expected == actual
            counts["conflicts"] += expected == "CONFLICT"
            counts["missedConflicts"] += expected == "CONFLICT" and actual != "CONFLICT"
            counts["nonOwned"] += expected != "SAME_PROJECT"
            counts["falseOwnership"] += expected != "SAME_PROJECT" and actual == "SAME_PROJECT"
        expected_claims, actual_claims = set(truth.get("claims", [])), set(observed.get("claims", []))
        counts["truePositive"] += len(expected_claims & actual_claims)
        counts["falsePositive"] += len(actual_claims - expected_claims)
        counts["falseNegative"] += len(expected_claims - actual_claims)
        for key in ("locatorHit", "bindingValid", "supported", "schemaValid", "failure", "timeout"):
            target = {"locatorHit": "locatorHits", "failure": "failures", "timeout": "timeouts"}.get(key, key)
            counts[target] += observed.get(key) is True
        retries = observed.get("retries", 0)
        counts["retries"] += retries
        counts["retriedCases"] += retries > 0
        duration.append(observed["durationMs"])
    labels = sorted(set(confusion) | {label for row in confusion.values() for label in row})
    classes = {}
    for label in labels:
        tp = confusion.get(label, {}).get(label, 0)
        predicted = sum(row.get(label, 0) for row in confusion.values())
        expected = sum(confusion.get(label, {}).values())
        classes[label] = {"precision": ratio(tp, predicted), "recall": ratio(tp, expected), "f1": ratio(2 * tp, predicted + expected)}
    count = counts["cases"]
    ordered = sorted(duration)
    return {"rawCounts": dict(sorted(counts.items())), "metrics": {
        "accuracy": ratio(counts["correct"], counts["classified"]),
        "macroF1": sum(v["f1"] or 0 for v in classes.values()) / len(classes) if classes else None,
        "confusionMatrix": confusion, "perClass": classes,
        "cer": ratio(counts["characterEdits"], counts["characters"]), "wer": ratio(counts["wordEdits"], counts["words"]),
        "pageCoverage": ratio(counts["coveredPages"], counts["pages"]), "emptyPageRate": ratio(counts["emptyPages"], counts["pages"]),
        "exactMatch": ratio(counts["exactMatches"], counts["textCases"]),
        "normalizedMatch": ratio(counts["normalizedMatches"], counts["textCases"]),
        "amountNumericMatch": ratio(counts["amountMatches"], counts["amountCases"]),
        "locatorHitRate": ratio(counts["locatorHits"], count), "hashParseBindingValidity": ratio(counts["bindingValid"], count),
        "unsupportedRate": ratio(count - counts["supported"], count), "schemaValidity": ratio(counts["schemaValid"], count),
        "retryRate": ratio(counts["retriedCases"], count),
        "precision": ratio(counts["truePositive"], counts["truePositive"] + counts["falsePositive"]),
        "recall": ratio(counts["truePositive"], counts["truePositive"] + counts["falseNegative"]),
        "conflictFalseNegativeRate": ratio(counts["missedConflicts"], counts["conflicts"]),
        "falseOwnershipRate": ratio(counts["falseOwnership"], counts["nonOwned"]),
        "p50Ms": ordered[max(0, math.ceil(len(ordered) * .5) - 1)] if ordered else None,
        "p95Ms": ordered[max(0, math.ceil(len(ordered) * .95) - 1)] if ordered else None,
    }}
