"""Explicit, one-phase V2 acceptance. Offline tests are NOT business acceptance.

Fixed loopback :8888 and approved V2 package. Credentials are memory-only test_admin.
Every business command is durably journaled BEFORE dispatch. UNKNOWN commands never
retransmit; create/upload have exact GET rediscovery. Extraction is explicit for ONE
file, never a model loop. Extraction also requires a root-verified OFFLINE batch plan
and explicit --max-planned-calls, checked against the plan's WORST CASE including
one repair per batch. Missing/oversize plans are CAPACITY_BLOCKED before dispatch.
A failed extraction latches the whole case for read-only
inspection. The oracle compares observed facts; it NEVER supplies confirmation values.

Runtime identity is a fresh ROOT_VERIFIED_RUNNING_ARTIFACT attestation, not git HEAD.
Root owns its process/build provenance checks; this tool validates the attestation and
artifact bytes, and does not claim to independently prove a JVM's loaded code identity.
Ledger stores complete request identity as method/path/bodySHA/fileSHA, never raw body,
prompt, source text, password, session, token or model response.
"""
from __future__ import annotations

import argparse
import csv
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
import getpass
import hashlib
import io
import json
import logging
import os
from pathlib import Path, PurePosixPath
import re
import sys
import time
from uuid import UUID, uuid4
import zipfile

import httpx

ORIGIN = "http://127.0.0.1:8888"
WORKTREE = Path(__file__).resolve().parents[2]
TEST_ROOT = WORKTREE / ".runtime" / "v2-acceptance"
PACKAGE_ROOT = Path(r"D:\code\audit_agent_md\智慧维修系统_合同审查实战测试包_V2_20261015")
PACKAGE_ZIP = Path(r"D:\code\audit_agent_md\测试包_V2_20261015.zip")
PACKAGE_SHA = "4a2a1f49faade70b7dfddc701ef0c7f8e22f8c3f3783dcf97be1f7859a75eb66"
CASES = ("01", "02", "03", "04", "05", "06")
HASH = re.compile(r"^[0-9a-f]{64}$")
CODE = re.compile(r"^[A-Z][A-Z0-9_]{0,95}$")
OP = re.compile(r"^[a-z][a-z0-9:-]{0,127}$")
ROLES = {"CONTRACT_BODY", "AWARD_NOTICE", "TENDER_DOCUMENT", "BID_RESPONSE", "SUPPLEMENT", "OTHER"}
ID_FIELDS = {"projectId", "fileId", "jobId", "parseResultId", "assessmentId", "roleDecisionId",
             "identityDecisionId", "decisionId", "candidateId", "confirmationId", "contractId", "runId",
             "reportId", "attemptId", "sourceParseResultId", "sourceRoleDecisionId", "sourceIdentityDecisionId"}
COUNT_FIELDS = {"versionNo", "candidateVersion", "resultCandidateVersion", "attemptNo", "parseVersion",
                "total", "completed", "failed", "unknown", "fileCount", "lineCount", "candidateCount",
                "modelCallCount", "acceptedCount", "rejectionCount", "canonicalCodePoints", "elapsedMs",
                "stepCount", "reportBytes", "processId", "pageCount", "zipBytes"}
POSITIVE_COUNT_FIELDS = {"javaProcessId", "pythonProcessId", "plannedCalls", "maximumModelCalls", "maxPlannedCalls"}
HASH_FIELDS = {"fileSha256", "canonicalTextSha256", "payloadFingerprint", "projectionSha256", "reportSha256",
               "artifactSha256", "runtimeEvidenceSha256", "inputFingerprint", "packageSha256",
               "javaInputSha256", "pythonInputSha256", "projectZipSha256", "planSha256"}
STATUS_FIELDS = {"status", "safeCode", "blocker", "effectiveRole", "effectiveIdentityStatus", "parseMethod",
                 "parseStatus", "selectedRole", "resolution", "coverage"}
BOOL_FIELDS = {"identityCurrent", "excludedFromProjectUse", "workerEnabled", "reconciled"}


class SafeFailure(Exception):
    def __init__(self, code):
        self.code = code if isinstance(code, str) and CODE.fullmatch(code) else "UNSAFE_ERROR_CODE"
        super().__init__(self.code)


def require(condition, code):
    if not condition:
        raise SafeFailure(code)


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"),
                      default=lambda value: str(value) if isinstance(value, Decimal) else _invalid_json())


def _invalid_json():
    raise SafeFailure("INVALID_JSON_VALUE")


def digest(value):
    return hashlib.sha256(value.encode("utf-8") if isinstance(value, str) else value).hexdigest()


def resource(value):
    try:
        require(isinstance(value, str) and str(UUID(value)) == value.lower(), "RESOURCE_ID_UNPROVEN")
    except (ValueError, TypeError, AttributeError):
        raise SafeFailure("RESOURCE_ID_UNPROVEN") from None
    return value


def scoped_path(path, root, code="UNSAFE_LEDGER_PATH"):
    path, root = Path(path), Path(root).resolve()
    resolved = path.resolve()
    require(resolved != root and resolved.is_relative_to(root), code)
    require(not path.is_symlink(), code)
    return resolved


def safe_result(value):
    require(isinstance(value, dict), "UNSAFE_RECEIPT")
    for key, item in value.items():
        if key in ID_FIELDS:
            resource(item)
        elif key in POSITIVE_COUNT_FIELDS:
            require(type(item) is int and item > 0, "UNSAFE_RECEIPT")
        elif key in COUNT_FIELDS:
            require(type(item) is int and item >= 0, "UNSAFE_RECEIPT")
        elif key in HASH_FIELDS:
            require(isinstance(item, str) and HASH.fullmatch(item), "UNSAFE_RECEIPT")
        elif key in STATUS_FIELDS:
            require(isinstance(item, str) and CODE.fullmatch(item), "UNSAFE_RECEIPT")
        elif key in BOOL_FIELDS:
            require(type(item) is bool, "UNSAFE_RECEIPT")
        elif key == "sourceSha":
            require(isinstance(item, str) and re.fullmatch(r"[0-9a-f]{40}", item), "UNSAFE_RECEIPT")
        elif key in {"fileIds", "stepIds", "materialFileIds", "parseResultIds"}:
            require(isinstance(item, list), "UNSAFE_RECEIPT")
            for identifier in item:
                resource(identifier)
        else:
            raise SafeFailure("UNSAFE_RECEIPT")
    return value


class Ledger:
    """One OS-locked writer; append-only, fsynced, hash-chained, fail-closed on torn tail."""
    def __init__(self, path, *, root=TEST_ROOT):
        self.path = scoped_path(path, root)
        require(self.path.suffix == ".jsonl", "UNSAFE_LEDGER_PATH")
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.lock = None
        self.events = []
        self.last_hash = "0" * 64
        lock_path = self.path.with_suffix(".lock")
        scoped_path(lock_path, root)
        try:
            self.lock = lock_path.open("a+b")
            if not lock_path.stat().st_size:
                self.lock.write(b"0")
                self.lock.flush()
                os.fsync(self.lock.fileno())
            self.lock.seek(0)
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(self.lock.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            if self.lock:
                self.lock.close()
                self.lock = None
            raise SafeFailure("LEDGER_BUSY") from None
        try:
            self._load()
        except Exception:
            self.close()
            raise

    def _load(self):
        if not self.path.exists():
            return
        require(self.path.stat().st_size <= 16_000_000, "LEDGER_CORRUPT")
        raw = self.path.read_bytes()
        require(not raw or raw.endswith(b"\n"), "LEDGER_CORRUPT")
        try:
            for line in raw.splitlines():
                record = json.loads(line)
                require(set(record) == {"seq", "previousSha256", "sha256", "value"}, "LEDGER_CORRUPT")
                unsigned = {key: record[key] for key in ("seq", "previousSha256", "value")}
                require(record["seq"] == len(self.events) + 1
                        and record["previousSha256"] == self.last_hash
                        and record["sha256"] == digest(canonical(unsigned)), "LEDGER_CORRUPT")
                self._validate(record["value"])
                self.events.append(record["value"])
                self.last_hash = record["sha256"]
        except (ValueError, TypeError, KeyError, SafeFailure):
            raise SafeFailure("LEDGER_CORRUPT") from None

    @staticmethod
    def _validate(value):
        fields = {"event", "case", "operation", "command", "key", "state", "result", "elapsedMs", "safeCode"}
        require(isinstance(value, dict) and set(value).issubset(fields)
                and value.get("event") in {"INTENT", "OUTCOME", "OBSERVATION"}
                and value.get("case") in CASES and OP.fullmatch(value.get("operation", "")), "UNSAFE_RECEIPT")
        safe_result(value.get("result", {}))
        if "command" in value:
            command = value["command"]
            require(set(command) == {"method", "path", "bodySha256", "fileSha256", "uploadMetadataSha256"}
                    and command["method"] in {"POST", "PATCH"}
                    and HASH.fullmatch(command["bodySha256"])
                    and (command["fileSha256"] is None or HASH.fullmatch(command["fileSha256"]))
                    and (command["uploadMetadataSha256"] is None or HASH.fullmatch(command["uploadMetadataSha256"])), "UNSAFE_RECEIPT")
            validate_path(command["path"])
        if "key" in value:
            require(re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", value["key"]), "UNSAFE_RECEIPT")
        if "state" in value:
            require(value["state"] in {"SENDING", "CONFIRMED", "UNKNOWN", "REJECTED"}, "UNSAFE_RECEIPT")
        if "safeCode" in value:
            require(CODE.fullmatch(value["safeCode"]), "UNSAFE_RECEIPT")
        if "elapsedMs" in value:
            require(type(value["elapsedMs"]) is int and value["elapsedMs"] >= 0, "UNSAFE_RECEIPT")

    def append(self, value):
        require(self.lock is not None, "LEDGER_CLOSED")
        self._validate(value)
        unsigned = {"seq": len(self.events) + 1, "previousSha256": self.last_hash, "value": value}
        record = {**unsigned, "sha256": digest(canonical(unsigned))}
        with self.path.open("ab") as stream:
            stream.write((canonical(record) + "\n").encode("utf-8"))
            stream.flush()
            os.fsync(stream.fileno())
        self.events.append(value)
        self.last_hash = record["sha256"]

    def note(self, case, operation, result):
        self.append({"event": "OBSERVATION", "case": case, "operation": operation, "result": safe_result(result)})

    def operation(self, case, operation):
        records = [e for e in self.events if e["case"] == case and e["operation"] == operation and e["event"] != "OBSERVATION"]
        if not records:
            return None
        return {**records[0], **records[-1]}

    def case_writable(self, case):
        operations = {e["operation"] for e in self.events if e["case"] == case and e["event"] == "INTENT"}
        for operation in operations:
            state = self.operation(case, operation)["state"]
            require(state not in {"SENDING", "UNKNOWN"}, "CASE_UNRESOLVED_GET_ONLY")
            require(not operation.startswith("extract:") or state == "CONFIRMED", "EXTRACTION_FAILED_CASE_STOPPED")
        require(not any(e["case"] == case and e["operation"].startswith("extract:")
                        and e.get("result", {}).get("status") in {"IN_PROGRESS", "FAILED", "OUTCOME_UNKNOWN"}
                        for e in self.events), "EXTRACTION_FAILED_CASE_STOPPED")

    def close(self):
        if self.lock is not None:
            try:
                self.lock.seek(0)
                if os.name == "nt":
                    import msvcrt
                    msvcrt.locking(self.lock.fileno(), msvcrt.LK_UNLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(self.lock.fileno(), fcntl.LOCK_UN)
            finally:
                self.lock.close()
                self.lock = None


def validate_path(path):
    require(isinstance(path, str) and path.startswith("/api/v1/")
            and re.fullmatch(r"/[A-Za-z0-9/_-]+", path) and "//" not in path,
            "UNSAFE_HTTP_PATH")


class Api:
    def __init__(self, ledger, *, transport=None, readonly=False):
        self.ledger = ledger
        self.readonly = readonly
        self.client = httpx.Client(base_url=ORIGIN, transport=transport, trust_env=False, follow_redirects=False)

    def close(self):
        self.client.close()

    def _request(self, method, path, *, timeout=30, **kwargs):
        validate_path(path)
        require(not self.readonly or method == "GET" or path == "/api/v1/auth/login", "READ_ONLY_PHASE_WRITE_FORBIDDEN")
        require(0 < timeout <= 1000, "INVALID_TIMEOUT")
        try:
            response = self.client.request(method, path, timeout=httpx.Timeout(timeout, connect=5), **kwargs)
        except httpx.HTTPError:
            raise SafeFailure("READ_TRANSPORT_FAILURE" if method == "GET" else "WRITE_OUTCOME_UNKNOWN") from None
        uncertain = "READ_HTTP_FAILURE" if method == "GET" else "WRITE_OUTCOME_UNKNOWN"
        require(len(response.content) <= 16_000_000 and response.status_code < 500
                and not 300 <= response.status_code < 400, uncertain)
        try:
            data = json.loads(response.content, parse_float=Decimal)
        except (ValueError, UnicodeError):
            raise SafeFailure(uncertain) from None
        require(isinstance(data, dict) and type(data.get("code")) is int, uncertain)
        result = data.get("data")
        require(not isinstance(result, dict) or result.get("classification") not in {
            "RECOVERY_OUTCOME_UNCERTAIN", "RECOVERY_IN_PROGRESS"}, uncertain)
        if data["code"] != 200 or response.status_code not in {200, 202}:
            if method != "GET" and 400 <= data["code"] <= 499:
                raise SafeFailure(f"WRITE_REJECTED_{data['code']}")
            raise SafeFailure(uncertain)
        return result

    def get(self, path, **kwargs):
        return self._request("GET", path, **kwargs)

    def command(self, case, operation, method, path, body=None, *, upload=None, select=lambda d: {}, timeout=30):
        validate_path(path)
        require(method in {"POST", "PATCH"}, "INVALID_COMMAND_METHOD")
        self.ledger.case_writable(case)
        command = {"method": method, "path": path, "bodySha256": digest(canonical(body)),
                   "fileSha256": digest(upload[1]) if upload is not None else None,
                   "uploadMetadataSha256": digest(canonical([upload[0], upload[2]])) if upload is not None else None}
        prior = self.ledger.operation(case, operation)
        if prior:
            require(prior["command"] == command, "COMMAND_IDENTITY_CHANGED")
            require(prior["state"] == "CONFIRMED", "COMMAND_ALREADY_TERMINAL")
            return prior["result"]
        key = "v2:" + str(uuid4())
        self.ledger.append({"event": "INTENT", "case": case, "operation": operation,
                            "command": command, "key": key, "state": "SENDING"})
        started = time.monotonic()
        try:
            options = {"headers": {"Idempotency-Key": key}}
            if upload is not None:
                options["files"] = {"file": upload}
            elif body is not None:
                options["content"] = canonical(body).encode("utf-8")
                options["headers"]["Content-Type"] = "application/json"
            response = self._request(method, path, timeout=timeout, **options)
            result = safe_result(select(response))
        except Exception as error:
            code = error.code if isinstance(error, SafeFailure) else "WRITE_OUTCOME_UNKNOWN"
            rejected = code.startswith("WRITE_REJECTED_")
            self.ledger.append({"event": "OUTCOME", "case": case, "operation": operation,
                                "state": "REJECTED" if rejected else "UNKNOWN", "safeCode": code,
                                "elapsedMs": int((time.monotonic() - started) * 1000), "result": {}})
            raise SafeFailure(code) from None
        self.ledger.append({"event": "OUTCOME", "case": case, "operation": operation,
                            "state": "CONFIRMED", "elapsedMs": int((time.monotonic() - started) * 1000), "result": result})
        return result

    def login(self, value):
        for before in (True, False):
            csrf = self.get("/api/v1/auth/csrf")
            require(csrf.get("headerName") in {"X-CSRF-TOKEN", "X-XSRF-TOKEN"}
                    and isinstance(csrf.get("token"), str) and 0 < len(csrf["token"]) <= 512, "CSRF_UNPROVEN")
            self.client.headers[csrf["headerName"]] = csrf["token"]
            if before:
                current = self._request("POST", "/api/v1/auth/login", json=value)
                require(current.get("username") == "test_admin", "ACCOUNT_IDENTITY_UNPROVEN")


def credentials(raw):
    try:
        value = json.loads(raw)
        require(isinstance(value, dict) and set(value) == {"username", "password"}
                and value["username"] == "test_admin" and isinstance(value["password"], str)
                and 0 < len(value["password"]) <= 1024, "CREDENTIAL_INPUT_INVALID")
        return value
    except (ValueError, TypeError, KeyError):
        raise SafeFailure("CREDENTIAL_INPUT_INVALID") from None


def validate_runtime(value, *, expected_sha):
    require(isinstance(expected_sha, str) and re.fullmatch(r"[0-9a-f]{40}", expected_sha), "EXPECTED_SHA_REQUIRED")
    require(isinstance(value, dict) and value.get("kind") == "ROOT_VERIFIED_RUNNING_ARTIFACT"
            and value.get("origin") == ORIGIN and value.get("sourceSha") == expected_sha
            and isinstance(value.get("java"), dict) and isinstance(value.get("python"), dict)
            and isinstance(value.get("observedAtUtc"), str), "RUNTIME_IDENTITY_UNPROVEN")
    for service in (value["java"], value["python"]):
        require(type(service.get("processId")) is int and service["processId"] > 0
                and isinstance(service.get("inputSha256"), str) and HASH.fullmatch(service["inputSha256"]),
                "RUNTIME_IDENTITY_UNPROVEN")
    require(isinstance(value["java"].get("artifactSha256"), str) and HASH.fullmatch(value["java"]["artifactSha256"])
            and isinstance(value["java"].get("artifactPath"), str), "RUNTIME_IDENTITY_UNPROVEN")
    try:
        observed = datetime.fromisoformat(value["observedAtUtc"].replace("Z", "+00:00"))
        age = (datetime.now(timezone.utc) - observed).total_seconds()
        require(0 <= age <= 900, "RUNTIME_IDENTITY_UNPROVEN")
    except (ValueError, TypeError):
        raise SafeFailure("RUNTIME_IDENTITY_UNPROVEN") from None
    return {"sourceSha": expected_sha, "artifactSha256": value["java"]["artifactSha256"],
            "javaProcessId": value["java"]["processId"], "pythonProcessId": value["python"]["processId"],
            "javaInputSha256": value["java"]["inputSha256"], "pythonInputSha256": value["python"]["inputSha256"]}


def rediscover(api, case, operation, *, decision_id=None):
    original = api.ledger.operation(case, operation)
    require(original is not None, "OPERATION_NOT_FOUND")
    if operation == "create":
        current = api.get("/api/v1/projects/creation-operations", params={"key": original["key"]})
        result = {"projectId": resource(current["id"]), "versionNo": current["versionNo"]}
    elif operation.startswith("upload:"):
        prefix = original["command"]["path"]
        current = api.get(prefix + "/by-operation", params={"key": original["key"]})
        require(current["file"]["fileSha256"] == original["command"]["fileSha256"], "UPLOAD_IDENTITY_UNPROVEN")
        result = {key: resource(current[key]) for key in ("projectId", "fileId", "jobId")}
        result["fileSha256"] = current["file"]["fileSha256"]
    elif operation.startswith(("role:", "same:", "exclude:")):
        resource(decision_id)
        role = operation.startswith("role:")
        suffix = "/role-confirmations" if role else "/identity-confirmations"
        path = original["command"]["path"]
        require(path.endswith(suffix), "AUTHORITY_IDENTITY_UNPROVEN")
        file_prefix = path[:-len(suffix)]
        current = api.get(file_prefix + ("/role-decisions/" if role else "/identity-decisions/") + decision_id)
        parts = file_prefix.split("/")
        require(current["projectId"] == parts[-3] and current["fileId"] == parts[-1]
                and current["decisionId"] == decision_id, "AUTHORITY_IDENTITY_UNPROVEN")
        result = {key: resource(current[key]) for key in ("projectId", "fileId", "decisionId")}
        # Public decision DTO has no exact operation-key proof; never clear UNKNOWN.
        result.update(status="OUTCOME_UNKNOWN", reconciled=False)
        api.ledger.note(case, operation, result)
        return result
    else:
        raise SafeFailure("EXACT_OPERATION_GET_UNAVAILABLE")
    api.ledger.append({"event": "OUTCOME", "case": case, "operation": operation,
                       "state": "CONFIRMED", "result": safe_result(result), "safeCode": "EXACT_OPERATION_REDISCOVERED"})
    return result


def number(value):
    try:
        require(value is not None and not isinstance(value, (bool, float)), "FACT_GAP")
        result = Decimal(str(value))
        require(result.is_finite(), "FACT_GAP")
        return result
    except (InvalidOperation, ValueError):
        raise SafeFailure("FACT_GAP") from None


def compare_facts(candidate, oracle):
    require(candidate.get("contractNo") == oracle["contract_no"]
            and number(candidate.get("contractAmount")) == number(oracle["contract_amount"]), "FACT_GAP")
    lines = candidate.get("lineItems", [])
    require(len(lines) == oracle["line_count"] == len(oracle["facts"]), "FACT_GAP")
    by_key = {line.get("itemKey"): line for line in lines}
    require(len(by_key) == len(lines), "FACT_GAP")
    for expected in oracle["facts"]:
        line = by_key.get(expected["row_id"])
        require(line is not None and line.get("itemName") == expected["name"], "FACT_GAP")
        for actual_key, expected_key in (("quantity", "quantity"), ("unitPrice", "unit_price"), ("declaredAmount", "line_total")):
            require(number(line.get(actual_key)) == number(expected[expected_key]), "FACT_GAP")
        require(number(line["quantity"]) * number(line["unitPrice"]) == number(line["declaredAmount"]), "FACT_GAP")
    require(sum((number(line["declaredAmount"]) for line in lines), Decimal(0))
            == number(oracle["contract_amount"]), "FACT_GAP")
    if "supplier" in oracle:
        require(candidate.get("partyB") == oracle["supplier"] and candidate.get("awardSupplier") == oracle["supplier"], "FACT_GAP")
    if "award_amount" in oracle:
        require(number(candidate.get("awardAmount")) == number(oracle["award_amount"]), "FACT_GAP")
    return {"lineCount": len(lines)}


class Package:
    """The pinned outer ZIP authenticates every extracted byte, including the oracle."""
    def __init__(self, *, root=PACKAGE_ROOT, archive=PACKAGE_ZIP, expected_sha=PACKAGE_SHA):
        self.root = Path(root).resolve()
        self.archive = Path(archive).resolve()
        require(digest(self.archive.read_bytes()) == expected_sha, "PACKAGE_HASH_MISMATCH")
        with zipfile.ZipFile(self.archive) as package:
            entries = [entry for entry in package.infolist() if not entry.is_dir()]
            require(len(entries) == 63 and sum(entry.file_size for entry in entries) <= 100_000_000,
                    "PACKAGE_STRUCTURE_INVALID")
            names = set()
            prefix = PACKAGE_ROOT.name + "/"
            for entry in entries:
                require(entry.filename.startswith(prefix) and entry.file_size <= 16_000_000,
                        "PACKAGE_STRUCTURE_INVALID")
                relative = PurePosixPath(entry.filename[len(prefix):])
                require(not relative.is_absolute() and ".." not in relative.parts
                        and str(relative) not in names, "PACKAGE_STRUCTURE_INVALID")
                names.add(str(relative))
                target = scoped_path(self.root.joinpath(*relative.parts), self.root, "PACKAGE_PATH_INVALID")
                require(target.is_file() and digest(target.read_bytes()) == digest(package.read(entry)),
                        "PACKAGE_EXTRACTED_BYTES_MISMATCH")
        manifest = self.root / "04_预期结果与验收资料" / "文件清单_含SHA256.csv"
        self.rows = list(csv.DictReader(io.StringIO(manifest.read_text(encoding="utf-8-sig"))))
        self.oracle = json.loads((manifest.parent / "业务真值_逐行金额与冲突.json").read_bytes(), parse_float=Decimal)
        self.by_case = {}
        self.zip_summaries = {}
        for case in CASES:
            result = []
            for row in self.rows:
                if row["scenario"] != str(int(case)) or not row["relative_path"].startswith("00_解压后按场景多选上传/"):
                    continue
                path = scoped_path(self.root / row["relative_path"], self.root, "PACKAGE_PATH_INVALID")
                require(path.suffix.lower() in {".pdf", ".docx"} and row["role"] in ROLES
                        and HASH.fullmatch(row["sha256"]) and digest(path.read_bytes()) == row["sha256"],
                        "PACKAGE_MANIFEST_INVALID")
                result.append({"path": path, "sha256": row["sha256"], "role": row["role"],
                               "foreign": row["expected_gate"] == "PARSED_BUT_IDENTITY_CONFLICT"})
            require(result and len({r["sha256"] for r in result}) == len(result), "PACKAGE_MANIFEST_INVALID")
            self.by_case[case] = sorted(result, key=lambda value: value["path"].name)
            zips = list((self.root / "01_各项目ZIP_单独上传").glob(case + "_*.zip"))
            require(len(zips) == 1, "PACKAGE_STRUCTURE_INVALID")
            self.zip_summaries[case] = {"packageSha256": expected_sha, "projectZipSha256": digest(zips[0].read_bytes()),
                                        "zipBytes": zips[0].stat().st_size, "fileCount": len(result)}

    def files(self, case):
        require(case in CASES, "INVALID_CASE")
        return self.by_case[case]

    def file(self, case, file_sha):
        found = [value for value in self.files(case) if value["sha256"] == file_sha]
        require(len(found) == 1, "FILE_NOT_APPROVED_FOR_CASE")
        return found[0]

    def read(self, metadata):
        path = scoped_path(metadata["path"], self.root, "PACKAGE_PATH_INVALID")
        data = path.read_bytes()
        require(digest(data) == metadata["sha256"], "FILE_CHANGED_BEFORE_DISPATCH")
        return data


class Driver:
    """Explicit phases: no method runs a batch of fact/model commands."""
    def __init__(self, api, package, case):
        require(case in CASES, "INVALID_CASE")
        self.api, self.package, self.case = api, package, case

    def _prior(self, operation):
        original = self.api.ledger.operation(self.case, operation)
        if original is not None:
            require(original["state"] == "CONFIRMED", "CASE_UNRESOLVED_GET_ONLY")
            return original["result"]
        return None

    def project_id(self):
        original = self._prior("create")
        require(original is not None, "PROJECT_CREATION_REQUIRED")
        return resource(original["projectId"])

    def base(self):
        return "/api/v1/projects/" + self.project_id()

    def create(self):
        self.api.ledger.case_writable(self.case)
        original = self._prior("create")
        if original is None:
            original = self.api.command(self.case, "create", "POST", "/api/v1/projects",
                                        select=lambda d: {"projectId": resource(d["id"]), "versionNo": d["versionNo"]})
        metadata = self._prior("metadata")
        if metadata is None:
            oracle = self.package.oracle[self.case]
            def select(d):
                require(d["id"] == original["projectId"] and d["metadataConfirmed"] is True,
                        "PROJECT_IDENTITY_UNPROVEN")
                return {"projectId": resource(d["id"]), "versionNo": d["versionNo"]}
            metadata = self.api.command(self.case, "metadata", "PATCH", self.base(), {
                "expectedVersion": original["versionNo"], "name": oracle["project_name"],
                "projectNo": oracle["project_id"], "category": None}, select=select)
        return metadata

    def upload(self, file_sha=None):
        self.api.ledger.case_writable(self.case)
        files = [self.package.file(self.case, file_sha)] if file_sha else self.package.files(self.case)
        for metadata in files:
            operation = "upload:" + metadata["sha256"]
            if self._prior(operation) is not None:
                continue
            data = self.package.read(metadata)
            mime = "application/pdf" if metadata["path"].suffix.lower() == ".pdf" else (
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
            def select(d):
                require(d["projectId"] == self.project_id() and d["file"]["projectId"] == self.project_id()
                        and d["file"]["id"] == d["fileId"] and d["file"]["fileSha256"] == metadata["sha256"]
                        and d["job"]["projectId"] == self.project_id() and d["job"]["fileId"] == d["fileId"]
                        and d["job"]["jobId"] == d["jobId"], "UPLOAD_IDENTITY_UNPROVEN")
                return {**{key: resource(d[key]) for key in ("projectId", "fileId", "jobId")},
                        "fileSha256": metadata["sha256"]}
            self.api.command(self.case, operation, "POST", self.base() + "/file-jobs",
                             upload=(metadata["path"].name, data, mime), select=select)
        return {"projectId": self.project_id(), "fileCount": len(files)}

    def detail(self):
        detail = self.api.get(self.base())
        require(detail["project"]["id"] == self.project_id() and isinstance(detail.get("files"), list),
                "PROJECT_IDENTITY_UNPROVEN")
        approved = {value["sha256"] for value in self.package.files(self.case)}
        hashes = set()
        for file in detail["files"]:
            require(file["projectId"] == self.project_id() and file.get("parentArchiveFileId") is None
                    and file["fileSha256"] in approved and file["fileSha256"] not in hashes,
                    "FILE_IDENTITY_UNPROVEN")
            resource(file["id"])
            hashes.add(file["fileSha256"])
        return detail

    def file(self, file_sha):
        self.package.file(self.case, file_sha)
        found = [file for file in self.detail()["files"] if file["fileSha256"] == file_sha]
        require(len(found) == 1, "FILE_NOT_DISCOVERED")
        return found[0]

    def poll(self):
        detail = self.detail()
        jobs = self.api.get(self.base() + "/file-jobs")
        require(jobs["projectId"] == self.project_id() and isinstance(jobs.get("items"), list), "JOB_IDENTITY_UNPROVEN")
        files = {file["id"]: file for file in detail["files"]}
        seen = set()
        counts = {"completed": 0, "failed": 0, "unknown": 0}
        for job in jobs["items"]:
            file = files.get(job.get("fileId"))
            require(file is not None and job["projectId"] == self.project_id()
                    and job["fileId"] not in seen and job["status"] in {"QUEUED", "PROCESSING", "SUCCEEDED", "FAILED", "UNKNOWN"},
                    "JOB_IDENTITY_UNPROVEN")
            receipt = self._prior("upload:" + file["fileSha256"])
            require(receipt is not None and receipt["fileId"] == file["id"] and receipt["jobId"] == job["jobId"],
                    "JOB_IDENTITY_UNPROVEN")
            seen.add(job["fileId"])
            key = {"SUCCEEDED": "completed", "FAILED": "failed", "UNKNOWN": "unknown"}.get(job["status"])
            if key:
                counts[key] += 1
            observed = {"projectId": self.project_id(), "fileId": file["id"], "jobId": resource(job["jobId"]),
                        "fileSha256": file["fileSha256"], "status": job["status"]}
            if job.get("parseResultId") is not None:
                require(job["parseResultId"] == file.get("parseResultId"), "PARSE_IDENTITY_UNPROVEN")
                observed["parseResultId"] = resource(job["parseResultId"])
            for field in ("canonicalTextSha256", "parseStatus", "parseMethod", "parseVersion", "pageCount"):
                if file.get(field) is not None:
                    observed[field] = file[field]
            if file.get("parsedText") is not None:
                observed["canonicalCodePoints"] = len(file["parsedText"])
            self.api.ledger.note(self.case, "poll:" + file["fileSha256"], observed)
        require(jobs["total"] == len(jobs["items"]) and all(jobs[key] == value for key, value in counts.items()),
                "JOB_COUNTS_UNPROVEN")
        status = "UNKNOWN" if counts["unknown"] else "FAILED" if counts["failed"] else (
            "COMPLETED" if counts["completed"] == len(self.package.files(self.case)) == len(files) else "PENDING")
        result = {"projectId": self.project_id(), "fileCount": len(files), "total": len(jobs["items"]),
                  **counts, "workerEnabled": jobs["workerEnabled"], "status": status}
        self.api.ledger.note(self.case, "poll", result)
        return result

    @staticmethod
    def human(file):
        require(file.get("parseStatus") == "PARSED" and file.get("effectiveRoleSource") == "HUMAN_CONFIRMATION"
                and file.get("effectiveIdentityStatus") == "SAME_PROJECT" and file.get("identityCurrent") is True
                and file.get("excludedFromProjectUse") is False, "HUMAN_AUTHORITY_UNPROVEN")
        for field in ("parseResultId", "roleDecisionId", "identityDecisionId"):
            resource(file.get(field))

    def authority(self, file_sha, action, role=None):
        self.api.ledger.case_writable(self.case)
        metadata = self.package.file(self.case, file_sha)
        if action == "same":
            require(not metadata["foreign"], "FOREIGN_MATERIAL_MUST_BE_EXCLUDED")
        if action == "exclude":
            require(metadata["foreign"], "EXCLUSION_NOT_APPROVED_FOR_FILE")
        operation = action + ":" + file_sha
        prior = self._prior(operation)
        if prior is not None:
            return prior
        file = self.file(file_sha)
        require(file["parseStatus"] == "PARSED", "PARSE_NOT_READY")
        prefix = self.base() + "/files/" + resource(file["id"])
        if action == "role":
            require(role in ROLES and role == metadata["role"], "EXPLICIT_ROLE_REQUIRED")
            require("CONFIRM_ROLE" in file["progression"]["availableActions"], "ROLE_ACTION_UNAVAILABLE")
            body = {"assessmentId": resource(file["roleAssessmentId"]), "selectedRole": role,
                    "expectedVersion": file.get("roleDecisionVersion") or 0, "reason": "V2_EXPLICIT_HUMAN_SOURCE_REVIEW"}
            suffix = "/role-confirmations"
        elif action in {"same", "exclude"}:
            require("CONFIRM_PROJECT_IDENTITY" in file["progression"]["availableActions"], "IDENTITY_ACTION_UNAVAILABLE")
            body = {"assessmentId": resource(file["identityAssessmentId"]),
                    "resolution": "CONFIRM_NOT_SAME_PROJECT" if action == "exclude" else "CONFIRM_SAME_PROJECT",
                    "expectedVersion": file.get("identityDecisionVersion") or 0, "reason": "V2_EXPLICIT_HUMAN_SOURCE_REVIEW"}
            suffix = "/identity-confirmations"
        elif action in {"resume-role", "resume-identity"}:
            allowed = {"ROLE_ASSESSMENT_PENDING"} if action == "resume-role" else {
                "IDENTITY_ASSESSMENT_PENDING", "IDENTITY_ASSESSMENT_STALE"}
            require(file["progression"]["blocker"] in allowed, "AUTHORITY_RESUME_UNAVAILABLE")
            body = recovery_body(file)
            suffix = "/progression/resume"
        else:
            raise SafeFailure("INVALID_AUTHORITY_ACTION")
        def select(d):
            if action.startswith("resume-"):
                require(d["id"] == file["id"] and d["projectId"] == self.project_id(), "AUTHORITY_IDENTITY_UNPROVEN")
                return {"projectId": self.project_id(), "fileId": file["id"]}
            require(d["projectId"] == self.project_id() and d["fileId"] == file["id"]
                    and d["assessmentId"] == body["assessmentId"], "AUTHORITY_IDENTITY_UNPROVEN")
            selection = "selectedRole" if action == "role" else "resolution"
            require(d[selection] == body[selection], "AUTHORITY_IDENTITY_UNPROVEN")
            return {"projectId": self.project_id(), "fileId": file["id"], "decisionId": resource(d["decisionId"]),
                    selection: d[selection], "versionNo": d["versionNo"]}
        return self.api.command(self.case, operation, "POST", prefix + suffix, body, select=select, timeout=1000)

    def extraction_read(self, file):
        projection = self.api.get(self.base() + "/files/" + file["id"] + "/contract-fact-extractions/latest")
        attempt = projection.get("attempt")
        require(isinstance(attempt, dict) and projection["currentParseResultId"] == file["parseResultId"]
                and projection["currentParseVersion"] == file["parseVersion"]
                and attempt["projectId"] == self.project_id() and attempt["fileId"] == file["id"]
                and attempt["fileSha256"] == file["fileSha256"] and attempt["parseResultId"] == file["parseResultId"]
                and attempt["canonicalTextSha256"] == file["canonicalTextSha256"]
                and attempt["roleDecisionId"] == file["roleDecisionId"]
                and attempt["identityDecisionId"] == file["identityDecisionId"], "FACT_ATTEMPT_IDENTITY_UNPROVEN")
        operation = "extract:" + file["fileSha256"]
        prior_ids = [event["result"]["attemptId"] for event in self.api.ledger.events
                     if event["case"] == self.case and event["operation"] == operation and "attemptId" in event.get("result", {})]
        require(not prior_ids or set(prior_ids) == {attempt["attemptId"]}, "FACT_ATTEMPT_CHANGED")
        summary = {"projectId": self.project_id(), "fileId": file["id"], "fileSha256": file["fileSha256"],
                   "parseResultId": file["parseResultId"], "attemptId": resource(attempt["attemptId"]),
                   "status": attempt["status"], "inputFingerprint": attempt["inputFingerprint"],
                   "candidateCount": len(projection["candidates"]), "rejectionCount": len(projection["rejections"]),
                   "modelCallCount": len(projection["modelCalls"]),
                   "acceptedCount": sum(candidate.get("validationStatus") == "ACCEPTED" for candidate in projection["candidates"])}
        self.api.ledger.note(self.case, operation, summary)
        require(attempt["status"] == "SUCCEEDED", "EXTRACTION_NOT_SUCCEEDED_READ_ONLY")
        return summary

    def extract(self, file_sha, *, plan=None, max_planned_calls=None, expected_sha=None):
        self.api.ledger.case_writable(self.case)
        metadata = self.package.file(self.case, file_sha)
        require(not metadata["foreign"] and metadata["role"] in {"CONTRACT_BODY", "AWARD_NOTICE"}, "FILE_NOT_FACT_ROLE")
        file = self.file(file_sha)
        self.human(file)
        require(file["effectiveRole"] == metadata["role"], "FACT_ROLE_IDENTITY_UNPROVEN")
        budget = extraction_budget(file, plan, max_planned_calls, expected_sha)
        scopes = {"CORE", "LINE_ITEMS"} if metadata["role"] == "CONTRACT_BODY" else {"AWARD"}
        require(set(plan["scopeBatchCounts"]) == scopes, "CAPACITY_BLOCKED")
        operation = "extract:" + file_sha
        if self._prior(operation) is None:
            self.api.ledger.note(self.case, "plan:" + file_sha, budget)
            require(file["progression"]["blocker"] == "FACT_EXTRACTION_PENDING"
                    and "RESUME_FACT_EXTRACTION" in file["progression"]["availableActions"], "EXTRACTION_ACTION_UNAVAILABLE")
            def select(d):
                require(d["projectId"] == self.project_id() and d["id"] == file["id"], "FACT_FILE_IDENTITY_UNPROVEN")
                return {"projectId": self.project_id(), "fileId": file["id"], "fileSha256": file_sha}
            self.api.command(self.case, operation, "POST", self.base() + "/files/" + file["id"] + "/progression/resume",
                             recovery_body(file), select=select, timeout=1000)
        try:
            return self.extraction_read(file)
        except Exception as error:
            code = error.code if isinstance(error, SafeFailure) else "FACT_ATTEMPT_IDENTITY_UNPROVEN"
            # extraction_read journals the validated attempt before rejecting a known non-success.
            if code != "EXTRACTION_NOT_SUCCEEDED_READ_ONLY":
                self.api.ledger.note(self.case, operation, {"fileId": file["id"], "status": "OUTCOME_UNKNOWN", "safeCode": code})
            raise SafeFailure(code) from None

    def factscheck(self):
        detail = self.detail()
        require(len(detail["files"]) == len(self.package.files(self.case)), "MATERIAL_SET_INCOMPLETE")
        source = None
        for file in detail["files"]:
            metadata = self.package.file(self.case, file["fileSha256"])
            if metadata["foreign"]:
                require(file.get("excludedFromProjectUse") is True and file.get("effectiveIdentityStatus") == "NOT_SAME_PROJECT",
                        "FOREIGN_MATERIAL_MUST_BE_EXCLUDED")
                continue
            self.human(file)
            require(file["effectiveRole"] == metadata["role"], "FACT_ROLE_IDENTITY_UNPROVEN")
            if metadata["role"] in {"CONTRACT_BODY", "AWARD_NOTICE"}:
                self.extraction_read(file)
            if metadata["role"] == "CONTRACT_BODY":
                require(source is None, "CONTRACT_SOURCE_AMBIGUOUS")
                source = file
        require(source is not None, "CONTRACT_SOURCE_UNPROVEN")
        matches = [candidate for candidate in detail["candidates"] if candidate["sourceContractFileId"] == source["id"]]
        require(len(matches) == 1, "CONTRACT_CANDIDATE_UNPROVEN")
        candidate = self.api.get(self.base() + "/contract-candidates/" + resource(matches[0]["id"]))
        require(candidate["sourceParseResultId"] == source["parseResultId"]
                and candidate["sourceRoleDecisionId"] == source["roleDecisionId"]
                and candidate["sourceIdentityDecisionId"] == source["identityDecisionId"]
                and candidate["candidateAuthoritySource"] == "HUMAN_CONFIRMATION", "CANDIDATE_AUTHORITY_UNPROVEN")
        result = {"projectId": self.project_id(), "candidateId": resource(candidate["candidate"]["id"]),
                  "versionNo": candidate["candidate"]["versionNo"],
                  **compare_facts(candidate["candidate"], self.package.oracle[self.case]), "coverage": "QUANTITATIVE_MATCH_ONLY"}
        self.api.ledger.note(self.case, "factscheck", result)
        return result


def recovery_body(file):
    value = file["progression"]
    require(isinstance(value.get("recoveryInputFingerprint"), str) and HASH.fullmatch(value["recoveryInputFingerprint"]),
            "RECOVERY_FINGERPRINT_UNPROVEN")
    return {"expectedBlocker": value["blocker"], "expectedRecoveryInputFingerprint": value["recoveryInputFingerprint"]}


def extraction_budget(file, plan, maximum, expected_sha):
    require(type(maximum) is int and 1 <= maximum <= 1000 and isinstance(plan, dict)
            and plan.get("kind") == "ROOT_VERIFIED_OFFLINE_FACT_PLAN"
            and isinstance(expected_sha, str) and re.fullmatch(r"[0-9a-f]{40}", expected_sha)
            and plan.get("sourceSha") == expected_sha, "CAPACITY_BLOCKED")
    for field, source in (("projectId", "projectId"), ("fileId", "id"), ("fileSha256", "fileSha256"),
                          ("parseResultId", "parseResultId"), ("canonicalTextSha256", "canonicalTextSha256")):
        require(plan.get(field) == file.get(source) and plan.get(field) is not None, "CAPACITY_BLOCKED")
    counts = plan.get("scopeBatchCounts")
    require(isinstance(counts, dict) and counts and set(counts).issubset({"CORE", "LINE_ITEMS", "AWARD"})
            and all(type(value) is int and value > 0 for value in counts.values()), "CAPACITY_BLOCKED")
    planned = sum(counts.values())
    # Current provider allows one repair per transport batch. Budget the worst case,
    # not merely successful first calls. This is a root-attested plan, not a planner clone.
    require(type(plan.get("plannedCalls")) is int and plan["plannedCalls"] == planned
            and type(plan.get("maximumModelCalls")) is int and plan["maximumModelCalls"] == planned * 2
            and plan["maximumModelCalls"] <= maximum, "CAPACITY_BLOCKED")
    return {"fileId": file["id"], "parseResultId": file["parseResultId"], "planSha256": digest(canonical(plan)),
            "plannedCalls": planned, "maximumModelCalls": planned * 2, "maxPlannedCalls": maximum}


def file_digest(path):
    with Path(path).open("rb") as stream:
        hasher = hashlib.sha256()
        while chunk := stream.read(1024 * 1024):
            hasher.update(chunk)
        return hasher.hexdigest()


def process_alive(pid):
    if os.name == "nt":
        import ctypes
        from ctypes import wintypes
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        kernel.OpenProcess.restype = wintypes.HANDLE
        kernel.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        handle = kernel.OpenProcess(0x1000, False, pid)
        if not handle:
            return False
        try:
            code = wintypes.DWORD()
            return bool(kernel.GetExitCodeProcess(handle, ctypes.byref(code))) and code.value == 259
        finally:
            kernel.CloseHandle(handle)
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def runtime_evidence(path, *, expected_sha, root=WORKTREE):
    target = scoped_path(path, root, "RUNTIME_IDENTITY_UNPROVEN")
    require(target.is_file() and target.stat().st_size <= 64_000, "RUNTIME_IDENTITY_UNPROVEN")
    raw = target.read_bytes()
    try:
        value = json.loads(raw)
    except (ValueError, UnicodeError):
        raise SafeFailure("RUNTIME_IDENTITY_UNPROVEN") from None
    result = validate_runtime(value, expected_sha=expected_sha)
    artifact = scoped_path(value["java"]["artifactPath"], root, "RUNTIME_IDENTITY_UNPROVEN")
    require(artifact.is_file() and artifact.stat().st_size <= 500_000_000, "RUNTIME_IDENTITY_UNPROVEN")
    require(file_digest(artifact) == value["java"]["artifactSha256"], "RUNTIME_ARTIFACT_HASH_MISMATCH")
    require(result["javaProcessId"] != result["pythonProcessId"] and process_alive(result["javaProcessId"])
            and process_alive(result["pythonProcessId"]), "RUNTIME_PROCESS_IDENTITY_UNPROVEN")
    return {**result, "runtimeEvidenceSha256": digest(raw)}


class SafeParser(argparse.ArgumentParser):
    def error(self, message):
        # argparse's default echoes unknown arguments, including pasted credentials.
        raise SafeFailure("INVALID_OPTIONS")


def main(argv=None):
    api = ledger = secret = None
    try:
        parser = SafeParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
        parser.add_argument("--phase", required=True,
                            help="verify/create/upload/poll/authority/extract/factscheck/reconcile; other phases SAFE_UNSUPPORTED")
        parser.add_argument("--case", choices=CASES)
        parser.add_argument("--expected-sha", "-ExpectedSha", dest="expected_sha", help="explicit expected running candidate, full 40 hex")
        parser.add_argument("--runtime-evidence", help="fresh root-verified Java+Python identity JSON inside the worktree")
        parser.add_argument("--ledger", default=str(TEST_ROOT / "ledger.jsonl"))
        parser.add_argument("--file-sha", help="ONE approved ordinary file SHA256; mandatory for authority/extract")
        parser.add_argument("--max-planned-calls", type=int,
                            help="extract only: explicit cap on WORST CASE model calls, including one repair per batch")
        parser.add_argument("--plan-evidence", help="extract only: ROOT_VERIFIED_OFFLINE_FACT_PLAN JSON; required, never inferred")
        parser.add_argument("--action", choices=["role", "same", "exclude", "resume-role", "resume-identity"])
        parser.add_argument("--role", choices=sorted(ROLES))
        parser.add_argument("--operation", help="original ledger operation for GET-only reconciliation")
        parser.add_argument("--decision-id", help="exact decision GET for unknown authority; does not clear UNKNOWN")
        parser.add_argument("--credentials-stdin", action="store_true", help="memory-only exact {username:test_admin,password:...} JSON")
        parser.add_argument("--write-fixture-summary", action="store_true", help="verify only: append SHA/count observations to ignored ledger")
        args = parser.parse_args(argv)
        supported = {"verify", "create", "upload", "poll", "authority", "extract", "factscheck", "reconcile"}
        require(args.phase in supported, "SAFE_UNSUPPORTED")
        if args.phase != "verify":
            require(args.case in CASES, "CASE_REQUIRED")
            require(isinstance(args.expected_sha, str) and re.fullmatch(r"[0-9a-f]{40}", args.expected_sha), "EXPECTED_SHA_REQUIRED")
            require(args.runtime_evidence, "RUNTIME_IDENTITY_UNPROVEN")
        if args.file_sha is not None:
            require(HASH.fullmatch(args.file_sha), "INVALID_FILE_SHA")
        if args.phase in {"authority", "extract"}:
            require(args.file_sha is not None, "ONE_FILE_SHA_REQUIRED")
        if args.phase == "extract":
            require(type(args.max_planned_calls) is int and 1 <= args.max_planned_calls <= 1000
                    and args.plan_evidence is not None, "CAPACITY_BLOCKED")
        if args.phase == "authority":
            require(args.action is not None, "EXPLICIT_AUTHORITY_ACTION_REQUIRED")
            require(args.action != "role" or args.role is not None, "EXPLICIT_ROLE_REQUIRED")
        if args.phase == "reconcile":
            require(args.operation is not None and OP.fullmatch(args.operation), "OPERATION_REQUIRED")
        package = Package()
        if args.phase == "verify":
            if args.write_fixture_summary:
                ledger = Ledger(args.ledger)
                for case in CASES:
                    ledger.note(case, "fixture", package.zip_summaries[case])
            print(canonical({"status": "VERIFIED_OFFLINE", "packageSha256": PACKAGE_SHA, "fileCount": 63,
                             "caseCount": 6, "projectZips": package.zip_summaries, "businessAcceptance": "NOT_VERIFIED"}))
            return 0
        identity = runtime_evidence(args.runtime_evidence, expected_sha=args.expected_sha)
        plan = None
        if args.phase == "extract":
            plan_path = scoped_path(args.plan_evidence, WORKTREE, "CAPACITY_BLOCKED")
            require(plan_path.is_file() and plan_path.stat().st_size <= 64_000, "CAPACITY_BLOCKED")
            try:
                plan = json.loads(plan_path.read_bytes())
            except (ValueError, UnicodeError):
                raise SafeFailure("CAPACITY_BLOCKED") from None
        ledger = Ledger(args.ledger)
        bindings = [event["result"] for event in ledger.events if event["operation"] == "binding"]
        binding = {"sourceSha": args.expected_sha, "packageSha256": PACKAGE_SHA,
                   "artifactSha256": identity["artifactSha256"], "javaInputSha256": identity["javaInputSha256"],
                   "pythonInputSha256": identity["pythonInputSha256"]}
        require(not bindings or all(value == binding for value in bindings), "LEDGER_RUNTIME_BINDING_CHANGED")
        if not bindings:
            ledger.note(args.case, "binding", binding)
        ledger.note(args.case, "runtime", identity)
        # No config/environment sourcing, storageState or credential file support.
        if args.credentials_stdin:
            secret = credentials(sys.stdin.read(8193))
        else:
            require(sys.stdin.isatty(), "INTERACTIVE_CREDENTIALS_OR_STDIN_REQUIRED")
            secret = credentials(canonical({"username": "test_admin", "password": getpass.getpass("test_admin password: ")}))
        api = Api(ledger, readonly=args.phase in {"poll", "factscheck", "reconcile"})
        api.login(secret)
        secret["password"] = ""
        driver = Driver(api, package, args.case)
        if args.phase == "create":
            result = driver.create()
        elif args.phase == "upload":
            result = driver.upload(args.file_sha)
        elif args.phase == "poll":
            result = driver.extraction_read(driver.file(args.file_sha)) if args.file_sha else driver.poll()
        elif args.phase == "authority":
            result = driver.authority(args.file_sha, args.action, args.role)
        elif args.phase == "extract":
            result = driver.extract(args.file_sha, plan=plan, max_planned_calls=args.max_planned_calls,
                                    expected_sha=args.expected_sha)
        elif args.phase == "factscheck":
            result = driver.factscheck()
        else:
            result = rediscover(api, args.case, args.operation, decision_id=args.decision_id)
        print(canonical({"status": "OBSERVED_HTTP_PHASE", "phase": args.phase, "case": args.case,
                         "observed": result, "businessAcceptance": "NOT_VERIFIED"}))
        return 0
    except (SafeFailure, KeyboardInterrupt, Exception) as error:
        code = error.code if isinstance(error, SafeFailure) else (
            "INTERRUPTED_OUTCOME_UNKNOWN" if isinstance(error, KeyboardInterrupt) else "HARNESS_UNEXPECTED_ERROR")
        print(canonical({"status": "NOT_VERIFIED", "safeCode": code}))
        return 1
    finally:
        if secret is not None:
            secret["password"] = ""
        if api is not None:
            api.close()
        if ledger is not None:
            ledger.close()


if __name__ == "__main__":
    logging.disable(logging.CRITICAL)
    raise SystemExit(main())
