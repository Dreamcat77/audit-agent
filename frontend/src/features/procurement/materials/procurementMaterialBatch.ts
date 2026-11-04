import type { ProcurementMaterialResult } from '../../../api/types';
import { createOperationKey } from '../../../api/operationKey.ts';
import { droppedFilePath } from './procurementFolderDrop.ts';
import { procurementFileProblem } from './procurementUploadQueue.ts';
import { OFFICE_MATERIAL_PATTERN } from './officeMaterialTypes.ts';
import { sameMaterialFile, type MaterialOperation, readMaterialOperations, writeMaterialOperations } from './procurementMaterialOperation.ts';

export type BatchPhase = 'WAITING_UPLOAD' | 'UPLOADING' | ProcurementMaterialResult['status'];
export interface MaterialUploadResult { id:string;status:ProcurementMaterialResult['status'];updatedAt:string;errorCode:string|null }
export interface BatchEntry<T extends MaterialUploadResult=ProcurementMaterialResult> { operation: MaterialOperation; phase: BatchPhase; result: T | null; error: string | null }
export interface BatchSnapshot<T extends MaterialUploadResult=ProcurementMaterialResult> { entries: readonly BatchEntry<T>[]; uploading: boolean; error: string | null }
export interface MaterialBatchApi<T extends MaterialUploadResult=ProcurementMaterialResult> {
  prepare?(operations: readonly MaterialOperation[]): Promise<void>;
  upload(file: File, key: string, operation?:MaterialOperation): Promise<T>;
  operation(key: string): Promise<T | null>;
  get(id: string): Promise<T>;
  retry(id: string): Promise<T>;
}
export function materialProblem(result: MaterialUploadResult): string {
  const messages: Record<string,string> = {
    MULTIPLE_PROJECTS_REQUIRE_SEPARATE_FILES:'检测到不同采购项目，未自动合并；请按项目核对',
    AI_NO_FIELDS:'文件已读取，但未提取到采购需求字段；可手动填写或重试',
    MATERIAL_CALL_TIMEOUT:'此文件等待模型响应超时，未生成结果，其他文件继续处理',
    MATERIAL_TIME_LIMIT:'此文件处理时间已达上限，未生成结果，其他文件继续处理',
    MATERIAL_QUEUE_TIMEOUT:'此文件等待处理超时，未生成结果，其他文件继续处理',
    AI_EXTRACTION_UNAVAILABLE:'模型信息提取失败，请查看关联日志或稍后重试',
    AI_EVIDENCE_INVALID:'模型返回的字段依据无法核对，请检查原文件后重试',
    TEXT_LIMIT_EXCEEDED:'文本超过处理上限，请按章节拆分',
    PAGE_LIMIT_EXCEEDED:'页数超过处理上限，请分段上传',
    DOCUMENT_TEXT_EMPTY:'文件未读取到文字',
    DOCUMENT_CONTAINER_INVALID:'DOCX文件结构或内容损坏，请修复原文件；其他材料继续处理',
    DOCUMENT_READ_FAILED:'文档读取失败，请核对原文件或稍后重试',
    PDF_PARSE_FAILED:'PDF文件读取失败，请核对原文件',
    OCR_UNAVAILABLE:'OCR服务未能识别文件，请查看关联日志后重试',
    AI_RESULT_INVALID:'模型返回格式不符合解析约定，请查看关联日志',
    SOURCE_FILE_MISSING:'未找到已保存的文件，请重新上传',
    SOURCE_FILE_MISMATCH:'已保存文件与原记录不一致，请联系管理员核对',
    STORAGE_OUTCOME_UNKNOWN:'文件保存状态待确认，原操作已保留',
    MATERIAL_OWNER_UNAVAILABLE:'原上传账号已停用或权限已变化，请联系管理员',
  };
  return messages[result.errorCode ?? ''] ?? `此文件未完成解析（${result.errorCode ?? result.status}）`;
}
function inProgress(phase: BatchPhase) { return ['WAITING_UPLOAD','UPLOADING','QUEUED','PROCESSING'].includes(phase); }

/** File references stay outside modal lifetime. Persisted IDs reopen server work after reload. */
export class MaterialUploadBatch<T extends MaterialUploadResult=ProcurementMaterialResult> {
  private state: BatchSnapshot<T> = {entries:[],uploading:false,error:null};
  private listeners = new Set<() => void>();
  private files = new Map<string,File>();
  private live = false;
  private flight = false;
  private polling = false;
  private generation = 0;
  private pollTimer: ReturnType<typeof setTimeout> | undefined;
  private readonly storageKey: string;
  private actor: string;
  private storage: Storage;
  private api: MaterialBatchApi<T>;
  private namespace: string;
  private filePattern: RegExp;
  constructor(actor: string, storage: Storage, api: MaterialBatchApi<T>, initialError: string | null=null,
      options: {namespace?:string;filePattern?:RegExp}={}) {
    this.actor=actor; this.storage=storage; this.api=api;
    this.namespace=options.namespace??'procurement';this.filePattern=options.filePattern??OFFICE_MATERIAL_PATTERN;
    this.storageKey=`${this.namespace}-material-batch:${encodeURIComponent(actor)}`;
    if(initialError) { this.state={entries:[],uploading:false,error:initialError}; return; }
    try {
      const raw=storage.getItem(this.storageKey);
      const operations=readMaterialOperations(storage,actor,this.namespace);
      if(raw) {
        const rows=JSON.parse(raw) as BatchEntry<T>[];
        if(!Array.isArray(rows) || rows.length>1000 || new Set(rows.map(row=>row?.operation?.key)).size!==rows.length
          || rows.some(row=>!row || !operations.some(o=>o.key===row.operation?.key
            && o.name===row.operation.name && o.size===row.operation.size
            && o.lastModified===row.operation.lastModified && o.relativePath===row.operation.relativePath
            && JSON.stringify(o.context)===JSON.stringify(row.operation.context))
          || !['WAITING_UPLOAD','UPLOADING','QUEUED','PROCESSING','PARSED','FAILED','UNKNOWN'].includes(row.phase))) throw Error();
        // Never render a stored extraction result: reopen it through the authenticated API.
        const entries=rows.map(row=>row.result || row.phase!=='FAILED'
          ? {operation:row.operation,result:null,phase:'UNKNOWN' as const,error:'正在核对服务端进度；未上传的文件可重新选择原文件继续'}
          : {operation:row.operation,result:null,phase:row.phase,error:row.error});
        // A quota failure can occur between the two writes. Canonical identities
        // must survive even when their corresponding batch snapshot is older.
        for(const operation of operations) if(!entries.some(row=>row.operation.key===operation.key))
          entries.push({operation,phase:'UNKNOWN',result:null,error:'上传结果待确认；原操作已保留，请重新选择同一文件继续'});
        this.state={entries,uploading:false,error:null};
        // Also remove extraction fields/evidence cached by earlier versions on this account.
        try { this.persistBatch(entries); }
        catch { this.state={...this.state,error:'无法保存上传操作记录，已暂停新上传；现有服务端解析继续运行'}; }
      } else this.state={entries:operations.map(operation=>({operation,phase:'UNKNOWN',result:null,error:null})),uploading:false,error:null};
    } catch { this.state={entries:[],uploading:false,error:'上传记录无法可靠读取，请保留记录并联系管理员核对'}; }
  }
  snapshot = () => this.state;
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener); }; };
  activate() { this.live=true; void this.reconcile(); void this.drain(); }
  pause() { this.live=false; clearTimeout(this.pollTimer); }
  /** Explicitly start a new demand; old asynchronous responses cannot refill it. */
  clear() {
    try {
      const archiveKey=`${this.namespace}-material-previous:${encodeURIComponent(this.actor)}`;
      const previous=JSON.parse(this.storage.getItem(archiveKey) ?? '[]');
      if(!Array.isArray(previous)) throw Error('invalid previous operation archive');
      if(this.state.entries.length) this.storage.setItem(archiveKey,JSON.stringify([...previous,
        ...this.state.entries.map(row=>({operation:row.operation,id:row.result?.id ?? null,phase:row.phase}))]));
      // Keep an explicit empty identity list so legacy session records cannot migrate back.
      this.storage.setItem(`${this.namespace}-material-operation:${encodeURIComponent(this.actor)}`,'[]');
      this.persistBatch([]);
    } catch {
      this.state={...this.state,error:'无法清空本次材料，请检查浏览器存储后再试'};
      for(const listener of this.listeners) listener();
      return false;
    }
    this.generation++;
    clearTimeout(this.pollTimer);
    this.files.clear();
    this.state={entries:[],uploading:false,error:null};
    for(const listener of this.listeners) listener();
    return true;
  }
  private persistBatch(entries: readonly BatchEntry<T>[]) {
    this.storage.setItem(this.storageKey,JSON.stringify(entries.map(row=>({
      operation:row.operation,phase:row.phase,error:row.error,
      result:row.result ? {id:row.result.id} : null,
    }))));
  }
  private publish(entries: readonly BatchEntry<T>[]=this.state.entries, uploading=this.state.uploading) {
    this.state={...this.state,entries,uploading};
    try {
      // Operation identities are written before any HTTP upload. If local persistence
      // fails, fail closed before starting another request.
      writeMaterialOperations(this.storage,this.actor,entries.map(row=>row.operation),this.namespace);
      this.persistBatch(entries);
    } catch { this.state={...this.state,error:'无法保存上传操作记录，已暂停新上传；现有服务端解析继续运行'}; }
    for(const listener of this.listeners) listener();
  }
  add(selected: readonly File[], context?:MaterialOperation['context']) {
    if(this.state.error) return;
    let rows=this.state.entries.map(row=>({...row}));
    // Explicit new batch only replaces fully known terminal outcomes.
    if(rows.length && rows.every(row=>row.phase==='PARSED'||row.phase==='FAILED')) rows=[];
    for(const file of selected) {
      let row=rows.find(item=>sameMaterialFile(item.operation,file));
      if(!row && !droppedFilePath(file)) {
        const uncertain=rows.filter(item=>item.phase==='UNKNOWN'&&!item.result
          && item.operation.name===file.name && item.operation.size===file.size && item.operation.lastModified===file.lastModified);
        if(uncertain.length===1) row=uncertain[0]; // Original key; server verifies the content hash.
        if(uncertain.length>1) {
          for(const candidate of uncertain) candidate.error='多个原文件的名称和大小相同，请重新拖入原文件夹，以保留各自上传操作';
          continue; // Never silently create another key for an ambiguous unknown upload.
        }
      }
      if(!row) {
        if(rows.length>=1000) { this.state={...this.state,error:'本批已达到 1000 份，请处理完成后再提交下一批'}; break; }
        const problem=procurementFileProblem(file,this.filePattern);
        // Empty/invalid files have no server identity but still appear in the batch summary.
        row={operation:{key:createOperationKey(),name:file.name,size:file.size,lastModified:file.lastModified,
          relativePath:droppedFilePath(file),...(context?{context:{...context}}:{})},phase:problem?'FAILED':'WAITING_UPLOAD',result:null,error:problem};
        rows.push(row);
      }
      if(!row.result && (row.phase==='WAITING_UPLOAD'||row.phase==='UNKNOWN') && !procurementFileProblem(file,this.filePattern)) {
        this.files.set(row.operation.key,file); row.phase='WAITING_UPLOAD'; row.error=null;
      }
    }
    this.publish(rows);
    if(this.live) void this.drain();
  }
  private accept(key: string, result: T) {
    this.publish(this.state.entries.map(row=>row.operation.key===key
      && !(row.phase==='PARSED' && result.status!=='PARSED')
      && !(row.result && Date.parse(row.result.updatedAt)>Date.parse(result.updatedAt))
      ? {...row,result,phase:result.status,error:result.status==='FAILED'||result.status==='UNKNOWN'?materialProblem(result):null}:row));
  }
  private async drain() {
    if(this.flight || !this.live || this.state.error) return;
    const generation=this.generation;
    this.flight=true; this.publish(this.state.entries,true);
    try {
      for(;;) {
        if(!this.live || this.state.error || generation!==this.generation) break;
        const row=this.state.entries.find(item=>item.phase==='WAITING_UPLOAD' && this.files.has(item.operation.key));
        if(!row) break;
        const key=row.operation.key;
        this.publish(this.state.entries.map(item=>item.operation.key===key?{...item,phase:'UPLOADING',error:null}:item),true);
        if(this.state.error) break;
        try {
          // Persist immutable membership before any file leaves the browser. Retrying
          // preparation or a lost response uses exactly the original batch/operation IDs.
          if(this.api.prepare) await this.api.prepare(this.state.entries.map(entry=>entry.operation));
          if(!this.live || generation!==this.generation) break;
          const result=await this.api.upload(this.files.get(key)!,key,row.operation);
          if(generation!==this.generation) break;
          this.accept(key,result); this.files.delete(key);
        }
        catch {
          if(generation!==this.generation) break;
          // A lost response never means failed upload. Preserve key and reconcile
          // independently; the next file does not wait for this outcome.
          this.publish(this.state.entries.map(item=>item.operation.key===key
            ? {...item,phase:'UNKNOWN',error:'上传结果待确认，原操作已保留；其他文件继续处理'}:item),true);
        }
        void this.reconcile();
      }
    } finally {
      this.flight=false;
      if(generation===this.generation) this.publish(this.state.entries,false);
      void this.reconcile();
      if(generation!==this.generation) void this.drain();
    }
  }
  async reconcile() {
    if(this.polling || !this.live || this.state.error) return;
    clearTimeout(this.pollTimer); this.polling=true;
    const generation=this.generation;
    try {
      for(const row of [...this.state.entries]) {
        if(!this.live || generation!==this.generation) break;
        if(!['QUEUED','PROCESSING','UNKNOWN'].includes(row.phase)) continue;
        try {
          const current=row.result ? await this.api.get(row.result.id) : await this.api.operation(row.operation.key);
          if(current && generation===this.generation) this.accept(row.operation.key,current);
        } catch { /* Missing response is not a missing server record. Keep identity. */ }
      }
    } finally {
      this.polling=false;
      if(generation!==this.generation) void this.reconcile();
      else if(this.live && this.state.entries.some(row=>inProgress(row.phase)||row.phase==='UNKNOWN'))
        this.pollTimer=setTimeout(()=>void this.reconcile(),2000);
    }
  }
  async retry(key: string) {
    const row=this.state.entries.find(item=>item.operation.key===key);
    if(!row?.result || row.phase==='PARSED'||inProgress(row.phase)) return;
    const generation=this.generation;
    try {
      const result=await this.api.retry(row.result.id);
      if(generation!==this.generation) return;
      this.accept(key,result); void this.reconcile();
    }
    catch { if(generation===this.generation) this.publish(this.state.entries.map(item=>item.operation.key===key?{...item,error:'重试结果待确认，请刷新进度'}:item)); }
  }
}

export { MaterialUploadBatch as ProcurementMaterialBatch };
