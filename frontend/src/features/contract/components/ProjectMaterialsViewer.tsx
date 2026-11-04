import { Download, FileText, LocateFixed } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { useAuthenticatedBlobPreview } from "../useAuthenticatedBlobPreview";
import type { FindingSourceAnchor } from "../types";
import type { ProjectBackedReviewMaterial, ProjectBackedReviewMaterialTab } from "../workspaceTypes";
import { authenticatedInlinePreviewKind } from "../authenticatedPreviewPresentation";
import { selectProjectMaterialSource } from "../projectMaterialSourceSelection";
import { locateProjectMaterialText, projectMaterialContentPresentation,
  projectBackedMaterialControlsPresentation, projectMaterialFileOnlyNotice,
  scrollToProjectMaterialLocation } from "../projectMaterialViewerPresentation";

interface Props {
  tabs: readonly ProjectBackedReviewMaterialTab[];
  focusedSource: FindingSourceAnchor | null;
  focusedMaterialId?: string | null;
  focusedMaterialLocatorUnavailable?: boolean;
  onClearSource: () => void;
}

type ViewerMode = "MATERIAL" | "LOCATION";

function firstMaterial(tabs: readonly ProjectBackedReviewMaterialTab[]): ProjectBackedReviewMaterial | null {
  return tabs.find((tab) => tab.files.length > 0)?.files[0] ?? null;
}

function ParsedDocumentText({ text }: { text: string }) {
  return <div className="space-y-3 select-text text-[15px] leading-[1.85] text-slate-800">
    {text.split(/\r?\n/).map((paragraph, index) => (
      <p key={index} className="min-h-[1.85em] whitespace-pre-wrap break-words">{paragraph || "\u00a0"}</p>
    ))}
  </div>;
}

export default function ProjectMaterialsViewer({ tabs, focusedSource, focusedMaterialId = null,
  focusedMaterialLocatorUnavailable = false, onClearSource }: Props) {
  const controls = projectBackedMaterialControlsPresentation();
  const initial = firstMaterial(tabs);
  const [activeTabKey, setActiveTabKey] = useState(
    tabs.find((tab) => tab.files.length > 0)?.key ?? tabs[0]?.key ?? "CONTRACT",
  );
  const [activeFileId, setActiveFileId] = useState<string | null>(initial?.id ?? null);
  const [mode, setMode] = useState<ViewerMode>("MATERIAL");
  const scrollTargetRef = useRef<HTMLElement>(null);

  useEffect(() => {
    const selection = selectProjectMaterialSource(tabs, focusedSource);
    if (selection) {
      setActiveTabKey(selection.tabKey as ProjectBackedReviewMaterialTab["key"]);
      setActiveFileId(selection.fileId);
      setMode(selection.viewerMode);
      return;
    }
    if (!focusedMaterialId) return;
    const owner = tabs.find((tab) => tab.files.some((file) => file.id === focusedMaterialId));
    if (!owner) return;
    setActiveTabKey(owner.key);
    setActiveFileId(focusedMaterialId);
    setMode("MATERIAL");
  }, [focusedMaterialId, focusedSource, tabs]);

  const activeTab = tabs.find((tab) => tab.key === activeTabKey) ?? tabs[0];
  const activeFile = activeTab?.files.find((file) => file.id === activeFileId)
    ?? activeTab?.files[0]
    ?? null;
  const content = activeFile ? projectMaterialContentPresentation(activeFile) : null;
  const declaredInline = activeFile
    ? authenticatedInlinePreviewKind(activeFile.mediaType, activeFile.mediaType)
    : null;
  const sourceSelection = selectProjectMaterialSource(tabs, focusedSource);
  const sourceMatches = Boolean(sourceSelection && activeFile && sourceSelection.fileId === activeFile.id);
  const location = useMemo(() => locateProjectMaterialText(
    sourceMatches ? activeFile?.parsedText ?? null : null,
    sourceMatches && focusedSource ? {
      excerpt: focusedSource.excerpt,
      paragraphIndex: focusedSource.paragraphIndex,
    } : null,
  ), [activeFile?.parsedText, focusedSource, sourceMatches]);
  const pdfLocation = mode === "LOCATION" && sourceMatches && declaredInline === "PDF"
    && focusedSource?.pageNumber != null;
  const needsInline = Boolean(activeFile && (
    mode === "MATERIAL" && content?.kind === "INLINE" || pdfLocation
  ));
  const preview = useAuthenticatedBlobPreview(
    needsInline && activeFile ? activeFile.originalUrl : null,
    activeFile?.mediaType ?? "application/octet-stream",
  );
  const kind = activeFile && preview.objectUrl
    ? authenticatedInlinePreviewKind(activeFile.mediaType, preview.contentType)
    : declaredInline;
  const previewUrl = preview.objectUrl && kind === "PDF" && focusedSource?.pageNumber
    ? `${preview.objectUrl}#page=${focusedSource.pageNumber}`
    : preview.objectUrl;
  const fileChoices = useMemo(() => activeTab?.files ?? [], [activeTab]);

  useEffect(() => {
    if (mode !== "LOCATION" || !sourceMatches || !location.scrollTarget) return;
    scrollToProjectMaterialLocation(scrollTargetRef.current, location);
  }, [activeFile?.id, location, mode, sourceMatches]);

  return <section className="flex h-full min-h-0 flex-col bg-slate-100">
    <div className="shrink-0 border-b border-slate-200 bg-white">
      <div className="flex items-center justify-between gap-3 border-b border-slate-100 px-3 py-2 text-xs">
        <span className="font-medium text-emerald-700">{controls.sourceLabel}</span>
        <span className="text-slate-500">{controls.supplementalGuidance}</span>
      </div>
      <nav className="flex items-center gap-1 overflow-x-auto px-3 py-2" aria-label="项目审核材料">
        {tabs.map((tab) => <button key={tab.key} type="button" onClick={() => {
          setActiveTabKey(tab.key); setActiveFileId(tab.files[0]?.id ?? null); setMode("MATERIAL"); onClearSource();
        }} className={`shrink-0 rounded-md border px-3 py-2 text-[13px] ${tab.key === activeTabKey
          ? "border-blue-200 bg-blue-50 text-blue-800 shadow-[inset_0_-2px_#2563eb]"
          : "border-transparent text-slate-600 hover:border-slate-200 hover:bg-slate-50"}`}>
          {tab.label}<span className="ml-1 text-xs text-slate-400">{tab.files.length}</span>
        </button>)}
      </nav>
      {fileChoices.length > 1 && <div className="flex gap-1 overflow-x-auto border-t border-slate-100 px-3 py-2" aria-label="当前材料文件">
        {fileChoices.map((file) => <button key={file.id} type="button" onClick={() => {
          setActiveFileId(file.id); setMode("MATERIAL"); onClearSource();
        }} className={`shrink-0 rounded border px-2.5 py-1.5 text-xs ${file.id === activeFile?.id
          ? "border-blue-200 bg-blue-50 text-blue-800" : "border-slate-200 text-slate-600"}`}>
          {file.fileName}
        </button>)}
      </div>}
      <div className="flex items-center justify-between gap-3 border-t border-slate-100 px-3 py-2">
        <div className="inline-flex rounded-md border border-slate-200 bg-slate-50 p-0.5">
          <button type="button" onClick={() => setMode("MATERIAL")} className={`rounded px-3 py-1.5 text-[13px] ${mode === "MATERIAL" ? "bg-white text-blue-700 shadow-sm" : "text-slate-600"}`}>材料文本</button>
          <button type="button" disabled={!sourceMatches} onClick={() => setMode("LOCATION")}
            className={`rounded px-3 py-1.5 text-[13px] disabled:cursor-not-allowed disabled:opacity-40 ${mode === "LOCATION" ? "bg-white text-blue-700 shadow-sm" : "text-slate-600"}`}>文本定位</button>
        </div>
        <div className="flex min-w-0 items-center gap-3">
          {activeFile && <span className="truncate text-xs text-slate-500" title={activeFile.fileName}>{activeFile.fileName} · {activeFile.statusLabel}</span>}
          {activeFile && <a href={activeFile.originalUrl} download
            className="inline-flex shrink-0 items-center gap-1.5 rounded border border-blue-200 bg-blue-50 px-2.5 py-1.5 text-xs text-blue-700">
            <Download size={13}/>下载原件
          </a>}
        </div>
      </div>
      {mode === "LOCATION" && sourceMatches && focusedSource && <div className={`border-t px-3 py-2 text-xs ${location.scrollTarget ? "border-amber-200 bg-amber-50 text-amber-950" : "border-slate-200 bg-slate-50 text-slate-600"}`} role="status">
        <p className="flex items-center gap-1.5 font-semibold"><LocateFixed size={14}/>{location.statusText}</p>
        {location.kind !== "UNAVAILABLE" && <p className="mt-1 break-words">{activeFile?.fileName}{focusedSource.pageNumber != null ? ` · 第 ${focusedSource.pageNumber} 页` : ""}</p>}
      </div>}
      {mode === "MATERIAL" && focusedMaterialLocatorUnavailable && focusedMaterialId === activeFile?.id && (
        <div className="border-t border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-700" role="status">
          {projectMaterialFileOnlyNotice()}
        </div>
      )}
    </div>

    <div className="min-h-0 flex-1 overflow-auto p-4">
      {!activeFile && <div className="flex min-h-80 flex-col items-center justify-center text-sm text-slate-500"><FileText size={24}/><p className="mt-3">当前分类暂无材料</p></div>}

      {activeFile && mode === "MATERIAL" && content?.kind === "PARSED_TEXT" && <article className="mx-auto min-h-full max-w-[860px] rounded border border-slate-200 bg-white px-10 py-9 shadow-sm">
        <header className="mb-6 border-b border-slate-100 pb-3">
          <h3 className="text-center text-lg font-semibold text-slate-900">{activeFile.fileName}</h3>
          <p className="mt-2 text-center text-xs text-slate-500">{content.provenanceNotice}</p>
        </header>
        <ParsedDocumentText text={content.text}/>
      </article>}

      {activeFile && mode === "LOCATION" && !pdfLocation && location.kind === "EXACT" && <article className="mx-auto min-h-full max-w-[860px] rounded border border-slate-200 bg-white p-8 shadow-sm">
        <pre className="whitespace-pre-wrap break-words font-sans text-[15px] leading-[1.85] text-slate-800">{location.before}<mark ref={scrollTargetRef} className="rounded bg-amber-300 px-0.5 text-slate-950 ring-2 ring-amber-400/60">{location.exactMatch}</mark>{location.after}</pre>
      </article>}

      {activeFile && mode === "LOCATION" && !pdfLocation && location.kind === "PARAGRAPH" && <article className="mx-auto min-h-full max-w-[860px] space-y-3 rounded border border-slate-200 bg-white p-8 shadow-sm text-[15px] leading-[1.85] text-slate-800">
        {location.paragraphs.map((paragraph, index) => index === location.paragraphArrayIndex
          ? <mark key={index} ref={scrollTargetRef} className="block rounded bg-amber-200 px-2 py-1 text-slate-950 ring-2 ring-amber-400/50">{paragraph || "\u00a0"}</mark>
          : <p key={index} className="min-h-[1.85em] whitespace-pre-wrap break-words">{paragraph || "\u00a0"}</p>)}
      </article>}

      {activeFile && mode === "LOCATION" && !pdfLocation && location.kind === "UNAVAILABLE" && <div className="flex min-h-80 items-center justify-center text-sm text-slate-500">暂无可靠来源定位</div>}

      {activeFile && mode === "LOCATION" && !pdfLocation && location.kind === "AMBIGUOUS" && <div className="flex min-h-80 items-center justify-center px-6 text-center text-sm text-slate-600">已打开来源材料，但存在多个相同值，无法唯一高亮。</div>}

      {activeFile && mode === "LOCATION" && !pdfLocation && location.kind === "NOT_FOUND" && <div className="flex min-h-80 items-center justify-center px-6 text-center text-sm text-slate-600">已打开来源材料，但未找到可唯一定位的原文。</div>}

      {activeFile && needsInline && preview.loading && <p className="text-sm text-slate-500">正在安全读取原件…</p>}
      {activeFile && needsInline && preview.error && <p role="alert" className="text-sm text-amber-800">{preview.error}</p>}
      {activeFile && needsInline && kind === "PDF" && previewUrl && <iframe title={`${activeFile.fileName} 原件`} src={previewUrl} className="h-full min-h-[520px] w-full rounded border border-slate-200 bg-white"/>}
      {activeFile && needsInline && kind === "IMAGE" && previewUrl && <img src={previewUrl} alt={`${activeFile.fileName} 原件`} className="mx-auto max-h-full max-w-full rounded border border-slate-200"/>}

      {activeFile && mode === "MATERIAL" && content?.kind === "DOWNLOAD_ONLY" && <div className="flex min-h-80 flex-col items-center justify-center text-center text-sm text-slate-600">
        <p>当前材料没有可证明的页面内文本，原件可认证下载。</p>
        <a href={activeFile.originalUrl} download className="mt-3 inline-flex items-center gap-1.5 rounded border border-blue-200 bg-blue-50 px-3 py-2 text-blue-700"><Download size={14}/>下载原件</a>
      </div>}
    </div>
  </section>;
}
