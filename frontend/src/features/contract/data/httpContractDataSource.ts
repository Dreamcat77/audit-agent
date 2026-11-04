import { ApiError, request } from "../../../api/client";
import type {
  Contract,
  BreachClauseReview,
  ContractTermClauseReview,
  ContractTextAnalysis,
  FindingAcknowledgement,
  ReviewProgress,
  ReviewResult,
} from "../types";
import { resolveContractApiId } from "./contractSourceMap";
import type {
  ContractDataSource,
  ContractRequestOptions,
  AcknowledgeFindingRequestOptions,
  ReviewRequestOptions,
} from "./contractDataSource";

function requireApiId(contractId: string): string {
  const apiId = resolveContractApiId(contractId);
  if (apiId) return apiId;
  throw new ApiError(422, "BUSINESS_ERROR", `当前合同暂未配置真实审核数据映射：${contractId}`);
}

export class HttpContractDataSource implements ContractDataSource {
  supportsReview(contractId: string): boolean {
    return resolveContractApiId(contractId) !== null;
  }

  analyzeContractText(
    projectId: string,
    fileIds: readonly string[],
    options: ContractRequestOptions = {},
  ): Promise<ContractTextAnalysis> {
    return request<ContractTextAnalysis>(
      `/api/v1/projects/${encodeURIComponent(projectId)}/ai/contract-text-analysis`,
      { method: "POST", body: { fileIds }, signal: options.signal },
    );
  }

  reviewBreachClauses(
    projectId: string,
    contractId: string,
    fileIds: readonly string[],
    options: ContractRequestOptions = {},
  ): Promise<BreachClauseReview> {
    return request<BreachClauseReview>(
      `/api/v1/projects/${encodeURIComponent(projectId)}/contracts/${encodeURIComponent(contractId)}/clause-reviews/breach`,
      { method: "POST", body: { fileIds }, signal: options.signal },
    );
  }

  reviewTermClauses(
    projectId: string,
    contractId: string,
    fileIds: readonly string[],
    options: ContractRequestOptions = {},
  ): Promise<ContractTermClauseReview> {
    return request<ContractTermClauseReview>(
      `/api/v1/projects/${encodeURIComponent(projectId)}/contracts/${encodeURIComponent(contractId)}/clause-reviews/term-termination`,
      { method: "POST", body: { fileIds }, signal: options.signal },
    );
  }

  getContract(contractId: string, options: ContractRequestOptions = {}): Promise<Contract> {
    const apiId = requireApiId(contractId);
    return request<Contract>(`/api/v1/contracts/${encodeURIComponent(apiId)}`, {
      signal: options.signal,
    });
  }

  async getLatestReviewResult(
    contractId: string,
    options: ContractRequestOptions = {},
  ): Promise<ReviewResult | null> {
    const apiId = requireApiId(contractId);
    try {
      return await request<ReviewResult>(
        `/api/v1/contracts/${encodeURIComponent(apiId)}/agent-results`,
        { signal: options.signal },
      );
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) return null;
      throw error;
    }
  }

  reviewContract(contractId: string, options: ReviewRequestOptions): Promise<ReviewResult> {
    const apiId = requireApiId(contractId);
    return request<ReviewResult>(`/api/v1/contracts/${encodeURIComponent(apiId)}/agent-review`, {
      method: "POST",
      headers: {
        "Idempotency-Key": options.idempotencyKey,
      },
      signal: options.signal,
    });
  }

  getReviewProgress(
    contractId: string,
    taskId: string,
    options: ContractRequestOptions = {},
  ): Promise<ReviewProgress> {
    const apiId = requireApiId(contractId);
    return request<ReviewProgress>(
      `/api/v1/contracts/${encodeURIComponent(apiId)}/audit-tasks/${encodeURIComponent(taskId)}/review-progress`,
      { signal: options.signal },
    );
  }

  acknowledgeFinding(
    checkResultId: string,
    options: AcknowledgeFindingRequestOptions,
  ): Promise<FindingAcknowledgement> {
    return request<FindingAcknowledgement>(
      `/api/v1/audit-check-results/${encodeURIComponent(checkResultId)}/acknowledgement`,
      {
        method: "POST",
        headers: { "Idempotency-Key": options.idempotencyKey },
        signal: options.signal,
      },
    );
  }
}
