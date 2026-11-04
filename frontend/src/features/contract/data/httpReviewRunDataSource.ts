import { ApiError, request } from "../../../api/client.ts";
import {
  assertReviewRunProjection,
  assertReviewStepOutputProjection,
} from "../reviewRunValidation.ts";
import type { ReviewRunDto, ReviewStepOutputDto } from "../reviewRunTypes.ts";
import type { ReviewRunDataSource, ReviewRunRequestOptions } from "./reviewRunDataSource.ts";

function root(projectId: string, contractId: string): string {
  return `/api/v1/projects/${encodeURIComponent(projectId)}/contracts/${encodeURIComponent(contractId)}/review-runs`;
}

export class HttpReviewRunDataSource implements ReviewRunDataSource {
  createReviewRun(
    projectId: string,
    contractId: string,
    expectedContractVersion: number,
    materialFileIds: readonly string[],
    idempotencyKey: string,
    options: ReviewRunRequestOptions = {},
  ): Promise<ReviewRunDto> {
    return request<unknown>(root(projectId, contractId), {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
      body: { expectedContractVersion, materialFileIds },
      signal: options.signal,
    }).then((value) => assertReviewRunProjection(value, "CREATE"));
  }

  getReviewRun(
    projectId: string,
    contractId: string,
    runId: string,
    options: ReviewRunRequestOptions = {},
  ): Promise<ReviewRunDto> {
    return request<unknown>(`${root(projectId, contractId)}/${encodeURIComponent(runId)}`, {
      signal: options.signal,
    }).then((value) => assertReviewRunProjection(value, "REOPEN"));
  }

  async getLatestReviewRun(
    projectId: string,
    contractId: string,
    options: ReviewRunRequestOptions = {},
  ): Promise<ReviewRunDto | null> {
    try {
      const value = await request<unknown>(`${root(projectId, contractId)}/latest`, {
        signal: options.signal,
      });
      return assertReviewRunProjection(value, "REOPEN");
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 404) return null;
      throw cause;
    }
  }

  executeReviewStep(
    projectId: string,
    contractId: string,
    runId: string,
    stepId: string,
    idempotencyKey: string,
    options: ReviewRunRequestOptions = {},
  ): Promise<ReviewStepOutputDto> {
    return request<unknown>(
      `${root(projectId, contractId)}/${encodeURIComponent(runId)}/steps/${encodeURIComponent(stepId)}/execute`,
      {
        method: "POST",
        headers: { "Idempotency-Key": idempotencyKey },
        body: {},
        signal: options.signal,
      },
    ).then(assertReviewStepOutputProjection);
  }

  getReviewStep(
    projectId: string,
    contractId: string,
    runId: string,
    stepId: string,
    options: ReviewRunRequestOptions = {},
  ): Promise<ReviewStepOutputDto> {
    return request<unknown>(
      `${root(projectId, contractId)}/${encodeURIComponent(runId)}/steps/${encodeURIComponent(stepId)}`,
      { signal: options.signal },
    ).then(assertReviewStepOutputProjection);
  }
}

export const httpReviewRunDataSource = new HttpReviewRunDataSource();
