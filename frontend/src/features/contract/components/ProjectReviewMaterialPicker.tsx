import { AlertTriangle, CheckCircle2, Files } from "lucide-react";

import {
  deriveProjectReviewMaterialSelection,
  type ProjectReviewMaterialSelection,
} from "../projectReviewMaterialSelection";
import type { ProjectBackedReviewMaterialTab } from "../workspaceTypes";

interface ProjectReviewMaterialPickerProps {
  tabs: readonly ProjectBackedReviewMaterialTab[];
  projectId: string;
  selectedFileIds: readonly string[];
  onSelectedFileIdsChange: (fileIds: readonly string[]) => void;
  disabled?: boolean;
}

export function ProjectReviewMaterialPicker({
  tabs,
  projectId,
  selectedFileIds,
  onSelectedFileIdsChange,
  disabled = false,
}: ProjectReviewMaterialPickerProps) {
  const selection = deriveProjectReviewMaterialSelection(tabs, projectId, selectedFileIds);
  const selected = new Set(selection.selectedFileIds);
  const eligible = new Set(selection.eligibleFileIds);
  const completenessLabel = { UPLOADED: "已上传", MISSING: "待补充", UNREADABLE: "无法读取", OPTIONAL: "可选" } as const;

  const updateSelection = (fileId: string, checked: boolean) => {
    const requested = checked
      ? [...selection.selectedFileIds, fileId]
      : selection.selectedFileIds.filter((selectedId) => selectedId !== fileId);
    onSelectedFileIdsChange(
      deriveProjectReviewMaterialSelection(tabs, projectId, requested).selectedFileIds,
    );
  };

  return (
    <section className="rounded-xl border border-slate-200 bg-white p-4" aria-labelledby="review-material-picker-title">
      <div className="mb-3 flex items-start justify-between gap-3">
        <div>
          <h3 id="review-material-picker-title" className="flex items-center gap-2 text-sm font-semibold text-slate-900">
            <Files size={16} className="text-blue-700" /> 本次智能复核材料
          </h3>
          <p className="mt-1 text-xs text-slate-500">
            {disabled
              ? "本次审核材料已确认。如需更换材料，请重新发起一次审核。"
              : "默认选择本项目全部已处理材料；未就绪材料不会进入本次请求。"}
          </p>
        </div>
        <span className="shrink-0 rounded-full bg-slate-100 px-2.5 py-1 text-xs text-slate-600">
          已选 {selection.selectedFileIds.length}/{selection.eligibleFileIds.length}
        </span>
      </div>

      <div className="mb-4 rounded-lg border border-slate-200 bg-slate-50 p-3">
        <p className="mb-2 text-xs font-semibold text-slate-800">材料完整性</p>
        <div className="grid gap-2 sm:grid-cols-2 xl:grid-cols-5">
          {selection.completeness.map((item) => <div key={item.key} className="flex items-center justify-between gap-2 rounded bg-white px-2.5 py-2 text-xs">
            <span className="text-slate-700">{item.label}</span>
            <span className={item.status === "UPLOADED" ? "text-emerald-700" : item.status === "OPTIONAL" ? "text-slate-500" : item.status === "MISSING" ? "text-amber-700" : "text-red-700"}>
              {completenessLabel[item.status]}
            </span>
          </div>)}
        </div>
      </div>

      <div className="space-y-2">
        {tabs.map((tab) => {
          const materials = tab.files.filter((material) => eligible.has(material.id));
          if (materials.length === 0) return null;
          return (
            <div key={tab.key} className="rounded-lg border border-slate-100 bg-slate-50/70 p-3">
              <p className="mb-2 text-xs font-medium text-slate-700">{tab.label}</p>
              <div className="space-y-2">
                {materials.map((material) => (
                  <label key={material.id} className="flex cursor-pointer items-start gap-2.5 text-xs text-slate-700">
                    <input
                      type="checkbox"
                      className="mt-0.5 h-4 w-4 rounded border-slate-300 text-blue-700"
                      checked={selected.has(material.id)}
                      disabled={disabled}
                      onChange={(event) => updateSelection(material.id, event.currentTarget.checked)}
                    />
                    <span className="min-w-0 flex-1">
                      <span className="block truncate font-medium text-slate-800">{material.fileName}</span>
                      <span className="mt-0.5 flex items-center gap-1 text-slate-500">
                        <CheckCircle2 size={12} className="text-emerald-600" /> {material.statusLabel}
                      </span>
                    </span>
                  </label>
                ))}
              </div>
            </div>
          );
        })}
        {selection.eligibleFileIds.length === 0 && (
          <p className="rounded-lg bg-slate-50 px-3 py-3 text-xs text-slate-600">
            暂无已完成处理且可用于复核的项目材料。
          </p>
        )}
      </div>

      <SelectionNotice selection={selection} />
    </section>
  );
}

function SelectionNotice({ selection }: { selection: ProjectReviewMaterialSelection }) {
  const warnings = [selection.warningMessage, selection.duplicateWarningMessage]
    .filter((message): message is string => Boolean(message));
  return (
    <>
      {selection.blockingMessage && (
        <p className="mt-3 flex items-start gap-2 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-800" role="alert">
          <AlertTriangle size={14} className="mt-0.5 shrink-0" /> {selection.blockingMessage}
        </p>
      )}
      {warnings.map((warning) => (
        <p key={warning} className="mt-3 flex items-start gap-2 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800">
          <AlertTriangle size={14} className="mt-0.5 shrink-0" /> {warning}
        </p>
      ))}
    </>
  );
}
