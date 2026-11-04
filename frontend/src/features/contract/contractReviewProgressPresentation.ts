export type ContractReviewProgressTone = "WAITING" | "ACTIVE" | "ACKNOWLEDGEMENT" | "COMPLETE";

export interface ContractReviewProgressFacts {
  current: number;
  total: number;
  statusLabel: string;
  complete: boolean;
  pendingAcknowledgement: boolean;
}

export interface ContractReviewProgressPresentation extends ContractReviewProgressFacts {
  percentage: number;
  tone: ContractReviewProgressTone;
}

export function contractReviewProgressPresentation(
  facts: ContractReviewProgressFacts,
): ContractReviewProgressPresentation {
  const percentage = facts.total <= 0
    ? 0
    : Math.min(100, Math.max(0, Math.round((facts.current / facts.total) * 100)));
  const tone: ContractReviewProgressTone = facts.pendingAcknowledgement
    ? "ACKNOWLEDGEMENT"
    : facts.complete
      ? "COMPLETE"
      : facts.statusLabel === "审核中" || facts.statusLabel === "量化核对中"
        ? "ACTIVE"
        : "WAITING";
  return { ...facts, percentage, tone };
}
