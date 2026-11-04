import assert from "node:assert/strict";
import test from "node:test";

import type {
  ProjectBackedReviewMaterial,
  ProjectBackedReviewMaterialTab,
} from "./workspaceTypes.ts";
import { deriveProjectReviewMaterialSelection } from "./projectReviewMaterialSelection.ts";

const PROJECT_ID = "00000000-0000-0000-0000-000000000201";
const OTHER_PROJECT_ID = "00000000-0000-0000-0000-000000000202";

function material(
  id: string,
  category: string,
  overrides: Partial<ProjectBackedReviewMaterial> = {},
): ProjectBackedReviewMaterial {
  const reviewRole = category === "PACKAGE" ? "UNKNOWN" : category;
  return {
    id,
    projectId: PROJECT_ID,
    fileName: `${id}.pdf`,
    mediaType: "application/pdf",
    classifierCategory: category,
    effectiveRole: reviewRole as ProjectBackedReviewMaterial["effectiveRole"],
    effectiveRoleSource: reviewRole === "UNKNOWN" ? "NO_AUTHORITATIVE_ROLE" : "LEGACY_EFFECTIVE",
    reviewRole: reviewRole as ProjectBackedReviewMaterial["reviewRole"],
    roleDecisionId: null,
    ingestionStatus: "AVAILABLE",
    parseStatus: "PARSED",
    effectiveIdentityStatus: "SAME_PROJECT",
    identityCurrent: true,
    excludedFromProjectUse: false,
    semanticEligible: true,
    statusLabel: "文本已就绪",
    parsedText: `已解析文本 ${id}`,
    provenance: {
      methodLabel: "PDF 文本解析",
      confidenceLabel: null,
      pageCountLabel: "1 页",
    },
    originalUrl: `/api/v1/project-files/${id}/content`,
    ...overrides,
  };
}

const tabs: readonly ProjectBackedReviewMaterialTab[] = [
  {
    key: "CONTRACT",
    label: "合同正文",
    files: [material("contract-file", "CONTRACT_BODY")],
  },
  {
    key: "TENDER",
    label: "招标文件",
    files: [
      material("tender-file", "TENDER_DOCUMENT"),
      material("pending-file", "TENDER_DOCUMENT", {
        ingestionStatus: "UPLOAD_PENDING",
        parseStatus: "PENDING",
        semanticEligible: false,
        parsedText: null,
      }),
    ],
  },
  {
    key: "BID",
    label: "投标文件",
    files: [material("foreign-file", "BID_RESPONSE", { projectId: OTHER_PROJECT_ID })],
  },
  {
    key: "AWARD",
    label: "中标通知书",
    files: [material("blank-file", "AWARD_NOTICE", { parsedText: "   " })],
  },
  {
    key: "OTHER",
    label: "其他材料",
    files: [
      material("supplement-file", "SUPPLEMENT"),
      material("package-file", "PACKAGE"),
    ],
  },
];

test("defaults to every eligible durable material in stable Project order", () => {
  const selection = deriveProjectReviewMaterialSelection(tabs, PROJECT_ID);

  assert.deepEqual(selection.eligibleFileIds, [
    "contract-file",
    "tender-file",
    "supplement-file",
  ]);
  assert.deepEqual(selection.selectedFileIds, selection.eligibleFileIds);
  assert.equal(selection.canStart, true);
  assert.equal(selection.blockingMessage, null);
  assert.equal(selection.warningMessage, null);
});

test("an explicit empty selection disables intelligent review", () => {
  const selection = deriveProjectReviewMaterialSelection(tabs, PROJECT_ID, []);

  assert.deepEqual(selection.selectedFileIds, []);
  assert.equal(selection.canStart, false);
  assert.match(selection.blockingMessage ?? "", /至少选择一份/);
});

test("missing selected contract body blocks while missing cross-material evidence only warns", () => {
  const withoutContract = deriveProjectReviewMaterialSelection(
    tabs,
    PROJECT_ID,
    ["tender-file"],
  );
  assert.equal(withoutContract.canStart, false);
  assert.match(withoutContract.blockingMessage ?? "", /合同正文/);

  const contractOnly = deriveProjectReviewMaterialSelection(
    tabs,
    PROJECT_ID,
    ["contract-file"],
  );
  assert.equal(contractOnly.canStart, true);
  assert.equal(contractOnly.blockingMessage, null);
  assert.match(contractOnly.warningMessage ?? "", /跨材料/);
});

test("ignores ineligible, cross-project, blank-text, and package IDs even if requested", () => {
  const selection = deriveProjectReviewMaterialSelection(
    tabs,
    PROJECT_ID,
    [
      "package-file",
      "foreign-file",
      "blank-file",
      "pending-file",
      "contract-file",
      "contract-file",
    ],
  );

  assert.deepEqual(selection.selectedFileIds, ["contract-file"]);
  assert.equal(selection.canStart, true);
});

test("excludes a human-confirmed foreign-project material even when its file belongs to this Project", () => {
  const foreignIdentityMaterial = {
    ...material("foreign-award-file", "AWARD_NOTICE"),
    effectiveIdentityStatus: "NOT_SAME_PROJECT",
    identityCurrent: true,
    excludedFromProjectUse: true,
  };
  const selection = deriveProjectReviewMaterialSelection(
    [{
      key: "CONTRACT",
      label: "合同正文",
      files: [material("contract-file", "CONTRACT_BODY")],
    }, {
      key: "AWARD",
      label: "中标通知书",
      files: [foreignIdentityMaterial],
    }],
    PROJECT_ID,
    ["contract-file", "foreign-award-file"],
  );

  assert.deepEqual(selection.eligibleFileIds, ["contract-file"]);
  assert.deepEqual(selection.selectedFileIds, ["contract-file"]);
});

test("warns for normalized same-name same-category materials without blocking or changing selection", () => {
  const duplicateTabs: readonly ProjectBackedReviewMaterialTab[] = [{
    key: "CONTRACT",
    label: "合同正文",
    files: [
      material("contract-copy-a", "CONTRACT_BODY", { fileName: "  合同Ａ.PDF  " }),
      material("contract-copy-b", "CONTRACT_BODY", { fileName: "合同a.pdf" }),
    ],
  }];

  const selection = deriveProjectReviewMaterialSelection(duplicateTabs, PROJECT_ID);

  assert.deepEqual(selection.selectedFileIds, ["contract-copy-a", "contract-copy-b"]);
  assert.equal(selection.canStart, true);
  assert.equal(selection.blockingMessage, null);
  assert.equal(
    selection.duplicateWarningMessage,
    "检测到同名同类别材料，请确认是否为重复版本",
  );
});

test("same normalized filename in different categories is not labeled as a duplicate", () => {
  const sameNameTabs: readonly ProjectBackedReviewMaterialTab[] = [
    {
      key: "CONTRACT",
      label: "合同正文",
      files: [material("contract-copy", "CONTRACT_BODY", { fileName: "采购资料.pdf" })],
    },
    {
      key: "TENDER",
      label: "招标文件",
      files: [material("tender-copy", "TENDER_DOCUMENT", { fileName: "采购资料.pdf" })],
    },
  ];

  const selection = deriveProjectReviewMaterialSelection(sameNameTabs, PROJECT_ID);

  assert.equal(selection.duplicateWarningMessage, null);
});

test("reports core completeness and marks OTHER as optional", () => {
  const selection = deriveProjectReviewMaterialSelection(tabs, PROJECT_ID);

  assert.deepEqual(selection.completeness, [
    { key: "CONTRACT", label: "合同正文", status: "UPLOADED" },
    { key: "TENDER", label: "采购/招标文件", status: "UPLOADED" },
    { key: "BID", label: "投标/响应文件", status: "MISSING" },
    { key: "AWARD", label: "中标通知书", status: "UNREADABLE" },
    { key: "OTHER", label: "其他附件", status: "OPTIONAL" },
  ]);
});
