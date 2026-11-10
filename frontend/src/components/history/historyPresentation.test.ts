import test from 'node:test';
import assert from 'node:assert/strict';
import {historyStatus} from './historyPresentation.ts';
test('review coverage and findings do not change completed processing labels',()=>{
  for(const status of ['COMPLETED','REVIEWED','PARTIAL','DATA_INSUFFICIENT','WAITING_CONFIRMATION'])assert.deepEqual(historyStatus(status),{label:'已完成',tone:'complete'});
});
test('running jobs have the same label across module state vocabularies',()=>{
  for(const status of ['UPLOADING','QUEUED','PROCESSING','PARSED','RUNNING','ANALYZING'])assert.deepEqual(historyStatus(status),{label:'正在分析',tone:'processing'});
});
test('execution failures and unknown states never claim completed analysis',()=>{
  for(const status of ['FAILED','UNKNOWN'])assert.deepEqual(historyStatus(status),{label:'已结束',tone:'neutral'});
  assert.deepEqual(historyStatus('NEW_UNRECOGNIZED_STATE'),{label:'状态待核实',tone:'neutral'});
});
