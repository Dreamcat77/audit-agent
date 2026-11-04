import test from 'node:test';
import assert from 'node:assert/strict';
import {MaterialUploadBatch,type MaterialBatchApi,type MaterialUploadResult} from '../procurement/materials/procurementMaterialBatch.ts';
import type {MaterialOperation} from '../procurement/materials/procurementMaterialOperation.ts';
function memory() {const map=new Map<string,string>();return {getItem:(k:string)=>map.get(k)??null,setItem:(k:string,v:string)=>{map.set(k,v);},removeItem:(k:string)=>{map.delete(k);}} as Storage;}
async function until(f:()=>boolean) {const end=Date.now()+3000;while(!f()){assert.ok(Date.now()<end);await new Promise(r=>setTimeout(r,5));}}
const options={namespace:'budget',filePattern:/\.(docx?|xlsx?|pdf|pptx?|txt|csv|rtf|png|jpe?g|bmp|tiff?)$/i};
const context={budgetYear:'2026',departmentCode:'PROCUREMENT'};
function queued(id:string):MaterialUploadResult {return {id,status:'QUEUED',updatedAt:'2026-10-10T00:00:00Z',errorCode:null};}
test('budget uses separate identities and preserves upload year/department across reload and lost response',async()=>{
  const storage=memory(),records=new Map<string,MaterialUploadResult>(),sent:MaterialOperation[]=[];
  const api:MaterialBatchApi<MaterialUploadResult>={upload:async(_file,key,op)=>{sent.push(op!);records.set(key,queued(key));throw Error('lost response');},operation:async key=>records.get(key)??null,get:async id=>records.get(id)!,retry:async id=>queued(id)};
  storage.setItem('procurement-material-operation:owner','private procurement identities');
  const first=new MaterialUploadBatch('owner',storage,api,null,options);first.activate();
  first.add([new File(['data'],'budget.xlsx')],context);context.budgetYear='2025';
  try {
    await until(()=>sent.length===1&&!first.snapshot().uploading);first.pause();
    assert.equal(sent[0].context?.budgetYear,'2026');
    const reopened=new MaterialUploadBatch('owner',storage,api,null,options);reopened.activate();
    try {await until(()=>reopened.snapshot().entries[0]?.phase==='QUEUED');assert.equal(reopened.snapshot().entries[0].operation.key,sent[0].key);assert.equal(sent.length,1);}
    finally {reopened.pause();}
    assert.equal(storage.getItem('procurement-material-operation:owner'),'private procurement identities');
  } finally {first.pause();context.budgetYear='2026';}
});
test('budget batch accepts twelve files over the former 10 MB limit and isolates invalid files',async()=>{
  const storage=memory(),sent:string[]=[];
  const api:MaterialBatchApi<MaterialUploadResult>={upload:async(file,key)=>{sent.push(file.name);return {...queued(key),status:'PARSED'};},operation:async()=>null,get:async id=>queued(id),retry:async id=>queued(id)};
  const batch=new MaterialUploadBatch('budget-owner',storage,api,null,options);batch.activate();
  try {
    batch.add([new File(['bad'],'bad.zip'),...Array.from({length:12},(_,i)=>new File([i===11?new Uint8Array(11*1024*1024):'data'],`budget-${i}.xlsx`))],context);
    await until(()=>batch.snapshot().entries.filter(e=>e.phase==='PARSED').length===12);
    assert.equal(batch.snapshot().entries.filter(e=>e.phase==='FAILED').length,1);assert.equal(sent.length,12);
  } finally {batch.pause();}
});
