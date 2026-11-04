"""Local adapter exercise: eight Java observations + synthetic OCR DTO + real semantic segmentation.

This deliberately does not call Paddle or any model provider. Result labels cover the local
fixture/adapter/metric execution only, never provider quality or production eligibility.
"""
import argparse
import json
from pathlib import Path
import platform
import time
from audit_ai.ocr.port import OcrExtraction, OcrPage
from audit_ai.model.evidence import EvidenceSource, segment_evidence_sources
from .manifest import canonical_json, validate_fixture_set
from .harness import evaluate


def python_observations(fixtures):
    validate_fixture_set(fixtures)
    result = []
    for case in fixtures["cases"]:
        if case["capability"] not in {"OCR", "SEMANTIC"}: continue
        start = time.perf_counter_ns()
        observed = {"caseId": case["id"], "schemaValid": True, "bindingValid": True, "locatorHit": True,
                    "supported": True, "retries": 0, "failure": False, "timeout": False}
        if case["capability"] == "OCR":
            # Synthetic OcrPort output: verifies DTO/metric plumbing, not image recognition quality.
            page = OcrPage(1, case["input"], 1.0)
            extracted = OcrExtraction(page.text, 1.0, (page,), "synthetic-ocr-port", 0)
            observed["text"] = extracted.text
        else:
            catalog = segment_evidence_sources([EvidenceSource(case["id"], "CONTRACT_BODY", case["input"])], max_segment_chars=1000)
            grounded = len(catalog.segments) == 1 and catalog.segments[0].exact_text == case["input"] and catalog.segments[0].citable
            observed["claims"] = ["GROUNDED_SEGMENT"] if grounded else []
            observed["bindingValid"] = observed["locatorHit"] = grounded
        observed["durationMs"] = (time.perf_counter_ns() - start) // 1_000_000
        result.append(observed)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixtures", required=True, type=Path)
    parser.add_argument("--java-observations", required=True, type=Path)
    parser.add_argument("--code-sha", required=True)
    args = parser.parse_args()
    fixtures = json.loads(args.fixtures.read_text(encoding="utf-8"))
    observations = json.loads(args.java_observations.read_text(encoding="utf-8"))
    expected_java = {case["id"] for case in fixtures["cases"] if case["capability"] not in {"OCR", "SEMANTIC"}}
    if {item["caseId"] for item in observations} != expected_java:
        raise ValueError("Java observations must originate from the same fixture manifest")
    observations += python_observations(fixtures)
    context = {"codeSha": args.code_sha, "provider": "local-compiled-and-synthetic-adapters", "model": "none", "revision": "v1",
               "policyVersion": "synthetic-v1", "schemaVersion": "v1", "promptVersion": "none",
               "thresholdProfile": "synthetic-regression-v1", "executionMode": "LOCAL_SYNTHETIC",
               "command": "mvnw -Dtest=JavaQualityHarnessTest test; python -m audit_ai.quality.synthetic",
               "environment": "local-java17-python" + platform.python_version()}
    print(canonical_json(evaluate(fixtures, observations, context)))


if __name__ == "__main__": main()
