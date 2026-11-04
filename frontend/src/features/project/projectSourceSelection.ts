import type { FindingSourceAnchor } from "../contract/types";
import type { ProjectFile, SelectedProjectSource } from "./types";
export { authenticatedInlinePreviewKind } from "../contract/authenticatedPreviewPresentation.ts";
import { authenticatedInlinePreviewKind } from "../contract/authenticatedPreviewPresentation.ts";

interface CandidateSource {
  fileId: string;
  pageNumber: number | null;
  paragraphIndex: number | null;
  locator: string;
  excerpt: string;
}

export const PROJECT_EVIDENCE_PANEL_ACCESSIBILITY = {
  tabIndex: -1,
  headingId: "project-evidence-heading",
} as const;

interface ProjectEvidenceNavigationTarget {
  scrollIntoView(options: ScrollIntoViewOptions): void;
  focus(): void;
}

function complete(value: string | null | undefined): value is string {
  return Boolean(value?.trim());
}

export function candidateSourceTarget(
  projectId: string,
  source: CandidateSource,
  files: Array<Pick<ProjectFile, "id" | "projectId">>,
): SelectedProjectSource | null {
  if (!complete(projectId) || !complete(source.fileId) || !complete(source.locator)
      || !complete(source.excerpt)
      || !files.some((file) => file.id === source.fileId && file.projectId === projectId)) return null;
  return {
    projectId,
    fileId: source.fileId,
    pageNumber: source.pageNumber,
    paragraphIndex: source.paragraphIndex,
    locator: source.locator,
    excerpt: source.excerpt,
  };
}

export function projectedSourceTarget(
  source: FindingSourceAnchor,
  files: Array<Pick<ProjectFile, "id" | "projectId">>,
): SelectedProjectSource | null {
  if (!source.projectId || !source.fileId || !source.locator || !source.excerpt) return null;
  return candidateSourceTarget(source.projectId, {
    fileId: source.fileId,
    pageNumber: source.pageNumber,
    paragraphIndex: source.paragraphIndex,
    locator: source.locator,
    excerpt: source.excerpt,
  }, files);
}

export function selectProjectSource(
  target: SelectedProjectSource | null,
  select: (source: SelectedProjectSource) => void,
): boolean {
  if (!target) return false;
  select(target);
  return true;
}

export function navigateToProjectEvidence(
  target: ProjectEvidenceNavigationTarget | null,
): boolean {
  if (!target) return false;
  target.scrollIntoView({ behavior: "smooth", block: "start" });
  target.focus();
  return true;
}

export function projectSourceView(source: SelectedProjectSource, file: Pick<ProjectFile,
  "originalFilename" | "mediaType" | "parsedText">) {
  const inline = file.mediaType === "application/pdf" || file.mediaType.startsWith("image/");
  const excerpt = source.excerpt.length <= 500 ? source.excerpt : `${source.excerpt.slice(0, 500)}…`;
  return {
    fileName: file.originalFilename,
    pageNumber: source.pageNumber,
    paragraphIndex: source.paragraphIndex,
    locator: source.locator,
    excerpt,
    parsedText: file.parsedText,
    previewMode: inline ? "INLINE" as const : "DOWNLOAD" as const,
  };
}
