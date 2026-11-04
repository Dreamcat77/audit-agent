import { useEffect,useSyncExternalStore } from 'react';
import { request } from '../../api/client';
import type { BudgetReviewTaskResponse } from '../../api/types';
import { MaterialUploadBatch, type MaterialUploadResult } from '../procurement/materials/procurementMaterialBatch';
import type { MaterialOperation } from '../procurement/materials/procurementMaterialOperation';
import { OFFICE_MATERIAL_PATTERN } from '../procurement/materials/officeMaterialTypes';
import { RESUMABLE_THRESHOLD,uploadMaterialResumable } from '../procurement/materials/resumableMaterialUpload';

export interface BudgetMaterialResult extends MaterialUploadResult {fileName:string;task:BudgetReviewTaskResponse}
const batches=new Map<string,MaterialUploadBatch<BudgetMaterialResult>>();
const prefix='/api/v1/budget/material-uploads';
export function budgetMaterialBatch(actor:string) {
  let batch=batches.get(actor);
  if(!batch) {
    let storage:Storage,initialError:string|null=null;
    try {storage=window.localStorage;}
    catch {storage={getItem:()=>null,setItem:()=>{throw Error('storage unavailable');},removeItem:()=>{}} as unknown as Storage;initialError='浏览器无法保存上传进度，请启用本地存储后再上传';}
    batch=new MaterialUploadBatch<BudgetMaterialResult>(actor,storage,{
      prepare:async operations=>{
        const groups=new Map<string,MaterialOperation[]>();
        for(const op of operations)if(op.context?.batchId && op.size>0 && op.size<=500*1024*1024 && OFFICE_MATERIAL_PATTERN.test(op.name)) {
          const members=groups.get(op.context.batchId)??[];members.push(op);groups.set(op.context.batchId,members);
        }
        for(const [batchId,members] of groups) await request(`${prefix}/batches`,{method:'POST',body:{
          batchId,budgetYear:members[0].context!.budgetYear,departmentCode:members[0].context!.departmentCode,
          members:members.map(op=>({operationKey:op.key,fileName:op.name,fileSize:op.size})),
        }});
      },
      upload:async(file:File,key:string,operation?:MaterialOperation)=>{
        if(!operation?.context) throw Error('missing budget upload context');
        if(file.size>=RESUMABLE_THRESHOLD)return uploadMaterialResumable<BudgetMaterialResult>('budget',file,key,operation.context);
        const body=new FormData();body.append('file',file);body.append('budgetYear',operation.context.budgetYear);body.append('departmentCode',operation.context.departmentCode);
        if(operation.context.batchId)body.append('batchId',operation.context.batchId);
        return request<BudgetMaterialResult>(prefix,{method:'POST',body,headers:{'Idempotency-Key':key}});
      },
      operation:key=>request<BudgetMaterialResult|null>(`${prefix}/operations/${encodeURIComponent(key)}`),
      get:id=>request<BudgetMaterialResult>(`${prefix}/${encodeURIComponent(id)}`),
      retry:()=>Promise.reject(Error('manual retry not exposed')),
    },initialError,{namespace:'budget',filePattern:OFFICE_MATERIAL_PATTERN});
    batches.set(actor,batch);
  }
  return batch;
}
export function useBudgetMaterialBatch(actor:string) {
  const batch=budgetMaterialBatch(actor);return {batch,snapshot:useSyncExternalStore(batch.subscribe,batch.snapshot,batch.snapshot)};
}
export function BudgetMaterialBackground({actorId}:{actorId:string}) {
  useEffect(()=>{const batch=budgetMaterialBatch(actorId);batch.activate();return()=>batch.pause();},[actorId]);return null;
}
