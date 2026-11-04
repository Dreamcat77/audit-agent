import assert from "node:assert/strict";
import { createRequire } from "node:module";
import test from "node:test";
import { build } from "esbuild";
import React from "react";
import { createRoot } from "react-dom/client";
import { installScopedGlobals } from "../../test/scopedGlobals.ts";

import { copyText } from "./clipboard.ts";
import { nextOpenCategory } from "./eightCategoryReviewPanelState.ts";
import type { ContractReviewCategoryView } from "./eightCategoryReviewProjection.ts";

function categories(): ContractReviewCategoryView[] {
  const titles = [
    "合同条款量化一致性审查", "合同草本结构对标审查", "违约责任条款量化审查",
    "合同期限与终止条件格式审查", "合同主体资格审查", "附件清单完整性审查",
    "合同生效条件与份数审查", "合同条款风险提示",
  ];
  const ids: ContractReviewCategoryView["id"][] = [
    "QUANTITATIVE", "DRAFT_STRUCTURE", "BREACH", "TERM", "PARTY_QUALIFICATION",
    "ATTACHMENT_COMPLETENESS", "EFFECTIVENESS_COPIES", "CLAUSE_RISK",
  ];
  return ids.map((id, index) => ({
    id, order: (index + 1) as ContractReviewCategoryView["order"], title: titles[index]!,
    coverage: id === "ATTACHMENT_COMPLETENESS" ? "NOT_COVERED" : "COVERED",
    judgement: index === 2 ? "ATTENTION" : id === "ATTACHMENT_COMPLETENESS" ? "INSUFFICIENT" : "NORMAL",
    statusLabel: index === 2 ? "发现需要关注的结果" : id === "ATTACHMENT_COMPLETENESS" ? "当前审核未覆盖" : "本项已核对",
    attentionCount: index === 2 ? 1 : 0, summary: `${titles[index]} summary`,
    uncoveredScopes: id === "ATTACHMENT_COMPLETENESS" ? ["缺少附件引用与实际附件匹配结果"] : [],
    items: index === 2 ? [{
      stableId: "run-1:BREACH:finding-1", outputType: "BREACH", outputId: "finding-1",
      title: "逾期责任", status: "ATTENTION", summary: "每日比例存在差异",
      actualValue: "1%", expectedValue: "0.5%", recommendation: "请复核",
      source: "DETERMINISTIC", typedFacts: [], primary: true,
      evidence: [{ evidenceId: "evidence-1", fileId: "file-1", fileName: "合同.pdf",
        excerpt: "逾期每日1%", locatorAvailable: true, pageNumber: 2, paragraphIndex: null,
        segmentIndex: null, startOffset: 10, endOffset: 17 }],
    }] : [],
  }));
}

async function render(initialOpenCategoryId: ContractReviewCategoryView["id"] | null, overrides = {}): Promise<string> {
  const bundled = await build({
    bundle: true, format: "cjs", jsx: "automatic", platform: "node", write: false,
    stdin: {
      contents: `
        import React from "react";
        import { renderToStaticMarkup } from "react-dom/server";
        import { EightCategoryReviewPanel } from "./src/features/contract/components/EightCategoryReviewPanel.tsx";
        export function renderPanel(props) {
          return renderToStaticMarkup(React.createElement(EightCategoryReviewPanel, props));
        }
      `,
      loader: "tsx", resolveDir: process.cwd(), sourcefile: "eight-category-panel-harness.tsx",
    },
  });
  const compiled = { exports: {} as { renderPanel(props: object): string } };
  const require = createRequire(import.meta.url);
  new Function("require", "module", "exports", bundled.outputFiles[0]!.text)(require, compiled, compiled.exports);
  return compiled.exports.renderPanel({
    categories: categories(), runId: "run-1", runStatus: "COMPLETED", initialOpenCategoryId,
    onEvidenceOpen() {}, clipboard: { async writeText() {} }, ...overrides,
  });
}

test("renders a compact two-column directory of eight collapsed categories", async () => {
  const markup = await render(null);
  for (const category of categories()) assert.match(markup, new RegExp(category.title));
  assert.equal((markup.match(/data-category-row=/g) ?? []).length, 8);
  assert.match(markup, /data-eight-category-grid/);
  assert.match(markup, /md:grid-cols-2/);
  assert.doesNotMatch(markup, /data-category-content=/);
  const categoryRows = markup.slice(markup.indexOf("data-eight-category-grid"));
  assert.doesNotMatch(categoryRows, /data-category-summary=/);
  assert.doesNotMatch(categoryRows, /每日比例存在差异|逾期每日1%/);
});

test("shows available project and contract identity in a compact top summary card", async () => {
  const markup = await render(null, { projectInfo: {
    projectName: "办公设备采购项目", projectNo: "CG-2026-018", contractName: "办公设备采购合同",
    contractNo: "HT-2026-018", supplier: "示例供应商", contractAmount: "￥180,000.00",
  } });
  assert.match(markup, /aria-label="项目基本信息"/);
  assert.match(markup, /项目名称/);
  assert.match(markup, /办公设备采购项目/);
  assert.match(markup, /CG-2026-018/);
  assert.match(markup, /示例供应商/);
  assert.match(markup, /￥180,000\.00/);
});

test("single-open state opens category three and switching to four closes category three", async () => {
  assert.equal(nextOpenCategory(null, "BREACH"), "BREACH");
  assert.equal(nextOpenCategory("BREACH", "TERM"), "TERM");
  assert.equal(nextOpenCategory("TERM", "TERM"), null);

  const breach = await render("BREACH");
  assert.match(breach, /data-category-content="BREACH"/);
  assert.match(breach, /每日比例存在差异/);
  assert.doesNotMatch(breach, /data-category-content="TERM"/);
  const term = await render("TERM");
  assert.match(term, /data-category-content="TERM"/);
  const termCategories = term.slice(term.indexOf("data-eight-category-grid"));
  assert.doesNotMatch(termCategories, /data-category-content="BREACH"|每日比例存在差异/);
});

test("copy reports success and failure without invoking review or model operations", async () => {
  const writes: string[] = [];
  const success = await copyText({ writeText: async (value) => { writes.push(value); } }, "persisted opinion");
  const failure = await copyText({ writeText: async () => { throw new Error("denied"); } }, "persisted opinion");
  assert.equal(success, true);
  assert.equal(failure, false);
  assert.deepEqual(writes, ["persisted opinion"]);
});

test("opinion preview provides readonly selectable text for manual copying when clipboard is denied", async () => {
  const denied = await copyText({ writeText: async () => { throw new Error("denied"); } }, "意见");
  assert.equal(denied, false);
  const markup = await render(null);
  assert.match(markup, /textarea[^>]*aria-label="综合意见手动复制"[^>]*readonly=""/);
  assert.match(markup, /手动全选复制/);
});

test("summary and full detail previews are separate and complete detail remains explicitly copyable", async () => {
  const markup = await render("BREACH");
  assert.match(markup, /预览综合意见（简版）/);
  assert.match(markup, /预览综合完整明细/);
  assert.match(markup, /复制综合完整明细/);
  assert.match(markup, /复制本项完整明细/);
  const summaryText = markup.match(/<textarea[^>]*aria-label="综合意见手动复制"[^>]*>([\s\S]*?)<\/textarea>/)?.[1];
  assert.ok(summaryText);
  assert.doesNotMatch(summaryText, /逾期每日1%|合同\/当前值/);
  assert.match(summaryText, /逾期责任/);
  const fullText = markup.match(/<textarea[^>]*aria-label="综合完整明细手动复制"[^>]*>([\s\S]*?)<\/textarea>/)?.[1];
  assert.ok(fullText);
  assert.match(fullText, /逾期每日1%/);
  assert.match(fullText, /合同\/当前值：1%/);
});

// Real component state and handlers; only the browser DOM and ClipboardPort
// boundaries are supplied here. No navigator.clipboard access is claimed.
class HostElement {
  nodeType = 1; namespaceURI = "http://www.w3.org/1999/xhtml"; style = {};
  children: HostElement[] = []; parentNode: HostElement | null = null;
  textContent = ""; nodeValue = ""; ownerDocument: any; tagName: string; nodeName: string;
  constructor(doc: any, tag: string) { this.ownerDocument = doc; this.tagName = this.nodeName = tag.toUpperCase(); }
  addEventListener() {} removeEventListener() {} setAttribute() {} setAttributeNS() {} removeAttribute() {}
  appendChild(child: HostElement) { child.parentNode = this; this.children.push(child); return child; }
  insertBefore(child: HostElement, before: HostElement) { child.parentNode = this;
    const index = this.children.indexOf(before); if (index < 0) this.children.push(child); else this.children.splice(index, 0, child); return child; }
  removeChild(child: HostElement) { this.children.splice(this.children.indexOf(child), 1); child.parentNode = null; return child; }
}

async function interactivePanel(t: test.TestContext) {
  const bundled = await build({ bundle: true, format: "cjs", jsx: "automatic", platform: "node", write: false,
    external: ["react", "react/jsx-runtime", "lucide-react"],
    stdin: { contents: 'export { EightCategoryReviewPanel } from "./src/features/contract/components/EightCategoryReviewPanel.tsx";',
      loader: "tsx", resolveDir: process.cwd() } });
  const compiled = { exports: {} as any };
  new Function("require", "module", "exports", bundled.outputFiles[0]!.text)(createRequire(import.meta.url), compiled, compiled.exports);
  const doc: any = { nodeType: 9, activeElement: null, addEventListener() {}, removeEventListener() {},
    createElement: (tag: string) => new HostElement(doc, tag),
    createElementNS: (_namespace: string, tag: string) => new HostElement(doc, tag),
    createTextNode: (text: string) => Object.assign(new HostElement(doc, "#text"), { nodeType: 3, nodeValue: text }) };
  doc.documentElement = new HostElement(doc, "html"); doc.body = new HostElement(doc, "body");
  const win = { document: doc, HTMLElement: HostElement, HTMLIFrameElement: class {}, addEventListener() {}, removeEventListener() {} };
  doc.defaultView = win;
  const restore = installScopedGlobals({ window: win, document: doc, IS_REACT_ACT_ENVIRONMENT: true });
  const container = new HostElement(doc, "div"), root = createRoot(container as any);
  t.after(async () => { try { await React.act(async () => root.unmount()); } finally { restore(); } });
  const writes: string[] = [];
  let rejectCopy = false;
  let props = { categories: categories(), runId: "run-1", runStatus: "FAILED", initialOpenCategoryId: "BREACH",
    onEvidenceOpen() {}, clipboard: { async writeText(value: string) { writes.push(value); if (rejectCopy) throw Error("denied"); } } };
  async function update(changes = {}) { props = { ...props, ...changes }; await React.act(async () => root.render(React.createElement(compiled.exports.EightCategoryReviewPanel, props))); }
  const propsOf = (node: HostElement): any => (node as any)[Object.keys(node).find((key) => key.startsWith("__reactProps$"))!];
  const textOf = (node: HostElement): string => node.textContent + node.nodeValue + node.children.map(textOf).join("");
  function find(predicate: (node: HostElement) => boolean, node = container): HostElement | undefined {
    return predicate(node) ? node : node.children.map((child) => find(predicate, child)).find(Boolean);
  }
  await update();
  return { writes, update, deny() { rejectCopy = true; },
    async clickCategory(id: string) { const row = find((node) => propsOf(node)?.["data-category-row"] === id);
      assert.ok(row, id); await React.act(async () => propsOf(row).onClick()); },
    textarea(label: string) { const node = find((node) => node.tagName === "TEXTAREA" && propsOf(node)?.["aria-label"] === label);
      return node ? propsOf(node) : undefined; },
    async click(label: string) { const button = find((node) => node.tagName === "BUTTON" && textOf(node) === label);
      assert.ok(button, label); await React.act(async () => propsOf(button).onClick()); },
  };
}

test("real panel handlers copy the chosen summary/detail and denied copy remains selectable with exact Run identity", async (t) => {
  const panel = await interactivePanel(t);
  await panel.click("复制综合意见");
  assert.match(panel.writes.at(-1)!, /简版草稿/);
  assert.match(panel.writes.at(-1)!, /run-1/);
  assert.doesNotMatch(panel.writes.at(-1)!, /逾期每日1%/);
  await panel.click("复制综合完整明细");
  assert.match(panel.writes.at(-1)!, /逾期每日1%/);
  assert.match(panel.writes.at(-1)!, /草稿/);
  await panel.click("复制本项意见");
  assert.doesNotMatch(panel.writes.at(-1)!, /逾期每日1%/);
  assert.match(panel.writes.at(-1)!, /草稿/);
  assert.match(panel.writes.at(-1)!, /run-1/);
  panel.deny();
  await panel.click("复制本项完整明细");
  assert.match(panel.writes.at(-1)!, /逾期每日1%/);
  const fallback = panel.textarea("复制失败后的手动复制文本");
  assert.ok(fallback); assert.equal(fallback.readOnly, true);
  assert.equal(fallback.value, panel.writes.at(-1));
  let selected = false; fallback.onFocus({ currentTarget: { select() { selected = true; } } }); assert.equal(selected, true);
  await panel.update({ runId: "run-2" });
  assert.equal(panel.textarea("复制失败后的手动复制文本"), undefined, "new Run must not show old Run copy fallback");
});

test("canonical paragraph is visible by default and frozen warnings remain expandable with all eight rows", async () => {
  const observation = { protocol: "contract-money-observation-v1", mode: "OBSERVE",
    warnings: [{ fieldPath: "hints[0].summary", errorKind: "MONETARY_CALCULATION", category: "DELIVERY_TERMS", textSha256: "a".repeat(64), ruleSubtypes: ["UNSAFE_CONCLUSION"] }],
    workWarnings: [{ logicalKey: "A11", ordinal: 11, purpose: "ANALYZE", operationFingerprint: "b".repeat(64), warningCount: 1, warningsSha256: "c".repeat(64) }] };
  const markup = await render(null, { categories: categories().map(category => ({ ...category,
    overallOpinion: "合同金额69,000元与成交金额67,000元存在2,000元差异，请核实交付条件。", monetaryObservation: observation })) });
  assert.match(markup, /<h3[^>]*>综合审核意见<\/h3>/);
  assert.match(markup, /data-overall-opinion[^>]*>合同金额69,000元与成交金额67,000元存在2,000元差异，请核实交付条件。/);
  assert.match(markup, /data-monetary-observation-marker/);
  assert.match(markup, /<details[^>]*data-monetary-observation-details/);
  assert.match(markup, /金额表述需要复核/);
  assert.doesNotMatch(markup, /hints\[0\]\.summary|最终意见字段|文字指纹/);
  assert.doesNotMatch(markup, /UNSAFE_CONCLUSION|A11|操作指纹|警告指纹/);
  assert.match(markup, /另有 1 处金额表述待核对/);
  assert.equal((markup.match(/data-category-row=/g) ?? []).length, 8);
});

test("all eighteen real panel copy handlers carry the observation marker and denied fallback retains it", async (t) => {
  const panel = await interactivePanel(t);
  const marker = "【联调参考意见｜金额相关模型表述尚待人工复核】";
  const observed = categories().map(category => ({ ...category, opinionVersion: 4,
    overallOpinion: "合同金额存在差异，请核实交付条件。",
    monetaryObservation: { protocol: "contract-money-observation-v1", mode: "OBSERVE", warnings: [], workWarnings: [] } }));
  await panel.update({ categories: observed, runStatus: "COMPLETED" });
  await panel.click("复制综合意见");
  assert.match(panel.writes[0]!, /合同金额存在差异，请核实交付条件。/);
  await panel.click("复制综合完整明细");
  for (const category of observed) {
    await panel.clickCategory(category.id);
    await panel.click("复制本项意见");
    await panel.click("复制本项完整明细");
  }
  assert.equal(panel.writes.length, 18);
  for (const text of panel.writes) assert.equal(text.split(marker).length - 1, 1);
  panel.deny();
  await panel.click("复制本项完整明细");
  assert.ok(panel.textarea("复制失败后的手动复制文本").value.includes(marker));
});
