import test from 'node:test';
import assert from 'node:assert/strict';
import { collectProcurementDrop, droppedFilePath, type DroppedEntry } from './procurementFolderDrop.ts';

const file = (name: string): DroppedEntry => ({name,isFile:true,isDirectory:false,
  file: ok => ok(new File(['content'],name))});
const folder = (name: string, batches: DroppedEntry[][]): DroppedEntry => ({name,isFile:false,isDirectory:true,
  createReader: () => ({readEntries: ok => ok(batches.shift() ?? [])})});

test('reads all batches and nested directory entries, not only the first 100 files', async () => {
  const inside = folder('nested', [[file('last.pdf')]]);
  const root = folder('dir', [Array.from({length:100},(_,i) => file(`${i}.pdf`)), [inside], []]);
  const result = await collectProcurementDrop([root],[]);
  assert.equal(result.files.length,101);
  assert.equal(result.files.at(-1)?.name,'last.pdf');
  assert.deepEqual(result.problems,[]);
});

test('broken file does not discard siblings and unsupported drag falls back to FileList', async () => {
  const broken: DroppedEntry = {name:'bad',isDirectory:false,isFile:true,file:(_ok,fail)=>fail(new Error('unreadable'))};
  const result=await collectProcurementDrop([folder('dir',[[broken,file('good.pdf')]])],[]);
  assert.deepEqual(result.files.map(f=>f.name),['good.pdf']);
  assert.equal(result.problems.length,1);
  assert.deepEqual((await collectProcurementDrop([null],[new File(['a'],'fallback.pdf')])).files.map(f=>f.name),['fallback.pdf']);
});

test('same-name files in different dragged directories keep distinct identities', async () => {
  const first = {...file('same.pdf'), fullPath: '/one/same.pdf'};
  const second = {...file('same.pdf'), fullPath: '/two/same.pdf'};
  const result = await collectProcurementDrop([folder('root', [[first, second]])], []);
  assert.deepEqual(result.files.map(droppedFilePath), ['/one/same.pdf', '/two/same.pdf']);
});
