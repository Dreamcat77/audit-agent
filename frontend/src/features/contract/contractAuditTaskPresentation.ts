import type { DirectF02AuditTaskRow } from "./auditTaskViewModel";
import type { ContractAuditTaskItem } from "./data/contractAuditTaskDataSource";

export function directF02AuditTaskRow(task: ContractAuditTaskItem): DirectF02AuditTaskRow {
  return {
    rowId: `direct-f02:${task.taskId}`,
    source: "DIRECT_F02",
    taskId: task.taskId,
    initiatedBy: "合同内审人员",
    contractDisplayId: task.contract.contractNo,
    contractApiId: task.contract.id,
    contract: task.contract,
    projectId: task.project?.projectId ?? null,
    projectName: task.project?.projectName ?? null,
    projectNo: task.project?.projectNo ?? null,
    reviewName: "合同量化一致性审核",
    taskStatus: task.taskStatus,
    reviewProgress: task.reviewProgress,
    risk: task.risk,
    reviewRun: task.reviewRun,
    createdAt: task.createdAt,
    updatedAt: task.finishedAt ?? task.startedAt ?? task.createdAt,
    reviewResult: null,
    isShowcase: false,
  };
}

export function directF02TaskStatusPresentation(row: DirectF02AuditTaskRow):
  "待审核" | "审核中" | "复核提示待知悉" | "量化复核待知悉" | "量化核对完成"
  | "审查执行完成" | "部分步骤完成" | "处理失败" | "结果待确认" | "归属待核" {
  if (row.reviewRun?.kind === "UNAVAILABLE") return "归属待核";
  if (row.reviewRun?.kind === "INTEGRATED") {
    switch (row.reviewRun.status) {
      case "FAILED": return "处理失败";
      case "UNKNOWN": return "结果待确认";
      case "PARTIALLY_COMPLETED": return "部分步骤完成";
      case "PENDING": return "待审核";
      case "RUNNING": return "审核中";
      case "COMPLETED": return row.reviewProgress.pendingAcknowledgement
        ? "复核提示待知悉" : "审查执行完成";
      default: return "归属待核";
    }
  }
  if (row.taskStatus === "PENDING" || row.taskStatus === "FAILED") return "待审核";
  if (row.taskStatus === "PROCESSING") return "审核中";
  if (row.reviewProgress.pendingAcknowledgement) return "量化复核待知悉";
  return row.reviewProgress.complete ? "量化核对完成" : "审核中";
}
