import { createOperationKey } from "../../../api/operationKey.ts";

const STORAGE_PREFIX = "audit-agent:budget:f05:pending-check:";

function scope(ids: string[]): string {
  const normalized = [...new Set(ids.map((id) => id.trim()).filter(Boolean))].sort();
  if (normalized.length < 2) throw new Error("项目拆分风险分析至少需要两条采购申报记录");
  return encodeURIComponent(normalized.join(","));
}

function browserStorage(): Storage {
  if (typeof window === "undefined") throw new Error("当前环境不支持保存检测操作");
  return window.sessionStorage;
}

export function loadOrCreateSplitRiskOperation(
  ids: string[],
  storage: Storage = browserStorage(),
  createKey: () => string = createOperationKey,
): string {
  const key = `${STORAGE_PREFIX}${scope(ids)}`;
  const retained = storage.getItem(key);
  if (retained) return retained;
  const created = `budget-f05-ui-${createKey()}`;
  storage.setItem(key, created);
  return created;
}

export function clearSplitRiskOperation(
  ids: string[],
  confirmedKey: string,
  storage: Storage = browserStorage(),
): void {
  const key = `${STORAGE_PREFIX}${scope(ids)}`;
  const retained = storage.getItem(key);
  if (retained && retained !== confirmedKey) {
    throw new Error("待确认的风险分析操作已变化，未清除其幂等标识");
  }
  storage.removeItem(key);
}

export function isAmbiguousSplitRiskOperation(error: unknown): boolean {
  if (!error || typeof error !== "object" || !("status" in error)) return true;
  const candidate = error as { status?: unknown; message?: unknown };
  if (typeof candidate.status !== "number") return true;
  const message = typeof candidate.message === "string" ? candidate.message.toLowerCase() : "";
  return candidate.status === 0
    || candidate.status >= 500
    || (candidate.status === 409 && message.includes("in progress"));
}
