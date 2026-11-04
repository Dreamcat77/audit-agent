import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("Project detail keeps the existing review workspace and supplies durable Project context", async () => {
  const source = await readFile(new URL("../project/components/ProjectDetailPage.tsx", import.meta.url), "utf8");
  assert.equal((source.match(/<ContractAuditWorkspace/g) ?? []).length, 1);
  assert.match(source, /projectId=\{projectId\}/);
  assert.match(source, /projectContract=\{/);
  assert.match(source, /projectMaterials=\{reviewMaterials\}/);
});

test("existing two-panel workspace selects durable orchestration for Project mode", async () => {
  const source = await readFile(new URL("./components/ContractReviewWorkspace.tsx", import.meta.url), "utf8");
  for (const existingComponent of [
    "ProjectMaterialsViewer", "ProjectReviewMaterialPicker", "IntelligentReviewPipeline",
    "F02ReviewResultPanel", "AiSemanticReviewPanel", "ResizablePanelGroup",
  ]) assert.match(source, new RegExp(existingComponent));
  assert.match(source, /useProjectContractReviewRun/);
  assert.match(source, /ContractReviewReportPanel/);
  assert.match(source, /closeoutContext/);
  assert.match(source, /const closeoutContext = useMemo\([\s\S]*?runId: durableReview\.run\.runId/);
  assert.match(source, /retryFailedStep/);
  assert.match(source, /projectBacked\s*\?\s*durableReview/);
  assert.match(source, /disabled=\{Boolean\(durableReview\.run\)\}/);
  assert.match(source, /if \(durableReview\.run\) \{[\s\S]*?presentation\?\.canContinue[\s\S]*?continuePending\(\)[\s\S]*?return;[\s\S]*?if \(!materialSelection\?\.canStart/);
  assert.match(source, /const projectReviewActionReady = durableReview\.run[\s\S]*?presentation\?\.canContinue[\s\S]*?: materialSelection\?\.canStart/);
  assert.match(source, /durableReview\.presentation\?\.canStartNew/);
  assert.doesNotMatch(source, /durableReview\.run && !integratedReviewRunning && \(/);
  const picker = await readFile(new URL("./components/ProjectReviewMaterialPicker.tsx", import.meta.url), "utf8");
  assert.match(picker, /本次审核材料已确认。如需更换材料，请重新发起一次审核。/);
  assert.doesNotMatch(source, /function\s+ProjectContractReviewWorkspace/);
});

test("durable non-F02 issues use the closeout acknowledgement component while F02 keeps its existing path", async () => {
  const workspace = await readFile(new URL("./components/ContractReviewWorkspace.tsx", import.meta.url), "utf8");
  assert.match(workspace, /ContractFindingAcknowledgement/);
  assert.match(workspace, /renderDurableAcknowledgement/);
  assert.match(workspace, /onAcknowledgeFinding=\{projectBacked \? undefined : acknowledgeFinding\}/);
});

test("normal review workspace does not expose the technical debug bundle", async () => {
  const source = await readFile(new URL("./components/ContractReviewWorkspace.tsx", import.meta.url), "utf8");
  assert.doesNotMatch(source, /SanitizedDebugBundle|DebugBundle|debug-bundles/);
});

test("legacy direct flow remains explicitly isolated from Project-backed actions", async () => {
  const source = await readFile(new URL("./components/ContractReviewWorkspace.tsx", import.meta.url), "utf8");
  assert.match(source, /useContractReviewWorkspace\([\s\S]*?!projectBacked[\s\S]*?\)/);
  assert.match(source, /if \(projectBacked\)[\s\S]*?durableReview\.(start|continuePending)/);
  assert.match(source, /startIntelligentReviewBranches/);
});

test("Project-backed blocked continuation exposes only the explicit durable restart action", async () => {
  const source = await readFile(new URL("./components/ContractReviewWorkspace.tsx", import.meta.url), "utf8");
  assert.match(source, /const restartRequired = projectBacked\s*\?\s*durableReview\.restartRequired\s*:\s*legacyReview\.restartRequired/);
  assert.match(source, /buttonLabel=\{durableReview\.run[\s\S]*?durableReview\.restartRequired[\s\S]*?请重新发起审核/);
  assert.match(source, /durableReview\.run && durableReview\.presentation\?\.canStartNew && !integratedReviewRunning/);
  assert.match(source, /onClick=\{\(\) => \{[\s\S]*?durableReview\.startNew\(\)[\s\S]*?重新发起审核/);
  assert.doesNotMatch(source, /const restartRequired = projectBacked\s*\?\s*false/);
});
