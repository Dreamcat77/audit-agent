import {useEffect,useMemo,useRef,useState} from 'react';
import {highlightSource,located,sourceWindows,uniqueSourceEvidence,type SourceEvidence,type SourceText,type SourceWindow} from '../contractAnalysisEvidenceView';
import '../../../styles/source-evidence-dialog.css';
import {OriginalEvidencePreview,type VisualBase} from './OriginalEvidencePreview';
import {conciseEvidence} from '../conciseEvidence';
import {OfficeEvidencePreview} from './OfficeEvidencePreview';

type Entry={window:SourceWindow;snippets:SourceEvidence[];verified:SourceEvidence[];error:boolean};
function sourceLabel(items:readonly SourceEvidence[]):string{
  return [...new Set(items.map(e=>e.pageNumber?`第 ${e.pageNumber} 页`:e.paragraphIndex?`第 ${e.paragraphIndex} 段`:'').filter(Boolean))].join('、');
}

/** Show only the source pages for this opinion; text is an automatic fallback for non-image formats. */
export function AnalysisEvidenceDialog({evidence,documents,loadText,onClose,visualBase,originalUrl,focusText=''}:{
  evidence:readonly SourceEvidence[];documents:readonly {fileId:string;name:string}[];
  loadText:(fileId:string,start:number,size:number)=>Promise<SourceText>;onClose:()=>void;visualBase?:VisualBase;originalUrl?:VisualBase;focusText?:string;
}){
  const [entries,setEntries]=useState<Entry[]>([]),[loading,setLoading]=useState(true);
  const [unavailable,setUnavailable]=useState<Set<string>>(()=>new Set());
  const dialog=useRef<HTMLElement>(null);
  const unique=useMemo(()=>uniqueSourceEvidence(evidence),[evidence]);
  const names=new Map(documents.map(d=>[d.fileId,d.name]));
  const fileIds=[...new Set(unique.map(e=>e.fileId))];
  const verifiedOffice=useMemo(()=>entries.flatMap(e=>e.verified),[entries]);
  const previewEvidence=useMemo(()=>[
    ...entries.flatMap(entry=>entry.snippets.length?entry.snippets:entry.window.evidence.map(e=>({...e,start:null,end:null}))),
    ...unique.filter(e=>!located(e)).map(e=>conciseEvidence(e,focusText))
  ],[entries,unique,focusText]);
  useEffect(()=>{
    const previous=document.activeElement as HTMLElement|null;
    dialog.current?.querySelector<HTMLButtonElement>('button')?.focus({preventScroll:true});
    return()=>{previous?.focus({preventScroll:true});};
  },[]);
  useEffect(()=>{
    let active=true;setLoading(true);setEntries([]);setUnavailable(new Set());
    void(async()=>{
      const windows=sourceWindows(evidence),result:Entry[]=[];
      for(let offset=0;offset<windows.length;offset+=3){
        if(!active)return;
        result.push(...await Promise.all(windows.slice(offset,offset+3).map(async window=>{
          try{
            const value=await loadText(window.fileId,window.start,window.end-window.start);
            const highlighted=highlightSource(window,value);let cursor=value.start;
            const snippets=highlighted.pieces.flatMap(piece=>{
              const start=cursor;cursor+=piece.text.length;
              return piece.highlight?[conciseEvidence({fileId:window.fileId,start,end:cursor,excerpt:piece.text,
                pageNumber:window.evidence.find(e=>located(e)&&e.start<=start&&e.end>start)?.pageNumber},focusText)]:[];
            });
            const verified=window.evidence.filter(e=>located(e)&&value.text.slice(e.start-value.start,e.end-value.start)===e.excerpt);
            return {window,snippets,verified,error:false};
          }catch{return {window,snippets:[],verified:[],error:true};}
        })));
      }
      if(active){setEntries(result);setLoading(false);}
    })();
    return()=>{active=false;};
  },[evidence,loadText,focusText]);
  return <div role="dialog" aria-modal="true" aria-label="来源原文" className="source-evidence-overlay" onKeyDown={event=>{
    if(event.key==='Escape'){event.preventDefault();onClose();}
    if(event.key==='Tab'){
      const buttons=dialog.current?.querySelectorAll<HTMLElement>('button:not(:disabled),[tabindex="0"]');
      if(!buttons?.length)return;
      const first=buttons[0],last=buttons[buttons.length-1];
      if(event.shiftKey&&document.activeElement===first){event.preventDefault();last.focus();}
      else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first.focus();}
    }
  }}>
    <section ref={dialog} className="source-evidence-panel">
      <header className="source-evidence-header"><h2 className="font-semibold">相关原文</h2>
        <button type="button" className="delivery-secondary-action shrink-0" onClick={onClose}>关闭</button>
      </header>
      <div role="region" aria-label="原文内容" tabIndex={0} className="source-evidence-body">
        {loading?<p role="status" className="py-8 text-center text-sm text-slate-600">正在定位相关原文…</p>:<div className="space-y-5">{fileIds.map(fileId=>{
          const showOriginal=visualBase&&!unavailable.has(fileId)&&!(/\.(docx?|xlsx?|csv|txt|zip)$/i.test(names.get(fileId)??''));
          const officeUrl=originalUrl?.(fileId)??(visualBase?`${visualBase(fileId)}/original`:undefined);
          const showOffice=officeUrl&&/\.(docx?|xlsx?|csv)$/i.test(names.get(fileId)??'');
          return <section key={fileId} aria-label={names.get(fileId)??'来源材料'}>
            <h3 className="mb-3 break-words text-sm font-semibold text-slate-800">{names.get(fileId)??'来源材料'}</h3>
            {showOffice?<OfficeEvidencePreview fileId={fileId} name={names.get(fileId)??''} url={officeUrl} legacyUrl={visualBase?`${visualBase(fileId)}/office-html`:undefined} evidence={unique} verified={verifiedOffice} onEscape={onClose}/>:showOriginal?<OriginalEvidencePreview fileId={fileId} evidence={previewEvidence} base={visualBase}
              onUnavailable={()=>setUnavailable(old=>new Set([...old,fileId]))}/>:<div className="space-y-3">
              {entries.filter(entry=>entry.window.fileId===fileId).map((entry,index)=><article key={entry.window.start} className="rounded-lg border border-slate-200 p-4">
                <p className="mb-2 text-xs text-slate-500">相关位置 {index+1}{sourceLabel(entry.window.evidence)&&` · ${sourceLabel(entry.window.evidence)}`}</p>
                {entry.error?<p role="status" className="text-sm text-slate-600">此处原文暂时无法读取，其他位置仍可核对。</p>:!entry.snippets.length?<p role="status" className="text-sm text-amber-800">此处引用尚未与原文位置核对一致，暂不高亮，以免定位错误。</p>:<div className="source-evidence-snippets">{entry.snippets.map((snippet,i)=><blockquote key={i}><mark data-source-highlight>{snippet.excerpt}</mark></blockquote>)}</div>}
              </article>)}
              {unique.filter(e=>e.fileId===fileId&&!located(e)).map((e,i)=><blockquote key={i} className="whitespace-pre-wrap text-sm leading-7">{conciseEvidence(e,focusText).excerpt}</blockquote>)}
            </div>}
          </section>;
        })}</div>}
        {!loading&&!unique.length&&<p className="text-sm text-slate-600">暂无相关原文。</p>}
      </div>
    </section>
  </div>;
}
