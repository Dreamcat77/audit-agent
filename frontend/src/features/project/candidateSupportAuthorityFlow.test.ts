import assert from "node:assert/strict";
import { registerHooks } from "node:module";
import test from "node:test";
import type { CandidateDetail, CandidateSupportAssociation } from "./types";
import type { CandidateSupportAuthorityReplacementResponse, ProjectDataSource } from "./data/projectDataSource";
const hooks = registerHooks({
  load(url, context, nextLoad) {
    const loaded = nextLoad(url, context);
    if (!url.endsWith("/src/api/client.ts")) return loaded;
    const source = typeof loaded.source === "string" ? loaded.source : Buffer.from(loaded.source ?? []).toString("utf8");
    return { ...loaded, source: `import.meta.env ??= {};\n${source}` };
  },
});
const { ApiError } = await import("../../api/client.ts");
const { createSessionStorageCandidateSupportOperationPersistence, loadCandidateSupportOperation,
  loadOrCreateCandidateSupportOperation, parseCandidateSupportAuthorityClassification } =
  await import("./candidateSupportAuthorityOperation.ts");
const { replaceCandidateSupportAndReconcile } = await import("./candidateSupportAuthorityFlow.ts");
hooks.deregister();

class MemoryStorage implements Storage {
  readonly values = new Map<string, string>();
  get length() { return this.values.size; }
  clear() { this.values.clear(); }
  getItem(key: string) { return this.values.get(key) ?? null; }
  key(index: number) { return [...this.values.keys()][index] ?? null; }
  removeItem(key: string) { this.values.delete(key); }
  setItem(key: string, value: string) { this.values.set(key, value); }
}

const scope = ["auditor-1", "project-1", "candidate-1"] as const;
const association: CandidateSupportAssociation = {
  supportAuthorityId: "support-1", sourceFileId: "award-a", fileSha256: "a".repeat(64),
  parseResultId: "parse-1", parseVersion: 2, projectVersionNo: 3, effectiveRole: "AWARD_NOTICE",
  roleDecisionId: "role-1", identityResolution: "CONFIRM_SAME_PROJECT", identityDecisionId: "identity-1",
  sourceFactFingerprint: "b".repeat(64), authorityVersion: 2, originalFilename: "award.pdf",
  current: true, safeReasonCode: null, eligibleAwardFacts: [],
};

function response(): CandidateSupportAuthorityReplacementResponse {
  const { originalFilename, current, safeReasonCode, eligibleAwardFacts, ...authority } = association;
  return { candidateId: "candidate-1", candidateVersion: 5, supportAuthorityVersion: 2,
    authorityMode: "EXPLICIT_SUPPORT_AUTHORITY", replayed: false,
    supportAssociations: [{ ...authority, projectId: "project-1", candidateId: "candidate-1",
      createdBy: "auditor-1", createdAt: "2026-09-20T00:00:00Z" }] };
}

function detail(): CandidateDetail {
  return {
    candidate: { id: "candidate-1", projectId: "project-1", sourceContractFileId: "contract-file",
      sourceParseResultId: "contract-parse", sourceRoleDecisionId: "contract-role", sourceIdentityDecisionId: "contract-identity",
      supportAuthorityVersionNo: 2, supportAuthorityFingerprint: "c".repeat(64),
      contractNo: "C-1", contractName: "Contract", contractType: "GOODS", partyA: "Buyer", partyB: "Supplier",
      partyBCreditCode: null, contractAmount: 100, amountInWords: null, awardAmount: 98,
      awardSupplier: "Supplier", awardSupplierCreditCode: null, lineItems: [], promotionState: "DRAFT",
      versionNo: 5, promotedContractId: null, promotedConfirmationId: null, promotedBy: null, promotedAt: null,
      createdAt: "2026-09-20T00:00:00Z", updatedAt: "2026-09-20T00:00:00Z" },
    sourceFile: { id: "contract-file", projectId: "project-1", parentArchiveFileId: null,
      normalizedRelativePath: null, originalFilename: "contract.pdf", mediaType: "application/pdf", category: "CONTRACT_BODY",
      ingestionStatus: "AVAILABLE", parseStatus: "PARSED", sizeBytes: 10, parsedText: null, parseMethod: "PDF_TEXT",
      parseEngine: "pdf", parseConfidence: null, pageCount: 1, parseDurationMs: 1, safeReasonCode: null,
      createdAt: "2026-09-20T00:00:00Z", updatedAt: "2026-09-20T00:00:00Z",
      progression: { stage: "CANDIDATE_READY", blocker: null, availableActions: [], candidateId: "candidate-1", contractId: null } },
    evidence: [], fieldStates: { AWARD_AMOUNT: "CONFIRMED" }, lineItemFieldStates: {},
    authorityMode: "EXPLICIT_SUPPORT_AUTHORITY", supportAuthorityVersionNo: 2,
    supportAuthorityFingerprint: "c".repeat(64), supportAuthorities: [{ ...association }],
  };
}

function setup(ids = ["award-a"]) {
  const storage = new MemoryStorage();
  let allocations = 0;
  const persistence = createSessionStorageCandidateSupportOperationPersistence(storage, () => `key-${++allocations}`);
  const operation = loadOrCreateCandidateSupportOperation(persistence, ...scope, 4, ids);
  const posts: unknown[][] = [], gets: unknown[][] = [];
  let post: () => Promise<CandidateSupportAuthorityReplacementResponse> = async () => response();
  let get: () => Promise<CandidateDetail> = async () => detail();
  const source: Pick<ProjectDataSource, "replaceCandidateSupportAuthority" | "getCandidate"> = {
    async replaceCandidateSupportAuthority(...args) { posts.push(args); return post(); },
    async getCandidate(...args) { gets.push(args); return get(); },
  };
  return { storage, persistence, operation, source, posts, gets, allocations: () => allocations,
    setPost: (fn: typeof post) => { post = fn; }, setGet: (fn: typeof get) => { get = fn; },
    run: (signal?: AbortSignal) => replaceCandidateSupportAndReconcile(source, persistence, ...scope, operation, signal),
    load: () => loadCandidateSupportOperation(persistence, ...scope),
  };
}

function classified(classification: string, message = "server detail") {
  return new ApiError(409, "BUSINESS_ERROR", message, { classification });
}

for (const classification of ["OUTCOME_UNCERTAIN", "REQUEST_REJECTED", "RESULT_SUPERSEDED", "INTEGRITY_CONFLICT"] as const) {
  test(`only existing ApiError data recognizes ${classification}`, () => {
    assert.equal(parseCandidateSupportAuthorityClassification(classified(classification)), classification);
    assert.equal(parseCandidateSupportAuthorityClassification({ status: 409, data: { classification } }), null);
    assert.equal(parseCandidateSupportAuthorityClassification(new Error(classification)), null);
    assert.equal(parseCandidateSupportAuthorityClassification(classification), null);
  });
}

for (const data of [undefined, null, "REQUEST_REJECTED", [], ["REQUEST_REJECTED"], {},
  { classification: null }, { classification: 1 }, { classification: ["REQUEST_REJECTED"] },
  { classification: "request_rejected" }, { classification: "REQUEST_REJECTED " }, { classification: "UNKNOWN" }]) {
  test(`unknown error data retains the operation even when its message claims rejection: ${JSON.stringify(data)}`, async () => {
    const h = setup();
    const error = new ApiError(409, "BUSINESS_ERROR", "REQUEST_REJECTED RESULT_SUPERSEDED stale expected version", data);
    assert.equal(parseCandidateSupportAuthorityClassification(error), null);
    h.setPost(async () => { throw error; });
    await assert.rejects(h.run(), (cause) => cause === error);
    assert.deepEqual(h.load(), h.operation);
    assert.equal(h.posts.length, 1);
    assert.equal(h.gets.length, 0);
    assert.equal(h.allocations(), 1);
  });
}

test("proven success reads exact durable Candidate before clearing and sends one immutable command", async () => {
  const h = setup([" award-a ", "award-a"]);
  const signal = new AbortController().signal;
  h.setGet(async () => { assert.deepEqual(h.load(), h.operation); return detail(); });
  const result = await h.run(signal);
  assert.deepEqual(result, detail());
  assert.deepEqual(h.posts, [["project-1", "candidate-1", { expectedCandidateVersion: 4, supportFileIds: ["award-a"] },
    { idempotencyKey: "key-1", signal }]]);
  assert.deepEqual(h.gets, [["project-1", "candidate-1", signal]]);
  assert.equal(h.load(), null);
  assert.equal(h.allocations(), 1);
});

test("real Candidate Detail JSON omits nested support version but top-level durable proof clears exact operation", async () => {
  const h = setup();
  const current = detail();
  // ContractCandidate hides internal support metadata with @JsonIgnore; Candidate Detail projects it separately.
  delete current.candidate.supportAuthorityVersionNo;
  delete current.candidate.supportAuthorityFingerprint;
  h.setGet(async () => current);
  assert.deepEqual(await h.run(), current);
  assert.equal(h.load(), null);
  assert.equal(h.posts.length, 1);
  assert.equal(h.gets.length, 1);
  assert.equal(h.allocations(), 1);
});

test("replayed empty replacement proves primary-only mode and its support version", async () => {
  const h = setup([]);
  h.setPost(async () => ({ ...response(), authorityMode: "PRIMARY_ONLY_COMPAT", supportAssociations: [], replayed: true }));
  const current = detail();
  current.authorityMode = "PRIMARY_ONLY_COMPAT";
  current.supportAuthorities = [];
  h.setGet(async () => current);
  assert.deepEqual(await h.run(), current);
  assert.equal(h.load(), null);
  assert.equal(h.posts.length, 1);
});

test("durable support proof compares the complete canonical set independently of server row order", async () => {
  const h = setup(["award-b", "award-a", "award-b"]);
  const result = response();
  result.supportAssociations.unshift({ ...result.supportAssociations[0], supportAuthorityId: "support-2", sourceFileId: "award-b" });
  h.setPost(async () => result);
  const current = detail();
  current.supportAuthorities!.push({ ...association, supportAuthorityId: "support-2", sourceFileId: "award-b" });
  h.setGet(async () => current);
  assert.deepEqual(await h.run(), current);
  assert.deepEqual(h.posts[0][2], { expectedCandidateVersion: 4, supportFileIds: ["award-a", "award-b"] });
  assert.equal(h.load(), null);
  assert.equal(h.posts.length, 1);
});

test("stale current authority after commit does not invalidate a proven successful replay", async () => {
  const h = setup();
  h.setPost(async () => ({ ...response(), replayed: true }));
  const current = detail();
  current.supportAuthorities![0].current = false;
  current.supportAuthorities![0].safeReasonCode = "ROLE_STALE";
  h.setGet(async () => current);
  await h.run();
  assert.equal(h.load(), null);
});

for (const error of [new TypeError("network lost"), new ApiError(0, "NETWORK_ERROR", "connection lost"),
  new DOMException("cancelled", "AbortError"), new DOMException("timeout", "TimeoutError"),
  new ApiError(503, "SERVER_ERROR", "unavailable"),
  classified("OUTCOME_UNCERTAIN"), classified("INTEGRITY_CONFLICT"),
  { status: 409, data: { classification: "REQUEST_REJECTED" } }]) {
  test(`uncertain or untrusted POST failure retains exact identity: ${JSON.stringify(error)}`, async () => {
    const h = setup();
    const before = [...h.storage.values];
    h.setPost(async () => { throw error; });
    await assert.rejects(h.run(), (cause) => cause === error);
    assert.deepEqual([...h.storage.values], before);
    assert.equal(h.posts.length, 1);
    assert.equal(h.gets.length, 0);
    assert.equal(h.allocations(), 1);
  });
}

test("lost response then explicit same-key IN_PROGRESS retry retains original command after Candidate reload", async () => {
  const h = setup();
  h.setPost(async () => { throw new ApiError(0, "NETWORK_ERROR", "lost response"); });
  await assert.rejects(h.run());
  await h.source.getCandidate("project-1", "candidate-1");
  const recovered = loadCandidateSupportOperation(h.persistence, ...scope)!;
  h.setPost(async () => { throw classified("OUTCOME_UNCERTAIN", "IN_PROGRESS"); });
  await assert.rejects(replaceCandidateSupportAndReconcile(h.source, h.persistence, ...scope, recovered));
  assert.deepEqual(h.load(), { idempotencyKey: "key-1", expectedCandidateVersion: 4, supportFileIds: ["award-a"] });
  assert.deepEqual(h.posts.map((args) => args.slice(2)), [
    [{ expectedCandidateVersion: 4, supportFileIds: ["award-a"] }, { idempotencyKey: "key-1", signal: undefined }],
    [{ expectedCandidateVersion: 4, supportFileIds: ["award-a"] }, { idempotencyKey: "key-1", signal: undefined }],
  ]);
  assert.equal(h.allocations(), 1);
});

const malformedResponses: [string, (value: CandidateSupportAuthorityReplacementResponse) => unknown][] = [
  ["null", () => null], ["missing fields", () => ({})],
  ["wrong Candidate", (v) => ({ ...v, candidateId: "other" })],
  ["wrong version", (v) => ({ ...v, candidateVersion: 6 })],
  ["unsafe version", (v) => ({ ...v, candidateVersion: "5" })],
  ["missing support version", (v) => ({ ...v, supportAuthorityVersion: null })],
  ["zero support version", (v) => ({ ...v, supportAuthorityVersion: 0 })],
  ["wrong mode", (v) => ({ ...v, authorityMode: "PRIMARY_ONLY_COMPAT" })],
  ["missing replay marker", (v) => ({ ...v, replayed: undefined })],
  ["missing support", (v) => ({ ...v, supportAssociations: [] })],
  ["non-array support", (v) => ({ ...v, supportAssociations: {} })],
  ["duplicate support", (v) => ({ ...v, supportAssociations: [...v.supportAssociations, ...v.supportAssociations] })],
  ["wrong file", (v) => ({ ...v, supportAssociations: [{ ...v.supportAssociations[0], sourceFileId: "award-b" }] })],
  ["wrong owner", (v) => ({ ...v, supportAssociations: [{ ...v.supportAssociations[0], projectId: "other" }] })],
  ["wrong association Candidate", (v) => ({ ...v, supportAssociations: [{ ...v.supportAssociations[0], candidateId: "other" }] })],
  ["mixed cohort", (v) => ({ ...v, supportAssociations: [{ ...v.supportAssociations[0], authorityVersion: 1 }] })],
];
for (const [name, change] of malformedResponses) {
  test(`malformed POST success cannot clear an operation: ${name}`, async () => {
    const h = setup();
    h.setPost(async () => change(response()) as CandidateSupportAuthorityReplacementResponse);
    await assert.rejects(h.run());
    assert.deepEqual(h.load(), h.operation);
    assert.equal(h.posts.length, 1);
    assert.equal(h.allocations(), 1);
  });
}

const mismatches: [string, (value: CandidateDetail) => void][] = [
  ["wrong Candidate", (v) => { v.candidate.id = "other"; }],
  ["foreign project", (v) => { v.candidate.projectId = "other"; }],
  ["old version", (v) => { v.candidate.versionNo = 4; }],
  ["advanced version", (v) => { v.candidate.versionNo = 6; }],
  ["untyped version", (v) => { v.candidate.versionNo = "5" as unknown as number; }],
  ["wrong detail cohort", (v) => { v.supportAuthorityVersionNo = 3; }],
  ["missing detail cohort", (v) => { delete v.supportAuthorityVersionNo; }],
  ["wrong Candidate cohort", (v) => { v.candidate.supportAuthorityVersionNo = 3; }],
  ["wrong mode", (v) => { v.authorityMode = "PRIMARY_ONLY_COMPAT"; }],
  ["missing associations", (v) => { delete v.supportAuthorities; }],
  ["wrong support file", (v) => { v.supportAuthorities![0].sourceFileId = "award-b"; }],
  ["mixed cohort", (v) => { v.supportAuthorities![0].authorityVersion = 1; }],
  ["duplicate support", (v) => { v.supportAuthorities!.push({ ...association }); }],
];
for (const [name, change] of mismatches) {
  test(`unproven Candidate reload retains original command: ${name}`, async () => {
    const h = setup();
    const current = detail(); change(current);
    h.setGet(async () => current);
    await assert.rejects(h.run());
    assert.deepEqual(h.load(), h.operation);
    assert.equal(h.posts.length, 1);
    assert.equal(h.gets.length, 1);
    assert.equal(h.allocations(), 1);
  });
}

for (const phase of ["before", "post", "get"] as const) {
  test(`abort ${phase} prevents clearing or creating another operation`, async () => {
    const h = setup();
    const controller = new AbortController();
    if (phase === "before") controller.abort();
    if (phase === "post") h.setPost(async () => { controller.abort(); return response(); });
    if (phase === "get") h.setGet(async () => { controller.abort(); return detail(); });
    await assert.rejects(h.run(controller.signal));
    assert.deepEqual(h.load(), h.operation);
    assert.equal(h.posts.length, phase === "before" ? 0 : 1);
    assert.equal(h.allocations(), 1);
  });
}

for (const reason of ["stale expected version", "stale support authority"]) {
  test(`REQUEST_REJECTED clears only sent operation and requires a later explicit Save: ${reason}`, async () => {
    const h = setup();
    const error = classified("REQUEST_REJECTED", reason);
    h.setPost(async () => { throw error; });
    await assert.rejects(h.run(), (cause) => cause === error);
    assert.equal(h.load(), null);
    assert.equal(h.posts.length, 1);
    assert.equal(h.allocations(), 1);
    const current = await h.source.getCandidate("project-1", "candidate-1");
    assert.equal(h.load(), null);
    assert.equal(h.allocations(), 1, "reload must not allocate");
    const next = loadOrCreateCandidateSupportOperation(h.persistence, ...scope, current.candidate.versionNo, []);
    h.setPost(async () => { throw classified("OUTCOME_UNCERTAIN"); });
    await assert.rejects(replaceCandidateSupportAndReconcile(h.source, h.persistence, ...scope, next));
    assert.equal(h.posts.length, 2);
    assert.equal(h.allocations(), 2);
    assert.deepEqual(h.load(), { idempotencyKey: "key-2", expectedCandidateVersion: 5, supportFileIds: [] });
  });
}

test("RESULT_SUPERSEDED proves exact Candidate advancement before clearing and exposes only safe recovery text", async () => {
  const h = setup();
  h.setPost(async () => { throw classified("RESULT_SUPERSEDED", "private server detail"); });
  const current = detail();
  current.candidate.versionNo = 6;
  current.supportAuthorities = [];
  h.setGet(async () => { assert.deepEqual(h.load(), h.operation); return current; });
  await assert.rejects(h.run(), (error: unknown) => {
    assert.ok(error instanceof Error);
    assert.match(error.message, /重新加载.*核对/);
    assert.doesNotMatch(error.message, /private server detail/);
    return true;
  });
  assert.equal(h.load(), null);
  assert.equal(h.posts.length, 1);
  assert.equal(h.gets.length, 1);
  assert.equal(h.allocations(), 1);
});

for (const [name, reload] of [
  ["failed", async () => { throw new ApiError(0, "NETWORK_ERROR", "lost GET"); }],
  ["aborted", async () => { throw new DOMException("aborted", "AbortError"); }],
  ["malformed", async () => null],
  ["wrong Candidate", async () => { const v = detail(); v.candidate.id = "other"; v.candidate.versionNo = 6; return v; }],
  ["foreign Project", async () => { const v = detail(); v.candidate.projectId = "other"; v.candidate.versionNo = 6; return v; }],
  ["no advancement", async () => detail()],
  ["lower version", async () => { const v = detail(); v.candidate.versionNo = 4; return v; }],
  ["untyped version", async () => { const v = detail(); v.candidate.versionNo = "6" as unknown as number; return v; }],
  ["fractional version", async () => { const v = detail(); v.candidate.versionNo = 5.5; return v; }],
] as const) {
  test(`RESULT_SUPERSEDED ${name} proof retains exact operation`, async () => {
    const h = setup();
    h.setPost(async () => { throw classified("RESULT_SUPERSEDED"); });
    h.setGet(reload as () => Promise<CandidateDetail>);
    await assert.rejects(h.run());
    assert.deepEqual(h.load(), h.operation);
    assert.equal(h.posts.length, 1);
    assert.equal(h.allocations(), 1);
  });
}

for (const classification of ["REQUEST_REJECTED", "RESULT_SUPERSEDED", "SUCCESS"] as const) {
  for (const sameKey of [false, true]) {
    test(`${classification} cannot clear a concurrently replaced ${sameKey ? "same-key command" : "operation"}`, async () => {
      const h = setup();
      const newer = { idempotencyKey: sameKey ? "key-1" : "newer-key", commandIdentity: '[6,["award-b"]]' };
      const replaceStored = () => h.storage.setItem(h.storage.key(0)!, JSON.stringify(newer));
      h.setPost(async () => {
        if (classification === "REQUEST_REJECTED") replaceStored();
        if (classification !== "SUCCESS") throw classified(classification);
        return response();
      });
      h.setGet(async () => {
        replaceStored();
        const current = detail();
        if (classification === "RESULT_SUPERSEDED") current.candidate.versionNo = 6;
        return current;
      });
      await assert.rejects(h.run());
      assert.deepEqual(h.load(), { idempotencyKey: newer.idempotencyKey, expectedCandidateVersion: 6, supportFileIds: ["award-b"] });
      assert.equal(h.posts.length, 1);
      assert.equal(h.allocations(), 1);
    });
  }
}

test("a mismatched or missing stored operation prevents the POST altogether", async () => {
  const h = setup();
  const changed = { ...h.operation, expectedCandidateVersion: 5 };
  await assert.rejects(replaceCandidateSupportAndReconcile(h.source, h.persistence, ...scope, changed));
  assert.equal(h.posts.length, 0);
  assert.equal(h.load()?.expectedCandidateVersion, 4);
  h.storage.clear();
  await assert.rejects(h.run());
  assert.equal(h.posts.length, 0);
  assert.equal(h.allocations(), 1);
});

test("failed success reload preserves operation without another POST", async () => {
  const h = setup();
  h.setGet(async () => { throw new DOMException("proof aborted", "AbortError"); });
  await assert.rejects(h.run());
  assert.deepEqual(h.load(), h.operation);
  assert.equal(h.posts.length, 1);
  assert.equal(h.allocations(), 1);
});
