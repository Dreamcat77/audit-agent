import type {
  Finding,
  FindingAcknowledgement,
  ReviewProgress,
  ReviewResult,
} from "./types";
import { selectF02ReviewVisibility } from "./reviewFindingsViewModel";
import { reviewRuleTitle } from "./reviewRulePresentation";
import { findingBusinessStatus, reviewFindingDetailPresentation } from "./reviewFindingPresentation";

export type ReviewHistoryEventType =
  | "SOURCE_PREPARATION"
  | "FINDING_RESULT"
  | "FINDING_ACKNOWLEDGEMENT";

export interface ReviewHistoryEvent {
  key: string;
  type: ReviewHistoryEventType;
  factId: string | null;
  displayOrder: number;
  title: string;
  detail: string | null;
  statusText: string;
  occurredAt: string | null;
  timeLabel: string;
  timeText: string;
  actorDisplayName: string | null;
}

const eventTypeOrder: Record<ReviewHistoryEventType, number> = {
  SOURCE_PREPARATION: 0,
  FINDING_RESULT: 1,
  FINDING_ACKNOWLEDGEMENT: 2,
};

export type ReviewHistoryAccessPresentation =
  | { state: "READY"; message: null }
  | { state: "LOADING"; message: string }
  | { state: "UNAVAILABLE"; message: string };

export function reviewHistoryAccessPresentation(
  loading: boolean,
  _error: string | null,
  progress: ReviewProgress | null,
): ReviewHistoryAccessPresentation {
  if (progress) return { state: "READY", message: null };
  if (loading) return { state: "LOADING", message: "正在读取审核记录…" };
  return {
    state: "UNAVAILABLE",
    message: "审核记录暂时无法读取，请稍后重试。",
  };
}

function parsedTime(value: string | null): number | null {
  if (!value) return null;
  const parsed = Date.parse(value);
  return Number.isNaN(parsed) ? null : parsed;
}

export function formatReviewFactTime(value: string | null): string {
  const timestamp = parsedTime(value);
  if (timestamp === null) return "暂无可靠时间";
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  }).format(new Date(timestamp));
}

function resultStatus(finding: Finding): string {
  const business = findingBusinessStatus(finding);
  return business.label === business.prompt
    ? business.label
    : `${business.label} · ${business.prompt}`;
}

function sourceEvent(displayOrder: number, occurredAt: string | null): ReviewHistoryEvent {
  return {
    key: `source-preparation:${displayOrder}`,
    type: "SOURCE_PREPARATION",
    factId: null,
    displayOrder,
    title: reviewRuleTitle("SOURCE_PREPARATION"),
    detail: null,
    statusText: "核对完成",
    occurredAt,
    timeLabel: "核对完成时间",
    timeText: formatReviewFactTime(occurredAt),
    actorDisplayName: null,
  };
}

function resultEvent(
  finding: Finding,
  title: string,
  displayOrder: number,
): ReviewHistoryEvent {
  return {
    key: `finding-result:${finding.id}`,
    type: "FINDING_RESULT",
    factId: finding.id,
    displayOrder,
    title,
    detail: reviewFindingDetailPresentation(finding),
    statusText: resultStatus(finding),
    occurredAt: finding.createdAt,
    timeLabel: "核对完成时间",
    timeText: formatReviewFactTime(finding.createdAt),
    actorDisplayName: null,
  };
}

function acknowledgementEvent(
  acknowledgement: FindingAcknowledgement,
  title: string,
  displayOrder: number,
): ReviewHistoryEvent {
  return {
    key: `finding-acknowledgement:${acknowledgement.id}`,
    type: "FINDING_ACKNOWLEDGEMENT",
    factId: acknowledgement.id,
    displayOrder,
    title,
    detail: null,
    statusText: "已知悉",
    occurredAt: acknowledgement.acknowledgedAt,
    timeLabel: "确认时间",
    timeText: formatReviewFactTime(acknowledgement.acknowledgedAt),
    actorDisplayName: acknowledgement.acknowledgedByDisplayName,
  };
}

function compareEvents(left: ReviewHistoryEvent, right: ReviewHistoryEvent): number {
  const leftTime = parsedTime(left.occurredAt);
  const rightTime = parsedTime(right.occurredAt);
  if (leftTime !== null || rightTime !== null) {
    if (leftTime === null) return 1;
    if (rightTime === null) return -1;
    if (leftTime !== rightTime) return leftTime - rightTime;
  }
  const leftFactId = left.factId ?? "";
  const rightFactId = right.factId ?? "";
  return left.displayOrder - right.displayOrder
    || eventTypeOrder[left.type] - eventTypeOrder[right.type]
    || (leftFactId === rightFactId ? 0 : leftFactId < rightFactId ? -1 : 1);
}

export function buildReviewHistoryTimeline(
  progress: ReviewProgress,
  result: ReviewResult,
): ReviewHistoryEvent[] {
  const visibility = selectF02ReviewVisibility(result.findings, progress);
  const findingsById = new Map(
    visibility.revealedFindings.map((finding) => [finding.id, finding]),
  );
  const events: ReviewHistoryEvent[] = [];

  visibility.revealedItems.forEach((item) => {
    if (item.type === "SOURCE_PREPARATION") {
      events.push(sourceEvent(item.displayOrder, item.completedAt));
      return;
    }
    if (!item.checkResultId) return;
    const finding = findingsById.get(item.checkResultId);
    if (!finding) return;
    const title = reviewRuleTitle(finding.ruleCode);
    events.push(resultEvent(finding, title, item.displayOrder));
    if (item.acknowledgement?.checkResultId === finding.id) {
      events.push(acknowledgementEvent(item.acknowledgement, title, item.displayOrder));
    }
  });

  return events.sort(compareEvents);
}
