import { request } from "../../api/client";

export interface BudgetSemanticAnalysisItem {
  itemId: string;
  suggestedCategory: string;
  hint: string;
}

export interface BudgetSemanticAnalysis {
  taskId: string;
  operation: "BUDGET_SEMANTIC_ANALYSIS";
  requestId: string;
  available: boolean;
  items: BudgetSemanticAnalysisItem[];
  model: string;
  durationMs: number;
}

export function requestBudgetSemanticAnalysis(
  taskId: string,
  signal?: AbortSignal,
): Promise<BudgetSemanticAnalysis> {
  return request<BudgetSemanticAnalysis>(
    `/api/v1/budget/review-tasks/${encodeURIComponent(taskId)}/ai/semantic-analysis`,
    { method: "POST", signal },
  );
}

export function budgetSemanticAnalysisPresentation(
  result: BudgetSemanticAnalysis | null,
  loading: boolean,
  error: string | null,
) {
  const common = {
    sourceLabel: "AI语义来源" as const,
    deterministicSourceLabel: "确定性金额来源 · Java BigDecimal" as const,
    authorityNotice: "分类建议仅供文字语义辅助，不参与金额、数量×单价、差额或阈值结论" as const,
  };
  if (loading) return {
    ...common, statusLabel: "正在请求", detail: "等待服务返回完整响应",
    modelLabel: null, durationLabel: "耗时：—", verificationLabel: null,
    requestId: null, items: [] as BudgetSemanticAnalysisItem[],
  };
  if (error) return {
    ...common, statusLabel: "请求失败", detail: error,
    modelLabel: null, durationLabel: "耗时：—",
    verificationLabel: "NOT VERIFIED / BLOCKED_BY_RUNTIME_CONFIG",
    requestId: null, items: [] as BudgetSemanticAnalysisItem[],
  };
  if (!result) return {
    ...common, statusLabel: "尚未请求", detail: "预算材料形成持久化任务后可手动请求",
    modelLabel: null, durationLabel: "耗时：—", verificationLabel: null,
    requestId: null, items: [] as BudgetSemanticAnalysisItem[],
  };
  if (!result.available) return {
    ...common, statusLabel: "当前不可用", detail: "服务未返回可用的语义分析结果",
    modelLabel: null, durationLabel: `耗时：${result.durationMs} ms`,
    verificationLabel: "NOT VERIFIED / BLOCKED_BY_RUNTIME_CONFIG",
    requestId: result.requestId, items: [] as BudgetSemanticAnalysisItem[],
  };
  return {
    ...common, statusLabel: "分析完成", detail: null,
    modelLabel: null, durationLabel: `耗时：${result.durationMs} ms`,
    verificationLabel: null, requestId: result.requestId, items: result.items,
  };
}
