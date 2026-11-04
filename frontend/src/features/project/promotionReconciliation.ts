import type { ProjectDataSource } from "./data/projectDataSource";
import { createOperationKey } from "../../api/operationKey.ts";
import { createRetainedOperation, type RetainedOperation } from "./projectOperation.ts";
import type { CandidatePromotionRequest } from "./candidateConfirmation";
import type { CandidateDetail, ProjectDetail } from "./types";
import { provenPromotedContractId } from "./projectDetailViewModel.ts";

export interface PromotionResponse { contractId: string; status: string; versionNo: number }
export type PromotionReconciliationOutcome = "PROMOTED_OR_REPLAYED" | "ALREADY_PROMOTED";

export type PromotionReconciliationSource = Pick<ProjectDataSource, "getProject" | "getCandidate" | "promote">;

export async function promoteAndReconcile(
  dataSource: PromotionReconciliationSource,
  projectId: string,
  candidateId: string,
  payload: CandidatePromotionRequest,
  idempotencyKey: string,
  operation?: Pick<RetainedOperation, "clear">,
): Promise<{ outcome: PromotionReconciliationOutcome; contractId: string;
  project: ProjectDetail; candidate: CandidateDetail }> {
  let conflict: unknown = null;
  try {
    await dataSource.promote(projectId, candidateId, payload, { idempotencyKey });
  } catch (error) {
    const status = (error as { status?: number })?.status;
    if (status !== 409) throw error;
    conflict = error;
  }
  const [project, candidate] = await Promise.all([
    dataSource.getProject(projectId), dataSource.getCandidate(projectId, candidateId),
  ]);
  const contractId = provenPromotedContractId(project, candidate);
  if (!contractId || candidate.candidate.promotedConfirmationId !== payload.confirmationId) {
    if (conflict) throw conflict;
    throw new Error("promotion durable state is not proven");
  }
  operation?.clear();
  return { outcome: conflict ? "ALREADY_PROMOTED" : "PROMOTED_OR_REPLAYED",
    contractId, project, candidate };
}

export function createProjectOperationKeys(createKey: () => string = createOperationKey) {
  return {
    metadata: createRetainedOperation(createKey),
    candidateConfirmation: createRetainedOperation(createKey),
    promotion: createRetainedOperation(createKey),
    f02: createRetainedOperation(createKey),
    acknowledgement: createRetainedOperation(createKey),
  };
}
