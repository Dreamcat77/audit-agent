import {useEffect,useRef,useState} from 'react';
import {apiUrl,request} from '../../../api/client';
import '../../../styles/source-evidence-dialog.css';
import {paginateWord} from '../officeEvidenceDom';
import {isPreviewResourceError,previewResourceMessage} from '../previewResourceError';
import {RotateCw} from 'lucide-react';

type SheetPage={sheet:string;page:number;total:number;rows:string[][]};
type Preview={kind:'pdf-pages'|'image-pages';url:string;page:number;total:number}|{kind:'pdf'|'image';url:string}|{kind:'sheets';pages:SheetPage[]}|{kind:'legacy'|'docx'}|{kind:'download'};

const safeLegacyHtml=(html:string)=>{
  const parsed=new DOMParser().parseFromString(html,'text/html');
  for(const element of parsed.querySelectorAll('script,iframe,object,embed,link,meta,form'))element.remove();
  for(const element of parsed.querySelectorAll('*'))for(const attr of Array.from(element.attributes)){
    if(/^on/i.test(attr.name)||['href','action','srcdoc'].includes(attr.name)||attr.name==='src'&&!/^data:image\/(png|jpeg|gif|bmp);base64,/i.test(attr.value))element.removeAttribute(attr.name);
  }
  return parsed.body.innerHTML;
};

export function OriginalFileDialog({name,url,legacyUrl,pageBase,onClose}:{name:string;url:string;legacyUrl?:string;pageBase?:string;onClose:()=>void}){
  const root=useRef<HTMLDivElement>(null),styles=useRef<HTMLDivElement>(null),activeUrl=useRef<string|null>(null);
  const [preview,setPreview]=useState<Preview|null>(null),[loading,setLoading]=useState(true),[error,setError]=useState(''),[resourceError,setResourceError]=useState(false),[pageBusy,setPageBusy]=useState(false),[pageIndex,setPageIndex]=useState(0),[rotation,setRotation]=useState(0);
  useEffect(()=>{
    let active=true;const controller=new AbortController();
    setPreview(null);setLoading(true);setError('');setResourceError(false);setPageIndex(0);setRotation(0);
    void(async()=>{
      const loadPage=async(page:number)=>{
        if(!pageBase)return null;
        const imageResponse=await fetch(apiUrl(`${pageBase}/pages/${page}/image`),{credentials:'include',cache:'no-store',signal:controller.signal});
        if(!imageResponse.ok||!(imageResponse.headers.get('content-type')??'').startsWith('image/'))return null;
        const imageBlob=await imageResponse.blob();const objectUrl=URL.createObjectURL(imageBlob);activeUrl.current=objectUrl;
        return {url:objectUrl,total:Number(imageResponse.headers.get('x-page-count')??page),page};
      };
      if(pageBase&&(/\.pdf$/i.test(name)||/\.(tiff?|gif)$/i.test(name))){
        const first=await loadPage(1);
        if(first){if(active)setPreview({kind:/\.pdf$/i.test(name)?'pdf-pages':'image-pages',...first});return;}
        throw new Error('原文件页暂时无法读取，请下载原文件查看。');
      }
      const response=await fetch(apiUrl(url),{credentials:'include',cache:'no-store',signal:controller.signal});
      if(!response.ok)throw new Error('原文件暂时无法读取。');
      const length=Number(response.headers.get('content-length')??0);
      if(length>120*1024*1024){if(active)setPreview({kind:'download'});return;}
      const blob=await response.blob();
      if(!active)return;
      if(blob.size>120*1024*1024){setPreview({kind:'download'});return;}
      if(/\.(docx|xlsx)$/i.test(name)){
        const {default:JSZip}=await import('jszip');
        const archive=await JSZip.loadAsync(await blob.arrayBuffer());let expanded=0,count=0;
        for(const item of Object.values(archive.files)){
          const size=(item as unknown as {_data?:{uncompressedSize?:number}})._data?.uncompressedSize??0;
          if(++count>20000||(expanded+=size)>160*1024*1024)throw new Error('文件内容较大，请下载原文件查看。');
        }
      }
      if(!active)return;
      if(/\.pdf$/i.test(name)||/^application\/pdf$/i.test(blob.type)){
        activeUrl.current=URL.createObjectURL(blob);setPreview({kind:'pdf',url:activeUrl.current});return;
      }
      if(/\.(png|jpe?g|gif|bmp|webp)$/i.test(name)){
        activeUrl.current=URL.createObjectURL(blob);setPreview({kind:'image',url:activeUrl.current});return;
      }
      if(/\.docx$/i.test(name)){
        const {renderAsync,defaultOptions}=await import('docx-preview');
        if(!active)return;
        setPreview({kind:'docx'});
        await renderAsync(await blob.arrayBuffer(),root.current!,styles.current!,{className:'office-word',inWrapper:false,
          ignoreLastRenderedPageBreak:false,renderAltChunks:false,renderComments:false,useBase64URL:true,
          h:element=>{
            if(typeof element==='object'&&element&&'tagName' in element){
              const value={...element};delete value.href;delete value.target;
              for(const key of Object.keys(value))if(/^on/i.test(key))delete value[key];
              return defaultOptions.h(value);
            }
            return defaultOptions.h(element);
          }});
        await document.fonts.ready;
        await Promise.all(Array.from(root.current!.querySelectorAll('img')).map(image=>image.decode().catch(()=>{})));
        paginateWord(root.current!);
        for(const page of root.current!.querySelectorAll<HTMLElement>('section.office-word'))page.classList.add('original-file-word');
        return;
      }
      if(/\.(xlsx?|csv)$/i.test(name)){
        const XLSX=await import('xlsx');
        const book=XLSX.read(await blob.arrayBuffer(),{type:'array',cellDates:false,cellHTML:false});
        let cells=0;
        const pages=book.SheetNames.flatMap(sheetName=>{
          const sheet=book.Sheets[sheetName],range=sheet['!ref']?XLSX.utils.decode_range(sheet['!ref']):{s:{r:0,c:0},e:{r:-1,c:-1}};
          if(range.e.r>100000||range.e.c>2000||(cells+=(range.e.r-range.s.r+1)*(range.e.c-range.s.c+1))>200000)throw new Error('文件内容较大，请下载原文件查看。');
          const rows=sheet['!ref']?XLSX.utils.sheet_to_json<string[]>(sheet,{header:1,raw:false,defval:''}):[];
          const size=40,total=Math.max(1,Math.ceil(rows.length/size));
          return Array.from({length:total},(_,page)=>({sheet:sheetName,page:page+1,total,rows:rows.slice(page*size,(page+1)*size)}));
        });
        if(active)setPreview({kind:'sheets',pages});
        return;
      }
      if(/\.doc$/i.test(name)&&legacyUrl){
        const html=await request<string>(legacyUrl,{signal:controller.signal});
        if(!active)return;
        const parsed=new DOMParser().parseFromString(safeLegacyHtml(html),'text/html');
        const section=document.createElement('section');section.className='office-word original-file-word';section.style.cssText='width:794px;min-height:1123px;padding:72px;';
        const article=document.createElement('article');section.appendChild(article);root.current?.replaceChildren(section);
        let content=parsed.body;while(content.children.length===1&&content.firstElementChild?.tagName==='DIV')content=content.firstElementChild as HTMLElement;
        for(const child of Array.from(content.childNodes))article.appendChild(document.importNode(child,true));
        paginateWord(root.current!);setPreview({kind:'legacy'});
        return;
      }
      if(active)setPreview({kind:'download'});
    })().catch(cause=>{
      if(active&&!controller.signal.aborted){
        const resource=isPreviewResourceError(cause);setResourceError(resource);
        console.warn('Original file preview failed',{failureType:cause instanceof Error?cause.name:'UnknownError',moduleResource:resource});
        setError(resource?previewResourceMessage:cause instanceof Error?cause.message:'原文件暂时无法预览。');
      }
    }).finally(()=>{if(active)setLoading(false);});
    return()=>{active=false;controller.abort();root.current?.replaceChildren();if(activeUrl.current)URL.revokeObjectURL(activeUrl.current);activeUrl.current=null;};
  },[name,url,legacyUrl,pageBase]);

  async function changePage(page:number){
    if(!preview||!(preview.kind==='pdf-pages'||preview.kind==='image-pages')||page<1||page>preview.total||page===preview.page||!pageBase)return;
    setPageBusy(true);
    try{const response=await fetch(apiUrl(`${pageBase}/pages/${page}/image`),{credentials:'include',cache:'no-store'});if(!response.ok)throw new Error('原文件页暂时无法读取。');const nextUrl=URL.createObjectURL(await response.blob());const previous=preview.url;activeUrl.current=nextUrl;setPreview({...preview,page,url:nextUrl});URL.revokeObjectURL(previous);}catch(cause){setError(cause instanceof Error?cause.message:'原文件页暂时无法读取。');}finally{setPageBusy(false);}
  }

  return <div role="dialog" aria-modal="true" aria-label="原文件预览" className="source-evidence-overlay" onKeyDown={event=>{if(event.key==='Escape'){event.preventDefault();onClose();}}}>
    <section className="source-evidence-panel original-file-panel">
      <header className="source-evidence-header"><h2 className="min-w-0 break-words font-semibold">{name}</h2><button type="button" className="delivery-secondary-action shrink-0" onClick={onClose}>关闭</button></header>
      <div className="source-evidence-body original-file-body">
        {loading&&<p role="status" className="py-8 text-center text-sm text-slate-600">正在打开原文件…</p>}
        {!loading&&error&&<div role="alert" className="py-8 text-center text-sm text-slate-600"><p>{error}</p>{resourceError&&<button type="button" className="result-source-action mt-3 mr-3" onClick={()=>window.location.reload()}>刷新页面</button>}<a href={apiUrl(url)} download className="result-source-action mt-3 inline-flex">下载原文件</a></div>}
        {!loading&&!error&&(preview?.kind==='pdf-pages'||preview?.kind==='image-pages')&&<section className="original-file-paged-image"><div className="original-file-page-toolbar"><span>第 {preview.page} 页 / 共 {preview.total} 页</span><div><button type="button" disabled={pageBusy||preview.page<=1} onClick={()=>void changePage(preview.page-1)}>上一页</button><button type="button" disabled={pageBusy||preview.page>=preview.total} onClick={()=>void changePage(preview.page+1)}>下一页</button><button type="button" title="顺时针旋转90度" aria-label="顺时针旋转90度" disabled={pageBusy} onClick={()=>setRotation(value=>(value+90)%360)}><RotateCw size={15}/></button></div></div><div className="original-file-page-viewport" data-page-rotation={rotation}><img src={preview.url} alt={`${name}第${preview.page}页`} style={{transform:`rotate(${rotation}deg)`}} /></div></section>}
        {!loading&&!error&&preview?.kind==='pdf'&&<iframe title={`${name}原文件`} src={preview.url} className="original-file-frame"/>}
        {!loading&&!error&&preview?.kind==='image'&&<div className="original-file-image-wrap"><img src={preview.url} alt={name} className="original-file-image"/></div>}
        <div ref={styles} className="original-file-docx-styles" aria-hidden="true"/>
        <div ref={root} className="original-file-docx" style={{visibility:!loading&&!error&&(preview?.kind==='docx'||preview?.kind==='legacy')?'visible':'hidden'}}/>
        {!loading&&!error&&preview?.kind==='sheets'&&<section className="original-file-sheet"><div className="original-file-page-toolbar"><span>{preview.pages[pageIndex]?.sheet??'工作表'} · 第 {preview.pages[pageIndex]?.page??0} 页 / 共 {preview.pages[pageIndex]?.total??0} 页</span><div><button type="button" disabled={pageBusy||pageIndex<=0} onClick={()=>setPageIndex(index=>Math.max(0,index-1))}>上一页</button><button type="button" disabled={pageBusy||pageIndex>=preview.pages.length-1} onClick={()=>setPageIndex(index=>Math.min(preview.pages.length-1,index+1))}>下一页</button></div></div><div><table><tbody>{(preview.pages[pageIndex]?.rows??[]).map((row,index)=><tr key={index}>{row.map((cell,column)=><td key={column}>{cell}</td>)}</tr>)}</tbody></table></div></section>}
        {!loading&&!error&&preview?.kind==='download'&&<div className="py-8 text-center text-sm text-slate-600"><p>此文件较大或暂不支持弹窗预览。</p><a href={apiUrl(url)} download className="result-source-action mt-3 inline-flex">下载原文件</a></div>}
      </div>
    </section>
  </div>;
}
