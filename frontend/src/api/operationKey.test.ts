import assert from "node:assert/strict";
import test from "node:test";
import { createOperationKey } from "./operationKey.ts";

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

test("uses native randomUUID without consuming fallback entropy", () => {
  let fallbackCalls = 0;
  const key = createOperationKey({
    randomUUID: () => "123e4567-e89b-42d3-a456-426614174000",
    getRandomValues: (bytes) => {
      fallbackCalls += 1;
      return bytes;
    },
  });

  assert.equal(key, "123e4567-e89b-42d3-a456-426614174000");
  assert.equal(fallbackCalls, 0);
});

test("uses getRandomValues to produce RFC 4122 UUID v4 version and variant bits", () => {
  const key = createOperationKey({
    getRandomValues: (bytes) => {
      bytes.fill(0xff);
      return bytes;
    },
  });

  assert.equal(key, "ffffffff-ffff-4fff-bfff-ffffffffffff");
  assert.match(key, UUID_V4);
  assert.equal(key[14], "4");
  assert.match(key[19]!, /[89ab]/);
});

test("fails closed when no secure Web Crypto source exists", () => {
  assert.throws(() => createOperationKey(null), /secure random generator is unavailable/);
});
