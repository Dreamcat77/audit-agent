import { createOperationKey } from "../../../api/operationKey.ts";

const STORAGE_PREFIX = "audit-agent:budget:f04:pending-validation:";

function storageKey(taskId: string, itemId: string): string {
  const task = taskId.trim();
  const item = itemId.trim();
  if (!task || !item) throw new Error("审批程序校验缺少任务或项目范围");
  return `${STORAGE_PREFIX}${encodeURIComponent(task)}:${encodeURIComponent(item)}`;
}

function browserStorage(): Storage {
  if (typeof window === "undefined") throw new Error("当前环境不支持保存校验操作");
  return window.sessionStorage;
}

export function loadOrCreateBudgetApprovalOperation(
  taskId: string,
  itemId: string,
  storage: Storage = browserStorage(),
  createKey: () => string = createOperationKey,
): string {
  const key = storageKey(taskId, itemId);
  const retained = storage.getItem(key);
  if (retained) return retained;
  const created = `budget-f04-ui-${createKey()}`;
  storage.setItem(key, created);
  return created;
}

export function clearBudgetApprovalOperation(
  taskId: string,
  itemId: string,
  confirmedKey: string,
  storage: Storage = browserStorage(),
): void {
  const key = storageKey(taskId, itemId);
  const retained = storage.getItem(key);
  if (retained && retained !== confirmedKey) {
    throw new Error("待确认的审批程序校验操作已变化，未清除其幂等标识");
  }
  storage.removeItem(key);
}

export function isAmbiguousBudgetApprovalOperation(error: unknown): boolean {
  if (!error || typeof error !== "object" || !("status" in error)) return true;
  const candidate = error as { status?: unknown; message?: unknown };
  if (typeof candidate.status !== "number") return true;
  const message = typeof candidate.message === "string" ? candidate.message.toLowerCase() : "";
  return candidate.status === 0
    || candidate.status >= 500
    || (candidate.status === 409 && message.includes("in progress"));
}
