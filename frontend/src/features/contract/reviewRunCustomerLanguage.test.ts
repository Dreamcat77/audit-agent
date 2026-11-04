import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const CUSTOMER_COMPONENTS = [
  "./components/ContractReviewWorkspace.tsx",
  "./components/IntelligentReviewPipeline.tsx",
  "./components/F02ReviewResultPanel.tsx",
  "./components/AiSemanticReviewPanel.tsx",
  "./components/ProjectMaterialsViewer.tsx",
];

test("Project-backed customer JSX contains no literal backend resource or enum labels", async () => {
  const forbidden = /ReviewRun|ReviewStep|MATERIAL_VALIDATION|F02_DETERMINISTIC|SEMANTIC_AI|MODEL_ASSISTED|safeReasonCode|inputSnapshotIdentity|policyVersion|sha256/i;
  for (const file of CUSTOMER_COMPONENTS) {
    const source = await readFile(new URL(file, import.meta.url), "utf8");
    const literalCustomerText = [...source.matchAll(/>\s*([^<{\r\n]+?)\s*</g)]
      .map((match) => match[1] ?? "").join(" ");
    assert.doesNotMatch(literalCustomerText, forbidden, file);
  }
});

test("durable components use business language and the approved locator fallback", async () => {
  const workspace = await readFile(new URL("./components/ContractReviewWorkspace.tsx", import.meta.url), "utf8");
  const semantic = await readFile(new URL("./components/AiSemanticReviewPanel.tsx", import.meta.url), "utf8");
  const viewer = await readFile(new URL("./projectMaterialViewerPresentation.ts", import.meta.url), "utf8");
  assert.match(workspace, /重新发起审核/);
  assert.match(workspace, /继续本次审核/);
  assert.match(semantic, /建议：/);
  assert.match(viewer, /暂无法精确定位原文位置，可查看来源材料。/);
  assert.doesNotMatch(workspace, /\{durableReview\.run\?\.runId\}/);
  assert.doesNotMatch(semantic, /modelProvider|modelRevision|promptVersion/);
});

test("server safe reason is never interpolated directly into customer JSX", async () => {
  for (const file of CUSTOMER_COMPONENTS) {
    const source = await readFile(new URL(file, import.meta.url), "utf8");
    assert.doesNotMatch(source, />\s*\{[^}]*safeReasonCode[^}]*\}\s*</, file);
  }
});
