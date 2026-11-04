import assert from 'node:assert/strict';
import test from 'node:test';
import { ProcurementIntakeReviewOperation } from './procurementIntakeReviewOperation.ts';
import type { ProcurementMaterialBatchResponse } from '../../../api/types';

const result: ProcurementMaterialBatchResponse = { files: [], quote: null, collusion: null, blacklist: null, report: null, notices: [] };
test('double click and reentrant subscriptions share one in-flight request', async () => {
  let resolve!: (value: ProcurementMaterialBatchResponse) => void, calls = 0;
  const operation = new ProcurementIntakeReviewOperation(() => { calls++; return new Promise(r => { resolve = r; }); }, () => 'error');
  let nested: Promise<void> | undefined;
  const unsubscribe = operation.subscribe(() => { if (operation.snapshot().pending) nested = operation.start(); });
  assert.equal(calls, 0, 'subscription must never start a model call');
  const running = operation.start(); assert.equal(running, nested); assert.equal(operation.start(), running);
  await Promise.resolve(); assert.equal(calls, 1); operation.reset(); assert.equal(operation.snapshot().pending, true);
  unsubscribe(); resolve(result); await running;
  assert.equal(operation.snapshot().result, result, 'unmount does not cancel the server command');
  assert.equal(operation.snapshot().pending, false);
});
test('failure stays saved until an explicit retry; project operations are isolated', async () => {
  let calls = 0;
  const first = new ProcurementIntakeReviewOperation(async () => { if (++calls === 1) throw Error('connection lost'); return result; }, () => 'saved; outcome unconfirmed');
  const second = new ProcurementIntakeReviewOperation(async () => result, () => 'other error');
  await first.start(); assert.equal(first.snapshot().error, 'saved; outcome unconfirmed');
  const unsubscribe = first.subscribe(() => {}); unsubscribe(); assert.equal(calls, 1);
  assert.deepEqual(second.snapshot(), { pending: false, result: null, error: null });
  await first.start(); assert.equal(calls, 2); assert.equal(first.snapshot().result, result);
  first.reset(); assert.deepEqual(first.snapshot(), { pending: false, result: null, error: null });
});
