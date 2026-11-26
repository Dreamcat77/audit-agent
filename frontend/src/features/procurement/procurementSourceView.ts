import type {SourceEvidence,SourceText} from '../contract/contractAnalysisEvidenceView.ts';

export type ProcurementCitation={documentId:string;fileName:string;sha256:string;locator:string;excerpt:string};
export type ProcurementSource={fileName:string;sha256:string;segments:readonly {locator:string;text:string}[]};
export type IndexedProcurementSource={source:ProcurementSource;text:string;segments:{locator:string;text:string;start:number}[]};

/** Offsets refer only to the exact stored parse, never a new OCR or a keyword match. */
export function indexProcurementSource(source:ProcurementSource):IndexedProcurementSource{
  let start=0;
  const segments=source.segments.map(segment=>{
    const item={...segment,start};start+=segment.text.length+1;return item;
  });
  return {source,segments,text:source.segments.map(s=>s.text).join('\n')};
}

export function locateProcurementCitation(citation:ProcurementCitation,index?:IndexedProcurementSource):SourceEvidence{
  const page=citation.locator.match(/(?:^|\/)page:(\d+)(?:\/|$)/);
  const fallback:SourceEvidence={fileId:citation.documentId,start:null,end:null,excerpt:citation.excerpt,sourceLocator:citation.locator,
    ...(page?{pageNumber:Number(page[1])}:{})};
  if(!index||index.source.sha256!==citation.sha256||!citation.excerpt.trim())return fallback;
  const matches:number[]=[];
  // The same words on a different page must not be presented as this citation.
  for(const segment of index.segments.filter(s=>s.locator===citation.locator)){
    let offset=segment.text.indexOf(citation.excerpt);
    while(offset>=0){matches.push(segment.start+offset);if(matches.length>1)return fallback;offset=segment.text.indexOf(citation.excerpt,offset+1);}
  }
  return matches.length===1?{...fallback,start:matches[0],end:matches[0]+citation.excerpt.length}:fallback;
}

export function procurementSourceText(index:IndexedProcurementSource,start:number,size:number):SourceText{
  if(!Number.isSafeInteger(start)||start<0||start>index.text.length||!Number.isSafeInteger(size)||size<1||size>20000)
    throw new Error('Invalid source window');
  return {text:index.text.slice(start,start+size),start,totalChars:index.text.length};
}
