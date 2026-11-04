import { useMemo } from "react";
import * as DialogPrimitive from "@radix-ui/react-dialog";
import { CheckCircle2, CircleAlert, History, UserRound, X } from "lucide-react";
import {
  buildReviewHistoryTimeline,
  type ReviewHistoryEvent,
} from "../reviewHistoryViewModel";
import type { ReviewProgress, ReviewResult } from "../types";

interface F02ReviewHistoryDrawerProps {
  progress: ReviewProgress;
  result: ReviewResult;
}

function EventIcon({ event }: { event: ReviewHistoryEvent }) {
  if (event.type === "FINDING_ACKNOWLEDGEMENT") {
    return <UserRound size={15} aria-hidden="true" />;
  }
  if (event.statusText.includes("复核") || event.statusText.includes("材料不足")) {
    return <CircleAlert size={15} aria-hidden="true" />;
  }
  return <CheckCircle2 size={15} aria-hidden="true" />;
}

function eventTone(event: ReviewHistoryEvent): string {
  if (event.type === "FINDING_ACKNOWLEDGEMENT") {
    return "border-blue-200 bg-blue-50 text-blue-700";
  }
  if (event.statusText.includes("复核")) {
    return "border-amber-200 bg-amber-50 text-amber-800";
  }
  if (event.statusText.includes("材料不足")) {
    return "border-slate-200 bg-slate-50 text-slate-700";
  }
  return "border-emerald-200 bg-emerald-50 text-emerald-700";
}

export default function F02ReviewHistoryDrawer({
  progress,
  result,
}: F02ReviewHistoryDrawerProps) {
  const events = useMemo(
    () => buildReviewHistoryTimeline(progress, result),
    [progress, result],
  );

  return (
    <DialogPrimitive.Root>
      <DialogPrimitive.Trigger asChild>
        <button
          type="button"
          className="inline-flex items-center gap-1.5 rounded border border-slate-200 bg-white px-3 py-2 text-xs font-medium text-blue-700 hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-600 focus-visible:ring-offset-2"
          aria-label="打开审核记录"
        >
          <History size={14} aria-hidden="true" />
          审核记录
        </button>
      </DialogPrimitive.Trigger>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-slate-950/35" />
        <DialogPrimitive.Content
          className="fixed inset-y-0 right-0 z-50 flex w-full max-w-lg flex-col overflow-hidden border-l border-slate-200 bg-[#F6F8FB] shadow-2xl focus:outline-none sm:w-[min(32rem,calc(100vw-2rem))]"
          aria-describedby="f02-review-history-description"
        >
          <header className="flex shrink-0 items-start justify-between gap-4 border-b border-slate-200 bg-white px-4 py-4 sm:px-5">
            <div className="min-w-0">
              <DialogPrimitive.Title className="text-base font-semibold text-slate-900">
                审核记录
              </DialogPrimitive.Title>
              <DialogPrimitive.Description
                id="f02-review-history-description"
                className="mt-1 text-xs leading-5 text-slate-600"
              >
                仅展示来源读取、精确核对结果与人工知悉事实。
              </DialogPrimitive.Description>
            </div>
            <DialogPrimitive.Close
              className="inline-flex size-9 shrink-0 items-center justify-center rounded border border-slate-200 bg-white text-slate-600 hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-600 focus-visible:ring-offset-2"
              aria-label="关闭审核记录"
            >
              <X size={16} aria-hidden="true" />
            </DialogPrimitive.Close>
          </header>

          <div className="min-h-0 flex-1 overflow-y-auto px-4 py-4 sm:px-5">
            {events.length === 0 ? (
              <p className="rounded border border-slate-200 bg-white px-4 py-5 text-center text-xs text-slate-600">
                暂无可展示的审核记录
              </p>
            ) : (
              <ol className="space-y-3" aria-label="审核记录时间线">
                {events.map((event) => (
                  <li key={event.key} className="rounded-lg border border-slate-200 bg-white p-4">
                    <div className="flex items-start gap-3">
                      <span className={`mt-0.5 inline-flex size-8 shrink-0 items-center justify-center rounded-full border ${eventTone(event)}`}>
                        <EventIcon event={event} />
                      </span>
                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-start justify-between gap-2">
                          <h3 className="break-words text-[13px] font-medium text-slate-900">
                            {event.title}
                          </h3>
                          <span className={`whitespace-nowrap rounded border px-2 py-0.5 text-xs ${eventTone(event)}`}>
                            {event.statusText}
                          </span>
                        </div>
                        {event.detail && (
                          <p className="mt-2 break-words text-xs leading-5 text-slate-700">
                            {event.detail}
                          </p>
                        )}
                        <dl className="mt-3 grid grid-cols-[5.5rem_minmax(0,1fr)] gap-y-1.5 text-xs">
                          {event.actorDisplayName && (
                            <>
                              <dt className="text-slate-500">确认人</dt>
                              <dd className="break-words text-slate-800">{event.actorDisplayName}</dd>
                            </>
                          )}
                          <dt className="text-slate-500">{event.timeLabel}</dt>
                          <dd className="break-words text-slate-800">
                            {event.occurredAt && event.timeText !== "暂无可靠时间" ? (
                              <time dateTime={event.occurredAt}>{event.timeText}</time>
                            ) : event.timeText}
                          </dd>
                        </dl>
                      </div>
                    </div>
                  </li>
                ))}
              </ol>
            )}
          </div>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}
