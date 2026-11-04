import assert from "node:assert/strict";
import test from "node:test";

const presentation = await import("./budgetApprovalProgramPresentation.ts").catch(() => ({}));

test("F01 can enter F04 only from the durable REVIEWED state", () => {
  assert.equal(typeof presentation.canProceedToApprovalProgram, "function");
  assert.equal(presentation.canProceedToApprovalProgram("REVIEWED"), true);
  for (const status of ["UPLOADED", "PARSED", "WAITING_CONFIRMATION", "FAILED", null]) {
    assert.equal(presentation.canProceedToApprovalProgram(status), false);
  }
});

test("workflow steps are display-only and stop at the real F04 capability", () => {
  const steps = presentation.budgetWorkflowSteps("F04");
  assert.deepEqual(steps.map((step: { title: string }) => step.title), [
    "预算材料上传与解析",
    "科目额度一致性校验",
    "审批程序规范性校验",
    "项目拆分风险检测",
    "生成预算审查报告",
  ]);
  assert.equal(steps.every((step: { navigable: boolean }) => step.navigable === false), true);
  assert.equal(steps[3].subtitle, "同科目、同供应商 30 天窗口风险分析");
  assert.equal(steps[3].statusLabel, "已在预算校验页接入 F05");
  assert.equal(steps[4].statusLabel, "完成后续预算审查环节后生成报告");
});

test("actual approval steps start empty and preserve explicit ordered editing", () => {
  assert.deepEqual(presentation.addActualStep([], "REQUEST_APPROVAL"), ["REQUEST_APPROVAL"]);
  assert.deepEqual(
    presentation.addActualStep(["REQUEST_APPROVAL"], "FINANCE_REVIEW"),
    ["REQUEST_APPROVAL", "FINANCE_REVIEW"],
  );
  assert.deepEqual(
    presentation.addActualStep(["REQUEST_APPROVAL"], "REQUEST_APPROVAL"),
    ["REQUEST_APPROVAL"],
  );
  assert.deepEqual(
    presentation.moveActualStep(["REQUEST_APPROVAL", "FINANCE_REVIEW"], 1, -1),
    ["FINANCE_REVIEW", "REQUEST_APPROVAL"],
  );
  assert.deepEqual(
    presentation.removeActualStep(["REQUEST_APPROVAL", "FINANCE_REVIEW"], 0),
    ["FINANCE_REVIEW"],
  );
});

test("result presentation distinguishes pass and each real risk dimension without final approval claims", () => {
  assert.deepEqual(presentation.validationResultPresentation({
    passed: true,
    missingProcedureSteps: [],
    procedureOrderValid: true,
    authorityMatched: true,
  }), {
    title: "审批程序校验完成",
    detail: "程序符合当前规则",
    tone: "PASS",
  });
  assert.equal(presentation.validationResultPresentation({
    passed: false,
    missingProcedureSteps: [{ code: "REQUEST_APPROVAL", displayName: "请示报批" }],
    procedureOrderValid: false,
    authorityMatched: true,
  }).title, "发现审批程序缺失");
  assert.equal(presentation.validationResultPresentation({
    passed: false,
    missingProcedureSteps: [],
    procedureOrderValid: false,
    authorityMatched: true,
  }).detail, "审批环节顺序不符合当前规则");
  assert.equal(presentation.validationResultPresentation({
    passed: false,
    missingProcedureSteps: [],
    procedureOrderValid: true,
    authorityMatched: false,
  }).title, "审批权限层级不符合当前规则");
  assert.doesNotMatch(
    JSON.stringify(presentation.validationResultPresentation({
      passed: true,
      missingProcedureSteps: [],
      procedureOrderValid: true,
      authorityMatched: true,
    })),
    /最终审核通过|系统已批准|合法|违法/,
  );
});
