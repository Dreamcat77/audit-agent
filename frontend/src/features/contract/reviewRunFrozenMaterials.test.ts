import assert from "node:assert/strict";
import test from "node:test";
import { frozenReviewMaterialTabs } from "./reviewRunFrozenMaterials.ts";
import type { ReviewRunMaterialDto } from "./reviewRunTypes.ts";
import type { ProjectBackedReviewMaterialTab } from "./workspaceTypes.ts";

const tabs = [{ key: "CONTRACT", label: "Contract", files: [
  { id: "frozen-file", fileName: "frozen.docx", fileSha256: "a".repeat(64),
    parseResultId: "parse-1", parseVersion: 1, canonicalTextSha256: "b".repeat(64) },
  { id: "uploaded-later", fileName: "later.docx" },
] }] as ProjectBackedReviewMaterialTab[];
const frozen = [{ fileId: "frozen-file", fileSha256: "a".repeat(64), parseResultId: "parse-1",
  parseVersion: 1, canonicalTextSha256: "b".repeat(64) }] as unknown as ReviewRunMaterialDto[];

test("historical viewer exposes only the exact run's frozen file IDs", () => {
  const visible = frozenReviewMaterialTabs(tabs, frozen, true);
  assert.deepEqual(visible.flatMap(tab => tab.files.map(file => file.id)), ["frozen-file"]);
  assert.deepEqual(tabs[0].files.map(file => file.id), ["frozen-file", "uploaded-later"]);
});

test("historical viewer fails closed when the current parse tuple differs from the frozen run", () => {
  const changed = [{ ...frozen[0], parseVersion: 2 }];
  assert.deepEqual(frozenReviewMaterialTabs(tabs, changed, true), []);
});

test("historical viewer exposes no current files before exact run is loaded", () => {
  assert.deepEqual(frozenReviewMaterialTabs(tabs, null, true), []);
  assert.equal(frozenReviewMaterialTabs(tabs, null, false), tabs);
});
