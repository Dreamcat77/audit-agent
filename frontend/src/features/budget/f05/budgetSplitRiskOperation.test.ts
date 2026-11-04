import assert from "node:assert/strict";
import test from "node:test";

const operation = await import("./budgetSplitRiskOperation.ts").catch(() => ({}));

function memoryStorage(): Storage {
  const values = new Map<string, string>();
  return {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => { values.set(key, value); },
    removeItem: (key) => { values.delete(key); },
    clear: () => values.clear(),
    key: (index) => Array.from(values.keys())[index] ?? null,
    get length() { return values.size; },
  } as Storage;
}

test("F05 keeps one key for the same project set regardless of selection order", () => {
  const storage = memoryStorage();
  let generated = 0;
  const createKey = () => `key-${++generated}`;
  const first = operation.loadOrCreateSplitRiskOperation(["b", "a"], storage, createKey);
  assert.equal(operation.loadOrCreateSplitRiskOperation(["a", "b"], storage, createKey), first);
  assert.equal(generated, 1);
  operation.clearSplitRiskOperation(["a", "b"], first, storage);
  assert.notEqual(operation.loadOrCreateSplitRiskOperation(["a", "b"], storage, createKey), first);
});
