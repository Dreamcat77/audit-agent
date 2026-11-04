import { useEffect, useMemo, useState } from "react";
import {
  AlertTriangle,
  ArrowDown,
  ArrowUp,
  CheckCircle2,
  ClipboardCheck,
  Loader2,
  Minus,
  Plus,
  Trash2,
} from "lucide-react";
import { getBudgetReview } from "../../../api";
import type { BudgetReviewResponse } from "../../../api";
import {
  createBudgetApprovalValidation,
  getBudgetApprovalRules,
  listBudgetApprovalValidations,
} from "./budgetApprovalProgramApi";
import { startBudgetApprovalProgramInitialLoad } from "./budgetApprovalProgramInitialLoad";
import {
  clearBudgetApprovalOperation,
  isAmbiguousBudgetApprovalOperation,
  loadOrCreateBudgetApprovalOperation,
} from "./budgetApprovalOperation";
import {
  addActualStep,
  moveActualStep,
  removeActualStep,
  validationResultPresentation,
} from "./budgetApprovalProgramPresentation";
import type {
  BudgetApprovalRuleGroup,
  BudgetApprovalRuleSet,
  BudgetApprovalStep,
  BudgetApprovalValidation,
} from "./types";

interface BudgetApprovalProgramPanelProps {
  taskId: string;
}

export function BudgetApprovalProgramPanel({ taskId }: BudgetApprovalProgramPanelProps) {
  const [review, setReview] = useState<BudgetReviewResponse | null>(null);
  const [ruleSet, setRuleSet] = useState<BudgetApprovalRuleSet | null>(null);
  const [history, setHistory] = useState<BudgetApprovalValidation[]>([]);
  const [selectedItemId, setSelectedItemId] = useState("");
  const [selectedStepCode, setSelectedStepCode] = useState("");
  const [actualSteps, setActualSteps] = useState<string[]>([]);
  const [result, setResult] = useState<BudgetApprovalValidation | null>(null);
  const [loading, setLoading] = useState(true);
  const [validating, setValidating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [historyWarning, setHistoryWarning] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    setHistoryWarning(null);
    void startBudgetApprovalProgramInitialLoad(
      {
        loadReview: () => getBudgetReview(taskId),
        loadRules: getBudgetApprovalRules,
        loadHistory: () => listBudgetApprovalValidations({ taskId, page: 1, size: 5 }),
      },
      {
        onCoreLoaded: (nextReview, nextRules) => {
          if (cancelled) return;
          setReview(nextReview);
          setRuleSet(nextRules);
          setSelectedItemId(nextReview.items[0]?.id ?? "");
          setLoading(false);
        },
        onCoreError: (reason) => {
          if (cancelled) return;
          setError(reason instanceof Error ? reason.message : "审批程序校验数据加载失败");
          setLoading(false);
        },
        onHistoryLoaded: (nextHistory) => {
          if (cancelled) return;
          setHistory(nextHistory.items);
          setHistoryWarning(null);
        },
        onHistoryWarning: () => {
          if (!cancelled) setHistoryWarning("最近校验记录暂时无法读取");
        },
      },
    );
    return () => { cancelled = true; };
  }, [taskId]);

  const selectedItem = review?.items.find((item) => item.id === selectedItemId) ?? null;
  const availableSteps = useMemo(() => {
    const unique = new Map<string, BudgetApprovalStep>();
    for (const group of ruleSet?.ruleGroups ?? []) {
      for (const rule of group.rules) {
        if (!unique.has(rule.stepCode)) {
          unique.set(rule.stepCode, { code: rule.stepCode, displayName: rule.stepName });
        }
      }
    }
    return [...unique.values()];
  }, [ruleSet]);
  const stepsByCode = useMemo(
    () => new Map(availableSteps.map((step) => [step.code, step])),
    [availableSteps],
  );
  const unselectedSteps = availableSteps.filter((step) => !actualSteps.includes(step.code));
  const nextSelectableCode = unselectedSteps.some((step) => step.code === selectedStepCode)
    ? selectedStepCode
    : unselectedSteps[0]?.code ?? "";

  const runValidation = async () => {
    if (!selectedItem || validating) return;
    const operationKey = loadOrCreateBudgetApprovalOperation(taskId, selectedItem.id);
    setValidating(true);
    setError(null);
    try {
      const nextResult = await createBudgetApprovalValidation({
        taskId,
        itemId: selectedItem.id,
        actualSteps,
        idempotencyKey: operationKey,
      });
      clearBudgetApprovalOperation(taskId, selectedItem.id, operationKey);
      setResult(nextResult);
      try {
        const nextHistory = await listBudgetApprovalValidations({ taskId, page: 1, size: 5 });
        setHistory(nextHistory.items);
        setHistoryWarning(null);
      } catch (historyFailure) {
        setHistoryWarning(historyFailure instanceof Error
          ? `校验已完成，但历史记录刷新失败：${historyFailure.message}`
          : "校验已完成，但历史记录刷新失败");
      }
    } catch (reason) {
      if (!isAmbiguousBudgetApprovalOperation(reason)) {
        clearBudgetApprovalOperation(taskId, selectedItem.id, operationKey);
      }
      setError(reason instanceof Error ? reason.message : "审批程序校验失败");
    } finally {
      setValidating(false);
    }
  };

  if (loading) {
    return (
      <div className="flex min-h-64 items-center justify-center rounded-lg border border-slate-200 bg-white text-sm text-slate-600">
        <Loader2 size={18} className="mr-2 animate-spin text-blue-600" />
        正在读取 F01 持久化数据与审批规则
      </div>
    );
  }

  return (
    <div className="space-y-5">
      {error && (
        <div className="flex items-center gap-2 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          <AlertTriangle size={16} className="shrink-0" />
          {error}
        </div>
      )}
      {historyWarning && (
        <div className="flex items-center gap-2 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-700">
          <AlertTriangle size={16} className="shrink-0" />
          {historyWarning}
        </div>
      )}

      <section className="rounded-lg border border-slate-200 bg-white p-5">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h2 className="text-base font-semibold text-slate-900">F01 预算审查摘要</h2>
            <p className="mt-1 text-xs text-slate-600">F04 只读使用已完成的 F01 任务与预算项目</p>
          </div>
          <span className="rounded border border-blue-200 bg-blue-50 px-2.5 py-1 text-xs font-medium text-blue-700">
            {review?.task.status ?? "暂无数据"}
          </span>
        </div>
        <dl className="mt-4 grid gap-3 text-sm sm:grid-cols-2 xl:grid-cols-5">
          <Summary label="任务标识" value={review?.task.taskId ?? taskId} mono />
          <Summary label="文件名" value={review?.task.fileName ?? "暂无数据"} />
          <Summary label="预算年度" value={review?.task.budgetYear ?? "暂无数据"} />
          <Summary label="部门" value={review?.task.departmentCode ?? "暂无数据"} />
          <Summary label="项目数量" value={String(review?.totalItems ?? 0)} />
        </dl>
      </section>

      <section className="rounded-lg border border-slate-200 bg-white p-5">
        <h2 className="text-base font-semibold text-slate-900">选择预算项目</h2>
        <p className="mt-1 text-xs text-slate-600">申报金额由后端从所选 F01 BudgetReviewItem 重新读取</p>
        <select
          value={selectedItemId}
          onChange={(event) => {
            setSelectedItemId(event.target.value);
            setActualSteps([]);
            setResult(null);
          }}
          className="mt-4 w-full rounded-lg border border-slate-300 bg-white px-3 py-2.5 text-sm text-slate-900 outline-none focus:border-blue-500"
        >
          {(review?.items ?? []).map((item) => (
            <option key={item.id} value={item.id}>
              第 {item.rowNumber ?? "-"} 行 · {item.subjectName || "暂无科目名称"} · {formatAmount(item.declaredAmount)}
            </option>
          ))}
        </select>
        {selectedItem && (
          <dl className="mt-3 grid gap-3 rounded-lg bg-slate-50 p-4 text-sm sm:grid-cols-4">
            <Summary label="材料行号" value={String(selectedItem.rowNumber ?? "暂无数据")} />
            <Summary label="科目名称" value={selectedItem.subjectName ?? "暂无数据"} />
            <Summary label="项目编号" value={selectedItem.projectCode ?? "暂无数据"} />
            <Summary label="申报金额" value={formatAmount(selectedItem.declaredAmount)} />
          </dl>
        )}
      </section>

      <section className="rounded-lg border border-slate-200 bg-white p-5">
        <div className="flex items-center justify-between gap-3">
          <div>
            <h2 className="text-base font-semibold text-slate-900">审批权限矩阵</h2>
            <p className="mt-1 text-xs text-slate-600">规则集 {ruleSet?.ruleSetCode ?? "暂无数据"}</p>
          </div>
          <span className="rounded border border-amber-200 bg-amber-50 px-2.5 py-1 text-xs text-amber-700">
            演示规则，待甲方最终确认
          </span>
        </div>
        <div className="mt-4 grid gap-4 lg:grid-cols-2">
          <RuleGroupCard title="程序要求" group={findGroup(ruleSet, "PROCEDURE_REQUIREMENT")} />
          <RuleGroupCard title="审批权限" group={findGroup(ruleSet, "AUTHORITY_LEVEL")} />
        </div>
      </section>

      <section className="rounded-lg border border-slate-200 bg-white p-5">
        <h2 className="text-base font-semibold text-slate-900">实际审批记录（联调前规则测试）</h2>
        <p className="mt-1 text-xs text-slate-600">当前未接入 OA；请按实际发生顺序添加、删除或调整步骤</p>
        <div className="mt-4 flex flex-wrap gap-2">
          <select
            value={nextSelectableCode}
            onChange={(event) => setSelectedStepCode(event.target.value)}
            disabled={unselectedSteps.length === 0}
            className="min-w-56 rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 disabled:opacity-50"
          >
            {unselectedSteps.map((step) => (
              <option key={step.code} value={step.code}>{step.displayName}</option>
            ))}
          </select>
          <button
            type="button"
            disabled={!nextSelectableCode}
            onClick={() => {
              setActualSteps((current) => addActualStep(current, nextSelectableCode));
              setSelectedStepCode("");
              setResult(null);
            }}
            className="inline-flex items-center gap-1.5 rounded-lg bg-blue-600 px-3 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-40"
          >
            <Plus size={15} />添加步骤
          </button>
        </div>

        <div className="mt-4 space-y-2">
          {actualSteps.length === 0 ? (
            <div className="rounded-lg border border-dashed border-slate-300 bg-slate-50 px-4 py-8 text-center text-sm text-slate-600">
              暂无实际审批步骤
            </div>
          ) : actualSteps.map((code, index) => (
            <div key={code} className="flex items-center gap-3 rounded-lg border border-slate-200 bg-slate-50 px-3 py-2.5">
              <span className="flex h-6 w-6 items-center justify-center rounded-full bg-blue-100 text-xs font-semibold text-blue-700">
                {index + 1}
              </span>
              <span className="min-w-0 flex-1 text-sm font-medium text-slate-900">
                {stepsByCode.get(code)?.displayName ?? code}
              </span>
              <button type="button" aria-label="上移" disabled={index === 0}
                onClick={() => { setActualSteps((current) => moveActualStep(current, index, -1)); setResult(null); }}
                className="rounded p-1.5 text-slate-600 hover:bg-white disabled:opacity-30"><ArrowUp size={15} /></button>
              <button type="button" aria-label="下移" disabled={index === actualSteps.length - 1}
                onClick={() => { setActualSteps((current) => moveActualStep(current, index, 1)); setResult(null); }}
                className="rounded p-1.5 text-slate-600 hover:bg-white disabled:opacity-30"><ArrowDown size={15} /></button>
              <button type="button" aria-label="删除"
                onClick={() => { setActualSteps((current) => removeActualStep(current, index)); setResult(null); }}
                className="rounded p-1.5 text-red-700 hover:bg-red-50"><Trash2 size={15} /></button>
            </div>
          ))}
        </div>

        <button
          type="button"
          onClick={runValidation}
          disabled={!selectedItem || validating}
          className="mt-4 inline-flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2.5 text-sm font-semibold text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-40"
        >
          {validating ? <Loader2 size={16} className="animate-spin" /> : <ClipboardCheck size={16} />}
          开始审批程序校验
        </button>
      </section>

      {result && <ValidationResultCard result={result} />}

      <section className="rounded-lg border border-slate-200 bg-white p-5">
        <h2 className="text-base font-semibold text-slate-900">最近校验记录</h2>
        {history.length === 0 ? (
          <p className="mt-3 text-sm text-slate-600">暂无校验记录</p>
        ) : (
          <div className="mt-3 overflow-x-auto">
            <table className="w-full min-w-[680px] text-left text-sm">
              <thead className="bg-slate-50 text-slate-600">
                <tr><th className="px-3 py-2">时间</th><th className="px-3 py-2">行号</th><th className="px-3 py-2">申报金额</th><th className="px-3 py-2">结论</th></tr>
              </thead>
              <tbody>
                {history.map((entry) => (
                  <tr key={entry.validationId} className="border-t border-slate-200 text-slate-900">
                    <td className="px-3 py-2">{new Date(entry.createdAt).toLocaleString()}</td>
                    <td className="px-3 py-2">{entry.sourceRowNumber ?? "-"}</td>
                    <td className="px-3 py-2">{formatAmount(entry.declaredAmount)}</td>
                    <td className={`px-3 py-2 font-medium ${entry.passed ? "text-emerald-700" : "text-amber-700"}`}>{entry.resultSummary}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <div className="grid gap-4 lg:grid-cols-2">
        <section className="rounded-lg border border-dashed border-slate-300 bg-slate-50 p-5">
          <div className="flex items-center gap-2 text-sm font-semibold text-slate-700"><Minus size={16} />项目拆分风险检测</div>
          <p className="mt-2 text-xs text-slate-600">同科目、同供应商 30 天窗口风险分析</p>
          <span className="mt-3 inline-flex rounded border border-emerald-200 bg-emerald-50 px-2 py-1 text-xs text-emerald-700">已在预算校验页接入 F05</span>
        </section>
        <section className="rounded-lg border border-dashed border-slate-300 bg-slate-50 p-5">
          <div className="flex items-center gap-2 text-sm font-semibold text-slate-700"><Minus size={16} />生成预算审查报告</div>
          <p className="mt-2 text-xs text-slate-600">完成后续预算审查环节后生成报告</p>
          <button type="button" disabled className="mt-3 rounded border border-slate-300 bg-white px-3 py-2 text-xs text-slate-500 opacity-60">生成报告</button>
        </section>
      </div>
    </div>
  );
}

function Summary({ label, value, mono = false }: { label: string; value: string; mono?: boolean }) {
  return <div><dt className="text-xs text-slate-500">{label}</dt><dd className={`mt-1 break-all font-medium text-slate-900 ${mono ? "font-mono text-xs" : ""}`}>{value}</dd></div>;
}

function RuleGroupCard({ title, group }: { title: string; group: BudgetApprovalRuleGroup | null }) {
  return (
    <div className="rounded-lg border border-slate-200 bg-slate-50 p-4">
      <h3 className="text-sm font-semibold text-slate-900">{title}</h3>
      <div className="mt-3 space-y-2">
        {(group?.rules ?? []).map((rule) => (
          <div key={rule.stepCode} className="flex items-center justify-between gap-3 rounded border border-slate-200 bg-white px-3 py-2">
            <span className="text-xs font-medium text-blue-700">{rule.thresholdLabel}</span>
            <span className="text-sm text-slate-900">{rule.stepName}</span>
          </div>
        ))}
        {!group?.rules.length && <p className="text-xs text-slate-600">暂无规则数据</p>}
      </div>
    </div>
  );
}

function ValidationResultCard({ result }: { result: BudgetApprovalValidation }) {
  const presentation = validationResultPresentation(result);
  const passed = presentation.tone === "PASS";
  return (
    <section className={`rounded-lg border p-5 ${passed ? "border-emerald-200 bg-emerald-50" : "border-amber-200 bg-amber-50"}`}>
      <div className="flex items-start gap-3">
        {passed ? <CheckCircle2 size={20} className="mt-0.5 text-emerald-700" /> : <AlertTriangle size={20} className="mt-0.5 text-amber-700" />}
        <div><h2 className={`text-base font-semibold ${passed ? "text-emerald-800" : "text-amber-800"}`}>{presentation.title}</h2><p className="mt-1 text-sm text-slate-700">{presentation.detail}</p></div>
      </div>
      <dl className="mt-4 grid gap-3 rounded-lg border border-white/70 bg-white/80 p-4 text-sm sm:grid-cols-2 xl:grid-cols-4">
        <ResultValue label="申报金额" value={formatAmount(result.declaredAmount)} />
        <ResultValue label="应履行程序" value={stepSequence(result.requiredProcedureSteps)} />
        <ResultValue label="应审批层级" value={result.requiredAuthorityStep.displayName} />
        <ResultValue label="实际审批记录" value={stepSequence(result.actualSteps)} />
        <ResultValue label="缺失程序" value={stepSequence(result.missingProcedureSteps)} />
        <ResultValue label="程序顺序" value={result.procedureOrderValid ? "符合" : "不符合"} />
        <ResultValue label="审批层级匹配" value={result.authorityMatched ? "匹配" : "不匹配"} />
        <ResultValue label="校验结论" value={result.resultSummary} />
      </dl>
    </section>
  );
}

function ResultValue({ label, value }: { label: string; value: string }) {
  return <div><dt className="text-xs text-slate-500">{label}</dt><dd className="mt-1 font-medium text-slate-900">{value}</dd></div>;
}

function findGroup(ruleSet: BudgetApprovalRuleSet | null, code: string): BudgetApprovalRuleGroup | null {
  return ruleSet?.ruleGroups.find((group) => group.code === code) ?? null;
}

function stepSequence(steps: BudgetApprovalStep[]): string {
  return steps.length === 0 ? "无" : steps.map((step) => step.displayName).join(" → ");
}

function formatAmount(amount: number | null | undefined): string {
  if (amount === null || amount === undefined) return "暂无数据";
  return new Intl.NumberFormat("zh-CN", { style: "currency", currency: "CNY" }).format(amount);
}
