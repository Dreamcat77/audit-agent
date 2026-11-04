import { useEffect, useRef, useState } from 'react';
import type { ProcurementSourceProject } from '../../../api/types';
import { collectProcurementDrop } from './procurementFolderDrop';
import { useMaterialBatch } from './procurementMaterialBatchStore';
import { OFFICE_MATERIAL_ACCEPT } from './officeMaterialTypes';

export function ProcurementMaterialUpload({actorId,onParsed,onUse,onBusy}: {
  actorId:string; onParsed:()=>void; onUse:(source:ProcurementSourceProject)=>void; onBusy:(busy:boolean)=>void;
}) {
  const {batch,snapshot}=useMaterialBatch(actorId);
  const [files,setFiles]=useState<File[]>([]);
  const [dragging,setDragging]=useState(false);
  const [notice,setNotice]=useState<string|null>(null);
  const input=useRef<HTMLInputElement>(null);
  const applied=useRef(new Set<string>());
  const callbacks=useRef({onParsed,onUse,onBusy}); callbacks.current={onParsed,onUse,onBusy};
  const alive=useRef(true);
  useEffect(()=>{alive.current=true; return ()=>{alive.current=false;};},[]);
  const unresolved=snapshot.entries.filter(row=>['WAITING_UPLOAD','UPLOADING','QUEUED','PROCESSING'].includes(row.phase));
  const parsed=snapshot.entries.filter(row=>row.phase==='PARSED');
  const failed=snapshot.entries.filter(row=>row.phase==='FAILED');
  const unknown=snapshot.entries.filter(row=>row.phase==='UNKNOWN');
  useEffect(()=>{
    callbacks.current.onBusy(snapshot.uploading||unresolved.length>0);
    for(const row of parsed) {
      const result=row.result;
      if(!result?.fields) continue;
      const identity=`${result.id}:${result.updatedAt}`;
      if(applied.current.has(identity)) continue;
      applied.current.add(identity);
      callbacks.current.onUse({id:result.id,name:result.fields.projectName,projectNo:result.fields.projectNo,
        category:result.fields.procurementType,updatedAt:result.updatedAt,fileName:result.fileName,fields:result.fields});
      callbacks.current.onParsed();
    }
  },[snapshot]);
  const start=()=>{
    batch.add(files); setFiles([]); setNotice(null);
    if(input.current) input.current.value='';
  };
  return <section className="space-y-3 rounded-lg border border-slate-200 bg-slate-50 p-4 text-xs">
    <p className="font-medium text-slate-800">上传采购材料，自动提取需求信息</p>
    <p className="text-slate-500">支持 Word、Excel、PDF、PPT、文本和常见图片及拖入文件夹；单文件最大 500 MB，单个失败不影响其他文件。提取后请核对下方信息，点击“保存并查看结果”即自动分析这批材料，无需重复上传。</p>
    <div onDragOver={event=>{event.preventDefault();setDragging(true);}}
      onDragLeave={event=>{if(!event.currentTarget.contains(event.relatedTarget as Node)) setDragging(false);}}
      onDrop={event=>{
        event.preventDefault();setDragging(false);
        const entries=Array.from(event.dataTransfer.items).map(item=>item.kind==='file'?item.webkitGetAsEntry?.()??null:null);
        const fallback=Array.from(event.dataTransfer.files);
        void collectProcurementDrop(entries,fallback).then(result=>{
          if(!alive.current) return;
          setFiles(current=>[...current,...result.files]);setNotice(result.problems.length?result.problems.join('；'):null);
        });
      }}>
      <input ref={input} type="file" multiple accept={OFFICE_MATERIAL_ACCEPT} className="sr-only"
        onChange={event=>{setFiles(Array.from(event.target.files??[]));setNotice(null);}} />
      <button type="button" onClick={()=>input.current?.click()}
        className={`w-full min-w-0 rounded border border-dashed border-slate-300 bg-white px-3 py-2 text-left hover:border-blue-400 focus-visible:outline-blue-500 ${dragging?'border-blue-400 bg-blue-50':''}`}>
        <span className="block truncate font-medium text-slate-800">{files.length?`已选择 ${files.length} 份采购材料`:'选择采购材料'}</span>
        <span className="mt-1 block text-slate-500">{files.length?`${(files.reduce((sum,file)=>sum+file.size,0)/1024/1024).toFixed(2)} MB · 点击重新选择`:'支持 Word、Excel、PDF、PPT、文本和常见图片，可拖入文件及文件夹'}</span>
      </button>
    </div>
    <div className="flex flex-wrap items-center gap-2">
      <button type="button" disabled={!files.length||!!snapshot.error} onClick={start}
        aria-label={files.length?`开始解析并填充 ${files.length} 份材料信息`:'开始解析并填充材料信息'}
        className="shrink-0 rounded border border-blue-700 bg-blue-700 px-4 py-2.5 text-sm font-semibold text-white hover:bg-blue-800 disabled:cursor-not-allowed disabled:border-slate-300 disabled:bg-slate-100 disabled:text-slate-600">
        {files.length?`开始解析并填充${files.length>1?`（${files.length}份）`:''}`:'开始解析并填充'}
      </button>
      {!files.length&&<span className="text-slate-600">请先上传材料，再点击此按钮自动填写采购信息</span>}
    </div>
    {snapshot.entries.length>0 && <>
      <p role="status" className="text-slate-700">本批 {snapshot.entries.length} 份：成功 {parsed.length} 份，失败 {failed.length} 份，处理中 {unresolved.length} 份{unknown.length>0?`，确认中 ${unknown.length} 份`:''}。</p>
      {(snapshot.uploading||unresolved.length>0) && <p className="text-slate-500">{snapshot.uploading?'正在上传，请勿刷新或关闭浏览器；可以关闭此窗口或切换功能。上传完成后，服务端会继续解析。':'文件已提交，服务端正在后台解析，可以离开此页面。扫描件识别可能需要几分钟。'}</p>}
    </>}
    {(notice||snapshot.error) && <p className="text-amber-700">{snapshot.error??notice}</p>}
  </section>;
}
