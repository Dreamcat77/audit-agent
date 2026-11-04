import { findingPresentationStatus, reviewFindingDetailPresentation, summarizeFindingPresentation } from "./reviewFindingPresentation";
import { selectF02ReviewVisibility } from "./reviewFindingsViewModel";
import { reviewRuleTitle } from "./reviewRulePresentation";
import type { Finding, ReviewProgress, ReviewProgressItem, ReviewResult } from "./types";

export type ReviewExecutionState = "NOT_STARTED" | "RUNNING" | "COMPLETED" | "FAILED";
export type ReviewExecutionStepStatus = "PENDING" | "RUNNING" | "PASS" | "WARNING" | "UNDETERMINED" | "FAIL";

export interface ReviewExecutionStep {
  key: string;
  shortLabel: string;
  label: string;
  detail: string;
  status: ReviewExecutionStepStatus;
}

export interface ReviewFindingAcknowledgement {
  findingId: string;
  acknowledged: boolean;
  acknowledgedBy?: string;
  acknowledgedAt?: string;
}

export interface MaterialValidationSummary {
  valid: boolean;
  message: string;
}

export interface ReviewExecutionSnapshot {
  state: ReviewExecutionState;
  steps: ReviewExecutionStep[];
  summary: { pass: number; warning: number; fail: number; undetermined: number };
  findings: Finding[];
  reportAvailable: boolean;
  acknowledgement?: ReviewFindingAcknowledgement | null;
  materialValidationSummary?: MaterialValidationSummary | null;
}

function statusForProgressItem(
  item: ReviewProgressItem,
  finding: Finding | undefined,
  visible: boolean,
): ReviewExecutionStepStatus {
  if (!visible) return "PENDING";
  if (item.type === "SOURCE_PREPARATION") return item.status === "DONE" ? "PASS" : "RUNNING";
  if (finding) return findingPresentationStatus(finding);
  if (item.status === "NOT_EVALUATED") return "UNDETERMINED";
  if (item.status === "PASS") return "PASS";
  if (item.risk === "RED") return "FAIL";
  if (item.risk === "YELLOW") return "WARNING";
  return "UNDETERMINED";
}

export function createReviewExecutionSnapshot({ result, progress = null, state = result ? "COMPLETED" : "NOT_STARTED" }: {
  result: ReviewResult | null;
  progress?: ReviewProgress | null;
  state?: ReviewExecutionState;
}): ReviewExecutionSnapshot {
  const visibility = selectF02ReviewVisibility(result?.findings ?? [], progress);
  const findings = visibility.orderedFindings;
  const findingsById = new Map(findings.map((finding) => [finding.id, finding]));
  const steps = progress
    ? visibility.orderedItems.map<ReviewExecutionStep>((item, index) => {
        const visible = index < visibility.revealedItemCount;
        const finding = visible && item.checkResultId
          ? findingsById.get(item.checkResultId)
          : undefined;
        return {
          key: item.checkResultId ?? `source-${item.displayOrder}`,
          shortLabel: String(index + 1),
          label: visible
            ? item.type === "SOURCE_PREPARATION"
              ? reviewRuleTitle("SOURCE_PREPARATION")
              : reviewRuleTitle(finding?.ruleCode ?? "")
            : "后续检查项",
          detail: visible
            ? finding ? reviewFindingDetailPresentation(finding)
              : item.type === "SOURCE_PREPARATION" ? "已读取当前项目与合同的结构化事实和来源材料" : "核对完成"
            : "待前序复核提示知悉后展示",
          status: statusForProgressItem(item, finding, visible),
        };
      })
    : [{
        key: "source-0",
        shortLabel: "1",
        label: reviewRuleTitle("SOURCE_PREPARATION"),
        detail: "准备读取当前项目与合同的结构化事实和来源材料",
        status: state === "RUNNING" ? "RUNNING" : state === "FAILED" ? "FAIL" : "PENDING",
      } satisfies ReviewExecutionStep];
  return {
    state,
    steps,
    summary: summarizeFindingPresentation(findings),
    findings,
    reportAvailable: Boolean(result),
    acknowledgement: null,
    materialValidationSummary: null,
  };
}

export function presentationStatusForFinding(finding: Finding): ReviewExecutionStepStatus {
  return findingPresentationStatus(finding);
}
