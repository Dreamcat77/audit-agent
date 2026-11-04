import type { ReviewStepRetryCommand } from "./data/httpReviewStepRetryDataSource.ts";
import type { ReviewRunDto, ReviewStepAttemptDto, ReviewStepDto } from "./reviewRunTypes.ts";
import type { ReviewRunOperationPersistence } from "./reviewRunOperation.ts";

export interface RetryableCurrentAttempt {
  step: ReviewStepDto;
  attempt: ReviewStepAttemptDto;
  command: ReviewStepRetryCommand;
}

export function retryableCurrentAttempt(
  run: ReviewRunDto,
  stepId: string,
): RetryableCurrentAttempt | null {
  if (run.executionMode !== "SERVER_WORKER" || !["RUNNING", "FAILED"].includes(run.status)) return null;
  const step = run.steps.find((candidate) => candidate.stepId === stepId) ?? null;
  if (!step || step.stepType === "MATERIAL_VALIDATION" || step.status !== "FAILED") return null;
  const attempt = run.attempts.find((candidate) =>
    candidate.stepId === step.stepId && candidate.attemptNo === step.currentAttemptNo) ?? null;
  if (!attempt || attempt.status !== "FAILED") return null;
  const externalSafe = attempt.externalCallState === "NOT_STARTED"
    || step.stepType === "SEMANTIC_AI" && attempt.externalCallState === "COMPLETED";
  if (!externalSafe) return null;
  return {
    step,
    attempt,
    command: {
      expectedRunVersion: run.versionNo,
      expectedStepVersion: step.versionNo,
      previousAttemptId: attempt.attemptId,
      previousAttemptNo: attempt.attemptNo,
      previousAttemptVersion: attempt.versionNo,
      previousStatus: "FAILED",
      previousExternalCallState: attempt.externalCallState as "NOT_STARTED" | "COMPLETED",
      reason: "USER_REQUESTED_RETRY",
    },
  };
}

function operationStorageKey(
  actorId: string, projectId: string, contractId: string, runId: string, stepId: string,
): string {
  return `audit-agent:contract-review-run:${[actorId, projectId, contractId, runId, stepId]
    .map((value) => encodeURIComponent(value.trim())).join(":")}:retry`;
}

export function loadOrCreateReviewStepRetryOperation(
  persistence: ReviewRunOperationPersistence,
  actorId: string,
  projectId: string,
  contractId: string,
  runId: string,
  stepId: string,
  command: ReviewStepRetryCommand,
): { idempotencyKey: string } {
  const storageKey = operationStorageKey(actorId, projectId, contractId, runId, stepId);
  const commandIdentity = JSON.stringify(command);
  const raw = persistence.storage.getItem(storageKey);
  if (raw !== null) {
    const stored = JSON.parse(raw) as { idempotencyKey?: unknown; commandIdentity?: unknown };
    if (typeof stored.idempotencyKey !== "string" || !stored.idempotencyKey
        || stored.commandIdentity !== commandIdentity) {
      throw new Error("上次重新处理结果尚未确认，请保持原审核状态后重试。");
    }
    return { idempotencyKey: stored.idempotencyKey };
  }
  const operation = {
    idempotencyKey: `review-retry-ui-${persistence.createKey()}`,
    commandIdentity,
  };
  persistence.storage.setItem(storageKey, JSON.stringify(operation));
  return { idempotencyKey: operation.idempotencyKey };
}

export function clearReviewStepRetryOperation(
  persistence: ReviewRunOperationPersistence,
  actorId: string,
  projectId: string,
  contractId: string,
  runId: string,
  stepId: string,
  expectedKey: string,
): void {
  const storageKey = operationStorageKey(actorId, projectId, contractId, runId, stepId);
  const raw = persistence.storage.getItem(storageKey);
  if (raw === null) return;
  const stored = JSON.parse(raw) as { idempotencyKey?: unknown };
  if (stored.idempotencyKey !== expectedKey) throw new Error("重新处理操作身份已发生变化，请刷新后重试。");
  persistence.storage.removeItem(storageKey);
}
