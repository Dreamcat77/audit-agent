import type {
  AcknowledgeFindingRequestOptions,
  ContractRequestOptions,
} from "./data/contractDataSource";
import type {
  FindingAcknowledgement,
  ReviewProgress,
  ReviewProgressItem,
  ReviewRiskSummary,
} from "./types";
import { createOperationKey } from "../../api/operationKey.ts";

export interface ProgressiveReplayState {
  orderedItems: ReviewProgressItem[];
  total: number;
  current: number;
  completedPrefixCount: number;
  pendingAcknowledgementItem: ReviewProgressItem | null;
  complete: boolean;
  riskCount: number;
  riskSummary: ReviewRiskSummary;
}

function riskSummary(riskCount: number): ReviewRiskSummary {
  if (riskCount === 0) return "无风险";
  if (riskCount <= 2) return "低风险";
  return "高风险";
}

function hasMatchingAcknowledgement(item: ReviewProgressItem): boolean {
  return item.checkResultId !== null
    && item.acknowledgement?.checkResultId === item.checkResultId;
}

export function deriveProgressiveReplayState(progress: ReviewProgress): ProgressiveReplayState {
  const orderedItems = progress.items
    .map((item, sourceIndex) => ({ item, sourceIndex }))
    .sort((left, right) => left.item.displayOrder - right.item.displayOrder
      || left.sourceIndex - right.sourceIndex)
    .map(({ item }) => item);
  const pendingIndex = orderedItems.findIndex((item) => (
    item.type === "FINDING"
    && item.risk !== null
    && item.risk !== "GREEN"
    && !hasMatchingAcknowledgement(item)
  ));
  const completedPrefixCount = pendingIndex < 0 ? orderedItems.length : pendingIndex;
  const riskCount = orderedItems.filter((item) => (
    item.type === "FINDING" && item.risk !== null && item.risk !== "GREEN"
  )).length;

  return {
    orderedItems,
    total: progress.total,
    current: progress.total === 0
      ? 0
      : pendingIndex < 0
        ? progress.total
        : Math.min(pendingIndex + 1, progress.total),
    completedPrefixCount,
    pendingAcknowledgementItem: pendingIndex < 0 ? null : orderedItems[pendingIndex],
    complete: pendingIndex < 0,
    riskCount,
    riskSummary: riskSummary(riskCount),
  };
}

export interface FindingAcknowledgementOperationPersistence {
  load(checkResultId: string): string | null;
  save(checkResultId: string, idempotencyKey: string): void;
  clear(checkResultId: string, confirmedIdempotencyKey: string): void;
}

export interface FindingAcknowledgementOperationState {
  keyFor(checkResultId: string): string;
  retainedKey(checkResultId: string): string | null;
  reconcile(progress: ReviewProgress): void;
  clearTerminal(checkResultId: string, confirmedIdempotencyKey: string): void;
}

interface FindingAcknowledgementOperationStateOptions {
  createIdempotencyKey?: () => string;
  persistence?: FindingAcknowledgementOperationPersistence;
}

const memoryOnlyPersistence: FindingAcknowledgementOperationPersistence = {
  load: () => null,
  save: () => undefined,
  clear: () => undefined,
};

function defaultAcknowledgementKey(): string {
  return `review-ack-ui-${createOperationKey()}`;
}

export function createFindingAcknowledgementOperationState(
  options: FindingAcknowledgementOperationStateOptions = {},
): FindingAcknowledgementOperationState {
  const keys = new Map<string, string>();
  const persistence = options.persistence ?? memoryOnlyPersistence;
  const createIdempotencyKey = options.createIdempotencyKey ?? defaultAcknowledgementKey;

  function retainedKey(checkResultId: string): string | null {
    const cached = keys.get(checkResultId);
    if (cached) return cached;
    const persisted = persistence.load(checkResultId);
    if (!persisted) return null;
    keys.set(checkResultId, persisted);
    return persisted;
  }

  function clear(checkResultId: string, confirmedIdempotencyKey: string): void {
    const retained = retainedKey(checkResultId);
    if (retained && retained !== confirmedIdempotencyKey) {
      throw new Error("风险知悉状态已发生变化，请刷新后重试");
    }
    persistence.clear(checkResultId, confirmedIdempotencyKey);
    keys.delete(checkResultId);
  }

  return {
    keyFor(checkResultId) {
      const retained = retainedKey(checkResultId);
      if (retained) return retained;
      const created = createIdempotencyKey();
      if (!created.trim()) throw new Error("暂时无法发起风险知悉，请重试");
      persistence.save(checkResultId, created);
      keys.set(checkResultId, created);
      return created;
    },
    retainedKey,
    reconcile(progress) {
      progress.items.forEach((item) => {
        if (!item.checkResultId || !hasMatchingAcknowledgement(item)) return;
        const retained = retainedKey(item.checkResultId);
        if (retained) clear(item.checkResultId, retained);
      });
    },
    clearTerminal: clear,
  };
}

const ACKNOWLEDGEMENT_STORAGE_PREFIX = "audit-agent:f02:pending-acknowledgement:";

export function createSessionStorageFindingAcknowledgementOperationPersistence(
  actorId: string,
  contractId: string,
  storage: Storage = window.sessionStorage,
): FindingAcknowledgementOperationPersistence {
  const actor = actorId.trim();
  const contract = contractId.trim();
  if (!actor || !contract) throw new Error("当前无法确认风险知悉范围，请重新进入合同页面");
  const keyFor = (checkResultId: string) => (
    `${ACKNOWLEDGEMENT_STORAGE_PREFIX}${encodeURIComponent(actor)}:${encodeURIComponent(contract)}:${encodeURIComponent(checkResultId)}`
  );

  return {
    load(checkResultId) {
      const value = storage.getItem(keyFor(checkResultId));
      return value?.trim() ? value : null;
    },
    save(checkResultId, idempotencyKey) {
      storage.setItem(keyFor(checkResultId), idempotencyKey);
    },
    clear(checkResultId, confirmedIdempotencyKey) {
      const storageKey = keyFor(checkResultId);
      const retained = storage.getItem(storageKey);
      if (retained && retained !== confirmedIdempotencyKey) {
        throw new Error("待确认的风险知悉状态已发生变化，请刷新后重试");
      }
      storage.removeItem(storageKey);
    },
  };
}

interface AcknowledgementProgressDataSource {
  acknowledgeFinding(
    checkResultId: string,
    options: AcknowledgeFindingRequestOptions,
  ): Promise<FindingAcknowledgement>;
  getReviewProgress(
    contractId: string,
    taskId: string,
    options?: ContractRequestOptions,
  ): Promise<ReviewProgress>;
}

export interface AcknowledgeFindingAndReloadProgressOptions {
  dataSource: AcknowledgementProgressDataSource;
  contractId: string;
  taskId: string;
  checkResultId: string;
  operationState: FindingAcknowledgementOperationState;
  signal?: AbortSignal;
}

function isTerminalOperationConflict(error: unknown): boolean {
  if (!error || typeof error !== "object") return false;
  const candidate = error as { status?: unknown; message?: unknown };
  if (candidate.status !== 409 || typeof candidate.message !== "string") return false;
  const message = candidate.message.trim().toLowerCase();
  return message === "idempotency key fingerprint conflict"
    || message === "idempotent acknowledgement previously failed";
}

function isAlreadyAcknowledgedConflict(error: unknown): boolean {
  if (!error || typeof error !== "object") return false;
  const candidate = error as { status?: unknown; message?: unknown };
  return candidate.status === 409
    && typeof candidate.message === "string"
    && candidate.message.trim().toLowerCase() === "review finding is already acknowledged";
}

function progressProvesAcknowledgement(
  progress: ReviewProgress,
  checkResultId: string,
): boolean {
  return progress.items.some((item) => item.checkResultId === checkResultId
    && item.acknowledgement?.checkResultId === checkResultId);
}

export async function acknowledgeFindingAndReloadProgress({
  dataSource,
  contractId,
  taskId,
  checkResultId,
  operationState,
  signal,
}: AcknowledgeFindingAndReloadProgressOptions): Promise<ReviewProgress> {
  const idempotencyKey = operationState.keyFor(checkResultId);
  try {
    await dataSource.acknowledgeFinding(checkResultId, { idempotencyKey, signal });
  } catch (error) {
    if (isTerminalOperationConflict(error)) {
      operationState.clearTerminal(checkResultId, idempotencyKey);
      throw error;
    }
    if (isAlreadyAcknowledgedConflict(error)) {
      const refreshed = await dataSource.getReviewProgress(contractId, taskId, { signal });
      operationState.reconcile(refreshed);
      if (progressProvesAcknowledgement(refreshed, checkResultId)) return refreshed;
    }
    throw error;
  }
  const refreshed = await dataSource.getReviewProgress(contractId, taskId, { signal });
  operationState.reconcile(refreshed);
  return refreshed;
}
