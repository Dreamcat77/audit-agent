import {useEffect,useRef,useState} from "react";
import {highlightSource,located,sourceWindows,uniqueSourceEvidence,type SourceEvidence,type SourceText,type SourceWindow,type SourcePiece} from "../contractAnalysisEvidenceView";

type Entry={window:SourceWindow;pieces:SourcePiece[];unverified:number;error:boolean};
const PAGE_SIZE=6;
function sourceLabel(items:readonly SourceEvidence[]):string{
  const parts=items.map(e=>[e.pageNumber?`第 ${e.pageNumber} 页`:null,e.paragraphIndex?`第 ${e.paragraphIndex} 段`:null].filter(Boolean).join(' · ')).filter(Boolean);
  return [...new Set(parts)].join('、');
}

/** One opinion's sources, exact verified highlights, no duplicate context windows. */
export function AnalysisEvidenceDialog({evidence,documents,loadText,onClose}:{
  evidence:readonly SourceEvidence[];documents:readonly {fileId:string;name:string}[];
  loadText:(fileId:string,start:number,size:number)=>Promise<SourceText>;onClose:()=>void;
}){
  const [entries,setEntries]=useState<Entry[]>([]);const [loading,setLoading]=useState(true);
  const [visible,setVisible]=useState(PAGE_SIZE);const dialog=useRef<HTMLElement>(null);
  const cache=useRef(new Map<string,Entry>());const scrolled=useRef(false);
  const unique=uniqueSourceEvidence(evidence),windows=sourceWindows(unique);
  const unlocated=unique.filter(e=>!located(e));
  const names=new Map(documents.map(d=>[d.fileId,d.name]));
  useEffect(()=>{
    const previous=document.activeElement as HTMLElement|null;
    dialog.current?.querySelector<HTMLButtonElement>('button')?.focus();
    return()=>{previous?.focus();};
  },[]);
  useEffect(()=>{
    let current=true;setLoading(true);
    const selected=sourceWindows(evidence).slice(0,visible);
    void (async()=>{
      const result:Entry[]=[];
      // Bound concurrent text reads; keep other sources when a single read fails.
      for(let offset=0;offset<selected.length;offset+=3){
        if(!current)return;
        const batch=await Promise.all(selected.slice(offset,offset+3).map(async window=>{
          const key=JSON.stringify(window);
          const cached=cache.current.get(key);if(cached)return cached;
          try{
            const value=await loadText(window.fileId,window.start,window.end-window.start);
            const entry={window,...highlightSource(window,value),error:false};cache.current.set(key,entry);return entry;
          }catch{return {window,pieces:[],unverified:window.evidence.length,error:true};}
        }));
        result.push(...batch);
      }
      if(current){setEntries(result);setLoading(false);}
    })();
    return()=>{current=false;};
  },[evidence,loadText,visible]);
  useEffect(()=>{
    if(!loading&&!scrolled.current){dialog.current?.querySelector<HTMLElement>('mark')?.scrollIntoView({block:'nearest'});scrolled.current=true;}
  },[loading]);
  return <div role="dialog" aria-modal="true" aria-label="来源原文" className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-5"
    onKeyDown={event=>{
      if(event.key==='Escape'){event.preventDefault();onClose();}
      if(event.key==='Tab'){
        const buttons=dialog.current?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)');
        if(!buttons?.length)return;
        const first=buttons[0],last=buttons[buttons.length-1];
        if(event.shiftKey&&document.activeElement===first){event.preventDefault();last.focus();}
        else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first.focus();}
      }
    }}>
    <section ref={dialog} className="max-h-[85vh] w-full max-w-3xl overflow-auto rounded-xl bg-white p-6 shadow-xl">
      <header className="sticky top-0 z-10 mb-4 flex items-start justify-between gap-4 border-b border-slate-100 bg-white pb-3">
        <div><h2 className="font-semibold">相关原文</h2><p className="mt-1 text-sm text-slate-600">高亮部分为本条意见引用的原文，周边文字供核对上下文。</p></div>
        <button type="button" className="delivery-secondary-action shrink-0" onClick={onClose}>关闭</button>
      </header>
      {loading?<p role="status" className="py-8 text-center text-sm text-slate-600">正在定位相关原文…</p>:<div className="space-y-5">
        {Array.from(new Set(entries.map(e=>e.window.fileId))).map(fileId=><section key={fileId} aria-label={names.get(fileId)??'来源材料'}>
          <h3 className="mb-3 break-words text-sm font-semibold text-slate-800">{names.get(fileId)??'来源材料'}</h3>
          <div className="space-y-3">{entries.filter(entry=>entry.window.fileId===fileId).map((entry,index)=><article key={entry.window.start} className="rounded-lg border border-slate-200 p-4">
            <p className="mb-2 text-xs text-slate-500">相关位置 {index+1}{sourceLabel(entry.window.evidence)&&` · ${sourceLabel(entry.window.evidence)}`}</p>
            {entry.error?<p role="status" className="text-sm text-slate-600">此处原文暂时无法读取，其他位置仍可核对。</p>:entry.pieces.length===0?<p role="status" className="text-sm text-amber-800">此处引用尚未与原文位置核对一致，暂不高亮，以免定位错误。</p>:<>
              <p className="whitespace-pre-wrap break-words text-sm leading-8 text-slate-500">{entry.pieces.map((piece,i)=>piece.highlight?<mark key={i} className="rounded-sm bg-amber-200 px-0.5 text-slate-950" data-source-highlight>{piece.text}</mark>:<span key={i}>{piece.text}</span>)}</p>
              {entry.unverified>0&&<p role="status" className="mt-2 text-xs text-amber-800">部分引用尚未核对一致，仅高亮已确认的位置。</p>}
            </>}
          </article>)}</div>
        </section>)}
      </div>}
      {unlocated.length>0&&<div className="mt-4 space-y-3">{unlocated.map((e,i)=><section key={`${e.fileId}:${i}`} className="rounded-lg border border-slate-200 p-4">
        <h3 className="text-sm font-semibold">{names.get(e.fileId)??'来源材料'}{sourceLabel([e])&&` · ${sourceLabel([e])}`}</h3><p className="mt-1 text-xs text-amber-800">该引用暂未提供精确位置，以下仅展示引用文字。</p>
        <blockquote className="mt-2 whitespace-pre-wrap break-words text-sm leading-7 text-slate-700">{e.excerpt}</blockquote>
      </section>)}</div>}
      {windows.length>visible&&!loading&&<button type="button" className="delivery-secondary-action mt-4" onClick={()=>setVisible(n=>n+PAGE_SIZE)}>继续查看其余 {windows.length-visible} 处引用</button>}
      {!unique.length&&<p className="text-sm text-slate-600">此项暂无可核对的原文引用。</p>}
    </section>
  </div>;
}
