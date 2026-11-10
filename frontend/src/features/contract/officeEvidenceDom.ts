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

export function officeSheetLocator(e:SourceEvidence):{sheet:number;row?:number}|null{
  const match=e.sourceLocator?.match(/(?:^|\/)sheet:(\d+)(?:\/row:(\d+))?/);
  return match?{sheet:Number(match[1]),...(match[2]?{row:Number(match[2])}:{})}:null;
}

/** Fill pages that have no stored Word break hints, keeping original block/table DOM, not parsed evidence text. */
export function paginateWord(root:HTMLElement):HTMLElement[]{
  const doc=root.ownerDocument,win=doc.defaultView!;
  for(const page of Array.from(root.querySelectorAll<HTMLElement>('section.office-word'))){
    const style=win.getComputedStyle(page);
    const capacity=parseFloat(style.minHeight)-parseFloat(style.paddingTop)-parseFloat(style.paddingBottom);
    if(!Number.isFinite(capacity)||capacity<200)continue;
    const articles=Array.from(page.querySelectorAll<HTMLElement>(':scope > article'));
    if(articles.length!==1)continue;
    const article=articles[0],blocks=Array.from(article.children);
    if(article.getBoundingClientRect().height<=capacity+2)continue;
    article.replaceChildren();
    let current=article,tail=page;
    const nextPage=()=>{
      const next=page.cloneNode(false) as HTMLElement;
      for(const header of page.querySelectorAll(':scope > header'))next.appendChild(header.cloneNode(true));
      current=article.cloneNode(false) as HTMLElement;next.appendChild(current);
      for(const footer of page.querySelectorAll(':scope > footer'))next.appendChild(footer.cloneNode(true));
      tail.after(next);tail=next;
    };
    // Expand tables to row-sized units before laying out; retain colgroups and table styles.
    for(const block of blocks){
      if(block.tagName==='TABLE'&&!(block as HTMLTableElement).querySelector('[rowspan]')){
        const rows=Array.from((block as HTMLTableElement).rows);
        let table=block.cloneNode(false) as HTMLTableElement;
        const setup=()=>{for(const c of block.querySelectorAll(':scope > colgroup'))table.appendChild(c.cloneNode(true));current.appendChild(table);};
        block.remove();setup();
        for(const row of rows){
          table.appendChild(row);
          if(current.getBoundingClientRect().height>capacity+2&&table.rows.length>1){
            row.remove();nextPage();table=block.cloneNode(false) as HTMLTableElement;setup();table.appendChild(row);
          }
        }
      }else{
        current.appendChild(block);
        if(current.getBoundingClientRect().height>capacity+2&&current.children.length>1){block.remove();nextPage();current.appendChild(block);}
      }
    }
  }
  return Array.from(root.querySelectorAll<HTMLElement>('section.office-word'));
}
