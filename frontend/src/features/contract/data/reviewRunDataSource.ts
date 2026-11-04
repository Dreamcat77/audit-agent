import type { ReviewRunDto, ReviewStepOutputDto } from "../reviewRunTypes.ts";

export interface ReviewRunRequestOptions {
  signal?: AbortSignal;
}

export interface ReviewRunDataSource {
  createReviewRun(
    projectId: string,
    contractId: string,
    expectedContractVersion: number,
    materialFileIds: readonly string[],
    idempotencyKey: string,
    options?: ReviewRunRequestOptions,
  ): Promise<ReviewRunDto>;
  getReviewRun(
    projectId: string,
    contractId: string,
    runId: string,
    options?: ReviewRunRequestOptions,
  ): Promise<ReviewRunDto>;
  getLatestReviewRun(
    projectId: string,
    contractId: string,
    options?: ReviewRunRequestOptions,
  ): Promise<ReviewRunDto | null>;
  executeReviewStep(
    projectId: string,
    contractId: string,
    runId: string,
    stepId: string,
    idempotencyKey: string,
    options?: ReviewRunRequestOptions,
  ): Promise<ReviewStepOutputDto>;
  getReviewStep(
    projectId: string,
    contractId: string,
    runId: string,
    stepId: string,
    options?: ReviewRunRequestOptions,
  ): Promise<ReviewStepOutputDto>;
}
