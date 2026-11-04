import {test} from 'node:test';
import assert from 'node:assert/strict';
import {OFFICE_MATERIAL_ACCEPT, OFFICE_MATERIAL_PATTERN} from './officeMaterialTypes.ts';
import {procurementFileProblem} from './procurementUploadQueue.ts';
test('both entries accept every advertised office format and retain safe upload bounds',()=>{
  for(const ext of OFFICE_MATERIAL_ACCEPT.split(','))for(const size of [10,25*1024*1024])assert.equal(procurementFileProblem({name:`材料${ext.toUpperCase()}`,size},OFFICE_MATERIAL_PATTERN),null);
  for(const ext of ['zip','rar','7z','exe'])assert.equal(procurementFileProblem({name:`材料.${ext}`,size:10},OFFICE_MATERIAL_PATTERN),'文件格式不支持');
  assert.equal(procurementFileProblem({name:'材料.pdf',size:501*1024*1024},OFFICE_MATERIAL_PATTERN),'文件超过 500 MB');
});
