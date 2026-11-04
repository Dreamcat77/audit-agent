import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import { locateProjectMaterialText, projectMaterialFileOnlyNotice } from "./projectMaterialViewerPresentation.ts";
import { selectProjectMaterialSource } from "./projectMaterialSourceSelection.ts";
import { frozenReviewMaterialTabs } from "./reviewRunFrozenMaterials.ts";
import { createDurableEvidenceFocus } from "./reviewRunEvidencePresentation.ts";
import type { DurableReviewEvidenceView } from "./reviewRunSemanticAdapter.ts";
import type { FindingSourceAnchor } from "./types.ts";
import type { ProjectBackedReviewMaterialTab } from "./workspaceTypes.ts";

const tabs = [
  { key: "CONTRACT", label: "合同", files: [
    { id: "contract-v1", projectId: "project-1", fileName: "合同.pdf", parsedText: "第一段\n重复文字\n第三段\n重复文字" },
  ] },
  { key: "AWARD", label: "中标", files: [
    { id: "award-v1", projectId: "project-1", fileName: "中标通知.pdf", parsedText: "中标金额100元" },
  ] },
] as unknown as ProjectBackedReviewMaterialTab[];

function source(fileId: string, excerpt: string, paragraphIndex: number | null,
  pageNumber: number | null = null): FindingSourceAnchor {
  return {
    role: "REVIEW_EVIDENCE", projectId: "project-1", fileId, fileName: "source.pdf",
    excerpt, paragraphIndex, pageNumber, locator: pageNumber ? `pdf/page:${pageNumber}` : "frozen:text",
  };
}

test("L01 unique text selects the exact file and creates an exact highlight", () => {
  assert.deepEqual(selectProjectMaterialSource(tabs, source("award-v1", "中标金额100元", null)), {
    tabKey: "AWARD", fileId: "award-v1", viewerMode: "LOCATION",
  });
  assert.equal(locateProjectMaterialText("前文\n中标金额100元\n后文",
    { excerpt: "中标金额100元", paragraphIndex: null }).kind, "EXACT");
});

test("L02 paragraph locator remains honest when repeated text is not unique", () => {
  const result = locateProjectMaterialText("第一段\n重复文字\n第三段\n重复文字",
    { excerpt: "重复文字", paragraphIndex: 4 });
  assert.equal(result.kind, "PARAGRAPH");
  if (result.kind === "PARAGRAPH") assert.equal(result.paragraphArrayIndex, 3);
});

test("L03 PDF page stays attached to the exact frozen source anchor", () => {
  const selected = selectProjectMaterialSource(tabs, source("contract-v1", "第一段", null, 2));
  assert.equal(selected?.fileId, "contract-v1");
  assert.equal(source("contract-v1", "第一段", null, 2).pageNumber, 2);
});

test("L04 two locations in one file remain separate source identities", () => {
  const first = source("contract-v1", "第一段", 1);
  const second = source("contract-v1", "第三段", 3);
  assert.equal(selectProjectMaterialSource(tabs, first)?.fileId, "contract-v1");
  assert.equal(selectProjectMaterialSource(tabs, second)?.fileId, "contract-v1");
  assert.notEqual(first.paragraphIndex, second.paragraphIndex);
});

test("L05 contract and award dual evidence open their own tabs and file IDs", () => {
  const contract = selectProjectMaterialSource(tabs, source("contract-v1", "第一段", 1));
  const award = selectProjectMaterialSource(tabs, source("award-v1", "中标金额100元", 1));
  assert.deepEqual([contract?.tabKey, contract?.fileId], ["CONTRACT", "contract-v1"]);
  assert.deepEqual([award?.tabKey, award?.fileId], ["AWARD", "award-v1"]);
});

test("L06 multi-source clause keeps every independently clickable material", () => {
  const values = [source("contract-v1", "第一段", 1), source("award-v1", "中标金额100元", 1)]
    .map((value) => selectProjectMaterialSource(tabs, value));
  assert.deepEqual(values.map((value) => value?.fileId), ["contract-v1", "award-v1"]);
});

test("L07 historical tabs use frozen file IDs even when a current file has the same name", () => {
  const current = [{ key: "CONTRACT", label: "合同", files: [
    { id: "contract-v1", fileName: "合同.pdf", fileSha256: "a".repeat(64),
      parseResultId: "parse-v1", parseVersion: 1, canonicalTextSha256: "b".repeat(64) },
    { id: "contract-v2", fileName: "合同.pdf" },
  ] }] as ProjectBackedReviewMaterialTab[];
  const frozen = frozenReviewMaterialTabs(current, [{ fileId: "contract-v1", fileSha256: "a".repeat(64),
    parseResultId: "parse-v1", parseVersion: 1, canonicalTextSha256: "b".repeat(64) }] as never[], true);
  assert.deepEqual(frozen[0]?.files.map((value) => value.id), ["contract-v1"]);
});

test("L08 duplicate text without a proven paragraph never claims a unique highlight", () => {
  assert.equal(locateProjectMaterialText("重复文字\n重复文字",
    { excerpt: "重复文字", paragraphIndex: null }).kind, "AMBIGUOUS");
});

test("L09 locator-unavailable evidence opens only the exact source file", () => {
  const evidence: DurableReviewEvidenceView = {
    id: "evidence-1", fileId: "contract-v1", fileName: "合同.pdf", excerpt: "",
    locatorAvailable: false, locatorLabel: projectMaterialFileOnlyNotice(), pageNumber: null,
    paragraphIndex: null, segmentIndex: null, startOffset: null, endOffset: null,
  };
  const focus = createDurableEvidenceFocus(evidence, "project-1", new Set(["contract-v1"]));
  assert.equal(focus?.materialId, "contract-v1");
  assert.equal(focus?.source, null);
  assert.equal(focus?.locatorUnavailable, true);
});

test("L10-L12 workspace clears source focus on exact run switch and never falls back to live files", async () => {
  const workspace = await readFile(new URL("./components/ContractReviewWorkspace.tsx", import.meta.url), "utf8");
  assert.match(workspace, /focusedRunIdRef\.current === runId/);
  assert.match(workspace, /setFocusedProjectSource\(null\)/);
  assert.match(workspace, /frozenReviewMaterialTabs\([\s\S]*?Boolean\(historicalRunId\)/);
  assert.doesNotMatch(workspace, /historicalRunId[\s\S]{0,100}projectMaterials\s*\?\?\s*\[\]/);
});
