export interface ContractReviewNavigation {
  projectId: string;
  contractId: string;
}

const PREFIX = "audit:contract-review-navigation:v1:";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function key(actorId: string): string | null {
  if (!actorId || actorId.trim().length === 0) return null;
  return `${PREFIX}${encodeURIComponent(actorId)}`;
}

function exactNavigation(value: unknown): value is ContractReviewNavigation {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const row = value as Record<string, unknown>;
  return Object.keys(row).length === 2
    && typeof row.projectId === "string" && UUID.test(row.projectId)
    && typeof row.contractId === "string" && UUID.test(row.contractId);
}

export function loadContractReviewNavigation(
  storage: Storage,
  actorId: string,
): ContractReviewNavigation | null {
  const storageKey = key(actorId);
  if (!storageKey) return null;
  try {
    const raw = storage.getItem(storageKey);
    if (!raw) return null;
    const parsed: unknown = JSON.parse(raw);
    return exactNavigation(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

export function saveContractReviewNavigation(
  storage: Storage,
  actorId: string,
  projectId: string,
  contractId: string,
): void {
  const storageKey = key(actorId);
  const value = { projectId, contractId };
  if (!storageKey || !exactNavigation(value)) throw new Error("合同审核导航身份无效");
  storage.setItem(storageKey, JSON.stringify(value));
}

export function clearContractReviewNavigation(storage: Storage, actorId: string): void {
  const storageKey = key(actorId);
  if (storageKey) storage.removeItem(storageKey);
}
