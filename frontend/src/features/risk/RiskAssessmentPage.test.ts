import assert from "node:assert/strict";
import { createRequire } from "node:module";
import test from "node:test";
import { build } from "esbuild";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";

const require = createRequire(import.meta.url);
const result = await build({
  bundle: true, write: false, platform: "node", format: "cjs", jsx: "automatic",
  external: ["react", "react-dom/server", "react/jsx-runtime", "lucide-react"],
  define: { "import.meta.env": '{"VITE_API_BASE_URL":""}' },
  stdin: {
    contents: 'export { RiskAssessmentContent } from "./src/features/risk/RiskAssessmentPage.tsx";',
    loader: "tsx", resolveDir: process.cwd(),
  },
});
const module = { exports: {} as any };
new Function("require", "module", "exports", result.outputFiles[0].text)(require, module, module.exports);
const { RiskAssessmentContent } = module.exports;

const task = {
  taskId: "task-risk",
  contract: { contractNo: "HT-9", contractName: "风险合同", partyB: "供应商" },
  project: { projectName: "风险项目", projectNo: "XM-9" },
  reviewProgress: { current: 4, total: 5 },
  risk: { riskCount: 2, materialInsufficient: false, riskSummary: "ATTENTION" },
  reviewRun: { runId: "run-exact-risk", status: "PARTIALLY_COMPLETED" },
};
const assessment = {
  scopeLabel: "当前用户可访问的持久化合同审核记录",
  reviewRequiredCount: 1,
  materialInsufficientCount: 0,
  items: [{ task, coverage: { label: "已覆盖 4/5 项", unassessed: true } }],
};

test("renders persisted facts, coverage and no fabricated score", () => {
  const html = renderToStaticMarkup(React.createElement(RiskAssessmentContent, {
    loading: false, error: null, assessment, onRetry() {}, onOpenTask() {},
  }));

  assert.match(html, /当前用户可访问的持久化合同审核记录/);
  assert.match(html, /风险项目/);
  assert.match(html, /风险合同/);
  assert.match(html, /已覆盖 4\/5 项/);
  assert.match(html, /尚有范围未完成审查/);
  assert.doesNotMatch(html, /风险评分|准确率|趋势预测/);
});

test("opens the exact persisted task and provides an honest unavailable state", () => {
  const opened: any[] = [];
  const tree = RiskAssessmentContent({ loading: false, error: null, assessment,
    onRetry() {}, onOpenTask(value: any) { opened.push(value); } });
  function visit(node: any): any[] {
    if (!node || typeof node !== "object") return [];
    if (Array.isArray(node)) return node.flatMap(visit);
    if (typeof node.type === "function") return visit(node.type(node.props));
    return [node, ...visit(node.props?.children)];
  }
  const button = visit(tree).find((node) => node.type === "button" && node.props?.children?.includes?.("查看审核结果"));
  assert.ok(button);
  button.props.onClick();
  assert.equal(opened[0], task);
  assert.equal(opened[0].reviewRun.runId, "run-exact-risk");

  const errorHtml = renderToStaticMarkup(React.createElement(RiskAssessmentContent, {
    loading: false, error: "审核记录暂时无法读取", assessment: null, onRetry() {}, onOpenTask() {},
  }));
  assert.match(errorHtml, /审核记录暂时无法读取/);
  assert.doesNotMatch(errorHtml, />0</);
});
