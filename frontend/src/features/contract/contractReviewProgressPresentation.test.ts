import assert from "node:assert/strict";
import test from "node:test";
import { contractReviewProgressPresentation } from "./contractReviewProgressPresentation.ts";

test("contract progress percentage rounds and clamps truthful current/total facts", () => {
  const cases = [
    { current: 1, total: 10, percentage: 10 },
    { current: 7, total: 11, percentage: 64 },
    { current: 10, total: 10, percentage: 100 },
    { current: 4, total: 0, percentage: 0 },
    { current: 12, total: 10, percentage: 100 },
    { current: -2, total: 10, percentage: 0 },
  ];

  for (const item of cases) {
    const presentation = contractReviewProgressPresentation({
      current: item.current,
      total: item.total,
      statusLabel: "审核中",
      complete: false,
      pendingAcknowledgement: false,
    });
    assert.equal(presentation.percentage, item.percentage, `${item.current}/${item.total}`);
    assert.equal(presentation.current, item.current);
    assert.equal(presentation.total, item.total);
  }
});

test("contract progress tone follows real workflow status without changing risk semantics", () => {
  const base = { current: 1, total: 10, complete: false, pendingAcknowledgement: false };
  assert.equal(contractReviewProgressPresentation({ ...base, statusLabel: "待审核" }).tone, "WAITING");
  assert.equal(contractReviewProgressPresentation({ ...base, statusLabel: "审核中" }).tone, "ACTIVE");
  assert.equal(contractReviewProgressPresentation({
    ...base,
    statusLabel: "复核提示待知悉",
    pendingAcknowledgement: true,
  }).tone, "ACKNOWLEDGEMENT");
  assert.equal(contractReviewProgressPresentation({
    ...base,
    current: 10,
    statusLabel: "审核完成",
    complete: true,
  }).tone, "COMPLETE");
});
