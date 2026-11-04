import assert from "node:assert/strict";
import { registerHooks } from "node:module";
import test from "node:test";
import type { Contract } from "./types.ts";

const hooks = registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith(".") && !/\.[a-z]+$/i.test(specifier)) {
      return nextResolve(`${specifier}.ts`, context);
    }
    return nextResolve(specifier, context);
  },
});

const { createContractDetailsAmountView } = await import("./contractDetailsPresentation.ts").finally(() => hooks.deregister());

test("contract details render every monetary fact through the exact RMB formatter", () => {
  const contract = {
    id: "contract-exact",
    contractNo: "CT-EXACT",
    contractName: "Exact contract",
    contractType: "PROCUREMENT",
    partyA: "Buyer",
    partyB: "Supplier",
    partyBCreditCode: null,
    contractAmount: 128000.5,
    amountInWords: "  人民币玖仟玖佰玖拾玖万亿元整  ",
    status: "READY_FOR_REVIEW",
    versionNo: 1,
    createdAt: "2026-08-25T00:00:00Z",
    updatedAt: "2026-08-25T00:00:00Z",
    lineItems: [{
      id: "line-exact",
      itemKey: "ITEM-1",
      itemName: "Exact item",
      quantity: 1,
      unitPrice: 1.235,
      declaredAmount: 0.1,
      sortNo: 1,
    }],
    awardNotice: {
      id: "award-exact",
      noticeNo: "AWARD-EXACT",
      projectName: "Exact project",
      winningBidder: "Supplier",
      winningBidderCreditCode: null,
      winningAmount: 128000.5,
      winningDate: "2026-08-25",
      sourceRef: null,
    },
  } as Contract;

  assert.deepEqual(createContractDetailsAmountView(contract), {
    contractAmount: "¥128,000.50",
    amountInWords: "  人民币玖仟玖佰玖拾玖万亿元整  ",
    lineItems: [{
      id: "line-exact",
      unitPrice: "¥1.24",
      declaredAmount: "¥0.10",
    }],
    winningAmount: "¥128,000.50",
  });
});

test("contract details display amount-only lines without fabricated quantity or price", () => {
  const contract = {
    id: "amount-only", contractNo: "C-2", contractName: "Service", contractType: "SERVICE",
    partyA: "Buyer", partyB: "Supplier", partyBCreditCode: null, contractAmount: 28000,
    amountInWords: null, status: "READY_FOR_REVIEW", versionNo: 1,
    createdAt: "2026-09-23T00:00:00Z", updatedAt: "2026-09-23T00:00:00Z",
    lineItems: [{ id: "l-1", itemKey: "S-1", itemName: "Service fee", basis: "AMOUNT_ONLY",
      quantity: null, unitPrice: null, declaredAmount: 28000, sortNo: 1 }], awardNotice: null,
  } as Contract;
  assert.deepEqual(createContractDetailsAmountView(contract).lineItems[0], {
    id: "l-1", unitPrice: null, declaredAmount: "¥28,000.00",
  });
});
