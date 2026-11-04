import type { CandidateDetail } from "./types";
import type { ProjectDataSource } from "./data/projectDataSource";
import {
  clearCandidateSupportOperation,
  loadCandidateSupportOperation,
  parseCandidateSupportAuthorityClassification,
  type CandidateSupportOperation,
  type CandidateSupportOperationPersistence,
} from "./candidateSupportAuthorityOperation.ts";

type Source = Pick<ProjectDataSource, "replaceCandidateSupportAuthority" | "getCandidate">;
const UNPROVEN = "材料关联结果尚未确认，已保留原操作，请重新加载后核对";
const STATE_CHANGED = "材料关联状态已变化，请重新加载并核对后保存";

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function exactCandidate(value: unknown, projectId: string, candidateId: string): value is CandidateDetail {
  return isObject(value) && isObject(value.candidate)
    && value.candidate.id === candidateId && value.candidate.projectId === projectId
    && Number.isSafeInteger(value.candidate.versionNo) && (value.candidate.versionNo as number) >= 0;
}

function exactSupportIds(value: unknown, expectedIds: readonly string[], supportVersion: number): boolean {
  if (!Array.isArray(value) || value.length !== expectedIds.length) return false;
  const ids: string[] = [];
  for (const row of value) {
    if (!isObject(row) || typeof row.sourceFileId !== "string" || !row.sourceFileId.trim()
        || row.sourceFileId.trim() !== row.sourceFileId || row.authorityVersion !== supportVersion) return false;
    ids.push(row.sourceFileId);
  }
  return new Set(ids).size === ids.length && JSON.stringify(ids.sort()) === JSON.stringify(expectedIds);
}

/** One explicit invocation sends at most one POST, always with the already persisted command. */
export async function replaceCandidateSupportAndReconcile(
  source: Source,
  persistence: CandidateSupportOperationPersistence,
  actorId: string,
  projectId: string,
  candidateId: string,
  operation: CandidateSupportOperation,
  signal?: AbortSignal,
): Promise<CandidateDetail> {
  const sent = loadCandidateSupportOperation(persistence, actorId, projectId, candidateId);
  if (!sent || sent.idempotencyKey !== operation.idempotencyKey
      || sent.expectedCandidateVersion !== operation.expectedCandidateVersion
      || JSON.stringify(sent.supportFileIds) !== JSON.stringify(operation.supportFileIds)) {
    throw new Error(UNPROVEN);
  }
  signal?.throwIfAborted();
  let result;
  try {
    result = await source.replaceCandidateSupportAuthority(projectId, candidateId,
      { expectedCandidateVersion: sent.expectedCandidateVersion, supportFileIds: [...sent.supportFileIds] },
      { idempotencyKey: sent.idempotencyKey, signal });
  } catch (error) {
    signal?.throwIfAborted();
    const classification = parseCandidateSupportAuthorityClassification(error);
    if (classification === "REQUEST_REJECTED") {
      clearCandidateSupportOperation(persistence, actorId, projectId, candidateId, sent);
      throw error;
    }
    if (classification === "RESULT_SUPERSEDED") {
      const current = await source.getCandidate(projectId, candidateId, signal);
      signal?.throwIfAborted();
      if (!exactCandidate(current, projectId, candidateId)
          || current.candidate.versionNo <= sent.expectedCandidateVersion + 1) throw new Error(UNPROVEN);
      clearCandidateSupportOperation(persistence, actorId, projectId, candidateId, sent);
      throw new Error(STATE_CHANGED);
    }
    // OUTCOME_UNCERTAIN, INTEGRITY_CONFLICT and every unclassified error retain the exact operation.
    throw error;
  }
  signal?.throwIfAborted();
  const mode = sent.supportFileIds.length ? "EXPLICIT_SUPPORT_AUTHORITY" : "PRIMARY_ONLY_COMPAT";
  if (!isObject(result) || result.candidateId !== candidateId
      || result.candidateVersion !== sent.expectedCandidateVersion + 1
      || !Number.isSafeInteger(result.supportAuthorityVersion) || result.supportAuthorityVersion < 1
      || result.authorityMode !== mode || typeof result.replayed !== "boolean"
      || !exactSupportIds(result.supportAssociations, sent.supportFileIds, result.supportAuthorityVersion)
      || !result.supportAssociations.every((row) => row.projectId === projectId && row.candidateId === candidateId)) {
    throw new Error(UNPROVEN);
  }
  const current = await source.getCandidate(projectId, candidateId, signal);
  signal?.throwIfAborted();
  if (!exactCandidate(current, projectId, candidateId)
      || current.candidate.versionNo !== result.candidateVersion
      || current.supportAuthorityVersionNo !== result.supportAuthorityVersion
      // The public Candidate Detail projects this metadata at the top level;
      // ContractCandidate itself hides the internal field with @JsonIgnore.
      || (current.candidate.supportAuthorityVersionNo !== undefined
          && current.candidate.supportAuthorityVersionNo !== result.supportAuthorityVersion)
      || current.authorityMode !== mode
      || !exactSupportIds(current.supportAuthorities, sent.supportFileIds, result.supportAuthorityVersion)) {
    throw new Error(UNPROVEN);
  }
  clearCandidateSupportOperation(persistence, actorId, projectId, candidateId, sent);
  return current;
}
