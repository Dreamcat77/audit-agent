import type { ContractMaterialIntakeDependencies, ContractMaterialIntakeOperation,
  ContractMaterialIntakeResult } from "./contractMaterialIntakeOperation.ts";
import type { ProjectFileAcceptance } from "../project/data/projectDataSource.ts";

export type IntakeKeyStorage=Pick<Storage,"getItem"|"setItem"|"removeItem"|"length"|"key">;
interface FileIdentity { key:string;name:string;size:number;lastModified:number }
interface Journal { version:1;createKey:string;projectId:string|null;files:FileIdentity[];createSent?:boolean;automaticAnalysis?:boolean }
const validKey=(key:unknown):key is string=>typeof key==="string"&&/^[A-Za-z0-9._:-]{1,128}$/.test(key);
const missing=(error:unknown)=>typeof error==="object"&&error!==null&&"status" in error&&error.status===404;

/** Persists client command identities only. Every restored outcome is proved with server GET. */
export function createRetainedContractMaterialIntake(dependencies:ContractMaterialIntakeDependencies,
  createKey:()=>string):ContractMaterialIntakeOperation {
  const retained=dependencies.retained!;
  const legacyStorageKey=`audit:contract-material-intake:v1:${encodeURIComponent(retained.actorId)}${retained.projectId?`:${encodeURIComponent(retained.projectId)}`:""}`;
  const prefix=`audit:contract-material-intake:v2:${encodeURIComponent(retained.actorId)}:${retained.projectId?`PROJECT:${encodeURIComponent(retained.projectId)}`:"PORTAL"}:`;
  let storageKey:string|null=null;
  const source=dependencies.dataSource;
  const selected=new Map<string,File>();
  let flight:Promise<ContractMaterialIntakeResult|null>|null=null;
  function load():Journal|null {
    if(storageKey===null) {
      if(retained.storage.getItem(legacyStorageKey)!==null)storageKey=legacyStorageKey;
      else {
        const keys:string[]=[];
        for(let index=0;index<retained.storage.length;index++){
          const key=retained.storage.key(index);if(key?.startsWith(prefix))keys.push(key);
        }
        storageKey=keys.sort()[0]??null;
      }
    }
    if(storageKey===null)return null;
    const value=retained.storage.getItem(storageKey);if(value===null)return null;
    const journal=JSON.parse(value) as Journal;
    if(journal.version!==1||!validKey(journal.createKey)||(journal.projectId!==null&&typeof journal.projectId!=="string")
      ||!Array.isArray(journal.files)||journal.files.some(f=>!validKey(f.key)||typeof f.name!=="string"
        ||!Number.isSafeInteger(f.size)||f.size<0||!Number.isSafeInteger(f.lastModified)))
      throw new Error("上次上传记录无法核对，请先核实原操作。");
    if(new Set(journal.files.map(f=>f.key)).size!==journal.files.length)throw new Error("上次上传记录无法核对。");
    return journal;
  }
  function save(journal:Journal,initialize=false){
    if(initialize)storageKey=prefix+encodeURIComponent(journal.createKey);
    const current=load();
    if(initialize?current!==null:current===null||current.createKey!==journal.createKey)
      throw new Error("此批材料已由其他页面核对，请重新读取后继续。");
    if(current)journal.files=journal.files.filter(file=>current.files.some(value=>value.key===file.key));
    retained.storage.setItem(storageKey!,JSON.stringify(journal));
  }
  function result(journal:Journal):ContractMaterialIntakeResult {
    return {kind:"OUTCOME_UNCERTAIN",projectId:journal.projectId,succeeded:[],unresolved:[],
      unresolvedCount:journal.files.length};
  }
  function validate(receipt:ProjectFileAcceptance,projectId:string):void {
    if(receipt.projectId!==projectId||receipt.file.projectId!==projectId||receipt.file.id!==receipt.fileId
      ||receipt.job!==null&&receipt.jobId===null)throw new Error("本次材料接收结果无法核对，请继续原操作。");
  }
  async function execute(allowWrites:boolean):Promise<ContractMaterialIntakeResult|null> {
    const journal=load();if(!journal)return null;
    if(!journal.projectId) {
      if(source.getCreationOperation&&(journal.createSent||!allowWrites)) {
        try {journal.projectId=(await source.getCreationOperation(journal.createKey)).id;save(journal);}
        catch(cause){if(!missing(cause))return result(journal);}
      }
      if(!journal.projectId) {
        if(!allowWrites)return result(journal);
        try {journal.createSent=true;save(journal);journal.projectId=(await source.createBlank({idempotencyKey:journal.createKey,
          ...(journal.automaticAnalysis?{automaticAnalysisFileCount:journal.files.length}:{})})).id;save(journal);}
        catch{return result(journal);}
      }
    }
    try {if((await source.getProject(journal.projectId)).project.id!==journal.projectId)return result(journal);}
    catch{return result(journal);}
    for(const item of [...journal.files]) {
      let receipt:ProjectFileAcceptance|null=null;
      if(source.getUploadOperation) {
        try {receipt=await source.getUploadOperation(journal.projectId,item.key);validate(receipt,journal.projectId);}
        catch(cause){if(!missing(cause))continue;}
      }
      // An existing receipt without a job is an unproven publication. GET must resolve it.
      if(receipt&&receipt.job===null)continue;
      if(!receipt&&allowWrites&&selected.has(item.key)) {
        try {
          const file=selected.get(item.key)!;
          if(source.uploadAsync)receipt=await source.uploadAsync(journal.projectId,file,{idempotencyKey:item.key});
          else {
            const uploaded=await source.upload(journal.projectId,file,{idempotencyKey:item.key});
            receipt={projectId:journal.projectId,fileId:uploaded.id,jobId:"LEGACY",file:uploaded,job:null};
          }
          validate(receipt,journal.projectId);
          if(source.uploadAsync&&receipt.job===null)continue;
        }catch{continue;}
      }
      if(receipt) {journal.files=journal.files.filter(f=>f.key!==item.key);save(journal);selected.delete(item.key);}
    }
    if(journal.files.length>0)return result(journal);
    if(load()?.createKey===journal.createKey)retained.storage.removeItem(storageKey!);
    return {kind:"COMPLETED",projectId:journal.projectId,succeeded:[],unresolved:[],acceptedAsync:Boolean(source.uploadAsync)};
  }
  function run(writes:boolean):Promise<ContractMaterialIntakeResult|null> {
    if(flight)return flight;
    flight=execute(writes).finally(()=>{flight=null;});return flight;
  }
  return {
    start(files) {
      if(flight)return flight as Promise<ContractMaterialIntakeResult>;
      storageKey=null;
      if(!dependencies.newProjectPerBatch&&load())return Promise.reject(new Error("上次材料上传结果尚未确认，请继续原操作。"));
      if(files.length===0)return Promise.resolve({kind:"CANCELLED"});
      if(files.some(f=>!/\.(zip|pdf|docx?|png|jpe?g)$/i.test(f.name))
        ||files.some(f=>/\.zip$/i.test(f.name))&&files.length!==1)
        return Promise.reject(new Error("请选择单个ZIP资料包，或PDF、DOC（Word 97-2003）、DOCX、PNG、JPG/JPEG材料。"));
      const journal:Journal={version:1,createKey:createKey(),projectId:dependencies.newProjectPerBatch?null:retained.projectId??null,automaticAnalysis:dependencies.automaticAnalysis,
        files:files.map(file=>({key:createKey(),name:file.name,size:file.size,lastModified:file.lastModified}))};
      // Saving must succeed before the first create or byte upload request.
      save(journal,true);selected.clear();journal.files.forEach((item,index)=>selected.set(item.key,files[index]!));
      return run(true) as Promise<ContractMaterialIntakeResult>;
    },
    retry(files=[]) {
      const journal=load();if(!journal)return Promise.reject(new Error("没有需要继续的材料上传。"));
      const remaining=[...journal.files];
      for(const file of files) {
        const index=remaining.findIndex(item=>item.name===file.name&&item.size===file.size&&item.lastModified===file.lastModified);
        if(index<0)return Promise.reject(new Error("请重新选择上次尚未确认的原文件，不能替换原操作的材料。"));
        const item=remaining.splice(index,1)[0]!;selected.set(item.key,file);
      }
      return run(true) as Promise<ContractMaterialIntakeResult>;
    },
    restore(){
      if(flight)return flight;
      flight=(async()=>{
        let previous:ContractMaterialIntakeResult|null=null;
        for(;;){storageKey=null;if(!load())return previous;
          const result=await execute(false);
          if(!result||result.kind!=="COMPLETED")return result;
          previous=result;
        }
      })().finally(()=>{flight=null;});
      return flight;
    },
    snapshot(){const journal=load();return {running:flight!==null,projectId:journal?.projectId??null,
      filenames:journal?.files.map(f=>f.name)??[],unresolvedFilenames:journal?.files.map(f=>f.name)??[],
      pendingOperation:journal!==null,needsFileSelection:journal?.files.some(f=>!selected.has(f.key))??false};},
  };
}
