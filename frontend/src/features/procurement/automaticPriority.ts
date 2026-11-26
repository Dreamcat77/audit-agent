type Source={id:string;documentId:string;fileName:string;sha256:string;locator:string;excerpt:string};
type Finding={module:string;code:string;level:string;title:string;explanation:string;suggestion:string;evidence:Source[]};
export type PriorityFinding=Finding & {members:Finding[]};
const sourceKey=(e:Source)=>JSON.stringify([e.documentId,e.sha256,e.locator,e.excerpt]);
const titleKey=(text:string)=>text.replace(/供应商/g,'').replace(/(?:竞争性)?磋商文件|招标文件/g,'采购文件')
  .replace(/未覆盖|未包含/g,'未包含').replace(/资格条款|资格条件/g,'资格要求')
  .replace(/不一致|不符/g,'有差异').replace(/[\s，,。；;：:]/g,'');
const codeKey=(f:Finding)=>f.code==='BUDGET_CONFLICT'&&/^报价(?:超过|超出)最高限价$/.test(titleKey(f.title))?'QUOTE_CEILING'
 :f.code==='QUANTITY_CONFLICT'&&/报价范围/.test(f.title)&&!/数量|件数|台数/.test(f.title)?'QUOTE_SCOPE':f.code;
function issueKey(finding:Finding):string {
 const title=titleKey(finding.title);
 if(codeKey(finding)==='QUOTE_CEILING'&&/^报价(?:超过|超出)最高限价$/.test(title))return '报价超过最高限价';
 if(codeKey(finding)!=='QUOTE_SCOPE')return title;
 if(/^报价范围与(?:采购需求|需求范围|采购要求)有差异$/.test(title))return '报价范围与采购需求有差异';
 const missing=/^(?:响应)?报价未(?:包)?含采购文件要求的(.+?)(?:费用)?$/.exec(title)
   ?? /^报价未(?:包)?含(.+?)(?:费用)?与采购要求有差异$/.exec(title);
 return missing? `报价未包含：${missing[1]}`:title;
}

function sameCeilingWithTotal(a:Finding,b:Finding):boolean {
 if(codeKey(a)!=='QUOTE_CEILING'||codeKey(b)!=='QUOTE_CEILING')return false;
 const left=new Set(a.evidence.map(sourceKey)),right=new Set(b.evidence.map(sourceKey));
 const common=a.evidence.filter(e=>right.has(sourceKey(e)));
 // Require the same actual offer row and limit. Only an extra total from that
 // offer file is redundant; a different supplier, row or limit stays separate.
 if(common.length<2||!common.some(e=>/最高限价/.test(e.excerpt)))return false;
 const offers=common.filter(e=>!e.excerpt.includes('最高限价')&&!/^合计/.test(e.excerpt.trim()));
 const extras=[...a.evidence.filter(e=>!right.has(sourceKey(e))),...b.evidence.filter(e=>!left.has(sourceKey(e)))];
 return extras.length>0&&extras.every(e=>/^合计\s*\|/.test(e.excerpt.trim())&&offers.some(o=>
   o.documentId===e.documentId&&o.sha256===e.sha256&&
   (e.excerpt.match(/\d[\d,]*(?:\.\d+)?/g)??[]).length===1&&
   (o.excerpt.match(/\d[\d,]*(?:\.\d+)?/g)??[]).includes(e.excerpt.match(/\d[\d,]*(?:\.\d+)?/g)![0])));
}

function sameExplicitCostExclusion(a:Finding,b:Finding):boolean {
 if(codeKey(a)!=='QUOTE_SCOPE'||codeKey(b)!=='QUOTE_SCOPE'||issueKey(a)!=='报价范围与采购需求有差异')return false;
 const right=new Set(b.evidence.map(sourceKey));
 const common=a.evidence.filter(e=>right.has(sourceKey(e)));
 const response=common.find(e=>/[^|；;。]+另行收费/.test(e.excerpt));
 if(!response)return false;
 const omitted=/([^|；;。]+)另行收费/.exec(response.excerpt)?.[1].trim();
 const costs=omitted?.split(/[、，,和及与]/).map(s=>s.trim()).filter(Boolean)??[];
 if(!costs.length||costs.some(s=>s.length<2||s.length>12))return false;
 const requirements=[a,b].map(f=>f.evidence.filter(e=>!common.some(c=>sourceKey(c)===sourceKey(e))));
 if(requirements.some(refs=>!refs.length))return false;
 const all=requirements.flat();
 return new Set(all.map(e=>JSON.stringify([e.documentId,e.sha256]))).size===1&&all.every(e=>
   /报价[^。；\n]{0,12}(?:应|须|必须)[^。；\n]{0,6}(?:包含|包括)/.test(e.excerpt)&&costs.every(c=>e.excerpt.includes(c)));
}

function unchangedNoticeOnly(a:Finding,b:Finding):boolean {
 if(a.code!=='RESTRICTIVE_CONDITION'||b.code!==a.code||!/地域.*注册/.test(a.title)||!/地域.*注册/.test(b.title))return false;
 const left=new Set(a.evidence.map(sourceKey)),right=new Set(b.evidence.map(sourceKey));
 const small=left.size<=right.size?left:right,large=left.size<=right.size?right:left;
 if(!small.size||![...small].every(key=>large.has(key)))return false;
 const common=a.evidence.filter(e=>right.has(sourceKey(e)));
 if(!common.some(e=>/(?:供应商|投标人|响应人).{0,12}(?:必须|须|仅).{0,12}(?:本市|本地|本省|本区).{0,12}(?:注册|设立)/.test(e.excerpt)))return false;
 const extra=[...a.evidence,...b.evidence].filter(e=>!small.has(sourceKey(e)));
 return extra.length>0&&extra.every(e=>/(?:本公告|本通知|本更正)[^。；]{0,12}(?:仅|只)[^。；]{0,12}(?:联系人|联系方式|联系电话)[^。；]{0,16}(?:不|未)(?:调整|修改|变更)[^。；]{0,30}资格/.test(e.excerpt));
}

/** Equal source ranges, or the same restriction plus an explicitly unchanged-contact notice. */
export function mergePriorityFindings(findings:Finding[]):PriorityFinding[]{
 const rows:PriorityFinding[]=[];
 for(const finding of findings){
  const refs=[...new Set(finding.evidence.map(sourceKey))].sort().join('\n');
  const match=rows.find(row=>(row.module!==finding.module||row.code!==finding.code||(row.title===finding.title&&row.explanation===finding.explanation))&&codeKey(row)===codeKey(finding)
    &&issueKey(row)===issueKey(finding)&&refs.length>0
    &&([...new Set(row.evidence.map(sourceKey))].sort().join('\n')===refs||unchangedNoticeOnly(row,finding)
      ||sameCeilingWithTotal(row,finding)||sameExplicitCostExclusion(row,finding)));
  if(match){match.members.push(finding);match.evidence=[...new Map([...match.evidence,...finding.evidence].map(e=>[sourceKey(e),e])).values()];if(finding.level==='CRITICAL')match.level='CRITICAL';}
  else rows.push({...finding,members:[finding]});
 }
 return rows.sort((a,b)=>(a.level==='CRITICAL'?0:1)-(b.level==='CRITICAL'?0:1));
}
type Comparison={documentId:string;status:string;difference:string;sourceRow?:string;supplier?:string};
type Offer={documentId:string;supplier:string;amount:string;sourceRow?:string};
type Report={offers:Offer[];quoteComparisons?:Comparison[];ceilingComparisons?:Comparison[]};
export function comparisonForOffer(comparisons:Comparison[]|undefined,offer:Offer,offers:Offer[]):Comparison|undefined{
 const same=comparisons?.filter(c=>c.documentId===offer.documentId&&(offer.sourceRow?c.sourceRow===offer.sourceRow:true))??[];
 return same.length===1&&(offer.sourceRow||offers.filter(o=>o.documentId===offer.documentId).length===1)?same[0]:undefined;
}
/** Arithmetic is supplied by the server. Never recompute or invent comparisons in the browser. */
export function financialPriorityFindings(report:Report,details:{label:string;evidence:Source[]}[]):(Finding & {comparisonDocumentId:string})[]{
 const rows:(Finding & {comparisonDocumentId:string})[]=[];
 for(const [comparisons,status,code,label] of [
  [report.quoteComparisons,'OVER_BUDGET','QUOTE_BUDGET','预算金额'],
  [report.ceilingComparisons,'OVER_CEILING','QUOTE_CEILING','最高限价']
 ] as const){
  for(const comparison of comparisons??[]){
   if(comparison.status!==status)continue;
   const offers=report.offers.filter(o=>o.documentId===comparison.documentId&&(!comparison.sourceRow||o.sourceRow===comparison.sourceRow));
   if(offers.length!==1)continue;
   const offer=offers[0];
   const evidence=details.filter(d=>(/^报价/.test(d.label)&&d.evidence.some(e=>e.documentId===offer.documentId&&(!offer.sourceRow||e.excerpt.includes(offer.supplier))))||d.label===label)
      .flatMap(d=>d.evidence).filter((e,i,all)=>all.findIndex(other=>sourceKey(other)===sourceKey(e))===i);
   rows.push({module:'QUOTE',code,comparisonDocumentId:offer.documentId,level:'WARNING',title:`${offer.supplier}报价超过${label}`,
      explanation:`报价 ${offer.amount} 元，超过${label} ${comparison.difference} 元。`,
      suggestion:'核对报价与采购要求，按相应采购规则处理。',evidence});
  }
 }
 return rows;
}
