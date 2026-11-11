import { useEffect, useState } from "react";
import { AlertTriangle, ArrowRight, FileWarning, RefreshCw, ShieldAlert } from "lucide-react";
import { userFacingError } from "../../api/userFacingError";
import type { ContractAuditTaskDataSource, ContractAuditTaskItem } from "../contract/data/contractAuditTaskDataSource";
import { contractAuditTaskDataSource } from "../contract/data/contractAuditTaskDataSource";
import { loadPersistedRiskAssessment, type PersistedRiskAssessment } from "./riskAssessmentProjection";

export interface RiskAssessmentContentProps {
  loading: boolean;
  error: string | null;
  assessment: PersistedRiskAssessment | null;
  onRetry: () => void;
  onOpenTask: (task: ContractAuditTaskItem) => void;
}

export function RiskAssessmentContent({ loading, error, assessment, onRetry, onOpenTask }: RiskAssessmentContentProps) {
  return (
    <section className="delivery-page space-y-6">
      <header className="delivery-page-header">
        <div><h1 className="delivery-page-title">风险预估</h1><p className="delivery-page-subtitle">仅展示各合同最新已完成审核中的关注事项</p></div>
      </header>

      {error && <div role="alert" className="delivery-alert delivery-alert-warning">
        <span className="flex items-center gap-3"><AlertTriangle size={18}/><span><strong className="block">风险记录暂不可用</strong><span className="mt-1 block text-xs">{error}</span></span></span>
        <button type="button" onClick={onRetry} className="delivery-secondary-action"><RefreshCw size={14}/>重新读取</button>
      </div>}

      {!error && <div className="delivery-surface p-5">
        <p className="text-xs font-medium text-stone-500">统计范围</p>
        <p className="mt-2 text-sm text-stone-700">{assessment?.scopeLabel ?? (loading ? "正在读取…" : "当前范围暂无可显示记录")}</p>
        <dl className="mt-5 grid gap-3 sm:grid-cols-2">
          <div className="rounded-xl bg-amber-50 px-4 py-3"><dt className="text-xs text-amber-800">需要复核</dt><dd className="mt-2 text-2xl font-semibold text-amber-900">{loading || !assessment ? "--" : assessment.reviewRequiredCount}</dd></div>
          <div className="rounded-xl bg-blue-50 px-4 py-3"><dt className="text-xs text-blue-800">材料不足</dt><dd className="mt-2 text-2xl font-semibold text-blue-900">{loading || !assessment ? "--" : assessment.materialInsufficientCount}</dd></div>
        </dl>
      </div>}

      {!error && assessment && <div className="space-y-3" aria-label="持久化风险记录">
        {assessment.items.map(({ task, coverage }) => (
          <article key={task.taskId} className="delivery-surface flex flex-col gap-4 p-5 lg:flex-row lg:items-center">
            <span className={`delivery-icon-chip ${task.risk.materialInsufficient ? "text-blue-700" : "text-amber-700"}`}>
              {task.risk.materialInsufficient ? <FileWarning size={19}/> : <ShieldAlert size={19}/>}
            </span>
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-center gap-2">
                <strong className="text-sm text-stone-800">{task.project?.projectName ?? "未命名项目"}</strong>
                <span className="text-xs text-stone-400">{task.project?.projectNo ?? "项目编号未提供"}</span>
              </div>
              <p className="mt-1 text-sm text-stone-700">{task.contract.contractName} · {task.contract.contractNo}</p>
              <div className="mt-3 flex flex-wrap gap-2 text-xs">
                {task.risk.riskCount > 0 && <span className="rounded-full bg-amber-50 px-2.5 py-1 text-amber-800">关注 {task.risk.riskCount} 项</span>}
                {task.risk.materialInsufficient && <span className="rounded-full bg-blue-50 px-2.5 py-1 text-blue-800">材料不足</span>}
                <span className="rounded-full bg-stone-100 px-2.5 py-1 text-stone-600">{coverage.label}</span>
                {coverage.unassessed && <span className="rounded-full bg-stone-100 px-2.5 py-1 text-stone-600">尚有范围未完成审查</span>}
              </div>
            </div>
            <button type="button" onClick={() => onOpenTask(task)} className="delivery-secondary-action shrink-0">
              查看审核结果<ArrowRight size={14}/>
            </button>
          </article>
        ))}
        {assessment.items.length === 0 && <div className="delivery-empty-state"><ShieldAlert size={24}/><strong>当前范围暂无风险记录</strong><span>这里不会用示例数字或模拟结果填充。</span></div>}
      </div>}
    </section>
  );
}

export function RiskAssessmentPage({
  dataSource = contractAuditTaskDataSource,
  onOpenTask,
}: {
  dataSource?: ContractAuditTaskDataSource;
  onOpenTask: (task: ContractAuditTaskItem) => void;
}) {
  const [assessment, setAssessment] = useState<PersistedRiskAssessment | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [nonce, setNonce] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    void loadPersistedRiskAssessment(dataSource, controller.signal).then((loaded) => {
      if (!controller.signal.aborted) setAssessment(loaded);
    }).catch((cause: unknown) => {
      if (cause instanceof Error && cause.name === "AbortError") return;
      if (!controller.signal.aborted) {
        setAssessment(null);
        setError(userFacingError(cause, "审核记录暂时无法读取"));
      }
    }).finally(() => {
      if (!controller.signal.aborted) setLoading(false);
    });
    return () => controller.abort();
  }, [dataSource, nonce]);

  return <RiskAssessmentContent loading={loading} error={error} assessment={assessment}
    onRetry={() => setNonce((value) => value + 1)} onOpenTask={onOpenTask}/>;
}
