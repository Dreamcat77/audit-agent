import { Brain, FileSearch, LoaderCircle, RefreshCw } from "lucide-react";
import { useState, type ReactNode } from "react";

import {
  contractTextAnalysisPresentation,
  type ContractTextAnalysisHintPresentation,
  type ContractTextEvidenceReference,
  type ContractTextMaterialReference,
} from "../contractTextAnalysisPresentation";
import type { BreachClauseReview, ContractTermClauseReview, ContractTextAnalysis } from "../types";
import {
  acknowledgeAiSemanticIssue,
  isAiSemanticIssueAcknowledged,
  requiresAiSemanticAcknowledgement,
} from "../aiSemanticAcknowledgement";
import type {
  DurableReviewEvidenceView,
  DurableSemanticReviewPresentation,
} from "../reviewRunSemanticAdapter";

interface AiSemanticReviewPanelProps {
  result: ContractTextAnalysis | null;
  breachResult?: BreachClauseReview | null;
  termResult?: ContractTermClauseReview | null;
  loading: boolean;
  error: string | null;
  notice: string | null;
  materials: readonly ContractTextMaterialReference[];
  canReanalyze: boolean;
  onReanalyze: () => void;
  onSourceOpen: (source: ContractTextEvidenceReference) => void;
  durableReview?: DurableSemanticReviewPresentation | null;
  onDurableEvidenceOpen?: (evidence: DurableReviewEvidenceView) => void;
  renderDurableAcknowledgement?: (findingId: string, findingVersionNo: number) => ReactNode;
}

const attentionTone = {
  INFO: "border-emerald-200 bg-emerald-50 text-emerald-800",
  REVIEW: "border-amber-200 bg-amber-50 text-amber-900",
  INSUFFICIENT: "border-slate-200 bg-slate-50 text-slate-700",
} as const;

const durableSectionTone = {
  neutral: "border-slate-200 bg-slate-50 text-slate-700",
  success: "border-emerald-200 bg-emerald-50 text-emerald-800",
  danger: "border-rose-200 bg-rose-50 text-rose-800",
  uncertain: "border-amber-200 bg-amber-50 text-amber-800",
} as const;

const durableIssueTone = {
  neutral: "border-slate-200 bg-slate-50 text-slate-700",
  review: "border-orange-200 bg-orange-50 text-orange-800",
  uncertain: "border-amber-200 bg-amber-50 text-amber-800",
} as const;

const frequencyLabel = {
  DAY: "每日",
  WEEK: "每周",
  MONTH: "每月",
  EVENT: "每次事件",
  ONCE: "一次性",
} as const;

const basisLabel = {
  CONTRACT_AMOUNT: "合同金额",
  LINE_ITEM_AMOUNT: "明细金额",
  OTHER_OR_UNCLEAR: "其他或依据不明确",
} as const;

const dateTypeLabel = {
  SIGNING_DATE: "签订日期",
  EFFECTIVE_DATE: "生效日期",
  PERFORMANCE_START_DATE: "履约开始日期",
  DELIVERY_DEADLINE: "交付期限",
  TERMINATION_DATE: "终止日期",
} as const;

const helperStatusLabel = {
  NORMAL: "已识别",
  REVIEW: "需关注",
  INSUFFICIENT: "材料依据不足",
} as const;

function money(value: number | null): string {
  return value === null ? "材料依据不足" : `¥${value.toLocaleString("zh-CN", { minimumFractionDigits: 2 })}`;
}

function HintCard({
  hint,
  index,
  requestId,
  onSourceOpen,
}: {
  hint: ContractTextAnalysisHintPresentation;
  index: number;
  requestId: string;
  onSourceOpen: (source: ContractTextEvidenceReference) => void;
}) {
  const storage = (() => {
    if (typeof window === "undefined") return null;
    try { return window.sessionStorage; } catch { return null; }
  })();
  const needsAcknowledgement = requiresAiSemanticAcknowledgement(hint.attention);
  const [acknowledged, setAcknowledged] = useState(() => storage
    ? isAiSemanticIssueAcknowledged(storage, requestId, hint.category, index)
    : false);
  const acknowledge = () => {
    try {
      if (storage) acknowledgeAiSemanticIssue(storage, requestId, hint.category, index);
    } finally {
      setAcknowledged(true);
    }
  };
  return (
    <article key={`${hint.category}-${hint.title}-${index}`} className={`rounded-lg border p-3 ${attentionTone[hint.attention]}`}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-[11px] font-medium">{hint.categoryLabel}</span>
          <span className="rounded border border-current/20 px-1.5 py-0.5 text-[11px]">{hint.attentionLabel}</span>
        </div>
        {needsAcknowledgement && (
          <div className="flex items-center gap-2 text-[11px] font-medium">
            <span>{acknowledged ? "本次分析已知悉" : "待知悉"}</span>
            {!acknowledged && <button type="button" onClick={acknowledge}
              className="rounded border border-red-300 bg-white px-2 py-1 text-red-800 hover:bg-red-50">
              我已知悉
            </button>}
          </div>
        )}
      </div>
      <h4 className="mt-2 text-xs font-semibold text-slate-900">{hint.title}</h4>
      <p className="mt-1 text-xs leading-5 text-slate-700">{hint.description}</p>

      {hint.breachEnrichment && (
        <dl className="mt-2 grid grid-cols-[6.5rem_minmax(0,1fr)] gap-y-1 rounded border border-current/10 bg-white/70 p-2 text-[11px] text-slate-700">
          <dt>精确比例</dt><dd>{hint.breachEnrichment.rate}{hint.breachEnrichment.rateUnit === "PERCENT" ? "%" : "‰"}，{frequencyLabel[hint.breachEnrichment.frequency]}</dd>
          <dt>计算基数</dt><dd>{basisLabel[hint.breachEnrichment.calculationBasis]}</dd>
          <dt>对应金额</dt><dd>{money(hint.breachEnrichment.correspondingAmount)}</dd>
          <dt>累计上限</dt><dd>{hint.breachEnrichment.capRate === null || hint.breachEnrichment.capRateUnit === null
            ? "材料依据不足"
            : `${hint.breachEnrichment.capRate}${hint.breachEnrichment.capRateUnit === "PERCENT" ? "%" : "‰"}`}</dd>
          <dt>上限金额</dt><dd>{money(hint.breachEnrichment.capAmount)}</dd>
          <dt>实际累计金额</dt><dd>{money(hint.breachEnrichment.actualPenaltyAmount)}</dd>
        </dl>
      )}

      {hint.termDateEnrichments.length > 0 && (
        <dl className="mt-2 grid grid-cols-[6.5rem_minmax(0,1fr)] gap-y-1 rounded border border-current/10 bg-white/70 p-2 text-[11px] text-slate-700">
          {hint.termDateEnrichments.map((date) => (
            <div key={`${date.type}-${date.normalizedDate}`} className="contents">
              <dt>{dateTypeLabel[date.type]}</dt><dd>{date.normalizedDate}</dd>
            </div>
          ))}
        </dl>
      )}

      {hint.sources.length > 0 && hint.attention === "INFO" && (
        <div className="mt-2 flex flex-wrap items-center gap-2 text-[11px] text-slate-600">
          <span>对比来源：</span>
          {hint.sources.map((source) => (
            <button key={source.id} type="button" onClick={() => onSourceOpen(source)}
              className="inline-flex items-center gap-1 text-[12px] font-medium text-blue-700 hover:underline">
              <FileSearch size={11} /> {source.fileName}
            </button>
          ))}
        </div>
      )}
      {hint.sources.length > 0 && hint.attention !== "INFO" && (
        <div className="mt-3 space-y-2 rounded border border-current/10 bg-white/70 p-2.5 text-[11px] text-slate-700">
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-medium">对比来源：</span>
            {hint.sources.map((source) => (
              <button key={`comparison-${source.id}`} type="button" onClick={() => onSourceOpen(source)}
                className="inline-flex items-center gap-1 text-[12px] font-medium text-blue-700 hover:underline">
                <FileSearch size={11} /> {source.fileName}
              </button>
            ))}
          </div>
          {hint.problemSources.map((source) => (
            <p key={`problem-${source.id}`} className="text-[12px]">
              <span className="font-medium">问题材料：</span>
                <button type="button" onClick={() => onSourceOpen(source)} className="text-red-700 font-semibold hover:underline">
                  {source.fileName}
                </button>
            </p>
          ))}
          {hint.problemMaterialNotice && (
            <p className="rounded border border-slate-200 bg-slate-50 p-2 text-slate-600">
              {hint.problemMaterialNotice}
            </p>
          )}
        </div>
      )}
    </article>
  );
}

export function AiSemanticReviewPanel({
  result,
  breachResult,
  termResult,
  loading,
  error,
  notice,
  materials,
  canReanalyze,
  onReanalyze,
  onSourceOpen,
  durableReview = null,
  onDurableEvidenceOpen,
  renderDurableAcknowledgement,
}: AiSemanticReviewPanelProps) {
  if (durableReview) return (
    <section className="rounded-xl border border-violet-200 bg-white p-4">
      <div>
        <h3 className="flex items-center gap-2 text-[15px] font-semibold text-slate-900">
          <Brain size={16} className="text-violet-700" /> {durableReview.title}
        </h3>
        <p className="mt-1 text-xs leading-5 text-slate-600">{durableReview.authorityNotice}</p>
      </div>
      <div className="mt-3 space-y-3">
        {durableReview.sections.map((section) => (
          <section key={section.key} className="rounded-lg border border-violet-100 bg-violet-50/30 p-3">
            <div className="flex items-center justify-between gap-3">
              <h4 className="text-xs font-semibold text-slate-900">{section.label}</h4>
              <span className={`rounded border px-2 py-1 text-[11px] ${durableSectionTone[section.statusTone ?? "neutral"]}`}>
                {section.statusLabel}
              </span>
            </div>
            <p className="mt-2 text-xs leading-5 text-slate-600">{section.detail}</p>
            {section.issues.length > 0 && <div className="mt-3 space-y-2">
              {section.issues.map((issue) => (
                <article key={issue.id} className="rounded border border-violet-100 bg-white p-3">
                  <div className="flex flex-wrap items-center gap-2 text-[11px]">
                    <span className="font-medium text-violet-900">{issue.categoryLabel}</span>
                    <span className={`rounded border px-1.5 py-0.5 ${durableIssueTone[issue.statusTone ?? "neutral"]}`}>
                      {issue.statusLabel}
                    </span>
                    <span className="text-slate-500">{issue.sourceLabel}</span>
                  </div>
                  <p className="mt-2 text-xs leading-5 text-slate-800">{issue.summary}</p>
                  {issue.recommendation && <p className="mt-1 text-xs leading-5 text-slate-600">
                    建议：{issue.recommendation}
                  </p>}
                  {issue.evidence.length > 0 && <div className="mt-3 space-y-2 border-t border-slate-100 pt-2">
                    {issue.evidence.map((evidence) => (
                      <div key={evidence.id} className="rounded bg-slate-50 p-2 text-[11px] text-slate-600">
                        <button type="button" onClick={() => onDurableEvidenceOpen?.(evidence)}
                          className="inline-flex items-center gap-1 font-medium text-blue-700 hover:underline">
                          <FileSearch size={11} /> {evidence.fileName}
                        </button>
                        <p className="mt-1">{evidence.locatorLabel}</p>
                        {evidence.excerpt && <p className="mt-1 whitespace-pre-wrap break-words text-slate-700">{evidence.excerpt}</p>}
                      </div>
                    ))}
                  </div>}
                  {renderDurableAcknowledgement && <div className="mt-3 border-t border-slate-100 pt-3">
                    {renderDurableAcknowledgement(issue.id, issue.versionNo)}
                  </div>}
                </article>
              ))}
            </div>}
          </section>
        ))}
      </div>
    </section>
  );
  const view = contractTextAnalysisPresentation(result, loading, error, {
    materials,
    notice,
    breachResult,
    termResult,
  });
  return (
    <section className="rounded-xl border border-violet-200 bg-white p-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h3 className="flex items-center gap-2 text-[15px] font-semibold text-slate-900">
            <Brain size={16} className="text-violet-700" /> {view.title}
          </h3>
          <p className="mt-1 text-xs leading-5 text-slate-600">{view.authorityNotice}</p>
        </div>
        <span className="shrink-0 rounded border border-violet-200 bg-violet-50 px-2 py-1 text-xs text-violet-800">
          {loading && <LoaderCircle size={12} className="mr-1 inline animate-spin" />}{view.statusLabel}
        </span>
      </div>

      {view.phase === "RUNNING" ? (
        <div role="status" className="mt-3 rounded-lg border border-violet-200 bg-violet-50 p-4">
          <p className="flex items-center gap-2 text-sm font-semibold text-violet-950">
            <LoaderCircle size={16} className="animate-spin" />正在进行合同语义分析
          </p>
          <p className="mt-2 text-xs text-slate-700">正在核对本次所选材料</p>
          <p className="mt-1 text-xs text-slate-600">分析可能需要一些时间，请稍候</p>
        </div>
      ) : view.detail && <p className="mt-3 text-xs leading-5 text-slate-600">{view.detail}</p>}
      {view.summaries.length > 0 && (
        <div className="mt-3 rounded-lg bg-violet-50/70 p-3">
          <p className="text-xs font-medium text-violet-950">文字语义摘要</p>
          <ul className="mt-2 list-disc space-y-1 pl-4 text-xs leading-5 text-slate-700">
            {view.summaries.map((summary, index) => <li key={index}>{summary}</li>)}
          </ul>
        </div>
      )}

      {view.phase === "RESULT" && <div className="mt-3 space-y-3">
        {view.sections.map((section) => (
          <section key={section.key} className="rounded-lg border border-violet-100 bg-violet-50/30 p-3">
            <h4 className="text-xs font-semibold text-slate-900">{section.label}</h4>
            <div className="mt-2 space-y-2">
              {section.slots.map((slot) => (
                <section key={slot.category} className="rounded border border-violet-100 bg-white/80 p-2.5">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <h5 className="text-xs font-medium text-slate-800">{slot.categoryLabel}</h5>
                    <span className={`rounded border px-1.5 py-0.5 text-[11px] ${slot.coverage === "ASSESSED"
                      ? "border-violet-200 bg-violet-50 text-violet-800"
                      : "border-slate-200 bg-slate-50 text-slate-600"}`}>
                      {slot.coverageLabel}
                    </span>
                  </div>
                  {slot.hints.length > 0 && (
                    <div className="mt-2 space-y-2">
                      {slot.hints.map((hint, index) => (
                        <HintCard key={`${result?.requestId}-${hint.category}-${hint.title}-${index}`}
                          hint={hint} index={index} requestId={result?.requestId ?? ""} onSourceOpen={onSourceOpen} />
                      ))}
                    </div>
                  )}
                  {slot.helperFacts.length > 0 && (
                    <div className="mt-2 space-y-2 rounded border border-slate-200 bg-slate-50 p-2 text-[11px] text-slate-700">
                      <p className="font-medium">已检测条款事实（不代表智能语义结论）</p>
                      {slot.helperFacts.map((fact, index) => (
                        <div key={`${fact.kind}-${fact.label}-${fact.source.id}-${index}`} className="rounded bg-white p-2">
                          <p><span className="font-medium">{fact.label}：</span>{fact.description}</p>
                          <p className="mt-1"><span className="font-medium">事实状态：</span>{helperStatusLabel[fact.status]}</p>
                          <p className="mt-1"><span className="font-medium">
                            {fact.status === "NORMAL" ? "来源材料：" : "问题材料："}
                          </span>
                            <button type="button" onClick={() => onSourceOpen(fact.source)}
                              className={`text-[12px] font-medium hover:underline ${fact.status === "NORMAL" ? "text-blue-700" : "text-red-700"}`}>
                              {fact.source.fileName}
                            </button>
                          </p>
                        </div>
                      ))}
                    </div>
                  )}
                </section>
              ))}
            </div>
          </section>
        ))}
      </div>}

      {(result || error) && (
        <button type="button" onClick={onReanalyze} disabled={!canReanalyze || loading}
          className="mt-3 inline-flex items-center gap-1.5 rounded border border-violet-200 bg-violet-50 px-3 py-2 text-xs text-violet-800 hover:bg-violet-100 disabled:cursor-not-allowed disabled:opacity-50">
          <RefreshCw size={13} className={loading ? "animate-spin" : ""} /> 重新进行智能语义分析
        </button>
      )}

      {view.analysisDuration && <p className="mt-3 text-xs text-slate-500">分析用时：{view.analysisDuration}</p>}
    </section>
  );
}
