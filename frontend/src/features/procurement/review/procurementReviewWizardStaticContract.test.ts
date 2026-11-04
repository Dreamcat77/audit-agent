import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("procurement review ends at reference results and retains optional risk acknowledgement", async () => {
  const wizard = await readFile(new URL("./ProcurementReviewWizard.tsx", import.meta.url), "utf8");
  const app = await readFile(new URL("../../../app/App.tsx", import.meta.url), "utf8");
  const api = await readFile(new URL("../../../api/audit.ts", import.meta.url), "utf8");
  const types = await readFile(new URL("../../../api/types.ts", import.meta.url), "utf8");

  for (const step of [
    "超预算告警",
    "报价规律",
    "查看分析明细与依据",
  ]) assert.match(wizard, new RegExp(step));

  assert.doesNotMatch(wizard, /查看全部步骤|stepsExpanded|处理进度|当前操作 · 第/);
  assert.doesNotMatch(wizard, /Step1QuotationUpload|activeStep === [1234]/);
  assert.match(wizard, /onBatch=/);
  assert.match(wizard, /ProcurementCollusionResults/);
  assert.doesNotMatch(wizard, /Step6CollusionScore|runProcurementReviewBlacklistCheck/);
  assert.match(wizard, /确认已知悉严重风险/);
  assert.match(wizard, /confirmProcurementCriticalRisk/);
  assert.match(wizard, /getProcurementCriticalRiskConfirmation/);
  assert.match(wizard, /返回上传材料/);
  assert.doesNotMatch(wizard, />查看参考结果</);
  assert.doesNotMatch(wizard, /moveNext|materialCompleted/);
  assert.match(wizard, /查看已有结果/);
  assert.doesNotMatch(app.slice(app.indexOf('const NAV_ITEMS:'),app.indexOf('// ── Shared Components')), /procurement-tasks/);
  assert.match(app, /<ProcurementReviewWizard[\s\S]*?embedded/);
  assert.doesNotMatch(app, /继续采购审查|开始采购审查/);
  assert.match(api, /critical-risk-confirmation/);
  assert.match(types, /interface ProcurementCriticalRiskConfirmation/);
});
