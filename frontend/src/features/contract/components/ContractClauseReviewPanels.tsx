import { AlertTriangle, FileSearch, LoaderCircle, Scale } from "lucide-react";
import type { ContractTextMaterialReference } from "../contractTextAnalysisPresentation";
import type { BreachClauseReview, ClauseComparison, ContractTermClauseReview } from "../types";

function amount(value: number | null): string {
  return value == null ? "材料依据不足" : `${value.toFixed(2)} 元`;
}

const frequencyLabel = { DAY: "天", WEEK: "周", MONTH: "月", EVENT: "次", ONCE: "次" } as const;
const dateLabel = {
  SIGNING_DATE: "签约日期", EFFECTIVE_DATE: "生效日期", PERFORMANCE_START_DATE: "履约开始日期",
  DELIVERY_DEADLINE: "交付截止", TERMINATION_DATE: "终止日期",
} as const;

function ClauseComparisons({ comparisons = [], onSourceOpen }: {
  comparisons?: readonly ClauseComparison[];
  onSourceOpen: (fileId: string, excerpt: string) => void;
}) {
  if (comparisons.length === 0) return null;
  return <div className="mt-3 space-y-2">{comparisons.map((comparison) => (
    <article key={comparison.key} className={`rounded-lg border p-3 text-xs ${comparison.status === "NORMAL"
      ? "border-emerald-200 bg-emerald-50/60" : "border-amber-200 bg-amber-50/70"}`}>
      <div className="flex flex-wrap items-center gap-2">
        <strong>{comparison.label}</strong>
        <span className={comparison.status === "NORMAL" ? "text-emerald-700" : "text-amber-800"}>
          {comparison.status === "NORMAL" ? "材料约定一致" : comparison.status === "REVIEW" ? "材料约定不一致" : "材料依据不足"}
        </span>
      </div>
      <p className="mt-1 text-slate-700">{comparison.message}</p>
      <div className="mt-2 flex flex-wrap items-center gap-2">
        <span className="font-medium">对比来源：</span>
        {comparison.sources.map((source) => <button key={`${comparison.key}-${source.fileId}`} type="button"
          onClick={() => onSourceOpen(source.fileId, source.excerpt)} className="inline-flex items-center gap-1 text-blue-700 hover:underline">
          <FileSearch size={11}/>{source.fileName}
        </button>)}
      </div>
    </article>
  ))}</div>;
}

export function BreachClauseReviewPanel({ result, loading, error, materials, onSourceOpen }: {
  result: BreachClauseReview | null;
  loading: boolean;
  error: string | null;
  materials: readonly ContractTextMaterialReference[];
  onSourceOpen: (fileId: string, excerpt: string) => void;
}) {
  const materialNames = new Map(materials.map((material) => [material.id, material.fileName]));
  return <section className="rounded-xl border border-amber-200 bg-white p-4">
    <h3 className="flex items-center gap-2 text-sm font-semibold"><Scale size={16} className="text-amber-700"/>4.2 违约责任条款审查</h3>
    {loading && <p className="mt-3 text-xs text-slate-600"><LoaderCircle size={13} className="mr-1 inline animate-spin"/>正在读取并核对合同条款……</p>}
    {error && <p role="alert" className="mt-3 text-xs text-amber-800">{error}</p>}
    {result?.materialInsufficient && <p className="mt-3 text-xs text-amber-800">合同正文材料依据不足，建议补充。</p>}
    <div className="mt-3 space-y-3">{result?.items.map((item, index) => <article key={`${item.sourceFileId}-${index}`} className="rounded-lg bg-amber-50/60 p-3 text-xs text-slate-700">
      <h4 className="font-semibold text-slate-900">{item.scenario}</h4>
      <dl className="mt-2 grid grid-cols-[7rem_minmax(0,1fr)] gap-y-1.5">
        <dt>约定比例</dt><dd>{item.rate}{item.rateUnit === "PER_MILLE" ? "‰" : "%"} / {item.frequency === null ? "频次未能精确识别" : frequencyLabel[item.frequency]}</dd>
        <dt>计算基数</dt><dd>{item.calculationBasis === "CONTRACT_AMOUNT" ? "合同金额" : "材料约定的其他基数"}</dd>
        <dt>对应金额</dt><dd>{amount(item.correspondingAmount)}{item.frequency === null ? "" : ` / ${frequencyLabel[item.frequency]}`}</dd>
        <dt>累计上限</dt><dd>{item.capRate == null || item.capRateUnit == null
          ? "未明确"
          : `${item.capRate}${item.capRateUnit === "PER_MILLE" ? "‰" : "%"}（${amount(item.capAmount)}）`}</dd>
        <dt>实际累计金额</dt><dd>{amount(item.actualPenaltyAmount)}</dd>
        {item.terminationCondition && <><dt>解除条件</dt><dd>{item.terminationCondition}</dd></>}
      </dl>
      {item.warning && <p className="mt-2 flex gap-1 text-amber-800"><AlertTriangle size={12}/>{item.warning}</p>}
      <div className="mt-3 rounded border border-amber-100 bg-white p-2">
        <p><span className="font-medium">{item.status === "NORMAL" ? "条款来源：" : "建议复核材料："}</span>
          <button type="button" onClick={() => onSourceOpen(item.sourceFileId, item.excerpt)} className="text-blue-700 hover:underline">
            <FileSearch size={12} className="mr-1 inline"/>{materialNames.get(item.sourceFileId) ?? "合同正文"}
          </button>
        </p>
        {item.status !== "NORMAL" && <p className="mt-1"><span className="font-medium">问题材料：</span>{materialNames.get(item.sourceFileId) ?? "合同正文"}</p>}
        <p className="mt-1 break-words"><span className="font-medium">原文摘录：</span>{item.excerpt}</p>
      </div>
    </article>)}</div>
    <ClauseComparisons comparisons={result?.comparisons} onSourceOpen={onSourceOpen}/>
    {result && !result.materialInsufficient && result.items.length === 0 && <p className="mt-3 text-xs text-slate-600">未识别到可量化的违约比例，建议结合合同原文复核。</p>}
  </section>;
}

export function ContractTermClauseReviewPanel({ result, loading, error, materials, onSourceOpen }: {
  result: ContractTermClauseReview | null;
  loading: boolean;
  error: string | null;
  materials: readonly ContractTextMaterialReference[];
  onSourceOpen: (fileId: string, excerpt: string) => void;
}) {
  const materialNames = new Map(materials.map((material) => [material.id, material.fileName]));
  return <section className="rounded-xl border border-cyan-200 bg-white p-4">
    <h3 className="text-sm font-semibold">4.3 合同期限与终止条件审查</h3>
    {loading && <p className="mt-3 text-xs text-slate-600"><LoaderCircle size={13} className="mr-1 inline animate-spin"/>正在读取并核对合同条款……</p>}
    {error && <p role="alert" className="mt-3 text-xs text-amber-800">{error}</p>}
    {result?.materialInsufficient && <p className="mt-3 text-xs text-amber-800">合同正文材料依据不足，建议补充。</p>}
    <div className="mt-3 space-y-2">{result?.dates.map((item, index) => <button key={`date-${index}`} type="button" onClick={() => onSourceOpen(item.sourceFileId, item.excerpt)} className="block w-full rounded bg-cyan-50/60 p-3 text-left text-xs">
      <strong>{dateLabel[item.type]}</strong><span className="ml-3">{item.normalizedDate ?? "未填写"}</span>
      <span className={`ml-2 ${item.status === "NORMAL" ? "text-emerald-700" : "text-amber-800"}`}>
        {item.status === "NORMAL" ? "正常" : "建议补充"}
      </span>
      <span className="mt-2 block"><strong>{item.status === "NORMAL" ? "条款来源：" : "建议复核材料："}</strong>{materialNames.get(item.sourceFileId) ?? "合同正文"}</span>
      {item.status !== "NORMAL" && <span className="mt-1 block"><strong>问题材料：</strong>{materialNames.get(item.sourceFileId) ?? "合同正文"}</span>}
      <span className="mt-1 block break-words"><strong>原文摘录：</strong>{item.excerpt}</span>
    </button>)}</div>
    <div className="mt-3 space-y-2">{result?.conditions.map((item, index) => <button key={`condition-${index}`} type="button" onClick={() => onSourceOpen(item.sourceFileId, item.excerpt)} className="block w-full rounded bg-slate-50 p-3 text-left text-xs">
      <strong>{item.type === "EFFECTIVE_EVENT" ? "生效条件" : "解除/终止条件"}</strong><span className="ml-3">{item.description}</span><span className="ml-2 text-emerald-700">已识别</span>
      <span className="mt-2 block"><strong>{item.status === "NORMAL" ? "条款来源：" : "建议复核材料："}</strong>{materialNames.get(item.sourceFileId) ?? "合同正文"}</span>
      {item.status !== "NORMAL" && <span className="mt-1 block"><strong>问题材料：</strong>{materialNames.get(item.sourceFileId) ?? "合同正文"}</span>}
      <span className="mt-1 block break-words"><strong>原文摘录：</strong>{item.excerpt}</span>
    </button>)}</div>
    <ClauseComparisons comparisons={result?.comparisons} onSourceOpen={onSourceOpen}/>
  </section>;
}
