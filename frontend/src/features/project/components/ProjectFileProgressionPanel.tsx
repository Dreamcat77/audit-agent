import type { ContractFactExtractionProjection, ProjectFile } from "../types";
import { projectFileProgressionPresentation } from
  "../projectProgressionPresentation";
import { factExtractionPresentation } from "../factExtractionPresentation.ts";

export function ProjectFileProgressionPanel({ file, factExtraction }:
  { file: ProjectFile; factExtraction?: ContractFactExtractionProjection }) {
  const view = projectFileProgressionPresentation(file.progression);
  const extraction = factExtractionPresentation(file.effectiveRole ?? "UNKNOWN", factExtraction);
  return <div className="mt-3 rounded border border-blue-100 bg-blue-50 p-3 text-xs text-slate-700">
    <p className="font-medium text-blue-950">{view.statusLabel}</p>
    {view.detailLabel && <p className="mt-1 text-slate-600">{view.detailLabel}</p>}
    {file.suggestedRole && <p className="mt-1">系统识别材料：{file.suggestedRole === "CONTRACT_BODY" ? "合同正文" : file.suggestedRole === "AWARD_NOTICE" ? "中标通知书" : file.suggestedRole === "TENDER_DOCUMENT" ? "采购/招标文件" : file.suggestedRole === "BID_RESPONSE" ? "投标/响应文件" : file.suggestedRole === "SUPPLEMENT" ? "补充材料" : "其他材料"}
      {file.roleConfidence != null ? `（置信度 ${Math.round(file.roleConfidence * 100)}%）` : ""}</p>}
    {extraction && <div className="mt-2 rounded border border-blue-200 bg-white p-2">
      <p className="font-medium">{extraction.statusLabel}</p>
      <p className="mt-1 text-slate-600">{extraction.countLabel}</p>
      <ul className="mt-1 space-y-0.5 text-slate-600">
        {extraction.scopes.map((scope) => <li key={scope.scope}>{scope.label}：{scope.statusLabel}</li>)}
      </ul>
    </div>}
  </div>;
}
