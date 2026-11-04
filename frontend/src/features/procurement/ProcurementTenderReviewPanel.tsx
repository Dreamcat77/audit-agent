import { useEffect, useRef, useState } from "react";
import { ResultCopyButton } from './ResultCopyButton';
import { tenderExplanationText } from './review/tenderFindingDisplay';
import { AlertTriangle, CheckCircle, FileSearch, FileText, LoaderCircle, Upload, XCircle } from "lucide-react";
import {
  getProcurementMaterialBatch,
  stageProcurementMaterials,
  analyzeProcurementMaterials,
} from "../../api/audit";
import type {
  ProcurementTenderFinding,
  ProcurementMaterialBatchResponse,
  ProcurementMaterialFileResult,
} from "../../api/types";
import { userFacingError } from "../../api/userFacingError";
import { enumLabel, riskLevelLabel } from "../../utils/enumLabels";
import { procurementFileProblem } from './materials/procurementUploadQueue';
import { OFFICE_MATERIAL_ACCEPT, OFFICE_MATERIAL_PATTERN } from './materials/officeMaterialTypes';

type RiskLevel = "CRITICAL" | "WARNING" | "INFO";

const riskMeta: Record<RiskLevel, {
  label: string;
  border: string;
  background: string;
  text: string;
  icon: JSX.Element;
}> = {
  CRITICAL: { label: "严重风险", border: "border-red-200", background: "bg-red-50", text: "text-red-700", icon: <XCircle size={14} /> },
  WARNING: { label: "警告风险", border: "border-amber-200", background: "bg-amber-50", text: "text-amber-700", icon: <AlertTriangle size={14} /> },
  INFO: { label: "正常", border: "border-blue-200", background: "bg-blue-50", text: "text-blue-700", icon: <CheckCircle size={14} /> },
};

function riskLevel(value: string): RiskLevel {
  return value === "CRITICAL" || value === "WARNING" || value === "INFO" ? value : "INFO";
}

function parseMethodLabel(value: string | null): string {
  if (value === "DOCX_TEXT") return "Word 文本解析";
  if (value === "PDF_TEXT") return "PDF 文本解析";
  if (value === "OCR") return "OCR 解析";
  return value || "未完成解析";
}

function sourceLabel(value: string): string {
  if (value === "RULE_AND_AI") return "规则+AI";
  if (value === "AI") return "AI";
  return "规则";
}

function sourceClass(value: string): string {
  if (value === "RULE_AND_AI") return "border-indigo-200 bg-indigo-50 text-indigo-700";
  if (value === "AI") return "border-violet-200 bg-violet-50 text-violet-700";
  return "border-slate-200 bg-slate-50 text-slate-600";
}

function formatDateTime(value: string | null | undefined): string {
  if (!value) return "-";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString("zh-CN", { hour12: false });
}

function formatFileSize(value: number): string {
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}

function contextForFinding(finding: ProcurementTenderFinding, fullText: string | null): string | null {
  if (!fullText) return null;
  const matchIndex = fullText.indexOf(finding.matchedText);
  if (matchIndex < 0) return null;

  const start = Math.max(0, matchIndex - 100);
  const end = Math.min(fullText.length, matchIndex + finding.matchedText.length + 100);
  return `${start > 0 ? "…" : ""}${fullText.slice(start, end)}${end < fullText.length ? "…" : ""}`;
}

function HighlightedText({ text, matchedText }: { text: string; matchedText: string }) {
  const matchIndex = text.indexOf(matchedText);
  if (matchIndex < 0) return <>{text}</>;

  const before = text.slice(0, matchIndex);
  const after = text.slice(matchIndex + matchedText.length);
  return <>{before}<mark className="bg-amber-200 px-0.5 font-medium text-slate-900">{matchedText}</mark>{after}</>;
}

function FindingCard({ finding, fullText }: { finding: ProcurementTenderFinding; fullText: string | null }) {
  const [expanded, setExpanded] = useState(false);
  const meta = riskMeta[riskLevel(finding.riskLevel)];
  const context = contextForFinding(finding, fullText);
  return (
    <div className={`overflow-hidden rounded border ${meta.border} ${meta.background}`}>
      <button type="button" onClick={() => setExpanded((current) => !current)} className="flex w-full items-start justify-between gap-3 px-3 py-3 text-left hover:bg-white/40" aria-expanded={expanded}>
        <div className="min-w-0">
          <div className={`flex items-center gap-2 text-xs font-medium ${meta.text}`}>
            {meta.icon}<span>{meta.label}</span><span>{finding.riskCategory}</span>
            <span className={`rounded border px-1.5 py-0.5 text-[10px] ${sourceClass(finding.source)}`}>{sourceLabel(finding.source)}</span>
          </div>
          <p className="mt-1 text-xs leading-relaxed text-slate-700"><span className="text-slate-500">命中句：</span><mark className="bg-amber-200 px-0.5 font-medium text-slate-900">{finding.matchedText}</mark></p>
        </div>
        <span className="shrink-0 text-[11px] text-slate-500">{expanded ? "收起" : "查看详情"}</span>
      </button>
      {expanded && (
        <div className="space-y-2 border-t border-white/70 px-3 pb-3 pt-3 text-xs text-slate-700">
          <div className="grid gap-2 sm:grid-cols-[70px_1fr]">
            <span className="text-slate-500">原文片段</span>
            <div className="whitespace-pre-wrap leading-relaxed">
              {context ? <HighlightedText text={context} matchedText={finding.matchedText} /> : <>
                <mark className="bg-amber-200 px-0.5 font-medium text-slate-900">{finding.matchedText}</mark>
                <p className="mt-1 text-slate-500">{fullText ? "未能在解析全文中定位该命中句，以下展示命中内容。" : "原文已解析，可查看完整招标文件。"}</p>
              </>}
            </div>
            {finding.pageNumber != null && <><span className="text-slate-500">所在页</span><span>第 {finding.pageNumber} 页</span></>}
            <span className="text-slate-500">规则</span><span>{finding.ruleName}</span>
            {finding.legalBasis && <><span className="text-slate-500">法规依据</span><span>{finding.legalBasis}</span></>}
            {finding.ruleExplanation && <><span className="text-slate-500">规则说明</span><span>{finding.ruleExplanation}</span></>}
            {finding.riskReason && <><span className="text-slate-500">风险理由</span><span>{tenderExplanationText(finding.riskReason)}</span></>}
            <span className="text-slate-500">整改建议</span><span className="text-amber-800">{tenderExplanationText(finding.remediationSuggestion)}</span>
          </div>
        </div>
      )}
    </div>
  );
}

export default function ProcurementTenderReviewPanel({ projectId, analyzed = false, onCompleted, onBatch, onMaterialsChanged }: {
  projectId: string; onCompleted?: () => void; onBatch?: (result: ProcurementMaterialBatchResponse) => void;
  onMaterialsChanged?: () => void; analyzed?: boolean;
}) {
  const [batch, setBatch] = useState<ProcurementMaterialBatchResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [uploading, setUploading] = useState(false);
  const [analyzing, setAnalyzing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const flight = useRef(false);
  const generation = useRef(0);
  const analyzedScope = useRef<string | null>(null);

  useEffect(() => {
    const current = ++generation.current;
    flight.current = false;
    setUploading(false); setAnalyzing(false);
    setBatch(null); setError(null); setLoading(true); analyzedScope.current = null;
    getProcurementMaterialBatch(projectId).then(result => {
      if (current !== generation.current) return;
      setBatch(result); onBatch?.(result);
      if (!result.report && result.files.length) {
        const ids = result.files.map(file => file.document?.id).filter((id): id is string => Boolean(id));
        if (ids.length === result.files.length) void analyzeScope(ids, current);
      }
    }).catch(cause => {
      if (current === generation.current) setError(userFacingError(cause, "采购材料记录加载失败，请稍后重试。"));
    }).finally(() => { if (current === generation.current) setLoading(false); });
    return () => { generation.current++; };
  }, [projectId]);

  async function analyzeScope(ids: string[], current = generation.current) {
    const key = [...ids].sort().join('|');
    if (!ids.length || flight.current || analyzedScope.current === key) return;
    analyzedScope.current = key;
    flight.current = true; setAnalyzing(true); setError(null);
    try {
      const result = await analyzeProcurementMaterials(projectId, ids);
      if (current !== generation.current) return;
      setBatch(result); onBatch?.(result);
      if (result.report) onCompleted?.();
    } catch (cause) {
      if (current !== generation.current) return;
      setError(userFacingError(cause, "材料处理结果：暂未识别。原文件和已保存结果已保留。"));
      try {
        const saved = await getProcurementMaterialBatch(projectId);
        if (current === generation.current) { setBatch(saved); onBatch?.(saved); }
      } catch { /* Preserve the result already visible. */ }
    } finally {
      if (current === generation.current) { flight.current = false; setAnalyzing(false); }
    }
  }

  async function upload(files: File[]) {
    if (!files.length || flight.current) return;
    const current = generation.current;
    const accepted: File[] = [];
    const rejected: string[] = [];
    for (const file of files) {
      const problem = procurementFileProblem(file, OFFICE_MATERIAL_PATTERN);
      if (problem) rejected.push(`${file.name}：暂未识别`);
      else accepted.push(file);
    }
    flight.current = true; setUploading(true); setError(null);
    try {
      if (accepted.length) {
        const result = await stageProcurementMaterials(projectId, accepted);
        if (current !== generation.current) return;
        analyzedScope.current = result.files.map(item => item.document?.id).filter(Boolean).sort().join('|');
        setBatch(result); onBatch?.(result);
        onMaterialsChanged?.();
        if (result.report) onCompleted?.();
      }
      if (rejected.length && current === generation.current) setError(rejected.join('；'));
    } catch (cause) {
      if (current !== generation.current) return;
      setError(userFacingError(cause, "材料处理结果：暂未识别。原文件和已保存结果已保留。"));
      onMaterialsChanged?.();
      try { const saved = await getProcurementMaterialBatch(projectId); if (current === generation.current) setBatch(saved); } catch { /* Keep the known files and the same retry batch. */ }
    } finally {
      if (current === generation.current) {
        flight.current = false; setUploading(false);
        if (inputRef.current) inputRef.current.value = "";
      }
    }
  }

  return <section className="overflow-hidden rounded-lg border border-slate-200 bg-white">
    <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-200 px-5 py-4">
      <div className="flex items-center gap-2"><FileSearch size={16} className="text-indigo-700" /><div><h3 className="text-sm font-semibold text-slate-900">采购材料审查</h3><p className="mt-1 text-xs text-slate-500">可分次上传招标文件、投标文件和报价表，材料齐备后统一分析并自动生成报告</p></div></div>
      <input ref={inputRef} type="file" multiple accept={OFFICE_MATERIAL_ACCEPT} className="hidden" onChange={event => void upload(Array.from(event.target.files ?? []))} />
      <div className="flex items-center gap-2">
        <button type="button" onClick={() => inputRef.current?.click()} disabled={uploading || analyzing || loading} className="inline-flex items-center gap-2 rounded border border-slate-300 px-3 py-2 text-xs text-slate-700 hover:bg-slate-50 disabled:opacity-50">{uploading ? <LoaderCircle size={14} className="animate-spin" /> : <Upload size={14} />}{uploading ? "正在上传…" : "上传材料"}</button>
        {analyzing && <span role="status" className="inline-flex items-center gap-1 text-xs text-indigo-700"><LoaderCircle size={13} className="animate-spin" />系统自动解析并生成结果</span>}
      </div>
    </div>
    <div className="space-y-4 p-4">
      {loading && <p role="status" className="text-xs text-slate-500">正在加载已保存的材料和结果…</p>}
      {error && <div role="alert" className="rounded border border-red-200 bg-red-50 p-3 text-xs text-red-700">{error}</div>}
      {!loading && !batch?.files.length && <div className="rounded border border-dashed border-slate-300 bg-slate-50 px-4 py-5 text-center text-xs leading-6 text-slate-500">支持多选 Word、PDF、Excel、演示文稿、文本及图片扫描件；单文件最大 500 MB。上传后系统自动解析并生成结果，无法识别的信息显示“暂未识别”。</div>}
      {!!batch?.files.length && <p className="text-xs font-medium text-slate-700">已上传材料（{batch.files.length} 份）</p>}
      {batch?.notices.map((notice, i) => <p key={i} role="status" className="rounded bg-slate-50 p-3 text-xs text-slate-600">{notice}</p>)}
      {batch?.files.map((file, index) => <MaterialResult key={file.document?.id ?? `${file.fileName}-${index}`} file={file} processing={analyzing || uploading} />)}
    </div>
  </section>;
}

function MaterialResult({ file, processing }: { file: ProcurementMaterialFileResult; processing: boolean }) {
  const label = file.kind === "BIDDER" ? "投标材料" : file.kind === "QUOTATION" ? "报价材料" : file.kind === "GENERAL" ? "其他采购材料" : file.kind === "TENDER" ? "合规审查材料" : "其他";
  const fields = file.document?.parseResult?.extractedFields ?? [];
  const names: Record<string,string> = { projectName:'项目名称',projectNo:'项目编号',departmentName:'申请部门',budgetAmount:'预算金额',ceilingAmount:'最高限价',subjectCode:'科目编码',subjectName:'科目名称',procurementMethod:'采购方式',requirementDescription:'需求内容',applicantName:'申请人',purchaserName:'采购人/招标人',agencyName:'招标代理机构',supplierName:'供应商',quoteAmount:'报价金额',productDescription:'产品描述',procurementType:'采购类型' };
  const expected = file.kind === 'QUOTATION' ? ['supplierName','quoteAmount']
    : file.kind === 'BIDDER' ? ['supplierName']
    : file.kind === 'TENDER' ? ['projectName','projectNo','budgetAmount','ceilingAmount','procurementMethod']
    : ['projectName','departmentName','procurementType','budgetAmount'];
  const displayFields = expected.map(field => ({ field, value: [...new Set(fields.filter(item => item.field === field).map(item => item.value))].join('；') || '暂未识别' }));
  return <div data-copy-result className="rounded-lg border border-slate-200">
    <div data-copy-heading className="flex flex-wrap items-start justify-between gap-2 bg-slate-50 px-4 py-3 text-xs">
      <span className="flex min-w-0 items-center gap-2 font-medium text-slate-800"><FileText size={14} /><span className="break-all">{file.fileName}</span></span><span className="text-slate-500">{label}</span><ResultCopyButton/>
    </div>
    <div data-copy-content className="space-y-3 p-4 text-xs">
      <p role="status" className={['PARSE_FAILED','ANALYSIS_FAILED'].includes(file.status) ? 'text-red-700' : 'text-slate-600'}>
        {processing ? <><LoaderCircle size={12} className="mr-1 inline animate-spin" />正在统一分析材料…</> : file.message}
      </p>
      {!processing && <dl className="space-y-1 text-slate-700">{displayFields.map(field => <div key={field.field} className="break-words"><dt className="inline text-slate-500">{names[field.field] ?? '材料信息'}：</dt><dd className="inline">{field.value}</dd></div>)}</dl>}
      {file.scan && <>
        <p className="text-slate-600">合规审查：{file.scan.findings.length ? `发现 ${file.scan.findings.length} 条需关注内容` : "当前已保存结果未命中风险规则"}
          {file.scan.aiStatus && file.scan.aiStatus !== "AVAILABLE" && "；AI 分析暂不可用，当前仅含规则结果"}</p>
        {file.scan.findings.map(finding => <FindingCard key={finding.id} finding={finding} fullText={file.document?.parseResult?.fullText ?? null} />)}
      </>}
      {file.document?.parseResult && <details><summary className="cursor-pointer text-slate-500">查看解析原文</summary><pre className="mt-2 max-h-64 overflow-auto whitespace-pre-wrap rounded bg-slate-50 p-3 font-sans leading-6 text-slate-600">{file.document.parseResult.fullText}</pre></details>}
    </div>
  </div>;
}
