import type {
  ContractAuditTaskItem,
  ContractAuditTaskSummary,
} from "../contract/data/contractAuditTaskDataSource.ts";

export interface WorkbenchTaskRow {
  task: ContractAuditTaskItem;
  taskId: string;
  contractName: string;
  projectName: string | null;
  statusLabel: string;
  progress: string;
  occurredAt: string;
}

function statusLabel(task: ContractAuditTaskItem): string {
  if (task.taskStatus === "PENDING") return "待审核";
  if (task.taskStatus === "PROCESSING" || task.taskStatus === "PARSING" || task.taskStatus === "REVIEWING") return "审核中";
  if (task.taskStatus === "FAILED") return "审核未完成";
  if (task.taskStatus === "NEEDS_MANUAL_REVIEW") return "待人工复核";
  if (task.reviewProgress.pendingAcknowledgement) return "待确认";
  return task.reviewProgress.complete ? "审核完成" : "审核中";
}

function taskRow(task: ContractAuditTaskItem): WorkbenchTaskRow {
  return {
    task,
    taskId: task.taskId,
    contractName: task.contract.contractName,
    projectName: task.project?.projectName ?? null,
    statusLabel: statusLabel(task),
    progress: `${task.reviewProgress.current} / ${task.reviewProgress.total}`,
    occurredAt: task.finishedAt ?? task.startedAt ?? task.createdAt,
  };
}

function needsAttention(task: ContractAuditTaskItem): boolean {
  return task.risk.materialInsufficient
    || task.risk.riskCount > 0
    || task.reviewProgress.pendingAcknowledgement;
}

function remainsPending(task: ContractAuditTaskItem): boolean {
  return task.taskStatus === "PENDING"
    || task.taskStatus === "PROCESSING"
    || task.taskStatus === "PARSING"
    || task.taskStatus === "REVIEWING"
    || task.taskStatus === "NEEDS_MANUAL_REVIEW"
    || task.taskStatus === "FAILED"
    || !task.reviewProgress.complete
    || task.reviewProgress.pendingAcknowledgement;
}

export function workbenchPresentation(
  summary: ContractAuditTaskSummary | null,
  recentTasks: readonly ContractAuditTaskItem[],
  todayTotal: number | null,
) {
  const attentionTask = recentTasks.find(needsAttention);
  return {
    stats: [
      { key: "TOTAL", label: "合同内审任务", value: summary?.total ?? null },
      { key: "PENDING", label: "待审核 / 审核中", value: summary?.pendingOrProcessing ?? null },
      { key: "COMPLETED", label: "审核完成", value: summary?.completed ?? null },
      { key: "REVIEW", label: "需复核", value: summary?.needsReview ?? null },
    ] as const,
    attention: attentionTask ? taskRow(attentionTask) : null,
    pending: recentTasks.filter(remainsPending).map(taskRow),
    recent: recentTasks.map(taskRow),
    today: { createdTotal: todayTotal },
  };
}
