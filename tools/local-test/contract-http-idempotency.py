"""Root-operated real POST replay/new-operation gate; NEVER an uncertain-write retry.

Requires the completed, unchanged http-long-review-receipt.json and approved DOCX.
Root pipes username/password JSON via STDIN; no credentials/config/environment/CLI.
Replays original create and identical async upload keys, then admits exactly ONE
deliberately new upload in the SAME project. No confirmation, promotion, new run,
model invocation API, retry, override or service/DB operation is called here.
Admission may cause the normal server file worker to process the approved fixture.
Fresh create-only http-idempotency-receipt.json journals every key before dispatch;
any loss/UNKNOWN stops the probe. Existing output blocks another run, even on PASS.
No source, response, report, cookie, token or provider data is emitted or stored.
"""
from __future__ import annotations

import importlib.util
import json
import logging
import os
from pathlib import Path
import re
import sys
from uuid import uuid4

_spec = importlib.util.spec_from_file_location("_frozen_contract_http_acceptance", Path(__file__).with_name("contract-http-acceptance.py"))
h = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = h
_spec.loader.exec_module(h)

DOCX_MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
INPUT_PATH = h.TEST_ROOT / "http-long-review-receipt.json"
OUTPUT_PATH = h.TEST_ROOT / "http-idempotency-receipt.json"
NAMES = {"replayCreate", "replayUpload", "newUpload"}
IDS = {"projectId", "fileId", "jobId", "parseResultId", "contractId", "runId", "reportId", "newFileId", "newJobId"}
COUNTS = {f"{kind}{point}" for kind in ["projects", "files", "jobs"] for point in ["Before", "AfterReplay", "AfterNew"]}
HASHES = {"sourceFileSha256", "uploadDescriptorSha256", "canonicalTextSha256", "oldProjectionSha256", "oldReportSha256"}
CHECKS = {"createReplaySameProject", "uploadReplaySameFileAndJob", "replayNoCountGrowth", "newUploadDistinct",
          "newUploadOneCountGrowth", "oldFrozenIdentityAndBytesStable"}


def request_hash(path, file_hash=None):
    # Same immutable request shape as the frozen producer, without source/body data.
    return h.sha(h.canonical_json({"method": "POST", "path": path, "body": None, "fileSha256": file_hash}))


def pin_original(old, fixtures):
    h.validate_receipt(old.value)
    value = old.value
    h.check(value["status"] == "PASS" and value["phase"] == "long-review" and set(value["ids"]) == h.ID_FIELDS
            and len(set(value["stepIds"])) == 5
            and all(value["checks"].get(k) is True for k in h.CHECK_FIELDS if k != "readOnlyReopen")
            and value["statuses"] == dict(fileJobStatus="SUCCEEDED", extractionStatus="SUCCEEDED", runStatus="COMPLETED", reportStatus="AVAILABLE"),
            "COMPLETED_ORIGINAL_RECEIPT_REQUIRED")
    h.check(value["hashes"].get("sourceFileSha256") == fixtures.docx_sha256
            and value["hashes"].get("canonicalTextSha256") == h.sha(fixtures.canonical_text), "ORIGINAL_FIXTURE_HASH_UNPROVEN")
    upload_path = f"/api/v1/projects/{value['ids']['projectId']}/file-jobs"
    for name, path, file_hash in [("createProject", "/api/v1/projects", None), ("uploadContract", upload_path, fixtures.docx_sha256)]:
        operation = value["operations"].get(name)
        h.check(operation is not None and operation["status"] == "CONFIRMED"
                and operation["requestSha256"] == request_hash(path, file_hash), "ORIGINAL_OPERATION_NOT_PROVEN_SUCCESSFUL")
    h.check(value["operations"]["createProject"]["key"] != value["operations"]["uploadContract"]["key"], "ORIGINAL_KEYS_COLLIDE")
    return upload_path


def validate_probe(value):
    try:
        h.check(set(value) <= {"schemaVersion", "phase", "origin", "status", "inputReceiptSha256", "syntheticManifestSha256",
                              "ids", "operations", "counts", "hashes", "checks", "failureCode"}
                and type(value["schemaVersion"]) is int and value["schemaVersion"] == 1 and value["phase"] == "idempotency"
                and value["origin"] == h.ORIGIN and value["syntheticManifestSha256"] == h.APPROVED_MANIFEST_HASH
                and value["status"] in {"PREPARED", "RUNNING", "PASS", "FAILED", "UNKNOWN"}, "UNSAFE_PROBE_RECEIPT")
        h.hash_value(value["inputReceiptSha256"])
        for key, identifier in value["ids"].items():
            h.check(key in IDS, "UNSAFE_PROBE_RECEIPT")
            h.resource_id(identifier)
        h.check(set(value["operations"]) == NAMES, "UNSAFE_PROBE_RECEIPT")
        for operation in value["operations"].values():
            h.check(set(operation) == {"key", "keySha256", "requestSha256", "status"}
                    and re.fullmatch(r"w4:[0-9a-f-]{36}", operation["key"])
                    and h.sha(operation["key"]) == operation["keySha256"]
                    and operation["status"] in {"NOT_SENT", "SENDING", "CONFIRMED", "REJECTED", "WRITE_OUTCOME_UNKNOWN"}, "UNSAFE_PROBE_RECEIPT")
            h.resource_id(operation["key"][3:])
            h.hash_value(operation["requestSha256"])
        h.check(len({o["key"] for o in value["operations"].values()}) == 3, "UNSAFE_PROBE_RECEIPT")
        for key, count in value["counts"].items(): h.check(key in COUNTS and h.natural(count), "UNSAFE_PROBE_RECEIPT")
        for key, digest in value["hashes"].items():
            h.check(key in HASHES, "UNSAFE_PROBE_RECEIPT")
            h.hash_value(digest)
        for key, passed in value["checks"].items(): h.check(key in CHECKS and type(passed) is bool, "UNSAFE_PROBE_RECEIPT")
        if "failureCode" in value: h.check(h.CODE_RE.fullmatch(value["failureCode"]), "UNSAFE_PROBE_RECEIPT")
    except (KeyError, ValueError, TypeError, AttributeError, h.SafeFailure):
        raise h.SafeFailure("UNSAFE_PROBE_RECEIPT") from None
    return value


class ProbeReceipt:
    def __init__(self, path, value): self.path, self.value = Path(path), validate_probe(value)

    @classmethod
    def reopen_unsent(cls, path, old, fixtures):
        try: value = json.loads(Path(path).read_bytes())
        except (ValueError, OSError): raise h.SafeFailure("UNSAFE_PROBE_RECEIPT") from None
        receipt = cls(path, value)
        pin_original(old, fixtures)
        h.check(receipt.value["status"] == "FAILED"
                and receipt.value.get("failureCode") == "READ_TRANSPORT_FAILURE"
                and receipt.value["inputReceiptSha256"] == h.sha(old.path.read_bytes())
                and not receipt.value["counts"] and not receipt.value["checks"]
                and all(operation["status"] == "NOT_SENT" for operation in receipt.value["operations"].values()),
                "PROBE_NOT_PROVEN_UNSENT")
        for name, original in [("replayCreate", "createProject"), ("replayUpload", "uploadContract")]:
            h.check(receipt.value["operations"][name]["key"] == old.value["operations"][original]["key"], "ORIGINAL_KEYS_CHANGED")
        receipt.value["status"] = "PREPARED"
        receipt.value.pop("failureCode")
        receipt.save()
        return receipt

    @classmethod
    def create(cls, path, old, fixtures):
        path = Path(path)
        h.check(not path.exists(), "EXISTING_PROBE_RECEIPT")
        upload_path = pin_original(old, fixtures)
        original_keys = {o["key"] for o in old.value["operations"].values()}
        new_key = "w4:" + str(uuid4())
        h.check(new_key not in original_keys, "NEW_OPERATION_KEY_COLLISION")
        operations = {}
        for name, key, fingerprint in [
                ("replayCreate", old.value["operations"]["createProject"]["key"], request_hash("/api/v1/projects")),
                ("replayUpload", old.value["operations"]["uploadContract"]["key"], request_hash(upload_path, fixtures.docx_sha256)),
                ("newUpload", new_key, request_hash(upload_path, fixtures.docx_sha256))]:
            operations[name] = dict(key=key, keySha256=h.sha(key), requestSha256=fingerprint, status="NOT_SENT")
        value = dict(schemaVersion=1, phase="idempotency", origin=h.ORIGIN, status="PREPARED",
                     inputReceiptSha256=h.sha(old.path.read_bytes()), syntheticManifestSha256=h.APPROVED_MANIFEST_HASH,
                     ids={k: old.value["ids"][k] for k in IDS - {"newFileId", "newJobId"}}, operations=operations,
                     counts={}, hashes=dict(sourceFileSha256=fixtures.docx_sha256,
                         uploadDescriptorSha256=h.sha(h.canonical_json(dict(filename=fixtures.docx_path.name,
                            mediaType=DOCX_MEDIA_TYPE, fileSha256=fixtures.docx_sha256)))), checks={})
        receipt = cls(path, value)
        path.parent.mkdir(parents=True, exist_ok=True)
        try:
            with path.open("xb") as stream:
                stream.write(h.canonical_json(value).encode())
                stream.flush()
                os.fsync(stream.fileno())
        except FileExistsError: raise h.SafeFailure("EXISTING_PROBE_RECEIPT") from None
        return receipt

    def save(self):
        validate_probe(self.value)
        temporary = self.path.with_name(self.path.name + "." + str(uuid4()) + ".pending")
        try:
            with temporary.open("xb") as stream:
                stream.write(h.canonical_json(self.value).encode())
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.path)
        finally:
            if temporary.exists(): temporary.unlink()


def post(api, receipt, name, path, upload=None):
    operation = receipt.value["operations"][name]
    h.check(operation["status"] == "NOT_SENT", "PROBE_OPERATION_ALREADY_SENT")
    h.check(operation["requestSha256"] == request_hash(path, h.sha(upload[1]) if upload else None), "PROBE_REQUEST_CHANGED")
    operation["status"] = "SENDING"
    receipt.value["status"] = "RUNNING"
    receipt.save()  # Original replay keys AND deliberately new key proved before POST.
    try:
        kwargs = dict(headers={"Idempotency-Key": operation["key"]})
        if upload: kwargs["files"] = {"file": upload}
        result = api._envelope(api._request("POST", path, **kwargs), write=True)
    except h.SafeFailure as failure:
        operation["status"] = "REJECTED" if failure.code.startswith("WRITE_REJECTED_") else "WRITE_OUTCOME_UNKNOWN"
        receipt.value["status"] = "FAILED" if operation["status"] == "REJECTED" else "UNKNOWN"
        receipt.save()
        raise
    operation["status"] = "CONFIRMED"
    receipt.save()
    return result


def snapshot(api, old):
    project_id = old.value["ids"]["projectId"]
    projects = api.get("/api/v1/projects")
    files = api.get(f"/api/v1/projects/{project_id}/files")
    jobs = api.get(f"/api/v1/projects/{project_id}/file-jobs")
    h.check(h.natural(projects.get("total")) and jobs["projectId"] == project_id and jobs["total"] == len(jobs["items"]), "COUNT_PROJECTION_UNPROVEN")
    file_ids = {h.resource_id(f["id"]) for f in files}
    job_ids = {h.resource_id(j["jobId"]) for j in jobs["items"]}
    h.check(len(file_ids) == len(files) and len(job_ids) == len(jobs["items"])
            and all(f["projectId"] == project_id for f in files)
            and all(j["projectId"] == project_id for j in jobs["items"])
            and old.value["ids"]["fileId"] in file_ids and old.value["ids"]["jobId"] in job_ids, "OWNED_COUNT_IDENTITY_UNPROVEN")
    return dict(projects=projects["total"], files=len(files), jobs=len(job_ids), fileIds=file_ids, jobIds=job_ids)


def frozen_proof(api, old, fixtures):
    ids = old.value["ids"]
    original = h.validate_file(h.get_file(api, ids), fixtures, ids)
    h.check(original["parseResultId"] == ids["parseResultId"], "ORIGINAL_PARSE_CHANGED")
    h.check(original["originalFilename"] == fixtures.docx_path.name and original["mediaType"] == DOCX_MEDIA_TYPE
            and original["fileSha256"] == fixtures.docx_sha256, "ORIGINAL_UPLOAD_DESCRIPTOR_MISMATCH")
    job = api.get(f"/api/v1/projects/{ids['projectId']}/files/{ids['fileId']}/processing")
    h.check(job["jobId"] == ids["jobId"] and job["fileId"] == ids["fileId"] and job["projectId"] == ids["projectId"]
            and job["parseResultId"] == ids["parseResultId"] and job["status"] == "SUCCEEDED", "ORIGINAL_JOB_CHANGED")
    prefix = f"/api/v1/projects/{ids['projectId']}/contracts/{ids['contractId']}/review-runs/{ids['runId']}"
    projection = h.validate_run(api.get(prefix), old, fixtures)
    proof = h.validate_report(api, prefix, api.get(prefix + "/reports/" + ids["reportId"]), projection, ids["reportId"])
    h.check(all(proof[k] == old.value["hashes"][k] for k in ["reportSha256", "projectionSha256"])
            and proof["reportBytes"] == old.value["counts"]["reportBytes"], "ORIGINAL_FROZEN_REPORT_CHANGED")
    return dict(canonicalTextSha256=h.sha(fixtures.canonical_text), oldProjectionSha256=proof["projectionSha256"], oldReportSha256=proof["reportSha256"])


def accepted_identity(value, fixtures, project_id):
    file_id, job_id = h.resource_id(value["fileId"]), h.resource_id(value["jobId"])
    h.check(value["projectId"] == project_id and value["file"]["id"] == file_id
            and value["file"]["projectId"] == project_id and value["file"]["fileSha256"] == fixtures.docx_sha256
            and value["file"]["originalFilename"] == fixtures.docx_path.name and value["file"]["mediaType"] == DOCX_MEDIA_TYPE
            and value["job"]["jobId"] == job_id and value["job"]["fileId"] == file_id
            and value["job"]["projectId"] == project_id, "UPLOAD_ADMISSION_IDENTITY_UNPROVEN")
    h.check(value["job"]["status"] not in {"UNKNOWN", "OUTCOME_UNKNOWN"}, "FILE_JOB_OUTCOME_UNKNOWN")
    h.check(value["job"]["status"] in {"QUEUED", "PROCESSING", "SUCCEEDED"}, "FILE_JOB_NOT_ADMITTED")
    return file_id, job_id


def run_probe(api, old, receipt, fixtures):
    upload_path = pin_original(old, fixtures)
    h.check(receipt.value["status"] == "PREPARED" and all(o["status"] == "NOT_SENT" for o in receipt.value["operations"].values()), "PROBE_ALREADY_STARTED")
    h.check(h.sha(old.path.read_bytes()) == receipt.value["inputReceiptSha256"], "ORIGINAL_RECEIPT_CHANGED")
    ids = old.value["ids"]
    before = snapshot(api, old)
    frozen = frozen_proof(api, old, fixtures)
    receipt.value["counts"].update({k + "Before": before[k] for k in ["projects", "files", "jobs"]})
    receipt.value["hashes"].update(frozen)
    receipt.save()
    project = post(api, receipt, "replayCreate", "/api/v1/projects")
    h.check(project["id"] == ids["projectId"], "CREATE_REPLAY_IDENTITY_MISMATCH")
    receipt.value["checks"]["createReplaySameProject"] = True
    content = fixtures.docx_path.read_bytes()
    h.check(h.sha(content) == fixtures.docx_sha256, "FIXTURE_CHANGED_BEFORE_REPLAY")
    upload = (fixtures.docx_path.name, content, DOCX_MEDIA_TYPE)
    replay = post(api, receipt, "replayUpload", upload_path, upload)
    # Exact original IDs are checked separately so wrong replay can never unlock a new operation.
    h.check(replay.get("fileId") == ids["fileId"] and replay.get("jobId") == ids["jobId"], "UPLOAD_REPLAY_IDENTITY_MISMATCH")
    accepted_identity(replay, fixtures, ids["projectId"])
    after_replay = snapshot(api, old)
    h.check(after_replay == before, "REPLAY_COUNT_OR_ID_GROWTH")
    h.check(frozen_proof(api, old, fixtures) == frozen, "ORIGINAL_FROZEN_STATE_CHANGED")
    receipt.value["counts"].update({k + "AfterReplay": after_replay[k] for k in ["projects", "files", "jobs"]})
    receipt.value["checks"].update(uploadReplaySameFileAndJob=True, replayNoCountGrowth=True)
    receipt.save()
    # ONLY after proved successful original replays: this is a NEW operation, not retry.
    h.check(h.sha(fixtures.docx_path.read_bytes()) == fixtures.docx_sha256, "FIXTURE_CHANGED_BEFORE_NEW_UPLOAD")
    fresh = post(api, receipt, "newUpload", upload_path, upload)
    new_file, new_job = accepted_identity(fresh, fixtures, ids["projectId"])
    h.check(new_file not in before["fileIds"] and new_job not in before["jobIds"], "NEW_UPLOAD_NOT_DISTINCT")
    receipt.value["ids"].update(newFileId=new_file, newJobId=new_job)
    receipt.save()
    after_new = snapshot(api, old)
    h.check(after_new["projects"] == before["projects"] and after_new["files"] == before["files"] + 1
            and after_new["jobs"] == before["jobs"] + 1 and after_new["fileIds"] == before["fileIds"] | {new_file}
            and after_new["jobIds"] == before["jobIds"] | {new_job}, "NEW_UPLOAD_COUNT_OR_ID_DELTA_MISMATCH")
    h.check(frozen_proof(api, old, fixtures) == frozen and h.sha(old.path.read_bytes()) == receipt.value["inputReceiptSha256"], "ORIGINAL_FROZEN_STATE_CHANGED")
    receipt.value["counts"].update({k + "AfterNew": after_new[k] for k in ["projects", "files", "jobs"]})
    receipt.value["checks"].update(newUploadDistinct=True, newUploadOneCountGrowth=True, oldFrozenIdentityAndBytesStable=True)
    receipt.value["status"] = "PASS"
    receipt.save()
    return receipt.value


def main(argv=None):
    logging.disable(logging.CRITICAL)
    api = receipt = None
    try:
        parser = h.SafeArgumentParser(description="One synthetic true POST replay/new-upload gate; credentials STDIN only.")
        parser.add_argument("--resume-not-sent", action="store_true")
        options = parser.parse_args(argv)  # No credentials, origin, fixture or receipt override options.
        fixtures = h.verify_fixtures()
        old = h.Receipt.load(h.receipt_path(INPUT_PATH))
        pin_original(old, fixtures)
        receipt = (ProbeReceipt.reopen_unsent(h.receipt_path(OUTPUT_PATH), old, fixtures) if options.resume_not_sent
                   else ProbeReceipt.create(h.receipt_path(OUTPUT_PATH), old, fixtures))
        h.check(not sys.stdin.isatty(), "CREDENTIAL_STDIN_REQUIRED")
        raw = bytearray(sys.stdin.buffer.read(16385))
        try: value = h.credentials(bytes(raw))
        finally: raw[:] = b"\0" * len(raw)
        api = h.Api(old)
        try: api.login(value)
        finally: value.clear()
        result = run_probe(api, old, receipt, fixtures)
        print(h.canonical_json(dict(phase="idempotency", status="PASS", ids=result["ids"], counts=result["counts"], hashes=result["hashes"])))
        return 0
    except h.SafeFailure as failure: code = failure.code
    except Exception: code = "UNEXPECTED_PROBE_FAILURE"
    finally:
        if api is not None:
            try: api.close()
            except Exception: pass
    if receipt is not None:
        receipt.value["status"] = "UNKNOWN" if code.endswith("OUTCOME_UNKNOWN") or any(
            o["status"] in {"SENDING", "WRITE_OUTCOME_UNKNOWN"} for o in receipt.value["operations"].values()) else "FAILED"
        receipt.value["failureCode"] = code
        try: receipt.save()
        except Exception: code = "PROBE_RECEIPT_PUBLICATION_FAILURE"
    print(h.canonical_json(dict(phase="idempotency", status="NOT_VERIFIED", reasonCode=code)))
    return 1


if __name__ == "__main__": raise SystemExit(main())
