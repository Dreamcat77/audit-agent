import type {
  ContractAuditTaskDataSource,
  ContractAuditTaskItem,
  ContractAuditTaskRiskFilter,
} from "../contract/data/contractAuditTaskDataSource.ts";

export interface PersistedRiskAssessmentItem {
  task: ContractAuditTaskItem;
  coverage: { label: string; unassessed: boolean };
}

export interface PersistedRiskAssessment {
  scopeLabel: string;
  reviewRequiredCount: number;
  materialInsufficientCount: number;
  items: PersistedRiskAssessmentItem[];
}

async function loadFilter(
  dataSource: ContractAuditTaskDataSource,
  risk: ContractAuditTaskRiskFilter,
  signal: AbortSignal,
): Promise<ContractAuditTaskItem[]> {
  const first = await dataSource.list({ page: 1, size: 100, risk, status: "COMPLETED", signal });
  const items = [...first.items];
  for (let page = 2; page <= first.totalPages; page += 1) {
    const next = await dataSource.list({ page, size: 100, risk, status: "COMPLETED", signal });
    items.push(...next.items);
  }
  return items;
}

function coverage(task: ContractAuditTaskItem): PersistedRiskAssessmentItem["coverage"] {
  const progress = task.reviewProgress;
  const runComplete = task.reviewRun?.status === "COMPLETED";
  if (progress.total <= 0) return { label: "尚无可计算检查项", unassessed: true };
  return {
    label: `已覆盖 ${progress.current}/${progress.total} 项`,
    unassessed: !progress.complete || !runComplete,
  };
}

export async function loadPersistedRiskAssessment(
  dataSource: ContractAuditTaskDataSource,
  signal: AbortSignal,
): Promise<PersistedRiskAssessment> {
  const [reviewItems, insufficientItems] = await Promise.all([
    loadFilter(dataSource, "REVIEW", signal),
    loadFilter(dataSource, "INSUFFICIENT", signal),
  ]);
  const byTask = new Map<string, ContractAuditTaskItem>();
  for (const task of [...reviewItems, ...insufficientItems]) {
    const previous = byTask.get(task.taskId);
    byTask.set(task.taskId, previous ? {
      ...previous,
      risk: {
        ...previous.risk,
        riskCount: Math.max(previous.risk.riskCount, task.risk.riskCount),
        materialInsufficient: previous.risk.materialInsufficient || task.risk.materialInsufficient,
      },
    } : task);
  }
  const reviewIds = new Set(reviewItems.map((task) => task.taskId));
  const insufficientIds = new Set(insufficientItems.map((task) => task.taskId));
  return {
    scopeLabel: "当前账号已完成的最新合同审核结果",
    reviewRequiredCount: reviewIds.size,
    materialInsufficientCount: insufficientIds.size,
    items: [...byTask.values()].map((task) => ({ task, coverage: coverage(task) })),
  };
}
