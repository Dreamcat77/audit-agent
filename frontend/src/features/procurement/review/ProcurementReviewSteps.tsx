import { useEffect, useRef, useState } from "react";
import { procurementReferenceView } from "./procurementReferenceView";
import { ResultCopyButton } from '../ResultCopyButton';
import type { ReactNode } from "react";
import { AlertTriangle, ArrowRight, CheckCircle2, LoaderCircle, Network, Upload } from "lucide-react";
import { Legend, PolarAngleAxis, PolarGrid, Radar, RadarChart, ResponsiveContainer, Tooltip } from "recharts";
import {
  getProcurementApproval,
  scoreProcurementCollusion,
  startProcurementApproval,
  uploadProcurementCollusionDocument,
  uploadProcurementQuoteCheck,
} from "../../../api/audit";
import type {
  BlacklistBatchCheckResponse,
  ProcurementApproval,
  ProcurementCollusionDocument,
  ProcurementCollusionReview,
  ProcurementQuoteCheckResponse,
  ProcurementReviewReport,
} from "../../../api/types";
import { userFacingError } from "../../../api/userFacingError";
import { ApiError } from "../../../api/client";
import { approvalStatusLabel, enumLabel, riskLevelLabel } from "../../../utils/enumLabels";
import { ProcurementQuoteAdvice } from './ProcurementQuoteAdvice';

export function Step1QuotationUpload({ projectId, onCompleted }: { projectId: string; onCompleted: (result: ProcurementQuoteCheckResponse) => void }) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const flight = useRef(false);

  async function upload() {
    if (!file || flight.current) return;
    flight.current = true;
    setLoading(true); setError(null);
    try {
      onCompleted(await uploadProcurementQuoteCheck(file, projectId));
    } catch (cause) {
      setError(userFacingError(cause, "询比价上传失败，请检查 Excel 后重试。"));
    } finally { flight.current = false; setLoading(false); }
  }

  return <StepPanel title="上传三方比价材料" description="支持供应商逐行报价和多品目横向三方比价表，自动校验金额并给出价格建议。">
    <input ref={inputRef} type="file" accept=".xlsx,.xls" className="hidden" onChange={(event) => setFile(event.target.files?.[0] ?? null)} />
    <div className="flex flex-col gap-3 sm:flex-row">
      <button type="button" disabled={loading} onClick={() => inputRef.current?.click()} className="inline-flex min-w-0 flex-1 items-center gap-2 rounded-lg border border-dashed border-slate-300 bg-slate-50 px-4 py-3 text-xs text-slate-700 hover:border-blue-300 disabled:opacity-50"><Upload size={14} /><span className="truncate">{file?.name ?? "选择 Excel 文件"}</span></button>
      <button type="button" onClick={() => void upload()} disabled={!file || loading} className="inline-flex items-center gap-2 rounded border border-blue-200 bg-blue-50 px-3 py-2 text-xs font-medium text-blue-700 hover:bg-blue-100 disabled:cursor-not-allowed disabled:opacity-50">{loading && <LoaderCircle size={13} className="animate-spin" />}{loading ? "正在分析..." : "上传并分析"}</button>
    </div>
    {error && <ErrorMessage message={error} />}
  </StepPanel>;
}

export function Step2OverBudget({ quote }: { quote: ProcurementQuoteCheckResponse | null }) {
  if (!quote) return <AwaitingMessage message="请先完成询比价上传。" />;
  const items = quote.quotes.filter((item) => item.overBudget);
  return <StepPanel title="超预算告警" description="系统根据项目预算自动识别超预算报价。">
    <ProcurementQuoteAdvice result={quote} />
    <div className="grid gap-3 sm:grid-cols-3"><Metric label="项目预算" value={money(quote.budgetAmount)} /><Metric label="报价均值" value={money(quote.averageQuote)} /><Metric label="超预算报价" value={`${items.length} 家`} /></div>
    {items.length === 0 ? <SuccessMessage message="未发现超预算报价。" /> : <div className="rounded border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-800">发现 {items.length} 家供应商报价超过项目预算，已在明细中标红。</div>}
    <QuoteDetailTable quote={quote} mode="budget" />
  </StepPanel>;
}

export function Step3PricePattern({ quote }: { quote: ProcurementQuoteCheckResponse | null }) {
  if (!quote) return <AwaitingMessage message="请先完成询比价上传。" />;
  const insufficient = quote.quotes.length < 3;
  return <StepPanel title="报价规律性分析" description="以报价离散系数 CV 判断供应商报价是否异常趋同。">
    <div className="grid gap-3 sm:grid-cols-3"><Metric label="供应商数量" value={`${quote.quotes.length} 家`} /><Metric label="报价 CV" value={quote.coefficientOfVariation.toFixed(4)} /><Metric label="分析结论" value={insufficient ? "家数不足" : quote.highlyConcentrated ? "高度趋同" : "无明显趋同"} /></div>
    {insufficient ? <div className="rounded border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800">报价家数不足，无法判断趋同性。</div> : quote.highlyConcentrated ? <div className="rounded border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-800">报价 CV 小于 0.05，存在高度趋同风险。</div> : <SuccessMessage message="报价离散度处于可接受范围。" />}
    <div className="rounded border border-blue-100 bg-blue-50 px-3 py-2 text-xs text-slate-700">CV = 报价标准差 / 报价均值 = {money(quote.standardDeviation)} / {money(quote.averageQuote)} = <strong>{quote.coefficientOfVariation.toFixed(4)}</strong></div>
    <QuoteDetailTable quote={quote} mode="pattern" />
  </StepPanel>;
}

export function Step4Blacklist({ result, loading, error, onRun }: { result: BlacklistBatchCheckResponse | null; loading: boolean; error: string | null; onRun: () => void }) {
  return <StepPanel title="供应商黑名单比对" description="使用询比价数据中的供应商名单，与已维护的黑名单库进行自动比对。">
    {!result && <button type="button" onClick={onRun} disabled={loading} className="inline-flex items-center gap-2 rounded border border-amber-200 bg-amber-50 px-3 py-2 text-xs font-medium text-amber-800 hover:bg-amber-100 disabled:opacity-50">{loading && <LoaderCircle size={13} className="animate-spin" />}{loading ? "比对中..." : "执行黑名单比对"}</button>}
    {error && <ErrorMessage message={error} />}
    {result && <><div className="grid gap-3 sm:grid-cols-3"><Metric label="比对供应商" value={`${result.totalCount} 家`} /><Metric label="命中黑名单" value={`${result.hitCount} 家`} /><Metric label="未命中" value={`${result.missCount} 家`} /></div>
      <BlacklistResultTable result={result} />
    </>}
  </StepPanel>;
}

function QuoteDetailTable({ quote, mode }: { quote: ProcurementQuoteCheckResponse; mode: "budget" | "pattern" }) {
  const isBudget = mode === "budget";
  return <div className="overflow-hidden rounded border border-slate-200">
    <div className="border-b border-slate-200 bg-slate-50 px-3 py-2 text-xs font-medium text-slate-700">供应商报价明细</div>
    <div className="overflow-x-auto"><table className="w-full text-xs"><thead><tr className="border-b border-slate-200 bg-white text-left text-slate-500"><th className="px-3 py-2 font-medium">序号</th><th className="px-3 py-2 font-medium">供应商名称</th><th className="px-3 py-2 text-right font-medium">报价金额</th>{isBudget ? <><th className="px-3 py-2 text-center font-medium">预算比对</th><th className="px-3 py-2 text-right font-medium">超出金额</th></> : <th className="px-3 py-2 text-right font-medium">偏离均值</th>}</tr></thead><tbody className="divide-y divide-slate-100">{quote.quotes.map((item, index) => {
      const overBudget = item.overBudget;
      return <tr key={item.id} className={overBudget && isBudget ? "bg-red-50" : "bg-white"}><td className="px-3 py-2 text-slate-500">{index + 1}</td><td className="px-3 py-2 text-slate-800">{item.supplierName}</td><td className="px-3 py-2 text-right font-mono text-slate-900">{money(item.quoteAmount)}</td>{isBudget ? <><td className="px-3 py-2 text-center"><span className={`rounded border px-1.5 py-0.5 text-[10px] ${overBudget ? "border-red-200 bg-red-100 text-red-700" : "border-emerald-200 bg-emerald-50 text-emerald-700"}`}>{overBudget ? "超预算" : "未超预算"}</span></td><td className={`px-3 py-2 text-right font-mono ${overBudget ? "text-red-700" : "text-emerald-700"}`}>{overBudget ? money(item.overBudgetAmount) : "-"}</td></> : <td className="px-3 py-2 text-right font-mono text-slate-700">{(item.deviationRatio * 100).toFixed(2)}%</td>}</tr>;
    })}</tbody></table></div>
  </div>;
}

function BlacklistResultTable({ result }: { result: BlacklistBatchCheckResponse }) {
  return <div className="grid gap-4 lg:grid-cols-2">
    <div className="overflow-hidden rounded border border-red-200"><div className="border-b border-red-200 bg-red-50 px-3 py-2 text-xs font-medium text-red-800">命中黑名单（{result.hits.length}）</div>{result.hits.length === 0 ? <div className="px-3 py-4 text-center text-xs text-slate-500">未发现命中记录。</div> : <div className="overflow-x-auto"><table className="w-full text-xs"><thead><tr className="border-b border-red-100 text-left text-red-700"><th className="px-3 py-2 font-medium">供应商</th><th className="px-3 py-2 font-medium">命中类型</th><th className="px-3 py-2 font-medium">列入原因</th></tr></thead><tbody className="divide-y divide-red-100">{result.hits.map((hit) => <tr key={hit.alertId} className="bg-red-50/40 text-red-900"><td className="px-3 py-2">{hit.supplierName}</td><td className="px-3 py-2">{hit.blacklistType}</td><td className="px-3 py-2">{hit.blacklistReason}</td></tr>)}</tbody></table></div>}</div>
    <div className="overflow-hidden rounded border border-emerald-200"><div className="border-b border-emerald-200 bg-emerald-50 px-3 py-2 text-xs font-medium text-emerald-800">未命中黑名单（{result.misses.length}）</div>{result.misses.length === 0 ? <div className="px-3 py-4 text-center text-xs text-slate-500">没有未命中供应商。</div> : <div className="overflow-x-auto"><table className="w-full text-xs"><thead><tr className="border-b border-emerald-100 text-left text-emerald-700"><th className="px-3 py-2 font-medium">序号</th><th className="px-3 py-2 font-medium">供应商</th><th className="px-3 py-2 font-medium">统一社会信用代码</th></tr></thead><tbody className="divide-y divide-emerald-100">{result.misses.map((miss, index) => <tr key={`${miss.rowNumber}-${miss.supplierName}`} className="bg-emerald-50/40 text-emerald-900"><td className="px-3 py-2">{index + 1}</td><td className="px-3 py-2">{miss.supplierName}</td><td className="px-3 py-2 font-mono">{miss.supplierCode || "-"}</td></tr>)}</tbody></table></div>}</div>
  </div>;
}

export function Step6CollusionScore({ projectId, quote, assessment, emptyNotice, onCompleted }: { projectId: string; quote: ProcurementQuoteCheckResponse | null; assessment: ProcurementCollusionReview | null; emptyNotice: string | null; onCompleted: (assessment: ProcurementCollusionReview) => void }) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [bidderName, setBidderName] = useState("");
  const [ipAddress, setIpAddress] = useState("");
  const [handlerName, setHandlerName] = useState("");
  const [uploadedDocuments, setUploadedDocuments] = useState<ProcurementCollusionDocument[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  async function upload() {
    if (!file || !bidderName.trim()) { setError("请填写投标人名称并选择投标文件。"); return; }
    const extension = file.name.toLowerCase().split(".").pop();
    if (extension !== "docx" && extension !== "pdf") { setError("仅支持 .docx 和 .pdf 格式的投标文件。"); return; }
    setLoading(true); setError(null);
    try {
      const document = await uploadProcurementCollusionDocument(projectId, { file, bidderName, ipAddress, handlerName });
      setUploadedDocuments((current) => [...current, document]);
      setFile(null); setBidderName(""); setIpAddress(""); setHandlerName("");
    }
    catch (cause) { setError(userFacingError(cause, "投标文件上传失败，请稍后重试。")); }
    finally { setLoading(false); if (inputRef.current) inputRef.current.value = ""; }
  }
  async function score() {
    setLoading(true); setError(null);
    try { onCompleted(await scoreProcurementCollusion(projectId)); }
    catch (cause) { setError(userFacingError(cause, "围串标评分失败，至少需要两份投标文件。")); }
    finally { setLoading(false); }
  }
  const documents = assessment?.documents ?? uploadedDocuments;
  const canScore = documents.length >= 2;
  return <StepPanel title="围串标风险评分" description="上传至少两份投标文件，系统结合文件相似度、报价 CV、IP 与经办人信息生成项目级风险评分。">
    <input ref={inputRef} type="file" accept=".docx,.pdf" className="hidden" onChange={(event) => setFile(event.target.files?.[0] ?? null)} />
    <div className="grid gap-3 sm:grid-cols-2"><input value={bidderName} onChange={(event) => setBidderName(event.target.value)} placeholder="投标人名称" className="rounded border border-slate-300 px-3 py-2 text-xs outline-none focus:border-blue-400" /><input value={ipAddress} onChange={(event) => setIpAddress(event.target.value)} placeholder="IP 地址（可选）" className="rounded border border-slate-300 px-3 py-2 text-xs outline-none focus:border-blue-400" /><input value={handlerName} onChange={(event) => setHandlerName(event.target.value)} placeholder="经办人（可选）" className="rounded border border-slate-300 px-3 py-2 text-xs outline-none focus:border-blue-400" /><button type="button" onClick={() => inputRef.current?.click()} className="rounded border border-slate-300 bg-white px-3 py-2 text-left text-xs text-slate-700">{file?.name ?? "选择 Word 或 PDF 投标文件"}</button></div>
    <div className="flex flex-wrap gap-3"><button type="button" onClick={() => void upload()} disabled={!file || loading} className="rounded border border-slate-300 bg-white px-3 py-2 text-xs text-slate-700 disabled:opacity-50">上传投标文件</button><button type="button" onClick={() => void score()} disabled={loading || !canScore} className="rounded border border-blue-200 bg-blue-50 px-3 py-2 text-xs font-medium text-blue-700 disabled:opacity-50">{loading ? "处理中..." : "计算风险评分"}</button></div>
    {error && <ErrorMessage message={error} />}
    {!assessment && emptyNotice && <div className="rounded border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-600">{emptyNotice}。上传至少两份投标文件后可计算围串标风险。</div>}
    {documents.length > 0 && <CollusionDocumentTable documents={documents} />}
    {assessment && <><div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4"><Metric label="综合评分" value={`${assessment.riskScore} / 100`} /><Metric label="文件相似度" value={`${(assessment.maxDocumentSimilarity * 100).toFixed(1)}%`} /><Metric label="报价 CV" value={assessment.quoteCoefficientOfVariation == null ? "未取得询比价数据" : assessment.quoteCoefficientOfVariation.toFixed(4)} /><Metric label="风险等级" value={riskLevelLabel(assessment.riskLevel)} /></div>
      <div className="grid gap-4 xl:grid-cols-2"><CollusionRiskRadar assessment={assessment} /><SupplierAssociationView assessment={assessment} documents={documents} /></div>
      <CollusionScoreExplanation assessment={assessment} quote={quote} documents={documents} />
      <SplitPurchasePanel assessment={assessment.splitPurchase} />
    </>}
  </StepPanel>;
}

function CollusionRiskRadar({ assessment }: { assessment: ProcurementCollusionReview }) {
  const similarityScore = assessment.maxDocumentSimilarity >= 0.85 ? 40 : assessment.maxDocumentSimilarity >= 0.7 ? 25 : assessment.maxDocumentSimilarity >= 0.5 ? 10 : 0;
  const cvScore = assessment.quoteCoefficientOfVariation == null ? 0 : assessment.quoteCoefficientOfVariation < 0.05 ? 25 : assessment.quoteCoefficientOfVariation < 0.1 ? 10 : 0;
  const data = [
    { dimension: "文件相似度", risk: Math.round(similarityScore / 40 * 100), threshold: 63 },
    { dimension: "报价趋同", risk: Math.round(cvScore / 25 * 100), threshold: 40 },
    { dimension: "IP 关联", risk: assessment.duplicateIpGroups > 0 ? 100 : 0, threshold: 100 },
    { dimension: "经办人关联", risk: assessment.duplicateHandlerGroups > 0 ? 100 : 0, threshold: 100 },
  ];
  return <section className="min-w-0 border border-slate-200 p-3 text-xs text-slate-700">
    <div className="flex items-start justify-between gap-3"><div><h4 className="font-semibold text-slate-900">风险评分雷达图</h4><p className="mt-1 leading-relaxed text-slate-500">各维度按其评分档位折算为风险强度，越接近外圈表示该维度贡献越高。</p></div><span className={`shrink-0 font-semibold ${assessment.riskLevel === "CRITICAL" ? "text-red-700" : assessment.riskLevel === "WARNING" ? "text-amber-700" : "text-emerald-700"}`}>{riskLevelLabel(assessment.riskLevel)} {assessment.riskScore} 分</span></div>
    <div className="mt-2 h-[248px] w-full"><ResponsiveContainer width="100%" height="100%"><RadarChart data={data} outerRadius="67%"><PolarGrid stroke="#cbd5e1" /><PolarAngleAxis dataKey="dimension" tick={{ fill: "#475569", fontSize: 12 }} /><Radar name="风险强度" dataKey="risk" stroke="#dc2626" fill="#ef4444" fillOpacity={0.22} /><Radar name="预警档位" dataKey="threshold" stroke="#f59e0b" fill="transparent" strokeDasharray="4 4" /><Tooltip formatter={(value: number) => `${value}%`} /><Legend wrapperStyle={{ fontSize: 12 }} /></RadarChart></ResponsiveContainer></div>
  </section>;
}

export function ProcurementCollusionResults({ assessment, quote }: { assessment: ProcurementCollusionReview; quote: ProcurementQuoteCheckResponse | null }) {
  const documents = assessment.documents;
  const missing = [assessment.quoteCoefficientOfVariation == null ? "报价趋同" : null,
    documents.some(d => !d.ipAddress) ? "部分投标 IP" : null, documents.some(d => !d.handlerName) ? "部分经办人" : null].filter(Boolean);
  return <StepPanel title="围串标辅助分析" description="根据已识别投标材料自动比对，风险线索需结合原始文件人工核实。">
    <div className="grid gap-3 sm:grid-cols-3"><Metric label="风险评分" value={`${assessment.riskScore} / 100`} /><Metric label="最高文件相似度" value={`${(assessment.maxDocumentSimilarity * 100).toFixed(1)}%`} /><Metric label="风险等级" value={riskLevelLabel(assessment.riskLevel)} /></div>
    {missing.length > 0 && <p className="rounded bg-slate-50 p-3 text-xs text-slate-600">未取得{missing.join("、")}信息，相关维度未完整评估；当前评分不代表排除围串标。</p>}
    <details><summary className="cursor-pointer text-xs text-slate-600">查看文件比对与评分依据</summary><div className="mt-3 space-y-4"><CollusionDocumentTable documents={documents} /><SimilarityTable similarities={assessment.similarities} /><SupplierAssociationView assessment={assessment} documents={documents} /><CollusionScoreExplanation assessment={assessment} quote={quote} documents={documents} /><SplitPurchasePanel assessment={assessment.splitPurchase} /></div></details>
  </StepPanel>;
}

function SupplierAssociationView({ assessment, documents }: { assessment: ProcurementCollusionReview; documents: ProcurementCollusionDocument[] }) {
  const fileName = (value: string | null, bidder: string) => value || `投标文件（${bidder}）`;
  const repeated = (field: "ipAddress" | "handlerName") => {
    const groups = new Map<string, ProcurementCollusionDocument[]>();
    documents.forEach((document) => { const value = document[field]; if (value) groups.set(value, [...(groups.get(value) ?? []), document]); });
    return [...groups.entries()].filter(([, members]) => members.length > 1);
  };
  const similarityRelations = assessment.similarities.filter((item) => item.similarity >= 0.5).map((item) => ({
    type: "文件相似", value: `${(item.similarity * 100).toFixed(1)}%`, severity: item.similarity >= 0.8 ? "high" : "medium",
    members: [fileName(item.fileNameA, item.bidderA), fileName(item.fileNameB, item.bidderB)],
  }));
  const ipRelations = repeated("ipAddress").map(([value, members]) => ({ type: "重复 IP", value, severity: "high", members: members.map((item) => item.fileName) }));
  const handlerRelations = repeated("handlerName").map(([value, members]) => ({ type: "重复经办人", value, severity: "high", members: members.map((item) => item.fileName) }));
  const relations = [...similarityRelations, ...ipRelations, ...handlerRelations];
  return <section className="min-w-0 border border-slate-200 p-3 text-xs text-slate-700">
    <div className="flex items-start gap-2"><Network size={16} className="mt-0.5 shrink-0 text-indigo-600" /><div><h4 className="font-semibold text-slate-900">供应商关联视图</h4><p className="mt-1 leading-relaxed text-slate-500">以文件相似度及材料中明确记载的投标 IP、经办人信息展示需要人工复核的关联线索。</p></div></div>
    {relations.length === 0 ? <p className="mt-5 rounded border border-emerald-200 bg-emerald-50 px-3 py-3 text-emerald-800">未发现文件、IP 或经办人之间的高关联线索。</p> : <div className="mt-4 space-y-3">{relations.map((relation, index) => <div key={`${relation.type}-${relation.value}-${index}`} className={`border-l-4 px-3 py-2 ${relation.severity === "high" ? "border-red-500 bg-red-50/60" : "border-amber-400 bg-amber-50/60"}`}><div className="flex flex-wrap items-center gap-2"><span className={`font-medium ${relation.severity === "high" ? "text-red-800" : "text-amber-800"}`}>{relation.type}</span><span className="rounded border border-slate-200 bg-white px-1.5 py-0.5 font-mono text-slate-600">{relation.value}</span></div><div className="mt-2 flex flex-wrap items-center gap-1.5">{relation.members.map((member, memberIndex) => <span key={`${member}-${memberIndex}`} className="inline-flex max-w-full items-center gap-1"><span className="max-w-[220px] truncate rounded border border-slate-200 bg-white px-2 py-1 text-slate-700" title={member}>{member}</span>{memberIndex < relation.members.length - 1 && <ArrowRight size={13} className="shrink-0 text-slate-400" />}</span>)}</div></div>)}</div>}
  </section>;
}

function SplitPurchasePanel({ assessment }: { assessment: ProcurementCollusionReview["splitPurchase"] }) {
  if (!assessment) return <section className="rounded border border-slate-200 p-3 text-xs text-slate-600">拆分采购识别将在围串标评分完成后生成。</section>;
  return <section className="space-y-3 rounded border border-violet-200 bg-violet-50/40 p-3 text-xs text-slate-700">
    <div className="flex flex-wrap items-center justify-between gap-2"><div><h4 className="font-semibold text-violet-950">拆分采购识别</h4><p className="mt-1 leading-relaxed">按同一财政年度、同一预算科目和供应商，以 询比价记录时间计算 30 天滑动窗口。累计金额达到阈值的 90% 即提示复核，达到或超过阈值时提高风险评分。</p></div><span className={assessment.riskLevel === "CRITICAL" ? "font-semibold text-red-700" : assessment.riskLevel === "WARNING" ? "font-semibold text-amber-700" : "font-semibold text-emerald-700"}>{riskLevelLabel(assessment.riskLevel)} · {assessment.riskScore} 分</span></div>
    {assessment.findings.length === 0 ? <p className="rounded border border-emerald-200 bg-emerald-50 px-3 py-2 text-emerald-800">未发现 30 天内接近或超过审批阈值的拆分采购记录。</p> : <div className="space-y-3">{assessment.findings.map((finding) => <article key={`${finding.supplierName}-${finding.windowStart}`} className="rounded border border-violet-200 bg-white p-3"><div className="flex flex-wrap justify-between gap-2"><strong>{finding.supplierName}</strong><span className="font-medium text-amber-800">{riskLevelLabel(finding.riskLevel)} · {finding.riskScore} 分</span></div><div className="mt-2 grid gap-2 sm:grid-cols-3"><Metric label="30 天累计金额" value={money(finding.totalAmount)} /><Metric label="接近阈值" value={`${finding.thresholdName}（${money(finding.thresholdAmount)}）`} /><Metric label="窗口时间" value={`${formatTime(finding.windowStart)} 至 ${formatTime(finding.windowEnd)}`} /></div><div className="mt-3 overflow-x-auto"><table className="w-full text-xs"><thead><tr className="border-b border-slate-200 text-left text-slate-500"><th className="px-2 py-2 font-medium">采购记录</th><th className="px-2 py-2 font-medium">项目名称</th><th className="px-2 py-2 text-right font-medium">报价金额</th><th className="px-2 py-2 font-medium">记录时间</th></tr></thead><tbody className="divide-y divide-slate-100">{finding.records.map((record) => <tr key={`${record.projectId}-${record.quoteTime}`}><td className="px-2 py-2 font-mono text-slate-600">{record.projectCode}</td><td className="px-2 py-2 text-slate-800">{record.projectName}</td><td className="px-2 py-2 text-right font-mono">{money(record.quoteAmount)}</td><td className="px-2 py-2 text-slate-600">{formatTime(record.quoteTime)}</td></tr>)}</tbody></table></div></article>)}</div>}
  </section>;
}

type RiskConfirmationAction={confirmed:boolean;loading:boolean;onConfirm:()=>void};

export function Step7ReviewReport({ riskConfirmation, projectId, report, emptyNotice, loading, error, onCompleted, onError }: { riskConfirmation?:RiskConfirmationAction; projectId: string; report: ProcurementReviewReport | null; emptyNotice: string | null; loading: boolean; error: string | null; onCompleted: (report: ProcurementReviewReport) => void; onError: (message: string) => void }) {
  return <StepPanel title="采购审查结果" description="统一分析完成后自动生成报告，汇总材料审查、价格比较、黑名单及围串标辅助识别结果。">
    {!report && <p className="text-xs text-slate-500">{loading ? "正在读取报告…" : emptyNotice || "请返回上传材料，完成一次统一分析后即可查看报告。"}</p>}
    {error && <ErrorMessage message={error} />}
    {report && <ReviewReportContent report={report} riskConfirmation={riskConfirmation} />}
  </StepPanel>;
}

export function Step8SubmitApproval({ projectId, submitted, onCompleted }: { projectId: string; submitted: boolean; onCompleted: () => void }) {
  const [loading, setLoading] = useState(false); const [error, setError] = useState<string | null>(null); const [approval, setApproval] = useState<ProcurementApproval | null>(null);
  useEffect(() => {
    let active = true;
    void getProcurementApproval(projectId).then((result) => { if (active) setApproval(result); }).catch((cause) => {
      if (active && !(cause instanceof ApiError && cause.status === 404)) setError(userFacingError(cause, "审批流程加载失败，请稍后重试。"));
    });
    return () => { active = false; };
  }, [projectId]);
  async function submit() { setLoading(true); setError(null); try { const result = await startProcurementApproval(projectId); setApproval(result); onCompleted(); } catch (cause) { setError(userFacingError(cause, "提交审批失败，请确认采购审查报告已生成。")); } finally { setLoading(false); } }
  return <StepPanel title="提交审批" description="采购审查报告完成后，才能进入正式审批流程。">
    {submitted || approval ? <><SuccessMessage message="审批流程已发起。" /><ApprovalFlow approval={approval} /></> : <button type="button" onClick={() => void submit()} disabled={loading} className="inline-flex items-center gap-2 rounded border border-emerald-200 bg-emerald-50 px-3 py-2 text-xs font-medium text-emerald-700 disabled:opacity-50">{loading && <LoaderCircle size={13} className="animate-spin" />}{loading ? "提交中..." : "提交审批"}</button>}
    {error && <ErrorMessage message={error} />}
  </StepPanel>;
}

function CollusionDocumentTable({ documents }: { documents: ProcurementCollusionDocument[] }) { return <div className="overflow-hidden rounded border border-slate-200"><div className="border-b border-slate-200 bg-slate-50 px-3 py-2 text-xs font-medium text-slate-700">已上传投标文件（{documents.length}）</div><div className="overflow-x-auto"><table className="w-full text-xs"><thead><tr className="border-b border-slate-200 text-left text-slate-500"><th className="px-3 py-2 font-medium">投标人</th><th className="px-3 py-2 font-medium">文件</th><th className="px-3 py-2 font-medium">IP 地址</th><th className="px-3 py-2 font-medium">经办人</th></tr></thead><tbody className="divide-y divide-slate-100">{documents.map((document) => <tr key={document.id}><td className="px-3 py-2 text-slate-800">{document.bidderName}</td><td className="px-3 py-2 text-slate-700">{document.fileName}</td><td className="px-3 py-2 font-mono text-slate-600">{document.ipAddress || "-"}</td><td className="px-3 py-2 text-slate-600">{document.handlerName || "-"}</td></tr>)}</tbody></table></div></div>; }

function SimilarityTable({ similarities }: { similarities: ProcurementCollusionReview["similarities"] }) {
  const fileName = (value: string | null, bidderName: string) => value || `历史评分未保存文件标识，请重新评分（投标人：${bidderName}）`;
  return <div className="overflow-hidden rounded border border-slate-200"><div className="border-b border-slate-200 bg-slate-50 px-3 py-2 text-xs font-medium text-slate-700">文件相似度明细</div>{similarities.length === 0 ? <div className="px-3 py-4 text-center text-xs text-slate-500">未发现需要展示的文件相似度记录。</div> : <div className="overflow-x-auto"><table className="w-full text-xs"><thead><tr className="border-b border-slate-200 text-left text-slate-500"><th className="px-3 py-2 font-medium">文件 A</th><th className="px-3 py-2 font-medium">文件 B</th><th className="px-3 py-2 text-right font-medium">相似度</th><th className="px-3 py-2 font-medium">判定</th></tr></thead><tbody className="divide-y divide-slate-100">{similarities.map((item, index) => <tr key={`${item.documentAId ?? index}-${item.documentBId ?? index}`}><td className="px-3 py-2 text-slate-800">{fileName(item.fileNameA, item.bidderA)}</td><td className="px-3 py-2 text-slate-800">{fileName(item.fileNameB, item.bidderB)}</td><td className="px-3 py-2 text-right font-mono">{(item.similarity * 100).toFixed(1)}%</td><td className={item.similarity >= 0.8 ? "px-3 py-2 text-red-700" : "px-3 py-2 text-slate-600"}>{similarityLabel(item.similarity)}</td></tr>)}</tbody></table></div>}</div>;
}

function CollusionScoreExplanation({ assessment, quote, documents }: { assessment: ProcurementCollusionReview; quote: ProcurementQuoteCheckResponse | null; documents: ProcurementCollusionDocument[] }) {
  const similarityScore = assessment.maxDocumentSimilarity >= 0.85 ? 40 : assessment.maxDocumentSimilarity >= 0.7 ? 25 : assessment.maxDocumentSimilarity >= 0.5 ? 10 : 0;
  const cvScore = assessment.quoteCoefficientOfVariation == null ? 0 : assessment.quoteCoefficientOfVariation < 0.05 ? 25 : assessment.quoteCoefficientOfVariation < 0.1 ? 10 : 0;
  const ipScore = assessment.duplicateIpGroups > 0 ? 20 : 0;
  const handlerScore = assessment.duplicateHandlerGroups > 0 ? 15 : 0;
  const reason = [
    similarityScore > 0 && `文件相似度 ${(assessment.maxDocumentSimilarity * 100).toFixed(1)}%`,
    cvScore > 0 && `报价 CV ${assessment.quoteCoefficientOfVariation?.toFixed(4)}`,
    ipScore > 0 && `发现 ${assessment.duplicateIpGroups} 组重复 IP`,
    handlerScore > 0 && `发现 ${assessment.duplicateHandlerGroups} 组重复经办人`,
  ].filter(Boolean).join("；");
  const recommendation = assessment.riskLevel === "CRITICAL" ? "建议立即人工复核，必要时暂停采购流程。" : assessment.riskLevel === "WARNING" ? "建议核验投标文件来源、报价形成过程及投标操作记录。" : "当前未发现达到预警阈值的围串标风险，仍建议保留审查记录。";
  return <div className="space-y-4"><section className="space-y-3 rounded border border-indigo-200 bg-indigo-50/50 p-3 text-xs text-slate-700"><div><h4 className="font-semibold text-indigo-950">综合评分说明</h4><p className="mt-1 leading-relaxed">综合评分基于文件相似度、报价 CV、IP 重复和经办人重复四个维度计算，满分 100 分，分数越高表示围串标风险越大。</p></div><div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4"><ScoreContribution label="文件相似度" value={`${(assessment.maxDocumentSimilarity * 100).toFixed(1)}%`} detail={similarityLabel(assessment.maxDocumentSimilarity)} score={similarityScore} /><ScoreContribution label="报价 CV" value={assessment.quoteCoefficientOfVariation == null ? "无数据" : assessment.quoteCoefficientOfVariation.toFixed(4)} detail={cvScore === 25 ? "高度趋同" : cvScore === 10 ? "存在趋同" : "无明显趋同"} score={cvScore} /><ScoreContribution label="重复 IP" value={`${assessment.duplicateIpGroups} 组`} detail={ipScore > 0 ? "存在重复" : "未发现重复"} score={ipScore} /><ScoreContribution label="重复经办人" value={`${assessment.duplicateHandlerGroups} 组`} detail={handlerScore > 0 ? "存在重复" : "未发现重复"} score={handlerScore} /></div></section>
    <section className="space-y-2 rounded border border-red-200 bg-red-50/50 p-3 text-xs text-slate-700"><h4 className="font-semibold text-red-900">风险等级说明</h4><p>风险等级按综合评分划分：正常为 0-39 分，警告为 40-69 分，严重为 70-100 分。</p><p><strong>{riskLevelLabel(assessment.riskLevel)}</strong>{reason ? `：${reason}，综合判定为${riskLevelLabel(assessment.riskLevel)}。` : "：当前各项指标未达到预警阈值。"}</p><p className="font-medium text-red-800">建议：{recommendation}</p></section>
    <section className="space-y-3 rounded border border-slate-200 p-3 text-xs text-slate-700"><div><h4 className="font-semibold text-slate-900">文件相似度说明</h4><p className="mt-1 leading-relaxed">文件相似度反映两份投标文件正文的文本接近程度。系统通过 TF-IDF 与余弦相似度计算；超过 80% 视为高度相似，可能存在围标风险。</p></div><SimilarityTable similarities={assessment.similarities} /></section>
    <QuoteCvExplanation quote={quote} assessment={assessment} />
    <DuplicateEvidence documents={documents} field="ipAddress" title="重复 IP 说明" description="不同投标人使用相同 IP 地址上传投标文件，可能存在同一人操作多家投标的情况。" empty="未发现重复 IP 地址。" />
    <DuplicateEvidence documents={documents} field="handlerName" title="重复经办人说明" description="不同投标人的经办人为同一人，可能存在同一人操作多家投标的情况。" empty="未发现重复经办人。" />
  </div>;
}

function ScoreContribution({ label, value, detail, score }: { label: string; value: string; detail: string; score: number }) { return <div className="rounded border border-indigo-100 bg-white px-3 py-2"><p className="text-slate-500">{label}</p><p className="mt-1 font-medium text-slate-900">{value}</p><p className={score > 0 ? "mt-1 text-red-700" : "mt-1 text-slate-500"}>{detail}（+{score} 分）</p></div>; }

function QuoteCvExplanation({ quote, assessment }: { quote: ProcurementQuoteCheckResponse | null; assessment: ProcurementCollusionReview }) {
  return <section className="space-y-3 rounded border border-slate-200 p-3 text-xs text-slate-700"><div><h4 className="font-semibold text-slate-900">报价 CV 说明</h4><p className="mt-1 leading-relaxed">报价 CV（离散系数）等于标准差除以均值，用于衡量报价离散程度。CV 小于 0.05 表示报价高度趋同，可能存在串标风险。</p></div>{quote ? <><div className="grid gap-2 sm:grid-cols-3"><Metric label="报价均值" value={money(quote.averageQuote)} /><Metric label="标准差" value={money(quote.standardDeviation)} /><Metric label="CV 值" value={quote.coefficientOfVariation.toFixed(4)} /></div><div className="rounded border border-blue-100 bg-blue-50 px-3 py-2">CV = 标准差 / 报价均值 = {money(quote.standardDeviation)} / {money(quote.averageQuote)} = <strong>{quote.coefficientOfVariation.toFixed(4)}</strong></div><QuoteDetailTable quote={quote} mode="pattern" /></> : <p className="text-slate-500">未取得询比价明细，当前评分使用的报价 CV 为 {assessment.quoteCoefficientOfVariation?.toFixed(4) ?? "无数据"}。</p>}</section>;
}

function DuplicateEvidence({ documents, field, title, description, empty }: { documents: ProcurementCollusionDocument[]; field: "ipAddress" | "handlerName"; title: string; description: string; empty: string }) {
  const groups = new Map<string, ProcurementCollusionDocument[]>();
  documents.forEach((document) => { const value = document[field]; if (value) groups.set(value, [...(groups.get(value) ?? []), document]); });
  const duplicates = [...groups.entries()].filter(([, members]) => members.length > 1);
  return <section className="space-y-3 rounded border border-slate-200 p-3 text-xs text-slate-700"><div><h4 className="font-semibold text-slate-900">{title}</h4><p className="mt-1 leading-relaxed">{description}</p></div>{duplicates.length === 0 ? <p className="text-slate-500">{empty}</p> : <div className="space-y-2">{duplicates.map(([value, members]) => <div key={value} className="rounded border border-amber-200 bg-amber-50 px-3 py-2"><strong>{value}</strong><p className="mt-1">{members.map((member) => `${member.bidderName}（${member.fileName}）`).join("、")}</p></div>)}</div>}</section>;
}

function similarityLabel(value: number) { return value >= 0.8 ? "高度相似" : value >= 0.5 ? "存在相似" : "差异明显"; }

function ReviewReportContent({ report,riskConfirmation }: { report: ProcurementReviewReport;riskConfirmation?:RiskConfirmationAction }) { const view=procurementReferenceView(report);return <div className="space-y-4"><div aria-label="风险提示" className="rounded border border-indigo-200 bg-indigo-50 px-4 py-3 text-xs text-indigo-950"><div data-copy-heading className="flex flex-wrap justify-between gap-3"><strong>{report.reportNumber}</strong><span>综合风险：{riskLevelLabel(view.riskLevel)}</span></div><div className="mt-2 space-y-2 leading-relaxed">{view.lines.length?view.lines.map((line,index)=><p key={index}>{line}</p>):<p>已完成的检查未发现需要关注的问题。未提供材料或数据不足的部分，请结合原材料核实。</p>}</div></div><div className="grid gap-4 lg:grid-cols-2"><ReportSection title="智能体预审" summary={`风险等级：${riskLevelLabel(view.agentReview.riskLevel)}`}><p>{view.agentReview.suggestion}</p>{view.agentReview.aiSummary && <p className="mt-1 text-slate-600">AI 摘要：{view.agentReview.aiSummary}</p>}<ReportItemList items={view.agentReview.items.map((item) => `${item.checkName}：${item.detail}`)} empty="未发现预审明细。" /></ReportSection><ReportSection title="询比价分析" summary={report.quoteCheck ? `风险等级：${riskLevelLabel(report.quoteCheck.riskLevel)}` : "未取得询比价结果"}>{report.quoteCheck ? <><div className="grid grid-cols-2 gap-2"><Metric label="预算金额" value={money(report.quoteCheck.budgetAmount)} /><Metric label="报价 CV" value={report.quoteCheck.coefficientOfVariation.toFixed(4)} /></div><QuoteDetailTable quote={report.quoteCheck} mode="budget" /></> : <p>尚未生成询比价分析结果。</p>}</ReportSection><ReportSection title="黑名单比对" summary={`命中 ${report.blacklist.alertCount} 家`}><ReportItemList items={report.blacklist.hits.map((hit) => `${hit.supplierName} · ${hit.blacklistType} · ${hit.blacklistReason}`)} empty="未命中供应商黑名单。" /></ReportSection><ReportSection title="招标文件合规审查" summary={`风险 ${view.tenderFindings.length} 条`}><TenderReportFindings findings={view.tenderFindings} confirmation={riskConfirmation}/></ReportSection><ReportSection title="围串标评分" summary={report.collusion ? `风险等级：${riskLevelLabel(report.collusion.riskLevel)}` : "尚未生成围串标评分"}>{report.collusion ? <div className="grid grid-cols-2 gap-2"><Metric label="综合评分" value={`${report.collusion.riskScore} / 100`} /><Metric label="最高文件相似度" value={`${(report.collusion.maxDocumentSimilarity * 100).toFixed(1)}%`} /><Metric label="重复 IP 分组" value={`${report.collusion.duplicateIpGroups} 组`} /><Metric label="重复经办人分组" value={`${report.collusion.duplicateHandlerGroups} 组`} /></div> : <p>尚未生成围串标评分结果。</p>}</ReportSection><ReportSection title="拆分采购识别" summary={report.splitPurchase ? `风险等级：${riskLevelLabel(report.splitPurchase.riskLevel)}` : "尚未生成拆分采购识别结果"}>{report.splitPurchase ? <><div className="grid grid-cols-2 gap-2"><Metric label="风险评分" value={`${report.splitPurchase.riskScore} / 100`} /><Metric label="风险记录" value={`${report.splitPurchase.findingCount} 条`} /></div><ReportItemList items={report.splitPurchase.findings.map((item) => `${item.supplierName}：30 天累计 ${money(item.totalAmount)}，${item.thresholdName} ${money(item.thresholdAmount)}，${riskLevelLabel(item.riskLevel)} ${item.riskScore} 分`)} empty="未发现拆分采购风险。" /></> : <p>尚未生成拆分采购识别结果。</p>}</ReportSection></div></div>; }

function ReportSection({ title, summary, children }: { title: string; summary: string; children: ReactNode }) { return <section data-copy-result className="space-y-3 rounded border border-slate-200 p-3 text-xs text-slate-700"><div data-copy-heading className="flex flex-wrap items-center justify-between gap-2"><h4 className="font-semibold text-slate-900">{title}</h4><span className="text-slate-500">{summary}</span><ResultCopyButton/></div><div data-copy-content className="space-y-3">{children}</div></section>; }

function TenderReportFindings({findings,confirmation}:{findings:ProcurementReviewReport['tender']['findings'];confirmation?:RiskConfirmationAction}) {
  if(!findings.length)return <p className="text-slate-500">未发现招标文件合规风险。</p>;
  return <><p className="text-xs leading-6 text-slate-500">以下为材料中的待核查线索，不代表认定违规；请结合原文和实际情况判断。</p><ul className="space-y-2 border-t border-slate-100 pt-2">{findings.map((item,index)=><li key={index} className="leading-relaxed">
    <div className="flex flex-wrap items-center gap-2"><span>{riskLevelLabel(item.riskLevel)}</span>{item.riskLevel==='CRITICAL'&&confirmation&&<span data-no-copy><button type="button" disabled={confirmation.confirmed||confirmation.loading} onClick={confirmation.onConfirm} title="确认已知悉本报告严重风险，系统记录操作人和时间" className="rounded border border-red-200 bg-white px-2 py-1 text-red-700 disabled:opacity-60">{confirmation.confirmed?'已确认':confirmation.loading?'确认中…':'确认'}</button></span>}</div>
    <p>{item.riskCategory}：{item.matchedText}</p>
    {item.riskReason&&<p className="mt-1 text-slate-600">核对意见：{item.riskReason}</p>}
    {item.remediationSuggestion&&<p className="mt-1 text-slate-600">建议：{item.remediationSuggestion}</p>}
  </li>)}</ul></>;
}

function ReportItemList({ items, empty }: { items: string[]; empty: string }) { return items.length === 0 ? <p className="text-slate-500">{empty}</p> : <ul className="space-y-1.5 border-t border-slate-100 pt-2 text-slate-700">{items.map((item, index) => <li key={`${index}-${item}`} className="leading-relaxed">{item}</li>)}</ul>; }

function ApprovalFlow({ approval }: { approval: ProcurementApproval | null }) {
  if (!approval) return <div className="rounded border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-600">正在加载审批流程...</div>;

  return <div className="overflow-hidden rounded border border-slate-200">
    <div className="border-b border-slate-200 bg-slate-50 px-3 py-2 text-xs font-medium text-slate-700">审批流程 · {approvalStatusLabel(approval.status)}</div>
    <div className="grid grid-cols-[minmax(0,2fr)_minmax(72px,1fr)_minmax(0,2fr)] gap-3 border-b border-slate-200 bg-slate-50 px-3 py-2 text-[11px] text-slate-500">
      <span>节点</span><span className="text-center">状态</span><span className="text-right">审批人</span>
    </div>
    <div className="divide-y divide-slate-100">
      {approval.nodes.map((node) => <div key={node.id} className="grid grid-cols-[minmax(0,2fr)_minmax(72px,1fr)_minmax(0,2fr)] items-center gap-3 px-3 py-2 text-xs">
        <div className="min-w-0"><strong className="block truncate text-slate-800">{node.nodeOrder}. {node.nodeName}</strong><span className="block truncate text-slate-500">{enumLabel(node.requiredRoleCode)}</span></div>
        <span className={`text-center ${node.status === "COMPLETED" ? "text-emerald-700" : node.status === "IN_PROGRESS" ? "text-blue-700" : "text-slate-500"}`}>{approvalStatusLabel(node.status)}</span>
        <span className="truncate text-right text-slate-600">{node.assigneeName || "待指派"}</span>
      </div>)}
    </div>
  </div>;
}

export function StepPanel({ title, description, children }: { title: string; description: string; children: ReactNode }) { return <section data-copy-result className="space-y-4 rounded-lg border border-slate-200 bg-white p-5"><div data-copy-heading className="flex items-start justify-between gap-3"><div><h3 className="text-sm font-semibold text-slate-900">{title}</h3><p className="mt-1 text-xs text-slate-500">{description}</p></div><ResultCopyButton/></div><div data-copy-content className="space-y-3">{children}</div></section>; }
export function AwaitingMessage({ message }: { message: string }) { return <div className="rounded border border-slate-200 bg-slate-50 px-4 py-5 text-center text-xs text-slate-500">{message}</div>; }
function Metric({ label, value }: { label: string; value: string }) { return <div className="rounded border border-slate-200 bg-slate-50 px-3 py-2"><p className="text-[11px] text-slate-500">{label}</p><p className="mt-1 text-sm font-medium text-slate-900">{value}</p></div>; }
function SuccessMessage({ message }: { message: string }) { return <div className="flex items-center gap-2 rounded border border-emerald-200 bg-emerald-50 px-3 py-2 text-xs text-emerald-800"><CheckCircle2 size={14} />{message}</div>; }
function ErrorMessage({ message }: { message: string }) { return <div className="flex items-center gap-2 rounded border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-800"><AlertTriangle size={14} />{message}</div>; }
function money(value: number) { return `¥${Number(value ?? 0).toLocaleString("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`; }
function formatTime(value: string) { return new Date(value).toLocaleString("zh-CN", { hour12: false }); }
