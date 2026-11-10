import {test} from 'node:test';
import assert from 'node:assert/strict';
import {evidencePages} from './evidencePages.ts';
test('nonadjacent evidence pages never expand into intervening pages or use a stale hint',()=>{
  assert.deepEqual(evidencePages([{anchors:[{pageNumber:9},{pageNumber:13},{pageNumber:9}],pages:[9,13]}],[1]),[9,13]);
});
test('known page survives absent coordinates; no locator never guesses page one',()=>{
  assert.deepEqual(evidencePages([{anchors:[],pages:[6]}],[1]),[6]);
  assert.deepEqual(evidencePages([],[]),[]);assert.deepEqual(evidencePages([],[NaN,-1,0,1.5,2048,6]),[6]);
});
