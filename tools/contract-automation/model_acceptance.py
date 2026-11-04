"""Real provider checks with pre-labelled synthetic facts, before database/browser acceptance."""
import json
from pathlib import Path
import re
import sys
import time
import uuid
import requests
from fixtures import MARK, specifications

ROOT = Path(__file__).resolve().parents[2]
results = []
selected = set(sys.argv[1:])
for spec in specifications():
    if selected and spec["id"] not in selected:
        continue
    name, body, _ = spec["files"][0]
    source = MARK + "\n" + body
    if spec["files"][0][2]:
        # The direct model probe must include the same physical rows as the DOCX fixture.
        source = source.replace("表内已列全部货物及费用，无其他收费。", "表内已列全部货物及费用，无其他收费。\n设备名称\t数量\t含税单价（元）\t金额（元）\n办公计算机\t20\t5000.00\t100000.00\n办公打印机\t10\t2000.00\t20000.00")
    document = str(uuid.uuid4())
    started = time.monotonic()
    response = requests.post("http://127.0.0.1:19143/ai/v1/contracts/analyze-case", json={
        "caseId": str(uuid.uuid4()), "inputDigest": "a" * 64, "scope": "FILE", "documents": [{"id": document, "text": source}]}, timeout=175)
    parsed = response.json()
    failures = []
    if response.status_code != 200:
        failures.append("HTTP_" + str(response.status_code))
    else:
        if spec.get("profile") and parsed["profile"] != spec["profile"]:
            failures.append("PROFILE_MISMATCH")
        if len(parsed["clauses"]) < spec.get("clausesMin", 1):
            failures.append("MISSING_EXPECTED_CLAUSE_COVERAGE")
        if spec.get("expectedRisks") == [] and any(f["level"]=="WARNING" and f.get("basis","CLAUSE_ISSUE")=="CLAUSE_ISSUE" for f in parsed["findings"]):
            failures.append("UNEXPECTED_RISK_ON_NORMAL_CONTRACT")
        for fact in parsed["facts"]:
            if fact["type"] in spec.get("noFacts", []):
                failures.append("INVENTED_MISSING_" + fact["type"])
        compact = lambda text: re.sub(r"\s+", "", text)
        for item in parsed["facts"] + parsed["clauses"] + parsed["findings"]:
            for citation in item["citations"]:
                if citation["document"] != document or compact(citation["text"]) not in compact(source):
                    failures.append("UNSUPPORTED_CITATION")
    result = {"scenario": spec["id"], "http": response.status_code, "seconds": round(time.monotonic() - started, 2),
              "realModel": True, "synthetic": True, "failures": sorted(set(failures)), "result": parsed}
    results.append(result)
    (ROOT / ("docs/contract-automation/evidence/model-" + spec["id"] + ".json")).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: v for k, v in result.items() if k != "result"}, ensure_ascii=False), flush=True)
print("REAL_MODEL_CASES=" + str(len(results)) + " FAILED=" + str(sum(bool(v["failures"]) for v in results)))
sys.exit(1 if any(v["failures"] for v in results) else 0)
