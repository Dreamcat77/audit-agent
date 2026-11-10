import {useCallback,useState} from 'react';
import type {BudgetMaterialReference,BudgetSourceEvidence} from '../../api/types';
import {request} from '../../api/client';
import {ResultProjectHeader,ResultSection,ResultItem,ResultEmpty} from '../../components/results/ResultLayout';
import {AnalysisEvidenceDialog} from '../contract/components/AnalysisEvidenceDialog';
import type {SourceEvidence,SourceText} from '../contract/contractAnalysisEvidenceView';
import {budgetAttention,budgetDifference,budgetMoney} from './budgetDifferences';
import {writeClipboardText} from '../../utils/clipboard';

const roleLabels:Record<string,string>={projectBudgetAmount:'项目预算',declaredTotalAmount:'申报合计',approvedTotalAmount:'批复总额',ceilingAmount:'最高限价'};
type Assessment=NonNullable<BudgetMaterialReference['assessments']>[number];
export function budgetPriority(reference:BudgetMaterialReference):Assessment[]{
  const lines=reference.lines.filter(l=>!!budgetDifference(l.status)||l.status==='CONFLICT').map(l=>({code:'LINE_'+l.status,level:'WARNING',
    title:[l.subject??l.subjectCode,budgetDifference(l.status)??'材料记载不一致'].filter(Boolean).join(' · '),explanation:budgetAttention(l).headline,
    basis:l.opinion,sources:l.sources}));
  return [...lines,...(reference.assessments??[]).filter(a=>a.level==='WARNING'||a.level==='CRITICAL')]
    .filter((item,index,all)=>all.findIndex(a=>a.code===item.code&&a.title===item.title&&a.explanation===item.explanation)===index);
}
export function BudgetBatchReport({reference,fileCount,createdAt,pending=false,unavailable=false,exportReport}:{
  reference:BudgetMaterialReference;fileCount:number;createdAt?:string;pending?:boolean;unavailable?:boolean;exportReport:()=>void;
}){
  const [evidenceFocus,setEvidenceFocus]=useState('');
  const [evidence,setEvidence]=useState<BudgetSourceEvidence[]|null>(null),[copied,setCopied]=useState<string|null>(null),[copyError,setCopyError]=useState(false);
  const priority=budgetPriority(reference),assessments=reference.assessments??[],totals=reference.totals??[];
  const loadText=useCallback((fileId:string,start:number,size:number)=>request<SourceText>(`/api/v1/budget/material-uploads/${encodeURIComponent(fileId)}/source-text?start=${start}&size=${size}`),[]);
  const visualBase=useCallback((fileId:string)=>`/api/v1/budget/material-uploads/${encodeURIComponent(fileId)}`,[]);
  const copy=async(key:string,text:string)=>{try{await writeClipboardText(text);setCopied(key);setCopyError(false);}catch{setCopyError(true);}};
  const names=(sources:BudgetSourceEvidence[])=>[...new Set(sources.map(s=>s.fileName))].join('、');
  const content=(item:Assessment)=>`${item.title}\n${item.explanation}\n${item.basis}\n涉及材料：${names(item.sources)}`;
  const sourceButton=(sources:BudgetSourceEvidence[],focus='')=>sources.length>0?<button type="button" className="result-source-action" onClick={()=>{setEvidenceFocus(focus);setEvidence(sources);}}>查看原文</button>:null;
  const renderAssessment=(item:Assessment,index:number)=><ResultItem key={`${item.code}-${index}`} title={item.title} tone={item.level==='WARNING'?'attention':'default'}>
    <p>{item.explanation}</p>{item.basis&&<p className="mt-2">{item.code.startsWith('SEMANTIC_')?'建议：':'依据：'}{item.basis}</p>}
    <p className="result-issue-source">涉及材料：{names(item.sources)}</p>
    <div className="mt-3 flex items-center gap-4">{sourceButton(item.sources,`${item.title} ${item.explanation}`)}<button type="button" className="result-source-action" onClick={()=>void copy(`item-${index}-${item.code}`,content(item))}>{copied===`item-${index}-${item.code}`?'已复制':'复制'}</button></div>
  </ResultItem>;
  const amountChecks=assessments.filter(a=>a.code.startsWith('TOTAL_'));
  const procedures=assessments.filter(a=>/APPROVAL|SEQUENCE|SPLIT/.test(a.code)&&!a.code.startsWith('TOTAL_'));
  const other=assessments.filter(a=>!amountChecks.includes(a)&&!procedures.includes(a));
  const provenLines=reference.lines.length>0&&reference.lines.every(l=>l.status==='WITHIN_APPROVAL');
  return <section aria-label="预算文件结果" className="budget-results-panel result-workspace space-y-5">
    <ResultProjectHeader kind="预算审查结果" name={reference.projectName??reference.lines.find(l=>l.project)?.project??'预算材料分析'}
      metadata={<>{fileCount} 份材料{createdAt?` · ${new Date(createdAt).toLocaleString('zh-CN')}`:''}</>}
      actions={<button type="button" onClick={exportReport} className="delivery-secondary-action">导出</button>}/>
    {pending&&<p role="status" className="text-sm text-slate-600">正在整理本批预算结果…</p>}
    {unavailable&&<p role="alert" className="text-sm text-amber-700">部分结果暂时无法读取，请稍后重新打开。</p>}
    {copyError&&<p role="alert" className="text-sm text-red-700">复制未完成，请重试。</p>}
    <ResultSection title="优先关注" number="01" tone={priority.length?'attention':!unavailable&&!pending&&reference.analysisCoverage==='COMPLETE'?'clear':'default'}
      actions={priority.length>0?<button type="button" className="delivery-secondary-action" onClick={()=>void copy('all',priority.map(content).join('\n\n'))}>{copied==='all'?'已复制':'复制问题清单'}</button>:undefined}>
      {priority.length?<div className="space-y-4">{priority.map(renderAssessment)}</div>:<ResultEmpty>{pending?'正在核对…':unavailable||reference.analysisCoverage!=='COMPLETE'?'暂无有充分依据的问题项':'现有材料暂未发现明确问题'}</ResultEmpty>}
    </ResultSection>
    <ResultSection title="总体预算与金额核对" number="02" tone={amountChecks.some(a=>a.level==='WARNING')?'attention':'default'}>
      {totals.length===0&&amountChecks.length===0?<ResultEmpty/>:<>
        <div className="result-two-columns">{totals.map((t,i)=><ResultItem key={i} title={roleLabels[t.role]??'材料金额'}>
          <p className="text-lg font-semibold">￥{budgetMoney(t.amount)}</p><p>{[t.scope==='ANNUAL'?'年度金额':t.scope==='TOTAL'?'项目整体金额':'材料所列范围',t.year?`${t.year}年度`:null].filter(Boolean).join(' · ')}</p>
          <p className="result-issue-source">来源材料：{names(t.sources)}</p>{sourceButton(t.sources,`${roleLabels[t.role]} ${t.amount}`)}
        </ResultItem>)}</div><div className="mt-4 space-y-4">{amountChecks.map(renderAssessment)}</div>
      </>}
    </ResultSection>
    <ResultSection title="预算科目与批复比对" number="03" tone={reference.lines.some(l=>!!budgetDifference(l.status)||l.status==='CONFLICT')?'attention':provenLines?'clear':'default'}>
      {reference.lines.length===0?<ResultEmpty/>:<div className="result-two-columns">{reference.lines.map((line,i)=>{
        const issue=!!budgetDifference(line.status),attention=budgetAttention(line);
        return <ResultItem key={i} title={line.subject??line.subjectCode??'预算信息'} tone={issue?'attention':line.status==='WITHIN_APPROVAL'?'clear':'default'} label={attention.label}>
          <dl className="budget-amount-comparison"><div><dt>申报金额（元）</dt><dd>{line.declaredAmount==null?'—':budgetMoney(line.declaredAmount)}</dd></div><div><dt>批复金额（元）</dt><dd>{line.approvalAmount==null?'—':budgetMoney(line.approvalAmount)}</dd></div></dl>
          <p className="mt-3">{attention.headline}</p><p className="result-issue-source">来源材料：{names(line.sources)}</p>{sourceButton(line.sources,`${line.subject??''} ${attention.headline}`)}
        </ResultItem>;
      })}</div>}
    </ResultSection>
    <ResultSection title="审批与申报核对" number="04" tone={procedures.some(a=>a.level==='WARNING')?'attention':'default'}>
      {procedures.length?<div className="space-y-4">{procedures.map(renderAssessment)}</div>:<ResultEmpty/>}
    </ResultSection>
    <ResultSection title="支出用途与内容审查" number="05" tone={other.some(a=>a.level==='WARNING')?'attention':'default'}>
      {other.length?<div className="space-y-4">{other.map(renderAssessment)}</div>:<ResultEmpty/>}
    </ResultSection>
    {evidence&&<AnalysisEvidenceDialog focusText={evidenceFocus} evidence={evidence.map(s=>({fileId:s.taskId,start:s.start??null,end:s.end??null,excerpt:s.excerpt,pageNumber:s.pageNumber,sourceLocator:s.locator} satisfies SourceEvidence))}
      documents={[...new Map(evidence.map(s=>[s.taskId,{fileId:s.taskId,name:s.fileName}])).values()]} loadText={loadText} visualBase={visualBase} onClose={()=>setEvidence(null)}/>}
  </section>;
}
