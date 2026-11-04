import assert from "node:assert/strict";
import test from "node:test";

import {
  clearContractReviewNavigation,
  loadContractReviewNavigation,
  saveContractReviewNavigation,
} from "./contractReviewNavigationPersistence.ts";

class MemoryStorage implements Storage {
  readonly values = new Map<string, string>();
  get length(): number { return this.values.size; }
  clear(): void { this.values.clear(); }
  getItem(key: string): string | null { return this.values.get(key) ?? null; }
  key(index: number): string | null { return [...this.values.keys()][index] ?? null; }
  removeItem(key: string): void { this.values.delete(key); }
  setItem(key: string, value: string): void { this.values.set(key, value); }
}

const ACTOR = "23471d15-267b-451e-aee4-2c539138c142";
const LEGACY_SHAPED_ACTOR = "00000000-0000-0000-0000-000000000001";
const PROJECT = "0675878d-6eb3-4ff8-b7bf-45df1e6706eb";
const CONTRACT = "da0c0c76-9da8-435f-a6ce-dcd3037ffc79";

test("review workspace navigation survives refresh and is actor scoped", () => {
  const storage = new MemoryStorage();
  saveContractReviewNavigation(storage, ACTOR, PROJECT, CONTRACT);

  assert.deepEqual(loadContractReviewNavigation(storage, ACTOR), {
    projectId: PROJECT,
    contractId: CONTRACT,
  });
  assert.equal(loadContractReviewNavigation(storage, "33471d15-267b-451e-aee4-2c539138c142"), null);

  clearContractReviewNavigation(storage, ACTOR);
  assert.equal(loadContractReviewNavigation(storage, ACTOR), null);
});

test("a stable 36-character non-RFC actor id can save and reload exact navigation", () => {
  const storage = new MemoryStorage();

  saveContractReviewNavigation(storage, LEGACY_SHAPED_ACTOR, PROJECT, CONTRACT);

  assert.deepEqual(loadContractReviewNavigation(storage, LEGACY_SHAPED_ACTOR), {
    projectId: PROJECT,
    contractId: CONTRACT,
  });
});

test("an opaque actor id is encoded, isolated and clearable without changing its identity", () => {
  const storage = new MemoryStorage();
  const actor = "audit-user:026:operator";
  const otherActor = "audit-user:026:reviewer";

  saveContractReviewNavigation(storage, actor, PROJECT, CONTRACT);

  assert.equal(storage.getItem("audit:contract-review-navigation:v1:audit-user%3A026%3Aoperator"),
    JSON.stringify({ projectId: PROJECT, contractId: CONTRACT }));
  assert.equal(loadContractReviewNavigation(storage, otherActor), null);
  clearContractReviewNavigation(storage, actor);
  assert.equal(loadContractReviewNavigation(storage, actor), null);
});

test("empty and whitespace-only actor ids fail closed", () => {
  const storage = new MemoryStorage();

  for (const actor of ["", "   "]) {
    assert.throws(() => saveContractReviewNavigation(storage, actor, PROJECT, CONTRACT),
      /合同审核导航身份无效/);
    assert.equal(loadContractReviewNavigation(storage, actor), null);
    clearContractReviewNavigation(storage, actor);
  }
  assert.equal(storage.length, 0);
});

test("project and contract identities remain strict RFC UUIDs", () => {
  const storage = new MemoryStorage();

  assert.throws(() => saveContractReviewNavigation(storage, ACTOR, "project-opaque", CONTRACT),
    /合同审核导航身份无效/);
  assert.throws(() => saveContractReviewNavigation(storage, ACTOR, PROJECT, "contract-opaque"),
    /合同审核导航身份无效/);
  assert.equal(storage.length, 0);
});

test("malformed or non-uuid navigation state fails closed", () => {
  const storage = new MemoryStorage();
  storage.setItem(`audit:contract-review-navigation:v1:${ACTOR}`, "not-json");
  assert.equal(loadContractReviewNavigation(storage, ACTOR), null);
  storage.setItem(`audit:contract-review-navigation:v1:${ACTOR}`,
    JSON.stringify({ projectId: "another-project", contractId: CONTRACT, findings: ["must-not-persist"] }));
  assert.equal(loadContractReviewNavigation(storage, ACTOR), null);
});

test("navigation state stores identities only", () => {
  const storage = new MemoryStorage();
  saveContractReviewNavigation(storage, ACTOR, PROJECT, CONTRACT);
  const serialized = [...storage.values.values()].join("\n");
  assert.deepEqual(Object.keys(JSON.parse(serialized)).sort(), ["contractId", "projectId"]);
  assert.doesNotMatch(serialized, /actorId|username|run|finding|evidence|output|excerpt|status|contractText/i);
});

test("RFC UUID actors retain the existing storage key format", () => {
  const storage = new MemoryStorage();

  saveContractReviewNavigation(storage, ACTOR, PROJECT, CONTRACT);

  assert.equal(storage.getItem(`audit:contract-review-navigation:v1:${ACTOR}`),
    JSON.stringify({ projectId: PROJECT, contractId: CONTRACT }));
});
