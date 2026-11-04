import { AlertTriangle, ChevronRight, LoaderCircle } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { userFacingError } from "../../api/userFacingError";
import { useAuth } from "../../auth/AuthProvider";
import type { ContractAuditTaskItem, ContractAuditTaskSummary } from "../contract/data/contractAuditTaskDataSource";
import { contractAuditTaskDataSource } from "../contract/data/contractAuditTaskDataSource";
import { contractDashboardPresentation } from "./contractDashboard";

export function ContractDashboardPage({ onOpenContractList, onOpenContractTask }: {
  onOpenContractList: () => void;
  onOpenContractTask: (task: ContractAuditTaskItem) => void;
}) {
  const { user } = useAuth();
  const [summary, setSummary] = useState<ContractAuditTaskSummary | null>(null);
  const [tasks, setTasks] = useState<ContractAuditTaskItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [nonce, setNonce] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    void Promise.all([
      contractAuditTaskDataSource.summary({ signal: controller.signal }),
      contractAuditTaskDataSource.list({ page: 1, size: 5, signal: controller.signal }),
    ]).then(([nextSummary, page]) => {
      if (controller.signal.aborted) return;
      setSummary(nextSummary);
      setTasks(page.items);
    }).catch((cause: unknown) => {
      if (cause instanceof Error && cause.name === "AbortError") return;
      setSummary(null);
      setTasks([]);
      setError(userFacingError(cause, "合同内审数据暂时无法读取"));
    }).finally(() => {
      if (!controller.signal.aborted) setLoading(false);
    });
    return () => controller.abort();
  }, [nonce]);
  const view = useMemo(() => contractDashboardPresentation(summary, tasks), [summary, tasks]);
  const today = new Intl.DateTimeFormat("zh-CN", {
    year: "numeric", month: "2-digit", day: "2-digit", weekday: "long",
  }).format(new Date());

  return <section className="space-y-6 p-6">
    <header className="rounded-lg border border-blue-200 bg-blue-50/60 p-5">
      <p className="text-xs text-blue-700">{today}</p>
      <h1 className="mt-1 text-lg font-semibold text-slate-900">你好，{user?.realName || user?.username || "已登录用户"}</h1>
      <p className="mt-1 text-sm text-slate-600">这里展示当前合同内审任务的真实进展。</p>
    </header>
    {error && <div role="alert" className="flex items-center justify-between rounded border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800">
      <span className="flex items-center gap-2"><AlertTriangle size={15}/>{error}</span>
      <button type="button" onClick={() => setNonce((value) => value + 1)} className="text-blue-700">重新读取</button>
    </div>}
    <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
      {view.kpis.map((item) => <button type="button" key={item.key} onClick={onOpenContractList}
        className="rounded-lg border border-slate-200 bg-white p-5 text-left hover:border-blue-300">
        <span className="text-sm text-slate-600">{item.label}</span>
        <strong className="mt-2 block text-2xl text-slate-900">{loading ? "--" : item.value ?? "--"}</strong>
      </button>)}
    </div>
    <section className="overflow-hidden rounded-lg border border-slate-200 bg-white">
      <div className="flex items-center justify-between border-b border-slate-200 px-5 py-4">
        <h2 className="font-semibold text-slate-900">当前合同内审</h2>
        <button type="button" onClick={onOpenContractList} className="text-sm text-blue-700">查看全部</button>
      </div>
      {loading && <p className="flex items-center gap-2 p-5 text-sm text-slate-600"><LoaderCircle size={15} className="animate-spin"/>正在读取合同内审任务</p>}
      {!loading && !error && view.rows.length === 0 && <p className="p-6 text-center text-sm text-slate-500">暂无合同内审任务</p>}
      <div className="divide-y divide-slate-100">
        {view.rows.map((row) => <button type="button" key={row.taskId} onClick={() => onOpenContractTask(row.task)}
          className="grid w-full grid-cols-1 gap-2 px-5 py-4 text-left hover:bg-blue-50/50 md:grid-cols-[1.2fr_1.4fr_1fr_auto_auto_auto] md:items-center">
          <span className="text-sm text-slate-900">{row.projectName}</span>
          <span className="text-sm font-medium text-slate-900">{row.contractName}</span>
          <span className="text-sm text-slate-600">{row.supplier}</span>
          <span className="text-xs text-slate-600">{row.statusLabel}</span>
          <span className="text-xs text-amber-700">{row.riskLabel}</span>
          <span className="flex items-center gap-2 text-xs text-slate-500">{new Date(row.updatedAt).toLocaleString("zh-CN")}<ChevronRight size={13}/></span>
        </button>)}
      </div>
    </section>
  </section>;
}
