import assert from "node:assert/strict";
import test from "node:test";

const operation = await import("./budgetApprovalOperation.ts").catch(() => ({}));

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

test("one uncertain F04 validation retains its key until terminal clear", () => {
  const storage = memoryStorage();
  let generated = 0;
  const createKey = () => `budget-f04-ui-key-${++generated}`;

  const first = operation.loadOrCreateBudgetApprovalOperation(
    "task/1", "item/1", storage, createKey,
  );
  assert.equal(operation.loadOrCreateBudgetApprovalOperation(
    "task/1", "item/1", storage, createKey,
  ), first);
  assert.equal(generated, 1);

  operation.clearBudgetApprovalOperation("task/1", "item/1", first, storage);
  const regenerated = operation.loadOrCreateBudgetApprovalOperation(
    "task/1", "item/1", storage, createKey,
  );
  assert.notEqual(regenerated, first);
  assert.equal(generated, 2);
});
