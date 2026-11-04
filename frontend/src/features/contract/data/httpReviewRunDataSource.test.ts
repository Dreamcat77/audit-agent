import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { registerHooks } from "node:module";
import test from "node:test";

const hooks = registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith(".") && !/\.[a-z]+$/i.test(specifier)) {
      return nextResolve(`${specifier}.ts`, context);
    }
    return nextResolve(specifier, context);
  },
  load(url, context, nextLoad) {
    const loaded = nextLoad(url, context);
    if (!url.endsWith("/src/api/client.ts")) return loaded;
    const source = typeof loaded.source === "string"
      ? loaded.source
      : Buffer.from(loaded.source ?? []).toString("utf8");
    return { ...loaded, source: `import.meta.env ??= {};\n${source}` };
  },
});

const [{ HttpReviewRunDataSource }, { ApiError }] = await Promise.all([
  import("./httpReviewRunDataSource.ts"),
  import("../../../api/client.ts"),
]).finally(() => hooks.deregister());

const PROJECT_ID = "00000000-0000-0000-0000-000000000201";
const CONTRACT_ID = "00000000-0000-0000-0000-000000000101";
const RUN_ID = "00000000-0000-0000-0000-000000000401";
const STEP_ID = "00000000-0000-0000-0000-000000000501";
const FILE_IDS = [
  "00000000-0000-0000-0000-000000000301",
  "00000000-0000-0000-0000-000000000302",
];

function envelope<T>(data: T): Response {
  return new Response(JSON.stringify({ code: 200, message: "success", data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

function errorEnvelope(status: number, message: string, data: unknown = null): Response {
  return new Response(JSON.stringify({ code: status, message, data }), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

test("ApiError keeps legacy construction compatible and preserves create error data", async (t) => {
  const legacy = new ApiError(409, "BUSINESS_ERROR", "legacy caller");
  assert.equal(legacy.status, 409);
  assert.equal(legacy.code, "BUSINESS_ERROR");
  assert.equal(legacy.message, "legacy caller");
  assert.equal(legacy.data, undefined);

  const originalFetch = globalThis.fetch;
  t.after(() => { globalThis.fetch = originalFetch; });
  let calls = 0;
  globalThis.fetch = (async () => {
    calls += 1;
    if (calls === 1) return envelope({ headerName: "X-CSRF-TOKEN", parameterName: "_csrf", token: "csrf" });
    return errorEnvelope(409, "本地化错误文本", { classification: "READINESS_REJECTED" });
  }) as typeof fetch;

  await assert.rejects(
    new HttpReviewRunDataSource().createReviewRun(
      PROJECT_ID, CONTRACT_ID, 7, FILE_IDS, "typed-create-error",
    ),
    (cause: unknown) => cause instanceof ApiError
      && cause.status === 409
      && cause.message === "本地化错误文本"
      && JSON.stringify(cause.data) === JSON.stringify({ classification: "READINESS_REJECTED" }),
  );
});

function minimalRun() {
  const stepTypes = ["MATERIAL_VALIDATION", "F02_DETERMINISTIC", "SEMANTIC_AI", "BREACH", "TERM"] as const;
  return {
    runId: RUN_ID,
    projectId: PROJECT_ID,
    contractId: CONTRACT_ID,
    contractVersion: 7,
    executionMode: "CLIENT_COMPATIBILITY" as const,
    sourceCandidateId: "00000000-0000-0000-0000-000000000601",
    sourceConfirmationId: null,
    runSchemaVersion: 1,
    contractSnapshotCanonicalJson: "{}",
    contractSnapshotSha256: "a".repeat(64),
    reviewProfileVersion: "profile-1",
    ruleVersion: "rule-1",
    modelPolicyVersion: "model-1",
    inputSnapshotIdentity: "b".repeat(64),
    requestedBy: "user-1",
    status: "PENDING" as const,
    requestedAt: "2026-09-16T00:00:00Z",
    startedAt: null,
    completedAt: null,
    failedAt: null,
    unknownAt: null,
    safeReasonCode: null,
    versionNo: 0,
    materials: [],
    steps: stepTypes.map((stepType, index) => ({
      stepId: `${STEP_ID}-${index}`,
      runId: RUN_ID,
      stepType,
      required: true,
      status: stepType === "MATERIAL_VALIDATION" ? "COMPLETED" as const : "PENDING" as const,
      stepPolicyVersion: "policy-1",
      currentAttemptNo: stepType === "MATERIAL_VALIDATION" ? 1 : 0,
      outputResourceType: null,
      outputResourceId: null,
      safeReasonCode: null,
      startedAt: null,
      completedAt: stepType === "MATERIAL_VALIDATION" ? "2026-09-16T00:00:00Z" : null,
      versionNo: 0,
    })),
    attempts: [],
    outputs: [],
    replayed: false,
  };
}

function minimalOutput(run: ReturnType<typeof minimalRun>, stepIndex: number) {
  return {
    run,
    step: run.steps[stepIndex],
    attempts: [],
    auditTask: null,
    auditCheckResults: [],
    findings: [],
    evidence: [],
  };
}

function reopenedRun() {
  const run = minimalRun();
  return { ...run, outputs: run.steps.map((_step, index) => minimalOutput(run, index)) };
}

test("HTTP read rejects malformed frozen opinion metadata before returning a Run", async (t) => {
  const originalFetch = globalThis.fetch;
  t.after(() => { globalThis.fetch = originalFetch; });
  const source = new HttpReviewRunDataSource();
  for (const metadata of [{ overallOpinion: " " }, { monetaryObservation: { protocol: "unsupported", mode: "OBSERVE", warnings: [], workWarnings: [] } }]) {
    const run = { ...reopenedRun(), businessProjection: { version: 1, runId: RUN_ID, runStatus: "PENDING",
      scopeStatement: "冻结材料。", categories: [], ...metadata } };
    globalThis.fetch = (async () => envelope(run)) as typeof fetch;
    await assert.rejects(source.getReviewRun(PROJECT_ID, CONTRACT_ID, RUN_ID), /结构不完整/);
  }
});

test("ReviewRun HTTP commands use the shared CSRF client and exact Phase 2B contract", async (t) => {
  const originalFetch = globalThis.fetch;
  t.after(() => { globalThis.fetch = originalFetch; });
  const calls: Array<{ url: string; init: RequestInit }> = [];
  globalThis.fetch = (async (input: string | URL | Request, init: RequestInit = {}) => {
    calls.push({ url: String(input), init });
    if (calls.length === 1 || calls.length === 4) {
      return envelope({ headerName: "X-CSRF-TOKEN", parameterName: "_csrf", token: `csrf-${calls.length}` });
    }
    if (calls.length === 2) return envelope(minimalRun());
    if (calls.length === 3) return envelope(reopenedRun());
    if (calls.length === 5 || calls.length === 6) return envelope({
      run: minimalRun(),
      step: { stepId: STEP_ID, runId: RUN_ID, stepType: "F02_DETERMINISTIC", required: true,
        status: "PENDING", stepPolicyVersion: "rule-1", currentAttemptNo: 1,
        outputResourceType: null, outputResourceId: null, safeReasonCode: null,
        startedAt: null, completedAt: null, versionNo: 0 },
      attempts: [], auditTask: null, auditCheckResults: [], findings: [], evidence: [], replayed: false,
    });
    throw new Error(`unexpected fetch ${calls.length}`);
  }) as typeof fetch;

  const source = new HttpReviewRunDataSource();
  await source.createReviewRun(PROJECT_ID, CONTRACT_ID, 7, FILE_IDS, "review-run-key");
  await source.getReviewRun(PROJECT_ID, CONTRACT_ID, RUN_ID);
  await source.executeReviewStep(PROJECT_ID, CONTRACT_ID, RUN_ID, STEP_ID, "review-step-key");
  await source.getReviewStep(PROJECT_ID, CONTRACT_ID, RUN_ID, STEP_ID);

  const root = `/api/v1/projects/${PROJECT_ID}/contracts/${CONTRACT_ID}/review-runs`;
  assert.deepEqual(calls.map((call) => [call.init.method, call.url]), [
    ["GET", "/api/v1/auth/csrf"],
    ["POST", root],
    ["GET", `${root}/${RUN_ID}`],
    ["GET", "/api/v1/auth/csrf"],
    ["POST", `${root}/${RUN_ID}/steps/${STEP_ID}/execute`],
    ["GET", `${root}/${RUN_ID}/steps/${STEP_ID}`],
  ]);
  assert.equal(calls[1]?.init.body, JSON.stringify({ expectedContractVersion: 7, materialFileIds: FILE_IDS }));
  assert.equal(calls[4]?.init.body, JSON.stringify({}));
  assert.equal(new Headers(calls[1]?.init.headers).get("Idempotency-Key"), "review-run-key");
  assert.equal(new Headers(calls[4]?.init.headers).get("Idempotency-Key"), "review-step-key");
  assert.equal(new Headers(calls[1]?.init.headers).get("X-CSRF-TOKEN"), "csrf-1");
  assert.equal(new Headers(calls[4]?.init.headers).get("X-CSRF-TOKEN"), "csrf-4");
  assert.equal(calls.every((call) => call.init.credentials === "include"), true);
});

test("latest owned ReviewRun uses one credentialed GET with its abort signal and validates a reopened projection", async (t) => {
  const originalFetch = globalThis.fetch;
  t.after(() => { globalThis.fetch = originalFetch; });
  const calls: Array<{ url: string; init: RequestInit }> = [];
  globalThis.fetch = (async (input: string | URL | Request, init: RequestInit = {}) => {
    calls.push({ url: String(input), init });
    return envelope(reopenedRun());
  }) as typeof fetch;
  const controller = new AbortController();
  const source = new HttpReviewRunDataSource();

  const result = await source.getLatestReviewRun(PROJECT_ID, CONTRACT_ID, { signal: controller.signal });

  const root = `/api/v1/projects/${PROJECT_ID}/contracts/${CONTRACT_ID}/review-runs`;
  assert.equal(result?.runId, RUN_ID);
  assert.deepEqual(calls.map((call) => [call.init.method, call.url]), [["GET", `${root}/latest`]]);
  assert.equal(calls[0]?.init.credentials, "include");
  assert.equal(calls[0]?.init.signal, controller.signal);
  assert.equal(calls[0]?.init.body, undefined);
  assert.equal(new Headers(calls[0]?.init.headers).get("X-CSRF-TOKEN"), null);
  assert.equal(new Headers(calls[0]?.init.headers).get("Idempotency-Key"), null);
});

test("latest owned ReviewRun rejects a projection that is valid only for creation", async (t) => {
  const originalFetch = globalThis.fetch;
  t.after(() => { globalThis.fetch = originalFetch; });
  globalThis.fetch = (async () => envelope(minimalRun())) as typeof fetch;

  await assert.rejects(
    new HttpReviewRunDataSource().getLatestReviewRun(PROJECT_ID, CONTRACT_ID),
    (cause: unknown) => cause instanceof Error && cause.message.includes("审核记录结构不完整"),
  );
});

test("latest owned ReviewRun maps only endpoint-local 404 to null", async (t) => {
  const originalFetch = globalThis.fetch;
  t.after(() => { globalThis.fetch = originalFetch; });
  globalThis.fetch = (async () => errorEnvelope(404, "not found")) as typeof fetch;

  const result = await new HttpReviewRunDataSource().getLatestReviewRun(PROJECT_ID, CONTRACT_ID);

  assert.equal(result, null);
});

test("latest owned ReviewRun rethrows non-404 API and network errors unchanged", async (t) => {
  const originalFetch = globalThis.fetch;
  t.after(() => { globalThis.fetch = originalFetch; });
  const source = new HttpReviewRunDataSource();

  for (const status of [401, 403, 409, 500]) {
    globalThis.fetch = (async () => errorEnvelope(status, `status-${status}`)) as typeof fetch;
    await assert.rejects(
      source.getLatestReviewRun(PROJECT_ID, CONTRACT_ID),
      (cause: unknown) => cause instanceof ApiError
        && cause.status === status
        && cause.message === `status-${status}`,
    );
  }

  globalThis.fetch = (async () => { throw new Error("offline"); }) as typeof fetch;
  await assert.rejects(
    source.getLatestReviewRun(PROJECT_ID, CONTRACT_ID),
    (cause: unknown) => cause instanceof ApiError
      && cause.status === 0
      && cause.code === "NETWORK_ERROR"
      && cause.message === "offline",
  );
});

test("ReviewRun transport boundary is explicitly typed without any", async () => {
  for (const file of ["../reviewRunTypes.ts", "reviewRunDataSource.ts", "httpReviewRunDataSource.ts"]) {
    const source = await readFile(new URL(file, import.meta.url), "utf8");
    assert.doesNotMatch(source, /\bany\b/);
  }
  const types = await readFile(new URL("../reviewRunTypes.ts", import.meta.url), "utf8");
  assert.doesNotMatch(types, /import type \{[^}]*AuditTaskStatus/);
  assert.match(types, /effectiveRole:\s*ReviewMaterialRole/);
  assert.match(types, /status:\s*ReviewAuditTaskStatus/);
});

test("ReviewRun HTTP boundary rejects an incomplete server manifest", async (t) => {
  const originalFetch = globalThis.fetch;
  t.after(() => { globalThis.fetch = originalFetch; });
  globalThis.fetch = (async () => envelope({ ...minimalRun(), steps: [] })) as typeof fetch;

  await assert.rejects(
    new HttpReviewRunDataSource().getReviewRun(PROJECT_ID, CONTRACT_ID, RUN_ID),
    /审核记录结构不完整/,
  );
});

test("ReviewRun HTTP boundary rejects duplicate, contradictory, and field-incomplete outputs", async (t) => {
  const originalFetch = globalThis.fetch;
  t.after(() => { globalThis.fetch = originalFetch; });
  const base = minimalRun();
  const semanticIndex = base.steps.findIndex((step) => step.stepType === "SEMANTIC_AI");
  const completedStep = { ...base.steps[semanticIndex]!, status: "COMPLETED" as const,
    completedAt: "2026-09-16T00:01:00Z" };
  const completed = { ...base, steps: base.steps.map((step, index) =>
    index === semanticIndex ? completedStep : step) };
  const pendingOutput = minimalOutput(base, semanticIndex);
  const duplicateOutputs = { ...completed, outputs: [
    { ...pendingOutput, run: completed, step: completedStep },
    { ...pendingOutput, run: completed, step: completedStep },
  ] };
  const contradictoryOutput = { ...completed, outputs: [
    { ...pendingOutput, run: completed },
  ] };
  const { contractVersion: _omitted, ...missingRequiredIdentity } = completed;

  const f02Index = base.steps.findIndex((step) => step.stepType === "F02_DETERMINISTIC");
  const f02Step = {
    ...base.steps[f02Index]!, status: "COMPLETED" as const, currentAttemptNo: 1,
    outputResourceType: "AUDIT_TASK", outputResourceId: "task-1",
    startedAt: "2026-09-16T00:00:10Z", completedAt: "2026-09-16T00:00:20Z", versionNo: 2,
  };
  const f02Attempt = {
    attemptId: "attempt-1", runId: RUN_ID, stepId: f02Step.stepId, attemptNo: 1,
    operationIdentity: "operation-1", status: "COMPLETED" as const, leaseOwner: null,
    leaseUntil: null, claimedAt: "2026-09-16T00:00:10Z", externalCallState: "COMPLETED" as const,
    externalRequestFingerprint: null, providerRequestId: null, provider: null, model: null,
    startedAt: "2026-09-16T00:00:10Z", completedAt: "2026-09-16T00:00:20Z",
    safeReasonCode: null, outputResourceType: "AUDIT_TASK", outputResourceId: "task-1", versionNo: 2,
  };
  const f02Run = {
    ...base, status: "PARTIALLY_COMPLETED" as const,
    steps: base.steps.map((step, index) => index === f02Index ? f02Step : step),
    attempts: [f02Attempt],
  };
  const auditTask = {
    id: "task-1", sourceType: "CONTRACT", sourceId: CONTRACT_ID, sourceVersion: 7,
    reviewType: "F02", status: "DONE" as const, createdBy: "user-1", requestId: "request-1",
    traceId: "trace-1", createdAt: "2026-09-16T00:00:10Z",
    startedAt: "2026-09-16T00:00:10Z", finishedAt: "2026-09-16T00:00:20Z", versionNo: 2,
  };
  const validCheck = {
    id: "same-check-id", taskId: "task-1", ruleCode: "F02-01", itemKey: null,
    checkStatus: "PASS" as const, riskLevel: "GREEN" as const, blockSign: false,
    actualValue: "1", expectedValue: "1", detail: "一致", ruleVersion: 1,
    createdAt: "2026-09-16T00:00:20Z",
  };
  const contradictoryCheck = {
    ...validCheck, checkStatus: "FAIL" as const, riskLevel: "RED" as const, blockSign: true,
  };
  const duplicateChecks = {
    ...f02Run,
    outputs: [{
      run: f02Run, step: f02Step, attempts: [f02Attempt], auditTask,
      auditCheckResults: [validCheck, contradictoryCheck], findings: [], evidence: [],
    }],
  };
  const missingReopenedOutput = f02Run;

  for (const malformed of [
    duplicateOutputs, contradictoryOutput, missingRequiredIdentity, duplicateChecks,
    missingReopenedOutput,
  ]) {
    globalThis.fetch = (async () => envelope(malformed)) as typeof fetch;
    await assert.rejects(
      new HttpReviewRunDataSource().getReviewRun(PROJECT_ID, CONTRACT_ID, RUN_ID),
      /审核记录结构不完整/,
    );
  }
});
