import assert from "node:assert/strict";
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

const [{
  clearSessionOperation,
  loadOrCreateSessionOperation,
}, {
  clearPendingF02ReviewOperation,
  createF02ReviewOperation,
  loadPendingF02ReviewOperation,
  savePendingF02ReviewOperation,
}, {
  createFindingAcknowledgementOperationState,
  createSessionStorageFindingAcknowledgementOperationPersistence,
}] = await Promise.all([
  import("./sessionOperation.ts"),
  import("./reviewOperation.ts"),
  import("./reviewProgressState.ts"),
]).finally(() => hooks.deregister());

const UUID_V4 = "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

function memoryStorage(): { storage: Storage; values: Map<string, string> } {
  const values = new Map<string, string>();
  const storage = {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => { values.set(key, value); },
    removeItem: (key: string) => { values.delete(key); },
    clear: () => values.clear(),
    key: (index: number) => Array.from(values.keys())[index] ?? null,
    get length() { return values.size; },
  } as Storage;
  return { storage, values };
}

function withHttpLanGlobals<T>(storage: Storage, callback: () => T): T {
  const originalCrypto = Object.getOwnPropertyDescriptor(globalThis, "crypto");
  const originalWindow = Object.getOwnPropertyDescriptor(globalThis, "window");
  let sequence = 0;
  Object.defineProperty(globalThis, "crypto", {
    configurable: true,
    value: {
      getRandomValues(bytes: Uint8Array) {
        bytes.fill(++sequence);
        return bytes;
      },
    },
  });
  Object.defineProperty(globalThis, "window", {
    configurable: true,
    value: { sessionStorage: storage },
  });
  try {
    return callback();
  } finally {
    if (originalCrypto) Object.defineProperty(globalThis, "crypto", originalCrypto);
    else Reflect.deleteProperty(globalThis, "crypto");
    if (originalWindow) Object.defineProperty(globalThis, "window", originalWindow);
    else Reflect.deleteProperty(globalThis, "window");
  }
}

test("ReviewSession retains its existing storage scope while using HTTP LAN fallback", () => {
  const { storage, values } = memoryStorage();
  withHttpLanGlobals(storage, () => {
    const first = loadOrCreateSessionOperation("actor 1", "session/1", "upload main");
    assert.match(first, new RegExp(`^${UUID_V4}$`));
    assert.equal(loadOrCreateSessionOperation("actor 1", "session/1", "upload main"), first);
    assert.deepEqual(Array.from(values.keys()), [
      "audit.review-session.operation.v1:actor%201:session%2F1:upload%20main",
    ]);
    clearSessionOperation("actor 1", "session/1", "upload main");
    assert.equal(values.size, 0);
  });
});

test("Direct F02 keeps its prefix and storage scope with HTTP LAN fallback", () => {
  const { storage, values } = memoryStorage();
  withHttpLanGlobals(storage, () => {
    const operation = createF02ReviewOperation();
    assert.match(operation.idempotencyKey, new RegExp(`^f02-ui-${UUID_V4}$`));
    savePendingF02ReviewOperation("actor 1", "contract/1", operation);
    assert.deepEqual(Array.from(values.keys()), [
      "audit-agent:f02:pending-operation:actor%201:contract%2F1",
    ]);
    assert.deepEqual(loadPendingF02ReviewOperation("actor 1", "contract/1"), operation);
    clearPendingF02ReviewOperation("actor 1", "contract/1", operation.idempotencyKey);
    assert.equal(values.size, 0);
  });
});

test("finding acknowledgement keeps its prefix, per-result scope and clear semantics", () => {
  const { storage, values } = memoryStorage();
  withHttpLanGlobals(storage, () => {
    const persistence = createSessionStorageFindingAcknowledgementOperationPersistence(
      "actor 1",
      "contract/1",
      storage,
    );
    const state = createFindingAcknowledgementOperationState({ persistence });
    const first = state.keyFor("result/1");
    assert.match(first, new RegExp(`^review-ack-ui-${UUID_V4}$`));
    assert.equal(state.keyFor("result/1"), first);
    assert.deepEqual(Array.from(values.keys()), [
      "audit-agent:f02:pending-acknowledgement:actor%201:contract%2F1:result%2F1",
    ]);
    state.clearTerminal("result/1", first);
    assert.equal(values.size, 0);
  });
});
