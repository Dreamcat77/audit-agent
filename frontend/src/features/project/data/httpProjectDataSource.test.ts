import assert from "node:assert/strict";
import { registerHooks } from "node:module";
import test from "node:test";
import type { CandidateSupportAuthorityReplacementRequest,
  CandidateSupportAuthorityReplacementResponse, ProjectDataSource } from "./projectDataSource";
import type { CandidateDetail, CandidateSupportAssociation } from "../types";

const hooks = registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith(".") && !/\.[a-z]+$/i.test(specifier)) return nextResolve(`${specifier}.ts`, context);
    return nextResolve(specifier, context);
  },
  load(url, context, nextLoad) {
    const loaded = nextLoad(url, context);
    if (!url.endsWith("/src/api/client.ts")) return loaded;
    const source = typeof loaded.source === "string" ? loaded.source : Buffer.from(loaded.source ?? []).toString("utf8");
    return { ...loaded, source: `import.meta.env ??= {};\n${source}` };
  },
});

const [{ HttpProjectDataSource }, { ApiError }] = await Promise.all([
  import("./httpProjectDataSource.ts"),
  import("../../../api/client.ts"),
]).finally(() => hooks.deregister());

const supportAssociation = {
  supportAuthorityId: "support-authority-1", projectId: "project-1", candidateId: "candidate-1",
  sourceFileId: "award-file-1", fileSha256: "a".repeat(64), parseResultId: "award-parse-1",
  parseVersion: 2, projectVersionNo: 3, effectiveRole: "AWARD_NOTICE",
  roleDecisionId: "award-role-1", identityResolution: "CONFIRM_SAME_PROJECT",
  identityDecisionId: "award-identity-1", sourceFactFingerprint: "b".repeat(64), authorityVersion: 1,
  createdBy: "auditor-1", createdAt: "2026-09-20T00:00:00Z",
} satisfies CandidateSupportAuthorityReplacementResponse["supportAssociations"][number];

const replacementResponse = {
  candidateId: "candidate-1", candidateVersion: 5, supportAuthorityVersion: 1,
  authorityMode: "EXPLICIT_SUPPORT_AUTHORITY", supportAssociations: [supportAssociation], replayed: false,
} satisfies CandidateSupportAuthorityReplacementResponse;

test("latest fact extraction reads the exact project and file projection", async (t) => {
  const previous = globalThis.fetch;
  t.after(() => { globalThis.fetch = previous; });
  const projection = {
    currentParseResultId: "parse-1", currentParseVersion: 2,
    attempt: { attemptId: "attempt-1", status: "SUCCEEDED", callCount: 2,
      acceptedCount: 4, rejectedCount: 1, failureReason: null },
    candidates: [],
    rejections: [{ scope: "LINE_ITEMS", reasonCode: "UNKNOWN_EVIDENCE_ID" }],
    modelCalls: [{ attemptNo: 1, scope: "CORE", callOrdinal: 1, outcome: "SUCCEEDED",
      safeReason: null, batchStatus: "PARTIAL" }],
  } as const;
  const calls: { url: string; init: RequestInit }[] = [];
  globalThis.fetch = async (input, init = {}) => {
    calls.push({ url: String(input), init });
    return envelope(projection);
  };

  const result = await new HttpProjectDataSource().getLatestFactExtraction("project/1", "file/1");

  assert.deepEqual(result, projection);
  assert.deepEqual(calls.map((call) => call.url), [
    "/api/v1/projects/project%2F1/files/file%2F1/contract-fact-extractions/latest",
  ]);
  assert.equal(calls[0].init.credentials, "include");
  assert.equal(calls[0].init.method, "GET");
});

test("support replacement sends only version and file IDs with the exact retained key and cookie/CSRF contract", async (t) => {
  const previous = globalThis.fetch;
  t.after(() => { globalThis.fetch = previous; });
  const calls: { url: string; init: RequestInit }[] = [];
  globalThis.fetch = async (input, init = {}) => {
    calls.push({ url: String(input), init });
    if (String(input) === "/api/v1/auth/csrf") return envelope({ headerName: "X-CSRF-TOKEN", token: "support-csrf" });
    return envelope(replacementResponse);
  };
  const source: ProjectDataSource = new HttpProjectDataSource();
  assert.equal(typeof source.replaceCandidateSupportAuthority, "function");
  // Structural typing can admit a wider object: serialization must still allow only command fields.
  const widerPayload = { expectedCandidateVersion: 4, supportFileIds: ["award-file-1"],
    parseResultId: "untrusted-parse", parseVersion: 999, effectiveRole: "AWARD_NOTICE",
    roleDecisionId: "untrusted-role", identityDecisionId: "untrusted-identity",
    identityResolution: "CONFIRM_SAME_PROJECT", category: "AWARD_NOTICE", fileSha256: "untrusted-hash",
    sourceFactFingerprint: "untrusted-facts", supportAuthorityFingerprint: "untrusted-cohort",
    originalFilename: "untrusted.pdf", awardAmount: 999, facts: [] };
  const payload: CandidateSupportAuthorityReplacementRequest = widerPayload;
  const signal = new AbortController().signal;
  const result: CandidateSupportAuthorityReplacementResponse = await source.replaceCandidateSupportAuthority(
    "project-1", "candidate-1", payload, { idempotencyKey: "support-key-retained", signal });

  assert.deepEqual(calls.map((call) => call.url), ["/api/v1/auth/csrf",
    "/api/v1/projects/project-1/contract-candidates/candidate-1/support-authority-replacements"]);
  const write = calls[1].init;
  assert.equal(write.method, "POST");
  assert.equal(write.credentials, "include");
  assert.equal(new Headers(write.headers).get("Idempotency-Key"), "support-key-retained");
  assert.equal(new Headers(write.headers).get("X-CSRF-TOKEN"), "support-csrf");
  assert.equal(new Headers(write.headers).get("Content-Type"), "application/json");
  assert.equal(write.signal, signal);
  assert.equal(calls[0].init.signal, signal);
  assert.deepEqual(JSON.parse(String(write.body)), { expectedCandidateVersion: 4, supportFileIds: ["award-file-1"] });
  assert.deepEqual(result, replacementResponse);
  assert.equal(result.replayed, false);
});

test("support replacement encodes path IDs and preserves an explicit empty set and primary-only replay", async (t) => {
  const previous = globalThis.fetch;
  t.after(() => { globalThis.fetch = previous; });
  const calls: { url: string; init: RequestInit }[] = [];
  const cleared: CandidateSupportAuthorityReplacementResponse = {
    candidateId: "candidate/1", candidateVersion: 6, supportAuthorityVersion: 2,
    authorityMode: "PRIMARY_ONLY_COMPAT", supportAssociations: [], replayed: true,
  };
  globalThis.fetch = async (input, init = {}) => {
    calls.push({ url: String(input), init });
    if (String(input) === "/api/v1/auth/csrf") return envelope({ headerName: "X-CSRF-TOKEN", token: "csrf" });
    return envelope(cleared);
  };
  const source = new HttpProjectDataSource();
  assert.equal(typeof source.replaceCandidateSupportAuthority, "function");
  const result = await source.replaceCandidateSupportAuthority("project/1", "candidate/1",
    { expectedCandidateVersion: 5, supportFileIds: [] }, { idempotencyKey: "clear-key-retained" });
  assert.equal(calls[1].url,
    "/api/v1/projects/project%2F1/contract-candidates/candidate%2F1/support-authority-replacements");
  assert.equal(new Headers(calls[1].init.headers).get("Idempotency-Key"), "clear-key-retained");
  assert.deepEqual(JSON.parse(String(calls[1].init.body)), { expectedCandidateVersion: 5, supportFileIds: [] });
  assert.deepEqual(result, cleared);
});

test("candidate reload preserves exact support projections and visible evidence separately from server field states", async (t) => {
  const previous = globalThis.fetch;
  t.after(() => { globalThis.fetch = previous; });
  const visibleEvidence = [100, 98].map((amount, index) => ({
    id: `evidence-${index}`, projectId: "project-1", sourceFileId: index ? "award-file-1" : "contract-file-1",
    parseResultId: index ? "award-parse-1" : "contract-parse-1", candidateId: index ? null : "candidate-1",
    factType: "AWARD_AMOUNT", groupKey: null, normalizedValue: String(amount), valueAmount: amount,
    sourceText: `Amount ${amount}`, pageNumber: 1, paragraphIndex: null, sourceLocator: "page:1",
  }));
  const projection: CandidateSupportAssociation = {
    supportAuthorityId: "support-authority-1", sourceFileId: "award-file-1", fileSha256: "a".repeat(64),
    parseResultId: "award-parse-1", parseVersion: 2, projectVersionNo: 3, effectiveRole: "AWARD_NOTICE",
    roleDecisionId: "award-role-1", identityResolution: "CONFIRM_SAME_PROJECT", identityDecisionId: "award-identity-1",
    sourceFactFingerprint: "b".repeat(64), authorityVersion: 1, originalFilename: "award.pdf",
    current: true, safeReasonCode: null, eligibleAwardFacts: [visibleEvidence[1]],
  };
  const detail: CandidateDetail = {
    candidate: { id: "candidate-1", projectId: "project-1", sourceContractFileId: "contract-file-1",
      sourceParseResultId: "contract-parse-1", sourceRoleDecisionId: "contract-role-1",
      sourceIdentityDecisionId: "contract-identity-1", supportAuthorityVersionNo: 1,
      supportAuthorityFingerprint: "c".repeat(64), contractNo: "C-1", contractName: "Contract",
      contractType: "GOODS", partyA: "Buyer", partyB: "Supplier", partyBCreditCode: null,
      contractAmount: 100, amountInWords: null, awardAmount: 98, awardSupplier: null,
      awardSupplierCreditCode: null, lineItems: [], promotionState: "DRAFT", versionNo: 5,
      promotedContractId: null, promotedConfirmationId: null, promotedBy: null, promotedAt: null,
      createdAt: "2026-09-20T00:00:00Z", updatedAt: "2026-09-20T00:00:00Z" },
    sourceFile: { id: "contract-file-1", projectId: "project-1", parentArchiveFileId: null,
      normalizedRelativePath: null, originalFilename: "contract.pdf", mediaType: "application/pdf",
      category: "CONTRACT_BODY", ingestionStatus: "AVAILABLE", parseStatus: "PARSED", sizeBytes: 1,
      parsedText: "contract", parseMethod: "PDF_TEXT", parseEngine: "PDFBox", parseConfidence: 1,
      pageCount: 1, parseDurationMs: 1, safeReasonCode: null, createdAt: "2026-09-20T00:00:00Z",
      updatedAt: "2026-09-20T00:00:00Z", progression: { stage: "READY", blocker: null,
        availableActions: [], candidateId: "candidate-1", contractId: null } },
    authorityMode: "EXPLICIT_SUPPORT_AUTHORITY", supportAuthorityVersionNo: 1,
    supportAuthorityFingerprint: "c".repeat(64), supportAuthorities: [projection],
    evidence: visibleEvidence, fieldStates: { AWARD_AMOUNT: "CONFIRMED", AWARD_SUPPLIER: "MISSING" },
    lineItemFieldStates: {},
  };
  const calls: string[] = [];
  globalThis.fetch = async (input) => { calls.push(String(input)); return envelope(detail); };
  const reloaded: CandidateDetail = await new HttpProjectDataSource().getCandidate("project-1", "candidate-1");
  assert.deepEqual(reloaded, detail);
  assert.equal(reloaded.candidate.awardAmount, 98);
  assert.equal(reloaded.fieldStates.AWARD_AMOUNT, "CONFIRMED");
  assert.deepEqual(reloaded.evidence.map((fact) => fact.valueAmount), [100, 98]);
  assert.equal(reloaded.supportAuthorities?.[0].eligibleAwardFacts[0].parseResultId, "award-parse-1");

  detail.supportAuthorities = [{ ...projection, originalFilename: null, current: false,
    safeReasonCode: "SUPPORT_AUTHORITY_NOT_CURRENT", eligibleAwardFacts: [] }];
  detail.fieldStates = { AWARD_AMOUNT: "MISSING", AWARD_SUPPLIER: "MISSING" };
  const stale = await new HttpProjectDataSource().getCandidate("project-1", "candidate-1");
  assert.deepEqual(stale.supportAuthorities, detail.supportAuthorities);
  assert.equal(stale.fieldStates.AWARD_AMOUNT, "MISSING");
  assert.deepEqual(stale.evidence.map((fact) => fact.valueAmount), [100, 98]);
  assert.deepEqual(calls, ["/api/v1/projects/project-1/contract-candidates/candidate-1",
    "/api/v1/projects/project-1/contract-candidates/candidate-1"]);
});

for (const status of [400, 404, 409]) {
  test(`support replacement preserves the existing ApiError and readable ${status} envelope data`, async (t) => {
    const previous = globalThis.fetch;
    t.after(() => { globalThis.fetch = previous; });
    const data = { classification: "UNPROVEN_OUTCOME", reason: "safe-server-detail" };
    globalThis.fetch = async (input) => {
      if (String(input) === "/api/v1/auth/csrf") return envelope({ headerName: "X-CSRF-TOKEN", token: "csrf" });
      return new Response(JSON.stringify({ code: status, message: "support command rejected", data }),
        { status, headers: { "Content-Type": "application/json" } });
    };
    const source = new HttpProjectDataSource();
    assert.equal(typeof source.replaceCandidateSupportAuthority, "function");
    await assert.rejects(source.replaceCandidateSupportAuthority("project-1", "candidate-1",
      { expectedCandidateVersion: 4, supportFileIds: ["award-file-1"] }, { idempotencyKey: "retained-key" }),
    (error: unknown) => {
      assert.ok(error instanceof ApiError);
      assert.equal(error.status, status);
      assert.equal(error.code, "BUSINESS_ERROR");
      assert.equal(error.message, "support command rejected");
      assert.deepEqual(error.data, data);
      return true;
    });
  });
}

test("support network uncertainty remains ApiError and an explicit retry sends the same key and payload", async (t) => {
  const previous = globalThis.fetch;
  t.after(() => { globalThis.fetch = previous; });
  const writes: RequestInit[] = [];
  globalThis.fetch = async (input, init = {}) => {
    if (String(input) === "/api/v1/auth/csrf") return envelope({ headerName: "X-CSRF-TOKEN", token: "csrf" });
    writes.push(init);
    if (writes.length === 1) throw new TypeError("connection lost");
    return envelope({ ...replacementResponse, replayed: true });
  };
  const source = new HttpProjectDataSource();
  assert.equal(typeof source.replaceCandidateSupportAuthority, "function");
  const payload: CandidateSupportAuthorityReplacementRequest = { expectedCandidateVersion: 4, supportFileIds: ["award-file-1"] };
  const options = { idempotencyKey: "uncertain-key-retained" };
  await assert.rejects(source.replaceCandidateSupportAuthority("project-1", "candidate-1", payload, options),
    (error: unknown) => {
      assert.ok(error instanceof ApiError);
      assert.equal(error.status, 0);
      assert.equal(error.code, "NETWORK_ERROR");
      assert.equal(error.message, "connection lost");
      assert.equal(error.data, undefined);
      return true;
    });
  assert.equal(writes.length, 1, "the datasource must not initiate another operation");
  const replayed = await source.replaceCandidateSupportAuthority("project-1", "candidate-1", payload, options);
  assert.deepEqual(replayed, { ...replacementResponse, replayed: true });
  assert.deepEqual(writes.map((write) => new Headers(write.headers).get("Idempotency-Key")),
    ["uncertain-key-retained", "uncertain-key-retained"]);
  assert.deepEqual(writes.map((write) => JSON.parse(String(write.body))), [payload, payload]);
});

test("candidate confirmation and promotion use separate keys and only promotion identity fields", async (t) => {
  const previous = globalThis.fetch;
  t.after(() => { globalThis.fetch = previous; });
  const calls = [];
  globalThis.fetch = (async (input, init = {}) => {
    calls.push({ url: String(input), init });
    if (String(input) === "/api/v1/auth/csrf") return envelope({ headerName: "X-CSRF-TOKEN", token: "csrf" });
    if (String(input).endsWith("/promote")) return envelope({ contractId: "contract" });
    return envelope({ confirmationId: "confirmation", resultCandidateVersion: 1 });
  });
  const source = new HttpProjectDataSource();
  const payload = { expectedVersion: 0, contractNo: "HUMAN", contractName: "Human",
    contractType: "GOODS", partyA: "Buyer", partyB: "Supplier", partyBCreditCode: null,
    contractAmount: 100, amountInWords: null, lineItems: [], awardNotice: null };
  await source.confirmCandidate("project", "candidate", payload, { idempotencyKey: "confirmation-key" });
  await source.getConfirmation("project", "candidate", "confirmation");
  await source.getLatestConfirmation("project", "candidate");
  await source.promote("project", "candidate", { expectedVersion: 1, confirmationId: "confirmation" },
    { idempotencyKey: "promotion-key" });
  const writes = calls.filter((value) => value.init.method === "POST");
  assert.deepEqual(writes.map((value) => new Headers(value.init.headers).get("Idempotency-Key")),
    ["confirmation-key", "promotion-key"]);
  assert.deepEqual(JSON.parse(writes[0].init.body), payload);
  assert.deepEqual(JSON.parse(writes[1].init.body),
    { expectedVersion: 1, confirmationId: "confirmation" });
  assert.deepEqual(calls.filter((value) => !value.url.endsWith("/auth/csrf")).map((value) => value.url), [
    "/api/v1/projects/project/contract-candidates/candidate/confirmations",
    "/api/v1/projects/project/contract-candidates/candidate/confirmations/confirmation",
    "/api/v1/projects/project/contract-candidates/candidate/confirmations/latest",
    "/api/v1/projects/project/contract-candidates/candidate/promote",
  ]);
});

test("role and project identity confirmations use explicit durable commands", async (t) => {
  const previous = globalThis.fetch;
  t.after(() => { globalThis.fetch = previous; });
  const calls = [];
  globalThis.fetch = (async (input, init = {}) => {
    calls.push({ url: String(input), init });
    if (String(input) === "/api/v1/auth/csrf") return envelope({ headerName: "X-CSRF-TOKEN", token: "csrf" });
    return envelope({ decisionId: "decision" });
  });
  const source = new HttpProjectDataSource();
  const role = { assessmentId: "role-assessment", selectedRole: "CONTRACT_BODY",
    expectedVersion: 0, reason: "人工核对原始材料" };
  const identity = { assessmentId: "identity-assessment", resolution: "CONFIRM_SAME_PROJECT",
    expectedVersion: 0, reason: "人工确认属于当前项目" };
  await source.confirmRole("project", "file", role, { idempotencyKey: "role-key" });
  await source.confirmProjectIdentity("project", "file", identity, { idempotencyKey: "identity-key" });

  const writes = calls.filter((value) => value.init.method === "POST");
  assert.deepEqual(writes.map((value) => value.url), [
    "/api/v1/projects/project/files/file/role-confirmations",
    "/api/v1/projects/project/files/file/identity-confirmations",
  ]);
  assert.deepEqual(writes.map((value) => new Headers(value.init.headers).get("Idempotency-Key")),
    ["role-key", "identity-key"]);
  assert.deepEqual(JSON.parse(writes[0].init.body), role);
  assert.deepEqual(JSON.parse(writes[1].init.body), identity);
});

function envelope(data) {
  return new Response(JSON.stringify({ code: 200, message: "success", data }),
    { status: 200, headers: { "Content-Type": "application/json" } });
}

test("Project datasource reuses cookie/CSRF client for create, metadata confirmation, upload and recovery", async (t) => {
  const originalFetch = globalThis.fetch;
  t.after(() => { globalThis.fetch = originalFetch; });
  const calls = [];
  globalThis.fetch = (async (input, init = {}) => {
    calls.push({ url: String(input), init });
    if (String(input) === "/api/v1/auth/csrf") return envelope({ headerName: "X-CSRF-TOKEN", token: "csrf", parameterName: "_csrf" });
    if (String(input).startsWith("/api/v1/projects/intake-drafts")) return envelope({ items: [], page: 1, size: 20, total: 0 });
    if (String(input).startsWith("/api/v1/projects/preparation-drafts")) return envelope({ items: [], page: 1, size: 20, total: 0 });
    if (String(input).startsWith("/api/v1/projects?") || String(input) === "/api/v1/projects") {
      return envelope({ items: [], page: 1, size: 20, total: 0 });
    }
    if (String(input).includes("/contract-candidates/")) return envelope({ candidate: { id: "c" }, evidence: [], fieldStates: {}, lineItemFieldStates: {} });
    if (init.method === "PATCH") return envelope({ id: "project-1", name: "Confirmed", projectNo: "P-1", category: "GOODS", versionNo: 1 });
    if (init.method === "POST" && String(input).endsWith("/files")) return envelope({ id: "file-1" });
    if (String(input).endsWith("/recover")) return envelope({ id: "file-1", ingestionStatus: "AVAILABLE" });
    if (init.method === "POST") return envelope({ id: "project-1" });
    return envelope({ project: { id: "project-1" }, files: [], candidates: [], extractionResults: [], associatedContracts: [] });
  });

  const source = new HttpProjectDataSource();
  await source.listProjects();
  assert.equal(typeof source.listIntakeDrafts, "function");
  await source.listIntakeDrafts();
  assert.equal(typeof source.listPreparationDrafts, "function");
  await source.listPreparationDrafts();
  await source.createBlank({ idempotencyKey: "create-key" });
  await source.getProject("project-1");
  assert.equal(typeof source.confirmMetadata, "function");
  await source.confirmMetadata("project-1", {
    expectedVersion: 0, name: "Confirmed", projectNo: "P-1", category: "GOODS",
  }, { idempotencyKey: "metadata-key" });
  await source.upload("project-1", new File(["pdf"], "a.pdf", { type: "application/pdf" }), { idempotencyKey: "upload-key" });
  await source.recover("project-1", "file-1");
  await source.getCandidate("project-1", "candidate-1");

  const writes = calls.filter((call) => call.init.method === "POST" || call.init.method === "PATCH");
  assert.deepEqual(writes.map((call) => new Headers(call.init.headers).get("Idempotency-Key")),
    ["create-key", "metadata-key", "upload-key", null]);
  assert.equal(writes.every((call) => call.init.credentials === "include"), true);
  assert.deepEqual(JSON.parse(writes[1].init.body), {
    expectedVersion: 0, name: "Confirmed", projectNo: "P-1", category: "GOODS",
  });
  assert.equal(new Headers(writes[2].init.headers).has("Content-Type"), false);
  assert.deepEqual(calls.filter((call) => !String(call.url).endsWith("/auth/csrf")).map((call) => call.url), [
    "/api/v1/projects?page=1&size=20",
    "/api/v1/projects/intake-drafts?page=1&size=20",
    "/api/v1/projects/preparation-drafts?page=1&size=20",
    "/api/v1/projects",
    "/api/v1/projects/project-1",
    "/api/v1/projects/project-1",
    "/api/v1/projects/project-1/files",
    "/api/v1/projects/project-1/files/file-1/recover",
    "/api/v1/projects/project-1/contract-candidates/candidate-1",
  ]);
});

function processingJob(projectId:string,fileId:string){return {jobId:"job-1",projectId,fileId,status:"QUEUED",attemptNo:0,
  reasonCode:null,parseResultId:null,createdAt:"2026-10-14T00:00:00Z",updatedAt:"2026-10-14T00:00:00Z"};}

test("async upload and recovery use separate 202/job GET routes with original key and cookie CSRF",async(t)=>{
  const previous=globalThis.fetch;t.after(()=>{globalThis.fetch=previous;});const calls:any[]=[];
  const job=processingJob("p/1","f/1");const receipt={projectId:"p/1",fileId:"f/1",jobId:job.jobId,file:{id:"f/1",projectId:"p/1"},job};
  globalThis.fetch=async(input,init={})=>{calls.push({url:String(input),init});
    if(String(input).endsWith("/auth/csrf"))return envelope({headerName:"X-CSRF-TOKEN",token:"csrf",parameterName:"_csrf"});
    const data=String(input).includes("creation-operations")?{id:"p/1"}:String(input).endsWith("/processing")||String(input).endsWith("/retry")?job
      :init.method==="GET"&&String(input).endsWith("/file-jobs")?{projectId:"p/1",workerEnabled:true,items:[job],total:1,completed:0,failed:0,unknown:0}:receipt;
    return new Response(JSON.stringify({code:200,message:"success",data}),{status:init.method==="POST"&&String(input).endsWith("/file-jobs")?202:200});};
  const source=new HttpProjectDataSource();
  assert.deepEqual(await source.uploadAsync("p/1",new File(["pdf"],"a.pdf"),{idempotencyKey:"original-key"}),receipt);
  await source.getUploadOperation("p/1","original-key");await source.listFileJobs("p/1");
  await source.getFileProcessing("p/1","f/1");await source.retryFileProcessing("p/1","f/1");
  await source.getCreationOperation("create-key");
  const business=calls.filter(call=>!call.url.endsWith("/auth/csrf"));
  assert.deepEqual(business.map(call=>call.url),["/api/v1/projects/p%2F1/file-jobs",
    "/api/v1/projects/p%2F1/file-jobs/by-operation?key=original-key","/api/v1/projects/p%2F1/file-jobs",
    "/api/v1/projects/p%2F1/files/f%2F1/processing","/api/v1/projects/p%2F1/files/f%2F1/processing/retry",
    "/api/v1/projects/creation-operations?key=create-key"]);
  assert.equal(new Headers(business[0].init.headers).get("Idempotency-Key"),"original-key");
  assert.equal(new Headers(business[0].init.headers).get("X-CSRF-TOKEN"),"csrf");
  assert.equal(new Headers(business[0].init.headers).has("Content-Type"),false);
  assert.equal(business.every(call=>call.init.credentials==="include"),true);
});

test("a mismatched async receipt never proves another project's upload accepted",async(t)=>{
  const previous=globalThis.fetch;t.after(()=>{globalThis.fetch=previous;});
  globalThis.fetch=async input=>String(input).endsWith("/auth/csrf")?envelope({headerName:"X-CSRF-TOKEN",token:"csrf",parameterName:"_csrf"})
    :envelope({projectId:"other",fileId:"f",jobId:"j",file:{id:"f",projectId:"other"},job:{...processingJob("other","f"),jobId:"j"}});
  await assert.rejects(new HttpProjectDataSource().uploadAsync("expected",new File(["pdf"],"a.pdf"),{idempotencyKey:"same"}),/核对/);
});

test("queue counts must be proved by the returned jobs rather than fabricated completion totals",async(t)=>{
  const previous=globalThis.fetch;t.after(()=>{globalThis.fetch=previous;});
  globalThis.fetch=async()=>envelope({projectId:"p",workerEnabled:true,items:[processingJob("p","f")],total:1,completed:1,failed:0,unknown:0});
  await assert.rejects(new HttpProjectDataSource().listFileJobs("p"),/核对/);
});
