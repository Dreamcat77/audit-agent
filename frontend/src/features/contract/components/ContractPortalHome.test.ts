import assert from "node:assert/strict";
import { createRequire } from "node:module";
import test from "node:test";
import { build } from "esbuild";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { readFile } from "node:fs/promises";

test("automatic analysis is the default without a build-time flag while explicit legacy data sources remain supported",async()=>{
  const source=await readFile(new URL('./ContractPortalHome.tsx',import.meta.url),'utf8');
  assert.match(source,/if\(!legacy&&!props\.dataSource\)/);
  assert.doesNotMatch(source,/VITE_CONTRACT_ANALYSIS_ENABLED/);
});

const bundled = await build({
  bundle: true, write: false, platform: "node", format: "cjs", jsx: "automatic",
  loader: { ".css": "empty" },
  external: ["react", "react-dom/server", "react/jsx-runtime", "lucide-react"],
  define: { "import.meta.env": '{"VITE_API_BASE_URL":"","VITE_CONTRACT_DATA_SOURCE":"http"}' },
  stdin: { contents: 'export { ContractPortalContent } from "./src/features/contract/components/ContractPortalHome.tsx";', loader: "tsx", resolveDir: process.cwd() },
});
const compiled = { exports: {} as any };
new Function("require", "module", "exports", bundled.outputFiles[0].text)(createRequire(import.meta.url), compiled, compiled.exports);
const { ContractPortalContent } = compiled.exports;

const base = {
  activeTab: "RECORDS", busy: false, notice: null,
  onTabChange() {}, onSelectFiles() {}, onRetry() {}, onOpenNoticeProject() {},
  recordsContent: React.createElement("div", null, "EXACT_RECORDS"),
  pendingContent: React.createElement("div", null, "DURABLE_PENDING"),
};

test("records are the default content and OA is explicitly unavailable", () => {
  const html = renderToStaticMarkup(React.createElement(ContractPortalContent, base));
  assert.match(html, /合同审核/);
  assert.match(html, /EXACT_RECORDS/);
  assert.doesNotMatch(html, /DURABLE_PENDING/);
  assert.match(html, /上传材料/);
  assert.match(html, /从 OA 导入（暂未接入）/);
  assert.match(html, /disabled=""/);
  assert.doesNotMatch(html, /智能分析|新建合同/);
});

test("pending tab shows the durable continuation workspace and file cancellation stays empty", () => {
  const selections: readonly File[][] = [];
  const props = { ...base, activeTab: "PENDING", onSelectFiles(files: readonly File[]) { (selections as File[][]).push([...files]); } };
  const tree = ContractPortalContent(props);
  function visit(node: any): any[] {
    if (!node || typeof node !== "object") return [];
    if (Array.isArray(node)) return node.flatMap(visit);
    if (typeof node.type === "function") return visit(node.type(node.props));
    return [node, ...visit(node.props?.children)];
  }
  const nodes = visit(tree);
  const input = nodes.find((node) => node.type === "input" && node.props?.type === "file");
  assert.ok(input);
  input.props.onChange({ target: { files: null, value: "unchanged" } });
  assert.deepEqual(selections, [[]]);
  const html = renderToStaticMarkup(tree);
  assert.match(html, /DURABLE_PENDING/);
  assert.doesNotMatch(html, /EXACT_RECORDS/);
});
