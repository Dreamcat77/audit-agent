import assert from "node:assert/strict";
import test from "node:test";

import { resolveApiProxyTarget } from "./viteProxyTarget.ts";

test("uses the legacy 8080 backend when no local override is provided", () => {
  assert.equal(resolveApiProxyTarget(undefined), "http://127.0.0.1:8080");
});

test("accepts the final closeout backend port as an explicit server-only override", () => {
  assert.equal(resolveApiProxyTarget("http://127.0.0.1:8888"), "http://127.0.0.1:8888");
});

test("rejects proxy targets that contain credentials or route fragments", () => {
  for (const value of [
    "http://user:secret@127.0.0.1:8888",
    "http://127.0.0.1:8888/api",
    "file:///tmp/backend",
  ]) {
    assert.throws(() => resolveApiProxyTarget(value));
  }
});
