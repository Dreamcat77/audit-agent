import { useEffect, useRef, useState } from "react";
import {
  AlertTriangle,
  ArrowLeft,
  Check,
  LoaderCircle,
  ShieldAlert,
  X,
} from "lucide-react";
import {
  confirmProcurementCriticalRisk,
  getProcurementCollusionReview,
  getProcurementCriticalRiskConfirmation,
  getProcurementQuoteCheck,
  getProcurementReviewReport,
  getProcurementReviewWorkflow,
  startProcurementReviewWorkflow,
} from "../../../api/audit";
import type {
  BlacklistBatchCheckResponse,
  ProcurementCollusionReview,
  ProcurementCriticalRiskConfirmation,
  ProcurementProject,
  ProcurementQuoteCheckResponse,
  ProcurementReviewReport,
  ProcurementReviewWorkflow,
} from "../../../api/types";
import { ApiError,apiUrl } from "../../../api/client";
import { procurementReferenceView } from "./procurementReferenceView";
import { createOperationKey } from "../../../api/operationKey.ts";
import { userFacingError } from "../../../api/userFacingError";
import ProcurementTenderReviewPanel from "../ProcurementTenderReviewPanel";
import { ProcurementQuoteAdvice } from './ProcurementQuoteAdvice';
import { useIntakeReview } from './procurementIntakeReviewStore';
import { useAuth } from '../../../auth/AuthProvider';
import {
  Step2OverBudget,
  Step3PricePattern,
  Step4Blacklist,
  ProcurementCollusionResults,
  Step7ReviewReport,
} from "./ProcurementReviewSteps";



type Props = { project: ProcurementProject; embedded?: boolean; onBack?: () => void };

export default function ProcurementReviewWizard({ project, embedded = false, onBack }: Props) {
  const { user } = useAuth();
  const canAnalyze = user?.roles.some(role => ['SYSTEM_ADMIN', 'PROCUREMENT_MANAGER', 'AUDITOR'].includes(role)) ?? false;
  const { operation: intakeOperation, snapshot: intakeState } = useIntakeReview(user?.userId ?? '', project.id);
  const [workflow, setWorkflow] = useState<ProcurementReviewWorkflow | null>(null);
  const [activeStep, setActiveStep] = useState(5);
  const [quote, setQuote] = useState<ProcurementQuoteCheckResponse | null>(null);
  const [blacklist, setBlacklist] = useState<BlacklistBatchCheckResponse | null>(null);
  const [collusion, setCollusion] = useState<ProcurementCollusionReview | null>(null);
  const [report, setReport] = useState<ProcurementReviewReport | null>(null);
  const [reportLoading, setReportLoading] = useState(false);
  const [exporting,setExporting]=useState(false);
  const [reportError, setReportError] = useState<string | null>(null);
  const [collusionNotice, setCollusionNotice] = useState<string | null>(null);
  const [reportNotice, setReportNotice] = useState<string | null>(null);

  const [criticalRiskConfirmation, setCriticalRiskConfirmation] = useState<ProcurementCriticalRiskConfirmation | null>(null);
  const [criticalRiskConfirmationLoading, setCriticalRiskConfirmationLoading] = useState(false);
  const [criticalRiskConfirmationError, setCriticalRiskConfirmationError] = useState<string | null>(null);
  const [criticalRiskModalOpen, setCriticalRiskModalOpen] = useState(false);
  const [criticalRiskNote, setCriticalRiskNote] = useState("");
  const criticalRiskOperationKey = useRef<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  async function refreshWorkflow() {
    const current = await getProcurementReviewWorkflow(project.id);
    setWorkflow(current);
    return current;
  }

  useEffect(() => {
    let active = true;
    // Reopen and command completion read durable results, including changes from other sessions.
    if (intakeState.pending) return () => { active = false; };
    setLoading(true); setError(null); setWorkflow(null); setQuote(null); setBlacklist(null); setCollusion(null); setReport(null); setCollusionNotice(null); setReportNotice(null); setActiveStep(5); setCriticalRiskConfirmation(null); setCriticalRiskConfirmationError(null); setCriticalRiskModalOpen(false); setCriticalRiskNote(""); criticalRiskOperationKey.current = null;
    Promise.allSettled([
      getProcurementReviewWorkflow(project.id),
      getProcurementQuoteCheck(project.id),
      getProcurementCollusionReview(project.id),
      getProcurementReviewReport(project.id),
    ]).then(([workflowResult, quoteResult, collusionResult, reportResult]) => {
      if (!active || intakeOperation.snapshot().pending) return;
      const hasSavedReport = reportResult.status === "fulfilled";
      if (workflowResult.status === "fulfilled") { setWorkflow(workflowResult.value); setActiveStep(hasSavedReport || workflowResult.value.currentStepOrder >= 7 ? 7 : 5); }
      else { void startProcurementReviewWorkflow(project.id).then((created) => { if (active) { setWorkflow(created); setActiveStep(hasSavedReport || created.currentStepOrder >= 7 ? 7 : 5); } }).catch((cause) => { if (active) setError(userFacingError(cause, "采购材料与结果加载失败，请稍后重试。")); }); }
      if (quoteResult.status === "fulfilled") setQuote(quoteResult.value);
      else if (!isNotFound(quoteResult.reason)) setError(userFacingError(quoteResult.reason, "询比价结果加载失败，请稍后重试。"));
      if (collusionResult.status === "fulfilled") setCollusion(collusionResult.value);
      else if (isNotFound(collusionResult.reason)) setCollusionNotice(null);
      else setError(userFacingError(collusionResult.reason, "围串标评分结果加载失败，请稍后重试。"));
      if (reportResult.status === "fulfilled") { setReport(reportResult.value); setActiveStep(7); }
      else if (isNotFound(reportResult.reason) || (reportResult.reason instanceof ApiError && reportResult.reason.status === 409)) setReportNotice(null);
      else setError(userFacingError(reportResult.reason, "采购审查报告加载失败，请稍后重试。"));
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [project.id, intakeState.pending]);

  useEffect(() => {
    let active = true;
    criticalRiskOperationKey.current = null;
    setCriticalRiskConfirmation(null);
    setCriticalRiskConfirmationError(null);
    setCriticalRiskNote("");
    if (!report || report.riskLevel !== "CRITICAL") {
      setCriticalRiskModalOpen(false);
      setCriticalRiskConfirmationLoading(false);
      return () => { active = false; };
    }
    setCriticalRiskConfirmationLoading(true);
    void getProcurementCriticalRiskConfirmation(project.id, report.id)
      .then((confirmation) => {
        if (!active) return;
        setCriticalRiskConfirmation(confirmation);
        setCriticalRiskModalOpen(false);
        if (!confirmation) setActiveStep(7);
      })
      .catch((cause) => {
        if (!active) return;
        setCriticalRiskConfirmationError(userFacingError(
          cause,
          "严重风险确认状态加载失败，请稍后重试。",
        ));
        setCriticalRiskModalOpen(false);
        setActiveStep(7);
      })
      .finally(() => { if (active) setCriticalRiskConfirmationLoading(false); });
    return () => { active = false; };
  }, [project.id, report?.id, report?.riskLevel]);

  async function exportReport() {
    if(!report||exporting)return;setExporting(true);setReportError(null);
    try {
      const response=await fetch(apiUrl(`/api/v1/procurement/projects/${encodeURIComponent(project.id)}/review-report/export?reportId=${encodeURIComponent(report.id)}`),{credentials:'include'});
      if(!response.ok||!response.headers.get('content-type')?.includes('wordprocessingml.document')) {
        const payload=await response.json().catch(()=>null);throw Error(payload?.message||'报告导出未完成，请刷新结果后再试');
      }
      const url=URL.createObjectURL(await response.blob()),link=document.createElement('a');
      link.href=url;link.download='采购审查参考报告.docx';document.body.appendChild(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),1000);
    }catch(cause){setReportError(userFacingError(cause,'报告导出未完成，请稍后再试'));}finally{setExporting(false);}
  }
  function movePrevious() { setActiveStep(5); }

  async function confirmCriticalRisk() {
    if (!report || report.riskLevel !== "CRITICAL" || criticalRiskConfirmationLoading) return;
    if (!criticalRiskOperationKey.current) {
      criticalRiskOperationKey.current = loadOrCreateCriticalRiskOperation(report.id);
    }
    setCriticalRiskConfirmationLoading(true);
    setCriticalRiskConfirmationError(null);
    try {
      const confirmation = await confirmProcurementCriticalRisk(
        project.id,
        report.id,
        criticalRiskOperationKey.current,
        criticalRiskNote,
      );
      setCriticalRiskConfirmation(confirmation);
      setCriticalRiskModalOpen(false);
      clearCriticalRiskOperation(report.id);
      criticalRiskOperationKey.current = null;
    } catch (cause) {
      setCriticalRiskConfirmationError(userFacingError(
        cause,
        "严重风险确认失败，请保留当前页面并重试。",
      ));
    } finally {
      setCriticalRiskConfirmationLoading(false);
    }
  }

  if (intakeState.pending) return <div role="status" className="flex items-center gap-2 rounded border border-blue-200 bg-blue-50 px-4 py-5 text-xs text-blue-700"><LoaderCircle size={14} className="shrink-0 animate-spin" /><span>采购需求已保存，正在分析已上传的材料并生成结果，无需再次上传。可以切换功能，稍后从该项目查看结果。</span></div>;
  if (loading) return <div className="flex items-center gap-2 rounded border border-blue-200 bg-blue-50 px-4 py-5 text-xs text-blue-700"><LoaderCircle size={14} className="animate-spin" />正在加载采购材料与结果...</div>;
  if (error) return <div className="space-y-3"><ErrorBlock message={error} />{!embedded && onBack && <button type="button" onClick={onBack} className="inline-flex items-center gap-2 rounded border border-slate-300 bg-white px-3 py-2 text-xs text-slate-700"><ArrowLeft size={14} />返回项目详情</button>}</div>;



  const displayedRisk=report?procurementReferenceView(report).riskLevel:null;
  return <div className="space-y-5">
    {intakeState.error && !report && <div role="alert" className="rounded border border-amber-200 bg-amber-50 px-4 py-3 text-xs text-amber-800">{intakeState.error}{canAnalyze && <button type="button" onClick={() => void intakeOperation.start()} className="ml-3 underline">继续分析已上传材料</button>}</div>}
    {!canAnalyze && !report && Boolean(project.sourceMaterialIds?.length) && <p className="rounded border border-blue-200 bg-blue-50 px-4 py-3 text-xs text-blue-700">采购需求和材料已保存，请由有审查权限的人员处理并查看结果。</p>}
    <div>{!embedded && onBack && <button type="button" onClick={onBack} className="mb-2 inline-flex items-center gap-1 text-xs text-slate-500 hover:text-blue-700"><ArrowLeft size={13} />返回项目详情</button>}<h2 className="text-base font-semibold text-slate-900">采购审查</h2></div>

    {report?.riskLevel === "CRITICAL" && displayedRisk === "CRITICAL" && <section className={`flex flex-wrap items-center gap-3 rounded-lg border px-4 py-3 text-xs ${criticalRiskConfirmation ? "border-emerald-200 bg-emerald-50 text-emerald-800" : "border-red-200 bg-red-50 text-red-800"}`}>
      {criticalRiskConfirmation ? <Check size={16} className="shrink-0" /> : <ShieldAlert size={16} className="shrink-0" />}
      <div className="min-w-0 flex-1">{criticalRiskConfirmation ? <><strong>严重风险已确认</strong><p className="mt-1">{criticalRiskConfirmation.confirmerName} · {formatConfirmationTime(criticalRiskConfirmation.confirmedTime)}</p></> : <><strong>参考结果包含严重风险</strong><p className="mt-1">请结合原材料核实，确认记录不影响查看参考结果。</p></>}</div>
      {!criticalRiskConfirmation && <button type="button" onClick={() => setCriticalRiskModalOpen(true)} disabled={criticalRiskConfirmationLoading} className="inline-flex items-center gap-1.5 rounded border border-red-300 bg-white px-3 py-2 font-medium text-red-700 hover:bg-red-50 disabled:opacity-50">{criticalRiskConfirmationLoading && <LoaderCircle size={13} className="animate-spin" />}查看并确认</button>}
    </section>}
    {criticalRiskConfirmationError && <ErrorBlock message={criticalRiskConfirmationError} />}
    {report && quote && <ProcurementQuoteAdvice result={quote} />}
    {activeStep===7&&report&&<div className="flex justify-end"><button type="button" onClick={()=>void exportReport()} disabled={exporting} className="rounded border border-slate-300 bg-white px-3 py-2 text-xs text-slate-700 disabled:opacity-50">{exporting?'正在导出…':'导出完整报告'}</button></div>}

    {activeStep === 5 && <ProcurementTenderReviewPanel projectId={project.id} analyzed={Boolean(report)}
      onAnalyzeIntake={canAnalyze && project.sourceMaterialIds?.length ? () => void intakeOperation.start() : undefined} onBatch={result => {
      setQuote(result.quote); setCollusion(result.collusion); if (result.blacklist) setBlacklist(result.blacklist);
      if (result.report) { setReport(result.report); setReportError(null); setReportNotice(null); }
    }} onMaterialsChanged={() => { intakeOperation.reset(); setReport(null); setQuote(null); setCollusion(null); setBlacklist(null); setCriticalRiskConfirmation(null); setReportNotice(null); void refreshWorkflow(); }}
      onCompleted={() => { setActiveStep(7); void refreshWorkflow(); }} />}
    {activeStep === 7 && report && (quote || blacklist || collusion) && <details className="rounded-lg border border-slate-200 bg-white p-4">
      <summary className="cursor-pointer text-xs font-medium text-slate-700">查看分析明细与依据</summary>
      <p className="mt-2 text-[11px] text-slate-500">以下为本次统一分析已保存的结果，无需再次分析。</p>
      <div className="mt-4 space-y-4">
        {quote && <><details><summary className="cursor-pointer text-xs text-slate-600">超预算告警与报价规律</summary><div className="mt-3 space-y-4"><Step2OverBudget quote={quote} /><Step3PricePattern quote={quote} /></div></details>
          </>}
        {blacklist && <Step4Blacklist result={blacklist} loading={false} error={null} onRun={() => {}} />}
        {collusion && <ProcurementCollusionResults assessment={collusion} quote={quote} />}
      </div>
    </details>}
    {activeStep === 7 && <Step7ReviewReport riskConfirmation={{confirmed:Boolean(criticalRiskConfirmation),loading:criticalRiskConfirmationLoading,onConfirm:()=>setCriticalRiskModalOpen(true)}} projectId={project.id} report={report} emptyNotice={reportNotice} loading={reportLoading} error={reportError} onError={setReportError} onCompleted={async (result) => { setReport(result); setReportError(null); setReportNotice(null); await refreshWorkflow(); }} />}
    {(activeStep !== 5 || report) && <div className="flex justify-start border-t border-slate-200 pt-4">{activeStep === 5 ? <button type="button" onClick={()=>setActiveStep(7)} className="rounded border border-slate-300 bg-white px-3 py-2 text-xs text-slate-700">查看已有结果</button> : <button type="button" onClick={movePrevious} className="inline-flex items-center gap-2 rounded border border-slate-300 bg-white px-3 py-2 text-xs text-slate-700"><ArrowLeft size={13}/>返回上传材料</button>}</div>}

    {criticalRiskModalOpen && report?.riskLevel === "CRITICAL" && <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 p-4" role="dialog" aria-modal="true" aria-labelledby="critical-risk-title">
      <div className="w-full max-w-lg rounded-lg bg-white shadow-xl">
        <div className="flex items-start gap-3 border-b border-red-100 px-5 py-4"><span className="flex size-10 shrink-0 items-center justify-center rounded-full bg-red-100 text-red-700"><AlertTriangle size={20} /></span><div className="min-w-0 flex-1"><h3 id="critical-risk-title" className="text-base font-semibold text-slate-900">发现严重风险</h3><p className="mt-1 text-xs text-slate-500">报告 {report.reportNumber}</p></div><button type="button" onClick={() => setCriticalRiskModalOpen(false)} className="rounded p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-700" aria-label="返回查看报告" title="返回查看报告"><X size={18} /></button></div>
        <div className="space-y-4 px-5 py-4"><div className="rounded border border-red-200 bg-red-50 px-4 py-3 text-sm leading-6 text-red-900">{report.overallConclusion}</div><p className="text-xs leading-5 text-slate-600">确认表示您已阅读并知悉上述严重风险。系统会记录确认人、确认时间、报告编号和确认内容，该记录仅表示已知悉风险。</p><label className="block text-xs text-slate-600">确认说明（选填）<textarea value={criticalRiskNote} onChange={(event) => setCriticalRiskNote(event.target.value)} maxLength={1000} rows={3} className="mt-2 w-full resize-y rounded border border-slate-200 px-3 py-2 text-sm text-slate-800 outline-none focus:border-red-300" placeholder="可填写已核实情况或后续处理安排" /></label>{criticalRiskConfirmationError && <ErrorBlock message={criticalRiskConfirmationError} />}</div>
        <div className="flex flex-wrap justify-end gap-2 border-t border-slate-100 px-5 py-4"><button type="button" onClick={() => setCriticalRiskModalOpen(false)} className="rounded border border-slate-200 bg-white px-4 py-2 text-xs text-slate-600 hover:bg-slate-50">返回查看报告</button><button type="button" onClick={() => void confirmCriticalRisk()} disabled={criticalRiskConfirmationLoading} className="inline-flex items-center gap-2 rounded bg-red-600 px-4 py-2 text-xs font-medium text-white hover:bg-red-700 disabled:cursor-not-allowed disabled:opacity-50">{criticalRiskConfirmationLoading ? <LoaderCircle size={14} className="animate-spin" /> : <ShieldAlert size={14} />}{criticalRiskConfirmationLoading ? "确认中..." : "确认已知悉严重风险"}</button></div>
      </div>
    </div>}
  </div>;
}

function ErrorBlock({ message }: { message: string }) { return <div role="alert" className="rounded border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700">{message}</div>; }

function isNotFound(error: unknown): error is ApiError {
  return error instanceof ApiError && error.status === 404;
}

const CRITICAL_RISK_OPERATION_PREFIX = "audit-agent:procurement:critical-risk-confirmation:";

function loadOrCreateCriticalRiskOperation(reportId: string): string {
  const storageKey = `${CRITICAL_RISK_OPERATION_PREFIX}${encodeURIComponent(reportId)}`;
  const retained = window.sessionStorage.getItem(storageKey);
  if (retained) return retained;
  const created = `procurement-critical-risk-${createOperationKey()}`;
  window.sessionStorage.setItem(storageKey, created);
  return created;
}

function clearCriticalRiskOperation(reportId: string): void {
  const storageKey = `${CRITICAL_RISK_OPERATION_PREFIX}${encodeURIComponent(reportId)}`;
  window.sessionStorage.removeItem(storageKey);
}

function formatConfirmationTime(value: string): string {
  const timestamp = Date.parse(value);
  return Number.isFinite(timestamp)
    ? new Date(timestamp).toLocaleString("zh-CN", { hour12: false })
    : value;
}
