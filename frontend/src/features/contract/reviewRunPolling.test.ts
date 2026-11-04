import assert from "node:assert/strict";
import test from "node:test";
import { startReviewRunPolling } from "./reviewRunPolling.ts";

const running = { status: "RUNNING", steps: [{ status: "RUNNING" }] };
const flush = async () => { await Promise.resolve(); await Promise.resolve(); await Promise.resolve(); };
function fixture(load: (signal: AbortSignal) => Promise<typeof running>, maxPolls?: number) {
  let visible = true;
  let listener = () => {};
  const timers = new Map<number, { callback: () => void; delay: number }>();
  const values: typeof running[] = [];
  const errors: unknown[] = [];
  let next = 0;
  const poller = startReviewRunPolling({ load, onValue: v => values.push(v), onError: e => errors.push(e),
    visible: () => visible, subscribeVisibility: callback => { listener = callback; return () => { listener = () => {}; }; },
    schedule: (callback, delay) => { const id = ++next; timers.set(id, { callback, delay }); return () => { timers.delete(id); }; }, maxPolls });
  return { poller, values, errors, timers,
    visibility(value: boolean) { visible = value; listener(); },
    tick() { const [id, timer] = timers.entries().next().value!; timers.delete(id); timer.callback(); return timer.delay; } };
}

test("GET polling caps backoff and total requests; manual reload reopens without commands", async () => {
  let gets = 0;
  const f = fixture(async () => { gets++; return running; }, 7);
  await flush();
  const delays = [];
  while (f.timers.size) { delays.push(f.tick()); await flush(); }
  assert.deepEqual(delays, [1000, 2000, 4000, 8000, 15000, 15000]);
  assert.equal(gets, 7);
  f.poller.reload(); await flush(); assert.equal(gets, 8);
  f.poller.dispose(); assert.equal(f.timers.size, 0);
});

test("the default GET observer remains active through queue and lease recovery until terminal", async () => {
  let gets = 0;
  const completed = { status: "COMPLETED", steps: [{ status: "COMPLETED" }] };
  const f = fixture(async () => (++gets > 120 ? completed : running));
  await flush();
  while (gets <= 120) {
    assert.equal(f.timers.size, 1);
    f.tick(); await flush();
  }
  assert.equal(gets, 121);
  assert.equal(f.timers.size, 0);
  assert.equal(f.values.at(-1)?.status, "COMPLETED");
  f.poller.dispose();
});

test("terminal states stop but UNKNOWN with a running sibling remains observable", async () => {
  for (const status of ["COMPLETED", "PARTIALLY_COMPLETED", "FAILED", "UNKNOWN"]) {
    const f = fixture(async () => ({ status, steps: [{ status }] }));
    await flush(); assert.equal(f.timers.size, 0); f.poller.dispose();
  }
  const f = fixture(async () => ({ status: "UNKNOWN", steps: [{ status: "RUNNING" }, { status: "UNKNOWN" }] }));
  await flush(); assert.equal(f.timers.size, 1); f.poller.dispose();
});

test("network failure preserves last server state and only retries GET", async () => {
  let gets = 0;
  const f = fixture(async () => { if (++gets === 1) return running; throw new Error("transport uncertainty"); }, 3);
  await flush(); f.tick(); await flush();
  assert.deepEqual(f.values, [running]); assert.equal(f.errors.length, 1);
  f.tick(); await flush(); assert.equal(f.timers.size, 0); assert.equal(gets, 3);
});

test("hidden, reload and dispose abort requests and discard late responses", async () => {
  const pending: Array<{ signal: AbortSignal; resolve: (value: typeof running) => void }> = [];
  const f = fixture(signal => new Promise(resolve => pending.push({ signal, resolve })));
  f.visibility(false); assert.equal(pending[0].signal.aborted, true);
  pending[0].resolve(running); await flush(); assert.equal(f.values.length, 0);
  f.visibility(true); assert.equal(pending.length, 2);
  f.poller.reload(); assert.equal(pending[1].signal.aborted, true); assert.equal(pending.length, 3);
  pending[1].resolve(running); await flush(); assert.equal(f.values.length, 0);
  f.poller.dispose(); assert.equal(pending[2].signal.aborted, true);
  pending[2].resolve(running); await flush(); assert.equal(f.values.length, 0);
  f.visibility(true); assert.equal(pending.length, 3);
});
