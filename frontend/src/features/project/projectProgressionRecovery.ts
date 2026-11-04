import { createOperationKey } from "../../api/operationKey.ts";
import type { ProgressionRecoveryRequest, ProjectDataSource } from "./data/projectDataSource.ts";
import type { ProjectFile, ProjectProgressionAction } from "./types.ts";

export type RecoveryAction = "RESUME_ROLE_ASSESSMENT" | "RESUME_IDENTITY_ASSESSMENT"
  | "RESUME_CANDIDATE_MATERIALIZATION" | "RESUME_FACT_EXTRACTION";
export type RecoveryBlocker = ProgressionRecoveryRequest["expectedBlocker"];

export interface ProjectProgressionRecoveryOperation {
  actorId: string;
  projectId: string;
  fileId: string;
  action: RecoveryAction;
  expectedBlocker: RecoveryBlocker;
  expectedRecoveryInputFingerprint: string;
  idempotencyKey: string;
}

export interface ProjectProgressionRecoveryPersistence {
  load(actorId: string, projectId: string, fileId: string,
    action: RecoveryAction): ProjectProgressionRecoveryOperation | null;
  save(operation: ProjectProgressionRecoveryOperation): void;
  remove(operation: ProjectProgressionRecoveryOperation): void;
}

type KeyStorage = Pick<Storage, "getItem" | "setItem" | "removeItem">;
const ACTIONS: readonly RecoveryAction[] = [
  "RESUME_ROLE_ASSESSMENT", "RESUME_IDENTITY_ASSESSMENT", "RESUME_CANDIDATE_MATERIALIZATION",
  "RESUME_FACT_EXTRACTION",
];

export function createBrowserProgressionRecoveryPersistence(
  suppliedStorage?: KeyStorage): ProjectProgressionRecoveryPersistence {
  const fallback = new Map<string, string>();
  const storage = suppliedStorage ?? (typeof localStorage === "undefined" ? {
    getItem: (key: string) => fallback.get(key) ?? null,
    setItem: (key: string, value: string) => { fallback.set(key, value); },
    removeItem: (key: string) => { fallback.delete(key); },
  } : localStorage);
  return {
    load(actorId, projectId, fileId, action) {
      const raw = storage.getItem(storageKey(actorId, projectId, fileId, action));
      if (raw == null) return null;
      const parsed: unknown = JSON.parse(raw);
      if (!validOperation(parsed, actorId, projectId, fileId, action)) {
        throw new Error("stored progression recovery identity is invalid; do not replace an uncertain command");
      }
      return parsed;
    },
    save(operation) {
      storage.setItem(storageKey(operation.actorId, operation.projectId,
        operation.fileId, operation.action), JSON.stringify(operation));
    },
    remove(operation) {
      storage.removeItem(storageKey(operation.actorId, operation.projectId,
        operation.fileId, operation.action));
    },
  };
}

export function findRetainedProgressionRecovery(persistence: ProjectProgressionRecoveryPersistence,
  actorId: string, projectId: string, fileId: string): ProjectProgressionRecoveryOperation | null {
  for (const action of ACTIONS) {
    const operation = persistence.load(actorId, projectId, fileId, action);
    if (operation) return operation;
  }
  return null;
}

export function loadOrCreateProgressionRecovery(persistence: ProjectProgressionRecoveryPersistence,
  actorId: string, file: ProjectFile,
  createKey: () => string = createOperationKey): ProjectProgressionRecoveryOperation {
  const retained = findRetainedProgressionRecovery(persistence, actorId, file.projectId, file.id);
  if (retained) return retained;
  const action = file.progression.availableActions.find(isRecoveryAction);
  const fingerprint = file.progression.recoveryInputFingerprint;
  const blocker = file.progression.blocker;
  if (!action || !isRecoveryBlocker(blocker) || fingerprint == null
      || !/^[0-9a-f]{64}$/.test(fingerprint)) {
    throw new Error("project file does not expose a valid server-authorized recovery input");
  }
  const operation: ProjectProgressionRecoveryOperation = {
    actorId, projectId: file.projectId, fileId: file.id, action,
    expectedBlocker: blocker, expectedRecoveryInputFingerprint: fingerprint,
    idempotencyKey: createKey(),
  };
  persistence.save(operation);
  return operation;
}

export async function resumeProjectFileProgression(dataSource: ProjectDataSource,
  persistence: ProjectProgressionRecoveryPersistence, actorId: string, file: ProjectFile,
  createKey: () => string = createOperationKey): Promise<ProjectFile> {
  const operation = loadOrCreateProgressionRecovery(persistence, actorId, file, createKey);
  try {
    const result = await dataSource.resumeProgression(operation.projectId, operation.fileId, {
      expectedBlocker: operation.expectedBlocker,
      expectedRecoveryInputFingerprint: operation.expectedRecoveryInputFingerprint,
    }, { idempotencyKey: operation.idempotencyKey });
    persistence.remove(operation);
    return result;
  } catch (error) {
    if (isProvenNoEffectRejection(error)) persistence.remove(operation);
    throw error;
  }
}

function isRecoveryAction(value: ProjectProgressionAction): value is RecoveryAction {
  return ACTIONS.includes(value as RecoveryAction);
}

function isRecoveryBlocker(value: string | null): value is RecoveryBlocker {
  return value === "ROLE_ASSESSMENT_PENDING" || value === "IDENTITY_ASSESSMENT_PENDING"
    || value === "IDENTITY_ASSESSMENT_STALE" || value === "CANDIDATE_MATERIALIZATION_PENDING"
    || value === "FACT_EXTRACTION_PENDING";
}

function isProvenNoEffectRejection(error: unknown): boolean {
  if (!error || typeof error !== "object") return false;
  const response = error as { status?: unknown; data?: unknown };
  if (response.status !== 409 || !response.data || typeof response.data !== "object") return false;
  const classification = (response.data as { classification?: unknown }).classification;
  return classification === "RECOVERY_INPUT_CHANGED"
    || classification === "RECOVERY_ACTION_NOT_ALLOWED"
    || classification === "FACT_SEGMENT_BUDGET_EXCEEDED"
    || classification === "REQUEST_REJECTED";
}

function storageKey(actorId: string, projectId: string, fileId: string,
  action: RecoveryAction): string {
  return `audit:progression-recovery:${encodeURIComponent(actorId)}:${encodeURIComponent(projectId)}`
    + `:${encodeURIComponent(fileId)}:${action}`;
}

function validOperation(value: unknown, actorId: string, projectId: string,
  fileId: string, action: RecoveryAction): value is ProjectProgressionRecoveryOperation {
  if (!value || typeof value !== "object") return false;
  const candidate = value as Partial<ProjectProgressionRecoveryOperation>;
  return candidate.actorId === actorId && candidate.projectId === projectId
    && candidate.fileId === fileId && candidate.action === action
    && isRecoveryBlocker(candidate.expectedBlocker ?? null)
    && typeof candidate.expectedRecoveryInputFingerprint === "string"
    && /^[0-9a-f]{64}$/.test(candidate.expectedRecoveryInputFingerprint)
    && typeof candidate.idempotencyKey === "string"
    && /^[A-Za-z0-9._:-]{1,128}$/.test(candidate.idempotencyKey);
}
