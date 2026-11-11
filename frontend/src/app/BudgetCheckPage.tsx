import {ProcessingStatusCard} from '../components/status/ProcessingStatusCard';
import {
  Fragment,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import {
  ArrowRight,
  AlertTriangle,
  Brain,
  CheckCircle,
  ChevronDown,
  ChevronUp,
  Download,
  FileSpreadsheet,
  Loader2,
  ScanLine,
  Search,
  SkipForward,
  Upload,
} from 'lucide-react';
import * as XLSX from 'xlsx';
import { createOperationKey } from '../api/operationKey';
import {
  confirmBudgetRow,
  getBudgetConfirmLogs,
  getBudgetReview,
  reviewBudgetMaterial,
} from '../api';
import type {
  BudgetConfirmLog,
  BudgetReviewItemResponse,
  BudgetReviewResponse,
  BudgetReviewTaskResponse,
  BudgetReviewCheckResponse,
  BudgetConfirmationProgress,
  ValidationModuleGroup,
} from '../api';
import { BudgetProjectRiskPanel } from '../features/budget/f05/BudgetProjectRiskPanel';
import { useAuth } from '../auth/AuthProvider';
import { useBudgetMaterialBatch } from '../features/budget/budgetMaterialBatchStore';
import { budgetResultStatus } from '../features/budget/BudgetResultHistory';
import { OFFICE_MATERIAL_ACCEPT } from '../features/procurement/materials/officeMaterialTypes';
import { BudgetHistoryResults } from '../features/budget/BudgetHistoryResults';
import type { BudgetMaterialBatchHistoryRecord } from '../api';
import { BudgetFileResults } from '../features/budget/BudgetFileResults';
import { budgetBatchResultState } from '../features/budget/budgetResultState';
import { sameMaterialFile } from '../features/procurement/materials/procurementMaterialOperation';
import { collectProcurementDrop } from '../features/procurement/materials/procurementFolderDrop';

const CHECK_TYPE_LABEL: Record<string, string> = {
  REQUIRED_FIELDS: '必填字段完整性',
  AMOUNT_FORMAT: '金额格式校验',
  SUBJECT_CODE: '科目编码比对',
  SUBJECT_NAME: '科目名称比对',
  CATEGORY_ATTRIBUTION: '科目分类归属',
  BUDGET_AMOUNT: '预算金额比对',
  SUPPLIER_NAME: '供应商名称比对',
  PROJECT_CODE: '项目编号校验',
  PROJECT_DEPARTMENT: '项目与部门匹配性',
  BUDGET_YEAR_VALIDITY: '预算年度有效性',
};
const CHECK_TYPE_ORDER = [
  'REQUIRED_FIELDS',
  'AMOUNT_FORMAT',
  'SUBJECT_CODE',
  'SUBJECT_NAME',
  'CATEGORY_ATTRIBUTION',
  'BUDGET_AMOUNT',
  'SUPPLIER_NAME',
  'PROJECT_CODE',
  'PROJECT_DEPARTMENT',
  'BUDGET_YEAR_VALIDITY',
];
const CHECK_REVEAL_INTERVAL_MS = 300;
const COMPLETION_HOLD_MS = 1200;

const MATCH_STATUS_LABEL: Record<string, string> = {
  MATCHED: '匹配成功',
  SUSPECTED: '疑似匹配',
  UNMATCHED: '匹配失败',
  DATA_ERROR: '数据异常',
  NOT_CHECKED: '未校验',
};

const AMOUNT_STATUS_LABEL: Record<string, string> = {
  PASSED: '金额正常',
  OVER_BUDGET: '超预算',
  NEED_CONFIRMATION: '待人工确认',
  NOT_CHECKED: '未校验',
  DATA_FORMAT_ERROR: '金额格式异常',
};
const CONFIRM_STEP_LABEL: Record<string, string> = {
  ROW_REVIEW: '整行异常确认',
  DATA_VALIDATION: '数据规范性校验',
  SUBJECT_CONSISTENCY: '科目一致性校验',
  AMOUNT_COMPLIANCE: '金额合规性校验',
  SUPPLIER_CHECK: '关联方校验',
  PROJECT_COMPLIANCE: '项目合规性校验',
};

function formatAmount(value: number | null): string {
  return value === null || value === undefined ? '-' : `￥${value.toFixed(2)}`;
}

function formatMoneyText(value: string | null): string {
  if (value === null || value.trim() === '') return '-';
  const normalized = value.replace(/,/g, '').trim();
  const numeric = Number(normalized.replace(/^[￥¥]/, ''));
  return Number.isFinite(numeric)
    ? formatAmount(numeric)
    : /^[￥¥]/.test(normalized) ? normalized : `￥${normalized}`;
}

function formatSignedAmount(value: number | null): string {
  if (value === null || value === undefined) return '-';
  const amount = formatAmount(Math.abs(value));
  return value > 0 ? `+${amount}` : value < 0 ? `-${amount}` : amount;
}

function departmentLabel(code: string | null): string {
  return code === 'PROCUREMENT' ? '采购管理部'
    : code === 'FINANCE' ? '财务部'
      : code === 'GENERAL' ? '综合管理部'
        : code || '-';
}

function rowResult(item: BudgetReviewItemResponse): {
  label: string;
  className: string;
  icon: string;
} {
  if (item.riskLevel === 'INFO') {
    return {
      label: '通过',
      className: 'border-emerald-200 bg-emerald-50 text-emerald-700',
      icon: '✓',
    };
  }
  if (item.riskLevel === 'WARNING') {
    return {
      label: '异常',
      className: 'border-amber-200 bg-amber-50 text-amber-700',
      icon: '!',
    };
  }
  return {
    label: item.amountStatus === 'OVER_BUDGET' ? '严重超预算' : '异常',
    className: 'border-red-200 bg-red-50 text-red-700',
    icon: '!',
  };
}

function statusClass(status: string): string {
  if (status === 'MATCHED' || status === 'PASSED') {
    return 'bg-emerald-50 text-emerald-700 border-emerald-200';
  }
  if (status === 'NOT_CHECKED') {
    return 'bg-slate-500/15 text-slate-300 border-slate-500/30';
  }
  if (status === 'SUSPECTED' || status === 'NEED_CONFIRMATION') {
    return 'bg-amber-50 text-amber-700 border-amber-200';
  }
  return 'bg-red-50 text-red-700 border-red-200';
}

function riskClass(riskLevel: string): string {
  if (riskLevel === 'CRITICAL') return 'border-red-200 bg-red-50 text-red-700';
  if (riskLevel === 'WARNING') return 'border-amber-200 bg-amber-50 text-amber-700';
  return 'border-emerald-200 bg-emerald-50 text-emerald-700';
}

function riskLabel(riskLevel: string): string {
  return riskLevel === 'CRITICAL' ? '严重' : riskLevel === 'WARNING' ? '警告' : '正常';
}

function getPageScrollContainer(): HTMLElement | null {
  return document.querySelector<HTMLElement>('[data-page-scroll-container], .portal-main');
}

export function BudgetCheckPage({
  onOpenProcurement,
}: {
  onOpenProcurement?: (projectId: string) => void;
}) {
  const {user}=useAuth();
  const {batch,snapshot}=useBudgetMaterialBatch(user?.userId??'');
  const [files, setFiles] = useState<File[]>([]);
  const file=files[0]??null;
  const [selectedId,setSelectedId]=useState<string|null>(null);
  const [selectionVersion,setSelectionVersion]=useState(0);
  const viewEpoch=useRef(0);
  const [historyOpen,setHistoryOpen]=useState(false);
  const [historyEntry,setHistoryEntry]=useState<BudgetMaterialBatchHistoryRecord|null>(null);
  const [resultLoading,setResultLoading]=useState(false);
  const [submittedBatch,setSubmittedBatch]=useState<{keys:string[];epoch:number}|null>(null);
  const [uploadNotice,setUploadNotice]=useState<string|null>(null);
  const [currentResultId,setCurrentResultId]=useState<string|null>(null);
  useLayoutEffect(()=>{
    if(currentResultId){const container=getPageScrollContainer();if(container)container.scrollTop=0;}
  },[currentResultId]);
  const [completedResults,setCompletedResults]=useState<{id:string;name:string}[]>([]);
  const [riskFocus,setRiskFocus]=useState<{token:string;taskIds:string[]}|null>(null);
  const parsed=snapshot.entries.filter(row=>row.phase==='PARSED'&&row.result);
  const processing=snapshot.entries.filter(row=>['WAITING_UPLOAD','UPLOADING','QUEUED','PROCESSING'].includes(row.phase));
  const batchBusy=snapshot.uploading||processing.length>0;
  const progressEntries=submittedBatch
    ?snapshot.entries.filter(row=>submittedBatch.keys.includes(row.operation.key))
    :processing;
  const overallProgressStatus=(()=>{
    if(progressEntries.some(row=>row.phase==='WAITING_UPLOAD'||row.phase==='UPLOADING'))return '正在上传本批材料，请稍候。';
    if(progressEntries.some(row=>row.phase==='QUEUED'||row.phase==='PROCESSING'))return '正在解析本批材料，可离开本页，结果会自动保存。';
    if(progressEntries.some(row=>row.phase==='UNKNOWN'))return '本批处理已结束，部分材料的结果暂时无法确认，请在历史结果中核实。';
    if(progressEntries.some(row=>row.phase==='FAILED'))return '本批处理已结束，部分材料未能生成结果。';
    if(progressEntries.length)return '本批材料解析已完成，正在整理预算结果。';
    return '正在处理本批材料，请稍候。';
  })();
  const canViewUploadedHistory=!files.length&&!batchBusy&&snapshot.entries.some(row=>
    row.result&&['PARSED','FAILED','UNKNOWN'].includes(row.phase));
  const selected=parsed.find(row=>row.result?.id===selectedId);
  const alive=useRef(true);
  useEffect(()=>{alive.current=true;return()=>{alive.current=false;};},[]);
  const [task, setTask] = useState<BudgetReviewTaskResponse | null>(null);
  const [review, setReview] = useState<BudgetReviewResponse | null>(null);
  const [visibleItems, setVisibleItems] = useState<BudgetReviewItemResponse[]>([]);
  const [processingRow, setProcessingRow] = useState(0);
  const [revealingResults, setRevealingResults] = useState(false);
  const [reviewRevealKey, setReviewRevealKey] = useState(0);
  const [loading, setLoading] = useState<'review' | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [budgetYear, setBudgetYear] = useState('');
  const [departmentCode, setDepartmentCode] = useState('');
  const [activeStep, setActiveStep] = useState<0 | 1>(0);
  useEffect(()=>{
    if(!submittedBatch || submittedBatch.epoch!==viewEpoch.current)return;
    const rows=snapshot.entries.filter(row=>submittedBatch.keys.includes(row.operation.key));
    if(!rows.length)return;
    const {processing,results}=budgetBatchResultState(rows);
    const hasUnknown=rows.some(row=>row.phase==='UNKNOWN');
    if(results.length){
      setCompletedResults(results);
      if(!currentResultId)setCurrentResultId(results[0].id);
      setHistoryOpen(false);setActiveStep(1);
    }
    if(processing){
      setUploadNotice(results.length?'已先展示当前可用结果，其余材料仍在处理中。'
        :snapshot.uploading?'正在上传本批材料，请稍候。':'材料已上传，正在解析与分析，可离开本页，结果会自动保存。');
      return;
    }
    if(snapshot.uploading)return;
    if(!results.length){
      if(hasUnknown){setUploadNotice('部分材料的上传或处理结果暂时无法读取，请在历史结果中核实记录，避免重复上传。');return;}
      setSubmittedBatch(null);
      setUploadNotice(null);setError('本批材料未能完成解析，请核对文件格式或联系管理员查看日志。');return;
    }
    setSubmittedBatch(null);
    setCompletedResults(results);
    if(!currentResultId)setCurrentResultId(results[0].id);
    const analyzedIds=rows.filter(row=>row.phase==='PARSED'&&row.result).map(row=>row.result!.task.taskId);
    setRiskFocus(analyzedIds.length?{token:submittedBatch.keys.join(','),taskIds:analyzedIds}:null);
    setUploadNotice(rows.some(row=>row.phase==='UNKNOWN'&&!row.result)
      ?'可用结果已保存，部分材料的上传或处理结果暂时无法读取，请在历史结果中核实记录，避免重复上传。'
      :rows.some(row=>['FAILED','UNKNOWN'].includes(row.phase)||['FAILED','UNKNOWN'].includes(row.result?.task.status??''))
        ?analyzedIds.length?'本次处理已结束，可用结果及未生成结果的说明已保留。':'本次处理已结束，未生成分析结果，具体原因见下方说明。'
        :rows.every(row=>row.phase==='PARSED'&&row.result?.task.status==='REVIEWED')
          ?'解析与分析已完成，结果已保存。':'材料已解析，正在整理同批材料的预算参考结果。');
    setHistoryOpen(false);setActiveStep(1);
  },[snapshot,submittedBatch]);
  useEffect(()=>{
    if(!selectedId) return;
    let current=true;setTask(selected?.result?.task??null);setReview(null);setError(null);
    setResultLoading(true);setActiveStep(1);setHistoryOpen(false);
    void getBudgetReview(selectedId).then(result=>{
      if(!current)return;setTask(result.task);setReview(result);setReviewRevealKey(key=>key+1);
    }).catch(err=>{if(current)setError(err instanceof Error?err.message:'预算结果读取失败');})
      .finally(()=>{if(current)setResultLoading(false);});
    return()=>{current=false;};
  },[selectedId,selectionVersion]);

  const returnToUpload=()=>{
    viewEpoch.current++;setSelectedId(null);setHistoryOpen(false);setHistoryEntry(null);
    setActiveStep(0);setReview(null);setTask(null);setError(null);setResultLoading(false);setLoading(null);
    setSubmittedBatch(null);setUploadNotice(null);setCurrentResultId(null);setCompletedResults([]);
    setRiskFocus(null);
  };
  const openHistory=()=>{
    viewEpoch.current++;setSelectedId(null);setHistoryOpen(true);setHistoryEntry(null);
    setReview(null);setTask(null);setError(null);setResultLoading(false);setLoading(null);
    setSubmittedBatch(null);setUploadNotice(null);setCurrentResultId(null);setCompletedResults([]);
    setRiskFocus(null);
  };
  const returnOneLevel=()=>{
    if(historyOpen&&historyEntry){setHistoryEntry(null);return;}
    returnToUpload();
  };

  useEffect(() => {
    if (!review) {
      setVisibleItems([]);
      setProcessingRow(0);
      setRevealingResults(false);
      return;
    }

    let cancelled = false;
    let timer: number | undefined;
    let index = 0;
    const items = review.items;

    setVisibleItems([]);
    setProcessingRow(items.length > 0 ? 1 : 0);
    setRevealingResults(items.length > 0);

    const revealNext = () => {
      if (cancelled || index >= items.length) return;

      // Capture the current row before scheduling the state update. React may
      // execute the updater after the local index has advanced.
      const item = items[index];
      index += 1;
      if (item) {
        setVisibleItems((current) => [...current, item]);
      }

      if (index < items.length) {
        setProcessingRow(index + 1);
        timer = window.setTimeout(revealNext, 400);
      } else {
        timer = window.setTimeout(() => {
          if (!cancelled) {
            setProcessingRow(0);
            setRevealingResults(false);
          }
        }, 420);
      }
    };

    timer = window.setTimeout(revealNext, 120);
    return () => {
      cancelled = true;
      if (timer !== undefined) window.clearTimeout(timer);
    };
  }, [review?.task.taskId, reviewRevealKey]);

  const handleSmartParse = () => {
    if (!files.length) {
      setError('请选择预算办公文件或图片材料');
      return;
    }
    viewEpoch.current++;
    setError(null);setHistoryOpen(false);setResultLoading(false);
    setRiskFocus(null);
    setTask(null);
    setReview(null);
    setSelectedId(null);
    setActiveStep(0);
    setCurrentResultId(null);setCompletedResults([]);
    const before=batch.snapshot();
    batch.add(files,{budgetYear,departmentCode,batchId:createOperationKey()});
    const after=batch.snapshot();
    const keys=after.entries.filter(row=>{
      if(files.some(file=>sameMaterialFile(row.operation,file)))return true;
      const previous=before.entries.find(item=>item.operation.key===row.operation.key);
      // A reselected original file can retain its folder upload key. Follow the
      // batch's resolved identity rather than guessing from the new file path.
      return previous?.phase==='UNKNOWN'&&!previous.result
        && (row.phase==='WAITING_UPLOAD'||row.phase==='UPLOADING');
    }).map(row=>row.operation.key);
    if(!keys.length||after.error){
      setSubmittedBatch(null);setUploadNotice(null);
      setError(after.error??'未能确认本次上传材料，请重新拖入原文件夹后继续。');return;
    }
    setSubmittedBatch({keys,epoch:viewEpoch.current});
    setUploadNotice('正在上传材料，请稍候。');setFiles([]);
  };

  const chooseFiles=(nextFiles:File[],problems:string[]=[])=>{
    viewEpoch.current++;setSubmittedBatch(null);setUploadNotice(null);
    setRiskFocus(null);
    setFiles(nextFiles);setError(problems.length?problems.join('；'):null);
  };

  const handleReview = async () => {
    if (!task) return;
    const epoch=viewEpoch.current;
    setLoading('review');
    setError(null);
    setVisibleItems([]);
    setProcessingRow(0);
    setRevealingResults(false);
    try {
      const nextReview = await reviewBudgetMaterial(task.taskId);
      if(epoch!==viewEpoch.current)return;
      setReview(nextReview);
      setActiveStep(1);
      setReviewRevealKey((current) => current + 1);
    } catch (err) {
      if(epoch===viewEpoch.current)setError(err instanceof Error ? err.message : '预算审查失败');
    } finally {
      if(epoch===viewEpoch.current)setLoading(null);
    }
  };

  const reviewItemCount = review?.totalItems ?? 10;
  const exceptionCount = review?.exceptionItems ?? 0;
  const departmentName = departmentLabel(review?.task.departmentCode??departmentCode);
  const displayYear=review?.task.budgetYear??budgetYear;

  const applyReviewProgress = async (
    progress: BudgetConfirmationProgress,
  ): Promise<BudgetReviewResponse | null> => {
    const epoch=viewEpoch.current;
    const replacementByRow = new Map<number | null, BudgetReviewItemResponse>();
    if (progress.updatedRow) replacementByRow.set(progress.updatedRow.rowNumber, progress.updatedRow);
    if (progress.nextRow) replacementByRow.set(progress.nextRow.rowNumber, progress.nextRow);
    const replacements = [...replacementByRow.values()];
    const mergeItems = (items: BudgetReviewItemResponse[]) => {
      const merged = items.map((item) => replacements.find(
        (replacement) => replacement.rowNumber === item.rowNumber,
      ) ?? item);
      const existingRows = new Set(merged.map((item) => item.rowNumber));
      return [...merged, ...replacements.filter((item) => !existingRows.has(item.rowNumber))];
    };

    const taskId = review.task.taskId;
    try {
      // The confirmation response only carries the current row and the next
      // exception row. When the next row passes, it has no nextRow payload,
      // even though the backend has already persisted it. Reload the full
      // review so newly completed rows are visible immediately.
      const latestReview = await getBudgetReview(taskId);
      if(epoch!==viewEpoch.current)return null;
      setReview(latestReview);
      setVisibleItems((current) => {
        const byRow = new Map<number | null, BudgetReviewItemResponse>(
          current.map((item) => [item.rowNumber, item]),
        );
        latestReview.items.forEach((item) => byRow.set(item.rowNumber, item));
        return [...byRow.values()].sort((left, right) =>
          (left.rowNumber ?? Number.MAX_SAFE_INTEGER)
          - (right.rowNumber ?? Number.MAX_SAFE_INTEGER));
      });
      return latestReview;
    } catch {
      // Keep the optimistic progress update if the follow-up read is
      // temporarily unavailable. The next refresh will reconcile all rows.
    }

    if(epoch!==viewEpoch.current)return null;
    setReview((current) => {
      if (!current) return current;
      const items = mergeItems(current.items);
      const passedItems = items.filter((item) => item.riskLevel === 'INFO').length;
      return {
        ...current,
        task: {
          ...current.task,
          status: progress.hasMoreException ? 'WAITING_CONFIRMATION' : 'REVIEWED',
          pendingRowNumber: progress.hasMoreException ? progress.nextExceptionRow : null,
          pendingStep: progress.hasMoreException ? progress.nextStep : null,
          pendingReason: progress.hasMoreException
            ? progress.nextRow?.abnormalReasons?.join('；')
              || progress.nextRow?.errorDetail
              || null
            : null,
        },
        totalItems: Math.max(current.totalItems, items.length),
        passedItems,
        exceptionItems: items.length - passedItems,
        allPassed: items.length > 0 && passedItems === items.length,
        items,
      };
    });
    setVisibleItems((current) => mergeItems(current));
    return null;
  };

  return (
    <div className={historyOpen?"history-page-canvas":"min-h-full bg-[#f5f5f7]"}>
      <div className={historyOpen?"history-page":"delivery-page"}>
        <header className={historyOpen?"history-page-header":"delivery-page-header"}>
          {historyOpen&&!historyEntry&&<h1 className="history-page-title">历史结果</h1>}
          {!historyOpen && <div>
            <h1 className="delivery-page-title">预算审查</h1>
            <p className="mt-1.5 text-sm text-slate-400">上传同一项目的预算和批复材料，集中查看差异与审查结果</p>
          </div>}
          <div className="ml-auto flex flex-wrap items-center gap-3">
          {(activeStep===1||historyOpen) && <button type="button" onClick={returnOneLevel} className={historyOpen?"history-button":"delivery-secondary-action"}><ArrowRight size={16} className="rotate-180" />返回</button>}
          {!historyOpen && <button type="button" onClick={openHistory} className="delivery-secondary-action">历史结果</button>}
          </div>
        </header>

        <main className={historyOpen?"history-page-content":"mt-7 space-y-6"}>
        {!historyOpen&&uploadNotice&&<p role="status" aria-live="polite" className="text-sm text-slate-600">{uploadNotice}</p>}
        {(error||snapshot.error) && (
          <div className="flex items-center gap-2 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-xs text-red-700">
            <AlertTriangle size={14} className="shrink-0" />
            <span>{snapshot.error??error}</span>
          </div>
        )}

        {historyOpen && <BudgetHistoryResults entry={historyEntry} onEntryChange={setHistoryEntry} />}

        {!historyOpen&&activeStep === 0 && (
          <section className="space-y-6">
            <div>
              <div>
                <h2 className="mb-3 text-lg font-bold text-slate-800">预算申报材料</h2>
                <section onDragOver={event=>event.preventDefault()} onDrop={event=>{
                  event.preventDefault();const entries=Array.from(event.dataTransfer.items).map(item=>item.kind==='file'?item.webkitGetAsEntry?.()??null:null);
                  const epoch=viewEpoch.current;
                  void collectProcurementDrop(entries,Array.from(event.dataTransfer.files)).then(result=>{if(alive.current&&epoch===viewEpoch.current){chooseFiles([...files,...result.files],result.problems);}});
                }} className={`rounded-2xl border-2 border-dashed bg-white p-5 transition sm:p-7 ${
                  task ? 'border-emerald-300' : 'border-slate-200 hover:border-[#f6a142]'
                }`}>
                  <label className="flex min-h-[275px] cursor-pointer flex-col items-center justify-center rounded-xl px-4 text-center">
                    <span className={`flex size-16 items-center justify-center rounded-2xl ${task ? 'bg-emerald-50 text-emerald-500' : 'bg-[#fff0e2] text-[#f6a142]'}`}><FileSpreadsheet size={29} /></span>
                    <span className="mt-5 text-lg font-bold text-slate-800">{files.length>1?`已选择 ${files.length} 份材料`:file ? file.name : '上传申报材料'}</span>
                    <span className="mt-2 max-w-md text-sm leading-6 text-slate-400">支持 Word、Excel、PDF、PPT、文本和常见图片，也可拖入文件夹；单文件最大 500 MB，逐份处理，单个失败不影响其他文件。扫描件识别可能需要几分钟。</span>
                    <span className="mt-4 flex flex-wrap justify-center gap-2">
                      {['科目编码', '科目名称', '申报金额', '供应商名称', '项目编号'].map((field) => <span key={field} className="rounded-lg border border-[#f6a142]/30 bg-[#fff8f0] px-2.5 py-1 text-xs font-medium text-[#ef9739]">{field}</span>)}
                    </span>
                    <input
                      type="file"
                      multiple
                      className="hidden"
                      accept={OFFICE_MATERIAL_ACCEPT}
                      onChange={(event) => {chooseFiles(Array.from(event.target.files??[]));event.target.value='';}}
                    />
                  </label>
                  <div className="flex flex-wrap items-center justify-center gap-3">
                    <label className="inline-flex cursor-pointer items-center gap-2 rounded-xl bg-[#f6a142] px-5 py-3 text-sm font-semibold text-white shadow-sm transition hover:bg-[#ed9130]">
                      <Upload size={16} />
                      选择文件
                      <input
                        type="file"
                        multiple
                        className="hidden"
                        accept={OFFICE_MATERIAL_ACCEPT}
                        onChange={(event) => {chooseFiles(Array.from(event.target.files??[]));event.target.value='';}}
                      />
                    </label>
                    <button type="button" onClick={handleSmartParse} disabled={!files.length || loading !== null || !!snapshot.error} className="inline-flex items-center gap-2 rounded-xl border border-slate-200 bg-slate-50 px-5 py-3 text-sm font-semibold text-slate-500 transition hover:bg-slate-100 disabled:cursor-not-allowed disabled:opacity-50">
                      {batchBusy ? <Loader2 size={16} className="animate-spin" /> : <Brain size={16} />}
                      {files.length?`开始智能解析${files.length>1?`（${files.length} 份）`:''}`:batchBusy?'解析中...':task?'重新智能解析':'开始智能解析'}
                    </button>
                    {canViewUploadedHistory&&<button type="button" onClick={openHistory} className="inline-flex items-center gap-2 rounded-xl border border-orange-200 bg-white px-5 py-3 text-sm font-semibold text-orange-700 transition hover:bg-orange-50">查看历史结果<ArrowRight size={16} aria-hidden="true" /></button>}
                  </div>
                  {files.length>0&&<p role="status" className="mt-3 text-center text-sm text-slate-500">已选择 {files.length} 份材料，请点击“开始智能解析”。</p>}
                  {task && <p className="mt-3 text-center text-xs text-emerald-600">已解析 {task.parsedLineCount} 行材料</p>}
                </section>
              </div>

            </div>

            {batchBusy?<ProcessingStatusCard variant="compact" label="本批材料处理状态" title="正在处理预算材料" description={overallProgressStatus} />
              :submittedBatch&&<p role="status" className="text-center text-sm text-slate-600">{overallProgressStatus}</p>}

          </section>
        )}

        {!historyOpen&&activeStep === 1&&currentResultId&&<section className="space-y-5">
          <BudgetFileResults key={currentResultId} taskId={currentResultId} onComplete={()=>setUploadNotice(previous=>previous==='材料已解析，正在整理同批材料的预算参考结果。'?'解析与分析已完成，结果已保存。':previous)}/>
        </section>}

        {!historyOpen&&activeStep === 1 && !currentResultId && !review && (
          <section className="rounded-2xl border border-slate-200/80 bg-white p-6 shadow-[0_2px_8px_rgba(15,23,42,0.03)]">
            {resultLoading ? <ProcessingStatusCard title="正在读取审查结果" description="正在加载已保存的预算结果，请稍候。" /> : <>
            <div className="flex items-center gap-3"><ScanLine size={18} className="text-[#ef9739]" /><div><h2 className="text-sm font-semibold text-slate-900">科目额度一致性校验</h2><p className="mt-1 text-xs text-slate-500">材料解析完成后，启动后端预算审查任务。</p></div></div>
            <div className="mt-5 flex flex-wrap items-center gap-3"><span className="text-xs text-emerald-700">{task ? `已准备 ${task.parsedLineCount} 行材料` : '请先上传并解析预算材料'}</span><button type="button" onClick={handleReview} disabled={!task || loading !== null} className="inline-flex items-center gap-2 rounded-xl bg-[#f6a142] px-4 py-2 text-xs font-semibold text-white hover:bg-[#ed9130] disabled:cursor-not-allowed disabled:opacity-40">{loading === 'review' ? <Loader2 size={14} className="animate-spin" /> : <CheckCircle size={14} />}{loading === 'review' ? '审查中...' : '开始预算审查'}</button></div>
            </>}
          </section>
        )}

        {!historyOpen&&activeStep === 1 && !currentResultId && review && (
          <>
            <p className="text-xs text-slate-500">{review.task.fileName} · {new Date(review.task.createdTime).toLocaleString()} · 已保存至历史结果</p>
            {(['REVIEWED','WAITING_CONFIRMATION'].includes(review.task.status)||review.task.status==='PARSED'&&review.items.length>0) ?
            <MaterialReviewPanel
              key={review.task.taskId}
              review={review}
              onReviewProgress={applyReviewProgress}
              onError={setError}
              visibleItems={visibleItems}
              processingRow={processingRow}
              revealingResults={revealingResults}
            /> : <section className="rounded-2xl border border-slate-200/80 bg-white p-5 text-sm text-slate-600">
              <p role="status">{review.task.status==='PARSED'?`已解析 ${review.task.parsedLineCount} 行材料，尚未审查`:budgetResultStatus(review.task.status)}</p>
              {review.task.status==='PARSED' && <button type="button" onClick={handleReview} disabled={loading!==null} className="mt-3 rounded-xl bg-[#f6a142] px-4 py-2 text-xs font-semibold text-white disabled:opacity-40">{loading==='review'?'审查中...':'开始预算审查'}</button>}
            </section>}
          </>
        )}

        <details hidden={historyOpen || activeStep !== 0} className="rounded-xl border border-slate-200 bg-white p-5"><summary className="cursor-pointer text-sm font-medium text-slate-700">选取项目进一步分析</summary>
          <BudgetProjectRiskPanel focus={riskFocus} onReady={()=>setUploadNotice(previous=>previous==='材料已解析，正在整理同批材料的预算参考结果。'?'解析与分析已完成，结果已保存。':previous)} />
        </details>

        </main>
      </div>
    </div>
  );
}

function MaterialReviewPanel({
  review,
  onReviewProgress,
  onError,
  visibleItems,
  processingRow,
  revealingResults,
}: {
  review: BudgetReviewResponse;
  onReviewProgress: (
    progress: BudgetConfirmationProgress,
  ) => Promise<BudgetReviewResponse | null>;
  onError: (message: string | null) => void;
  visibleItems: BudgetReviewItemResponse[];
  processingRow: number;
  revealingResults: boolean;
}) {
  const [riskFilter, setRiskFilter] = useState('ALL');
  const [keyword, setKeyword] = useState('');
  const [expandedItemId, setExpandedItemId] = useState<string | null>(null);
  const [confirmedItemIds, setConfirmedItemIds] = useState<Set<string>>(new Set());
  const [confirmedRowNumbers, setConfirmedRowNumbers] = useState<Set<number>>(new Set());
  const [confirmingItem, setConfirmingItem] = useState<BudgetReviewItemResponse | null>(null);
  const [confirmRemark, setConfirmRemark] = useState('');
  const [confirming, setConfirming] = useState(false);
  const [confirmingKey, setConfirmingKey] = useState<string | null>(null);
  const confirmingKeyRef = useRef<string | null>(null);
  const [showConfirmLogs, setShowConfirmLogs] = useState(false);
  const [confirmLogs, setConfirmLogs] = useState<BudgetConfirmLog[]>([]);
  const [logsLoading, setLogsLoading] = useState(false);
  const [revealingRowNumber, setRevealingRowNumber] = useState<number | null>(null);
  const [finishingRowNumber, setFinishingRowNumber] = useState<number | null>(null);
  const [displayStepByRow, setDisplayStepByRow] = useState<Record<number, number>>({});
  const revealTimersRef = useRef<number[]>([]);
  const nextAutoExpandItemRef = useRef<{ id: string } | null>(null);
  const autoExpandRowNumberRef = useRef<number | null>(null);
  const pendingScrollPositionRef = useRef<{
    top: number;
    left: number;
    token: number;
  } | null>(null);
  const expandedItemIdRef = useRef<string | null>(null);
  expandedItemIdRef.current = expandedItemId;

  useEffect(() => {
    clearRevealTimers();
    autoExpandRowNumberRef.current = null;
    setDisplayStepByRow({});
    setRevealingRowNumber(null);
    setFinishingRowNumber(null);
  }, [review.task.taskId]);

  const filteredItems = useMemo(() => {
    const query = keyword.trim().toLowerCase();
    return visibleItems.filter((item) => {
      const riskMatched = riskFilter === 'ALL' || item.riskLevel === riskFilter;
      const keywordMatched = !query
        || (item.subjectName ?? '').toLowerCase().includes(query)
        || (item.subjectCode ?? '').toLowerCase().includes(query)
        || (item.supplierName ?? '').toLowerCase().includes(query)
        || (item.projectCode ?? '').toLowerCase().includes(query);
      return riskMatched && keywordMatched;
    });
  }, [keyword, riskFilter, visibleItems]);

  const infoCount = review.items.filter((item) => item.riskLevel === 'INFO').length;
  const pendingRowNumber = review.task.status === 'WAITING_CONFIRMATION'
    ? review.task.pendingRowNumber
    : null;
  const confirmationDetails = confirmingItem
    ? confirmationExceptionDetails(
      confirmingItem,
      confirmingItem.rowNumber === pendingRowNumber ? review.task.pendingStep : null,
      review.task,
    )
    : [];
  useEffect(() => {
    if (pendingRowNumber === null || revealingRowNumber !== null) return;
    const pendingItem = visibleItems.find((item) => item.rowNumber === pendingRowNumber);
    if (!pendingItem) return;

    const shouldAutoExpand = autoExpandRowNumberRef.current === pendingRowNumber;
    // Keep a manually opened row stable, but switch automatically when the
    // backend has advanced to the next exception row.
    if (expandedItemIdRef.current !== null
      && expandedItemIdRef.current !== pendingItem.id
      && !shouldAutoExpand) {
      return;
    }

    const shouldScroll = expandedItemIdRef.current === null || shouldAutoExpand;
    if (shouldAutoExpand) {
      autoExpandRowNumberRef.current = null;
    }
    setExpandedItemId(pendingItem.id);
    if (!shouldScroll) return;
  }, [pendingRowNumber, revealingRowNumber, visibleItems]);

  useLayoutEffect(() => {
    const pendingPosition = pendingScrollPositionRef.current;
    if (!pendingPosition) return;
    const scrollContainer = getPageScrollContainer();
    if (!scrollContainer) return;

    scrollContainer.scrollTop = pendingPosition.top;
    scrollContainer.scrollLeft = pendingPosition.left;
    const token = pendingPosition.token;
    const frame = window.requestAnimationFrame(() => {
      const latestPosition = pendingScrollPositionRef.current;
      if (!latestPosition || latestPosition.token !== token) return;
      scrollContainer.scrollTop = latestPosition.top;
      scrollContainer.scrollLeft = latestPosition.left;
      pendingScrollPositionRef.current = null;
    });
    return () => window.cancelAnimationFrame(frame);
  }, [review, visibleItems, expandedItemId, confirmingItem]);

  useEffect(() => () => {
    revealTimersRef.current.forEach((timer) => window.clearTimeout(timer));
  }, []);

  const clearRevealTimers = () => {
    revealTimersRef.current.forEach((timer) => window.clearTimeout(timer));
    revealTimersRef.current = [];
    nextAutoExpandItemRef.current = null;
  };

  const scheduleNextRowExpansion = (itemId: string, delayMs: number) => {
    nextAutoExpandItemRef.current = { id: itemId };
    const timer = window.setTimeout(() => {
      if (nextAutoExpandItemRef.current?.id !== itemId) return;
      nextAutoExpandItemRef.current = null;
      setExpandedItemId(itemId);
    }, delayMs);
    revealTimersRef.current.push(timer);
  };

  const finishCurrentRow = (rowNumber: number) => {
    setFinishingRowNumber(rowNumber);
    const timer = window.setTimeout(() => {
      setFinishingRowNumber(null);
      setRevealingRowNumber(null);
    }, COMPLETION_HOLD_MS);
    revealTimersRef.current.push(timer);
  };

  const skipFinishWait = () => {
    if (finishingRowNumber === null) return;
    const nextItem = nextAutoExpandItemRef.current;
    clearRevealTimers();
    setFinishingRowNumber(null);
    setRevealingRowNumber(null);
    if (nextItem) {
      nextAutoExpandItemRef.current = null;
      setExpandedItemId(nextItem.id);
    }
  };

  const revealUpdatedRow = (
    rowNumber: number,
    fromStep: number,
    targetStep: number,
  ) => {
    clearRevealTimers();
    setFinishingRowNumber(null);
    // A confirmation response must only move the row forward. A stale
    // currentStep from an updated row must never make the UI replay an older
    // exception.
    const safeFromStep = Math.max(0, Math.min(fromStep, CHECK_TYPE_ORDER.length));
    const safeTargetStep = Math.max(
      safeFromStep,
      Math.min(targetStep, CHECK_TYPE_ORDER.length),
    );
    if (safeTargetStep <= safeFromStep) {
      setDisplayStepByRow((current) => ({
        ...current,
        [rowNumber]: Math.max(current[rowNumber] ?? 0, safeTargetStep),
      }));
      if (safeTargetStep >= CHECK_TYPE_ORDER.length) {
        setRevealingRowNumber(rowNumber);
        finishCurrentRow(rowNumber);
      } else {
        setRevealingRowNumber(null);
      }
      return;
    }

    let nextStep = safeFromStep;
    setRevealingRowNumber(rowNumber);
    setDisplayStepByRow((current) => ({
      ...current,
      [rowNumber]: Math.max(current[rowNumber] ?? 0, safeFromStep),
    }));

    const revealNext = () => {
      nextStep += 1;
      setDisplayStepByRow((current) => ({
        ...current,
        [rowNumber]: Math.max(current[rowNumber] ?? 0, nextStep),
      }));
      if (nextStep < safeTargetStep) {
        const timer = window.setTimeout(revealNext, CHECK_REVEAL_INTERVAL_MS);
        revealTimersRef.current.push(timer);
      } else if (safeTargetStep >= CHECK_TYPE_ORDER.length) {
        finishCurrentRow(rowNumber);
      } else {
        setRevealingRowNumber(null);
      }
    };
    const timer = window.setTimeout(revealNext, CHECK_REVEAL_INTERVAL_MS);
    revealTimersRef.current.push(timer);
  };

  const submitConfirmation = async () => {
    if (!confirmingItem || pendingRowNumber === null || confirming) return;
    const confirmationKey = `${review.task.taskId}:${pendingRowNumber}`;
    if (confirmingKeyRef.current === confirmationKey) return;
    confirmingKeyRef.current = confirmationKey;
    setConfirmingKey(confirmationKey);
    setConfirming(true);
    const scrollContainerBeforeConfirmation = getPageScrollContainer();
    pendingScrollPositionRef.current = scrollContainerBeforeConfirmation
      ? {
        top: scrollContainerBeforeConfirmation.scrollTop,
        left: scrollContainerBeforeConfirmation.scrollLeft,
        token: Date.now(),
      }
      : null;
    try {
      const previousChecks = checksForItem(confirmingItem);
      const previousStep = review.task.pendingStep
        && review.task.pendingRowNumber === pendingRowNumber
        ? stepNumberForCheckType(review.task.pendingStep)
        : Math.max(
          displayStepByRow[pendingRowNumber] ?? 0,
          currentStepForItem(confirmingItem, previousChecks),
        );
      const progress = await confirmBudgetRow({
        batchId: review.task.taskId,
        rowNumber: pendingRowNumber,
        remark: confirmRemark.trim() || undefined,
      });
      const sameRowStillPending = progress.hasMoreException
        && progress.nextExceptionRow === pendingRowNumber;
      if (!sameRowStillPending) {
        setConfirmedItemIds((current) => new Set(current).add(confirmingItem.id));
      }
      if (pendingRowNumber !== null && !sameRowStillPending) {
        setConfirmedRowNumbers((current) => new Set(current).add(pendingRowNumber));
      }
      if (progress.updatedRow?.rowNumber === pendingRowNumber) {
        const sameRowStillPendingAfterResume = progress.hasMoreException
          && progress.nextExceptionRow === pendingRowNumber;
        const updatedStep = sameRowStillPendingAfterResume && progress.nextStep
          ? stepNumberForCheckType(progress.nextStep)
          : CHECK_TYPE_ORDER.length;
        setExpandedItemId(progress.updatedRow.id);
        revealUpdatedRow(pendingRowNumber, previousStep, updatedStep);
      }
      const latestReview = await onReviewProgress(progress);
      if (progress.hasMoreException && progress.nextExceptionRow !== pendingRowNumber) {
        const nextItem = progress.nextRow
          ?? latestReview?.items.find(
            (item) => item.rowNumber === progress.nextExceptionRow,
          )
          ?? null;
        if (nextItem) {
          autoExpandRowNumberRef.current = progress.nextExceptionRow;
          const remainingSteps = Math.max(
            0,
            CHECK_TYPE_ORDER.length - previousStep,
          );
          scheduleNextRowExpansion(
            nextItem.id,
            remainingSteps * CHECK_REVEAL_INTERVAL_MS + COMPLETION_HOLD_MS + 20,
          );
        }
      }
      // Close the modal after the silent reconciliation has completed. This
      // keeps the scroll restore request alive until the table has committed
      // the updated row and the next exception row.
      setConfirmingItem(null);
      setConfirmRemark('');
    } catch (err) {
      onError(err instanceof Error ? err.message : '确认异常失败');
    } finally {
      confirmingKeyRef.current = null;
      setConfirming(false);
      setConfirmingKey(null);
    }
  };

  const loadConfirmLogs = async () => {
    const nextVisible = !showConfirmLogs;
    setShowConfirmLogs(nextVisible);
    if (!nextVisible) return;
    setLogsLoading(true);
    try {
      const logs = await getBudgetConfirmLogs(review.task.taskId);
      setConfirmLogs(logs);
      setConfirmedRowNumbers((current) => new Set([
        ...current,
        ...logs.map((log) => log.rowNumber),
      ]));
    } catch (err) {
      onError(err instanceof Error ? err.message : '确认日志查询失败');
    } finally {
      setLogsLoading(false);
    }
  };
  const exportResults = () => {
    if (filteredItems.length === 0) return;
    const rows = filteredItems.map((item) => ({
      行号: item.rowNumber ?? '',
      科目编码: item.subjectCode ?? '',
      科目名称: item.subjectName ?? '',
      申报金额: item.declaredAmount ?? '',
      供应商名称: item.supplierName ?? '',
      预算批复科目: item.approvalSubjectName ?? '',
      批复金额: item.approvalAmount ?? '',
      匹配: MATCH_STATUS_LABEL[item.matchStatus] ?? item.matchStatus,
      匹配方式: item.matchMethod ?? '',
      距离: item.matchDistance ?? '',
      金额校验: AMOUNT_STATUS_LABEL[item.amountStatus] ?? item.amountStatus,
      差异金额: item.differenceAmount ?? '',
      风险等级: riskLabel(item.riskLevel),
      异常原因: (item.abnormalReasons ?? []).join('；') || item.errorDetail || '',
    }));
    const worksheet = XLSX.utils.json_to_sheet(rows);
    const workbook = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(workbook, worksheet, '校验结果');
    const date = new Date();
    const dateText = [
      date.getFullYear(),
      String(date.getMonth() + 1).padStart(2, '0'),
      String(date.getDate()).padStart(2, '0'),
    ].join('');
    XLSX.writeFile(
      workbook,
      `预算校验结果_${review.task.taskId}_${dateText}.xlsx`,
    );
  };

  return (
    <section className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <span className="flex size-5 items-center justify-center rounded-full border border-blue-300 bg-blue-50 text-[10px] font-bold text-blue-700">2</span>
        <h2 className="text-sm font-semibold text-slate-900">科目额度一致性校验</h2>
        <span className="font-mono text-[10px] text-slate-400">[F01/P0]</span>
        <span className="ml-auto text-[11px] text-slate-500">以预算批复金额为校验基准</span>
      </div>

      <div className="grid grid-cols-1 gap-3 lg:grid-cols-3">
        <SummaryCard label="全部科目" value={review.totalItems} tone="blue" />
        <SummaryCard label="通过" value={infoCount} tone="green" hint="所有校验项通过" />
        <SummaryCard label="异常项" value={review.exceptionItems} tone="red" hint="需要人工确认后继续" />
      </div>

      <div className="overflow-hidden rounded-lg border border-slate-200 bg-white shadow-sm">
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-200 px-4 py-3">
          <div className="flex items-center gap-2">
            <ScanLine size={14} className="text-blue-600" />
            <span className="text-xs font-semibold text-slate-900">科目校验明细</span>
            <span className="text-[11px] text-slate-400">— 以预算批复金额为基准</span>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <select
              value={riskFilter}
              onChange={(event) => setRiskFilter(event.target.value)}
              className="rounded border border-slate-200 bg-slate-50 px-2.5 py-1.5 text-[11px] text-slate-700"
              aria-label="风险筛选"
            >
              <option value="ALL">全部风险</option>
              <option value="CRITICAL">严重</option>
              <option value="WARNING">警告</option>
              <option value="INFO">正常</option>
            </select>
            <div className="flex items-center gap-1.5 rounded border border-slate-200 bg-slate-50 px-2.5 py-1.5">
              <Search size={12} className="text-slate-500" />
              <input
                value={keyword}
                onChange={(event) => setKeyword(event.target.value)}
                placeholder="搜索科目 / 供应商"
                className="w-28 bg-transparent text-[11px] text-slate-900 outline-none placeholder:text-slate-400"
                aria-label="搜索科目或供应商"
              />
            </div>
            <span className="text-[11px] text-slate-500">{filteredItems.length} / {visibleItems.length} 行</span>
            <button
              type="button"
              onClick={exportResults}
              disabled={filteredItems.length === 0}
              className="inline-flex items-center gap-1.5 rounded border border-emerald-200 px-2.5 py-1.5 text-[11px] text-emerald-700 hover:bg-emerald-50 disabled:cursor-not-allowed disabled:opacity-40"
            >
              <Download size={12} />
              导出
            </button>
          </div>
        </div>
        {revealingResults && (
          <div className="flex items-center justify-between border-b border-blue-100 bg-blue-50 px-4 py-2 text-[11px] text-blue-700">
            <span className="inline-flex items-center gap-1.5"><Loader2 size={12} className="animate-spin" />正在载入第 {processingRow}/{review.totalItems} 行校验结果</span>
            <span>已显示 {visibleItems.length} 行</span>
          </div>
        )}
        <div className="overflow-x-auto">
          <table className="min-w-[980px] w-full text-left text-xs">
            <thead className="bg-slate-50 text-slate-500">
              <tr>
                <th className="px-3 py-2 font-medium">编号</th>
                <th className="px-3 py-2 font-medium">科目名称</th>
                <th className="px-3 py-2 font-medium">申报部门</th>
                <th className="px-3 py-2 font-medium">申报金额</th>
                <th className="px-3 py-2 font-medium">批复金额</th>
                <th className="px-3 py-2 font-medium">差额</th>
                <th className="px-3 py-2 font-medium">项目编号</th>
                <th className="px-3 py-2 font-medium">校验结果</th>
                <th className="sticky right-0 z-20 min-w-28 bg-slate-50 px-3 py-2 font-medium">操作</th>
              </tr>
            </thead>
            <tbody>
            {filteredItems.map((item) => {
              const expanded = expandedItemId === item.id;
              const itemConfirming = confirmingKey === `${review.task.taskId}:${item.rowNumber}`;
              const stableRowKey = `${review.task.taskId}-${item.rowNumber ?? item.id}`;
              const isConfirmed = confirmedItemIds.has(item.id)
                || (item.rowNumber !== null && confirmedRowNumbers.has(item.rowNumber));
              const result = rowResult(item);
              return (
                <Fragment key={stableRowKey}>
                  <tr
                    id={`f01-row-${item.id}`}
                    className={`border-t border-slate-200 text-slate-900 ${
                      item.riskLevel === 'CRITICAL'
                        ? 'bg-red-500/[0.035]'
                        : item.riskLevel === 'WARNING'
                          ? 'bg-amber-500/[0.035]'
                          : ''
                    }`}
                  >
                    <td className="px-3 py-3 font-mono text-slate-500">{item.rowNumber ?? '-'}</td>
                    <td className="px-3 py-3">
                      <div className="font-medium text-slate-900">{item.subjectName || '-'}</div>
                      <div className="mt-0.5 font-mono text-[10px] text-slate-400">{item.subjectCode || '无科目编码'}</div>
                    </td>
                    <td className="px-3 py-3 text-slate-600">{departmentLabel(review.task.departmentCode)}</td>
                    <td className={`px-3 py-3 font-mono font-semibold ${item.riskLevel === 'INFO' ? 'text-slate-800' : 'text-red-600'}`}>
                      {formatAmount(item.declaredAmount)}
                    </td>
                    <td className="px-3 py-3 font-mono text-slate-600">{formatAmount(item.approvalAmount)}</td>
                    <td className={`px-3 py-3 font-mono font-semibold ${
                      item.differenceAmount && item.differenceAmount > 0
                        ? 'text-red-600'
                        : item.differenceAmount && item.differenceAmount < 0
                          ? 'text-emerald-600'
                          : 'text-slate-600'
                    }`}>
                      {formatSignedAmount(item.differenceAmount)}
                    </td>
                    <td className="px-3 py-3">
                      <span className="rounded border border-slate-200 bg-slate-50 px-2 py-1 font-mono text-[10px] text-slate-600">
                        {item.projectCode || '-'}
                      </span>
                    </td>
                    <td className="px-3 py-3">
                      <span className={`inline-flex items-center gap-1 rounded border px-2 py-1 font-medium ${result.className}`}>
                        <span className="flex size-3.5 items-center justify-center rounded-full border text-[9px]">{result.icon}</span>
                        {result.label}
                      </span>
                    </td>
                    <td className="sticky right-0 z-10 bg-white px-3 py-3">
                      <div className="flex flex-wrap items-center gap-2">
                        <button
                          type="button"
                          disabled={itemConfirming || (pendingRowNumber === item.rowNumber && confirming)}
                          aria-expanded={expanded}
                          aria-controls={`f01-inline-checks-${item.id}`}
                          onClick={() => setExpandedItemId(expanded ? null : item.id)}
                          className="inline-flex items-center gap-1 rounded border border-blue-200 px-2 py-1 text-blue-700 hover:bg-blue-50 disabled:cursor-not-allowed disabled:opacity-60"
                        >
                          {expanded ? <ChevronUp size={13} /> : <ChevronDown size={13} />}
                          {expanded ? '收起' : pendingRowNumber === item.rowNumber ? '查看异常' : '展开'}
                        </button>
                        {isConfirmed && (
                          <span className="inline-flex items-center gap-1 rounded border border-slate-500/30 bg-slate-500/10 px-2 py-1 text-slate-400">
                            ✓ 已确认
                          </span>
                        )}
                      </div>
                    </td>
                  </tr>
                  {expanded && (
                    <tr id={`f01-inline-checks-${item.id}`} className="border-t border-blue-200 bg-slate-50">
                      <td colSpan={9} className="p-0">
                        <InlineReviewChecks
                          item={item}
                          task={review.task}
                          displayStep={item.rowNumber === null
                            ? undefined
                            : displayStepByRow[item.rowNumber]}
                          revealing={revealingRowNumber === item.rowNumber}
                          finishing={finishingRowNumber === item.rowNumber}
                          canConfirm={pendingRowNumber === item.rowNumber}
                          pendingStep={pendingRowNumber === item.rowNumber
                            ? review.task.pendingStep
                            : null}
                          confirming={itemConfirming || confirming}
                          onConfirm={() => {
                            setExpandedItemId(item.id);
                            setConfirmingItem(item);
                          }}
                          onSkipFinishWait={skipFinishWait}
                        />
                      </td>
                    </tr>
                  )}
                </Fragment>
              );
            })}
            </tbody>
          </table>
        </div>
      </div>

      <div className={`flex flex-wrap items-start justify-between gap-3 rounded-lg border px-4 py-3 ${
        review.exceptionItems > 0
          ? 'border-red-200 bg-red-50'
          : 'border-emerald-200 bg-emerald-50'
      }`}>
        <div className="flex min-w-0 items-start gap-2">
          {review.exceptionItems > 0
            ? <AlertTriangle size={15} className="mt-0.5 shrink-0 text-red-600" />
            : <CheckCircle size={15} className="mt-0.5 shrink-0 text-emerald-600" />}
          <div className="min-w-0">
            <div className={`text-xs font-semibold ${review.exceptionItems > 0 ? 'text-red-700' : 'text-emerald-700'}`}>
              {review.exceptionItems > 0
                ? `发现 ${review.exceptionItems} 项异常，已自动标记为差异项`
                : '全部科目校验通过'}
            </div>
            {review.exceptionItems > 0 && (
              <div className="mt-1 text-[11px] leading-5 text-red-700">
                {visibleItems
                  .filter((item) => item.riskLevel !== 'INFO')
                  .map((item) => `${item.subjectName || `第${item.rowNumber ?? '-'}行`}（${(item.abnormalReasons ?? []).join('；') || item.errorDetail || '请展开查看'}）`)
                  .join('；')}
              </div>
            )}
          </div>
        </div>
        <button
          type="button"
          disabled
          title="后续校验阶段尚未接入"
          className="inline-flex shrink-0 items-center gap-1 rounded border border-blue-200 bg-white px-3 py-1.5 text-xs font-medium text-blue-400 disabled:cursor-not-allowed disabled:opacity-70"
        >
          下一步 <ArrowRight size={13} />
        </button>
      </div>

      <div className="rounded-lg border border-slate-200 bg-white">
        <button
          type="button"
          onClick={loadConfirmLogs}
          className="flex w-full items-center justify-between px-4 py-3 text-left text-xs font-semibold text-slate-900"
        >
          <span>人工确认日志</span>
          {showConfirmLogs ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
        </button>
        {showConfirmLogs && (
          <div className="border-t border-slate-200 p-3">
            {logsLoading ? (
              <div className="text-xs text-slate-600">正在加载确认记录...</div>
            ) : confirmLogs.length === 0 ? (
              <div className="text-xs text-slate-600">当前批次暂无人工确认记录</div>
            ) : (
              <div className="overflow-x-auto">
                <table className="min-w-[760px] w-full text-left text-xs">
                  <thead className="text-slate-500">
                    <tr>
                      <th className="px-2 py-2">确认时间</th>
                      <th className="px-2 py-2">确认人</th>
                      <th className="px-2 py-2">行号</th>
                      <th className="px-2 py-2">步骤</th>
                      <th className="px-2 py-2">异常原因</th>
                      <th className="px-2 py-2">备注</th>
                    </tr>
                  </thead>
                  <tbody>
                    {confirmLogs.map((log) => (
                      <tr key={log.id} className="border-t border-slate-200 text-slate-900">
                        <td className="px-2 py-2">{new Date(log.confirmTime).toLocaleString()}</td>
                        <td className="px-2 py-2">{log.confirmerName}</td>
                        <td className="px-2 py-2">{log.rowNumber}</td>
                        <td className="px-2 py-2">{CONFIRM_STEP_LABEL[log.confirmStep] ?? log.confirmStep}</td>
                        <td className="px-2 py-2 text-amber-700">{log.abnormalReason || '-'}</td>
                        <td className="px-2 py-2">{log.remark || '-'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        )}
      </div>

      {confirmingItem && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4">
          <div className="max-h-[88vh] w-full max-w-lg overflow-y-auto rounded-lg border border-amber-200 bg-white p-5 shadow-2xl">
            <h3 className="text-sm font-semibold text-slate-900">确认异常</h3>
            <p className="mt-2 text-xs leading-5 text-slate-600">
              第 {confirmingItem.rowNumber ?? '-'} 行 · {confirmingItem.subjectName || '未填写科目名称'}
            </p>
            <div className="mt-3 rounded border border-red-200 bg-red-50 p-3">
              <div className="text-xs font-semibold text-red-700">
                当前校验项存在 {confirmationDetails.length} 项异常：
              </div>
              <div className="mt-2 space-y-2">
                {confirmationDetails.map((detail, index) => (
                  <div
                    key={`${confirmingItem.id}-exception-${detail.checkType}-${index}`}
                    className="rounded border border-red-200/80 bg-white/80 p-2.5 text-xs"
                  >
                    <div className="flex flex-wrap items-center justify-between gap-2 font-semibold text-red-700">
                      <span>{index + 1}. {detail.label}</span>
                      <span>❌ 异常</span>
                    </div>
                    <div className="mt-1.5 grid gap-1 leading-5 text-slate-700">
                      <div>
                        <span className="text-slate-500">期望：</span>
                        <span className="font-mono break-words text-slate-900">{detail.expected}</span>
                      </div>
                      <div>
                        <span className="text-slate-500">实际：</span>
                        <span className="font-mono break-words text-slate-900">{detail.actual}</span>
                      </div>
                      <div>
                        <span className="text-slate-500">结论：</span>
                        <span className="text-red-700">{detail.conclusion}</span>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            </div>
            {confirmingItem.aiSemanticAnalysis?.available
              && confirmationDetails.length > 0 && (
                <div className="mt-3 rounded border border-blue-200 bg-blue-50/70 px-3 py-2.5 text-xs text-blue-900">
                  <div className="flex flex-wrap items-center gap-1.5 font-semibold text-blue-800">
                    <Brain size={14} />
                    <span>AI 辅助分析</span>
                    <span className="rounded-full border border-blue-200 bg-white/70 px-1.5 py-0.5 text-[10px] font-medium">
                      已调用模型
                    </span>
                  </div>
                  <div className="mt-1.5 flex flex-wrap gap-x-3 gap-y-1 text-[11px] text-blue-700">
                    {confirmingItem.aiSemanticAnalysis.durationMs !== null && (
                      <span>分析耗时：{confirmingItem.aiSemanticAnalysis.durationMs} ms</span>
                    )}
                  </div>
                  {confirmationDetails.some((detail) => detail.checkType === 'CATEGORY_ATTRIBUTION')
                    && confirmingItem.aiSemanticAnalysis.suggestedCategory && (
                    <div className="mt-2">
                      <span className="text-blue-700">建议分类：</span>
                      <span className="font-medium">{confirmingItem.aiSemanticAnalysis.suggestedCategory}</span>
                      {confirmingItem.aiSemanticAnalysis.confidence !== null && (
                        <span className="ml-2 text-blue-700">
                          置信度：{Math.round(confirmingItem.aiSemanticAnalysis.confidence * 100)}%
                        </span>
                      )}
                    </div>
                  )}
                  {confirmationDetails.some((detail) => detail.checkType === 'SUBJECT_NAME')
                    && (confirmingItem.aiSemanticAnalysis.similarityScore !== null
                      || confirmingItem.aiSemanticAnalysis.judgment) && (
                    <div className="mt-2">
                      {confirmingItem.aiSemanticAnalysis.similarityScore !== null && (
                        <span className="text-blue-700">
                          相似度：{Math.round(confirmingItem.aiSemanticAnalysis.similarityScore * 100)}%
                        </span>
                      )}
                      {confirmingItem.aiSemanticAnalysis.judgment && (
                        <span className="ml-2 font-medium">
                          判断：{confirmingItem.aiSemanticAnalysis.judgment}
                        </span>
                      )}
                    </div>
                  )}
                  {confirmingItem.aiSemanticAnalysis.hint && (
                    <div className="mt-1 leading-5">
                      <span className="text-blue-700">AI 分析：</span>
                      {confirmingItem.aiSemanticAnalysis.hint}
                    </div>
                  )}
                  {confirmingItem.aiSemanticAnalysis.enhancedDescription && (
                    <div className="mt-1 leading-5">
                      <span className="text-blue-700">AI 增强说明：</span>
                      {confirmingItem.aiSemanticAnalysis.enhancedDescription}
                    </div>
                  )}
                  {confirmingItem.aiSemanticAnalysis.suggestion && (
                    <div className="mt-1 leading-5">
                      <span className="text-blue-700">处理建议：</span>
                      {confirmingItem.aiSemanticAnalysis.suggestion}
                    </div>
                  )}
                  <div className="mt-1 text-[11px] text-blue-600">
                    AI 只提供辅助分析；红色异常结论仍由 F01 确定性规则判定
                  </div>
                </div>
              )}
            <label className="mt-4 block text-xs text-slate-600">
              备注（可选）
              <textarea
                value={confirmRemark}
                onChange={(event) => setConfirmRemark(event.target.value)}
                rows={3}
                className="mt-1 w-full resize-none rounded border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-900 outline-none"
                placeholder="填写本次人工确认说明"
              />
            </label>
            <div className="mt-4 flex justify-end gap-2">
              <button
                type="button"
                onClick={() => {
                  setConfirmingItem(null);
                  setConfirmRemark('');
                }}
                disabled={confirming}
                className="rounded border border-slate-200 px-3 py-2 text-xs text-slate-600"
              >
                返回查看
              </button>
              <button
                type="button"
                onClick={submitConfirmation}
                disabled={confirming || confirmingKey !== null}
                className="rounded border border-amber-200 bg-amber-600 px-3 py-2 text-xs font-semibold text-white disabled:opacity-50"
              >
                {confirming ? '确认中...' : '确认并继续'}
              </button>
            </div>
          </div>
        </div>
      )}
    </section>
  );
}

function SummaryCard({
  label,
  value,
  tone,
  hint,
}: {
  label: string;
  value: number;
  tone: 'blue' | 'red' | 'amber' | 'green';
  hint?: string;
}) {
  const toneClass = {
    blue: 'border-blue-200 bg-blue-50/60 text-blue-700',
    red: 'border-red-200 bg-red-50/70 text-red-700',
    amber: 'border-amber-200 bg-amber-50/70 text-amber-700',
    green: 'border-emerald-200 bg-emerald-50/70 text-emerald-700',
  }[tone];
  return (
    <div className={`min-h-24 rounded-lg border p-4 ${toneClass}`}>
      <div className="text-xs font-medium text-slate-500">{label}</div>
      <div className="mt-2 text-2xl font-semibold leading-none">{value}</div>
      {hint && <div className="mt-1 text-[11px] text-slate-500">{hint}</div>}
    </div>
  );
}

function InlineReviewChecks({
  item,
  task,
  displayStep,
  revealing,
  finishing,
  canConfirm,
  pendingStep,
  confirming,
  onConfirm,
  onSkipFinishWait,
}: {
  item: BudgetReviewItemResponse;
  task: BudgetReviewTaskResponse;
  displayStep?: number;
  revealing: boolean;
  finishing: boolean;
  canConfirm: boolean;
  pendingStep: string | null;
  confirming: boolean;
  onConfirm: () => void;
  onSkipFinishWait: () => void;
}) {
  const checks = checksForItem(item);
  const pendingStepNumber = canConfirm && pendingStep
    ? stepNumberForCheckType(pendingStep)
    : null;
  const calculatedStep = currentStepForItem(item, checks);
  // Zero is only an uninitialized animation value. It must not override the
  // backend pause step, otherwise a newly paused row renders every module as
  // pending and loses the current check label.
  const validDisplayStep = displayStep && displayStep > 0 ? displayStep : undefined;
  const currentStep = Math.min(
    pendingStepNumber === null
      ? validDisplayStep ?? calculatedStep
      : Math.min(validDisplayStep ?? pendingStepNumber, pendingStepNumber),
    checks.length,
  );
  const visibleChecks = checks.slice(0, currentStep);
  const visibleModules = moduleGroupsForItem(item, visibleChecks);
  const exceptionReasons = item.abnormalReasons?.length
    ? item.abnormalReasons
    : visibleChecks
      .filter((check) => check.status === 'FAILED' || (!check.status && !check.passed))
      .map((check) => check.errorDetail)
      .filter(Boolean);
  const exceptionCount = exceptionReasons.length;
  const moduleExceptionSummaries = exceptionSummariesByModule(item, visibleModules, exceptionReasons);
  const pendingCheck = canConfirm && pendingStep
    ? checks.find((check) => check.checkType === normalizedCheckType(pendingStep))
    : undefined;
  const currentCheck = pendingCheck ?? visibleChecks[currentStep - 1];
  const currentCheckPending = currentCheck
    && (currentCheck.status === 'PENDING' || currentCheck.status === 'NOT_CHECKED');
  const hasPendingChecks = checks.some((check) =>
    check.status === 'PENDING' || check.status === 'NOT_CHECKED');
  const pausedCheckLabel = currentCheck
    ? CHECK_TYPE_LABEL[currentCheck.checkType] ?? currentCheck.checkType
    : pendingStep
      ? CONFIRM_STEP_LABEL[pendingStep] ?? pendingStep
      : '当前校验项';
  const rowCompleted = currentStep >= CHECK_TYPE_ORDER.length
    && !currentCheckPending
    && !hasPendingChecks
    && !canConfirm;
  const showFinishing = finishing && rowCompleted;
  const progressText = currentCheckPending
    ? `当前执行：${CHECK_TYPE_LABEL[currentCheck.checkType] ?? currentCheck.checkType}`
    : canConfirm
      ? `已暂停：${pausedCheckLabel}`
      : rowCompleted
      ? '当前行校验完成 ✅'
      : currentStep < CHECK_TYPE_ORDER.length
      ? `已暂停：${pausedCheckLabel}`
      : '9项校验已完成';
  return (
    <div className="space-y-3 p-4">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-dashed border-slate-200 pb-2">
         <div className="text-sm font-semibold text-slate-900">
           第 {item.rowNumber ?? '-'} 行 - {item.subjectName || '未填写科目名称'} — F01 校验详情
        </div>
        <div className={`text-[11px] ${
          revealing || currentCheckPending
            ? 'text-blue-700'
            : currentStep < CHECK_TYPE_ORDER.length
              ? 'text-red-700'
              : 'text-emerald-700'
        }`}>
          {showFinishing ? '当前行校验完成 ✅' : revealing ? '正在继续校验...' : progressText}
        </div>
      </div>
      <div className="grid grid-cols-1 items-stretch gap-2 sm:grid-cols-2 sm:auto-rows-[112px]">
        {visibleChecks.map((check, checkIndex) => (
          <ReviewCheckCard
            key={`${item.id}-inline-${check.checkType}`}
            check={check}
            item={item}
            task={task}
            isCurrent={CHECK_TYPE_ORDER.indexOf(check.checkType) === currentStep - 1}
            revealDelayMs={checkIndex * 80}
            canConfirm={canConfirm}
            confirming={confirming}
            onConfirm={onConfirm}
          />
        ))}
      </div>
      <div className={`flex flex-wrap items-center justify-between gap-2 rounded border px-3 py-2 text-xs ${
        exceptionCount > 0
          ? 'border-red-200 bg-red-50 text-red-700'
          : 'border-emerald-200 bg-emerald-50 text-emerald-700'
      }`}>
        <div className="min-w-0 flex-1">
          {rowCompleted ? (
            <>
              <div className="font-semibold text-emerald-700">
                ✅ 当前行校验完成
                {exceptionCount > 0 ? `，已确认 ${exceptionCount} 项异常` : ''}
              </div>
              {exceptionCount > 0 && (
                <ol className="mt-1 list-decimal space-y-0.5 pl-5 leading-5 text-red-700">
                  {moduleExceptionSummaries.map((reason, index) => (
                    <li key={`${item.id}-completed-summary-${index}`}>{reason}</li>
                  ))}
                </ol>
              )}
            </>
          ) : exceptionCount > 0 ? (
            <>
              <div className="font-semibold">该行存在 {exceptionCount} 项异常：</div>
              <ol className="mt-1 list-decimal space-y-0.5 pl-5 leading-5">
                {moduleExceptionSummaries.map((reason, index) => (
                  <li key={`${item.id}-summary-${index}`}>{reason}</li>
                ))}
              </ol>
            </>
          ) : (
            <span>该行五大模块校验全部正常，已标记为正常</span>
          )}
        </div>
        {showFinishing && (
          <button
            type="button"
            onClick={onSkipFinishWait}
            className="inline-flex shrink-0 items-center gap-1 rounded border border-blue-400/40 bg-blue-50 px-3 py-1.5 font-semibold text-blue-700 hover:bg-blue-100"
          >
            <SkipForward size={13} />
            跳过等待，继续下一行
          </button>
        )}
      </div>
    </div>
  );
}

const MODULE_CONFIG: Array<{
  moduleKey: ValidationModuleGroup['moduleKey'];
  moduleName: string;
  checkTypes: string[];
}> = [
  {
    moduleKey: 'DATA_VALIDATION',
    moduleName: '数据规范性校验',
    checkTypes: ['REQUIRED_FIELDS', 'AMOUNT_FORMAT'],
  },
  {
    moduleKey: 'SUBJECT_CONSISTENCY',
    moduleName: '科目一致性校验',
    checkTypes: ['SUBJECT_CODE', 'SUBJECT_NAME', 'CATEGORY_ATTRIBUTION'],
  },
  {
    moduleKey: 'AMOUNT_COMPLIANCE',
    moduleName: '金额合规性校验',
    checkTypes: ['BUDGET_AMOUNT'],
  },
  {
    moduleKey: 'SUPPLIER_CHECK',
    moduleName: '关联方校验',
    checkTypes: ['SUPPLIER_NAME'],
  },
  {
    moduleKey: 'PROJECT_COMPLIANCE',
    moduleName: '项目合规性校验',
    checkTypes: ['PROJECT_CODE', 'PROJECT_DEPARTMENT', 'BUDGET_YEAR_VALIDITY'],
  },
];

function moduleGroupsForItem(
  item: BudgetReviewItemResponse,
  visibleChecks: BudgetReviewCheckResponse[],
): ValidationModuleGroup[] {
  const visibleByType = new Map(visibleChecks.map((check) => [check.checkType, check]));
  return MODULE_CONFIG.map((config) => {
    const children = config.checkTypes
      .map((checkType) => visibleByType.get(checkType))
      .filter((check): check is BudgetReviewCheckResponse => Boolean(check));
    const hasException = children.some((check) =>
      check.status === 'FAILED' || check.status === 'WARNING');
    const hasPending = children.some((check) =>
      check.status === 'PENDING' || check.status === 'NOT_CHECKED');
    const moduleStatus = hasException
      ? 'ERROR'
      : hasPending || children.length < config.checkTypes.length
        ? 'PENDING'
        : 'PASS';
    const exceptionSummary = children
      .filter((check) => check.status === 'FAILED' || check.status === 'WARNING')
      .map((check) => check.errorDetail)
      .filter(Boolean)
      .join('；');
    const backendGroup = item.moduleGroups?.find(
      (group) => group.moduleKey === config.moduleKey,
    );
    return {
      moduleKey: config.moduleKey,
      moduleName: config.moduleName,
      moduleStatus,
      children,
      exceptionSummary: exceptionSummary || (
        moduleStatus === 'ERROR' ? backendGroup?.exceptionSummary ?? '' : ''
      ),
    };
  });
}

function normalizedCheckType(checkType: string | null): string | null {
  const legacyModuleStep: Record<string, string> = {
    DATA_VALIDATION: 'REQUIRED_FIELDS',
    SUBJECT_CONSISTENCY: 'SUBJECT_CODE',
    AMOUNT_COMPLIANCE: 'BUDGET_AMOUNT',
    BUDGET_COMPARE: 'BUDGET_AMOUNT',
    SUPPLIER_CHECK: 'SUPPLIER_NAME',
    PROJECT_COMPLIANCE: 'PROJECT_CODE',
  };
  return checkType
    ? legacyModuleStep[checkType] ?? checkType
    : null;
}

function stepNumberForCheckType(checkType: string | null): number {
  const normalizedType = normalizedCheckType(checkType);
  const index = normalizedType === null ? -1 : CHECK_TYPE_ORDER.indexOf(normalizedType);
  return index >= 0 ? index + 1 : 0;
}

function exceptionSummariesByModule(
  item: BudgetReviewItemResponse,
  visibleModules: ValidationModuleGroup[],
  fallbackReasons: string[],
): string[] {
  const backendSummaries = (item.moduleGroups ?? [])
    .filter((module) => module.moduleStatus === 'ERROR' && module.exceptionSummary)
    .map((module) => `${module.moduleName} - ${module.exceptionSummary}`);
  if (backendSummaries.length > 0) return backendSummaries;

  return fallbackReasons.map((reason) => {
    const module = visibleModules.find((candidate) =>
      candidate.children.some((check) => check.errorDetail === reason));
    return module ? `${module.moduleName} - ${reason}` : reason;
  });
}

interface ConfirmationExceptionDetail {
  checkType: string;
  label: string;
  expected: string;
  actual: string;
  conclusion: string;
}

function confirmationExceptionDetails(
  item: BudgetReviewItemResponse,
  pendingStep: string | null,
  task?: BudgetReviewTaskResponse,
): ConfirmationExceptionDetail[] {
  const checks = checksForItem(item);
  const currentStep = currentStepForItem(item, checks);
  const pendingCheckType = normalizedCheckType(pendingStep);
  const currentCheck = pendingCheckType
    ? checks.find((check) => check.checkType === pendingCheckType)
    : checks
      .slice(0, currentStep)
      .find((check) => check.status === 'FAILED' || check.status === 'WARNING');
  const reasons = item.abnormalReasons ?? [];

  if (!currentCheck) {
    return [{
      checkType: 'ROW',
      label: '预算校验',
      expected: '各项校验通过',
      actual: '-',
      conclusion: item.errorDetail || '请核实该异常',
    }];
  }

  const relatedReasons = reasons.filter((reason) => reasonMatchesCheck(reason, currentCheck.checkType));
  return [{
    checkType: currentCheck.checkType,
    label: CHECK_TYPE_LABEL[currentCheck.checkType] ?? currentCheck.checkType,
    expected: checkValue(currentCheck, item, 'expected', task),
    actual: checkValue(currentCheck, item, 'actual', task),
    conclusion: relatedReasons.join('；') || currentCheck.errorDetail || '校验异常',
  }];
}

function reasonMatchesCheck(reason: string, checkType: string): boolean {
  const keywords: Record<string, string[]> = {
    REQUIRED_FIELDS: ['必填字段', '字段缺失'],
    AMOUNT_FORMAT: ['金额格式'],
    SUBJECT_CODE: ['科目编码', '科目匹配失败'],
    SUBJECT_NAME: ['科目名称'],
    CATEGORY_ATTRIBUTION: ['科目分类'],
    BUDGET_AMOUNT: ['超预算', '预算批复'],
    SUPPLIER_NAME: ['供应商'],
    PROJECT_CODE: ['项目编号'],
    PROJECT_DEPARTMENT: ['项目不属于当前部门', '项目与部门', '项目不存在'],
    BUDGET_YEAR_VALIDITY: ['预算年度', '尚未启用', '已停用', '有效期'],
  };
  return (keywords[checkType] ?? []).some((keyword) => reason.includes(keyword));
}

function checkExceptionDescription(
  item: BudgetReviewItemResponse,
  check: BudgetReviewCheckResponse,
): string {
  const relatedReasons = (item.abnormalReasons ?? [])
    .filter((reason) => reasonMatchesCheck(reason, check.checkType));
  return relatedReasons.join('；') || check.errorDetail || '校验异常';
}

function compactCheckConclusion(
  check: BudgetReviewCheckResponse,
  item: BudgetReviewItemResponse,
): string {
  switch (check.checkType) {
    case 'REQUIRED_FIELDS':
      return '已全部填写';
    case 'AMOUNT_FORMAT':
      return '格式合法';
    case 'SUBJECT_CODE':
      return '一致';
    case 'SUBJECT_NAME':
      return `${item.matchMethod === 'FUZZY' ? '模糊' : '精确'}匹配`;
    case 'CATEGORY_ATTRIBUTION':
      return '归属一致';
    case 'BUDGET_AMOUNT':
      return '金额正常';
    case 'SUPPLIER_NAME':
      return '一致';
    case 'PROJECT_CODE':
      return '唯一';
    case 'PROJECT_DEPARTMENT':
      return '匹配';
    case 'BUDGET_YEAR_VALIDITY':
      return '有效';
    default:
      return (check.errorDetail || '校验通过')
        .replace(/^[✅❌]\s*/, '')
        .replace(/（.*）$/, '');
  }
}

function compactExceptionConclusion(
  check: BudgetReviewCheckResponse,
  item: BudgetReviewItemResponse,
): string {
  switch (check.checkType) {
    case 'REQUIRED_FIELDS':
      return '字段缺失';
    case 'AMOUNT_FORMAT':
      return '格式非法';
    case 'SUBJECT_CODE':
      return '匹配失败';
    case 'SUBJECT_NAME':
      return item.matchMethod === 'FUZZY' ? '模糊匹配' : '名称不一致';
    case 'CATEGORY_ATTRIBUTION':
      return '归属不匹配';
    case 'BUDGET_AMOUNT':
      return item.differenceAmount !== null && item.differenceAmount > 0
        ? `超预算（${formatSignedAmount(item.differenceAmount)}）`
        : '金额异常';
    case 'SUPPLIER_NAME':
      return '名称不一致';
    case 'PROJECT_CODE':
      return '重复或无效';
    case 'PROJECT_DEPARTMENT':
      return '部门不匹配';
    case 'BUDGET_YEAR_VALIDITY':
      return '年度无效';
    default:
      return checkExceptionDescription(item, check)
        .replace(/^[✅❌⚠️]\s*/, '')
        .split('；')[0];
  }
}

function ReviewCheckCard({
  check,
  item,
  task,
  isCurrent,
  revealDelayMs,
  canConfirm,
  confirming,
  onConfirm,
}: {
  check: BudgetReviewCheckResponse;
  item: BudgetReviewItemResponse;
  task: BudgetReviewTaskResponse;
  isCurrent: boolean;
  revealDelayMs: number;
  canConfirm: boolean;
  confirming: boolean;
  onConfirm: () => void;
  }) {
  const pending = check.status === 'PENDING' || check.status === 'NOT_CHECKED';
  const executing = isCurrent && pending;
  const skipped = check.status === 'SKIPPED';
  const passed = check.status === 'PASSED' || (!check.status && check.passed);
  const showConfirmButton = canConfirm && isCurrent && !pending && !passed;
  const stateClass = executing
    ? 'border-l-4 border-blue-500 border-t border-r border-b border-blue-200 bg-blue-50'
    : pending
    ? 'border-l-4 border-slate-500 border-t border-r border-b border-slate-500/20 bg-slate-500/10 opacity-60'
    : skipped
    ? 'border-l-4 border-slate-400 border-t border-r border-b border-slate-200 bg-slate-50'
    : passed
    ? 'border-l-4 border-emerald-500 border-t border-r border-b border-emerald-200 bg-emerald-50'
    : 'border-l-4 border-red-500 border-t border-r border-b border-red-200 bg-red-50 animate-pulse';
  const resultClass = executing
    ? 'text-blue-700'
    : pending
      ? 'text-slate-400'
      : skipped
        ? 'text-slate-500'
        : passed ? 'text-emerald-700' : 'text-red-700';
  const expectedValue = checkValue(check, item, 'expected', task);
  const actualValue = checkValue(check, item, 'actual', task);
  const compactConclusion = compactCheckConclusion(check, item);
  const exceptionConclusion = compactExceptionConclusion(check, item);
  const detailTitle = checkExceptionDescription(item, check);
  const conclusion = executing
    ? '执行中...'
    : pending
      ? '待校验'
      : skipped
        ? '已跳过'
        : passed
          ? compactConclusion
          : exceptionConclusion;

  return (
    <div
      className={`h-[112px] min-w-0 overflow-hidden rounded border px-2.5 py-2 ${stateClass}`}
      style={{
        animation: 'f01-check-fade-in 220ms ease-out both',
        animationDelay: `${revealDelayMs}ms`,
      }}
    >
      <div className="flex h-full min-w-0 flex-col text-[12px]">
        <div className="flex min-w-0 items-center gap-1.5">
          {executing
            ? <Loader2 size={14} className="shrink-0 animate-spin text-blue-700" />
            : passed
              ? <CheckCircle size={14} className="shrink-0 text-emerald-700" />
              : skipped
                ? <SkipForward size={14} className="shrink-0 text-slate-500" />
              : pending
                ? <span className="shrink-0 text-slate-400">⏳</span>
                : <AlertTriangle size={14} className="shrink-0 text-red-700" />}
          <span
            className="min-w-0 truncate font-semibold text-slate-900"
            title={CHECK_TYPE_LABEL[check.checkType] ?? check.checkType}
          >
            {CHECK_TYPE_LABEL[check.checkType] ?? check.checkType}
          </span>
          {showConfirmButton && (
            <button
              type="button"
              onClick={onConfirm}
              disabled={confirming}
              className="ml-auto inline-flex shrink-0 items-center gap-1 rounded border border-amber-300 bg-amber-50 px-2 py-1 text-[11px] font-semibold text-amber-700 hover:bg-amber-100 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {confirming && <Loader2 size={11} className="animate-spin" />}
              {confirming ? '确认中' : '确认异常'}
            </button>
          )}
        </div>
        <div className="mt-1.5 grid min-w-0 gap-0.5 leading-4 text-[11px]">
          <div className="flex min-w-0 gap-1">
            <span className="shrink-0 text-slate-500">期望：</span>
            <span className="min-w-0 truncate font-mono text-slate-800" title={expectedValue}>
              {expectedValue}
            </span>
          </div>
          <div className="flex min-w-0 gap-1">
            <span className="shrink-0 text-slate-500">实际：</span>
            <span className="min-w-0 truncate font-mono text-slate-800" title={actualValue}>
              {actualValue}
            </span>
          </div>
          <div className={`flex min-w-0 gap-1 font-medium ${resultClass}`} title={detailTitle}>
            <span className="shrink-0 text-slate-500">结论：</span>
            <span className="min-w-0 truncate">{conclusion}</span>
          </div>
        </div>
      </div>
    </div>
  );
}

function checkValue(
  check: BudgetReviewCheckResponse,
  item: BudgetReviewItemResponse,
  side: 'expected' | 'actual',
  task?: BudgetReviewTaskResponse,
): string {
  if (check.status === 'SKIPPED' && side === 'actual') return '-';
  if (side === 'expected' && isSubjectMatchFailure(check, item)) return '-';
  if (check.checkType === 'REQUIRED_FIELDS') {
    if (side === 'expected') return '5个字段全部填写';
    const filledCount = requiredFieldCount(item);
    return item.missingFields
      ? `${filledCount}/5（缺失：${item.missingFields}）`
      : `${filledCount}/5`;
  }
  if (check.checkType === 'AMOUNT_FORMAT') {
    if (side === 'expected') return '正数，≤2位小数';
    return item.declaredAmount === null
      ? item.amountFormatErrors ? `格式非法：${item.amountFormatErrors}` : (check.actualValue || '-')
      : formatAmount(item.declaredAmount);
  }
  if (check.checkType === 'CATEGORY_ATTRIBUTION') {
    if (side === 'expected') return '科目编码所属分类';
    return item.subjectName || check.actualValue || '-';
  }
  if (check.checkType === 'BUDGET_AMOUNT') {
    return formatMoneyText(side === 'expected' ? check.expectedValue : check.actualValue);
  }
  if (check.checkType === 'PROJECT_CODE' && side === 'expected') {
    return '同批次唯一';
  }
  if (check.checkType === 'PROJECT_DEPARTMENT' && side === 'actual') {
    return projectDepartmentActualValue(item, task);
  }
  if (check.checkType === 'PROJECT_DEPARTMENT' && side === 'expected') {
    return departmentLabel(task?.departmentCode ?? null);
  }
  if (check.checkType === 'BUDGET_YEAR_VALIDITY' && side === 'expected') {
    return task?.budgetYear || '当前年度';
  }
  if (check.checkType === 'BUDGET_YEAR_VALIDITY' && side === 'actual') {
    return budgetYearActualValue(item, task);
  }
  return (side === 'expected' ? check.expectedValue : check.actualValue) || '-';
}

function requiredFieldCount(item: BudgetReviewItemResponse): number {
  return [
    item.subjectCode,
    item.subjectName,
    item.declaredAmount,
    item.supplierName,
    item.projectCode,
  ].filter((value) => value !== null && value !== undefined
    && (typeof value !== 'string' || value.trim() !== '')).length;
}

function isSubjectMatchFailure(
  check: BudgetReviewCheckResponse,
  item: BudgetReviewItemResponse,
): boolean {
  return (check.checkType === 'SUBJECT_CODE' || check.checkType === 'SUBJECT_NAME')
    && (item.matchStatus === 'UNMATCHED'
      || item.matchStatus === 'DATA_ERROR'
      || (check.status === 'FAILED' && !check.expectedValue));
}

function projectDepartmentActualValue(
  item: BudgetReviewItemResponse,
  task?: BudgetReviewTaskResponse,
): string {
  if (!item.projectCode?.trim()) return '-';
  const reason = (item.abnormalReasons ?? []).find((value) =>
    value.includes('项目归属') || value.includes('项目编号不存在'));
  if (reason?.includes('项目编号不存在')) return '未找到项目配置';
  const match = reason?.match(/项目归属\s*([^，。；]+)/);
  if (match?.[1]) return match[1].trim();
  if (item.projectDepartmentStatus === 'PASSED') {
    return departmentLabel(task?.departmentCode ?? null);
  }
  return '项目归属部门未返回';
}

function budgetYearActualValue(
  item: BudgetReviewItemResponse,
  task?: BudgetReviewTaskResponse,
): string {
  const year = task?.budgetYear || '当前年度';
  const reasons = item.abnormalReasons ?? [];
  const start = reasons
    .map((reason) => reason.match(/起始年度：(\d{4})/)?.[1])
    .find(Boolean);
  const end = reasons
    .map((reason) => reason.match(/有效期至：(\d{4})/)?.[1])
    .find(Boolean);
  if (start && end) return `${start}-${end}`;
  if (start) return `${start}年起`;
  if (end) return `截至${end}年`;
  if (!item.subjectCode?.trim()) return '-';
  return `有效期已覆盖${year}`;
}

function checksForItem(item: BudgetReviewItemResponse): BudgetReviewCheckResponse[] {
  const existing = new Map((item.checks ?? []).map((check) => [check.checkType, check]));
  const executionBoundary = item.currentStep ?? 0;
  return CHECK_TYPE_ORDER.map((checkType, index) => {
    const check = existing.get(checkType) ?? fallbackCheck(item, checkType);
    // Old tasks can contain NOT_CHECKED/PENDING for a step that the persisted
    // currentStep proves was already passed. Keep the UI consistent with the
    // engine and show it as skipped instead of reopening a stale wait state.
    if (executionBoundary > index + 1
      && (check.status === 'PENDING' || check.status === 'NOT_CHECKED')) {
      return {
        ...check,
        passed: false,
        status: 'SKIPPED',
        errorDetail: check.errorDetail && check.errorDetail !== '未校验'
          ? `已跳过：${check.errorDetail}`
          : '已跳过：该校验项未产生可比数据',
      };
    }
    return check;
  });
}

function currentStepForItem(
  item: BudgetReviewItemResponse,
  checks: BudgetReviewCheckResponse[],
): number {
  const firstPending = checks.findIndex((check) =>
    check.status === 'PENDING' || check.status === 'NOT_CHECKED');
  const firstFailed = checks.findIndex((check) =>
    check.status === 'FAILED' || check.status === 'WARNING'
      || (!check.status && !check.passed));
  // The first pending boundary tells us how far the engine has actually
  // executed. Failed checks before that boundary are historical results and
  // must not reset the row to the first exception after confirmation.
  // currentStep is a count of visible checks, so include the current pending
  // check itself. This also keeps a first-step pause visible instead of
  // producing an empty detail panel.
  const inferredStep = firstPending >= 0
    ? firstPending + 1
    : firstFailed >= 0 ? firstFailed + 1 : checks.length;
  if (firstPending >= 0) {
    // A stale persisted currentStep must never make a not-yet-executed check
    // look completed. The first pending check is the visible pause boundary.
    return Math.min(
      item.currentStep && item.currentStep > 0 ? item.currentStep : inferredStep,
      inferredStep,
    );
  }
  return item.currentStep && item.currentStep > 0
    ? Math.min(Math.max(item.currentStep, inferredStep), checks.length)
    : inferredStep;
}

function fallbackCheck(
  item: BudgetReviewItemResponse,
  checkType: string,
): BudgetReviewCheckResponse {
  if (checkType === 'REQUIRED_FIELDS') {
    const status = item.requiredFieldsStatus === 'NOT_CHECKED'
      ? 'PENDING'
      : item.requiredFieldsStatus || (item.missingFields ? 'FAILED' : 'PASSED');
    return {
      checkType,
      expectedValue: '5个字段全部填写',
      actualValue: item.missingFields ? `缺失：${item.missingFields}` : '全部已填写',
      passed: status === 'PASSED',
      status,
      errorDetail: status === 'PASSED' ? '必填字段完整' : `必填字段缺失：${item.missingFields}`,
    };
  }
  if (checkType === 'AMOUNT_FORMAT') {
    const status = item.amountFormatStatus === 'NOT_CHECKED'
      ? 'PENDING'
      : item.amountFormatStatus || (item.amountFormatErrors ? 'FAILED' : 'PASSED');
    return {
      checkType,
      expectedValue: '正数，≤2位小数',
      actualValue: item.declaredAmount === null ? item.amountFormatErrors : formatAmount(item.declaredAmount),
      passed: status === 'PASSED',
      status,
      errorDetail: status === 'PASSED' ? '金额格式合法' : `金额格式非法：${item.amountFormatErrors}`,
    };
  }
  if (checkType === 'CATEGORY_ATTRIBUTION') {
    const status = item.categoryAttributionStatus === 'FAILED'
      ? 'FAILED'
      : item.categoryAttributionStatus === 'NOT_CHECKED' ? 'PENDING' : 'PASSED';
    const categoryReason = (item.abnormalReasons ?? [])
      .find((reason) => reason.startsWith('科目分类归属'));
    return {
      checkType,
      expectedValue: '科目编码所属分类',
      actualValue: item.subjectName,
      passed: status === 'PASSED',
      status,
      errorDetail: status === 'PASSED'
        ? '科目分类归属正常'
        : categoryReason || '科目分类归属不匹配',
    };
  }
  if (checkType === 'BUDGET_AMOUNT') {
    const status = item.amountStatus === 'PASSED' ? 'PASSED'
      : item.amountStatus === 'SKIPPED' ? 'SKIPPED'
      : item.amountStatus === 'NOT_CHECKED' ? 'PENDING' : 'FAILED';
    return {
      checkType,
      expectedValue: formatAmount(item.approvalAmount),
      actualValue: formatAmount(item.declaredAmount),
      passed: status === 'PASSED',
      status,
      errorDetail: status === 'SKIPPED'
        ? '已跳过：科目未匹配到预算批复，暂无可比较金额'
        : AMOUNT_STATUS_LABEL[item.amountStatus] ?? '未校验',
    };
  }
  if (checkType === 'SUBJECT_NAME') {
    const status = item.matchStatus === 'NOT_CHECKED' ? 'PENDING'
      : item.matchStatus === 'MATCHED' ? 'PASSED' : 'FAILED';
    return {
      checkType,
      expectedValue: item.approvalSubjectName,
      actualValue: item.subjectName,
      passed: status === 'PASSED',
      status,
      errorDetail: MATCH_STATUS_LABEL[item.matchStatus] ?? '未校验',
    };
  }
  if (checkType === 'SUPPLIER_NAME') {
    const status = !item.approvalSupplierName && !item.supplierName
      ? 'PENDING'
      : normalizeText(item.approvalSupplierName) === normalizeText(item.supplierName)
        ? 'PASSED' : 'FAILED';
    return {
      checkType,
      expectedValue: item.approvalSupplierName,
      actualValue: item.supplierName,
      passed: status === 'PASSED',
      status,
      errorDetail: '供应商名称为空或不一致',
    };
  }
  if (checkType === 'PROJECT_CODE') {
    const status = item.projectCodeStatus === 'NOT_CHECKED'
      ? 'PENDING'
      : item.projectCodeStatus || (!item.projectCode?.trim() ? 'FAILED' : 'PASSED');
    return {
      checkType,
      expectedValue: '同批次唯一',
      actualValue: item.projectCode,
      passed: status === 'PASSED',
      status,
      errorDetail: status === 'PASSED' ? '项目编号唯一' : item.projectCode?.trim()
        ? '项目编号重复' : '项目编号为空',
    };
  }
  if (checkType === 'PROJECT_DEPARTMENT') {
    const status = item.projectDepartmentStatus === 'NOT_CHECKED'
      ? 'PENDING'
      : item.projectDepartmentStatus || (!item.projectCode?.trim() ? 'PENDING' : 'PASSED');
    return {
      checkType,
      expectedValue: '项目属于当前部门',
      actualValue: item.projectCode,
      passed: status === 'PASSED',
      status,
      errorDetail: status === 'PASSED' ? '项目与部门匹配'
        : status === 'NOT_CHECKED' ? '项目编号为空，暂无法校验' : '项目不存在或不属于当前部门',
    };
  }
  if (checkType === 'BUDGET_YEAR_VALIDITY') {
    const status = item.budgetYearValidityStatus === 'NOT_CHECKED'
      ? 'PENDING'
      : item.budgetYearValidityStatus
        || (!item.subjectCode?.trim() ? 'PENDING' : 'PASSED');
    return {
      checkType,
      expectedValue: '所选年度在有效期内',
      actualValue: item.subjectCode,
      passed: status === 'PASSED',
      status,
      errorDetail: status === 'PASSED' ? '预算年度有效' : '预算年度无效',
    };
  }
  if (checkType === 'SUBJECT_CODE') {
    const status = item.codeFormatError ? 'FAILED'
      : item.subjectCode && item.approvalSubjectCode
        ? item.subjectCode.trim().toLowerCase() === item.approvalSubjectCode.trim().toLowerCase()
          ? 'PASSED' : 'FAILED'
        : 'PENDING';
    return {
      checkType,
      expectedValue: item.approvalSubjectCode,
      actualValue: item.subjectCode,
      passed: status === 'PASSED',
      status,
      errorDetail: status === 'PASSED' ? '科目编码一致'
        : status === 'PENDING' ? '科目编码待校验' : '科目编码为空或不一致',
    };
  }
  const status = item.codeFormatError ? 'FAILED' : 'PASSED';
  return {
    checkType,
    expectedValue: item.approvalSubjectCode,
    actualValue: item.subjectCode,
    passed: status === 'PASSED',
    status,
    errorDetail: '科目编码为空或不一致',
  };
}

function normalizeText(value: string | null): string {
  return (value ?? '').trim().toLowerCase();
}
