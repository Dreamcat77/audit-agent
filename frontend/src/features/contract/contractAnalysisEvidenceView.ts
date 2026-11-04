/** Display-only grouping. Never rewrite a frozen quote or infer a locator from keywords. */
export type SourceEvidence = {fileId:string;start:number|null;end:number|null;excerpt:string;
  pageNumber?:number|null;paragraphIndex?:number|null;segmentIndex?:number|null};
export type SourceWindow = {fileId:string;start:number;end:number;evidence:SourceEvidence[]};
export type SourceText = {text:string;start:number;totalChars:number};
export type SourcePiece = {text:string;highlight:boolean};

export function located(e:SourceEvidence):e is SourceEvidence & {start:number;end:number}{
  return Number.isSafeInteger(e.start)&&Number.isSafeInteger(e.end)&&e.start!>=0&&e.end!>e.start!;
}

export function uniqueSourceEvidence(items:readonly SourceEvidence[]):SourceEvidence[]{
  const result=new Map<string,SourceEvidence>();
  for(const e of items){
    if(!e.fileId||!e.excerpt.trim())continue;
    // Equal text at two distinct locations is not a duplicate.
    const key=JSON.stringify([e.fileId,e.start,e.end,e.excerpt,...(located(e)?[]:[e.pageNumber,e.paragraphIndex,e.segmentIndex])]);
    if(!result.has(key))result.set(key,e);
  }
  return [...result.values()];
}

export function sourceWindows(items:readonly SourceEvidence[]):SourceWindow[]{
  const unique=uniqueSourceEvidence(items);
  const order=new Map([...new Set(unique.map(e=>e.fileId))].map((id,index)=>[id,index]));
  const sorted=unique.filter(located)
    .sort((a,b)=>order.get(a.fileId)!-order.get(b.fileId)!||a.start-b.start||a.end-b.end);
  const result:SourceWindow[]=[];
  for(const e of sorted){
    const start=Math.max(0,e.start-90),end=Math.min(start+20000,e.end+90);
    const previous=result.at(-1);
    // Coalesce context as well as evidence so the same paragraph is shown once.
    if(previous?.fileId===e.fileId&&start<=previous.end&&Math.max(previous.end,end)-previous.start<=20000){
      previous.end=Math.max(previous.end,end);previous.evidence.push(e);
    }else result.push({fileId:e.fileId,start,end,evidence:[e]});
  }
  return result;
}

export function highlightSource(window:SourceWindow,source:SourceText):{pieces:SourcePiece[];unverified:number}{
  if(source.start!==window.start||!Number.isSafeInteger(source.totalChars)
      ||source.start+source.text.length>source.totalChars)return {pieces:[],unverified:window.evidence.length};
  const ranges:Array<{start:number;end:number}>=[];let unverified=0;
  for(const e of window.evidence){
    const start=e.start!-source.start,end=e.end!-source.start;
    if(start<0||end>source.text.length||source.text.slice(start,end)!==e.excerpt){unverified++;continue;}
    ranges.push({start,end});
  }
  ranges.sort((a,b)=>a.start-b.start||a.end-b.end);
  const merged:Array<{start:number;end:number}>=[];
  for(const range of ranges){
    const last=merged.at(-1);
    if(last&&range.start<=last.end)last.end=Math.max(last.end,range.end);
    else merged.push({...range});
  }
  // Never silently substitute an unverified context for the requested evidence.
  if(!merged.length)return {pieces:[],unverified};
  const pieces:SourcePiece[]=[];let cursor=0;
  for(const range of merged){
    if(range.start>cursor)pieces.push({text:source.text.slice(cursor,range.start),highlight:false});
    pieces.push({text:source.text.slice(range.start,range.end),highlight:true});cursor=range.end;
  }
  if(cursor<source.text.length)pieces.push({text:source.text.slice(cursor),highlight:false});
  return {pieces,unverified};
}
