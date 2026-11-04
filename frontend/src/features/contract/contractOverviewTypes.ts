import type { Contract, ReviewResult } from "./types";

export const CURRENT_F02_CONTRACT_IDS = [
  "CT-2024-0094",
  "CT-2024-0089",
] as const;

export type CurrentF02ContractId = (typeof CURRENT_F02_CONTRACT_IDS)[number];

export type OverviewLoadState = "LOADING" | "LOADED" | "ERROR";

export interface ContractOverviewItem {
  displayId: CurrentF02ContractId;
  apiId: string | null;
  contractState: OverviewLoadState;
  contract: Contract | null;
  reviewState: OverviewLoadState;
  reviewResult: ReviewResult | null;
}

export interface ContractOverviewStats {
  currentContracts: number | null;
  reviewedContracts: number | null;
  riskContracts: number | null;
  pendingContracts: number | null;
}
