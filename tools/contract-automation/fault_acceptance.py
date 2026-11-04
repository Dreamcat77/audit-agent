"""Isolated HTTP/DM8 fault injection; never claims a real model success."""
import json
from pathlib import Path
import sys
import requests

root = Path(__file__).resolve().parents[2]
token = (root / ".test-runtime/contract-acceptance/control-token").read_text(encoding="utf-8")
evidence = root / "docs/contract-automation/evidence"
spec = next(s for s in json.loads((root / ".test-runtime/contract-fixtures/golden.json").read_text(encoding="utf-8")) if s["id"] == "normal-service")

def command(action, **kwargs):
    r = requests.post("http://127.0.0.1:19147/command", headers={"Authorization":"Bearer " + token},
                      json={"action":action, **kwargs}, timeout=2200)
    r.raise_for_status()
    result = r.json()
    if not result.get("completed"):
        raise RuntimeError(result.get("reason", "coordinator failed"))

results = []
try:
    for mode, expected in (("unavailable", "FAILED"), ("unknown", "UNKNOWN"), ("malformed", "FAILED"), ("empty", "DATA_INSUFFICIENT")):
        command("backend", mode=mode)
        label = "fault-" + mode
        command("upload", paths=spec["paths"], label=label)
        receipt = json.loads((evidence / (label + "-receipt.json")).read_text(encoding="utf-8"))["receipt"]
        command("watch", caseId=receipt["caseId"], label=label)
        case = json.loads((evidence / (label + "-case.json")).read_text(encoding="utf-8"))
        report = json.loads((evidence / (label + "-report.json")).read_text(encoding="utf-8"))
        calls = [s for s in case["steps"] if s["kind"] in {"FILE", "FRAGMENT", "FOCUSED", "CROSS_FILE"}]
        failures = []
        if len(calls) != 1 or calls[0]["status"] != expected: failures.append("WRONG_FAILURE_CLASS_OR_RETRY")
        if not any(d["facts"] for d in report["documents"]): failures.append("VALID_RECALL_DISCARDED")
        if case["status"] != "PARTIAL": failures.append("WRONG_PARTIAL_RESULT_STATE")
        if any(s["kind"] == "FOCUSED" for s in calls): failures.append("FAILED_CALL_RETRIED_AS_FOCUS")
        if any(f["origin"] == "AI_REVIEW_SUGGESTION" for f in report["findings"]): failures.append("FABRICATED_MODEL_FINDING")
        before = case
        command("restart", mode=mode)
        command("call", path="/api/v1/contract-analysis-cases/" + case["id"], output=label + "-reopened.json")
        reopened = json.loads((evidence / (label + "-reopened.json")).read_text(encoding="utf-8"))
        if reopened != before: failures.append("TERMINAL_CASE_CHANGED_AFTER_RESTART")
        result = {"mode":mode,"caseId":case["id"],"state":case["status"],"modelStep":calls[0]["status"] if calls else None,
                  "facts":sum(len(d["facts"]) for d in report["documents"]),"realModel":False,"faultInjection":True,
                  "realDm8":True,"restartPreserved":reopened == before,"failures":failures}
        results.append(result)
        print(json.dumps(result), flush=True)
finally:
    command("backend", mode="actual")
    (evidence / "p5-fault-injection-verification.json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
sys.exit(1 if any(r["failures"] for r in results) else 0)
