import assert from 'node:assert/strict';
import test from 'node:test';
import { ProcurementMaterialBatch, type MaterialBatchApi } from './procurementMaterialBatch.ts';
import type { ProcurementMaterialResult } from '../../../api/types';

function memory() {
  const values=new Map<string,string>();
  return {getItem:(key:string)=>values.get(key)??null,setItem:(key:string,value:string)=>{values.set(key,value);},removeItem:(key:string)=>{values.delete(key);}} as Storage;
}
function result(file:File,key:string,status:ProcurementMaterialResult['status']='QUEUED'): ProcurementMaterialResult {
  return {id:key,fileName:file.name,status,fields:null,errorCode:status==='FAILED'?'AI_NO_FIELDS':null,
    parseMethod:null,createdAt:'2026-10-10T00:00:00Z',updatedAt:'2026-10-10T00:00:00Z',leaseUntil:'2026-10-10T00:20:00Z'};
}
async function until(condition:()=>boolean) {
  const limit=Date.now()+2000;
  while(!condition()) {if(Date.now()>limit) throw Error('condition timeout');await new Promise(resolve=>setTimeout(resolve,5));}
}
function fixture(storage=memory(),actor='a') {
  const records=new Map<string,ProcurementMaterialResult>(); const uploads:string[]=[];
  const api:MaterialBatchApi={upload:async(file,key)=>{uploads.push(key);const row=result(file,key);records.set(key,row);return row;},
    operation:async(key)=>records.get(key)??null,get:async(id)=>records.get(id)!,retry:async(id)=>({...records.get(id)!,status:'QUEUED'})};
  const batch=new ProcurementMaterialBatch(actor,storage,api);
  return {batch,api,records,uploads,storage};
}
test('fifteen files continue past invalid files, model failures and lost responses without a modal subscriber',async()=>{
  const f=fixture(); const files=Array.from({length:15},(_,i)=>new File(['pdf'],`${i}.pdf`,{lastModified:i}));
  files[2]=new File([],'empty.pdf',{lastModified:2});
  const unsub=f.batch.subscribe(()=>{}); unsub();
  f.api.upload=async(file,key)=>{
    f.uploads.push(key); const row=result(file,key,file.name==='0.pdf'?'FAILED':'QUEUED');f.records.set(key,row);
    if(file.name==='3.pdf') throw Error('lost response'); return row;
  };
  try {
    f.batch.activate();f.batch.add(files);
    await until(()=>f.uploads.length===14 && !f.batch.snapshot().uploading);
    assert.equal(f.batch.snapshot().entries.length,15);assert.equal(new Set(f.uploads).size,14);
    await f.batch.reconcile();
    assert.equal(f.batch.snapshot().entries[0].phase,'FAILED');assert.equal(f.batch.snapshot().entries[3].phase,'QUEUED');
    assert.ok(f.uploads.includes(f.batch.snapshot().entries[14].operation.key));
  } finally {f.batch.pause();}
});
test('closing UI during the first upload does not prevent remaining files and reload reopens original server IDs',async()=>{
  const f=fixture(); let release:()=>void=()=>{};
  const blocked=new Promise<void>(resolve=>{release=resolve;});
  const first=f.api.upload;
  f.api.upload=async(file,key)=>{if(file.name==='0.pdf') await blocked;return first(file,key);};
  try {
    f.batch.activate();const unsubscribe=f.batch.subscribe(()=>{});
    f.batch.add(Array.from({length:12},(_,i)=>new File(['pdf'],`${i}.pdf`,{lastModified:i})));
    await until(()=>f.batch.snapshot().entries[0]?.phase==='UPLOADING');unsubscribe();release();
    await until(()=>f.uploads.length===12&&!f.batch.snapshot().uploading);
    f.batch.pause();
    for(const [key,row] of f.records) f.records.set(key,{...row,status:'PARSED'});
    const restored=new ProcurementMaterialBatch('a',f.storage,f.api);restored.activate();
    try { await until(()=>restored.snapshot().entries.every(row=>row.phase==='PARSED'));
      assert.deepEqual(restored.snapshot().entries.map(row=>row.result!.id),f.uploads);assert.equal(f.uploads.length,12);
    } finally {restored.pause();}
  } finally {release();f.batch.pause();}
});
test('unknown upload after reload resumes with the same key and unrelated files still upload',async()=>{
  const f=fixture(); const file=new File(['pdf'],'uncertain.pdf',{lastModified:1});
  f.api.upload=async(_file,key)=>{f.uploads.push(key);throw Error('unknown');};
  f.batch.activate();f.batch.add([file]);await until(()=>!f.batch.snapshot().uploading&&f.uploads.length===1);f.batch.pause();
  const key=f.uploads[0];f.api.upload=async(file,key)=>{f.uploads.push(key);return result(file,key);};
  const restored=new ProcurementMaterialBatch('a',f.storage,f.api);
  try {restored.activate();restored.add([file,new File(['pdf'],'another.pdf',{lastModified:2})]);
    await until(()=>f.uploads.length===3&&!restored.snapshot().uploading);
    assert.equal(f.uploads[1],key);assert.notEqual(f.uploads[2],key);
  } finally {restored.pause();}
});
test('corrupt operation records and storage failure fail closed before creating another server operation',async()=>{
  const storage=memory();storage.setItem('procurement-material-operation:a','not json');
  const f=fixture(storage);f.batch.activate();f.batch.add([new File(['pdf'],'a.pdf')]);
  assert.ok(f.batch.snapshot().error);assert.equal(f.uploads.length,0);f.batch.pause();
  const broken=memory();const g=fixture(broken);broken.setItem=()=>{throw Error('quota');};
  g.batch.activate();g.batch.add([new File(['pdf'],'b.pdf')]);
  assert.ok(g.batch.snapshot().error);assert.equal(g.uploads.length,0);g.batch.pause();
});
test('another account cannot inherit batch identities or continue earlier browser uploads',async()=>{
  const f=fixture();f.batch.activate();f.batch.add([new File(['pdf'],'a.pdf')]);
  await until(()=>f.uploads.length===1&&!f.batch.snapshot().uploading);f.batch.pause();
  const other=new ProcurementMaterialBatch('b',f.storage,f.api);assert.equal(other.snapshot().entries.length,0);other.pause();
});

test('browser persistence excludes extraction fields and evidence and reload fetches the authenticated result',async()=>{
  const f=fixture(); const file=new File(['pdf'],'private.pdf',{lastModified:9});
  f.api.upload=async(file,key)=>{
    const row={...result(file,key,'PARSED'),fields:{projectName:'PRIVATE-EXTRACTED-TEXT',
      evidence:{projectName:[{snippet:'PRIVATE-SOURCE-EVIDENCE'}]}}} as unknown as ProcurementMaterialResult;
    f.records.set(key,row);f.uploads.push(key);return row;
  };
  try {
    f.batch.activate();f.batch.add([file]);await until(()=>f.uploads.length===1&&!f.batch.snapshot().uploading);
    assert.equal(f.batch.snapshot().entries[0].result?.fields?.projectName,'PRIVATE-EXTRACTED-TEXT');
    const raw=f.storage.getItem('procurement-material-batch:a')!;
    assert.doesNotMatch(raw,/PRIVATE-EXTRACTED-TEXT|PRIVATE-SOURCE-EVIDENCE|fields|evidence/);
    f.batch.pause();
    // Upgrade a legacy cache containing full results without trusting or displaying them.
    f.storage.setItem('procurement-material-batch:a',JSON.stringify(f.batch.snapshot().entries));
    const restored=new ProcurementMaterialBatch('a',f.storage,f.api);
    assert.equal(restored.snapshot().entries[0].result,null);
    assert.equal(restored.snapshot().entries[0].phase,'UNKNOWN');
    assert.doesNotMatch(f.storage.getItem('procurement-material-batch:a')!,/PRIVATE-EXTRACTED-TEXT|PRIVATE-SOURCE-EVIDENCE/);
    restored.activate();
    try {await until(()=>restored.snapshot().entries[0].phase==='PARSED');
      assert.equal(restored.snapshot().entries[0].result?.fields?.projectName,'PRIVATE-EXTRACTED-TEXT');
      assert.equal(f.uploads.length,1);
    } finally {restored.pause();}
  } finally {f.batch.pause();}
});

test('interrupted snapshot write reopens every canonical identity instead of silently dropping the new operation',async()=>{
  const f=fixture();f.batch.activate();f.batch.add([new File(['pdf'],'first.pdf',{lastModified:1})]);
  await until(()=>f.uploads.length===1&&!f.batch.snapshot().uploading);f.batch.pause();
  const write=f.storage.setItem.bind(f.storage);
  f.storage.setItem=(key,value)=>{if(key.startsWith('procurement-material-batch:')) throw Error('quota');write(key,value);};
  f.batch.add([new File(['pdf'],'second.pdf',{lastModified:2})]);
  const identities=JSON.parse(f.storage.getItem('procurement-material-operation:a')!);
  const restored=new ProcurementMaterialBatch('a',f.storage,f.api);
  assert.deepEqual(restored.snapshot().entries.map(row=>row.operation.key),identities.map((row:{key:string})=>row.key));
  assert.equal(restored.snapshot().entries[1].phase,'UNKNOWN');assert.equal(f.uploads.length,1);
  restored.pause();
});

test('a batch snapshot cannot change canonical file metadata',()=>{
  const f=fixture();f.batch.add([new File(['pdf'],'first.pdf',{lastModified:1})]);
  const rows=JSON.parse(f.storage.getItem('procurement-material-batch:a')!);rows[0].operation.name='changed.pdf';
  f.storage.setItem('procurement-material-batch:a',JSON.stringify(rows));
  assert.ok(new ProcurementMaterialBatch('a',f.storage,f.api).snapshot().error);
});

test('a file originally selected from a folder resumes its unknown upload with the original key through the file picker',async()=>{
  const f=fixture();const file=new File(['pdf'],'same.pdf',{lastModified:7});
  Object.defineProperty(file,'webkitRelativePath',{value:'folder/same.pdf'});
  f.api.upload=async(_file,key)=>{f.uploads.push(key);throw Error('lost');};
  f.batch.activate();f.batch.add([file]);await until(()=>f.uploads.length===1&&!f.batch.snapshot().uploading);f.batch.pause();
  const original=f.uploads[0];const restored=new ProcurementMaterialBatch('a',f.storage,f.api);
  f.api.upload=async(file,key)=>{f.uploads.push(key);return result(file,key);};
  try{restored.activate();restored.add([new File(['pdf'],'same.pdf',{lastModified:7})]);
    await until(()=>f.uploads.length===2&&!restored.snapshot().uploading);
    assert.equal(f.uploads[1],original);assert.equal(restored.snapshot().entries.length,1);
  }finally{restored.pause();}
});

test('clear during an upload stops unsent old files, ignores late results and lets a new batch use new identities',async()=>{
  const f=fixture();let release:()=>void=()=>{};
  const pending=new Promise<void>(resolve=>{release=resolve;});
  f.api.upload=async(file,key)=>{
    f.uploads.push(key);
    if(file.name==='old.pdf') await pending;
    const row=result(file,key,'PARSED');f.records.set(key,row);return row;
  };
  try {
    f.batch.activate();f.batch.add([new File(['old'],'old.pdf',{lastModified:1}),new File(['unsent'],'unsent.pdf',{lastModified:2})]);
    await until(()=>f.uploads.length===1);
    const oldKey=f.uploads[0];assert.equal(f.batch.clear(),true);
    assert.deepEqual(f.batch.snapshot(),{entries:[],uploading:false,error:null});
    assert.match(f.storage.getItem('procurement-material-previous:a')!,new RegExp(oldKey));
    assert.equal(new ProcurementMaterialBatch('a',f.storage,f.api).snapshot().entries.length,0);
    f.batch.add([new File(['new'],'new.pdf',{lastModified:3})]);release();
    await until(()=>f.uploads.length===2&&!f.batch.snapshot().uploading);
    assert.notEqual(f.uploads[1],oldKey);
    assert.equal(f.batch.snapshot().entries.length,1);
    assert.equal(f.batch.snapshot().entries[0].operation.name,'new.pdf');
    assert.equal(f.batch.snapshot().entries[0].phase,'PARSED');
    assert.equal(f.records.get(oldKey)?.status,'PARSED');
  }finally {release();f.batch.pause();}
});

test('a poll started before clearing cannot refill the new demand or persist its old result',async()=>{
  const f=fixture();f.batch.activate();f.batch.add([new File(['old'],'old.pdf',{lastModified:1})]);
  await until(()=>f.uploads.length===1&&!f.batch.snapshot().uploading);
  let release:(row:ProcurementMaterialResult)=>void=()=>{};let started=false;
  f.api.get=async()=>{started=true;return await new Promise<ProcurementMaterialResult>(resolve=>{release=resolve;});};
  const polling=f.batch.reconcile();await until(()=>started);
  const old=f.batch.snapshot().entries[0].result!;
  assert.equal(f.batch.clear(),true);release({...old,status:'PARSED'});await polling;
  assert.equal(f.batch.snapshot().entries.length,0);
  assert.equal(new ProcurementMaterialBatch('a',f.storage,f.api).snapshot().entries.length,0);
  f.batch.pause();
});

test('clear fails closed if the archived operation identity cannot be saved',async()=>{
  const f=fixture();f.batch.add([new File(['old'],'old.pdf',{lastModified:1})]);
  const key=f.batch.snapshot().entries[0].operation.key;
  const write=f.storage.setItem.bind(f.storage);
  f.storage.setItem=(name,value)=>{if(name.startsWith('procurement-material-previous:')) throw Error('quota');write(name,value);};
  assert.equal(f.batch.clear(),false);assert.ok(f.batch.snapshot().error);
  assert.equal(f.batch.snapshot().entries[0].operation.key,key);
  assert.equal(new ProcurementMaterialBatch('a',f.storage,f.api).snapshot().entries[0].operation.key,key);
});
