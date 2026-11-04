import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { registerHooks } from "node:module";
import test from "node:test";

const hooks = registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith(".") && !/\.[a-z]+$/i.test(specifier)) return nextResolve(`${specifier}.ts`, context);
    return nextResolve(specifier, context);
  },
});

const { archivedPackagePresentation, candidateFactGroups, candidateNavigation, canSelectNewUpload,
  filePresentation, projectMaterialCandidateGroups, provenPromotedContractId, sourcePresentation } =
  await import("./projectDetailViewModel.ts").finally(() => hooks.deregister());

test("file states preserve category, pending, failed and unsupported semantics", () => {
  const base = { id: "f", projectId: "p", parentArchiveFileId: null, normalizedRelativePath: null,
    originalFilename: "file.docx", mediaType: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    category: "CONTRACT_BODY", ingestionStatus: "AVAILABLE", parseStatus: "PARSED", sizeBytes: 10,
    parsedText: "real extracted text", parseMethod: "DOCX_TEXT", parseEngine: "Apache POI",
    parseConfidence: null, pageCount: null, parseDurationMs: 12,
    safeReasonCode: null, createdAt: "now", updatedAt: "now" };
  assert.deepEqual(filePresentation(base), {
    categoryLabel: "合同正文", statusLabel: "已解析", action: null,
    originalMode: "DOWNLOAD", parsedText: "real extracted text", provenance: {
      methodLabel: "Word 文本解析", confidenceLabel: null, pageCountLabel: null,
    },
  });
  assert.deepEqual(filePresentation({ ...base, category: "TENDER_DOCUMENT", ingestionStatus: "UPLOAD_PENDING",
    parseStatus: "PENDING", parsedText: null, parseMethod: null, parseEngine: null, parseDurationMs: null }), {
    categoryLabel: "招标文件", statusLabel: "处理中", action: "继续处理",
    originalMode: "DOWNLOAD", parsedText: null, provenance: {
      methodLabel: "—", confidenceLabel: null, pageCountLabel: null,
    },
  });
  assert.deepEqual(filePresentation({ ...base, category: "BID_RESPONSE", ingestionStatus: "UPLOAD_FAILED",
    parseStatus: "FAILED", parsedText: null, parseMethod: null, parseEngine: null, parseDurationMs: null,
    safeReasonCode: "UPLOAD_FAILED" }), {
    categoryLabel: "投标文件", statusLabel: "上传失败", action: null,
    originalMode: "DOWNLOAD", parsedText: null, provenance: {
      methodLabel: "—", confidenceLabel: null, pageCountLabel: null,
    },
  });
  assert.equal(filePresentation({ ...base, category: "UNKNOWN", parseStatus: "UNSUPPORTED", parsedText: null }).statusLabel,
    "当前版本暂不支持文本解析");
});

test("OCR and PDFBox provenance use factual labels and never invent missing confidence", () => {
  const base = { id: "f", projectId: "p", parentArchiveFileId: null, normalizedRelativePath: null,
    originalFilename: "scan.pdf", mediaType: "application/pdf", category: "CONTRACT_BODY",
    ingestionStatus: "AVAILABLE", parseStatus: "PARSED", sizeBytes: 10,
    parsedText: "OCR durable text", parseMethod: "OCR", parseEngine: "PaddleOCR-3.7.0",
    parseConfidence: 0.9123, pageCount: 2, parseDurationMs: 183,
    safeReasonCode: null, createdAt: "now", updatedAt: "now" };
  assert.deepEqual(filePresentation(base).provenance, {
    methodLabel: "OCR 文字识别", confidenceLabel: "91.2%", pageCountLabel: "2 页",
  });
  assert.deepEqual(filePresentation({ ...base, parseMethod: "PDF_TEXT", parseEngine: "Apache PDFBox",
    parseConfidence: null, pageCount: null }).provenance, {
    methodLabel: "PDF 文本解析", confidenceLabel: null, pageCountLabel: null,
  });
});

test("Project details summarize PACKAGE once and keep extracted text collapsed by default", () => {
  const packageFile = { id: "package", projectId: "p", parentArchiveFileId: null,
    normalizedRelativePath: null, originalFilename: "项目资料.zip", mediaType: "application/zip",
    category: "PACKAGE", ingestionStatus: "AVAILABLE", parseStatus: "NOT_APPLICABLE", sizeBytes: 20,
    parsedText: null, parseMethod: null, parseEngine: null, parseConfidence: null, pageCount: null,
    parseDurationMs: null, safeReasonCode: null, createdAt: "now", updatedAt: "now" };
  assert.deepEqual(archivedPackagePresentation([packageFile]), {
    label: "原始资料包已归档", filenames: ["项目资料.zip"], count: 1,
  });

  const page = readFileSync(new URL("./components/ProjectDetailPage.tsx", import.meta.url), "utf8");
  assert.match(page, /<details/);
  assert.doesNotMatch(page, /<details[^>]*\sopen/);
  assert.doesNotMatch(page, /技术调用详情|解析引擎|置信度|parseEngine|parseDurationMs/);
  assert.doesNotMatch(page, /accept="[^"]*zip/);
  assert.match(page, /type="file" multiple accept="\.pdf,\.doc,\.docx,\.png,\.jpg,\.jpeg"/);
  assert.match(page, /查看材料内容/);
  assert.match(page, /合同信息识别结果/);
  assert.match(page, /已确认合同信息/);
  assert.doesNotMatch(page, /实名手机号|黑名单|关联风险/);
});

test("candidate fields and line item states remain grouped and source anchors never get guessed", () => {
  const detail = {
    candidate: { id: "candidate", projectId: "p", sourceContractFileId: "f", contractNo: "C-1",
      contractName: null, contractType: null, partyA: "Buyer", partyB: "Supplier", partyBCreditCode: null,
      contractAmount: 100, amountInWords: null, awardAmount: null, awardSupplier: null,
      awardSupplierCreditCode: null, lineItems: [{ id: "l1", projectId: "p", candidateId: "candidate",
        itemKey: "ITEM-1", itemName: "Server", quantity: 2, unitPrice: 50, declaredAmount: 100, sortNo: 1 }],
      promotionState: "DRAFT", versionNo: 0, promotedContractId: null, promotedBy: null, promotedAt: null,
      createdAt: "now", updatedAt: "now" },
    sourceFile: {},
    evidence: [{ factType: "CONTRACT_NO", groupKey: null, sourceFileId: "f", normalizedValue: "C-1",
      valueAmount: null, sourceText: "Contract No C-1", pageNumber: 1, paragraphIndex: null,
      sourceLocator: "pdf:page:1" }],
    fieldStates: { CONTRACT_NO: "CONFIRMED", CONTRACT_NAME: "MISSING" },
    lineItemFieldStates: { "ITEM-1": { LINE_ITEM_NAME: "CONFIRMED", LINE_ITEM_QUANTITY: "CONFIRMED" } },
  };
  const presentation = candidateFactGroups(detail);
  assert.equal(presentation.scalarStates.CONTRACT_NO, "CONFIRMED");
  assert.equal(presentation.scalarStates.CONTRACT_NAME, "MISSING");
  assert.deepEqual(presentation.lineItems[0].fieldStates,
    { LINE_ITEM_NAME: "CONFIRMED", LINE_ITEM_QUANTITY: "CONFIRMED" });
  assert.equal(sourcePresentation(detail.evidence, "CONTRACT_NO").label, "查看来源");
  assert.equal(sourcePresentation(detail.evidence, "CONTRACT_NAME").label, "暂无可靠来源定位");
});

function evidence(factType, normalizedValue, sourceFileId, sourceLocator, groupKey = null, extra = {}) {
  return {
    factType, groupKey, sourceFileId, normalizedValue, valueAmount: null,
    valueText: null, sourceText: `${factType} evidence`, pageNumber: 1,
    paragraphIndex: null, sourceLocator, ...extra,
  };
}

test("project material candidates remain visible when R02 returns no Contract candidates", () => {
  const detail = {
    candidates: [],
    files: [
      { id: "tender", originalFilename: "tender.docx" },
      { id: "award", originalFilename: "award.pdf" },
    ],
    extractionResults: [
      evidence("PURCHASER", "采购人甲", "tender", "docx:paragraph:2"),
      evidence("PROCUREMENT_AGENT", "代理机构乙", "tender", "docx:paragraph:3"),
      evidence("PACKAGE_SCOPE", "包A：服务器", "tender", "docx:paragraph:8", "PACKAGE:A"),
      evidence("BUDGET_AMOUNT", "250000", "tender", "docx:paragraph:9", "PACKAGE:A"),
      evidence("CONTROL_PRICE", "240000", "tender", "docx:paragraph:10", "PACKAGE:A"),
      evidence("PROJECT_NO", "SCIT-GZ-ZF-2026-0017", "award", "pdf:page:1"),
      evidence("CONTRACT_NO", "HT-IGNORED", "award", "pdf:page:2"),
    ],
  };

  const groups = projectMaterialCandidateGroups(detail);

  assert.deepEqual(groups.map((group) => group.factType), [
    "PROJECT_NO", "PURCHASER", "PROCUREMENT_AGENT", "BUDGET_AMOUNT", "CONTROL_PRICE", "PACKAGE_SCOPE",
  ]);
  assert.deepEqual(groups.map((group) => group.values[0]), [
    "SCIT-GZ-ZF-2026-0017", "采购人甲", "代理机构乙", "250000", "240000", "包A：服务器",
  ]);
});

test("conflicting ungrouped project numbers preserve both values and both locators without a primary", () => {
  const input = [
    evidence("PROJECT_NO", "SCIT-GZ-ZF-2026-0017", "notice", "pdf:page:3"),
    evidence("PROJECT_NO", "SCIT-GZ-ZF-2026-0007", "tender", "docx:paragraph:5"),
  ];
  const detail = { files: [
    { id: "notice", originalFilename: "notice.pdf" },
    { id: "tender", originalFilename: "tender.docx" },
  ], extractionResults: input };

  const group = projectMaterialCandidateGroups(detail)[0];
  const reversed = projectMaterialCandidateGroups({ ...detail, extractionResults: [...input].reverse() })[0];

  assert.equal(group.groupKey, null);
  assert.equal(group.conflict, true);
  assert.deepEqual(group.values, ["SCIT-GZ-ZF-2026-0007", "SCIT-GZ-ZF-2026-0017"]);
  assert.deepEqual(reversed.values, ["SCIT-GZ-ZF-2026-0007", "SCIT-GZ-ZF-2026-0017"]);
  assert.deepEqual(group.sources.map((source) => source.sourceLocator).sort(),
    ["docx:paragraph:5", "pdf:page:3"]);
  assert.equal("primaryValue" in group, false);
});

test("package A and B amounts form separate non-conflict groups", () => {
  const detail = { files: [{ id: "tender", originalFilename: "tender.docx" }], extractionResults: [
    evidence("BUDGET_AMOUNT", "250000", "tender", "docx:table:1:row:2", "PACKAGE:A"),
    evidence("BUDGET_AMOUNT", "4150000", "tender", "docx:table:1:row:3", "PACKAGE:B"),
    evidence("CONTROL_PRICE", "245000", "tender", "docx:table:1:row:4", "PACKAGE:A"),
    evidence("CONTROL_PRICE", "4100000", "tender", "docx:table:1:row:5", "PACKAGE:B"),
  ] };

  const groups = projectMaterialCandidateGroups(detail);

  assert.deepEqual(groups.map((group) => ({
    key: `${group.factType}|${group.groupKey}`,
    values: group.values,
    conflict: group.conflict,
  })), [
    { key: "BUDGET_AMOUNT|PACKAGE:A", values: ["250000"], conflict: false },
    { key: "BUDGET_AMOUNT|PACKAGE:B", values: ["4150000"], conflict: false },
    { key: "CONTROL_PRICE|PACKAGE:A", values: ["245000"], conflict: false },
    { key: "CONTROL_PRICE|PACKAGE:B", values: ["4100000"], conflict: false },
  ]);
});

test("equal duplicate evidence is not a conflict and retains both sources", () => {
  const detail = { files: [
    { id: "tender", originalFilename: "tender.docx" },
    { id: "notice", originalFilename: "notice.pdf" },
  ], extractionResults: [
    evidence("PURCHASER", "采购人甲", "tender", "docx:paragraph:2"),
    evidence("PURCHASER", "采购人甲", "notice", "pdf:page:1"),
  ] };

  const group = projectMaterialCandidateGroups(detail)[0];

  assert.equal(group.conflict, false);
  assert.deepEqual(group.values, ["采购人甲"]);
  assert.equal(group.sources.length, 2);
});

test("equivalent monetary scales use valueAmount and do not create a false conflict", () => {
  const detail = { files: [{ id: "tender", originalFilename: "tender.docx" }], extractionResults: [
    evidence("BUDGET_AMOUNT", "25万元", "tender", "docx:paragraph:1", "PACKAGE:A",
      { valueAmount: 250000, valueText: "25万元" }),
    evidence("BUDGET_AMOUNT", "250000.00", "tender", "docx:paragraph:2", "PACKAGE:A",
      { valueAmount: 250000.00, valueText: "250,000元" }),
  ] };

  const group = projectMaterialCandidateGroups(detail)[0];

  assert.equal(group.conflict, false);
  assert.deepEqual(group.values, ["250000"]);
  assert.equal(group.sources.length, 2);
});

test("distinct DECIMAL 18 monetary candidates remain conflicts beyond JavaScript safe integers", () => {
  const impreciseNumber = Number("9007199254740993");
  const detail = { files: [{ id: "tender", originalFilename: "tender.docx" }], extractionResults: [
    evidence("CONTROL_PRICE", "9007199254740992", "tender", "docx:paragraph:1", "PACKAGE:A",
      { valueAmount: impreciseNumber, valueText: "9007199254740992元" }),
    evidence("CONTROL_PRICE", "9007199254740993", "tender", "docx:paragraph:2", "PACKAGE:A",
      { valueAmount: impreciseNumber, valueText: "9007199254740993元" }),
  ] };

  const group = projectMaterialCandidateGroups(detail)[0];

  assert.equal(group.conflict, true);
  assert.deepEqual(group.values, ["9007199254740992", "9007199254740993"]);
});

test("source filenames are resolved by ID while unknown IDs remain explicit", () => {
  const detail = { files: [{ id: "known", originalFilename: "采购文件.docx" }], extractionResults: [
    evidence("PROJECT_NAME", "项目甲", "known", "docx:paragraph:1"),
    evidence("PROJECT_NAME", "项目乙", "missing-file", "pdf:page:7"),
  ] };

  const sources = projectMaterialCandidateGroups(detail)[0].sources;

  assert.deepEqual(sources.map((source) => ({ id: source.sourceFileId, name: source.sourceFileName })), [
    { id: "known", name: "采购文件.docx" },
    { id: "missing-file", name: "未知来源文件（missing-file）" },
  ]);
});

test("money candidate display prefers numeric amount while other facts keep normalized and raw fallbacks", () => {
  const detail = { files: [{ id: "f", originalFilename: "source.docx" }], extractionResults: [
    evidence("BUDGET_AMOUNT", "  normalized  ", "f", "docx:paragraph:1", "PACKAGE:A",
      { valueAmount: 12, valueText: "raw" }),
    evidence("BUDGET_AMOUNT", "   ", "f", "docx:paragraph:2", "PACKAGE:B",
      { valueAmount: 4150000, valueText: "raw" }),
    evidence("CONTROL_PRICE", null, "f", "docx:paragraph:3", null,
      { valueAmount: null, valueText: "  raw value  " }),
  ] };

  assert.deepEqual(projectMaterialCandidateGroups(detail).map((group) => group.values), [
    ["12"], ["4150000"], ["raw value"],
  ]);
});

test("automatic material results retain the boundary before a formal contract exists", () => {
  const page = readFileSync(new URL("./components/ProjectDetailPage.tsx", import.meta.url), "utf8");
  assert.match(page, /材料依据不足时不会创建正式合同/);
  assert.doesNotMatch(page, /写入 Contract|一键确认|自动采用/);
});

test("upload workflow has no manual type, project or contract confirmation panels", () => {
  const page = readFileSync(new URL("./components/ProjectDetailPage.tsx", import.meta.url), "utf8");
  assert.match(page, /files=\{state.detail.files\}/);
  assert.match(page, /useProjectWorkspace\(projectId, dataSource, user\?\.userId/);
  assert.doesNotMatch(page, /<(?:CandidateSupportAuthorityPanel|CandidateConfirmationPanel|ProjectMetadataConfirmation|ProjectIdentityConfirmation)/);
  assert.doesNotMatch(page, /onConfirm=\{state.confirmCandidateContent\}|onPromote=\{state.retryPromotion\}/);
});
