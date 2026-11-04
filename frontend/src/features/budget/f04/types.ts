export interface BudgetApprovalStep {
  code: string;
  displayName: string;
}

export interface BudgetApprovalRuleItem {
  stepCode: string;
  stepName: string;
  thresholdLabel: string;
  minAmount: number | null;
  maxAmount: number | null;
  minInclusive: boolean;
  maxInclusive: boolean;
  stepOrder: number;
}

export interface BudgetApprovalRuleGroup {
  code: "PROCEDURE_REQUIREMENT" | "AUTHORITY_LEVEL" | string;
  rules: BudgetApprovalRuleItem[];
}

export interface BudgetApprovalRuleSet {
  ruleSetCode: string;
  ruleGroups: BudgetApprovalRuleGroup[];
}

export interface BudgetApprovalValidation {
  validationId: string;
  ruleSetCode: string;
  taskId: string;
  itemId: string;
  sourceRowNumber: number | null;
  declaredAmount: number;
  requiredProcedureSteps: BudgetApprovalStep[];
  requiredAuthorityStep: BudgetApprovalStep;
  actualSteps: BudgetApprovalStep[];
  missingProcedureSteps: BudgetApprovalStep[];
  procedureOrderValid: boolean;
  authorityMatched: boolean;
  passed: boolean;
  resultSummary: string;
  actualSource: "MANUAL_TEST" | string;
  createdAt: string;
}

export interface BudgetApprovalValidationPage {
  items: BudgetApprovalValidation[];
  page: number;
  size: number;
  total: number;
}
