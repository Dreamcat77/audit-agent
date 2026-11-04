import { contractMockCases } from "../mock/contractMock";
import type {
  Contract,
  BreachClauseReview,
  ContractTermClauseReview,
  ContractTextAnalysis,
  FindingAcknowledgement,
  ReviewProgress,
  ReviewResult,
} from "../types";
import { ApiError } from "../../../api/client";
import { HttpContractDataSource } from "./httpContractDataSource";

export interface ContractRequestOptions {
  signal?: AbortSignal;
}

export interface ReviewRequestOptions extends ContractRequestOptions {
  idempotencyKey: string;
}

export type AcknowledgeFindingRequestOptions = ReviewRequestOptions;

export interface ContractDataSource {
  supportsReview(contractId: string): boolean;
  analyzeContractText(
    projectId: string,
    fileIds: readonly string[],
    options?: ContractRequestOptions,
  ): Promise<ContractTextAnalysis>;
  reviewBreachClauses?(
    projectId: string,
    contractId: string,
    fileIds: readonly string[],
    options?: ContractRequestOptions,
  ): Promise<BreachClauseReview>;
  reviewTermClauses?(
    projectId: string,
    contractId: string,
    fileIds: readonly string[],
    options?: ContractRequestOptions,
  ): Promise<ContractTermClauseReview>;
  getContract(contractId: string, options?: ContractRequestOptions): Promise<Contract>;
  getLatestReviewResult(
    contractId: string,
    options?: ContractRequestOptions,
  ): Promise<ReviewResult | null>;
  reviewContract(contractId: string, options: ReviewRequestOptions): Promise<ReviewResult>;
  getReviewProgress(
    contractId: string,
    taskId: string,
    options?: ContractRequestOptions,
  ): Promise<ReviewProgress>;
  acknowledgeFinding(
    checkResultId: string,
    options: AcknowledgeFindingRequestOptions,
  ): Promise<FindingAcknowledgement>;
}

function createAbortError(): Error {
  const error = new Error("请求已取消");
  error.name = "AbortError";
  return error;
}

function wait(milliseconds: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(createAbortError());
      return;
    }

    const timer = window.setTimeout(() => {
      signal?.removeEventListener("abort", onAbort);
      resolve();
    }, milliseconds);
    const onAbort = () => {
      window.clearTimeout(timer);
      reject(createAbortError());
    };
    signal?.addEventListener("abort", onAbort, { once: true });
  });
}

export class MockContractDataSource implements ContractDataSource {
  private readonly acknowledgementsByResult = new Map<string, FindingAcknowledgement>();
  private readonly resultByIdempotencyKey = new Map<string, string>();
  private readonly operationClock: () => Date;

  constructor(operationClock: () => Date = () => new Date()) {
    this.operationClock = operationClock;
  }

  supportsReview(contractId: string): boolean {
    return Object.hasOwn(contractMockCases, contractId);
  }

  async analyzeContractText(
    projectId: string,
    _fileIds: readonly string[],
    options: ContractRequestOptions = {},
  ): Promise<ContractTextAnalysis> {
    if (options.signal?.aborted) throw createAbortError();
    return {
      projectId,
      operation: "CONTRACT_TEXT_ANALYSIS",
      requestId: "NOT_APPLICABLE_LOCAL_FAKE",
      available: false,
      summaries: [],
      hints: [],
      model: "UNAVAILABLE_LOCAL_FAKE",
      durationMs: 0,
    };
  }

  async getContract(contractId: string, options: ContractRequestOptions = {}): Promise<Contract> {
    await wait(180, options.signal);
    const mockCase = contractMockCases[contractId];
    if (!mockCase) {
      throw new Error(`当前合同暂未配置智能审核数据：${contractId}`);
    }
    return mockCase.contract;
  }

  async getLatestReviewResult(
    _contractId: string,
    options: ContractRequestOptions = {},
  ): Promise<ReviewResult | null> {
    await wait(80, options.signal);
    return null;
  }

  async reviewContract(contractId: string, options: ReviewRequestOptions): Promise<ReviewResult> {
    await wait(620, options.signal);
    const mockCase = contractMockCases[contractId];
    if (!mockCase) {
      throw new Error(`当前合同暂未配置智能审核数据：${contractId}`);
    }
    return mockCase.reviewResult;
  }

  async getReviewProgress(
    contractId: string,
    taskId: string,
    options: ContractRequestOptions = {},
  ): Promise<ReviewProgress> {
    if (options.signal?.aborted) throw createAbortError();
    const mockCase = contractMockCases[contractId];
    if (!mockCase || mockCase.reviewResult.taskId !== taskId) {
      throw new Error("模拟审核进度不存在");
    }
    const riskCount = mockCase.reviewResult.findings.filter(
      (finding) => finding.riskLevel !== "GREEN",
    ).length;
    return {
      total: 1 + mockCase.reviewResult.findings.length,
      riskSummary: riskCount === 0 ? "无风险" : riskCount <= 2 ? "低风险" : "高风险",
      items: [
        {
          type: "SOURCE_PREPARATION",
          checkResultId: null,
          title: "读取合同及比对来源",
          displayOrder: 0,
          status: "DONE",
          risk: null,
          completedAt: null,
          acknowledgement: null,
        },
        ...mockCase.reviewResult.findings.map((finding, index) => ({
          type: "FINDING" as const,
          checkResultId: finding.id,
          title: finding.ruleCode,
          displayOrder: index + 1,
          status: finding.checkStatus,
          risk: finding.riskLevel,
          completedAt: finding.createdAt,
          acknowledgement: this.acknowledgementsByResult.get(finding.id) ?? null,
        })),
      ],
    };
  }

  async acknowledgeFinding(
    checkResultId: string,
    options: AcknowledgeFindingRequestOptions,
  ): Promise<FindingAcknowledgement> {
    if (options.signal?.aborted) throw createAbortError();
    const priorResultId = this.resultByIdempotencyKey.get(options.idempotencyKey);
    if (priorResultId && priorResultId !== checkResultId) {
      throw new ApiError(409, "BUSINESS_ERROR", "idempotency key fingerprint conflict");
    }
    const finding = Object.values(contractMockCases)
      .flatMap((mockCase) => mockCase.reviewResult.findings.map((item) => ({ item })))
      .find(({ item }) => item.id === checkResultId);
    if (!finding) throw new ApiError(404, "BUSINESS_ERROR", "resource not found");
    const eligible = finding.item.checkStatus === "NOT_EVALUATED"
        && finding.item.riskLevel === "YELLOW"
      || finding.item.checkStatus === "FAIL"
        && finding.item.riskLevel === "RED";
    if (!eligible) {
      throw new ApiError(
        422,
        "BUSINESS_ERROR",
        "review finding status and risk are not acknowledgement-eligible",
      );
    }

    const existing = this.acknowledgementsByResult.get(checkResultId);
    if (existing) {
      if (priorResultId === checkResultId) return existing;
      throw new ApiError(409, "BUSINESS_ERROR", "review finding is already acknowledged");
    }
    const acknowledgement: FindingAcknowledgement = {
      id: checkResultId.replace(/^00000000/, "10000000"),
      checkResultId,
      acknowledgedBy: "mock-auditor",
      acknowledgedByDisplayName: "模拟审核员",
      acknowledgedAt: this.operationClock().toISOString(),
      requestId: `mock-request-${checkResultId}`,
      traceId: `mock-trace-${checkResultId}`,
      ruleVersion: finding.item.ruleVersion,
    };
    this.resultByIdempotencyKey.set(options.idempotencyKey, checkResultId);
    this.acknowledgementsByResult.set(checkResultId, acknowledgement);
    return acknowledgement;
  }
}

const mockEnabled =
  import.meta.env.DEV && import.meta.env.VITE_CONTRACT_DATA_SOURCE === "mock";

export const contractDataSourceMode: "mock" | "http" = mockEnabled ? "mock" : "http";
export const contractDataSource: ContractDataSource = mockEnabled
  ? new MockContractDataSource()
  : new HttpContractDataSource();
