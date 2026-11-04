import assert from "node:assert/strict";
import test from "node:test";
import { createHash } from "node:crypto";
import { CloseoutOperations, commandFingerprint } from "./closeoutOperation.ts";

function storage() {
  const entries = new Map<string, string>();
  return { getItem: (key: string) => entries.get(key) ?? null, setItem: (key: string, value: string) => { entries.set(key, value); }, removeItem: (key: string) => { entries.delete(key); }, entries };
}
test("refresh preserves uncertain operation identity and never persists handling note", async () => {
  const store = storage();
  const first = new CloseoutOperations(store);
  const fingerprint = await commandFingerprint(["actor", "run", 0, "敏感处理意见"]);
  const key = first.begin("ack:actor:run:finding", fingerprint);
  const reopened = new CloseoutOperations(store);
  assert.equal(reopened.begin("ack:actor:run:finding", fingerprint), key);
  assert.throws(() => reopened.begin("ack:actor:run:finding", "changed"), /尚未确认/);
  assert.doesNotMatch(JSON.stringify([...store.entries]), /敏感处理意见/);
  assert.throws(() => reopened.newVersion("ack:actor:run:finding"), /尚未确认/);
});
test("only verified terminal result plus explicit action permits new report key", () => {
  const operations = new CloseoutOperations(storage());
  const first = operations.begin("report:actor:run", "command");
  operations.observe("report:actor:run", "report-id", "UNKNOWN");
  assert.equal(operations.begin("report:actor:run", "command"), first);
  assert.throws(() => operations.newVersion("report:actor:run"));
  operations.observe("report:actor:run", "report-id", "AVAILABLE");
  assert.equal(operations.begin("report:actor:run", "command"), first);
  operations.newVersion("report:actor:run");
  assert.notEqual(operations.begin("report:actor:run", "command"), first);
});
test("corrupt persisted operation fails closed and cannot silently replace key", () => {
  const store = storage(); store.setItem("contract-closeout:v1:scope", "garbled");
  assert.throws(() => new CloseoutOperations(store).begin("scope", "command"), /操作记录/);
});

test("HTTP browser without randomUUID retains a secure immutable operation key", () => {
  const descriptor = Object.getOwnPropertyDescriptor(globalThis, "crypto");
  let calls = 0;
  Object.defineProperty(globalThis, "crypto", { configurable: true, value: {
    getRandomValues: (bytes: Uint8Array) => { calls += 1; bytes.fill(0x35); return bytes; },
  } });
  try {
    const store = storage();
    const first = new CloseoutOperations(store).begin("http-browser", "same-command");
    assert.match(first, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    assert.equal(new CloseoutOperations(store).begin("http-browser", "same-command"), first);
    assert.equal(calls, 1);
    assert.throws(() => new CloseoutOperations(store).begin("http-browser", "changed-command"));
  } finally {
    if (descriptor) Object.defineProperty(globalThis, "crypto", descriptor);
    else Reflect.deleteProperty(globalThis, "crypto");
  }
});

test("plain HTTP can fingerprint Chinese confirmation notes without WebCrypto", async () => {
  const descriptor=Object.getOwnPropertyDescriptor(globalThis,"crypto");
  Object.defineProperty(globalThis,"crypto",{configurable:true,value:{}});
  try {
    const command=["actor","项目",null,0,"已核对金额及附件"];
    const expected=createHash("sha256").update(JSON.stringify(command)).digest("hex");
    assert.equal(await commandFingerprint(command),expected);
    assert.equal(await commandFingerprint(command),expected);
    assert.notEqual(await commandFingerprint([...command,"changed"]),expected);
  } finally {
    if(descriptor)Object.defineProperty(globalThis,"crypto",descriptor);
    else Reflect.deleteProperty(globalThis,"crypto");
  }
});
