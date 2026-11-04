import type { BudgetApprovalStep } from "./types";

export type BudgetWorkflowStage = "F01" | "F04";

export interface BudgetWorkflowStep {
  number: number;
  title: string;
  subtitle: string;
  statusLabel: string;
  state: "COMPLETE" | "ACTIVE" | "PENDING";
  navigable: false;
}

export function canProceedToApprovalProgram(status: string | null | undefined): boolean {
  return status === "REVIEWED";
}

export function budgetWorkflowSteps(stage: BudgetWorkflowStage): BudgetWorkflowStep[] {
  const inF04 = stage === "F04";
  return [
    { number: 1, title: "预算材料上传与解析", subtitle: "F01", statusLabel: inF04 ? "已完成" : "进行中", state: inF04 ? "COMPLETE" : "ACTIVE", navigable: false },
    { number: 2, title: "科目额度一致性校验", subtitle: "F01", statusLabel: inF04 ? "已完成" : "等待 F01 完成", state: inF04 ? "COMPLETE" : "PENDING", navigable: false },
    { number: 3, title: "审批程序规范性校验", subtitle: "F04", statusLabel: inF04 ? "进行中" : "等待 F01 完成", state: inF04 ? "ACTIVE" : "PENDING", navigable: false },
    { number: 4, title: "项目拆分风险检测", subtitle: "同科目、同供应商 30 天窗口风险分析", statusLabel: "已在预算校验页接入 F05", state: "PENDING", navigable: false },
    { number: 5, title: "生成预算审查报告", subtitle: "", statusLabel: "完成后续预算审查环节后生成报告", state: "PENDING", navigable: false },
  ];
}

export function addActualStep(current: string[], code: string): string[] {
  return !code || current.includes(code) ? [...current] : [...current, code];
}

export function removeActualStep(current: string[], index: number): string[] {
  return current.filter((_, currentIndex) => currentIndex !== index);
}

export function moveActualStep(current: string[], index: number, direction: -1 | 1): string[] {
  const target = index + direction;
  if (index < 0 || index >= current.length || target < 0 || target >= current.length) return [...current];
  const moved = [...current];
  [moved[index], moved[target]] = [moved[target]!, moved[index]!];
  return moved;
}

export function validationResultPresentation(result: {
  passed: boolean;
  missingProcedureSteps: BudgetApprovalStep[];
  procedureOrderValid: boolean;
  authorityMatched: boolean;
}): { title: string; detail: string; tone: "PASS" | "RISK" } {
  if (result.passed) {
    return { title: "审批程序校验完成", detail: "程序符合当前规则", tone: "PASS" };
  }
  if (result.missingProcedureSteps.length > 0) {
    return {
      title: "发现审批程序缺失",
      detail: `缺少审批环节：${result.missingProcedureSteps.map((step) => step.displayName).join("、")}`,
      tone: "RISK",
    };
  }
  if (!result.procedureOrderValid) {
    return { title: "审批程序顺序需关注", detail: "审批环节顺序不符合当前规则", tone: "RISK" };
  }
  return { title: "审批权限层级不符合当前规则", detail: "请核对实际审批权限层级", tone: "RISK" };
}
