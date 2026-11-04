import assert from "node:assert/strict";
import { createRequire } from "node:module";
import test from "node:test";
import { build } from "esbuild";

async function renderDurableCompletedF02(): Promise<string> {
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
        import F02ReviewResultPanel from "./src/features/contract/components/F02ReviewResultPanel.tsx";
        export function renderPanel() {
          return renderToStaticMarkup(React.createElement(F02ReviewResultPanel, {
            result: {
              taskId: "task-1", status: "DONE", reviewType: "F02_QUANTITATIVE_CONSISTENCY",
              overallRisk: "GREEN", blockSign: false, replayed: false,
              findings: [{ id: "check-1", ruleCode: "F02_CONTRACT_TOTAL_MATCH", itemKey: null,
                checkStatus: "PASS", riskLevel: "GREEN", blockSign: false,
                actualValue: "100.00", expectedValue: "100.00", detail: "核对一致",
                ruleVersion: 1, createdAt: "2026-09-16T00:00:00Z" }],
            },
            progress: null,
            progressiveReplayState: null,
            reviewLoading: false,
            resultLoading: false,
            resultUnavailable: false,
            showAllPersistedFindings: true,
            durableStatus: "COMPLETED",
          }));
        }
      `,
      loader: "tsx",
      resolveDir: process.cwd(),
      sourcefile: "f02-durable-panel-harness.tsx",
    },
  });
  const compiled = { exports: {} as { renderPanel: () => string } };
  const require = createRequire(import.meta.url);
  new Function("require", "module", "exports", bundled.outputFiles[0].text)(
    require, compiled, compiled.exports,
  );
  return compiled.exports.renderPanel();
}

test("completed durable F02 output is labelled complete without legacy acknowledgement state", async () => {
  const markup = await renderDurableCompletedF02();
  assert.match(markup, /审核完成/);
  assert.doesNotMatch(markup, /审核进行中|待知悉|我已知悉|人工知悉/);
});
