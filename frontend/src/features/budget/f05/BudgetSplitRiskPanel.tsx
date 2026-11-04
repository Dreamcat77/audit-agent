import { useEffect, useMemo, useState } from "react";
import {
  AlertTriangle,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Loader2,
  RefreshCw,
  ShieldAlert,
} from "lucide-react";
import {
  checkSplitRisk,
  getSplitRiskAssessment,
  listProcurementProjects,
} from "../../../api";
import type { ProcurementProject, SplitRiskCheckResponse, SplitRiskRecord } from "../../../api";
import {
  clearSplitRiskOperation,
  isAmbiguousSplitRiskOperation,
  loadOrCreateSplitRiskOperation,
} from "./budgetSplitRiskOperation";

const LAST_ASSESSMENT_KEY = "audit-agent:budget:f05:last-assessment";

interface ProjectGroup {
  key: string;
  projectName: string;
  budgetSubject: string;
  projects: ProcurementProject[];
  analyzableProjects: ProcurementProject[];
  totalAmount: number;
}

export function groupSplitRiskProjects(projects: ProcurementProject[]): ProjectGroup[] {
  const groups = new Map<string, ProcurementProject[]>();
  projects.forEach((project) => {
    const subject = normalize(project.subjectCode || project.subjectName || "");
    const key = `${subject}|${normalize(project.projectName)}`;
    groups.set(key, [...(groups.get(key) ?? []), project]);
  });
  return [...groups.entries()].map(([key, members]) => ({
    key,
    projectName: members[0]?.projectName || "未命名项目",
    budgetSubject: members[0]?.subjectName || members[0]?.subjectCode || "未录入预算科目",
    projects: members,
    analyzableProjects: members.filter(isAnalyzable),
    totalAmount: members.reduce((sum, item) => sum + (item.budgetAmount ?? 0), 0),
  })).sort((left, right) => left.projectName.localeCompare(right.projectName, "zh-CN"));
}

export function BudgetSplitRiskPanel({
  onOpenProcurement,
}: {
  onOpenProcurement?: (projectId: string) => void;
}) {
  const [projects, setProjects] = useState<ProcurementProject[]>([]);
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set());
  const [result, setResult] = useState<SplitRiskCheckResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [checking, setChecking] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const page = await listProcurementProjects({ page: 1, size: 100 });
      setProjects(page.items);
      setSelectedIds((current) => current.filter((id) => {
        const project = page.items.find((item) => item.id === id);
        return project ? isAnalyzable(project) : false;
      }));
      const latestId = window.localStorage.getItem(LAST_ASSESSMENT_KEY);
      if (latestId) {
        try { setResult(await getSplitRiskAssessment(latestId)); } catch { /* stale local pointer */ }
      }
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "采购记录加载失败");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);

  const projectGroups = useMemo(() => groupSplitRiskProjects(projects), [projects]);
  const selectedProjects = useMemo(
    () => projects.filter((project) => selectedIds.includes(project.id)),
    [projects, selectedIds],
  );

  const runCheck = async () => {
    if (selectedIds.length < 2 || checking) return;
    const operationKey = loadOrCreateSplitRiskOperation(selectedIds);
    setChecking(true);
    setError(null);
    try {
      const next = await checkSplitRisk({ procurementIds: selectedIds }, operationKey);
      clearSplitRiskOperation(selectedIds, operationKey);
      window.localStorage.setItem(LAST_ASSESSMENT_KEY, next.assessmentId);
      setResult(next);
    } catch (reason) {
      if (!isAmbiguousSplitRiskOperation(reason)) {
        clearSplitRiskOperation(selectedIds, operationKey);
      }
      setError(reason instanceof Error ? reason.message : "预算项目拆分风险分析失败");
    } finally {
      setChecking(false);
    }
  };

  const toggleProject = (id: string) => {
    setSelectedIds((current) => current.includes(id)
      ? current.filter((value) => value !== id)
      : [...current, id]);
  };

  const toggleGroupSelection = (group: ProjectGroup) => {
    const groupIds = group.analyzableProjects.map((project) => project.id);
    setSelectedIds((current) => {
      const allSelected = groupIds.length > 0 && groupIds.every((id) => current.includes(id));
      if (allSelected) return current.filter((id) => !groupIds.includes(id));
      return [...new Set([...current, ...groupIds])];
    });
  };

  const toggleGroupExpansion = (key: string) => {
    setExpandedGroups((current) => {
      const next = new Set(current);
      if (next.has(key)) next.delete(key); else next.add(key);
      return next;
    });
  };

  return (
    <section className="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-[0_2px_8px_rgba(15,23,42,0.03)] sm:p-7">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="flex items-center gap-2"><ShieldAlert size={19} className="text-[#ef9739]" /><h2 className="text-lg font-bold text-slate-800">预算项目拆分风险分析</h2></div>
          <p className="mt-2 text-sm text-slate-500">先按项目查看申报汇总，展开后可核对和处理每条采购分支。</p>
        </div>
        <button type="button" onClick={() => void load()} disabled={loading} className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 px-3 py-2 text-xs text-slate-600 hover:bg-slate-50 disabled:opacity-50">
          <RefreshCw size={13} className={loading ? "animate-spin" : ""} />刷新记录
        </button>
      </div>

      {error && <div className="mt-4 flex items-center gap-2 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700"><AlertTriangle size={14} />{error}</div>}

      {loading ? (
        <div className="mt-5 flex min-h-28 items-center justify-center text-sm text-slate-500"><Loader2 size={16} className="mr-2 animate-spin" />正在读取采购记录</div>
      ) : projectGroups.length === 0 ? (
        <p className="mt-5 rounded-lg bg-slate-50 px-4 py-6 text-center text-sm text-slate-500">暂无采购申报记录</p>
      ) : (
        <div className="mt-5 max-h-[30rem] space-y-2 overflow-auto rounded-xl border border-slate-200 bg-slate-50/60 p-2">
          {projectGroups.map((group) => {
            const expanded = expandedGroups.has(group.key);
            const groupIds = group.analyzableProjects.map((project) => project.id);
            const selectedCount = groupIds.filter((id) => selectedIds.includes(id)).length;
            const allSelected = groupIds.length > 0 && selectedCount === groupIds.length;
            return (
              <article key={group.key} className="overflow-hidden rounded-lg border border-slate-200 bg-white">
                <div className="flex items-center gap-3 px-3 py-3 sm:px-4">
                  <input
                    type="checkbox"
                    aria-label={`选择项目 ${group.projectName}`}
                    title={groupIds.length === 0 ? "该项目没有可参与分析的分支" : "选择该项目的全部可分析分支"}
                    disabled={groupIds.length === 0}
                    checked={allSelected}
                    onChange={() => toggleGroupSelection(group)}
                  />
                  <button type="button" onClick={() => toggleGroupExpansion(group.key)} className="flex min-w-0 flex-1 items-center gap-3 text-left">
                    {expanded ? <ChevronDown size={17} className="shrink-0 text-[#ef9739]" /> : <ChevronRight size={17} className="shrink-0 text-slate-400" />}
                    <span className="min-w-0 flex-1">
                      <strong className="block truncate text-sm text-slate-900">{group.projectName}</strong>
                      <span className="mt-1 block text-xs text-slate-500">{group.budgetSubject} · {group.projects.length} 条采购分支</span>
                    </span>
                  </button>
                  <div className="shrink-0 text-right">
                    <strong className="block text-sm text-slate-800">{money(group.totalAmount)}</strong>
                    <span className="mt-1 block text-[11px] text-slate-400">已选 {selectedCount} / {groupIds.length}</span>
                  </div>
                </div>
                {expanded && (
                  <div className="border-t border-slate-200 bg-slate-50/70 px-3 py-2 sm:px-4">
                    <div className="overflow-x-auto">
                      <table className="w-full min-w-[760px] text-left text-xs">
                        <thead className="text-slate-400"><tr><th className="px-2 py-2">选择</th><th className="px-2 py-2">采购分支</th><th className="px-2 py-2">供应商</th><th className="px-2 py-2 text-right">金额</th><th className="px-2 py-2">记录时间</th><th className="px-2 py-2">状态</th><th className="px-2 py-2 text-right">操作</th></tr></thead>
                        <tbody className="divide-y divide-slate-200">{group.projects.map((project) => {
                          const issue = analysisIssue(project);
                          return <tr key={project.id} className="text-slate-700"><td className="px-2 py-2"><input aria-label={`选择采购分支 ${project.projectCode}`} title={issue || "选择该采购分支"} type="checkbox" disabled={Boolean(issue)} checked={selectedIds.includes(project.id)} onChange={() => toggleProject(project.id)} /></td><td className="px-2 py-2"><div className="font-medium text-slate-900">{project.projectCode}</div><div className="mt-0.5 text-slate-400">{project.projectName}</div></td><td className="px-2 py-2">{project.supplierName || "未录入"}</td><td className="px-2 py-2 text-right font-medium">{money(project.budgetAmount)}</td><td className="px-2 py-2">{formatDate(project.createdTime)}</td><td className={`px-2 py-2 ${issue ? "text-amber-700" : "text-emerald-700"}`}>{issue || "可参与分析"}</td><td className="px-2 py-2 text-right"><button type="button" onClick={() => onOpenProcurement?.(project.id)} disabled={!onOpenProcurement} className="inline-flex items-center gap-1 text-[#d97706] hover:text-[#b45309] disabled:cursor-not-allowed disabled:text-slate-300">进入采购审查<ChevronRight size={13} /></button></td></tr>;
                        })}</tbody>
                      </table>
                    </div>
                  </div>
                )}
              </article>
            );
          })}
        </div>
      )}

      <div className="mt-4 flex flex-wrap items-center justify-between gap-3">
        <span className="text-xs text-slate-500">已选择 {selectedProjects.length} 条采购分支，合计 {money(selectedProjects.reduce((sum, item) => sum + (item.budgetAmount ?? 0), 0))}</span>
        <button type="button" disabled={selectedIds.length < 2 || checking} onClick={() => void runCheck()} className="inline-flex items-center gap-2 rounded-lg bg-[#f6a142] px-4 py-2.5 text-sm font-semibold text-white hover:bg-[#ed9130] disabled:cursor-not-allowed disabled:opacity-40">{checking ? <Loader2 size={15} className="animate-spin" /> : <ShieldAlert size={15} />}{checking ? "分析中..." : "执行风险分析"}</button>
      </div>

      {result && <SplitRiskResult result={result} projects={projects} onOpenProcurement={onOpenProcurement} />}
    </section>
  );
}

function SplitRiskResult({
  result,
  projects,
  onOpenProcurement,
}: {
  result: SplitRiskCheckResponse;
  projects: ProcurementProject[];
  onOpenProcurement?: (projectId: string) => void;
}) {
  const [expandedRecords, setExpandedRecords] = useState<Set<string>>(new Set());
  const risky = result.records.length > 0;
  const projectById = useMemo(() => new Map(projects.map((project) => [project.id, project])), [projects]);

  const toggleRecord = (key: string) => {
    setExpandedRecords((current) => {
      const next = new Set(current);
      if (next.has(key)) next.delete(key); else next.add(key);
      return next;
    });
  };

  return <div className={`mt-5 rounded-xl border p-4 ${risky ? "border-amber-200 bg-amber-50" : "border-emerald-200 bg-emerald-50"}`}>
    <div className="flex items-start gap-3">{risky ? <AlertTriangle size={18} className="mt-0.5 text-amber-700" /> : <CheckCircle2 size={18} className="mt-0.5 text-emerald-700" />}<div><h3 className={`font-semibold ${risky ? "text-amber-800" : "text-emerald-800"}`}>{risky ? `发现 ${result.records.length} 个项目需关注` : "本次选定范围未触发已配置规则"}</h3><p className="mt-1 text-xs text-slate-600">风险等级 {riskLabel(result.riskLevel)} · 评分 {result.riskScore} · 分析记录号 {result.assessmentId}</p></div></div>
    <dl className="mt-4 grid grid-cols-2 gap-3 text-xs sm:grid-cols-4"><Metric label="选定记录金额" value={money(result.totalAmount)} /><Metric label="触发规则记录占比" value={`${result.splitRatio.toFixed(2)}%`} /><Metric label="最高窗口记录占比" value={`${result.timeConcentration.toFixed(2)}%`} /><Metric label="同供应商记录占比" value={`${result.supplierConcentration.toFixed(2)}%`} /></dl>
    {result.records.length > 0 && <div className="mt-4 space-y-2">{result.records.map((record) => <RiskProject key={recordKey(record)} record={record} projectById={projectById} expanded={expandedRecords.has(recordKey(record))} onToggle={() => toggleRecord(recordKey(record))} onOpenProcurement={onOpenProcurement} />)}</div>}
  </div>;
}

function RiskProject({
  record,
  projectById,
  expanded,
  onToggle,
  onOpenProcurement,
}: {
  record: SplitRiskRecord;
  projectById: Map<string, ProcurementProject>;
  expanded: boolean;
  onToggle: () => void;
  onOpenProcurement?: (projectId: string) => void;
}) {
  const branches = record.procurementIds.map((id) => projectById.get(id)).filter((value): value is ProcurementProject => Boolean(value));
  const projectName = branches[0]?.projectName || record.budgetSubject;
  return <article className="overflow-hidden rounded-lg border border-amber-200 bg-white text-xs text-slate-700">
    <button type="button" onClick={onToggle} className="flex w-full items-center gap-3 px-3 py-3 text-left">
      {expanded ? <ChevronDown size={16} className="shrink-0 text-amber-700" /> : <ChevronRight size={16} className="shrink-0 text-amber-700" />}
      <span className="min-w-0 flex-1"><strong className="block truncate text-sm text-slate-900">{projectName}</strong><span className="mt-1 block text-slate-500">{record.budgetSubject} · {record.procurementCount} 条采购分支 · 合计 {money(record.totalAmount)}</span></span>
      <span className="shrink-0 rounded bg-amber-100 px-2 py-0.5 text-amber-800">{riskLabel(record.riskLevel)} {record.riskScore} 分</span>
    </button>
    {expanded && <div className="border-t border-amber-100 bg-amber-50/40 px-3 py-3"><p className="mb-2 text-slate-500">规则时间窗口 {formatDate(record.windowStart)} 至 {formatDate(record.windowEnd)} · {record.supplierName ? `供应商 ${record.supplierName}` : "未形成一致供应商信息"}</p><div className="space-y-2">{branches.map((branch) => <div key={branch.id} className="flex flex-wrap items-center gap-3 rounded border border-slate-200 bg-white px-3 py-2"><div className="min-w-0 flex-1"><strong className="block text-slate-900">{branch.projectCode}</strong><span className="mt-1 block text-slate-500">{money(branch.budgetAmount)} · {formatDate(branch.createdTime)} · {branch.supplierName || "未录入供应商"}</span></div><button type="button" onClick={() => onOpenProcurement?.(branch.id)} disabled={!onOpenProcurement} className="inline-flex items-center gap-1 text-[#d97706] hover:text-[#b45309] disabled:cursor-not-allowed disabled:text-slate-300">进入采购审查<ChevronRight size={13} /></button></div>)}</div></div>}
  </article>;
}

function recordKey(record: SplitRiskRecord): string {
  return `${record.ruleCode}-${record.procurementIds.join("-")}`;
}

function Metric({ label, value }: { label: string; value: string }) {
  return <div><dt className="text-slate-500">{label}</dt><dd className="mt-1 font-semibold text-slate-900">{value}</dd></div>;
}

function money(value: number | null): string {
  return value === null ? "-" : new Intl.NumberFormat("zh-CN", { style: "currency", currency: "CNY" }).format(value);
}

function formatDate(value: string): string {
  const timestamp = Date.parse(value);
  return Number.isFinite(timestamp) ? new Date(timestamp).toLocaleDateString() : "-";
}

function analysisIssue(project: ProcurementProject): string | null {
  if (project.budgetAmount === null || project.budgetAmount <= 0) return "缺少有效金额";
  if (!project.subjectCode?.trim() && !project.subjectName?.trim()) return "缺少预算科目";
  if (!project.projectName.trim()) return "缺少项目名称";
  if (!Number.isFinite(Date.parse(project.createdTime))) return "缺少记录时间";
  return null;
}

function isAnalyzable(project: ProcurementProject): boolean {
  return analysisIssue(project) === null;
}

function normalize(value: string): string {
  return value.replace(/\s+/g, "").toLocaleLowerCase();
}

function riskLabel(level: string): string {
  return level === "CRITICAL" ? "严重" : level === "WARNING" ? "警告" : "未触发规则";
}
