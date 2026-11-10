import type {ReactNode} from "react";
import {Copy} from "lucide-react";
import {ResultSection,ResultItem} from "../../../components/results/ResultLayout";

export type AnalysisCategoryEvidence={fileId:string;start:number;end:number;excerpt:string};
export type AnalysisCategory={id:string;order:number;title:string;status:string;summary:string;
  items:{title:string;summary:string;level:string;evidence:AnalysisCategoryEvidence[]}[]};

const headings=[['QUANTITATIVE','合同条款量化一致性审查'],['DRAFT_STRUCTURE','合同草本结构对标审查'],['BREACH','违约责任条款量化审查'],['TERM','合同期限与终止条件格式审查'],['PARTY_QUALIFICATION','合同主体资格审查'],['ATTACHMENT_COMPLETENESS','附件清单完整性审查'],['EFFECTIVENESS_COPIES','合同生效条件与份数审查'],['CLAUSE_RISK','合同条款风险提示']];

/** One batch, eight stable headings; only source-backed content is displayed. */
export function ContractAnalysisCategories({categories,documents,copied,onCopy,evidenceButtons}:{
  categories:AnalysisCategory[];documents:{fileId:string;name:string}[];copied:string|null;
  onCopy:(key:string,text:string)=>void;evidenceButtons:(evidence:AnalysisCategoryEvidence[],focus?:string)=>ReactNode;
}) {
  const names=new Map(documents.map(document=>[document.fileId,document.name]));
  const modules=headings.map(([id,title],index)=>categories.find(category=>category.id===id)??{id,title,order:index+1,status:'DATA_INSUFFICIENT',summary:'材料不足，未生成',items:[]});
  return <ResultSection label="合同八大项分析" title="八大项审查结果" number="02" collapsible={false}>
    <div className="result-module-grid">{modules.map(category=><details key={category.id} data-result-module={category.id} className="result-module" open={category.status==='ATTENTION'}>
      <summary><div className="result-module-top"><span className="result-module-title"><span className="result-module-number">{String(category.order).padStart(2,'0')}</span>{category.title}</span><span className={`result-module-status${category.status==="ATTENTION"?' result-module-status-attention':''}`}>{category.status==="ATTENTION"?"需关注":category.items.length?`${category.items.length} 项内容`:"暂无内容"}</span></div><p className="result-module-preview">{category.items[0]?.summary??'材料不足，未生成'}</p><span className="result-module-toggle"/></summary>
      <div className="result-module-content space-y-3">{category.items.length===0?<p className="py-3 text-sm text-slate-500">材料不足，未生成</p>:category.items.map((item,index)=>{
        const sourceNames=Array.from(new Set(item.evidence.map(e=>names.get(e.fileId)).filter(Boolean)));
        const key=`category-${category.id}-${index}`;
        return <ResultItem key={key} title={item.title} tone={item.level==='WARNING'?'attention':'default'} label={item.level==='WARNING'?'需关注':undefined}>
          <div className="flex justify-end"><button type="button" className="inline-flex shrink-0 items-center gap-1 rounded border border-slate-200 bg-white px-2 py-1 text-xs text-slate-700" onClick={()=>onCopy(key,item.summary)}><Copy size={12}/>{copied===key?"已复制":"复制"}</button></div>
          <p className="mt-2 whitespace-pre-wrap text-sm leading-7 text-slate-700">{item.summary}</p>
          {sourceNames.length>0&&<p className="mt-2 break-words text-xs text-slate-500">涉及材料：{sourceNames.join("、")}</p>}
          <div className="mt-2 flex flex-wrap gap-3">{evidenceButtons(item.evidence,`${item.title} ${item.summary}`)}</div>
        </ResultItem>;
      })}</div>
    </details>)}</div>
  </ResultSection>;
}
