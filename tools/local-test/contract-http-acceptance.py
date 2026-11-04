"""Root-operated, synthetic-only HTTP acceptance; preparation is NOT live acceptance.

Offline: python tools/local-test/contract-http-acceptance.py --phase verify-fixtures
Live: root pipes exactly {"username":...,"password":...} JSON through STDIN in memory.
      --phase long-review (one fresh receipt; no automatic write recovery/retry)
      --phase reopen --receipts .runtime/contract-repair-test/http-long-review-receipt.json

Origin is fixed loopback. No config sourcing, credential arguments/environment, raw
responses, source text, cookies, tokens, report bodies or provider identities in output.
Reopen authenticates a fresh session, then performs business GETs only, constrained
to the saved identities. /contract-fact-extractions/latest has no exact-attempt API;
it is constrained to the saved file and MUST still return the saved attempt ID.
Keys and payload hashes are fsynced before every business POST/PATCH. An uncertain
write remains uncertain and blocks replay; this utility never sends a new key for it.
Root owns real services, database/platform checks, restart and final acceptance.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
from html.parser import HTMLParser
import io
import json
import logging
import math
import os
from pathlib import Path, PurePosixPath
import re
import struct
import sys
import time
from decimal import Decimal
from uuid import UUID, uuid4
import xml.etree.ElementTree as ET
import zipfile

import httpx

ORIGIN = "http://127.0.0.1:8888"
WORKTREE = Path(__file__).resolve().parents[2]
TEST_ROOT = WORKTREE / ".runtime/contract-repair-test"
FIXTURE_ROOT = WORKTREE / ".runtime/contract-repair-input-20261014/synthetic_contract_regression"
APPROVED_MANIFEST_HASH = "4fe9d34178f19739efb9a6117505cafc00a6420a305ba7fe072faca5bbe86151"
APPROVED_DOCX_HASH = "9a4e5c8a9d4e75b72013fb31f7323667de1afbbd3e1df8d5b53bcdac24f591ea"
APPROVED_SOURCE_HASH = "1cecc9c14b4d8af2736ff5c768969af107f63daa4fccc70dd3991b027d0b8b92"
AMINI_TIMEOUT_SECONDS = 1000  # Over the real 900-second GLOBAL model budget, not per batch.
FILE_TIMEOUT_SECONDS = 1200
RUN_TIMEOUT_SECONDS = 1800
REPORT_TIMEOUT_SECONDS = 120
DEFAULT_TIMEOUT_SECONDS = 30
POLL_SECONDS = 2
CATEGORY_IDS = ["QUANTITATIVE", "DRAFT_STRUCTURE", "BREACH", "TERM", "PARTY_QUALIFICATION",
                "ATTACHMENT_COMPLETENESS", "EFFECTIVENESS_COPIES", "CLAUSE_RISK"]
STEP_TYPES = {"MATERIAL_VALIDATION", "F02_DETERMINISTIC", "SEMANTIC_AI", "BREACH", "TERM"}
F02_RULES = {"F02_AMOUNT_WORDS_MATCH", "F02_LINE_ITEM_ARITHMETIC", "F02_LINE_SUM_MATCH",
             "F02_AWARD_AMOUNT_MATCH", "F02_AWARD_SUPPLIER_MATCH"}
OPERATIONS = {"createProject", "confirmMetadata", "uploadContract", "confirmRole", "confirmIdentity",
              "resumeRoleAssessment", "resumeIdentityAssessment", "resumeFactExtraction",
              "resumeCandidateMaterialization", "confirmCandidate", "promoteCandidate",
              "createReviewRun", "generateReport"}
ID_FIELDS = {"projectId", "fileId", "jobId", "parseResultId", "roleAssessmentId", "roleDecisionId",
             "identityAssessmentId", "identityDecisionId", "extractionAttemptId", "candidateId",
             "confirmationId", "contractId", "runId", "reportId"}
COUNT_FIELDS = {"sourceCodePoints", "canonicalCodePoints", "segmentCount", "batchesPerScope",
                "modelCallCount", "rejectionCount", "acceptedCount", "candidateCount", "stepCount", "reportBytes"}
HASH_FIELDS = {"sourceFileSha256", "sourceTextSha256", "canonicalTextSha256", "tailSha256", "inputFingerprint", "projectionSha256", "reportSha256",
               "confirmationPayloadSha256"}
CHECK_FIELDS = {"originalSyntheticHashVerified", "canonicalFullTextAndTailVerified", "humanRoleAndIdentity",
                "allFactBatchesAudited", "normalHumanSyntheticConfirmation", "serverWorkerFiveSteps",
                "canonicalProjectionAndReportMatch", "frozenReportReadBytesStable", "readOnlyReopen"}
HASH_RE = re.compile(r"^[0-9a-f]{64}$")
CODE_RE = re.compile(r"^[A-Z][A-Z0-9_]{0,95}$")


class SafeFailure(Exception):
    """Only controlled local codes, never an exception message from transport or server."""
    def __init__(self, code: str):
        self.code = code if CODE_RE.fullmatch(code) else "UNSAFE_ERROR_CODE"
        super().__init__(self.code)


def check(condition, code):
    if not condition:
        raise SafeFailure(code)


def sha(value):
    return hashlib.sha256(value.encode("utf-8") if isinstance(value, str) else value).hexdigest()


def canonical_json(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"),
                      default=lambda x: str(x) if isinstance(x, Decimal) else _unsupported_json())


def _unsupported_json():
    raise SafeFailure("INVALID_JSON_TYPE")


def resource_id(value):
    try:
        check(isinstance(value, str) and str(UUID(value)) == value.lower(), "INVALID_RESOURCE_ID")
    except (ValueError, AttributeError, TypeError):
        raise SafeFailure("INVALID_RESOURCE_ID") from None
    return value


def hash_value(value):
    check(isinstance(value, str) and HASH_RE.fullmatch(value), "INVALID_HASH")
    return value


def natural(value):
    return type(value) is int and 0 <= value <= 100_000_000


def read_inside(root: Path, relative: str):
    item = PurePosixPath(relative)
    check(isinstance(relative, str) and not item.is_absolute() and not item.drive
          and ".." not in item.parts and "\\" not in relative and ":" not in relative, "UNSAFE_FIXTURE_PATH")
    target = (root / item).resolve()
    check(target.is_relative_to(root.resolve()) and target != root.resolve(), "UNSAFE_FIXTURE_PATH")
    check(target.is_file() and target.stat().st_size <= 8_000_000, "FIXTURE_UNAVAILABLE")
    return target, target.read_bytes()


@dataclass(frozen=True)
class Fixtures:
    docx_path: Path
    docx_sha256: str
    source_text: str
    canonical_text: str
    contract_title: str


def verify_fixtures(root=FIXTURE_ROOT):
    _, raw = read_inside(root, "manifest.json")
    check(sha(raw) == APPROVED_MANIFEST_HASH, "UNAPPROVED_MANIFEST")
    manifest = json.loads(raw)
    check(manifest.get("synthetic") is True and manifest.get("no_real_customer_data") is True
          and manifest.get("generated_for") == "local-regression-not-customer-acceptance", "FIXTURE_NOT_SYNTHETIC")
    selected = {}
    for relative, metadata in manifest["files"].items():
        if metadata["sha256"] not in {APPROVED_DOCX_HASH, APPROVED_SOURCE_HASH}:
            continue
        target, content = read_inside(root, relative)
        check(sha(content) == metadata["sha256"], "FIXTURE_HASH_MISMATCH")
        if "bytes" in metadata:
            check(len(content) == metadata["bytes"], "FIXTURE_SIZE_MISMATCH")
        selected[metadata["sha256"]] = (target, content)
    check(set(selected) == {APPROVED_DOCX_HASH, APPROVED_SOURCE_HASH}, "FIXTURE_SELECTION_UNPROVEN")
    source_text = selected[APPROVED_SOURCE_HASH][1].decode("utf-8")
    check(len(source_text) == 8235, "SOURCE_CODEPOINT_MISMATCH")
    # This is an oracle for ONE hash-approved fixture, not a second production DOCX parser.
    with zipfile.ZipFile(io.BytesIO(selected[APPROVED_DOCX_HASH][1])) as container:
        check(container.getinfo("word/document.xml").file_size < 1_000_000, "FIXTURE_XML_TOO_LARGE")
        body = ET.fromstring(container.read("word/document.xml"))
    ns = {"w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main"}
    check(not body.findall("w:body/w:tbl", ns), "UNSUPPORTED_APPROVED_FIXTURE_LAYOUT")
    paragraphs = []
    for paragraph in body.findall("w:body/w:p", ns):
        parts = []
        for element in paragraph.iter():
            if element.tag == "{" + ns["w"] + "}t": parts.append(element.text or "")
            elif element.tag in {"{" + ns["w"] + "}br", "{" + ns["w"] + "}cr"}: parts.append("\n")
            elif element.tag == "{" + ns["w"] + "}tab": parts.append("\t")
        text = "".join(parts)
        if text.strip(): paragraphs.append(text)
    canonical = "\n".join(paragraphs)
    check(len(canonical) == 8257 and canonical.endswith(source_text)
          and sha(canonical) == "2a4ca8bf088224bc98a9d5314d8b146b164d19fef3fee26e779a210d36ccd47f",
          "FIXTURE_CANONICAL_LAYOUT_UNPROVEN")
    return Fixtures(selected[APPROVED_DOCX_HASH][0], APPROVED_DOCX_HASH, source_text, canonical, paragraphs[0])


def segment_catalog(text, parse_id):
    resource_id(parse_id)
    check(0 < len(text) <= 30720, "TOTAL_INPUT_BOUND_EXCEEDED")
    result = []
    start = row_start = 0
    while start < len(text):
        newline = text.find("\n", start)
        row_end = len(text) if newline < 0 else newline + 1
        end = min(row_end, start + 240)
        result.append({"segmentId": f"segment-{len(result) + 1:02d}", "start": start, "end": end,
                       "text": text[start:end], "sourceLocator": f"parse-result:{parse_id}:canonical-codepoint:{start}-{end}",
                       "rowIdentity": "row:" + sha(f"contract-source-row-v1:{parse_id}:{row_start}:{row_end}")})
        start = end
        if start == row_end: row_start = start
    check(len(result) <= 256, "TOTAL_SEGMENT_BOUND_EXCEEDED")
    return result


def segment_count(text):
    return len(segment_catalog(text, "00000000-0000-0000-0000-000000000001"))


def batch_fingerprint(input_hash, scope, batch, batches):
    values = ["contract-fact-call-v2", hash_value(input_hash), scope, str(batch), str(batches)]
    digest = hashlib.sha256()
    for value in values:
        raw = value.encode("utf-8")
        digest.update(struct.pack(">I", len(raw)))
        digest.update(raw)
    return digest.hexdigest()


def validate_receipt(value):
    try:
        top = {"schemaVersion", "origin", "phase", "syntheticManifestSha256", "status", "operations", "ids",
               "counts", "hashes", "checks", "statuses", "audit", "stepIds", "failureCode"}
        check(isinstance(value, dict) and not set(value) - top and value["schemaVersion"] == 1
              and value["origin"] == ORIGIN and value["syntheticManifestSha256"] == APPROVED_MANIFEST_HASH
              and type(value["schemaVersion"]) is int and value["phase"] in {"long-review", "reopen"}
              and value["status"] in {"PREPARED", "RUNNING", "PASS", "FAILED", "UNKNOWN"}, "UNSAFE_RECEIPT")
        for key, identifier in value["ids"].items():
            check(key in ID_FIELDS, "UNSAFE_RECEIPT")
            resource_id(identifier)
        for key, count in value["counts"].items(): check(key in COUNT_FIELDS and natural(count), "UNSAFE_RECEIPT")
        for key, digest in value["hashes"].items(): check(key in HASH_FIELDS and HASH_RE.fullmatch(digest), "UNSAFE_RECEIPT")
        for key, passed in value["checks"].items(): check(key in CHECK_FIELDS and type(passed) is bool, "UNSAFE_RECEIPT")
        for key, status in value["statuses"].items():
            check(key in {"fileJobStatus", "extractionStatus", "runStatus", "reportStatus"}
                  and status in {"QUEUED", "PROCESSING", "SUCCEEDED", "FAILED", "UNKNOWN", "IN_PROGRESS",
                                 "OUTCOME_UNKNOWN", "PENDING", "RUNNING", "GENERATING", "COMPLETED", "PARTIALLY_COMPLETED", "AVAILABLE"}, "UNSAFE_RECEIPT")
        for name, operation in value["operations"].items():
            check(name in OPERATIONS and set(operation) == {"key", "keySha256", "requestSha256", "status"}
                  and re.fullmatch(r"w4:[0-9a-f-]{36}", operation["key"])
                  and operation["keySha256"] == sha(operation["key"])
                  and HASH_RE.fullmatch(operation["requestSha256"])
                  and operation["status"] in {"SENDING", "CONFIRMED", "WRITE_OUTCOME_UNKNOWN", "REJECTED"}, "UNSAFE_RECEIPT")
            resource_id(operation["key"][3:])
        for identifier in value["stepIds"]: resource_id(identifier)
        for call in value["audit"]:
            check(set(call) == {"callAuditId", "scope", "callOrdinal", "phase", "outcome", "batchStatus", "requestFingerprint"}
                  and call["scope"] in {"CORE", "LINE_ITEMS"} and natural(call["callOrdinal"]) and call["callOrdinal"] > 0
                  and call["phase"] in {"TRANSPORT", "REPAIR"} and call["outcome"] in {"SUCCEEDED", "FAILED"}
                  and call["batchStatus"] in {"EMPTY", "PARTIAL", "COMPLETE", "FAILED", "UNKNOWN"}, "UNSAFE_RECEIPT")
            resource_id(call["callAuditId"])
            hash_value(call["requestFingerprint"])
        if "failureCode" in value: check(CODE_RE.fullmatch(value["failureCode"]), "UNSAFE_RECEIPT")
    except (KeyError, TypeError, ValueError, AttributeError, SafeFailure):
        raise SafeFailure("UNSAFE_RECEIPT") from None
    return value


class Receipt:
    def __init__(self, path, value):
        self.path, self.value = Path(path), validate_receipt(value)

    @classmethod
    def create(cls, path):
        path = Path(path)
        check(not path.exists(), "EXISTING_RECEIPT_REOPEN_REQUIRED")
        receipt = cls(path, {"schemaVersion": 1, "origin": ORIGIN, "phase": "long-review",
                            "syntheticManifestSha256": APPROVED_MANIFEST_HASH, "status": "PREPARED",
                            "operations": {}, "ids": {}, "counts": {}, "hashes": {}, "checks": {}, "statuses": {},
                            "audit": [], "stepIds": []})
        # Exclusive initial claim prevents two harnesses from replacing each other's keys.
        path.parent.mkdir(parents=True, exist_ok=True)
        try:
            with path.open("xb") as stream:
                stream.write(canonical_json(receipt.value).encode("utf-8"))
                stream.flush()
                os.fsync(stream.fileno())
        except FileExistsError:
            raise SafeFailure("EXISTING_RECEIPT_REOPEN_REQUIRED") from None
        return receipt

    @classmethod
    def load(cls, path):
        path = Path(path)
        check(path.is_file() and path.stat().st_size <= 256_000, "RECEIPT_UNAVAILABLE")
        try: value = json.loads(path.read_bytes())
        except (ValueError, UnicodeError): raise SafeFailure("UNSAFE_RECEIPT") from None
        return cls(path, value)

    def save(self):
        validate_receipt(self.value)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_name(self.path.name + "." + str(uuid4()) + ".pending")
        try:
            with temporary.open("xb") as stream:
                stream.write(canonical_json(self.value).encode("utf-8"))
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.path)
        finally:
            if temporary.exists(): temporary.unlink()

    def set_id(self, key, identifier):
        check(key in ID_FIELDS, "UNSAFE_RECEIPT")
        identifier = resource_id(identifier)
        check(key not in self.value["ids"] or self.value["ids"][key] == identifier, "IMMUTABLE_RESOURCE_ID_CHANGED")
        self.value["ids"][key] = identifier
        self.save()
        return identifier

    def begin(self, name, request_hash):
        check(name in OPERATIONS and name not in self.value["operations"], "OPERATION_ALREADY_RECORDED")
        key = "w4:" + str(uuid4())
        self.value["operations"][name] = {"key": key, "keySha256": sha(key), "requestSha256": hash_value(request_hash), "status": "SENDING"}
        self.value["status"] = "RUNNING"
        self.save()  # MUST finish before HTTP dispatch, including disk flush.
        return key

    def finish(self, name, state):
        self.value["operations"][name]["status"] = state
        self.save()


def credentials(raw):
    check(isinstance(raw, bytes) and len(raw) <= 16384, "INVALID_CREDENTIAL_INPUT")
    try: value = json.loads(raw)
    except (ValueError, UnicodeError): raise SafeFailure("INVALID_CREDENTIAL_INPUT") from None
    check(isinstance(value, dict) and set(value) == {"username", "password"}
          and all(isinstance(value[k], str) and 0 < len(value[k]) <= 256 for k in value), "INVALID_CREDENTIAL_INPUT")
    return value


class Api:
    def __init__(self, receipt, *, transport=None):
        self.receipt = receipt
        self.readonly = False
        self.client = httpx.Client(base_url=ORIGIN, trust_env=False, follow_redirects=False,
                                   timeout=httpx.Timeout(DEFAULT_TIMEOUT_SECONDS, connect=5), transport=transport)

    def close(self):
        self.client.cookies.clear()
        self.client.headers.clear()
        self.client.close()

    def _request(self, method, path, *, timeout=DEFAULT_TIMEOUT_SECONDS, **kwargs):
        check(not self.readonly or method == "GET", "READ_ONLY_PHASE_WRITE_FORBIDDEN")
        check(isinstance(path, str) and path.startswith("/api/v1/")
              and re.fullmatch(r"/[A-Za-z0-9/_-]+", path) and "//" not in path
              and ".." not in path and "\\" not in path, "UNSAFE_HTTP_PATH")
        check(method in {"GET", "POST", "PATCH"} and 0 < timeout <= AMINI_TIMEOUT_SECONDS, "INVALID_HTTP_OPERATION")
        url = httpx.URL(ORIGIN + path)
        check(url.host == "127.0.0.1" and url.port == 8888 and url.scheme == "http", "UNSAFE_HTTP_ORIGIN")
        try:
            response = self.client.request(method, url, timeout=httpx.Timeout(timeout, connect=5), **kwargs)
        except httpx.HTTPError:
            raise SafeFailure("WRITE_OUTCOME_UNKNOWN" if method != "GET" else "READ_TRANSPORT_FAILURE") from None
        check(len(response.content) <= 16_000_000, "RESPONSE_BOUND_EXCEEDED")
        return response

    @staticmethod
    def _envelope(response, write=False):
        uncertain = "WRITE_OUTCOME_UNKNOWN" if write else "READ_HTTP_FAILURE"
        if response.status_code >= 500: raise SafeFailure(uncertain)
        if 300 <= response.status_code < 400: raise SafeFailure(uncertain)
        try: payload = response.json(parse_float=Decimal)
        except (ValueError, UnicodeError): raise SafeFailure(uncertain) from None
        check(isinstance(payload, dict) and type(payload.get("code")) is int, uncertain)
        code = payload["code"]
        data = payload.get("data")
        if isinstance(data, dict) and data.get("classification") == "RECOVERY_OUTCOME_UNCERTAIN":
            raise SafeFailure(uncertain)
        if response.status_code not in {200, 202} or code != 200:
            if write and 400 <= code <= 499:
                raise SafeFailure(f"WRITE_REJECTED_{code}")
            raise SafeFailure(uncertain)
        return data

    def get(self, path):
        return self._envelope(self._request("GET", path))

    def artifact(self, path):
        response = self._request("GET", path)
        check(response.status_code == 200 and response.headers.get("Content-Type", "").startswith("text/html")
              and 0 < len(response.content) <= 8_000_000, "REPORT_ARTIFACT_UNAVAILABLE")
        return response.content

    def write(self, name, method, path, body=None, *, upload=None, timeout=DEFAULT_TIMEOUT_SECONDS):
        check(not self.readonly and method in {"POST", "PATCH"}, "READ_ONLY_PHASE_WRITE_FORBIDDEN")
        request_hash = sha(canonical_json({"method": method, "path": path, "body": body,
                                          "fileSha256": sha(upload[1]) if upload else None}))
        key = self.receipt.begin(name, request_hash)
        try:
            options = {"headers": {"Idempotency-Key": key}}
            if upload is not None: options["files"] = {"file": upload}
            elif body is not None: options["json"] = body
            data = self._envelope(self._request(method, path, timeout=timeout, **options), write=True)
        except SafeFailure as failure:
            self.receipt.finish(name, "REJECTED" if failure.code.startswith("WRITE_REJECTED_") else "WRITE_OUTCOME_UNKNOWN")
            raise
        self.receipt.finish(name, "CONFIRMED")
        return data

    def login(self, value):
        self._csrf()
        self._envelope(self._request("POST", "/api/v1/auth/login", json=value), write=True)
        self._csrf()  # Spring rotates CSRF/session identity on authentication.

    def _csrf(self):
        result = self.get("/api/v1/auth/csrf")
        check(isinstance(result, dict) and result.get("headerName") in {"X-CSRF-TOKEN", "X-XSRF-TOKEN"}
              and isinstance(result.get("token"), str) and 0 < len(result["token"]) <= 512, "INVALID_CSRF_RESPONSE")
        self.client.headers[result["headerName"]] = result["token"]


def validate_file(file, fixtures, ids):
    check(file["id"] == ids["fileId"] and file["projectId"] == ids["projectId"]
          and file["fileSha256"] == fixtures.docx_sha256 and file["parseStatus"] == "PARSED"
          and file["ingestionStatus"] == "AVAILABLE", "FILE_IDENTITY_UNPROVEN")
    text = file.get("parsedText")
    check(isinstance(text, str) and len(text) == len(fixtures.canonical_text)
          and sha(text) == sha(fixtures.canonical_text)
          and sha(text[-256:]) == sha(fixtures.canonical_text[-256:])
          and file.get("canonicalTextSha256") == sha(fixtures.canonical_text), "CANONICAL_TEXT_OR_TAIL_MISMATCH")
    return file


def validate_fact_audit(projection, fixtures, ids):
    attempt = projection.get("attempt")
    check(isinstance(attempt, dict), "FACT_ATTEMPT_UNAVAILABLE")
    check(attempt.get("status") == "SUCCEEDED", "FACT_ATTEMPT_NOT_SUCCEEDED")
    for key in ["projectId", "fileId", "parseResultId", "roleDecisionId", "identityDecisionId"]:
        check(attempt.get(key) == ids[key], "FACT_AUTHORITY_IDENTITY_MISMATCH")
    check(attempt["fileSha256"] == fixtures.docx_sha256 and attempt["canonicalTextSha256"] == sha(fixtures.canonical_text)
          and attempt["effectiveRole"] == "CONTRACT_BODY" and projection["currentParseResultId"] == ids["parseResultId"],
          "FACT_INPUT_IDENTITY_MISMATCH")
    attempt_id = resource_id(attempt["attemptId"])
    input_hash = hash_value(attempt["inputFingerprint"])
    segments = segment_catalog(fixtures.canonical_text, ids["parseResultId"])
    batches = math.ceil(len(segments) / 32)
    expected = {}
    for index, (scope, batch) in enumerate((s, b) for s in ["CORE", "LINE_ITEMS"] for b in range(1, batches + 1)):
        expected[batch_fingerprint(input_hash, scope, batch, batches)] = (scope, {index * 2 + 1, index * 2 + 2})
    calls = projection["modelCalls"]
    check(isinstance(calls, list) and 0 < len(calls) <= len(expected) * 2, "FACT_AUDIT_BOUND_MISMATCH")
    grouped = {}
    audit = []
    seen_ordinals, seen_ids = set(), set()
    for call in calls:
        fingerprint = hash_value(call["requestFingerprint"])
        check(fingerprint in expected, "FACT_BATCH_FINGERPRINT_MISMATCH")
        scope, ordinals = expected[fingerprint]
        identifier = resource_id(call["callAuditId"])
        check(call.get("factExtractionAttemptId") == attempt_id and call["scope"] == scope
              and call["callOrdinal"] in ordinals and call["callOrdinal"] not in seen_ordinals
              and identifier not in seen_ids and call["batchStatus"] in {"EMPTY", "PARTIAL", "COMPLETE"},
              "FACT_BATCH_AUDIT_IDENTITY_MISMATCH")
        seen_ordinals.add(call["callOrdinal"])
        seen_ids.add(identifier)
        grouped.setdefault(fingerprint, []).append(call)
        audit.append({key: call[key] for key in ["callAuditId", "scope", "callOrdinal", "phase", "outcome", "batchStatus", "requestFingerprint"]})
    check(set(grouped) == set(expected), "FACT_FINAL_BATCH_MISSING")
    for fingerprint, group in grouped.items():
        first = min(expected[fingerprint][1])
        check(any(c["outcome"] == "SUCCEEDED" for c in group)
              and any(c["callOrdinal"] == first and c["phase"] == "TRANSPORT" for c in group)
              and all(c["phase"] == ("TRANSPORT" if c["callOrdinal"] == first else "REPAIR") for c in group), "FACT_BATCH_NOT_PROVEN_COMPLETE")
    catalog = {s["segmentId"]: s for s in segments}
    accepted = 0
    for candidate in projection["candidates"]:
        check(candidate.get("attemptId") == attempt_id, "FACT_CANDIDATE_OWNER_MISMATCH")
        # Rejected candidates are retained specifically because their evidence/value
        # may be invalid. They are audit, never a claim of proved business facts.
        if candidate.get("validationStatus") != "ACCEPTED":
            check(candidate.get("publishedExtractionId") is None, "REJECTED_FACT_WAS_PUBLISHED")
            continue
        segment = catalog.get(candidate.get("segmentId"))
        start, end = candidate.get("excerptStart"), candidate.get("excerptEnd")
        check(segment is not None and candidate.get("sourceLocator") == segment["sourceLocator"]
              and natural(start) and natural(end) and start < end <= len(segment["text"])
              and candidate.get("excerpt") == segment["text"][start:end], "FACT_EVIDENCE_MISMATCH")
        resource_id(candidate.get("publishedExtractionId"))
        accepted += 1
        if str(candidate.get("factType", "")).startswith("LINE_ITEM_"):
            check(candidate.get("groupKey") == "line:" + sha(json.dumps([segment["rowIdentity"]], separators=(",", ":"))),
                  "FACT_ROW_ASSOCIATION_UNPROVEN")
    check(accepted == attempt["acceptedCount"] and natural(attempt["rejectedCount"]), "FACT_AUDIT_COUNTER_MISMATCH")
    return {"attemptId": attempt_id, "inputFingerprint": input_hash, "batchesPerScope": batches,
            "segmentCount": len(segments), "acceptedCount": accepted, "rejectionCount": len(projection["rejections"]),
            "audit": sorted(audit, key=lambda call: call["callOrdinal"])}


def confirmation_payload(fixtures, version):
    # Root explicitly approved human GOODS classification; never claim AI extracted it.
    required = ["合同编号：HT-TEST-2026-001", "甲方：测试单位甲", "乙方：测试供应商乙",
                "统一社会信用代码：91310000TEST000001", "合同总金额：900000.00元", "金额大写：玖拾万元整",
                "设备明细：电脑30台，单价10000.00元，金额300000.00元。",
                "设备明细：打印机20台，单价30000.00元，金额600000.00元。"]
    check(all(line in fixtures.source_text for line in required), "SYNTHETIC_DECLARED_FACT_UNPROVEN")
    return {"expectedVersion": version, "contractNo": "HT-TEST-2026-001", "contractName": fixtures.contract_title,
            "contractType": "GOODS", "partyA": "测试单位甲", "partyB": "测试供应商乙", "partyBCreditCode": "91310000TEST000001",
            "contractAmount": "900000.00", "amountInWords": "玖拾万元整", "lineItems": [
                {"itemKey": "SYNTHETIC-LINE-1", "itemName": "电脑", "basis": "QUANTITY_PRICE", "quantity": "30",
                 "unitPrice": "10000.00", "declaredAmount": "300000.00", "sortNo": 1},
                {"itemKey": "SYNTHETIC-LINE-2", "itemName": "打印机", "basis": "QUANTITY_PRICE", "quantity": "20",
                 "unitPrice": "30000.00", "declaredAmount": "600000.00", "sortNo": 2}], "awardNotice": None}


def poll(read, terminal, pending, timeout, code, *, pause=time.sleep, monotonic=time.monotonic):
    deadline = monotonic() + timeout
    while True:
        check(monotonic() < deadline, code + "_TIMEOUT")
        value = read()
        status = value.get("status")
        if status in terminal: return value
        check(status not in {"UNKNOWN", "OUTCOME_UNKNOWN"}, code + "_OUTCOME_UNKNOWN")
        check(status in pending, code + "_TERMINAL_NOT_SUCCESS")
        check(monotonic() < deadline, code + "_TIMEOUT")
        pause(min(POLL_SECONDS, max(0, deadline - monotonic())))


def get_file(api, ids):
    detail = api.get(f"/api/v1/projects/{ids['projectId']}")
    matching = [f for f in detail["files"] if f["id"] == ids["fileId"]]
    check(len(matching) == 1, "FILE_REOPEN_IDENTITY_UNPROVEN")
    return matching[0]


def resume(api, receipt, file, name, blocker):
    progression = file["progression"]
    check(progression.get("blocker") == blocker, "CURRENT_BLOCKER_MISMATCH")
    fingerprint = hash_value(progression.get("recoveryInputFingerprint"))
    ids = receipt.value["ids"]
    return api.write(name, "POST", f"/api/v1/projects/{ids['projectId']}/files/{ids['fileId']}/progression/resume",
                     {"expectedBlocker": blocker, "expectedRecoveryInputFingerprint": fingerprint},
                     timeout=AMINI_TIMEOUT_SECONDS)


def validate_run(run, receipt, fixtures):
    ids = receipt.value["ids"]
    check(run["runId"] == ids["runId"] and run["projectId"] == ids["projectId"] and run["contractId"] == ids["contractId"]
          and run["executionMode"] == "SERVER_WORKER" and run["status"] == "COMPLETED"
          and run["sourceCandidateId"] == ids["candidateId"] and run["sourceConfirmationId"] == ids["confirmationId"], "RUN_IDENTITY_OR_EXECUTION_UNPROVEN")
    steps = run["steps"]
    check(len(steps) == 5 and {s["stepType"] for s in steps} == STEP_TYPES
          and all(s["status"] == "COMPLETED" and s["runId"] == ids["runId"] for s in steps)
          and {s["stepId"] for s in steps} == set(receipt.value["stepIds"]), "FIVE_STEPS_NOT_COMPLETED")
    materials = run["materials"]
    check(len(materials) == 1 and materials[0]["fileId"] == ids["fileId"]
          and materials[0]["fileSha256"] == fixtures.docx_sha256
          and all(materials[0][k] == ids[k] for k in ["projectId", "parseResultId", "roleDecisionId", "identityDecisionId"])
          and materials[0]["canonicalTextSha256"] == sha(fixtures.canonical_text)
          and materials[0]["effectiveRole"] == "CONTRACT_BODY", "FROZEN_MATERIAL_NOT_APPROVED")
    projection = run["businessProjection"]
    check(projection["version"] == 1 and projection["runId"] == ids["runId"] and projection["runStatus"] == "COMPLETED"
          and [c["id"] for c in projection["categories"]] == CATEGORY_IDS, "CANONICAL_PROJECTION_UNAVAILABLE")
    return projection


class ReportText(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts = []

    def handle_data(self, value): self.parts.append(value)


def validate_report(api, run_prefix, report, projection, expected_id):
    check(report["reportId"] == expected_id and report["status"] == "AVAILABLE"
          and report["reportSchemaVersion"] == 3 and report["reviewRunId"] == projection["runId"], "AVAILABLE_V3_REPORT_REQUIRED")
    first, second = api.artifact(f"{run_prefix}/reports/{expected_id}/artifact"), api.artifact(f"{run_prefix}/reports/{expected_id}/artifact")
    check(first == second, "FROZEN_REPORT_CHANGED_ON_GET")
    parser = ReportText()
    parser.feed(first.decode("utf-8"))
    text = "".join(parser.parts)
    values = [projection["scopeStatement"]]
    for category in projection["categories"]:
        values.extend([category["title"], category["statusLabel"], category["summary"], *category["uncoveredScopes"]])
        for item in category["items"]:
            values.extend(item.get(key) for key in ["title", "summary", "actualValue", "expectedValue", "recommendation"])
            values.extend(item.get("typedFacts", []))
            for evidence in item.get("evidence", []): values.extend([evidence.get("fileName"), evidence.get("excerpt")])
    check(all(str(v) in text for v in values if v is not None), "REPORT_CANONICAL_SEMANTICS_DIFFER")
    check(report.get("artifactSize") == len(first), "REPORT_SIZE_MISMATCH")
    return {"reportSha256": sha(first), "reportBytes": len(first), "projectionSha256": sha(canonical_json(projection))}


def long_review(api, receipt, fixtures):
    project = api.write("createProject", "POST", "/api/v1/projects")
    project_id = receipt.set_id("projectId", project["id"])
    api.write("confirmMetadata", "PATCH", f"/api/v1/projects/{project_id}",
              {"expectedVersion": project["versionNo"], "name": "P001办公设备采购项目", "projectNo": "P001", "category": "GOODS"})
    # Recheck bytes immediately before admission; never trust an earlier path hash after mutation.
    file_bytes = fixtures.docx_path.read_bytes()
    check(sha(file_bytes) == fixtures.docx_sha256, "FIXTURE_CHANGED_BEFORE_UPLOAD")
    accepted = api.write("uploadContract", "POST", f"/api/v1/projects/{project_id}/file-jobs", upload=(
        fixtures.docx_path.name, file_bytes, "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
    check(accepted["projectId"] == project_id and accepted["file"]["fileSha256"] == fixtures.docx_sha256, "UPLOAD_IDENTITY_UNPROVEN")
    file_id = receipt.set_id("fileId", accepted["fileId"])
    job_id = receipt.set_id("jobId", accepted["jobId"])
    file_prefix = f"/api/v1/projects/{project_id}/files/{file_id}"
    job = poll(lambda: api.get(file_prefix + "/processing"), {"SUCCEEDED"}, {"QUEUED", "PROCESSING"}, FILE_TIMEOUT_SECONDS, "FILE_JOB")
    check(job["jobId"] == job_id and job["projectId"] == project_id and job["fileId"] == file_id, "JOB_IDENTITY_UNPROVEN")
    receipt.set_id("parseResultId", job["parseResultId"])
    ids = receipt.value["ids"]
    file = validate_file(get_file(api, ids), fixtures, ids)
    check(file["parseResultId"] == ids["parseResultId"], "PARSE_IDENTITY_UNPROVEN")
    receipt.value["counts"].update(sourceCodePoints=len(fixtures.source_text), canonicalCodePoints=len(fixtures.canonical_text))
    receipt.value["hashes"].update(sourceFileSha256=fixtures.docx_sha256, sourceTextSha256=sha(fixtures.source_text),
                                   canonicalTextSha256=sha(fixtures.canonical_text), tailSha256=sha(fixtures.canonical_text[-256:]))
    receipt.value["checks"].update(originalSyntheticHashVerified=True, canonicalFullTextAndTailVerified=True)
    receipt.value["statuses"]["fileJobStatus"] = "SUCCEEDED"
    receipt.save()
    if not file.get("roleAssessmentId"):
        resume(api, receipt, file, "resumeRoleAssessment", "ROLE_ASSESSMENT_PENDING")
        file = validate_file(get_file(api, ids), fixtures, ids)
    receipt.set_id("roleAssessmentId", file["roleAssessmentId"])
    role = api.write("confirmRole", "POST", file_prefix + "/role-confirmations", {
        "assessmentId": ids["roleAssessmentId"], "selectedRole": "CONTRACT_BODY", "expectedVersion": file.get("roleDecisionVersion") or 0,
        "reason": "人工核对已批准的合成回归材料"})
    receipt.set_id("roleDecisionId", role["decisionId"])
    file = validate_file(get_file(api, ids), fixtures, ids)
    if not file.get("identityAssessmentId") or file["progression"].get("blocker") == "IDENTITY_ASSESSMENT_STALE":
        blocker = file["progression"]["blocker"]
        check(blocker in {"IDENTITY_ASSESSMENT_PENDING", "IDENTITY_ASSESSMENT_STALE"}, "IDENTITY_ASSESSMENT_BLOCKER_UNPROVEN")
        resume(api, receipt, file, "resumeIdentityAssessment", blocker)
        file = validate_file(get_file(api, ids), fixtures, ids)
    receipt.set_id("identityAssessmentId", file["identityAssessmentId"])
    identity = api.write("confirmIdentity", "POST", file_prefix + "/identity-confirmations", {
        "assessmentId": ids["identityAssessmentId"], "resolution": "CONFIRM_SAME_PROJECT",
        "expectedVersion": file.get("identityDecisionVersion") or 0, "reason": "人工确认已批准的合成材料属于P001项目"})
    receipt.set_id("identityDecisionId", identity["decisionId"])
    file = validate_file(get_file(api, ids), fixtures, ids)
    check(file["roleDecisionId"] == ids["roleDecisionId"] and file["identityDecisionId"] == ids["identityDecisionId"]
          and file["effectiveRole"] == "CONTRACT_BODY" and file["effectiveRoleSource"] == "HUMAN_CONFIRMATION"
          and file["identityCurrent"] is True and file["effectiveIdentityStatus"] == "SAME_PROJECT", "HUMAN_AUTHORITY_UNPROVEN")
    receipt.value["checks"]["humanRoleAndIdentity"] = True
    receipt.save()
    resume(api, receipt, file, "resumeFactExtraction", "FACT_EXTRACTION_PENDING")
    fact = api.get(file_prefix + "/contract-fact-extractions/latest")
    audit = validate_fact_audit(fact, fixtures, ids)
    receipt.set_id("extractionAttemptId", audit["attemptId"])
    receipt.value["audit"] = audit["audit"]
    receipt.value["counts"].update({k: audit[k] for k in ["batchesPerScope", "segmentCount", "acceptedCount", "rejectionCount"]})
    receipt.value["counts"]["modelCallCount"] = len(audit["audit"])
    receipt.value["hashes"]["inputFingerprint"] = audit["inputFingerprint"]
    receipt.value["statuses"]["extractionStatus"] = "SUCCEEDED"
    receipt.value["checks"]["allFactBatchesAudited"] = True
    receipt.save()
    file = validate_file(get_file(api, ids), fixtures, ids)
    if file["progression"].get("blocker") == "CANDIDATE_MATERIALIZATION_PENDING":
        resume(api, receipt, file, "resumeCandidateMaterialization", "CANDIDATE_MATERIALIZATION_PENDING")
    candidates = api.get(f"/api/v1/projects/{project_id}/contract-candidates")
    candidates = [c for c in candidates if c["sourceContractFileId"] == file_id]
    check(len(candidates) == 1, "CONTRACT_CANDIDATE_UNAVAILABLE")
    candidate_id = receipt.set_id("candidateId", candidates[0]["id"])
    candidate_prefix = f"/api/v1/projects/{project_id}/contract-candidates/{candidate_id}"
    detail = api.get(candidate_prefix)
    check(detail["sourceParseResultId"] == ids["parseResultId"] and detail["sourceRoleDecisionId"] == ids["roleDecisionId"]
          and detail["sourceIdentityDecisionId"] == ids["identityDecisionId"] and detail["candidateAuthoritySource"] == "HUMAN_CONFIRMATION",
          "CANDIDATE_AUTHORITY_UNPROVEN")
    payload = confirmation_payload(fixtures, detail["candidate"]["versionNo"])
    confirmed = api.write("confirmCandidate", "POST", candidate_prefix + "/confirmations", payload)
    confirmation_id = receipt.set_id("confirmationId", confirmed["confirmationId"])
    check(confirmed["candidateId"] == candidate_id and confirmed["projectId"] == project_id
          and confirmed["sourceFileId"] == file_id and confirmed["sourceParseResultId"] == ids["parseResultId"]
          and confirmed["sourceRoleDecisionId"] == ids["roleDecisionId"]
          and confirmed["sourceIdentityDecisionId"] == ids["identityDecisionId"], "CONFIRMATION_IDENTITY_UNPROVEN")
    receipt.value["hashes"]["confirmationPayloadSha256"] = hash_value(confirmed["payloadFingerprint"])
    receipt.value["checks"]["normalHumanSyntheticConfirmation"] = True
    receipt.value["counts"]["candidateCount"] = 1
    receipt.save()
    promoted = api.write("promoteCandidate", "POST", candidate_prefix + "/promote", {
        "expectedVersion": confirmed["resultCandidateVersion"], "confirmationId": confirmation_id})
    check(promoted["projectId"] == project_id and promoted["candidateId"] == candidate_id, "PROMOTION_IDENTITY_UNPROVEN")
    contract_id = receipt.set_id("contractId", promoted["contractId"])
    run_base = f"/api/v1/projects/{project_id}/contracts/{contract_id}/review-runs"
    created = api.write("createReviewRun", "POST", run_base, {
        "expectedContractVersion": promoted["versionNo"], "materialFileIds": [file_id]})
    run_id = receipt.set_id("runId", created["runId"])
    check(created["executionMode"] == "SERVER_WORKER" and len(created["steps"]) == 5, "SERVER_WORKER_REQUIRED")
    receipt.value["stepIds"] = [resource_id(s["stepId"]) for s in created["steps"]]
    receipt.save()
    run_prefix = run_base + "/" + run_id
    run = poll(lambda: api.get(run_prefix), {"COMPLETED"}, {"PENDING", "RUNNING"}, RUN_TIMEOUT_SECONDS, "REVIEW_RUN")
    projection = validate_run(run, receipt, fixtures)
    for step in run["steps"]:
        output = api.get(run_prefix + "/steps/" + step["stepId"])
        check(output["step"]["stepId"] == step["stepId"] and output["step"]["status"] == "COMPLETED", "STEP_OUTPUT_UNPROVEN")
        if step["stepType"] == "F02_DETERMINISTIC":
            checks = output["auditCheckResults"]
            check({c["ruleCode"] for c in checks} == F02_RULES, "F02_FIVE_RULES_UNPROVEN")
            check(all(c["checkStatus"] != "PASS" for c in checks if c["ruleCode"].startswith("F02_AWARD_")), "MISSING_AWARD_CANNOT_PASS")
    receipt.value["checks"]["serverWorkerFiveSteps"] = True
    receipt.value["statuses"]["runStatus"] = "COMPLETED"
    receipt.value["counts"]["stepCount"] = 5
    receipt.save()
    report = api.write("generateReport", "POST", run_prefix + "/reports", {})
    report_id = receipt.set_id("reportId", report["reportId"])
    report = poll(lambda: api.get(run_prefix + "/reports/" + report_id), {"AVAILABLE"}, {"PENDING", "GENERATING"}, REPORT_TIMEOUT_SECONDS, "REPORT")
    proof = validate_report(api, run_prefix, report, projection, report_id)
    receipt.value["hashes"].update({k: proof[k] for k in ["projectionSha256", "reportSha256"]})
    receipt.value["counts"]["reportBytes"] = proof["reportBytes"]
    receipt.value["statuses"]["reportStatus"] = "AVAILABLE"
    receipt.value["checks"].update(canonicalProjectionAndReportMatch=True, frozenReportReadBytesStable=True)
    receipt.value["status"] = "PASS"
    receipt.save()
    return receipt.value


def reopen(api, receipt, fixtures):
    check(receipt.value["status"] == "PASS" and set(receipt.value["ids"]) == ID_FIELDS, "COMPLETE_RECEIPT_REQUIRED")
    api.readonly = True
    ids = receipt.value["ids"]
    validate_file(get_file(api, ids), fixtures, ids)
    file_prefix = f"/api/v1/projects/{ids['projectId']}/files/{ids['fileId']}"
    for suffix, key, returned in [("role-decisions", "roleDecisionId", "decisionId"),
                                  ("identity-decisions", "identityDecisionId", "decisionId")]:
        value = api.get(file_prefix + "/" + suffix + "/" + ids[key])
        check(value[returned] == ids[key] and value["fileId"] == ids["fileId"], "DECISION_REOPEN_IDENTITY_MISMATCH")
    fact = validate_fact_audit(api.get(file_prefix + "/contract-fact-extractions/latest"), fixtures, ids)
    check(fact["attemptId"] == ids["extractionAttemptId"] and fact["audit"] == receipt.value["audit"]
          and fact["inputFingerprint"] == receipt.value["hashes"]["inputFingerprint"], "FACT_AUDIT_CHANGED_ON_REOPEN")
    candidate_prefix = f"/api/v1/projects/{ids['projectId']}/contract-candidates/{ids['candidateId']}"
    candidate = api.get(candidate_prefix)["candidate"]
    check(candidate["id"] == ids["candidateId"] and candidate["promotedContractId"] == ids["contractId"]
          and candidate["promotedConfirmationId"] == ids["confirmationId"], "PROMOTION_CHANGED_ON_REOPEN")
    confirmation = api.get(candidate_prefix + "/confirmations/" + ids["confirmationId"])
    check(confirmation["confirmationId"] == ids["confirmationId"]
          and confirmation["payloadFingerprint"] == receipt.value["hashes"]["confirmationPayloadSha256"], "CONFIRMATION_CHANGED_ON_REOPEN")
    run_prefix = f"/api/v1/projects/{ids['projectId']}/contracts/{ids['contractId']}/review-runs/{ids['runId']}"
    run = api.get(run_prefix)
    projection = validate_run(run, receipt, fixtures)
    for identifier in receipt.value["stepIds"]:
        output = api.get(run_prefix + "/steps/" + identifier)
        check(output["step"]["stepId"] == identifier and output["step"]["status"] == "COMPLETED", "STEP_CHANGED_ON_REOPEN")
    proof = validate_report(api, run_prefix, api.get(run_prefix + "/reports/" + ids["reportId"]), projection, ids["reportId"])
    check(all(proof[key] == receipt.value["hashes"][key] for key in ["projectionSha256", "reportSha256"])
          and proof["reportBytes"] == receipt.value["counts"]["reportBytes"], "FROZEN_REPORT_CHANGED_ON_REOPEN")
    return {"schemaVersion": 1, "origin": ORIGIN, "phase": "reopen", "syntheticManifestSha256": APPROVED_MANIFEST_HASH,
            "status": "PASS", "operations": dict(receipt.value["operations"]), "ids": dict(ids), "counts": dict(receipt.value["counts"]),
            "hashes": dict(receipt.value["hashes"]), "checks": dict(receipt.value["checks"], readOnlyReopen=True),
            "statuses": dict(receipt.value["statuses"]), "audit": list(receipt.value["audit"]), "stepIds": list(receipt.value["stepIds"])}


def receipt_path(raw):
    path = Path(raw)
    path = (WORKTREE / path).resolve() if not path.is_absolute() else path.resolve()
    check(TEST_ROOT.resolve().is_relative_to(WORKTREE.resolve()) and path.parent == TEST_ROOT.resolve()
          and path.suffix == ".json" and re.fullmatch(r"[A-Za-z0-9_.-]+\.json", path.name), "UNSAFE_RECEIPT_PATH")
    return path


class SafeArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        # argparse's default echoes unknown arguments, possibly pasted credentials.
        raise SafeFailure("INVALID_OPTION")


def main(argv=None):
    # Prevent third-party HTTP loggers from rendering authentication headers/URLs/bodies.
    logging.disable(logging.CRITICAL)
    parser = SafeArgumentParser(description="Synthetic-only fixed-loopback acceptance; root injects credentials via STDIN.")
    parser.add_argument("--phase", choices=["verify-fixtures", "long-review", "reopen"], required=True)
    parser.add_argument("--receipts")
    args = None
    phase = "UNINITIALIZED"
    receipt = api = None
    try:
        args = parser.parse_args(argv)
        phase = args.phase
        fixtures = verify_fixtures()
        if args.phase == "verify-fixtures":
            print(canonical_json({"status": "OFFLINE_FIXTURE_PASS", "syntheticManifestSha256": APPROVED_MANIFEST_HASH,
                                  "fileSha256": fixtures.docx_sha256, "sourceCodePoints": len(fixtures.source_text),
                                  "canonicalCodePoints": len(fixtures.canonical_text), "tailSha256": sha(fixtures.canonical_text[-256:]),
                                  "segmentCount": segment_count(fixtures.canonical_text)}))
            return 0
        check(not sys.stdin.isatty(), "CREDENTIAL_STDIN_REQUIRED")
        if args.phase == "long-review":
            check(args.receipts is None, "FRESH_RECEIPT_PATH_FIXED")
            receipt = Receipt.create(receipt_path(TEST_ROOT / "http-long-review-receipt.json"))
        else:
            check(args.receipts is not None, "RECEIPTS_REQUIRED")
            receipt = Receipt.load(receipt_path(args.receipts))
        raw = bytearray(sys.stdin.buffer.read(16385))
        try: value = credentials(bytes(raw))
        finally: raw[:] = b"\0" * len(raw)
        api = Api(receipt)
        try: api.login(value)
        finally: value.clear()
        result = long_review(api, receipt, fixtures) if args.phase == "long-review" else reopen(api, receipt, fixtures)
        if args.phase == "reopen":
            Receipt(receipt_path(TEST_ROOT / "http-reopen-receipt.json"), result).save()
        print(canonical_json({"phase": args.phase, "status": "PASS", "ids": result["ids"], "counts": result["counts"], "hashes": result["hashes"]}))
        return 0
    except SafeFailure as failure:
        code = failure.code
    except Exception:
        code = "UNEXPECTED_HARNESS_FAILURE"
    finally:
        if api is not None:
            try: api.close()
            except Exception: pass  # Never render third-party cleanup exception strings.
    if receipt is not None and phase == "long-review":
        receipt.value["status"] = "UNKNOWN" if code.endswith("OUTCOME_UNKNOWN") or any(
            o["status"] in {"SENDING", "WRITE_OUTCOME_UNKNOWN"} for o in receipt.value["operations"].values()) else "FAILED"
        receipt.value["failureCode"] = code
        try: receipt.save()
        except Exception: code = "RECEIPT_PUBLICATION_FAILURE"
    print(canonical_json({"phase": phase, "status": "NOT_VERIFIED", "reasonCode": code}))
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
