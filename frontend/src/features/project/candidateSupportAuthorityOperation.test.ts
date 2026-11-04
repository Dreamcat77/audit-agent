import assert from "node:assert/strict";
import { registerHooks } from "node:module";
import test from "node:test";
const hooks = registerHooks({
  load(url, context, nextLoad) {
    const loaded = nextLoad(url, context);
    if (!url.endsWith("/src/api/client.ts")) return loaded;
    const source = typeof loaded.source === "string" ? loaded.source : Buffer.from(loaded.source ?? []).toString("utf8");
    return { ...loaded, source: `import.meta.env ??= {};\n${source}` };
  },
});
const {
  clearCandidateSupportOperation,
  createSessionStorageCandidateSupportOperationPersistence,
  loadCandidateSupportOperation,
  loadOrCreateCandidateSupportOperation,
} = await import("./candidateSupportAuthorityOperation.ts");
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
const operation = { idempotencyKey: "support-key-1", expectedCandidateVersion: 4,
  supportFileIds: ["award-a", "award-b"] };

function setup() {
  const storage = new MemoryStorage();
  let allocations = 0;
  const persistence = createSessionStorageCandidateSupportOperationPersistence(storage,
    () => `support-key-${++allocations}`);
  return { storage, persistence, allocations: () => allocations };
}

test("support command stores only canonical identity and retains the exact key through refresh", () => {
  const h = setup();
  assert.deepEqual(loadOrCreateCandidateSupportOperation(h.persistence, ...scope, 4,
    [" award-b ", "award-a", "award-a"]), operation);
  assert.deepEqual(JSON.parse(h.storage.getItem(h.storage.key(0)!)!), {
    idempotencyKey: "support-key-1", commandIdentity: '[4,["award-a","award-b"]]',
  });
  const before = [...h.storage.values];
  const refreshed = createSessionStorageCandidateSupportOperationPersistence(h.storage,
    () => { throw new Error("refresh cannot allocate"); });
  assert.deepEqual(loadCandidateSupportOperation(refreshed, ...scope), operation);
  assert.deepEqual(loadOrCreateCandidateSupportOperation(refreshed, ...scope, 4,
    ["award-b", "award-a"]), operation);
  assert.deepEqual([...h.storage.values], before);
  assert.equal(h.allocations(), 1);
});

test("read-only rediscovery never allocates and is scoped by actor, project and Candidate", () => {
  const h = setup();
  assert.equal(loadCandidateSupportOperation(h.persistence, ...scope), null);
  assert.equal(h.allocations(), 0);
  loadOrCreateCandidateSupportOperation(h.persistence, ...scope, 4, ["award-a", "award-b"]);
  for (const other of [["other", scope[1], scope[2]], [scope[0], "other", scope[2]],
    [scope[0], scope[1], "other"]] as const) {
    assert.equal(loadCandidateSupportOperation(h.persistence, ...other), null);
  }
  assert.equal(h.allocations(), 1);
});

test("a changed command cannot replace an unresolved operation", () => {
  const h = setup();
  loadOrCreateCandidateSupportOperation(h.persistence, ...scope, 4, ["award-a", "award-b"]);
  for (const [version, ids] of [[5, ["award-a", "award-b"]], [4, ["award-c"]]] as const) {
    assert.throws(() => loadOrCreateCandidateSupportOperation(h.persistence, ...scope, version, ids));
    assert.deepEqual(loadCandidateSupportOperation(h.persistence, ...scope), operation);
  }
  assert.equal(h.allocations(), 1);
});

test("explicit empty replacement is retained and only exact guarded clear permits a later new key", () => {
  const h = setup();
  const empty = loadOrCreateCandidateSupportOperation(h.persistence, ...scope, 0, []);
  assert.deepEqual(empty, { idempotencyKey: "support-key-1", expectedCandidateVersion: 0, supportFileIds: [] });
  clearCandidateSupportOperation(h.persistence, ...scope, empty);
  assert.equal(loadCandidateSupportOperation(h.persistence, ...scope), null);
  assert.deepEqual(loadOrCreateCandidateSupportOperation(h.persistence, ...scope, 1, ["award-c"]),
    { idempotencyKey: "support-key-2", expectedCandidateVersion: 1, supportFileIds: ["award-c"] });
});

for (const changed of [
  { ...operation, idempotencyKey: "new-key" },
  { ...operation, expectedCandidateVersion: 5 },
  { ...operation, supportFileIds: ["award-c"] },
]) {
  test(`guarded clear refuses a different complete operation ${JSON.stringify(changed)}`, () => {
    const h = setup();
    loadOrCreateCandidateSupportOperation(h.persistence, ...scope, 4, ["award-a", "award-b"]);
    assert.throws(() => clearCandidateSupportOperation(h.persistence, ...scope, changed));
    assert.deepEqual(loadCandidateSupportOperation(h.persistence, ...scope), operation);
  });
}

test("guarded clear cannot claim an absent operation was the exact sent command", () => {
  const h = setup();
  assert.throws(() => clearCandidateSupportOperation(h.persistence, ...scope, operation));
  assert.equal(h.storage.length, 0);
  assert.equal(h.allocations(), 0);
});

const malformed = [
  "not-json", "null", "[]", "{}",
  JSON.stringify({ idempotencyKey: "unsafe key", commandIdentity: '[4,["award-a","award-b"]]' }),
  JSON.stringify({ idempotencyKey: "k".repeat(129), commandIdentity: "[4,[]]" }),
  ...["null", "{}", "[4]", '[4,["award-b","award-a"]]', '[4,["award-a","award-a"]]',
    '[4,[" award-a"]]', '[4,[""]]', '[4,[null]]', '[-1,[]]', '[1.5,[]]',
    '[9007199254740992,[]]', '[4,[],"extra"]'].map((commandIdentity) =>
    JSON.stringify({ idempotencyKey: "support-key-1", commandIdentity })),
  JSON.stringify({ idempotencyKey: "support-key-1", commandIdentity: "[4,[]]", sourceText: "not-identity" }),
];

for (const raw of malformed) {
  test(`malformed stored support operation is preserved and never replaced: ${raw}`, () => {
    const h = setup();
    loadOrCreateCandidateSupportOperation(h.persistence, ...scope, 4, ["award-a", "award-b"]);
    const key = h.storage.key(0)!;
    h.storage.setItem(key, raw);
    assert.throws(() => loadCandidateSupportOperation(h.persistence, ...scope));
    assert.throws(() => loadOrCreateCandidateSupportOperation(h.persistence, ...scope, 4, []));
    assert.throws(() => clearCandidateSupportOperation(h.persistence, ...scope, operation));
    assert.equal(h.storage.getItem(key), raw);
    assert.equal(h.allocations(), 1);
  });
}

test("invalid new commands and unsafe keys cannot be persisted", () => {
  const h = setup();
  for (const version of [-1, 0.5, Number.NaN, Number.POSITIVE_INFINITY, Number.MAX_SAFE_INTEGER]) {
    assert.throws(() => loadOrCreateCandidateSupportOperation(h.persistence, ...scope, version, []));
  }
  for (const ids of [[" "], [null], "award-a"] as unknown as string[][]) {
    assert.throws(() => loadOrCreateCandidateSupportOperation(h.persistence, ...scope, 0, ids));
  }
  assert.equal(h.allocations(), 0);
  for (const key of ["", "bad key", "bad\nkey", " key ", "bad/key", "k".repeat(129)]) {
    const persistence = createSessionStorageCandidateSupportOperationPersistence(h.storage, () => key);
    assert.throws(() => loadOrCreateCandidateSupportOperation(persistence, ...scope, 0, []));
  }
  assert.equal(h.storage.length, 0);
});

test("storage read/write failures do not pretend a recoverable operation exists", () => {
  const h = setup();
  h.storage.getItem = () => { throw new Error("storage read failed"); };
  assert.throws(() => loadOrCreateCandidateSupportOperation(h.persistence, ...scope, 4, []));
  assert.equal(h.allocations(), 0);
  h.storage.getItem = () => null;
  h.storage.setItem = () => { throw new Error("storage write failed"); };
  assert.throws(() => loadOrCreateCandidateSupportOperation(h.persistence, ...scope, 4, []));
  assert.equal(h.storage.length, 0);
});
