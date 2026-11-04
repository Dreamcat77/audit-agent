import assert from "node:assert/strict";
import { registerHooks } from "node:module";
import { readFileSync } from "node:fs";
import test from "node:test";
import { transformSync } from "esbuild";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
registerHooks({ load(url, context, nextLoad) {
  if (url.endsWith(".tsx")) return { format: "module", shortCircuit: true,
    source: transformSync(readFileSync(new URL(url), "utf8"), { loader: "tsx", format: "esm", jsx: "automatic" }).code };
  const result = nextLoad(url, context);
  if (url.endsWith("/api/client.ts")) return { ...result, source: `import.meta.env ??= {};\n${result.source}` };
  return result;
} });
const { ContractFindingAcknowledgement } = await import("./ContractFindingAcknowledgement.tsx");
const { ContractReviewReportPanel } = await import("./ContractReviewReportPanel.tsx");
const context = { actorId: "internal-actor", projectId: "internal-project", contractId: "internal-contract", runId: "technical-run" };
test("ack customer control is plain Chinese, records only seen, and waits for rediscovery", () => {
  const html = renderToStaticMarkup(React.createElement(ContractFindingAcknowledgement, { context, findingId: "raw-finding-uuid", findingVersionNo: 0 }));
  assert.match(html, /确认已查看/); assert.match(html, /不代表问题已解决/); assert.match(html, /disabled/);
  assert.doesNotMatch(html, /raw-finding-uuid|internal-actor|technical-run|ACKNOWLEDGED|policyVersion/);
});
test("report panel renders status safely, not identifiers or server internals", () => {
  const html = renderToStaticMarkup(React.createElement(ContractReviewReportPanel, { context, runStatus: "UNKNOWN", initialReports: [
    { reportId: "raw-report-uuid", reviewRunId: "technical-run", reportVersion: 2, reportSchemaVersion: 1, status: "UNKNOWN",
      generatedBy: "internal-actor", requestedAt: "2026-09-16T00:00:00Z", generatedAt: null, artifactMediaType: null, artifactSize: null }
  ] }));
  assert.match(html, /结果状态待确认/); assert.match(html, /审核结束后系统会自动生成报告/); assert.match(html, /第 2 版/);
  assert.doesNotMatch(html, /raw-report-uuid|technical-run|internal-actor|UNKNOWN|policyVersion|sha256/);
  assert.doesNotMatch(html, /href=/);
});
test("historical view keeps closeout facts readable without exposing write controls", () => {
  const acknowledgement = renderToStaticMarkup(React.createElement(ContractFindingAcknowledgement, {
    context, findingId: "raw-finding-uuid", findingVersionNo: 0, readOnly: true,
  }));
  const report = renderToStaticMarkup(React.createElement(ContractReviewReportPanel, {
    context, runStatus: "COMPLETED", readOnly: true,
  }));
  assert.doesNotMatch(acknowledgement, /<textarea/);
  assert.equal((acknowledgement.match(/<button/g) ?? []).length, 1);
  assert.equal((report.match(/<button/g) ?? []).length, 1);
});
