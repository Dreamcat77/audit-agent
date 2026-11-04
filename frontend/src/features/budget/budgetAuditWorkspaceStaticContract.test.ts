import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("budget workspace keeps F01 and exposes the durable F05 split-risk slice", async () => {
  const workspace = await readFile(new URL("./BudgetAuditWorkspace.tsx", import.meta.url), "utf8");
  const panel = await readFile(new URL("./f05/BudgetProjectRiskPanel.tsx", import.meta.url), "utf8");
  const f01 = await readFile(new URL("../../app/BudgetCheckPage.tsx", import.meta.url), "utf8");
  const app = await readFile(new URL("../../app/App.tsx", import.meta.url), "utf8");

  assert.match(app, /BudgetAuditWorkspace/);
  assert.match(workspace, /<BudgetCheckPage onOpenProcurement=\{onOpenProcurement\}\s*\/>/);
  assert.match(f01, /BudgetProjectRiskPanel/);
  assert.match(panel, /material-risk/);
  assert.match(panel, /riskOperation/);
  assert.match(panel, /预算项目风险分析/);
  assert.match(panel, /groupRiskCandidates/);
  assert.match(panel, /历史分析结果/);
  assert.doesNotMatch(panel, /进入采购审查|listProcurementProjects|LAST_ASSESSMENT_KEY/);
  assert.doesNotMatch(f01, /label: '数据源状态'/);
  assert.doesNotMatch(f01, /数据源：DM8 \/ ACTIVE 映射/);
  assert.doesNotMatch(f01, /<h2[^>]*>预算批复数据<\/h2>/);
  assert.doesNotMatch(f01, /\['最近校验批次'|\['历史异常记录'|\['校验规则配置'/);
  assert.doesNotMatch(panel, /F05 项目拆分风险提示|未发现明显拆分风险/);
  assert.doesNotMatch(`${workspace}\n${panel}`, /splitRiskCases|fake OA|已从 OA 获取|OA 自动读取/);
});
