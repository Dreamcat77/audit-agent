import type { CandidateConfirmation, ConfirmedContractPayload } from "./candidateConfirmation";
import type { ProjectDataSource } from "./data/projectDataSource";
import { promoteAndReconcile } from "./promotionReconciliation.ts";

type Source = Pick<ProjectDataSource, "confirmCandidate" | "getLatestConfirmation" | "getCandidate"
  | "getProject" | "promote">;

export async function saveCandidateConfirmation(
  source: Pick<Source, "confirmCandidate">, projectId: string, candidateId: string,
  payload: ConfirmedContractPayload, confirmationKey: string,
): Promise<CandidateConfirmation> {
  try {
    return await source.confirmCandidate(projectId, candidateId, payload,
      { idempotencyKey: confirmationKey });
  } catch (cause) {
    if (!uncertainConfirmationOutcome(cause)) throw cause;
    return source.confirmCandidate(projectId, candidateId, payload,
      { idempotencyKey: confirmationKey });
  }
}

/** Latest/current facts are for rediscovery, not proof of an earlier command key. */
export async function discoverLatestCurrentConfirmation(
  source: Pick<Source, "getLatestConfirmation" | "getCandidate">,
  projectId: string, candidateId: string,
): Promise<CandidateConfirmation | null> {
  let latest: CandidateConfirmation;
  try { latest = await source.getLatestConfirmation(projectId, candidateId); }
  catch (cause) {
    if ((cause as { status?: number }).status === 404) return null;
    throw cause;
  }
  const candidate = await source.getCandidate(projectId, candidateId);
  const proposal = candidate.candidate, file = candidate.sourceFile;
  return proposal.promotionState === "DRAFT"
    && proposal.versionNo === latest.resultCandidateVersion
    && proposal.sourceContractFileId === latest.sourceFileId
    && proposal.sourceParseResultId === latest.sourceParseResultId
    && proposal.sourceRoleDecisionId === latest.sourceRoleDecisionId
    && proposal.sourceIdentityDecisionId === latest.sourceIdentityDecisionId
    && file.id === latest.sourceFileId
    && file.parseResultId === latest.sourceParseResultId
    && file.effectiveRole === "CONTRACT_BODY"
    && file.effectiveRoleSource === "HUMAN_CONFIRMATION"
    && file.roleDecisionId === latest.sourceRoleDecisionId
    && file.effectiveIdentityStatus === "SAME_PROJECT"
    && file.identityDecisionId === latest.sourceIdentityDecisionId
    && file.identityCurrent === true ? latest : null;
}

/** Confirmation and promotion are separate durable operations and never share an operation key. */
export async function confirmThenPromote(source: Source, projectId: string, candidateId: string,
  payload: ConfirmedContractPayload, confirmationKey: string,
  promotionKey: string | ((confirmation: CandidateConfirmation) => string),
  onSaved?: (value: CandidateConfirmation) => void,
) {
  const confirmation = await saveCandidateConfirmation(source, projectId, candidateId,
    payload, confirmationKey);
  onSaved?.(confirmation);
  const resolvedPromotionKey = typeof promotionKey === "function"
    ? promotionKey(confirmation) : promotionKey;
  if (confirmationKey === resolvedPromotionKey) {
    throw new Error("confirmation and promotion keys must differ");
  }
  const promoted = await retrySavedPromotion(source, projectId, candidateId,
    confirmation, resolvedPromotionKey);
  return { ...promoted, confirmation };
}

/** Called after refresh or a failed promotion: this path makes no confirmation POST. */
export function retrySavedPromotion(source: Pick<Source, "getProject" | "getCandidate" | "promote">,
  projectId: string, candidateId: string, confirmation: CandidateConfirmation, promotionKey: string) {
  return promoteAndReconcile(source, projectId, candidateId,
    { expectedVersion: confirmation.resultCandidateVersion, confirmationId: confirmation.confirmationId },
    promotionKey);
}

function uncertainConfirmationOutcome(cause: unknown): boolean {
  if (cause instanceof Error && cause.name === "AbortError") return false;
  const error = cause as { status?: number; code?: string } | null;
  return error?.code === "NETWORK_ERROR" || error?.code === "SERVER_ERROR"
    || error?.status === 0 || (typeof error?.status === "number" && error.status >= 500);
}
