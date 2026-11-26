import type {SourceEvidence} from './contractAnalysisEvidenceView';

/** Whitespace differs between Office runs/cells and the frozen reader. No OCR or punctuation correction. */
const compact=(s:string)=>s.replace(/[\s\u200b\ufeff]/gu,'');
export function markOfficeEvidence(root:HTMLElement,excerpt:string,table=false):boolean{
  const needle=compact(table?excerpt.replace(/\s+\|\s+/g,''):excerpt);
  if(!needle)return false;
  const walker=root.ownerDocument.createTreeWalker(root,4);
  const nodes:{node:Text;start:number;end:number;offsets:number[]}[]=[];
  let text='',node:Node|null;
  while((node=walker.nextNode())){
    const parent=node.parentElement;
    if(!parent||parent.closest('style,script,header,footer,[data-office-decoration]'))continue;
    const value=node.textContent??'',offsets:number[]=[];
    for(let i=0;i<value.length;i++)if(compact(value[i]))offsets.push(i);
    nodes.push({node:node as Text,start:text.length,end:text.length+offsets.length,offsets});
    text+=offsets.map(i=>value[i]).join('');
  }
  const start=text.indexOf(needle);
  // Duplicate words are not a reliable position. Never highlight the first arbitrarily.
  if(start<0||text.indexOf(needle,start+1)>=0)return false;
  const end=start+needle.length;
  for(const part of nodes){
    if(part.end<=start||part.start>=end)continue;
    const from=part.offsets[Math.max(0,start-part.start)],to=part.offsets[Math.min(part.offsets.length,end-part.start)-1]+1;
    const range=root.ownerDocument.createRange();range.setStart(part.node,from);range.setEnd(part.node,to);
    const mark=root.ownerDocument.createElement('mark');mark.dataset.sourceHighlight='';
    range.surroundContents(mark);
  }
  return true;
}

export function officeSheetLocator(e:SourceEvidence):{sheet:number;row?:number;column?:number}|null{
  const match=e.sourceLocator?.match(/(?:^|\/)sheet:(\d+)(?:\/row:(\d+))?/);
  const column=e.sourceLocator?.match(/\/col:(\d+)(?:\/|$)/);
  return match?{sheet:Number(match[1]),...(match[2]?{row:Number(match[2])}:{}),...(column?{column:Number(column[1])}:{})}:null;
}

/** Fill pages that have no stored Word break hints, keeping original block/table DOM, not parsed evidence text. */
export function paginateWord(root:HTMLElement):HTMLElement[]{
  const doc=root.ownerDocument,win=doc.defaultView!;
  for(const page of Array.from(root.querySelectorAll<HTMLElement>('section.office-word'))){
    const style=win.getComputedStyle(page);
    const pageHeight=parseFloat(style.minHeight),capacity=pageHeight-parseFloat(style.paddingTop)-parseFloat(style.paddingBottom);
    if(!Number.isFinite(capacity)||capacity<200||page.scrollHeight<=pageHeight+2)continue;
    const articles=Array.from(page.querySelectorAll<HTMLElement>(':scope > article'));
    if(!articles.length)continue;
    const blocks=articles.flatMap((article,index)=>Array.from(article.children).map(block=>({articleIndex:index,block})));
    const articleTemplates=articles.map(article=>article.cloneNode(false) as HTMLElement);
    for(const article of articles)article.replaceChildren();
    let currentArticles=articles,tail=page;
    const contentHeight=()=>currentArticles.reduce((height,article)=>height+article.getBoundingClientRect().height,0);
    const nextPage=()=>{
      const next=page.cloneNode(false) as HTMLElement;
      for(const header of page.querySelectorAll(':scope > header'))next.appendChild(header.cloneNode(true));
      currentArticles=articleTemplates.map(template=>template.cloneNode(false) as HTMLElement);
      currentArticles.forEach(article=>next.appendChild(article));
      for(const footer of page.querySelectorAll(':scope > footer'))next.appendChild(footer.cloneNode(true));
      tail.after(next);tail=next;
    };
    // Keep vertically merged rows together, but do not treat the entire table
    // as one page merely because one of its cells has a rowspan.
    for(const {articleIndex,block} of blocks){
      const current=currentArticles[articleIndex];
      if(block.tagName==='TABLE'){
        const rows=Array.from((block as HTMLTableElement).rows);
        let table=block.cloneNode(false) as HTMLTableElement;
        const setup=()=>{for(const c of block.querySelectorAll(':scope > colgroup'))table.appendChild(c.cloneNode(true));currentArticles[articleIndex].appendChild(table);};
        block.remove();setup();
        for(let at=0;at<rows.length;){
          let end=at+1;
          for(let r=at;r<end;r++)for(const cell of Array.from(rows[r].cells)){
            const span=cell.getAttribute('rowspan')==='0'?rows.length-r:cell.rowSpan;
            end=Math.min(rows.length,Math.max(end,r+span));
          }
          const group=rows.slice(at,end),hasEarlierContent=currentArticles.some(article=>article.children.length>0)&&table.rows.length>0;
          group.forEach(row=>table.appendChild(row));
          if(contentHeight()>capacity+2&&hasEarlierContent){
            group.forEach(row=>row.remove());
            if(!table.rows.length)table.remove();
            nextPage();table=block.cloneNode(false) as HTMLTableElement;setup();group.forEach(row=>table.appendChild(row));
          }
          at=end;
        }
      }else{
        current.appendChild(block);
        if(contentHeight()>capacity+2&&currentArticles.some(article=>article.children.length>1||article!==current&&article.children.length>0)){
          block.remove();nextPage();currentArticles[articleIndex].appendChild(block);
        }
      }
    }
  }
  return Array.from(root.querySelectorAll<HTMLElement>('section.office-word'));
}
