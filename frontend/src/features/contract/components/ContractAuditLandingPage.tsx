import {
  AlertTriangle,
  ArrowRight,
  CheckCircle2,
  ChevronRight,
  Clock3,
  FileCheck2,
  Inbox,
  ListFilter,
  LoaderCircle,
  Plus,
  ShieldAlert,
  Sparkles,
} from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { userFacingError } from "../../../api/userFacingError";
import {
  contractAuditLandingPresentation,
  type ContractAuditLandingTaskRow,
} from "../contractAuditLandingPresentation";
import {
  contractAuditTaskDataSource,
  type ContractAuditTaskDataSource,
  type ContractAuditTaskItem,
  type ContractAuditTaskPage,
  type ContractAuditTaskSummary,
} from "../data/contractAuditTaskDataSource";

export interface ContractAuditLandingCallbacks {
  onAnalyze: () => void;
  onCreateContract: () => void;
  onOpenList: () => void;
  onOpenTask: (task: ContractAuditTaskItem) => void;
}

export interface ContractAuditLandingPageProps extends ContractAuditLandingCallbacks {
  dataSource?: ContractAuditTaskDataSource;
}

export interface ContractAuditLandingLoad {
  summary: ContractAuditTaskSummary;
  attentionPage: ContractAuditTaskPage;
  completedPage: ContractAuditTaskPage;
  todayTotal: number;
}

function todayRange(now: Date): { createdFrom: string; createdTo: string } {
  const createdFrom = new Date(now);
  createdFrom.setHours(0, 0, 0, 0);
  const createdTo = new Date(now);
  createdTo.setHours(23, 59, 59, 999);
  return { createdFrom: createdFrom.toISOString(), createdTo: createdTo.toISOString() };
}

export async function loadContractAuditLanding(
  dataSource: ContractAuditTaskDataSource,
  now: Date,
  signal: AbortSignal,
): Promise<ContractAuditLandingLoad> {
  const { createdFrom, createdTo } = todayRange(now);
  const [summary, pendingPage, processingPage, needsReviewPage, completedPage, todayPage] = await Promise.all([
    dataSource.summary({ signal }),
    dataSource.list({ page: 1, size: 6, status: "PENDING", signal }),
    dataSource.list({ page: 1, size: 6, status: "PROCESSING", signal }),
    dataSource.list({ page: 1, size: 6, status: "NEEDS_REVIEW", signal }),
    dataSource.list({ page: 1, size: 6, status: "COMPLETED", signal }),
    dataSource.list({ page: 1, size: 1, createdFrom, createdTo, signal }),
  ]);
  const attentionItems = new Map<string, ContractAuditTaskItem>();
  for (const item of [...pendingPage.items, ...processingPage.items, ...needsReviewPage.items]) {
    if (!attentionItems.has(item.taskId)) attentionItems.set(item.taskId, item);
  }
  const attentionPage: ContractAuditTaskPage = {
    items: [...attentionItems.values()].slice(0, 6), page: 1, size: 6,
    total: summary.pendingOrProcessing + summary.needsReview,
    totalPages: Math.ceil((summary.pendingOrProcessing + summary.needsReview) / 6),
  };
  return { summary, attentionPage, completedPage, todayTotal: todayPage.total };
}

type VisualTone = "danger" | "warning" | "success" | "neutral";

function StatusCard({ label, value, tone, onOpenList }: { label: string; value: number | null; tone: VisualTone; onOpenList: () => void }) {
  return <button type="button" onClick={onOpenList} className="delivery-surface delivery-stat-card" data-tone={tone}>
    <span className="text-sm text-stone-500">{label}</span>
    <strong className="delivery-stat-value">{value ?? "--"}</strong>
  </button>;
}

function reviewStatusTone(row: ContractAuditLandingTaskRow): VisualTone {
  if (row.task.reviewProgress.complete) return "success";
  if (row.task.taskStatus === "NEEDS_MANUAL_REVIEW" || row.task.reviewProgress.pendingAcknowledgement) return "danger";
  return "warning";
}

function riskTone(row: ContractAuditLandingTaskRow): VisualTone {
  return row.task.risk.materialInsufficient || row.task.risk.riskCount > 0 ? "danger" : "success";
}

function contractAmount(amount: number): string {
  return new Intl.NumberFormat("zh-CN", {
    style: "currency",
    currency: "CNY",
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(amount);
}

const contractStatusCopy: Record<string, string> = {
  ACTIVE: "已生效",
  DRAFT: "草稿",
  READY_FOR_REVIEW: "待审核",
};

function AttentionTaskCard({ row, onOpenTask }: { row: ContractAuditLandingTaskRow; onOpenTask: (task: ContractAuditTaskItem) => void }) {
  const { current, total } = row.task.reviewProgress;
  const progress = Number.isFinite(current) && current >= 0 && Number.isFinite(total) && total > 0 ? `${current} / ${total}` : "暂不可用";
  return <article className="rounded-2xl border border-stone-200 bg-white p-5 shadow-sm">
    <div className="flex flex-wrap items-start justify-between gap-3">
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-semibold text-stone-800">{row.project?.projectName ?? "未关联项目"}</p>
        <button type="button" onClick={() => onOpenTask(row.task)} className="mt-1 block max-w-full truncate text-left text-base font-semibold text-stone-900 hover:text-orange-700">
          {row.contract.contractName}
        </button>
      </div>
      <span className="delivery-status-pill" data-tone={riskTone(row)}>{row.riskLabel}</span>
    </div>
    <div className="mt-4 rounded-xl border-l-4 border-orange-400 bg-orange-50/70 px-4 py-3">
      <p className="text-xs font-semibold text-orange-800">风险摘要</p>
      <p className="mt-1 text-sm text-stone-700">{row.riskLabel} · 风险项 {row.task.risk.riskCount}</p>
    </div>
    <div className="mt-4 grid gap-2 text-xs text-stone-500 sm:grid-cols-2">
      <p>合同编号：<span className="text-stone-700">{row.contractNo}</span></p>
      <p>合同金额：{contractAmount(row.contract.contractAmount)}</p>
      <p>合同状态：<span className="text-stone-700">{contractStatusCopy[row.contract.status] ?? "待确认"}</span></p>
      <p>审核进度：{progress}</p>
    </div>
    <div className="mt-5 flex flex-wrap items-center justify-between gap-3">
      <span className="delivery-status-pill" data-tone={reviewStatusTone(row)}>{row.statusLabel}</span>
      <div className="flex flex-wrap gap-2">
        <button type="button" onClick={() => onOpenTask(row.task)} className="delivery-primary-action">{row.actionLabel}<ChevronRight size={15}/></button>
        <button type="button" onClick={() => onOpenTask(row.task)} className="delivery-secondary-action">查看详情</button>
      </div>
    </div>
  </article>;
}

function CompletedTaskCard({ row, onOpenTask }: { row: ContractAuditLandingTaskRow; onOpenTask: (task: ContractAuditTaskItem) => void }) {
  return <article className="rounded-2xl border border-stone-200 bg-stone-50/70 p-4">
    <div className="flex items-start justify-between gap-3">
      <div className="min-w-0">
        <p className="truncate text-xs text-stone-500">{row.project?.projectName ?? "未关联项目"}</p>
        <h3 className="mt-1 truncate font-semibold text-stone-800">{row.contract.contractName}</h3>
        <p className="mt-2 text-xs text-stone-500">合同编号：{row.contractNo} · 合同金额：{contractAmount(row.contract.contractAmount)}</p>
      </div>
      <span className="delivery-status-pill shrink-0" data-tone="success">审核完成</span>
    </div>
    <button type="button" onClick={() => onOpenTask(row.task)} className="delivery-secondary-action mt-4">查看详情<ChevronRight size={14}/></button>
  </article>;
}

export function ContractAuditLandingContent({
  view,
  loading,
  error,
  onRetry,
  onAnalyze,
  onCreateContract,
  onOpenList,
  onOpenTask,
}: ContractAuditLandingCallbacks & {
  view: ReturnType<typeof contractAuditLandingPresentation> | null;
  loading: boolean;
  error: string | null;
  onRetry?: () => void;
}) {
  const summary = view?.summary;
  return <>
    <header className="delivery-page-header">
      <div>
        <h1 className="delivery-page-title">合同审核中心</h1>
        <p className="delivery-page-subtitle">集中处理待核验材料，并按 exact run 重开历史审核记录</p>
      </div>
      <div className="flex flex-wrap gap-2">
        <button type="button" onClick={onAnalyze} className="delivery-primary-action"><Sparkles size={16}/>智能分析</button>
        <button type="button" onClick={onCreateContract} className="delivery-secondary-action"><Plus size={15}/>新建合同</button>
        <button type="button" onClick={onOpenList} className="delivery-secondary-action"><ListFilter size={15}/>审核记录</button>
      </div>
    </header>

    <nav className="flex gap-2 border-b border-stone-200" aria-label="合同审核中心视图">
      <button type="button" aria-current="page" className="border-b-2 border-orange-500 px-4 py-3 text-sm font-semibold text-orange-700">待处理</button>
      <button type="button" onClick={onOpenList} className="px-4 py-3 text-sm text-stone-500 hover:text-stone-800">审核记录</button>
    </nav>

    {error && <div role="alert" className="delivery-alert delivery-alert-warning">
      <span className="flex items-center gap-3"><AlertTriangle size={18}/><span><strong className="block">审核任务暂未更新</strong><span className="mt-1 block text-xs">{error}</span></span></span>
      <button type="button" onClick={onRetry} className="delivery-secondary-action">重新加载</button>
    </div>}

    <section className="grid grid-cols-2 gap-4 md:grid-cols-4">
      <StatusCard label="合同审核任务" value={summary?.total ?? null} tone="neutral" onOpenList={onOpenList}/>
      <StatusCard label="待审核 / 审核中" value={summary?.pendingOrProcessing ?? null} tone="warning" onOpenList={onOpenList}/>
      <StatusCard label="需复核" value={summary?.needsReview ?? null} tone="danger" onOpenList={onOpenList}/>
      <StatusCard label="审核完成" value={summary?.completed ?? null} tone="success" onOpenList={onOpenList}/>
    </section>

    <section className="delivery-surface delivery-summary-card">
      <span className="delivery-icon-chip"><Clock3 size={18}/></span>
      <div className="min-w-0 flex-1">
        <h2 className="font-semibold text-stone-800">今日审核概要</h2>
        <p className="mt-1 text-sm text-stone-500">
          今日新建任务 · {loading ? "正在同步" : view?.todayCreatedTotal === null || view?.todayCreatedTotal === undefined ? "暂不可用" : `${view.todayCreatedTotal} 个`}
        </p>
      </div>
      <strong className="text-2xl tabular-nums text-stone-800">{view?.todayCreatedTotal ?? "--"}</strong>
    </section>

    <button type="button" onClick={onOpenList} className="delivery-business-entry">
      <span className="flex items-center gap-3"><span className="delivery-icon-chip"><FileCheck2 size={18}/></span><span><strong className="block text-base text-stone-800">查看今天需要重点审核的合同</strong><span className="mt-1 block text-xs text-stone-500">进入完整任务列表，查看状态、结果与证据来源</span></span></span>
      <span className="delivery-business-entry-icon"><ArrowRight size={17}/></span>
    </button>

    <section className="grid gap-6 xl:grid-cols-[minmax(0,1.55fr)_minmax(19rem,0.65fr)]">
      <section className="delivery-surface p-5" aria-labelledby="attention-tasks-heading">
        <div className="mb-4 flex flex-wrap items-baseline justify-between gap-3">
          <div><h2 id="attention-tasks-heading" className="font-semibold text-stone-800">待处理</h2><p className="mt-1 text-xs text-stone-500">当前需继续核验或审核的对象共 {view?.attentionTotal ?? "--"} 条</p></div>
          <button type="button" onClick={onOpenList} className="delivery-link">查看全部</button>
        </div>
        {loading && <div className="delivery-empty-state"><LoaderCircle size={22} className="animate-spin"/><span className="text-sm">正在读取合同审核任务</span></div>}
        {!loading && !error && view?.attention.length === 0 && <div className="delivery-empty-state"><Inbox size={24}/><strong className="text-sm text-stone-600">当前没有需关注的审核任务</strong><span className="text-xs">新产生的风险与待复核任务会在这里展示</span></div>}
        <div className="grid gap-4 2xl:grid-cols-2">
          {!loading && !error && view?.attention.map((row) => <AttentionTaskCard key={row.taskId} row={row} onOpenTask={onOpenTask}/>) }
        </div>
      </section>

      <section className="delivery-surface p-5" aria-labelledby="risk-summary-heading">
        <div className="flex items-center gap-3"><span className="flex size-10 items-center justify-center rounded-xl bg-red-50 text-red-500"><ShieldAlert size={19}/></span><div><h2 id="risk-summary-heading" className="font-semibold text-stone-800">风险摘要</h2><p className="mt-1 text-xs text-stone-500">当前需关注任务</p></div></div>
        <dl className="mt-6 space-y-3">
          <div className="flex items-center justify-between rounded-xl bg-stone-50 px-4 py-3"><dt className="text-sm text-stone-500">关注任务</dt><dd className="text-xl font-semibold tabular-nums text-stone-800">{view?.loadedRiskSummary.loadedTaskCount ?? "--"}</dd></div>
          <div className="flex items-center justify-between rounded-xl bg-red-50 px-4 py-3"><dt className="text-sm text-red-600">风险项数</dt><dd className="text-xl font-semibold tabular-nums text-red-600">{view?.loadedRiskSummary.riskCount ?? "--"}</dd></div>
          <div className="flex items-center justify-between rounded-xl bg-amber-50 px-4 py-3"><dt className="text-sm text-amber-700">材料不足任务</dt><dd className="text-xl font-semibold tabular-nums text-amber-700">{view?.loadedRiskSummary.materialInsufficientTaskCount ?? "--"}</dd></div>
        </dl>
      </section>
    </section>

  </>;
}

export function ContractAuditLandingPage({
  dataSource = contractAuditTaskDataSource,
  onAnalyze,
  onCreateContract,
  onOpenList,
  onOpenTask,
}: ContractAuditLandingPageProps) {
  const [summary, setSummary] = useState<ContractAuditTaskSummary | null>(null);
  const [attentionPage, setAttentionPage] = useState<ContractAuditTaskPage | null>(null);
  const [completedPage, setCompletedPage] = useState<ContractAuditTaskPage | null>(null);
  const [todayTotal, setTodayTotal] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reloadNonce, setReloadNonce] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    void loadContractAuditLanding(dataSource, new Date(), controller.signal).then((loaded) => {
      if (controller.signal.aborted) return;
      setSummary(loaded.summary);
      setAttentionPage(loaded.attentionPage);
      setCompletedPage(loaded.completedPage);
      setTodayTotal(loaded.todayTotal);
    }).catch((cause: unknown) => {
      if (controller.signal.aborted || (cause instanceof Error && cause.name === "AbortError")) return;
      setSummary(null);
      setAttentionPage(null);
      setCompletedPage(null);
      setTodayTotal(null);
      setError(userFacingError(cause, "合同审核任务暂时无法读取"));
    }).finally(() => {
      if (!controller.signal.aborted) setLoading(false);
    });
    return () => controller.abort();
  }, [dataSource, reloadNonce]);

  const view = useMemo(
    () => contractAuditLandingPresentation(summary, attentionPage, completedPage, todayTotal),
    [summary, attentionPage, completedPage, todayTotal],
  );
  return <section className="delivery-page space-y-6">
    <ContractAuditLandingContent view={view} loading={loading} error={error}
      onRetry={() => setReloadNonce((value) => value + 1)} onAnalyze={onAnalyze}
      onCreateContract={onCreateContract} onOpenList={onOpenList} onOpenTask={onOpenTask}/>
  </section>;
}
