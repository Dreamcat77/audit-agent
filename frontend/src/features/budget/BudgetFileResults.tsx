import { useEffect, useRef, useState } from 'react';
import {ResultProjectHeader,ResultSection,ResultItem,ResultEmpty} from '../../components/results/ResultLayout';
import {ProcessingStatusCard} from '../../components/status/ProcessingStatusCard';
import * as XLSX from 'xlsx';
import { CircleAlert, FileSearch, FileText } from 'lucide-react';
import { getBudgetMaterialBatch, getBudgetReview } from '../../api';
import { ApiError } from '../../api/client';
import type { BudgetMaterialBatchView, BudgetReviewResponse } from '../../api';
import { budgetLineReference, groupBudgetProjects } from './budgetProjectGrouping';
import { budgetApprovalAmount, budgetCoverageLabel, budgetResultExplanation, budgetResultPending, budgetResultStatus } from './budgetResultState';
import { budgetAttention, budgetDifference, budgetDifferenceAmount, budgetMoney } from './budgetDifferences';
import { groupBudgetMaterialLines, mergeBudgetMaterialReferences } from './budgetBatchResult';
import { writeClipboardText } from '../../utils/clipboard';
import { mapConcurrent } from '../../utils/mapConcurrent';

const departmentName = (code: string | null) => (({ PROCUREMENT: '采购管理部', FINANCE: '财务部', GENERAL: '综合管理部' } as Record<string, string>)[code ?? ''] ?? '部门待核对');
const money = (value: number | null) => value == null ? '本批材料未找到' : `￥${budgetMoney(value)}`;

function ResultMessage({ title, description, pending = false, attention = false }: {
  title: string; description: string; pending?: boolean; attention?: boolean;
}) {
  if (pending) return <ProcessingStatusCard title={title} description={description} />;
  const Icon = attention ? CircleAlert : FileSearch;
  return <div className="budget-result-message">
    <span className={`budget-result-message-icon${attention ? ' budget-result-message-icon-attention' : ''}`}>
      <Icon size={24} aria-hidden="true" />
    </span>
    <div className="budget-result-message-content">
      <h3>{title}</h3><p>{description}</p>
    </div>
  </div>;
}

type BatchResultState = { batch: BudgetMaterialBatchView | null; reviews: BudgetReviewResponse[]; loading: boolean; unavailable: boolean };

function BatchOverview({ batch, lineCount, differenceCount, uncertainCount, showMetrics = true }: {
  batch: BudgetMaterialBatchView | null; lineCount: number; differenceCount: number; uncertainCount: number; showMetrics?: boolean;
}) {
  if (!batch) return null;
  return <div className="budget-batch-overview">
    {showMetrics && <dl className="budget-batch-metrics">
      {[[batch.files.length, '本批材料'], [lineCount, '预算科目'], [differenceCount, '差异项'], [uncertainCount, '信息待核对']].map(([count, label]) =>
        <div key={label}><dd>{count}</dd><dt>{label}</dt></div>)}
    </dl>}
    <details className="budget-batch-files"><summary>查看本批 {batch.files.length} 份材料及处理状态</summary>
      <ul>{batch.files.map((file, index) => <li key={`${file.taskId ?? 'pending'}-${index}`}>
        <span>{file.fileName}</span><span>{budgetResultStatus(file.status)}</span>
      </li>)}</ul>
    </details>
  </div>;
}

export function BudgetFileResults({ taskId, batchId, onComplete }: {
  taskId?: string;
  batchId?: string;
  onComplete?:()=>void;
}) {
  const lookupId = batchId ?? taskId;
  const [copied,setCopied]=useState('');
  const [copyError,setCopyError]=useState('');
  const copySequence=useRef(0);
  const copy=async(key:string,text:string)=>{const sequence=++copySequence.current;setCopied('');try{await writeClipboardText(text);if(sequence===copySequence.current){setCopied(key);setCopyError('');}}catch{if(sequence===copySequence.current)setCopyError('复制未成功，请重试。');}};
  const completed=useRef(onComplete);completed.current=onComplete;
  const [state, setState] = useState<BatchResultState>(
    { batch: null, reviews: [], loading: true, unavailable: false });
  useEffect(() => {
    let current = true;
    ++copySequence.current;setCopied('');setCopyError('');
    let timer: ReturnType<typeof setTimeout> | undefined;
    let failures = 0;
    if (!lookupId) return;
    setState({ batch: null, reviews: [], loading: true, unavailable: false });
    const load = () => { void getBudgetMaterialBatch(lookupId).catch(async error => {
      if (!(error instanceof ApiError && error.status === 404 && taskId && !batchId)) throw error;
      const legacyReview = await getBudgetReview(taskId!);
      const legacyBatch: BudgetMaterialBatchView = {
        batchId: taskId!, status: 'LEGACY', budgetYear: legacyReview.task.budgetYear,
        departmentCode: legacyReview.task.departmentCode, createdAt: legacyReview.task.createdTime,
        files: [{ taskId: taskId!, fileName: legacyReview.task.fileName, status: legacyReview.task.status }],
      };
      return legacyBatch;
    }).then(async batch => {
      const requests = await mapConcurrent(batch.files.filter(file => file.taskId),4,async file => {
        try { return { taskId: file.taskId!, review: await getBudgetReview(file.taskId!) }; }
        catch (error) { return { taskId: file.taskId!, error }; }
      });
      const reviews = requests.flatMap(result => 'review' in result ? [result.review] : []);
      const unavailable = requests.some(result => 'error' in result);
      if (current) {
        failures = 0;
        setState({ batch, reviews, loading: false, unavailable });
        const complete = batch.status === 'REVIEWED' && !unavailable && reviews.length === batch.files.length && reviews.every(review =>
          review.materialReference?.status === 'COMPLETED' || !review.materialReference);
        if (complete) completed.current?.();
        if (unavailable || batch.status === 'OPEN' || batch.status === 'PROCESSING' || reviews.some(review =>
          review.materialReference?.status === 'PENDING' || !review.materialReference && budgetResultPending(review.task.status))) timer = setTimeout(load, 3000);
      }
    }).catch(error => {
      if (!current) return;
      setState(previous => ({ ...previous, loading: false, unavailable: true }));
      // Repeat only the read, without resubmitting extraction or changing identity.
      // Authentication, permission and missing-resource errors need user action.
      if (error instanceof ApiError && (error.code === 'NETWORK_ERROR' || error.code === 'SERVER_ERROR'
        || error.status === 408 || error.status === 429)) {
        timer = setTimeout(load, Math.min(30000, 3000 * 2 ** Math.min(failures++, 4)));
      }
    });
    };
    load();
    return () => { current = false; ++copySequence.current;clearTimeout(timer); };
  }, [lookupId, taskId, batchId]);
  const groups = groupBudgetProjects(state.reviews);
  const incompleteFiles = state.batch?.files.filter(file => ['FAILED', 'UNKNOWN'].includes(file.status)) ?? [];
  const partial = state.unavailable || incompleteFiles.length > 0 || state.reviews.some(review =>
    review.task.status !== 'REVIEWED' || review.items.length !== review.task.parsedLineCount);
  const exportResult = () => {
    const rows = groups.flatMap(group => group.lines.map(line => ({
      '项目编号': group.projectCode ?? '项目待核对', '预算年度': group.year ?? '年度待核对',
      '申报部门': departmentName(group.department), '预算科目': line.item.subjectName ?? '科目待核对',
      '科目编码': line.item.subjectCode ?? '', '申报金额': line.item.declaredAmount,
      '批复金额': line.item.approvalAmount, '核对意见': budgetLineReference(line),
      '来源材料': line.sources.map(source => `${source.fileName}${source.rowNumber == null ? '' : `（第${source.rowNumber}行）`}`).join('；'),
    })));
    const workbook = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(workbook, XLSX.utils.json_to_sheet(rows), '预算参考结果');
    XLSX.writeFile(workbook, '预算材料参考结果.xlsx');
  };
  const fileTask = state.reviews[0]?.task;
  const references = state.reviews.map(review => review.materialReference).filter(Boolean) as NonNullable<BudgetReviewResponse['materialReference']>[];
  const reference = mergeBudgetMaterialReferences(references);
  const referenceGroups = groupBudgetMaterialLines(reference?.lines ?? []);
  const differences = reference?.lines.filter(line => budgetDifference(line.status)) ?? [];
  const uncertainCount = reference?.lines.filter(line => budgetAttention(line).tone === 'uncertain').length ?? 0;
  const batchFiles = state.batch?.files ?? [];
  const batchFileNames = [...new Set(batchFiles.map(file => file.fileName).filter(Boolean))];
  const processingExplanation = fileTask ? budgetResultExplanation(fileTask.status,fileTask.errorDetail) : null;
  const pending = state.loading || reference?.status === 'PENDING' || state.batch?.status === 'OPEN' || state.batch?.status === 'PROCESSING'
    || !!fileTask && !reference && budgetResultPending(fileTask.status);
  const messageTitle = pending ? '正在整理预算结果'
    : state.unavailable ? '暂时无法读取结果'
    : incompleteFiles.length ? '本批部分材料结果待核实'
    : processingExplanation ? '本次未生成分析结果'
    : fileTask?.status === 'WAITING_CONFIRMATION' ? '材料信息需要核对'
    : '本批材料暂无可展示的预算结果';
  const exportReference = () => {
    if(!reference)return;
    const workbook=XLSX.utils.book_new();
    const rows=reference.lines.map(line=>({
      '项目':line.project??'—','预算科目':line.subject??line.subjectCode??'—','年度':line.year??'—','部门':line.department??'—',
      '申报金额（元）':line.declaredAmount,'材料批复金额（元）':line.approvalAmount,'差异类型':budgetDifference(line.status)??'','差额（元）':budgetDifferenceAmount(line.declaredAmount,line.approvalAmount),'核对意见':line.opinion,
      '来源材料':Array.from(new Set(line.sources.map(source=>`${source.fileName}（${source.locator}）`))).join('；'),
    }));
    XLSX.utils.book_append_sheet(workbook,XLSX.utils.json_to_sheet(rows),'预算参考结果');
    XLSX.utils.book_append_sheet(workbook,XLSX.utils.json_to_sheet(rows.filter(row=>!!row['差异类型']),{header:['项目','预算科目','年度','部门','申报金额（元）','材料批复金额（元）','差异类型','差额（元）','核对意见','来源材料']}),'差异清单');
    XLSX.utils.book_append_sheet(workbook,XLSX.utils.json_to_sheet((reference.assessments??[]).map(item=>({'审查事项':item.title,'风险等级':item.level==='WARNING'?'需关注':'参考','核对结果':item.explanation,'依据':item.basis,'涉及材料':[...new Set(item.sources.map(s=>s.fileName))].join('；')}))),'风险及审批核对');
    XLSX.writeFile(workbook,'预算材料参考结果.xlsx');
  };
  const projectNames=[...new Set(referenceGroups.map(group=>group.project).filter(Boolean))];
  const reportName=projectNames.length?projectNames.slice(0,2).join('、')+(projectNames.length>2?` 等${projectNames.length}个项目`:''):'预算材料分析';
  if(pending)return <section aria-label="预算文件结果" className="budget-results-panel result-workspace space-y-5">
    <ResultProjectHeader kind="预算审查结果" name={reportName} metadata={batchFiles.length?`${batchFiles.length} 份材料`:undefined}
      actions={<ProcessingStatusCard variant="compact" title="正在分析材料" description="正在提取文件内容并核对预算信息，请稍候。" />} />
  </section>;
  if(reference)return <section aria-label="预算文件结果" className="budget-results-panel result-workspace space-y-5">
    <ResultProjectHeader kind="预算审查结果" name={reportName} metadata={<>{batchFiles.length} 份材料{state.batch?.createdAt?` · ${new Date(state.batch.createdAt).toLocaleString('zh-CN')}`:''}</>}
      actions={reference.lines.length>0?<button type="button" onClick={exportReference} className="delivery-secondary-action">导出</button>:undefined}>
      {[[reference.lines.length,'预算科目'],[differences.length,'差异项'],[uncertainCount,'信息待核对']].map(([count,label])=><div key={label}><p>{label}</p><p>{count} 项</p></div>)}
    </ResultProjectHeader>
    {state.unavailable&&<p role="alert" className="text-sm text-amber-800">部分结果暂时无法读取，当前展示并不完整。</p>}
    {incompleteFiles.length>0&&<details className="result-source-list"><summary>{incompleteFiles.length} 份材料未生成完整结果</summary><ul className="mt-3 space-y-2 text-sm">{incompleteFiles.map(file=><li key={file.taskId??file.fileName}>{file.fileName}：{budgetResultStatus(file.status)}</li>)}</ul></details>}
    {copyError&&<p role="alert" className="text-sm text-red-700">{copyError}</p>}
    <ResultSection title="差异清单" label="预算差异清单" number="01" tone={differences.length?'attention':reference.lines.length>0&&!uncertainCount&&!pending&&!partial?'clear':'default'}>
      {differences.length===0?<ResultEmpty>{pending?'正在核对…':reference.lines.length===0?'材料不足，未生成':'已核对信息中暂无明确差异'}</ResultEmpty>:<ul className="result-check-list">{differences.map((line,index)=><li key={index} className="result-check-item text-red-700"><strong>{line.project?`${line.project} · `:''}{line.subject??line.subjectCode??'预算科目'} · {budgetDifference(line.status)}</strong><p>{budgetAttention(line).headline}</p><p className="result-issue-source">涉及材料：{[...new Set(line.sources.map(source=>source.fileName))].join('、')}</p></li>)}</ul>}
    </ResultSection>
    <ResultSection title="预算科目与批复比对" number="02" tone={differences.length||uncertainCount?'attention':reference.lines.length>0&&!pending&&!partial?'clear':'default'}>
      {referenceGroups.length===0?<ResultEmpty>{pending?'正在整理…':'材料不足，未生成'}</ResultEmpty>:referenceGroups.map(group=><div key={group.key} className="budget-project-result-group">
        <div className="budget-project-result-heading"><h4>{group.project??'预算科目'}</h4><p>{[group.year?`${group.year} 年度`:null,group.department].filter(Boolean).join(' · ')}</p></div>
        <div className="result-two-columns">{group.lines.map((line,index)=>{
          const attention=budgetAttention(line),difference=attention.tone==='difference';
          const missingDeclaration=line.status==='MISSING_DECLARATION',missingApproval=line.status==='UNAPPROVED_SUBJECT';
          return <div key={index} data-budget-difference={difference}><ResultItem tone={difference||attention.tone==='uncertain'?'attention':'clear'} title={<h4>{line.subject||'预算科目'}{line.subjectCode?`（${line.subjectCode}）`:''}</h4>} label={attention.label}>
            <dl className="budget-amount-comparison"><div><dt>申报金额（元）</dt><dd>{line.declaredAmount==null&&missingDeclaration?'未找到该科目申报':money(line.declaredAmount)}</dd></div><div><dt>材料批复金额（元）</dt><dd>{line.approvalAmount==null&&missingApproval?'未找到该科目批复':money(line.approvalAmount)}</dd></div></dl>
            <p className={`budget-result-headline mt-3 text-base font-semibold ${difference?'text-red-700':'text-slate-800'}`}>{attention.headline}</p><p className="mt-2 text-sm leading-7 text-slate-600">{attention.action}</p>
            {attention.tone==='uncertain'&&<p className="mt-2 text-sm leading-7 text-slate-600">{line.opinion}</p>}
            <p className="result-issue-source">来源材料：{[...new Set(line.sources.map(source=>source.fileName))].join('、')}</p>
          </ResultItem></div>;
        })}</div>
      </div>)}
    </ResultSection>
    <ResultSection title="风险与审批核对" label="预算风险与审批核对" number="03" tone={reference.assessments?.some(item=>item.level==='WARNING')?'attention':'default'}>
      {(reference.assessments??[]).length===0?<ResultEmpty>{pending?'正在分析…':'材料不足，未生成'}</ResultEmpty>:<div className="result-two-columns">{reference.assessments!.map((item,index)=><ResultItem key={index} title={item.title} tone={item.level==='WARNING'?'attention':'default'} label={item.level==='WARNING'?'需关注':'参考内容'}>
        <div className="flex justify-end"><button type="button" className="shrink-0 rounded border border-slate-300 bg-white px-2 py-1 text-xs" onClick={()=>void copy(`assessment-${index}`,`${item.explanation}\n依据：${item.basis}`)}>{copied===`assessment-${index}`?'已复制':'复制'}</button></div>
        <p className="mt-2 text-sm leading-7 text-slate-700">{item.explanation}</p><p className="mt-2 text-xs leading-6 text-slate-600">依据：{item.basis}</p><p className="result-issue-source">涉及材料：{[...new Set(item.sources.map(source=>source.fileName))].join('、')}</p>
        <details className="mt-2 text-xs"><summary>查看原文依据</summary>{item.sources.map((source,i)=><blockquote key={i} className="mt-2 whitespace-pre-wrap text-slate-600">{source.fileName}{'\n'}{source.excerpt}</blockquote>)}</details>
      </ResultItem>)}</div>}
    </ResultSection>
    <BatchOverview batch={state.batch} lineCount={reference.lines.length} differenceCount={differences.length} uncertainCount={uncertainCount} showMetrics={false}/>
  </section>;
  if(groups.length===0)return <section aria-label="预算文件结果" className="budget-results-panel result-workspace space-y-5">
    <ResultProjectHeader kind="预算审查结果" name="预算材料分析" metadata={state.batch?`${batchFiles.length} 份材料`:undefined}/>
    {(pending||state.unavailable)&&<ResultMessage title={pending?'正在整理预算结果':'暂时无法读取结果'} description={pending?'请稍候。':'请稍后重新打开。'} pending={pending} attention={state.unavailable}/>}
    {['差异清单','预算科目与批复比对','风险与审批核对'].map((title,index)=><ResultSection key={title} title={title} number={String(index+1).padStart(2,'0')}><ResultEmpty>{pending?'正在整理…':state.unavailable?'本项暂未完成':'材料不足，未生成'}</ResultEmpty></ResultSection>)}
    <BatchOverview batch={state.batch} lineCount={0} differenceCount={0} uncertainCount={0} showMetrics={false}/>
  </section>;
  return <section aria-label="预算文件结果" className="budget-results-panel space-y-5">
    <div className="budget-results-panel-heading flex flex-wrap items-center justify-between gap-3">
      <div className="budget-results-panel-title"><span className="budget-results-file-icon"><FileText size={20} aria-hidden="true" /></span><div><h2 className="break-words text-lg font-bold text-slate-800">本批预算材料参考结果</h2><p className="mt-1 break-words text-xs text-slate-500">{batchFiles.length || batchFileNames.length ? `${batchFiles.length || batchFileNames.length} 份材料：${batchFileNames.slice(0, 4).join('、')}${batchFileNames.length > 4 ? ` 等 ${batchFileNames.length} 份` : ''}` : state.loading ? '正在读取材料清单…' : state.unavailable ? '批次材料暂时不可读取' : '暂无材料信息'}</p></div></div>
      {!state.loading && groups.length > 0 && <button type="button" onClick={exportResult} className="rounded-xl border border-slate-200 bg-white px-4 py-2 text-sm text-slate-600">导出</button>}
    </div>
    <BatchOverview batch={state.batch} lineCount={0} differenceCount={0} uncertainCount={0} showMetrics={false} />
    {state.loading ? <ResultMessage title={messageTitle} description="正在整理预算结果，请稍候。" pending /> : groups.length === 0 ? <ResultMessage title={messageTitle}
      description={state.unavailable ? '暂时无法读取完整结果，请稍后重新打开。'
        : incompleteFiles.length ? `本批有 ${incompleteFiles.length} 份材料结果待核实；请检查下方材料状态后再判断整批结果。`
        : processingExplanation ?? (fileTask && budgetResultPending(fileTask.status) ? `这份材料${budgetResultStatus(fileTask.status)}，完成后会自动显示结果。`
        : fileTask && fileTask.status !== 'REVIEWED' ? '本批材料尚未完成核对，请稍后重新查看。' : '尚无可展示的预算结果。')}
      pending={pending} attention={state.unavailable || !!processingExplanation} /> : <>
      <p className="text-sm leading-7 text-slate-600">{groups.length > 0
        ? '已按材料中的项目编号整理预算科目及核对意见，供工作参考。来源材料保留在各科目下，便于核对。'
        : state.unavailable ? '暂时无法读取完整结果，请稍后重新打开。'
          : processingExplanation ? processingExplanation
              : fileTask && budgetResultPending(fileTask.status) ? `这份材料${budgetResultStatus(fileTask.status)}，完成后会自动显示结果。`
                : fileTask && fileTask.status !== 'REVIEWED' ? '这份材料尚未完成核对，请稍后重新查看。'
                : '尚无可展示的预算结果。'}
        {partial && groups.length > 0 && '部分材料尚未完成核对，以下结果仅包含目前能够读取的信息。'}
      </p>
      {state.unavailable && <p role="alert" className="text-sm text-amber-700">部分结果暂时无法读取，当前展示并不完整。</p>}
      {groups.map(group => <article key={group.key} className="rounded-2xl border border-slate-200 bg-white p-5 sm:p-7">
        <h3 className="text-base font-semibold text-slate-800">{group.projectCode ? `项目：${group.projectCode}` : '项目待核对'}</h3>
        <p className="mt-2 text-sm text-slate-500">{group.year ? `${group.year} 年度` : '年度待核对'} · {departmentName(group.department)}</p>
        <p className="mt-3 text-sm leading-7 text-slate-600">{group.conflictingAmounts || group.lines.some(line => budgetLineReference(line) !== '已检查的信息与预算批复一致，申报金额未超过对应额度。')
          ? '该项目有需要进一步核对的内容，请重点参考下列科目意见。'
          : '已检查的预算科目与批复一致，申报金额未超过对应额度。'}
          {group.conflictingAmounts && '不同材料中同一科目的申报金额不一致，已保留各项记录，请核对适用版本。'}
        </p>
        <div className="mt-4 space-y-4">{group.lines.map((line, index) => <ResultItem key={`${line.item.id}-${index}`} title={`${line.item.subjectName || '科目名称待核对'}${line.item.subjectCode ? `（${line.item.subjectCode}）` : ''}`} tone={budgetLineReference(line)==='已检查的信息与预算批复一致，申报金额未超过对应额度。'?'clear':'attention'}>
          <p className="mt-2 text-sm text-slate-600">申报金额：{money(line.item.declaredAmount)}；系统批复金额：{budgetApprovalAmount(line.item.approvalAmount, line.item.matchStatus)}</p>
          <p className="mt-2 text-sm leading-7 text-slate-600">{budgetLineReference(line)}</p>
          <p className="mt-2 break-words text-xs leading-6 text-slate-500">来源材料：{line.sources.map(source => `${source.fileName}${source.rowNumber == null ? '' : `（第${source.rowNumber}行）`}`).join('、')}</p>
        </ResultItem>)}</div>
      </article>)}
    </>}
  </section>;
}
