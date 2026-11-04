import assert from "node:assert/strict";
import test from "node:test";
import { createBrowserRetainedOperation, createRetainedOperation, createSingleFlightGuard } from
  "./projectOperation.ts";

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

test("promotion single-flight guard rejects a second submit until the first settles", () => {
  const guard = createSingleFlightGuard();
  assert.equal(guard.enter(), true);
  assert.equal(guard.enter(), false);
  guard.leave();
  assert.equal(guard.enter(), true);
});

test("retained operation reuses one key until clear and regenerates afterwards", () => {
  const generated = ["operation-1", "operation-2"];
  const operation = createRetainedOperation(() => generated.shift()!);

  assert.equal(operation.key(), "operation-1");
  assert.equal(operation.key(), "operation-1");
  operation.clear();
  assert.equal(operation.key(), "operation-2");
});

test("candidate confirmation and promotion keys survive refresh without storing contract text", () => {
  const values = new Map<string, string>();
  const storage = { getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => { values.set(key, value); },
    removeItem: (key: string) => { values.delete(key); } };
  const confirm = createBrowserRetainedOperation("confirm:project:candidate", () => "confirm-key", storage);
  const promote = createBrowserRetainedOperation("promote:project:candidate:C1", () => "promote-key", storage);
  assert.equal(confirm.key(), "confirm-key"); assert.equal(promote.key(), "promote-key");
  assert.equal(createBrowserRetainedOperation("promote:project:candidate:C1",
    () => "wrong-new-key", storage).key(), "promote-key");
  assert.deepEqual([...values.values()].sort(), ["confirm-key", "promote-key"]);
  confirm.clear(); promote.clear();
  assert.equal(values.size, 0);
});

test("Project default operation key works with HTTP LAN Web Crypto fallback", () => {
  const originalCrypto = Object.getOwnPropertyDescriptor(globalThis, "crypto");
  let entropyCalls = 0;
  Object.defineProperty(globalThis, "crypto", {
    configurable: true,
    value: {
      getRandomValues(bytes: Uint8Array) {
        bytes.fill(++entropyCalls);
        return bytes;
      },
    },
  });
  try {
    const operation = createRetainedOperation();
    const first = operation.key();
    assert.match(first, UUID_V4);
    assert.equal(operation.key(), first);
    assert.equal(entropyCalls, 1);
    operation.clear();
    const second = operation.key();
    assert.match(second, UUID_V4);
    assert.notEqual(second, first);
    assert.equal(entropyCalls, 2);
  } finally {
    if (originalCrypto) Object.defineProperty(globalThis, "crypto", originalCrypto);
    else Reflect.deleteProperty(globalThis, "crypto");
  }
});
