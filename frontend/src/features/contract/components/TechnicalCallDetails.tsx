import type { ContractTextTechnicalDetails } from "../contractTextAnalysisPresentation";

export function TechnicalCallDetails({
  details,
}: {
  details: ContractTextTechnicalDetails | null;
}) {
  if (!details) return null;
  return (
    <details className="mt-3 rounded-lg border border-slate-200 bg-slate-50 text-xs text-slate-600">
      <summary className="cursor-pointer px-3 py-2 font-medium text-slate-700">
        技术调用详情
      </summary>
      <dl className="grid grid-cols-[6rem_minmax(0,1fr)] gap-y-1.5 border-t border-slate-200 px-3 py-3">
        <dt>AI 模型</dt><dd className="break-words text-slate-800">{details.model}</dd>
        <dt>调用耗时</dt><dd className="text-slate-800">{details.duration}</dd>
        <dt>Request ID</dt><dd className="break-all text-slate-800">{details.requestId}</dd>
        <dt>选择材料</dt><dd className="text-slate-800">{details.selectedFileCount} 份</dd>
      </dl>
    </details>
  );
}
