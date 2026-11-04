import assert from "node:assert/strict";
import { registerHooks } from "node:module";
import test from "node:test";

const hooks = registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith(".") && !/\.[a-z]+$/i.test(specifier)) {
      return nextResolve(`${specifier}.ts`, context);
    }
    return nextResolve(specifier, context);
  },
});

const [reviewRules, reviewFindings, reviewExecution, auditTasks, projectMaterials, projectReview, materialSource, contractDetails] = await Promise.all([
  import("./reviewRulePresentation.ts").catch(() => ({})),
  import("./reviewFindingPresentation.ts").catch(() => ({})),
  import("./reviewExecutionPresentation.ts").catch(() => ({})),
  import("./auditTaskPresentation.ts").catch(() => ({})),
  import("../project/projectMaterialPresentation.ts").catch(() => ({})),
  import("../project/projectReviewPresentation.ts").catch(() => ({})),
  import("./projectMaterialSourceSelection.ts").catch(() => ({})),
  import("./contractDetailsPresentation.ts").catch(() => ({})),
]).finally(() => hooks.deregister());

const RULE_LABELS = {
  SOURCE_PREPARATION: "资料读取与来源核对",
  F02_AMOUNT_WORDS_MATCH: "合同金额大小写一致性核对",
  F02_LINE_ITEM_ARITHMETIC: "合同分项金额计算核对",
  F02_LINE_SUM_MATCH: "合同分项合计与合同总额核对",
  F02_AWARD_AMOUNT_MATCH: "合同金额与中标金额一致性核对",
  F02_AWARD_SUPPLIER_MATCH: "合同乙方与中标供应商一致性核对",
} as const;

test("all F02 progress items use the exact Chinese business titles without exposing internal codes", () => {
  assert.equal(typeof reviewRules.reviewRuleTitle, "function");
  for (const [code, title] of Object.entries(RULE_LABELS)) {
    assert.equal(reviewRules.reviewRuleTitle(code), title);
  }
  assert.equal(reviewRules.reviewRuleTitle("F02_FUTURE_INTERNAL_MATCH"), "其他合同一致性核对");

  const result = {
    taskId: "task-1", status: "DONE", reviewType: "F02_DETERMINISTIC", overallRisk: "GREEN",
    blockSign: false, replayed: false,
    findings: Object.keys(RULE_LABELS).filter((code) => code !== "SOURCE_PREPARATION").map((ruleCode, index) => ({
      id: `finding-${index}`, ruleCode, itemKey: null, checkStatus: "PASS", riskLevel: "GREEN",
      blockSign: false, actualValue: "1", expectedValue: "1", detail: [
        "Amount in words does not match numeric contract amount",
        "Line quantity multiplied by unit price matches declared amount",
        "Line item sum differs from contract amount",
        "Contract amount differs from award amount",
        "Contract supplier matches award supplier by credit code",
      ][index],
      ruleVersion: 1, createdAt: "2026-08-26T11:00:00Z",
    })),
  };
  const progress = {
    total: 6, riskSummary: "无风险",
    items: [
      { type: "SOURCE_PREPARATION", checkResultId: null, title: "SOURCE_PREPARATION", displayOrder: 0,
        status: "DONE", risk: null, completedAt: "2026-08-26T10:59:59Z", acknowledgement: null },
      ...result.findings.map((finding, index) => ({ type: "FINDING", checkResultId: finding.id,
        title: finding.ruleCode, displayOrder: index + 1, status: "PASS", risk: "GREEN",
        completedAt: finding.createdAt, acknowledgement: null })),
    ],
  };
  const snapshot = reviewExecution.createReviewExecutionSnapshot({ result, progress });
  assert.deepEqual(snapshot.steps.map((step: { label: string }) => step.label), Object.values(RULE_LABELS));
  assert.doesNotMatch(JSON.stringify(snapshot.steps), /F02_|SOURCE_PREPARATION|_MATCH|_ARITHMETIC/);
  assert.doesNotMatch(JSON.stringify(snapshot.steps), /Amount in words|Line quantity|Line item sum|Contract amount|Contract supplier|matches|differs/i);
});

test("review summary exposes exactly consistent, review, and insufficient assistance blocks", () => {
  assert.equal(typeof reviewFindings.reviewSummaryBlocks, "function");
  assert.deepEqual(reviewFindings.reviewSummaryBlocks({
    total: 6,
    riskSummary: "低风险",
    items: [
      { type: "SOURCE_PREPARATION", risk: null },
      { type: "FINDING", risk: "GREEN", status: "PASS" },
      { type: "FINDING", risk: "GREEN", status: "PASS" },
      { type: "FINDING", risk: "GREEN", status: "PASS" },
      { type: "FINDING", risk: "YELLOW", status: "NOT_EVALUATED" },
      { type: "FINDING", risk: "RED", status: "FAIL" },
    ],
  }), [
    { label: "一致项", value: 3 },
    { label: "需复核项", value: 1 },
    { label: "材料不足项", value: 1 },
  ]);
});

test("task center uses current-total or factual Chinese state and never pass language", () => {
  assert.equal(typeof auditTasks.compactReviewProgressPresentation, "function");
  assert.deepEqual(auditTasks.compactReviewProgressPresentation(null, null, false), {
    label: "待审核", current: null, total: null,
  });
  assert.deepEqual(auditTasks.compactReviewProgressPresentation({
    findings: Array(5).fill({ riskLevel: "GREEN" }),
  }, null, false), {
    label: "6 / 6", current: 6, total: 6,
  });
  assert.deepEqual(auditTasks.compactReviewProgressPresentation({
    findings: [{ riskLevel: "GREEN" }, { riskLevel: "RED" }],
  }, null, false), { label: "审核中", current: null, total: null });
  assert.deepEqual(auditTasks.auditTaskRiskPresentation([
    { riskLevel: "GREEN" }, { riskLevel: "RED" },
  ]), { label: "建议复核", tone: "WARNING" });
  assert.deepEqual(auditTasks.directTaskReviewPromptPresentation("高风险"), {
    label: "建议重点复核", tone: "PRIORITY",
  });
  assert.match(auditTasks.riskBadgeToneClass("PRIORITY"), /bg-red-/);
  assert.match(auditTasks.riskBadgeToneClass("PRIORITY"), /text-(slate|red)-9/);
  assert.doesNotMatch(auditTasks.riskBadgeToneClass("PRIORITY"), /orange|amber/);
  assert.equal(auditTasks.shouldIncludeShowcaseRows("http"), false);
  assert.equal(auditTasks.shouldIncludeShowcaseRows("mock"), true);
  assert.doesNotMatch(JSON.stringify([
    auditTasks.compactReviewProgressPresentation(null, null, false),
    auditTasks.auditTaskRiskPresentation([{ riskLevel: "GREEN" }]),
  ]), /通过|F02_/);
});

const baseFile = {
  id: "file-contract", projectId: "project-1", parentArchiveFileId: null,
  normalizedRelativePath: "01_合同/采购合同.docx", originalFilename: "采购合同.docx",
  mediaType: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  category: "CONTRACT_BODY", ingestionStatus: "AVAILABLE", parseStatus: "PARSED",
  effectiveRole: "CONTRACT_BODY", effectiveRoleSource: "LEGACY_EFFECTIVE", roleDecisionId: null,
  effectiveIdentityStatus: "SAME_PROJECT", identityCurrent: true, excludedFromProjectUse: false,
  sizeBytes: 20, parsedText: "原始提取文本 ¥876000，不得改写。", safeReasonCode: null,
  createdAt: "2026-08-26T10:00:00Z", updatedAt: "2026-08-26T10:00:00Z",
};

test("project material presentation has five stable Chinese tabs, groups other files, removes stars, and preserves parsed text", () => {
  assert.equal(typeof projectMaterials.projectMaterialTabs, "function");
  const tabs = projectMaterials.projectMaterialTabs([
    baseFile,
    { ...baseFile, id: "file-tender", category: "TENDER_DOCUMENT", effectiveRole: "TENDER_DOCUMENT",
      originalFilename: "招标文件.pdf" },
    { ...baseFile, id: "file-bid", category: "BID_RESPONSE", effectiveRole: "BID_RESPONSE",
      originalFilename: "投标文件.docx" },
    { ...baseFile, id: "file-award", category: "AWARD_NOTICE", effectiveRole: "AWARD_NOTICE",
      originalFilename: "中标通知书.docx" },
    { ...baseFile, id: "file-other", category: "SUPPLEMENT", effectiveRole: "SUPPLEMENT",
      originalFilename: "附件说明.txt" },
    { ...baseFile, id: "file-package", category: "PACKAGE", originalFilename: "项目材料.zip",
      effectiveRole: "UNKNOWN", effectiveRoleSource: "NO_AUTHORITATIVE_ROLE",
      mediaType: "application/zip", parsedText: null, parseStatus: "NOT_APPLICABLE" },
    { ...baseFile, id: "file-unknown", category: "UNKNOWN", originalFilename: "其他资料.bin",
      effectiveRole: "UNKNOWN", effectiveRoleSource: "NO_AUTHORITATIVE_ROLE",
      ingestionStatus: "AVAILABLE", parseStatus: "UNSUPPORTED", parsedText: null },
  ], "project-1");
  assert.deepEqual(tabs.map((tab: { label: string }) => tab.label),
    ["合同正文", "招标文件", "投标文件", "中标通知书", "其他材料"]);
  assert.equal(tabs[4].files.length, 2);
  assert.equal(tabs.flatMap((tab: { files: Array<{ id: string }> }) => tab.files)
    .some((file: { id: string }) => file.id === "file-package"), false);
  assert.equal(tabs[0].files[0].parsedText, baseFile.parsedText);
  assert.equal(tabs[0].files[0].classifierCategory, "CONTRACT_BODY");
  assert.equal(tabs[0].files[0].reviewRole, "CONTRACT_BODY");
  assert.equal(tabs[0].files[0].ingestionStatus, "AVAILABLE");
  assert.equal(tabs[0].files[0].parseStatus, "PARSED");
  assert.equal(tabs[0].files[0].semanticEligible, true);
  assert.equal(tabs[4].files.find((file: { id: string }) => file.id === "file-unknown").semanticEligible, false);
  assert.equal(tabs[5], undefined);
  assert.doesNotMatch(JSON.stringify(tabs.map((tab: { label: string; files: Array<{ statusLabel: string }> }) => ({
    label: tab.label, statuses: tab.files.map((file) => file.statusLabel),
  }))), /\*|CONTRACT_BODY|TENDER_DOCUMENT|BID_RESPONSE|AWARD_NOTICE|SUPPLEMENT|UNKNOWN|PARSED|UNSUPPORTED/);
});

test("candidate and file states have one Chinese presentation mapping with no raw lifecycle codes", () => {
  assert.equal(projectMaterials.candidatePromotionLabel("DRAFT"), "系统整理中");
  assert.equal(projectMaterials.candidatePromotionLabel("PROMOTED"), "已生成正式合同");
  assert.equal(projectMaterials.fieldStateLabel("CONFIRMED"), "已识别");
  assert.equal(projectMaterials.fieldStateLabel("MISSING"), "未识别");
  assert.equal(projectMaterials.fieldStateLabel("CONFLICT"), "内容存在差异");
  assert.equal(projectMaterials.materialStatusLabel({ ...baseFile, ingestionStatus: "UPLOAD_PENDING", parseStatus: "PENDING" }), "处理中");
  assert.equal(projectMaterials.materialStatusLabel({ ...baseFile, parseStatus: "UNSUPPORTED" }), "当前版本暂不支持文本解析");
});

test("Project-backed source preparation describes only durable project, contract, amount, line, award, and material facts", () => {
  assert.equal(typeof projectReview.buildProjectSourcePreparationSections, "function");
  const sections = projectReview.buildProjectSourcePreparationSections({
    project: { id: "project-1", name: "智慧办公终端采购", projectNo: null, category: null,
      versionNo: 1, createdAt: "2026-08-26T10:00:00Z", updatedAt: "2026-08-26T10:00:00Z" },
    candidate: {
      id: "candidate-1", projectId: "project-1", sourceContractFileId: "file-contract",
      contractNo: "HT-001", contractName: "采购合同", contractType: "采购", partyA: "甲方",
      partyB: "供应商A", partyBCreditCode: "91350000TEST", contractAmount: 876000,
      amountInWords: "人民币捌拾柒万陆仟元整", awardAmount: 849000, awardSupplier: "供应商A",
      awardSupplierCreditCode: "91350000TEST", lineItems: [{ id: "line-1", projectId: "project-1",
        candidateId: "candidate-1", itemKey: "ITEM-1", itemName: "终端", quantity: 1,
        unitPrice: 876000, declaredAmount: 876000, sortNo: 1 }], promotionState: "PROMOTED",
      versionNo: 1, promotedContractId: "contract-1", promotedBy: "user-1",
      promotedAt: "2026-08-26T10:10:00Z", createdAt: "2026-08-26T10:00:00Z",
      updatedAt: "2026-08-26T10:10:00Z",
    },
    fieldStates: { PROJECT_NAME: "CONFIRMED", PROJECT_NO: "MISSING", CONTRACT_NO: "CONFIRMED",
      CONTRACT_NAME: "CONFIRMED", PARTY_A: "CONFIRMED", PARTY_B: "CONFIRMED",
      CREDIT_CODE: "CONFIRMED", AMOUNT_NUMERIC: "CONFIRMED", AMOUNT_UPPERCASE: "CONFIRMED",
      AWARD_AMOUNT: "CONFLICT", AWARD_SUPPLIER: "CONFIRMED" },
    files: [baseFile, { ...baseFile, id: "award-file", category: "AWARD_NOTICE", originalFilename: "中标通知书.docx" }],
  });
  assert.deepEqual(sections.map((section: { title: string }) => section.title), [
    "项目信息", "合同基本信息", "合同金额", "合同明细", "中标信息", "来源材料",
  ]);
  assert.match(JSON.stringify(sections), /项目名称.*已识别/);
  assert.match(JSON.stringify(sections), /项目编号.*未识别/);
  assert.match(JSON.stringify(sections), /中标金额.*内容存在差异/);
  assert.match(JSON.stringify(sections), /已识别 1 项/);
  assert.doesNotMatch(JSON.stringify(sections), /PROMOTED|CONFIRMED|MISSING|CONFLICT|CONTRACT_BODY|AWARD_NOTICE/);
});

test("Project-backed finding source selects the owning material tab, real file, and safe view mode", () => {
  assert.equal(typeof materialSource.selectProjectMaterialSource, "function");
  const tabs = [{ key: "CONTRACT", label: "合同正文", files: [{ id: "contract-file", projectId: "project-1" }] },
    { key: "AWARD", label: "中标通知书", files: [{ id: "award-file", projectId: "project-1" }] }];
  assert.deepEqual(materialSource.selectProjectMaterialSource(tabs, {
    role: "AWARD_NOTICE", projectId: "project-1", fileId: "award-file", pageNumber: 2,
    paragraphIndex: null, locator: "pdf:page:2", excerpt: "中标金额 ¥849,000.00",
  }), { tabKey: "AWARD", fileId: "award-file", viewerMode: "LOCATION" });
  assert.deepEqual(materialSource.selectProjectMaterialSource(tabs, {
    role: "CONTRACT", projectId: "project-1", fileId: "contract-file", pageNumber: null,
    paragraphIndex: 8, locator: "docx:paragraph:8", excerpt: "合同金额 ¥876,000.00",
  }), { tabKey: "CONTRACT", fileId: "contract-file", viewerMode: "LOCATION" });
  assert.equal(materialSource.selectProjectMaterialSource(tabs, {
    role: "AWARD_NOTICE", projectId: "project-2", fileId: "award-file", pageNumber: 2,
    paragraphIndex: null, locator: "pdf:page:2", excerpt: "foreign",
  }), null);
  assert.equal(materialSource.selectProjectMaterialSource(tabs, {
    role: "AWARD_NOTICE", projectId: "project-1", fileId: "award-file", pageNumber: 2,
    paragraphIndex: null, locator: null, excerpt: null,
  }), null);
});

test("technical contract type codes have one Chinese product presentation without changing the stored value", () => {
  assert.equal(typeof contractDetails.contractTypePresentation, "function");
  assert.equal(contractDetails.contractTypePresentation("PURCHASE"), "采购合同");
  assert.equal(contractDetails.contractTypePresentation("PROCUREMENT"), "采购合同");
  assert.equal(contractDetails.contractTypePresentation("GOODS"), "采购合同");
  assert.equal(contractDetails.contractTypePresentation("SERVICE"), "服务合同");
  assert.equal(contractDetails.contractTypePresentation("CONSTRUCTION"), "工程合同");
  assert.equal(contractDetails.contractTypePresentation("LEASE"), "租赁合同");
  assert.equal(contractDetails.contractTypePresentation("FRAMEWORK"), "框架协议");
  assert.equal(contractDetails.contractTypePresentation("采购合同"), "采购合同");
  assert.equal(contractDetails.contractTypePresentation("FUTURE_TECH_CODE"), "其他合同");
});
