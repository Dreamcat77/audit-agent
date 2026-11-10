import type {SourceEvidence} from './contractAnalysisEvidenceView.ts';

/** Display-only: each result is a contiguous slice of the frozen quotation, with matching offsets. */
export function conciseEvidence(e:SourceEvidence,focus='',limit=220):SourceEvidence{
  if(e.excerpt.length<=limit)return e;
  const text=e.excerpt,terms=new Set<string>();
  for(const part of focus.slice(0,2000).match(/[\p{Script=Han}]+|[A-Za-z0-9]+(?:[.,][0-9]+)*/gu)??[]){
    if(/[\p{Script=Han}]/u.test(part))for(let i=0;i<part.length-1;i++)terms.add(part.slice(i,i+2));
    else if(part.length>1)terms.add(part);
  }
  const spans=Array.from(text.matchAll(/[^。；;！？!?]+[。；;！？!?]?/gu)).map(m=>({start:m.index!,end:m.index!+m[0].length,text:m[0]}));
  const score=(value:string)=>[...terms].reduce((sum,t)=>sum+(value.includes(t)?/\d/.test(t)?8:1:0),0);
  const best=spans.reduce((a,b)=>score(b.text)>score(a.text)?b:a,spans[0]??{start:0,end:text.length,text});
  let start=best.start,end=best.end;
  if(end-start>limit){
    let at=start,rank=-1;
    for(let pos=start;pos<end;pos+=40){const value=score(text.slice(pos,Math.min(end,pos+Math.max(1,limit-60))));if(value>rank){rank=value;at=pos;}}
    start=Math.max(best.start,at-30);end=Math.min(best.end,start+limit);
    if(start>0&&/[\uDC00-\uDFFF]/.test(text[start]))start++;
    if(end<text.length&&/[\uDC00-\uDFFF]/.test(text[end]))end--;
  }else{
    const next=spans.find(s=>s.start===end);
    if(next&&/^\s*(但|除|不含|其中|上述|以.*为准)/.test(next.text)&&next.end-start<=limit)end=next.end;
  }
  while(start<end&&/\s/.test(text[start]))start++;
  while(end>start&&/\s/.test(text[end-1]))end--;
  const bound=Number.isSafeInteger(e.start)&&Number.isSafeInteger(e.end)&&e.end! - e.start! ===text.length;
  return {...e,excerpt:text.slice(start,end),start:bound?e.start!+start:null,end:bound?e.start!+end:null};
}
