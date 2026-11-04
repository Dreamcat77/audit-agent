import { useEffect, useState } from "react";
import type { ProjectMetadataConfirmation } from "../data/projectDataSource";
import { projectMetadataDraft } from "../projectMetadataConfirmation";
import type { ProjectDetail } from "../types";

export function ProjectMetadataConfirmationPanel({ detail, loading, error, conflict, onConfirm, reloadVersion = 0 }: {
  detail: ProjectDetail;
  loading: boolean;
  error: string | null;
  conflict: string | null;
  reloadVersion?: number;
  onConfirm: (payload: ProjectMetadataConfirmation) => Promise<"CONFIRMED" | "CONFLICT" | null>;
}) {
  const [draft, setDraft] = useState(() => projectMetadataDraft(detail));
  // Background GET objects are fresh, but they are not permission to discard local edits.
  useEffect(() => setDraft(projectMetadataDraft(detail)), [detail.project.id, detail.project.versionNo, reloadVersion]);
  const update = (field: "name" | "projectNo" | "category", value: string) =>
    setDraft((current) => ({ ...current, [field]: value }));
  const inputClass = "mt-1 w-full rounded border border-slate-200 px-2 py-1.5 text-sm";
  const submit = () => onConfirm({
    expectedVersion: draft.expectedVersion,
    name: draft.name.trim() || null,
    projectNo: draft.projectNo.trim() || null,
    category: draft.category.trim() || null,
  });

  return <section className="mt-4 rounded border border-slate-200 bg-slate-50 p-4">
    <h2 className="text-sm font-semibold text-slate-900">项目信息确认</h2>
    <p className="mt-1 text-xs text-slate-600">已确认事实优先；无冲突的唯一提取值仅作为建议，类目不会由系统编造。</p>
    <div className="mt-3 grid gap-3 md:grid-cols-3">
      <label className="text-xs text-slate-600">项目名称
        <input className={inputClass} value={draft.name} onChange={(event) => update("name", event.target.value)}/>
      </label>
      <label className="text-xs text-slate-600">项目编号
        <input className={inputClass} value={draft.projectNo} onChange={(event) => update("projectNo", event.target.value)}/>
      </label>
      <label className="text-xs text-slate-600">项目类目
        <input className={inputClass} value={draft.category} placeholder="待提供"
          onChange={(event) => update("category", event.target.value)}/>
      </label>
    </div>
    {conflict && <p role="alert" className="mt-3 text-xs text-amber-800">{conflict}</p>}
    {error && <p role="alert" className="mt-3 text-xs text-red-700">{error}</p>}
    <button type="button" disabled={loading} onClick={() => void submit()}
      className="mt-3 rounded border border-blue-700 bg-blue-700 px-3 py-2 text-xs text-white disabled:opacity-50">
      {loading ? "正在确认项目信息…" : "确认项目信息"}
    </button>
  </section>;
}
