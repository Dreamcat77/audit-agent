import {useCallback,useEffect,useMemo,useRef,useState} from "react";
import {AnalysisEvidenceDialog} from "./AnalysisEvidenceDialog";
import {uniqueSourceEvidence,type SourceEvidence,type SourceText} from "../contractAnalysisEvidenceView";
import {ResultProjectHeader,ResultSection,ResultItem,ResultEmpty} from "../../../components/results/ResultLayout";
import {ProcessingStatusCard} from "../../../components/status/ProcessingStatusCard";
import {ArrowLeft,Check,Copy,Edit3,History,Loader2,UploadCloud,X} from "lucide-react";
import {ApiError,request} from "../../../api/client";
import {writeClipboardText} from "../../../utils/clipboard";
import {userFacingError} from "../../../api/userFacingError";
import {useAuth} from "../../../auth/AuthProvider";
import {httpProjectDataSource} from "../../project/data/httpProjectDataSource";
import {createOperationKey} from "../../../api/operationKey";
import {ContractAnalysisCategories,type AnalysisCategory} from "./ContractAnalysisCategories";
import ContractAnalysisFormalReviews from "./ContractAnalysisFormalReviews";
import {createContractMaterialIntakeOperation} from "../contractMaterialIntakeOperation";
import {analysisBusy,contractCheckLabel,factLabels,topicLabels,partitionAnalysisFindings,showAnalysisIntakeNotice,findingSourceNames,findingCopyText,aggregateMaterialHighlights,type AnalysisState} from "../contractAnalysisPresentation";

type Evidence={fileId:string;start:number;end:number;excerpt:string};
type Fact={type:string;value:string;evidence:Evidence[]};
type Clause={topic:string;summary:string;evidence:Evidence[]};
type Finding={code:string;level:string;title:string;explanation:string;suggestion:string;evidence:Evidence[];origin:string};
type Check={code:string;status:string;explanation:string;evidence:Evidence[]};
type Summary={id:string;status:AnalysisState;statusLabel:string;headline:string;files:number;risks:number;createdAt:string;projectId?:string;projectName?:string|null};
type PendingProjectNameUpdate={idempotencyKey:string;expectedVersion:number;name:string;projectNo:string|null;category:string|null};
type Document={fileId:string;name:string;status:string;role:string;profile:string;facts:Fact[];clauses:Clause[];checks:Check[];findings:Finding[];sourceChars:number;reviewedChars:number;fullyReviewed:boolean};
type Report={id:string;status:AnalysisState;statusLabel:string;headline:string;explanation:string;createdAt:string;projectId?:string;projectName?:string;documents:Document[];findings:Finding[];checks:Check[];limitations:string[];comparisons?:Clause[];categories?:AnalysisCategory[]};
const base="/api/v1/contract-analysis-cases";

/** Independent upload-only flow. Existing formal contract pages remain accessible separately. */
export function ContractAnalysisWorkspace({onOpenLegacy}:{onOpenLegacy:()=>void}){
  const {user}=useAuth();
  void onOpenLegacy;
  return <ContractAnalysisSession key={user?.userId??"anonymous"} actorId={user?.userId}/>;
}
function ContractAnalysisSession({actorId}:{actorId?:string}){
  const [rows,setRows]=useState<Summary[]>([]);const [page,setPage]=useState(1);const [loadingHistory,setLoadingHistory]=useState(true);
  const [projectKeyword,setProjectKeyword]=useState("");const [createdDate,setCreatedDate]=useState("");
  const [appliedKeyword,setAppliedKeyword]=useState("");const [appliedDate,setAppliedDate]=useState("");
  const [editingProjectId,setEditingProjectId]=useState<string|null>(null);const [editingName,setEditingName]=useState("");const [savingProject,setSavingProject]=useState(false);
  const [selected,setSelected]=useState<string|null>(null);const [report,setReport]=useState<Report|null>(null);
  const [selectedProjectId,setSelectedProjectId]=useState<string|null>(null);
  const [busy,setBusy]=useState(false);const [notice,setNotice]=useState<string|null>(null);const [error,setError]=useState<string|null>(null);
  const [copied,setCopied]=useState<string|null>(null);const [source,setSource]=useState<{id:number;caseId:string;items:SourceEvidence[]}|null>(null);
  const [pendingFiles,setPendingFiles]=useState(false);const alive=useRef(true);const copySequence=useRef(0);const sourceSequence=useRef(0);
  const projectNameOperationKey=(projectId:string)=>`audit-agent:contract-analysis:project-name:${actorId??"anonymous"}:${projectId}`;
  const readProjectNameOperation=(projectId:string):PendingProjectNameUpdate|null=>{
    try{const raw=window.sessionStorage.getItem(projectNameOperationKey(projectId));if(!raw)return null;const value=JSON.parse(raw) as PendingProjectNameUpdate;
      return typeof value.idempotencyKey==="string"&&typeof value.expectedVersion==="number"&&typeof value.name==="string"?value:null;
    }catch{return null;}
  };
  const operation=useMemo(()=>createContractMaterialIntakeOperation({dataSource:httpProjectDataSource,automaticAnalysis:true,
    ...(actorId?{retained:{actorId,storage:window.localStorage}}:{})}),[actorId]);
  useEffect(()=>{alive.current=true;return()=>{alive.current=false;};},[]);
  async function openProject(projectId:string){
    try {
      const item=await request<{id:string}>(`${base}/from-project/${encodeURIComponent(projectId)}`);
      if(alive.current){setSelected(item.id);setSelectedProjectId(projectId);setReport(null);setSource(null);}
    } catch(cause) {
      if(cause instanceof ApiError&&cause.status===404&&alive.current){
        setNotice("这条记录来自此前的处理流程，当前自动分析页暂不能重新打开；新上传的材料会按现在的自动分析流程处理。");
        return;
      }
      throw cause;
    }
  }
  useEffect(()=>{let current=true;void operation.restore().then(async result=>{
    if(!current||!result)return;
    if(result.kind==="COMPLETED")await openProject(result.projectId);
    else{setNotice("上次上传尚有文件未完成接收，请选择原文件继续；已接收材料和原操作编号均已保留。");setPendingFiles(true);}
  }).catch(cause=>{if(current)setError(userFacingError(cause,"暂时无法读取上次上传，请稍后重试。"));});return()=>{current=false;};},[operation]);
  useEffect(()=>{let current=true;let timer:ReturnType<typeof setTimeout>;if(!selected)setLoadingHistory(true);
    const load=async()=>{try{
      if(selected){const [result,identity]=await Promise.all([request<Report>(`${base}/${selected}/report`),request<{projectId:string}>(`${base}/${selected}`)]);if(!current)return;setReport(result);setSelectedProjectId(result.projectId??identity.projectId);setError(null);if(analysisBusy(result.status))timer=setTimeout(()=>void load(),3000);}
      else{const params:{page:string;size:string;projectName?:string;createdDate?:string;timezoneOffsetMinutes?:string}={page:String(page),size:"15"};if(appliedKeyword)params.projectName=appliedKeyword;if(appliedDate){params.createdDate=appliedDate;params.timezoneOffsetMinutes=String(new Date().getTimezoneOffset());}const result=await request<Summary[]>(base,{params});if(!current)return;setRows(result);setLoadingHistory(false);setError(null);if(result.some(r=>analysisBusy(r.status)))timer=setTimeout(()=>void load(),5000);}
    }catch(cause){if(current){setLoadingHistory(false);
      // Retry transient reads silently; retain actionable client/auth errors.
      if(selected&&cause instanceof ApiError&&cause.status<500)setError(userFacingError(cause,"当前分析记录无法打开，请返回记录列表后重试。"));
      timer=setTimeout(()=>void load(),8000);
    }}};
    void load();return()=>{current=false;clearTimeout(timer);};
  },[selected,page,notice,actorId,appliedKeyword,appliedDate]);
  useEffect(()=>{++copySequence.current;++sourceSequence.current;setCopied(null);setSource(null);},[selected]);
  async function upload(files:File[]){if(!files.length||busy)return;setBusy(true);setError(null);setNotice("正在保存本批材料，完成后自动解析与分析…");
    try{const result=await (operation.snapshot().pendingOperation?operation.retry(files):operation.start(files));if(!alive.current)return;
      if(result.kind==="COMPLETED"){setPendingFiles(false);setNotice("材料已接收，系统正在自动分析。你也可以从分析记录中随时查看。");await openProject(result.projectId);}
      else if(result.kind!=="CANCELLED"){setPendingFiles(true);setNotice("部分文件的接收结果尚未确认，请选择原文件继续本次上传，避免重复新建。");}
    }catch(cause){if(alive.current)setError(userFacingError(cause,"文件暂时无法接收，请检查后重试。"));}
    finally{if(alive.current)setBusy(false);}
  }
  async function copy(key:string,value:string){const sequence=++copySequence.current;setCopied(null);
    try{await writeClipboardText(value);if(alive.current&&sequence===copySequence.current)setCopied(key);}
    catch{if(alive.current&&sequence===copySequence.current)setError("暂时无法复制，请选中文本后复制。");}}
  async function saveProjectName(row:Summary){
    if(!row.projectId||!editingName.trim()||savingProject)return;
    setSavingProject(true);setError(null);
    try{
      let operation=readProjectNameOperation(row.projectId);
      if(!operation){const detail=await httpProjectDataSource.getProject(row.projectId);operation={idempotencyKey:createOperationKey(),expectedVersion:detail.project.versionNo,name:editingName.trim(),projectNo:detail.project.projectNo,category:detail.project.category};window.sessionStorage.setItem(projectNameOperationKey(row.projectId),JSON.stringify(operation));}
      await httpProjectDataSource.confirmMetadata(row.projectId,{expectedVersion:operation.expectedVersion,name:operation.name,projectNo:operation.projectNo,category:operation.category},{idempotencyKey:operation.idempotencyKey});
      if(alive.current){window.sessionStorage.removeItem(projectNameOperationKey(row.projectId));setRows(values=>values.map(value=>value.projectId===row.projectId?{...value,projectName:operation.name}:value));setEditingProjectId(null);setEditingName("");}
    }catch(cause){if(cause instanceof ApiError&&cause.status>=400&&cause.status<500)try{window.sessionStorage.removeItem(projectNameOperationKey(row.projectId));}catch{}if(alive.current)setError(userFacingError(cause,"项目名称未保存，请刷新后重试。"));}
    finally{if(alive.current)setSavingProject(false);}
  }
  const loadSourceText=useCallback((fileId:string,start:number,size:number)=>request<SourceText>(`${base}/${selected}/sources/${encodeURIComponent(fileId)}/text`,{params:{start:String(start),size:String(size)}}),[selected]);
  async function showSource(e:SourceEvidence|readonly SourceEvidence[]){
    if(!selected||!report)return;
    const items=uniqueSourceEvidence(Array.isArray(e)?e:[e as SourceEvidence]);
    if(items.length)setSource({id:++sourceSequence.current,caseId:selected,items});
  }
  async function showFormalSource(e:import("../eightCategoryReviewProjection").ContractReviewCategoryEvidenceView){
    const document=report?.documents.find(item=>item.fileId===e.fileId);if(!selected||!document)return;
    await showSource({fileId:e.fileId,start:e.locatorAvailable?e.startOffset:null,end:e.locatorAvailable?e.endOffset:null,excerpt:e.excerpt,
      pageNumber:e.pageNumber,paragraphIndex:e.paragraphIndex,segmentIndex:e.segmentIndex});
  }
  const evidenceButtons=(items:Evidence[])=>uniqueSourceEvidence(items).length>0?<button type="button" className="text-sm text-blue-700 hover:underline" onClick={()=>void showSource(items)}>查看原文</button>:null;
  const {risks,followUps}=partitionAnalysisFindings(report?.findings??[]);
  const materialHighlights=aggregateMaterialHighlights(report?.documents??[]);
  return <section className="delivery-page result-workspace space-y-5">
    <header className="delivery-page-header"><div><h1 className="delivery-page-title">合同审核</h1><p className="delivery-page-subtitle">上传合同及相关材料，系统自动分析并保存结果</p></div>
      <div className="flex flex-wrap items-center gap-3">{selected&&<button type="button" className="delivery-secondary-action" onClick={()=>{setLoadingHistory(true);setSelected(null);setSelectedProjectId(null);setReport(null);setSource(null);setNotice(null);}}><ArrowLeft size={15}/>返回分析记录</button>}
      <label className={`delivery-primary-action ${busy?"opacity-60 cursor-wait":"cursor-pointer"}`}><UploadCloud size={16}/>{busy?"正在上传…":pendingFiles?"选择原文件继续":"上传材料"}
      <input type="file" multiple disabled={busy} className="sr-only" accept=".zip,.pdf,.doc,.docx,.png,.jpg,.jpeg" onChange={event=>{void upload(Array.from(event.target.files??[]));event.target.value="";}}/></label></div></header>
    {error&&<div role="alert" className="delivery-alert delivery-alert-danger">{error}</div>}
    {notice&&showAnalysisIntakeNotice(report?.status,busy,pendingFiles)&&<div role="status" className="delivery-alert delivery-alert-neutral">{busy&&<Loader2 size={16} className="animate-spin"/>}{notice}</div>}
    {!selected?<div className="space-y-4" data-contract-history aria-busy={loadingHistory}><div className="flex justify-between items-center"><h2 className="flex items-center gap-2 text-lg font-semibold"><History size={18}/>分析记录</h2></div>
      <form className="rounded-xl border border-slate-200 bg-white p-4" onSubmit={event=>{event.preventDefault();setPage(1);setAppliedKeyword(projectKeyword.trim());setAppliedDate(createdDate);}}><div className="flex flex-wrap items-end gap-3"><label className="min-w-[220px] flex-1"><span className="mb-1 block text-sm font-medium text-slate-700">项目名称</span><input value={projectKeyword} onChange={event=>setProjectKeyword(event.target.value)} className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100" placeholder="输入项目名称模糊查询" /></label><label className="w-44"><span className="mb-1 block text-sm font-medium text-slate-700">上传日期</span><input type="date" value={createdDate} onChange={event=>setCreatedDate(event.target.value)} className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100" /></label><button type="submit" className="delivery-primary-action">筛选</button><button type="button" className="delivery-secondary-action" onClick={()=>{setProjectKeyword("");setCreatedDate("");setAppliedKeyword("");setAppliedDate("");setPage(1);}}>清除</button></div></form>
      {loadingHistory?<ProcessingStatusCard title="正在读取分析记录" description="正在加载已保存的记录，请稍候。" />:rows.length===0?<div className="rounded-xl border border-slate-200 bg-white p-10 text-center text-slate-500">暂无符合条件的分析记录。上传合同即可开始。</div>:<div className="overflow-hidden rounded-xl border border-slate-200 bg-white divide-y divide-slate-100">{rows.map(row=>{const pendingNameUpdate=row.projectId?readProjectNameOperation(row.projectId):null;return <article key={row.id} data-analysis-case-id={row.id} className="p-5 hover:bg-slate-50 flex items-center gap-4"><button type="button" className="min-w-0 flex-1 text-left" onClick={()=>{setSelected(row.id);setSelectedProjectId(row.projectId??null);setReport(null);setSource(null);}}><div className="text-xs text-slate-500">项目名称</div><div className="font-semibold break-words text-slate-900">{row.projectName||"未命名项目"}</div>{row.headline!==row.projectName&&<div className="mt-1 text-sm text-slate-700 break-words">{row.headline}</div>}<div className="text-sm text-slate-500 mt-1">{new Date(row.createdAt).toLocaleString("zh-CN")} · {row.files} 份材料</div></button><div className="flex items-center gap-3 shrink-0"><span className="text-sm text-slate-600">{row.statusLabel}{row.risks>0?` · ${row.risks} 项关注`:""}</span>{row.projectId&&(editingProjectId===row.id?<div className="flex items-center gap-2"><input autoFocus value={editingName} disabled={Boolean(pendingNameUpdate)||savingProject} onChange={event=>setEditingName(event.target.value)} onKeyDown={event=>{if(event.key==="Enter"){event.preventDefault();void saveProjectName(row);}if(event.key==="Escape"&&!pendingNameUpdate){setEditingProjectId(null);setEditingName("");}}} className="w-48 rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100 disabled:bg-slate-100" aria-label="项目名称"/><button type="button" title={pendingNameUpdate?"重试确认上次保存":"保存项目名称"} aria-label={pendingNameUpdate?"重试确认上次保存":"保存项目名称"} disabled={savingProject||!editingName.trim()} className="p-2 text-emerald-700 disabled:opacity-40" onClick={()=>void saveProjectName(row)}><Check size={16}/></button><button type="button" title="取消编辑" aria-label="取消编辑" disabled={Boolean(pendingNameUpdate)} className="p-2 text-slate-500 disabled:opacity-40" onClick={()=>{setEditingProjectId(null);setEditingName("");}}> <X size={16}/></button></div>:<button type="button" title="修改项目名称" aria-label="修改项目名称" className="p-2 text-slate-500 hover:text-slate-900" onClick={()=>{setEditingProjectId(row.id);setEditingName(pendingNameUpdate?.name??row.projectName??"");}}><Edit3 size={16}/></button>)}</div></article>})}</div>}
      <div className="flex justify-end gap-3"><button type="button" disabled={loadingHistory||page===1} className="delivery-secondary-action" onClick={()=>{setLoadingHistory(true);setPage(p=>p-1);}}>上一页</button><span className="self-center text-sm">第 {page} 页</span><button type="button" disabled={loadingHistory||rows.length<15} className="delivery-secondary-action" onClick={()=>{setLoadingHistory(true);setPage(p=>p+1);}}>下一页</button></div>
    </div>:!report?<ProcessingStatusCard title="正在读取审查结果" description="正在加载已保存的结果，请稍候。" />:<div key={report.id} className="space-y-5">
      <div className="space-y-5"><ResultProjectHeader kind="合同审查结果" name={report.projectName||"合同审查项目"} metadata={<><span data-analysis-project-name className="sr-only">{report.projectName||"合同审查项目"}</span>本批 {report.documents.length} 份材料 · {new Date(report.createdAt).toLocaleString("zh-CN")}</>}
        actions={analysisBusy(report.status)?<ProcessingStatusCard variant="compact" title={report.status==='QUEUED'?'正在准备审查':'正在分析材料'} description="系统正在提取文件内容并执行审查，请稍候。" />:undefined}/>
      {!analysisBusy(report.status)&&<div className="space-y-5"><ResultSection title="优先关注" number="01" label="风险线索" tone={risks.length?"attention":"default"}>{risks.length===0?<ResultEmpty>{analysisBusy(report.status)?"正在分析…":"暂无明确风险线索"}</ResultEmpty>:<div className="result-two-columns">{risks.map((f,i)=>{const sources=findingSourceNames(f,report.documents);return <article className="result-issue" key={`${f.code}:${i}`}><div className="flex flex-wrap items-start justify-between gap-3"><h4 className="font-semibold text-amber-950">{i+1}. {f.title}</h4><button className="inline-flex shrink-0 items-center gap-1 rounded border border-amber-300 bg-white px-2.5 py-1.5 text-sm text-slate-700 hover:bg-amber-100" type="button" onClick={()=>void copy(`risk-${i}`,findingCopyText(f,sources))}><Copy size={13}/>{copied===`risk-${i}`?"已复制":"复制问题项"}</button></div>{sources.length>0&&<p className="mt-2 text-xs font-medium text-amber-900">涉及材料：{sources.join("、")}</p>}<p className="mt-2 text-sm leading-7">{f.explanation}</p><p className="text-sm leading-7 text-slate-600">建议：{f.suggestion}</p><div className="flex flex-wrap gap-3 mt-2">{evidenceButtons(f.evidence)}</div></article>})}</div>}</ResultSection>
      <ContractAnalysisCategories categories={report.categories??[]} documents={report.documents} copied={copied} onCopy={(key,text)=>void copy(key,text)} evidenceButtons={evidenceButtons}/>
      {selectedProjectId&&<ContractAnalysisFormalReviews projectId={selectedProjectId} sourceFileIds={report.documents.map(d=>d.fileId)} actorId={actorId??""} onEvidenceOpen={showFormalSource} />}
      <ResultSection title="补充核对与建议" number="03">{followUps.length===0?<ResultEmpty>暂无补充建议</ResultEmpty>:<div className="result-two-columns">{followUps.map((f,i)=><ResultItem title={f.title} key={`${f.code}:${i}`}><p className="text-sm leading-7 mt-2">{f.explanation}</p><p className="text-sm leading-7 text-slate-600">建议：{f.suggestion}</p><div className="flex flex-wrap gap-3 mt-2">{evidenceButtons(f.evidence)}</div></ResultItem>)}</div>}</ResultSection>
      <ResultSection title="关联材料核对" number="04">{(report.comparisons?.length??0)===0?<ResultEmpty/>:<div className="result-two-columns">{report.comparisons!.map((item,i)=><ResultItem title={topicLabels[item.topic]??"条款比较"} key={`${item.topic}:${i}`}><p className="text-sm leading-7 mt-2">{item.summary}</p><div className="flex flex-wrap gap-3 mt-2">{evidenceButtons(item.evidence)}</div></ResultItem>)}</div>}</ResultSection>
      {materialHighlights.length>0&&<details className="rounded-lg border border-slate-200"><summary className="cursor-pointer p-4 font-medium">材料提取内容（{materialHighlights.length} 项，按内容归集）</summary><div className="space-y-2 border-t border-slate-100 p-4">{materialHighlights.map(item=><article className="rounded border border-slate-100 bg-slate-50 px-3 py-2" key={item.key}><div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1"><strong className="text-sm text-slate-800">{factLabels[item.title]??topicLabels[item.title]??"材料内容"}</strong><span className="break-all text-xs text-slate-500">来源：{item.sourceName}</span></div><p className="mt-1 whitespace-pre-wrap break-words text-sm leading-6 text-slate-700">{item.text}</p></article>)}</div></details>}
      {report.checks.length>0&&<details className="rounded-lg border border-slate-200"><summary className="p-4 cursor-pointer font-medium">金额及关联材料核对</summary><div className="px-4 pb-4 space-y-3">{report.checks.map((check,i)=><div key={`${check.code}:${i}`} className={`text-sm leading-6 ${check.status==="FAIL"?"text-red-700":"text-slate-600"}`}><strong>{contractCheckLabel(check.status)}：</strong>{check.explanation}<div className="flex gap-3">{evidenceButtons(check.evidence)}</div></div>)}</div></details>}
      </div>}</div>
    </div>}
    {source&&source.caseId===selected&&report&&!analysisBusy(report.status)&&<AnalysisEvidenceDialog key={source.id} evidence={source.items} documents={report.documents} loadText={loadSourceText} onClose={()=>setSource(null)}/>}
  </section>;
}
