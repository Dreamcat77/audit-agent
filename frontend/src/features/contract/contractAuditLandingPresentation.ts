import type {
  ContractAuditTaskItem,
  ContractAuditTaskPage,
  ContractAuditTaskSummary,
} from "./data/contractAuditTaskDataSource";

export interface ContractAuditLandingTaskRow {
  task: ContractAuditTaskItem;
  taskId: string;
  project: ContractAuditTaskItem["project"];
  contract: ContractAuditTaskItem["contract"];
  contractNo: string;
  statusLabel: string;
  actionLabel: "开始审核" | "继续审核" | "查看并复核" | "查看详情";
  riskLabel: string;
}

function statusLabel(task: ContractAuditTaskItem): string {
  if (task.taskStatus === "PENDING") return "待审核";
  if (task.taskStatus === "PROCESSING" || task.taskStatus === "PARSING" || task.taskStatus === "REVIEWING") return "审核中";
  if (task.taskStatus === "FAILED") return "处理失败";
  if (task.taskStatus === "NEEDS_MANUAL_REVIEW") return "待人工复核";
  if (task.reviewProgress.pendingAcknowledgement) return "待复核";
  return task.reviewProgress.complete ? "审核完成" : "审核中";
}

function actionLabel(task: ContractAuditTaskItem): ContractAuditLandingTaskRow["actionLabel"] {
  if (task.taskStatus === "PENDING" || task.taskStatus === "FAILED") return "开始审核";
  if (task.taskStatus === "PROCESSING" || task.taskStatus === "PARSING" || task.taskStatus === "REVIEWING") return "继续审核";
  if (task.reviewProgress.pendingAcknowledgement || task.taskStatus === "NEEDS_MANUAL_REVIEW") return "查看并复核";
  return task.reviewProgress.complete ? "查看详情" : "继续审核";
}

function riskLabel(task: ContractAuditTaskItem): string {
  return task.risk.materialInsufficient ? "材料不足" : task.risk.riskSummary;
}

function taskRow(task: ContractAuditTaskItem): ContractAuditLandingTaskRow {
  return {
    task,
    taskId: task.taskId,
    project: task.project,
    contract: task.contract,
    contractNo: task.contract.contractNo,
    statusLabel: statusLabel(task),
    actionLabel: actionLabel(task),
    riskLabel: riskLabel(task),
  };
}

export function contractAuditLandingPresentation(
  summary: ContractAuditTaskSummary | null,
  attentionPage: ContractAuditTaskPage | null,
  completedPage: ContractAuditTaskPage | null,
  todayTotal: number | null,
) {
  const loadedTasks = attentionPage?.items ?? [];
  return {
    summary,
    attentionTotal: attentionPage?.total ?? null,
    completedTotal: completedPage?.total ?? null,
    todayCreatedTotal: todayTotal,
    loadedRiskSummary: {
      scopeLabel: "当前已加载关注任务",
      loadedTaskCount: attentionPage ? loadedTasks.length : null,
      riskCount: attentionPage ? loadedTasks.reduce((total, task) => total + task.risk.riskCount, 0) : null,
      materialInsufficientTaskCount: attentionPage ? loadedTasks.filter((task) => task.risk.materialInsufficient).length : null,
    },
    attention: (attentionPage?.items ?? []).map(taskRow),
    completed: (completedPage?.items ?? []).map(taskRow),
  };
}
