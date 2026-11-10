import {useEffect,useRef,useState} from 'react';
import {apiUrl,request} from '../../../api/client';
import type {SourceEvidence} from '../contractAnalysisEvidenceView';
import {markOfficeEvidence,officeSheetLocator,paginateWord} from '../officeEvidenceDom';
import cjkFont from '../../../assets/fonts/ui-cjk.woff2?url';

const frameHtml=`<!doctype html><html><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data: blob:; font-src 'self' data: blob:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'"></head><body></body></html>`;
const frameCss=`html,body{margin:0;background:#e8edf2;color:#172b41;font:14px Arial,"Microsoft YaHei",sans-serif}*{box-sizing:border-box}body{padding:16px;overflow-wrap:normal}section.office-word{background:#fff;margin:0 auto 16px;box-shadow:0 1px 5px #bac4d0;position:relative}section.office-word article{overflow:visible}mark[data-source-highlight]{background:#ffdf62!important;color:inherit!important;box-shadow:0 0 0 1px #edb82b}table.office-sheet{border-collapse:collapse;background:#fff;margin:0 auto;min-width:600px}table.office-sheet td,table.office-sheet th{border:1px solid #c4ced9;padding:8px 12px;min-width:95px;max-width:420px;white-space:pre-wrap;overflow-wrap:anywhere;vertical-align:top}table.office-sheet th{background:#f2f5f8;font-weight:400;color:#64748b;min-width:38px}.office-sheet-title{background:white;padding:16px;font-weight:bold;margin:0}a{color:inherit;text-decoration:none;pointer-events:none}`;
type Page={element:HTMLElement;label:string;highlighted:boolean};

/** Local rendering of the authenticated source file. No remote viewer, external resources or model call. */
export function OfficeEvidencePreview({fileId,name,url,legacyUrl,evidence,verified,onEscape}:{fileId:string;name:string;url:string;legacyUrl?:string;evidence:readonly SourceEvidence[];verified:readonly SourceEvidence[];onEscape?:()=>void}){
  const iframe=useRef<HTMLIFrameElement>(null),pages=useRef<Page[]>([]);
  const escape=useRef(onEscape);escape.current=onEscape;
  const fit=useRef<()=>void>(()=>{});
  const [ready,setReady]=useState(false),[loading,setLoading]=useState(true),[error,setError]=useState(''),[index,setIndex]=useState(0),[labels,setLabels]=useState<string[]>([]),[highlighted,setHighlighted]=useState(false);
  useEffect(()=>{const observer=new ResizeObserver(()=>fit.current());if(iframe.current)observer.observe(iframe.current);return()=>observer.disconnect();},[]);
  useEffect(()=>{
    if(!ready)return;
    const controller=new AbortController();const active=()=>!controller.signal.aborted;
    setLoading(true);setError('');setLabels([]);setIndex(0);pages.current=[];
    void(async()=>{
      let blob=new Blob();
      if(!/\.doc$/i.test(name)){
        const response=await fetch(apiUrl(url),{credentials:'include',signal:controller.signal});
        if(!response.ok)throw new Error('原文件暂时无法读取。');
        if(Number(response.headers.get('content-length'))>60*1024*1024)throw new Error('原文件较大，请下载原文件查看。');
        blob=await response.blob();if(blob.size>60*1024*1024)throw new Error('原文件较大，请下载原文件查看。');
        if(/\.(docx|xlsx)$/i.test(name)){
          const {default:JSZip}=await import('jszip');if(!active())return;
          const zip=await JSZip.loadAsync(await blob.arrayBuffer());let expanded=0,count=0;
          for(const item of Object.values(zip.files)){
            const size=(item as unknown as {_data?:{uncompressedSize?:number}})._data?.uncompressedSize??0;
            if(++count>20000||(expanded+=size)>160*1024*1024)throw new Error('原文件较大，请下载原文件查看。');
          }
        }
      }
      if(!active())return;
      const frame=iframe.current!,doc=frame.contentDocument!;
      doc.onkeydown=event=>{
        if(event.key==='Escape'){event.preventDefault();escape.current?.();}
        if(event.key==='Tab'){event.preventDefault();frame.closest('.source-evidence-panel')?.querySelector<HTMLButtonElement>('button')?.focus();}
      };
      doc.body.replaceChildren();doc.head.querySelectorAll('style').forEach(s=>s.remove());
      const style=doc.createElement('style');style.textContent=`@font-face{font-family:AuditSourceCJK;src:url("${new URL(cjkFont,location.href).href}") format("woff2");font-display:swap}`+frameCss+'body{font-family:Arial,AuditSourceCJK,sans-serif}';doc.head.appendChild(style);
      const root=doc.createElement('div');doc.body.appendChild(root);
      const quotes=evidence.filter(e=>e.fileId===fileId),checked=verified.filter(e=>e.fileId===fileId);
      let result:Page[]=[];
      if(/\.docx?$/i.test(name)){
        if(/\.doc$/i.test(name)){
          if(!legacyUrl)throw new Error('原文件暂时无法读取。');
          const html=await request<string>(legacyUrl,{signal:controller.signal});if(!active())return;
          const parsed=new DOMParser().parseFromString(html,'text/html');
          for(const css of parsed.querySelectorAll('style'))doc.head.appendChild(doc.importNode(css,true));
          const section=doc.createElement('section');section.className='office-word';section.style.cssText='width:794px;min-height:1123px;padding:72px;';
          const article=doc.createElement('article');section.appendChild(article);root.appendChild(section);
          let content:HTMLElement=parsed.body;
          while(content.children.length===1&&content.firstElementChild?.tagName==='DIV')content=content.firstElementChild as HTMLElement;
          for(const child of Array.from(content.childNodes))article.appendChild(doc.importNode(child,true));
          for(const element of root.querySelectorAll('script,iframe,object,embed,link,meta,form'))element.remove();
          for(const element of root.querySelectorAll('*'))for(const attr of Array.from(element.attributes)){
            if(/^on/i.test(attr.name)||['href','action','srcdoc'].includes(attr.name)||attr.name==='src'&&!/^data:image\/(png|jpeg|gif|bmp);base64,/i.test(attr.value))element.removeAttribute(attr.name);
          }
        }else{
        const {renderAsync,defaultOptions}=await import('docx-preview');
        if(!active())return;
        const documentStyles=doc.createElement('div');doc.head.appendChild(documentStyles);
        await renderAsync(await blob.arrayBuffer(),root,documentStyles,{className:'office-word',inWrapper:false,ignoreLastRenderedPageBreak:false,
          renderAltChunks:false,renderComments:false,useBase64URL:true,
          h:element=>{
            if(typeof element==='object'&&element&&'tagName' in element){
              const value={...element};delete value.href;delete value.target;
              for(const key of Object.keys(value))if(/^on/i.test(key))delete value[key];
              return defaultOptions.h(value);
            }
            return defaultOptions.h(element);
          }});
        }
        // Renderer clears its style container. Reinsert the isolated viewer rules last.
        doc.head.appendChild(style);
        if(!active())return;
        for(const element of root.querySelectorAll<HTMLElement>('[style]'))if(element.style.fontFamily)element.style.fontFamily+=',AuditSourceCJK';
        await Promise.all(Array.from(root.querySelectorAll('img')).map(img=>img.decode().catch(()=>{})));
        await doc.fonts.ready;if(!active())return;
        const all=paginateWord(root);
        // Match against the complete original to reject duplicated citations, then select only evidence pages.
        for(const e of checked)markOfficeEvidence(root,e.excerpt,e.excerpt.includes(' | '));
        result=all.flatMap((element,i)=>{
          const hit=!!element.querySelector('mark[data-source-highlight]');
          const hinted=quotes.some(q=>q.pageNumber===i+1);
          return hit||hinted?[{element,label:`Word 排版页 · 第 ${i+1} 页`,highlighted:hit}]:[];
        });
      }else if(/\.(xlsx?|csv)$/i.test(name)){
        const XLSX=await import('xlsx');if(!active())return;
        const book=XLSX.read(await blob.arrayBuffer(),{type:'array',cellDates:false,cellHTML:false,cellStyles:true});
        let cells=0;
        for(let s=0;s<book.SheetNames.length;s++){
          const sheet=book.Sheets[book.SheetNames[s]],ref=sheet['!ref'];if(!ref)continue;
          const range=XLSX.utils.decode_range(ref);
          if(range.e.r>100000||range.e.c>2000||(cells+=(range.e.r-range.s.r+1)*(range.e.c-range.s.c+1))>200000)throw new Error('工作表较大，请下载原文件查看。');
          const candidates=checked.filter(e=>officeSheetLocator(e)?.sheet===s+1);
          const section=doc.createElement('section'),title=doc.createElement('h3');title.className='office-sheet-title';title.textContent=book.SheetNames[s];section.appendChild(title);
          const table=doc.createElement('table');table.className='office-sheet';section.appendChild(table);
          const cols=sheet['!cols']??[],head=table.createTHead().insertRow();
          table.style.tableLayout='fixed';
          const colgroup=doc.createElement('colgroup'),numberCol=doc.createElement('col');numberCol.style.width='44px';colgroup.appendChild(numberCol);
          const columnWidths:number[]=[];
          for(let c=range.s.c;c<=range.e.c;c++){
            columnWidths.push(Math.max(100,Math.min(cols[c]?.wpx??(cols[c]?.wch?cols[c].wch*7:180),420)));
          }
          const contentWidth=columnWidths.reduce((sum,width)=>sum+width,0),tableWidth=Math.max(600,contentWidth+44);
          for(const width of columnWidths){const col=doc.createElement('col');col.style.width=`${width/contentWidth*(tableWidth-44)}px`;colgroup.appendChild(col);}
          table.prepend(colgroup);table.style.width=`${tableWidth}px`;
          head.dataset.officeDecoration='';head.appendChild(doc.createElement('th'));
          for(let c=range.s.c;c<=range.e.c;c++){const th=doc.createElement('th');th.textContent=XLSX.utils.encode_col(c);head.appendChild(th);}
          const body=table.createTBody();const merges=sheet['!merges']??[];
          for(let r=range.s.r;r<=range.e.r;r++){
            const row=body.insertRow();row.dataset.officeRow=String(r+1);const number=doc.createElement('th');number.dataset.officeDecoration='';number.textContent=String(r+1);row.appendChild(number);
            for(let c=range.s.c;c<=range.e.c;c++){
              const merge=merges.find(m=>r>=m.s.r&&r<=m.e.r&&c>=m.s.c&&c<=m.e.c);
              if(merge&&(r!==merge.s.r||c!==merge.s.c))continue;
              const td=row.insertCell(),cell=sheet[XLSX.utils.encode_cell({r,c})];td.textContent=cell?XLSX.utils.format_cell(cell):'';
              if(merge){td.rowSpan=merge.e.r-merge.s.r+1;td.colSpan=merge.e.c-merge.s.c+1;}
            }
          }
          root.appendChild(section);
          let hit=false;
          for(const e of candidates){const at=officeSheetLocator(e);const scope=at?.row?body.querySelector<HTMLElement>(`[data-office-row="${at.row}"]`):body;
            if(scope)hit=markOfficeEvidence(scope,e.excerpt,true)||hit;
          }
          if(hit||quotes.some(e=>officeSheetLocator(e)?.sheet===s+1))result.push({element:section,label:`工作表 · ${book.SheetNames[s]}`,highlighted:hit});
        }
        // Without a frozen worksheet locator, only a unique match in the entire workbook is safe.
        for(const e of checked.filter(e=>!officeSheetLocator(e)))markOfficeEvidence(root,e.excerpt,true);
        for(const [s,element] of (Array.from(root.children) as HTMLElement[]).entries()){
          if(!element.querySelector('mark[data-source-highlight]'))continue;
          const known=result.find(p=>p.element===element);
          if(known)known.highlighted=true;else result.push({element,label:`工作表 · ${book.SheetNames[s]}`,highlighted:true});
        }
      }else throw new Error('此格式暂不支持原页预览，请下载原文件查看。');
      if(!active())return;
      if(!result.length)throw new Error('原文位置暂未定位，请下载原文件查看。');
      const width=Math.max(...result.map(p=>p.element.getBoundingClientRect().width));
      fit.current=()=>{if(/\.docx?$/i.test(name))root.style.setProperty('zoom',String(Math.min(1,Math.max(.25,(frame.clientWidth-32)/width))));};
      fit.current();
      for(const element of Array.from(root.children) as HTMLElement[])element.style.display='none';
      pages.current=result;setLabels(result.map(p=>p.label));setLoading(false);show(0);
    })().catch(e=>{if(active()){
      const known=['原文件暂时无法读取。','原文件较大，请下载原文件查看。','工作表较大，请下载原文件查看。','此格式暂不支持原页预览，请下载原文件查看。','原文位置暂未定位，请下载原文件查看。'];
      setError(e instanceof Error&&known.includes(e.message)?e.message:'原页暂不可预览。');setLoading(false);
    }});
    return()=>{controller.abort();pages.current=[];fit.current=()=>{};};
  },[ready,fileId,name,url,legacyUrl,evidence,verified]);
  function show(at:number){
    pages.current.forEach((p,i)=>{p.element.style.display=i===at?'':'none';});
    const selected=pages.current[at];setIndex(at);setHighlighted(selected?.highlighted??false);
    const frame=iframe.current;if(frame?.contentWindow){frame.contentWindow.scrollTo(0,0);}
  }
  return <section className="original-evidence office-evidence" aria-label="原文件证据页">
    <div className="original-evidence-toolbar"><span>{labels[index]??'原文件证据页'}</span><div>{labels.length>1&&<>
      <button type="button" disabled={index===0} onClick={()=>show(index-1)}>上一证据页</button><span>{index+1} / {labels.length}</span>
      <button type="button" disabled={index===labels.length-1} onClick={()=>show(index+1)}>下一证据页</button></>}</div></div>
    {loading&&<p role="status">正在读取原页…</p>}
    {error?<p role="status">{error} <a href={apiUrl(url)} download className="result-source-action">下载原文件</a></p>:!loading&&!highlighted?<p className="original-evidence-note">仅定位到页面，未标记具体位置</p>:null}
    <iframe ref={iframe} srcDoc={frameHtml} sandbox="allow-same-origin" tabIndex={0} title={`${name}原文件证据页`} onLoad={()=>setReady(true)}
      className="office-evidence-frame" style={{visibility:loading||error?'hidden':'visible',height:error?0:undefined}}/>
  </section>;
}
