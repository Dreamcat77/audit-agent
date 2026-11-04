import { createOperationKey } from "../../api/operationKey.ts";

export interface F02ReviewOperation {
  idempotencyKey: string;
}

export interface F02ReviewOperationPersistence {
  load(actorId: string, contractId: string): F02ReviewOperation | null;
  save(actorId: string, contractId: string, operation: F02ReviewOperation): void;
  clear(actorId: string, contractId: string, confirmedIdempotencyKey: string): void;
}

const STORAGE_KEY_PREFIX = "audit-agent:f02:pending-operation:";

function operationStorageKey(actorId: string, contractId: string): string {
  const normalizedActorId = actorId.trim();
  const normalizedContractId = contractId.trim();
  if (!normalizedActorId || !normalizedContractId) {
    throw new Error("当前审核缺少必要的用户或合同信息");
  }
  return `${STORAGE_KEY_PREFIX}${encodeURIComponent(normalizedActorId)}:${encodeURIComponent(normalizedContractId)}`;
}

function getOperationStorage(): Storage {
  if (typeof window === "undefined") {
    throw new Error("当前环境不支持保存审核操作");
  }
  return window.sessionStorage;
}

function isF02ReviewOperation(value: unknown): value is F02ReviewOperation {
  if (!value || typeof value !== "object") return false;
  const candidate = value as Partial<F02ReviewOperation>;
  return typeof candidate.idempotencyKey === "string" && candidate.idempotencyKey.trim().length > 0;
}

export function createF02ReviewOperation(): F02ReviewOperation {
  return { idempotencyKey: `f02-ui-${createOperationKey()}` };
}

export function loadPendingF02ReviewOperation(
  actorId: string,
  contractId: string,
): F02ReviewOperation | null {
  const rawValue = getOperationStorage().getItem(operationStorageKey(actorId, contractId));
  if (!rawValue) return null;

  let parsedValue: unknown;
  try {
    parsedValue = JSON.parse(rawValue);
  } catch {
    throw new Error("上一轮审核状态无法读取");
  }
  if (!isF02ReviewOperation(parsedValue)) {
    throw new Error("上一轮审核状态已失效");
  }
  return { idempotencyKey: parsedValue.idempotencyKey };
}

export function savePendingF02ReviewOperation(
  actorId: string,
  contractId: string,
  operation: F02ReviewOperation,
): void {
  getOperationStorage().setItem(operationStorageKey(actorId, contractId), JSON.stringify(operation));
}

export function clearPendingF02ReviewOperation(
  actorId: string,
  contractId: string,
  confirmedIdempotencyKey: string,
): void {
  const storedOperation = loadPendingF02ReviewOperation(actorId, contractId);
  if (storedOperation && storedOperation.idempotencyKey !== confirmedIdempotencyKey) {
    throw new Error("待确认的审核已发生变化，请刷新后重试");
  }
  getOperationStorage().removeItem(operationStorageKey(actorId, contractId));
}

/** Default persistence for the real legacy/direct F02 workflow. */
export const sessionStorageF02ReviewOperationPersistence: F02ReviewOperationPersistence = {
  load: loadPendingF02ReviewOperation,
  save: savePendingF02ReviewOperation,
  clear: clearPendingF02ReviewOperation,
};

/** Presentation-only seam: Showcase operations never enter the real F02 namespace. */
export const ephemeralF02ReviewOperationPersistence: F02ReviewOperationPersistence = {
  load: () => null,
  save: () => undefined,
  clear: () => undefined,
};
