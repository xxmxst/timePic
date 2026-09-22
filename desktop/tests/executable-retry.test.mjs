import { test } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { retryExecutableOpen } from '../scripts/executable-retry.mjs';
const root = path.resolve('release');
const target = path.join(root, 'win-unpacked', 'TimePic.exe');
const locked = () => Object.assign(new Error('busy'), { code: 'UNKNOWN', syscall: 'open', path: target });

test('temporary executable opens retry with bounded backoff and eventually return', async () => {
  let calls = 0; const waits = [];
  const value = await retryExecutableOpen(() => { if (++calls < 4) throw locked(); return 'ok'; }, target, root, async ms => { waits.push(ms); });
  assert.equal(value, 'ok'); assert.deepEqual(waits, [1000, 2000, 4000]); assert.equal(calls, 4);
});
test('permanent open failure stops after four retries', async () => {
  let calls = 0; const waits = [];
  await assert.rejects(retryExecutableOpen(() => { calls++; throw locked(); }, target, root, async ms => { waits.push(ms); }), /busy/);
  assert.equal(calls, 5); assert.deepEqual(waits, [1000, 2000, 4000, 8000]);
});
test('never retries partial writes, unrelated errors or paths outside build output', async () => {
  for (const error of [Object.assign(locked(), { syscall: 'write' }), Object.assign(locked(), { code: 'ENOSPC' }), Object.assign(locked(), { path: path.resolve('input.exe') }), new Error('invalid PE')]) {
    let calls = 0;
    await assert.rejects(retryExecutableOpen(() => { calls++; throw error; }, target, root, async () => assert.fail('unexpected retry')));
    assert.equal(calls, 1);
  }
  await assert.rejects(retryExecutableOpen(() => { throw locked(); }, target, path.join(root, 'other'), async () => assert.fail('unexpected retry')));
});
