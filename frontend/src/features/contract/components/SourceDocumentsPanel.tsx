import { useEffect, useRef } from "react";
import { FileText, LocateFixed, Trash2, X } from "lucide-react";
import type {
  DocumentViewerMode,
  EvidenceAnchor,
  ReviewDocumentKind,
  ReviewDocumentUiState,
} from "../workspaceTypes";
import AddReviewMaterial from "./AddReviewMaterial";

interface DocumentViewerPaneProps {
  documents: ReviewDocumentUiState[];
  activeDocument: ReviewDocumentUiState;
  activeDocumentId: string;
  activeObjectUrl: string | null;
  viewerMode: DocumentViewerMode;
  activeEvidence: EvidenceAnchor | null;
  mockText: string | null;
  mockMode: boolean;
  readOnly?: boolean;
  onDocumentChange: (clientId: string) => void;
  onViewerModeChange: (mode: DocumentViewerMode) => void;
  onAddFile: (kind: ReviewDocumentKind, file: File) => void;
  onRemoveFile: (clientId: string) => void;
  onClearEvidence: () => void;
}

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function isPdf(document: ReviewDocumentUiState): boolean {
  return document.mimeType === "application/pdf" || Boolean(document.fileName?.toLowerCase().endsWith(".pdf"));
}

function isImage(document: ReviewDocumentUiState): boolean {
  return Boolean(document.mimeType?.startsWith("image/")) || /\.(png|jpe?g)$/i.test(document.fileName ?? "");
}

function HighlightedText({ text, anchor }: { text: string; anchor: EvidenceAnchor | null }) {
  const markRef = useRef<HTMLElement>(null);
  const start = anchor?.startOffset;
  const end = anchor?.endOffset;
  const hasRange = typeof start === "number"
    && typeof end === "number"
    && start >= 0
    && end > start
    && end <= text.length;

  useEffect(() => {
    if (hasRange) markRef.current?.scrollIntoView({ behavior: "smooth", block: "center" });
  }, [anchor, hasRange]);

  if (!hasRange || start === undefined || end === undefined) return <>{text}</>;
  return (
    <>
      <span>{text.slice(0, start)}</span>
      <mark ref={markRef} className="rounded-sm bg-amber-100 px-0.5 text-inherit shadow-[inset_0_-2px_#f59e0b]">
        {text.slice(start, end)}
      </mark>
      <span>{text.slice(end)}</span>
    </>
  );
}

export default function DocumentViewerPane({
  documents,
  activeDocument,
  activeDocumentId,
  activeObjectUrl,
  viewerMode,
  activeEvidence,
  mockText,
  mockMode,
  readOnly = false,
  onDocumentChange,
  onViewerModeChange,
  onAddFile,
  onRemoveFile,
  onClearEvidence,
}: DocumentViewerPaneProps) {
  return (
    <section className="flex h-full min-h-0 flex-col bg-slate-100">
      <div className="shrink-0 border-b border-slate-200 bg-white">
        <div className="flex items-center gap-1.5 px-3 py-2">
          <div className="min-w-0 flex-1 flex items-center gap-1.5 overflow-x-auto no-scrollbar">
            {documents.map((document) => (
              <button
                key={document.clientId}
                type="button"
                onClick={() => onDocumentChange(document.clientId)}
                className={`shrink-0 rounded-md border px-3 py-2 text-[13px] transition-colors ${
                  activeDocumentId === document.clientId
                    ? "border-blue-200 bg-blue-50 text-blue-800 shadow-[inset_0_-2px_#2563eb]"
                    : "border-transparent text-slate-600 hover:border-slate-200 hover:bg-slate-50"
                }`}
              >
                {document.label}
                {document.status === "LOCAL_SELECTED" && <span className="ml-1.5 text-emerald-600">●</span>}
              </button>
            ))}
          </div>
          {!readOnly && <AddReviewMaterial onAddFile={onAddFile} documents={documents} compact />}
        </div>
        <div className="flex items-center justify-between gap-3 border-t border-slate-100 px-3 py-2">
          <div className="inline-flex rounded-md border border-slate-200 bg-slate-50 p-0.5">
            {(["ORIGINAL", "TEXT"] as const).map((mode) => (
              <button
                key={mode}
                type="button"
                onClick={() => onViewerModeChange(mode)}
                className={`rounded px-3 py-1.5 text-[13px] transition-colors ${viewerMode === mode ? "bg-white text-blue-700 shadow-sm" : "text-slate-600 hover:text-slate-900"}`}
              >
                {mockMode
                  ? mode === "ORIGINAL" ? "材料文本" : "文本定位"
                  : mode === "ORIGINAL" ? "原始文件" : "识别文本"}
              </button>
            ))}
          </div>
          <div className="flex items-center gap-2 min-w-0">
            {activeDocument.fileName && (
              <span className="truncate text-xs text-slate-500" title={activeDocument.fileName}>
                {activeDocument.fileName} · {formatFileSize(activeDocument.fileSize ?? 0)}
              </span>
            )}
            {!readOnly && activeDocument.status === "LOCAL_SELECTED" && (
              <button
                type="button"
                onClick={() => onRemoveFile(activeDocument.clientId)}
                className="text-slate-500 transition-colors hover:text-red-700"
                aria-label="移除本地文件"
                title="移除本地文件"
              >
                <Trash2 size={12} />
              </button>
            )}
          </div>
        </div>
        {activeEvidence && (
          <div className="flex items-center justify-between gap-3 border-t border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-900" role="status">
            <span className="inline-flex min-w-0 items-center gap-2 truncate"><LocateFixed size={14} className="shrink-0" />已定位：{activeEvidence.label ?? "原文证据"}</span>
            <button type="button" onClick={onClearEvidence} className="inline-flex shrink-0 items-center gap-1 text-amber-800 hover:text-amber-950" aria-label="取消原文定位"><X size={13} />取消定位</button>
          </div>
        )}
      </div>

      <div className="flex-1 min-h-0 overflow-auto p-4">
        {viewerMode === "ORIGINAL" && activeObjectUrl && isPdf(activeDocument) && (
          <object
            data={activeObjectUrl}
            type="application/pdf"
            className="h-full min-h-[520px] w-full rounded border border-slate-200 bg-white"
            aria-label={`${activeDocument.label} PDF 预览`}
          >
            <p className="p-5 text-sm text-slate-700">当前浏览器无法内嵌预览该 PDF，可使用浏览器下载功能查看。</p>
          </object>
        )}
        {viewerMode === "ORIGINAL" && activeObjectUrl && isImage(activeDocument) && (
          <div className="flex min-h-full items-start justify-center rounded border border-slate-200 bg-white p-3">
            <img src={activeObjectUrl} alt={activeDocument.label} className="max-h-full max-w-full object-contain" />
          </div>
        )}
        {viewerMode === "ORIGINAL" && activeObjectUrl && !isPdf(activeDocument) && !isImage(activeDocument) && (
          <div className="flex h-full min-h-80 items-center justify-center text-center text-sm text-amber-800">
            当前文件类型不支持内嵌预览。
          </div>
        )}
        {viewerMode === "ORIGINAL" && !activeObjectUrl && mockMode && mockText && (
          <div className="mx-auto min-h-full max-w-[860px] rounded border border-slate-200 bg-white p-9 text-slate-800 shadow-sm">
            <div className="mb-6 flex items-center gap-2 text-[13px] font-medium text-blue-700">
              <FileText size={14} /> 材料文本 · 已保存业务信息
            </div>
            <pre className="whitespace-pre-wrap break-words font-sans text-[15px] leading-[1.85]">{mockText}</pre>
          </div>
        )}
        {viewerMode === "ORIGINAL" && !activeObjectUrl && (!mockMode || !mockText) && (
          <div className="h-full min-h-80 flex flex-col items-center justify-center text-center px-6">
            <FileText size={24} className="text-slate-400" />
            <p className="mt-3 text-sm text-slate-900">暂无{activeDocument.label}原件</p>
            <p className="mt-1 text-xs text-slate-600">添加审核材料后可在此查看 PDF 或图片原件。</p>
          </div>
        )}

        {viewerMode === "TEXT" && mockMode && mockText && (
          <div className="mx-auto min-h-full max-w-[860px] rounded border border-slate-200 bg-white p-9 text-slate-800 shadow-sm">
            <div className="mb-6 flex items-center gap-2 text-[13px] font-medium text-blue-700">
              <LocateFixed size={14} /> 材料文本 · 原文定位
            </div>
            <pre className="whitespace-pre-wrap break-words font-sans text-[15px] leading-[1.85] text-slate-800">
              <HighlightedText text={mockText} anchor={activeEvidence} />
            </pre>
          </div>
        )}
        {viewerMode === "TEXT" && (!mockMode || !mockText) && (
          <div className="h-full min-h-80 flex flex-col items-center justify-center text-center px-6">
            <FileText size={24} className="text-slate-400" />
            <p className="mt-3 text-sm text-slate-900">材料解析能力待接入</p>
            <p className="mt-1 text-xs text-slate-600">当前没有可作为审核依据的已读取材料内容。</p>
          </div>
        )}
      </div>
    </section>
  );
}
