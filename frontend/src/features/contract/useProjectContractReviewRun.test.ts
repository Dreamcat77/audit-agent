import assert from "node:assert/strict";
import { registerHooks } from "node:module";
import test from "node:test";

import type { ReviewRunDataSource } from "./data/reviewRunDataSource.ts";
import type { ReviewRunDto, ReviewStepOutputDto, ReviewStepStatus, ReviewStepType } from "./reviewRunTypes.ts";

registerHooks({
  load(url, context, nextLoad) {
    const loaded = nextLoad(url, context);
    if (!url.endsWith("/src/api/client.ts")) return loaded;
    const source = typeof loaded.source === "string"
      ? loaded.source
      : Buffer.from(loaded.source ?? []).toString("utf8");
    return { ...loaded, source: `import.meta.env ??= {};\n${source}` };
  },
});

const { ApiError } = await import("../../api/client.ts");
const {
  ProjectContractReviewRunCoordinator,
  canonicalReviewMaterialIds,
  reviewRunCustomerError,
  useProjectContractReviewRun,
} = await import("./useProjectContractReviewRun.ts");
const {
  createSessionStorageReviewRunOperationPersistence,
  loadReviewRunRestartMarker,
  saveReviewRunRestartMarker,
} = await import("./reviewRunOperation.ts");
const {
  REVIEW_RUN_RESTART_REQUIRED_MESSAGE,
  presentReviewRun,
} = await import("./reviewRunPresentation.ts");

class MemoryStorage implements Storage {
  readonly values = new Map<string, string>();
  get length(): number { return this.values.size; }
  clear(): void { this.values.clear(); }
  getItem(key: string): string | null { return this.values.get(key) ?? null; }
  key(index: number): string | null { return [...this.values.keys()][index] ?? null; }
  removeItem(key: string): void { this.values.delete(key); }
  setItem(key: string, value: string): void { this.values.set(key, value); }
}

const ACTOR = "actor-1";
const PROJECT = "project-1";
const CONTRACT = "contract-1";
const RUN = "run-1";
const POINTER_KEY = "audit-agent:contract-review-run:actor-1:project-1:contract-1:pointer";
const STEP_TYPES: readonly ReviewStepType[] = [
  "MATERIAL_VALIDATION", "F02_DETERMINISTIC", "SEMANTIC_AI", "BREACH", "TERM",
];

function reviewRun(statuses: Partial<Record<ReviewStepType, ReviewStepStatus>> = {}): ReviewRunDto {
  return {
    runId: RUN, projectId: PROJECT, contractId: CONTRACT, contractVersion: 3,
    executionMode: "CLIENT_COMPATIBILITY",
    sourceCandidateId: "candidate", sourceConfirmationId: null, runSchemaVersion: 1,
    contractSnapshotCanonicalJson: "{}", contractSnapshotSha256: "hash", reviewProfileVersion: "profile",
    ruleVersion: "rules", modelPolicyVersion: "models", inputSnapshotIdentity: "snapshot",
    requestedBy: ACTOR, status: "PENDING", requestedAt: "2026-09-16T00:00:00Z",
    startedAt: null, completedAt: null, failedAt: null, unknownAt: null, safeReasonCode: null,
    versionNo: 0,
    materials: [], attempts: [], outputs: [], replayed: false,
    steps: STEP_TYPES.map((stepType, index) => ({
      stepId: `step-${index}`, runId: RUN, stepType, required: true,
      status: statuses[stepType] ?? (stepType === "MATERIAL_VALIDATION" ? "COMPLETED" : "PENDING"),
      stepPolicyVersion: "policy", currentAttemptNo: 1, outputResourceType: null,
      outputResourceId: null, safeReasonCode: null, startedAt: null,
      completedAt: stepType === "MATERIAL_VALIDATION" ? "2026-09-16T00:00:00Z" : null,
      versionNo: 0,
    })),
  };
}

test("server-worker create freezes the run pointer without browser step execution", async () => {
  const storage = new MemoryStorage();
  const created = { ...reviewRun(), executionMode: "SERVER_WORKER" as const };
  let executes = 0;
  let gets = 0;
  const source: ReviewRunDataSource = {
    getLatestReviewRun: async () => null,
    createReviewRun: async () => created,
    getReviewRun: async () => { gets += 1; return reopenedReviewRun(created); },
    executeReviewStep: async () => { executes += 1; throw new Error("browser must not execute worker-owned steps"); },
    getReviewStep: async () => { throw new Error("not used"); },
  };
  const coordinator = new ProjectContractReviewRunCoordinator(ACTOR, PROJECT, CONTRACT, source,
    createSessionStorageReviewRunOperationPersistence(storage, () => "worker"));

  const result = await coordinator.createAndExecute(3, ["file-a"]);

  assert.equal(result, created);
  assert.equal(executes, 0);
  assert.equal(gets, 0);
  assert.deepEqual(JSON.parse([...storage.values.values()].find((value) => value.includes("runId")) ?? "{}"), { runId: RUN });
});

test("initial Start reconciles a retained readiness-rejected command before creating with changed materials", async () => {
  const storage = new MemoryStorage();
  const operationKey = "audit-agent:contract-review-run:actor-1:project-1:contract-1:create:operation";
  storage.setItem(operationKey, JSON.stringify({
    idempotencyKey: "review-run-ui-old-key",
    commandIdentity: '[3,["foreign-file","valid-file"]]',
  }));
  const created = { ...reviewRun(), executionMode: "SERVER_WORKER" as const };
  const calls: Array<{ key: string; files: readonly string[] }> = [];
  const source: ReviewRunDataSource = {
    getLatestReviewRun: async () => {
      throw new Error("retained create command must be reconciled before latest discovery");
    },
    createReviewRun: async (_project, _contract, _version, files, key) => {
      calls.push({ key, files });
      if (key === "review-run-ui-old-key") {
        throw new ApiError(409, "BUSINESS_ERROR", "旧材料选择已被确定拒绝", {
          classification: "READINESS_REJECTED",
        });
      }
      return created;
    },
    getReviewRun: async () => { throw new Error("server worker create must not reopen"); },
    executeReviewStep: async () => { throw new Error("server worker create must not execute in browser"); },
    getReviewStep: async () => { throw new Error("not used"); },
  };
  const coordinator = new ProjectContractReviewRunCoordinator(ACTOR, PROJECT, CONTRACT, source,
    createSessionStorageReviewRunOperationPersistence(storage, () => "fresh-key"));

  const result = await coordinator.startOrResume(3, ["valid-file"]);

  assert.equal(result.runId, RUN);
  assert.deepEqual(calls, [
    { key: "review-run-ui-old-key", files: ["foreign-file", "valid-file"] },
    { key: "review-run-ui-fresh-key", files: ["valid-file"] },
  ]);
  assert.equal(storage.getItem(operationKey), null);
});

test("readiness rejection cannot clear or overwrite a pointer created while the old command is in flight", async () => {
  const storage = new MemoryStorage();
  const operationKey = "audit-agent:contract-review-run:actor-1:project-1:contract-1:create:operation";
  storage.setItem(operationKey, JSON.stringify({
    idempotencyKey: "review-run-ui-old-key",
    commandIdentity: '[3,["foreign-file","valid-file"]]',
  }));
  const calls: string[] = [];
  const source: ReviewRunDataSource = {
    getLatestReviewRun: async () => null,
    createReviewRun: async (_project, _contract, _version, _files, key) => {
      calls.push(key);
      if (key === "review-run-ui-old-key") {
        storage.setItem(POINTER_KEY, JSON.stringify({ runId: "concurrent-run" }));
        throw new ApiError(409, "BUSINESS_ERROR", "旧材料选择已被确定拒绝", {
          classification: "READINESS_REJECTED",
        });
      }
      return { ...reviewRun(), executionMode: "SERVER_WORKER" as const };
    },
    getReviewRun: async () => { throw new Error("not used"); },
    executeReviewStep: async () => { throw new Error("not used"); },
    getReviewStep: async () => { throw new Error("not used"); },
  };
  const coordinator = new ProjectContractReviewRunCoordinator(ACTOR, PROJECT, CONTRACT, source,
    createSessionStorageReviewRunOperationPersistence(storage, () => "fresh-key"));

  await assert.rejects(
    coordinator.startOrResume(3, ["valid-file"]),
    /当前审核记录已发生变化/,
  );

  assert.deepEqual(calls, ["review-run-ui-old-key"]);
  assert.equal(storage.getItem(POINTER_KEY), JSON.stringify({ runId: "concurrent-run" }));
  assert.notEqual(storage.getItem(operationKey), null);
});

function output(run: ReviewRunDto, stepId: string): ReviewStepOutputDto {
  const step = run.steps.find((item) => item.stepId === stepId);
  assert.ok(step);
  return { run, step, attempts: [], auditTask: null, auditCheckResults: [], findings: [], evidence: [], replayed: false };
}

function reopenedReviewRun(run: ReviewRunDto): ReviewRunDto {
  return { ...run, outputs: run.steps.map((step) => output(run, step.stepId)) };
}

test("historical task reopen reads only its exact run and leaves the active pointer intact", async () => {
  const storage = new MemoryStorage();
  storage.setItem(POINTER_KEY, JSON.stringify({ runId: "newer-run" }));
  const calls: string[] = [];
  const old = reopenedReviewRun({ ...reviewRun(), status: "FAILED" });
  const source: ReviewRunDataSource = {
    getLatestReviewRun: async () => { calls.push("latest"); throw new Error("must not discover latest"); },
    getReviewRun: async (_project, _contract, runId) => {
      calls.push(`get:${runId}`);
      return old;
    },
    createReviewRun: async () => { calls.push("create"); throw new Error("read must not create"); },
    executeReviewStep: async () => { calls.push("execute"); throw new Error("read must not execute"); },
    getReviewStep: async () => { calls.push("get-step"); throw new Error("read must not execute"); },
  };
  const coordinator = new ProjectContractReviewRunCoordinator(ACTOR, PROJECT, CONTRACT, source,
    createSessionStorageReviewRunOperationPersistence(storage, () => "unused"));

  const reopened = await coordinator.reopenExact(RUN);

  assert.equal(reopened.runId, RUN);
  assert.deepEqual(calls, [`get:${RUN}`]);
  assert.equal(storage.getItem(POINTER_KEY), JSON.stringify({ runId: "newer-run" }));
});

test("new run uses canonical materials, stable create key, four bounded branches and authoritative final GET", async () => {
  const storage = new MemoryStorage();
  let keyCounter = 0;
  const persistence = createSessionStorageReviewRunOperationPersistence(storage, () => `key-${++keyCounter}`);
  const created = reviewRun();
  let active = 0;
  let maxActive = 0;
  const executed: Array<{ stepId: string; key: string }> = [];
  const calls: string[] = [];
  const dataSource: ReviewRunDataSource = {
    getLatestReviewRun: async () => null,
    createReviewRun: async (_project, _contract, version, files, key) => {
      calls.push("create");
      assert.equal(version, 3);
      assert.deepEqual(files, ["file-a", "file-b"]);
      assert.equal(key, "review-run-ui-key-1");
      return created;
    },
    getReviewRun: async () => {
      calls.push("get-run");
      return reopenedReviewRun({ ...created, status: "COMPLETED" });
    },
    executeReviewStep: async (_project, _contract, _run, stepId, key) => {
      calls.push(`execute:${stepId}`);
      executed.push({ stepId, key });
      active += 1;
      maxActive = Math.max(maxActive, active);
      await new Promise<void>((resolve) => setImmediate(resolve));
      active -= 1;
      return output(created, stepId);
    },
    getReviewStep: async () => { throw new Error("not used"); },
  };

  const coordinator = new ProjectContractReviewRunCoordinator(
    ACTOR, PROJECT, CONTRACT, dataSource, persistence,
  );
  const final = await coordinator.createAndExecute(3, ["file-b", "file-a", "file-a"]);
  assert.equal(final.status, "COMPLETED");
  assert.equal(maxActive, 4);
  assert.deepEqual(executed.map((item) => item.stepId).sort(), ["step-1", "step-2", "step-3", "step-4"]);
  assert.equal(executed.every((item) => item.key.startsWith("review-step-ui-key-")), true);
  assert.equal(calls.at(-1), "get-run");
  assert.deepEqual(JSON.parse([...storage.values.values()].find((value) => value.includes("runId")) ?? "{}"), { runId: RUN });
});

test("a proven create response publishes and freezes its manifest before step execution settles", async () => {
  const storage = new MemoryStorage();
  const created = reviewRun();
  let published: ReviewRunDto | null = null;
  let everyExecutionSawFrozenManifest = true;
  const source: ReviewRunDataSource = {
    getLatestReviewRun: async () => null,
    createReviewRun: async () => created,
    getReviewRun: async () => reopenedReviewRun({ ...created, status: "COMPLETED" }),
    executeReviewStep: async (_project, _contract, _run, stepId) => {
      everyExecutionSawFrozenManifest &&= published?.runId === RUN;
      return output(created, stepId);
    },
    getReviewStep: async () => { throw new Error("not used"); },
  };
  const coordinator = new ProjectContractReviewRunCoordinator(ACTOR, PROJECT, CONTRACT, source,
    createSessionStorageReviewRunOperationPersistence(storage, () => "stable"));

  await coordinator.startOrResume(3, ["file-a"], undefined, (run) => { published = run; });

  assert.equal(published?.runId, RUN);
  assert.equal(everyExecutionSawFrozenManifest, true);
});

test("lost create response retains one key and retry reuses it", async () => {
  const storage = new MemoryStorage();
  let keyCounter = 0;
  const keys: string[] = [];
  let createCalls = 0;
  const created = reviewRun({
    F02_DETERMINISTIC: "COMPLETED", SEMANTIC_AI: "COMPLETED", BREACH: "COMPLETED", TERM: "COMPLETED",
  });
  const dataSource: ReviewRunDataSource = {
    getLatestReviewRun: async () => null,
    createReviewRun: async (_p, _c, _v, _f, key) => {
      keys.push(key); createCalls += 1;
      if (createCalls === 1) throw new ApiError(0, "NETWORK_ERROR", "connection closed");
      return created;
    },
    getReviewRun: async () => reopenedReviewRun(created),
    executeReviewStep: async () => { throw new Error("completed steps must not execute"); },
    getReviewStep: async () => { throw new Error("not used"); },
  };
  const coordinator = new ProjectContractReviewRunCoordinator(ACTOR, PROJECT, CONTRACT, dataSource,
    createSessionStorageReviewRunOperationPersistence(storage, () => `key-${++keyCounter}`));
  await assert.rejects(coordinator.createAndExecute(3, ["file-a"]), /connection closed/);
  await coordinator.createAndExecute(3, ["file-a"]);
  assert.deepEqual(keys, ["review-run-ui-key-1", "review-run-ui-key-1"]);
});

test("final reload uncertainty reopens the proven pointer instead of creating a second run", async () => {
  const storage = new MemoryStorage();
  let createCalls = 0;
  let getCalls = 0;
  const created = reviewRun();
  const completed = {
    ...reviewRun({ F02_DETERMINISTIC: "COMPLETED", SEMANTIC_AI: "COMPLETED", BREACH: "COMPLETED", TERM: "COMPLETED" }),
    status: "COMPLETED" as const,
  };
  const source: ReviewRunDataSource = {
    getLatestReviewRun: async () => null,
    createReviewRun: async () => { createCalls += 1; return created; },
    getReviewRun: async () => {
      getCalls += 1;
      if (getCalls === 1) throw new ApiError(0, "NETWORK_ERROR", "final reload lost");
      return reopenedReviewRun(completed);
    },
    executeReviewStep: async (_p, _c, _r, stepId) => output(created, stepId),
    getReviewStep: async () => { throw new Error("not used"); },
  };
  const coordinator = new ProjectContractReviewRunCoordinator(ACTOR, PROJECT, CONTRACT, source,
    createSessionStorageReviewRunOperationPersistence(storage, () => "stable"));
  await assert.rejects(coordinator.startOrResume(3, ["file-a"]), /final reload lost/);
  const recovered = await coordinator.startOrResume(3, ["file-a"]);
  assert.equal(recovered.runId, RUN);
  assert.equal(recovered.status, "COMPLETED");
  assert.equal(createCalls, 1);
  assert.equal(getCalls, 2);
});

test("restore uses exact pointer GET without discovery and falls back only after 404", async () => {
  const storage = new MemoryStorage();
  const persistence = createSessionStorageReviewRunOperationPersistence(storage, () => "key");
  const existing = reopenedReviewRun(reviewRun());
  storage.setItem("audit-agent:contract-review-run:actor-1:project-1:contract-1:pointer", JSON.stringify({ runId: RUN }));
  let gets = 0;
  let executes = 0;
  let discoveries = 0;
  const source: ReviewRunDataSource = {
    getLatestReviewRun: async () => { discoveries += 1; return null; },
    createReviewRun: async () => { throw new Error("not used"); },
    getReviewRun: async () => { gets += 1; return existing; },
    executeReviewStep: async () => { executes += 1; throw new Error("must not auto execute"); },
    getReviewStep: async () => { throw new Error("not used"); },
  };
  const coordinator = new ProjectContractReviewRunCoordinator(ACTOR, PROJECT, CONTRACT, source, persistence);
  assert.equal((await coordinator.restore())?.runId, RUN);
  assert.equal(gets, 1);
  assert.equal(executes, 0);
  assert.equal(discoveries, 0);

  saveReviewRunRestartMarker(storage, ACTOR, PROJECT, CONTRACT, RUN, "step-1");
  source.getReviewRun = async () => { throw new ApiError(404, "BUSINESS_ERROR", "not found"); };
  assert.equal(await coordinator.restore(), null);
  assert.equal(discoveries, 1);
  assert.equal(loadReviewRunRestartMarker(storage, ACTOR, PROJECT, CONTRACT), null);
  assert.equal(storage.getItem("audit-agent:contract-review-run:actor-1:project-1:contract-1:pointer"), null);
});

function restoreSource(
  overrides: Partial<ReviewRunDataSource> = {},
): ReviewRunDataSource {
  return {
    createReviewRun: async () => { throw new Error("restore must not create a run"); },
    executeReviewStep: async () => { throw new Error("restore must not execute a step"); },
    getReviewStep: async () => { throw new Error("restore must not load individual steps"); },
    getReviewRun: async () => { throw new Error("unexpected exact pointer GET"); },
    getLatestReviewRun: async () => { throw new Error("unexpected discovery GET"); },
    ...overrides,
  };
}

for (const rawPointer of [null, "not-json", JSON.stringify({ runId: " " })]) {
  test(`restore discovers and saves a proven run with absent or malformed pointer: ${rawPointer}`, async () => {
    const storage = new MemoryStorage();
    if (rawPointer !== null) storage.setItem(POINTER_KEY, rawPointer);
    const durable = reopenedReviewRun(reviewRun());
    const abort = new AbortController();
    let discoveries = 0;
    const coordinator = new ProjectContractReviewRunCoordinator(ACTOR, PROJECT, CONTRACT,
      restoreSource({ getLatestReviewRun: async (project, contract, options) => {
        discoveries += 1;
        assert.equal(project, PROJECT);
        assert.equal(contract, CONTRACT);
        assert.equal(options?.signal, abort.signal);
        assert.equal(storage.getItem(POINTER_KEY), null);
        return durable;
      } }), createSessionStorageReviewRunOperationPersistence(storage, () => {
        throw new Error("restore must not allocate an operation identity");
      }));

    assert.equal(await coordinator.restore(abort.signal), durable);
    assert.equal(discoveries, 1);
    assert.equal(storage.getItem(POINTER_KEY), JSON.stringify({ runId: RUN }));
  });
}

test("discovery absence clears stale restart state without creating", async () => {
  const storage = new MemoryStorage();
  saveReviewRunRestartMarker(storage, ACTOR, PROJECT, CONTRACT, RUN, "step-1");
  let discoveries = 0;
  let assessed: { restartRequired: boolean } | undefined;
  const coordinator = new ProjectContractReviewRunCoordinator(ACTOR, PROJECT, CONTRACT,
    restoreSource({ getLatestReviewRun: async () => { discoveries += 1; return null; } }),
    createSessionStorageReviewRunOperationPersistence(storage));

  assert.equal(await coordinator.restore(undefined, (value) => { assessed = value; }), null);
  assert.equal(discoveries, 1);
  assert.equal(storage.getItem(POINTER_KEY), null);
  assert.equal(loadReviewRunRestartMarker(storage, ACTOR, PROJECT, CONTRACT), null);
  assert.deepEqual(assessed, { restartRequired: false });
});

for (const status of ["COMPLETED", "FAILED", "UNKNOWN", "PARTIALLY_COMPLETED"] as const) {
  test(`discovery preserves durable ${status} status and complete output projection`, async () => {
    const storage = new MemoryStorage();
    const durable = reopenedReviewRun({ ...reviewRun(), status });
    const coordinator = new ProjectContractReviewRunCoordinator(ACTOR, PROJECT, CONTRACT,
      restoreSource({ getLatestReviewRun: async () => durable }),
      createSessionStorageReviewRunOperationPersistence(storage));

    const restored = await coordinator.restore();
    assert.equal(restored, durable);
    assert.equal(restored?.status, status);
    assert.equal(storage.getItem(POINTER_KEY), JSON.stringify({ runId: RUN }));
  });
}

for (const mutation of [
  { projectId: "another-project" }, { contractId: "another-contract" },
  { requestedBy: "another-actor" }, { outputs: [] },
]) {
  test(`discovery rejects unowned or incomplete REOPEN projection: ${JSON.stringify(mutation)}`, async () => {
    const storage = new MemoryStorage();
    const invalid = { ...reopenedReviewRun(reviewRun()), ...mutation };
    const coordinator = new ProjectContractReviewRunCoordinator(ACTOR, PROJECT, CONTRACT,
      restoreSource({ getLatestReviewRun: async () => invalid }),
      createSessionStorageReviewRunOperationPersistence(storage));

    await assert.rejects(coordinator.restore(), /无法验证本次审核记录的归属|审核记录结构不完整/);
    assert.equal(storage.getItem(POINTER_KEY), null);
  });
}

for (const mutation of [
  { projectId: "another-project" }, { contractId: "another-contract" },
  { requestedBy: "another-actor" }, { runId: "another-run" },
]) {
  test(`exact pointer identity mismatch clears navigation and never discovers: ${JSON.stringify(mutation)}`, async () => {
    const storage = new MemoryStorage();
    storage.setItem(POINTER_KEY, JSON.stringify({ runId: RUN }));
    saveReviewRunRestartMarker(storage, ACTOR, PROJECT, CONTRACT, RUN, "step-1");
    let discoveries = 0;
    const coordinator = new ProjectContractReviewRunCoordinator(ACTOR, PROJECT, CONTRACT,
      restoreSource({
        getReviewRun: async () => ({ ...reopenedReviewRun(reviewRun()), ...mutation }),
        getLatestReviewRun: async () => { discoveries += 1; return null; },
      }), createSessionStorageReviewRunOperationPersistence(storage));

    await assert.rejects(coordinator.restore(), /无法验证本次审核记录的归属/);
    assert.equal(storage.getItem(POINTER_KEY), null);
    assert.equal(loadReviewRunRestartMarker(storage, ACTOR, PROJECT, CONTRACT), null);
    assert.equal(discoveries, 0);
  });
}

for (const status of [401, 403, 409, 500, 503, 0]) {
  test(`exact pointer error ${status} preserves uncertain navigation without discovery`, async () => {
    const storage = new MemoryStorage();
    storage.setItem(POINTER_KEY, JSON.stringify({ runId: RUN }));
    saveReviewRunRestartMarker(storage, ACTOR, PROJECT, CONTRACT, RUN, "step-1");
    const failure = new ApiError(status, "BUSINESS_ERROR", "unproven outcome");
    let discoveries = 0;
    const coordinator = new ProjectContractReviewRunCoordinator(ACTOR, PROJECT, CONTRACT,
      restoreSource({
        getReviewRun: async () => { throw failure; },
        getLatestReviewRun: async () => { discoveries += 1; return null; },
      }), createSessionStorageReviewRunOperationPersistence(storage));

    await assert.rejects(coordinator.restore(), (error) => error === failure);
    assert.equal(discoveries, 0);
    assert.equal(storage.getItem(POINTER_KEY), JSON.stringify({ runId: RUN }));
    assert.equal(loadReviewRunRestartMarker(storage, ACTOR, PROJECT, CONTRACT)?.runId, RUN);
  });
}

test("404 clears only the missing run marker before discovering another durable run", async () => {
  for (const markerRun of [RUN, "run-2"]) {
    const storage = new MemoryStorage();
    storage.setItem(POINTER_KEY, JSON.stringify({ runId: RUN }));
    saveReviewRunRestartMarker(storage, ACTOR, PROJECT, CONTRACT, markerRun, "run-2-step-1");
    const durable = reopenedReviewRun(runWithId("run-2", {}, "RUNNING"));
    let assessed: { restartRequired: boolean } | undefined;
    const coordinator = new ProjectContractReviewRunCoordinator(ACTOR, PROJECT, CONTRACT,
      restoreSource({
        getReviewRun: async (_p, _c, runId) => {
          assert.equal(runId, RUN);
          throw new ApiError(404, "BUSINESS_ERROR", "missing");
        },
        getLatestReviewRun: async () => {
          assert.equal(storage.getItem(POINTER_KEY), null);
          assert.equal(loadReviewRunRestartMarker(storage, ACTOR, PROJECT, CONTRACT)?.runId ?? null,
            markerRun === RUN ? null : "run-2");
          return durable;
        },
      }), createSessionStorageReviewRunOperationPersistence(storage));

    assert.equal(await coordinator.restore(undefined, (value) => { assessed = value; }), durable);
    assert.equal(storage.getItem(POINTER_KEY), JSON.stringify({ runId: "run-2" }));
    assert.deepEqual(assessed, { restartRequired: markerRun === "run-2" });
  }
});

test("pointer changed during exact GET fails closed before 404 discovery", async () => {
  const storage = new MemoryStorage();
  storage.setItem(POINTER_KEY, JSON.stringify({ runId: RUN }));
  let discoveries = 0;
  const coordinator = new ProjectContractReviewRunCoordinator(ACTOR, PROJECT, CONTRACT,
    restoreSource({
      getReviewRun: async () => {
        storage.setItem(POINTER_KEY, JSON.stringify({ runId: "new-pointer" }));
        saveReviewRunRestartMarker(storage, ACTOR, PROJECT, CONTRACT, "new-pointer", "new-step");
        throw new ApiError(404, "BUSINESS_ERROR", "missing");
      },
      getLatestReviewRun: async () => { discoveries += 1; return null; },
    }), createSessionStorageReviewRunOperationPersistence(storage));

  await assert.rejects(coordinator.restore(), /当前审核记录已发生变化/);
  assert.equal(discoveries, 0);
  assert.equal(storage.getItem(POINTER_KEY), JSON.stringify({ runId: "new-pointer" }));
  assert.equal(loadReviewRunRestartMarker(storage, ACTOR, PROJECT, CONTRACT)?.runId, "new-pointer");
});

for (const scenario of [
  { path: "pointerless", result: "run" },
  { path: "pointerless", result: "null" },
  { path: "exact-404", result: "run" },
  { path: "exact-404", result: "null" },
] as const) {
  test(`pending ${scenario.path} discovery returning ${scenario.result} cannot replace newer navigation`, async () => {
    const storage = new MemoryStorage();
    if (scenario.path === "exact-404") storage.setItem(POINTER_KEY, JSON.stringify({ runId: RUN }));
    let resolveDiscovery!: (run: ReviewRunDto | null) => void;
    const discovery = new Promise<ReviewRunDto | null>((resolve) => { resolveDiscovery = resolve; });
    let markStarted!: () => void;
    const started = new Promise<void>((resolve) => { markStarted = resolve; });
    let assessments = 0;
    const coordinator = new ProjectContractReviewRunCoordinator(ACTOR, PROJECT, CONTRACT,
      restoreSource({
        getReviewRun: async () => {
          assert.equal(scenario.path, "exact-404");
          throw new ApiError(404, "BUSINESS_ERROR", "missing");
        },
        getLatestReviewRun: async () => { markStarted(); return discovery; },
      }), createSessionStorageReviewRunOperationPersistence(storage));
    const restoring = coordinator.restore(undefined, () => { assessments += 1; });
    await started;
    assert.equal(storage.getItem(POINTER_KEY), null);
    storage.setItem(POINTER_KEY, JSON.stringify({ runId: "new-pointer" }));
    saveReviewRunRestartMarker(storage, ACTOR, PROJECT, CONTRACT, "new-pointer", "new-step");

    const rejected = assert.rejects(restoring, /当前审核记录已发生变化/);
    resolveDiscovery(scenario.result === "run" ? reopenedReviewRun(reviewRun()) : null);
    await rejected;

    assert.equal(storage.getItem(POINTER_KEY), JSON.stringify({ runId: "new-pointer" }));
    assert.deepEqual(loadReviewRunRestartMarker(storage, ACTOR, PROJECT, CONTRACT), {
      runId: "new-pointer", stepId: "new-step", reason: "RESTART_REQUIRED",
    });
    assert.equal(assessments, 0);
  });

  test(`cancelled ${scenario.path} discovery returning ${scenario.result} cannot mutate navigation`, async () => {
    const storage = new MemoryStorage();
    if (scenario.path === "exact-404") storage.setItem(POINTER_KEY, JSON.stringify({ runId: RUN }));
    let resolveDiscovery!: (run: ReviewRunDto | null) => void;
    const discovery = new Promise<ReviewRunDto | null>((resolve) => { resolveDiscovery = resolve; });
    let markStarted!: () => void;
    const started = new Promise<void>((resolve) => { markStarted = resolve; });
    const controller = new AbortController();
    let assessments = 0;
    const coordinator = new ProjectContractReviewRunCoordinator(ACTOR, PROJECT, CONTRACT,
      restoreSource({
        getReviewRun: async () => {
          assert.equal(scenario.path, "exact-404");
          throw new ApiError(404, "BUSINESS_ERROR", "missing");
        },
        getLatestReviewRun: async () => { markStarted(); return discovery; },
      }), createSessionStorageReviewRunOperationPersistence(storage));
    const restoring = coordinator.restore(controller.signal, () => { assessments += 1; });
    await started;
    saveReviewRunRestartMarker(storage, ACTOR, PROJECT, CONTRACT, "retained-run", "retained-step");
    controller.abort();

    const rejected = assert.rejects(restoring, (error) => error instanceof Error && error.name === "AbortError");
    resolveDiscovery(scenario.result === "run" ? reopenedReviewRun(reviewRun()) : null);
    await rejected;

    assert.equal(storage.getItem(POINTER_KEY), null);
    assert.deepEqual(loadReviewRunRestartMarker(storage, ACTOR, PROJECT, CONTRACT), {
      runId: "retained-run", stepId: "retained-step", reason: "RESTART_REQUIRED",
    });
    assert.equal(assessments, 0);
  });
}

for (const marker of [null, "same-run", "other-run", "other-step", "completed-step"] as const) {
  test(`discovered run assesses only a matching actionable restart marker: ${marker}`, async () => {
    const storage = new MemoryStorage();
    if (marker !== null) saveReviewRunRestartMarker(storage, ACTOR, PROJECT, CONTRACT,
      marker === "other-run" ? "run-other" : RUN,
      marker === "other-step" ? "step-other" : "step-1");
    const durable = reopenedReviewRun({
      ...reviewRun(marker === "completed-step" ? { F02_DETERMINISTIC: "COMPLETED" } : {}),
      status: "RUNNING",
    });
    let assessed: { restartRequired: boolean } | undefined;
    const coordinator = new ProjectContractReviewRunCoordinator(ACTOR, PROJECT, CONTRACT,
      restoreSource({ getLatestReviewRun: async () => durable }),
      createSessionStorageReviewRunOperationPersistence(storage));

    assert.equal(await coordinator.restore(undefined, (value) => { assessed = value; }), durable);
    assert.deepEqual(assessed, { restartRequired: marker === "same-run" });
    assert.equal(loadReviewRunRestartMarker(storage, ACTOR, PROJECT, CONTRACT)?.runId ?? null,
      marker === "same-run" ? RUN : null);
  });
}

test("restore rejects a mismatched durable owner and discards the invalid local pointer", async () => {
  const storage = new MemoryStorage();
  const pointerKey = "audit-agent:contract-review-run:actor-1:project-1:contract-1:pointer";
  storage.setItem(pointerKey, JSON.stringify({ runId: RUN }));
  const mismatched = { ...reviewRun(), projectId: "another-project" };
  const source: ReviewRunDataSource = {
    getLatestReviewRun: async () => null,
    createReviewRun: async () => { throw new Error("not used"); },
    getReviewRun: async () => mismatched,
    executeReviewStep: async () => { throw new Error("not used"); },
    getReviewStep: async () => { throw new Error("not used"); },
  };
  const coordinator = new ProjectContractReviewRunCoordinator(ACTOR, PROJECT, CONTRACT, source,
    createSessionStorageReviewRunOperationPersistence(storage, () => "key"));
  await assert.rejects(coordinator.restore(), /无法验证本次审核记录的归属/);
  assert.equal(storage.getItem(pointerKey), null);
});

test("restore rejects a substituted run identity even under the same owner scope", async () => {
  const storage = new MemoryStorage();
  const pointerKey = "audit-agent:contract-review-run:actor-1:project-1:contract-1:pointer";
  storage.setItem(pointerKey, JSON.stringify({ runId: RUN }));
  const substituted = { ...reviewRun(), runId: "another-run" };
  const source: ReviewRunDataSource = {
    getLatestReviewRun: async () => null,
    createReviewRun: async () => { throw new Error("not used"); },
    getReviewRun: async () => substituted,
    executeReviewStep: async () => { throw new Error("not used"); },
    getReviewStep: async () => { throw new Error("not used"); },
  };
  const coordinator = new ProjectContractReviewRunCoordinator(ACTOR, PROJECT, CONTRACT, source,
    createSessionStorageReviewRunOperationPersistence(storage, () => "key"));
  await assert.rejects(coordinator.restore(), /无法验证本次审核记录的归属/);
  assert.equal(storage.getItem(pointerKey), null);
});

test("restore fails closed when the durable manifest omits a required review step", async () => {
  const storage = new MemoryStorage();
  const pointerKey = "audit-agent:contract-review-run:actor-1:project-1:contract-1:pointer";
  storage.setItem(pointerKey, JSON.stringify({ runId: RUN }));
  const incomplete = { ...reviewRun(), steps: reviewRun().steps.filter((step) => step.stepType !== "TERM") };
  const source: ReviewRunDataSource = {
    getLatestReviewRun: async () => null,
    createReviewRun: async () => { throw new Error("not used"); },
    getReviewRun: async () => incomplete,
    executeReviewStep: async () => { throw new Error("not used"); },
    getReviewStep: async () => { throw new Error("not used"); },
  };
  const coordinator = new ProjectContractReviewRunCoordinator(ACTOR, PROJECT, CONTRACT, source,
    createSessionStorageReviewRunOperationPersistence(storage, () => "key"));

  await assert.rejects(coordinator.restore(), /审核记录结构不完整/);
});

test("restore clears restart markers invalidated by current durable run truth", async () => {
  const cases: ReadonlyArray<{
    name: string;
    markerRunId: string;
    markerStepId: string;
    durable: ReviewRunDto;
    expectedStatusLabel: string;
  }> = [
    {
      name: "F02 completed",
      markerRunId: RUN,
      markerStepId: "step-1",
      durable: reopenedReviewRun({
        ...reviewRun({ F02_DETERMINISTIC: "COMPLETED" }),
        status: "RUNNING",
      }),
      expectedStatusLabel: "审核进行中",
    },
    {
      name: "different run",
      markerRunId: "run-other",
      markerStepId: "step-other",
      durable: reopenedReviewRun({ ...reviewRun(), status: "RUNNING" }),
      expectedStatusLabel: "审核进行中",
    },
    {
      name: "different F02 step",
      markerRunId: RUN,
      markerStepId: "step-other",
      durable: reopenedReviewRun({ ...reviewRun(), status: "RUNNING" }),
      expectedStatusLabel: "审核进行中",
    },
    {
      name: "F02 failed",
      markerRunId: RUN,
      markerStepId: "step-1",
      durable: reopenedReviewRun({
        ...reviewRun({ F02_DETERMINISTIC: "FAILED" }),
        status: "RUNNING",
      }),
      expectedStatusLabel: "审核进行中",
    },
    {
      name: "durable completed",
      markerRunId: RUN,
      markerStepId: "step-1",
      durable: reopenedReviewRun({
        ...reviewRun({
          F02_DETERMINISTIC: "COMPLETED",
          SEMANTIC_AI: "COMPLETED",
          BREACH: "COMPLETED",
          TERM: "COMPLETED",
        }),
        status: "COMPLETED",
      }),
      expectedStatusLabel: "审核完成",
    },
    {
      name: "durable failed",
      markerRunId: RUN,
      markerStepId: "step-1",
      durable: reopenedReviewRun({
        ...reviewRun({ F02_DETERMINISTIC: "FAILED" }),
        status: "FAILED",
      }),
      expectedStatusLabel: "审核未完整完成",
    },
    {
      name: "durable unknown",
      markerRunId: RUN,
      markerStepId: "step-1",
      durable: reopenedReviewRun({
        ...reviewRun({ F02_DETERMINISTIC: "UNKNOWN" }),
        status: "UNKNOWN",
      }),
      expectedStatusLabel: "结果状态待确认",
    },
  ];

  for (const scenario of cases) {
    const storage = new MemoryStorage();
    storage.setItem(
      "audit-agent:contract-review-run:actor-1:project-1:contract-1:pointer",
      JSON.stringify({ runId: RUN }),
    );
    saveReviewRunRestartMarker(
      storage, ACTOR, PROJECT, CONTRACT, scenario.markerRunId, scenario.markerStepId,
    );
    let assessment: { restartRequired: boolean } | null = null;
    const source: ReviewRunDataSource = {
      getLatestReviewRun: async () => null,
      createReviewRun: async () => { throw new Error("not used"); },
      getReviewRun: async () => scenario.durable,
      executeReviewStep: async () => { throw new Error("restore must remain GET-only"); },
      getReviewStep: async () => { throw new Error("not used"); },
    };
    const coordinator = new ProjectContractReviewRunCoordinator(ACTOR, PROJECT, CONTRACT, source,
      createSessionStorageReviewRunOperationPersistence(storage, () => "stable"));

    const restored = await coordinator.restore(undefined,
      (value: { restartRequired: boolean }) => { assessment = value; });

    assert.equal(restored, scenario.durable, scenario.name);
    assert.deepEqual(assessment, { restartRequired: false }, scenario.name);
    assert.equal(loadReviewRunRestartMarker(storage, ACTOR, PROJECT, CONTRACT), null, scenario.name);
    assert.equal(presentReviewRun(restored!).statusLabel, scenario.expectedStatusLabel, scenario.name);
  }
});

test("explicit continuation submits only pending eligible steps and keeps terminal siblings", async () => {
  const storage = new MemoryStorage();
  const persistence = createSessionStorageReviewRunOperationPersistence(storage, () => "stable");
  const current = reviewRun({
    F02_DETERMINISTIC: "COMPLETED",
    SEMANTIC_AI: "PENDING",
    BREACH: "FAILED",
    TERM: "UNKNOWN",
  });
  const executed: string[] = [];
  const source: ReviewRunDataSource = {
    getLatestReviewRun: async () => null,
    createReviewRun: async () => { throw new Error("not used"); },
    getReviewRun: async () => reopenedReviewRun(current),
    executeReviewStep: async (_p, _c, _r, stepId) => { executed.push(stepId); return output(current, stepId); },
    getReviewStep: async () => { throw new Error("not used"); },
  };
  const coordinator = new ProjectContractReviewRunCoordinator(ACTOR, PROJECT, CONTRACT, source, persistence);
  await coordinator.continuePending(current);
  assert.deepEqual(executed, ["step-2"]);
});

test("F02 command conflict plus durable pending state requires explicit restart without losing sibling results", async () => {
  const storage = new MemoryStorage();
  const initial = reviewRun();
  const durable = reopenedReviewRun({
    ...reviewRun({
      F02_DETERMINISTIC: "PENDING",
      SEMANTIC_AI: "COMPLETED",
      BREACH: "COMPLETED",
      TERM: "COMPLETED",
    }),
    status: "RUNNING",
  });
  const executed: string[] = [];
  let assessed: { restartRequired: boolean } | null = null;
  const source: ReviewRunDataSource = {
    getLatestReviewRun: async () => null,
    createReviewRun: async () => { throw new Error("must not create another run"); },
    getReviewRun: async () => durable,
    executeReviewStep: async (_p, _c, _r, stepId) => {
      executed.push(stepId);
      if (stepId === "step-1") {
        throw new ApiError(409, "BUSINESS_ERROR", "raw backend policy conflict");
      }
      return output(durable, stepId);
    },
    getReviewStep: async () => { throw new Error("not used"); },
  };
  const coordinator = new ProjectContractReviewRunCoordinator(ACTOR, PROJECT, CONTRACT, source,
    createSessionStorageReviewRunOperationPersistence(storage, () => "stable"));

  const final = await coordinator.continuePending(initial, undefined,
    (value: { restartRequired: boolean }) => { assessed = value; });

  assert.equal(final, durable);
  assert.equal(final.outputs.filter((item) =>
    ["SEMANTIC_AI", "BREACH", "TERM"].includes(item.step.stepType)).length, 3);
  assert.deepEqual(executed.sort(), ["step-1", "step-2", "step-3", "step-4"]);
  assert.deepEqual(assessed, { restartRequired: true });
  const presentation = presentReviewRun(final, assessed?.restartRequired ?? false);
  assert.equal(presentation.canContinue, false);
  assert.equal(presentation.canStartNew, true);
  assert.equal(presentation.statusDetail, REVIEW_RUN_RESTART_REQUIRED_MESSAGE);
  assert.match(presentation.statusDetail, /重新发起本次审核/);
  assert.doesNotMatch(presentation.statusDetail, /raw backend policy conflict|409|F02_DETERMINISTIC/);
});

test("rejected browser requests do not require restart when durable state proves another outcome", async () => {
  const cases: ReadonlyArray<{
    name: string;
    rejectedType: ReviewStepType;
    rejection: Error;
    finalStatus: ReviewStepStatus;
  }> = [
    {
      name: "network response lost after F02 completed",
      rejectedType: "F02_DETERMINISTIC",
      rejection: new ApiError(0, "NETWORK_ERROR", "connection closed"),
      finalStatus: "COMPLETED",
    },
    {
      name: "semantic request rejected while durable semantic is unknown",
      rejectedType: "SEMANTIC_AI",
      rejection: new ApiError(409, "BUSINESS_ERROR", "semantic conflict"),
      finalStatus: "UNKNOWN",
    },
    {
      name: "F02 conflict completed before final reload",
      rejectedType: "F02_DETERMINISTIC",
      rejection: new ApiError(409, "BUSINESS_ERROR", "policy conflict"),
      finalStatus: "COMPLETED",
    },
  ];

  for (const scenario of cases) {
    const storage = new MemoryStorage();
    const initial = reviewRun();
    const durableStatuses: Partial<Record<ReviewStepType, ReviewStepStatus>> = {
      F02_DETERMINISTIC: "COMPLETED",
      SEMANTIC_AI: "COMPLETED",
      BREACH: "COMPLETED",
      TERM: "COMPLETED",
      [scenario.rejectedType]: scenario.finalStatus,
    };
    const durable = reopenedReviewRun({
      ...reviewRun(durableStatuses),
      status: scenario.finalStatus === "UNKNOWN" ? "UNKNOWN" : "COMPLETED",
    });
    let assessed: { restartRequired: boolean } | null = null;
    const rejectedStepId = initial.steps.find((step) => step.stepType === scenario.rejectedType)?.stepId;
    const source: ReviewRunDataSource = {
      getLatestReviewRun: async () => null,
      createReviewRun: async () => { throw new Error("not used"); },
      getReviewRun: async () => durable,
      executeReviewStep: async (_p, _c, _r, stepId) => {
        if (stepId === rejectedStepId) throw scenario.rejection;
        return output(durable, stepId);
      },
      getReviewStep: async () => { throw new Error("not used"); },
    };
    const coordinator = new ProjectContractReviewRunCoordinator(ACTOR, PROJECT, CONTRACT, source,
      createSessionStorageReviewRunOperationPersistence(storage, () => "stable"));

    const final = await coordinator.continuePending(initial, undefined,
      (value: { restartRequired: boolean }) => { assessed = value; });

    assert.equal(final, durable, scenario.name);
    assert.deepEqual(assessed, { restartRequired: false }, scenario.name);
  }
});

class HookElement {
  readonly nodeType = 1;
  readonly namespaceURI = "http://www.w3.org/1999/xhtml";
  readonly style: Record<string, string> = {};
  readonly children: HookElement[] = [];
  readonly ownerDocument: HookDocument;
  readonly tagName: string;
  readonly nodeName: string;
  parentNode: HookElement | null = null;
  textContent = "";

  constructor(ownerDocument: HookDocument, tagName: string) {
    this.ownerDocument = ownerDocument;
    this.tagName = tagName.toUpperCase();
    this.nodeName = this.tagName;
  }

  addEventListener(): void {}
  removeEventListener(): void {}
  setAttribute(): void {}
  removeAttribute(): void {}
  appendChild(child: HookElement): HookElement {
    child.parentNode = this;
    this.children.push(child);
    return child;
  }
  insertBefore(child: HookElement, before: HookElement | null): HookElement {
    child.parentNode = this;
    const index = before ? this.children.indexOf(before) : -1;
    if (index < 0) this.children.push(child);
    else this.children.splice(index, 0, child);
    return child;
  }
  removeChild(child: HookElement): HookElement {
    const index = this.children.indexOf(child);
    if (index >= 0) this.children.splice(index, 1);
    child.parentNode = null;
    return child;
  }
}

class HookDocument {
  readonly nodeType = 9;
  readonly documentElement: HookElement;
  readonly body: HookElement;
  activeElement: HookElement | null = null;
  defaultView!: Record<string, unknown>;

  constructor() {
    this.documentElement = new HookElement(this, "html");
    this.body = new HookElement(this, "body");
    this.documentElement.appendChild(this.body);
  }

  addEventListener(): void {}
  removeEventListener(): void {}
  createElement(tagName: string): HookElement { return new HookElement(this, tagName); }
}

function runWithId(runId: string, statuses: Partial<Record<ReviewStepType, ReviewStepStatus>>, status: ReviewRunDto["status"]): ReviewRunDto {
  const base = reviewRun(statuses);
  const steps = base.steps.map((step, index) => ({
    ...step,
    runId,
    stepId: `${runId}-step-${index}`,
  }));
  return { ...base, runId, status, steps, outputs: [] };
}

async function mountReviewRunHook(options: Parameters<typeof useProjectContractReviewRun>[0]) {
  const hookDocument = new HookDocument();
  const hookWindow = {
    document: hookDocument,
    addEventListener() {},
    removeEventListener() {},
    setTimeout: globalThis.setTimeout.bind(globalThis),
    clearTimeout: globalThis.clearTimeout.bind(globalThis),
    HTMLElement: HookElement,
    HTMLIFrameElement: class {},
    location: { protocol: "http:" },
  };
  Object.assign(hookWindow, { window: hookWindow, self: hookWindow, top: hookWindow, parent: hookWindow });
  hookDocument.defaultView = hookWindow;
  Object.assign(globalThis, { window: hookWindow, document: hookDocument, IS_REACT_ACT_ENVIRONMENT: true });
  const React = await import("react");
  const { createRoot } = await import("react-dom/client");
  let state!: ReturnType<typeof useProjectContractReviewRun>;
  let currentOptions = options;
  function Harness() {
    state = useProjectContractReviewRun(currentOptions);
    return null;
  }
  const root = createRoot(new HookElement(hookDocument, "div") as unknown as Element);
  const render = async (nextOptions = currentOptions) => {
    currentOptions = nextOptions;
    await React.act(async () => {
      root.render(React.createElement(Harness));
      await new Promise<void>((resolve) => setImmediate(resolve));
    });
  };
  await render();
  return {
    get state() { return state; },
    act: React.act,
    render,
    unmount: async () => { await React.act(async () => { root.unmount(); }); },
  };
}

function completedRun(runId: string): ReviewRunDto {
  return reopenedReviewRun(runWithId(runId, {
    F02_DETERMINISTIC: "COMPLETED", SEMANTIC_AI: "COMPLETED", BREACH: "COMPLETED", TERM: "COMPLETED",
  }, "COMPLETED"));
}

async function remountAfterLostCreate(
  source: ReviewRunDataSource,
  storage: MemoryStorage,
  createKey: () => string,
) {
  storage.setItem(POINTER_KEY, JSON.stringify({ runId: RUN }));
  const options = {
    enabled: true, actorId: ACTOR, projectId: PROJECT, contractId: CONTRACT,
    expectedContractVersion: 3, selectedFileIds: ["file-a"], dataSource: source,
    persistence: createSessionStorageReviewRunOperationPersistence(storage, createKey),
  };
  const hook = await mountReviewRunHook(options);
  await hook.act(async () => { hook.state.startNew(); });
  await hook.act(async () => { await hook.state.start(); });
  assert.equal(hook.state.run, null);
  assert.equal(storage.getItem(POINTER_KEY), null);
  await hook.unmount();
  const refreshed = await mountReviewRunHook(options);
  assert.ok([...storage.values.keys()].some((key) => key.endsWith(":create:operation")),
    "discovery alone must not consume the uncertain operation");
  return { hook: refreshed, options };
}

for (const changedInputs of [false, true]) {
  test(`explicit new reconciles discovered baseline then creates once with a fresh key; changed inputs=${changedInputs}`, async (t) => {
    const storage = new MemoryStorage();
    const oldRun = completedRun(RUN);
    const recovered = completedRun("run-2");
    const nextRun = completedRun("run-3");
    const calls: Array<{ key: string; version: number; files: readonly string[] }> = [];
    const gets: string[] = [];
    let allocated = 0;
    const source = restoreSource({
      getLatestReviewRun: async () => recovered,
      getReviewRun: async (_p, _c, runId) => {
        gets.push(runId);
        return runId === RUN ? oldRun : runId === "run-2" ? recovered : nextRun;
      },
      createReviewRun: async (_p, _c, version, files, key) => {
        calls.push({ key, version, files });
        if (calls.length === 1) throw new ApiError(0, "NETWORK_ERROR", "created run-2; response lost");
        if (key === "review-run-ui-key-2") {
          assert.equal(storage.getItem(POINTER_KEY), null, "baseline replay pointer must be cleared before fresh create");
        }
        return { ...(key === "review-run-ui-key-1" ? recovered : nextRun), outputs: [] };
      },
    });
    const { hook, options } = await remountAfterLostCreate(source, storage, () => `key-${++allocated}`);
    t.after(hook.unmount);
    assert.equal(hook.state.run?.runId, "run-2");
    const current = changedInputs ? { ...options, expectedContractVersion: 4, selectedFileIds: ["file-b"] } : options;
    await hook.render(current);
    await hook.act(async () => { hook.state.startNew(); });
    await hook.act(async () => { await hook.state.start(); });
    assert.deepEqual(calls, [
      { key: "review-run-ui-key-1", version: 3, files: ["file-a"] },
      { key: "review-run-ui-key-1", version: 3, files: ["file-a"] },
      { key: "review-run-ui-key-2", version: changedInputs ? 4 : 3, files: [changedInputs ? "file-b" : "file-a"] },
    ]);
    assert.equal(allocated, 2);
    assert.deepEqual(gets, [RUN, "run-3"], "reconciliation must not execute or reopen the baseline");
    assert.equal(hook.state.run?.runId, "run-3");
    assert.equal(hook.state.error, null);
  });
}

for (const status of [0, 409, 503]) {
  test(`uncertain reconciliation ${status} keeps original command and key until proof before changed-input create`, async (t) => {
    const storage = new MemoryStorage();
    let allocated = 0;
    const calls: Array<{ key: string; version: number; files: readonly string[] }> = [];
    const recovered = completedRun("run-2");
    const source = restoreSource({
      getLatestReviewRun: async () => recovered,
      getReviewRun: async (_p, _c, runId) => completedRun(runId),
      createReviewRun: async (_p, _c, version, files, key) => {
        calls.push({ key, version, files });
        if (calls.length === 1) throw new ApiError(0, "NETWORK_ERROR", "response lost");
        if (calls.length === 2) throw new ApiError(status, status === 0 ? "NETWORK_ERROR" : "BUSINESS_ERROR",
          "in progress or outcome is not proven");
        return { ...(key === "review-run-ui-key-1" ? recovered : completedRun("run-3")), outputs: [] };
      },
    });
    const { hook, options } = await remountAfterLostCreate(source, storage, () => `key-${++allocated}`);
    t.after(hook.unmount);
    await hook.render({ ...options, expectedContractVersion: 4, selectedFileIds: ["file-b"] });
    await hook.act(async () => { hook.state.startNew(); });
    const retained = [...storage.values.entries()];
    await hook.act(async () => { await hook.state.start(); });
    assert.equal(calls.length, 2, "must replay the original command despite changed current inputs");
    assert.equal(allocated, 1);
    assert.deepEqual([...storage.values.entries()], retained);
    assert.equal(hook.state.run, null);
    assert.ok(hook.state.error);
    await hook.act(async () => { await hook.state.start(); });
    assert.deepEqual(calls, [
      { key: "review-run-ui-key-1", version: 3, files: ["file-a"] },
      { key: "review-run-ui-key-1", version: 3, files: ["file-a"] },
      { key: "review-run-ui-key-1", version: 3, files: ["file-a"] },
      { key: "review-run-ui-key-2", version: 4, files: ["file-b"] },
    ]);
    assert.equal(allocated, 2);
    assert.equal(hook.state.run?.runId, "run-3");
  });
}

test("typed readiness rejection with changed current command clears stale operation and creates once with one fresh key", async (t) => {
  const storage = new MemoryStorage();
  let allocated = 0;
  const calls: Array<{ key: string; version: number; files: readonly string[] }> = [];
  const recovered = completedRun("run-2");
  const next = completedRun("run-3");
  const source = restoreSource({
    getLatestReviewRun: async () => recovered,
    getReviewRun: async (_p, _c, runId) => completedRun(runId),
    createReviewRun: async (_p, _c, version, files, key) => {
      calls.push({ key, version, files });
      if (calls.length === 1) throw new ApiError(0, "NETWORK_ERROR", "response lost");
      if (calls.length === 2) throw new ApiError(409, "BUSINESS_ERROR", "任意本地化文本", {
        classification: "READINESS_REJECTED",
      });
      return { ...next, outputs: [] };
    },
  });
  const { hook, options } = await remountAfterLostCreate(source, storage, () => `key-${++allocated}`);
  t.after(hook.unmount);
  await hook.render({ ...options, expectedContractVersion: 4, selectedFileIds: ["file-b", "file-b"] });
  await hook.act(async () => { hook.state.startNew(); });

  await hook.act(async () => { await hook.state.start(); });

  assert.deepEqual(calls, [
    { key: "review-run-ui-key-1", version: 3, files: ["file-a"] },
    { key: "review-run-ui-key-1", version: 3, files: ["file-a"] },
    { key: "review-run-ui-key-2", version: 4, files: ["file-b"] },
  ]);
  assert.equal(allocated, 2);
  assert.equal(hook.state.run?.runId, "run-3");
  assert.equal(hook.state.error, null);
});

test("typed readiness rejection with unchanged command clears stale operation without a second POST in the same Start", async (t) => {
  const storage = new MemoryStorage();
  let allocated = 0;
  const keys: string[] = [];
  const recovered = completedRun("run-2");
  const next = completedRun("run-3");
  const source = restoreSource({
    getLatestReviewRun: async () => recovered,
    getReviewRun: async (_p, _c, runId) => completedRun(runId),
    createReviewRun: async (_p, _c, _version, _files, key) => {
      keys.push(key);
      if (keys.length === 1) throw new ApiError(0, "NETWORK_ERROR", "response lost");
      if (keys.length === 2) throw new ApiError(404, "BUSINESS_ERROR", "确定性拒绝", {
        classification: "READINESS_REJECTED",
      });
      return { ...next, outputs: [] };
    },
  });
  const { hook } = await remountAfterLostCreate(source, storage, () => `key-${++allocated}`);
  t.after(hook.unmount);
  await hook.act(async () => { hook.state.startNew(); });

  await hook.act(async () => { await hook.state.start(); });

  assert.deepEqual(keys, ["review-run-ui-key-1", "review-run-ui-key-1"]);
  assert.equal(storage.length, 0, "the proven-stale create operation must be cleared");
  assert.ok(hook.state.error);
  await hook.act(async () => { await hook.state.start(); });
  assert.deepEqual(keys, ["review-run-ui-key-1", "review-run-ui-key-1", "review-run-ui-key-2"]);
  assert.equal(hook.state.run?.runId, "run-3");
});

test("typed readiness rejection cannot clear a concurrently replaced create operation", async (t) => {
  const storage = new MemoryStorage();
  const operationKey = "audit-agent:contract-review-run:actor-1:project-1:contract-1:create:operation";
  const recovered = completedRun("run-2");
  let creates = 0;
  const source = restoreSource({
    getLatestReviewRun: async () => recovered,
    getReviewRun: async (_p, _c, runId) => completedRun(runId),
    createReviewRun: async () => {
      creates += 1;
      if (creates === 1) throw new ApiError(0, "NETWORK_ERROR", "response lost");
      storage.setItem(operationKey, JSON.stringify({
        idempotencyKey: "newer-key", commandIdentity: '[4,["file-b"]]',
      }));
      throw new ApiError(409, "BUSINESS_ERROR", "确定性拒绝", {
        classification: "READINESS_REJECTED",
      });
    },
  });
  const { hook } = await remountAfterLostCreate(source, storage, () => "key-1");
  t.after(hook.unmount);
  await hook.act(async () => { hook.state.startNew(); });

  await hook.act(async () => { await hook.state.start(); });

  assert.equal(creates, 2);
  assert.equal(storage.getItem(operationKey), JSON.stringify({
    idempotencyKey: "newer-key", commandIdentity: '[4,["file-b"]]',
  }));
  assert.equal(hook.state.run, null);
  assert.ok(hook.state.error);
});

for (const classification of ["OUTCOME_UNCERTAIN", "INTEGRITY_CONFLICT", undefined, "UNKNOWN"] as const) {
  test(`create classification ${classification ?? "missing"} fails closed and retains the exact old key`, async (t) => {
    const storage = new MemoryStorage();
    let allocated = 0;
    const keys: string[] = [];
    const recovered = completedRun("run-2");
    const source = restoreSource({
      getLatestReviewRun: async () => recovered,
      getReviewRun: async (_p, _c, runId) => completedRun(runId),
      createReviewRun: async (_p, _c, _version, _files, key) => {
        keys.push(key);
        if (keys.length === 1) throw new ApiError(0, "NETWORK_ERROR", "response lost");
        const data = classification === undefined ? undefined : { classification };
        throw new ApiError(409, "BUSINESS_ERROR", "message text is intentionally irrelevant", data);
      },
    });
    const { hook, options } = await remountAfterLostCreate(source, storage, () => `key-${++allocated}`);
    t.after(hook.unmount);
    await hook.render({ ...options, expectedContractVersion: 4, selectedFileIds: ["file-b"] });
    await hook.act(async () => { hook.state.startNew(); });
    const retained = [...storage.values.entries()];

    await hook.act(async () => { await hook.state.start(); });

    assert.deepEqual(keys, ["review-run-ui-key-1", "review-run-ui-key-1"]);
    assert.equal(allocated, 1);
    assert.deepEqual([...storage.values.entries()], retained);
    assert.equal(hook.state.run, null);
  });
}

for (const changedState of ["pointer", "operation"] as const) {
  test(`reconciliation proof cannot overwrite a newer ${changedState}`, async (t) => {
    const storage = new MemoryStorage();
    const recovered = completedRun("run-2");
    let creates = 0;
    const operationKey = "audit-agent:contract-review-run:actor-1:project-1:contract-1:create:operation";
    const source = restoreSource({
      getLatestReviewRun: async () => recovered,
      getReviewRun: async (_p, _c, runId) => completedRun(runId),
      createReviewRun: async () => {
        creates += 1;
        if (creates === 1) throw new ApiError(0, "NETWORK_ERROR", "response lost");
        if (changedState === "pointer") {
          storage.setItem(POINTER_KEY, JSON.stringify({ runId: "newer-pointer" }));
          saveReviewRunRestartMarker(storage, ACTOR, PROJECT, CONTRACT, "newer-pointer", "newer-step");
        } else {
          storage.setItem(operationKey, JSON.stringify({ idempotencyKey: "newer-key", commandIdentity: '[3,["file-a"]]' }));
        }
        return { ...recovered, outputs: [] };
      },
    });
    const { hook } = await remountAfterLostCreate(source, storage, () => "stable");
    t.after(hook.unmount);
    await hook.act(async () => { hook.state.startNew(); });
    const operationBefore = storage.getItem(operationKey);
    await hook.act(async () => { await hook.state.start(); });
    assert.equal(hook.state.run, null);
    assert.ok(hook.state.error);
    assert.equal(creates, 2);
    assert.equal(storage.getItem(POINTER_KEY), changedState === "pointer"
      ? JSON.stringify({ runId: "newer-pointer" }) : null);
    assert.equal(storage.getItem(operationKey), changedState === "operation"
      ? JSON.stringify({ idempotencyKey: "newer-key", commandIdentity: '[3,["file-a"]]' }) : operationBefore);
    if (changedState === "pointer") assert.equal(loadReviewRunRestartMarker(storage, ACTOR, PROJECT, CONTRACT)?.runId, "newer-pointer");
  });
}

test("aborted reconciliation preserves its old key and cannot consume a newer workspace intent", async (t) => {
  const storage = new MemoryStorage();
  const recovered = completedRun("run-2");
  const otherRun = reopenedReviewRun({ ...completedRun("other-old"), projectId: "other" });
  const otherCreated = { ...completedRun("other-new"), projectId: "other", outputs: [] };
  let resolveReplay!: (run: ReviewRunDto) => void;
  const replay = new Promise<ReviewRunDto>((resolve) => { resolveReplay = resolve; });
  const createdProjects: string[] = [];
  let allocated = 0;
  const source = restoreSource({
    getLatestReviewRun: async (project) => project === PROJECT ? recovered : otherRun,
    getReviewRun: async (project, _c, runId) => project === PROJECT
      ? completedRun(runId) : runId === "other-old" ? otherRun : reopenedReviewRun(otherCreated),
    createReviewRun: async (project) => {
      createdProjects.push(project);
      if (createdProjects.length === 1) throw new ApiError(0, "NETWORK_ERROR", "response lost");
      return project === PROJECT ? replay : otherCreated;
    },
  });
  const { hook, options } = await remountAfterLostCreate(source, storage, () => `key-${++allocated}`);
  t.after(hook.unmount);
  await hook.act(async () => { hook.state.startNew(); });
  const retained = [...storage.values.entries()];
  let pending!: Promise<void>;
  await hook.act(async () => {
    pending = hook.state.start();
    await new Promise<void>((resolve) => setImmediate(resolve));
  });
  await hook.render({ ...options, projectId: "other" });
  await hook.act(async () => { hook.state.startNew(); });
  await hook.act(async () => {
    resolveReplay({ ...recovered, outputs: [] });
    await pending;
  });
  assert.equal(hook.state.run, null);
  assert.deepEqual([...storage.values.entries()], retained,
    "aborted replay must neither consume the old key nor restore a stale pointer");
  await hook.act(async () => { await hook.state.start(); });
  assert.deepEqual(createdProjects, [PROJECT, PROJECT, "other"]);
  assert.equal(hook.state.run?.runId, "other-new");
});

for (const executionMode of ["CLIENT_COMPATIBILITY", "SERVER_WORKER"] as const) {
  test(`reconciliation adopts a different unseen ${executionMode} run without a second create`, async (t) => {
    const storage = new MemoryStorage();
    const oldRun = completedRun(RUN);
    const unseen = { ...runWithId("run-2", {}, "PENDING"), executionMode };
    const finalRun = reopenedReviewRun({ ...completedRun("run-2"), executionMode });
    const keys: string[] = [];
    const executed: string[] = [];
    let allocated = 0;
    const source = restoreSource({
      getLatestReviewRun: async () => oldRun,
      getReviewRun: async (_p, _c, runId) => runId === RUN ? oldRun : finalRun,
      createReviewRun: async (_p, _c, version, files, key) => {
        assert.equal(version, 3);
        assert.deepEqual(files, ["file-a"]);
        keys.push(key);
        if (keys.length === 1) throw new ApiError(0, "NETWORK_ERROR", "response lost");
        return unseen;
      },
      executeReviewStep: async (_p, _c, runId, stepId) => {
        assert.equal(runId, "run-2");
        executed.push(stepId);
        return output(finalRun, stepId);
      },
    });
    const { hook, options } = await remountAfterLostCreate(source, storage, () => `key-${++allocated}`);
    t.after(hook.unmount);
    await hook.render({ ...options, expectedContractVersion: 4, selectedFileIds: ["file-b"] });
    await hook.act(async () => { hook.state.startNew(); });
    await hook.act(async () => { await hook.state.start(); });
    assert.deepEqual(keys, ["review-run-ui-key-1", "review-run-ui-key-1"]);
    assert.equal(hook.state.run?.runId, "run-2");
    assert.equal(hook.state.error, null);
    assert.deepEqual(executed, executionMode === "CLIENT_COMPATIBILITY"
      ? ["run-2-step-1", "run-2-step-2", "run-2-step-3", "run-2-step-4"] : []);
    assert.equal(allocated, executionMode === "CLIENT_COMPATIBILITY" ? 5 : 1,
      "only client step identities may be allocated after adopting the proven run");
  });
}

for (const status of ["PENDING", "RUNNING"] as const) {
  test(`fresh mount discovers ${status} SERVER_WORKER and starts GET-only polling`, async (t) => {
    const storage = new MemoryStorage();
    const discovered = reopenedReviewRun({
      ...runWithId(RUN, {}, status),
      executionMode: "SERVER_WORKER",
    });
    let discoveries = 0;
    let gets = 0;
    let creates = 0;
    let executes = 0;
    const source: ReviewRunDataSource = {
      getLatestReviewRun: async () => { discoveries += 1; return discovered; },
      getReviewRun: async (projectId, contractId, runId) => {
        assert.deepEqual([projectId, contractId, runId], [PROJECT, CONTRACT, RUN]);
        gets += 1;
        return discovered;
      },
      createReviewRun: async () => { creates += 1; throw new Error("restore must not create"); },
      executeReviewStep: async () => { executes += 1; throw new Error("worker run must not execute in browser"); },
      getReviewStep: async () => { throw new Error("not used"); },
    };
    const hook = await mountReviewRunHook({
      enabled: true, actorId: ACTOR, projectId: PROJECT, contractId: CONTRACT,
      expectedContractVersion: 3, selectedFileIds: ["file-a"], dataSource: source,
      persistence: createSessionStorageReviewRunOperationPersistence(storage, () => "not-used"),
    });
    t.after(hook.unmount);

    assert.equal(hook.state.run?.status, status);
    assert.equal(hook.state.run?.executionMode, "SERVER_WORKER");
    assert.equal(hook.state.recovered, true);
    assert.equal(hook.state.observing, true);
    assert.equal(storage.getItem(POINTER_KEY), JSON.stringify({ runId: RUN }));
    assert.equal(discoveries, 1);
    assert.equal(gets, 1);
    assert.equal(creates, 0);
    assert.equal(executes, 0);
  });
}

for (const status of ["PENDING", "RUNNING"] as const) {
  test(`fresh mount discovers ${status} CLIENT_COMPATIBILITY for explicit continuation only`, async (t) => {
    const storage = new MemoryStorage();
    const discovered = reopenedReviewRun(runWithId(RUN, {}, status));
    let discoveries = 0;
    let gets = 0;
    let creates = 0;
    let executes = 0;
    const source: ReviewRunDataSource = {
      getLatestReviewRun: async () => { discoveries += 1; return discovered; },
      getReviewRun: async () => { gets += 1; throw new Error("client restore must not poll"); },
      createReviewRun: async () => { creates += 1; throw new Error("restore must not create"); },
      executeReviewStep: async () => { executes += 1; throw new Error("restore must not auto-execute"); },
      getReviewStep: async () => { throw new Error("not used"); },
    };
    const hook = await mountReviewRunHook({
      enabled: true, actorId: ACTOR, projectId: PROJECT, contractId: CONTRACT,
      expectedContractVersion: 3, selectedFileIds: ["file-a"], dataSource: source,
      persistence: createSessionStorageReviewRunOperationPersistence(storage, () => "not-used"),
    });
    t.after(hook.unmount);

    assert.equal(hook.state.run?.status, status);
    assert.equal(hook.state.run?.executionMode, "CLIENT_COMPATIBILITY");
    assert.equal(hook.state.recovered, true);
    assert.equal(hook.state.observing, false);
    assert.equal(hook.state.presentation?.canContinue, true);
    assert.equal(storage.getItem(POINTER_KEY), JSON.stringify({ runId: RUN }));
    assert.equal(discoveries, 1);
    assert.equal(gets, 0);
    assert.equal(creates, 0);
    assert.equal(executes, 0);
  });
}

test("explicit new bypasses old latest discovery and proven create restores normal Start after final GET failure", async (t) => {
  const storage = new MemoryStorage();
  storage.setItem(POINTER_KEY, JSON.stringify({ runId: RUN }));
  const oldRun = completedRun(RUN);
  const nextRun = completedRun("run-2");
  const calls: string[] = [];
  let failFinalGet = true;
  const source = restoreSource({
    getLatestReviewRun: async () => { calls.push("latest"); return oldRun; },
    getReviewRun: async (_p, _c, runId) => {
      calls.push(`get:${runId}`);
      if (runId === RUN) return oldRun;
      if (failFinalGet) throw new ApiError(0, "NETWORK_ERROR", "final response lost");
      return nextRun;
    },
    createReviewRun: async () => { calls.push("create"); return { ...nextRun, outputs: [] }; },
  });
  const hook = await mountReviewRunHook({
    enabled: true, actorId: ACTOR, projectId: PROJECT, contractId: CONTRACT,
    expectedContractVersion: 3, selectedFileIds: ["file-a"], dataSource: source,
    persistence: createSessionStorageReviewRunOperationPersistence(storage, () => "stable"),
  });
  t.after(hook.unmount);
  saveReviewRunRestartMarker(storage, ACTOR, PROJECT, CONTRACT, RUN, `${RUN}-step-1`);
  await hook.act(async () => { hook.state.startNew(); });
  assert.equal(hook.state.run, null);
  assert.equal(storage.getItem(POINTER_KEY), null);
  assert.equal(loadReviewRunRestartMarker(storage, ACTOR, PROJECT, CONTRACT), null);
  await hook.act(async () => { await hook.state.start(); });
  assert.deepEqual(calls, [`get:${RUN}`, "create", "get:run-2"]);
  assert.equal(hook.state.run?.runId, "run-2");
  assert.ok(hook.state.error);
  failFinalGet = false;
  await hook.act(async () => { await hook.state.start(); });
  assert.deepEqual(calls, [`get:${RUN}`, "create", "get:run-2", "get:run-2"]);
  assert.equal(hook.state.run, nextRun);
  assert.equal(hook.state.error, null);
});

for (const failure of [
  new ApiError(0, "NETWORK_ERROR", "response lost"),
  new ApiError(409, "BUSINESS_ERROR", "create in progress or outcome is not proven"),
  new ApiError(503, "BUSINESS_ERROR", "temporarily unavailable"),
]) {
  test(`explicit new retains intent and stored create identity after ${failure.status}`, async (t) => {
    const storage = new MemoryStorage();
    storage.setItem(POINTER_KEY, JSON.stringify({ runId: RUN }));
    const oldRun = completedRun(RUN);
    const nextRun = completedRun("run-2");
    const keys: string[] = [];
    let discoveries = 0;
    let allocations = 0;
    const source = restoreSource({
      getLatestReviewRun: async () => { discoveries += 1; return oldRun; },
      getReviewRun: async (_p, _c, runId) => runId === RUN ? oldRun : nextRun,
      createReviewRun: async (_p, _c, version, files, key) => {
        assert.equal(version, 3);
        assert.deepEqual(files, ["file-a"]);
        keys.push(key);
        if (keys.length === 1) throw failure;
        return { ...nextRun, outputs: [] };
      },
    });
    const options = {
      enabled: true, actorId: ACTOR, projectId: PROJECT, contractId: CONTRACT,
      expectedContractVersion: 3, selectedFileIds: ["file-a"], dataSource: source,
      persistence: createSessionStorageReviewRunOperationPersistence(storage, () => `key-${++allocations}`),
    };
    const hook = await mountReviewRunHook(options);
    t.after(hook.unmount);
    await hook.act(async () => { hook.state.startNew(); });
    await hook.act(async () => { await hook.state.start(); });
    assert.equal(hook.state.run, null);
    assert.ok(hook.state.error);
    assert.equal(storage.getItem(POINTER_KEY), null);
    assert.equal(storage.length, 1, "only the uncertain create identity is persisted");
    await hook.render({ ...options, selectedFileIds: ["file-a", "file-a"] });
    await hook.act(async () => { await hook.state.start(); });
    assert.deepEqual(keys, ["review-run-ui-key-1", "review-run-ui-key-1"]);
    assert.equal(allocations, 1);
    assert.equal(discoveries, 0);
    assert.equal(hook.state.run?.runId, "run-2");
  });
}

for (const identity of ["actorId", "projectId", "contractId"] as const) {
  test(`workspace ${identity} change clears explicit new intent before Start`, async (t) => {
    const storage = new MemoryStorage();
    storage.setItem(POINTER_KEY, JSON.stringify({ runId: RUN }));
    const oldRun = completedRun(RUN);
    const otherRun = reopenedReviewRun({
      ...completedRun("other-run"),
      [identity === "actorId" ? "requestedBy" : identity]: "other",
    });
    let creates = 0;
    const source = restoreSource({
      getLatestReviewRun: async () => otherRun,
      getReviewRun: async (_p, _c, runId) => runId === RUN ? oldRun : otherRun,
      createReviewRun: async () => {
        creates += 1;
        throw new ApiError(0, "NETWORK_ERROR", "response lost");
      },
    });
    const options = {
      enabled: true, actorId: ACTOR, projectId: PROJECT, contractId: CONTRACT,
      expectedContractVersion: 3, selectedFileIds: ["file-a"], dataSource: source,
      persistence: createSessionStorageReviewRunOperationPersistence(storage, () => "stable"),
    };
    const hook = await mountReviewRunHook(options);
    t.after(hook.unmount);
    await hook.act(async () => { hook.state.startNew(); });
    await hook.act(async () => { await hook.state.start(); });
    assert.equal(creates, 1, "explicit new must reach create before changing workspace");
    await hook.render({ ...options, [identity]: "other" });
    assert.equal(hook.state.run?.runId, "other-run");
    await hook.act(async () => { await hook.state.start(); });
    assert.equal(creates, 1);
    assert.equal(hook.state.run?.runId, "other-run");
    assert.equal(hook.state.error, null);
  });
}

test("late create proof from an aborted workspace cannot clear the current explicit new intent", async (t) => {
  const storage = new MemoryStorage();
  storage.setItem(POINTER_KEY, JSON.stringify({ runId: RUN }));
  const oldRun = completedRun(RUN);
  const otherRun = reopenedReviewRun({ ...completedRun("other-old"), projectId: "other" });
  const otherCreated = { ...completedRun("other-new"), projectId: "other", outputs: [] };
  let resolveOldCreate!: (value: ReviewRunDto) => void;
  const oldCreate = new Promise<ReviewRunDto>((resolve) => { resolveOldCreate = resolve; });
  const createdProjects: string[] = [];
  let discoveries = 0;
  const source = restoreSource({
    getLatestReviewRun: async () => { discoveries += 1; return otherRun; },
    getReviewRun: async (project, _c, runId) => project === PROJECT
      ? oldRun : runId === "other-old" ? otherRun : reopenedReviewRun(otherCreated),
    createReviewRun: async (project) => {
      createdProjects.push(project);
      return project === PROJECT ? oldCreate : otherCreated;
    },
  });
  const options = {
    enabled: true, actorId: ACTOR, projectId: PROJECT, contractId: CONTRACT,
    expectedContractVersion: 3, selectedFileIds: ["file-a"], dataSource: source,
    persistence: createSessionStorageReviewRunOperationPersistence(storage, () => "stable"),
  };
  const hook = await mountReviewRunHook(options);
  t.after(hook.unmount);
  await hook.act(async () => { hook.state.startNew(); });
  let pendingCreate!: Promise<void>;
  await hook.act(async () => {
    pendingCreate = hook.state.start();
    await new Promise<void>((resolve) => setImmediate(resolve));
  });
  await hook.render({ ...options, projectId: "other" });
  assert.equal(hook.state.run?.runId, "other-old");
  await hook.act(async () => { hook.state.startNew(); });
  await hook.act(async () => {
    resolveOldCreate({ ...completedRun("old-late-create"), outputs: [] });
    await pendingCreate;
  });
  assert.equal(hook.state.run, null, "stale create proof must not publish to the current workspace");
  await hook.act(async () => { await hook.state.start(); });
  assert.deepEqual(createdProjects, [PROJECT, "other"]);
  assert.equal(discoveries, 1, "only the workspace switch should discover");
  assert.equal(hook.state.run?.runId, "other-new");
});

test("remount after explicit new rediscovers the old durable latest without creating", async (t) => {
  const storage = new MemoryStorage();
  storage.setItem(POINTER_KEY, JSON.stringify({ runId: RUN }));
  const oldRun = completedRun(RUN);
  let discoveries = 0;
  let creates = 0;
  const source = restoreSource({
    getLatestReviewRun: async () => { discoveries += 1; return oldRun; },
    getReviewRun: async () => oldRun,
    createReviewRun: async () => { creates += 1; throw new Error("must not create"); },
  });
  const options = {
    enabled: true, actorId: ACTOR, projectId: PROJECT, contractId: CONTRACT,
    expectedContractVersion: 3, selectedFileIds: ["file-a"], dataSource: source,
    persistence: createSessionStorageReviewRunOperationPersistence(storage, () => "stable"),
  };
  const hook = await mountReviewRunHook(options);
  await hook.act(async () => { hook.state.startNew(); });
  assert.equal(hook.state.run, null);
  assert.equal(storage.length, 0, "explicit new intent must not be persisted");
  await hook.unmount();
  const refreshed = await mountReviewRunHook(options);
  t.after(refreshed.unmount);
  assert.equal(discoveries, 1);
  assert.equal(refreshed.state.run, oldRun);
  assert.equal(refreshed.state.recovered, true);
  await refreshed.act(async () => { await refreshed.state.start(); });
  assert.equal(creates, 0);
});

test("blocked hook requires explicit restart before clearing the old pointer and creating a new run", async (t) => {
  const hookDocument = new HookDocument();
  const hookWindow = {
    document: hookDocument,
    addEventListener() {},
    removeEventListener() {},
    setTimeout: globalThis.setTimeout.bind(globalThis),
    clearTimeout: globalThis.clearTimeout.bind(globalThis),
    HTMLElement: HookElement,
    HTMLIFrameElement: class {},
    location: { protocol: "http:" },
  };
  Object.assign(hookWindow, { window: hookWindow, self: hookWindow, top: hookWindow, parent: hookWindow });
  hookDocument.defaultView = hookWindow;
  Object.assign(globalThis, {
    window: hookWindow,
    document: hookDocument,
    IS_REACT_ACT_ENVIRONMENT: true,
  });
  const React = await import("react");
  const { createRoot } = await import("react-dom/client");
  const storage = new MemoryStorage();
  const pointerKey = "audit-agent:contract-review-run:actor-1:project-1:contract-1:pointer";
  storage.setItem(pointerKey, JSON.stringify({ runId: RUN }));
  const persistence = createSessionStorageReviewRunOperationPersistence(storage, () => "stable");
  const initialBase = runWithId(RUN, {}, "RUNNING");
  const initial = reopenedReviewRun(initialBase);
  const blockedBase = runWithId(RUN, {
    F02_DETERMINISTIC: "PENDING",
    SEMANTIC_AI: "COMPLETED",
    BREACH: "COMPLETED",
    TERM: "COMPLETED",
  }, "RUNNING");
  const blocked = reopenedReviewRun(blockedBase);
  const nextCreated = runWithId("run-2", {}, "PENDING");
  const nextCompletedBase = runWithId("run-2", {
    F02_DETERMINISTIC: "COMPLETED",
    SEMANTIC_AI: "COMPLETED",
    BREACH: "COMPLETED",
    TERM: "COMPLETED",
  }, "COMPLETED");
  const nextCompleted = reopenedReviewRun(nextCompletedBase);
  const serverRuns = new Map<string, ReviewRunDto>([[RUN, initial]]);
  let oldRunReloads = 0;
  let createCalls = 0;
  let discoveries = 0;
  const executed: string[] = [];
  const source: ReviewRunDataSource = {
    getLatestReviewRun: async () => { discoveries += 1; return blocked; },
    createReviewRun: async () => {
      createCalls += 1;
      serverRuns.set("run-2", nextCreated);
      return nextCreated;
    },
    getReviewRun: async (_project, _contract, runId) => {
      if (runId === RUN) {
        oldRunReloads += 1;
        return oldRunReloads === 1 ? initial : blocked;
      }
      if (runId === "run-2") return nextCompleted;
      throw new Error("unexpected run");
    },
    executeReviewStep: async (_project, _contract, runId, stepId) => {
      executed.push(`${runId}:${stepId}`);
      if (runId === RUN && stepId === `${RUN}-step-1`) {
        throw new ApiError(409, "BUSINESS_ERROR", "raw policy conflict");
      }
      const target = runId === RUN ? blocked : nextCompleted;
      return output(target, stepId);
    },
    getReviewStep: async () => { throw new Error("not used"); },
  };
  let state: ReturnType<typeof useProjectContractReviewRun> | null = null;
  function Harness() {
    state = useProjectContractReviewRun({
      enabled: true,
      actorId: ACTOR,
      projectId: PROJECT,
      contractId: CONTRACT,
      expectedContractVersion: 3,
      selectedFileIds: ["file-a"],
      dataSource: source,
      persistence,
    });
    return null;
  }
  const root = createRoot(new HookElement(hookDocument, "div") as unknown as Element);
  await React.act(async () => {
    root.render(React.createElement(Harness));
    await new Promise<void>((resolve) => setImmediate(resolve));
  });
  assert.ok(state);
  assert.equal(state.run?.runId, RUN);

  await React.act(async () => { await state?.continuePending(); });
  assert.equal(state?.run, blocked);
  assert.equal(state?.restartRequired, true);
  assert.equal(state?.presentation?.canContinue, false);
  assert.equal(state?.error, REVIEW_RUN_RESTART_REQUIRED_MESSAGE);
  assert.equal(state?.run?.outputs.filter((item) =>
    ["SEMANTIC_AI", "BREACH", "TERM"].includes(item.step.stepType)).length, 3);
  const executionsAfterBlocker = executed.length;

  await React.act(async () => { await state?.continuePending(); });
  assert.equal(executed.length, executionsAfterBlocker);
  assert.equal(createCalls, 0);
  assert.equal(storage.getItem(pointerKey), JSON.stringify({ runId: RUN }));
  assert.deepEqual(loadReviewRunRestartMarker(storage, ACTOR, PROJECT, CONTRACT), {
    runId: RUN,
    stepId: `${RUN}-step-1`,
    reason: "RESTART_REQUIRED",
  });

  await React.act(async () => { root.unmount(); });
  state = null;
  const refreshedPersistence = createSessionStorageReviewRunOperationPersistence(
    storage, () => "must-not-create-during-restore",
  );
  function RefreshedHarness() {
    state = useProjectContractReviewRun({
      enabled: true,
      actorId: ACTOR,
      projectId: PROJECT,
      contractId: CONTRACT,
      expectedContractVersion: 3,
      selectedFileIds: ["file-a"],
      dataSource: source,
      persistence: refreshedPersistence,
    });
    return null;
  }
  const refreshedRoot = createRoot(new HookElement(hookDocument, "div") as unknown as Element);
  t.after(async () => { await React.act(async () => { refreshedRoot.unmount(); }); });
  const executionsBeforeRefresh = executed.length;
  await React.act(async () => {
    refreshedRoot.render(React.createElement(RefreshedHarness));
    await new Promise<void>((resolve) => setImmediate(resolve));
  });
  assert.ok(state);
  assert.equal(state.run, blocked);
  assert.equal(state.restartRequired, true);
  assert.equal(state.presentation?.canContinue, false);
  assert.equal(state.presentation?.canStartNew, true);
  assert.equal(state.presentation?.statusDetail, REVIEW_RUN_RESTART_REQUIRED_MESSAGE);
  assert.equal(state.error, REVIEW_RUN_RESTART_REQUIRED_MESSAGE);
  assert.equal(executed.length, executionsBeforeRefresh);

  await React.act(async () => { state?.startNew(); });
  assert.equal(state?.run, null);
  assert.equal(state?.restartRequired, false);
  assert.equal(storage.getItem(pointerKey), null);
  assert.equal(loadReviewRunRestartMarker(storage, ACTOR, PROJECT, CONTRACT), null);
  assert.equal(serverRuns.get(RUN), initial);

  await React.act(async () => { await state?.start(); });
  assert.equal(createCalls, 1);
  assert.equal(discoveries, 0);
  assert.equal(state?.run?.runId, "run-2");
  assert.equal(serverRuns.has(RUN), true);
});

test("canonical material identity is stable and rejects an empty selection", () => {
  assert.deepEqual(canonicalReviewMaterialIds(["b", "a", "b"]), ["a", "b"]);
  assert.throws(() => canonicalReviewMaterialIds([]), /至少选择一份/);
});

test("create and continuation errors use operation-specific customer language", () => {
  assert.equal(reviewRunCustomerError(
    new ApiError(0, "NETWORK_ERROR", "connection closed"), "CREATE",
  ), "正在确认本次审核是否已成功创建，请勿重复提交。");
  assert.equal(reviewRunCustomerError(
    new ApiError(409, "BUSINESS_ERROR", "authority drift"), "CREATE",
  ), "当前合同或审核材料已发生变化，请返回项目页面确认最新内容后重新发起审核。");
  assert.equal(reviewRunCustomerError(
    new ApiError(409, "BUSINESS_ERROR", "message text is intentionally irrelevant", {
      classification: "OUTCOME_UNCERTAIN",
    }), "CREATE",
  ), "正在确认本次审核是否已成功创建，请勿重复提交。");
  assert.equal(reviewRunCustomerError(
    new ApiError(409, "BUSINESS_ERROR", "review run create is in progress or outcome is not proven"), "CREATE",
  ), "当前合同或审核材料已发生变化，请返回项目页面确认最新内容后重新发起审核。",
  "English message text must not classify an untyped error");
  assert.equal(reviewRunCustomerError(
    new ApiError(409, "BUSINESS_ERROR", "policy drift"), "CONTINUE",
  ), "审核规则已更新，请重新发起本次审核。");
});
