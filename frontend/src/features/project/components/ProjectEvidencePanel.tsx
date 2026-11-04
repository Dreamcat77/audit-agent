import { FileText, X } from "lucide-react";
import { forwardRef } from "react";
import { useAuthenticatedBlobPreview } from "../../contract/useAuthenticatedBlobPreview";
import type { ProjectDataSource } from "../data/projectDataSource";
import { authenticatedInlinePreviewKind, PROJECT_EVIDENCE_PANEL_ACCESSIBILITY,
  projectSourceView } from "../projectSourceSelection";
import type { ProjectFile, SelectedProjectSource } from "../types";

interface ProjectEvidencePanelProps {
  source: SelectedProjectSource;
  files: ProjectFile[];
  dataSource: ProjectDataSource;
  onClose: () => void;
}

export const ProjectEvidencePanel = forwardRef<HTMLElement, ProjectEvidencePanelProps>(
function ProjectEvidencePanel({ source, files, dataSource, onClose }, ref) {
  const file = files.find((candidate) => candidate.id === source.fileId
    && candidate.projectId === source.projectId) ?? null;
  const view = file ? projectSourceView(source, file) : null;
  const originalUrl = file ? dataSource.originalContentUrl(source.projectId, source.fileId) : null;
  const preview = useAuthenticatedBlobPreview(
    view?.previewMode === "INLINE" ? originalUrl : null,
    file?.mediaType ?? "application/octet-stream",
  );
  const previewKind = preview.objectUrl && file
    ? authenticatedInlinePreviewKind(file.mediaType, preview.contentType) : null;
  const previewUrl = preview.objectUrl && previewKind === "PDF" && source.pageNumber
    ? `${preview.objectUrl}#page=${source.pageNumber}` : preview.objectUrl;

  return <section ref={ref} tabIndex={PROJECT_EVIDENCE_PANEL_ACCESSIBILITY.tabIndex}
    aria-labelledby={PROJECT_EVIDENCE_PANEL_ACCESSIBILITY.headingId}
    className="mt-6 rounded-lg border border-blue-200 bg-white p-5">
    <div className="flex items-center justify-between gap-3">
      <h2 id={PROJECT_EVIDENCE_PANEL_ACCESSIBILITY.headingId} className="font-semibold text-slate-900">已定位来源</h2>
      <button type="button" onClick={onClose} aria-label="关闭项目证据" className="text-slate-500"><X size={16}/></button>
    </div>
    {!file || !view ? <p role="alert" className="mt-3 text-sm text-amber-800">来源文件不在当前项目的 durable 文件列表中。</p> : <>
      <dl className="mt-3 grid grid-cols-[7rem_minmax(0,1fr)] gap-y-2 text-xs">
        <dt className="text-slate-500">文件</dt><dd>{view.fileName}</dd>
        {view.pageNumber != null && <><dt className="text-slate-500">页码</dt><dd>第 {view.pageNumber} 页</dd></>}
        {view.paragraphIndex != null && <><dt className="text-slate-500">段落</dt><dd>第 {view.paragraphIndex} 段</dd></>}
        <dt className="text-slate-500">定位</dt><dd className="break-words">{view.locator}</dd>
      </dl>
      <div className="mt-3 rounded bg-amber-50 p-3"><p className="text-xs font-medium text-amber-900">摘录</p>
        <p className="mt-1 whitespace-pre-wrap break-words text-xs text-slate-700">{view.excerpt}</p></div>
      <div className="mt-3 rounded bg-slate-50 p-3"><p className="text-xs font-medium text-slate-700">提取文本</p>
        {view.parsedText ? <pre className="mt-1 whitespace-pre-wrap select-text text-xs">{view.parsedText}</pre>
          : <p className="mt-1 text-xs text-slate-500">暂无提取文本</p>}</div>
      <div className="mt-3">
        <a href={originalUrl ?? undefined} download className="inline-flex items-center gap-1 text-xs font-medium text-blue-700">
          <FileText size={13}/>下载原件
        </a>
      </div>
      {view.previewMode === "INLINE" && preview.loading && <p className="mt-3 text-xs text-slate-500">正在安全读取原件…</p>}
      {view.previewMode === "INLINE" && preview.error && <p role="alert" className="mt-3 text-xs text-amber-800">{preview.error}</p>}
      {view.previewMode === "INLINE" && previewUrl && previewKind === "PDF" &&
        <iframe title={`${view.fileName} 原件`} src={previewUrl} className="mt-3 h-[32rem] w-full rounded border border-slate-200"/>}
      {view.previewMode === "INLINE" && previewUrl && previewKind === "IMAGE" &&
        <img src={previewUrl} alt={`${view.fileName} 原件`} className="mt-3 max-h-[32rem] max-w-full rounded border border-slate-200"/>}
      {view.previewMode === "INLINE" && preview.objectUrl && !previewKind &&
        <p role="alert" className="mt-3 text-xs text-amber-800">原件响应类型与可安全预览类型不一致，仅保留认证下载。</p>}
      {view.previewMode === "DOWNLOAD" && <p className="mt-2 text-xs text-slate-500">该文件类型仅支持认证下载，不在页面内执行或预览。</p>}
    </>}
  </section>;
});
