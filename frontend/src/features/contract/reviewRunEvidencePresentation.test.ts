import assert from "node:assert/strict";
import test from "node:test";

import { createDurableEvidenceFocus } from "./reviewRunEvidencePresentation.ts";
import type { DurableReviewEvidenceView } from "./reviewRunSemanticAdapter.ts";

function evidence(locatorAvailable: boolean): DurableReviewEvidenceView {
  return {
    id: "evidence-1",
    fileId: "file-1",
    fileName: "001.pdf",
    excerpt: "验收完成后支付合同款。",
    locatorAvailable,
    locatorLabel: locatorAvailable ? "第 2 页 · 第 3 段" : "暂无法精确定位原文位置，可查看来源材料。",
    pageNumber: locatorAvailable ? 2 : null,
    paragraphIndex: locatorAvailable ? 3 : null,
    segmentIndex: locatorAvailable ? 4 : null,
    startOffset: locatorAvailable ? 10 : null,
    endOffset: locatorAvailable ? 20 : null,
  };
}

test("validated durable evidence focuses the exact Project material and real locator", () => {
  const focus = createDurableEvidenceFocus(evidence(true), "project-1", new Set(["file-1"]));
  assert.ok(focus);
  assert.equal(focus.materialId, "file-1");
  assert.equal(focus.locatorUnavailable, false);
  assert.deepEqual(focus.source, {
    role: "REVIEW_EVIDENCE",
    projectId: "project-1",
    fileId: "file-1",
    fileName: "001.pdf",
    pageNumber: 2,
    paragraphIndex: 3,
    locator: "第 2 页 · 第 3 段",
    excerpt: "验收完成后支付合同款。",
  });
});

test("unavailable locator opens only its source file without invented coordinates", () => {
  const focus = createDurableEvidenceFocus(evidence(false), "project-1", new Set(["file-1"]));
  assert.ok(focus);
  assert.equal(focus.materialId, "file-1");
  assert.equal(focus.locatorUnavailable, true);
  assert.equal(focus.source, null);
  assert.equal(focus.notice, "暂无法精确定位原文位置，可查看来源材料。");
  assert.doesNotMatch(JSON.stringify(focus), /ai:validated-excerpt/);
});

test("evidence outside current Project materials fails closed", () => {
  assert.equal(createDurableEvidenceFocus(evidence(true), "project-1", new Set(["file-other"])), null);
});

test("validated locator without an excerpt still opens the exact file without inventing text", () => {
  const item = { ...evidence(true), excerpt: "" };
  const focus = createDurableEvidenceFocus(item, "project-1", new Set(["file-1"]));
  assert.ok(focus?.source);
  assert.equal(focus.source.excerpt, "");
  assert.equal(focus.source.fileId, "file-1");
});
