import assert from "node:assert/strict";
import test from "node:test";

import {
  acknowledgeAiSemanticIssue,
  aiSemanticAcknowledgementKey,
  isAiSemanticIssueAcknowledged,
  requiresAiSemanticAcknowledgement,
} from "./aiSemanticAcknowledgement.ts";

class MemorySessionStorage {
  private readonly values = new Map<string, string>();

  getItem(key: string): string | null {
    return this.values.get(key) ?? null;
  }

  setItem(key: string, value: string): void {
    this.values.set(key, value);
  }
}

test("only REVIEW and INSUFFICIENT AI hints require acknowledgement", () => {
  assert.equal(requiresAiSemanticAcknowledgement("REVIEW"), true);
  assert.equal(requiresAiSemanticAcknowledgement("INSUFFICIENT"), true);
  assert.equal(requiresAiSemanticAcknowledgement("INFO"), false);
});

test("acknowledging one of two same-category issues changes only the selected issue", () => {
  const storage = new MemorySessionStorage();
  acknowledgeAiSemanticIssue(storage, "request-1", "PROJECT_IDENTITY", 0);

  assert.equal(isAiSemanticIssueAcknowledged(storage, "request-1", "PROJECT_IDENTITY", 0), true);
  assert.equal(isAiSemanticIssueAcknowledged(storage, "request-1", "PROJECT_IDENTITY", 1), false);
});

test("a new analysis requestId cannot inherit an older analysis acknowledgement", () => {
  const storage = new MemorySessionStorage();
  acknowledgeAiSemanticIssue(storage, "request-old", "TERM_TERMINATION", 0);

  assert.equal(isAiSemanticIssueAcknowledged(storage, "request-new", "TERM_TERMINATION", 0), false);
  assert.equal(
    aiSemanticAcknowledgementKey("request-new", "TERM_TERMINATION", 0),
    "audit-agent:ai-semantic-ack:request-new:TERM_TERMINATION:0",
  );
});
