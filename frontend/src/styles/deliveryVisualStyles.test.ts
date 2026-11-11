import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("the authenticated portal stylesheet is included by the global style entry", async () => {
  const styles = await readFile(new URL("./index.css", import.meta.url), "utf8");
  assert.match(styles, /@import ['"]\.\/delivery-visual-v2\.css['"]/);
});

test("authenticated pages share 40px form and action control heights", async () => {
  const deliveryStyles = await readFile(new URL("./delivery-visual-v2.css", import.meta.url), "utf8");
  const historyStyles = await readFile(new URL("./history-results.css", import.meta.url), "utf8");

  assert.match(deliveryStyles, /\.portal-main input[^\{]*,\s*\.portal-main select\s*\{[^}]*height:\s*40px/s);
  assert.match(deliveryStyles, /\.portal-main button\.inline-flex\.items-center[^\{]*,\s*\.portal-main a\.inline-flex\.items-center\s*\{[^}]*height:\s*40px/s);
  assert.match(deliveryStyles, /\.portal-main button:not\(\[aria-label\]\):not\(\[title\]\):not\(\[class~="text-left"\]\)[^\{]*\{[^}]*height:\s*40px/s);
  assert.match(deliveryStyles, /\.delivery-primary-action,[\s\S]*?\.delivery-secondary-action\s*\{[^}]*height:\s*40px/s);
  assert.match(historyStyles, /\.history-button\s*\{[^}]*height:40px/s);
});

test("budget and procurement workspaces use the shared page header and width", async () => {
  const budgetPage = await readFile(new URL("../app/BudgetCheckPage.tsx", import.meta.url), "utf8");
  const procurementPage = await readFile(new URL("../features/procurement/AutomaticCasePanel.tsx", import.meta.url), "utf8");

  assert.match(budgetPage, /historyOpen\?"history-page":"delivery-page"/);
  assert.match(budgetPage, /historyOpen\?"history-page-header":"delivery-page-header"/);
  assert.match(procurementPage, /"delivery-page result-workspace procurement-results space-y-5"/);
  assert.match(procurementPage, /"delivery-page-header"/);
});

test("work and history pages share desktop and responsive content geometry", async () => {
  const deliveryStyles = await readFile(new URL("./delivery-visual-v2.css", import.meta.url), "utf8");
  const historyStyles = await readFile(new URL("./history-results.css", import.meta.url), "utf8");

  assert.match(deliveryStyles, /\.delivery-page\s*\{[^}]*max-width:\s*1440px;[^}]*padding:\s*24px/s);
  assert.match(historyStyles, /\.history-page\s*\{[^}]*max-width:1440px;[^}]*padding:24px/s);
  assert.match(historyStyles, /\.procurement-page-shell:has\(> \.delivery-page\)\s*\{padding:0\}/);
  assert.match(deliveryStyles, /@media\s*\(max-width:\s*900px\)[\s\S]*?\.delivery-page\s*\{[^}]*padding:\s*20px/s);
  assert.match(historyStyles, /@media\(max-width:900px\)\s*\{\.history-page\s*\{padding:20px/s);
});
