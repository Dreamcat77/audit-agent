import {test} from 'node:test';
import assert from 'node:assert/strict';
import {webcrypto} from 'node:crypto';
import {installPilotUuid} from './pilot-uuid.mjs';
test('HTTP fallback uses CSPRNG, sets version/variant and yields distinct operation IDs', () => {
  const crypto = {getRandomValues: bytes => webcrypto.getRandomValues(bytes)};
  installPilotUuid(crypto);
  const ids = Array.from({length:128}, () => crypto.randomUUID());
  assert.equal(new Set(ids).size,128);
  for (const id of ids) assert.match(id,/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
});
test('native implementation is preserved; missing secure randomness fails closed', () => {
  const native = () => 'native'; const crypto = {randomUUID:native};
  installPilotUuid(crypto); assert.equal(crypto.randomUUID,native);
  const unavailable={getRandomValues:()=>{throw Error('unavailable')}};
  installPilotUuid(unavailable);assert.throws(()=>unavailable.randomUUID(),/unavailable/);
});
