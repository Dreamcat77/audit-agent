import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import vm from "node:vm";

// Execute the real standalone helper without importing the top-level visual run,
// which intentionally starts Vite and a browser. Only its WebSocket boundary is controlled.
function harness({ autoOpen = true } = {}) {
  const sockets = [];
  const timers = new Set();
  const trackedSetTimeout = (callback, milliseconds) => {
    const timer = setTimeout(() => { timers.delete(timer); callback(); }, milliseconds);
    timers.add(timer); return timer;
  };
  const trackedClearTimeout = (timer) => { timers.delete(timer); clearTimeout(timer); };
  class ControlledSocket extends EventTarget {
    constructor() {
      super(); this.readyState = 0; this.commands = []; this.closeCalls = 0;
      sockets.push(this);
      if (autoOpen) queueMicrotask(() => {
        this.readyState = 1; this.dispatchEvent(new Event("open"));
      });
    }
    send(value) {
      if (this.sendFailure) throw new Error("SYNTHETIC_SEND_FAILURE");
      this.commands.push(JSON.parse(value));
    }
    close() {
      this.closeCalls++; this.readyState = 3; this.dispatchEvent(new Event("close"));
    }
    reply(value) { this.dispatchEvent(new MessageEvent("message", { data: JSON.stringify(value) })); }
    error() { this.dispatchEvent(new Event("error")); }
  }
  const source = readFileSync(new URL("./LoginPage.visual.test.mjs", import.meta.url), "utf8").replace(/\r\n/g, "\n");
  const helper = source.match(/async function connectDebugger\([\s\S]*?\n\}/)?.[0];
  assert.ok(helper, "standalone debugger helper must remain available to this offline test");
  const connect = vm.runInNewContext(`(${helper})`, { WebSocket: ControlledSocket, setTimeout: trackedSetTimeout, clearTimeout: trackedClearTimeout });
  return { connect, sockets, timers };
}

async function within(promise, timeoutMs = 150) {
  let timer;
  try {
    return await Promise.race([promise, new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error("TEST_PROMISE_DID_NOT_SETTLE")), timeoutMs);
    })]);
  } finally { clearTimeout(timer); }
}

test("debugger preserves correlated command results and protocol errors", async () => {
  const { connect, sockets, timers } = harness();
  const connection = await within(connect("ws://synthetic", { commandTimeoutMs: 30 }));
  const first = connection.send("Runtime.evaluate"), second = connection.send("Page.captureScreenshot");
  sockets[0].reply({ id: 2, result: { data: "synthetic" } });
  sockets[0].reply({ id: 1, result: { result: { value: true } } });
  assert.equal((await within(first)).result.value, true);
  assert.equal((await within(second)).data, "synthetic");
  const rejected = connection.send("Synthetic.invalid");
  sockets[0].reply({ id: 3, error: { message: "SYNTHETIC_PROTOCOL_ERROR" } });
  await assert.rejects(within(rejected), /SYNTHETIC_PROTOCOL_ERROR/);
  assert.equal(timers.size, 0);
  connection.socket.close();
});

test("an unanswered command times out while a later command can still complete", async () => {
  const { connect, sockets } = harness();
  const connection = await within(connect("ws://synthetic", { commandTimeoutMs: 20 }));
  await assert.rejects(within(connection.send("Runtime.evaluate")), /DEBUGGER_COMMAND_TIMEOUT/);
  sockets[0].reply({ id: 1, result: { ignoredLateResult: true } });
  const next = connection.send("Page.captureScreenshot");
  sockets[0].reply({ id: 2, result: { data: "current" } });
  assert.equal((await within(next)).data, "current");
  connection.socket.close();
});

test("peer close rejects every pending command and prevents new sends", async () => {
  const { connect, sockets, timers } = harness();
  const connection = await within(connect("ws://synthetic", { commandTimeoutMs: 100 }));
  const rejected = Promise.all([
    assert.rejects(within(connection.send("Runtime.evaluate")), /DEBUGGER_CONNECTION_CLOSED/),
    assert.rejects(within(connection.send("Browser.close")), /DEBUGGER_CONNECTION_CLOSED/),
  ]);
  sockets[0].close(); await rejected;
  await assert.rejects(within(connection.send("Runtime.evaluate")), /DEBUGGER_CONNECTION_CLOSED/);
  assert.equal(sockets[0].commands.length, 2);
  assert.equal(timers.size, 0);
});

test("socket error rejects pending commands instead of leaving cleanup waiting", async () => {
  const { connect, sockets } = harness();
  const connection = await within(connect("ws://synthetic", { commandTimeoutMs: 100 }));
  const rejected = assert.rejects(within(connection.send("Browser.close")), /DEBUGGER_CONNECTION_ERROR/);
  sockets[0].error(); await rejected;
  await assert.rejects(within(connection.send("Runtime.evaluate")), /DEBUGGER_CONNECTION_ERROR/);
  connection.socket.close();
});

test("a connection that never opens is rejected and its socket is closed", async () => {
  const { connect, sockets } = harness({ autoOpen: false });
  await assert.rejects(within(connect("ws://synthetic", { connectionTimeoutMs: 20 })), /DEBUGGER_CONNECTION_TIMEOUT/);
  assert.equal(sockets[0].closeCalls, 1);
});

test("peer close before open rejects the connection immediately", async () => {
  const { connect, sockets } = harness({ autoOpen: false });
  const rejected = assert.rejects(within(connect("ws://synthetic", { connectionTimeoutMs: 100 })), /DEBUGGER_CONNECTION_CLOSED/);
  sockets[0].close(); await rejected;
});

test("Browser.close can use a shorter bound when the peer never acknowledges it", async () => {
  const { connect } = harness();
  const connection = await within(connect("ws://synthetic", { commandTimeoutMs: 5000 }));
  await assert.rejects(within(connection.send("Browser.close", {}, 20)), /DEBUGGER_COMMAND_TIMEOUT/);
  connection.socket.close();
});

test("synchronous send failure rejects its command and clears the pending timer", async () => {
  const { connect, sockets, timers } = harness();
  const connection = await within(connect("ws://synthetic", { commandTimeoutMs: 30 }));
  sockets[0].sendFailure = true;
  await assert.rejects(within(connection.send("Runtime.evaluate")), /SYNTHETIC_SEND_FAILURE/);
  assert.equal(timers.size, 0);
  connection.socket.close();
});
