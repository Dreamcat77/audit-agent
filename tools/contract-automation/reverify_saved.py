"""Fresh independent verification of a saved synthetic case and canonical evidence.

Uses the loopback acceptance coordinator without exposing its credentials. This
does not upload another case or invoke the model. Source text stays in the
ignored test-runtime directory and is removed after each verification.
"""
import json
from pathlib import Path
import sys
import requests
from verify_report import verify

root = Path(__file__).resolve().parents[2]
evidence = root / "docs/contract-automation/evidence"
private = root / ".test-runtime/contract-acceptance"
token = (private / "control-token").read_text(encoding="utf-8")
specs = json.loads((root / ".test-runtime/contract-fixtures/golden.json").read_text(encoding="utf-8"))
results = []
for scenario in sys.argv[1:]:
    spec = next(s for s in specs if s["id"] == scenario)
    case = json.loads((evidence / ("e2e-" + scenario + "-case.json")).read_text(encoding="utf-8"))
    report = json.loads((evidence / ("e2e-" + scenario + "-report.json")).read_text(encoding="utf-8"))
    texts = {}
    for source in case["sources"]:
        if source["status"] != "READY":
            continue
        target = private / ("reverify-" + source["fileId"] + ".json")
        try:
            response = requests.post("http://127.0.0.1:19147/command",
                headers={"Authorization": "Bearer " + token}, timeout=180,
                json={"action": "call", "path": f"/api/v1/contract-analysis-cases/{case['id']}/sources/{source['fileId']}/text",
                      "output": str(target)})
            response.raise_for_status()
            if not response.json().get("completed"):
                raise RuntimeError("canonical source re-read failed")
            texts[source["fileId"]] = json.loads(target.read_text(encoding="utf-8"))["text"]
        finally:
            target.unlink(missing_ok=True)
    result = verify(spec, case, report, texts)
    result["freshVerifier"] = True
    (evidence / ("e2e-" + scenario + "-verification.json")).write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    results.append(result)
    print(json.dumps(result, ensure_ascii=False), flush=True)
sys.exit(1 if any(result["failures"] for result in results) else 0)
