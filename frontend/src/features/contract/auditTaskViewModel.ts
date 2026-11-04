import type { ContractSummary } from "./data/contractCatalogDataSource";
import type {
  ContractAuditTaskProgress,
  ContractAuditTaskRisk,
  ContractAuditTaskNavigation,
} from "./data/contractAuditTaskDataSource";
import type { ReviewResult, RiskLevel } from "./types";

export type ContractAuditTaskSource = "SHOWCASE" | "DIRECT_F02";

interface ContractAuditTaskRowBase {
  rowId: string;
  source: ContractAuditTaskSource;
  taskId: string;
  contractDisplayId: string;
  contractApiId: string | null;
  contract: ContractSummary;
  reviewName: string;
  projectId: string | null;
  projectName: string | null;
  projectNo: string | null;
  reviewResult: ReviewResult | null;
  createdAt: string;
  updatedAt: string;
  isShowcase: boolean;
}

export interface ShowcaseAuditTaskRow extends ContractAuditTaskRowBase {
  source: "SHOWCASE";
  initiatedBy: string;
  createdAt: string;
  updatedAt: string;
  overallRisk: RiskLevel;
  reviewResult: ReviewResult;
  isShowcase: true;
}

export interface DirectF02AuditTaskRow extends ContractAuditTaskRowBase {
  source: "DIRECT_F02";
  contractApiId: string;
  initiatedBy: string;
  taskStatus: import("./types").AuditTaskStatus;
  reviewProgress: ContractAuditTaskProgress;
  risk: ContractAuditTaskRisk;
  reviewRun?: ContractAuditTaskNavigation;
  reviewResult: null;
  isShowcase: false;
}

export type ContractAuditTaskRow = ShowcaseAuditTaskRow | DirectF02AuditTaskRow;

export interface ContractAuditTaskStats {
  tasks: number | null;
  pending: number | null;
  completed: number | null;
  risks: number | null;
}
