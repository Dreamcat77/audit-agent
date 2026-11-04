"""FORMAT_WORD97 only: authenticated root DOC/DOCX and independent DOC-only ZIP.

Reuses the frozen V2 journal/auth/runtime boundary. No candidate, confirmation,
review or model command is exposed. --target root uses journal case 01; --target
zip uses case 02 and a DIFFERENT Project, so SHA deduplication cannot hide ZIP parsing.
These case slots are format bookkeeping, never V2-01/V2-02 business acceptance.
Poll/reopen perform business GETs only and verify original bytes without saving them.
DM8 profile/process provenance is root-owned; HTTP success alone is not DM8 proof.

Live phases: create, upload, poll, reopen, reconcile. Offline phase: verify.
Every live invocation needs --expected-sha and the same root-verified runtime JSON
schema as v2-http-acceptance.py. Credentials: getpass or --credentials-stdin only.
No automatic retry, model invocation, file retransmission, recovery or service control.
"""
from __future__ import annotations

import getpass
import importlib.util
import io
import json
import logging
from pathlib import Path, PurePosixPath
import re
import sys
import unicodedata
import zipfile

import httpx

_spec = importlib.util.spec_from_file_location("word97_v2_safety", Path(__file__).with_name("v2-http-acceptance.py"))
B = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = B
_spec.loader.exec_module(B)

FORMAT_ROOT = B.WORKTREE / ".runtime" / "v2-word97-acceptance"
OLE2 = b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1"
INPUTS = {
    "doc": ("真实Word97_软件采购合同.doc", "bdb1f4b83300597d21169b0f71ebb41ddb928c4e1f65ead2fd9e32872e8064dd", "application/msword"),
    "docx": ("等价内容_DOCX_正确上传路径.docx", "e45d6d975960263418af98529eddb4f1d23add50faed2d53c9e4e7beb2b71a00", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    "zip": ("仅旧版DOC_解包解析兼容性检查.zip", "1f1b1419a579818726ce5c37324577f0e0aaa96a877d3d1d903c037c074f87f1", "application/zip"),
}


class FormatFiles:
    def __init__(self):
        # Authenticates every one of the 63 extracted files against the pinned outer ZIP.
        self.package = B.Package()
        directory = self.package.root / "02_格式负例_不要混入正常项目" / "07_真实旧版Word_DOC"
        self.items = {kind: {"name": name, "sha256": sha, "mime": mime,
                              "path": B.scoped_path(directory / name, self.package.root, "FORMAT_INPUT_PATH_INVALID")}
                      for kind, (name, sha, mime) in INPUTS.items()}
        for kind in self.items:
            self.read(kind)
        with zipfile.ZipFile(io.BytesIO(self.read("zip"))) as archive:
            entries = [entry for entry in archive.infolist() if not entry.is_dir()]
            B.require(len(entries) == 1, "FORMAT_ZIP_STRUCTURE_INVALID")
            entry = entries[0]
            relative = PurePosixPath(entry.filename)
            B.require(not relative.is_absolute() and ".." not in relative.parts and "\\" not in entry.filename
                      and relative.name == self.items["doc"]["name"] and not entry.flag_bits & 1
                      and 0 < entry.file_size <= 8 * 1024 * 1024, "FORMAT_ZIP_STRUCTURE_INVALID")
            data = archive.read(entry)
            self.zip_child_sha = B.digest(data)
            self.zip_child_path = str(relative)
            B.require(data.startswith(OLE2) and self.zip_child_sha == self.items["doc"]["sha256"], "FORMAT_ZIP_DOC_IDENTITY_UNPROVEN")

    def read(self, kind):
        B.require(kind in self.items, "FORMAT_INPUT_NOT_APPROVED")
        item = self.items[kind]
        target = B.scoped_path(item["path"], self.package.root, "FORMAT_INPUT_PATH_INVALID")
        B.require(target.is_file() and 0 < target.stat().st_size <= 8 * 1024 * 1024, "FORMAT_INPUT_SIZE_INVALID")
        data = target.read_bytes()
        B.require(B.digest(data) == item["sha256"], "FORMAT_INPUT_HASH_MISMATCH")
        B.require(data.startswith(OLE2) if kind == "doc" else data.startswith(b"PK\x03\x04"), "FORMAT_INPUT_MAGIC_MISMATCH")
        return data


class FormatApi(B.Api):
    def _request(self, method, path, **kwargs):
        if method != "GET":
            allowed = (method == "POST" and (path in {"/api/v1/auth/login", "/api/v1/projects"}
                       or re.fullmatch(r"/api/v1/projects/[0-9a-f-]{36}/file-jobs", path))) or (
                method == "PATCH" and re.fullmatch(r"/api/v1/projects/[0-9a-f-]{36}", path))
            B.require(allowed, "FORMAT_WRITE_FORBIDDEN")
        return super()._request(method, path, **kwargs)

    def original(self, project_id, file_id, expected_sha):
        B.resource(project_id)
        B.resource(file_id)
        path = f"/api/v1/projects/{project_id}/files/{file_id}/content"
        B.validate_path(path)
        try:
            with self.client.stream("GET", path, timeout=httpx.Timeout(30, connect=5), headers={"Accept-Encoding": "identity"}) as response:
                B.require(response.status_code == 200, "ORIGINAL_DOWNLOAD_UNAVAILABLE")
                chunks, size = [], 0
                for chunk in response.iter_bytes():
                    size += len(chunk)
                    B.require(size <= 8 * 1024 * 1024, "ORIGINAL_SIZE_LIMIT")
                    chunks.append(chunk)
                B.require(size > 0 and B.digest(b"".join(chunks)) == expected_sha, "ORIGINAL_HASH_MISMATCH")
        except httpx.HTTPError:
            raise B.SafeFailure("ORIGINAL_DOWNLOAD_UNAVAILABLE") from None
        return {"projectId": project_id, "fileId": file_id, "fileSha256": expected_sha, "status": "ORIGINAL_HASH_MATCHED"}


def text_signature(text):
    B.require(isinstance(text, str) and text.strip(), "PARSED_TEXT_UNPROVEN")
    normalized = unicodedata.normalize("NFKC", text.replace("\r\n", "\n").replace("\r", "\n"))
    rows = ["\t".join(" ".join(cell.split()) for cell in line.split("\t"))
            for line in normalized.splitlines() if "\t" in line]
    return {"codepointCount": len(text), "normalizedTextSha256": B.digest("".join(normalized.split())),
            "orderedRowsSha256": B.digest(B.canonical(rows)), "rowCount": len(rows),
            "cellCount": sum(row.count("\t") + 1 for row in rows)}


def compare_text(doc, docx):
    left, right = text_signature(doc), text_signature(docx)
    equal = left["normalizedTextSha256"] == right["normalizedTextSha256"]
    rows = left["rowCount"] > 0 and left["orderedRowsSha256"] == right["orderedRowsSha256"]
    return {"status": "NORMALIZED_TEXT_AND_TABLE_ORDER_MATCH" if equal and rows else "FORMAT_COMPARISON_DIFFERENCE",
            "doc": left, "docx": right}


class FormatDriver:
    def __init__(self, api, catalog, target):
        B.require(target in {"root", "zip"}, "FORMAT_TARGET_REQUIRED")
        self.api, self.catalog, self.target = api, catalog, target
        self.case = "01" if target == "root" else "02"
        if api.ledger.events:
            first = api.ledger.events[0]
            B.require(first["event"] == "OBSERVATION" and first["operation"] == "format-word97"
                      and first["result"] == {"status": "FORMAT_WORD97", "packageSha256": B.PACKAGE_SHA}, "FORMAT_LEDGER_REQUIRED")
        else:
            api.ledger.note(self.case, "format-word97", {"status": "FORMAT_WORD97", "packageSha256": B.PACKAGE_SHA})

    def prior(self, operation):
        value = self.api.ledger.operation(self.case, operation)
        if value is not None:
            B.require(value["state"] == "CONFIRMED", "CASE_UNRESOLVED_GET_ONLY")
            return value["result"]
        return None

    def project_id(self):
        value = self.prior("create")
        B.require(value is not None, "FORMAT_PROJECT_CREATION_REQUIRED")
        return B.resource(value["projectId"])

    def base(self):
        return "/api/v1/projects/" + self.project_id()

    def create(self):
        self.api.ledger.case_writable(self.case)
        current = self.prior("create")
        if current is None:
            def select(data):
                other_case = "02" if self.case == "01" else "01"
                other = self.api.ledger.operation(other_case, "create")
                identity = B.resource(data["id"])
                B.require(other is None or other.get("result", {}).get("projectId") != identity, "FORMAT_PROJECTS_NOT_INDEPENDENT")
                return {"projectId": identity, "versionNo": data["versionNo"]}
            current = self.api.command(self.case, "create", "POST", "/api/v1/projects", select=select)
        confirmed = self.prior("metadata")
        if confirmed is None:
            label = "根 DOC 与等价 DOCX" if self.target == "root" else "独立 DOC ZIP 路径"
            def select(data):
                B.require(data["id"] == self.project_id() and data["metadataConfirmed"] is True, "FORMAT_PROJECT_IDENTITY_UNPROVEN")
                return {"projectId": self.project_id(), "versionNo": data["versionNo"]}
            confirmed = self.api.command(self.case, "metadata", "PATCH", self.base(), {
                "expectedVersion": current["versionNo"], "name": "Word97 格式兼容性验证 · " + label,
                "projectNo": "FORMAT-WORD97-" + self.target.upper(), "category": None}, select=select)
        return confirmed

    def kinds(self):
        return ("doc", "docx") if self.target == "root" else ("zip",)

    def upload(self):
        self.api.ledger.case_writable(self.case)
        for kind in self.kinds():
            if self.prior("upload:" + kind) is not None:
                continue
            item = self.catalog.items[kind]
            data = self.catalog.read(kind)
            def select(value):
                B.require(value["projectId"] == self.project_id() and value["file"]["projectId"] == self.project_id()
                          and value["file"]["id"] == value["fileId"] and value["file"]["fileSha256"] == item["sha256"]
                          and value["job"]["projectId"] == self.project_id() and value["job"]["fileId"] == value["fileId"]
                          and value["job"]["jobId"] == value["jobId"], "FORMAT_ADMISSION_IDENTITY_UNPROVEN")
                return {**{field: B.resource(value[field]) for field in ("projectId", "fileId", "jobId")}, "fileSha256": item["sha256"]}
            self.api.command(self.case, "upload:" + kind, "POST", self.base() + "/file-jobs",
                             upload=(item["name"], data, item["mime"]), select=select)
        return {"projectId": self.project_id(), "fileCount": len(self.kinds())}

    def _snapshot(self):
        detail = self.api.get(self.base())
        jobs = self.api.get(self.base() + "/file-jobs")
        B.require(detail["project"]["id"] == self.project_id() and jobs["projectId"] == self.project_id()
                  and isinstance(detail.get("files"), list) and isinstance(jobs.get("items"), list), "FORMAT_PROJECT_IDENTITY_UNPROVEN")
        by_id = {}
        kinds = {}
        accepted = {kind: self.prior("upload:" + kind) for kind in self.kinds()}
        for file in detail["files"]:
            identity = B.resource(file["id"])
            B.require(identity not in by_id and file["projectId"] == self.project_id(), "FORMAT_FILE_IDENTITY_UNPROVEN")
            if self.target == "root":
                found = [kind for kind, original in accepted.items() if original is not None
                         and original["fileId"] == identity and original["fileSha256"] == file["fileSha256"]
                         and file.get("parentArchiveFileId") is None]
            else:
                root = accepted["zip"]
                found = []
                if root is not None:
                    if identity == root["fileId"] and file["fileSha256"] == self.catalog.items["zip"]["sha256"] and file.get("parentArchiveFileId") is None:
                        found = ["zip"]
                    elif file.get("parentArchiveFileId") == root["fileId"] and identity != root["fileId"] and file["fileSha256"] == self.catalog.zip_child_sha:
                        B.require(file["normalizedRelativePath"] == self.catalog.zip_child_path, "FORMAT_ZIP_CHILD_PATH_UNPROVEN")
                        found = ["doc"]
            B.require(len(found) == 1 and found[0] not in kinds.values(), "FORMAT_FILE_IDENTITY_UNPROVEN")
            by_id[identity], kinds[identity] = file, found[0]
        manifests, texts, parsed = [], {}, []
        seen_jobs = set()
        seen_parses = set()
        counts = {"completed": 0, "failed": 0, "unknown": 0}
        for job in jobs["items"]:
            identity = job.get("fileId")
            B.require(identity in by_id and identity not in seen_jobs and job["projectId"] == self.project_id()
                      and job["status"] in {"QUEUED", "PROCESSING", "SUCCEEDED", "FAILED", "UNKNOWN"}, "FORMAT_JOB_IDENTITY_UNPROVEN")
            seen_jobs.add(identity)
            file, kind = by_id[identity], kinds[identity]
            original = accepted.get(kind)
            if original is not None:
                B.require(job["jobId"] == original["jobId"] and identity == original["fileId"], "FORMAT_JOB_IDENTITY_UNPROVEN")
            row = {"projectId": self.project_id(), "fileId": identity, "jobId": B.resource(job["jobId"]),
                   "fileSha256": file["fileSha256"], "status": job["status"], "parseStatus": file["parseStatus"]}
            count = {"SUCCEEDED": "completed", "FAILED": "failed", "UNKNOWN": "unknown"}.get(job["status"])
            if count:
                counts[count] += 1
            if file["parseStatus"] == "PARSED":
                method = "DOCX_TEXT" if kind == "docx" else "DOC_TEXT"
                B.require(kind != "zip" and file.get("parseMethod") == method and file.get("ingestionStatus") == "AVAILABLE"
                          and job.get("parseResultId") == file.get("parseResultId") and type(file.get("parseVersion")) is int
                          and file["parseVersion"] > 0 and isinstance(file.get("parsedText"), str)
                          and B.digest(file["parsedText"]) == file.get("canonicalTextSha256"), "FORMAT_PARSE_IDENTITY_UNPROVEN")
                parse_id = B.resource(file["parseResultId"])
                B.require(parse_id not in seen_parses, "CROSS_PROJECT_PARSE_REUSE")
                seen_parses.add(parse_id)
                # A ParseResult ID belongs to one real file. A second project must parse anew.
                for event in self.api.ledger.events:
                    prior = event.get("result", {})
                    if prior.get("parseResultId") == parse_id:
                        B.require(prior.get("fileId") == identity and prior.get("projectId") == self.project_id(), "CROSS_PROJECT_PARSE_REUSE")
                row.update(parseResultId=parse_id, parseVersion=file["parseVersion"], parseMethod=method,
                           canonicalTextSha256=file["canonicalTextSha256"], canonicalCodePoints=len(file["parsedText"]))
                texts[kind] = file["parsedText"]
                parsed.append(row)
            manifests.append(row)
        B.require(jobs["total"] == len(jobs["items"]) and all(jobs[field] == value for field, value in counts.items()), "FORMAT_JOB_COUNTS_UNPROVEN")
        expected_files = 2
        complete = (len(by_id) == expected_files == len(manifests) == counts["completed"]
                    and (len(texts) == 2 if self.target == "root" else len(texts) == 1
                         and any(kinds[identity] == "zip" and by_id[identity]["parseStatus"] == "NOT_APPLICABLE" for identity in by_id)))
        bad_parse = any(file["parseStatus"] in {"FAILED", "UNSUPPORTED"} for file in by_id.values())
        status = "UNKNOWN" if counts["unknown"] else "FAILED" if counts["failed"] or bad_parse else "COMPLETED" if complete else "PENDING"
        manifests.sort(key=lambda value: value["fileId"])
        summary = {"projectId": self.project_id(), "fileCount": len(by_id), "total": len(manifests), **counts,
                   "status": status, "inputFingerprint": B.digest(B.canonical(manifests))}
        comparison = compare_text(texts["doc"], texts["docx"]) if self.target == "root" and complete else None
        return summary, manifests, parsed, comparison

    def _observe(self, snapshot):
        summary, manifests, parsed, comparison = snapshot
        if summary["status"] == "COMPLETED":
            for row in manifests:
                original = self.api.original(self.project_id(), row["fileId"], row["fileSha256"])
                self.api.ledger.note(self.case, "original:" + row["fileId"], original)
        for row in manifests:
            self.api.ledger.note(self.case, "job:" + row["fileId"], row)
        for row in parsed:
            self.api.ledger.note(self.case, "parsed:" + row["fileId"], row)
        if comparison is not None:
            for kind in ("doc", "docx"):
                view = comparison[kind]
                self.api.ledger.note(self.case, "compare:" + kind, {"status": comparison["status"],
                    "projectionSha256": view["normalizedTextSha256"], "inputFingerprint": view["orderedRowsSha256"],
                    "canonicalCodePoints": view["codepointCount"], "lineCount": view["rowCount"], "total": view["cellCount"]})
        self.api.ledger.note(self.case, "snapshot", summary)
        return {**summary, **({"comparison": comparison} if comparison is not None else {})}

    def poll(self):
        return self._observe(self._snapshot())

    def reopen(self):
        completed = [event["result"] for event in self.api.ledger.events if event["case"] == self.case
                     and event["operation"] == "snapshot" and event.get("result", {}).get("status") == "COMPLETED"]
        B.require(completed, "COMPLETED_FORMAT_RECEIPT_REQUIRED")
        baseline = completed[-1]["inputFingerprint"]
        current = self._snapshot()
        B.require(current[0]["status"] == "COMPLETED" and current[0]["inputFingerprint"] == baseline, "REOPEN_IDENTITY_CHANGED")
        return {**self._observe(current), "reconciled": True}

    def reconcile(self, operation):
        original = self.api.ledger.operation(self.case, operation)
        B.require(original is not None, "OPERATION_NOT_FOUND")
        if operation == "create":
            current = self.api.get("/api/v1/projects/creation-operations", params={"key": original["key"]})
            result = {"projectId": B.resource(current["id"]), "versionNo": current["versionNo"]}
            other = self.api.ledger.operation("02" if self.case == "01" else "01", "create")
            B.require(other is None or other.get("result", {}).get("projectId") != result["projectId"], "FORMAT_PROJECTS_NOT_INDEPENDENT")
        else:
            B.require(operation in {"upload:" + kind for kind in self.kinds()}, "FORMAT_OPERATION_NOT_APPROVED")
            kind = operation.split(":")[1]
            current = self.api.get(self.base() + "/file-jobs/by-operation", params={"key": original["key"]})
            B.require(current["projectId"] == self.project_id() and current["file"]["projectId"] == self.project_id()
                      and current["file"]["id"] == current["fileId"] and current["file"]["fileSha256"] == self.catalog.items[kind]["sha256"]
                      and current["job"]["projectId"] == self.project_id() and current["job"]["fileId"] == current["fileId"]
                      and current["job"]["jobId"] == current["jobId"], "FORMAT_ADMISSION_IDENTITY_UNPROVEN")
            result = {**{field: B.resource(current[field]) for field in ("projectId", "fileId", "jobId")}, "fileSha256": self.catalog.items[kind]["sha256"]}
        for field in ("projectId", "fileId", "jobId", "fileSha256"):
            if field in original.get("result", {}):
                B.require(original["result"][field] == result[field], "REOPEN_IDENTITY_CHANGED")
        self.api.ledger.append({"event": "OUTCOME", "case": self.case, "operation": operation,
                                "state": "CONFIRMED", "result": result, "safeCode": "EXACT_OPERATION_REDISCOVERED"})
        return result


def main(argv=None):
    api = ledger = credentials = None
    try:
        parser = B.SafeParser(description=__doc__)
        parser.add_argument("--phase", required=True, help="verify/create/upload/poll/reopen/reconcile only")
        parser.add_argument("--target", choices=["root", "zip"], default="root")
        parser.add_argument("--expected-sha", "-ExpectedSha", dest="expected_sha")
        parser.add_argument("--runtime-evidence")
        parser.add_argument("--ledger", default=str(FORMAT_ROOT / "format-ledger.jsonl"))
        parser.add_argument("--credentials-stdin", action="store_true")
        parser.add_argument("--operation", help="reconcile: create/upload:doc/upload:docx/upload:zip")
        args = parser.parse_args(argv)
        B.require(args.phase in {"verify", "create", "upload", "poll", "reopen", "reconcile"}, "SAFE_UNSUPPORTED")
        if args.phase != "verify":
            B.require(isinstance(args.expected_sha, str) and re.fullmatch(r"[0-9a-f]{40}", args.expected_sha), "EXPECTED_SHA_REQUIRED")
            B.require(args.runtime_evidence, "RUNTIME_IDENTITY_UNPROVEN")
        catalog = FormatFiles()
        if args.phase == "verify":
            print(B.canonical({"receiptKind": "FORMAT_WORD97", "status": "VERIFIED_OFFLINE", "businessAcceptance": "NOT_APPLICABLE",
                               "packageSha256": B.PACKAGE_SHA, "inputs": {kind: {"sha256": value["sha256"], "sizeBytes": len(catalog.read(kind))}
                                                                          for kind, value in catalog.items.items()}}))
            return 0
        identity = B.runtime_evidence(args.runtime_evidence, expected_sha=args.expected_sha)
        ledger = B.Ledger(args.ledger, root=FORMAT_ROOT)
        api = FormatApi(ledger, readonly=args.phase in {"poll", "reopen", "reconcile"})
        driver = FormatDriver(api, catalog, args.target)
        binding = {"sourceSha": args.expected_sha, "packageSha256": B.PACKAGE_SHA,
                   "artifactSha256": identity["artifactSha256"], "javaInputSha256": identity["javaInputSha256"],
                   "pythonInputSha256": identity["pythonInputSha256"]}
        bindings = [event["result"] for event in ledger.events if event["operation"] == "binding"]
        B.require(not bindings or all(value == binding for value in bindings), "LEDGER_RUNTIME_BINDING_CHANGED")
        if not bindings:
            ledger.note(driver.case, "binding", binding)
        ledger.note(driver.case, "runtime", identity)
        if args.credentials_stdin:
            credentials = B.credentials(sys.stdin.read(8193))
        else:
            B.require(sys.stdin.isatty(), "INTERACTIVE_CREDENTIALS_OR_STDIN_REQUIRED")
            credentials = B.credentials(B.canonical({"username": "test_admin", "password": getpass.getpass("test_admin password: ")}))
        api.login(credentials)
        credentials["password"] = ""
        if args.phase == "reconcile":
            result = driver.reconcile(args.operation)
        else:
            result = getattr(driver, args.phase)()
        print(B.canonical({"receiptKind": "FORMAT_WORD97", "target": args.target, "phase": args.phase,
                           "status": "OBSERVED_FORMAT_PHASE", "observed": result,
                           "businessAcceptance": "NOT_APPLICABLE", "dm8Verification": "ROOT_OWNED_NOT_INFERRED_FROM_HTTP"}))
        return 0
    except (Exception, KeyboardInterrupt) as error:
        code = error.code if isinstance(error, B.SafeFailure) else "WORD97_HARNESS_UNEXPECTED_ERROR"
        print(B.canonical({"receiptKind": "FORMAT_WORD97", "status": "NOT_VERIFIED", "safeCode": code}))
        return 1
    finally:
        if credentials is not None:
            credentials["password"] = ""
        if api is not None:
            api.close()
        if ledger is not None:
            ledger.close()


if __name__ == "__main__":
    logging.disable(logging.CRITICAL)
    raise SystemExit(main())
