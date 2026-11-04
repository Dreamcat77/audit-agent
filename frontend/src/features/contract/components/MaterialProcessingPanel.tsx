import { CheckCircle, Clock3, FileText, LoaderCircle } from "lucide-react";
import type {
  DocumentProcessingStage,
  ReviewDocumentKind,
  ReviewDocumentUiState,
} from "../workspaceTypes";

interface DocumentProcessingProgressProps {
  documents: ReviewDocumentUiState[];
  mockMode: boolean;
  mockStages: Partial<Record<ReviewDocumentKind, DocumentProcessingStage>>;
}

const mockStageLabel: Record<DocumentProcessingStage, string> = {
  WAITING: "等待接收材料",
  RECEIVED: "已接收材料",
  READING: "正在读取文字",
  EXTRACTING: "正在提取关键信息",
  COMPLETED: "材料信息已就绪",
  FAILED: "材料处理失败",
};

function StageIcon({ stage }: { stage: DocumentProcessingStage }) {
  if (stage === "READING" || stage === "EXTRACTING") {
    return <LoaderCircle size={14} className="animate-spin text-blue-700" />;
  }
  if (stage === "COMPLETED") return <CheckCircle size={14} className="text-emerald-700" />;
  return <Clock3 size={14} className="text-slate-500" />;
}

export default function DocumentProcessingProgress({
  documents,
  mockMode,
  mockStages,
}: DocumentProcessingProgressProps) {
  const visibleDocuments = documents.filter(
    (document) => mockMode || document.required || document.status === "LOCAL_SELECTED",
  );

  return (
    <section className="rounded-lg border border-slate-200 bg-white">
      <div className="px-4 py-3 flex items-center justify-between gap-3">
        <div>
          <h3 className="text-[15px] font-semibold text-slate-900">材料解析进度</h3>
          <p className="mt-1 text-xs text-slate-600">
            {mockMode ? "材料读取与关键信息提取过程" : "当前材料上传及解析接口待接入"}
          </p>
        </div>
      </div>
      <div className="px-4 pb-3 space-y-1.5">
        {visibleDocuments.map((document) => {
          const stage = mockMode ? (mockStages[document.kind] ?? "WAITING") : "WAITING";
          const detail = mockMode
            ? mockStageLabel[stage]
            : document.status === "LOCAL_SELECTED"
              ? "已选择本地材料 · 本次尚未上传或解析"
              : "尚未选择材料";
          return (
            <div key={document.clientId} className="flex items-center gap-2 rounded bg-slate-50 px-2.5 py-2">
              {mockMode ? <StageIcon stage={stage} /> : <FileText size={14} className="text-slate-500" />}
              <span className="min-w-0 flex-1 truncate text-xs text-slate-800">{document.label}</span>
              <span className={`text-[11px] ${mockMode && stage === "COMPLETED" ? "text-emerald-700" : "text-slate-500"}`}>{detail}</span>
            </div>
          );
        })}
      </div>
    </section>
  );
}
