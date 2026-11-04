import { request } from "../../../api/client.ts";
import type { ReviewStepAttemptDto } from "../reviewRunTypes.ts";
import type { ReviewRunRequestOptions } from "./reviewRunDataSource.ts";

export interface ReviewStepRetryCommand {
  expectedRunVersion: number;
  expectedStepVersion: number;
  previousAttemptId: string;
  previousAttemptNo: number;
  previousAttemptVersion: number;
  previousStatus: "FAILED";
  previousExternalCallState: "NOT_STARTED" | "COMPLETED";
  reason: string;
}
export interface ReviewStepRetryResult { attempt: ReviewStepAttemptDto; replayed: boolean }

export class HttpReviewStepRetryDataSource {
  /** Explicit user command only. Caller must persist and reuse key+body while the outcome is uncertain. */
  async retry(projectId: string, contractId: string, runId: string, stepId: string,
    command: ReviewStepRetryCommand, idempotencyKey: string,
    options: ReviewRunRequestOptions = {}): Promise<ReviewStepRetryResult> {
    if (!/^[A-Za-z0-9._:-]{1,128}$/.test(idempotencyKey)) throw new Error("invalid retry identity");
    const path = `/api/v1/projects/${encodeURIComponent(projectId)}/contracts/${encodeURIComponent(contractId)}`
      + `/review-runs/${encodeURIComponent(runId)}/steps/${encodeURIComponent(stepId)}/retries`;
    const value = await request<ReviewStepRetryResult>(path, {
      method: "POST", headers: { "Idempotency-Key": idempotencyKey }, body: command, signal: options.signal,
    });
    const attempt = value?.attempt;
    if (typeof value?.replayed !== "boolean" || !attempt || attempt.runId !== runId || attempt.stepId !== stepId
      || typeof attempt.attemptId !== "string" || !attempt.attemptId || attempt.attemptId === command.previousAttemptId
      || attempt.attemptNo !== command.previousAttemptNo + 1 || !Number.isSafeInteger(attempt.versionNo) || attempt.versionNo < 0
      || !["PENDING", "RUNNING", "COMPLETED", "FAILED", "UNKNOWN"].includes(attempt.status)
      || !/^[a-f0-9]{64}$/.test(attempt.operationIdentity))
      throw new Error("invalid review retry projection");
    return value;
  }
}
export const httpReviewStepRetryDataSource = new HttpReviewStepRetryDataSource();
