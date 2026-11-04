export interface ProjectMaterialContentInput {
  mediaType: string;
  parsedText: string | null;
  provenance: {
    methodLabel: string;
    confidenceLabel: string | null;
  };
}

export type ProjectMaterialContentPresentation =
  | { kind: "PARSED_TEXT"; text: string; originalDownloadAvailable: true;
      provenanceNotice: string }
  | { kind: "INLINE"; text: null; originalDownloadAvailable: true; provenanceNotice: null }
  | { kind: "DOWNLOAD_ONLY"; text: null; originalDownloadAvailable: true; provenanceNotice: null };

export function projectMaterialContentPresentation(
  file: ProjectMaterialContentInput,
): ProjectMaterialContentPresentation {
  if (file.parsedText?.trim()) {
    return {
      kind: "PARSED_TEXT",
      text: file.parsedText,
      originalDownloadAvailable: true,
      provenanceNotice: "材料内容已读取 · 原件可下载",
    };
  }
  const inline = file.mediaType === "application/pdf" || file.mediaType.startsWith("image/");
  return inline
    ? { kind: "INLINE", text: null, originalDownloadAvailable: true, provenanceNotice: null }
    : { kind: "DOWNLOAD_ONLY", text: null, originalDownloadAvailable: true, provenanceNotice: null };
}

export function projectBackedMaterialControlsPresentation() {
  return {
    sourceLabel: "复用项目资料",
    supplementalGuidance: "如需补充，请返回项目详情使用“补充项目资料”",
    primaryUploadAction: null,
  } as const;
}

export function projectMaterialFileOnlyNotice(): "暂无法精确定位原文位置，可查看来源材料。" {
  return "暂无法精确定位原文位置，可查看来源材料。";
}

export interface ProjectMaterialTextSource {
  excerpt: string | null;
  paragraphIndex: number | null;
}

const CENTERED_SCROLL = { behavior: "smooth", block: "center" } as const;

export type ProjectMaterialTextLocation =
  | { kind: "EXACT"; statusText: "已定位来源"; before: string; exactMatch: string; after: string;
      scrollTarget: true; scrollOptions: typeof CENTERED_SCROLL }
  | { kind: "PARAGRAPH"; statusText: string; paragraphs: string[]; paragraphArrayIndex: number;
      highlightedParagraph: string; scrollTarget: true; scrollOptions: typeof CENTERED_SCROLL }
  | { kind: "AMBIGUOUS"; statusText: "已打开来源材料，但存在多个相同值，无法唯一高亮。";
      scrollTarget: false; scrollOptions: null }
  | { kind: "NOT_FOUND"; statusText: "已打开来源材料，但未找到可唯一定位的原文。";
      scrollTarget: false; scrollOptions: null }
  | { kind: "UNAVAILABLE"; statusText: "暂无可靠来源定位"; scrollTarget: false;
      scrollOptions: null };

export interface ProjectMaterialScrollTarget {
  scrollIntoView(options: ScrollIntoViewOptions): void;
}

function normalizedSearchText(value: string): string {
  return value.normalize("NFKC").replace(/\s+/gu, "");
}

type NormalizedRangeResult =
  | { kind: "UNIQUE"; start: number; end: number }
  | { kind: "AMBIGUOUS" }
  | { kind: "NOT_FOUND" };

function normalizedUniqueRange(text: string, excerpt: string): NormalizedRangeResult {
  let normalized = "";
  const offsets: number[] = [];
  let sourceOffset = 0;
  for (const character of text) {
    const next = normalizedSearchText(character);
    normalized += next;
    for (let index = 0; index < next.length; index += 1) offsets.push(sourceOffset);
    sourceOffset += character.length;
  }
  const needle = normalizedSearchText(excerpt);
  if (!needle) return { kind: "NOT_FOUND" };
  const first = normalized.indexOf(needle);
  if (first < 0) return { kind: "NOT_FOUND" };
  if (normalized.lastIndexOf(needle) !== first) return { kind: "AMBIGUOUS" };
  const start = offsets[first];
  const finalOffset = offsets[first + needle.length - 1];
  if (start == null || finalOffset == null) return { kind: "NOT_FOUND" };
  const finalCharacter = text.slice(finalOffset).match(/^./u)?.[0] ?? "";
  return { kind: "UNIQUE", start, end: finalOffset + finalCharacter.length };
}

export function scrollToProjectMaterialLocation(
  target: ProjectMaterialScrollTarget | null,
  location: ProjectMaterialTextLocation,
): boolean {
  if (!target || !location.scrollTarget) return false;
  target.scrollIntoView(location.scrollOptions);
  return true;
}

export function locateProjectMaterialText(
  parsedText: string | null,
  source: ProjectMaterialTextSource | null,
): ProjectMaterialTextLocation {
  if (!parsedText || !source) {
    return { kind: "UNAVAILABLE", statusText: "暂无可靠来源定位", scrollTarget: false, scrollOptions: null };
  }
  const excerpt = source.excerpt?.trim() ?? "";
  let ambiguous = false;
  if (excerpt) {
    const first = parsedText.indexOf(excerpt);
    if (first >= 0 && parsedText.lastIndexOf(excerpt) === first) {
      return {
        kind: "EXACT",
        statusText: "已定位来源",
        before: parsedText.slice(0, first),
        exactMatch: excerpt,
        after: parsedText.slice(first + excerpt.length),
        scrollTarget: true,
        scrollOptions: CENTERED_SCROLL,
      };
    }
    ambiguous = first >= 0;
    const normalizedRange = normalizedUniqueRange(parsedText, excerpt);
    if (normalizedRange.kind === "UNIQUE") {
      return {
        kind: "EXACT",
        statusText: "已定位来源",
        before: parsedText.slice(0, normalizedRange.start),
        exactMatch: parsedText.slice(normalizedRange.start, normalizedRange.end),
        after: parsedText.slice(normalizedRange.end),
        scrollTarget: true,
        scrollOptions: CENTERED_SCROLL,
      };
    }
    ambiguous ||= normalizedRange.kind === "AMBIGUOUS";
  }
  const paragraphs = parsedText.split(/\r?\n/);
  const paragraphArrayIndex = source.paragraphIndex == null ? -1 : source.paragraphIndex - 1;
  if (paragraphArrayIndex >= 0 && paragraphArrayIndex < paragraphs.length) {
    return {
      kind: "PARAGRAPH",
      statusText: `已定位到第 ${source.paragraphIndex} 段`,
      paragraphs,
      paragraphArrayIndex,
      highlightedParagraph: paragraphs[paragraphArrayIndex]!,
      scrollTarget: true,
      scrollOptions: CENTERED_SCROLL,
    };
  }
  if (ambiguous) {
    return {
      kind: "AMBIGUOUS",
      statusText: "已打开来源材料，但存在多个相同值，无法唯一高亮。",
      scrollTarget: false,
      scrollOptions: null,
    };
  }
  if (excerpt) {
    return {
      kind: "NOT_FOUND",
      statusText: "已打开来源材料，但未找到可唯一定位的原文。",
      scrollTarget: false,
      scrollOptions: null,
    };
  }
  return { kind: "UNAVAILABLE", statusText: "暂无可靠来源定位", scrollTarget: false, scrollOptions: null };
}
