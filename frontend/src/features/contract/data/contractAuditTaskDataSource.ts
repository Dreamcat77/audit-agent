import { request } from "../../../api/client";
import type { AuditTaskStatus, ReviewRiskSummary } from "../types";
import type { ContractSummary } from "./contractCatalogDataSource";

export interface ContractAuditTaskProject {
  projectId: string;
  projectName: string | null;
  projectNo: string | null;
}

export interface ContractAuditTaskProgress {
  current: number;
  total: number;
  complete: boolean;
  pendingAcknowledgement: boolean;
}

export interface ContractAuditTaskRisk {
  riskCount: number;
  materialInsufficient: boolean;
  riskSummary: ReviewRiskSummary;
}

export interface ContractAuditTaskItem {
  taskId: string;
  contract: ContractSummary;
  project: ContractAuditTaskProject | null;
  taskStatus: AuditTaskStatus;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  reviewProgress: ContractAuditTaskProgress;
  risk: ContractAuditTaskRisk;
  reviewRun?: ContractAuditTaskNavigation;
}

export interface ContractAuditTaskNavigation {
  kind: "INTEGRATED" | "INDEPENDENT_F02" | "UNAVAILABLE";
  projectId: string | null;
  runId: string | null;
  status: "PENDING" | "RUNNING" | "COMPLETED" | "PARTIALLY_COMPLETED" | "FAILED" | "UNKNOWN" | null;
}

export interface ContractAuditTaskPage {
  items: ContractAuditTaskItem[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

export interface ContractAuditTaskDataSource {
  list(input: ContractAuditTaskListInput): Promise<ContractAuditTaskPage>;
  summary(input?: { signal?: AbortSignal; includeHistory?: boolean }): Promise<ContractAuditTaskSummary>;
}

export type ContractAuditTaskRiskFilter = "NORMAL" | "REVIEW" | "INSUFFICIENT";
export type ContractAuditTaskStatusFilter = "PENDING" | "PROCESSING" | "COMPLETED" | "FAILED" | "NEEDS_REVIEW";

export interface ContractAuditTaskListInput {
  page: number;
  size: number;
  keyword?: string;
  createdFrom?: string;
  createdTo?: string;
  risk?: ContractAuditTaskRiskFilter;
  status?: ContractAuditTaskStatusFilter;
  includeHistory?: boolean;
  signal?: AbortSignal;
}

export interface ContractAuditTaskSummary {
  total: number;
  pendingOrProcessing: number;
  completed: number;
  needsReview: number;
}

export class HttpContractAuditTaskDataSource implements ContractAuditTaskDataSource {
  list(input: ContractAuditTaskListInput): Promise<ContractAuditTaskPage> {
    const params: Record<string, string> = {
      page: String(input.page), size: String(input.size),
    };
    if (input.includeHistory) params.includeHistory = "true";
    for (const [key, value] of Object.entries({
      keyword: input.keyword?.trim() || undefined,
      createdFrom: input.createdFrom,
      createdTo: input.createdTo,
      risk: input.risk,
      status: input.status,
    })) {
      if (value) params[key] = value;
    }
    return request<ContractAuditTaskPage>("/api/v1/contract-audit-tasks", {
      params,
      signal: input.signal,
    });
  }

  summary(input: { signal?: AbortSignal; includeHistory?: boolean } = {}): Promise<ContractAuditTaskSummary> {
    return request<ContractAuditTaskSummary>("/api/v1/contract-audit-tasks/summary", {
      signal: input.signal,
      params: input.includeHistory ? { includeHistory: "true" } : undefined,
    });
  }
}

export const contractAuditTaskDataSource: ContractAuditTaskDataSource =
  new HttpContractAuditTaskDataSource();
