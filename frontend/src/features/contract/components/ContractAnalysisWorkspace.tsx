import {useCallback,useEffect,useMemo,useRef,useState} from "react";
import {AnalysisEvidenceDialog} from "./AnalysisEvidenceDialog";
import {uniqueSourceEvidence,type SourceEvidence,type SourceText} from "../contractAnalysisEvidenceView";
import {ResultProjectHeader,ResultSection,ResultItem,ResultEmpty} from "../../../components/results/ResultLayout";
import {HistoryFilterBar,HistoryList,HistoryRow,HistoryFooter,HistoryPagination,historyDate,historyStatus} from '../../../components/history/HistoryResults';
import {ProcessingStatusCard} from "../../../components/status/ProcessingStatusCard";
import {ArrowLeft,Copy,Loader2,UploadCloud} from "lucide-react";
import {ApiError,request} from "../../../api/client";
import {writeClipboardText} from "../../../utils/clipboard";
import {userFacingError} from "../../../api/userFacingError";
import {useAuth} from "../../../auth/AuthProvider";
import {httpProjectDataSource} from "../../project/data/httpProjectDataSource";
import {ContractAnalysisCategories,type AnalysisCategory} from "./ContractAnalysisCategories";
import ContractAnalysisFormalReviews from "./ContractAnalysisFormalReviews";
import {createContractMaterialIntakeOperation} from "../contractMaterialIntakeOperation";
import {analysisBusy,contractCheckLabel,factLabels,topicLabels,partitionAnalysisFindings,findingSourceNames,findingCopyText,aggregateMaterialHighlights,type AnalysisState} from "../contractAnalysisPresentation";

type Evidence={fileId:string;start:number;end:number;excerpt:string};
type Fact={type:string;value:string;evidence:Evidence[]};
type Clause={topic:string;summary:string;evidence:Evidence[]};
type Finding={code:string;level:string;title:string;explanation:string;suggestion:string;evidence:Evidence[];origin:string};
type Check={code:string;status:string;explanation:string;evidence:Evidence[]};
type Summary={id:string;status:AnalysisState;statusLabel:string;headline:string;files:number;risks:number;createdAt:string;projectId?:string;projectName?:string|null};
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
  const [historyRevision,setHistoryRevision]=useState(0);
  const [historyError,setHistoryError]=useState<string|null>(null);
  const [appliedKeyword,setAppliedKeyword]=useState("");const [appliedDate,setAppliedDate]=useState("");
  const [selected,setSelected]=useState<string|null>(null);const [report,setReport]=useState<Report|null>(null);
  const [selectedProjectId,setSelectedProjectId]=useState<string|null>(null);
  const [busy,setBusy]=useState(false);const [error,setError]=useState<string|null>(null);
  const [evidenceFocus,setEvidenceFocus]=useState('');
  const [copied,setCopied]=useState<string|null>(null);const [source,setSource]=useState<{id:number;caseId:string;items:SourceEvidence[]}|null>(null);
  const uploading=useRef(false);const alive=useRef(true);const copySequence=useRef(0);const sourceSequence=useRef(0);
  const selection=useRef(selected);selection.current=selected;
  const operation=useMemo(()=>createContractMaterialIntakeOperation({dataSource:httpProjectDataSource,automaticAnalysis:true,newProjectPerBatch:true,
    ...(actorId?{retained:{actorId,storage:window.localStorage}}:{})}),[actorId]);
  useEffect(()=>{alive.current=true;return()=>{alive.current=false;};},[]);
  async function openProject(projectId:string){
    try {
      const item=await request<{id:string}>(`${base}/from-project/${encodeURIComponent(projectId)}`);
      if(alive.current){setSelected(item.id);setSelectedProjectId(projectId);setReport(null);setSource(null);}
    } catch(cause) {
      if(cause instanceof ApiError&&cause.status===404&&alive.current){
        setError("这条分析记录暂时无法打开，请选择其他记录或重新上传材料。");
        return;
      }
      throw cause;
    }
  }
  useEffect(()=>{let current=true;let timer:ReturnType<typeof setTimeout>;if(!selected){setLoadingHistory(true);setHistoryError(null);}
    const load=async()=>{try{
      if(selected){const [result,identity]=await Promise.all([request<Report>(`${base}/${selected}/report`),request<{projectId:string}>(`${base}/${selected}`)]);if(!current)return;setReport(result);setSelectedProjectId(result.projectId??identity.projectId);setError(null);if(analysisBusy(result.status))timer=setTimeout(()=>void load(),3000);}
      else{const params:{page:string;size:string;projectName?:string;createdDate?:string;timezoneOffsetMinutes?:string}={page:String(page),size:"15"};if(appliedKeyword)params.projectName=appliedKeyword;if(appliedDate){params.createdDate=appliedDate;params.timezoneOffsetMinutes=String(new Date().getTimezoneOffset());}const result=await request<Summary[]>(base,{params});if(!current)return;setRows(result);setLoadingHistory(false);setHistoryError(null);setError(null);if(result.some(r=>analysisBusy(r.status)))timer=setTimeout(()=>void load(),5000);}
    }catch(cause){if(current){setLoadingHistory(false);
      // Retry transient reads silently; retain actionable client/auth errors.
      if(selected&&cause instanceof ApiError&&cause.status<500)setError(userFacingError(cause,"当前分析记录无法打开，请返回记录列表后重试。"));
      if(!selected)setHistoryError('历史结果暂时无法读取，请稍后重新查询。');
      timer=setTimeout(()=>void load(),8000);
    }}};
    void load();return()=>{current=false;clearTimeout(timer);};
  },[selected,page,actorId,appliedKeyword,appliedDate,historyRevision]);
  useEffect(()=>{++copySequence.current;++sourceSequence.current;setCopied(null);setSource(null);},[selected]);
  async function upload(files:File[]){if(!files.length||uploading.current)return;uploading.current=true;setBusy(true);setError(null);
    try{const result=await operation.start(files);if(!alive.current)return;
      if(result.kind==="COMPLETED")await openProject(result.projectId);
      else if(result.kind!=="CANCELLED")setError("本批材料尚未全部上传成功，请在分析记录中查看接收情况。");
    }catch(cause){if(alive.current)setError(userFacingError(cause,"文件暂时无法接收，请检查后重试。"));}
    finally{uploading.current=false;if(alive.current)setBusy(false);}
  }
  async function copy(key:string,value:string){const sequence=++copySequence.current;setCopied(null);
    try{await writeClipboardText(value);if(alive.current&&sequence===copySequence.current)setCopied(key);}
    catch{if(alive.current&&sequence===copySequence.current)setError("暂时无法复制，请选中文本后复制。");}}
  const loadSourceText=useCallback((fileId:string,start:number,size:number)=>request<SourceText>(`${base}/${selected}/sources/${encodeURIComponent(fileId)}/text`,{params:{start:String(start),size:String(size)}}),[selected]);
  const visualBase=useCallback((fileId:string)=>`${base}/${selected}/sources/${encodeURIComponent(fileId)}`,[selected]);
  const originalUrl=useCallback((fileId:string)=>selectedProjectId?`/api/v1/projects/${encodeURIComponent(selectedProjectId)}/files/${encodeURIComponent(fileId)}/content`:'',[selectedProjectId]);
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
  const evidenceButtons=(items:Evidence[],focus='')=>uniqueSourceEvidence(items).length>0?<button type="button" className="text-sm text-blue-700 hover:underline" onClick={()=>{setEvidenceFocus(focus);void showSource(items);}}>查看原文</button>:null;
  const {risks,followUps}=partitionAnalysisFindings(report?.findings??[]);
  const materialHighlights=aggregateMaterialHighlights(report?.documents??[]);
  return <section className={selected?"delivery-page result-workspace space-y-5":"history-page"}>
    <header className={selected?"delivery-page-header":"history-page-header"}><div><h1 className={selected?"delivery-page-title":"history-page-title"}>{selected?"合同审核":"历史结果"}</h1></div>
      <div className="flex flex-wrap items-center gap-3">{selected&&<button type="button" className="delivery-secondary-action" onClick={()=>{setLoadingHistory(true);setSelected(null);setSelectedProjectId(null);setReport(null);setSource(null);setError(null);}}><ArrowLeft size={15}/>返回分析记录</button>}
      {!selected&&<label className={`history-button history-button-primary ${busy?"opacity-60 cursor-wait":"cursor-pointer"}`}><UploadCloud size={16}/>{busy?"正在上传…":"上传材料"}
      <input type="file" multiple disabled={busy} className="sr-only" accept=".zip,.pdf,.doc,.docx,.png,.jpg,.jpeg" onChange={event=>{void upload(Array.from(event.target.files??[]));event.target.value="";}}/></label>}</div></header>
    {error&&<div role="alert" className="delivery-alert delivery-alert-danger">{error}</div>}
    {!selected?<div className="history-results" data-contract-history aria-busy={loadingHistory}>
      <HistoryFilterBar keyword={projectKeyword} date={createdDate} keywordLabel="项目名称" placeholder="输入项目名称关键词"
        onKeywordChange={setProjectKeyword} onDateChange={setCreatedDate}
        onQuery={()=>{setLoadingHistory(true);setPage(1);setAppliedKeyword(projectKeyword.trim());setAppliedDate(createdDate);setHistoryRevision(v=>v+1);}}
        onReset={()=>{setLoadingHistory(true);setProjectKeyword('');setCreatedDate('');setAppliedKeyword('');setAppliedDate('');setPage(1);setHistoryRevision(v=>v+1);}}/>
      <HistoryList label="合同历史记录" loading={loadingHistory} error={historyError} empty={!rows.length}>
        {rows.map(row=><HistoryRow key={row.id} data-analysis-case-id={row.id} name={row.projectName||row.headline||'未命名项目'}
          details={row.headline!==row.projectName?row.headline:undefined}
          metadata={<><span>{historyDate(row.createdAt)}</span><span>{row.files} 份材料</span></>}
          status={historyStatus(row.status).label} tone={historyStatus(row.status).tone} statusDetail={row.risks>0?`${row.risks} 项关注`:undefined}
          onOpen={()=>{setSelected(row.id);setSelectedProjectId(row.projectId??null);setReport(null);setSource(null);}}/>)}
      </HistoryList>
      <HistoryFooter summary={`本页 ${loadingHistory?'—':rows.length} 条记录 · 第 ${page} 页`}>
        <HistoryPagination page={page} busy={loadingHistory} hasNext={rows.length===15} onPageChange={page=>{setLoadingHistory(true);setPage(page);}}/>
      </HistoryFooter>
    </div>:!report?<ProcessingStatusCard title="正在读取审查结果" description="正在加载已保存的结果，请稍候。" />:<div key={report.id} className="space-y-5">
      <div className="space-y-5"><ResultProjectHeader kind="合同审查结果" name={report.projectName||"合同审查项目"} metadata={<><span data-analysis-project-name className="sr-only">{report.projectName||"合同审查项目"}</span>本批 {report.documents.length} 份材料 · {new Date(report.createdAt).toLocaleString("zh-CN")}</>}
        actions={analysisBusy(report.status)?<ProcessingStatusCard variant="compact" title={report.status==='QUEUED'?'正在准备审查':'正在分析材料'} description="系统正在提取文件内容并执行审查，请稍候。" />:undefined}/>
      {!analysisBusy(report.status)&&<div className="space-y-5"><ResultSection title="优先关注" number="01" label="风险线索" tone={risks.length?"attention":"default"}>{risks.length===0?<ResultEmpty>{analysisBusy(report.status)?"正在分析…":"暂无明确风险线索"}</ResultEmpty>:<div className="result-two-columns">{risks.map((f,i)=>{const sources=findingSourceNames(f,report.documents);return <article className="result-issue" key={`${f.code}:${i}`}><div className="flex flex-wrap items-start justify-between gap-3"><h4 className="font-semibold text-amber-950">{i+1}. {f.title}</h4><button className="inline-flex shrink-0 items-center gap-1 rounded border border-amber-300 bg-white px-2.5 py-1.5 text-sm text-slate-700 hover:bg-amber-100" type="button" onClick={()=>void copy(`risk-${i}`,findingCopyText(f,sources))}><Copy size={13}/>{copied===`risk-${i}`?"已复制":"复制问题项"}</button></div>{sources.length>0&&<p className="mt-2 text-xs font-medium text-amber-900">涉及材料：{sources.join("、")}</p>}<p className="mt-2 text-sm leading-7">{f.explanation}</p><p className="text-sm leading-7 text-slate-600">建议：{f.suggestion}</p><div className="flex flex-wrap gap-3 mt-2">{evidenceButtons(f.evidence,`${f.title} ${f.explanation}`)}</div></article>})}</div>}</ResultSection>
      <ContractAnalysisCategories categories={report.categories??[]} documents={report.documents} copied={copied} onCopy={(key,text)=>void copy(key,text)} evidenceButtons={evidenceButtons}/>
      {selectedProjectId&&<ContractAnalysisFormalReviews projectId={selectedProjectId} sourceFileIds={report.documents.map(d=>d.fileId)} actorId={actorId??""} onEvidenceOpen={showFormalSource} />}
      <ResultSection title="补充核对与建议" number="03">{followUps.length===0?<ResultEmpty>暂无补充建议</ResultEmpty>:<div className="result-two-columns">{followUps.map((f,i)=><ResultItem title={f.title} key={`${f.code}:${i}`}><p className="text-sm leading-7 mt-2">{f.explanation}</p><p className="text-sm leading-7 text-slate-600">建议：{f.suggestion}</p><div className="flex flex-wrap gap-3 mt-2">{evidenceButtons(f.evidence,`${f.title} ${f.explanation}`)}</div></ResultItem>)}</div>}</ResultSection>
      <ResultSection title="关联材料核对" number="04">{(report.comparisons?.length??0)===0?<ResultEmpty/>:<div className="result-two-columns">{report.comparisons!.map((item,i)=><ResultItem title={topicLabels[item.topic]??"条款比较"} key={`${item.topic}:${i}`}><p className="text-sm leading-7 mt-2">{item.summary}</p><div className="flex flex-wrap gap-3 mt-2">{evidenceButtons(item.evidence,item.summary)}</div></ResultItem>)}</div>}</ResultSection>
      {materialHighlights.length>0&&<details className="rounded-lg border border-slate-200"><summary className="cursor-pointer p-4 font-medium">材料提取内容（{materialHighlights.length} 项，按内容归集）</summary><div className="space-y-2 border-t border-slate-100 p-4">{materialHighlights.map(item=><article className="rounded border border-slate-100 bg-slate-50 px-3 py-2" key={item.key}><div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1"><strong className="text-sm text-slate-800">{factLabels[item.title]??topicLabels[item.title]??"材料内容"}</strong><span className="break-all text-xs text-slate-500">来源：{item.sourceName}</span></div><p className="mt-1 whitespace-pre-wrap break-words text-sm leading-6 text-slate-700">{item.text}</p></article>)}</div></details>}
      {report.checks.length>0&&<details className="rounded-lg border border-slate-200"><summary className="p-4 cursor-pointer font-medium">金额及关联材料核对</summary><div className="px-4 pb-4 space-y-3">{report.checks.map((check,i)=><div key={`${check.code}:${i}`} className={`text-sm leading-6 ${check.status==="FAIL"?"text-red-700":"text-slate-600"}`}><strong>{contractCheckLabel(check.status)}：</strong>{check.explanation}<div className="flex gap-3">{evidenceButtons(check.evidence,check.explanation)}</div></div>)}</div></details>}
      </div>}</div>
    </div>}
    {source&&source.caseId===selected&&report&&!analysisBusy(report.status)&&<AnalysisEvidenceDialog focusText={evidenceFocus} key={source.id} evidence={source.items} documents={report.documents} loadText={loadSourceText} visualBase={visualBase} originalUrl={originalUrl} onClose={()=>setSource(null)}/>}
  </section>;
}
