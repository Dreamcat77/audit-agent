import assert from "node:assert/strict";
import test from "node:test";

import { buildCategoryOpinion, buildOverallOpinion } from "./contractReviewOpinion.ts";
import * as opinions from "./contractReviewOpinion.ts";
import type { ContractReviewCategoryView } from "./eightCategoryReviewProjection.ts";

const category: ContractReviewCategoryView = {
  id: "DRAFT_STRUCTURE", order: 2, title: "合同草本结构对标审查",
  coverage: "PARTIALLY_COVERED", judgement: "ATTENTION", statusLabel: "发现差异，覆盖不完整",
  attentionCount: 1, summary: "已识别结构差异；没有指定模板。",
  uncoveredScopes: ["未指定合同模板，未完成模板结构对标"],
  items: [{
    stableId: "run-1:SEMANTIC_AI:finding-1", outputType: "SEMANTIC_AI", outputId: "finding-1",
    title: "结构差异", status: "ATTENTION", summary: "结构差异已持久化",
    actualValue: null, expectedValue: null, recommendation: "复核已持久化差异",
    source: "MODEL_ASSISTED", evidence: [], typedFacts: [], primary: true,
  }],
};

test("category opinion contains persisted facts and explicit uncovered scope", () => {
  const text = buildCategoryOpinion(category);
  assert.match(text, /结构差异已持久化/);
  assert.match(text, /复核已持久化差异/);
  assert.match(text, /未指定合同模板/);
  assert.doesNotMatch(text, /民法典|全部合规/);
});

test("overall opinion lists unfinished, uncovered and insufficient categories and deduplicates stable outputs", () => {
  const duplicate = { ...category, id: "CLAUSE_RISK" as const, order: 8 as const,
    title: "合同条款风险提示", coverage: "UNFINISHED" as const,
    judgement: "UNAVAILABLE" as const, statusLabel: "未完成" };
  const uncovered = { ...category, id: "ATTACHMENT_COMPLETENESS" as const, order: 6 as const,
    title: "附件清单完整性审查", coverage: "NOT_COVERED" as const,
    judgement: "INSUFFICIENT" as const, statusLabel: "未覆盖", items: [] };

  const text = buildOverallOpinion([category, duplicate, uncovered], "run-1", "PARTIALLY_COMPLETED");

  assert.match(text, /run-1/);
  assert.match(text, /未完成范围/);
  assert.match(text, /未覆盖范围/);
  assert.match(text, /资料不足范围/);
  assert.equal(text.match(/结构差异已持久化/g)?.length, 1);
  assert.doesNotMatch(text, /全部合规/);
});

test("item and overall copies preserve normal comparisons typed facts evidence and human review scope", () => {
  const normal = { ...category, coverage: "COVERED" as const, judgement: "NORMAL" as const,
    statusLabel: "本项已核对", summary: "合同与中标金额已核对一致。", uncoveredScopes: [],
    items: [{ ...category.items[0]!, status: "NORMAL" as const, summary: "合同与中标金额一致",
      actualValue: "123456.78", expectedValue: "123456.78", recommendation: null,
      typedFacts: ["合同侧：责任方 乙方；费率 1%；频次 每周"],
      evidence: [{ evidenceId: "e1", fileId: "f1", fileName: "合同正文", excerpt: "每周按总价1%",
        locatorAvailable: true, pageNumber: 2, paragraphIndex: null, segmentIndex: null,
        startOffset: 0, endOffset: 9 }] }],
  };
  for (const text of [buildCategoryOpinion(normal), buildOverallOpinion([normal], "run-1", "COMPLETED")]) {
    assert.match(text, /合同与中标金额已核对一致/);
    assert.match(text, /123456\.78/);
    assert.match(text, /责任方 乙方/);
    assert.match(text, /每周按总价1%/);
    assert.match(text, /不表示问题已解决/);
  }
  assert.match(buildOverallOpinion([normal], "run-1", "RUNNING"), /草稿/);
  assert.match(buildOverallOpinion([normal], "run-1", "COMPLETED"), /审核状态：审核完成/);
});

test("summary uses server business opinion without expanding normal line details or truncating risks", () => {
  assert.equal(typeof opinions.buildCategorySummaryOpinion, "function");
  const normal = { ...category, judgement: "NORMAL" as const, coverage: "COVERED" as const,
    statusLabel: "已核对", uncoveredScopes: [], opinionVersion: 2,
    conciseOpinion: "合同48项明细合计67,000元，与成交金额一致。" + "必要业务说明。".repeat(35),
    items: Array.from({ length: 52 }, (_, index) => ({ ...category.items[0]!, status: "NORMAL" as const,
      stableId: `run-1:F02:${index}`, title: `逐行检查${index}`, summary: `完整明细原文${index}` })),
  };
  const text = opinions.buildCategorySummaryOpinion(normal);
  assert.match(text, /合同48项明细合计67,000元/);
  assert.ok(text.includes(normal.conciseOpinion), "server opinion must remain untruncated");
  assert.doesNotMatch(text, /逐行检查|完整明细原文/);
  assert.match(text, /52.*完整明细/);
  const detail = buildCategoryOpinion(normal);
  for (let index = 0; index < 52; index++) assert.ok(detail.includes(`完整明细原文${index}`));
});

test("legacy summary retains every risk class, insufficiency and uncovered scope without claiming completion", () => {
  assert.equal(typeof opinions.buildCategorySummaryOpinion, "function");
  const legacy = { ...category, items: [category.items[0]!,
    { ...category.items[0]!, stableId: "run-1:TERM:missing", title: "终止日期", status: "INSUFFICIENT" as const },
    { ...category.items[0]!, stableId: "run-1:BREACH:late", title: "逾期交付", status: "ATTENTION" as const }],
    uncoveredScopes: ["未指定合同模板", "附件九未覆盖"],
  };
  const text = opinions.buildCategorySummaryOpinion(legacy);
  for (const value of [legacy.summary, "结构差异", "逾期交付", "终止日期", "附件九未覆盖", "未指定合同模板"])
    assert.ok(text.includes(value), value);
  assert.match(text, /需关注.*结构差异.*逾期交付/);
  assert.match(text, /资料不足.*终止日期/);
  assert.match(text, /3.*完整明细/);
  assert.doesNotMatch(text, /结构差异已持久化|复核已持久化差异|全部合规/);
});

test("overall summary keeps eight headings, run identity, draft, advice and every unresolved category", () => {
  assert.equal(typeof opinions.buildOverallSummaryOpinion, "function");
  const categories = Array.from({ length: 8 }, (_, index) => ({ ...category,
    order: index + 1 as ContractReviewCategoryView["order"], title: `业务项${index + 1}`,
    conciseOpinion: `第${index + 1}项具体业务结论。`, opinionVersion: 2,
    overallAdvice: "请补齐附件九，并人工核实主体资格。",
    coverage: index === 0 ? "UNFINISHED" as const : index === 1 ? "NOT_COVERED" as const : "COVERED" as const,
    judgement: index === 2 ? "INSUFFICIENT" as const : "NORMAL" as const,
    items: [], uncoveredScopes: index === 1 ? ["附件九未覆盖"] : [],
  }));
  const text = opinions.buildOverallSummaryOpinion(categories, "run-frozen-exact", "FAILED");
  for (let index = 1; index <= 8; index++) assert.ok(text.includes(`${index}. 业务项${index}`));
  assert.match(text, /审核记录：run-frozen-exact/);
  assert.match(text, /草稿/);
  assert.match(text, /审核未完整完成/);
  assert.match(text, /综合建议：请补齐附件九，并人工核实主体资格/);
  assert.match(text, /未完成范围：业务项1/);
  assert.match(text, /未覆盖范围：业务项2/);
  assert.match(text, /资料不足范围：业务项3/);
  assert.match(text, /人工确认表示已查看，不表示问题已解决/);
});

test("server concise limitations appear once while an omitted uncovered scope remains explicit", () => {
  const text = opinions.buildCategorySummaryOpinion({ ...category,
    conciseOpinion: "已完成结构核对；未指定合同模板，未完成模板结构对标。",
    uncoveredScopes: ["未指定合同模板，未完成模板结构对标", "附件九尚未提供"],
  });
  assert.equal(text.match(/未指定合同模板，未完成模板结构对标/g)?.length, 1);
  assert.match(text, /附件九尚未提供/);
});

test("full and concise overall opinions report the same partial coverage and unresolved item scopes", () => {
  const partial = { ...category, title: "期限与终止", judgement: "ATTENTION" as const,
    items: [category.items[0]!, { ...category.items[0]!, stableId: "r:term:missing",
      status: "INSUFFICIENT" as const, title: "交付日期", summary: "程序未能确认日期" }] };
  for (const text of [buildOverallOpinion([partial], "run-1", "COMPLETED"),
    opinions.buildOverallSummaryOpinion([partial], "run-1", "COMPLETED")]) {
    assert.match(text, /未覆盖范围：期限与终止/);
    assert.match(text, /资料不足范围：期限与终止/);
  }
});

test("copied detail retains all evidence including trailing negation rate and condition", () => {
  const excerpt = "合成上下文".repeat(50) + "未经甲方书面同意不得转包；每周2%，累计不超过10%；延期超过4周方可解除。";
  const withEvidence = { ...category, items: [{ ...category.items[0]!, evidence: [{
    evidenceId: "e", fileId: "f", fileName: "合同正文", excerpt, locatorAvailable: true,
    pageNumber: 1, paragraphIndex: null, segmentIndex: null, startOffset: 0, endOffset: excerpt.length,
  }] }] };
  assert.ok(buildCategoryOpinion(withEvidence).includes(excerpt));
  assert.ok(buildOverallOpinion([withEvidence], "run-1", "COMPLETED").includes(excerpt));
});

test("each concise and detailed copy identifies the supplied opinion version", () => {
  const current = { ...category, opinionVersion: 3 };
  for (const text of [buildCategoryOpinion(current), opinions.buildCategorySummaryOpinion(current),
    buildOverallOpinion([current], "run-1", "COMPLETED"),
    opinions.buildOverallSummaryOpinion([current], "run-1", "COMPLETED")]) {
    assert.match(text, /意见版本：3/);
  }
  assert.doesNotMatch(buildCategoryOpinion(category), /意见版本/);
});

test("default overall opinion uses the canonical server paragraph without duplicating all eight summaries", () => {
  const paragraph = "本次对合同正文及中标通知书进行了辅助核对。合同金额69,000元与成交金额67,000元存在2,000元差异。需关注交付期限与书面催告条件；主体资格待外部核验。建议对照双方原文核实差异。";
  const current = { ...category, opinionVersion: 4, overallOpinion: paragraph,
    conciseOpinion: "单项具体结论仍在八项查看。", overallAdvice: "完整建议仍在八项保留。" };
  const text = opinions.buildOverallSummaryOpinion([current], "run-1", "COMPLETED");
  assert.ok(text.includes(paragraph));
  assert.doesNotMatch(text, /单项具体结论仍在八项查看|完整建议仍在八项保留|2\. 合同草本结构/);
  assert.match(text, /审核记录：run-1/);
  assert.match(text, /意见版本：4/);
  assert.match(opinions.buildOverallSummaryOpinion([current], "run-1", "FAILED"), /草稿/);
  assert.ok(buildOverallOpinion([current], "run-1", "COMPLETED").includes(paragraph));
});

test("every formatter carries exactly one frozen observation marker while STRICT and old opinions do not", () => {
  const marker = "【联调参考意见｜金额相关模型表述尚待人工复核】";
  const current = { ...category, overallOpinion: "合同金额存在差异，请核实双方原文。",
    monetaryObservation: { protocol: "contract-money-observation-v1", mode: "OBSERVE", warnings: [], workWarnings: [] } };
  for (const text of [buildCategoryOpinion(current), opinions.buildCategorySummaryOpinion(current),
    buildOverallOpinion([current], "run-1", "COMPLETED"),
    opinions.buildOverallSummaryOpinion([current], "run-1", "COMPLETED")]) {
    assert.equal(text.split(marker).length - 1, 1);
    assert.ok(text.startsWith(marker));
  }
  assert.doesNotMatch(buildCategoryOpinion(category), /联调参考意见/);
  assert.doesNotMatch(buildCategoryOpinion({ ...current,
    monetaryObservation: { ...current.monetaryObservation, mode: "STRICT" } }), /联调参考意见/);
});
