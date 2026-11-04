import assert from "node:assert/strict";
import test from "node:test";
import { factExtractionPresentation } from "./factExtractionPresentation.ts";
import type { ContractFactExtractionProjection } from "./types.ts";

const projection: ContractFactExtractionProjection = {
  currentParseResultId: "parse-1", currentParseVersion: 1,
  attempt: {
    attemptId: "attempt-1", status: "SUCCEEDED", callCount: 1,
    acceptedCount: 3, rejectedCount: 1, failureReason: null,
  },
  candidates: [],
  rejections: [{ scope: "LINE_ITEMS", reasonCode: "UNKNOWN_EVIDENCE_ID" }],
  modelCalls: [
    { attemptNo: 1, scope: "CORE", callOrdinal: 1, outcome: "SUCCEEDED",
      safeReason: null, batchStatus: "PARTIAL" },
    { attemptNo: 1, scope: "LINE_ITEMS", callOrdinal: 1, outcome: "SUCCEEDED",
      safeReason: null, batchStatus: "PARTIAL" },
  ],
};

test("presents partial scope coverage without calling it complete", () => {
  const view = factExtractionPresentation("CONTRACT_BODY", projection);

  assert.equal(view?.statusLabel, "处理完成，已整理出可核实信息");
  assert.equal(view?.countLabel, "自动采用 3 项 · 拒绝 1 项");
  assert.deepEqual(view?.scopes.map((scope) => [scope.label, scope.statusLabel]), [
    ["核心事实", "已提取部分候选"], ["金额明细", "已提取部分候选"],
  ]);
});

test("shows failed and missing scope states honestly", () => {
  const view = factExtractionPresentation("AWARD_NOTICE", {
    ...projection,
    attempt: { ...projection.attempt!, status: "FAILED", acceptedCount: 0,
      rejectedCount: 2, failureReason: "ALL_SCOPES_FAILED" },
    rejections: [], modelCalls: [],
  });

  assert.equal(view?.statusLabel, "提取未完成，已保留可用的材料结果");
  assert.deepEqual(view?.scopes.map((scope) => scope.statusLabel), ["未形成可验证结果"]);
});

test("a successful model response with no grounded facts is still a finished result", () => {
  const view = factExtractionPresentation("CONTRACT_BODY", {
    ...projection,
    attempt: { ...projection.attempt!, acceptedCount: 0, rejectedCount: 0 },
    modelCalls: [],
  });

  assert.equal(view?.statusLabel, "处理完成，未找到可确认的信息");
});

test("does not invent diagnostics when no extraction attempt exists", () => {
  assert.equal(factExtractionPresentation("CONTRACT_BODY", {
    currentParseResultId: "parse-1", currentParseVersion: 1,
    attempt: null, candidates: [], rejections: [], modelCalls: [],
  }), null);
});
