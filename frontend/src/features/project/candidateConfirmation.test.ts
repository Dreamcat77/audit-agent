import assert from "node:assert/strict";
import test from "node:test";
import { addAmountOnlyLineItem, addBlankAwardNotice, confirmedPayloadFromCandidate,
  removeConfirmedLineItem, setConfirmedLineItemBasis } from "./candidateConfirmation.ts";

const confirmation = await import("./candidateConfirmation.ts");

function candidate() {
  return {
    id: "candidate", projectId: "project", sourceContractFileId: "file", contractNo: "C-1",
    contractName: "Extracted", contractType: "GOODS", partyA: "Buyer", partyB: "Supplier",
    partyBCreditCode: null, contractAmount: 100, amountInWords: "ONE HUNDRED", awardAmount: 100,
    awardSupplier: "Supplier", awardSupplierCreditCode: null,
    lineItems: [{ id: "line-id", projectId: "project", candidateId: "candidate", itemKey: "ITEM-1",
      itemName: "Server", quantity: 2, unitPrice: 50, declaredAmount: 100, sortNo: 1 }],
    promotionState: "DRAFT" as const, versionNo: 0, promotedContractId: null, promotedBy: null, promotedAt: null,
    createdAt: "now", updatedAt: "now",
  };
}

function completePayload() {
  const payload = confirmedPayloadFromCandidate(candidate());
  if (!payload.awardNotice) throw new Error("award notice fixture missing");
  payload.awardNotice.noticeNo = "ZB-2026-0101";
  payload.awardNotice.projectName = "智慧办公终端采购项目";
  payload.awardNotice.winningDate = "2026-08-26";
  return payload;
}

test("confirmation creates an editable business snapshot without formal identities or lifecycle controls", () => {
  const extracted = candidate();
  const payload = confirmedPayloadFromCandidate(extracted);
  assert.equal(payload.expectedVersion, 0);
  assert.equal(payload.contractNo, "C-1");
  assert.equal(payload.lineItems[0].itemKey, "ITEM-1");
  for (const prohibited of ["id", "projectId", "candidateId", "status", "createdAt", "updatedAt", "promotedBy"]) {
    assert.equal(Object.hasOwn(payload, prohibited), false);
    assert.equal(Object.hasOwn(payload.lineItems[0], prohibited), false);
  }
  payload.contractName = "Human confirmed";
  assert.equal(extracted.contractName, "Extracted", "editing confirmation must not mutate extracted facts");
});

for (const [field, message] of [
  ["noticeNo", "请填写中标通知书编号"],
  ["projectName", "请填写中标项目名称"],
  ["winningDate", "请选择中标日期"],
] as const) {
  test(`blank award ${field} blocks promotion before the data source call`, async () => {
    assert.equal(typeof confirmation.submitConfirmedContract, "function");
    const payload = completePayload();
    if (!payload.awardNotice) throw new Error("award notice fixture missing");
    payload.awardNotice[field] = "";
    let calls = 0;

    const result = await confirmation.submitConfirmedContract(payload, async () => {
      calls += 1;
      return "formal-contract";
    });

    assert.equal(calls, 0);
    assert.equal(result.result, null);
    assert.equal(result.errors.some((error: { message: string }) => error.message === message), true);
  });
}

test("all required contract, line-item, and award fields call promotion exactly once", async () => {
  assert.equal(typeof confirmation.submitConfirmedContract, "function");
  const payload = completePayload();
  let calls = 0;

  const result = await confirmation.submitConfirmedContract(payload, async (submitted: unknown) => {
    calls += 1;
    assert.equal(submitted, payload);
    return "formal-contract";
  });

  assert.equal(calls, 1);
  assert.deepEqual(result, { errors: [], result: "formal-contract" });
});

test("amount-only line preserves its declared amount without requiring invented quantity or unit price", async () => {
  const payload = completePayload();
  payload.lineItems[0] = {
    ...payload.lineItems[0], basis: "AMOUNT_ONLY", quantity: null, unitPrice: null,
  };
  let calls = 0;
  const result = await confirmation.submitConfirmedContract(payload, async (submitted) => {
    calls += 1;
    assert.deepEqual(submitted.lineItems[0], payload.lineItems[0]);
    return "formal-contract";
  });
  assert.equal(calls, 1);
  assert.deepEqual(result.errors, []);
});

test("human basis correction clears values that amount-only lines cannot truthfully carry", () => {
  const line = completePayload().lineItems[0]!;
  const corrected = setConfirmedLineItemBasis(line, "AMOUNT_ONLY");

  assert.equal(corrected.basis, "AMOUNT_ONLY");
  assert.equal(corrected.quantity, null);
  assert.equal(corrected.unitPrice, null);
  assert.equal(corrected.declaredAmount, 100);
});

test("human confirmation can add and remove an amount-only line when extraction produced no rows", () => {
  const payload = confirmedPayloadFromCandidate({ ...candidate(), lineItems: [] });

  const added = addAmountOnlyLineItem(payload);
  assert.deepEqual(added.lineItems, [{ itemKey: "", itemName: "", basis: "AMOUNT_ONLY",
    quantity: null, unitPrice: null, declaredAmount: null, sortNo: 1 }]);
  assert.deepEqual(removeConfirmedLineItem(added, 0).lineItems, []);
  assert.deepEqual(payload.lineItems, [], "editing the confirmation must not mutate its prior state");
});

test("human confirmation can add a blank award notice when structured extraction missed it", () => {
  const payload = confirmedPayloadFromCandidate({ ...candidate(), awardAmount: null, awardSupplier: null });
  assert.equal(payload.awardNotice, null);

  const added = addBlankAwardNotice(payload);

  assert.deepEqual(added.awardNotice, { noticeNo: "", projectName: "", winningBidder: "",
    winningBidderCreditCode: null, winningAmount: null, winningDate: "", sourceRef: null });
  assert.equal(payload.awardNotice, null, "editing the confirmation must not mutate its prior state");
});

test("amount-only line with quantity or unit price is rejected before submission", async () => {
  const payload = completePayload();
  payload.lineItems[0] = {
    ...payload.lineItems[0], basis: "AMOUNT_ONLY", quantity: 1, unitPrice: 100,
  };
  let calls = 0;

  const result = await confirmation.submitConfirmedContract(payload, async () => {
    calls += 1;
    return "unexpected";
  });

  assert.equal(calls, 0);
  assert.equal(result.errors.some((error: { field: string }) =>
    error.field === "lineItems[0].basis"), true);
});

test("required contract and line-item fields are validated before promotion", async () => {
  assert.equal(typeof confirmation.submitConfirmedContract, "function");
  const cases = [
    [(payload: ReturnType<typeof completePayload>) => { payload.contractNo = " "; }, "请填写合同编号"],
    [(payload: ReturnType<typeof completePayload>) => { payload.contractName = ""; }, "请填写合同名称"],
    [(payload: ReturnType<typeof completePayload>) => { payload.contractType = ""; }, "请填写合同类型"],
    [(payload: ReturnType<typeof completePayload>) => { payload.partyA = ""; }, "请填写甲方"],
    [(payload: ReturnType<typeof completePayload>) => { payload.partyB = ""; }, "请填写乙方"],
    [(payload: ReturnType<typeof completePayload>) => { payload.contractAmount = null; }, "请填写合同金额"],
    [(payload: ReturnType<typeof completePayload>) => { payload.lineItems[0].itemKey = ""; }, "请填写第 1 条合同明细的项目编号"],
    [(payload: ReturnType<typeof completePayload>) => { payload.lineItems[0].itemName = ""; }, "请填写第 1 条合同明细的项目名称"],
    [(payload: ReturnType<typeof completePayload>) => { payload.lineItems[0].quantity = null; }, "请填写第 1 条合同明细的数量"],
    [(payload: ReturnType<typeof completePayload>) => { payload.lineItems[0].unitPrice = null; }, "请填写第 1 条合同明细的单价"],
    [(payload: ReturnType<typeof completePayload>) => { payload.lineItems[0].declaredAmount = null; }, "请填写第 1 条合同明细的申报金额"],
  ] as const;
  let calls = 0;

  for (const [invalidate, message] of cases) {
    const payload = completePayload();
    invalidate(payload);
    const result = await confirmation.submitConfirmedContract(payload, async () => {
      calls += 1;
      return "unexpected";
    });
    assert.equal(result.errors.some((error: { message: string }) => error.message === message), true);
  }

  assert.equal(calls, 0);
});

test("different durable and extracted project numbers do not participate in promotion validation", async () => {
  assert.equal(typeof confirmation.submitConfirmedContract, "function");
  const durableProjectNo = "XM-2026-0109";
  const extractedProjectNo = "XM-2026-0101";
  assert.notEqual(durableProjectNo, extractedProjectNo);
  let calls = 0;

  const result = await confirmation.submitConfirmedContract(completePayload(), async () => {
    calls += 1;
    return "formal-contract";
  });

  assert.equal(calls, 1);
  assert.deepEqual(result.errors, []);
});
