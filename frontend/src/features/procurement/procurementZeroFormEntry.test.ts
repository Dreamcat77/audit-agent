import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("procurement page defaults to automatic batch upload and keeps formal management explicit", async () => {
  const app = await readFile(new URL("../../app/App.tsx", import.meta.url), "utf8");
  const panel = await readFile(new URL("./AutomaticCasePanel.tsx", import.meta.url), "utf8");
  const pageStart = app.indexOf("if (!selectedProcurement) {");
  const pageEnd = app.indexOf("if (!selectedProject) {", pageStart);
  assert.notEqual(pageStart, -1);
  assert.notEqual(pageEnd, -1);
  const page = app.slice(pageStart, pageEnd);

  assert.match(app, /const \[showFormalProcurementManagement, setShowFormalProcurementManagement\] = useState\(false\)/);
  assert.match(page, /!showFormalProcurementManagement && <AutomaticCasePanel/);
  assert.doesNotMatch(page, /onLegacyHistory=/);
  assert.doesNotMatch(panel, /查看早期采购记录/);

  const formalStart = page.indexOf("{showFormalProcurementManagement && <section");
  const formalEnd = page.indexOf("</section>}", formalStart);
  assert.notEqual(formalStart, -1);
  assert.notEqual(formalEnd, -1);
  const formalManagement = page.slice(formalStart, formalEnd);
  assert.doesNotMatch(formalManagement, /新建采购需求/);
  assert.match(formalManagement, /返回采购首页/);
  assert.match(formalManagement, /<NewProcurementDialog/);
  assert.match(formalManagement, /<ConfirmDialog/);
  assert.doesNotMatch(page.slice(0, formalStart), /<NewProcurementDialog/);

  assert.match(panel, /<input type="file" multiple/);
  assert.match(panel, /onChange=\{e => \{ const selected = Array\.from\(e\.target\.files \|\| \[\]\); e\.target\.value = ''; void upload\(selected\); \}\}/);
  assert.match(panel, /body\.append\('files', f\)/);
  assert.match(panel, /\/history/);
  assert.doesNotMatch(panel, /\{!caseProcessing&&report && <section/);
});
