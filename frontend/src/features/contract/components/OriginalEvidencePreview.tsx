import {useEffect,useRef,useState} from 'react';
import {apiUrl,request} from '../../../api/client';
import {located,uniqueSourceEvidence,type SourceEvidence} from '../contractAnalysisEvidenceView';
import {evidencePages} from '../evidencePages';

type Anchor={pageNumber:number;method:string;start:number;end:number;polygon:{x:number;y:number}[];confidence:number;verification?:string};
type Selection={status:string;anchors:Anchor[];pages:number[]};
export type VisualBase=(fileId:string)=>string;

/** Pixels come only from the authenticated original renderer. The SVG is a transparent overlay. */
export function OriginalEvidencePreview({fileId,evidence,base,onUnavailable}:{fileId:string;evidence:readonly SourceEvidence[];base:VisualBase;onUnavailable?:()=>void}){
  const [selection,setSelection]=useState<Selection|null>(null),[page,setPage]=useState(0);
  const [image,setImage]=useState<{url:string;page:number;endpoint:string}|null>(null),[error,setError]=useState(false),[loading,setLoading]=useState(true),[zoom,setZoom]=useState(100);
  const viewport=useRef<HTMLDivElement>(null),picture=useRef<HTMLDivElement>(null);
  const endpoint=base(fileId);
  useEffect(()=>{
    let active=true;setSelection(null);setError(false);setPage(0);setZoom(100);
    void(async()=>{
      const quotes=uniqueSourceEvidence(evidence).filter(e=>e.fileId===fileId);const values:Selection[]=[];
      const pages:number[]=[];
      for(let at=0;at<quotes.length;at+=3){
        if(!active)return;
        const batch=await Promise.all(quotes.slice(at,at+3).map(async e=>{
          let value:Selection|null=null;
          if(located(e))try{value=await request<Selection>(`${endpoint}/visual`,{params:{start:String(e.start),end:String(e.end),...(e.pageNumber?{page:String(e.pageNumber)}:{})}});}catch{/* Keep a known evidence page without guessing a highlight. */}
          return {value,pages:evidencePages(value?[value]:[],e.pageNumber?[e.pageNumber]:[])};
        }));
        values.push(...batch.flatMap(v=>v.value?[v.value]:[]));pages.push(...batch.flatMap(v=>v.pages));
      }
      if(!active)return;
      const anchors=values.flatMap(v=>v.anchors);
      const selectedPages=[...new Set(pages)].sort((a,b)=>a-b);
      setSelection({status:anchors.length?'LOCATED':'PAGE_ONLY',anchors,pages:selectedPages});setPage(selectedPages[0]??0);
      if(!selectedPages.length)setLoading(false);
    })();
    return()=>{active=false;};
  },[endpoint,evidence,fileId]);
  useEffect(()=>{
    if(!selection||!selection.pages.includes(page))return;
    const controller=new AbortController();let url:string|null=null;setImage(null);setLoading(true);setError(false);
    void fetch(apiUrl(`${endpoint}/pages/${page}/image`),{credentials:'include',signal:controller.signal}).then(async response=>{
      if(!response.ok||!(response.headers.get('content-type')??'').startsWith('image/png'))throw new Error('PAGE_UNAVAILABLE');
      const blob=await response.blob();if(controller.signal.aborted)return;url=URL.createObjectURL(blob);setImage({url,page,endpoint});setLoading(false);
    }).catch(()=>{if(!controller.signal.aborted){setError(true);setLoading(false);onUnavailable?.();}});
    return()=>{controller.abort();if(url)URL.revokeObjectURL(url);};
  },[endpoint,page,selection]);
  const anchors=selection?.anchors.filter(a=>a.pageNumber===page)??[];
  const visibleImage=image?.page===page&&image.endpoint===endpoint?image.url:null;
  const pageIndex=selection?.pages.indexOf(page)??-1;
  const position=()=>{
    const scroll=viewport.current,body=picture.current;if(!scroll||!body)return;
    const top=anchors.length?Math.min(...anchors.flatMap(a=>a.polygon.map(p=>p.y))):0;
    const left=anchors.length?Math.min(...anchors.flatMap(a=>a.polygon.map(p=>p.x))):0;
    scroll.scrollTop=Math.max(0,top*body.clientHeight-55);
    scroll.scrollLeft=Math.max(0,left*body.clientWidth-55);
  };
  useEffect(()=>{if(visibleImage)position();},[zoom,visibleImage,page]);
  return <section className="original-evidence" aria-label="原文件片段">
    <div className="original-evidence-toolbar">
      <span>{page?`证据页 · 第 ${page} 页`:'证据页'}</span>
      <div>
        {selection&&selection.pages.length>1&&<><button type="button" disabled={pageIndex<=0||loading} onClick={()=>setPage(selection.pages[pageIndex-1])}>上一证据页</button>
        <span>{pageIndex+1} / {selection.pages.length}</span><button type="button" disabled={pageIndex>=selection.pages.length-1||loading} onClick={()=>setPage(selection.pages[pageIndex+1])}>下一证据页</button></>}
        <button type="button" disabled={zoom<=75} aria-label="缩小原页" onClick={()=>setZoom(z=>z-25)}>−</button>
        <span>{zoom}%</span><button type="button" disabled={zoom>=200} aria-label="放大原页" onClick={()=>setZoom(z=>z+25)}>＋</button>
      </div>
    </div>
    {loading||!selection||(!error&&selection.pages.includes(page)&&!visibleImage)?<p role="status">正在读取原页…</p>:!selection.pages.length?<p role="status">暂无可定位的证据页。</p>:error?<p role="status">原页暂不可预览。</p>:<>
      {!anchors.length&&<p className="original-evidence-note">仅定位到页面，未标记具体位置</p>}
      {anchors.some(a=>a.verification==='UNCERTAIN')&&<p className="original-evidence-note">识别文字存在差异，请以原文件为准。</p>}
      <div className="original-evidence-viewport" ref={viewport}>
        <div className="original-evidence-picture" ref={picture} style={{width:`${zoom}%`}}>
          {visibleImage&&<img src={visibleImage} alt={`原文件第${page}页`} onLoad={position}/>}
          <svg viewBox="0 0 1 1" preserveAspectRatio="none" aria-label="原页高亮区域">
            {anchors.map((a,i)=><polygon key={`${a.start}-${a.end}-${i}`} points={a.polygon.map(p=>`${p.x},${p.y}`).join(' ')} data-original-highlight/>)}
          </svg>
        </div>
      </div>
    </>}
  </section>;
}
