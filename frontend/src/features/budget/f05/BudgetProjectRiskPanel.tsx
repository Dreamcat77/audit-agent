import { useEffect, useRef, useState } from 'react';
import { ChevronDown, ChevronRight, Loader2, ShieldAlert } from 'lucide-react';
import { request } from '../../../api/client';
import { riskOperation } from './budgetRiskOperation';
import type { BudgetReviewResponse } from '../../../api';
import { budgetRiskCategory, candidateKey, groupRiskCandidates, repeatedMaterialGroups } from './budgetRiskCategories';
import type { RiskCandidate, RiskResult } from './budgetRiskCategories';
const prefix='/api/v1/budget/material-risk';
type BrowsePage={items:RiskCandidate[];nextCursor:string|null};
type HistoryItem={id:string;createdAt:string;projectCount:number;description:string;totalAmount:number|null;bandLabel:string|null};
type HistoryPage={items:HistoryItem[];page:number;size:number;total:number};
const money=(n:number|null)=>n==null?'—':`￥${n.toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2})}`;
export function BudgetProjectRiskPanel({focus,onReady}:{focus?:{token:string;taskIds:string[]}|null;onReady?:()=>void}) {
  const [rows,setRows]=useState<RiskCandidate[]>([]),[selection,setSelection]=useState<Record<string,RiskCandidate>>({}),[expanded,setExpanded]=useState<string[]>([]);
  const selected=Object.keys(selection);
  const selectedRows=selected.map(key=>selection[key]);
  const repeatedGroups=repeatedMaterialGroups(selectedRows);
  const [result,setResult]=useState<RiskResult|null>(null),[history,setHistory]=useState<HistoryItem[]>([]);
  const [page,setPage]=useState(1),[historyPage,setHistoryPage]=useState(1),[loading,setLoading]=useState(false),[checking,setChecking]=useState(false),[error,setError]=useState<string|null>(null);
  const [nextCursor,setNextCursor]=useState<string|null>(null),[cursors,setCursors]=useState<(string|null)[]>([null]),[historyLoading,setHistoryLoading]=useState(false),[historyTotal,setHistoryTotal]=useState(0);
  const [search,setSearch]=useState(''),[keyword,setKeyword]=useState('');
  const [historySearch,setHistorySearch]=useState(''),[historyKeyword,setHistoryKeyword]=useState(''),[historyDate,setHistoryDate]=useState(''),[activeDate,setActiveDate]=useState(''),[openingId,setOpeningId]=useState<string|null>(null);
  const consumed=useRef<string|null>(null),section=useRef<HTMLElement>(null),alive=useRef(true),historyRequest=useRef(0),candidateRequest=useRef(0),detailRequest=useRef(0);
  useEffect(()=>{alive.current=true;return()=>{alive.current=false;candidateRequest.current++;historyRequest.current++;detailRequest.current++;};},[]);
  async function loadCandidates(next=1,cursor:string|null=null,term=keyword) {
    const epoch=++candidateRequest.current;
    setLoading(true);setError(null);
    setKeyword(term);
    try {const data=await request<BrowsePage>(`${prefix}/browse`,{params:{...(cursor?{cursor}:{}),...(term?{keyword:term}:{})}});if(!alive.current||epoch!==candidateRequest.current)return;setRows(data.items);setPage(next);setNextCursor(data.nextCursor);return data.items;}
    catch(e){if(alive.current&&epoch===candidateRequest.current)setError(e instanceof Error?e.message:'材料读取失败，请稍后再试。');}finally{if(alive.current&&epoch===candidateRequest.current)setLoading(false);}
  }
  async function loadHistory(next=1,term=historyKeyword,date=activeDate) {
    const epoch=++historyRequest.current;setHistoryLoading(true);
    setHistoryKeyword(term);setActiveDate(date);
    try {const incoming=await request<HistoryPage>(`${prefix}/history`,{params:{page:String(next),...(term?{keyword:term}:{}),...(date?{date}:{})}});if(!alive.current||epoch!==historyRequest.current)return;setHistory(incoming.items);setHistoryPage(next);setHistoryTotal(incoming.total);}
    catch{if(alive.current&&epoch===historyRequest.current)setError('历史分析暂时无法读取，请稍后重新打开。');}
    finally{if(alive.current&&epoch===historyRequest.current)setHistoryLoading(false);}
  }
  useEffect(()=>{void loadCandidates();void loadHistory();},[]);
  useEffect(()=>{
    if(!focus){setExpanded([]);setSelection({});return;}
    if(consumed.current===focus.token)return;
    let active=true,timer:ReturnType<typeof setTimeout>|undefined;
    async function open() {
      try {
        const reviews:BudgetReviewResponse[]=[];
        for(let start=0;start<focus!.taskIds.length;start+=20){
          reviews.push(...await Promise.all(focus!.taskIds.slice(start,start+20).map(id=>request<BudgetReviewResponse>(`/api/v1/budget/reviews/${id}`))));
          if(!active)return;
        }
        if(!active)return;
        if(reviews.some(r=>r.materialReference?.status==='PENDING')){timer=setTimeout(open,3000);return;}
        setSearch('');setCursors([null]);
        const incoming=await loadCandidates(1,null,'');
        if(!active)return;
        if(!incoming)return;
        consumed.current=focus!.token;setSelection({});setResult(null);
        setExpanded(Array.from(new Set(incoming.map(row=>budgetRiskCategory(row.line.subject??row.line.subjectCode)))));
        onReady?.();
        section.current?.scrollIntoView({behavior:'smooth',block:'start'});
      }catch{if(active)setError('本批材料结果暂时无法读取，请稍后重新打开。');}
    }
    void open();return()=>{active=false;clearTimeout(timer);};
  },[focus]);
  async function analyze() {
    if(checking||!selected.length)return;
    const keys=[...selected].sort();
    setChecking(true);setError(null);
    try {
      const operation=riskOperation(keys);
      detailRequest.current++;
      const next=await request<RiskResult>(`${prefix}/analyses`,{method:'POST',body:{id:operation.id,selections:keys.map(key=>selection[key].source)}});
      if(!alive.current)return;
      setResult(next);window.sessionStorage.removeItem(operation.storageKey);void loadHistory();
    }catch(e){if(alive.current)setError(e instanceof Error?e.message:'分析未完成，请稍后继续核对。');}finally{if(alive.current)setChecking(false);}
  }
  return <section ref={section} aria-label="预算项目风险分析" className="scroll-mt-24 rounded-2xl border border-slate-200 bg-white p-5 sm:p-7">
    <div className="flex items-center gap-2"><ShieldAlert size={19} className="text-[#ef9739]"/><h2 className="text-lg font-bold text-slate-800">预算项目风险分析</h2></div>
    <p className="mt-2 text-sm text-slate-500">勾选需要一起分析的材料记录，系统会按所选记录逐条合计申报金额。</p>
    {error&&<p role="alert" className="mt-3 text-sm text-red-700">{error}</p>}
    <div className="mt-5 grid grid-cols-1 items-start gap-6 xl:grid-cols-[minmax(0,1fr)_360px] 2xl:grid-cols-[minmax(0,1fr)_400px]">
      <div className="min-w-0 space-y-3">
      <form aria-label="项目查询" onSubmit={event=>{event.preventDefault();setCursors([null]);setExpanded(['电子','工程','装潢','其他','科目待核对']);void loadCandidates(1,null,search.trim());}} className="flex flex-wrap items-center gap-2">
        <input aria-label="搜索项目或材料" value={search} maxLength={100} onChange={e=>setSearch(e.target.value)} placeholder="项目、科目或材料名称" className="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm sm:w-64"/>
        <button disabled={loading||checking} className="rounded-lg border border-slate-200 px-3 py-2 text-sm">查询</button>
        {keyword&&<button type="button" disabled={loading||checking} onClick={()=>{setSearch('');setCursors([null]);void loadCandidates(1,null,'');}} className="text-xs text-slate-500">重置</button>}
      </form>
      {groupRiskCandidates(rows).map(group=><article key={group.name} className="rounded-xl border border-slate-200">
        <button type="button" aria-expanded={expanded.includes(group.name)} onClick={()=>setExpanded(old=>old.includes(group.name)?old.filter(v=>v!==group.name):[...old,group.name])} className="flex w-full items-center gap-3 p-4 text-left text-sm font-semibold text-slate-800">{expanded.includes(group.name)?<ChevronDown size={16}/>:<ChevronRight size={16}/>}<span className="flex-1">{group.name}</span><span className="font-normal text-slate-500">{group.rows.length} 项材料记录</span></button>
        {expanded.includes(group.name)&&<div className="space-y-3 border-t border-slate-100 p-4">{!group.rows.length?<p className="text-sm text-slate-400">暂无已解析项目</p>:group.rows.map(row=><div key={candidateKey(row)} className="flex items-start gap-3 rounded-lg bg-slate-50 p-3 text-sm">
          <input type="checkbox" aria-label={`选择项目 ${row.line.project??'项目待核对'} ${row.fileName}`} checked={selected.includes(candidateKey(row))} disabled={checking||loading||selected.length>=100&&!selected.includes(candidateKey(row))} onChange={()=>setSelection(old=>{const next={...old},key=candidateKey(row);if(next[key])delete next[key];else next[key]=row;return next;})} className="mt-1"/>
          <span className="min-w-0 flex-1"><strong className="block break-words text-slate-800">{row.line.project??'项目待核对'}</strong><span className="mt-1 block text-xs text-slate-500">{row.line.subject??row.line.subjectCode??'科目待核对'} · 申报 {money(row.line.declaredAmount)}</span><span className="mt-1 block break-words text-xs text-slate-500">{row.fileName}</span></span>
        </div>)}</div>}
      </article>)}
      {loading&&<p role="status" className="flex items-center gap-2 text-sm text-slate-500"><Loader2 size={15} className="animate-spin"/>正在读取材料</p>}
      <nav aria-label="项目分页" className="flex items-center justify-between gap-2 text-xs text-slate-500"><span>第 {page} 页 · 每页最多 12 项</span><span className="flex gap-3"><button type="button" disabled={page===1||loading||checking} onClick={()=>void loadCandidates(page-1,cursors[page-2])} className="disabled:opacity-40">上一页</button><button type="button" disabled={!nextCursor||loading||checking} onClick={()=>{setCursors(old=>[...old.slice(0,page),nextCursor]);setExpanded(['电子','工程','装潢','其他','科目待核对']);void loadCandidates(page+1,nextCursor);}} className="disabled:opacity-40">下一页</button></span></nav>
      <div className="flex flex-wrap items-center justify-between gap-3"><span className="text-xs text-slate-500">已选择 {selected.length} 条材料记录{selected.length>0&&<button type="button" disabled={checking} onClick={()=>setSelection({})} className="ml-3 text-slate-500">清空选择</button>}{selected.length>=100&&<span className="ml-2">每次最多分析 100 条</span>}</span><button type="button" disabled={!selected.length||checking||!!openingId} onClick={()=>void analyze()} className="inline-flex items-center gap-2 rounded-lg bg-[#f6a142] px-4 py-2.5 text-sm font-semibold text-white disabled:opacity-40">{checking&&<Loader2 size={15} className="animate-spin"/>}{checking?'分析中…':'执行风险分析'}</button></div>
      {repeatedGroups.length>0&&<p role="status" className="rounded border border-amber-200 bg-amber-50 px-3 py-2 text-xs leading-5 text-amber-900">所选内容中有 {repeatedGroups.length} 组项目、科目、年度、部门和金额相同的跨材料记录，请核对是否为重复材料。系统不会自动去重，所有勾选记录都会计入合计。</p>}
      {result&&<SplitResult key={result.id} result={result}/>}
      </div>
      <aside aria-label="风险分析历史" className="rounded-xl border border-slate-200 bg-slate-50 p-4">
        <h3 className="text-sm font-semibold text-slate-800">历史分析结果</h3>
        <form aria-label="历史分析查询" onSubmit={e=>{e.preventDefault();void loadHistory(1,historySearch.trim(),historyDate);}} className="mt-3 space-y-2">
          <label className="block text-xs text-slate-500">分析日期<input type="date" aria-label="分析日期" value={historyDate} onChange={e=>setHistoryDate(e.target.value)} className="mt-1 block w-full rounded-lg border border-slate-200 bg-white px-2 py-1.5 text-xs"/></label>
          <input aria-label="搜索历史分析" maxLength={100} value={historySearch} onChange={e=>setHistorySearch(e.target.value)} placeholder="项目、科目或材料名称" className="block w-full rounded-lg border border-slate-200 bg-white px-2 py-2 text-xs"/>
          <div className="flex items-center gap-3"><button disabled={historyLoading} className="rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs">查询</button>{(historyKeyword||activeDate)&&<button type="button" disabled={historyLoading} onClick={()=>{setHistorySearch('');setHistoryDate('');void loadHistory(1,'','');}} className="text-xs text-slate-500">重置</button>}</div>
        </form>
        {historyLoading&&<p role="status" className="mt-3 text-xs text-slate-500">正在读取历史分析…</p>}
        {!historyLoading&&!history.length&&<p className="mt-4 text-xs text-slate-500">{historyKeyword||activeDate?'没有符合条件的历史分析':'暂无历史分析'}</p>}
        <div aria-label="历史分析列表" className="mt-3 space-y-2">{history.map(item=><button key={item.id} type="button" disabled={checking||!!openingId} onClick={async()=>{const epoch=++detailRequest.current;setOpeningId(item.id);try{const saved=await request<RiskResult>(`${prefix}/analyses/${item.id}`);if(alive.current&&epoch===detailRequest.current)setResult(saved);}catch{if(alive.current&&epoch===detailRequest.current)setError('历史结果读取失败，请稍后再试。');}finally{if(alive.current&&epoch===detailRequest.current)setOpeningId(null);}}} className={`block w-full rounded-lg border bg-white p-3 text-left text-xs ${result?.id===item.id?'border-orange-300':'border-slate-200'}`}><span className="block text-slate-700">{new Date(item.createdAt).toLocaleString('zh-CN',{hour12:false,timeZone:'Asia/Shanghai'})}</span><span className="mt-1 block break-words text-slate-500">{item.description} · {item.projectCount} 项</span>{item.bandLabel&&<span className="mt-1 block text-slate-700">{money(item.totalAmount)} · {item.bandLabel}</span>}</button>)}</div>
        <nav aria-label="历史分析分页" className="mt-4 flex flex-wrap items-center justify-between gap-2 text-xs text-slate-500"><span>共 {historyTotal} 条 · 第 {historyPage} 页</span><span className="flex gap-3"><button type="button" disabled={historyLoading||historyPage<=1} onClick={()=>void loadHistory(historyPage-1)} className="disabled:opacity-40">上一页</button><button type="button" disabled={historyLoading||historyPage*6>=historyTotal} onClick={()=>void loadHistory(historyPage+1)} className="disabled:opacity-40">下一页</button></span></nav>
      </aside>
    </div>
  </section>;
}

function SplitResult({result}:{result:RiskResult}) {
  const analysis=result.splitAnalysis;
  const [detailPage,setDetailPage]=useState(1);
  if(analysis?.version==='SELECTED_SUM_V2'||analysis?.version==='SELECTED_SUM_V3')return <div role="region" aria-label="预算风险参考结果" className="space-y-3 rounded-xl border border-amber-200 bg-amber-50 p-4">
    <h3 className="text-sm font-semibold text-slate-800">申报金额分析结果</h3>
    <p className="text-sm leading-7 text-slate-700">{analysis.summary}</p>
    <p className="text-sm text-slate-700">{analysis.complete?'申报金额合计':'已识别金额小计'}：<strong>{money(analysis.totalAmount)}</strong></p>
    {analysis.bandLabel&&<p className="text-sm font-semibold text-slate-800">金额区间：{analysis.bandLabel}</p>}
    <ul aria-label="本次所选申报内容" className="space-y-2 rounded-lg bg-white p-3 text-sm leading-7 text-slate-700">{result.projects.slice((detailPage-1)*12,detailPage*12).map(row=><li key={candidateKey(row)} className="break-words">{row.line.project??'项目待核对'} · {row.line.subject??row.line.subjectCode??'科目待核对'}：{money(row.line.declaredAmount)}<span className="block text-xs text-slate-500">来源材料：{row.fileName}</span></li>)}</ul>
    {result.projects.length>12&&<nav aria-label="所选内容分页" className="flex items-center justify-between text-xs text-slate-500"><span>共 {result.projects.length} 项 · 第 {detailPage} 页</span><span className="flex gap-3"><button type="button" disabled={detailPage<=1} onClick={()=>setDetailPage(p=>p-1)} className="disabled:opacity-40">上一页</button><button type="button" disabled={detailPage*12>=result.projects.length} onClick={()=>setDetailPage(p=>p+1)} className="disabled:opacity-40">下一页</button></span></nav>}
    <p className="text-xs text-slate-500">结果已保存，可在右侧历史列表中回看。</p>
  </div>;
  return <div role="region" aria-label="预算风险参考结果" className="space-y-3 rounded-xl border border-amber-200 bg-amber-50 p-4">
    <h3 className="text-sm font-semibold text-slate-800">项目拆分风险分析结果</h3>
    {!analysis?<p className="text-sm leading-7 text-slate-700">这份历史结果未包含累计金额门槛分析，不能据此判断拆分风险。可重新勾选相关申报内容进行分析，原记录仍保留。</p>:<>
      <p className="text-sm leading-7 text-slate-700">{analysis.summary}</p>
      <p className="text-sm text-slate-700">计入 {analysis.countedItems} 项，申报金额合计 <strong>{money(analysis.totalAmount)}</strong>。不同年度、部门分别核算。</p>
      {!!analysis.rules.length&&<p className="text-xs leading-6 text-slate-500">本次核对门槛：{analysis.rules.map(rule=>`${rule.name}（${money(rule.threshold)}）`).join('；')}。正式适用门槛及审批、报告要求以甲方制度为准。</p>}
      {analysis.findings.map((finding,index)=><article key={`${finding.rule.code}:${index}`} aria-label="拆分风险提示" className="space-y-2 rounded-lg border border-red-200 bg-white p-3 text-sm leading-7 text-slate-700">
        <h4 className="font-semibold text-red-700">一般风险 · {finding.rule.name}</h4>
        <p>{finding.explanation}</p>
        <p className="text-xs text-slate-500">预算年度：{finding.year??'—'}；申报部门：{finding.department??'—'}</p>
        <ul className="list-disc space-y-1 pl-5">{finding.projects.map(row=><li key={candidateKey(row)} className="break-words"><span className="font-medium">{row.line.project??'项目待核对'} · {row.line.subject??row.line.subjectCode??'科目待核对'}</span>：{money(row.line.declaredAmount)}<span className="block text-xs text-slate-500">来源材料：{row.fileName}</span></li>)}</ul>
      </article>)}
      {!!analysis.exclusions.length&&<div className="rounded-lg bg-white p-3 text-sm leading-7 text-slate-700"><h4 className="font-medium">未计入的材料记录</h4><ul className="mt-2 space-y-2">{analysis.exclusions.map(item=><li key={candidateKey(item.project)} className="break-words"><span>{item.project.line.project??'项目待核对'} · {item.project.line.subject??item.project.line.subjectCode??'科目待核对'}：{item.reason}</span><span className="block text-xs text-slate-500">来源材料：{item.project.fileName}</span></li>)}</ul></div>}
      {!analysis.findings.length&&!!analysis.countedItems&&<ul aria-label="本次所选申报内容" className="space-y-2 rounded-lg bg-white p-3 text-sm leading-7 text-slate-700">{result.projects.filter(row=>!analysis.exclusions.some(item=>candidateKey(item.project)===candidateKey(row))).map(row=><li key={candidateKey(row)} className="break-words">{row.line.project??'项目待核对'} · {row.line.subject??row.line.subjectCode??'科目待核对'}：{money(row.line.declaredAmount)}<span className="block text-xs text-slate-500">来源材料：{row.fileName}</span></li>)}</ul>}
    </>}
    <p className="text-xs text-slate-500">结果已保存，可在右侧历史列表中回看。</p>
  </div>;
}
