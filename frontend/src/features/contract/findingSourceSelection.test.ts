import assert from "node:assert/strict";
import test from "node:test";
import { findingSourceDisplayMode } from "./reviewFindingPresentation.ts";

test("an explicit durable unavailable projection overrides every legacy evidence fallback", () => {
  assert.equal(findingSourceDisplayMode({ available: false }, true), "UNAVAILABLE");
  assert.equal(findingSourceDisplayMode({ available: true }, true), "PROJECTED");
  assert.equal(findingSourceDisplayMode(null, true), "LEGACY");
  assert.equal(findingSourceDisplayMode(undefined, false), "UNAVAILABLE");
});
