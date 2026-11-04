import { useEffect, useMemo, useRef, useState } from "react";
import {
  AlertTriangle,
  CheckCircle,
  ChevronDown,
  ChevronUp,
  CircleAlert,
  LoaderCircle,
  LocateFixed,
  ShieldAlert,
} from "lucide-react";

import {
  findingBusinessStatus,
  findingSourceAnchorPresentationKey,
  findingSourceDisplayMode,
  groupF02FindingsForPresentation,
  presentFindingSource,
  createFindingComparisonPresentation,
  createAcknowledgementControl,
  deriveRiskAcknowledgementPresentation,
  handleAcknowledgementActionClick,
  nextExpandedFindingId,
  reviewFindingDetailPresentation,
  reviewSummaryBlocks,
} from "../reviewFindingPresentation";
import { reviewRuleTitle } from "../reviewRulePresentation";
import { formatReviewFactTime } from "../reviewHistoryViewModel";
import { selectF02ReviewVisibility } from "../reviewFindingsViewModel";
import type { ReviewExecutionSnapshot } from "../reviewExecutionPresentation";
import type { ProgressiveReplayState } from "../reviewProgressState";
import type {
  Contract,
  Finding,
  FindingSourceAnchor,
  ReviewProgress,
  ReviewResult,
} from "../types";
import type { ReviewStepStatus } from "../reviewRunTypes";
import type { EvidenceAnchor } from "../workspaceTypes";
import { FindingComparisonSources } from "./FindingComparisonSources";

interface F02ReviewResultPanelProps {
  contract?: Contract | null;
  result: ReviewResult | null;
  progress?: ReviewProgress | null;
  progressiveReplayState?: ProgressiveReplayState | null;
  reviewLoading: boolean;
  resultLoading: boolean;
  resultUnavailable: boolean;
  snapshot?: ReviewExecutionSnapshot;
  findingEvidence?: Readonly<Record<string, EvidenceAnchor>>;
  acknowledgementLoadingResultId?: string | null;
  acknowledgementError?: string | null;
  onAcknowledgeFinding?: (findingId: string) => void;
  onFindingFocus?: (anchor: EvidenceAnchor) => void;
  onProjectedSourceFocus?: (source: FindingSourceAnchor) => void;
  showAllPersistedFindings?: boolean;
  durableStatus?: ReviewStepStatus | null;
  durableStatusDetail?: string | null;
}

const toneUi = {
  SUCCESS: {
    badge: "border-emerald-200 bg-emerald-50 text-emerald-700",
    panel: "border-emerald-200 bg-emerald-50/40",
  },
  ATTENTION: {
    badge: "border-amber-300 bg-amber-50 text-amber-900",
    panel: "border-amber-300 bg-amber-50/60",
  },
  PRIORITY: {
    badge: "border-orange-300 bg-orange-50 text-orange-900",
    panel: "border-orange-300 bg-orange-50/70",
  },
  NEUTRAL: {
    badge: "border-slate-300 bg-slate-100 text-slate-700",
    panel: "border-slate-300 bg-slate-50",
  },
} as const;

function FindingIcon({ business }: { business: ReturnType<typeof findingBusinessStatus> }) {
  if (business.tone === "SUCCESS") {
    return <CheckCircle size={16} className="text-emerald-600" aria-hidden="true" />;
  }
  if (business.tone === "PRIORITY") {
    return <CircleAlert size={16} className="text-orange-700" aria-hidden="true" />;
  }
  return <AlertTriangle size={16} className={business.tone === "ATTENTION" ? "text-amber-700" : "text-slate-600"} aria-hidden="true" />;
}

function CountBadge({ label, value, tone }: { label: string; value: number; tone: string }) {
  return <span className={`whitespace-nowrap rounded border px-2.5 py-1 text-xs ${tone}`}>{label} {value}</span>;
}

export default function F02ReviewResultPanel({
  contract = null,
  result,
  progress = null,
  progressiveReplayState = null,
  reviewLoading,
  resultLoading,
  resultUnavailable,
  findingEvidence,
  acknowledgementLoadingResultId = null,
  acknowledgementError = null,
  onAcknowledgeFinding,
  onFindingFocus,
  onProjectedSourceFocus,
  showAllPersistedFindings = false,
  durableStatus = null,
  durableStatusDetail = null,
}: F02ReviewResultPanelProps) {
  const [expandedFindingId, setExpandedFindingId] = useState<string | null>(null);
  const previousPendingCheckResultId = useRef<string | null>(null);
  const visibility = useMemo(
    () => selectF02ReviewVisibility(result?.findings ?? [], progress),
    [progress, result],
  );
  const orderedFindings = visibility.orderedFindings;
  const revealedFindings = showAllPersistedFindings
    ? visibility.orderedFindings
    : visibility.revealedFindings;
  const groups = useMemo(
    () => groupF02FindingsForPresentation(revealedFindings),
    [revealedFindings],
  );
  const acknowledgementsByResult = new Map(
    (progress?.items ?? [])
      .filter((item) => item.checkResultId && item.acknowledgement)
      .map((item) => [item.checkResultId as string, item.acknowledgement!]),
  );
  const progressItemsByResult = new Map(
    (progress?.items ?? [])
      .filter((item) => item.checkResultId)
      .map((item) => [item.checkResultId as string, item]),
  );
  const projectedSourcesByResult = new Map(
    (progress?.items ?? [])
      .filter((item) => item.checkResultId && item.source)
      .map((item) => [item.checkResultId as string, item.source!]),
  );
  const formatReviewFindingIds = new Set(
    orderedFindings
      .filter((finding) => finding.ruleCode === "F02_AMOUNT_WORDS_MATCH"
        && finding.checkStatus === "NOT_EVALUATED"
        && projectedSourcesByResult.get(finding.id)?.available === true)
      .map((finding) => finding.id),
  );

  const pendingCheckResultId = progressiveReplayState?.pendingAcknowledgementItem?.checkResultId ?? null;

  useEffect(() => {
    previousPendingCheckResultId.current = null;
    setExpandedFindingId(null);
  }, [result?.taskId]);
  useEffect(() => {
    setExpandedFindingId((current) => nextExpandedFindingId(
      current,
      previousPendingCheckResultId.current,
      pendingCheckResultId,
    ));
    previousPendingCheckResultId.current = pendingCheckResultId;
  }, [pendingCheckResultId]);

  if (!result) {
    return (
      <section className="flex min-h-40 flex-col items-center justify-center rounded-lg border border-slate-200 bg-white p-6 text-center">
        {reviewLoading ? (
          <>
            <LoaderCircle size={22} className="animate-spin text-blue-700" aria-hidden="true" />
            <p className="mt-3 text-sm font-medium text-slate-900">正在执行金额与结构化信息精确校验</p>
            <p className="mt-1 text-xs text-slate-600">完整结果返回后展示精确核对事实。</p>
          </>
        ) : durableStatus === "RUNNING" ? (
          <>
            <LoaderCircle size={22} className="animate-spin text-blue-700" aria-hidden="true" />
            <p className="mt-3 text-sm font-medium text-slate-900">精确校验正在处理中</p>
            <p className="mt-1 text-xs text-slate-600">请稍后重新查看本次审核记录。</p>
          </>
        ) : durableStatus === "FAILED" || durableStatus === "UNKNOWN" ? (
          <>
            <AlertTriangle size={22} className="text-amber-700" aria-hidden="true" />
            <p className="mt-3 text-sm font-medium text-amber-900">
              {durableStatus === "UNKNOWN" ? "精确校验结果暂无法确认" : "精确校验未完成"}
            </p>
            <p className="mt-1 text-xs text-slate-600">{durableStatusDetail}</p>
          </>
        ) : resultLoading ? (
          <>
            <LoaderCircle size={22} className="animate-spin text-blue-700" aria-hidden="true" />
            <p className="mt-3 text-sm font-medium text-slate-900">正在读取已有精确校验结果</p>
            <p className="mt-1 text-xs text-slate-600">正在加载最近一次审核结果。</p>
          </>
        ) : resultUnavailable ? (
          <>
            <AlertTriangle size={22} className="text-amber-700" aria-hidden="true" />
            <p className="mt-3 text-sm font-medium text-amber-900">最新精确校验结果暂时无法读取</p>
            <p className="mt-1 text-xs text-slate-600">不能据此判断当前合同尚无审核结果。</p>
          </>
        ) : (
          <>
            <ShieldAlert size={22} className="text-slate-500" aria-hidden="true" />
            <p className="mt-3 text-sm font-medium text-slate-900">尚无精确校验结果</p>
            <p className="mt-1 text-xs text-slate-600">当前没有可展示的真实检查事实。</p>
          </>
        )}
      </section>
    );
  }

  const summaryBlocks = progress
    ? reviewSummaryBlocks(progress, { formatReviewFindingIds })
    : [
        { label: "一致项" as const, value: orderedFindings.filter((finding) => finding.checkStatus === "PASS").length },
        { label: "需复核项" as const, value: orderedFindings.filter((finding) => finding.checkStatus === "FAIL").length },
        ...(formatReviewFindingIds.size > 0
          ? [{ label: "格式待复核项" as const, value: formatReviewFindingIds.size }]
          : []),
        {
          label: "材料不足项" as const,
          value: orderedFindings.filter((finding) => finding.checkStatus === "NOT_EVALUATED"
            && !formatReviewFindingIds.has(finding.id)).length,
        },
      ];
  const reviewCount = summaryBlocks.find((block) => block.label === "需复核项")?.value ?? 0;
  const formatReviewCount = summaryBlocks.find((block) => block.label === "格式待复核项")?.value ?? 0;
  const insufficientCount = summaryBlocks.find((block) => block.label === "材料不足项")?.value ?? 0;
  const riskAcknowledgement = progress
    ? deriveRiskAcknowledgementPresentation(progress, pendingCheckResultId)
    : null;
  const overallPrompt = result.blockSign
    ? "建议优先复核"
    : reviewCount > 0
      ? "建议重点复核"
      : formatReviewCount > 0
        ? "金额大写格式待人工复核"
      : insufficientCount > 0
        ? "部分材料依据不足"
        : "核对一致";
  const completed = durableStatus === "COMPLETED" || progressiveReplayState?.complete === true;

  return (
    <section className="space-y-3">
      <div className="rounded-lg border border-blue-200 bg-blue-50 p-4">
        <h3 className="text-[15px] font-semibold text-blue-950">精确校验结果</h3>
        <p className="mt-1 text-xs leading-5 text-blue-900">
          系统对合同金额、明细合计、中标金额及供应商信息进行精确核对。
        </p>
      </div>

      <div className="rounded-lg border border-slate-200 bg-white p-4">
        <div className="flex flex-wrap items-center gap-2">
          <span className={`rounded border px-2.5 py-1 text-xs ${completed
            ? "border-emerald-200 bg-emerald-50 text-emerald-700"
            : "border-blue-200 bg-blue-50 text-blue-700"}`}>
            {completed ? "审核完成" : "审核进行中"}
          </span>
          <span className={`rounded border px-2.5 py-1 text-xs ${result.blockSign
            ? "border-orange-300 bg-orange-50 text-orange-900"
            : reviewCount > 0
              ? "border-amber-300 bg-amber-50 text-amber-900"
              : "border-slate-200 bg-slate-50 text-slate-700"}`}>
            复核提示：{overallPrompt}
          </span>
        </div>
        {riskAcknowledgement?.statusText && (
          <p className="mt-2 text-xs text-slate-600">{riskAcknowledgement.statusText}</p>
        )}
      </div>

      <div className="rounded-lg border border-slate-200 bg-white p-4">
        <h3 className="text-[15px] font-semibold text-slate-900">审核建议汇总</h3>
        <div className="mt-3 flex flex-wrap gap-2">
          {summaryBlocks.map((block) => (
            <CountBadge
              key={block.label}
              label={block.label}
              value={block.value}
              tone={block.label === "一致项"
                ? "border-emerald-200 bg-emerald-50 text-emerald-700"
                : block.label === "需复核项" || block.label === "格式待复核项"
                  ? "border-amber-300 bg-amber-50 text-amber-900"
                  : "border-slate-200 bg-slate-50 text-slate-700"}
            />
          ))}
        </div>
        <p className="mt-3 text-xs leading-5 text-slate-600">
          {showAllPersistedFindings
            ? "汇总仅呈现本次审核已保存的精确核对事实。"
            : "汇总只呈现当前精确核对事实；人工知悉不会改写既有检查状态或审核结果。"}
        </p>
      </div>

      {groups.map((group) => (
        <section key={group.key} className="overflow-hidden rounded-lg border border-slate-200 bg-white">
          <div className="border-b border-slate-200 px-4 py-3">
            <h3 className="text-[15px] font-semibold text-slate-900">{group.title}</h3>
            <p className="mt-1 text-xs leading-5 text-slate-600">{group.description}</p>
          </div>
          <div className="space-y-2 p-3">
            {group.findings.map((finding) => {
              const open = expandedFindingId === finding.id;
              const label = reviewRuleTitle(finding.ruleCode);
              const evidence = findingEvidence?.[finding.itemKey ? `${finding.ruleCode}:${finding.itemKey}` : finding.ruleCode]
                ?? findingEvidence?.[finding.ruleCode];
              const source = presentFindingSource(evidence);
              const projectedSource = projectedSourcesByResult.get(finding.id);
              const presentationOptions = {
                projectedSourceAvailable: projectedSource?.available === true,
              };
              const business = findingBusinessStatus(finding, presentationOptions);
              const findingUi = toneUi[business.tone];
              const comparison = createFindingComparisonPresentation(
                finding,
                contract,
                presentationOptions,
              );
              const sourceMode = findingSourceDisplayMode(
                projectedSource,
                source.available && Boolean(evidence && onFindingFocus),
              );
              const acknowledgement = acknowledgementsByResult.get(finding.id);
              const progressItem = progressItemsByResult.get(finding.id);
              const control = progressItem && progressiveReplayState
                ? createAcknowledgementControl(
                    progressItem,
                    progressiveReplayState,
                    acknowledgementLoadingResultId,
                    acknowledgementError,
                  )
                : null;
              return (
                <div key={finding.id} className={`overflow-hidden rounded-lg border ${findingUi.panel}`}>
                  <div className="flex items-stretch" data-testid="finding-card-header">
                    <button
                      data-testid="finding-accordion-toggle"
                      type="button"
                      onClick={() => setExpandedFindingId(open ? null : finding.id)}
                      className="min-w-0 flex-1 px-3 py-3 text-left transition-colors hover:bg-slate-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-blue-600"
                      aria-expanded={open}
                    >
                      <div className="flex items-start gap-3">
                        <span className="mt-0.5"><FindingIcon business={business} /></span>
                        <div className="min-w-0 flex-1">
                          <div className="flex flex-wrap items-center gap-2">
                            <span className="text-[13px] font-medium text-slate-900">{label}</span>
                            <span className={`rounded border px-2 py-0.5 text-xs ${findingUi.badge}`}>{business.label}</span>
                            <span className="text-[11px] text-slate-600">{business.prompt}</span>
                            {acknowledgement && (
                              <span className="text-[11px] text-slate-600">
                                已知悉 · {acknowledgement.acknowledgedByDisplayName} · {formatReviewFactTime(acknowledgement.acknowledgedAt)}
                              </span>
                            )}
                            <span className="ml-auto text-slate-500" aria-hidden="true">{open ? <ChevronUp size={14} /> : <ChevronDown size={14} />}</span>
                          </div>
                          <p className="mt-1 text-xs leading-5 text-slate-700">
                            {reviewFindingDetailPresentation(finding, presentationOptions)}{finding.checkStatus === "FAIL" ? " 建议人工复核。" : ""}
                          </p>
                        </div>
                      </div>
                    </button>
                    {control && onAcknowledgeFinding && (
                      <div className="flex shrink-0 flex-col items-center justify-center border-l border-slate-200/80 px-3 py-2">
                        <span className="mb-1 text-[11px] font-medium text-amber-800">待知悉</span>
                        <button
                          data-testid="finding-acknowledgement-action"
                          type="button"
                          onClick={(event) => handleAcknowledgementActionClick(
                            event,
                            finding.id,
                            onAcknowledgeFinding,
                          )}
                          disabled={control.disabled}
                          aria-busy={control.busy}
                          className="inline-flex items-center gap-2 rounded border border-blue-700 bg-blue-700 px-3 py-2 text-xs font-medium text-white hover:bg-blue-800 disabled:cursor-wait disabled:opacity-60"
                        >
                          {control.busy && <LoaderCircle size={13} className="animate-spin" aria-hidden="true" />}
                          {control.label}
                        </button>
                        {control.statusText && (
                          <p
                            className={`mt-1 max-w-40 text-center text-[11px] ${control.busy ? "text-slate-600" : "text-red-700"}`}
                            role={control.busy ? "status" : "alert"}
                          >
                            {control.statusText}
                          </p>
                        )}
                      </div>
                    )}
                  </div>
                  {open && (
                    <div className="border-t border-slate-200/80 px-3 pb-3 pt-3">
                      {comparison.cards.length > 0 && (
                        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                          {comparison.cards.map((card) => (
                            <div key={card.label} className="rounded border border-slate-200 bg-white p-3">
                              <p className="text-xs text-slate-500">{card.label}</p>
                              {card.detail && <p className="mt-1 text-xs text-slate-600">{card.detail}</p>}
                              <p className="mt-1 break-all text-[13px] text-slate-900">{card.value}</p>
                            </div>
                          ))}
                        </div>
                      )}
                      <dl className="mt-3 grid grid-cols-[6rem_minmax(0,1fr)] gap-y-1.5 text-xs">
                        <dt className="text-slate-500">核对完成时间</dt>
                        <dd className="text-slate-800">{formatReviewFactTime(finding.createdAt)}</dd>
                        {acknowledgement && (
                          <>
                            <dt className="text-slate-500">确认人</dt>
                            <dd className="break-words text-slate-800">{acknowledgement.acknowledgedByDisplayName}</dd>
                            <dt className="text-slate-500">确认时间</dt>
                            <dd className="text-slate-800">{formatReviewFactTime(acknowledgement.acknowledgedAt)}</dd>
                          </>
                        )}
                      </dl>
                      <div className="mt-3 flex flex-wrap items-center justify-end gap-3">
                        {sourceMode === "PROJECTED" && projectedSource && onProjectedSourceFocus ? (
                          <FindingComparisonSources source={projectedSource} onFocus={onProjectedSourceFocus} />
                        ) : sourceMode === "PROJECTED" && projectedSource ? (
                          <div className="space-y-2 text-xs text-slate-600">
                            {projectedSource.sources.map((projectedAnchor, sourceIndex) => (
                              <details key={findingSourceAnchorPresentationKey(projectedAnchor, sourceIndex)}>
                                <summary className="cursor-pointer font-medium text-blue-700"><LocateFixed size={14} className="mr-1 inline" />
                                  对比来源·{projectedAnchor.fileName ?? (projectedAnchor.role === "AWARD_NOTICE" ? "中标通知书" : "合同正文")}
                                </summary>
                                <p className="mt-2 max-w-xl break-words rounded bg-white p-2">{projectedAnchor.excerpt}</p>
                                <p className="mt-1 text-slate-500">{projectedAnchor.locator}{projectedAnchor.pageNumber ? ` · 第 ${projectedAnchor.pageNumber} 页` : ""}</p>
                              </details>
                            ))}
                          </div>
                        ) : sourceMode === "LEGACY" && evidence && onFindingFocus ? (
                          <button type="button" onClick={() => onFindingFocus({ ...evidence, label })}
                            className="inline-flex items-center gap-1.5 text-xs font-medium text-blue-700 hover:text-blue-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-600">
                            <LocateFixed size={14} aria-hidden="true" />{source.label}
                          </button>
                        ) : (
                          <p className="text-xs text-slate-500" title={projectedSource?.reason ?? undefined}>暂无可靠来源定位</p>
                        )}
                      </div>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </section>
      ))}

      {revealedFindings.length < orderedFindings.length && (
        <p className="rounded border border-slate-200 bg-white px-3 py-2 text-xs text-slate-600">
          后续检查事实将在当前复核提示完成知悉并刷新持久状态后展示。
        </p>
      )}
    </section>
  );
}
