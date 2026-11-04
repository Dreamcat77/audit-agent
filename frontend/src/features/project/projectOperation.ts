import { createOperationKey } from "../../api/operationKey.ts";

export interface RetainedOperation {
  key(): string;
  clear(): void;
}

export function createRetainedOperation(createKey: () => string = createOperationKey): RetainedOperation {
  let retained: string | null = null;
  return {
    key() { retained ??= createKey(); return retained; },
    clear() { retained = null; },
  };
}

type KeyStorage = Pick<Storage, "getItem" | "setItem" | "removeItem">;

/** Persists only an operation key, never contract text or a model response. */
export function createBrowserRetainedOperation(scope: string,
  createKey: () => string = createOperationKey, suppliedStorage?: KeyStorage): RetainedOperation {
  const storage = suppliedStorage ?? (typeof localStorage === "undefined" ? null : localStorage);
  const storageKey = `audit:operation:${scope}`;
  const fallback = createRetainedOperation(createKey);
  if (!storage) return fallback;
  return {
    key() {
      const existing = storage.getItem(storageKey);
      if (existing != null) {
        if (!/^[A-Za-z0-9._:-]{1,128}$/.test(existing)) {
          throw new Error("stored operation identity is invalid; do not replace an uncertain key");
        }
        return existing;
      }
      const next = createKey();
      storage.setItem(storageKey, next);
      return next;
    },
    clear() { storage.removeItem(storageKey); },
  };
}

export interface SingleFlightGuard {
  enter(): boolean;
  leave(): void;
}

export function createSingleFlightGuard(): SingleFlightGuard {
  let running = false;
  return {
    enter() {
      if (running) return false;
      running = true;
      return true;
    },
    leave() { running = false; },
  };
}
