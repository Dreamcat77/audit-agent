import type { Finding, ReviewProgress, ReviewProgressItem } from "./types";
import { deriveProgressiveReplayState } from "./reviewProgressState";

export interface F02ReviewVisibility {
  orderedItems: ReviewProgressItem[];
  revealedItems: ReviewProgressItem[];
  revealedItemCount: number;
  orderedFindings: Finding[];
  revealedFindings: Finding[];
}

/** Orders a copy only by the contract-owned server projection, preserving unmatched arrival order. */
export function orderF02FindingsForView(
  findings: readonly Finding[],
  progress?: ReviewProgress | null,
): Finding[] {
  const serverOrder = new Map(
    (progress?.items ?? [])
      .filter((item) => item.type === "FINDING" && item.checkResultId !== null)
      .map((item) => [item.checkResultId as string, item.displayOrder]),
  );
  return findings
    .map((finding, sourceIndex) => ({ finding, sourceIndex }))
    .sort((left, right) => {
      const leftOrder = serverOrder.get(left.finding.id) ?? Number.MAX_SAFE_INTEGER;
      const rightOrder = serverOrder.get(right.finding.id) ?? Number.MAX_SAFE_INTEGER;
      return leftOrder - rightOrder || left.sourceIndex - right.sourceIndex;
    })
    .map(({ finding }) => finding);
}

/** Selects detail rows only from the prefix proven visible by durable progress facts. */
export function selectDurablyRevealedF02Findings(
  findings: readonly Finding[],
  progress?: ReviewProgress | null,
): Finding[] {
  return selectF02ReviewVisibility(findings, progress).revealedFindings;
}

/** One durable visibility boundary shared by every F02 result-detail surface. */
export function selectF02ReviewVisibility(
  findings: readonly Finding[],
  progress?: ReviewProgress | null,
): F02ReviewVisibility {
  const orderedFindings = orderF02FindingsForView(findings, progress);
  if (!progress) {
    return {
      orderedItems: [],
      revealedItems: [],
      revealedItemCount: 0,
      orderedFindings,
      revealedFindings: [],
    };
  }
  const replay = deriveProgressiveReplayState(progress);
  const revealedItemCount = replay.complete
    ? replay.orderedItems.length
    : replay.completedPrefixCount + 1;
  const revealedItems = replay.orderedItems.slice(
    0,
    revealedItemCount,
  );
  const visibleResultIds = new Set(
    revealedItems
      .filter((item) => item.type === "FINDING" && item.checkResultId !== null)
      .map((item) => item.checkResultId as string),
  );
  return {
    orderedItems: replay.orderedItems,
    revealedItems,
    revealedItemCount,
    orderedFindings,
    revealedFindings: orderedFindings.filter((finding) => visibleResultIds.has(finding.id)),
  };
}
