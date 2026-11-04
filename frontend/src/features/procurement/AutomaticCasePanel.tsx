import { useEffect, useRef, useState } from 'react';
import {ResultProjectHeader,ResultSection,ResultItem,ResultEmpty} from '../../components/results/ResultLayout';
import { ApiError, apiUrl, request } from '../../api/client';
import { uploadRequest } from '../../api/clientUpload';
import { userFacingError } from '../../api/userFacingError';
import { writeClipboardText as copyText } from '../../utils/clipboard';
import { createOperationKey } from '../../api/operationKey';
import { OFFICE_MATERIAL_ACCEPT } from './materials/officeMaterialTypes';
import { automaticHistoryParams, automaticCheckTone, automaticPrioritySummary, automaticCheckExplanation, automaticFactLabel, automaticReviewReason, caseHistoryLabel, definiteUploadRejection } from './automaticCaseState';

type Document = { id: string; name: string; status: string; errorCode?: string; factCount: number };
type Offer = { supplier: string; amount: string; documentId: string; lineValidation: string;
  lines: { item: string; quantity: string; unitPrice: string; amount: string; locator: string }[] };
type Source = { id: string; documentId: string; fileName: string; sha256: string; locator: string; excerpt: string };
type Review = { status: string; model?: string; errorCode?: string; rejectedCitations: number; coverageGaps: string[];
  scope: { documentId: string; fileName: string; role: string; sourceChars: number; suppliedChars: number }[];
  checks: { code: string; status: string; explanation: string; evidence: Source[] }[];
  findings: { code: string; level: string; title: string; explanation: string; suggestion: string; evidence: Source[] }[] };
type Case = { findingIds?: Record<string,string>; id: string; caseNumber: string; projectName?: string; createdAt?: string; status: string; documents: Document[]; report?: {
  quoteStatus: string; quoteComparisons?: {documentId:string;status:string;difference:string}[]; ceilingComparisons?: {documentId:string;status:string;difference:string}[]; projectName?: string; budget?: string; ceilingAmount?: string; awardAmount?: string; offers: Offer[]; notices: string[];
  modules: Record<string, string>; reviews?: Record<string, Review>; calculation?: { averageQuote: string; coefficientOfVariation: string;
    quotes: { supplierName: string; overBudget: boolean }[] } } };
type HistoryEntry = { id: string; projectName?: string; status: string; createdAt?: string; fileNames: string[] };
type HistoryPage = { items: HistoryEntry[]; nextCursor: string | null };
type Evidence = { fileName: string; segments: { locator: string; text: string }[]; facts: { type: string; value: string; locator: string; excerpt: string; verification: string }[] };
const path = '/api/v1/procurement/cases';
const labels: Record<string, string> = { UPLOADING: '保存材料中', QUEUED: '等待处理', RUNNING: '正在处理',
  COMPLETED: '本轮分析完成', PARTIAL: '部分完成', DATA_INSUFFICIENT: '数据不足', FAILED: '执行失败', UNKNOWN: '结果待核实', PENDING: '等待分析', NOT_EXECUTED: '未执行',
  EXTRACTED: '事实提取完成', PARSED: '全文读取完成', STORED: '原文件已保存', STAGING: '原文件待核实',
  PARSING: '读取文件中', CALLING: '提取事实中' };

/** One batch upload and report workspace; historical records never start model calls. */
export function AutomaticCasePanel({ ownerId }: { ownerId: string }) {
  const store = `procurement-auto-case-v1:${ownerId}`;
  const [historyOpen, setHistoryOpen] = useState(false);
  const [search, setSearch] = useState('');
  const [date, setDate] = useState('');
  const [copied, setCopied] = useState('');
  const [acknowledged,setAcknowledged]=useState<string[]>([]);
  const [ackBusy,setAckBusy]=useState('');
  const historyEpoch=useRef(0);
  const [cases, setCases] = useState<HistoryEntry[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [historyBusy, setHistoryBusy] = useState(false);
  const [historyError, setHistoryError] = useState('');
  const [current, setCurrent] = useState<Case | null>(null);
  const currentId=useRef<string|null>(null);
  currentId.current=current?.id??null;
  const [evidence, setEvidence] = useState<Evidence | null>(null);
  const [files, setFiles] = useState<File[]>([]);
  const [busy, setBusy] = useState(false);
  const [uploadProgress, setUploadProgress] = useState<{ loaded: number; total: number } | null>(null);
  const [dragging, setDragging] = useState(false);
  const [error, setError] = useState('');
  const [uncertain, setUncertain] = useState(false);
  useEffect(() => {
    let active = true;
    setCurrent(null); setEvidence(null); setFiles([]); setUncertain(false); setError('');
    // Upload recovery is independent of listing availability and preserves its operation key.
    void (async () => {
      let selected: Case | undefined;
      let saved: { key?: string; caseId?: string } = {};
      try { saved = JSON.parse(localStorage.getItem(store) || '{}'); }
      catch { /* Malformed UI state supplies no usable operation identity. */ }
      try {
        if (saved.caseId) {
          const retained = await request<Case>(`${path}/${saved.caseId}`);
          if (['UPLOADING','QUEUED','RUNNING'].includes(retained.status)) selected=retained;
          if (!active) return;
        }
        if (saved.key && !saved.caseId) {
          const recovered = await request<Case | null>(`${path}/operations/${saved.key}`);
          if (!active) return;
          if (recovered) { selected = recovered; localStorage.setItem(store, JSON.stringify({ ...saved, caseId: recovered.id })); }
          else setUncertain(true);
        }
      }
      catch (e) {
        if (!active) return;
        setUncertain(true);
        setError(userFacingError(e, '原上传结果尚未确认，案件与原操作已保留'));
      }
      setCurrent(selected || null);
    })();
    return () => { active = false; };
  }, [ownerId, store]);
  useEffect(() => {
    if (!current || !['UPLOADING', 'QUEUED', 'RUNNING'].includes(current.status)) return;
    let active = true;
    const timer = window.setInterval(() => {
      request<Case>(`${path}/${current.id}`).then(c => { if (active) { setCurrent(c); setCases(old => old.map(entry => entry.id === c.id ? {...entry,projectName:c.projectName,status:c.status} : entry)); setError(''); } })
        .catch(e => { if (active) setError(userFacingError(e, '进度读取失败，案件与原操作已保留')); });
    }, 2500);
    return () => { active = false; window.clearInterval(timer); };
  }, [current?.id, current?.status]);
  useEffect(() => {
    setAcknowledged([]);
    if(!current?.report)return;
    let active=true;
    request<string[]>(`${path}/${current.id}/acknowledgments`).then(value=>{if(active)setAcknowledged(value);})
      .catch(()=>{if(active)setError('确认记录暂时无法读取，请重试。');});
    return ()=>{active=false;};
  },[current?.id,!!current?.report]);
  async function acknowledge(key:string) {
    if(!current||ackBusy)return;
    const id=current.id;
    setAckBusy(key);
    try {await request(`${path}/${id}/acknowledgments/${key}`,{method:'POST'});if(currentId.current===id)setAcknowledged(old=>[...new Set([...old,key])]);}
    catch(error){if(currentId.current===id)setError(userFacingError(error,'确认未保存，请重试。'));}
    finally{setAckBusy('');}
  }
  function historyParams() {
    return automaticHistoryParams(search,date);
  }
  useEffect(() => {
    if (!historyOpen || current) return;
    let active = true;
    const epoch=++historyEpoch.current;
    setHistoryBusy(true); setHistoryError('');
    const timer = window.setTimeout(() => {
      request<HistoryPage>(`${path}/history`, { params: historyParams() }).then(value => {
        if (active && epoch===historyEpoch.current) {setCases(value.items);setNextCursor(value.nextCursor);}
      }).catch(e => {if(active && epoch===historyEpoch.current)setHistoryError(userFacingError(e,'历史结果查询失败，请重试'));})
        .finally(() => {if(active)setHistoryBusy(false);});
    }, 300);
    return () => {active=false;window.clearTimeout(timer);};
  }, [historyOpen, search, date, ownerId, current?.id]);
  const caseProcessing = !!current && ['UPLOADING', 'QUEUED', 'RUNNING'].includes(current.status);
  async function upload(selected: File[]) {
    if (!selected.length || busy || caseProcessing) return;
    setBusy(true); setUploadProgress(null); setError(''); setFiles(selected); setCurrent(null); setEvidence(null);
    let operationKey = '';
    try {
      let saved: { key?: string; manifest?: string; caseId?: string } = {};
      try { saved = JSON.parse(localStorage.getItem(store) || '{}'); } catch { /* Validate below. */ }
      const manifest = JSON.stringify(selected.map(f => [f.name, f.size, f.lastModified]).sort());
      // Retain the exact key on uncertain responses and refresh. Server verifies actual content hashes.
      if (saved.key && !saved.caseId && saved.manifest !== manifest) {
        setUncertain(true); throw new Error('上一批上传结果待核实，请重新选择原批材料或从历史案件恢复');
      }
      const key = saved.key && !saved.caseId ? saved.key : createOperationKey();
      operationKey = key;
      localStorage.setItem(store, JSON.stringify({ key, manifest }));
      const body = new FormData(); selected.forEach(f => body.append('files', f));
      const value = await uploadRequest<Case>(path, { method: 'POST', body, headers: { 'Idempotency-Key': key },
        onUploadProgress: progress => setUploadProgress(progress) });
      localStorage.setItem(store, JSON.stringify({ key, manifest, caseId: value.id }));
      setCurrent(value); setCases(old => [{ id: value.id, status: value.status, projectName: value.projectName, createdAt:value.createdAt, fileNames: value.documents.map(d => d.name) }, ...old.filter(c => c.id !== value.id)]); setEvidence(null); setUncertain(false);
    } catch (e) {
      const rejected = e instanceof ApiError && definiteUploadRejection(e.status);
      if (rejected && operationKey) {
        try { const saved = JSON.parse(localStorage.getItem(store) || '{}');
          if (saved.key === operationKey && !saved.caseId) localStorage.removeItem(store);
        } catch { /* An unreadable operation is not proof that it may be replaced. */ }
      }
      setUncertain(!rejected); setError(userFacingError(e, rejected ? '这批文件未接纳，请调整后重新选择' : '上传响应尚未确认，请重试同一批文件'));
    }
    finally { setBusy(false); }
  }
  async function showEvidence(fileId: string) {
    if (!current) return;
    try { setEvidence(await request<Evidence>(`${path}/${current.id}/files/${fileId}/evidence`)); }
    catch (e) { setError(userFacingError(e, '证据读取失败')); }
  }
  async function moreHistory() {
    if (!nextCursor || historyBusy) return;
    setHistoryBusy(true);const epoch=historyEpoch.current;
    try { const value = await request<HistoryPage>(`${path}/history`, { params: { before: nextCursor, ...historyParams() } });
      if(epoch!==historyEpoch.current)return;
      setCases(old => [...old, ...value.items.filter(c => !old.some(p => p.id === c.id))]); setNextCursor(value.nextCursor);
    } catch (e) { if(epoch===historyEpoch.current)setHistoryError(userFacingError(e, '历史案件读取失败，可重试')); }
    finally { if(epoch===historyEpoch.current)setHistoryBusy(false); }
  }
  const report = current?.report;
  const findings = Object.entries(report?.reviews ?? {}).flatMap(([module, review]) =>
    review.findings.map(finding => ({ ...finding, originalExplanation:finding.explanation, explanation:automaticCheckExplanation({...finding,status:'CONFLICT'}), module })))
    .filter((finding, index, all) => all.findIndex(other => other.title === finding.title && other.explanation === finding.explanation) === index)
    .sort((a, b) => (a.level === 'CRITICAL' ? 0 : 1) - (b.level === 'CRITICAL' ? 0 : 1));
  async function copy(key: string, value: string) {
    try { await copyText(value); setCopied(key); }
    catch { setError('复制未成功，请重试。'); }
  }
  async function open(id: string) {
    try { setCurrent(await request<Case>(`${path}/${id}`)); setEvidence(null); setCopied(''); setError(''); }
    catch (error) { setError(userFacingError(error, '结果读取失败，请重试')); }
  }
  const visibleCases = cases;
  const reportText = findings.map(f => `${f.title}\n${f.explanation}\n建议：${f.suggestion}\n涉及材料：${[...new Set(f.evidence.map(e => e.fileName))].join('、')}`).join('\n\n');
  return <section className="result-workspace mx-auto max-w-[1440px] space-y-5 p-4 sm:p-6" aria-label="采购材料批次自动审查">
    <header className="flex flex-wrap items-center justify-between gap-3">
      <div><h1 className="text-xl font-semibold text-slate-900">采购审查</h1><p className="mt-1 text-sm text-slate-500">上传同一项目的材料，集中查看审查结果</p></div>
      <div className="flex gap-2">
        {current ? <button type="button" className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm" onClick={() => { setCurrent(null); setEvidence(null); setError(''); }}>返回{historyOpen ? '历史结果' : '采购首页'}</button>
          : <button type="button" className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm" onClick={() => { setHistoryOpen(!historyOpen); setError(''); }}>{historyOpen ? '返回采购首页' : '历史结果'}</button>}
      </div>
    </header>
    {error && <p role="alert" className="rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700">{error}</p>}
    {!current && !historyOpen && <div className="rounded-xl border border-slate-200 bg-white p-6 sm:p-8">
      <h2 className="text-base font-semibold text-slate-800">上传采购材料</h2>
      <p className="mb-5 mt-2 text-sm leading-6 text-slate-500">可将招标文件、需求表、预算文件、中标通知书和供应商报价一起上传。选择文件后自动开始，无需手动填写。</p>
      <label onDragOver={e => { e.preventDefault(); if (!busy) setDragging(true); }} onDragLeave={() => setDragging(false)}
        onDrop={e => { e.preventDefault(); setDragging(false); if (!busy) void upload(Array.from(e.dataTransfer.files)); }}
        className={`flex min-h-44 cursor-pointer flex-col items-center justify-center gap-3 rounded-xl border-2 border-dashed p-6 text-center ${dragging ? 'border-blue-500 bg-blue-50' : 'border-slate-300 bg-slate-50'}`}>
        <input type="file" multiple accept={OFFICE_MATERIAL_ACCEPT} disabled={busy} className="sr-only" aria-label="选择一批采购材料并自动开始审查"
          onChange={e => { const selected = Array.from(e.target.files || []); e.target.value = ''; void upload(selected); }} />
        <span className="text-base font-medium text-slate-800">{busy ? '正在上传…' : '点击选择或拖放一批文件'}</span>
        <span className="text-sm text-slate-500">支持常用办公文档、PDF 和扫描图片</span>
      </label>
    </div>}
    {busy && <div role="status" aria-live="polite" className="rounded-lg border border-blue-200 bg-blue-50 p-4 text-sm text-blue-800">
      正在上传本批材料{uploadProgress ? ` ${Math.min(100, Math.floor(uploadProgress.loaded / uploadProgress.total * 100))}%` : '…'}
    </div>}
    {uncertain && files.length > 0 && <button type="button" className="rounded border px-4 py-2 text-sm" disabled={busy} onClick={() => void upload(files)}>继续原批上传</button>}
    {!current && historyOpen && <section className="space-y-4 rounded-xl border border-slate-200 bg-white p-5" aria-label="采购历史结果">
      {historyError && <p role="alert" className="text-sm text-red-700">{historyError}</p>}
      <div className="flex flex-wrap items-end gap-3">
        <label className="text-sm text-slate-600">上传日期<input type="date" value={date} onChange={e => setDate(e.target.value)} className="mt-1 block rounded-lg border border-slate-300 px-3 py-2" /></label>
        <label className="text-sm text-slate-600">项目或材料名称<input value={search} maxLength={160} onChange={e => setSearch(e.target.value)} placeholder="输入名称关键词" className="mt-1 block w-60 rounded-lg border border-slate-300 px-3 py-2" /></label>
        {(search || date) && <button type="button" className="rounded-lg border px-3 py-2 text-sm" onClick={() => {setSearch('');setDate('');}}>清空</button>}
      </div>
      <div className="grid gap-3 lg:grid-cols-2">{visibleCases.map(c => <button type="button" key={c.id} data-procurement-case-id={c.id} onClick={() => void open(c.id)} className="rounded-lg border border-slate-200 p-4 text-left hover:border-blue-300 hover:bg-blue-50">
        <p className="font-medium text-slate-900">{c.projectName || c.fileNames[0] || '采购材料分析'}</p>
        <p className="mt-2 text-xs text-slate-500">{c.createdAt ? new Date(c.createdAt).toLocaleString() : ''} · {c.fileNames.length} 份材料 · {['QUEUED','RUNNING','UPLOADING'].includes(c.status) ? '正在处理' : '处理已结束'}</p>
        <p className="mt-2 truncate text-xs text-slate-500" title={c.fileNames.join('、')}>{c.fileNames.join('、')}</p>
      </button>)}</div>
      {!visibleCases.length && <p className="py-5 text-center text-sm text-slate-500">暂无符合条件的记录{nextCursor ? '，可继续读取更多历史结果。' : '。'}</p>}
      {nextCursor && <button type="button" className="rounded-lg border px-4 py-2 text-sm" disabled={historyBusy} onClick={() => void moreHistory()}>{historyBusy ? '正在读取…' : '更多历史结果'}</button>}
    </section>}
    {current && <>
      <ResultProjectHeader kind="采购审查结果" name={current.projectName || report?.projectName || '采购材料分析'} metadata={<>{current.documents.length} 份材料{current.createdAt ? ` · ${new Date(current.createdAt).toLocaleString('zh-CN')}` : ''}</>}
        actions={caseProcessing?<span role="status" className="text-sm text-blue-700">正在分析…</span>:undefined}>
        {[['预算金额',report?.budget],['最高限价',report?.ceilingAmount],['中标金额',report?.awardAmount]].map(([label,value])=><div key={label}><p>{label}</p><p>{value==null?'—':`${Number(value).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2})} 元`}</p></div>)}
      </ResultProjectHeader>
      {report && <section key={current.id} aria-label="本批采购审查结果" className="space-y-5">
        <ResultSection title="优先关注" number="01" tone={findings.length||report.quoteComparisons?.some(c=>c.status==='OVER_BUDGET')||report.ceilingComparisons?.some(c=>c.status==='OVER_CEILING')?'attention':'default'} actions={<>
          {findings.length > 0 && <button type="button" onClick={() => void copy('all',reportText)} className="delivery-secondary-action">{copied === 'all' ? '已复制' : '复制问题清单'}</button>}
          <a href={apiUrl(`${path}/${current.id}/report.html`)} className="delivery-secondary-action" download>导出完整报告</a></>}>
        {findings.length ? <div className="result-two-columns">{findings.map((finding,index) => <article key={`${finding.module}-${index}`} className={`rounded-lg border p-4 ${finding.level === 'CRITICAL' ? 'border-red-200 bg-red-50' : 'border-amber-200 bg-amber-50'}`}>
          <div className="flex items-start justify-between gap-3"><strong className="text-sm text-slate-900">{finding.title}</strong><button type="button" className="shrink-0 rounded border border-slate-300 bg-white px-2 py-1 text-xs" onClick={() => void copy(`finding-${index}`,`${finding.explanation}\n${finding.suggestion}`)}>{copied === `finding-${index}` ? '已复制' : '复制'}</button></div>
          {finding.level === 'CRITICAL' && current.findingIds?.[`${finding.title}\n${finding.originalExplanation}`] && (()=>{
            const key=current.findingIds![`${finding.title}\n${finding.originalExplanation}`];
            return <button type="button" disabled={acknowledged.includes(key)||!!ackBusy} className="mt-2 rounded border border-red-300 bg-white px-3 py-1 text-xs text-red-700 disabled:opacity-60" onClick={()=>void acknowledge(key)}>{acknowledged.includes(key)?'已确认阅悉':ackBusy===key?'正在保存…':'确认已阅'}</button>;
          })()}
          <p className="mt-2 text-sm leading-6 text-slate-700">{finding.explanation}</p><p className="mt-2 text-sm leading-6 text-slate-600">建议：{finding.suggestion}</p>
          <p className="mt-3 text-xs text-slate-500">涉及材料：{[...new Set(finding.evidence.map(e => e.fileName))].join('、')}</p>
          <details className="mt-2 text-xs text-slate-600"><summary>查看依据</summary>{finding.evidence.map(source => <blockquote className="mt-2 whitespace-pre-wrap" key={source.id}>{source.fileName}{'\n'}{source.excerpt}</blockquote>)}</details>
        </article>)}</div> : report.quoteComparisons?.some(c=>c.status==='OVER_BUDGET')||report.ceilingComparisons?.some(c=>c.status==='OVER_CEILING') ? <div className="result-issue"><p className="text-sm font-medium leading-7 text-amber-900">{automaticPrioritySummary(caseProcessing,report.quoteComparisons??[],report.ceilingComparisons??[])}</p></div> : <ResultEmpty>{automaticPrioritySummary(caseProcessing, report.quoteComparisons ?? [], report.ceilingComparisons ?? [])}</ResultEmpty>}
        </ResultSection>
        <ResultSection title="报价比对" number="02" tone={report.offers.some(o=>o.lineValidation==='CONFLICT')||report.quoteComparisons?.some(c=>c.status==='OVER_BUDGET')||report.ceilingComparisons?.some(c=>c.status==='OVER_CEILING')||report.reviews?.BLACKLIST?.checks.some(c=>c.status==='BLOCKED')?'attention':report.offers.length>0&&report.offers.every(o=>report.quoteComparisons?.some(c=>c.documentId===o.documentId&&c.status==='WITHIN_BUDGET'))?'clear':'default'}>{report.offers.length===0?<ResultEmpty>{caseProcessing?'正在分析…':'材料不足，未生成'}</ResultEmpty>:<div className="result-two-columns">{report.offers.map(o => {
          const budgetStatus=report.quoteComparisons?.find(c=>c.documentId===o.documentId)?.status;
          const ceilingStatus=report.ceilingComparisons?.find(c=>c.documentId===o.documentId)?.status;
          const over=budgetStatus==='OVER_BUDGET',overCeiling=ceilingStatus==='OVER_CEILING',lineConflict=o.lineValidation==='CONFLICT';
          const blocked = report.reviews?.BLACKLIST?.checks.some(c => c.status === 'BLOCKED' && c.explanation.startsWith(`${o.supplier}命中`));
          return <ResultItem key={o.documentId} tone={over||blocked||overCeiling||lineConflict?'attention':budgetStatus==='WITHIN_BUDGET'?'clear':'default'} title={`${o.supplier} · ${Number(o.amount).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2})} 元`} label={over?'超过预算':overCeiling?'超过限价':blocked?'黑名单命中':lineConflict?'明细计算不一致':budgetStatus==='WITHIN_BUDGET'?'未超过预算':undefined}><p className="text-sm font-medium">{blocked ? '黑名单命中，暂停推荐' : ''}{over ? ' · 超过材料预算' : budgetStatus==='WITHIN_BUDGET' ? '未超过材料预算' : ''}{overCeiling?' · 超过最高限价':ceilingStatus==='WITHIN_CEILING'?' · 未超过最高限价':''}</p>
            <p className="mt-1 text-xs text-slate-500">来源：{current.documents.find(d => d.id === o.documentId)?.name}</p>
            <details className="mt-2 text-xs"><summary>报价明细</summary>{o.lines.map((l,index) => <p key={index} className="mt-1">{l.item}：{l.quantity} × {l.unitPrice} = {l.amount} 元</p>)}</details></ResultItem>;
        })}</div>}</ResultSection>
        <div className="result-two-columns procurement-check-grid">{[['TENDER_COMPLIANCE','招标文件合规性审查'],['BLACKLIST','供应商黑名单比对'],['AGENT_PRECHECK','跨材料一致性核对']].map(([module,title],moduleIndex)=>{
          const review=report.reviews?.[module],checks=review?.checks.filter(c=>c.code!=='MATERIAL_COVERAGE')??[];
          const tone=review?.findings.length||checks.some(c=>automaticCheckTone(c.status)==='attention')?'attention':checks.length>0&&checks.every(c=>automaticCheckTone(c.status)==='clear')?'clear':'default';
          return <ResultSection key={module} title={title} tone={tone} number={String(moduleIndex+3).padStart(2,'0')}>
            {checks.length>0?<div className="result-check-list">{checks.map((check,index)=><ResultItem tone={automaticCheckTone(check.status)} title={check.code.startsWith('IDENTITY_')?automaticFactLabel(check.code.slice(9)):'核对意见'} label={automaticCheckTone(check.status)==='clear'?'未见异常':automaticCheckTone(check.status)==='attention'?'需关注':undefined} key={index}>{automaticCheckExplanation(check)}
              {check.evidence.length>0&&<p className="result-issue-source">涉及材料：{[...new Set(check.evidence.map(e=>e.fileName))].join('、')}</p>}
            </ResultItem>)}</div>:<ResultEmpty>{caseProcessing?'正在分析…':review&&['FAILED','UNKNOWN'].includes(review.status)?'本项暂未完成':'材料不足，未生成'}</ResultEmpty>}
          </ResultSection>;
        })}</div>

      </section>}
      <details className="rounded-xl border border-slate-200 bg-white p-5 text-sm"><summary className="cursor-pointer font-medium text-slate-700">本批材料（{current.documents.length} 份）</summary><ul className="mt-3 divide-y divide-slate-100">{current.documents.map(d => <li key={d.id} className="flex flex-wrap items-center justify-between gap-3 py-3"><span>{d.name}</span><div className="flex gap-3 text-xs"><button type="button" className="text-blue-700" onClick={() => void showEvidence(d.id)}>查看提取内容</button><a className="text-blue-700" href={apiUrl(`${path}/${current.id}/files/${d.id}/original`)}>原文件</a></div></li>)}</ul></details>
    </>}
    {evidence && <details open className="rounded-xl border border-slate-200 bg-white p-5"><summary className="text-sm font-medium">{evidence.fileName} · 提取内容</summary>
      <ul className="mt-3 space-y-3 text-sm">{evidence.facts.filter(f => f.verification === 'SOURCE_VERIFIED').map((f,index) => <li key={index}><strong>{automaticFactLabel(f.type)}：{f.value}</strong><blockquote className="mt-1 whitespace-pre-wrap text-xs leading-6 text-slate-500">{f.excerpt}</blockquote></li>)}</ul></details>}
  </section>;
}
