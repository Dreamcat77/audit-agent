import type {
  ContractAuditTaskItem,
  ContractAuditTaskSummary,
} from "../contract/data/contractAuditTaskDataSource.ts";

export interface ContractDashboardRow {
  task: ContractAuditTaskItem;
  taskId: string;
  projectName: string;
  contractName: string;
  supplier: string;
  statusLabel: string;
  riskLabel: string;
  updatedAt: string;
}

function statusLabel(task: ContractAuditTaskItem): string {
  if (task.taskStatus === "PENDING") return "待审核";
  if (task.taskStatus === "PROCESSING") return "审核中";
  if (task.taskStatus === "FAILED") return "失败";
  if (task.reviewProgress.pendingAcknowledgement) return "需复核";
  return task.reviewProgress.complete ? "审核完成" : "审核中";
}

function riskLabel(task: ContractAuditTaskItem): string {
  if (task.risk.materialInsufficient) return "材料不足";
  return task.risk.riskCount > 0 ? "建议复核" : "正常";
}

export function contractDashboardPresentation(
  summary: ContractAuditTaskSummary | null,
  tasks: readonly ContractAuditTaskItem[],
) {
  return {
    kpis: [
      { key: "TOTAL", label: "合同内审任务", value: summary?.total ?? null },
      { key: "PENDING", label: "待审核 / 审核中", value: summary?.pendingOrProcessing ?? null },
      { key: "COMPLETED", label: "审核完成", value: summary?.completed ?? null },
      { key: "REVIEW", label: "需复核", value: summary?.needsReview ?? null },
    ] as const,
    rows: tasks.slice(0, 5).map((task): ContractDashboardRow => ({
      task,
      taskId: task.taskId,
      projectName: task.project?.projectName || "未关联项目",
      contractName: task.contract.contractName,
      supplier: task.contract.partyB,
      statusLabel: statusLabel(task),
      riskLabel: riskLabel(task),
      updatedAt: task.finishedAt ?? task.startedAt ?? task.createdAt,
    })),
  };
}
