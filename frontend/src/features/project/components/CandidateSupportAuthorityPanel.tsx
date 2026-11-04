import { useEffect, useState } from "react";
import type { CandidateDetail, ProjectFile } from "../types";
import { candidateSupportAuthorityPresentation } from "../candidateSupportAuthorityPresentation";
import { formatProjectMoney } from "../projectDetailViewModel";
import { fieldStateLabel } from "../projectMaterialPresentation";

export function CandidateSupportAuthorityPanel({ candidate, files, loading, pending, error,
  onSave, onReload, onReconcile, reloadVersion = 0 }: {
  candidate: CandidateDetail;
  files: ProjectFile[];
  loading: boolean;
  pending: boolean;
  error: string | null;
  reloadVersion?: number;
  onSave: (fileIds: string[]) => Promise<unknown>;
  onReload: () => Promise<unknown>;
  onReconcile: () => Promise<unknown>;
}) {
  const [selection, setSelection] = useState(() => candidateSupportAuthorityPresentation(candidate, files).assignedIds);
  // Explicit reload and authoritative candidate changes reset edits, not background GET identity.
  useEffect(() => {
    setSelection(candidateSupportAuthorityPresentation(candidate, files).assignedIds);
  }, [candidate.candidate.id, candidate.candidate.versionNo, candidate.supportAuthorityVersionNo, reloadVersion]);
  const view = candidateSupportAuthorityPresentation(candidate, files, selection);
  const locked = loading || pending || !view.editable;
  return <section className="mt-4 rounded-lg border border-slate-200 bg-white p-5" aria-label="关联中标材料">
    <h2 className="font-semibold">关联中标材料</h2>
    <p className="mt-1 text-xs text-slate-600">选择本合同候选采用的全部中标材料。保存后需重新确认合同内容；清空并保存可恢复仅使用合同正文。</p>
    <div className="mt-3 space-y-1 text-sm">
      <p>中标金额：{formatProjectMoney(view.awardAmount)} · {fieldStateLabel(view.amountState)}</p>
      <p>中标供应商：{view.awardSupplier || "待识别"} · {fieldStateLabel(view.supplierState)}</p>
    </div>
    <div className="mt-3 space-y-2">
      {view.rows.map((row) => <label key={row.id} className="flex items-start gap-2 rounded border border-slate-100 p-3 text-sm">
        <input type="checkbox" checked={row.selected} disabled={locked || (!row.eligible && !row.selected)}
          onChange={(event) => setSelection(event.target.checked ? [...selection, row.id] : selection.filter((id) => id !== row.id))}/>
        <span>{row.filename}<span className="ml-2 text-xs text-slate-500">
          {row.effectiveRole === "AWARD_NOTICE" ? "中标通知书" : "当前非中标材料"} · {row.current ? "当前有效" : "关联已失效，需核对"}
          {row.assigned ? " · 已关联" : " · 未关联"}
        </span></span>
      </label>)}
      {view.rows.length === 0 && <p className="text-sm text-slate-500">暂无已确认类型及项目归属的中标材料。</p>}
    </div>
    <p className="mt-2 text-xs text-slate-500">已选择 {view.selectedIds.length} 份材料；保存会替换全部关联。</p>
    {!view.editable && <p className="mt-2 text-sm text-slate-600">已生成正式合同，不能修改关联。</p>}
    {pending && <p role="status" className="mt-2 text-sm text-amber-800">上次关联结果尚未核对，已保留原操作；请核对后再编辑。</p>}
    {error && <p role="alert" className="mt-2 text-sm text-red-700">{error}</p>}
    <div className="mt-3 flex flex-wrap gap-2 text-sm">
      <button type="button" disabled={locked || !view.canSave} onClick={() => void onSave(view.selectedIds)}
        className="rounded bg-blue-600 px-3 py-2 text-white disabled:opacity-50">保存关联</button>
      <button type="button" disabled={locked || !view.canClear} onClick={() => setSelection([])}
        className="rounded border px-3 py-2 disabled:opacity-50">清空选择</button>
      <button type="button" disabled={loading} onClick={() => void onReload()}
        className="rounded border px-3 py-2 disabled:opacity-50">重新加载</button>
      {pending && <button type="button" disabled={loading} onClick={() => void onReconcile()}
        className="rounded border border-amber-300 px-3 py-2 text-amber-800 disabled:opacity-50">核对上次关联结果</button>}
    </div>
  </section>;
}
