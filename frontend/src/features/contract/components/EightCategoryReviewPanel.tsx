import { Check, ChevronDown, ChevronRight, Copy, FileSearch } from "lucide-react";
import { useMemo, useState } from "react";
import {ResultItem} from "../../../components/results/ResultLayout";

import { browserClipboard, copyText, type ClipboardPort } from "../clipboard.ts";
import { BUSINESS_REVIEW_SCOPE, MONETARY_OBSERVATION_MARKER, buildCategoryOpinion, buildOverallOpinion,
  buildCategorySummaryOpinion, buildOverallSummaryOpinion } from "../contractReviewOpinion.ts";
import { nextOpenCategory } from "../eightCategoryReviewPanelState.ts";
import type {
  ContractReviewCategoryEvidenceView,
  ContractReviewCategoryId,
  ContractReviewCategoryView,
} from "../eightCategoryReviewProjection.ts";
import type { ReviewRunStatus } from "../reviewRunTypes.ts";

interface EightCategoryReviewPanelProps {
  categories: readonly ContractReviewCategoryView[];
  projectInfo?: {
    projectName?: string | null;
    projectNo?: string | null;
    contractName?: string | null;
    contractNo?: string | null;
    supplier?: string | null;
    contractAmount?: string | null;
  };
  runId: string;
  runStatus: ReviewRunStatus;
  initialOpenCategoryId?: ContractReviewCategoryId | null;
  onEvidenceOpen: (evidence: ContractReviewCategoryEvidenceView) => void;
  clipboard?: ClipboardPort;
}

type CopyState = { key: string; succeeded: boolean; value: string; runId: string;
  runStatus: ReviewRunStatus; categories: readonly ContractReviewCategoryView[] } | null;

const judgementClasses = {
  NORMAL: "border-emerald-200 bg-emerald-50 text-emerald-800",
  ATTENTION: "border-rose-200 bg-rose-50 text-rose-800",
  INSUFFICIENT: "border-amber-200 bg-amber-50 text-amber-800",
  UNAVAILABLE: "border-slate-300 bg-slate-100 text-slate-700",
} as const;

function locationLabel(evidence: ContractReviewCategoryEvidenceView): string {
  if (!evidence.locatorAvailable) return "暂无法精确定位原文位置，可查看来源材料";
  const values = [
    evidence.pageNumber === null ? null : `第 ${evidence.pageNumber} 页`,
    evidence.paragraphIndex === null ? null : `第 ${evidence.paragraphIndex} 段`,
    evidence.segmentIndex === null ? null : `文本片段 ${evidence.segmentIndex}`,
  ].filter((value): value is string => value !== null);
  return values.length > 0 ? values.join(" · ") : "已验证来源位置";
}

export function EightCategoryReviewPanel({
  categories,
  projectInfo,
  runId,
  runStatus,
  initialOpenCategoryId = null,
  onEvidenceOpen,
  clipboard = browserClipboard,
}: EightCategoryReviewPanelProps) {
  const [openCategoryId, setOpenCategoryId] = useState<ContractReviewCategoryId | null>(initialOpenCategoryId);
  const [lastCopyState, setCopyState] = useState<CopyState>(null);
  const copyState = lastCopyState?.runId === runId && lastCopyState.runStatus === runStatus
    && lastCopyState.categories === categories ? lastCopyState : null;
  const overallOpinion = useMemo(() => buildOverallSummaryOpinion(categories, runId, runStatus),
    [categories, runId, runStatus]);
  const overallDetail = useMemo(() => buildOverallOpinion(categories, runId, runStatus),
    [categories, runId, runStatus]);
  const attentionTotal = useMemo(() => new Set(categories.flatMap((category) =>
    category.items.filter((item) => item.status !== "NORMAL").map((item) => item.stableId))).size, [categories]);
  const incompleteCount = categories.filter((category) => category.coverage !== "COVERED").length;
  const canonicalOpinion = categories[0]?.overallOpinion;
  const observation = categories[0]?.monetaryObservation;

  const performCopy = async (key: string, value: string) => {
    const succeeded = await copyText(clipboard, value);
    setCopyState({ key, succeeded, value, runId, runStatus, categories });
  };

  return (
    <section className="space-y-3" aria-labelledby="eight-category-title">
      {projectInfo && Object.values(projectInfo).some((value) => value?.trim()) && (
        <section aria-label="项目基本信息" className="rounded-lg border border-slate-200 bg-white px-4 py-3">
          <h3 className="text-sm font-semibold text-slate-800">项目基本信息</h3>
          <dl className="mt-2 grid grid-cols-1 gap-x-5 gap-y-2 sm:grid-cols-2 xl:grid-cols-3">
            {([
              ["项目名称", projectInfo.projectName],
              ["项目编号", projectInfo.projectNo],
              ["合同名称", projectInfo.contractName],
              ["合同编号", projectInfo.contractNo],
              ["供应商", projectInfo.supplier],
              ["合同金额", projectInfo.contractAmount],
            ] as const).filter(([, value]) => value?.trim()).map(([label, value]) => (
              <div key={label} className="min-w-0">
                <dt className="text-[11px] text-slate-500">{label}</dt>
                <dd className="mt-0.5 break-words text-xs font-medium leading-5 text-slate-800">{value}</dd>
              </div>
            ))}
          </dl>
        </section>
      )}
      <div className="rounded-xl border border-slate-200 bg-white p-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <p className="text-xs font-medium text-slate-500">本次审核概览</p>
            <h3 id="eight-category-title" className="mt-1 text-base font-semibold text-slate-950">综合审核意见</h3>
            {observation?.mode === "OBSERVE" && <p data-monetary-observation-marker role="note"
              className="mt-2 rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm font-semibold text-amber-900">{MONETARY_OBSERVATION_MARKER}</p>}
            {canonicalOpinion && <p data-overall-opinion className="mt-3 whitespace-pre-wrap text-sm leading-7 text-slate-900">{canonicalOpinion}</p>}
            <p className="mt-1 text-xs leading-5 text-slate-600">
              关注 {attentionTotal} 项 · 覆盖不完整 {incompleteCount} 项。结果来自当前审核记录的持久化输出。
            </p>
            <p className="mt-1 text-xs leading-5 text-slate-600">{categories[0]?.scopeStatement ?? BUSINESS_REVIEW_SCOPE}</p>
            {runStatus !== "COMPLETED" && <p className="mt-1 text-xs text-amber-800">当前意见为草稿，待审核完成。</p>}
          </div>
          <div className="flex flex-wrap gap-2">
          <button type="button" onClick={() => void performCopy("overall", overallOpinion)}
            className="inline-flex items-center gap-1.5 rounded-md border border-slate-300 bg-white px-3 py-2 text-xs font-medium text-slate-700 hover:bg-slate-50">
            {copyState?.key === "overall" && copyState.succeeded ? <Check size={14} /> : <Copy size={14} />}
            复制综合意见
          </button>
          <button type="button" onClick={() => void performCopy("overall-detail", overallDetail)}
            className="inline-flex items-center gap-1.5 rounded-md border border-slate-300 bg-white px-3 py-2 text-xs font-medium text-slate-700 hover:bg-slate-50">
            {copyState?.key === "overall-detail" && copyState.succeeded ? <Check size={14} /> : <Copy size={14} />}
            复制综合完整明细
          </button>
          </div>
        </div>
        {observation && (observation.warnings.length > 0 || observation.workWarnings.length > 0) && (
          <details data-monetary-observation-details className="mt-3 rounded-md border border-amber-200 bg-amber-50 p-3 text-xs text-amber-900">
            <summary className="cursor-pointer font-medium">金额表述待复核明细</summary>
            <p className="mt-2">相关模型表述保留在下方对应检查项及证据中。金额结论以本次确定性核对结果为准。</p>
            <ul className="mt-2 space-y-2">
              {observation.warnings.map((warning, index) => <li key={`final-${index}`}>
                第 {index + 1} 项金额表述需要复核，请结合下方检查结果与来源材料核对。
              </li>)}
              {observation.workWarnings.map((warning, index) => <li key={`work-${index}`}>
                另有 {warning.warningCount} 处金额表述待核对。
              </li>)}
            </ul>
          </details>
        )}
        {copyState?.key.startsWith("overall") && (
          <p role="status" className={`mt-2 text-xs ${copyState.succeeded ? "text-emerald-700" : "text-rose-700"}`}>
            {copyState.succeeded ? (copyState.key === "overall" ? "综合意见已复制" : "综合完整明细已复制") : "浏览器未允许自动复制，请在下方文本中手动全选复制。"}
          </p>
        )}
        {copyState && !copyState.succeeded && (
          <div className="mt-3 rounded-md border border-amber-200 bg-amber-50 p-3">
            <p className="text-xs text-amber-900">请选中文本，手动全选复制。</p>
            <textarea aria-label="复制失败后的手动复制文本" readOnly value={copyState.value}
              onFocus={(event) => event.currentTarget.select()}
              rows={12} className="mt-2 w-full rounded border border-slate-300 p-2 text-xs leading-5" />
          </div>
        )}
        <details open={copyState?.succeeded === false && copyState.key === "overall"}
          className="mt-3 rounded-md bg-slate-50 px-3 py-2 text-xs text-slate-700">
          <summary className="cursor-pointer font-medium">预览综合意见（简版）</summary>
          <p className="mt-2">可点击文本后手动全选复制。</p>
          <textarea aria-label="综合意见手动复制" readOnly value={overallOpinion}
            onFocus={(event) => event.currentTarget.select()} rows={12}
            className="mt-2 w-full rounded border border-slate-200 bg-white p-2 font-sans leading-5" />
        </details>
        <details open={copyState?.succeeded === false && copyState.key === "overall-detail"}
          className="mt-3 rounded-md bg-slate-50 px-3 py-2 text-xs text-slate-700">
          <summary className="cursor-pointer font-medium">预览综合完整明细</summary>
          <textarea aria-label="综合完整明细手动复制" readOnly value={overallDetail}
            onFocus={(event) => event.currentTarget.select()} rows={12}
            className="mt-2 w-full rounded border border-slate-200 bg-white p-2 font-sans leading-5" />
        </details>
      </div>

      <div className="grid grid-cols-1 gap-2 md:grid-cols-2" data-eight-category-grid>
        <h4 className="col-span-full text-sm font-semibold text-slate-800">合同审核八大项</h4>
        {categories.map((category) => {
          const open = openCategoryId === category.id;
          const copyHeader = (title: string) => `${title}${runStatus === "COMPLETED" ? "" : "（草稿，待审核完成）"}\n审核记录：${runId}\n`;
          const opinion = copyHeader("本项审核意见（简版）") + buildCategorySummaryOpinion(category);
          const detail = copyHeader("本项审核完整明细") + buildCategoryOpinion(category);
          return (
            <article key={category.id} className="min-w-0 overflow-hidden rounded-lg border border-slate-200 bg-white">
              <button type="button" data-category-row={category.id} aria-expanded={open}
                onClick={() => setOpenCategoryId((current) => nextOpenCategory(current, category.id))}
                className="flex min-h-11 w-full items-center gap-2 px-3 py-2 text-left hover:bg-slate-50">
                {open ? <ChevronDown size={16} className="shrink-0 text-slate-500" />
                  : <ChevronRight size={16} className="shrink-0 text-slate-500" />}
                <span className="min-w-0 flex-1 break-words text-xs font-semibold leading-5 text-slate-900">
                  {category.order}. {category.title}
                </span>
                <span className={`shrink-0 rounded-full border px-1.5 py-0.5 text-[10px] font-medium ${judgementClasses[category.judgement]}`}>
                  {category.statusLabel}
                </span>
                <span className="shrink-0 text-[10px] text-slate-500">关注 {category.attentionCount}</span>
              </button>

              {open && (
                <div data-category-content={category.id} className="space-y-4 border-t border-slate-200 px-4 py-4">
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div>
                      <p className="text-xs font-semibold text-slate-500">本项总结</p>
                      <p className="mt-1 text-sm leading-6 text-slate-800">{category.conciseOpinion?.trim() || category.summary}</p>
                    </div>
                    <div className="flex flex-wrap gap-2">
                    <button type="button" onClick={() => void performCopy(category.id, opinion)}
                      className="inline-flex shrink-0 items-center gap-1.5 rounded border border-slate-300 px-2.5 py-1.5 text-xs text-slate-700 hover:bg-slate-50">
                      {copyState?.key === category.id && copyState.succeeded ? <Check size={13} /> : <Copy size={13} />}
                      复制本项意见
                    </button>
                    <button type="button" onClick={() => void performCopy(`${category.id}-detail`, detail)}
                      className="inline-flex shrink-0 items-center gap-1.5 rounded border border-slate-300 px-2.5 py-1.5 text-xs text-slate-700 hover:bg-slate-50">
                      {copyState?.key === `${category.id}-detail` && copyState.succeeded ? <Check size={13} /> : <Copy size={13} />}
                      复制本项完整明细
                    </button>
                    </div>
                  </div>
                  {(copyState?.key === category.id || copyState?.key === `${category.id}-detail`) && (
                    <p role="status" className={`text-xs ${copyState.succeeded ? "text-emerald-700" : "text-rose-700"}`}>
                      {copyState.succeeded ? (copyState.key === category.id ? "本项意见已复制" : "本项完整明细已复制") : "浏览器未允许自动复制，请在上方文本中手动全选复制。"}
                    </p>
                  )}
                  <details className="rounded-md bg-slate-50 px-3 py-2 text-xs text-slate-700">
                    <summary className="cursor-pointer font-medium">预览本项意见（简版）</summary>
                    <textarea aria-label={`${category.title}简版手动复制`} readOnly value={opinion}
                      onFocus={(event) => event.currentTarget.select()} rows={5}
                      className="mt-2 w-full rounded border border-slate-200 p-2 leading-5" />
                  </details>
                  <details className="rounded-md bg-slate-50 px-3 py-2 text-xs text-slate-700">
                    <summary className="cursor-pointer font-medium">预览本项完整明细</summary>
                    <textarea aria-label={`${category.title}完整明细手动复制`} readOnly value={detail}
                      onFocus={(event) => event.currentTarget.select()} rows={10}
                      className="mt-2 w-full rounded border border-slate-200 p-2 leading-5" />
                  </details>

                  {category.uncoveredScopes.length > 0 && (
                    <div className="rounded-lg border border-amber-200 bg-amber-50 p-3">
                      <p className="text-xs font-semibold text-amber-900">尚未覆盖</p>
                      <ul className="mt-1 list-disc space-y-1 pl-5 text-xs leading-5 text-amber-900">
                        {category.uncoveredScopes.map((value) => <li key={value}>{value}</li>)}
                      </ul>
                    </div>
                  )}

                  <div>
                    <p className="text-xs font-semibold text-slate-500">具体检查项</p>
                    {category.items.length === 0 ? (
                      <p className="mt-2 rounded-lg border border-dashed border-slate-300 p-3 text-xs text-slate-600">
                        本次审核没有可展示的持久化检查结果。
                      </p>
                    ) : (
                      <div className="mt-2 space-y-3">
                        {category.items.map((item) => (
                          <ResultItem key={item.stableId} title={item.title} tone={item.status==='NORMAL'?'clear':item.status==='ATTENTION'?'attention':'default'} label={item.status==='NORMAL'?'正常':item.status==='ATTENTION'?'需关注':'资料不足'}>
                            <p className="mt-2 text-xs font-semibold text-slate-500">双方事实 / 差异</p>
                            <p className="mt-1 text-sm leading-6 text-slate-800">{item.summary}</p>
                            {item.typedFacts.length > 0 && (
                              <ul className="mt-2 space-y-1 rounded-md bg-white/70 px-3 py-2 text-xs leading-5 text-slate-700">
                                {item.typedFacts.map((fact) => <li key={fact}>{fact}</li>)}
                              </ul>
                            )}
                            {(item.actualValue !== null || item.expectedValue !== null) && (
                              <dl className="mt-2 grid gap-2 text-xs sm:grid-cols-2">
                                {item.actualValue !== null && <div><dt className="text-slate-500">合同 / 当前值</dt><dd className="mt-0.5 text-slate-900">{item.actualValue}</dd></div>}
                                {item.expectedValue !== null && <div><dt className="text-slate-500">对照 / 期望值</dt><dd className="mt-0.5 text-slate-900">{item.expectedValue}</dd></div>}
                              </dl>
                            )}
                            <p className="mt-3 text-xs font-semibold text-slate-500">来源依据</p>
                            {item.evidence.length === 0 ? (
                              <p className="mt-1 text-xs text-slate-600">暂无法精确定位原文位置，可查看来源材料。</p>
                            ) : (
                              <div className="mt-1 flex flex-wrap gap-2">
                                {item.evidence.map((evidence) => (
                                  <button type="button" key={evidence.evidenceId} onClick={() => onEvidenceOpen(evidence)}
                                    className="inline-flex items-center gap-1.5 rounded border border-slate-300 bg-white px-2.5 py-1.5 text-left text-xs text-slate-700 hover:bg-slate-50">
                                    <FileSearch size={13} />
                                    <span>{evidence.fileName}</span>
                                    <span className="text-slate-400">{locationLabel(evidence)}</span>
                                  </button>
                                ))}
                              </div>
                            )}
                            <p className="mt-3 text-xs font-semibold text-slate-500">处理建议</p>
                            <p className="mt-1 text-xs leading-5 text-slate-700">
                              {item.recommendation ?? (item.status === "NORMAL" ? "保留本次审核记录。" : "请结合来源材料人工复核。")}
                            </p>
                          </ResultItem>
                        ))}
                      </div>
                    )}
                  </div>
                </div>
              )}
            </article>
          );
        })}
      </div>
    </section>
  );
}
