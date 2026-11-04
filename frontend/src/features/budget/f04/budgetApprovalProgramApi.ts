import { request } from "../../../api/client";
import type {
  BudgetApprovalRuleSet,
  BudgetApprovalValidation,
  BudgetApprovalValidationPage,
} from "./types";

const BASE_PATH = "/api/v1/budget/approval-program";

export function getBudgetApprovalRules(): Promise<BudgetApprovalRuleSet> {
  return request<BudgetApprovalRuleSet>(`${BASE_PATH}/rules`);
}

export function createBudgetApprovalValidation(command: {
  taskId: string;
  itemId: string;
  actualSteps: string[];
  idempotencyKey: string;
}): Promise<BudgetApprovalValidation> {
  return request<BudgetApprovalValidation>(`${BASE_PATH}/validations`, {
    method: "POST",
    headers: { "Idempotency-Key": command.idempotencyKey },
    body: {
      taskId: command.taskId,
      itemId: command.itemId,
      actualSteps: command.actualSteps,
    },
  });
}

export function listBudgetApprovalValidations(params: {
  taskId?: string;
  page?: number;
  size?: number;
} = {}): Promise<BudgetApprovalValidationPage> {
  const query: Record<string, string> = {};
  if (params.taskId) query.taskId = params.taskId;
  if (params.page !== undefined) query.page = String(params.page);
  if (params.size !== undefined) query.size = String(params.size);
  return request<BudgetApprovalValidationPage>(`${BASE_PATH}/validations`, { params: query });
}
