import { ApiError } from "../../api/client.ts";
import { createOperationKey } from "../../api/operationKey.ts";

export type CandidateSupportClassification = "OUTCOME_UNCERTAIN" | "REQUEST_REJECTED"
  | "RESULT_SUPERSEDED" | "INTEGRITY_CONFLICT";

/** This endpoint alone owns these classifications; status and message text never supply one. */
export function parseCandidateSupportAuthorityClassification(error: unknown): CandidateSupportClassification | null {
  if (!(error instanceof ApiError) || error.data === null || typeof error.data !== "object"
      || Array.isArray(error.data)) return null;
  const data = error.data as Record<string, unknown>;
  switch (data.classification) {
    case "OUTCOME_UNCERTAIN":
    case "REQUEST_REJECTED":
    case "RESULT_SUPERSEDED":
    case "INTEGRITY_CONFLICT":
      return data.classification;
    default:
      return null;
  }
}

export interface CandidateSupportOperation {
  readonly idempotencyKey: string;
  readonly expectedCandidateVersion: number;
  readonly supportFileIds: readonly string[];
}

export interface CandidateSupportOperationPersistence {
  readonly storage: Storage;
  readonly createKey: () => string;
}

const PREFIX = "audit-agent:candidate-support:";
const SAFE_KEY = /^[A-Za-z0-9._:-]{1,128}$/;
const INVALID_OPERATION = "材料关联操作状态无法读取，请重新加载后核对";

function required(value: string): string {
  if (typeof value !== "string" || !value.trim()) throw new Error(INVALID_OPERATION);
  return value.trim();
}

function storageKey(actorId: string, projectId: string, candidateId: string): string {
  return `${PREFIX}${[actorId, projectId, candidateId].map((id) => encodeURIComponent(required(id))).join(":")}:operation`;
}

function commandIdentity(expectedCandidateVersion: number, supportFileIds: readonly string[]): string {
  // Leave room for the exact expected+1 result fence in JavaScript's safe integer range.
  if (!Number.isSafeInteger(expectedCandidateVersion) || expectedCandidateVersion < 0
      || expectedCandidateVersion >= Number.MAX_SAFE_INTEGER || !Array.isArray(supportFileIds)) {
    throw new Error(INVALID_OPERATION);
  }
  return JSON.stringify([expectedCandidateVersion, [...new Set(supportFileIds.map(required))].sort()]);
}

function readOperation(storage: Storage, key: string): CandidateSupportOperation | null {
  const raw = storage.getItem(key);
  if (raw === null) return null;
  try {
    const value = JSON.parse(raw);
    if (!value || typeof value !== "object" || Array.isArray(value)
        || Object.keys(value).sort().join(",") !== "commandIdentity,idempotencyKey"
        || typeof value.idempotencyKey !== "string" || !SAFE_KEY.test(value.idempotencyKey)
        || typeof value.commandIdentity !== "string") throw new Error(INVALID_OPERATION);
    const command = JSON.parse(value.commandIdentity);
    if (!Array.isArray(command) || command.length !== 2
        || commandIdentity(command[0], command[1]) !== value.commandIdentity) throw new Error(INVALID_OPERATION);
    return { idempotencyKey: value.idempotencyKey, expectedCandidateVersion: command[0], supportFileIds: command[1] };
  } catch {
    // Corruption is uncertainty, never permission to discard or replace an operation.
    throw new Error(INVALID_OPERATION);
  }
}

export function createSessionStorageCandidateSupportOperationPersistence(
  storage: Storage,
  keyFactory: () => string = createOperationKey,
): CandidateSupportOperationPersistence {
  return { storage, createKey: keyFactory };
}

/** Read-only rediscovery; it never creates an operation merely because the Candidate was reloaded. */
export function loadCandidateSupportOperation(
  persistence: CandidateSupportOperationPersistence,
  actorId: string,
  projectId: string,
  candidateId: string,
): CandidateSupportOperation | null {
  return readOperation(persistence.storage, storageKey(actorId, projectId, candidateId));
}

/** Call only for an explicit Save. An unresolved different command must be reconciled first. */
export function loadOrCreateCandidateSupportOperation(
  persistence: CandidateSupportOperationPersistence,
  actorId: string,
  projectId: string,
  candidateId: string,
  expectedCandidateVersion: number,
  supportFileIds: readonly string[],
): CandidateSupportOperation {
  const key = storageKey(actorId, projectId, candidateId);
  const identity = commandIdentity(expectedCandidateVersion, supportFileIds);
  const stored = readOperation(persistence.storage, key);
  if (stored) {
    if (commandIdentity(stored.expectedCandidateVersion, stored.supportFileIds) !== identity) {
      throw new Error("材料或候选合同版本已变化，请先核对上次关联操作");
    }
    return stored;
  }
  const idempotencyKey = persistence.createKey();
  if (typeof idempotencyKey !== "string" || !SAFE_KEY.test(idempotencyKey)) throw new Error(INVALID_OPERATION);
  persistence.storage.setItem(key, JSON.stringify({ idempotencyKey, commandIdentity: identity }));
  const [version, ids] = JSON.parse(identity);
  return { idempotencyKey, expectedCandidateVersion: version, supportFileIds: ids };
}

/** Compare the entire sent identity synchronously before deleting this session-storage entry. */
export function clearCandidateSupportOperation(
  persistence: CandidateSupportOperationPersistence,
  actorId: string,
  projectId: string,
  candidateId: string,
  sent: CandidateSupportOperation,
): void {
  const key = storageKey(actorId, projectId, candidateId);
  const stored = readOperation(persistence.storage, key);
  if (!stored || stored.idempotencyKey !== sent.idempotencyKey
      || stored.expectedCandidateVersion !== sent.expectedCandidateVersion
      || JSON.stringify(stored.supportFileIds) !== JSON.stringify(sent.supportFileIds)) {
    throw new Error("材料关联操作已变化，请重新加载后核对");
  }
  persistence.storage.removeItem(key);
}
