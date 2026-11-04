import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { demoVisibleNavigationItems } from "./demoNavigation.ts";

test("sidebar exposes the integrated business tools without Project as a required entry", () => {
  const allItems = [
    "dashboard", "project", "procurement", "budget-check", "bidding", "contract",
    "settlement", "risk", "blacklist", "agent", "ai-tools", "model-eval", "api-test", "system",
  ].map((id) => ({ id, label: id }));

  assert.deepEqual(
    demoVisibleNavigationItems(allItems).map((item) => item.id),
    ["dashboard", "budget-check", "procurement", "contract", "blacklist", "risk"],
  );
});

test("app routes every visible integration entry to its concrete workspace", async () => {
  const app = await readFile(new URL("./App.tsx", import.meta.url), "utf8");

  assert.match(app, /dashboard:\s*<DeliveryWorkbenchPage/);
  assert.match(app, /procurement:\s*<ProcurementPage/);
  assert.match(app, /"budget-check":\s*<BudgetAuditWorkspace/);
  assert.match(app, /contract:\s*<ContractPage/);
  assert.match(app, /blacklist:\s*<BlacklistAlertPage/);
  assert.match(app, /risk:\s*<RiskAssessmentPage/);
});

test("customer navigation uses the approved six business labels without fake badges", async () => {
  const app = await readFile(new URL("./App.tsx", import.meta.url), "utf8");
  for (const [id, label] of [
    ["dashboard", "工作台"], ["budget-check", "预算审查"], ["procurement", "采购审查"],
    ["contract", "合同审核"], ["blacklist", "黑名单"], ["risk", "风险预估"],
  ]) {
    assert.match(app, new RegExp(`id: "${id}", label: "${label}"`));
  }
  assert.doesNotMatch(app, /id: "(?:procurement|risk|blacklist)"[^\n]*badge:/);
});

test("dashboard contains no unsupported accuracy metric or timer-driven AI activity", async () => {
  const app = await readFile(new URL("./App.tsx", import.meta.url), "utf8");
  const dashboard = await readFile(
    new URL("../features/dashboard/DeliveryWorkbenchPage.tsx", import.meta.url),
    "utf8",
  );
  assert.match(app, /dashboard:\s*<DeliveryWorkbenchPage/);
  assert.match(app, /type ContractNavigationIntent/);
  assert.match(app, /"OPEN_ANALYSIS"/);
  assert.match(app, /"OPEN_LIST"/);
  assert.match(app, /"OPEN_TASK"/);
  assert.match(app, /"CREATE_CONTRACT"/);
  assert.match(dashboard, /dataSource\.summary/);
  assert.match(dashboard, /dataSource\.list/);
  assert.match(dashboard, /title: "预算审查"/);
  assert.match(dashboard, /title: "采购审查"/);
  assert.match(dashboard, /title: "合同审核"/);
  assert.doesNotMatch(dashboard, /项目管理|onOpenProjects/);
  assert.doesNotMatch(dashboard, /平均准确率|97\.7%|accuracy|准确率/);
  assert.doesNotMatch(dashboard, /agentTaskPool|setInterval|setTimeout|智能体实时运行状态/);
  assert.doesNotMatch(dashboard, /\b47\b|\b16\b|\b1284\b/);
});
