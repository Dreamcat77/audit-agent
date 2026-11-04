import { Eye, LoaderCircle } from "lucide-react";
import { useState } from "react";
import type {
  ContractAuditTaskRow,
  DirectF02AuditTaskRow,
  ShowcaseAuditTaskRow,
} from "../auditTaskViewModel";
import {
  displayChineseUppercaseAmount,
  formatRmbAmount,
} from "../reviewFindingPresentation";
import { auditTaskRiskPresentation, compactReviewProgressPresentation, directTaskReviewPromptPresentation,
  riskBadgeToneClass } from "../auditTaskPresentation";
import { directF02TaskStatusPresentation } from "../contractAuditTaskPresentation";
import { useContractAuditTaskList } from "../useContractAuditTaskList";
import CompactReviewProgress from "./CompactReviewProgress";
import ContractReviewProgressBar from "./ContractReviewProgressBar";
import ShowcaseReviewHistoryDialog from "./ShowcaseReviewHistoryDialog";
import { ChineseDateInput } from "../../../app/components/ui/chinese-date-input";

interface ContractOverviewListProps {
  onOpenShowcase: (row: ShowcaseAuditTaskRow) => void;
  onOpenDirectF02: (row: DirectF02AuditTaskRow) => void;
  onOpenPending: () => void;
  embedded?: boolean;
}

function formatTime(value: string): string {
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit",
  }).format(parsed);
}

function OverviewStat({ label, value }: { label: string; value: number | null }) {
  return <div className="flex flex-col gap-2 rounded-lg border border-slate-200 bg-white p-5 shadow-sm"><span className="text-sm text-slate-600">{label}</span><span className="text-2xl font-semibold text-slate-900">{value === null ? "--" : value}</span></div>;
}

function SmartReviewBadge({ row }: { row: ContractAuditTaskRow }) {
  if (row.source === "DIRECT_F02") {
    const label = directF02TaskStatusPresentation(row);
    const style = label === "审查执行完成" || label === "量化核对完成"
      ? "border-emerald-200 bg-emerald-50 text-emerald-700"
      : label === "复核提示待知悉" || label === "量化复核待知悉" || label === "部分步骤完成"
        ? "border-amber-200 bg-amber-50 text-amber-800"
        : label === "处理失败" || label === "结果待确认" || label === "归属待核"
          ? "border-red-200 bg-red-50 text-red-700"
        : label === "审核中"
          ? "border-blue-200 bg-blue-50 text-blue-700"
          : "border-slate-200 bg-slate-100 text-slate-600";
    return <span className={`whitespace-nowrap rounded border px-2 py-1 text-xs ${style}`}>{label}</span>;
  }
  const progress = compactReviewProgressPresentation(row.reviewResult, null, false);
  const completed = progress.current !== null && progress.current === progress.total;
  return completed
    ? <span className="whitespace-nowrap rounded border border-emerald-200 bg-emerald-50 px-2 py-1 text-xs text-emerald-700">审核完成</span>
    : row.reviewResult
      ? <span className="whitespace-nowrap rounded border border-blue-200 bg-blue-50 px-2 py-1 text-xs text-blue-700">审核中</span>
      : <span className="whitespace-nowrap rounded border border-slate-200 bg-slate-100 px-2 py-1 text-xs text-slate-600">待审核</span>;
}

function RiskBadge({ row }: { row: ContractAuditTaskRow }) {
  if (row.source === "DIRECT_F02") {
    const prompt = directTaskReviewPromptPresentation(row.risk.riskSummary);
    const style = riskBadgeToneClass(prompt.tone);
    return <span className={`whitespace-nowrap rounded border px-2 py-1 text-xs ${style}`}>{prompt.label}</span>;
  }
  if (!row.reviewResult) return null;
  const risk = auditTaskRiskPresentation(row.reviewResult.findings);
  const style = riskBadgeToneClass(risk.tone);
  return <span className={`whitespace-nowrap rounded border px-2 py-1 text-xs ${style}`}>{risk.label}</span>;
}

export function ContractOverviewTableRow({ row, onOpen, onOpenHistory }: {
  row: ContractAuditTaskRow;
  onOpen: (row: ContractAuditTaskRow) => void;
  onOpenHistory: (row: ShowcaseAuditTaskRow) => void;
}) {
  return (
    <tr className="cursor-pointer transition-colors hover:bg-blue-50/60" onClick={() => onOpen(row)}>
      <td className="min-w-44 px-4 py-4"><div className="font-medium text-slate-900">{row.projectName ?? "—"}</div>{row.projectNo && <div className="mt-1 text-xs text-slate-500">项目编号：{row.projectNo}</div>}</td>
      <td className="min-w-56 px-4 py-4"><div className="font-medium text-slate-900">{row.contract.contractName}</div><div className="mt-1 text-xs text-slate-500">合同编号：{row.contract.contractNo}</div></td>
      <td className="min-w-40 px-4 py-4 text-slate-700">{row.contract.partyB}</td>
      <td className="min-w-48 px-4 py-4"><div className="whitespace-nowrap font-semibold tabular-nums text-slate-900">{formatRmbAmount(row.contract.contractAmount)}</div><div className="mt-1 text-xs text-slate-600">{displayChineseUppercaseAmount(row.contract.amountInWords)}</div></td>
      <td className="min-w-[190px] px-4 py-3">{row.source === "DIRECT_F02"
        ? <ContractReviewProgressBar
            current={row.reviewProgress.current}
            total={row.reviewProgress.total}
            statusLabel={row.reviewProgress.pendingAcknowledgement ? "量化复核待知悉"
              : row.reviewProgress.complete ? "量化核对完成" : "量化核对中"}
            complete={row.reviewProgress.complete}
            pendingAcknowledgement={row.reviewProgress.pendingAcknowledgement}
          />
        : <CompactReviewProgress result={row.reviewResult} />}</td>
      <td className="px-4 py-4"><SmartReviewBadge row={row} /></td>
      <td className="px-4 py-4"><RiskBadge row={row} /></td>
      <td className="whitespace-nowrap px-4 py-4 text-xs text-slate-600">{formatTime(row.createdAt)}</td>
      <td className="whitespace-nowrap px-4 py-4 text-xs text-slate-600">{formatTime(row.updatedAt)}</td>
      <td className="px-4 py-3">
        <div className="flex items-center gap-3 whitespace-nowrap">
          <button type="button" onClick={(event) => { event.stopPropagation(); onOpen(row); }} className="inline-flex items-center gap-1.5 text-blue-700 hover:text-blue-600"><Eye size={13} />查看</button>
          {row.source === "SHOWCASE" && <button type="button" onClick={(event) => { event.stopPropagation(); onOpenHistory(row); }} className="text-blue-700 hover:text-blue-600">审核记录</button>}
        </div>
      </td>
    </tr>
  );
}

export default function ContractOverviewList({ onOpenShowcase, onOpenDirectF02, onOpenPending, embedded = false }: ContractOverviewListProps) {
  const taskList = useContractAuditTaskList();
  const [showcaseHistory, setShowcaseHistory] = useState<ShowcaseAuditTaskRow | null>(null);

  const openRow = (row: ContractAuditTaskRow) => {
    if (row.source === "SHOWCASE") onOpenShowcase(row);
    else onOpenDirectF02(row);
  };

  return (
    <div className={`contract-light min-h-full space-y-4 text-slate-700 ${embedded ? "" : "bg-[#F6F8FB] p-6"}`}>
      {!embedded && <><div><h2 className="text-lg font-semibold text-slate-900">合同审核中心</h2><p className="mt-1 text-[13px] text-slate-600">按本次持久化审核身份查看历史记录、结果与证据。</p></div>
      <nav className="flex gap-2 border-b border-slate-200" aria-label="合同审核中心视图">
        <button type="button" onClick={onOpenPending} className="px-4 py-3 text-sm text-slate-500 hover:text-slate-800">待处理</button>
        <button type="button" aria-current="page" className="border-b-2 border-orange-500 px-4 py-3 text-sm font-semibold text-orange-700">审核记录</button>
      </nav></>}
      {taskList.dynamicLoading && <div className="inline-flex items-center gap-2 text-xs text-slate-600"><LoaderCircle size={14} className="animate-spin" />正在同步内审任务</div>}
      {taskList.dynamicError && <div className="flex items-center justify-between gap-3 rounded border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800"><span>{taskList.dynamicError}</span><button type="button" onClick={taskList.refresh} className="shrink-0 text-blue-700 hover:text-blue-600">重新加载</button></div>}
      <section className="flex flex-wrap items-end gap-3 rounded-lg border border-slate-200 bg-white p-4" aria-label="合同内审筛选">
        <label className="min-w-56 flex-1 text-xs text-slate-600">项目 / 关键字
          <input value={taskList.filters.keyword} placeholder="项目、合同或供应商"
            onChange={(event) => taskList.setFilters({ ...taskList.filters, keyword: event.target.value })}
            className="mt-1 w-full rounded border border-slate-200 px-3 py-2 text-sm text-slate-900" />
        </label>
        <label className="text-xs text-slate-600">开始日期
          <ChineseDateInput value={taskList.filters.createdFrom}
            onChange={(value) => taskList.setFilters({ ...taskList.filters, createdFrom: value })}
            className="mt-1 block rounded border border-slate-200 px-3 py-2 text-sm text-slate-900" />
        </label>
        <label className="text-xs text-slate-600">结束日期
          <ChineseDateInput value={taskList.filters.createdTo}
            onChange={(value) => taskList.setFilters({ ...taskList.filters, createdTo: value })}
            className="mt-1 block rounded border border-slate-200 px-3 py-2 text-sm text-slate-900" />
        </label>
        <label className="text-xs text-slate-600">风险等级
          <select value={taskList.filters.risk}
            onChange={(event) => taskList.setFilters({ ...taskList.filters, risk: event.target.value as typeof taskList.filters.risk })}
            className="mt-1 block rounded border border-slate-200 px-3 py-2 text-sm text-slate-900">
            <option value="">全部</option><option value="NORMAL">正常</option>
            <option value="REVIEW">需复核</option><option value="INSUFFICIENT">材料不足</option>
          </select>
        </label>
        <label className="text-xs text-slate-600">审核状态
          <select value={taskList.filters.status}
            onChange={(event) => taskList.setFilters({ ...taskList.filters, status: event.target.value as typeof taskList.filters.status })}
            className="mt-1 block rounded border border-slate-200 px-3 py-2 text-sm text-slate-900">
            <option value="">全部</option><option value="PENDING">待审核</option>
            <option value="PROCESSING">审核中</option><option value="COMPLETED">量化核对完成</option>
            <option value="FAILED">处理失败</option><option value="NEEDS_REVIEW">需复核</option>
          </select>
        </label>
        <button type="button" onClick={taskList.resetFilters}
          className="rounded border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-700">重置</button>
      </section>
      <div className="grid grid-cols-2 gap-4 md:grid-cols-4"><OverviewStat label="内审任务" value={taskList.stats.tasks} /><OverviewStat label="待审核 / 审核中" value={taskList.stats.pending} /><OverviewStat label="F02 量化核对完成" value={taskList.stats.completed} /><OverviewStat label="风险任务" value={taskList.stats.risks} /></div>
      <div className="rounded-lg border border-slate-200 bg-white shadow-sm overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full min-w-[1440px] text-sm">
            <thead className="bg-slate-50">
              <tr className="border-b border-slate-200 text-slate-500">
                {["项目", "合同", "供应商", "合同金额", "审核进度", "审核状态", "风险等级", "创建时间", "更新时间", "操作"].map((header) => (
                  <th key={header} className="px-4 py-3 text-left font-medium whitespace-nowrap">{header}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {taskList.rows.map((row) => (
                <ContractOverviewTableRow key={row.rowId} row={row} onOpen={openRow} onOpenHistory={setShowcaseHistory} />
              ))}
            </tbody>
          </table>
        </div>
      </div>
      <div className="flex items-center justify-between text-xs text-slate-600"><span>{taskList.dynamicLoading ? "正在同步内审任务" : taskList.dynamicError ? "任务数量暂不可用" : `共 ${taskList.displayTotal} 条内审任务 · 第 ${taskList.page} / ${Math.max(taskList.totalPages, 1)} 页`}</span><div className="flex gap-2"><button type="button" disabled={taskList.page <= 1 || taskList.dynamicLoading || Boolean(taskList.dynamicError)} onClick={taskList.previousPage} className="rounded border border-slate-200 bg-white px-3 py-1.5 disabled:opacity-35">上一页</button><button type="button" disabled={taskList.page >= taskList.totalPages || taskList.dynamicLoading || Boolean(taskList.dynamicError)} onClick={taskList.nextPage} className="rounded border border-slate-200 bg-white px-3 py-1.5 disabled:opacity-35">下一页</button></div></div>
      <ShowcaseReviewHistoryDialog row={showcaseHistory} onClose={() => setShowcaseHistory(null)} />
    </div>
  );
}
