import { useEffect, useSyncExternalStore } from 'react';
import { ProcurementMaterialBatch } from './procurementMaterialBatch';
import { uploadProcurementMaterial, getProcurementMaterialOperation, getProcurementMaterial, retryProcurementMaterial } from './procurementMaterialApi';
import { materialOperationStorageKey, readMaterialOperations, writeMaterialOperations } from './procurementMaterialOperation';

const batches=new Map<string,ProcurementMaterialBatch>();
export function materialBatch(actor: string) {
  let batch=batches.get(actor);
  if(!batch) {
    let initialError: string|null=null;
    let storage: Storage;
    try { storage=window.localStorage; }
    catch {
      storage={getItem:()=>null,setItem:()=>{throw Error('local storage unavailable');},removeItem:()=>{}} as unknown as Storage;
      initialError='浏览器无法保存上传进度，请启用本地存储后再上传';
    }
    try {
      if(!initialError && storage.getItem(materialOperationStorageKey(actor))===null) {
        const legacy=readMaterialOperations(window.sessionStorage,actor);
        if(legacy.length) writeMaterialOperations(storage,actor,legacy);
      }
    } catch { initialError='上传记录无法可靠读取，请保留原记录并联系管理员核对'; }
    batch=new ProcurementMaterialBatch(actor,storage,{upload:uploadProcurementMaterial,
      operation:getProcurementMaterialOperation,get:getProcurementMaterial,retry:retryProcurementMaterial},initialError);
    batches.set(actor,batch);
  }
  return batch;
}
export function useMaterialBatch(actor: string) {
  const batch=materialBatch(actor);
  const snapshot=useSyncExternalStore(batch.subscribe,batch.snapshot,batch.snapshot);
  return {batch,snapshot};
}

/** Kept at the authenticated app root, outside module and dialog lifetimes. */
export function ProcurementMaterialBackground({actorId}: {actorId:string}) {
  useEffect(()=>{ const batch=materialBatch(actorId); batch.activate(); return ()=>batch.pause(); },[actorId]);
  return null;
}
