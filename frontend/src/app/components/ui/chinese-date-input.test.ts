import assert from "node:assert/strict";
import test from "node:test";

import { chineseDateInputPresentation } from "./chinese-date-input-presentation.ts";

test("empty native date input presents an explicit Chinese year month day affordance", () => {
  assert.deepEqual(chineseDateInputPresentation("", false), {
    type: "date",
    lang: "zh-CN",
    value: "",
    emptyPlaceholder: "年 / 月 / 日",
    showPlaceholder: true,
  });
});

test("empty focused native date input keeps the Chinese year month day affordance visible", () => {
  assert.deepEqual(chineseDateInputPresentation("", true), {
    type: "date",
    lang: "zh-CN",
    value: "",
    emptyPlaceholder: "年 / 月 / 日",
    showPlaceholder: true,
  });
});

test("native date input preserves the YYYY-MM-DD business value unchanged", () => {
  assert.deepEqual(chineseDateInputPresentation("2026-09-03", true), {
    type: "date",
    lang: "zh-CN",
    value: "2026-09-03",
    emptyPlaceholder: null,
    showPlaceholder: false,
  });
});
