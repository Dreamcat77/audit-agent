import {useCallback,useEffect,useRef,useState} from 'react';
import {AnalysisEvidenceDialog} from '../contract/components/AnalysisEvidenceDialog';
import type {SourceEvidence} from '../contract/contractAnalysisEvidenceView';
import type {VisualBase} from '../contract/components/OriginalEvidencePreview';
import {locateProcurementCitation,procurementSourceText,type IndexedProcurementSource,type ProcurementCitation} from './procurementSourceView';

/** Shares the contract viewer, including exact highlights, context, keyboard and multi-file support. */
export function ProcurementEvidenceDialog({evidence,loadSource,onClose,visualBase,focusText}:{
  evidence:readonly ProcurementCitation[];loadSource:(id:string)=>Promise<IndexedProcurementSource>;onClose:()=>void;visualBase?:VisualBase;focusText?:string;
}){
  const opener=useRef(document.activeElement as HTMLElement|null);
  useEffect(()=>()=>{queueMicrotask(()=>opener.current?.focus());},[]);
  const [ready,setReady]=useState<{items:SourceEvidence[];sources:Map<string,IndexedProcurementSource>}|null>(null);
  useEffect(()=>{
    let active=true;
    void(async()=>{
      const sources=new Map<string,IndexedProcurementSource>();
      const ids=[...new Set(evidence.map(e=>e.documentId))];
      for(let offset=0;offset<ids.length;offset+=3){
        if(!active)return;
        await Promise.all(ids.slice(offset,offset+3).map(async id=>{try{sources.set(id,await loadSource(id));}catch{/* Keep other files and show the unavailable quotation without a fabricated highlight. */}}));
      }
      if(active)setReady({sources,items:evidence.map(e=>locateProcurementCitation(e,sources.get(e.documentId)))});
    })();
    return()=>{active=false;};
  },[evidence,loadSource]);
  const loadText=useCallback(async(id:string,start:number,size:number)=>{
    const source=ready?.sources.get(id);if(!source)throw new Error('Source unavailable');
    return procurementSourceText(source,start,size);
  },[ready]);
  if(!ready)return <div role="dialog" aria-modal="true" aria-label="来源原文" className="source-evidence-overlay" onKeyDown={e=>{if(e.key==='Escape')onClose();if(e.key==='Tab')e.preventDefault();}}>
    <section className="source-evidence-panel"><header className="source-evidence-header"><h2 className="text-base font-semibold">相关原文</h2><button autoFocus className="delivery-secondary-action" onClick={onClose}>关闭</button></header><div className="source-evidence-body"><p role="status" className="py-8 text-center text-sm text-slate-600">正在定位相关原文…</p></div></section>
  </div>;
  return <AnalysisEvidenceDialog focusText={focusText} evidence={ready.items} documents={[...new Map(evidence.map(e=>[e.documentId,{fileId:e.documentId,name:e.fileName}])).values()]} loadText={loadText} visualBase={visualBase} onClose={onClose}/>;
}
