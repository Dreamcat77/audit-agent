import type { ContractFactBatchStatus, ContractFactExtractionProjection, ContractFactScope,
  DocumentRole } from "./types.ts";

export interface FactExtractionScopeView {
  scope: ContractFactScope;
  label: string;
  statusLabel: string;
}

export interface FactExtractionView {
  statusLabel: string;
  countLabel: string;
  scopes: FactExtractionScopeView[];
}

const LABELS: Record<ContractFactScope, string> = {
  CORE: "核心事实", LINE_ITEMS: "金额明细", AWARD: "成交事实",
};

function expectedScopes(role: DocumentRole): ContractFactScope[] {
  if (role === "CONTRACT_BODY") return ["CORE", "LINE_ITEMS"];
  if (role === "AWARD_NOTICE") return ["AWARD"];
  return [];
}

function scopeStatus(status: ContractFactBatchStatus | null): string {
  switch (status) {
    case "EMPTY": return "未提取到有依据候选";
    case "PARTIAL": return "已提取部分候选";
    case "COMPLETE": return "本批候选已验证";
    case "FAILED": return "本次未提取到可确认内容";
    default: return "未形成可验证结果";
  }
}

export function factExtractionPresentation(role: DocumentRole,
  projection: ContractFactExtractionProjection | null | undefined): FactExtractionView | null {
  if (!projection?.attempt) return null;
  const attempt = projection.attempt;
  const latest = new Map<ContractFactScope, ContractFactBatchStatus>();
  for (const call of projection.modelCalls) {
    if (call.scope && call.batchStatus) latest.set(call.scope, call.batchStatus);
  }
  const statusLabel = attempt.status === "SUCCEEDED"
    ? attempt.acceptedCount === 0 ? "处理完成，未找到可确认的信息" : "处理完成，已整理出可核实信息"
    : attempt.status === "FAILED"
      ? "提取未完成，已保留可用的材料结果"
      : attempt.status === "OUTCOME_UNKNOWN"
        ? "提取处理已结束，未取得可确认的信息" : "正在整理材料信息";
  return {
    statusLabel,
    countLabel: `自动采用 ${attempt.acceptedCount} 项 · 拒绝 ${attempt.rejectedCount} 项`,
    scopes: expectedScopes(role).map((scope) => ({
      scope, label: LABELS[scope], statusLabel: scopeStatus(latest.get(scope) ?? null),
    })),
  };
}
