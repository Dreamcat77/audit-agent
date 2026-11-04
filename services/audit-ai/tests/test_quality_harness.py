from copy import deepcopy
import hashlib
import json
from pathlib import Path
import pytest
from audit_ai.quality.manifest import validate_fixture_set, canonical_json
from audit_ai.quality.metrics import edit_distance, measure
from audit_ai.quality.harness import evaluate


def fixture(capability="OCR"):
    return {"schemaVersion": 1, "fixtureSet": "synthetic-core", "version": "v1",
            "groundTruthVersion": "v1", "source": "SYNTHETIC",
            "provenance": {"reviewer": "repository-tests", "reason": "synthetic baseline", "createdAt": "2026-09-16"},
            "cases": [{"id": "ocr-1", "capability": capability, "input": "abc",
                       "inputSha256": hashlib.sha256(b"abc").hexdigest(),
                       "truth": {"schemaVersion": 1, "text": "abc", "label": "MATCH", "claims": ["CLAUSE"], "amount": "100.00"}}]}


def observations():
    return [{"caseId": "ocr-1", "text": "adc", "label": "MATCH", "claims": ["CLAUSE"], "amount": "100.0",
             "durationMs": 10, "schemaValid": True, "bindingValid": True, "locatorHit": True,
             "supported": True, "retries": 0, "failure": False, "timeout": False}]


def context(provider="synthetic-a"):
    return {"codeSha": "a" * 40, "provider": provider, "model": "synthetic-model", "revision": "v1",
            "policyVersion": "synthetic-v1", "schemaVersion": "v1", "promptVersion": "none",
            "thresholdProfile": "synthetic-regression-v1", "executionMode": "LOCAL_SYNTHETIC",
            "command": "python -m audit_ai.quality", "environment": "local-unit"}


def test_distance_and_nonperfect_metrics_have_hand_checked_counts():
    assert edit_distance("abc", "adc") == 1
    assert edit_distance("", "ab") == 2
    result = measure(fixture()["cases"], observations())
    assert result["rawCounts"]["characterEdits"] == 1
    assert result["metrics"]["cer"] == pytest.approx(1 / 3)
    assert result["metrics"]["wer"] == 1
    assert result["metrics"]["amountNumericMatch"] == 1
    assert result["metrics"]["p95Ms"] == 10


def test_confusion_and_conflict_false_negative_not_hidden_by_accuracy():
    cases = [deepcopy(fixture("PROJECT_IDENTITY")["cases"][0]) for _ in range(2)]
    cases[0]["truth"]["label"] = "CONFLICT"
    cases[1]["truth"]["label"] = "SAME_PROJECT"
    observed = observations() * 2
    observed = deepcopy(observed)
    observed[0]["label"] = observed[1]["label"] = "SAME_PROJECT"
    result = measure(cases, observed)
    assert result["metrics"]["accuracy"] == 0.5
    assert result["metrics"]["conflictFalseNegativeRate"] == 1
    assert result["metrics"]["falseOwnershipRate"] == 1
    assert result["metrics"]["macroF1"] == pytest.approx(1 / 3)


@pytest.mark.parametrize("change", [
    {"source": "CUSTOMER_ORIGINAL"}, {"source": "PUBLIC"},
    {"source": "CUSTOMER_APPROVED_SANITIZED"}, {"version": ""}, {"groundTruthVersion": "latest"},
])
def test_fixture_provenance_and_versions_fail_closed(change):
    value = fixture()
    value.update(change)
    with pytest.raises(ValueError):
        validate_fixture_set(value)


def test_ground_truth_and_hash_tamper_are_rejected():
    value = fixture()
    value["cases"][0]["truth"]["schemaVersion"] = 2
    with pytest.raises(ValueError): validate_fixture_set(value)
    value = fixture()
    value["cases"][0]["input"] = "changed"
    with pytest.raises(ValueError): validate_fixture_set(value)


def test_customer_confidential_and_credential_patterns_rejected():
    for text in ["-----BEGIN PRIVATE KEY-----", "Bearer synthetic-secret-material", "客户原件", "保密合同"]:
        value = fixture()
        value["cases"][0]["input"] = text
        value["cases"][0]["inputSha256"] = hashlib.sha256(text.encode()).hexdigest()
        with pytest.raises(ValueError): validate_fixture_set(value)


def test_result_manifest_is_canonical_comparable_and_cannot_promote_labels():
    left = evaluate(fixture(), observations(), context())
    right = evaluate(fixture(), observations(), context("synthetic-b"))
    assert left["comparisonIdentity"] == right["comparisonIdentity"]
    assert left["verificationLabels"] == ["LOCAL_SYNTHETIC_VERIFIED"]
    assert left["acceptanceStatus"] == "MEASURED_NOT_ACCEPTED"
    assert left["results"][0]["passed"] is False  # CER=1/3 is not perfect synthetic regression.
    assert canonical_json(left) == canonical_json(dict(reversed(list(left.items()))))
    assert "abc" not in canonical_json(left)  # No fixture/document body in result artifact.
    bad = context()
    bad["verificationLabels"] = ["REAL_PROVIDER_VERIFIED"]
    with pytest.raises(ValueError): evaluate(fixture(), observations(), bad)
    bad = context()
    bad["executionMode"] = "REAL_CUSTOMER_MATERIAL"
    with pytest.raises(ValueError): evaluate(fixture(), observations(), bad)


def test_missing_duplicate_or_foreign_observations_cannot_pass():
    with pytest.raises(ValueError): evaluate(fixture(), [], context())
    with pytest.raises(ValueError): evaluate(fixture(), observations() * 2, context())
    bad = observations()
    bad[0]["caseId"] = "foreign"
    with pytest.raises(ValueError): evaluate(fixture(), bad, context())


def test_repository_fixture_manifest_covers_named_domains_with_synthetic_source():
    root = Path(__file__).resolve().parents[3]
    value = json.loads((root / "quality/fixtures/synthetic-v1.json").read_text(encoding="utf-8"))
    validate_fixture_set(value)
    assert value["source"] == "SYNTHETIC"
    assert {case["capability"] for case in value["cases"]} == {
        "PARSE", "OCR", "DOCUMENT_ROLE", "PROJECT_IDENTITY", "CONTRACT_CANDIDATE", "F02",
        "SEMANTIC", "BREACH", "TERM", "EVIDENCE_GROUNDING"}


def test_python_synthetic_adapters_execute_without_claiming_real_ocr_or_model():
    from audit_ai.quality.synthetic import python_observations
    root = Path(__file__).resolve().parents[3]
    value = json.loads((root / "quality/fixtures/synthetic-v1.json").read_text(encoding="utf-8"))
    observed = python_observations(value)
    assert {item["caseId"] for item in observed} == {"ocr-1", "semantic-1"}
    assert next(item for item in observed if item["caseId"] == "ocr-1")["text"] == "synthetic ocr"
    assert next(item for item in observed if item["caseId"] == "semantic-1")["claims"] == ["GROUNDED_SEGMENT"]


def test_provider_error_text_cannot_become_result_labels():
    bad = observations()
    bad[0]["label"] = "private customer clause and address"
    with pytest.raises(ValueError): evaluate(fixture(), bad, context())


def test_public_observations_are_measured_without_execution_verification():
    public = fixture()
    public["source"] = "PUBLIC"
    public["provenance"].update(license="CC0", sourceReference="public-fixture-reference")
    offline = context()
    offline["executionMode"] = "OFFLINE_OBSERVATIONS"
    result = evaluate(public, observations(), offline)
    assert result["verificationLabels"] == []
    assert result["acceptanceStatus"] == "MEASURED_NOT_ACCEPTED"


def test_truth_free_text_cannot_become_metric_label():
    invalid = fixture()
    invalid["cases"][0]["truth"]["label"] = "unbounded private customer statement"
    with pytest.raises(ValueError): validate_fixture_set(invalid)


def test_empty_rates_are_unknown_and_unsupported_retries_and_p95_remain_visible():
    cases = [deepcopy(fixture()["cases"][0]) for _ in range(3)]
    observed = [deepcopy(observations()[0]) for _ in range(3)]
    for i, row in enumerate(observed): row["durationMs"] = [2, 10, 100][i]
    observed[0]["supported"] = False
    observed[1]["retries"] = 2
    observed[2]["timeout"] = True
    result = measure(cases, observed)
    assert result["metrics"]["unsupportedRate"] == pytest.approx(1/3)
    assert result["metrics"]["retryRate"] == pytest.approx(1/3)
    assert result["metrics"]["p50Ms"] == 10
    assert result["metrics"]["p95Ms"] == 100
    assert result["rawCounts"]["timeouts"] == 1
    assert result["metrics"]["conflictFalseNegativeRate"] is None
