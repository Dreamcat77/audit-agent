import {useEffect,useMemo,useRef,useState} from "react";
import {CheckCircle2,Loader2,ShieldCheck} from "lucide-react";
import {request} from "../../../api/client";
import {httpProjectDataSource} from "../../project/data/httpProjectDataSource";
import {projectMaterialTabs} from "../../project/projectMaterialPresentation";
import type {ProjectContract,ProjectDetail} from "../../project/types";
import type {Contract} from "../types";
import {HttpContractDataSource} from "../data/httpContractDataSource";
import type {ProjectBackedReviewMaterialTab} from "../workspaceTypes";
import {useProjectContractReviewRun} from "../useProjectContractReviewRun";
import {createEightCategoryReviewProjection} from "../eightCategoryReviewProjection";
import {EightCategoryReviewPanel} from "./EightCategoryReviewPanel";
import {ContractFindingAcknowledgement} from "../closeout/ContractFindingAcknowledgement";
import type {ContractReviewCategoryEvidenceView} from "../eightCategoryReviewProjection";
import {formalContractsForAnalysis,formalReviewSelectionForAnalysis,reviewRunMatchesAnalysisBatch,confirmableFindingsForAnalysis} from "../contractAnalysisFormalReviewScope";

const contracts=new HttpContractDataSource();
type EvidenceView=ContractReviewCategoryEvidenceView;

export default function ContractAnalysisFormalReviews({projectId,sourceFileIds,actorId,onEvidenceOpen}:{
  projectId:string;sourceFileIds:readonly string[];actorId:string;
  onEvidenceOpen:(evidence:EvidenceView)=>void;
}){
  const [detail,setDetail]=useState<ProjectDetail|null>(null);
  const [formal,setFormal]=useState<Array<{summary:ProjectContract;contract:Contract}>>([]);
  const [loading,setLoading]=useState(true);const [error,setError]=useState<string|null>(null);
  useEffect(()=>{let active=true;setLoading(true);setError(null);setDetail(null);setFormal([]);
    void httpProjectDataSource.getProject(projectId).then(async value=>{
      if(!active)return;setDetail(value);
      const associated=formalContractsForAnalysis(value,sourceFileIds);
      const loaded=await Promise.all(associated.map(async summary=>({summary,contract:await contracts.getContract(summary.contractId)})));
      if(active)setFormal(loaded);
    }).catch(cause=>{if(active)setError(cause instanceof Error?cause.message:"正式审核信息暂时无法读取");})
      .finally(()=>{if(active)setLoading(false);});
    return()=>{active=false;};
  },[projectId,sourceFileIds.join("|")]);
  const materials=useMemo(()=>detail?projectMaterialTabs(detail.files,projectId).map(tab=>({
    ...tab,files:tab.files.map(file=>({...file,originalUrl:httpProjectDataSource.originalContentUrl(projectId,file.id)})),
  })) as ProjectBackedReviewMaterialTab[]:[],[detail,projectId]);
  if(loading)return null;
  if(error)return <p role="alert" className="text-sm text-rose-800">正式审核记录暂时无法读取，请稍后重试。</p>;
  if(formal.length===0)return null;
  return <>
    <details aria-label="正式合同审核" className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm">
    <summary className="flex cursor-pointer flex-wrap items-center justify-between gap-3 border-b border-slate-200 bg-slate-50 px-5 py-4">
      <div className="flex min-w-0 items-start gap-3"><span className="mt-0.5 rounded-md bg-emerald-100 p-2 text-emerald-800"><ShieldCheck size={18}/></span>
        <div><h3 className="text-base font-semibold text-slate-900">正式审核记录与问题确认</h3></div>
      </div>
      {formal.length>0&&<span className="text-sm text-slate-600">{formal.length} 份正式合同</span>}
    </summary>
    <div className="space-y-4 p-4 sm:p-5">
      {formal.map(({summary,contract})=><FormalRun key={summary.contractId} projectId={projectId}
        sourceFileIds={sourceFileIds} actorId={actorId} summary={summary} contract={contract} materials={materials} onEvidenceOpen={onEvidenceOpen}/>)}
    </div>
    </details>
  </>;
}

function FormalRun({projectId,sourceFileIds,actorId,summary,contract,materials,onEvidenceOpen}:{
  projectId:string;sourceFileIds:readonly string[];actorId:string;summary:ProjectContract;contract:Contract;
  materials:readonly ProjectBackedReviewMaterialTab[];onEvidenceOpen:(evidence:EvidenceView)=>void;
}){
  const source=useMemo(()=>new Set(sourceFileIds),[sourceFileIds]);
  const selection=useMemo(()=>formalReviewSelectionForAnalysis(materials,projectId,sourceFileIds),[materials,projectId,sourceFileIds]);
  const review=useProjectContractReviewRun({enabled:Boolean(actorId),actorId,projectId,contractId:summary.contractId,
    expectedContractVersion:summary.versionNo,selectedFileIds:selection.selectedFileIds});
  const runMatchesBatch=review.run?reviewRunMatchesAnalysisBatch(review.run,selection.selectedFileIds):false;
  const started=useRef(false);const [expanded,setExpanded]=useState(true);
  useEffect(()=>{
    if(!actorId||review.restoring||review.executing||!selection.canStart||started.current)return;
    started.current=true;
    if(review.run){if(runMatchesBatch&&review.presentation?.canContinue)void review.continuePending();return;}
    void review.start();
  },[actorId,review.restoring,review.executing,review.run,review.presentation?.canContinue,review.continuePending,review.start,runMatchesBatch,selection.canStart]);
  const names=useMemo(()=>new Map(materials.flatMap(tab=>tab.files).map(file=>[file.id,file.fileName])),[materials]);
  const categories=useMemo(()=>review.run&&runMatchesBatch?createEightCategoryReviewProjection(review.run,names):null,[review.run,runMatchesBatch,names]);
  const closeout=review.run&&runMatchesBatch?{actorId,projectId,contractId:summary.contractId,runId:review.run.runId}:null;
  const confirmable=review.run&&runMatchesBatch?confirmableFindingsForAnalysis(review.run):[];
  const title=contract.contractName||summary.contractName||"正式合同";
  return <article className="overflow-hidden rounded-lg border border-slate-200">
    <button type="button" aria-expanded={expanded} onClick={()=>setExpanded(value=>!value)} className="flex w-full flex-wrap items-center justify-between gap-3 bg-white px-4 py-3 text-left hover:bg-slate-50">
      <span className="min-w-0"><strong className="block break-words text-sm text-slate-900">{title}</strong><span className="mt-1 block text-xs text-slate-600">{contract.contractNo} · {contract.partyB} · ￥{Number(contract.contractAmount).toLocaleString("zh-CN",{minimumFractionDigits:2,maximumFractionDigits:2})}</span></span>
      <span className="shrink-0 text-sm font-medium text-emerald-800">{review.run?review.presentation?.statusLabel??"已建立正式审核":review.restoring?"正在恢复审核":review.executing?"正在审核":"准备审核"}</span>
    </button>
    {expanded&&<div className="space-y-4 border-t border-slate-200 bg-slate-50/60 p-3 sm:p-4">
      <dl className="grid gap-2 rounded-lg border border-slate-200 bg-white p-3 text-sm sm:grid-cols-2">
        {[["合同编号",contract.contractNo],["合同金额",`￥${Number(contract.contractAmount).toLocaleString("zh-CN",{minimumFractionDigits:2,maximumFractionDigits:2})}`],["供应商",contract.partyB]]
          .filter((item):item is [string,string]=>Boolean(item[1])).map(([label,value])=><div key={label} className="min-w-0"><dt className="text-xs text-slate-500">{label}</dt><dd className="mt-1 break-words font-medium text-slate-800">{value}</dd></div>)}
      </dl>
      {!selection.canStart&&<p className="rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm leading-6 text-amber-900">正式审核材料尚未满足条件：{selection.blockingMessage??"系统尚未确认本批材料均可用于正式审核。"}</p>}
      {review.error&&<p role="alert" className="rounded-lg border border-rose-200 bg-rose-50 p-3 text-sm text-rose-800">正式审核暂未完成：{review.error}</p>}
      {review.run&&!runMatchesBatch&&<p role="status" className="rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm leading-6 text-amber-900">系统找到该合同的正式审核记录，但其中材料与当前分析批次不同。为避免混用其他批次结论，本页暂不显示该记录的八大项或确认项。</p>}
      {!review.run&&(review.restoring||review.executing)&&<p role="status" className="py-4 text-center text-sm text-slate-600"><Loader2 size={16} className="mr-2 inline animate-spin"/>正在创建并执行正式审核…</p>}
      {review.run&&runMatchesBatch&&categories&&<>
        {review.run.status==="COMPLETED"?<p className="flex items-center gap-2 rounded-lg border border-emerald-200 bg-emerald-50 px-3 py-2 text-sm text-emerald-900"><CheckCircle2 size={16}/>正式审核已完成，以下为八大项正式结果。</p>:<p className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-sm leading-6 text-amber-900">正式审核当前状态：{review.presentation?.statusLabel??review.run.status}。已完成的项目照常展示，未完成项目会单独标明。</p>}
        <EightCategoryReviewPanel categories={categories} runId={review.run.runId} runStatus={review.run.status}
          onEvidenceOpen={evidence=>{if(source.has(evidence.fileId))onEvidenceOpen(evidence);}}/>
        {closeout&&confirmable.length>0&&<details className="rounded-lg border border-slate-200 bg-white">
          <summary className="cursor-pointer px-3 py-2 text-sm font-medium text-slate-800">问题查看确认</summary>
          <div className="grid max-h-[36rem] gap-3 overflow-y-auto border-t border-slate-200 p-3 md:grid-cols-2">{confirmable.map(finding=><section key={finding.findingId} className="rounded-md border border-slate-200 p-3"><h4 className="text-sm font-medium text-slate-900">{{QUANTITATIVE:"金额核对",STRUCTURE:"合同结构",BREACH:"违约责任",TERM:"期限与终止",QUALIFICATION:"主体资格",ATTACHMENT:"附件完整性",EFFECTIVENESS:"生效与份数",OTHER:"其他条款"}[finding.category]??"审查提示"}</h4><p className="mt-1 text-sm leading-6 text-slate-700">{finding.summary}</p><ContractFindingAcknowledgement context={closeout} findingId={finding.findingId} findingVersionNo={finding.versionNo}/></section>)}</div>
        </details>}
      </>}
    </div>}
  </article>;
}
