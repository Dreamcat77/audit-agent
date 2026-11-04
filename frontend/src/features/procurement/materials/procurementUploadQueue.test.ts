import assert from 'node:assert/strict';
import test from 'node:test';
import { uploadFilesIndependently, procurementFileProblem, PROCUREMENT_MAX_UPLOAD_BYTES, otherPendingFiles } from './procurementUploadQueue.ts';
import { readMaterialOperation, readMaterialOperations, writeMaterialOperations, materialOperationStorageKey } from './procurementMaterialOperation.ts';

test('more than ten files and a failed middle request retain every independent success', async () => {
  const files = Array.from({length:15}, (_,i) => new File(['pdf'], `${i}.pdf`));
  const attempted: string[] = [], saved: string[] = [];
  const failures = await uploadFilesIndependently(files, async file => {
    attempted.push(file.name);
    if(file.name==='3.pdf') throw new Error('unknown response');
    return file.name;
  }, (_file,result) => saved.push(result));
  assert.equal(attempted.length,15);
  assert.equal(saved.length,14);
  assert.deepEqual(failures.map(item=>item.file.name),['3.pdf']);
  assert.equal(saved.at(-1),'14.pdf');
});
test('invalid individual size does not reject the rest of the selection', async () => {
  const files = [{name:'empty.pdf',size:0},{name:'large.pdf',size:21*1024*1024},{name:'huge.pdf',size:PROCUREMENT_MAX_UPLOAD_BYTES+1},{name:'good.pdf',size:10}] as File[];
  const saved: string[]=[];
  const failures = await uploadFilesIndependently(files, async file => {
    const problem=procurementFileProblem(file,/\.pdf$/i); if(problem) throw new Error(problem);
    return file.name;
  }, (_file,name)=>saved.push(name));
  assert.deepEqual(saved,['large.pdf','good.pdf']);
  assert.deepEqual(failures.map(item=>item.file.name),['empty.pdf','huge.pdf']);
});
test('cancelled project generation stops later files and ignores late results', async () => {
  let current=true;
  const saved: string[]=[];
  await uploadFilesIndependently([new File(['a'],'a.pdf'),new File(['b'],'b.pdf')], async file=>{current=false;return file.name;},(_file,name)=>saved.push(name),()=>current);
  assert.deepEqual(saved,[]);
});
test('a new selection retains earlier uncertain files until the same file is reconciled', () => {
  const unknown = new File(['a'], 'a.pdf', {lastModified:1});
  const another = new File(['b'], 'b.pdf', {lastModified:2});
  assert.deepEqual(otherPendingFiles([unknown], [another]), [unknown]);
  assert.deepEqual(otherPendingFiles([unknown], [new File(['a'], 'a.pdf', {lastModified:1})]), []);
  assert.deepEqual(otherPendingFiles([unknown], [new File(['a'], 'a.pdf', {lastModified:3})]), [unknown]);
});
test('uncertain per-file identities survive reload, unrelated uploads and individual completion', () => {
  const data=new Map<string,string>();
  const storage={getItem:(key:string)=>data.get(key)??null,setItem:(key:string,value:string)=>{data.set(key,value);},removeItem:(key:string)=>{data.delete(key);}};
  const a={key:'11111111-1111-1111-1111-111111111111',name:'a.pdf',size:23,lastModified:1};
  const b={key:'22222222-2222-2222-2222-222222222222',name:'b.pdf',size:24,lastModified:2};
  // Old single-operation records remain recoverable.
  storage.setItem(materialOperationStorageKey('actor'),JSON.stringify(a));
  assert.deepEqual(readMaterialOperation(storage,'actor'),a);
  writeMaterialOperations(storage,'actor',[...readMaterialOperations(storage,'actor'),b]);
  assert.deepEqual(readMaterialOperations(storage,'actor'),[a,b]);
  writeMaterialOperations(storage,'actor',readMaterialOperations(storage,'actor').filter(item=>item.key!==b.key));
  assert.deepEqual(readMaterialOperation(storage,'actor'),a);
  assert.deepEqual(readMaterialOperations(storage,'other'),[]);
  storage.setItem(materialOperationStorageKey('actor'),JSON.stringify([a,a]));
  assert.throws(()=>readMaterialOperations(storage,'actor'));
});
