import assert from "node:assert/strict";
import { registerHooks } from "node:module";
import test from "node:test";

const hooks = registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith(".") && !/\.[a-z]+$/i.test(specifier)) return nextResolve(`${specifier}.ts`, context);
    return nextResolve(specifier, context);
  },
});

const { createProjectIntakeOperation } = await import("./projectIntakeOperation.ts").finally(() => hooks.deregister());

const project = {
  id: "project-1", name: null, projectNo: null, category: null, versionNo: 0,
  createdAt: "2026-08-31T00:00:00Z", updatedAt: "2026-08-31T00:00:00Z",
};
const uploaded = {
  id: "file-1", projectId: "project-1", parentArchiveFileId: null,
  normalizedRelativePath: null, originalFilename: "项目资料包.zip",
  mediaType: "application/zip", category: "ARCHIVE",
  ingestionStatus: "AVAILABLE", parseStatus: "NOT_APPLICABLE", sizeBytes: 3,
  parsedText: null, safeReasonCode: null,
  createdAt: "2026-08-31T00:00:01Z", updatedAt: "2026-08-31T00:00:01Z",
};
const detail = {
  project, intakeStatus: "FILES_AVAILABLE", files: [uploaded], candidates: [],
  extractionResults: [], associatedContracts: [], failedFileCount: 0, unsupportedFileCount: 0,
};

test("an uncertain upload reuses the same Project, upload key, and selected ZIP", async () => {
  const generatedKeys = ["create-key", "upload-key"];
  const createKeys: string[] = [];
  const uploadCalls: Array<{ projectId: string; file: File; key: string }> = [];
  let uploadAttempt = 0;
  const source = {
    async createBlank(options: { idempotencyKey: string }) {
      createKeys.push(options.idempotencyKey);
      return project;
    },
    async upload(projectId: string, file: File, options: { idempotencyKey: string }) {
      uploadCalls.push({ projectId, file, key: options.idempotencyKey });
      uploadAttempt += 1;
      if (uploadAttempt === 1) throw new Error("transport outcome unknown");
      return uploaded;
    },
    async getProject() { return detail; },
  };
  const stages: string[] = [];
  const operation = createProjectIntakeOperation(() => generatedKeys.shift()!, (stage: string) => stages.push(stage));
  const selected = new File(["zip"], "项目资料包.zip", { type: "application/zip" });

  operation.select(selected);
  await assert.rejects(operation.run(source));
  assert.equal(operation.snapshot().stage, "UNCERTAIN");
  assert.equal(operation.snapshot().filename, "项目资料包.zip");

  const completed = await operation.run(source);

  assert.equal(completed.project.id, "project-1");
  assert.deepEqual(createKeys, ["create-key"]);
  assert.deepEqual(uploadCalls.map((call) => call.projectId), ["project-1", "project-1"]);
  assert.deepEqual(uploadCalls.map((call) => call.key), ["upload-key", "upload-key"]);
  assert.equal(uploadCalls[0].file, selected);
  assert.equal(uploadCalls[1].file, selected);
  assert.deepEqual(stages, [
    "FILE_SELECTED", "CREATING_PROJECT", "UPLOADING_PACKAGE", "UNCERTAIN",
    "UPLOADING_PACKAGE", "REFRESHING_DURABLE_STATE", "COMPLETE",
  ]);
});

test("an uncertain create retries the same create key before any upload", async () => {
  const createKeys: string[] = [];
  const uploadKeys: string[] = [];
  let createAttempt = 0;
  const source = {
    async createBlank(options: { idempotencyKey: string }) {
      createKeys.push(options.idempotencyKey);
      createAttempt += 1;
      if (createAttempt === 1) throw new Error("transport outcome unknown");
      return project;
    },
    async upload(_projectId: string, _file: File, options: { idempotencyKey: string }) {
      uploadKeys.push(options.idempotencyKey);
      return uploaded;
    },
    async getProject() { return detail; },
  };
  const keys = ["create-key", "upload-key"];
  const operation = createProjectIntakeOperation(() => keys.shift()!);
  operation.select(new File(["zip"], "package.zip", { type: "application/zip" }));

  await assert.rejects(operation.run(source));
  assert.deepEqual(uploadKeys, []);
  await operation.run(source);

  assert.deepEqual(createKeys, ["create-key", "create-key"]);
  assert.deepEqual(uploadKeys, ["upload-key"]);
});

test("durable refresh must prove the uploaded ProjectFile before completion", async () => {
  const source = {
    async createBlank() { return project; },
    async upload() { return uploaded; },
    async getProject() { return { ...detail, files: [] }; },
  };
  const operation = createProjectIntakeOperation(() => "one-key-per-scope");
  operation.select(new File(["zip"], "package.zip", { type: "application/zip" }));

  await assert.rejects(operation.run(source), /durable project file not proven/);
  assert.equal(operation.snapshot().stage, "UNCERTAIN");
});

test("durable failed package is terminal and a replacement uses new operation identities", async () => {
  const generatedKeys = ["create-key-1", "upload-key-1", "create-key-2", "upload-key-2"];
  const createKeys: string[] = [];
  const uploadKeys: string[] = [];
  let refreshCount = 0;
  const failed = { ...uploaded, ingestionStatus: "UPLOAD_FAILED" as const,
    safeReasonCode: "ARCHIVE_ENTRY_REJECTED" };
  const source = {
    async createBlank(options: { idempotencyKey: string }) {
      createKeys.push(options.idempotencyKey);
      return project;
    },
    async upload(_projectId: string, _file: File, options: { idempotencyKey: string }) {
      uploadKeys.push(options.idempotencyKey);
      return uploaded;
    },
    async getProject() {
      refreshCount += 1;
      return refreshCount === 1 ? { ...detail, files: [failed], failedFileCount: 1 } : detail;
    },
  };
  const stages: string[] = [];
  const operation = createProjectIntakeOperation(
    () => generatedKeys.shift()!, (stage: string) => stages.push(stage));

  operation.select(new File(["bad-zip"], "failed.zip", { type: "application/zip" }));
  await assert.rejects(operation.run(source), /durable project file upload failed/);
  assert.equal(operation.snapshot().stage, "FAILED");

  operation.select(new File(["good-zip"], "replacement.zip", { type: "application/zip" }));
  const completed = await operation.run(source);

  assert.equal(completed.file.ingestionStatus, "AVAILABLE");
  assert.deepEqual(createKeys, ["create-key-1", "create-key-2"]);
  assert.deepEqual(uploadKeys, ["upload-key-1", "upload-key-2"]);
  assert.deepEqual(stages, [
    "FILE_SELECTED", "CREATING_PROJECT", "UPLOADING_PACKAGE", "REFRESHING_DURABLE_STATE", "FAILED",
    "FILE_SELECTED", "CREATING_PROJECT", "UPLOADING_PACKAGE", "REFRESHING_DURABLE_STATE", "COMPLETE",
  ]);
});

test("ZIP async acceptance completes intake without waiting for child parsing",async()=>{
  let syncCalls=0;
  const source={async createBlank(){return project;},async upload(){syncCalls++;throw new Error("blocked parser");},
    async uploadAsync(){return {projectId:project.id,fileId:uploaded.id,jobId:"j1",file:uploaded,job:{status:"QUEUED"}};},
    async getProject(){return detail;}};
  const operation=createProjectIntakeOperation(()=>"key");
  operation.select(new File(["zip"],"package.zip"));
  assert.equal((await operation.run(source)).project.id,project.id);
  assert.equal(syncCalls,0);
  assert.equal(operation.snapshot().stage,"ACCEPTED");
});
