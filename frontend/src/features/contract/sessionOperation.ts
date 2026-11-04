import { ApiError } from "../../api/client";
import { createOperationKey } from "../../api/operationKey.ts";

const PREFIX = "audit.review-session.operation.v1";

function operationStorageKey(actorId: string, resourceId: string, action: string): string {
  return `${PREFIX}:${encodeURIComponent(actorId)}:${encodeURIComponent(resourceId)}:${encodeURIComponent(action)}`;
}

export function newSecureOperationKey(): string {
  return createOperationKey();
}

export function loadOrCreateSessionOperation(
  actorId: string,
  resourceId: string,
  action: string,
): string {
  const key = operationStorageKey(actorId, resourceId, action);
  const existing = window.sessionStorage.getItem(key);
  if (existing) return existing;
  const created = newSecureOperationKey();
  window.sessionStorage.setItem(key, created);
  return created;
}

export function clearSessionOperation(actorId: string, resourceId: string, action: string): void {
  window.sessionStorage.removeItem(operationStorageKey(actorId, resourceId, action));
}

export function isAmbiguousSessionOperation(error: unknown): boolean {
  if (!(error instanceof ApiError)) return true;
  return error.status === 0 || error.status >= 500
    || (error.status === 409 && error.message.toLowerCase().includes("in progress"));
}
