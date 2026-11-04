import assert from "node:assert/strict";
import test from "node:test";
import { frozenContractFromReviewRun } from "./reviewRunFrozenContract.ts";
import type { ReviewRunIdentityDto } from "./reviewRunTypes.ts";

const snapshot = {
  schemaVersion: 1,
  contractId: "contract-1",
  versionNo: 3,
  contractNo: "OLD-001",
  contractName: "冻结合同",
  contractType: "SERVICE",
  partyA: "采购方",
  partyB: "冻结供应商",
  partyBCreditCode: null,
  contractAmount: "67000",
  amountInWords: "人民币陆万柒仟元整",
  status: "READY_FOR_REVIEW",
  createdAt: "2026-09-17T00:00:00Z",
  updatedAt: "2026-09-17T00:00:00Z",
  lineItems: [{
    id: "line-1", itemKey: "item-1", itemName: "维修服务", quantity: "2",
    unitPrice: "14000", declaredAmount: "28000", sortNo: 1,
  }],
  awardNotice: {
    id: "award-1", noticeNo: "AWARD-001", projectName: "原项目",
    winningBidder: "冻结供应商", winningBidderCreditCode: null,
    winningAmount: "67000", winningDate: "2026-09-17", sourceRef: "file-1",
  },
};

function run(value: object = snapshot): ReviewRunIdentityDto {
  return {
    runId: "old-run", projectId: "project-1", contractId: "contract-1", contractVersion: 3,
    sourceCandidateId: "candidate-1", sourceConfirmationId: null, runSchemaVersion: 1,
    contractSnapshotCanonicalJson: JSON.stringify(value), contractSnapshotSha256: "hash",
    reviewProfileVersion: "profile", ruleVersion: "rules", modelPolicyVersion: "model",
    inputSnapshotIdentity: "identity", requestedBy: "actor-1", status: "COMPLETED",
    requestedAt: "2026-09-17T00:00:00Z", startedAt: null, completedAt: null,
    failedAt: null, unknownAt: null, safeReasonCode: null, versionNo: 2,
  };
}

test("historical run displays its frozen Contract including line items and award", () => {
  const contract = frozenContractFromReviewRun(run());
  assert.equal(contract.contractName, "冻结合同");
  assert.equal(contract.contractAmount, 67000);
  assert.equal(contract.lineItems[0]?.declaredAmount, 28000);
  assert.equal(contract.awardNotice?.winningBidder, "冻结供应商");
});

test("schema v2 reopens amount-only lines without inventing quantity or unit price", () => {
  const amountOnly = {
    ...snapshot,
    schemaVersion: 2,
    lineItems: [{
      id: "line-amount-only", itemKey: "service-1", itemName: "Service fee",
      basis: "AMOUNT_ONLY", quantity: null, unitPrice: null, declaredAmount: "28000", sortNo: 1,
    }],
  };
  const contract = frozenContractFromReviewRun(run(amountOnly));
  assert.equal(contract.lineItems[0]?.basis, "AMOUNT_ONLY");
  assert.equal(contract.lineItems[0]?.quantity, null);
  assert.equal(contract.lineItems[0]?.unitPrice, null);
  assert.equal(contract.lineItems[0]?.declaredAmount, 28000);
});

test("mismatched or unsupported frozen Contract identity fails closed", () => {
  assert.throws(() => frozenContractFromReviewRun(run({ ...snapshot, contractId: "other" })));
  assert.throws(() => frozenContractFromReviewRun(run({ ...snapshot, schemaVersion: 3 })));
  assert.throws(() => frozenContractFromReviewRun(run({ ...snapshot, lineItems: "missing" })));
});
