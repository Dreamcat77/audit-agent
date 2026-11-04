import type { DurableReviewEvidenceView } from "./reviewRunSemanticAdapter.ts";
import type { FindingSourceAnchor } from "./types.ts";

export interface DurableEvidenceFocus {
  materialId: string;
  source: FindingSourceAnchor | null;
  locatorUnavailable: boolean;
  notice: string | null;
}

const LOCATOR_UNAVAILABLE_NOTICE = "暂无法精确定位原文位置，可查看来源材料。";

export function createDurableEvidenceFocus(
  evidence: DurableReviewEvidenceView,
  projectId: string,
  availableFileIds: ReadonlySet<string>,
): DurableEvidenceFocus | null {
  if (!availableFileIds.has(evidence.fileId)) return null;
  if (!evidence.locatorAvailable) return {
    materialId: evidence.fileId,
    source: null,
    locatorUnavailable: true,
    notice: LOCATOR_UNAVAILABLE_NOTICE,
  };
  return {
    materialId: evidence.fileId,
    locatorUnavailable: false,
    notice: null,
    source: {
      role: "REVIEW_EVIDENCE",
      projectId,
      fileId: evidence.fileId,
      fileName: evidence.fileName,
      pageNumber: evidence.pageNumber,
      paragraphIndex: evidence.paragraphIndex,
      locator: evidence.locatorLabel,
      excerpt: evidence.excerpt,
    },
  };
}
