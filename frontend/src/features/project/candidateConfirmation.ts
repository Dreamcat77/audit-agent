import type { ContractCandidate } from "./types";

export interface ConfirmedContractPayload {
  expectedVersion: number;
  contractNo: string;
  contractName: string;
  contractType: string;
  partyA: string;
  partyB: string;
  partyBCreditCode: string | null;
  contractAmount: number | null;
  amountInWords: string | null;
  lineItems: Array<{
    itemKey: string;
    itemName: string;
    basis?: "QUANTITY_PRICE" | "AMOUNT_ONLY";
    quantity: number | null;
    unitPrice: number | null;
    declaredAmount: number | null;
    sortNo: number;
  }>;
  awardNotice: {
    noticeNo: string;
    projectName: string;
    winningBidder: string;
    winningBidderCreditCode: string | null;
    winningAmount: number | null;
    winningDate: string;
    sourceRef: string | null;
  } | null;
}

export interface CandidateConfirmation {
  confirmationId: string;
  projectId: string;
  candidateId: string;
  baseCandidateVersion: number;
  resultCandidateVersion: number;
  sourceFileId: string;
  sourceParseResultId: string;
  sourceRoleDecisionId: string;
  sourceIdentityDecisionId: string;
  payloadFingerprint: string;
  payloadSchemaVersion: number;
  payload: Omit<ConfirmedContractPayload, "expectedVersion">;
  confirmedBy: string;
  confirmedAt: string;
  supersedesConfirmationId: string | null;
  replayed: boolean;
}

export interface CandidatePromotionRequest { expectedVersion: number; confirmationId: string }

export function confirmedPayloadFromConfirmation(value: CandidateConfirmation): ConfirmedContractPayload {
  return { ...value.payload, expectedVersion: value.resultCandidateVersion };
}

export interface PromotionValidationError {
  field: string;
  message: string;
}

export interface PromotionSubmissionResult {
  errors: PromotionValidationError[];
  result: string | null;
}

export function confirmedPayloadFromCandidate(candidate: ContractCandidate): ConfirmedContractPayload {
  const hasAward = candidate.awardAmount != null || Boolean(candidate.awardSupplier);
  return {
    expectedVersion: candidate.versionNo,
    contractNo: candidate.contractNo ?? "",
    contractName: candidate.contractName ?? "",
    contractType: candidate.contractType ?? "",
    partyA: candidate.partyA ?? "",
    partyB: candidate.partyB ?? "",
    partyBCreditCode: candidate.partyBCreditCode,
    contractAmount: candidate.contractAmount,
    amountInWords: candidate.amountInWords,
    lineItems: candidate.lineItems.map((line) => ({
      itemKey: line.itemKey ?? "", itemName: line.itemName ?? "", quantity: line.quantity,
      basis: line.basis ?? (line.quantity == null && line.unitPrice == null ? "AMOUNT_ONLY" : "QUANTITY_PRICE"),
      unitPrice: line.unitPrice, declaredAmount: line.declaredAmount, sortNo: line.sortNo,
    })),
    awardNotice: hasAward ? {
      noticeNo: "", projectName: "", winningBidder: candidate.awardSupplier ?? "",
      winningBidderCreditCode: candidate.awardSupplierCreditCode,
      winningAmount: candidate.awardAmount, winningDate: "", sourceRef: null,
    } : null,
  };
}

export function setConfirmedLineItemBasis(
  line: ConfirmedContractPayload["lineItems"][number],
  basis: "QUANTITY_PRICE" | "AMOUNT_ONLY",
): ConfirmedContractPayload["lineItems"][number] {
  return basis === "AMOUNT_ONLY"
    ? { ...line, basis, quantity: null, unitPrice: null }
    : { ...line, basis };
}

export function addAmountOnlyLineItem(
  payload: ConfirmedContractPayload,
): ConfirmedContractPayload {
  const sortNo = payload.lineItems.reduce((maximum, line) => Math.max(maximum, line.sortNo), 0) + 1;
  return { ...payload, lineItems: [...payload.lineItems, {
    itemKey: "", itemName: "", basis: "AMOUNT_ONLY", quantity: null,
    unitPrice: null, declaredAmount: null, sortNo,
  }] };
}

export function removeConfirmedLineItem(
  payload: ConfirmedContractPayload,
  index: number,
): ConfirmedContractPayload {
  return { ...payload, lineItems: payload.lineItems.filter((_, itemIndex) => itemIndex !== index) };
}

export function addBlankAwardNotice(
  payload: ConfirmedContractPayload,
): ConfirmedContractPayload {
  if (payload.awardNotice) return payload;
  return { ...payload, awardNotice: {
    noticeNo: "", projectName: "", winningBidder: "", winningBidderCreditCode: null,
    winningAmount: null, winningDate: "", sourceRef: null,
  } };
}

export function validateConfirmedContractPayload(
  payload: ConfirmedContractPayload,
): PromotionValidationError[] {
  const errors: PromotionValidationError[] = [];
  requiredText(errors, "contractNo", payload.contractNo, "请填写合同编号");
  requiredText(errors, "contractName", payload.contractName, "请填写合同名称");
  requiredText(errors, "contractType", payload.contractType, "请填写合同类型");
  requiredText(errors, "partyA", payload.partyA, "请填写甲方");
  requiredText(errors, "partyB", payload.partyB, "请填写乙方");
  requiredNumber(errors, "contractAmount", payload.contractAmount, "请填写合同金额");

  payload.lineItems.forEach((line, index) => {
    const position = index + 1;
    requiredText(errors, `lineItems[${index}].itemKey`, line.itemKey,
      `请填写第 ${position} 条合同明细的项目编号`);
    requiredText(errors, `lineItems[${index}].itemName`, line.itemName,
      `请填写第 ${position} 条合同明细的项目名称`);
    if (line.basis === "AMOUNT_ONLY") {
      if (line.quantity != null || line.unitPrice != null) {
        errors.push({ field: `lineItems[${index}].basis`,
          message: `第 ${position} 条合同明细按申报金额确认时，数量和单价必须留空` });
      }
      requiredNumber(errors, `lineItems[${index}].declaredAmount`, line.declaredAmount,
        `请填写第 ${position} 条合同明细的申报金额`);
      return;
    }
    requiredNumber(errors, `lineItems[${index}].quantity`, line.quantity,
      `请填写第 ${position} 条合同明细的数量`);
    requiredNumber(errors, `lineItems[${index}].unitPrice`, line.unitPrice,
      `请填写第 ${position} 条合同明细的单价`);
    requiredNumber(errors, `lineItems[${index}].declaredAmount`, line.declaredAmount,
      `请填写第 ${position} 条合同明细的申报金额`);
  });

  if (payload.awardNotice) {
    requiredText(errors, "awardNotice.noticeNo", payload.awardNotice.noticeNo,
      "请填写中标通知书编号");
    requiredText(errors, "awardNotice.projectName", payload.awardNotice.projectName,
      "请填写中标项目名称");
    requiredText(errors, "awardNotice.winningBidder", payload.awardNotice.winningBidder,
      "请填写中标供应商");
    requiredNumber(errors, "awardNotice.winningAmount", payload.awardNotice.winningAmount,
      "请填写中标金额");
    requiredText(errors, "awardNotice.winningDate", payload.awardNotice.winningDate,
      "请选择中标日期");
  }
  return errors;
}

export async function submitConfirmedContract(
  payload: ConfirmedContractPayload,
  promote: (confirmed: ConfirmedContractPayload) => Promise<string | null>,
): Promise<PromotionSubmissionResult> {
  const errors = validateConfirmedContractPayload(payload);
  if (errors.length > 0) return { errors, result: null };
  return { errors: [], result: await promote(payload) };
}

function requiredText(errors: PromotionValidationError[], field: string,
  value: string | null | undefined, message: string) {
  if (!value?.trim()) errors.push({ field, message });
}

function requiredNumber(errors: PromotionValidationError[], field: string,
  value: number | null | undefined, message: string) {
  if (value == null || !Number.isFinite(value)) errors.push({ field, message });
}
