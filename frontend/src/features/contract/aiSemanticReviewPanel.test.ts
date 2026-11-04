import assert from "node:assert/strict";
import { createRequire } from "node:module";
import test from "node:test";
import { build } from "esbuild";

async function loadPanelHarness(): Promise<{
  renderPanel: (props: object) => string;
  renderDurableOutputs: (outputs: readonly object[]) => string;
}> {
  const bundled = await build({
    bundle: true,
    format: "cjs",
    jsx: "automatic",
    platform: "node",
    write: false,
    stdin: {
      contents: `
        import React from "react";
        import { renderToStaticMarkup } from "react-dom/server";
        import { AiSemanticReviewPanel } from "./src/features/contract/components/AiSemanticReviewPanel.tsx";
        import { adaptDurableSemanticReview } from "./src/features/contract/reviewRunSemanticAdapter.ts";
        export function renderPanel(props) {
          return renderToStaticMarkup(React.createElement(AiSemanticReviewPanel, props));
        }
        export function renderDurableOutputs(outputs) {
          return renderPanel({
            result: null, loading: false, error: null, notice: null, materials: [],
            canReanalyze: false, onReanalyze() {}, onSourceOpen() {},
            durableReview: adaptDurableSemanticReview(outputs, new Map()),
          });
        }
      `,
      loader: "tsx",
      resolveDir: process.cwd(),
      sourcefile: "ai-semantic-panel-harness.tsx",
    },
  });
  const compiled = { exports: {} as {
    renderPanel: (props: object) => string;
    renderDurableOutputs: (outputs: readonly object[]) => string;
  } };
  const require = createRequire(import.meta.url);
  new Function("require", "module", "exports", bundled.outputFiles[0].text)(
    require, compiled, compiled.exports,
  );
  return compiled.exports;
}

const baseProps = {
  result: null,
  loading: false,
  error: null,
  notice: null,
  materials: [],
  canReanalyze: true,
  onReanalyze: () => {},
  onSourceOpen: () => {},
};

test("not-started AI panel stays compact without rendering empty 4.1 4.2 4.3 slots", async () => {
  const { renderPanel } = await loadPanelHarness();
  const markup = renderPanel(baseProps);

  assert.match(markup, /智能语义审查/);
  assert.match(markup, /开始智能审核后/);
  assert.doesNotMatch(markup, /4\.1 跨材料语义一致性|未形成可靠判断/);
});

test("running AI panel shows an explicit truthful loading block without fake percentages", async () => {
  const { renderPanel } = await loadPanelHarness();
  const markup = renderPanel({ ...baseProps, loading: true });

  assert.match(markup, /正在进行合同语义分析/);
  assert.match(markup, /正在核对本次所选材料/);
  assert.match(markup, /分析可能需要一些时间，请稍候/);
  assert.doesNotMatch(markup, /37%|70%|90%|4\.1 跨材料语义一致性/);
});

test("result panel uses compact clickable filenames, hides right excerpts, and offers per-issue acknowledgement", async () => {
  const { renderPanel } = await loadPanelHarness();
  const materials = [
    { id: "contract", fileName: "03_智慧维修系统采购合同.docx" },
    { id: "tender", fileName: "采购文件.docx" },
  ];
  const result = {
    projectId: "project-1", operation: "CONTRACT_TEXT_ANALYSIS", requestId: "request-result",
    available: true, summaries: [], model: "deepseek-chat", durationMs: 100,
    hints: [
      { category: "PROJECT_IDENTITY", attention: "REVIEW", title: "项目名称待复核",
        description: "项目名称表述不同", evidenceRefs: ["contract", "tender"],
        evidenceItems: [{ fileId: "contract", status: "REVIEW", excerpt: "不得显示的项目原文",
          locatorAvailable: true }] },
      { category: "BREACH_LIABILITY", attention: "INSUFFICIENT", title: "违约责任依据不足",
        description: "缺少可比条款", evidenceRefs: ["contract"],
        evidenceItems: [{ fileId: "contract", status: "INSUFFICIENT", excerpt: "不得显示的违约原文",
          locatorAvailable: true }] },
      { category: "TERM_TERMINATION", attention: "REVIEW", title: "终止条件待复核",
        description: "终止条件存在差异", evidenceRefs: ["contract"],
        evidenceItems: [{ fileId: "contract", status: "REVIEW", excerpt: "不得显示的终止原文",
          locatorAvailable: true }] },
      { category: "SERVICE_WARRANTY", attention: "INFO", title: "质保条款一致",
        description: "未发现明显冲突", evidenceRefs: ["contract"] },
    ],
  };
  const markup = renderPanel({ ...baseProps, result, materials });

  assert.match(markup, /4\.1 跨材料语义一致性/);
  assert.match(markup, /4\.2 违约责任条款审查/);
  assert.match(markup, /4\.3 合同期限与终止条件审查/);
  assert.match(markup, /text-\[12px\][^\"]*font-medium/);
  assert.match(markup, /text-red-700[^\"]*font-(medium|semibold)/);
  assert.equal((markup.match(/我已知悉/g) ?? []).length, 3);
  assert.equal((markup.match(/待知悉/g) ?? []).length, 3);
  assert.doesNotMatch(markup, /不得显示的项目原文|不得显示的违约原文|不得显示的终止原文|原文摘录/);
});

test("INFO hints and deterministic helper facts never request AI acknowledgement", async () => {
  const { renderPanel } = await loadPanelHarness();
  const result = {
    projectId: "project-1", operation: "CONTRACT_TEXT_ANALYSIS", requestId: "request-info",
    available: true, summaries: [], model: "deepseek-chat", durationMs: 100,
    hints: [{ category: "TERM_TERMINATION", attention: "INFO", title: "期限已核对",
      description: "条款一致", evidenceRefs: ["contract"] }],
  };
  const termResult = {
    materialInsufficient: false,
    dates: [{ type: "SIGNING_DATE", source: "2026年9月3日", normalizedDate: "2026-09-03",
      status: "NORMAL", sourceFileId: "contract", excerpt: "签订日期为2026年9月3日",
      locatorAvailable: true }],
    conditions: [], comparisons: [],
  };
  const markup = renderPanel({ ...baseProps, result, termResult,
    materials: [{ id: "contract", fileName: "合同.docx" }] });

  assert.match(markup, /已检测条款事实/);
  assert.doesNotMatch(markup, /我已知悉|待知悉|原文摘录/);
});

test("durable review presentation keeps completed siblings and opens validated source material", async () => {
  const { renderPanel } = await loadPanelHarness();
  const durableReview = {
    title: "智能语义与条款审核",
    authorityNotice: "智能分析用于辅助发现需复核内容，最终判断以业务人员核验为准。",
    sections: [
      { key: "SEMANTIC", label: "智能语义审核", statusLabel: "已完成", statusTone: "success", detail: "已形成 1 项审核提示。",
        issues: [{ id: "finding-hidden", categoryLabel: "交付与验收", statusLabel: "需复核", statusTone: "review",
          sourceLabel: "智能分析提示", summary: "付款条件需要人工核对。", recommendation: "查看合同正文。",
          evidence: [{ id: "evidence-hidden", fileId: "file-hidden", fileName: "001.pdf",
            excerpt: "验收完成后支付。", locatorAvailable: true, locatorLabel: "第 2 页 · 第 3 段",
            pageNumber: 2, paragraphIndex: 3, segmentIndex: null, startOffset: 10, endOffset: 18 }] }] },
      { key: "BREACH", label: "违约条款审核", statusLabel: "未完成", statusTone: "danger", detail: "智能分析服务暂不可用，本环节未完成。", issues: [] },
      { key: "TERM", label: "履约期限与终止条件审核", statusLabel: "结果暂无法确认", statusTone: "uncertain", detail: "本环节结果暂无法确认，请稍后查看。", issues: [] },
    ],
  };
  const markup = renderPanel({ ...baseProps, durableReview, onDurableEvidenceOpen: () => {} });

  assert.match(markup, /智能语义审核/);
  assert.match(markup, /违约条款审核/);
  assert.match(markup, /履约期限与终止条件审核/);
  assert.match(markup, /付款条件需要人工核对/);
  assert.match(markup, /001\.pdf/);
  assert.match(markup, /第 2 页 · 第 3 段/);
  assert.match(markup, /text-emerald-800/);
  assert.match(markup, /text-rose-800/);
  assert.match(markup, /text-amber-800/);
  assert.match(markup, /text-orange-800/);
  assert.doesNotMatch(markup, /finding-hidden|evidence-hidden|file-hidden|ReviewRun|MODEL_ASSISTED/);
});

test("rendered durable clause findings never expose serialized backend fields or enum values", async () => {
  const { renderDurableOutputs } = await loadPanelHarness();
  const markup = renderDurableOutputs([{
    step: {
      stepId: "step-hidden", runId: "run-hidden", stepType: "BREACH", required: true,
      status: "COMPLETED", stepPolicyVersion: "policy-hidden", currentAttemptNo: 1,
      outputResourceType: "REVIEW_FINDING_SET", outputResourceId: "attempt-hidden",
      safeReasonCode: null, startedAt: null, completedAt: "2026-09-16T00:01:00Z", versionNo: 1,
    },
    findings: [{
      findingId: "finding-hidden", runId: "run-hidden", stepId: "step-hidden",
      attemptId: "attempt-hidden", ordinal: 1, findingType: "BREACH_CLAUSE", category: "BREACH",
      statusCode: "REVIEW", attentionCode: null, riskLevel: "YELLOW",
      summary: "scenario=OVERDUE_DELIVERY; rate=1 PERCENT; frequency=DAY; calculationBasis=CONTRACT_AMOUNT",
      recommendation: "请结合来源材料人工复核。", findingSchemaVersion: 1,
      policyVersion: "policy-hidden", source: "DETERMINISTIC", modelProvider: null,
      modelName: null, modelRevision: null, promptVersion: null, modelSchemaVersion: null,
      createdAt: "2026-09-16T00:01:00Z", versionNo: 0,
    }],
    evidence: [],
  }]);

  assert.match(markup, /系统已识别一项违约条款核对结果，请结合来源依据复核/);
  assert.doesNotMatch(markup, /scenario=|OVERDUE_DELIVERY|PERCENT|frequency=DAY|CONTRACT_AMOUNT|BREACH_CLAUSE|DETERMINISTIC|policy-hidden|run-hidden|step-hidden/);
});
