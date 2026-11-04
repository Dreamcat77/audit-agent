import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { contractTextAnalysisPresentation } from "./contractTextAnalysisPresentation.ts";

test("normal users see typed semantic assistance and friendly evidence filenames without technical IDs", () => {
  const view = contractTextAnalysisPresentation({
    projectId: "project-1",
    operation: "CONTRACT_TEXT_ANALYSIS",
    requestId: "request-1",
    available: true,
    summaries: ["付款条款要求验收后结算。"],
    hints: [{
      category: "DELIVERY_TERMS",
      attention: "REVIEW",
      title: "关注验收条件",
      description: "建议人工核对验收材料。",
      evidenceRefs: ["file-1"],
      evidenceItems: [{ fileId: "file-1", status: "REVIEW",
        excerpt: "验收材料", locatorAvailable: true }],
    }],
    model: "qwen-plus",
    durationMs: 31,
  }, false, null, {
    materials: [{ id: "file-1", fileName: "政府采购合同.docx" }],
    systemAdmin: false,
  });
  assert.equal(view.title, "智能语义审查");
  assert.equal(view.statusLabel, "分析完成");
  assert.match(view.authorityNotice, /项目、供应商、交付、质保和服务要求/);
  assert.deepEqual(view.summaries, ["付款条款要求验收后结算。"]);
  assert.equal(view.hints[0]?.title, "关注验收条件");
  assert.equal(view.hints[0]?.categoryLabel, "交付与验收要求");
  assert.equal(view.hints[0]?.attentionLabel, "建议复核");
  assert.deepEqual(view.hints[0]?.sources, [{ id: "file-1", fileName: "政府采购合同.docx",
    status: "REVIEW", excerpt: "验收材料", locatorAvailable: true }]);
  assert.equal(view.analysisDuration, "0.0 秒");
  assert.doesNotMatch(JSON.stringify({
    title: view.title,
    statusLabel: view.statusLabel,
    detail: view.detail,
    summaries: view.summaries,
    analysisDuration: view.analysisDuration,
  }), /qwen-plus|request-1|file-1|prompt|reasoning|思维链/i);
});

test("unavailable and request failure remain factual instead of showing fake AI output", () => {
  const unavailable = contractTextAnalysisPresentation({
    projectId: "project-1", operation: "CONTRACT_TEXT_ANALYSIS", requestId: "request-2",
    available: false, summaries: [], hints: [], model: "UNAVAILABLE_LOCAL_FAKE", durationMs: 0,
  }, false, null, { materials: [], systemAdmin: false });
  assert.equal(unavailable.statusLabel, "当前不可用");
  assert.doesNotMatch(JSON.stringify(unavailable), /NOT VERIFIED|BLOCKED_BY_RUNTIME_CONFIG/);
  assert.deepEqual(unavailable.summaries, []);
  assert.deepEqual(unavailable.hints, []);

  const failed = contractTextAnalysisPresentation(null, false, "服务调用失败");
  assert.equal(failed.statusLabel, "暂不可用");
  assert.equal(failed.detail, "服务调用失败 精确校验不受影响。");
  assert.deepEqual(failed.summaries, []);
});

test("a changed material selection clears the result and explicitly waits for reanalysis", () => {
  const view = contractTextAnalysisPresentation(null, false, null, {
    notice: "材料已变更，等待重新分析",
  });

  assert.equal(view.statusLabel, "等待重新分析");
  assert.equal(view.detail, "材料已变更，等待重新分析");
  assert.deepEqual(view.summaries, []);
  assert.deepEqual(view.hints, []);
});

test("not-started and running presentation states do not expose result-only sections", () => {
  const notStarted = contractTextAnalysisPresentation(null, false, null);
  assert.equal(notStarted.phase, "NOT_STARTED");
  assert.deepEqual(notStarted.sections, []);

  const running = contractTextAnalysisPresentation(null, true, null);
  assert.equal(running.phase, "RUNNING");
  assert.deepEqual(running.sections, []);
});

test("successful available result is the only state that exposes the 4.1 4.2 4.3 sections", () => {
  const result = contractTextAnalysisPresentation({
    projectId: "project-1", operation: "CONTRACT_TEXT_ANALYSIS", requestId: "request-sections-visible",
    available: true, summaries: [], model: "qwen-plus", durationMs: 10, hints: [],
  }, false, null);

  assert.equal(result.phase, "RESULT");
  assert.deepEqual(result.sections.map((section) => section.key), [
    "SEMANTIC_COMPARISON", "BREACH_LIABILITY", "TERM_TERMINATION",
  ]);
});

test("a successful analysis with no safe hints explains the deterministic fallback", () => {
  const view = contractTextAnalysisPresentation({
    projectId: "project-1",
    operation: "CONTRACT_TEXT_ANALYSIS",
    requestId: "request-filtered-1",
    available: true,
    summaries: ["已完成合同文字语义分析；金额与数值校验由确定性规则独立处理。"],
    hints: [],
    model: "qwen-plus",
    durationMs: 300,
  }, false, null);

  assert.equal(view.statusLabel, "分析完成");
  assert.equal(
    view.detail,
    "本轮未生成可展示的文字语义建议；金额与数值结果请查看精确校验。",
  );
  assert.deepEqual(view.hints, []);
});

test("customer presentation exposes duration only and never model request or material count", () => {
  const view = contractTextAnalysisPresentation({
    projectId: "project-1",
    operation: "CONTRACT_TEXT_ANALYSIS",
    requestId: "request-admin-1",
    available: true,
    summaries: ["未发现明显文字冲突。"],
    hints: [],
    model: "qwen-plus",
    durationMs: 16_900,
  }, false, null, {
    materials: [
      { id: "file-1", fileName: "合同.docx" },
      { id: "file-2", fileName: "采购文件.pdf" },
    ],
  });

  assert.equal(view.analysisDuration, "16.9 秒");
  assert.doesNotMatch(JSON.stringify(view), /qwen-plus|request-admin-1|selectedFileCount|materialCount/);
  const panel = readFileSync(new URL("./components/AiSemanticReviewPanel.tsx", import.meta.url), "utf8");
  const sourcePanel = readFileSync(new URL("./components/SourceDocumentsPanel.tsx", import.meta.url), "utf8");
  assert.doesNotMatch(panel, /TechnicalCallDetails|AI 模型|请求标识|材料数量/);
  assert.match(panel, /分析用时/);
  assert.doesNotMatch(sourcePanel, /PaddleOCR|PDFBox|Apache POI|FastAPI|Request ID|OCR/);
});

test("INFO hints stay concise while retaining real comparison sources", () => {
  const view = contractTextAnalysisPresentation({
    projectId: "project-1", operation: "CONTRACT_TEXT_ANALYSIS", requestId: "request-info",
    available: true, summaries: ["文字核对完成"], model: "qwen-plus", durationMs: 10,
    hints: [{ category: "PROJECT_IDENTITY", attention: "INFO", title: "项目编号一致",
      description: "未发现明显文字冲突", evidenceRefs: ["file-1"],
      evidenceItems: [{ fileId: "file-1", status: "NORMAL", excerpt: "项目编号",
        locatorAvailable: true }] }],
  }, false, null, { materials: [{ id: "file-1", fileName: "合同.docx" }] });
  assert.deepEqual(view.hints[0]?.sources, [{
    id: "file-1", fileName: "合同.docx", status: "NORMAL",
    excerpt: "项目编号", locatorAvailable: true,
  }]);
});

test("REVIEW evidence refs remain neutral comparison sources unless evidenceItems identify a problem", () => {
  const view = contractTextAnalysisPresentation({
    projectId: "project-1", operation: "CONTRACT_TEXT_ANALYSIS", requestId: "request-review",
    available: true, summaries: [], model: "qwen-plus", durationMs: 10,
    hints: [{ category: "DELIVERY_TERMS", attention: "REVIEW", title: "建议复核交付条款",
      description: "不同材料表述需要人工确认", evidenceRefs: ["file-1", "file-2"],
      evidenceItems: [] }],
  }, false, null, { materials: [
    { id: "file-1", fileName: "合同.docx" },
    { id: "file-2", fileName: "采购文件.docx" },
  ] });

  assert.deepEqual(view.hints[0]?.sources.map((source) => source.status), ["NORMAL", "NORMAL"]);
  assert.deepEqual(view.hints[0]?.problemSources, []);
  assert.equal(view.hints[0]?.problemMaterialNotice, "本条建议未标注具体问题材料，请结合上述对比来源复核。");
});

test("REVIEW shows all comparison refs but only REVIEW or INSUFFICIENT evidenceItems as problems", () => {
  const materials = [
    { id: "contract", fileName: "合同.docx" },
    { id: "tender", fileName: "采购文件.docx" },
    { id: "bid", fileName: "响应文件.docx" },
  ];
  const view = contractTextAnalysisPresentation({
    projectId: "project-1", operation: "CONTRACT_TEXT_ANALYSIS", requestId: "request-one-problem",
    available: true, summaries: [], model: "qwen-plus", durationMs: 10,
    hints: [{ category: "DELIVERY_TERMS", attention: "REVIEW", title: "交付期限有差异",
      description: "建议复核", evidenceRefs: ["contract", "tender", "bid"],
      evidenceItems: [{ fileId: "tender", status: "REVIEW", excerpt: "交付期限为6月15日",
        locatorAvailable: true }] }],
  }, false, null, { materials });

  assert.deepEqual(view.hints[0]?.sources.map((source) => source.id), ["contract", "tender", "bid"]);
  assert.deepEqual(view.hints[0]?.problemSources.map((source) => source.id), ["tender"]);
  assert.equal(view.hints[0]?.problemMaterialNotice, null);
});

test("one AI result is partitioned into 4.1 4.2 and 4.3 by semantic category", () => {
  const view = contractTextAnalysisPresentation({
    projectId: "project-1", operation: "CONTRACT_TEXT_ANALYSIS", requestId: "request-sections",
    available: true, summaries: [], model: "qwen-plus", durationMs: 10,
    hints: [
      { category: "PROJECT_IDENTITY", attention: "INFO", title: "项目一致",
        description: "项目身份已核对", evidenceRefs: ["contract"] },
      { category: "SERVICE_WARRANTY", attention: "REVIEW", title: "质保差异",
        description: "建议复核质保期", evidenceRefs: ["contract"] },
      { category: "BREACH_LIABILITY", attention: "REVIEW", title: "逾期比例差异",
        description: "合同与招标比例不同", evidenceRefs: ["contract"] },
      { category: "BREACH_LIABILITY", attention: "INFO", title: "累计上限已识别",
        description: "累计上限条款已识别", evidenceRefs: ["contract"] },
      { category: "TERM_TERMINATION", attention: "REVIEW", title: "交付日期差异",
        description: "建议复核交付日期", evidenceRefs: ["contract"] },
    ],
  }, false, null, { materials: [{ id: "contract", fileName: "合同.docx" }] });

  assert.deepEqual(view.sections.map((section) => [section.key, section.label, section.hints.length]), [
    ["SEMANTIC_COMPARISON", "4.1 跨材料语义一致性", 2],
    ["BREACH_LIABILITY", "4.2 违约责任条款审查", 2],
    ["TERM_TERMINATION", "4.3 合同期限与终止条件审查", 1],
  ]);
});

test("all canonical audit slots stay visible and unassessed when the model returns no hints", () => {
  const view = contractTextAnalysisPresentation({
    projectId: "project-1", operation: "CONTRACT_TEXT_ANALYSIS", requestId: "request-empty-slots",
    available: true, summaries: [], model: "qwen-plus", durationMs: 10, hints: [],
  }, false, null);

  const slots = view.sections.flatMap((section) => section.slots);
  assert.deepEqual(slots.map((slot) => slot.category), [
    "CONTRACT_STRUCTURE",
    "PROJECT_IDENTITY",
    "SUPPLIER_IDENTITY",
    "DELIVERY_TERMS",
    "SERVICE_WARRANTY",
    "SCOPE_AND_SUBCONTRACTING",
    "BREACH_LIABILITY",
    "TERM_TERMINATION",
  ]);
  assert.ok(slots.every((slot) => slot.coverage === "UNASSESSED"));
  assert.ok(slots.every((slot) => slot.coverageLabel === "未形成可靠判断"));
  assert.ok(slots.every((slot) => slot.hints.length === 0));
  assert.doesNotMatch(JSON.stringify(slots), /NOT_EVALUATED|"attention":"INFO"|"attention":"PASS"/);
});

test("canonical slots retain multiple same-category hints and append OTHER_TEXT without hiding normal slots", () => {
  const view = contractTextAnalysisPresentation({
    projectId: "project-1", operation: "CONTRACT_TEXT_ANALYSIS", requestId: "request-slot-groups",
    available: true, summaries: [], model: "qwen-plus", durationMs: 10,
    hints: [
      { category: "SERVICE_WARRANTY", attention: "REVIEW", title: "响应时限冲突",
        description: "合同响应慢于投标承诺", evidenceRefs: ["contract"] },
      { category: "SERVICE_WARRANTY", attention: "REVIEW", title: "到场时限冲突",
        description: "合同到场慢于投标承诺", evidenceRefs: ["contract"] },
      { category: "OTHER_TEXT", attention: "INFO", title: "其他条款",
        description: "其他文字已核对", evidenceRefs: ["contract"] },
    ],
  }, false, null, { materials: [{ id: "contract", fileName: "合同.docx" }] });

  const semanticSlots = view.sections.find((section) => section.key === "SEMANTIC_COMPARISON")?.slots ?? [];
  assert.equal(semanticSlots.find((slot) => slot.category === "SERVICE_WARRANTY")?.coverage, "ASSESSED");
  assert.equal(semanticSlots.find((slot) => slot.category === "SERVICE_WARRANTY")?.hints.length, 2);
  assert.equal(semanticSlots.find((slot) => slot.category === "PROJECT_IDENTITY")?.coverage, "UNASSESSED");
  assert.equal(semanticSlots.at(-1)?.category, "OTHER_TEXT");
});

test("term helper exposes exact 04 facts without manufacturing a semantic conclusion", () => {
  const blankExcerpt = "签约日期：    年  月  日";
  const effectiveExcerpt = "生效条件：双方签字、盖章后生效。";
  const view = contractTextAnalysisPresentation({
    projectId: "project-1", operation: "CONTRACT_TEXT_ANALYSIS", requestId: "request-term-helper",
    available: true, summaries: [], model: "qwen-plus", durationMs: 10, hints: [],
  }, false, null, {
    materials: [{ id: "contract", fileName: "04_政府采购合同.docx" }],
    termResult: {
      materialInsufficient: false,
      dates: [{ type: "SIGNING_DATE", source: null, normalizedDate: null, status: "INSUFFICIENT",
        sourceFileId: "contract", excerpt: blankExcerpt, locatorAvailable: true }],
      conditions: [{ type: "EFFECTIVE_EVENT", description: effectiveExcerpt, status: "NORMAL",
        sourceFileId: "contract", excerpt: effectiveExcerpt, locatorAvailable: true }],
      comparisons: [],
    },
  });

  const slot = view.sections.find((section) => section.key === "TERM_TERMINATION")?.slots[0];
  assert.equal(slot?.coverage, "UNASSESSED");
  assert.deepEqual(slot?.hints, []);
  assert.deepEqual(slot?.helperFacts.map((fact) => [fact.label, fact.status, fact.source.fileName,
    fact.source.excerpt, fact.source.locatorAvailable]), [
    ["签订日期", "INSUFFICIENT", "04_政府采购合同.docx", blankExcerpt, true],
    ["事件触发生效", "NORMAL", "04_政府采购合同.docx", effectiveExcerpt, true],
  ]);
  assert.doesNotMatch(JSON.stringify(slot), /"attention":"INSUFFICIENT"|"attention":"INFO"/);
});

test("verified AI evidence safely binds breach money and term date enrichments to the same hints", () => {
  const breachExcerpt = "每延期一周按合同金额1%支付违约金；累计最高不超过合同金额20%。";
  const termExcerpt = "交付期限2026年6月30日前";
  const view = contractTextAnalysisPresentation({
    projectId: "project-1", operation: "CONTRACT_TEXT_ANALYSIS", requestId: "request-enrichment",
    available: true, summaries: [], model: "qwen-plus", durationMs: 10,
    hints: [
      { category: "BREACH_LIABILITY", attention: "INFO", title: "逾期交付违约责任",
        description: "比例、频率及上限已识别", evidenceRefs: ["contract"],
        evidenceItems: [{ fileId: "contract", status: "NORMAL", excerpt: breachExcerpt,
          locatorAvailable: true }] },
      { category: "TERM_TERMINATION", attention: "INFO", title: "交付期限",
        description: "交付日期已识别", evidenceRefs: ["contract"],
        evidenceItems: [{ fileId: "contract", status: "NORMAL", excerpt: termExcerpt,
          locatorAvailable: true }] },
    ],
  }, false, null, {
    materials: [{ id: "contract", fileName: "合同.docx" }],
    breachResult: {
      materialInsufficient: false,
      items: [{ scenario: "逾期交付", rate: 1, rateUnit: "PERCENT", frequency: "WEEK",
        calculationBasis: "CONTRACT_AMOUNT", correspondingAmount: 670, capRate: 20, capRateUnit: "PERCENT",
        capAmount: 13400, actualPenaltyAmount: null, terminationCondition: null,
        status: "NORMAL", warning: null, sourceFileId: "contract", excerpt: breachExcerpt }],
      comparisons: [{ key: "legacy", label: "旧比较", status: "REVIEW", message: "不得作为AI结论",
        sources: [] }],
    },
    termResult: {
      materialInsufficient: false,
      dates: [{ type: "DELIVERY_DEADLINE", source: "2026年6月30日",
        normalizedDate: "2026-06-30", status: "NORMAL", sourceFileId: "contract",
        excerpt: termExcerpt, locatorAvailable: true }],
      conditions: [], comparisons: [],
    },
  });

  const breach = view.sections.find((section) => section.key === "BREACH_LIABILITY")?.hints[0];
  assert.deepEqual(breach?.breachEnrichment, {
    rate: 1, rateUnit: "PERCENT", frequency: "WEEK",
    calculationBasis: "CONTRACT_AMOUNT", correspondingAmount: 670, capRate: 20, capRateUnit: "PERCENT",
    capAmount: 13400, actualPenaltyAmount: null,
  });
  const term = view.sections.find((section) => section.key === "TERM_TERMINATION")?.hints[0];
  assert.deepEqual(term?.termDateEnrichments, [{
    type: "DELIVERY_DEADLINE", normalizedDate: "2026-06-30",
  }]);
});

test("unverified or mismatched AI evidence never receives deterministic enrichment", () => {
  const view = contractTextAnalysisPresentation({
    projectId: "project-1", operation: "CONTRACT_TEXT_ANALYSIS", requestId: "request-no-guess",
    available: true, summaries: [], model: "qwen-plus", durationMs: 10,
    hints: [{ category: "BREACH_LIABILITY", attention: "REVIEW", title: "违约责任",
      description: "建议复核", evidenceRefs: ["contract"], evidenceItems: [{
        fileId: "contract", status: "REVIEW", excerpt: "模型无法定位的摘录", locatorAvailable: false,
      }] }],
  }, false, null, {
    materials: [{ id: "contract", fileName: "合同.docx" }],
    breachResult: { materialInsufficient: false, comparisons: [], items: [{
      scenario: "逾期交付", rate: 1, rateUnit: "PERCENT", frequency: "WEEK",
      calculationBasis: "CONTRACT_AMOUNT", correspondingAmount: 670, capRate: 20, capRateUnit: "PERCENT",
      capAmount: 13400, actualPenaltyAmount: null, terminationCondition: null,
      status: "NORMAL", warning: null, sourceFileId: "contract", excerpt: "真实条款",
    }] },
  });

  assert.equal(view.sections[1]?.hints[0]?.breachEnrichment, null);
});

test("unsupported explicit breach frequency never becomes a customer-visible enrichment", () => {
  const excerpt = "逾期交付按合同金额1%/季度支付违约金。";
  const view = contractTextAnalysisPresentation({
    projectId: "project-1", operation: "CONTRACT_TEXT_ANALYSIS", requestId: "request-frequency",
    available: true, summaries: [], model: "qwen-plus", durationMs: 10,
    hints: [{ category: "BREACH_LIABILITY", attention: "REVIEW", title: "违约频次",
      description: "建议复核违约频次", evidenceRefs: ["contract"], evidenceItems: [{
        fileId: "contract", status: "REVIEW", excerpt, locatorAvailable: true,
      }] }],
  }, false, null, {
    materials: [{ id: "contract", fileName: "合同.docx" }],
    breachResult: { materialInsufficient: false, comparisons: [], items: [{
      scenario: "逾期交付", rate: 1, rateUnit: "PERCENT", frequency: null,
      calculationBasis: "CONTRACT_AMOUNT", correspondingAmount: null, capRate: 20,
      capRateUnit: "PERCENT", capAmount: null, actualPenaltyAmount: null,
      terminationCondition: null, status: "REVIEW", warning: "频次未能精确识别",
      sourceFileId: "contract", excerpt,
    }] },
  });

  assert.equal(view.sections[1]?.hints[0]?.breachEnrichment, null);
});

test("semantic and deterministic panels use truthful comparison-source vocabulary", () => {
  const semantic = readFileSync(new URL("./components/AiSemanticReviewPanel.tsx", import.meta.url), "utf8");
  const clauses = readFileSync(new URL("./components/ContractClauseReviewPanels.tsx", import.meta.url), "utf8");
  const f02 = readFileSync(new URL("./components/F02ReviewResultPanel.tsx", import.meta.url), "utf8");
  assert.match(semantic, /对比来源/);
  assert.match(semantic, /问题材料/);
  assert.doesNotMatch(semantic, /原文摘录/);
  assert.match(semantic, /capRateUnit/);
  assert.match(clauses, /对比来源/);
  assert.match(clauses, /问题材料/);
  assert.match(clauses, /原文摘录/);
  assert.match(f02, /对比来源/);
  assert.match(f02, /FindingComparisonSources/);
});

test("contract workspace has no timer-driven AI or OCR stages and keeps AI outside F02 findings", () => {
  const workspace = readFileSync(new URL("./components/ContractReviewWorkspace.tsx", import.meta.url), "utf8");
  const semanticHook = readFileSync(new URL("./useContractSemanticAnalysis.ts", import.meta.url), "utf8");
  const reviewHook = readFileSync(new URL("./useContractReviewWorkspace.ts", import.meta.url), "utf8");
  const reviewOperation = readFileSync(new URL("./reviewOperation.ts", import.meta.url), "utf8");
  const semanticPresentation = readFileSync(new URL("./contractTextAnalysisPresentation.ts", import.meta.url), "utf8");
  const progressState = readFileSync(new URL("./reviewProgressState.ts", import.meta.url), "utf8");
  const progressTimeline = readFileSync(new URL("./components/ReviewProgressTimeline.tsx", import.meta.url), "utf8");
  const history = readFileSync(new URL("./components/ReviewSessionHistoryDialog.tsx", import.meta.url), "utf8");
  const findings = readFileSync(new URL("./components/F02ReviewResultPanel.tsx", import.meta.url), "utf8");
  const findingPresentation = readFileSync(new URL("./reviewFindingPresentation.ts", import.meta.url), "utf8");
  assert.doesNotMatch(workspace, /setTimeout|mockProcessing|runMockReview/);
  assert.match(workspace, /AiSemanticReviewPanel/);
  assert.match(workspace, /useContractSemanticAnalysis/);
  assert.match(workspace, /IntelligentReviewPipeline/);
  assert.doesNotMatch(workspace, /请求 AI 辅助分析/);
  assert.doesNotMatch(
    `${workspace}\n${semanticHook}\n${reviewHook}\n${reviewOperation}\n${semanticPresentation}\n${progressState}\n${progressTimeline}\n${history}\n${findings}`,
    /AI 辅助|操作标识|Request ID|数据库|BigDecimal|Java|服务端|运行配置|存储异常|持久化|幂等标识/,
  );
  assert.match(findingPresentation, /金额与明细精确校验/);
  assert.doesNotMatch(findings, /Java|BigDecimal|技术实现详情|ruleVersion/);
  assert.match(findings, /系统对合同金额、明细合计、中标金额及供应商信息进行精确核对/);
  assert.doesNotMatch(findings, /ContractTextAnalysis|AI.*hints|summaries/);
});

test("integrated review DOM renders one AI result with helper data only as enrichment", () => {
  const workspace = readFileSync(new URL("./components/ContractReviewWorkspace.tsx", import.meta.url), "utf8");
  const hook = readFileSync(new URL("./useContractClauseReviews.ts", import.meta.url), "utf8");
  const http = readFileSync(new URL("./data/httpContractDataSource.ts", import.meta.url), "utf8");
  const picker = workspace.indexOf("<ProjectReviewMaterialPicker");
  const progress = workspace.indexOf("<IntelligentReviewPipeline");
  const deterministic = workspace.indexOf("<F02ReviewResultPanel");
  const group = workspace.indexOf('aria-labelledby="ai-review-group-title"');
  const semantic = workspace.indexOf("<AiSemanticReviewPanel");
  const assistance = workspace.indexOf('id="review-assistance-title"');
  assert.ok(picker >= 0 && progress > picker && deterministic > progress && group > deterministic
    && semantic > group && assistance > semantic);
  assert.match(workspace, /!projectBacked && \(\s*<ReviewProgressTimeline/);
  assert.match(workspace, /useContractClauseReviews/);
  assert.match(workspace, /<AiSemanticReviewPanel[\s\S]*?breachResult=\{breachResult\}/);
  assert.match(workspace, /<AiSemanticReviewPanel[\s\S]*?termResult=\{termResult\}/);
  assert.doesNotMatch(workspace, /<BreachClauseReviewPanel/);
  assert.doesNotMatch(workspace, /<ContractTermClauseReviewPanel/);
  assert.match(hook, /reviewBreachClauses/);
  assert.match(hook, /reviewTermClauses/);
  assert.doesNotMatch(hook, /setTimeout|mock|fake/i);
  assert.match(http, /clause-reviews\/breach/);
  assert.match(http, /clause-reviews\/term-termination/);
  assert.match(http, /clause-reviews\/breach[\s\S]*?body: \{ fileIds \}/);
  assert.match(http, /clause-reviews\/term-termination[\s\S]*?body: \{ fileIds \}/);
});

test("legacy F02 results retain acknowledgement while project-backed review is read-only", () => {
  const workspace = readFileSync(new URL("./components/ContractReviewWorkspace.tsx", import.meta.url), "utf8");
  const resultPanel = readFileSync(new URL("./components/F02ReviewResultPanel.tsx", import.meta.url), "utf8");

  assert.match(resultPanel, /acknowledgementLoadingResultId\??:/);
  assert.match(resultPanel, /acknowledgementError\??:/);
  assert.match(resultPanel, /onAcknowledgeFinding\??:/);
  assert.match(resultPanel, /createAcknowledgementControl/);
  assert.match(resultPanel, /handleAcknowledgementActionClick\([\s\S]*?finding\.id,[\s\S]*?onAcknowledgeFinding/);
  assert.match(resultPanel, /data-testid="finding-acknowledgement-action"/);
  assert.match(resultPanel, /disabled=\{control\.disabled\}/);
  assert.match(resultPanel, /aria-busy=\{control\.busy\}/);

  const panelCall = workspace.slice(workspace.indexOf("<F02ReviewResultPanel"));
  assert.match(panelCall, /acknowledgementLoadingResultId=\{acknowledgementLoadingResultId\}/);
  assert.match(panelCall, /acknowledgementError=\{acknowledgementError\}/);
  assert.match(panelCall, /onAcknowledgeFinding=\{projectBacked \? undefined : acknowledgeFinding\}/);
});
