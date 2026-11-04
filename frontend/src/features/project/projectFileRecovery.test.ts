import assert from "node:assert/strict";
import test from "node:test";
import { recoverProjectFileAndReload } from "./projectFileRecovery.ts";

test("recovery sends no bytes/key and publishes only the reloaded durable Project facts", async () => {
  const calls = [];
  const durable = { project: { id: "p" }, files: [{ id: "f", ingestionStatus: "AVAILABLE" }] };
  const source = {
    recover: async (projectId, fileId) => { calls.push(["recover", projectId, fileId]); return { id: fileId }; },
    getProject: async (projectId) => { calls.push(["detail", projectId]); return durable; },
    listFiles: async (projectId) => { calls.push(["files", projectId]); return durable.files; },
  };
  assert.equal(await recoverProjectFileAndReload(source, "p", "f"), durable);
  assert.deepEqual(calls, [["recover", "p", "f"], ["detail", "p"], ["files", "p"]]);
});
