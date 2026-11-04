import { LoaderCircle } from "lucide-react";
import {
  createAcknowledgementControl,
  formatFindingComparisonValue,
  isCurrentPendingAcknowledgementItem,
  progressRiskNodePresentation,
  reviewFindingDetailPresentation,
} from "../reviewFindingPresentation";
import {
  createReviewExecutionSnapshot,
  type ReviewExecutionSnapshot,
  type ReviewExecutionStepStatus,
} from "../reviewExecutionPresentation";
import type { ProgressiveReplayState } from "../reviewProgressState";
import { formatReviewFactTime } from "../reviewHistoryViewModel";
import { selectF02ReviewVisibility } from "../reviewFindingsViewModel";
import type { ReviewProgress, ReviewProgressItem, ReviewResult } from "../types";
import type { SourcePreparationSection } from "../reviewRulePresentation";
import ReviewStepIndicator, {
  reviewStepStatusClasses,
  reviewStepStatusLabels,
} from "./ReviewStepIndicator";

interface ReviewProgressTimelineProps {
  contractReady: boolean;
  contractLoading: boolean;
  contractFailed: boolean;
  reviewLoading: boolean;
  result: ReviewResult | null;
  progress?: ReviewProgress | null;
  progressiveReplayState?: ProgressiveReplayState | null;
  acknowledgementLoadingResultId?: string | null;
  acknowledgementError?: string | null;
  onAcknowledgeFinding?: (findingId: string) => void;
  snapshot?: ReviewExecutionSnapshot;
  sourcePreparationSections?: readonly SourcePreparationSection[];
}

function displayedStatus(
  fallback: ReviewExecutionStepStatus,
  item: ReviewProgressItem | undefined,
): ReviewExecutionStepStatus {
  if (!item || fallback === "PENDING" || fallback === "RUNNING") return fallback;
  if (item.type === "SOURCE_PREPARATION" || item.risk === "GREEN") return "PASS";
  if (item.risk === "YELLOW") return "WARNING";
  if (item.risk === "RED") return "FAIL";
  return fallback;
}

export default function ReviewProgressTimeline({
  contractLoading,
  contractFailed,
  reviewLoading,
  result,
  progress = null,
  progressiveReplayState = null,
  acknowledgementLoadingResultId = null,
  acknowledgementError = null,
  onAcknowledgeFinding,
  snapshot,
  sourcePreparationSections = [],
}: ReviewProgressTimelineProps) {
  const execution = snapshot ?? createReviewExecutionSnapshot({
    result,
    progress,
    state: contractFailed
      ? "FAILED"
      : reviewLoading || contractLoading
        ? "RUNNING"
        : result
          ? "COMPLETED"
          : "NOT_STARTED",
  });
  const visibility = selectF02ReviewVisibility(result?.findings ?? [], progress);
  const total = progressiveReplayState?.total ?? progress?.total ?? execution.steps.length;
  const current = progressiveReplayState?.current
    ?? (reviewLoading || contractLoading ? Math.min(1, total) : result ? total : 0);
  const findingsById = new Map((result?.findings ?? []).map((finding) => [finding.id, finding]));
  const processStatus = progressiveReplayState?.complete ? "审核完成" : "审核进行中";

  return (
    <section className="overflow-hidden rounded-lg border border-slate-200 bg-white">
      <div className="flex items-start justify-between gap-3 border-b border-slate-200 px-4 py-3">
        <div>
          <h3 className="text-[15px] font-semibold text-slate-900">审核进度</h3>
          <p className="mt-1 text-xs text-slate-600">按已完成的核对结果逐项展示；页面不重复计算业务金额。</p>
        </div>
        <div className="flex flex-wrap items-center justify-end gap-2">
          <span className={`whitespace-nowrap rounded border px-2 py-1 text-xs ${progressiveReplayState?.complete ? "border-emerald-200 bg-emerald-50 text-emerald-700" : "border-blue-200 bg-blue-50 text-blue-700"}`}>
            {processStatus}
          </span>
          {total > 0 && (
            <span
              className="whitespace-nowrap rounded border border-blue-200 bg-blue-50 px-2 py-1 text-xs tabular-nums text-blue-700"
              aria-label={`当前第 ${current} 项，共 ${total} 项`}
            >
              {current} / {total}
            </span>
          )}
        </div>
      </div>
      <div className="p-4">
        {reviewLoading && (
          <div className="mb-3 flex items-center gap-2 rounded border border-blue-200 bg-blue-50 px-3 py-2 text-xs text-blue-800">
            <LoaderCircle size={13} className="animate-spin" aria-hidden="true" />
            正在执行智能审核；当前不表示系统正在逐条实时运行。
          </div>
        )}
        <ol className="space-y-1" aria-label="合同审核进度">
          {execution.steps.map((step, index) => {
            const item = index < visibility.revealedItemCount
              ? progressiveReplayState?.orderedItems[index] ?? visibility.orderedItems[index]
              : undefined;
            const status = displayedStatus(step.status, item);
            const acknowledged = Boolean(
              item?.checkResultId
              && item.acknowledgement?.checkResultId === item.checkResultId,
            );
            const statusLabel = item?.risk
              ? progressRiskNodePresentation(item.risk, acknowledged).statusText
              : reviewStepStatusLabels[status];
            const control = item && progressiveReplayState
              ? createAcknowledgementControl(
                  item,
                  progressiveReplayState,
                  acknowledgementLoadingResultId,
                  acknowledgementError,
                )
              : null;
            const isCurrent = isCurrentPendingAcknowledgementItem(
              item,
              progressiveReplayState?.pendingAcknowledgementItem,
            );
            const finding = item?.checkResultId ? findingsById.get(item.checkResultId) : undefined;
            const detail = item?.type === "SOURCE_PREPARATION"
              ? item.status === "DONE"
                ? "来源准备事实已记录"
                : "来源准备事实待读取"
              : step.detail;
            return (
              <li
                key={step.key}
                className="relative flex items-start gap-3 pb-3 last:pb-0"
                aria-current={isCurrent ? "step" : undefined}
              >
                {index < execution.steps.length - 1 && (
                  <span className={`absolute bottom-0 left-[13px] top-7 w-px ${status === "PENDING" ? "bg-slate-200" : "bg-blue-200"}`} />
                )}
                <span className="relative z-10">
                  <ReviewStepIndicator
                    label={step.label}
                    status={status}
                    statusLabel={statusLabel}
                    size="timeline"
                  />
                </span>
                <div className="min-w-0 flex-1 pt-0.5">
                  <div className="flex items-center justify-between gap-3">
                    <p className="text-[13px] font-medium text-slate-900">
                      <span className="mr-1.5 text-xs tabular-nums text-slate-500">{index + 1}</span>
                      {step.label}
                    </p>
                    <span className={`whitespace-nowrap rounded border px-2 py-0.5 text-xs ${reviewStepStatusClasses[status]}`}>
                      {statusLabel}
                    </span>
                  </div>
                  <p className="mt-1 break-words text-xs text-slate-600">{detail}</p>
                  {item?.type === "SOURCE_PREPARATION" && sourcePreparationSections.length > 0 && (
                    <div className="mt-3 grid gap-2">
                      {sourcePreparationSections.map((section) => (
                        <details key={section.title} open className="rounded border border-slate-200 bg-slate-50 px-3 py-2">
                          <summary className="cursor-pointer text-xs font-medium text-slate-800">✓ {section.title}</summary>
                          <dl className="mt-2 grid grid-cols-[minmax(6rem,auto)_minmax(0,1fr)] gap-x-3 gap-y-1.5 text-xs">
                            {section.items.map((fact) => (
                              <div key={`${section.title}:${fact.label}`} className="contents">
                                <dt className="text-slate-500">{fact.label}</dt>
                                <dd className="break-words text-slate-800">{fact.value} · {fact.statusLabel}</dd>
                              </div>
                            ))}
                          </dl>
                        </details>
                      ))}
                    </div>
                  )}
                  {finding && finding.riskLevel !== "GREEN" && (
                    <dl className="mt-3 grid grid-cols-[4.5rem_minmax(0,1fr)] gap-y-1.5 rounded border border-amber-200 bg-amber-50 p-3 text-xs">
                      <dt className="text-slate-500">当前值</dt>
                      <dd className="break-words text-slate-900">{formatFindingComparisonValue(finding.ruleCode, finding.actualValue)}</dd>
                      <dt className="text-slate-500">目标值</dt>
                      <dd className="break-words text-slate-900">{formatFindingComparisonValue(finding.ruleCode, finding.expectedValue)}</dd>
                      <dt className="text-slate-500">事实说明</dt>
                      <dd className="break-words text-slate-900">{reviewFindingDetailPresentation(finding)}</dd>
                    </dl>
                  )}
                  {item?.completedAt !== undefined && status !== "PENDING" && (
                    <p className="mt-1 text-xs text-slate-500">
                      核对完成时间：{formatReviewFactTime(item.completedAt)}
                    </p>
                  )}
                  {item?.acknowledgement && acknowledged && (
                    <p className="mt-1 text-xs text-slate-600">
                      确认人：{item.acknowledgement.acknowledgedByDisplayName} · 确认时间：{formatReviewFactTime(item.acknowledgement.acknowledgedAt)}
                    </p>
                  )}
                  {control && onAcknowledgeFinding && item?.checkResultId && (
                    <div className="mt-3">
                      <button
                        type="button"
                        onClick={() => onAcknowledgeFinding(item.checkResultId as string)}
                        disabled={control.disabled}
                        aria-busy={control.busy}
                        className="inline-flex items-center gap-2 rounded border border-blue-700 bg-blue-700 px-3 py-2 text-xs font-medium text-white hover:bg-blue-800 disabled:cursor-wait disabled:opacity-60"
                      >
                        {control.busy && <LoaderCircle size={13} className="animate-spin" aria-hidden="true" />}
                        {control.label}
                      </button>
                      {control.statusText && (
                        <p
                          className={`mt-2 text-xs ${control.busy ? "text-slate-600" : "text-red-700"}`}
                          role={control.busy ? "status" : "alert"}
                        >
                          {control.statusText}
                        </p>
                      )}
                    </div>
                  )}
                </div>
              </li>
            );
          })}
        </ol>
      </div>
    </section>
  );
}
