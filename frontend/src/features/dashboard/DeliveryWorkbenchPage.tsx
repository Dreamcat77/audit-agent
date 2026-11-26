import { AlertTriangle, ArrowRight, BarChart3, FileSearch, Scale, ShoppingCart, ShieldAlert, Ban } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { userFacingError } from "../../api/userFacingError";
import { useAuth } from "../../auth/AuthProvider";
import { canAccessPage } from "../../app/appShellPresentation";
import type { ContractAuditTaskDataSource, ContractAuditTaskItem, ContractAuditTaskSummary } from "../contract/data/contractAuditTaskDataSource";
import { contractAuditTaskDataSource } from "../contract/data/contractAuditTaskDataSource";
import { workbenchPresentation } from "./workbenchPresentation";

interface DeliveryWorkbenchCallbacks {
  onOpenBudget: () => void;
  onOpenProcurement: () => void;
  onOpenContract: () => void;
  onOpenBlacklist: () => void;
  onOpenRisk: () => void;
}

export interface DeliveryWorkbenchPageProps extends DeliveryWorkbenchCallbacks {
  dataSource?: ContractAuditTaskDataSource;
}

export interface DeliveryWorkbenchLoad {
  summary: ContractAuditTaskSummary;
  recentTasks: ContractAuditTaskItem[];
  todayTotal: number;
}

function todayRange(now: Date): { createdFrom: string; createdTo: string } {
  const createdFrom = new Date(now);
  createdFrom.setHours(0, 0, 0, 0);
  const createdTo = new Date(now);
  createdTo.setHours(23, 59, 59, 999);
  return { createdFrom: createdFrom.toISOString(), createdTo: createdTo.toISOString() };
}

export async function loadDeliveryWorkbench(
  dataSource: ContractAuditTaskDataSource,
  now: Date,
  signal: AbortSignal,
): Promise<DeliveryWorkbenchLoad> {
  const { createdFrom, createdTo } = todayRange(now);
  const [summary, recent, createdToday] = await Promise.all([
    dataSource.summary({ signal }),
    dataSource.list({ page: 1, size: 20, signal }),
    dataSource.list({ page: 1, size: 1, createdFrom, createdTo, signal }),
  ]);
  return { summary, recentTasks: recent.items, todayTotal: createdToday.total };
}

export function DeliveryWorkbenchContent({
  view, loading, error, onRetry, onOpenBudget, onOpenProcurement, onOpenContract,
  onOpenBlacklist, onOpenRisk, roles = [],
  userName, lastUpdatedLabel,
}: DeliveryWorkbenchCallbacks & {
  view: ReturnType<typeof workbenchPresentation>;
  loading: boolean;
  error: string | null;
  onRetry?: () => void;
  userName?: string;
  lastUpdatedLabel?: string;
  roles?: readonly string[];
}) {
  const tools = [
    { key: "budget", title: "预算审查", description: "核对采购预算与已配置标准，形成可追溯的审查结果。", icon: <FileSearch size={21}/>, onOpen: onOpenBudget },
    { key: "procurement", title: "采购审查", description: "进入采购需求辅助审查，查看既有采购业务能力。", icon: <ShoppingCart size={21}/>, onOpen: onOpenProcurement },
    { key: "contract", title: "合同审核", description: "集中核验材料，发起审核并按八大项查看本次结果。", icon: <Scale size={21}/>, onOpen: onOpenContract },
  ].filter((tool) => canAccessPage(roles, tool.key === "budget" ? "budget-check" : tool.key));
  const auxiliaryTools = [
    { key: "blacklist", title: "黑名单", description: "查询已配置的供应商黑名单与报警记录。", icon: <Ban size={20}/>, onOpen: onOpenBlacklist },
    { key: "risk", title: "风险预估", description: "查看现有审核记录中的风险信息。", icon: <ShieldAlert size={20}/>, onOpen: onOpenRisk },
  ].filter((tool) => canAccessPage(roles, tool.key));
  const showContractStatistics = canAccessPage(roles, "contract");

  return <>
    <header className="delivery-page-header">
      <div>
          <h1 className="delivery-page-title">工作台</h1>
        <p className="delivery-page-subtitle">
          {userName ? `你好，${userName}` : "内审辅助工具"}
        </p>
      </div>
    </header>

    {error && <div role="alert" className="delivery-alert delivery-alert-warning">
      <span className="flex items-center gap-3"><AlertTriangle size={18}/><span><strong className="block">统计数据暂未更新</strong><span className="mt-1 block text-xs">{error}</span></span></span>
      <button type="button" onClick={onRetry} className="delivery-secondary-action">重新读取</button>
    </div>}

    {tools.length > 0 && <section aria-labelledby="business-tools-heading">
      <div className="mb-3">
        <h2 id="business-tools-heading" className="text-base font-semibold text-stone-800">业务工具</h2>
      </div>
      <div className="workbench-tool-grid">
        {tools.map((tool) => <button key={tool.key} type="button" onClick={tool.onOpen}
          className="delivery-surface group flex min-h-32 items-center gap-4 p-4 text-left transition hover:border-orange-200 hover:shadow-sm">
          <span className="delivery-icon-chip text-orange-600">{tool.icon}</span>
          <span className="flex min-w-0 flex-1 flex-col gap-1.5">
            <strong className="text-sm text-stone-800">{tool.title}</strong>
            <span className="text-xs leading-5 text-stone-500">{tool.description}</span>
          </span>
          <ArrowRight size={16} className="shrink-0 text-stone-400 group-hover:text-orange-700"/>
        </button>)}
      </div>
    </section>}

    {auxiliaryTools.length > 0 && <section aria-label="辅助工具" className="workbench-tool-grid">
      {auxiliaryTools.map((tool) => <button key={tool.key} type="button" onClick={tool.onOpen}
        className="delivery-surface flex min-h-20 items-center gap-4 p-4 text-left transition hover:border-orange-200 hover:shadow-sm">
        <span className="delivery-icon-chip text-stone-600">{tool.icon}</span>
        <span className="flex-1"><strong className="block text-sm text-stone-800">{tool.title}</strong><span className="mt-1 block text-xs leading-5 text-stone-500">{tool.description}</span></span>
        <ArrowRight size={15} className="text-stone-400"/>
      </button>)}
    </section>}

    {showContractStatistics && <section className="delivery-surface p-4" aria-labelledby="workbench-statistics-heading">
      <div className="flex items-center gap-3">
        <span className="delivery-icon-chip"><BarChart3 size={18}/></span>
        <div><h2 id="workbench-statistics-heading" className="font-semibold text-stone-800">合同审核数据概览</h2><p className="mt-1 text-xs text-stone-500">当前已持久化合同审核任务的只读统计</p></div>
      </div>
      <dl className="mt-5 grid grid-cols-2 gap-3 md:grid-cols-4">
        {view.stats.map((item) => <div key={item.key} className="rounded-xl bg-stone-50 px-4 py-3">
          <dt className="text-xs text-stone-500">{item.label}</dt>
          <dd className="mt-2 text-xl font-semibold tabular-nums text-stone-800">{loading ? "--" : item.value ?? "--"}</dd>
        </div>)}
      </dl>
    </section>}
  </>;
}

export function DeliveryWorkbenchPage({ dataSource = contractAuditTaskDataSource, onOpenBudget, onOpenProcurement, onOpenContract, onOpenBlacklist, onOpenRisk }: DeliveryWorkbenchPageProps) {
  const { user } = useAuth();
  const roles = user?.roles ?? [];
  const showContractStatistics = canAccessPage(roles, "contract");
  const [summary, setSummary] = useState<ContractAuditTaskSummary | null>(null);
  const [recentTasks, setRecentTasks] = useState<ContractAuditTaskItem[]>([]);
  const [todayTotal, setTodayTotal] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [nonce, setNonce] = useState(0);
  const [lastUpdatedAt, setLastUpdatedAt] = useState<Date | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    if (!showContractStatistics) {
      setSummary(null);
      setRecentTasks([]);
      setTodayTotal(null);
      setError(null);
      setLoading(false);
      return () => controller.abort();
    }
    setLoading(true);
    setError(null);
    void loadDeliveryWorkbench(dataSource, new Date(), controller.signal).then((loaded) => {
      if (controller.signal.aborted) return;
      setSummary(loaded.summary);
      setRecentTasks(loaded.recentTasks);
      setTodayTotal(loaded.todayTotal);
      setLastUpdatedAt(new Date());
    }).catch((cause: unknown) => {
      if (cause instanceof Error && cause.name === "AbortError") return;
      if (controller.signal.aborted) return;
      setSummary(null);
      setRecentTasks([]);
      setTodayTotal(null);
      setError(userFacingError(cause, "合同内审统计暂时无法读取"));
    }).finally(() => {
      if (!controller.signal.aborted) setLoading(false);
    });
    return () => controller.abort();
  }, [dataSource, nonce, showContractStatistics, user?.userId]);

  const view = useMemo(() => workbenchPresentation(summary, recentTasks, todayTotal), [summary, recentTasks, todayTotal]);
  return <section className="delivery-page space-y-6">
    <DeliveryWorkbenchContent view={view} loading={loading} error={error}
      onRetry={() => setNonce((value) => value + 1)}
      onOpenBudget={onOpenBudget} onOpenProcurement={onOpenProcurement} onOpenContract={onOpenContract}
      onOpenBlacklist={onOpenBlacklist} onOpenRisk={onOpenRisk}
      roles={roles}
      userName={user?.realName || user?.username || "已登录用户"}
      lastUpdatedLabel={lastUpdatedAt?.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" })}/>
  </section>;
}
