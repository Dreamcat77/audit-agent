import assert from "node:assert/strict";
import test from "node:test";
import { reportStatusLabel, closeoutError, canGenerateReport } from "./closeoutPresentation.ts";
test("customer wording does not leak arbitrary server errors or technical status", () => {
  for (const status of ["PENDING", "GENERATING", "AVAILABLE", "FAILED", "UNKNOWN", "unexpected-uuid-policyVersion"]) {
    assert.doesNotMatch(reportStatusLabel(status), /[A-Za-z0-9]/);
  }
  assert.equal(reportStatusLabel("UNKNOWN"), "结果状态待确认");
  assert.doesNotMatch(closeoutError(new Error("backend exception " + "a".repeat(64))), /backend|exception|a{64}/);
  assert.equal(canGenerateReport("COMPLETED"), true);
  for (const status of ["UNKNOWN", "FAILED", "PARTIALLY_COMPLETED"]) assert.equal(canGenerateReport(status), true);
  for (const status of ["PENDING", "RUNNING"]) assert.equal(canGenerateReport(status), false);
});
