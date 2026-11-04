import { contractMockCases } from "../mock/contractMock";
import type {
  Contract,
  ContractTextAnalysis,
  FindingAcknowledgement,
  ReviewProgress,
  ReviewResult,
} from "../types";
import type {
  AcknowledgeFindingRequestOptions,
  ContractDataSource,
  ContractRequestOptions,
  ReviewRequestOptions,
} from "./contractDataSource";
import { MockContractDataSource } from "./contractDataSource";

function abortError(): Error {
  const error = new Error("请求已取消");
  error.name = "AbortError";
  return error;
}

function wait(milliseconds: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(abortError());
      return;
    }
    const timer = window.setTimeout(() => {
      signal?.removeEventListener("abort", onAbort);
      resolve();
    }, milliseconds);
    const onAbort = () => {
      window.clearTimeout(timer);
      reject(abortError());
    };
    signal?.addEventListener("abort", onAbort, { once: true });
  });
}

function requireShowcase(contractId: string) {
  const showcase = contractMockCases[contractId];
  if (!showcase) throw new Error("未找到该审核任务");
  return showcase;
}

export class ShowcaseContractDataSource implements ContractDataSource {
  private readonly reviewFacts: MockContractDataSource;

  constructor(operationClock: () => Date = () => new Date()) {
    this.reviewFacts = new MockContractDataSource(operationClock);
  }

  supportsReview(contractId: string): boolean {
    return Object.hasOwn(contractMockCases, contractId);
  }

  analyzeContractText(
    projectId: string,
    fileIds: readonly string[],
    options: ContractRequestOptions = {},
  ): Promise<ContractTextAnalysis> {
    return this.reviewFacts.analyzeContractText(projectId, fileIds, options);
  }

  async getContract(
    contractId: string,
    options: ContractRequestOptions = {},
  ): Promise<Contract> {
    await wait(120, options.signal);
    return requireShowcase(contractId).contract;
  }

  async getLatestReviewResult(
    contractId: string,
    options: ContractRequestOptions = {},
  ): Promise<ReviewResult> {
    await wait(80, options.signal);
    return requireShowcase(contractId).reviewResult;
  }

  async reviewContract(
    contractId: string,
    options: ReviewRequestOptions,
  ): Promise<ReviewResult> {
    await wait(620, options.signal);
    return requireShowcase(contractId).reviewResult;
  }

  getReviewProgress(
    contractId: string,
    taskId: string,
    options: ContractRequestOptions = {},
  ): Promise<ReviewProgress> {
    return this.reviewFacts.getReviewProgress(contractId, taskId, options);
  }

  acknowledgeFinding(
    checkResultId: string,
    options: AcknowledgeFindingRequestOptions,
  ): Promise<FindingAcknowledgement> {
    return this.reviewFacts.acknowledgeFinding(checkResultId, options);
  }
}

export const showcaseContractDataSource: ContractDataSource = new ShowcaseContractDataSource();
