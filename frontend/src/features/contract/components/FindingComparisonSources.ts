import { createElement, type ReactElement, type ReactNode } from "react";
import { LocateFixed } from "lucide-react";

import type { FindingSourceAnchor, FindingSourceProjection } from "../types";
import { findingSourceAnchorPresentationKey } from "../reviewFindingPresentation";

interface FindingComparisonSourcesProps {
  source: FindingSourceProjection;
  onFocus: (anchor: FindingSourceAnchor) => void;
}

export function FindingComparisonSources({
  source,
  onFocus,
}: FindingComparisonSourcesProps): ReactElement {
  const children: ReactNode[] = [
    createElement("span", { key: "label", className: "text-xs text-slate-500" }, "对比来源："),
  ];
  source.sources.forEach((anchor, index) => {
    if (index > 0) {
      children.push(createElement("span", {
        key: `separator-${findingSourceAnchorPresentationKey(anchor, index)}`,
        className: "text-slate-400",
        "aria-hidden": "true",
      }, "↔"));
    }
    children.push(createElement("button", {
      key: findingSourceAnchorPresentationKey(anchor, index),
      type: "button",
      onClick: () => onFocus(anchor),
      className: "inline-flex items-center gap-1.5 text-xs font-medium text-blue-700 hover:text-blue-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-600",
    },
    createElement(LocateFixed, { size: 14, "aria-hidden": "true" }),
    anchor.fileName ?? (anchor.role === "AWARD_NOTICE" ? "中标通知书" : "合同正文"),
    source.comparisonMode === "INTERNAL" ? "（内部核对）" : ""));
  });
  return createElement("div", { className: "flex flex-wrap items-center gap-2" }, children);
}
