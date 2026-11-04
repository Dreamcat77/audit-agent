import assert from 'node:assert/strict';
import {createHash,randomBytes} from 'node:crypto';
import {test} from 'node:test';
import {MaterialSha256} from './materialSha256.ts';
test('HTTP-compatible SHA-256 matches standard vectors and streamed block boundaries',()=>{
  for(const size of [0,1,55,56,63,64,65,127,128,129,1024,1024*1024+13]) {
    const bytes=randomBytes(size),expected=createHash('sha256').update(bytes).digest('hex');
    assert.equal(new MaterialSha256().update(bytes).digest(),expected);
    const streamed=new MaterialSha256();for(let i=0;i<bytes.length;i+=37)streamed.update(bytes.subarray(i,i+37));assert.equal(streamed.digest(),expected);
  }
  assert.equal(new MaterialSha256().update(new TextEncoder().encode('abc')).digest(),'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
});
