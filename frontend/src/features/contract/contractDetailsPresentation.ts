import {
  displayChineseUppercaseAmount,
  formatRmbAmount,
} from "./reviewFindingPresentation";
import type { Contract } from "./types";

export interface ContractDetailsAmountView {
  contractAmount: string;
  amountInWords: string;
  lineItems: Array<{
    id: string;
    unitPrice: string | null;
    declaredAmount: string;
  }>;
  winningAmount: string | null;
}

const TECHNICAL_CONTRACT_TYPES: Readonly<Record<string, string>> = {
  PURCHASE: "采购合同",
  PROCUREMENT: "采购合同",
  GOODS: "采购合同",
  SERVICE: "服务合同",
  CONSTRUCTION: "工程合同",
  LEASE: "租赁合同",
  FRAMEWORK: "框架协议",
};

export function contractTypePresentation(value: string | null): string {
  const normalized = value?.trim();
  if (!normalized) return "其他合同";
  const mapped = TECHNICAL_CONTRACT_TYPES[normalized.toUpperCase()];
  if (mapped) return mapped;
  return /[\u3400-\u9fff]/u.test(normalized) ? normalized : "其他合同";
}

/** Builds the exact, presentation-only monetary values consumed by contract details. */
export function createContractDetailsAmountView(contract: Contract): ContractDetailsAmountView {
  return {
    contractAmount: formatRmbAmount(contract.contractAmount),
    amountInWords: displayChineseUppercaseAmount(contract.amountInWords),
    lineItems: contract.lineItems.map((item) => ({
      id: item.id,
      unitPrice: item.unitPrice == null ? null : formatRmbAmount(item.unitPrice),
      declaredAmount: formatRmbAmount(item.declaredAmount),
    })),
    winningAmount: contract.awardNotice
      ? formatRmbAmount(contract.awardNotice.winningAmount)
      : null,
  };
}
