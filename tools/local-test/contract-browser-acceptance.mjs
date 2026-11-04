/**
 * Real UI acceptance, fixed loopback origin; NO mocked routes, tracing, HAR, storageState,
 * credential arguments/environment, login screenshots, raw HTTP bodies or source-text logs.
 * Run from this worktree (Node 24, frontend/playwright-core, installed Chrome/Edge).
 *
 * Offline: node tools/local-test/contract-browser-acceptance.mjs --phase verify-fixtures
 * Live: pipe {"username":...,"password":...} JSON through STDIN, never command arguments:
 *   --phase login
 *   --phase intake [--check-drafts]                creates exactly two synthetic projects
 *   --phase reopen --receipts <runtime receipt>   READ ONLY, after refresh/restart
 *   --phase opinions --project-id UUID --contract-id UUID --run-id UUID [--report-id UUID]
 * Options: --headed, --browser chrome|edge, --ui-timeout-ms 30000,
 *          --acceptance-timeout-ms 180000 (admission, NOT whole OCR completion).
 * Root owns service readiness, credential injection, DM8 verification and rollback.
 * An 8-page real OCR already took ~828s: this harness deliberately never waits for all A OCR.
 * Opinion phase requires an EXISTING completed synthetic run and AVAILABLE v3 report.
 * Native clipboard denial must actually fail; unsupported CDP permission is NOT a pass.
 * Profiles/downloads/evidence stay under canonical .runtime/contract-repair-test.
 * Browser-managed profile is sensitive test runtime, not an evidence artifact to publish.
 */
import { createHash, randomUUID } from "node:crypto";
import { existsSync } from "node:fs";
import { mkdir, readFile, realpath, stat, writeFile } from "node:fs/promises";
import { createRequire } from "node:module";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { inflateRawSync } from "node:zlib";

const ORIGIN = "http://127.0.0.1:5173";
const WORKTREE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const TEST_ROOT = path.join(WORKTREE, ".runtime", "contract-repair-test");
const FIXTURE_ROOT = path.join(WORKTREE, ".runtime", "contract-repair-input-20261014", "synthetic_contract_regression");
const APPROVED_MANIFEST_HASH = "4fe9d34178f19739efb9a6117505cafc00a6420a305ba7fe072faca5bbe86151";
const APPROVED_ZIP_HASH = "2ffdd2885e32e71ad5b54877f1431be316158315aac5835dd4f3063fbc3eb515";
const ZIP_NAME = "P001_合成多文件上传包.zip";
const MIXED_NAME = "P001_混合PDF_原生文字加扫描页.pdf";
const INTERLEAVED_NAME = "P001_合同段落表格交错.docx";
const CATEGORY_IDS = ["QUANTITATIVE", "DRAFT_STRUCTURE", "BREACH", "TERM", "PARTY_QUALIFICATION",
  "ATTACHMENT_COMPLETENESS", "EFFECTIVENESS_COPIES", "CLAUSE_RISK"];
const JOB_STATES = new Set(["QUEUED", "PROCESSING", "SUCCEEDED", "FAILED", "UNKNOWN"]);
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const HASH = /^[0-9a-f]{64}$/;
const BROWSERS = { chrome: "C:/Program Files/Google/Chrome/Application/chrome.exe",
  edge: "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe" };

class SafeFailure extends Error {
  constructor(code) { super(code); this.code = code; }
}
function check(value, code) { if (!value) throw new SafeFailure(code); }
function sha(value) { return createHash("sha256").update(value).digest("hex"); }
function id(value) { check(typeof value === "string" && UUID.test(value), "INVALID_RESOURCE_ID"); return value; }
function nullableId(value) { return value === null || value === undefined ? null : id(value); }
function inside(parent, child) {
  const relative = path.relative(parent, child);
  return relative !== "" && !relative.startsWith("..") && !path.isAbsolute(relative);
}
function integer(value) { return Number.isSafeInteger(value) && value >= 0; }
function parseOptions(args) {
  const options = { phase: "verify-fixtures", headed: false, checkDrafts: false,
    uiTimeoutMs: 30000, acceptanceTimeoutMs: 180000 };
  const strings = { "--phase": "phase", "--browser": "browser", "--receipts": "receipts",
    "--project-id": "projectId", "--project-b-id": "projectBId", "--contract-id": "contractId", "--run-id": "runId", "--report-id": "reportId" };
  for (let index = 0; index < args.length; index++) {
    const arg = args[index];
    if (arg === "--headed") options.headed = true;
    else if (arg === "--check-drafts") options.checkDrafts = true;
    else if (strings[arg]) { check(args[index + 1] && !args[index + 1].startsWith("--"), "INVALID_OPTION");
      options[strings[arg]] = args[++index]; }
    else if (arg === "--ui-timeout-ms" || arg === "--acceptance-timeout-ms") {
      const value = Number(args[++index]); check(integer(value) && value >= 5000 && value <= 300000, "INVALID_TIMEOUT");
      options[arg === "--ui-timeout-ms" ? "uiTimeoutMs" : "acceptanceTimeoutMs"] = value;
    } else throw new SafeFailure("INVALID_OPTION");
  }
  check(["verify-fixtures", "login", "intake", "continue-intake", "finish-intake", "reopen", "opinions"].includes(options.phase), "INVALID_PHASE");
  check(!options.browser || options.browser in BROWSERS, "INVALID_BROWSER");
  if (options.phase === "reopen") check(options.receipts, "RECEIPTS_REQUIRED");
  if (options.phase === "continue-intake") id(options.projectId);
  if (options.phase === "finish-intake") { id(options.projectId); id(options.projectBId); check(options.receipts, "RECEIPTS_REQUIRED"); }
  if (options.phase === "opinions") {
    id(options.projectId); id(options.contractId); id(options.runId);
    if (options.reportId) id(options.reportId);
  }
  return options;
}

async function verifiedFile(root, relative) {
  check(typeof relative === "string" && !relative.includes("\\") && !relative.split("/").includes(".."), "UNSAFE_FIXTURE_PATH");
  const resolved = path.resolve(root, relative);
  check(inside(root, resolved), "UNSAFE_FIXTURE_PATH");
  const actual = await realpath(resolved), actualRoot = await realpath(root);
  check(inside(actualRoot, actual), "FIXTURE_SYMLINK_ESCAPE");
  check((await stat(actual)).size <= 100 * 1024 * 1024, "FIXTURE_TOO_LARGE");
  return { path: actual, bytes: await readFile(actual) };
}

// Narrow approved-fixture validator, never extraction/storage/business ZIP processing.
function verifyZip(bytes, expected) {
  let end = -1;
  for (let pos = bytes.length - 22; pos >= Math.max(0, bytes.length - 65557); pos--) {
    if (bytes.readUInt32LE(pos) === 0x06054b50) { end = pos; break; }
  }
  check(end >= 0, "FIXTURE_ZIP_INVALID");
  const count = bytes.readUInt16LE(end + 10), centralSize = bytes.readUInt32LE(end + 12), central = bytes.readUInt32LE(end + 16);
  check(bytes.readUInt16LE(end + 4) === 0 && bytes.readUInt16LE(end + 6) === 0
    && count === bytes.readUInt16LE(end + 8) && central + centralSize === end
    && end + 22 + bytes.readUInt16LE(end + 20) === bytes.length && count === expected.size, "FIXTURE_ZIP_INVALID");
  const seen = new Set(); let cursor = central;
  for (let index = 0; index < count; index++) {
    check(cursor + 46 <= end && bytes.readUInt32LE(cursor) === 0x02014b50, "FIXTURE_ZIP_INVALID");
    const flags = bytes.readUInt16LE(cursor + 8), method = bytes.readUInt16LE(cursor + 10);
    const compressedSize = bytes.readUInt32LE(cursor + 20), size = bytes.readUInt32LE(cursor + 24);
    const nameSize = bytes.readUInt16LE(cursor + 28), extraSize = bytes.readUInt16LE(cursor + 30), commentSize = bytes.readUInt16LE(cursor + 32);
    const local = bytes.readUInt32LE(cursor + 42);
    check(cursor + 46 + nameSize + extraSize + commentSize <= end, "FIXTURE_ZIP_INVALID");
    const name = bytes.subarray(cursor + 46, cursor + 46 + nameSize).toString("utf8");
    check(!name.includes("/") && !name.includes("\\") && !seen.has(name) && expected.has(name), "FIXTURE_ZIP_ENTRIES_MISMATCH");
    check(!(flags & 1) && [0, 8].includes(method) && local + 30 <= central
      && bytes.readUInt32LE(local) === 0x04034b50 && bytes.readUInt16LE(local + 8) === method, "FIXTURE_ZIP_INVALID");
    const localNameSize = bytes.readUInt16LE(local + 26), localExtraSize = bytes.readUInt16LE(local + 28);
    const payload = local + 30 + localNameSize + localExtraSize;
    check(payload + compressedSize <= central
      && bytes.subarray(local + 30, local + 30 + localNameSize).toString("utf8") === name, "FIXTURE_ZIP_INVALID");
    const compressed = bytes.subarray(payload, payload + compressedSize);
    const content = method === 0 ? compressed : inflateRawSync(compressed, { maxOutputLength: 8 * 1024 * 1024 });
    const metadata = expected.get(name);
    check(content.length === size && size === metadata.bytes && sha(content) === metadata.sha256, "FIXTURE_ZIP_CONTENT_MISMATCH");
    seen.add(name); cursor += 46 + nameSize + extraSize + commentSize;
  }
  check(cursor === end && seen.size === expected.size, "FIXTURE_ZIP_ENTRIES_MISMATCH");
}

async function verifyFixtures() {
  const manifestFile = await verifiedFile(FIXTURE_ROOT, "manifest.json");
  check(sha(manifestFile.bytes) === APPROVED_MANIFEST_HASH, "UNAPPROVED_MANIFEST");
  const manifest = JSON.parse(manifestFile.bytes.toString("utf8"));
  check(manifest.synthetic === true && manifest.no_real_customer_data === true
    && manifest.generated_for === "local-regression-not-customer-acceptance"
    && manifest.scan_files === 6 && manifest.scan_pages_per_file === 8, "FIXTURE_NOT_APPROVED_SYNTHETIC");
  const uploadMetadata = new Map(), selected = new Map();
  for (const [relative, metadata] of Object.entries(manifest.files)) {
    const fixture = await verifiedFile(FIXTURE_ROOT, relative);
    check(sha(fixture.bytes) === metadata.sha256, "FIXTURE_HASH_MISMATCH");
    if (metadata.bytes !== undefined) check(fixture.bytes.length === metadata.bytes, "FIXTURE_SIZE_MISMATCH");
    if (metadata.codepoints !== undefined) {
      const text = fixture.bytes.toString("utf8");
      check([...text].length === metadata.codepoints, "FIXTURE_CODEPOINT_MISMATCH");
      if (metadata.utf16_units !== undefined) check(text.length === metadata.utf16_units, "FIXTURE_UTF16_MISMATCH");
    }
    if (relative.startsWith("upload_files/")) {
      uploadMetadata.set(path.posix.basename(relative), metadata);
      selected.set(path.posix.basename(relative), fixture.path);
    }
  }
  check(uploadMetadata.size === 9 && [...uploadMetadata.values()].filter((value) => value.pages === 8).length === 6,
    "FIXTURE_UPLOAD_SET_MISMATCH");
  const zip = await verifiedFile(FIXTURE_ROOT, ZIP_NAME);
  check(sha(zip.bytes) === APPROVED_ZIP_HASH, "UNAPPROVED_ZIP"); verifyZip(zip.bytes, uploadMetadata);
  const allowedA = new Set([APPROVED_ZIP_HASH, ...[...uploadMetadata.values()].map((value) => value.sha256)]);
  const allowedB = new Set([uploadMetadata.get(MIXED_NAME).sha256, uploadMetadata.get(INTERLEAVED_NAME).sha256]);
  return { zip: zip.path, mixed: selected.get(MIXED_NAME), interleaved: selected.get(INTERLEAVED_NAME),
    allowedA, allowedB, allHashes: new Set([...allowedA, ...allowedB]),
    summary: { manifestSha256: APPROVED_MANIFEST_HASH, zipSha256: APPROVED_ZIP_HASH,
      checkedFiles: Object.keys(manifest.files).length, checkedZipEntries: uploadMetadata.size, synthetic: true } };
}

async function credentialsFromStdin() {
  check(!process.stdin.isTTY, "CREDENTIAL_STDIN_REQUIRED");
  const chunks = []; let length = 0;
  for await (const chunk of process.stdin) { length += chunk.length; check(length <= 16384, "CREDENTIAL_INPUT_TOO_LARGE"); chunks.push(chunk); }
  const bytes = Buffer.concat(chunks); let credentials;
  try { credentials = JSON.parse(bytes.toString("utf8")); } catch { throw new SafeFailure("INVALID_CREDENTIAL_INPUT"); }
  finally { bytes.fill(0); chunks.forEach((chunk) => chunk.fill(0)); }
  check(credentials && Object.keys(credentials).every((key) => ["username", "password"].includes(key))
    && typeof credentials.username === "string" && credentials.username.length > 0
    && typeof credentials.password === "string" && credentials.password.length > 0, "INVALID_CREDENTIAL_INPUT");
  return credentials;
}

async function scopedRuntime() {
  await mkdir(TEST_ROOT, { recursive: true });
  const base = await realpath(TEST_ROOT);
  check(inside(await realpath(WORKTREE), base), "UNSAFE_RUNTIME_ROOT");
  const directory = path.join(base, "browser-acceptance", `run-${Date.now()}-${randomUUID()}`);
  await mkdir(directory, { recursive: true });
  check(inside(base, await realpath(directory)), "UNSAFE_RUNTIME_ROOT");
  const profile = path.join(directory, "browser-profile"), downloads = path.join(directory, "downloads");
  await mkdir(profile); await mkdir(downloads);
  return { directory, profile, downloads };
}
async function writeArtifact(runtime, basename, data) {
  check(/^[a-z0-9-]+\.json$/.test(basename), "UNSAFE_ARTIFACT_NAME");
  await writeFile(path.join(runtime.directory, basename), `${JSON.stringify(data, null, 2)}\n`, { flag: "wx" });
}
async function readiness() {
  for (const url of [ORIGIN, `${ORIGIN}/api/v1/health`]) {
    let response; try { response = await fetch(url, { signal: AbortSignal.timeout(10000), redirect: "error" }); }
    catch { throw new SafeFailure("LOOPBACK_SERVICE_NOT_READY"); }
    check(response.ok, "LOOPBACK_SERVICE_NOT_READY"); await response.body?.cancel();
  }
}
async function api(context, relative) {
  check(relative.startsWith("/api/v1/") && !relative.includes(".."), "UNSAFE_API_PATH");
  const response = await context.request.get(`${ORIGIN}${relative}`, { timeout: 30000, maxRedirects: 0 });
  check(response.status() === 200, "SERVER_READ_UNAVAILABLE");
  let envelope; try { envelope = await response.json(); } catch { throw new SafeFailure("INVALID_SERVER_ENVELOPE"); }
  check(envelope.code === 200 && envelope.data !== undefined, "INVALID_SERVER_ENVELOPE");
  return envelope.data;
}
function safeJob(job, projectId) {
  check(job && job.projectId === projectId && JOB_STATES.has(job.status) && integer(job.attemptNo), "JOB_BINDING_OR_STATE_INVALID");
  return { jobId: id(job.jobId), projectId: id(job.projectId), fileId: id(job.fileId),
    status: job.status, attemptNo: job.attemptNo, parseResultId: nullableId(job.parseResultId) };
}
function safeReceipt(value) {
  const projectId = id(value.projectId), fileId = id(value.fileId), jobId = id(value.jobId);
  check(value.file?.id === fileId && value.file?.projectId === projectId
    && value.job?.jobId === jobId && value.job?.fileId === fileId, "RECEIPT_BINDING_INVALID");
  const job = safeJob(value.job, projectId);
  check(HASH.test(value.file.fileSha256), "RECEIPT_FILE_HASH_UNAVAILABLE");
  return { projectId, fileId, jobId, fileSha256: value.file.fileSha256, accepted: true, status: job.status };
}
function observe(page) {
  const observer = { creates: [], receipts: [], admissions: 0, createRequests: 0,
    errors: [], consoleErrors: 0, pageErrors: 0, externalRequests: 0, businessWrites: 0, pending: new Set() };
  page.on("console", (message) => { if (message.type() === "error") observer.consoleErrors++; });
  page.on("pageerror", () => observer.pageErrors++);
  page.on("request", (request) => {
    const url = new URL(request.url());
    if (["http:", "https:"].includes(url.protocol) && url.origin !== ORIGIN) observer.externalRequests++;
    if (["POST", "PUT", "PATCH", "DELETE"].includes(request.method()) && url.pathname.startsWith("/api/v1/")
      && !url.pathname.startsWith("/api/v1/auth/")) observer.businessWrites++;
    if (request.method() !== "POST") return;
    if (url.pathname === "/api/v1/projects") observer.createRequests++;
    if (/^\/api\/v1\/projects\/[^/]+\/file-jobs$/.test(url.pathname)) observer.admissions++;
  });
  page.on("response", (response) => {
    const request = response.request(), url = new URL(response.url());
    if (request.method() !== "POST" || !(url.pathname === "/api/v1/projects"
      || /^\/api\/v1\/projects\/[^/]+\/file-jobs$/.test(url.pathname))) return;
    const capture = (async () => {
      const envelope = await response.json(); check(envelope.code === 200, "ADMISSION_REJECTED");
      const key = await request.headerValue("idempotency-key");
      check(typeof key === "string" && /^[A-Za-z0-9._:-]{1,128}$/.test(key), "CLIENT_OPERATION_IDENTITY_MISSING");
      if (url.pathname === "/api/v1/projects") {
        check(response.status() === 200 || response.status() === 201, "PROJECT_CREATE_NOT_ACCEPTED");
        observer.creates.push({ projectId: id(envelope.data.id), key, operationKeySha256: sha(key) });
      } else {
        check(response.status() === 202, "ASYNC_UPLOAD_NOT_HTTP_202");
        observer.receipts.push({ ...safeReceipt(envelope.data), key, operationKeySha256: sha(key) });
      }
    })().catch((error) => observer.errors.push(error instanceof SafeFailure ? error.code : "ADMISSION_CAPTURE_ERROR"));
    observer.pending.add(capture); void capture.finally(() => observer.pending.delete(capture));
  });
  return observer;
}
async function until(predicate, timeout, code) {
  const start = Date.now();
  while (Date.now() - start < timeout) { if (await predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 250)); }
  throw new SafeFailure(code);
}
async function login(page, credentials, options) {
  await page.goto(ORIGIN, { waitUntil: "domcontentloaded" });
  const loginMain = page.getByRole("main", { name: "智能审计系统登录" });
  await loginMain.waitFor({ timeout: options.uiTimeoutMs });
  await page.getByLabel("用户账号", { exact: true }).fill(credentials.username);
  await page.getByLabel("登录密码", { exact: true }).fill(credentials.password);
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await page.getByRole("navigation", { name: "业务工具", exact: true }).waitFor({ timeout: options.uiTimeoutMs });
  check(await page.locator('input[type="password"]').count() === 0, "LOGIN_FORM_STILL_MOUNTED");
}
async function activateButton(button) {
  await button.scrollIntoViewIfNeeded();
  await button.focus();
  await button.evaluate((node) => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve(
    document.activeElement === node))))).then((focused) => check(focused, "VISIBLE_BUTTON_FOCUS_NOT_OBTAINED"));
  await button.press("Enter");
}
async function portal(page, options, tab = "审核记录") {
  const navigation = page.getByRole("navigation", { name: "业务工具", exact: true });
  const returnMaterials = page.getByRole("button", { name: "返回材料核验列表", exact: true });
  if (await returnMaterials.isVisible()) await returnMaterials.click();
  // Explicit visible navigation unmounts any prior embedded ProjectWorkspace; no storage/route injection.
  safeStage = "PORTAL_WORKBENCH";
  await activateButton(navigation.getByRole("button", { name: "工作台", exact: true }));
  await page.getByRole("heading", { name: "工作台", exact: true }).waitFor({ timeout: options.uiTimeoutMs });
  safeStage = "PORTAL_CONTRACT";
  await activateButton(navigation.getByRole("button", { name: "合同审核", exact: true }));
  await page.getByRole("heading", { name: "合同审核", exact: true }).waitFor({ timeout: options.uiTimeoutMs });
  await page.getByRole("navigation", { name: "合同审核中心", exact: true }).getByRole("button", { name: tab, exact: true }).click();
}
async function submitSelection(page, files, observer, expectedReceipts, options) {
  const input = page.locator('header input[type="file"][multiple]');
  check(await input.count() === 1 && await input.isEnabled(), "PORTAL_UPLOAD_NOT_AVAILABLE");
  await input.setInputFiles(files);
  await until(() => { check(observer.errors.length === 0, observer.errors[0] ?? "ADMISSION_CAPTURE_ERROR");
    return observer.receipts.length >= expectedReceipts; }, options.acceptanceTimeoutMs, "ASYNC_ADMISSION_TIMEOUT");
  const queue = page.locator('section[aria-label="材料后台处理进度"]');
  await queue.waitFor({ timeout: options.uiTimeoutMs });
}
async function snapshot(context, projectId, allowedHashes) {
  const [detail, jobs] = await Promise.all([api(context, `/api/v1/projects/${projectId}`),
    api(context, `/api/v1/projects/${projectId}/file-jobs`)]);
  check(detail.project?.id === projectId && jobs.projectId === projectId && typeof jobs.workerEnabled === "boolean"
    && Array.isArray(detail.files) && Array.isArray(jobs.items), "PROJECT_QUEUE_BINDING_INVALID");
  const files = detail.files.map((file) => {
    check(file.projectId === projectId && HASH.test(file.fileSha256) && allowedHashes.has(file.fileSha256), "UNAPPROVED_OR_FOREIGN_FILE");
    check(["PENDING", "PARSED", "FAILED", "UNSUPPORTED", "NOT_APPLICABLE"].includes(file.parseStatus)
      && ["UPLOAD_PENDING", "AVAILABLE", "UPLOAD_FAILED"].includes(file.ingestionStatus), "FILE_STATE_INVALID");
    return { fileId: id(file.id), parentArchiveFileId: nullableId(file.parentArchiveFileId),
      fileSha256: file.fileSha256, ingestionStatus: file.ingestionStatus, parseStatus: file.parseStatus,
      parseResultId: nullableId(file.parseResultId) };
  });
  const safeJobs = jobs.items.map((job) => safeJob(job, projectId));
  check(new Set(files.map((file) => file.fileId)).size === files.length
    && new Set(files.map((file) => file.fileSha256)).size === files.length
    && new Set(safeJobs.map((job) => job.jobId)).size === safeJobs.length
    && new Set(safeJobs.map((job) => job.fileId)).size === safeJobs.length, "DUPLICATE_FILE_OR_JOB");
  for (const job of safeJobs) check(files.some((file) => file.fileId === job.fileId), "JOB_FILE_NOT_DISCOVERABLE");
  for (const file of files) if (file.parentArchiveFileId)
    check(files.some((parent) => parent.fileId === file.parentArchiveFileId && parent.fileSha256 === APPROVED_ZIP_HASH), "CROSS_PROJECT_ARCHIVE_PARENT");
  const counts = { total: safeJobs.length, completed: safeJobs.filter((job) => job.status === "SUCCEEDED").length,
    failed: safeJobs.filter((job) => job.status === "FAILED").length, unknown: safeJobs.filter((job) => job.status === "UNKNOWN").length };
  check(Object.entries(counts).every(([key, value]) => jobs[key] === value), "QUEUE_COUNTS_NOT_REAL");
  return { projectId: id(projectId), workerEnabled: jobs.workerEnabled, ...counts,
    files: files.sort((a, b) => a.fileId.localeCompare(b.fileId)), jobs: safeJobs.sort((a, b) => a.jobId.localeCompare(b.jobId)) };
}
function active(snapshot) { return snapshot.jobs.some((job) => job.status === "QUEUED" || job.status === "PROCESSING"); }
function unchanged(before, after) {
  check(before.projectId === after.projectId, "REOPEN_PROJECT_CHANGED");
  for (const file of before.files) { const current = after.files.find((value) => value.fileId === file.fileId);
    check(current && current.fileSha256 === file.fileSha256 && current.parentArchiveFileId === file.parentArchiveFileId,
      "REOPEN_FILE_IDENTITY_CHANGED");
    if (file.parseResultId) check(current.parseResultId === file.parseResultId, "FROZEN_PARSE_IDENTITY_CHANGED");
  }
  for (const job of before.jobs) { const current = after.jobs.find((value) => value.jobId === job.jobId);
    check(current && current.fileId === job.fileId && current.attemptNo >= job.attemptNo, "REOPEN_JOB_IDENTITY_CHANGED");
    if (job.parseResultId) check(current.parseResultId === job.parseResultId, "FROZEN_JOB_PARSE_IDENTITY_CHANGED");
  }
}
function independent(a, b) {
  check(a.projectId !== b.projectId, "TWO_PROJECTS_NOT_INDEPENDENT");
  check(a.files.every((file) => !b.files.some((other) => other.fileId === file.fileId))
    && a.jobs.every((job) => !b.jobs.some((other) => other.jobId === job.jobId || other.fileId === job.fileId)), "CROSS_PROJECT_RESOURCE_REUSE");
}
async function reopenUi(page, context, projectId, options) {
  const responses = [];
  const listener = (response) => { const url = new URL(response.url());
    if (response.request().method() === "GET" && /\/api\/v1\/projects\/(intake-drafts|preparation-drafts)$/.test(url.pathname)) responses.push(response); };
  page.on("response", listener);
  try {
    await portal(page, options, "待继续");
    await until(async () => {
      for (const response of responses) {
        if (response.status() !== 200) continue;
        const data = (await response.json()).data;
        if (!Array.isArray(data?.items)) continue;
        const index = data.items.findIndex((item) => item.projectId === projectId);
        if (index < 0) continue;
        const collection = new URL(response.url()).pathname.endsWith("/intake-drafts") ? "intake-drafts-heading" : "preparation-drafts-heading";
        const section = page.locator(`section[aria-labelledby="${collection}"]`);
        const articles = section.locator("article");
        if (await articles.count() !== data.items.length) continue;
        const projectRead = page.waitForResponse((value) => new URL(value.url()).pathname === `/api/v1/projects/${projectId}`
          && value.request().method() === "GET", { timeout: options.uiTimeoutMs });
        await articles.nth(index).getByRole("button").first().click();
        check((await projectRead).status() === 200, "UI_REOPEN_PROJECT_READ_FAILED");
        safeStage = "REOPEN_ACCEPTED_QUEUE";
        await page.locator('section[aria-label="材料后台处理进度"]').waitFor({ timeout: options.uiTimeoutMs });
        return true;
      }
      return false;
    }, options.uiTimeoutMs, "PROJECT_NOT_DISCOVERED_IN_VISIBLE_PENDING_FIRST_PAGE");
    const real = await api(context, `/api/v1/projects/${projectId}`); check(real.project?.id === projectId, "UI_REOPEN_BINDING_INVALID");
  } finally { page.off("response", listener); }
}
async function queueScreenshot(page, runtime, basename) {
  check(await page.locator('input[type="password"]').count() === 0, "LOGIN_SCREENSHOT_FORBIDDEN");
  await page.locator('section[aria-label="材料后台处理进度"]').screenshot({ path: path.join(runtime.directory, basename),
    animations: "disabled", timeout: 30000 });
}
async function checkDirtyDrafts(page, projectId, options) {
  const markers = ["SYNTHETIC-UNSAVED-NAME", "SYNTHETIC-UNSAVED-NO", "SYNTHETIC-UNSAVED-CATEGORY"];
  const labels = ["项目名称", "项目编号", "项目类目"];
  for (let index = 0; index < labels.length; index++) await page.getByLabel(labels[index], { exact: true }).fill(markers[index]);
  const checkbox = page.locator('section[aria-label="关联中标材料"] input[type="checkbox"]:enabled').first();
  const hasCheckbox = await checkbox.count() > 0; let selection;
  if (hasCheckbox) { selection = !await checkbox.isChecked(); await checkbox.setChecked(selection); }
  for (let poll = 0; poll < 3; poll++) {
    await page.waitForResponse((response) => response.request().method() === "GET"
      && new URL(response.url()).pathname === `/api/v1/projects/${projectId}/file-jobs`, { timeout: options.uiTimeoutMs });
    for (let index = 0; index < labels.length; index++)
      check(await page.getByLabel(labels[index], { exact: true }).inputValue() === markers[index], "DIRTY_METADATA_RESET_BY_QUEUE_POLL");
    if (hasCheckbox) check(await checkbox.isChecked() === selection && await checkbox.isEnabled(), "DIRTY_SUPPORT_RESET_OR_LOCKED_BY_QUEUE_POLL");
  }
  return { metadataPolls: 3, metadata: "VERIFIED", supportSelection: hasCheckbox ? "VERIFIED" : "NOT_VERIFIED_NO_ELIGIBLE_CONFIRMED_AWARD" };
}
async function reconcileOriginalOperations(context, observer) {
  for (const creation of observer.creates) {
    const project = await api(context, `/api/v1/projects/creation-operations?key=${encodeURIComponent(creation.key)}`);
    check(project.id === creation.projectId, "ORIGINAL_CREATE_OPERATION_CHANGED");
  }
  for (const receipt of observer.receipts) {
    const reopened = safeReceipt(await api(context, `/api/v1/projects/${receipt.projectId}/file-jobs/by-operation?key=${encodeURIComponent(receipt.key)}`));
    check(reopened.projectId === receipt.projectId && reopened.fileId === receipt.fileId
      && reopened.jobId === receipt.jobId && reopened.fileSha256 === receipt.fileSha256, "ORIGINAL_UPLOAD_OPERATION_CHANGED");
    const processing = safeJob(await api(context,
      `/api/v1/projects/${receipt.projectId}/files/${receipt.fileId}/processing`), receipt.projectId);
    check(processing.jobId === receipt.jobId && processing.fileId === receipt.fileId, "ORIGINAL_FILE_PROCESSING_IDENTITY_CHANGED");
  }
}
async function projectInventory(context) {
  const ids = new Set();
  for (const collection of ["", "/intake-drafts", "/preparation-drafts"]) {
    for (let page = 1; page <= 20; page++) {
      const data = await api(context, `/api/v1/projects${collection}?page=${page}&size=20`);
      check(Array.isArray(data.items) && integer(data.total), "PROJECT_INVENTORY_UNAVAILABLE");
      for (const item of data.items) ids.add(id(collection ? item.projectId : item.id));
      if (page * 20 >= data.total) break;
      check(page < 20, "PROJECT_INVENTORY_EXCEEDS_ACCEPTANCE_BOUND");
    }
  }
  return ids;
}
async function intake(page, context, observer, fixtures, runtime, credentials, options) {
  safeStage = "INTAKE_INVENTORY";
  const projectsBefore = await projectInventory(context);
  let receiptA;
  const continued = options.phase === "continue-intake";
  if (continued) {
    check(projectsBefore.has(options.projectId), "EXISTING_PROJECT_A_NOT_DISCOVERABLE");
    const existing = await snapshot(context, options.projectId, fixtures.allowedA);
    const roots = existing.files.filter((file) => file.parentArchiveFileId === null && file.fileSha256 === APPROVED_ZIP_HASH);
    check(roots.length === 1, "EXISTING_PROJECT_A_NOT_APPROVED_PACKAGE");
    const job = existing.jobs.find((value) => value.fileId === roots[0].fileId);
    check(job, "EXISTING_PROJECT_A_JOB_MISSING");
    receiptA = { projectId: existing.projectId, fileId: roots[0].fileId, jobId: job.jobId,
      fileSha256: APPROVED_ZIP_HASH, accepted: true, status: job.status, reusedProvenAccepted: true };
    await reopenUi(page, context, existing.projectId, options);
  } else {
    await portal(page, options); await submitSelection(page, [fixtures.zip], observer, 1, options);
    receiptA = observer.receipts[0]; check(receiptA.fileSha256 === APPROVED_ZIP_HASH, "PROJECT_A_NOT_APPROVED_PACKAGE");
  }
  const aBeforeB = await snapshot(context, receiptA.projectId, fixtures.allowedA);
  check(active(aBeforeB), "PROJECT_A_NOT_ACTIVE_BEFORE_SECOND_PROJECT");
  await queueScreenshot(page, runtime, "project-a-accepted-queue.png");
  safeStage = "PORTAL_FOR_B";
  await portal(page, options, "审核记录");
  check(await page.getByRole("navigation", { name: "合同审核中心", exact: true }).getByRole("button", { name: "待继续", exact: true }).isEnabled(),
    "BACKGROUND_QUEUE_BLOCKS_HISTORY_NAVIGATION");
  await submitSelection(page, [fixtures.mixed, fixtures.interleaved], observer, continued ? 2 : 3, options);
  safeStage = "B_RECEIPTS_AND_SNAPSHOTS";
  const receiptsB = observer.receipts.slice(continued ? 0 : 1); check(receiptsB.length === 2 && receiptsB.every((value) => value.projectId === receiptsB[0].projectId)
    && new Set(receiptsB.map((value) => value.fileSha256)).size === 2
    && receiptsB.every((value) => fixtures.allowedB.has(value.fileSha256)), "PROJECT_B_SELECTION_NOT_INDEPENDENT");
  let a = await snapshot(context, receiptA.projectId, fixtures.allowedA), b = await snapshot(context, receiptsB[0].projectId, fixtures.allowedB);
  independent(a, b); check(active(a), "PROJECT_A_NOT_ACTIVE_WHILE_PROJECT_B_ACCEPTED");
  check(b.files.length === 2 && b.jobs.length === 2, "PROJECT_B_DUPLICATE_OR_MISSING_ADMISSION");
  await queueScreenshot(page, runtime, "project-b-accepted-queue.png");
  let drafts = null;
  safeStage = "DIRTY_DRAFTS";
  if (options.checkDrafts && active(b)) drafts = await checkDirtyDrafts(page, b.projectId, options);
  else if (options.checkDrafts) drafts = { metadata: "NOT_VERIFIED_NO_ACTIVE_QUEUE", supportSelection: "NOT_VERIFIED" };
  await portal(page, options, "审核记录"); await page.reload({ waitUntil: "domcontentloaded" });
  safeStage = "REFRESH_REOPEN_A";
  await page.getByRole("navigation", { name: "业务工具", exact: true }).waitFor({ timeout: options.uiTimeoutMs });
  await reopenUi(page, context, a.projectId, options); await queueScreenshot(page, runtime, "project-a-after-refresh.png");
  await page.getByRole("button", { name: "退出登录", exact: true }).click(); await login(page, credentials, options);
  safeStage = "RELOGIN_REOPEN_B";
  await reopenUi(page, context, b.projectId, options); await queueScreenshot(page, runtime, "project-b-after-relogin.png");
  await reconcileOriginalOperations(context, observer);
  safeStage = "FINAL_INTAKE_READS";
  const afterA = await snapshot(context, a.projectId, fixtures.allowedA), afterB = await snapshot(context, b.projectId, fixtures.allowedB);
  unchanged(aBeforeB, afterA); unchanged(a, afterA); unchanged(b, afterB); independent(afterA, afterB);
  const expectedCreates = continued ? 1 : 2, expectedUploads = continued ? 2 : 3;
  check(observer.createRequests === expectedCreates && observer.creates.length === expectedCreates
    && observer.admissions === expectedUploads && observer.receipts.length === expectedUploads
    && new Set(observer.creates.map((value) => value.projectId)).size === expectedCreates
    && new Set(observer.creates.map((value) => value.operationKeySha256)).size === expectedCreates
    && new Set(observer.receipts.map((value) => value.operationKeySha256)).size === expectedUploads
    && observer.errors.length === 0, "DUPLICATE_CLIENT_CREATE_OR_ADMISSION");
  check(observer.businessWrites === expectedCreates + expectedUploads, "UNEXPECTED_BUSINESS_MUTATION");
  const projectsAfter = await projectInventory(context), added = [...projectsAfter].filter((value) => !projectsBefore.has(value));
  check(added.length === expectedCreates && (continued ? projectsBefore.has(afterA.projectId) : added.includes(afterA.projectId)) && added.includes(afterB.projectId)
    && [...projectsBefore].every((value) => projectsAfter.has(value)), "SERVER_PROJECT_INVENTORY_DUPLICATED_OR_CHANGED");
  const sanitized = { schemaVersion: 1, origin: ORIGIN, syntheticManifestSha256: APPROVED_MANIFEST_HASH,
    projectA: afterA, projectB: afterB, receipts: [...(continued ? [receiptA] : []), ...observer.receipts.map(({ key, ...value }) => value)],
    creates: observer.creates.map(({ key, ...value }) => value), checks: { admissionWhileAActive: true,
      historyNavigation: true, refresh: true, relogin: true, originalOperationsReconciled: true,
      noDuplicateWrites: true, noCrossProjectResources: true, reusedProvenAcceptedA: continued, dirtyDrafts: drafts } };
  await writeArtifact(runtime, "intake-receipts.json", sanitized); return sanitized.checks;
}
async function loadReceipts(filename, fixtures) {
  const resolved = await realpath(path.resolve(WORKTREE, filename)), root = await realpath(TEST_ROOT);
  check(inside(root, resolved) && path.extname(resolved) === ".json" && (await stat(resolved)).size <= 1024 * 1024, "UNSAFE_RECEIPT_INPUT");
  const value = JSON.parse(await readFile(resolved, "utf8"));
  check(value.schemaVersion === 1 && value.origin === ORIGIN && value.syntheticManifestSha256 === APPROVED_MANIFEST_HASH,
    "UNAPPROVED_RECEIPT_INPUT");
  for (const [label, hashes] of [["projectA", fixtures.allowedA], ["projectB", fixtures.allowedB]]) {
    const snapshot = value[label]; id(snapshot.projectId);
    check(Array.isArray(snapshot.files) && Array.isArray(snapshot.jobs) && snapshot.files.length <= hashes.size, "INVALID_RECEIPT_SNAPSHOT");
    for (const file of snapshot.files) { id(file.fileId); nullableId(file.parentArchiveFileId); nullableId(file.parseResultId);
      check(hashes.has(file.fileSha256), "UNAPPROVED_RECEIPT_FILE"); }
    for (const job of snapshot.jobs) safeJob(job, snapshot.projectId);
  }
  independent(value.projectA, value.projectB); return value;
}
async function finishIntake(page, context, observer, fixtures, runtime, credentials, options) {
  safeStage = "FINISH_ACCEPTED_RECEIPT_PROOF";
  const input = await realpath(path.resolve(WORKTREE, options.receipts)), root = await realpath(TEST_ROOT);
  check(inside(root, input) && path.basename(input) === "failure-receipts.json" && (await stat(input)).size <= 1024 * 1024,
    "UNSAFE_ACCEPTED_RECEIPT_INPUT");
  const prior = JSON.parse(await readFile(input, "utf8"));
  check(prior.phase === "continue-intake" && prior.status === "NOT_VERIFIED" && prior.businessWriteCount === 3
    && prior.createRequests === 1 && prior.admissions === 2 && prior.creates?.length === 1
    && prior.creates[0].projectId === options.projectBId && prior.receipts?.length === 2 && prior.captureErrors?.length === 0,
    "PREVIOUS_B_ADMISSION_NOT_PROVEN");
  for (const receipt of prior.receipts) check(receipt.projectId === options.projectBId && fixtures.allowedB.has(receipt.fileSha256)
    && receipt.accepted === true && UUID.test(receipt.fileId) && UUID.test(receipt.jobId), "PREVIOUS_B_ADMISSION_NOT_PROVEN");
  const inventoryBefore = await projectInventory(context);
  const a = await snapshot(context, options.projectId, fixtures.allowedA), b = await snapshot(context, options.projectBId, fixtures.allowedB);
  independent(a, b); // Reopening must work both during processing and after the queue has drained.
  check(a.files.filter((file) => file.parentArchiveFileId === null && file.fileSha256 === APPROVED_ZIP_HASH).length === 1
    && b.files.length === 2 && b.jobs.length === 2, "PREVIOUS_A_B_RESOURCE_SHAPE_INVALID");
  for (const receipt of prior.receipts) check(b.files.some((file) => file.fileId === receipt.fileId && file.fileSha256 === receipt.fileSha256)
    && b.jobs.some((job) => job.jobId === receipt.jobId && job.fileId === receipt.fileId), "PREVIOUS_B_ADMISSION_CHANGED");
  safeStage = "FINISH_OPEN_B"; await reopenUi(page, context, b.projectId, options);
  let drafts = null;
  safeStage = "FINISH_DIRTY_DRAFTS";
  if (options.checkDrafts) drafts = active(b) ? await checkDirtyDrafts(page, b.projectId, options)
    : { metadata: "NOT_VERIFIED_NO_ACTIVE_QUEUE", supportSelection: "NOT_VERIFIED" };
  safeStage = "FINISH_HISTORY_AND_REFRESH"; await portal(page, options, "审核记录");
  await page.reload({ waitUntil: "domcontentloaded" });
  await page.getByRole("navigation", { name: "业务工具", exact: true }).waitFor({ timeout: options.uiTimeoutMs });
  safeStage = "FINISH_REFRESH_A"; await reopenUi(page, context, a.projectId, options);
  await queueScreenshot(page, runtime, "project-a-after-refresh.png");
  safeStage = "FINISH_LOGOUT"; await page.getByRole("button", { name: "退出登录", exact: true }).click();
  await login(page, credentials, options);
  safeStage = "FINISH_RELOGIN_B"; await reopenUi(page, context, b.projectId, options);
  await queueScreenshot(page, runtime, "project-b-after-relogin.png");
  safeStage = "FINISH_FROZEN_IDS";
  const afterA = await snapshot(context, a.projectId, fixtures.allowedA), afterB = await snapshot(context, b.projectId, fixtures.allowedB);
  unchanged(a, afterA); unchanged(b, afterB); independent(afterA, afterB);
  const inventoryAfter = await projectInventory(context);
  check(observer.businessWrites === 0 && inventoryBefore.size === inventoryAfter.size
    && [...inventoryBefore].every((value) => inventoryAfter.has(value)), "READ_RECONCILIATION_CREATED_DUPLICATE_BUSINESS_DATA");
  const checks = { previousThreeWritesProvedByCapturedSuccessfulResponses: true, historyNavigation: true,
    refresh: true, relogin: true, originalAcceptedFileAndJobIds: true, noDuplicateWrites: true,
    noCrossProjectResources: true, queueActiveAtReadReconciliation: active(a) || active(b),
    dirtyDrafts: drafts, originalOperationKeys: "ALREADY_PROVEN_ACCEPTED_NOT_REPLAYED_HERE" };
  await writeArtifact(runtime, "intake-receipts.json", { schemaVersion: 1, origin: ORIGIN,
    syntheticManifestSha256: APPROVED_MANIFEST_HASH, projectA: afterA, projectB: afterB, receipts: prior.receipts,
    creates: prior.creates, checks });
  return checks;
}
async function reopen(page, context, observer, fixtures, runtime, options) {
  const before = await loadReceipts(options.receipts, fixtures);
  await reopenUi(page, context, before.projectA.projectId, options); await queueScreenshot(page, runtime, "project-a-reopened.png");
  let drafts = null;
  if (options.checkDrafts) {
    const current = await snapshot(context, before.projectA.projectId, fixtures.allowedA);
    drafts = active(current) ? await checkDirtyDrafts(page, current.projectId, options) : { metadata: "NOT_VERIFIED_NO_ACTIVE_QUEUE" };
  }
  await reopenUi(page, context, before.projectB.projectId, options); await queueScreenshot(page, runtime, "project-b-reopened.png");
  const a = await snapshot(context, before.projectA.projectId, fixtures.allowedA), b = await snapshot(context, before.projectB.projectId, fixtures.allowedB);
  unchanged(before.projectA, a); unchanged(before.projectB, b); independent(a, b);
  check(observer.businessWrites === 0, "READ_ONLY_REOPEN_MUTATED_BUSINESS_STATE");
  await writeArtifact(runtime, "reopen-receipts.json", { schemaVersion: 1, origin: ORIGIN,
    syntheticManifestSha256: APPROVED_MANIFEST_HASH, projectA: a, projectB: b, checks: { readOnly: true,
      originalFileAndJobIds: true, frozenParseIds: true, noCrossProjectResources: true, dirtyDrafts: drafts } });
  return { readOnly: true, originalFileAndJobIds: true, frozenParseIds: true, noCrossProjectResources: true };
}

async function denyClipboard(context, page) {
  try {
    const pageSession = await context.newCDPSession(page);
    const { targetInfo } = await pageSession.send("Target.getTargetInfo");
    await pageSession.detach();
    const session = await context.browser().newBrowserCDPSession();
    clipboardSession = session;
    await session.send("Browser.grantPermissions", { permissions: [], origin: ORIGIN,
      ...(targetInfo.browserContextId ? { browserContextId: targetInfo.browserContextId } : {}) });
    const states = await page.evaluate(async () => ({
      write: (await navigator.permissions.query({ name: "clipboard-write" })).state,
      read: (await navigator.permissions.query({ name: "clipboard-read" })).state,
    }));
    check(states.write === "denied" && states.read === "denied", "NATIVE_CLIPBOARD_DENIAL_NOT_CONFIGURED");
    // The installed Chromium resets this override on CDP detach. Keep it until copy validation ends.
  } catch { throw new SafeFailure("NATIVE_CLIPBOARD_DENIAL_NOT_CONFIGURED"); }
}
function categoryValues(category, scope, includeEvidence = true) {
  return [category.title, category.statusLabel, category.summary, scope, ...(category.uncoveredScopes ?? []),
    ...(category.items ?? []).flatMap((item) => [item.title, item.summary, item.actualValue, item.expectedValue,
      ...(item.typedFacts ?? []), ...(includeEvidence ? (item.evidence ?? []).flatMap((evidence) => [evidence.fileName, evidence.excerpt]) : [])])]
    .filter((value) => typeof value === "string" && value.length > 0);
}
async function openHistoryRun(page, context, run, options) {
  const frozen = JSON.parse(run.contractSnapshotCanonicalJson), contractNo = frozen.contractNo ?? frozen.contract?.contractNo;
  check(typeof contractNo === "string" && contractNo.length > 0, "FROZEN_CONTRACT_NUMBER_UNAVAILABLE");
  await portal(page, options, "审核记录");
  const filter = page.getByPlaceholder("项目、合同或供应商", { exact: true });
  const responsePromise = page.waitForResponse((response) => {
    const url = new URL(response.url()); return url.pathname === "/api/v1/contract-audit-tasks"
      && url.searchParams.get("keyword") === contractNo && response.request().method() === "GET";
  }, { timeout: options.uiTimeoutMs });
  await filter.fill(contractNo); const response = await responsePromise;
  check(response.status() === 200, "HISTORY_FILTER_READ_FAILED");
  const data = (await response.json()).data;
  const items = Array.isArray(data?.items) ? data.items : [];
  check(items.some((item) => item.reviewRun?.runId === run.runId), "RUN_NOT_IN_VISIBLE_HISTORY_FIRST_PAGE");
  const rows = page.locator("tbody tr").filter({ hasText: contractNo });
  await until(async () => await rows.count() > 0, options.uiTimeoutMs, "HISTORY_ROW_NOT_RENDERED");
  const attempts = await rows.count();
  for (let index = 0; index < attempts; index++) {
    const received = page.waitForResponse((value) => value.request().method() === "GET"
      && /\/review-runs\/[^/]+$/.test(new URL(value.url()).pathname), { timeout: options.uiTimeoutMs });
    await rows.nth(index).getByRole("button", { name: "查看", exact: true }).click();
    const opened = (await (await received).json()).data;
    if (opened?.runId === run.runId) {
      await page.locator('section[aria-labelledby="eight-category-title"]').waitFor({ timeout: options.uiTimeoutMs }); return;
    }
    await page.getByRole("button", { name: "返回内审任务列表", exact: true }).click();
  }
  throw new SafeFailure("EXACT_HISTORY_RUN_NOT_OPENED");
}
async function manualFallback(page, expectedValues, expectedFullValue = null, categoryPrefix = null) {
  const fallback = page.getByRole("textbox", { name: "复制失败后的手动复制文本", exact: true });
  try { await fallback.waitFor({ timeout: 5000 }); } catch {
    if (runtime) {
      const native = await page.evaluate(async () => ({ writePermission: (await navigator.permissions.query({ name: "clipboard-write" })).state,
        secureContext: isSecureContext, clipboardAvailable: typeof navigator.clipboard?.writeText === "function" }));
      await writeArtifact(runtime, "clipboard-denial-diagnostic.json", { ...native,
        copySucceededStatusVisible: await page.getByText("综合意见已复制", { exact: true }).isVisible(),
        fallbackDomCount: await page.locator('textarea[aria-label="复制失败后的手动复制文本"]').count(),
        fallbackRoleCount: await fallback.count() });
    }
    throw new SafeFailure("NATIVE_CLIPBOARD_DENIAL_NOT_OBSERVED");
  }
  await until(async () => {
    const current = await fallback.inputValue();
    return expectedValues.every((part) => current.includes(part))
      && (expectedFullValue === null || current === expectedFullValue)
      && (categoryPrefix === null || current.startsWith(categoryPrefix));
  }, 5000, "COPY_PROJECTION_SEMANTICS_DIFFER");
  const value = await fallback.inputValue();
  check(expectedValues.every((part) => value.includes(part)) && (expectedFullValue === null || value === expectedFullValue), "COPY_PROJECTION_SEMANTICS_DIFFER");
  check(await fallback.getAttribute("readonly") !== null, "MANUAL_FALLBACK_NOT_READONLY");
  await fallback.focus();
  check(await fallback.evaluate((textarea) => textarea.selectionStart === 0 && textarea.selectionEnd === textarea.value.length), "MANUAL_FALLBACK_NOT_SELECTABLE");
  return { sha256: sha(value), characters: [...value].length, readonly: true, fullySelected: true };
}
async function opinions(page, context, observer, fixtures, runtime, options) {
  safeStage = "OPINION_READ";
  const prefix = `/api/v1/projects/${options.projectId}/contracts/${options.contractId}/review-runs/${options.runId}`;
  const run = await api(context, prefix);
  check(run.runId === options.runId && run.projectId === options.projectId && run.contractId === options.contractId
    && run.status === "COMPLETED", "READ_ONLY_OPINION_REQUIRES_COMPLETED_RUN");
  check(Array.isArray(run.materials) && run.materials.length > 0 && run.materials.every((material) =>
    fixtures.allHashes.has(material.fileSha256 ?? material.sha256)), "OPINION_RUN_NOT_APPROVED_SYNTHETIC");
  const projection = run.businessProjection;
  check(projection?.version === 1 && projection.runId === run.runId && projection.runStatus === "COMPLETED"
    && projection.categories?.length === 8 && projection.categories.every((category, index) => category.id === CATEGORY_IDS[index]),
    "CANONICAL_EIGHT_ITEM_PROJECTION_UNAVAILABLE");
  await openHistoryRun(page, context, run, options);
  safeStage = "OPINION_PAGE";
  const panel = page.locator('section[aria-labelledby="eight-category-title"]');
  check(await panel.locator("[data-category-row]").count() === 8, "PAGE_NOT_EIGHT_ITEMS");
  for (const category of projection.categories) {
    const row = panel.locator(`[data-category-row="${category.id}"]`);
    check((await row.innerText()).includes(category.title) && (await row.innerText()).includes(category.statusLabel), "PAGE_CATEGORY_SEMANTICS_DIFFER");
    if (await row.getAttribute("aria-expanded") === "true") await activateButton(row);
    check(await panel.locator(`[data-category-summary="${category.id}"]`).innerText() === category.summary, "PAGE_SUMMARY_SEMANTICS_DIFFER");
  }
  await denyClipboard(context, page);
  safeStage = "OPINION_OVERALL_COPY";
  await panel.locator("summary").filter({ hasText: "预览综合意见" }).click();
  const preview = await panel.getByRole("textbox", { name: "综合意见手动复制", exact: true }).inputValue();
  const allValues = projection.categories.flatMap((category) => categoryValues(category, projection.scopeStatement));
  check(allValues.every((part) => preview.includes(part)), "OVERALL_PREVIEW_CANONICAL_SEMANTICS_DIFFER");
  await activateButton(panel.getByRole("button", { name: "复制综合意见", exact: true }));
  const overall = await manualFallback(page, allValues, preview), itemCopies = [];
  for (const category of projection.categories) {
    safeStage = "OPINION_ITEM_" + category.id;
    const row = panel.locator(`[data-category-row="${category.id}"]`);
    if (await row.getAttribute("aria-expanded") !== "true") await activateButton(row);
    await until(async () => await row.getAttribute("aria-expanded") === "true", options.uiTimeoutMs, "CATEGORY_NOT_EXPANDED");
    const content = panel.locator(`[data-category-content="${category.id}"]`);
    const contentText = await content.innerText();
    check([category.summary, ...(category.uncoveredScopes ?? []), ...(category.items ?? []).flatMap((item) =>
      [item.title, item.summary, item.actualValue, item.expectedValue, ...(item.typedFacts ?? [])])]
      .filter((value) => value !== null && value !== undefined).every((part) => contentText.includes(part)), "PAGE_ITEM_FACTS_SEMANTICS_DIFFER");
    await activateButton(content.getByRole("button", { name: "复制本项意见", exact: true }));
    await content.getByRole("status").filter({ hasText: "浏览器未允许自动复制" }).waitFor();
    itemCopies.push({ categoryId: category.id, ...await manualFallback(page, categoryValues(category, projection.scopeStatement),
      null, `${category.order}. ${category.title}\n状态：${category.statusLabel}\n`) });
  }
  const reports = await api(context, `${prefix}/reports`), available = (Array.isArray(reports) ? reports : reports.items ?? [])
    .filter((report) => report.status === "AVAILABLE" && (!options.reportId || report.reportId === options.reportId));
  check(available.length > 0, "AVAILABLE_FROZEN_REPORT_REQUIRED");
  const report = available.find((value) => value.reportSchemaVersion === 3);
  check(report && report.reviewRunId === run.runId, "AVAILABLE_V3_REPORT_REQUIRED"); id(report.reportId);
  const artifactUrl = `${ORIGIN}${prefix}/reports/${report.reportId}/artifact`;
  await page.locator(`section[aria-label="合同审查报告"] a[download][href*="${report.reportId}"]`)
    .waitFor({ timeout: options.uiTimeoutMs });
  const first = await context.request.get(artifactUrl), second = await context.request.get(artifactUrl);
  check(first.status() === 200 && second.status() === 200, "FROZEN_REPORT_ARTIFACT_UNAVAILABLE");
  const bytes = await first.body(), repeated = await second.body();
  check(sha(bytes) === sha(repeated) && bytes.length > 0, "FROZEN_REPORT_BYTES_CHANGED_ON_GET");
  const semantics = await page.evaluate(({ html, values }) => {
    const text = new DOMParser().parseFromString(html, "text/html").body.textContent ?? "";
    return values.every((value) => text.includes(value));
  }, { html: bytes.toString("utf8"), values: allValues });
  check(semantics, "FROZEN_REPORT_CANONICAL_SEMANTICS_DIFFER");
  check(observer.businessWrites === 0, "OPINION_COPY_TRIGGERED_BUSINESS_WRITE");
  // No opinion/source text in screenshot: capture only fixed category headers, mask summaries/details/textarea.
  const screenshotPanel = panel.locator("[data-category-row]").first();
  await screenshotPanel.screenshot({ path: path.join(runtime.directory, "opinion-category-header.png"), animations: "disabled" });
  const result = { projectId: id(run.projectId), contractId: id(run.contractId), runId: id(run.runId),
    projectionVersion: projection.version, overall, itemCopies, reportId: report.reportId,
    reportSchemaVersion: report.reportSchemaVersion, reportSha256: sha(bytes), reportBytes: bytes.length,
    checks: { realNativeClipboardRejection: true, allEightSummaries: true, pageItemOverallReportSameSemantics: true,
      frozenReportReadBytesStable: true, readonlySelectableFallback: true, noExtraModelOrBusinessWrites: true } };
  await writeArtifact(runtime, "opinion-receipts.json", result); return result.checks;
}

let safePhase = "UNINITIALIZED", safeStage = "INITIALIZE", context, credentials, runtime, observer, clipboardSession;
try {
  const options = parseOptions(process.argv.slice(2)); safePhase = options.phase;
  const fixtures = await verifyFixtures();
  if (options.phase === "verify-fixtures") {
    process.stdout.write(`${JSON.stringify({ phase: options.phase, status: "VERIFIED_OFFLINE", ...fixtures.summary })}\n`);
  } else {
    credentials = await credentialsFromStdin(); await readiness();
    runtime = await scopedRuntime(); const require = createRequire(path.join(WORKTREE, "frontend", "package.json"));
    const { chromium } = require("playwright-core");
    const browser = options.browser ?? Object.keys(BROWSERS).find((key) => existsSync(BROWSERS[key]));
    check(browser && existsSync(BROWSERS[browser]), "INSTALLED_BROWSER_UNAVAILABLE");
    context = await chromium.launchPersistentContext(runtime.profile, { executablePath: BROWSERS[browser],
      headless: !options.headed, baseURL: ORIGIN, locale: "zh-CN", timezoneId: "Asia/Singapore",
      viewport: { width: 1440, height: 1000 }, acceptDownloads: false, downloadsPath: runtime.downloads,
      args: ["--no-first-run", "--disable-background-networking", "--disable-component-update",
        "--disable-save-password-bubble", "--disable-features=PasswordManagerEnabled,AutofillServerCommunication"] });
    context.setDefaultTimeout(options.uiTimeoutMs);
    await context.addInitScript(() => {
      window.__contractAcceptanceClicks = [];
      for (const kind of ["pointerdown", "pointerup", "click"]) document.addEventListener(kind, (event) => {
        const target = event.target instanceof Element ? event.target : null;
        const category = target?.closest("[data-category-row]")?.getAttribute("data-category-row") ?? null;
        const button = target?.closest("button");
        const label = button?.closest('nav[aria-label="业务工具"]')
          ? (["工作台", "合同审核"].find((value) => button.textContent?.trim() === value) ?? "OTHER_NAV") : null;
        const entry = { kind, category, navigation: label, targetTag: target?.tagName ?? null, trusted: event.isTrusted,
          copyButton: button?.textContent?.trim() === "复制本项意见", x: Math.round(event.clientX), y: Math.round(event.clientY) };
        window.__contractAcceptanceClicks.push(entry);
        if (window.__contractAcceptanceClicks.length > 24) window.__contractAcceptanceClicks.shift();
      }, { capture: true });
    });
    const page = context.pages()[0] ?? await context.newPage(); observer = observe(page);
    safeStage = "LOGIN"; await login(page, credentials, options); let checks = { login: true };
    if (["intake", "continue-intake"].includes(options.phase)) checks = await intake(page, context, observer, fixtures, runtime, credentials, options);
    if (options.phase === "finish-intake") checks = await finishIntake(page, context, observer, fixtures, runtime, credentials, options);
    if (options.phase === "reopen") checks = await reopen(page, context, observer, fixtures, runtime, options);
    if (options.phase === "opinions") checks = await opinions(page, context, observer, fixtures, runtime, options);
    await Promise.all(observer.pending);
    check(observer.errors.length === 0 && observer.externalRequests === 0, "UNSAFE_REQUEST_OR_CAPTURE_ERROR");
    check(observer.pageErrors === 0, "BROWSER_PAGE_RUNTIME_ERROR");
    const summary = { phase: options.phase, status: "VERIFIED_REAL_BROWSER", origin: ORIGIN, browser,
      fixtures: fixtures.summary, checks, consoleErrorCount: observer.consoleErrors, pageErrorCount: observer.pageErrors,
      businessWriteCount: observer.businessWrites, evidenceDirectory: path.relative(WORKTREE, runtime.directory).replaceAll("\\", "/"),
      dm8DialectVerification: "ROOT_OWNED_NOT_INFERRED_FROM_HTTP", entireOcrCompletion: "NOT_WAITED_FOR" };
    await writeArtifact(runtime, "summary.json", summary); process.stdout.write(`${JSON.stringify(summary)}\n`);
  }
} catch (error) {
  const failureCode = error instanceof SafeFailure ? error.code : "BROWSER_ACCEPTANCE_ERROR";
  const safeDiagnostic = { stage: safeStage, errorType: ["Error", "TypeError", "TimeoutError", "TargetClosedError", "SafeFailure"].includes(error.name) ? error.name : "SUPPRESSED",
    nativeOperation: /^(?:locator|page|browserContext)\.[A-Za-z]+/.exec(error.message)?.[0] ?? null,
    networkCode: /net::ERR_[A-Z_]+/.exec(error.message)?.[0] ?? null, strictMode: error.message.includes("strict mode violation") };
  if (runtime && observer) {
    let safeDom = null;
    try {
      const page = context?.pages()[0];
      if (page) safeDom = { pageErrorCount: observer.pageErrors, consoleErrorCount: observer.consoleErrors,
        workbenchHeadings: await page.getByRole("heading", { name: "工作台", exact: true }).count(),
        contractHeadings: await page.getByRole("heading", { name: "合同审核", exact: true }).count(),
        portalNavigationCount: await page.getByRole("navigation", { name: "合同审核中心", exact: true }).count(),
        queueCount: await page.locator('section[aria-label="材料后台处理进度"]').count(),
        loginMainCount: await page.getByRole("main", { name: "智能审计系统登录" }).count(),
        documentFocused: await page.evaluate(() => document.hasFocus()),
        clickEvents: await page.evaluate(() => window.__contractAcceptanceClicks ?? []),
        categoryStates: await page.locator("[data-category-row]").evaluateAll((rows) => rows.map((row) =>
          ({ id: row.getAttribute("data-category-row"), expanded: row.getAttribute("aria-expanded") }))),
        navigationStates: await page.locator('nav[aria-label="业务工具"] button').evaluateAll((buttons) => buttons
          .filter((button) => ["工作台", "合同审核"].includes(button.textContent?.trim()))
          .map((button) => ({ label: button.textContent.trim(), active: button.getAttribute("data-active") }))) };
    } catch { /* No source text or raw browser errors. */ }
    try { await writeArtifact(runtime, "failure-receipts.json", { phase: safePhase, status: "NOT_VERIFIED", code: failureCode, ...safeDiagnostic,
      safeDom,
      creates: observer.creates.map(({ key, ...value }) => value), receipts: observer.receipts.map(({ key, ...value }) => value),
      businessWriteCount: observer.businessWrites, createRequests: observer.createRequests, admissions: observer.admissions,
      captureErrors: observer.errors }); } catch { /* Never render artifact failure details. */ }
  }
  // Never print Playwright exception.message/stack: it can contain filled credential values or raw locators.
  process.stderr.write(`${JSON.stringify({ phase: safePhase, status: "NOT_VERIFIED",
    code: failureCode, ...safeDiagnostic })}\n`); process.exitCode = 1;
} finally {
  if (credentials) { credentials.username = ""; credentials.password = ""; }
  if (clipboardSession) { try { await clipboardSession.detach(); } catch { /* scoped test context only */ } }
  if (context) { try { await context.close(); } catch { process.exitCode = 1; } }
}
