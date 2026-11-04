import { request,ApiError } from '../../../api/client';
import { createOperationKey } from '../../../api/operationKey.ts';
import { MaterialSha256 } from './materialSha256.ts';

export const MATERIAL_CHUNK_BYTES=4*1024*1024;
export const RESUMABLE_THRESHOLD=8*1024*1024;
type Context={projectId?:string;budgetYear?:string;departmentCode?:string;batchId?:string};
type Progress={operation:string;status:string;chunkBytes:number;received:number[];receivedBytes:number};
export async function materialFileIdentity(file:Blob,onRead?:(bytes:number)=>void) {
  const full=new MaterialSha256(),partHashes:string[]=[];
  for(let offset=0;offset<file.size;offset+=MATERIAL_CHUNK_BYTES) {
    const bytes=new Uint8Array(await file.slice(offset,offset+MATERIAL_CHUNK_BYTES).arrayBuffer());
    full.update(bytes);partHashes.push(new MaterialSha256().update(bytes).digest());onRead?.(Math.min(file.size,offset+bytes.length));
    await new Promise(resolve=>setTimeout(resolve,0));
  }
  return {sha256:full.digest(),partHashes};
}
async function retry<T>(operation:()=>Promise<T>):Promise<T> {
  for(let attempt=0;;attempt++)try{return await operation();}catch(e){
    if(attempt>=2||e instanceof ApiError&&![0,409,500,502,503,504].includes(e.status))throw e;
    await new Promise(resolve=>setTimeout(resolve,500*(attempt+1)));
  }
}
// Re-selecting the original file reopens the same server identity. Only hashes/IDs
// live in browser storage; the file itself stays in the user's selected File handle.
export async function uploadMaterialResumable<T>(kind:'budget'|'procurement'|'project',file:File,key:string|undefined,context:Context={},onProgress?:(percent:number)=>void):Promise<T> {
  const identity=await materialFileIdentity(file);
  if(!key) {
    const storageKey=`material-transfer-v1:${context.projectId??kind}:${identity.sha256}:${file.name}`;
    key=window.localStorage.getItem(storageKey)??createOperationKey();window.localStorage.setItem(storageKey,key);
  }
  const path=`/api/v1/material-transfers/${kind}/${encodeURIComponent(key)}`;
  const spec={fileName:file.name,contentType:file.type||null,size:file.size,...identity,...context};
  let state=await retry(()=>request<Progress>(path,{method:'POST',body:spec}));
  if(state.chunkBytes!==MATERIAL_CHUNK_BYTES)throw Error('上传分片配置不一致，请刷新页面后继续');
  if(state.status!=='COMPLETED') {
    const received=new Set(state.received);
    for(let index=0;index<identity.partHashes.length;index++) {
      if(!received.has(index))state=await retry(async()=>{
        const body=new FormData();body.append('file',file.slice(index*MATERIAL_CHUNK_BYTES,(index+1)*MATERIAL_CHUNK_BYTES),'part');
        return request<Progress>(`${path}/parts/${index}`,{method:'PUT',body});
      });
      onProgress?.(Math.min(99,Math.floor((index+1)/identity.partHashes.length*100)));
    }
  }
  const result=await retry(()=>request<T>(`${path}/complete`,{method:'POST'}));onProgress?.(100);return result;
}
