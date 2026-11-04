import assert from "node:assert/strict";
import test from "node:test";

import {
  clearReviewStepRetryOperation,
  loadOrCreateReviewStepRetryOperation,
  retryableCurrentAttempt,
} from "./reviewStepRetry.ts";
import type { ReviewRunDto } from "./reviewRunTypes.ts";

class MemoryStorage implements Storage {
  values = new Map<string, string>();
  get length() { return this.values.size; }
  clear() { this.values.clear(); }
  getItem(key: string) { return this.values.get(key) ?? null; }
  key(index: number) { return [...this.values.keys()][index] ?? null; }
  removeItem(key: string) { this.values.delete(key); }
  setItem(key: string, value: string) { this.values.set(key, value); }
}

function run(stepStatus: "FAILED" | "UNKNOWN", externalCallState: "NOT_STARTED" | "COMPLETED" | "OUTCOME_UNKNOWN"): ReviewRunDto {
  const stepId = "step-1";
  return {
    runId: "run-1", projectId: "project-1", contractId: "contract-1", contractVersion: 1,
    executionMode: "SERVER_WORKER", sourceCandidateId: "candidate", sourceConfirmationId: null,
    runSchemaVersion: 1, contractSnapshotCanonicalJson: "{}", contractSnapshotSha256: "hash",
    reviewProfileVersion: "profile", ruleVersion: "rule", modelPolicyVersion: "model",
    inputSnapshotIdentity: "identity", requestedBy: "actor", status: stepStatus,
    requestedAt: "2026-09-16T00:00:00Z", startedAt: null, completedAt: null,
    failedAt: null, unknownAt: null, safeReasonCode: null, versionNo: 4,
    materials: [], replayed: false, outputs: [],
    steps: [{ stepId, runId: "run-1", stepType: "BREACH", required: true, status: stepStatus,
      stepPolicyVersion: "policy", currentAttemptNo: 1, outputResourceType: null, outputResourceId: null,
      safeReasonCode: "safe", startedAt: null, completedAt: null, versionNo: 3 }],
    attempts: [{ attemptId: "attempt-1", runId: "run-1", stepId, attemptNo: 1,
      operationIdentity: "a".repeat(64), status: stepStatus, leaseOwner: null, leaseUntil: null,
      claimedAt: null, externalCallState, externalRequestFingerprint: null, providerRequestId: null,
      provider: null, model: null, startedAt: null, completedAt: null, safeReasonCode: "safe",
      outputResourceType: null, outputResourceId: null, versionNo: 2 }],
  };
}

test("only a server-worker FAILED current attempt with proven safe external state is retryable", () => {
  const eligible = retryableCurrentAttempt(run("FAILED", "NOT_STARTED"), "step-1");
  assert.deepEqual(eligible?.command, {
    expectedRunVersion: 4, expectedStepVersion: 3, previousAttemptId: "attempt-1",
    previousAttemptNo: 1, previousAttemptVersion: 2, previousStatus: "FAILED",
    previousExternalCallState: "NOT_STARTED", reason: "USER_REQUESTED_RETRY",
  });
  assert.equal(retryableCurrentAttempt(run("UNKNOWN", "OUTCOME_UNKNOWN"), "step-1"), null);
  assert.equal(retryableCurrentAttempt(run("FAILED", "OUTCOME_UNKNOWN"), "step-1"), null);
  assert.equal(retryableCurrentAttempt({ ...run("FAILED", "NOT_STARTED"), executionMode: "CLIENT_COMPATIBILITY" }, "step-1"), null);
});

test("retry keeps one key and immutable command until a proven response clears it", () => {
  const retryable = retryableCurrentAttempt(run("FAILED", "NOT_STARTED"), "step-1");
  assert.ok(retryable);
  const storage = new MemoryStorage();
  let sequence = 0;
  const persistence = { storage, createKey: () => `key-${++sequence}` };
  const first = loadOrCreateReviewStepRetryOperation(
    persistence, "actor", "project", "contract", "run-1", "step-1", retryable.command,
  );
  const replay = loadOrCreateReviewStepRetryOperation(
    persistence, "actor", "project", "contract", "run-1", "step-1", retryable.command,
  );
  assert.deepEqual(replay, first);
  assert.throws(() => loadOrCreateReviewStepRetryOperation(
    persistence, "actor", "project", "contract", "run-1", "step-1",
    { ...retryable.command, previousAttemptVersion: 99 },
  ));
  clearReviewStepRetryOperation(
    persistence, "actor", "project", "contract", "run-1", "step-1", first.idempotencyKey,
  );
  assert.equal(storage.length, 0);
});
