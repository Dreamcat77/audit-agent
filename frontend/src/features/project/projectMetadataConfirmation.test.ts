import assert from "node:assert/strict";
import test from "node:test";

const metadata = await import("./projectMetadataConfirmation.ts").catch(() => ({}));

test("metadata draft prefers durable Project facts and only uses unique non-conflicting extraction suggestions", () => {
  assert.equal(typeof metadata.projectMetadataDraft, "function");
  const base = {
    project: { id: "project", name: null, projectNo: null, category: null, versionNo: 3 },
    extractionResults: [
      { factType: "PROJECT_NAME", normalizedValue: "Suggested Name", valueText: "Suggested Name", valueAmount: null },
      { factType: "PROJECT_NO", normalizedValue: "P-1", valueText: "P-1", valueAmount: null },
      { factType: "PROJECT_NO", normalizedValue: "P-2", valueText: "P-2", valueAmount: null },
    ],
  };
  assert.deepEqual(metadata.projectMetadataDraft(base), {
    expectedVersion: 3, name: "Suggested Name", projectNo: "", category: "",
  });
  assert.deepEqual(metadata.projectMetadataDraft({
    ...base,
    project: { ...base.project, name: "Durable Name", projectNo: "D-1", category: "WORKS" },
  }), { expectedVersion: 3, name: "Durable Name", projectNo: "D-1", category: "WORKS" });
});

test("metadata success and 409 both refresh durable Project facts without a second PATCH", async () => {
  assert.equal(typeof metadata.confirmMetadataAndReload, "function");
  let patches = 0;
  let clears = 0;
  const durable = { project: { id: "project", name: "Durable", versionNo: 1 } };
  const successSource = {
    confirmMetadata: async () => { patches += 1; return { id: "project" }; },
    getProject: async () => durable,
  };
  const success = await metadata.confirmMetadataAndReload(successSource, "project",
    { expectedVersion: 0, name: "Durable", projectNo: null, category: null }, "metadata-key",
    { clear: () => { clears += 1; } });
  assert.equal(success.outcome, "CONFIRMED");
  assert.equal(success.project, durable);

  const conflict = Object.assign(new Error("version conflict"), { status: 409 });
  const conflictSource = {
    confirmMetadata: async () => { patches += 1; throw conflict; },
    getProject: async () => ({ project: { id: "project", name: "Someone Else", versionNo: 2 } }),
  };
  const reconciled = await metadata.confirmMetadataAndReload(conflictSource, "project",
    { expectedVersion: 1, name: "Mine", projectNo: null, category: null }, "new-metadata-key",
    { clear: () => { clears += 1; } });
  assert.equal(reconciled.outcome, "CONFLICT");
  assert.equal(reconciled.project.project.name, "Someone Else");
  assert.equal(patches, 2, "each explicit action sends exactly one PATCH");
  assert.equal(clears, 2, "confirmed and terminal conflict operations are cleared after durable GET");
});

test("an uncertain metadata outcome retains its immutable operation key", async () => {
  assert.equal(typeof metadata.confirmMetadataAndReload, "function");
  let cleared = false;
  const networkFailure = Object.assign(new Error("network"), { status: 0 });
  await assert.rejects(metadata.confirmMetadataAndReload({
    confirmMetadata: async () => { throw networkFailure; },
    getProject: async () => { throw new Error("must not GET for non-409 POST failure"); },
  }, "project", { expectedVersion: 0, name: null, projectNo: null, category: null }, "same-key",
  { clear: () => { cleared = true; } }), (failure) => failure === networkFailure);
  assert.equal(cleared, false);
});

test("a 409 with no durable version change remains uncertain and retains the metadata key", async () => {
  let cleared = false;
  const conflict = Object.assign(new Error("in progress"), { status: 409 });
  const result = await metadata.confirmMetadataAndReload({
    confirmMetadata: async () => { throw conflict; },
    getProject: async () => ({ project: { id: "project", versionNo: 0 } }),
  }, "project", { expectedVersion: 0, name: "Mine", projectNo: null, category: null }, "same-key",
  { clear: () => { cleared = true; } });
  assert.equal(result.outcome, "CONFLICT");
  assert.equal(cleared, false);
});
