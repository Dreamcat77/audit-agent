import assert from "node:assert/strict";
import test from "node:test";
import { confirmThenPromote, discoverLatestCurrentConfirmation, retrySavedPromotion,
  saveCandidateConfirmation } from
  "./candidateConfirmationFlow.ts";

const payload = { expectedVersion: 0, contractNo: "HUMAN-1", contractName: "Human Contract",
  contractType: "GOODS", partyA: "Buyer", partyB: "Supplier", partyBCreditCode: null,
  contractAmount: 100, amountInWords: null, lineItems: [], awardNotice: null };
const saved = { confirmationId: "confirmation-1", projectId: "project", candidateId: "candidate",
  baseCandidateVersion: 0, resultCandidateVersion: 1, sourceFileId: "file", sourceParseResultId: "parse",
  sourceRoleDecisionId: "role", sourceIdentityDecisionId: "identity", payloadFingerprint: "a".repeat(64),
  payloadSchemaVersion: 1, payload: { ...payload, expectedVersion: undefined }, confirmedBy: "actor",
  confirmedAt: "2026-09-15T00:00:00Z", supersedesConfirmationId: null, replayed: false };

test("contract content confirmation is durable without promoting a formal contract", async () => {
  const calls: unknown[] = [];
  const source = {
    confirmCandidate: async (_p: string, _c: string, body: unknown, options: { idempotencyKey: string }) => {
      calls.push(["CONFIRM", body, options.idempotencyKey]);
      return saved;
    },
    promote: async () => { calls.push(["PROMOTE"]); throw new Error("must not run"); },
  };
  assert.equal(await saveCandidateConfirmation(source as never, "project", "candidate", payload,
    "confirm-only-key"), saved);
  assert.deepEqual(calls, [["CONFIRM", payload, "confirm-only-key"]]);
});

test("one click posts durable confirmation then identity-only promotion with distinct keys", async () => {
  const calls: unknown[] = [];
  const source = {
    confirmCandidate: async (_p: string, _c: string, body: unknown, options: { idempotencyKey: string }) => {
      calls.push(["CONFIRM", body, options.idempotencyKey]); return saved;
    },
    promote: async (_p: string, _c: string, body: unknown, options: { idempotencyKey: string }) => {
      calls.push(["PROMOTE", body, options.idempotencyKey]); return { contractId: "contract" };
    },
    getProject: async () => ({ project: { id: "project" }, associatedContracts: [
      { projectId: "project", candidateId: "candidate", contractId: "contract" }] }),
    getCandidate: async () => ({ candidate: { id: "candidate", projectId: "project",
      promotionState: "PROMOTED", promotedContractId: "contract",
      promotedConfirmationId: "confirmation-1" } }),
  };
  const result = await confirmThenPromote(source as never, "project", "candidate", payload,
    "confirm-key", "promote-key");
  assert.equal(result.contractId, "contract");
  assert.deepEqual(calls, [["CONFIRM", payload, "confirm-key"],
    ["PROMOTE", { expectedVersion: 1, confirmationId: "confirmation-1" }, "promote-key"]]);
});

test("promotion failure leaves saved confirmation and retry never repeats confirmation", async () => {
  let confirms = 0, promotes = 0;
  let durableSaved = null;
  const source = {
    confirmCandidate: async () => { confirms++; return saved; },
    promote: async () => { promotes++; if (promotes === 1) throw new Error("network");
      return { contractId: "contract" }; },
    getProject: async () => ({ project: { id: "project" }, associatedContracts: [
      { projectId: "project", candidateId: "candidate", contractId: "contract" }] }),
    getCandidate: async () => ({ candidate: { id: "candidate", projectId: "project",
      promotionState: "PROMOTED", promotedContractId: "contract",
      promotedConfirmationId: "confirmation-1" } }),
  };
  await assert.rejects(confirmThenPromote(source as never, "project", "candidate", payload,
    "confirm-key", "promote-key", (value) => { durableSaved = value; }), /network/);
  assert.equal(durableSaved, saved);
  const retried = await retrySavedPromotion(source as never, "project", "candidate", saved as never,
    "promote-key");
  assert.equal(retried.contractId, "contract");
  assert.equal(confirms, 1);
  assert.equal(promotes, 2);
});

test("definite confirmation 409 cannot adopt an unrelated matching latest snapshot", async () => {
  const { expectedVersion: _version, ...sameBody } = payload;
  const unrelated = { ...saved, payload: sameBody };
  const rejected = Object.assign(new Error("different operation already won"), { status: 409 });
  let promotes = 0;
  const source = {
    confirmCandidate: async () => { throw rejected; },
    getLatestConfirmation: async () => unrelated,
    getCandidate: async () => promotes ? { candidate: { id: "candidate", projectId: "project",
      promotionState: "PROMOTED", promotedContractId: "contract",
      promotedConfirmationId: "confirmation-1" } } : {
      candidate: { versionNo: 1, promotionState: "DRAFT", sourceContractFileId: "file",
        sourceParseResultId: "parse", sourceRoleDecisionId: "role",
        sourceIdentityDecisionId: "identity" },
      sourceFile: { id: "file", parseResultId: "parse", effectiveRole: "CONTRACT_BODY",
        effectiveRoleSource: "HUMAN_CONFIRMATION", roleDecisionId: "role",
        effectiveIdentityStatus: "SAME_PROJECT", identityDecisionId: "identity",
        identityCurrent: true },
    },
    promote: async () => { promotes++; return { contractId: "contract" }; },
    getProject: async () => ({ project: { id: "project" }, associatedContracts: [
      { projectId: "project", candidateId: "candidate", contractId: "contract" }] }),
  };
  await assert.rejects(confirmThenPromote(source as never, "project", "candidate", payload,
    "different-command-key", "promotion-key"), (cause) => cause === rejected);
  assert.equal(promotes, 0);
});

test("uncertain confirmation result replays the exact same key instead of adopting latest", async () => {
  const keys: string[] = [];
  let latestReads = 0;
  const source = {
    confirmCandidate: async (_project: string, _candidate: string, _body: unknown,
      options: { idempotencyKey: string }) => {
      keys.push(options.idempotencyKey);
      if (keys.length === 1) throw Object.assign(new Error("lost response"), { status: 0 });
      return saved;
    },
    getLatestConfirmation: async () => { latestReads++; throw new Error("not needed"); },
    getCandidate: async () => ({ candidate: { id: "candidate", projectId: "project",
      promotionState: "PROMOTED", promotedContractId: "contract",
      promotedConfirmationId: "confirmation-1" } }),
    getProject: async () => ({ project: { id: "project" }, associatedContracts: [
      { projectId: "project", candidateId: "candidate", contractId: "contract" }] }),
    promote: async () => ({ contractId: "contract" }),
  };
  const result = await confirmThenPromote(source as never, "project", "candidate", payload,
    "immutable-confirm-key", "distinct-promote-key");
  assert.equal(result.contractId, "contract");
  assert.deepEqual(keys, ["immutable-confirm-key", "immutable-confirm-key"]);
  assert.equal(latestReads, 0);
});

test("refresh discovers current durable confirmation without issuing a POST", async () => {
  let writes = 0;
  const source = { getLatestConfirmation: async () => saved,
    getCandidate: async () => ({ candidate: { versionNo: 1, promotionState: "DRAFT",
      sourceContractFileId: "file", sourceParseResultId: "parse", sourceRoleDecisionId: "role",
      sourceIdentityDecisionId: "identity" },
      sourceFile: { id: "file", parseResultId: "parse", effectiveRole: "CONTRACT_BODY",
        effectiveRoleSource: "HUMAN_CONFIRMATION",
        roleDecisionId: "role", effectiveIdentityStatus: "SAME_PROJECT",
        identityDecisionId: "identity", identityCurrent: true } }),
    confirmCandidate: async () => { writes++; throw new Error("not called"); } };
  assert.equal(await discoverLatestCurrentConfirmation(source as never, "project", "candidate"), saved);
  assert.equal(writes, 0);
});

test("latest snapshot with same candidate version is not promotable after authority drift", async () => {
  const source = { getLatestConfirmation: async () => saved,
    getCandidate: async () => ({ candidate: { versionNo: 1, promotionState: "DRAFT",
      sourceContractFileId: "file", sourceParseResultId: "parse", sourceRoleDecisionId: "role",
      sourceIdentityDecisionId: "identity" },
      sourceFile: { id: "file", parseResultId: "parse", effectiveRole: "OTHER",
        effectiveRoleSource: "HUMAN_CONFIRMATION",
        roleDecisionId: "other-role", effectiveIdentityStatus: "SAME_PROJECT",
        identityDecisionId: "identity", identityCurrent: true } }) };
  assert.equal(await discoverLatestCurrentConfirmation(source as never, "project", "candidate"), null);
});

test("promotion operation identity can be scoped to the returned immutable confirmation", async () => {
  const observed: string[] = [];
  const source = { confirmCandidate: async () => saved,
    promote: async (_p: string, _c: string, _body: unknown, options: { idempotencyKey: string }) => {
      observed.push(options.idempotencyKey); return { contractId: "contract" }; },
    getProject: async () => ({ project: { id: "project" }, associatedContracts: [
      { projectId: "project", candidateId: "candidate", contractId: "contract" }] }),
    getCandidate: async () => ({ candidate: { id: "candidate", projectId: "project",
      promotionState: "PROMOTED", promotedContractId: "contract",
      promotedConfirmationId: "confirmation-1" } }),
  };
  await confirmThenPromote(source as never, "project", "candidate", payload,
    "confirm-key", (value) => `promote-key:${value.confirmationId}`);
  assert.deepEqual(observed, ["promote-key:confirmation-1"]);
});
