import type { ContractTextAnalysisAttention, ContractTextAnalysisCategory } from "./types";

interface SessionStorageLike {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

const AI_SEMANTIC_ACKNOWLEDGEMENT_NAMESPACE = "audit-agent:ai-semantic-ack";

export function requiresAiSemanticAcknowledgement(attention: ContractTextAnalysisAttention): boolean {
  return attention === "REVIEW" || attention === "INSUFFICIENT";
}

export function aiSemanticAcknowledgementKey(
  requestId: string,
  category: ContractTextAnalysisCategory,
  hintIndex: number,
): string {
  return `${AI_SEMANTIC_ACKNOWLEDGEMENT_NAMESPACE}:${requestId}:${category}:${hintIndex}`;
}

export function isAiSemanticIssueAcknowledged(
  storage: SessionStorageLike,
  requestId: string,
  category: ContractTextAnalysisCategory,
  hintIndex: number,
): boolean {
  return storage.getItem(aiSemanticAcknowledgementKey(requestId, category, hintIndex)) === "acknowledged";
}

export function acknowledgeAiSemanticIssue(
  storage: SessionStorageLike,
  requestId: string,
  category: ContractTextAnalysisCategory,
  hintIndex: number,
): void {
  storage.setItem(aiSemanticAcknowledgementKey(requestId, category, hintIndex), "acknowledged");
}
