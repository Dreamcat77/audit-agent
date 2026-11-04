import { createOperationKey } from "../../api/operationKey.ts";

export interface ActiveReviewRunPointer {
  runId: string;
}

export interface ReviewRunOperation {
  idempotencyKey: string;
}

export interface ReviewRunCreateOperation extends ReviewRunOperation {
  expectedContractVersion: number;
  materialFileIds: readonly string[];
}

export interface ReviewRunRestartMarker {
  runId: string;
  stepId: string;
  reason: "RESTART_REQUIRED";
}

interface StoredCreateOperation extends ReviewRunOperation {
  commandIdentity: string;
}

export interface ReviewRunOperationPersistence {
  readonly storage: Storage;
  readonly createKey: () => string;
}

const PREFIX = "audit-agent:contract-review-run:";

function required(value: string, message: string): string {
  const normalized = value.trim();
  if (!normalized) throw new Error(message);
  return normalized;
}

function scope(actorId: string, projectId: string, contractId: string): string {
  return [
    required(actorId, "当前审核缺少用户信息"),
    required(projectId, "当前审核缺少项目信息"),
    required(contractId, "当前审核缺少合同信息"),
  ].map(encodeURIComponent).join(":");
}

function pointerKey(actorId: string, projectId: string, contractId: string): string {
  return `${PREFIX}${scope(actorId, projectId, contractId)}:pointer`;
}

function createKey(actorId: string, projectId: string, contractId: string): string {
  return `${PREFIX}${scope(actorId, projectId, contractId)}:create:operation`;
}

function restartMarkerKey(actorId: string, projectId: string, contractId: string): string {
  return `${PREFIX}${scope(actorId, projectId, contractId)}:restart-required`;
}

function stepKey(actorId: string, projectId: string, contractId: string, runId: string, stepId: string): string {
  return `${PREFIX}${scope(actorId, projectId, contractId)}:step:${encodeURIComponent(required(runId, "审核记录无效"))}:${encodeURIComponent(required(stepId, "审核环节无效"))}`;
}

function parseObject(raw: string, errorMessage: string): Record<string, unknown> {
  try {
    const value: unknown = JSON.parse(raw);
    if (value && typeof value === "object" && !Array.isArray(value)) {
      return value as Record<string, unknown>;
    }
  } catch {
    // Fall through to the one safe storage error below.
  }
  throw new Error(errorMessage);
}

function readOperation(storage: Storage, key: string): StoredCreateOperation | null {
  const raw = storage.getItem(key);
  if (raw === null) return null;
  const value = parseObject(raw, "审核操作状态无法读取，请刷新后重试");
  if (typeof value.idempotencyKey !== "string" || !value.idempotencyKey.trim()
      || typeof value.commandIdentity !== "string" || !value.commandIdentity) {
    throw new Error("审核操作状态已失效，请重新进入审核");
  }
  return { idempotencyKey: value.idempotencyKey, commandIdentity: value.commandIdentity };
}

function canonicalFileIds(fileIds: readonly string[]): readonly string[] {
  return [...new Set(fileIds.map((fileId) => required(fileId, "审核材料无效")))].sort();
}

function commandIdentity(expectedContractVersion: number, fileIds: readonly string[]): string {
  if (!Number.isSafeInteger(expectedContractVersion) || expectedContractVersion < 0) {
    throw new Error("合同版本无效");
  }
  const canonicalIds = canonicalFileIds(fileIds);
  if (canonicalIds.length === 0) throw new Error("请至少选择一份审核材料");
  return JSON.stringify([expectedContractVersion, canonicalIds]);
}

export function createSessionStorageReviewRunOperationPersistence(
  storage: Storage,
  keyFactory: () => string = createOperationKey,
): ReviewRunOperationPersistence {
  return { storage, createKey: keyFactory };
}

export function browserReviewRunOperationPersistence(): ReviewRunOperationPersistence {
  if (typeof window === "undefined") throw new Error("当前环境无法保存审核进度");
  return createSessionStorageReviewRunOperationPersistence(window.sessionStorage);
}

export function loadActiveReviewRun(
  storage: Storage,
  actorId: string,
  projectId: string,
  contractId: string,
): ActiveReviewRunPointer | null {
  const key = pointerKey(actorId, projectId, contractId);
  const raw = storage.getItem(key);
  if (raw === null) return null;
  let value: Record<string, unknown>;
  try {
    value = parseObject(raw, "上次审核记录无法读取，请重新选择审核材料");
  } catch {
    storage.removeItem(key);
    return null;
  }
  if (typeof value.runId !== "string" || !value.runId.trim()) {
    storage.removeItem(key);
    return null;
  }
  return { runId: value.runId };
}

export function saveActiveReviewRun(
  storage: Storage,
  actorId: string,
  projectId: string,
  contractId: string,
  runId: string,
): void {
  storage.setItem(pointerKey(actorId, projectId, contractId), JSON.stringify({
    runId: required(runId, "审核记录无效"),
  }));
}

export function clearActiveReviewRun(
  storage: Storage,
  actorId: string,
  projectId: string,
  contractId: string,
  expectedRunId: string,
): void {
  const current = loadActiveReviewRun(storage, actorId, projectId, contractId);
  if (current && current.runId !== expectedRunId) {
    throw new Error("当前审核记录已发生变化，请刷新后重试");
  }
  storage.removeItem(pointerKey(actorId, projectId, contractId));
}

export function loadReviewRunRestartMarker(
  storage: Storage,
  actorId: string,
  projectId: string,
  contractId: string,
): ReviewRunRestartMarker | null {
  const key = restartMarkerKey(actorId, projectId, contractId);
  const raw = storage.getItem(key);
  if (raw === null) return null;
  let value: Record<string, unknown>;
  try {
    value = parseObject(raw, "审核导航状态无法读取");
  } catch {
    storage.removeItem(key);
    return null;
  }
  const fields = Object.keys(value).sort();
  if (fields.length !== 3 || fields[0] !== "reason" || fields[1] !== "runId" || fields[2] !== "stepId"
      || value.reason !== "RESTART_REQUIRED"
      || typeof value.runId !== "string" || !value.runId.trim()
      || typeof value.stepId !== "string" || !value.stepId.trim()) {
    storage.removeItem(key);
    return null;
  }
  return { runId: value.runId, stepId: value.stepId, reason: "RESTART_REQUIRED" };
}

export function saveReviewRunRestartMarker(
  storage: Storage,
  actorId: string,
  projectId: string,
  contractId: string,
  runId: string,
  stepId: string,
): void {
  const marker: ReviewRunRestartMarker = {
    runId: required(runId, "审核记录无效"),
    stepId: required(stepId, "审核环节无效"),
    reason: "RESTART_REQUIRED",
  };
  storage.setItem(restartMarkerKey(actorId, projectId, contractId), JSON.stringify(marker));
}

export function clearReviewRunRestartMarker(
  storage: Storage,
  actorId: string,
  projectId: string,
  contractId: string,
  expectedRunId: string,
): void {
  const marker = loadReviewRunRestartMarker(storage, actorId, projectId, contractId);
  if (!marker || marker.runId !== expectedRunId) return;
  storage.removeItem(restartMarkerKey(actorId, projectId, contractId));
}

export function loadOrCreateReviewRunCreateOperation(
  persistence: ReviewRunOperationPersistence,
  actorId: string,
  projectId: string,
  contractId: string,
  expectedContractVersion: number,
  fileIds: readonly string[],
): ReviewRunOperation {
  const key = createKey(actorId, projectId, contractId);
  const identity = commandIdentity(expectedContractVersion, fileIds);
  const stored = readOperation(persistence.storage, key);
  if (stored) {
    if (stored.commandIdentity !== identity) {
      throw new Error("审核材料或合同版本已变化，请先确认上次审核操作");
    }
    return { idempotencyKey: stored.idempotencyKey };
  }
  const operation: StoredCreateOperation = {
    idempotencyKey: `review-run-ui-${required(persistence.createKey(), "无法生成审核操作标识")}`,
    commandIdentity: identity,
  };
  persistence.storage.setItem(key, JSON.stringify(operation));
  return { idempotencyKey: operation.idempotencyKey };
}

export function loadReviewRunCreateOperation(
  persistence: ReviewRunOperationPersistence,
  actorId: string,
  projectId: string,
  contractId: string,
): ReviewRunCreateOperation | null {
  const stored = readOperation(persistence.storage, createKey(actorId, projectId, contractId));
  if (!stored) return null;
  try {
    const command: unknown = JSON.parse(stored.commandIdentity);
    if (!Array.isArray(command) || command.length !== 2
        || !Number.isSafeInteger(command[0]) || command[0] < 0
        || !Array.isArray(command[1]) || command[1].length === 0
        || !command[1].every((fileId: unknown) => typeof fileId === "string" && fileId.trim())
        || commandIdentity(command[0], command[1]) !== stored.commandIdentity) {
      throw new Error("invalid command identity");
    }
    return {
      idempotencyKey: stored.idempotencyKey,
      expectedContractVersion: command[0],
      materialFileIds: command[1],
    };
  } catch {
    throw new Error("审核操作状态已失效，请重新进入审核");
  }
}

export function clearReviewRunCreateOperation(
  persistence: ReviewRunOperationPersistence,
  actorId: string,
  projectId: string,
  contractId: string,
  expectedIdempotencyKey: string,
): void {
  const key = createKey(actorId, projectId, contractId);
  const stored = readOperation(persistence.storage, key);
  if (stored && stored.idempotencyKey !== expectedIdempotencyKey) {
    throw new Error("本次审核操作已发生变化，请刷新后重试");
  }
  persistence.storage.removeItem(key);
}

export function loadOrCreateReviewStepOperation(
  persistence: ReviewRunOperationPersistence,
  actorId: string,
  projectId: string,
  contractId: string,
  runId: string,
  stepId: string,
): ReviewRunOperation {
  const key = stepKey(actorId, projectId, contractId, runId, stepId);
  const raw = persistence.storage.getItem(key);
  if (raw !== null) {
    const value = parseObject(raw, "审核环节操作状态无法读取，请刷新后重试");
    if (typeof value.idempotencyKey !== "string" || !value.idempotencyKey.trim()) {
      throw new Error("审核环节操作状态已失效，请重新进入审核");
    }
    return { idempotencyKey: value.idempotencyKey };
  }
  const operation: ReviewRunOperation = {
    idempotencyKey: `review-step-ui-${required(persistence.createKey(), "无法生成审核环节操作标识")}`,
  };
  persistence.storage.setItem(key, JSON.stringify(operation));
  return operation;
}
