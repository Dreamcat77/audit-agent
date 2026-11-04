import assert from "node:assert/strict";
import test from "node:test";
import type { ProjectDataSource } from "./data/projectDataSource.ts";
import {
  createBrowserProgressionRecoveryPersistence,
  findRetainedProgressionRecovery,
  loadOrCreateProgressionRecovery,
  resumeProjectFileProgression,
} from "./projectProgressionRecovery.ts";
import type { ProjectFile } from "./types.ts";

class MemoryStorage implements Storage {
  private readonly values = new Map<string, string>();
  get length() { return this.values.size; }
  clear() { this.values.clear(); }
  getItem(key: string) { return this.values.get(key) ?? null; }
  key(index: number) { return [...this.values.keys()][index] ?? null; }
  removeItem(key: string) { this.values.delete(key); }
  setItem(key: string, value: string) { this.values.set(key, value); }
}

function apiError(status: number, classification?: string) {
  return Object.assign(new Error("safe test error"), {
    status, data: classification ? { classification } : undefined,
  });
}

function file(projectId = "project-a", fingerprint = "a".repeat(64)): ProjectFile {
  return {
    id: "file-a", projectId,
    progression: {
      stage: "PARSED", blocker: "ROLE_ASSESSMENT_PENDING",
      availableActions: ["RESUME_ROLE_ASSESSMENT"], candidateId: null, contractId: null,
      recoveryInputFingerprint: fingerprint,
    },
  } as ProjectFile;
}

test("retained uncertain command keeps its original key and input identity", () => {
  const persistence = createBrowserProgressionRecoveryPersistence(new MemoryStorage());
  const first = loadOrCreateProgressionRecovery(persistence, "actor", file(), () => "operation-1");
  const changed = loadOrCreateProgressionRecovery(
    persistence, "actor", file("project-a", "b".repeat(64)), () => "operation-2");

  assert.deepEqual(changed, first);
  assert.equal(changed.idempotencyKey, "operation-1");
  assert.equal(changed.expectedRecoveryInputFingerprint, "a".repeat(64));
  assert.equal(findRetainedProgressionRecovery(persistence, "actor", "project-b", "file-a"), null);
});

test("successful replay clears retained operation after using the original command", async () => {
  const persistence = createBrowserProgressionRecoveryPersistence(new MemoryStorage());
  const original = loadOrCreateProgressionRecovery(persistence, "actor", file(), () => "operation-1");
  let sent: unknown;
  const dataSource = {
    async resumeProgression(_projectId: string, _fileId: string, payload: unknown,
      options: { idempotencyKey: string }) {
      sent = { payload, key: options.idempotencyKey };
      return file();
    },
  } as unknown as ProjectDataSource;

  await resumeProjectFileProgression(dataSource, persistence, "actor", file(), () => "unexpected");

  assert.deepEqual(sent, {
    payload: {
      expectedBlocker: original.expectedBlocker,
      expectedRecoveryInputFingerprint: original.expectedRecoveryInputFingerprint,
    },
    key: "operation-1",
  });
  assert.equal(findRetainedProgressionRecovery(persistence, "actor", "project-a", "file-a"), null);
});

test("uncertain failure retains the exact command while proven no-effect rejection clears it", async () => {
  const uncertainPersistence = createBrowserProgressionRecoveryPersistence(new MemoryStorage());
  const uncertainSource = {
    async resumeProgression() {
      throw apiError(0);
    },
  } as unknown as ProjectDataSource;
  await assert.rejects(() => resumeProjectFileProgression(
    uncertainSource, uncertainPersistence, "actor", file(), () => "uncertain-key"));
  assert.equal(findRetainedProgressionRecovery(
    uncertainPersistence, "actor", "project-a", "file-a")?.idempotencyKey, "uncertain-key");

  const rejectedPersistence = createBrowserProgressionRecoveryPersistence(new MemoryStorage());
  const rejectedSource = {
    async resumeProgression() {
      throw apiError(409, "RECOVERY_INPUT_CHANGED");
    },
  } as unknown as ProjectDataSource;
  await assert.rejects(() => resumeProjectFileProgression(
    rejectedSource, rejectedPersistence, "actor", file(), () => "rejected-key"));
  assert.equal(findRetainedProgressionRecovery(
    rejectedPersistence, "actor", "project-a", "file-a"), null);

  const capacityPersistence = createBrowserProgressionRecoveryPersistence(new MemoryStorage());
  const capacitySource = {
    async resumeProgression() {
      throw apiError(409, "FACT_SEGMENT_BUDGET_EXCEEDED");
    },
  } as unknown as ProjectDataSource;
  await assert.rejects(() => resumeProjectFileProgression(
    capacitySource, capacityPersistence, "actor", file(), () => "capacity-key"));
  assert.equal(findRetainedProgressionRecovery(
    capacityPersistence, "actor", "project-a", "file-a"), null);
});

test("fact extraction recovery binds the server blocker and immutable input fingerprint", () => {
  const persistence = createBrowserProgressionRecoveryPersistence(new MemoryStorage());
  const extractionFile = {
    ...file(),
    progression: {
      stage: "IDENTITY_CONFIRMED", blocker: "FACT_EXTRACTION_PENDING",
      availableActions: ["RESUME_FACT_EXTRACTION"], candidateId: null, contractId: null,
      recoveryInputFingerprint: "c".repeat(64),
    },
  } as ProjectFile;

  const operation = loadOrCreateProgressionRecovery(
    persistence, "actor", extractionFile, () => "fact-operation-1");

  assert.equal(operation.action, "RESUME_FACT_EXTRACTION");
  assert.equal(operation.expectedBlocker, "FACT_EXTRACTION_PENDING");
  assert.equal(operation.expectedRecoveryInputFingerprint, "c".repeat(64));
});
