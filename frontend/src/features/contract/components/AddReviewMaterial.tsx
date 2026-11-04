import { useRef, useState } from "react";
import { FilePlus2, X } from "lucide-react";
import type { ReviewDocumentKind, ReviewDocumentUiState } from "../workspaceTypes";

interface AddReviewMaterialProps {
  onAddFile: (kind: ReviewDocumentKind, file: File) => void;
  compact?: boolean;
  documents?: ReviewDocumentUiState[];
}

const materialKinds: { kind: ReviewDocumentKind; label: string; detail: string }[] = [
  { kind: "CONTRACT_BODY", label: "合同正文", detail: "最多保留一个本地当前版本" },
  { kind: "AWARD_NOTICE", label: "中标通知书", detail: "最多保留一个本地当前版本" },
  { kind: "BIDDING_DOCUMENT", label: "招标文件", detail: "补充比对来源" },
  { kind: "TENDER_DOCUMENT", label: "投标文件", detail: "允许添加多份" },
  { kind: "OTHER", label: "其他材料", detail: "允许添加多份" },
];

export default function AddReviewMaterial({
  onAddFile,
  compact = false,
  documents = [],
}: AddReviewMaterialProps) {
  const [open, setOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const pendingKindRef = useRef<ReviewDocumentKind | null>(null);

  const chooseKind = (kind: ReviewDocumentKind) => {
    pendingKindRef.current = kind;
    setError(null);
    inputRef.current?.click();
  };

  return (
    <div className="relative shrink-0">
      <button
        type="button"
        onClick={() => setOpen((current) => !current)}
        className={`inline-flex items-center gap-1.5 rounded border border-dashed border-blue-300 text-blue-700 transition-colors hover:bg-blue-50 ${compact ? "px-2 py-1 text-xs" : "px-3 py-2 text-[13px]"}`}
      >
        <FilePlus2 size={compact ? 11 : 13} />
        添加材料
      </button>
      <input
        ref={inputRef}
        type="file"
        accept=".pdf,.png,.jpg,.jpeg,application/pdf,image/png,image/jpeg"
        className="hidden"
        onChange={(event) => {
          const file = event.target.files?.[0];
          const kind = pendingKindRef.current;
          event.target.value = "";
          if (!file || !kind) return;
          try {
            onAddFile(kind, file);
            setOpen(false);
            setError(null);
          } catch (selectionError) {
            setError(selectionError instanceof Error ? selectionError.message : "无法选择该文件");
          }
        }}
      />
      {open && (
        <div className="absolute right-0 top-full z-40 mt-2 w-72 overflow-hidden rounded-lg border border-slate-200 bg-white shadow-xl">
          <div className="flex items-center justify-between border-b border-slate-200 px-3 py-2.5">
            <div>
              <p className="text-[13px] font-medium text-slate-900">选择材料类型</p>
              <p className="mt-1 text-xs text-slate-600">支持 PDF、PNG、JPG、JPEG</p>
            </div>
            <button type="button" onClick={() => setOpen(false)} className="text-slate-500 hover:text-slate-900" aria-label="关闭">
              <X size={13} />
            </button>
          </div>
          <div className="p-1.5">
            {materialKinds.map((material) => {
              const replacesSelection = material.kind !== "TENDER_DOCUMENT"
                && material.kind !== "OTHER"
                && documents.some((document) =>
                  document.kind === material.kind && document.status === "LOCAL_SELECTED",
                );
              return (
                <button
                  key={material.kind}
                  type="button"
                  onClick={() => chooseKind(material.kind)}
                  className="w-full rounded px-2.5 py-2 text-left transition-colors hover:bg-slate-50"
                >
                  <span className="block text-[13px] text-slate-900">{material.label}</span>
                  <span className={`mt-1 block text-xs ${replacesSelection ? "text-amber-800" : "text-slate-600"}`}>
                    {replacesSelection ? "将替换现有本地选择" : material.detail}
                  </span>
                </button>
              );
            })}
          </div>
          {error && <p role="alert" className="px-3 pb-3 text-xs text-red-700">{error}</p>}
        </div>
      )}
    </div>
  );
}
