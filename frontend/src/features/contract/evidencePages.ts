export function evidencePages(values:readonly {anchors:readonly {pageNumber:number}[];pages:readonly number[]}[],fallback:readonly number[]):number[]{
  const valid=(p:number)=>Number.isInteger(p)&&p>0&&p<=1024;
  const anchors=values.flatMap(v=>v.anchors.map(a=>a.pageNumber)).filter(valid);
  // A proven anchor supersedes a quotation's older coarse page hint.
  const pages=anchors.length?anchors:values.flatMap(v=>v.pages).filter(valid);
  return [...new Set(pages.length?pages:fallback.filter(valid))].sort((a,b)=>a-b);
}
