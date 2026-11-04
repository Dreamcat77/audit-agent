"""Real HTTP/model/DM8 acceptance. Reads only synthetic fixtures and private generated test users."""
import argparse
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import sys
import time
import uuid
import requests
from verify_report import verify as verify_contract

BACKUP = Path("/home/admin/audit-agent-backups/qa26-stage2-20261027")
FIXTURES = BACKUP / "fixtures"
RESULTS = BACKUP / "results"
RESULTS.mkdir(exist_ok=True)
BASE = "http://127.0.0.1:18082"
ACCESS = json.loads((BACKUP / "qa26-access-private.json").read_text())
GOLDEN = json.loads((FIXTURES / "golden.json").read_text())
assert GOLDEN["synthetic"] is True
RUN_REVISION = ""

def scenario_label(module, scenario_id):
    return module + "-" + scenario_id + ("-" + RUN_REVISION if RUN_REVISION else "")

def save(name, value):
    temp = RESULTS / (name + ".tmp")
    temp.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
    temp.replace(RESULTS / (name + ".json"))

def fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False).encode()).hexdigest()

class Client:
    def __init__(self, account):
        self.session = requests.Session()
        self.session.trust_env = False
        self.csrf()
        response = self.session.post(BASE + "/api/v1/auth/login", json={"username": account["username"], "password": account["password"]}, timeout=30)
        assert response.status_code == 200, "LOGIN_HTTP_" + str(response.status_code)
        self.csrf()
    def csrf(self):
        response = self.session.get(BASE + "/api/v1/auth/csrf", timeout=20)
        response.raise_for_status()
        token = response.json()["data"]
        self.session.headers[token["headerName"]] = token["token"]
    def call(self, method, path, **kwargs):
        response = self.session.request(method, BASE + path, timeout=190, **kwargs)
        # 202 means intake accepted. Every caller still polls the durable job
        # and validates terminal business results; acceptance is not success.
        if response.status_code not in {200, 202}:
            try:
                body = response.json()
                code = str(body.get("code", "MISSING"))
            except ValueError:
                code = "NON_JSON"
            raise AssertionError("HTTP_" + str(response.status_code) + "_" + path + "_CODE_" + code)
        return response.json()["data"]

CLIENT = Client(ACCESS["primary"])
OTHER = Client(ACCESS["secondary"])

def identity(label, initial):
    path = RESULTS / (label + "-identity.json")
    if not path.exists():
        save(label + "-identity", initial)
    return json.loads(path.read_text())

def budget(spec):
    label = scenario_label("budget", spec["id"])
    files = [FIXTURES / p for p in spec["paths"]]
    state = identity(label, {"batchId": str(uuid.uuid4()), "members": [{"operationKey": str(uuid.uuid4()), "fileName": p.name, "fileSize": p.stat().st_size} for p in files]})
    batch = state["batchId"]
    body = {"batchId": batch, "members": state["members"]}
    CLIENT.call("POST", "/api/v1/budget/material-uploads/batches", json=body)
    CLIENT.call("POST", "/api/v1/budget/material-uploads/batches", json=body)
    jobs = []
    for member, path in zip(state["members"], files):
        old = CLIENT.call("GET", "/api/v1/budget/material-uploads/operations/" + member["operationKey"])
        if old:
            jobs.append(old)
            continue
        with path.open("rb") as stream:
            jobs.append(CLIENT.call("POST", "/api/v1/budget/material-uploads", files={"file": (path.name, stream, "application/octet-stream")},
                                    data={"batchId": batch}, headers={"Idempotency-Key": member["operationKey"]}))
    deadline = time.monotonic() + 1800
    last = None
    while True:
        jobs = [CLIENT.call("GET", "/api/v1/budget/material-uploads/" + job["id"]) for job in jobs]
        reviews = [CLIENT.call("GET", "/api/v1/budget/reviews/" + job["id"]) for job in jobs]
        status = [(j["status"], (r.get("materialReference") or {}).get("status")) for j, r in zip(jobs, reviews)]
        if status != last:
            print(json.dumps({"label": label, "progress": status}), flush=True)
            last = status
        if all(j["status"] in {"PARSED", "FAILED", "UNKNOWN"} for j in jobs) and all((r.get("materialReference") or {}).get("status") == "COMPLETED" for r in reviews):
            break
        assert time.monotonic() < deadline, "BUDGET_TERMINAL_DEADLINE"
        time.sleep(3)
    save(label + "-raw", {"jobs": jobs, "reviews": reviews})
    lines = [line for review in reviews for line in review["materialReference"]["lines"]]
    e = spec["expected"]
    # A bad attachment makes overall coverage partial, even when the readable
    # subset is below approval. Never demand a false all-clear to pass the test.
    expected_status = "INSUFFICIENT" if e.get("partial") else e["status"]
    failures = []
    matches = [line for line in lines if line["status"] == expected_status and str(line.get("declaredAmount")) != "None"
               and str(line.get("approvalAmount")) != "None" and Decimal(str(line["declaredAmount"])) == Decimal(e["declared"])
               and Decimal(str(line["approvalAmount"])) == Decimal(e["approval"])]
    if not matches:
        failures.append("EXPECTED_BUDGET_FACTS_OR_STATUS_MISSING")
    if e.get("missingApproval") and not any(line["declaredAmount"] is None and line["approvalAmount"] is not None
                                             and Decimal(str(line["approvalAmount"])) == Decimal(e["missingApproval"]) for line in lines):
        failures.append("MISSING_APPROVAL_ITEM_NOT_SHOWN")
    if e.get("partial"):
        if not any(j["status"] == "FAILED" for j in jobs) or not any(j["status"] == "PARSED" for j in jobs):
            failures.append("PARTIAL_FILE_FAILURE_NOT_ISOLATED")
    elif any(j["status"] != "PARSED" for j in jobs):
        failures.append("NORMAL_FILE_NOT_PARSED")
    if any(not line["sources"] for line in matches):
        failures.append("BUDGET_EVIDENCE_MISSING")
    frozen = [r["materialReference"] for r in reviews]
    reopened = [CLIENT.call("GET", "/api/v1/budget/reviews/" + j["id"])["materialReference"] for j in jobs]
    if frozen != reopened:
        failures.append("READ_CHANGED_REPORT")
    denied = OTHER.session.get(BASE + "/api/v1/budget/material-uploads/batches/" + batch, timeout=30).status_code
    if denied not in {403, 404}:
        failures.append("CROSS_OWNER_BUDGET_ACCESS")
    return {"label": label, "batchId": batch, "jobs": [j["id"] for j in jobs], "statuses": [j["status"] for j in jobs],
            "lines": len(lines), "reportFingerprint": fingerprint(frozen), "failures": failures, "realModel": True, "synthetic": True}

def procurement(spec):
    label = scenario_label("procurement", spec["id"])
    state = identity(label, {"key": str(uuid.uuid4())})
    case = CLIENT.call("GET", "/api/v1/procurement/cases/operations/" + state["key"])
    if not case:
        import contextlib
        with contextlib.ExitStack() as stack:
            files = [("files", ((FIXTURES / path).name, stack.enter_context((FIXTURES / path).open("rb")), "application/octet-stream")) for path in spec["paths"]]
            case = CLIENT.call("POST", "/api/v1/procurement/cases", files=files, headers={"Idempotency-Key": state["key"]})
    case_id = case["id"]
    deadline = time.monotonic() + 3600
    last = None
    while case["status"] in {"UPLOADING", "QUEUED", "RUNNING"}:
        marker = (case["status"], sum(d["status"] == "EXTRACTED" for d in case["documents"]), case.get("report", {}).get("modules") if case.get("report") else None)
        if marker != last:
            print(json.dumps({"label": label, "case": case_id, "progress": marker}), flush=True)
            last = marker
        assert time.monotonic() < deadline, "PROCUREMENT_TERMINAL_DEADLINE"
        time.sleep(3)
        case = CLIENT.call("GET", "/api/v1/procurement/cases/" + case_id)
    save(label + "-raw", case)
    report, failures, citations = case.get("report"), [], 0
    if not report:
        return {"label": label, "caseId": case_id, "failures": ["NO_REPORT"]}
    for field, value in (("budget", "222600"), ("awardAmount", "212000")):
        if report.get(field) is None or Decimal(str(report[field])) != Decimal(value):
            failures.append("WRONG_" + field.upper())
    expected = {"澄川星河办公设备有限公司": Decimal("212000"), "澄川明川数字设备有限公司": Decimal("216400"), "澄川启衡信息设备有限公司": Decimal("219800")}
    offers = {o["supplier"]: Decimal(str(o["amount"])) for o in report["offers"]}
    if offers != expected:
        failures.append("WRONG_SUPPLIER_QUOTES")
    if sum(len(o["lines"]) for o in report["offers"]) != 9:
        failures.append("QUOTE_LINE_COUNT_NOT_9")
    evidence = {d["id"]: CLIENT.call("GET", "/api/v1/procurement/cases/" + case_id + "/files/" + d["id"] + "/evidence") for d in case["documents"]}
    for doc in case["documents"]:
        for fact in evidence[doc["id"]]["facts"]:
            citations += 1
            if not any(s["locator"] == fact["locator"] and fact["excerpt"] in s["text"] for s in evidence[doc["id"]]["segments"]):
                failures.append("FACT_ORIGINAL_MISMATCH")
    for module in ("AGENT_PRECHECK", "TENDER_COMPLIANCE"):
        review = report.get("reviews", {}).get(module)
        if not review or review["status"] != "COMPLETED" or not review["checks"]:
            failures.append(module + "_NOT_COMPLETED")
        if not review:
            continue
        for item in review["checks"] + review["findings"]:
            for e in item["evidence"]:
                citations += 1
                doc = evidence.get(e["documentId"])
                if not doc or doc["sha256"] != e["sha256"] or not any(s["locator"] == e["locator"] and e["excerpt"] in s["text"] for s in doc["segments"]):
                    failures.append("REVIEW_ORIGINAL_MISMATCH")
    findings = [f for review in report.get("reviews", {}).values() for f in review["findings"]]
    if spec["id"] == "normal" and any(f["level"] == "WARNING" for f in findings):
        failures.append("NORMAL_BATCH_FALSE_RISK_REQUIRES_REVIEW")
    if spec["id"] == "conflict" and not any(f["code"] in {"PAYMENT_CONFLICT", "CLARIFICATION_CONFLICT", "BUSINESS_CONFLICT"} and len({e["documentId"] for e in f["evidence"]}) >= 2 for f in findings):
        failures.append("EXPECTED_BILATERAL_PAYMENT_CONFLICT_MISSING")
    if CLIENT.call("GET", "/api/v1/procurement/cases/" + case_id) != case:
        failures.append("READ_CHANGED_CASE")
    if OTHER.session.get(BASE + "/api/v1/procurement/cases/" + case_id, timeout=30).status_code not in {403, 404}:
        failures.append("CROSS_OWNER_PROCUREMENT_ACCESS")
    return {"label": label, "caseId": case_id, "status": case["status"], "modules": report["modules"], "offers": len(offers),
            "citations": citations, "reportFingerprint": fingerprint(case), "failures": sorted(set(failures)), "realModel": True, "synthetic": True}

def contract(spec):
    label = scenario_label("contract", spec["id"])
    paths = [FIXTURES / p for p in spec["paths"]]
    state = identity(label, {"key": str(uuid.uuid4()), "fileKeys": [str(uuid.uuid4()) for p in paths]})
    receipt = CLIENT.call("POST", "/api/v1/contract-analysis-cases/intakes", json={"fileCount": len(paths)}, headers={"Idempotency-Key": state["key"]})
    content_types = {".pdf": "application/pdf", ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                     ".doc": "application/msword", ".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg"}
    for key, path in zip(state["fileKeys"], paths):
        with path.open("rb") as stream:
            CLIENT.call("POST", "/api/v1/projects/" + receipt["projectId"] + "/file-jobs", files={"file": (path.name, stream, content_types[path.suffix.lower()])}, headers={"Idempotency-Key": key})
    deadline, previous = time.monotonic() + 2400, None
    while True:
        case = CLIENT.call("GET", "/api/v1/contract-analysis-cases/" + receipt["caseId"])
        marker = (case["status"], len(case["steps"]))
        if marker != previous:
            print(json.dumps({"label": label, "progress": marker}), flush=True)
            previous = marker
        if case["status"] not in {"QUEUED", "RUNNING"}:
            break
        assert time.monotonic() < deadline, "CONTRACT_TERMINAL_DEADLINE"
        time.sleep(3)
    report = CLIENT.call("GET", "/api/v1/contract-analysis-cases/" + receipt["caseId"] + "/report")
    texts = {s["fileId"]: CLIENT.call("GET", "/api/v1/contract-analysis-cases/" + case["id"] + "/sources/" + s["fileId"] + "/text")["text"] for s in case["sources"] if s["status"] == "READY"}
    spec = dict(spec, paths=[str(p) for p in paths])
    result = verify_contract(spec, case, report, texts)
    result["label"] = label
    result["reportFingerprint"] = fingerprint(report)
    if OTHER.session.get(BASE + "/api/v1/contract-analysis-cases/" + case["id"], timeout=30).status_code not in {403, 404}:
        result["failures"].append("CROSS_OWNER_CONTRACT_ACCESS")
    save(label + "-raw", {"case": case, "report": report})
    return result

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("module", choices=["budget", "procurement", "contract"])
    parser.add_argument("scenarios", nargs="*")
    parser.add_argument("--revision", choices=["r2", "r3", "r6"], default="")
    args = parser.parse_args()
    RUN_REVISION = args.revision
    results = []
    for spec in GOLDEN[args.module]:
        if args.scenarios and spec["id"] not in args.scenarios:
            continue
        try:
            result = globals()[args.module](spec)
        except Exception as error:
            result = {"label": scenario_label(args.module, spec["id"]), "failures": [type(error).__name__ + ":" + str(error)[:250]]}
        results.append(result)
        save(result["label"] + "-verification", result)
        save(args.module + ("-" + RUN_REVISION if RUN_REVISION else "") + "-summary", results)
        print("QA26_RESULT " + json.dumps(result, ensure_ascii=False), flush=True)
    sys.exit(any(r["failures"] for r in results))
