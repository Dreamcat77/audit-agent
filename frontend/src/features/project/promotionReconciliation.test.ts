import assert from "node:assert/strict";
import test from "node:test";
import { createProjectOperationKeys, promoteAndReconcile } from "./promotionReconciliation.ts";

test("promotion advances only after Project and candidate GET facts prove the same Contract", async () => {
  const calls = [];
  const source = {
    promote: async () => { calls.push("POST"); return { contractId: "contract" }; },
    getProject: async () => { calls.push("GET_PROJECT"); return {
      project: { id: "project" },
      associatedContracts: [{ projectId: "project", candidateId: "candidate", contractId: "contract" }],
    }; },
    getCandidate: async () => { calls.push("GET_CANDIDATE"); return { candidate: {
      id: "candidate", projectId: "project", promotionState: "PROMOTED", promotedContractId: "contract",
      promotedConfirmationId: "confirmation",
    } }; },
  };
  const result = await promoteAndReconcile(source, "project", "candidate",
    { expectedVersion: 1, confirmationId: "confirmation" }, "promotion-key");
  assert.equal(result.contractId, "contract");
  assert.equal(result.outcome, "PROMOTED_OR_REPLAYED");
  assert.deepEqual(calls, ["POST", "GET_PROJECT", "GET_CANDIDATE"]);
});

test("409 is reconciled by GET and unproven state retains the immutable promotion key", async () => {
  let cleared = false;
  const conflict = Object.assign(new Error("conflict"), { status: 409 });
  const source = {
    promote: async () => { throw conflict; },
    getProject: async () => ({ project: { id: "project" }, associatedContracts: [] }),
    getCandidate: async () => ({ candidate: {
      id: "candidate", projectId: "project", promotionState: "DRAFT", promotedContractId: null,
    } }),
  };
  await assert.rejects(
    promoteAndReconcile(source, "project", "candidate",
      { expectedVersion: 1, confirmationId: "confirmation" }, "same-key",
      { clear: () => { cleared = true; } }),
    (failure) => failure === conflict,
  );
  assert.equal(cleared, false);
});

test("a competing 409 cannot attribute confirmation X to durable Contract Y", async () => {
  let cleared = false;
  let posts = 0;
  const source = {
    promote: async (_projectId, _candidateId, payload) => {
      posts += 1;
      assert.deepEqual(payload, { expectedVersion: 1, confirmationId: "confirmation-x" });
      throw Object.assign(new Error("already promoted"), { status: 409 });
    },
    getProject: async () => ({
      project: { id: "project" },
      associatedContracts: [{
        projectId: "project", candidateId: "candidate", contractId: "contract-y",
        contractNo: "PAYLOAD-Y", contractName: "Durable Y", contractType: "GOODS",
        partyA: "Buyer", partyB: "Supplier", partyBCreditCode: null, contractAmount: 100,
        amountInWords: null, status: "READY_FOR_REVIEW", versionNo: 0,
      }],
    }),
    getCandidate: async () => ({ candidate: {
      id: "candidate", projectId: "project", promotionState: "PROMOTED", promotedContractId: "contract-y",
      promotedConfirmationId: "confirmation-y",
    } }),
  };
  await assert.rejects(promoteAndReconcile(source, "project", "candidate",
    { expectedVersion: 1, confirmationId: "confirmation-x" }, "terminal-key",
    { clear: () => { cleared = true; } }), /already promoted/);
  assert.equal(posts, 1, "409 reconciliation must never auto-submit another promotion");
  assert.equal(cleared, false);
});

test("promotion, F02 and acknowledgement operation identities are separate", () => {
  const values = ["metadata-key", "confirmation-key", "promotion-key", "f02-key", "ack-key"];
  const keys = createProjectOperationKeys(() => values.shift());
  assert.deepEqual([keys.metadata.key(), keys.candidateConfirmation.key(), keys.promotion.key(),
    keys.f02.key(), keys.acknowledgement.key()],
    ["metadata-key", "confirmation-key", "promotion-key", "f02-key", "ack-key"]);
});

test("Project operation collection uses HTTP LAN Web Crypto fallback by default", () => {
  const originalCrypto = Object.getOwnPropertyDescriptor(globalThis, "crypto");
  let entropyCalls = 0;
  Object.defineProperty(globalThis, "crypto", {
    configurable: true,
    value: {
      getRandomValues(bytes: Uint8Array) {
        bytes.fill(++entropyCalls);
        return bytes;
      },
    },
  });
  try {
    const keys = createProjectOperationKeys();
    const firstMetadataKey = keys.metadata.key();
    assert.match(firstMetadataKey,
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    assert.equal(keys.metadata.key(), firstMetadataKey);
    assert.match(keys.candidateConfirmation.key(),
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    assert.match(keys.promotion.key(),
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    assert.equal(entropyCalls, 3);
    keys.metadata.clear();
    assert.notEqual(keys.metadata.key(), firstMetadataKey);
    assert.equal(entropyCalls, 4);
  } finally {
    if (originalCrypto) Object.defineProperty(globalThis, "crypto", originalCrypto);
    else Reflect.deleteProperty(globalThis, "crypto");
  }
});
