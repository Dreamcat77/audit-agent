import assert from 'node:assert/strict';
import test from 'node:test';
import {mapConcurrent} from './mapConcurrent.ts';
test('large batches preserve ordering with a fixed number of active reads', async () => {
  let active=0,maximum=0;
  const values=Array.from({length:67},(_,i)=>i);
  const result=await mapConcurrent(values,4,async value=>{
    maximum=Math.max(maximum,++active);
    await new Promise(resolve=>setTimeout(resolve,value%3));active--;return value*2;
  });
  assert.deepEqual(result,values.map(i=>i*2));assert.equal(maximum,4);
  assert.deepEqual(await mapConcurrent([],4,async value=>value),[]);
});
