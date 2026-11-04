import assert from "node:assert/strict";
import test from "node:test";

import {
  clearActiveReviewRun,
  clearReviewRunRestartMarker,
  clearReviewRunCreateOperation,
  createSessionStorageReviewRunOperationPersistence,
  loadReviewRunRestartMarker,
  loadReviewRunCreateOperation,
  loadOrCreateReviewRunCreateOperation,
  loadOrCreateReviewStepOperation,
  loadActiveReviewRun,
  saveActiveReviewRun,
  saveReviewRunRestartMarker,
} from "./reviewRunOperation.ts";

class MemoryStorage implements Storage {
  readonly values = new Map<string, string>();
  get length(): number { return this.values.size; }
  clear(): void { this.values.clear(); }
  getItem(key: string): string | null { return this.values.get(key) ?? null; }
  key(index: number): string | null { return [...this.values.keys()][index] ?? null; }
  removeItem(key: string): void { this.values.delete(key); }
  setItem(key: string, value: string): void { this.values.set(key, value); }
}

const ACTOR = "auditor-1";
const PROJECT = "project-1";
const CONTRACT = "contract-1";
const RUN = "run-1";
const STEP = "step-1";

test("active run pointer is scoped by actor, project and contract and clears only the expected run", () => {
  const storage = new MemoryStorage();
  saveActiveReviewRun(storage, ACTOR, PROJECT, CONTRACT, RUN);
  assert.deepEqual(loadActiveReviewRun(storage, ACTOR, PROJECT, CONTRACT), { runId: RUN });
  assert.equal(loadActiveReviewRun(storage, "another-user", PROJECT, CONTRACT), null);
  assert.equal(loadActiveReviewRun(storage, ACTOR, "another-project", CONTRACT), null);
  assert.throws(() => clearActiveReviewRun(storage, ACTOR, PROJECT, CONTRACT, "run-other"), /发生变化/);
  assert.deepEqual(loadActiveReviewRun(storage, ACTOR, PROJECT, CONTRACT), { runId: RUN });
  clearActiveReviewRun(storage, ACTOR, PROJECT, CONTRACT, RUN);
  assert.equal(loadActiveReviewRun(storage, ACTOR, PROJECT, CONTRACT), null);
});

test("create and step operation keys survive refresh and reject changed commands", () => {
  const storage = new MemoryStorage();
  let sequence = 0;
  const persistence = createSessionStorageReviewRunOperationPersistence(storage, () => `generated-${++sequence}`);
  const first = loadOrCreateReviewRunCreateOperation(
    persistence, ACTOR, PROJECT, CONTRACT, 3, ["file-b", "file-a", "file-a"],
  );
  const refreshed = createSessionStorageReviewRunOperationPersistence(storage, () => "must-not-run");
  const replay = loadOrCreateReviewRunCreateOperation(
    refreshed, ACTOR, PROJECT, CONTRACT, 3, ["file-a", "file-b"],
  );
  assert.deepEqual(replay, first);
  assert.match(first.idempotencyKey, /^review-run-ui-generated-1$/);
  assert.throws(() => loadOrCreateReviewRunCreateOperation(
    refreshed, ACTOR, PROJECT, CONTRACT, 4, ["file-a", "file-b"],
  ), /审核材料或合同版本已变化/);

  const step = loadOrCreateReviewStepOperation(refreshed, ACTOR, PROJECT, CONTRACT, RUN, STEP);
  assert.deepEqual(
    loadOrCreateReviewStepOperation(refreshed, ACTOR, PROJECT, CONTRACT, RUN, STEP),
    step,
  );
  assert.match(step.idempotencyKey, /^review-step-ui-/);
});

test("storage contains only pointer and operation identity, never durable review output", () => {
  const storage = new MemoryStorage();
  const persistence = createSessionStorageReviewRunOperationPersistence(storage, () => "stable");
  saveActiveReviewRun(storage, ACTOR, PROJECT, CONTRACT, RUN);
  const create = loadOrCreateReviewRunCreateOperation(
    persistence, ACTOR, PROJECT, CONTRACT, 1, ["file-a"],
  );
  loadOrCreateReviewStepOperation(persistence, ACTOR, PROJECT, CONTRACT, RUN, STEP);
  const serialized = [...storage.values.values()].join("\n");
  assert.doesNotMatch(serialized, /finding|evidence|output|summary|excerpt|materialText/i);
  clearReviewRunCreateOperation(persistence, ACTOR, PROJECT, CONTRACT, create.idempotencyKey);
  assert.equal([...storage.values.keys()].some((key) => key.includes(":create:")), false);
});

test("restart marker is scoped to one user and stores only navigation identity", () => {
  const storage = new MemoryStorage();
  saveReviewRunRestartMarker(storage, ACTOR, PROJECT, CONTRACT, RUN, STEP);

  assert.deepEqual(loadReviewRunRestartMarker(storage, ACTOR, PROJECT, CONTRACT), {
    runId: RUN,
    stepId: STEP,
    reason: "RESTART_REQUIRED",
  });
  assert.equal(loadReviewRunRestartMarker(storage, "another-user", PROJECT, CONTRACT), null);
  assert.equal(loadReviewRunRestartMarker(storage, ACTOR, "another-project", CONTRACT), null);
  const serialized = [...storage.values.values()].join("\n");
  assert.doesNotMatch(serialized, /finding|evidence|output|summary|excerpt|contractText|policy|hash|409|error/i);
  assert.deepEqual(Object.keys(JSON.parse(serialized)).sort(), ["reason", "runId", "stepId"]);
});

test("restart marker clear is run-guarded and malformed storage is discarded", () => {
  const storage = new MemoryStorage();
  saveReviewRunRestartMarker(storage, ACTOR, PROJECT, CONTRACT, RUN, STEP);
  clearReviewRunRestartMarker(storage, ACTOR, PROJECT, CONTRACT, "another-run");
  assert.equal(loadReviewRunRestartMarker(storage, ACTOR, PROJECT, CONTRACT)?.runId, RUN);
  clearReviewRunRestartMarker(storage, ACTOR, PROJECT, CONTRACT, RUN);
  assert.equal(loadReviewRunRestartMarker(storage, ACTOR, PROJECT, CONTRACT), null);

  saveReviewRunRestartMarker(storage, ACTOR, PROJECT, CONTRACT, RUN, STEP);
  const markerKey = [...storage.values.keys()].find((key) => key.endsWith(":restart-required"));
  assert.ok(markerKey);
  storage.setItem(markerKey, JSON.stringify({ runId: RUN, stepId: STEP, reason: "RAW_409" }));
  assert.equal(loadReviewRunRestartMarker(storage, ACTOR, PROJECT, CONTRACT), null);
  assert.equal(storage.getItem(markerKey), null);
});

test("malformed operation storage fails closed with safe Chinese errors", () => {
  const storage = new MemoryStorage();
  const persistence = createSessionStorageReviewRunOperationPersistence(storage, () => "stable");
  loadOrCreateReviewRunCreateOperation(persistence, ACTOR, PROJECT, CONTRACT, 1, ["file-a"]);
  const createKey = [...storage.values.keys()].find((key) => key.includes(":create:"));
  assert.ok(createKey);
  storage.setItem(createKey, "not-json");
  assert.throws(() => loadOrCreateReviewRunCreateOperation(
    persistence, ACTOR, PROJECT, CONTRACT, 1, ["file-a"],
  ), /审核操作状态无法读取/);
});

test("malformed active pointer is discarded instead of becoming false durable state", () => {
  const storage = new MemoryStorage();
  const key = "audit-agent:contract-review-run:auditor-1:project-1:contract-1:pointer";
  storage.setItem(key, "not-json");
  assert.equal(loadActiveReviewRun(storage, ACTOR, PROJECT, CONTRACT), null);
  assert.equal(storage.getItem(key), null);
});

test("retained create operation rehydrates its original command without allocating or mutating storage", () => {
  const storage = new MemoryStorage();
  const persistence = createSessionStorageReviewRunOperationPersistence(storage, () => {
    throw new Error("loading must not allocate a key");
  });
  const raw = JSON.stringify({ idempotencyKey: "old-key", commandIdentity: '[3,["file-a","file-b"]]' });
  const key = "audit-agent:contract-review-run:auditor-1:project-1:contract-1:create:operation";
  storage.setItem(key, raw);
  assert.deepEqual(loadReviewRunCreateOperation(persistence, ACTOR, PROJECT, CONTRACT), {
    idempotencyKey: "old-key", expectedContractVersion: 3, materialFileIds: ["file-a", "file-b"],
  });
  assert.equal(loadReviewRunCreateOperation(persistence, "another-user", PROJECT, CONTRACT), null);
  assert.equal(storage.getItem(key), raw);
});

for (const identity of [
  "not-json", "null", "{}", "[]", '[3]', '[3,["file-a"],"extra"]',
  '[-1,["file-a"]]', '[1.5,["file-a"]]', '[9007199254740992,["file-a"]]',
  '["3",["file-a"]]', '[3,[]]', '[3,"file-a"]', '[3,[null]]', '[3,[1]]',
  '[3,[""]]', '[3,[" "]]', '[3,[" file-a"]]', '[3,["file-a","file-a"]]', '[3,["file-b","file-a"]]',
]) {
  test(`retained create identity rejects malformed or noncanonical command without discarding uncertainty: ${identity}`, () => {
    const storage = new MemoryStorage();
    const persistence = createSessionStorageReviewRunOperationPersistence(storage, () => "must-not-run");
    const key = "audit-agent:contract-review-run:auditor-1:project-1:contract-1:create:operation";
    const raw = JSON.stringify({ idempotencyKey: "old-key", commandIdentity: identity });
    storage.setItem(key, raw);
    assert.throws(() => loadReviewRunCreateOperation(persistence, ACTOR, PROJECT, CONTRACT),
      /审核操作状态/);
    assert.equal(storage.getItem(key), raw);
  });
}
