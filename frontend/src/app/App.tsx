import { useState, useEffect, useRef } from "react";
import {
  LayoutDashboard, ShoppingCart, Gavel, FileText, PackageCheck,
  ShieldAlert, Bot, Settings, Bell, Search, ChevronRight, ChevronDown,
  TrendingUp, TrendingDown, AlertTriangle, CheckCircle, Clock,
  XCircle, Send, Paperclip, MoreHorizontal, Filter, Plus, Download,
  Eye, RefreshCw, Zap, Activity, Database, Brain, ArrowUpRight,
  ArrowRight, Circle, Users, Building2, Scale, Cpu, BarChart3, Link,
  Target, Layers, PenLine, BookOpen, ImageIcon, Sparkles, GitBranch,
  Gauge, Award, FlaskConical, ThumbsUp, ThumbsDown, RotateCcw,
  MessageSquare, Wand2, ChevronUp, Hash, Percent, FileSearch, LogOut, Ban,
  Pencil, ListTodo, UserRoundCog
} from "lucide-react";
import {
  LineChart, Line, BarChart, Bar, XAxis, YAxis, Tooltip,
  ResponsiveContainer, PieChart, Pie, Cell, CartesianGrid, ReferenceLine
} from "recharts";
import { ApiTestPage } from "./ApiTestPage";
import { ConfirmDialog, FormDialog, NoticeDialog } from "./components/ConfirmDialog";
import { SpreadsheetHeaderMappingDialog, type HeaderMappingSelection } from "./components/SpreadsheetHeaderMappingDialog";
import { demoVisibleNavigationItems } from "./demoNavigation.ts";
import { presentRoleLabels } from "./appShellPresentation.ts";
import { PortalShell } from "./PortalShell";
import { userFacingError } from "../api/userFacingError";
import { ApiError } from "../api/client";
import { approvalStatusLabel, enumLabel, riskLevelLabel } from "../utils/enumLabels";
import { BudgetAuditWorkspace } from "../features/budget/BudgetAuditWorkspace";
import { BlacklistAlertPage } from "./BlacklistAlertPage";
import ProcurementTenderReviewPanel from "../features/procurement/ProcurementTenderReviewPanel";
import ProcurementReviewWizard from "../features/procurement/review/ProcurementReviewWizard";
import { intakeReviewOperation } from "../features/procurement/review/procurementIntakeReviewStore";
import { ProcurementMaterialUpload } from "../features/procurement/materials/ProcurementMaterialUpload";
import { AutomaticCasePanel } from "../features/procurement/AutomaticCasePanel";
import { materialBatch, ProcurementMaterialBackground } from "../features/procurement/materials/procurementMaterialBatchStore";
import { BudgetMaterialBackground } from "../features/budget/budgetMaterialBatchStore";
import { ProcurementQuoteAdvice } from "../features/procurement/review/ProcurementQuoteAdvice";
import { canCombineProcurementMaterials, mergeProcurementMaterialForm, procurementMaterialForm } from "../features/procurement/materials/procurementMaterialForm";
import { useAuth } from "../auth/AuthProvider";
import ContractAuditWorkspace from "../features/contract/components/ContractReviewWorkspace";
import DirectF02AuditTaskWorkspace from "../features/contract/components/DirectF02AuditTaskWorkspace";
import { ContractPortalHome } from "../features/contract/components/ContractPortalHome";
import { clearContractReviewNavigation, loadContractReviewNavigation } from "../features/contract/contractReviewNavigationPersistence";
import { ProjectWorkspace } from "../features/project/components/ProjectWorkspace";
import { DeliveryWorkbenchPage } from "../features/dashboard/DeliveryWorkbenchPage";
import { RiskAssessmentPage } from "../features/risk/RiskAssessmentPage";
import type { DirectF02AuditTaskRow, ShowcaseAuditTaskRow } from "../features/contract/auditTaskViewModel";
import type { ContractAuditTaskItem } from "../features/contract/data/contractAuditTaskDataSource";
import { directF02AuditTaskRow } from "../features/contract/contractAuditTaskPresentation";
import { showcaseContractDataSource } from "../features/contract/data/showcaseContractDataSource";
import {
  checkBudget,
  checkBlacklist,
  uploadBlacklistBatch,
  previewBlacklistBatchHeaders,
  uploadBlacklistBatchConfirmed,
  uploadProcurementQuoteCheck,
  previewProcurementQuoteHeaders,
  uploadProcurementQuoteCheckConfirmed,
  getProcurementQuoteCheck,
  listProcurementProjects,
  listProcurementSourceProjects,
  getProcurementAgentReview,
  refreshProcurementAgentReview,
  listProcurementFieldConfirmations,
  confirmProcurementField,
  generateProcurementReviewReport,
  getProcurementReviewReport,
  createProcurementProject,
  getProcurementApproval,
  startProcurementApproval,
  approveProcurementApproval,
  returnProcurementApproval,
  addProcurementApprovalComment,
  getProcurementApprovalHistory,
  reassignProcurementApproval,
  listProcurementApprovalTasks,
  updateProcurementProject,
  deleteProcurementProject,
  exportProcurementProject,
  listNotifications,
  getUnreadNotificationCount,
  markNotificationRead,
  listOperationAudits,
  checkSplitRisk,
  checkCollusion,
  checkAttachment,
} from "../api";
import type {
  BlacklistBatchCheckResponse,
  ProcurementQuoteCheckResponse,
  ProcurementProject,
  ProcurementSourceProject,
  ProcurementAgentReview,
  ProcurementFieldConfirmation,
  ProcurementReviewReport,
  ProcurementApproval,
  ProcurementApprovalOperation,
  ProcurementApprovalTask,
  ProcurementNotification,
  OperationAuditRecord,
  SpreadsheetHeaderPreview,
} from "../api";

// ── Types ──────────────────────────────────────────────────────────────────
type Page =
  | "dashboard"
  | "project"
  | "procurement"
  | "procurement-tasks"
  | "budget-check"
  | "bidding"
  | "contract"
  | "settlement"
  | "risk"
  | "blacklist"
  | "agent"
  | "ai-tools"
  | "model-eval"
  | "system"
  | "api-test";

type RiskLevel = "red" | "yellow" | "blue" | "green";

interface AgentResult {
  checkType?: string;
  status?: string;
  needsConfirmation?: boolean;
  confirmation?: ProcurementFieldConfirmation | null;
  title: string;
  level: RiskLevel;
  summary: string;
  aiExplanation?: string | null;
  aiSuggestion?: string | null;
  detailRows?: { label: string; value: string }[];
  items?: { label: string; status: "ok" | "warn" | "error" | "missing" }[];
  canConfirm?: boolean;
  confirmationNote?: string;
  confirmationLoading?: boolean;
  confirmationError?: string | null;
  onConfirmationNoteChange?: (value: string) => void;
  onConfirm?: () => void;
  onEdit?: () => void;
}

// ── Constants ──────────────────────────────────────────────────────────────
const NAV_ITEMS: { id: Page; label: string; icon: React.ReactNode; badge?: number }[] = [
  { id: "dashboard", label: "工作台", icon: <LayoutDashboard size={16} /> },
  { id: "project", label: "项目管理", icon: <Layers size={16} /> },
  { id: "procurement", label: "采购审查", icon: <ShoppingCart size={16} /> },
  { id: "budget-check", label: "预算审查", icon: <FileSearch size={16} /> },
  { id: "bidding", label: "投标评审", icon: <Gavel size={16} /> },
  { id: "contract", label: "合同审核", icon: <FileText size={16} /> },
  { id: "settlement", label: "验收结算", icon: <PackageCheck size={16} />, badge: 2 },
  { id: "risk", label: "风险预估", icon: <ShieldAlert size={16} /> },
  { id: "blacklist", label: "黑名单", icon: <Ban size={16} /> },
  { id: "agent", label: "智能体对话", icon: <Bot size={16} /> },
  { id: "ai-tools", label: "AI扩展工具", icon: <Sparkles size={16} /> },
  { id: "model-eval", label: "模型效果评估", icon: <Gauge size={16} /> },
  { id: "api-test", label: "API测试", icon: <Activity size={16} /> },
  { id: "system", label: "系统管理", icon: <Settings size={16} /> },
];

// ── Shared Components ──────────────────────────────────────────────────────
function RiskBadge({ level, compact, title }: { level: RiskLevel; compact?: boolean; title?: string }) {
  const map = {
    red: { bg: "bg-red-50", text: "text-red-700", border: "border-red-200", dot: "bg-red-400", label: "红色拦截" },
    yellow: { bg: "bg-amber-50", text: "text-amber-700", border: "border-amber-200", dot: "bg-amber-400", label: "黄色提示" },
    blue: { bg: "bg-blue-50", text: "text-blue-700", border: "border-blue-200", dot: "bg-blue-400", label: "蓝色提醒" },
    green: { bg: "bg-emerald-50", text: "text-emerald-700", border: "border-emerald-200", dot: "bg-emerald-400", label: "通过" },
  };
  const s = map[level];
  return (
    <span title={title} className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded text-xs font-mono border ${s.bg} ${s.text} ${s.border}`}>
      <span className={`size-1.5 rounded-full ${s.dot}`} />
      {!compact && s.label}
    </span>
  );
}

function AgentCard({ result, expanded: defaultExpanded = false }: { result: AgentResult; expanded?: boolean }) {
  const [open, setOpen] = useState(defaultExpanded);
  const [detailOpen, setDetailOpen] = useState(false);
  const icon = result.level === "red" ? <XCircle size={14} className="text-red-700" /> :
    result.level === "yellow" ? <AlertTriangle size={14} className="text-amber-700" /> :
      result.level === "green" ? <CheckCircle size={14} className="text-emerald-700" /> :
        <Circle size={14} className="text-blue-700" />;

  return (
    <div className={`rounded border ${result.level === "red" ? "border-red-200 bg-red-50" : result.level === "yellow" ? "border-amber-200 bg-amber-50" : "border-slate-200 bg-slate-50"} overflow-hidden`}>
      <button
        onClick={() => setOpen(!open)}
        className="w-full flex items-center justify-between px-4 py-3 text-left hover:bg-slate-50 transition-colors"
      >
        <div className="flex items-center gap-3">
          <span className="flex items-center gap-1.5">
            <Bot size={13} className="text-blue-700" />
            <span className="text-xs text-blue-700 font-mono">智能体审核</span>
          </span>
          <span className="text-slate-400">·</span>
          <span className="text-sm font-medium text-slate-900">{result.title}</span>
          {result.confirmation && (
            <span className="rounded border border-emerald-200 bg-emerald-50 px-2 py-0.5 text-[11px] font-medium text-emerald-700">已确认</span>
          )}
          {!result.confirmation && result.needsConfirmation && (
            <span className="rounded border border-amber-200 bg-amber-50 px-2 py-0.5 text-[11px] font-medium text-amber-700">待确认</span>
          )}
        </div>
        <div className="flex items-center gap-3">
          {icon}
          <RiskBadge level={result.level} />
          {open ? <ChevronDown size={13} className="text-slate-400" /> : <ChevronRight size={13} className="text-slate-400" />}
        </div>
      </button>
      {open && (
        <div className="px-4 pb-4 border-t border-slate-200">
          <div className="mt-3 space-y-2 text-sm">
            <p className="text-slate-600"><span className="font-medium text-slate-700">规则结果：</span>{result.summary}</p>
            {result.aiExplanation && (
              <p className="border-l-2 border-blue-300 pl-3 text-slate-700">
                <span className="font-medium text-blue-700">AI 解释：</span>{result.aiExplanation}
              </p>
            )}
            {result.aiSuggestion && (
              <p className="border-l-2 border-amber-300 pl-3 text-slate-700">
                <span className="font-medium text-amber-700">AI 建议：</span>{result.aiSuggestion}
              </p>
            )}
          </div>
          {result.items && (
            <div className="space-y-1.5">
              {result.items.map((item, i) => {
                const ic = item.status === "error" ? <XCircle size={12} className="text-red-700 shrink-0" /> :
                  item.status === "warn" ? <AlertTriangle size={12} className="text-amber-700 shrink-0" /> :
                    item.status === "missing" ? <Circle size={12} className="text-blue-700 shrink-0" /> :
                      <CheckCircle size={12} className="text-emerald-700 shrink-0" />;
                return (
                  <div key={i} className="flex items-center gap-2 text-xs">
                    {ic}
                    <span className={item.status === "error" ? "text-red-700" : item.status === "warn" ? "text-amber-700" : item.status === "missing" ? "text-slate-400" : "text-slate-600"}>
                      {item.label}
                    </span>
                  </div>
                );
              })}
            </div>
          )}
          <div className="flex gap-2 mt-4">
            <button
              type="button"
              onClick={(event) => { event.stopPropagation(); setDetailOpen(true); }}
              className="text-xs px-3 py-1.5 rounded bg-blue-50 text-blue-700 border border-blue-200 hover:bg-blue-100 transition-colors"
            >
              查看详情
            </button>
            {result.level !== "green" && (
              <button className="text-xs px-3 py-1.5 rounded bg-slate-50 text-slate-500 border border-slate-200 hover:bg-slate-100 transition-colors">忽略并说明</button>
            )}
          </div>
        </div>
      )}
      {detailOpen && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 px-4"
          role="presentation"
          onClick={() => setDetailOpen(false)}
        >
          <div
            className="w-full max-w-xl rounded-lg border border-slate-200 bg-white p-5 shadow-xl"
            role="dialog"
            aria-modal="true"
            aria-label={`${result.title}详情`}
            onClick={(event) => event.stopPropagation()}
          >
            <div className="flex items-start justify-between gap-4">
              <div>
                <div className="text-xs text-blue-700">智能体审核</div>
                <h3 className="mt-1 text-base font-semibold text-slate-900">{result.title}</h3>
              </div>
              <RiskBadge level={result.level} />
            </div>
            <div className="mt-4 space-y-3 text-sm">
              <div>
                <div className="font-medium text-slate-700">规则结果</div>
                <div className="mt-1 text-slate-600">{result.summary || "-"}</div>
              </div>
              {result.detailRows?.map((row) => (
                <div key={row.label} className="flex gap-3 border-t border-slate-100 pt-2">
                  <span className="w-20 shrink-0 text-slate-500">{row.label}</span>
                  <span className="text-slate-700">{row.value || "-"}</span>
                </div>
              ))}
              <div className="border-t border-slate-100 pt-3">
                <div className="font-medium text-blue-700">AI 解释</div>
                <div className="mt-1 text-slate-600">{result.aiExplanation || "当前未返回 AI 解释。"}</div>
              </div>
              <div>
                <div className="font-medium text-amber-700">AI 建议</div>
                <div className="mt-1 text-slate-600">{result.aiSuggestion || "当前未返回 AI 建议。"}</div>
              </div>
              {result.needsConfirmation && (
                <div className={`rounded border px-3 py-3 ${result.confirmation ? "border-emerald-200 bg-emerald-50" : "border-amber-200 bg-amber-50"}`}>
                  {result.confirmation ? (
                    <div className="space-y-1 text-xs text-emerald-900">
                      <div className="font-medium">已确认</div>
                      <div>确认人：{result.confirmation.confirmerName}</div>
                      <div>确认时间：{formatProcurementDateTime(result.confirmation.confirmedTime)}</div>
                      <div>信息当前值：{result.confirmation.fieldValue || "未填写"}</div>
                      {result.confirmation.confirmationNote && <div>确认说明：{result.confirmation.confirmationNote}</div>}
                    </div>
                  ) : (
                    <div className="space-y-2 text-xs text-amber-950">
                      <div className="font-medium text-amber-900">该信息需要人工确认</div>
                      <div>当前值：{result.detailRows?.find((row) => row.label === "实际值")?.value || "未填写"}</div>
                      <div>规则提示：{result.summary || "请核对信息是否符合业务实际"}</div>
                      {result.aiExplanation && <div>AI 提示：{result.aiExplanation}</div>}
                      {result.aiSuggestion && <div>AI 建议：{result.aiSuggestion}</div>}
                      {result.canConfirm ? (
                        <>
                          <input
                            value={result.confirmationNote ?? ""}
                            onChange={(event) => result.onConfirmationNoteChange?.(event.target.value)}
                            placeholder="确认说明（可选）"
                            className="w-full rounded border border-slate-200 bg-white px-2.5 py-2 text-xs text-slate-900 outline-none focus:border-blue-400"
                          />
                          {result.confirmationError && <div className="text-red-700">{result.confirmationError}</div>}
                          <div className="flex flex-wrap gap-2">
                            <button
                              type="button"
                              onClick={result.onConfirm}
                              disabled={result.confirmationLoading}
                              className="rounded border border-emerald-200 bg-emerald-50 px-2.5 py-1.5 font-medium text-emerald-700 hover:bg-emerald-100 disabled:cursor-not-allowed disabled:opacity-50"
                            >
                              {result.confirmationLoading ? "保存中…" : "确认无误，继续"}
                            </button>
                            <button
                              type="button"
                              onClick={result.onEdit}
                              className="rounded border border-slate-200 bg-white px-2.5 py-1.5 text-slate-700 hover:bg-slate-50"
                            >
                              返回修改
                            </button>
                          </div>
                        </>
                      ) : (
                        <div className="text-amber-800">当前账号无确认权限，请联系项目申请人处理。</div>
                      )}
                    </div>
                  )}
                </div>
              )}
            </div>
            <div className="mt-5 flex justify-end">
              <button
                type="button"
                onClick={() => setDetailOpen(false)}
                className="rounded border border-slate-200 bg-white px-3 py-1.5 text-xs text-slate-600 hover:bg-slate-50"
              >
                关闭
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function StatCard({ label, value, sub, trend, accent }: { label: string; value: string; sub?: string; trend?: "up" | "down"; accent?: string }) {
  return (
    <div className="bg-card border border-slate-200 rounded-lg p-5 flex flex-col gap-2">
      <span className="text-xs text-muted-foreground font-mono uppercase tracking-wider">{label}</span>
      <div className="flex items-end gap-2">
        <span className={`text-2xl font-semibold ${accent || "text-slate-900"}`}>{value}</span>
        {trend && (
          <span className={`flex items-center text-xs mb-0.5 ${trend === "up" ? "text-emerald-700" : "text-red-700"}`}>
            {trend === "up" ? <TrendingUp size={12} /> : <TrendingDown size={12} />}
          </span>
        )}
      </div>
      {sub && <span className="text-xs text-muted-foreground">{sub}</span>}
    </div>
  );
}

function FlowStep({ label, status, index }: { label: string; status: "done" | "active" | "pending" | "agent" | "risk"; index: number }) {
  const map = {
    done: "bg-emerald-500 border-emerald-500 text-white",
    active: "bg-blue-500 border-blue-500 text-white ring-2 ring-blue-500/30",
    pending: "bg-transparent border-slate-300 text-slate-400",
    agent: "bg-transparent border-purple-200 text-purple-700",
    risk: "bg-transparent border-red-500 text-red-700",
  };
  return (
    <div className="flex items-center gap-1.5">
      {index > 0 && <div className="w-4 h-px bg-slate-100" />}
      <div className="flex flex-col items-center gap-1">
        <div className={`size-5 rounded-full border flex items-center justify-center text-[10px] font-mono ${map[status]}`}>
          {status === "done" ? "✓" : index + 1}
        </div>
        <span className="text-[9px] text-slate-500 whitespace-nowrap">{label}</span>
      </div>
    </div>
  );
}

// ── Contract Audit Page ────────────────────────────────────────────────────
type ContractNavigationIntent =
  | { type: "OPEN_ANALYSIS" }
  | { type: "OPEN_LIST" }
  | { type: "OPEN_TASK"; task: ContractAuditTaskItem }
  | { type: "CREATE_CONTRACT" };

function ContractPage({ initialNavigation = null, onNavigationConsumed, onOpenProject }: {
  initialNavigation?: ContractNavigationIntent | null;
  onNavigationConsumed?: () => void;
  onOpenProject: (projectId: string) => void;
}) {
  const [selectedShowcase, setSelectedShowcase] = useState<ShowcaseAuditTaskRow | null>(null);
  const [selectedDirectF02, setSelectedDirectF02] = useState<DirectF02AuditTaskRow | null>(null);
  useEffect(() => {
    if (!initialNavigation) return;
    setSelectedShowcase(null);
    setSelectedDirectF02(initialNavigation.type === "OPEN_TASK"
      ? directF02AuditTaskRow(initialNavigation.task) : null);
    onNavigationConsumed?.();
  }, [initialNavigation, onNavigationConsumed]);

  if (selectedDirectF02) {
    return <DirectF02AuditTaskWorkspace key={selectedDirectF02.rowId ?? selectedDirectF02.taskId}
      row={selectedDirectF02}
      onBack={() => setSelectedDirectF02(null)} />;
  }

  if (selectedShowcase) {
    return (
      <ContractAuditWorkspace
        key={selectedShowcase.rowId}
        contractId={selectedShowcase.contractDisplayId}
        contractTitle={selectedShowcase.contract.contractName}
        contractStatus="量化一致性审查完成"
        supplier={selectedShowcase.contract.partyB}
        projectName={selectedShowcase.projectName}
        projectNo={selectedShowcase.projectNo}
        initiatedBy={selectedShowcase.initiatedBy}
        createdAt={selectedShowcase.createdAt}
        mode="showcase"
        dataSource={showcaseContractDataSource}
        onBack={() => setSelectedShowcase(null)}
      />
    );
  }

  return <ContractPortalHome onOpenShowcase={setSelectedShowcase}
    onOpenDirectF02={setSelectedDirectF02} onOpenProject={onOpenProject}/>;
}
// ── Agent Chat Page ────────────────────────────────────────────────────────
interface ChatMsg {
  role: "user" | "agent";
  content: string;
  time: string;
  sources?: string[];
}

const initialMessages: ChatMsg[] = [
  {
    role: "user",
    content: "移动采集终端采购的预算有没有超标？",
    time: "09:42",
  },
  {
    role: "agent",
    content: "已调取 PR-2024-0187《移动采集终端批量采购》项目数据。\n\n**结论：预算超标 12.5%**\n\n本次采购预算 ¥45 万，超出《资产配置管理办法》规定的同类设备配置上限 ¥40 万/批次。\n\n**数据明细：**\n- 申报预算：¥450,000.00\n- 配置标准上限：¥400,000.00\n- 超出金额：¥50,000.00（+12.5%）\n- 适用依据：《专用设备配置标准》第 15 条第 3 款\n\n建议：需求经办人应调整预算至配置标准范围内，或提交专项报告说明超标原因，经分管领导审批后方可继续流转。",
    time: "09:42",
    sources: ["《专用设备配置标准》第15条", "PR-2024-0187 需求数据"],
  },
  {
    role: "user",
    content: "供应商A和C有没有关联关系？",
    time: "09:45",
  },
  {
    role: "agent",
    content: "已对 BD-2024-0056《智能卡口设备采购》项目中报名供应商进行关联分析。\n\n**结论：供应商 A 与 C 存在关联关系——红色预警**\n\n**关联证据：**\n- 甲科技有限公司（供应商A）法人：张某某（身份证尾号：...3817）\n- 乙信息技术公司（供应商C）法人：张某某（同一人）\n- 甲科技持有乙信息 15% 股权（工商数据核验）\n- 双方在 2023 年曾联合投标同类项目\n\n已自动生成风险预警 RK-001，建议采购管理员将此情况提交评审委员会，依法认定是否构成串标行为。",
    time: "09:46",
    sources: ["工商数据库实时核验", "企业关联关系图谱"],
  },
];

const chatHistories = [
  { id: 1, title: "移动采集终端预算超标分析", time: "今天 09:42", active: true },
  { id: 2, title: "合同付款条款合规查询", time: "今天 08:20" },
  { id: 3, title: "通信系统控制价审查", time: "昨天 16:45" },
  { id: 4, title: "供应商关联关系分析", time: "昨天 14:30" },
  { id: 5, title: "2024年采购总结分析", time: "07/12" },
  { id: 6, title: "投标文件雷同检测咨询", time: "07/11" },
  { id: 7, title: "竞争性磋商适用条件", time: "07/10" },
  { id: 8, title: "质保金比例合规性", time: "07/09" },
  { id: 9, title: "专家打分偏差处理", time: "07/08" },
  { id: 10, title: "验收材料完整性核查", time: "07/07" },
];

// Tool-call step sequences keyed by question keyword
const agentToolSequences: Record<string, { tool: string; desc: string; result: string }[]> = {
  default: [
    { tool: "knowledge_search", desc: "检索政府采购法及实施条例…", result: "命中 12 条相关法规" },
    { tool: "project_query", desc: "查询关联项目数据库…", result: "获取项目记录 3 份" },
    { tool: "risk_check", desc: "比对历史风险案例库…", result: "相似案例 2 条" },
  ],
  预算: [
    { tool: "budget_engine", desc: "调用预算合规引擎，核查配置标准…", result: "检索《专用设备配置标准》第15条" },
    { tool: "market_price", desc: "拉取近12个月市场价格数据…", result: "获取同类采购均价 ¥3,820/台" },
    { tool: "risk_flag", desc: "生成预算偏离分析报告…", result: "偏离率 +12.5%，触发黄色预警" },
  ],
  合同: [
    { tool: "contract_parser", desc: "解析合同条款结构，提取关键字段…", result: "识别 47 个条款节点" },
    { tool: "three_compare", desc: "执行招标文件 / 投标文件 / 合同三方比对…", result: "发现 3 处偏差" },
    { tool: "legal_check", desc: "查询《民法典》合同编相关条文…", result: "命中第 496、502 条" },
    { tool: "risk_flag", desc: "生成合同风险评估报告…", result: "红色风险 1 项，黄色 2 项" },
  ],
  供应商: [
    { tool: "corp_graph", desc: "查询企业关系图谱数据库…", result: "加载 342 个关联节点" },
    { tool: "equity_check", desc: "核验股权穿透关系（工商数据实时接口）…", result: "穿透至第 3 层" },
    { tool: "bid_history", desc: "检索近5年投标记录…", result: "找到共同投标记录 3 次" },
    { tool: "risk_flag", desc: "评估串标可能性…", result: "关联置信度 87%，触发红色预警" },
  ],
  风险: [
    { tool: "risk_scan", desc: "全局扫描当前在审项目…", result: "47 个项目，扫描中" },
    { tool: "pattern_match", desc: "匹配风险模式库（618 条规则）…", result: "命中 16 个风险点" },
    { tool: "priority_rank", desc: "按严重程度排序预警列表…", result: "红色 5 · 黄色 8 · 蓝色 3" },
  ],
};

const agentResponses: Record<string, string> = {
  default: "已完成分析。根据检索结果，该问题涉及《政府采购法》第26条及相关实施条例规定。建议结合项目具体情况，由采购管理员会同法务部门进行合规性评估，如有疑义可提交审计主任终审。",
  预算: "根据预算合规引擎分析：\n\n**结论：黄色预警 — 预算超标**\n\n申报金额 ¥450,000，超出《专用设备配置标准》第15条第3款规定上限 ¥400,000，超标率 **+12.5%**。\n\n近12个月市场均价为 ¥3,820/台，按200台计算合理预算应为 ¥764,000，与申报值相近，但配置标准设有批次上限，建议：\n1. 拆分为2个批次采购（各≤¥400,000）\n2. 或向分管领导申请超标采购审批并留存依据",
  合同: "合同三方比对完成，发现以下问题：\n\n🔴 **实质性偏差（1项）**\n- 第12条付款条款：合同约定预付款40%，招标文件规定不超过30%，**超出10个百分点**，违反招标文件约束性条款\n\n🟡 **非实质性偏差（2项）**\n- 第8条质保期：合同24个月 vs 投标承诺36个月（对甲方更不利）\n- 第19条违约金：比例从5%下调至3%\n\n**建议：** 第12条须退回修改，预付款比例须降至≤30%方可签署。",
  供应商: "供应商关联分析完成：\n\n**⛔ 红色预警 — 发现供应商关联关系**\n\n- **甲科技有限公司（供应商A）** 法定代表人：张某某\n- **乙信息技术公司（供应商C）** 法定代表人：张某某（同一人）\n- 甲科技持有乙信息 **15% 股权**（工商登记核实）\n- 两家企业2022–2024年曾4次联合或轮流中标同类项目\n\n**已自动生成风险预警 RK-2024-088**，建议提交评审委员会依法认定是否构成串标，必要时启动废标程序。",
  风险: "当前风险预警全景扫描结果：\n\n**🔴 红色拦截（5项）**\n- RK-088 供应商A/C串标疑点（BD-2024-0056）\n- RK-087 合同付款条款偏离（CT-2024-0094）\n- RK-085 合同价款超中标价3.2%（CT-2024-0094）\n- RK-083 资质证书有效期临期（BD-2024-0052）\n- RK-081 控制价异常偏高（PR-2024-0185）\n\n**🟡 黄色提示（8项）** — 均需24h内处置\n\n建议优先处理 RK-088 和 RK-087，两项均涉及正在进行中的审批流程，需在今日14:00前响应。",
};

function AgentPage() {
  const [messages, setMessages] = useState<ChatMsg[]>(initialMessages);
  const [input, setInput] = useState("");
  const [activeHistoryId, setActiveHistoryId] = useState(1);
  const [toolSteps, setToolSteps] = useState<{ tool: string; desc: string; result: string; done: boolean }[]>([]);
  const [isThinking, setIsThinking] = useState(false);
  const [streamingText, setStreamingText] = useState("");
  const [isStreaming, setIsStreaming] = useState(false);
  const messagesEndRef = { current: null as HTMLDivElement | null };

  function sendMessage() {
    if (!input.trim() || isThinking || isStreaming) return;
    const userMsg: ChatMsg = { role: "user", content: input, time: new Date().toTimeString().slice(0, 5) };
    setMessages(prev => [...prev, userMsg]);
    const q = input;
    setInput("");
    setIsThinking(true);
    setToolSteps([]);

    // Pick tool sequence
    const key = Object.keys(agentToolSequences).find(k => k !== "default" && q.includes(k)) ?? "default";
    const steps = agentToolSequences[key];
    const finalResponse = agentResponses[key] ?? agentResponses.default;

    // Animate tool steps one by one
    steps.forEach((step, idx) => {
      setTimeout(() => {
        setToolSteps(prev => [...prev, { ...step, done: false }]);
        setTimeout(() => {
          setToolSteps(prev => prev.map((s, i) => i === prev.length - 1 ? { ...s, done: true } : s));
          if (idx === steps.length - 1) {
            // All steps done — start streaming response
            setIsThinking(false);
            setIsStreaming(true);
            setStreamingText("");
            let charIdx = 0;
            const streamInterval = setInterval(() => {
              charIdx += Math.floor(Math.random() * 4 + 2);
              if (charIdx >= finalResponse.length) {
                charIdx = finalResponse.length;
                clearInterval(streamInterval);
                setIsStreaming(false);
                setStreamingText("");
                setToolSteps([]);
                const sources = key === "供应商" ? ["企业关系图谱", "工商数据库实时接口"] :
                                key === "合同" ? ["招标文件", "投标文件", "合同文本"] :
                                key === "预算" ? ["配置标准库", "市场价格数据库"] :
                                ["政府采购法知识库", "项目数据库"];
                setMessages(prev => [...prev, {
                  role: "agent",
                  content: finalResponse,
                  time: new Date().toTimeString().slice(0, 5),
                  sources,
                }]);
              } else {
                setStreamingText(finalResponse.slice(0, charIdx));
              }
            }, 30);
          }
        }, 700 + Math.random() * 400);
      }, idx * 1200);
    });
  }

  return (
    <div className="h-full flex min-h-0 overflow-hidden">
      {/* History sidebar */}
      <div className="w-56 shrink-0 border-r border-slate-200 flex flex-col">
        <div className="p-3 border-b border-slate-200">
          <button className="w-full flex items-center gap-2 px-3 py-2 rounded bg-blue-50 text-blue-700 text-xs font-medium border border-blue-200 hover:bg-blue-100 transition-colors">
            <Plus size={13} />新建对话
          </button>
        </div>
        <div className="flex-1 overflow-y-auto divide-y divide-slate-200">
          {chatHistories.map(h => (
            <button
              key={h.id}
              onClick={() => setActiveHistoryId(h.id)}
              className={`w-full px-4 py-3 text-left hover:bg-slate-50 transition-colors ${h.id === activeHistoryId ? "bg-slate-50" : ""}`}
            >
              <p className="text-xs text-slate-900 truncate">{h.title}</p>
              <p className="text-[10px] text-muted-foreground mt-0.5">{h.time}</p>
            </button>
          ))}
        </div>
      </div>

      {/* Main chat */}
      <div className="flex-1 flex flex-col min-w-0 min-h-0">
        {/* Context card */}
        <div className="px-5 py-2.5 border-b border-slate-200 flex items-center gap-3 bg-slate-50 shrink-0">
          <span className="text-xs text-muted-foreground">关联项目：</span>
          <span className="text-xs px-2 py-0.5 rounded bg-blue-50 text-blue-700 border border-blue-200 font-mono">BD-2024-0056 · 智能卡口设备采购</span>
          <span className="text-xs px-2 py-0.5 rounded bg-blue-50 text-blue-700 border border-blue-200 font-mono">PR-2024-0187 · 移动采集终端批量采购</span>
          <button className="ml-auto text-xs text-muted-foreground hover:text-slate-600 transition-colors">+ 关联项目</button>
        </div>

        {/* Messages */}
        <div className="flex-1 overflow-y-auto p-5 space-y-5">
          {messages.map((msg, i) => (
            <div key={i} className={`flex gap-3 ${msg.role === "user" ? "flex-row-reverse" : ""}`}>
              <div className={`size-7 rounded-full shrink-0 flex items-center justify-center text-[11px] font-semibold mt-0.5 ${msg.role === "user" ? "bg-blue-100 text-blue-700 border border-blue-200" : "bg-purple-100 text-purple-700 border border-purple-200"}`}>
                {msg.role === "user" ? "我" : <Bot size={13} />}
              </div>
              <div className={`max-w-[80%] ${msg.role === "user" ? "items-end" : "items-start"} flex flex-col gap-1.5`}>
                <div className={`px-4 py-3 rounded-lg text-sm leading-relaxed whitespace-pre-wrap ${msg.role === "user" ? "bg-blue-50 text-slate-900 border border-blue-200" : "bg-card border border-slate-200 text-slate-600"}`}>
                  {msg.content}
                </div>
                {msg.sources && (
                  <div className="flex gap-1.5 flex-wrap">
                    {msg.sources.map((s, j) => (
                      <span key={j} className="text-[10px] px-2 py-0.5 rounded bg-slate-50 text-muted-foreground border border-slate-200 font-mono">
                        📎 {s}
                      </span>
                    ))}
                  </div>
                )}
                <span className="text-[10px] text-muted-foreground px-1">{msg.time}</span>
              </div>
            </div>
          ))}

          {/* Tool call steps + streaming */}
          {(isThinking || isStreaming) && (
            <div className="flex gap-3">
              <div className="size-7 rounded-full shrink-0 flex items-center justify-center mt-0.5 bg-purple-100 text-purple-700 border border-purple-200">
                <Bot size={13} />
              </div>
              <div className="flex-1 max-w-[80%] space-y-2">
                {/* Tool steps */}
                {toolSteps.length > 0 && (
                  <div className="px-4 py-3 rounded-lg bg-slate-50 border border-slate-200 space-y-2">
                    {toolSteps.map((step, i) => (
                      <div key={i} className="flex items-start gap-2 text-xs">
                        <div className={`size-4 rounded flex items-center justify-center shrink-0 mt-0.5 ${step.done ? "bg-emerald-100" : "bg-blue-100"}`}>
                          {step.done
                            ? <CheckCircle size={9} className="text-emerald-700" />
                            : <RefreshCw size={9} className="text-blue-700 animate-spin" />}
                        </div>
                        <div className="flex-1">
                          <span className="font-mono text-[10px] text-purple-700">[{step.tool}]</span>
                          <span className="text-muted-foreground ml-1.5">{step.desc}</span>
                          {step.done && <span className="text-emerald-700 ml-2 text-[10px]">→ {step.result}</span>}
                        </div>
                      </div>
                    ))}
                    {isThinking && (
                      <div className="flex items-center gap-2 text-xs text-muted-foreground">
                        <span className="size-4 flex items-center justify-center"><RefreshCw size={9} className="text-blue-700 animate-spin" /></span>
                        <span>等待下一步工具调用…</span>
                      </div>
                    )}
                  </div>
                )}
                {/* Streaming text */}
                {isStreaming && streamingText && (
                  <div className="px-4 py-3 rounded-lg bg-card border border-slate-200 text-sm text-slate-600 leading-relaxed whitespace-pre-wrap">
                    {streamingText}
                    <span className="inline-block w-0.5 h-4 bg-blue-400 ml-0.5 animate-pulse align-middle" />
                  </div>
                )}
              </div>
            </div>
          )}
          <div ref={messagesEndRef} />
        </div>

        {/* Input */}
        <div className="px-5 py-4 border-t border-slate-200 shrink-0">
          <div className="flex gap-2 items-end bg-card border border-slate-200 rounded-lg px-4 py-3 focus-within:border-blue-200 transition-colors">
            <textarea
              value={input}
              onChange={e => setInput(e.target.value)}
              onKeyDown={e => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); sendMessage(); } }}
              placeholder="向智能体提问……例如：这个合同的付款条款有没有问题？"
              rows={2}
              className="flex-1 bg-transparent text-sm text-slate-900 placeholder:text-muted-foreground resize-none focus:outline-none"
            />
            <div className="flex items-center gap-2">
              <button className="text-muted-foreground hover:text-slate-600 transition-colors">
                <Paperclip size={14} />
              </button>
              <button
                onClick={sendMessage}
                className="size-8 rounded-lg bg-blue-500 hover:bg-blue-400 transition-colors flex items-center justify-center"
              >
                <Send size={13} className="text-white" />
              </button>
            </div>
          </div>
          <p className="text-[10px] text-muted-foreground mt-2 text-center">智能体可检索制度库、项目数据、供应商信息 · 审计辅助工具，结果仅供参考</p>
        </div>
      </div>

      {/* Right context panel */}
      <div className="w-64 shrink-0 border-l border-slate-200 flex flex-col hidden xl:flex">
        <div className="px-4 py-3 border-b border-slate-200">
          <p className="text-xs font-semibold text-slate-900">上下文信息</p>
        </div>
        <div className="flex-1 overflow-y-auto p-4 space-y-4">
          <div>
            <p className="text-[10px] text-muted-foreground font-mono uppercase mb-2">当前角色</p>
            <div className="flex items-center gap-2">
              <div className="size-7 rounded-full bg-blue-100 flex items-center justify-center text-xs text-blue-700">李</div>
              <div>
                <p className="text-xs text-slate-900">李建国</p>
                <p className="text-[10px] text-muted-foreground">采购管理员</p>
              </div>
            </div>
          </div>
          <div>
            <p className="text-[10px] text-muted-foreground font-mono uppercase mb-2">快捷问询</p>
            <div className="space-y-1.5">
              {[
                "本月有哪些红色风险未处理？",
                "查一下最近有没有超预算的项目",
                "这个合同的审批进度怎么样？",
                "招标文件里有没有歧视性条款？",
              ].map((q, i) => (
                <button
                  key={i}
                  onClick={() => setInput(q)}
                  className="w-full text-left text-xs px-3 py-2 rounded bg-slate-50 text-slate-600 border border-slate-200 hover:bg-slate-100 hover:border-slate-200 transition-colors"
                >
                  {q}
                </button>
              ))}
            </div>
          </div>
          <div>
            <p className="text-[10px] text-muted-foreground font-mono uppercase mb-2">知识库</p>
            <div className="space-y-1.5 text-[10px] text-muted-foreground">
              {["政府采购法及实施条例", "招标投标法", "采购管理办法", "财务管理制度", "资产配置标准"].map(k => (
                <div key={k} className="flex items-center gap-1.5">
                  <Database size={9} className="text-blue-700" />
                  <span>{k}</span>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

// ── Procurement Page ───────────────────────────────────────────────────────
function procurementStatusLabel(status: string) {
  return enumLabel(status);
}



function formatProcurementDate(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleDateString("zh-CN");
}

function formatProcurementDateTime(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("zh-CN", { hour12: false });
}

function toUiRiskLevel(level: string): RiskLevel {
  if (level === "CRITICAL") return "red";
  if (level === "WARNING") return "yellow";
  if (level === "INFO") return "green";
  return "blue";
}

function procurementReviewBadge(project: ProcurementProject) {
  if (project.agentReviewStatus !== "AVAILABLE" || !project.agentRiskLevel) {
    return <span className="inline-flex items-center gap-1.5 rounded border border-slate-200 bg-slate-50 px-2 py-0.5 text-xs font-mono text-slate-500" title="该项目尚未执行智能体预审"><span className="size-1.5 rounded-full bg-slate-300" />未检查</span>;
  }
  return <RiskBadge level={toUiRiskLevel(project.agentRiskLevel)} title={project.agentReviewTime ? `上次预审：${formatProcurementDateTime(project.agentReviewTime)}` : "已完成预审"} />;
}

function toAgentItemStatus(status: string): "ok" | "warn" | "error" | "missing" {
  if (status === "PASSED") return "ok";
  if (status === "FAILED") return "error";
  if (status === "PENDING" || status === "NOT_CHECKED") return "missing";
  return "warn";
}

function toAgentResults(
  review: ProcurementAgentReview,
  confirmations: ProcurementFieldConfirmation[] = [],
  options: {
    canConfirm?: boolean;
    confirmationNotes?: Record<string, string>;
    confirmationLoading?: string | null;
    confirmationError?: string | null;
    onConfirmationNoteChange?: (fieldCode: string, value: string) => void;
    onConfirm?: (fieldCode: string) => void;
    onEdit?: () => void;
  } = {},
): AgentResult[] {
  const confirmationsByField = new Map(confirmations.map((confirmation) => [confirmation.fieldCode, confirmation]));
  return review.items.map((item) => ({
    checkType: item.checkType,
    status: item.status,
    needsConfirmation: item.needsConfirmation,
    confirmation: confirmationsByField.get(item.checkType) ?? null,
    title: item.checkName,
    level: toUiRiskLevel(item.riskLevel || review.riskLevel),
    summary: item.detail,
    aiExplanation: item.aiExplanation,
    aiSuggestion: item.aiSuggestion,
    detailRows: [
      { label: "校验状态", value: confirmationsByField.has(item.checkType) ? "已确认" : enumLabel(item.status) },
      ...(item.expectedValue ? [{ label: "期望值", value: item.expectedValue }] : []),
      ...(item.actualValue ? [{ label: "实际值", value: item.actualValue }] : []),
      ...(item.suggestion ? [{ label: "规则建议", value: item.suggestion }] : []),
    ],
    items: [
      { label: `校验结果：${enumLabel(item.status)}`, status: toAgentItemStatus(item.status) },
      ...(item.expectedValue ? [{ label: `期望值：${item.expectedValue}`, status: "ok" as const }] : []),
      ...(item.actualValue ? [{ label: `实际值：${item.actualValue}`, status: "ok" as const }] : []),
      ...(item.suggestion ? [{ label: `处理建议：${item.suggestion}`, status: "warn" as const }] : []),
    ],
    canConfirm: options.canConfirm,
    confirmationNote: options.confirmationNotes?.[item.checkType] ?? "",
    confirmationLoading: options.confirmationLoading === item.checkType,
    confirmationError: options.confirmationError,
    onConfirmationNoteChange: (value) => options.onConfirmationNoteChange?.(item.checkType, value),
    onConfirm: () => options.onConfirm?.(item.checkType),
    onEdit: options.onEdit,
  }));
}

interface NewProcurementForm {
  materialAutoValues?: Record<string, string>;
  materialConflicts?: string[];
  sourceProjectId: string;
  sourceMaterialIds: string[];
  projectName: string;
  departmentName: string;
  procurementType: string;
  budgetAmount: string;
  subjectCode: string;
  subjectName: string;
  procurementMethod: string;
  requirementDescription: string;
  applicantName: string;
  attachmentFile: File | null;
}

function emptyNewProcurementForm(): NewProcurementForm {
  return {
    sourceProjectId: "",
    sourceMaterialIds: [],
    projectName: "",
    departmentName: "",
    procurementType: "",
    budgetAmount: "",
    subjectCode: "",
    subjectName: "",
    procurementMethod: "",
    requirementDescription: "",
    applicantName: "",
    attachmentFile: null,
  };
}

function NewProcurementDialog({
  open,
  mode,
  form,
  materialResetVersion,
  onClearMaterials,
  actorId,
  onMaterialParsed,
  onMaterialUse,
  onMaterialBusy,
  existingAttachmentName,
  loading,
  closingBlocked,
  error,
  onChange,
  onClose,
  onSubmit,
}: {
  open: boolean;
  mode: "create" | "edit";
  form: NewProcurementForm;
  materialResetVersion: number;
  onClearMaterials: () => void;
  actorId: string;
  onMaterialParsed: () => void;
  onMaterialUse: (source: ProcurementSourceProject) => void;
  onMaterialBusy: (busy: boolean) => void;
  existingAttachmentName?: string | null;
  loading: boolean;
  closingBlocked: boolean;
  error: string | null;
  onChange: (field: keyof NewProcurementForm, value: string | string[] | File | null) => void;
  onClose: () => void;
  onSubmit: (event: React.FormEvent<HTMLFormElement>) => void;
}) {
  if (!open) return null;
  const editing = mode === "edit";
  const textFields: Array<[keyof NewProcurementForm, string, string]> = [
    ["projectName", "项目名称", "请输入采购项目名称"],
    ["departmentName", "申请部门", "请输入申请部门"],
    ["subjectCode", "预算科目编码", "请输入科目编码"],
    ["subjectName", "预算科目名称", "请输入科目名称"],
    ["applicantName", "申请人", "请输入申请人姓名"],
  ];
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/30 p-4" onMouseDown={() => !closingBlocked && onClose()}>
      <form onSubmit={onSubmit} onMouseDown={(event) => event.stopPropagation()} className="max-h-[90vh] w-full max-w-3xl overflow-y-auto rounded-lg border border-slate-200 bg-white shadow-xl">
        <div className="flex items-center justify-between border-b border-slate-200 px-5 py-4">
          <div>
            <h3 className="text-sm font-semibold text-slate-900">{editing ? "编辑采购项目" : "新建采购需求"}</h3>
            {editing && <p className="mt-1 text-[11px] text-muted-foreground">仅草稿项目可以编辑，供应商信息由后续询比价材料补充。</p>}
          </div>
          <button type="button" onClick={onClose} disabled={closingBlocked} className="text-muted-foreground hover:text-slate-700" title="关闭"><XCircle size={16} /></button>
        </div>
        <div className="grid grid-cols-1 gap-4 p-5 md:grid-cols-2">
          {!editing && <div className="md:col-span-2"><ProcurementMaterialUpload key={materialResetVersion} actorId={actorId}
            onParsed={onMaterialParsed} onUse={onMaterialUse} onBusy={onMaterialBusy} /></div>}
          {textFields.map(([field, label, placeholder]) => (
            <label key={field} className="space-y-1.5 text-xs text-slate-700">
              <span>{label}<em className="ml-1 text-red-600">*</em></span>
              <input required value={String(form[field] ?? "")} onChange={(event) => onChange(field, event.target.value)} placeholder={placeholder} className="w-full rounded border border-slate-200 bg-white px-3 py-2 text-xs text-slate-900 outline-none focus:border-blue-400" />
            </label>
          ))}
          <label className="space-y-1.5 text-xs text-slate-700">
            <span>采购类型<em className="ml-1 text-red-600">*</em></span>
            <select required value={form.procurementType} onChange={(event) => onChange("procurementType", event.target.value)} className="w-full rounded border border-slate-200 bg-white px-3 py-2 text-xs text-slate-900 outline-none focus:border-blue-400">
              <option value="">请选择采购类型</option><option value="货物">货物</option><option value="服务">服务</option><option value="工程">工程</option>
            </select>
          </label>
          <label className="space-y-1.5 text-xs text-slate-700">
            <span>采购方式<em className="ml-1 text-red-600">*</em></span>
            <select required value={form.procurementMethod} onChange={(event) => onChange("procurementMethod", event.target.value)} className="w-full rounded border border-slate-200 bg-white px-3 py-2 text-xs text-slate-900 outline-none focus:border-blue-400">
              <option value="">请选择采购方式</option><option value="公开招标">公开招标</option><option value="邀请招标">邀请招标</option><option value="竞争性磋商">竞争性磋商</option><option value="竞争性谈判">竞争性谈判</option><option value="询价">询价</option><option value="单一来源">单一来源</option>
            </select>
          </label>
          <label className="space-y-1.5 text-xs text-slate-700">
            <span>预算金额<em className="ml-1 text-red-600">*</em></span>
            <input required type="number" min="0.01" step="0.01" value={form.budgetAmount} onChange={(event) => onChange("budgetAmount", event.target.value)} placeholder="请输入金额（元）" className="w-full rounded border border-slate-200 bg-white px-3 py-2 text-xs font-mono text-slate-900 outline-none focus:border-blue-400" />
          </label>
          <label className="space-y-1.5 text-xs text-slate-700 md:col-span-2">
            <span>需求说明和技术要求</span>
            <textarea rows={4} value={form.requirementDescription} onChange={(event) => onChange("requirementDescription", event.target.value)} placeholder="可填写采购数量、技术参数、交付要求等" className="w-full resize-y rounded border border-slate-200 bg-white px-3 py-2 text-xs text-slate-900 outline-none focus:border-blue-400" />
          </label>
          {editing && <label className="space-y-1.5 text-xs text-slate-700 md:col-span-2">
            <span>附件</span>
            <input type="file" onChange={(event) => onChange("attachmentFile", event.target.files?.[0] ?? null)} className="block w-full rounded border border-dashed border-slate-300 bg-slate-50 px-3 py-2 text-xs text-slate-600" />
            {existingAttachmentName && !form.attachmentFile && <span className="block text-[11px] text-slate-600">已保存附件：{existingAttachmentName}</span>}
            <span className="block text-[11px] text-muted-foreground">不选择新文件将保留原附件信息。</span>
          </label>}
        </div>
        {error && <div className="mx-5 mb-4 rounded border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700">{error}</div>}
        <div className="flex justify-end gap-2 border-t border-slate-200 px-5 py-4">
          <button type="button" onClick={onClose} disabled={closingBlocked} className="rounded border border-slate-200 bg-white px-3 py-2 text-xs text-slate-600 hover:bg-slate-50 disabled:opacity-50">关闭</button>
          {editing ? (
            <button type="submit" disabled={loading} className="inline-flex items-center gap-1.5 rounded border border-blue-200 bg-blue-50 px-3 py-2 text-xs text-blue-700 hover:bg-blue-100 disabled:opacity-50">
              {loading && <RefreshCw size={12} className="animate-spin" />}{loading ? "保存中…" : "保存修改"}
            </button>
          ) : (
            <>
              <button type="button" onClick={onClearMaterials} disabled={closingBlocked} className="inline-flex items-center gap-1.5 rounded border border-slate-200 bg-white px-3 py-2 text-xs text-slate-700 hover:bg-slate-50 disabled:opacity-50">
                清空填充
              </button>
              <button type="submit" disabled={loading} className="inline-flex items-center gap-1.5 rounded border border-blue-200 bg-blue-50 px-3 py-2 text-xs text-blue-700 hover:bg-blue-100 disabled:opacity-50">
                {loading && <RefreshCw size={12} className="animate-spin" />}{loading ? "保存中…" : "保存并查看结果"}
              </button>
            </>
          )}
        </div>
      </form>
    </div>
  );
}

function formatQuoteMoney(value: number) {
  return `¥${Number(value ?? 0).toLocaleString("zh-CN", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`;
}

function formatQuoteRatio(value: number) {
  return `${(Number(value ?? 0) * 100).toFixed(2)}%`;
}

function QuoteRiskBadge({ level }: { level: string }) {
  const styles = level === "CRITICAL"
    ? "border-red-200 bg-red-50 text-red-700"
    : level === "WARNING"
      ? "border-amber-200 bg-amber-50 text-amber-700"
      : "border-emerald-200 bg-emerald-50 text-emerald-700";
  const label = level === "CRITICAL" ? "严重"
    : level === "WARNING" ? "警告"
      : "正常";
  return <span className={`inline-flex items-center rounded border px-2 py-1 text-xs font-medium ${styles}`}>{label}</span>;
}

function ProcurementQuoteCheckResult({ result }: { result: ProcurementQuoteCheckResponse }) {
  const overBudgetQuotes = result.quotes.filter((quote) => quote.overBudget);
  const suspiciousQuotes = result.suspiciousSuppliers.length > 0
    ? result.suspiciousSuppliers
    : result.quotes.filter((quote) => quote.suspicious);
  const insufficientQuotes = result.quotes.length < 3;
  const chartData = result.quotes.map((quote) => ({
    supplierName: quote.supplierName,
    quoteAmount: Number(quote.quoteAmount),
    overBudget: quote.overBudget,
  }));
  const chartMaxAmount = Math.max(
    Number(result.budgetAmount),
    ...chartData.map((item) => item.quoteAmount),
  );
  const chartYAxisMax = chartMaxAmount > 0 ? Math.ceil(chartMaxAmount * 1.15) : 1;

  return (
    <div className="space-y-4">
      <ProcurementQuoteAdvice result={result} />
      <div className="flex flex-wrap items-center justify-between gap-2 rounded border border-blue-100 bg-blue-50/60 px-3 py-2 text-xs">
        <span className="text-slate-600">
          分析批次：<strong className="font-mono text-slate-900">{result.batchId}</strong>
        </span>
        <QuoteRiskBadge level={result.riskLevel} />
      </div>

      <div className="grid grid-cols-2 gap-2 lg:grid-cols-4">
        <div className="rounded border border-slate-200 bg-slate-50 px-3 py-2">
          <span className="block text-[11px] text-muted-foreground">预算金额</span>
          <strong className="mt-1 block font-mono text-sm text-slate-900">{formatQuoteMoney(result.budgetAmount)}</strong>
        </div>
        <div className="rounded border border-slate-200 bg-slate-50 px-3 py-2">
          <span className="block text-[11px] text-muted-foreground">平均报价</span>
          <strong className="mt-1 block font-mono text-sm text-slate-900">{formatQuoteMoney(result.averageQuote)}</strong>
        </div>
        <div className={`rounded border px-3 py-2 ${result.highlyConcentrated ? "border-amber-200 bg-amber-50" : "border-emerald-200 bg-emerald-50"}`}>
          <span className={`block text-[11px] ${result.highlyConcentrated ? "text-amber-700" : "text-emerald-700"}`}>报价离散系数（CV）</span>
          <strong className={`mt-1 block font-mono text-sm ${result.highlyConcentrated ? "text-amber-800" : "text-emerald-800"}`}>
            {Number(result.coefficientOfVariation ?? 0).toFixed(4)}
          </strong>
        </div>
        <div className={`rounded border px-3 py-2 ${result.highlyConcentrated || insufficientQuotes ? "border-amber-200 bg-amber-50" : "border-emerald-200 bg-emerald-50"}`}>
          <span className={`block text-[11px] ${result.highlyConcentrated || insufficientQuotes ? "text-amber-700" : "text-emerald-700"}`}>报价规律性</span>
          <strong className={`mt-1 block text-sm ${result.highlyConcentrated || insufficientQuotes ? "text-amber-800" : "text-emerald-800"}`}>
            {insufficientQuotes ? "报价家数不足" : result.highlyConcentrated ? "高度趋同" : "正常分布"}
          </strong>
          {insufficientQuotes && <span className="mt-0.5 block text-[10px] text-amber-700">少于 3 家，无法判断趋同性</span>}
        </div>
      </div>

      <div className="rounded border border-slate-200 bg-white px-3 py-3">
        <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
          <h4 className="text-xs font-medium text-slate-900">供应商报价与预算对比</h4>
          <div className="flex items-center gap-3 text-[11px] text-slate-500">
            <span className="inline-flex items-center gap-1"><span className="size-2 rounded-sm bg-emerald-500" />预算内</span>
            <span className="inline-flex items-center gap-1"><span className="size-2 rounded-sm bg-red-500" />超预算</span>
            <span className="inline-flex items-center gap-1"><span className="w-3 border-t border-dashed border-red-600" />预算基准</span>
          </div>
        </div>
        <div className="overflow-x-auto">
          <div
            className="h-64"
            style={{ minWidth: Math.max(560, chartData.length * 84) }}
            role="img"
            aria-label="各供应商报价与预算金额对比图"
          >
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={chartData} margin={{ top: 12, right: 16, left: 8, bottom: 34 }}>
              <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#e2e8f0" />
              <XAxis
                dataKey="supplierName"
                interval={0}
                angle={-18}
                textAnchor="end"
                height={56}
                tick={{ fontSize: 10, fill: "#64748b" }}
                tickFormatter={(value) => String(value).length > 10 ? `${String(value).slice(0, 10)}…` : String(value)}
              />
              <YAxis
                width={72}
                domain={[0, chartYAxisMax]}
                tick={{ fontSize: 10, fill: "#64748b" }}
                tickFormatter={(value) => `¥${Number(value).toLocaleString("zh-CN", { notation: "compact", maximumFractionDigits: 1 })}`}
              />
              <Tooltip
                cursor={{ fill: "#f8fafc" }}
                formatter={(value) => [formatQuoteMoney(Number(value)), "报价金额"]}
                labelFormatter={(label) => `供应商：${label}`}
                contentStyle={{ background: "#fff", border: "1px solid #e2e8f0", borderRadius: 6, fontSize: 11 }}
              />
              <ReferenceLine
                y={Number(result.budgetAmount)}
                stroke="#dc2626"
                strokeDasharray="5 4"
                strokeWidth={1.5}
                label={{ value: `预算 ${formatQuoteMoney(result.budgetAmount)}`, position: "insideTopRight", fill: "#b91c1c", fontSize: 10 }}
              />
              <Bar dataKey="quoteAmount" name="报价金额" maxBarSize={48} radius={[3, 3, 0, 0]}>
                {chartData.map((item, index) => (
                  <Cell key={`${item.supplierName}-${index}`} fill={item.overBudget ? "#ef4444" : "#10b981"} />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
          </div>
        </div>
      </div>

      <div>
        <div className="mb-1.5 flex items-center justify-between gap-2">
          <h4 className="text-xs font-medium text-red-700">超预算告警</h4>
          <span className="text-[11px] text-muted-foreground">{overBudgetQuotes.length} 家供应商</span>
        </div>
        {overBudgetQuotes.length === 0 ? (
          <div className="rounded border border-emerald-200 bg-emerald-50 px-3 py-2 text-xs text-emerald-700">
            暂无供应商报价超过预算金额。
          </div>
        ) : (
          <div className="overflow-x-auto rounded border border-red-200">
            <table className="w-full text-xs">
              <thead className="bg-red-50 text-red-800">
                <tr>
                  {["行号", "供应商名称", "报价金额", "超出金额", "超出比例"].map((header) => (
                    <th key={header} className="px-3 py-2 text-left font-medium whitespace-nowrap">{header}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-red-100">
                {overBudgetQuotes.map((quote) => (
                  <tr key={quote.id || `${quote.rowNumber}-${quote.supplierName}`} className="bg-red-50/60 text-red-950">
                    <td className="px-3 py-2 font-mono">{quote.rowNumber}</td>
                    <td className="px-3 py-2 font-medium">{quote.supplierName}</td>
                    <td className="px-3 py-2 font-mono">{formatQuoteMoney(quote.quoteAmount)}</td>
                    <td className="px-3 py-2 font-mono">{formatQuoteMoney(quote.overBudgetAmount)}</td>
                    <td className="px-3 py-2 font-mono">{formatQuoteRatio(quote.overBudgetRatio)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div>
        <div className="mb-1.5 flex items-center justify-between gap-2">
          <h4 className="text-xs font-medium text-amber-700">可疑供应商清单</h4>
          <span className="text-[11px] text-muted-foreground">超预算或报价偏离均值</span>
        </div>
        {suspiciousQuotes.length === 0 ? (
          <div className="rounded border border-emerald-200 bg-emerald-50 px-3 py-2 text-xs text-emerald-700">
            暂未发现需要重点关注的供应商。
          </div>
        ) : (
          <div className="overflow-x-auto rounded border border-amber-200">
            <table className="w-full text-xs">
              <thead className="bg-amber-50 text-amber-800">
                <tr>
                  {["行号", "供应商名称", "报价金额", "偏离均值", "关注原因"].map((header) => (
                    <th key={header} className="px-3 py-2 text-left font-medium whitespace-nowrap">{header}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-amber-100">
                {suspiciousQuotes.map((quote) => (
                  <tr key={`suspicious-${quote.id || quote.rowNumber}`} className="bg-amber-50/50 text-amber-950">
                    <td className="px-3 py-2 font-mono">{quote.rowNumber}</td>
                    <td className="px-3 py-2 font-medium">{quote.supplierName}</td>
                    <td className="px-3 py-2 font-mono">{formatQuoteMoney(quote.quoteAmount)}</td>
                    <td className="px-3 py-2 font-mono">{formatQuoteRatio(quote.deviationRatio)}</td>
                    <td className="px-3 py-2">{quote.suspiciousReason || "报价存在异常特征"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}

const procurementApprovalRoleLabels: Record<string, string> = {
  PROCUREMENT_APPLICANT: "采购申请人",
  PROCUREMENT_REVIEWER: "采购审查员",
  FINANCE_REVIEWER: "财务审核员",
  PROCUREMENT_LEADER: "采购领导",
  PROCUREMENT_COMMITTEE: "采购领导小组",
};

function procurementApprovalRoleLabel(roleCode: string) {
  return procurementApprovalRoleLabels[roleCode] ?? enumLabel(roleCode);
}

function procurementApprovalOperationLabel(operationType: string) {
  return enumLabel(operationType);
}

function ProcurementReviewReportPanel({ report }: { report: ProcurementReviewReport }) {
  const riskLabel = report.riskLevel === "CRITICAL" ? "严重异常" : report.riskLevel === "WARNING" ? "一般异常" : "正常";
  const riskStyle = report.riskLevel === "CRITICAL"
    ? "border-red-200 bg-red-50 text-red-700"
    : report.riskLevel === "WARNING"
      ? "border-amber-200 bg-amber-50 text-amber-700"
      : "border-emerald-200 bg-emerald-50 text-emerald-700";

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3 rounded border border-slate-200 bg-slate-50 px-3 py-2 text-xs">
        <div className="space-y-1 text-slate-600">
          <div>报告编号：<strong className="font-mono text-slate-900">{report.reportNumber}</strong></div>
          <div>生成时间：<span className="font-mono">{formatProcurementDateTime(report.generatedTime)}</span></div>
        </div>
        <span className={`rounded border px-2 py-1 font-medium ${riskStyle}`}>综合风险：{riskLevelLabel(report.riskLevel)} · {riskLabel}</span>
      </div>

      <div className={`rounded border px-3 py-3 text-xs ${riskStyle}`}>
        <div className="mb-1 font-medium">综合审查结论</div>
        <p className="leading-relaxed">{report.overallConclusion}</p>
      </div>

      <div className="rounded border border-slate-200 bg-white px-3 py-3 text-xs">
        <div className="mb-2 font-medium text-slate-900">项目基本信息</div>
        <div className="grid grid-cols-2 gap-x-4 gap-y-2 text-slate-600 lg:grid-cols-4">
          <span>项目编号：<strong className="font-mono text-slate-900">{report.project.projectCode}</strong></span>
          <span>项目名称：<strong className="text-slate-900">{report.project.projectName}</strong></span>
          <span>预算金额：<strong className="font-mono text-slate-900">{formatQuoteMoney(report.project.budgetAmount ?? 0)}</strong></span>
          <span>项目状态：<strong className="text-slate-900">{procurementStatusLabel(report.project.status)}</strong></span>
        </div>
      </div>

      <div className="rounded border border-blue-200 bg-blue-50/50 px-3 py-3 text-xs">
        <div className="mb-2 font-medium text-blue-900">智能体预审结果</div>
        <div className="space-y-2 text-slate-700">
          <div>规则结论：{report.agentReview.suggestion}</div>
          {report.agentReview.aiSummary && <div>AI 预审摘要：{report.agentReview.aiSummary}</div>}
          <div className="space-y-1">
            {report.agentReview.items.map((item) => (
              <div key={`${item.checkType}-${item.checkName}`} className="border-t border-blue-100 pt-1">
                <div className="font-medium text-slate-900">{item.checkName}：{item.detail}</div>
                {item.aiExplanation && <div>AI 解释：{item.aiExplanation}</div>}
                {item.aiSuggestion && <div>AI 建议：{item.aiSuggestion}</div>}
              </div>
            ))}
          </div>
        </div>
      </div>

      <div className="rounded border border-slate-200 px-3 py-3 text-xs">
        <div className="mb-2 font-medium text-slate-900">询比价异常分析</div>
        {!report.quoteCheck ? (
          <div className="rounded border border-slate-200 bg-slate-50 px-3 py-2 text-slate-500">尚未生成询比价分析结果。</div>
        ) : (
          <>
          <div className="grid grid-cols-2 gap-2 lg:grid-cols-4">
            <span>异常等级：<strong className="text-slate-900">{riskLevelLabel(report.quoteCheck.riskLevel)}</strong></span>
            <span>报价 CV：<strong className="font-mono text-slate-900">{Number(report.quoteCheck.coefficientOfVariation ?? 0).toFixed(4)}</strong></span>
            <span>规律性：<strong className={report.quoteCheck.highlyConcentrated ? "text-amber-700" : "text-emerald-700"}>{report.quoteCheck.highlyConcentrated ? "高度趋同" : "正常"}</strong></span>
            <span>可疑供应商：<strong className="text-slate-900">{report.quoteCheck.suspiciousSuppliers.length} 家</strong></span>
          </div>
          {report.quoteCheck.suspiciousSuppliers.length > 0 && (
            <div className="mt-3 overflow-x-auto rounded border border-amber-200">
              <div className="border-b border-amber-200 bg-amber-50 px-3 py-2 font-medium text-amber-800">可疑供应商明细</div>
              <table className="w-full text-xs">
                <thead className="bg-amber-50/60 text-amber-800">
                  <tr>
                    {["行号", "供应商名称", "报价金额", "偏离均值", "超出预算", "异常原因"].map((header) => (
                      <th key={header} className="px-3 py-2 text-left font-medium whitespace-nowrap">{header}</th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-amber-100">
                  {report.quoteCheck.suspiciousSuppliers.map((quote) => (
                    <tr key={`report-suspicious-${quote.id || quote.rowNumber}`} className={quote.overBudget ? "bg-red-50 text-red-950" : "bg-amber-50/40 text-amber-950"}>
                      <td className="px-3 py-2 font-mono">{quote.rowNumber}</td>
                      <td className="px-3 py-2 font-medium">{quote.supplierName}</td>
                      <td className="px-3 py-2 font-mono">{formatQuoteMoney(quote.quoteAmount)}</td>
                      <td className="px-3 py-2 font-mono">{formatQuoteRatio(quote.deviationRatio)}</td>
                      <td className="px-3 py-2 font-mono">{quote.overBudget ? formatQuoteMoney(quote.overBudgetAmount) : "否"}</td>
                      <td className="px-3 py-2">{quote.suspiciousReason || "报价存在异常特征"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          </>
        )}
      </div>

      <div className="rounded border border-slate-200 px-3 py-3 text-xs">
        <div className="mb-2 font-medium text-slate-900">黑名单比对结果</div>
        {report.blacklist.alertCount === 0 ? (
          <div className="rounded border border-emerald-200 bg-emerald-50 px-3 py-2 text-emerald-700">当前批次没有黑名单命中告警。</div>
        ) : (
          <div className="overflow-x-auto rounded border border-red-200">
            <table className="w-full text-xs">
              <thead className="bg-red-50 text-red-800">
                <tr>{["供应商名称", "黑名单类型", "命中原因", "匹配方式", "告警等级"].map((header) => <th key={header} className="px-3 py-2 text-left font-medium whitespace-nowrap">{header}</th>)}</tr>
              </thead>
              <tbody className="divide-y divide-red-100">
                {report.blacklist.hits.map((hit) => (
                  <tr key={hit.alertId} className="bg-red-50/60 text-red-950">
                    <td className="px-3 py-2 font-medium">{hit.supplierName}</td>
                    <td className="px-3 py-2">{hit.blacklistType}</td>
                    <td className="px-3 py-2">{hit.blacklistReason || "已命中黑名单"}</td>
                    <td className="px-3 py-2">{enumLabel(hit.matchType)}（{hit.matchScore}分）</td>
                    <td className={`px-3 py-2 font-medium ${hit.alertLevel === "CRITICAL" ? "text-red-700" : hit.alertLevel === "WARNING" ? "text-amber-700" : "text-slate-700"}`}>{riskLevelLabel(hit.alertLevel)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}

function ProcurementApprovalTasksPage({ onOpenProject }: { onOpenProject: (projectId: string) => void }) {
  const [tasks, setTasks] = useState<ProcurementApprovalTask[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadTasks = async () => {
    setLoading(true);
    setError(null);
    try {
      setTasks(await listProcurementApprovalTasks());
    } catch (cause) {
      setError(userFacingError(cause, "待办任务加载失败，请稍后重试。"));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void loadTasks(); }, []);

  return (
    <div className="p-5 lg:p-7">
      <div className="mb-5 flex items-center justify-between gap-3">
        <div><h1 className="text-lg font-semibold text-slate-900">我的待办</h1><p className="mt-1 text-xs text-muted-foreground">采购审批和退回修改任务</p></div>
        <button type="button" onClick={() => void loadTasks()} disabled={loading} title="刷新待办" className="inline-flex items-center gap-1.5 rounded border border-slate-200 bg-white px-3 py-2 text-xs text-slate-700 hover:border-blue-300 hover:text-blue-700 disabled:opacity-50"><RefreshCw size={13} className={loading ? "animate-spin" : undefined} />刷新</button>
      </div>
      {error && <div role="alert" className="mb-4 rounded border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700">{error}</div>}
      <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white">
        {loading ? <div className="p-8 text-center text-xs text-muted-foreground">正在加载待办…</div> : tasks.length === 0 ? <div className="p-8 text-center text-xs text-muted-foreground">暂无待办任务</div> : (
          <table className="w-full min-w-[760px] text-xs">
            <thead className="border-b border-slate-200 bg-slate-50 text-left text-slate-600"><tr>{["任务类型", "项目编号", "项目名称", "预算金额", "当前事项", "责任人 / 退回人", "时间"].map((label) => <th key={label} className="px-4 py-3 font-medium">{label}</th>)}</tr></thead>
            <tbody className="divide-y divide-slate-100">
              {tasks.map((task) => (
                <tr key={task.nodeId} className="cursor-pointer text-slate-700 hover:bg-blue-50" onClick={() => onOpenProject(task.projectId)}>
                  <td className="px-4 py-3"><span className={`rounded border px-2 py-0.5 ${task.taskType === "PENDING_MODIFICATION" ? "border-amber-200 bg-amber-50 text-amber-700" : "border-blue-200 bg-blue-50 text-blue-700"}`}>{task.taskType === "PENDING_MODIFICATION" ? "待修改" : "待审批"}</span></td>
                  <td className="px-4 py-3 font-mono text-blue-700">{task.projectCode ?? "-"}</td><td className="px-4 py-3 font-medium text-slate-900">{task.projectName ?? "-"}</td>
                  <td className="px-4 py-3 font-mono">{task.budgetAmount == null ? "-" : `¥${Number(task.budgetAmount).toLocaleString("zh-CN", { minimumFractionDigits: 2 })}`}</td>
                  <td className="max-w-[280px] px-4 py-3"><p>{task.nodeName}</p>{task.taskType === "PENDING_MODIFICATION" && task.returnOpinion && <p className="mt-1 truncate text-amber-700" title={task.returnOpinion}>退回意见：{task.returnOpinion}</p>}</td>
                  <td className="px-4 py-3">{task.taskType === "PENDING_MODIFICATION" ? task.returnOperatorName ?? "-" : task.assigneeName ?? "待指派"}</td><td className="px-4 py-3 font-mono text-muted-foreground">{task.startedTime ? formatProcurementDateTime(task.startedTime) : "-"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}

function ProcurementPage({ initialProjectId, onNavigationConsumed }: { initialProjectId?: string | null; onNavigationConsumed?: () => void }) {
  const procurementPageSize = 10;
  const { user } = useAuth();
  const [procurementProjects, setProcurementProjects] = useState<ProcurementProject[]>([]);
  const [procurementPage, setProcurementPage] = useState(1);
  const [procurementTotal, setProcurementTotal] = useState(0);
  const [procurementTotalPages, setProcurementTotalPages] = useState(0);
  const [procurementStatusFilter, setProcurementStatusFilter] = useState<string | undefined>(undefined);
  const [procurementKeyword, setProcurementKeyword] = useState("");
  const procurementRequestId = useRef(0);
  const [procurementListLoading, setProcurementListLoading] = useState(true);
  const [procurementListError, setProcurementListError] = useState<string | null>(null);
  const [showFormalProcurementManagement, setShowFormalProcurementManagement] = useState(false);
  const [createFormOpen, setCreateFormOpen] = useState(false);
  const [editingProcurementId, setEditingProcurementId] = useState<string | null>(null);
  const [createForm, setCreateForm] = useState<NewProcurementForm>(emptyNewProcurementForm);
  const [sourceProjects, setSourceProjects] = useState<ProcurementSourceProject[]>([]);
  const [materialResetVersion, setMaterialResetVersion] = useState(0);
  const materialPrimaryRef = useRef<ProcurementSourceProject | null>(null);
  const [createFormLoading, setCreateFormLoading] = useState(false);
  const [materialBusy, setMaterialBusy] = useState(false);
  const [createSubmitConfirmOpen, setCreateSubmitConfirmOpen] = useState(false);
  const [draftSubmitConfirmOpen, setDraftSubmitConfirmOpen] = useState(false);
  const [procurementDeleteLoadingId, setProcurementDeleteLoadingId] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<ProcurementProject | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [deleteSuccess, setDeleteSuccess] = useState<string | null>(null);
  const [editBlockedProject, setEditBlockedProject] = useState<ProcurementProject | null>(null);
  const [createFormError, setCreateFormError] = useState<string | null>(null);
  const [selectedProcurement, setSelectedProcurement] = useState<string | null>(null);
  const [reviewWizardOpen, setReviewWizardOpen] = useState(false);
  const [agentScanPhase, setAgentScanPhase] = useState<"idle" | "scanning" | "done">("idle");
  const [agentScanStep, setAgentScanStep] = useState(0);
  const [agentReview, setAgentReview] = useState<ProcurementAgentReview | null>(null);
  const [agentReviewError, setAgentReviewError] = useState<string | null>(null);
  const [fieldConfirmations, setFieldConfirmations] = useState<ProcurementFieldConfirmation[]>([]);
  const [fieldConfirmationNotes, setFieldConfirmationNotes] = useState<Record<string, string>>({});
  const [fieldConfirmationLoading, setFieldConfirmationLoading] = useState<string | null>(null);
  const [fieldConfirmationError, setFieldConfirmationError] = useState<string | null>(null);
  const [blacklistResult, setBlacklistResult] = useState<BlacklistBatchCheckResponse | null>(null);
  const [blacklistError, setBlacklistError] = useState<string | null>(null);
  const [quoteFile, setQuoteFile] = useState<File | null>(null);
  const [quoteResult, setQuoteResult] = useState<ProcurementQuoteCheckResponse | null>(null);
  const [quoteLoading, setQuoteLoading] = useState(false);
  const [quoteHistoryLoading, setQuoteHistoryLoading] = useState(false);
  const [quoteError, setQuoteError] = useState<string | null>(null);
  const [quoteHistoryBatchId, setQuoteHistoryBatchId] = useState("");
  const [projectExportLoading, setProjectExportLoading] = useState(false);
  const [projectExportError, setProjectExportError] = useState<string | null>(null);
  const [approval, setApproval] = useState<ProcurementApproval | null>(null);
  const [approvalHistory, setApprovalHistory] = useState<ProcurementApprovalOperation[]>([]);
  const [approvalLoading, setApprovalLoading] = useState(false);
  const [approvalError, setApprovalError] = useState<string | null>(null);
  const [approvalOpinion, setApprovalOpinion] = useState("");
  const [approvalActionLoading, setApprovalActionLoading] = useState(false);
  const [reviewReport, setReviewReport] = useState<ProcurementReviewReport | null>(null);
  const [reviewReportLoading, setReviewReportLoading] = useState(false);
  const [reviewReportError, setReviewReportError] = useState<string | null>(null);
  const [reviewReportNotice, setReviewReportNotice] = useState<string | null>(null);
  const [headerPreviews, setHeaderPreviews] = useState<{ quote: SpreadsheetHeaderPreview; blacklist: SpreadsheetHeaderPreview } | null>(null);
  const [reassignOpen, setReassignOpen] = useState(false);
  const [reassignTargetUserId, setReassignTargetUserId] = useState("");
  const [reassignOpinion, setReassignOpinion] = useState("");
  const [reassignLoading, setReassignLoading] = useState(false);
  const [reassignError, setReassignError] = useState<string | null>(null);
  const [resubmitOpen, setResubmitOpen] = useState(false);
  const [modificationNote, setModificationNote] = useState("");
  const [resubmitError, setResubmitError] = useState<string | null>(null);

  const agentScanSteps = [
    "读取项目基本信息和预算金额…",
    "比对《专用设备配置标准》…",
    "查询近12个月市场价格基准数据…",
    "分析需求描述规范性与技术参数…",
    "检查采购方式选择合规性…",
    "生成综合审查报告…",
  ];

  async function loadProcurementProjects(
    page = procurementPage,
    status = procurementStatusFilter,
    keyword = procurementKeyword,
  ) {
    const requestId = ++procurementRequestId.current;
    setProcurementListLoading(true);
    setProcurementListError(null);
    try {
      const result = await listProcurementProjects({
        page,
        size: procurementPageSize,
        status,
        keyword: keyword.trim() || undefined,
      });
      if (requestId !== procurementRequestId.current) return;
      if (result.totalPages > 0 && page > result.totalPages) {
        setProcurementTotal(result.total);
        setProcurementTotalPages(result.totalPages);
        setProcurementPage(result.totalPages);
        return;
      }
      setProcurementProjects(result.items);
      setProcurementPage(result.page);
      setProcurementTotal(result.total);
      setProcurementTotalPages(result.totalPages);
    } catch (error) {
      if (requestId !== procurementRequestId.current) return;
      setProcurementProjects([]);
      setProcurementTotal(0);
      setProcurementTotalPages(0);
      setProcurementListError(userFacingError(error, "采购项目列表加载失败，请稍后重试。"));
    } finally {
      if (requestId === procurementRequestId.current) setProcurementListLoading(false);
    }
  }

  useEffect(() => {
    if (!showFormalProcurementManagement && !initialProjectId) return;
    const delay = procurementKeyword.trim() ? 500 : 0;
    const timer = window.setTimeout(() => {
      void loadProcurementProjects(procurementPage, procurementStatusFilter, procurementKeyword);
    }, delay);
    return () => window.clearTimeout(timer);
  }, [procurementPage, procurementStatusFilter, procurementKeyword, showFormalProcurementManagement, initialProjectId]);

  useEffect(() => {
    if (!initialProjectId || procurementListLoading) return;
    const project = procurementProjects.find((item) => item.id === initialProjectId);
    if (project) selectProcurement(project.id);
    onNavigationConsumed?.();
  }, [initialProjectId, procurementListLoading, procurementProjects]);

  useEffect(() => {
    if (!selectedProcurement || reviewWizardOpen) return;
    let active = true;
    let step = 0;
    const interval = setInterval(() => {
      step = Math.min(step + 1, agentScanSteps.length - 1);
      if (active) setAgentScanStep(step);
    }, 480);

    setAgentReview(null);
    setFieldConfirmations([]);
    setAgentReviewError(null);
    setFieldConfirmationError(null);
    setAgentScanPhase("scanning");

    Promise.all([
      getProcurementAgentReview(selectedProcurement),
      listProcurementFieldConfirmations(selectedProcurement),
    ])
      .then(async ([cachedResult, confirmations]) => {
        const result = cachedResult ?? await refreshProcurementAgentReview(selectedProcurement);
        if (active) {
          setAgentReview(result);
          setFieldConfirmations(confirmations);
        }
      })
      .catch((error) => {
        if (active) setAgentReviewError(userFacingError(error, "智能体预审结果加载失败，请稍后重试。"));
      })
      .finally(() => {
        if (active) {
          clearInterval(interval);
          setAgentScanStep(agentScanSteps.length - 1);
          setAgentScanPhase("done");
        }
      });

    return () => {
      active = false;
      clearInterval(interval);
    };
  }, [selectedProcurement, reviewWizardOpen]);

  async function handleRefreshAgentReview() {
    if (!selectedProcurement || agentScanPhase === "scanning") return;
    const projectId = selectedProcurement;
    let step = 0;
    const interval = setInterval(() => {
      step = Math.min(step + 1, agentScanSteps.length - 1);
      if (selectedProcurement === projectId) setAgentScanStep(step);
    }, 480);
    setAgentReviewError(null);
    setAgentScanStep(0);
    setAgentScanPhase("scanning");
    try {
      const result = await refreshProcurementAgentReview(projectId);
      if (selectedProcurement === projectId) setAgentReview(result);
    } catch (error) {
      if (selectedProcurement === projectId) {
        setAgentReviewError(userFacingError(error, "智能体预审执行失败，请稍后重试。"));
      }
    } finally {
      clearInterval(interval);
      if (selectedProcurement === projectId) {
        setAgentScanStep(agentScanSteps.length - 1);
        setAgentScanPhase("done");
      }
    }
  }

  async function handleConfirmReviewField(fieldCode: string) {
    if (!selectedProcurement) return;
    setFieldConfirmationLoading(fieldCode);
    setFieldConfirmationError(null);
    try {
      await confirmProcurementField(
        selectedProcurement,
        fieldCode,
        fieldConfirmationNotes[fieldCode]?.trim(),
      );
      setFieldConfirmations(await listProcurementFieldConfirmations(selectedProcurement));
      setFieldConfirmationNotes((current) => ({ ...current, [fieldCode]: "" }));
    } catch (error) {
      setFieldConfirmationError(userFacingError(error, "信息确认保存失败，请稍后重试。"));
    } finally {
      setFieldConfirmationLoading(null);
    }
  }

  useEffect(() => {
    if (!selectedProcurement || reviewWizardOpen) return;
    let active = true;
    setApproval(null);
    setApprovalHistory([]);
    setApprovalError(null);
    setApprovalOpinion("");
    setReviewReport(null);
    setReviewReportError(null);
    setReviewReportNotice(null);

    setApprovalLoading(true);
    Promise.allSettled([
      getProcurementApproval(selectedProcurement),
      getProcurementApprovalHistory(selectedProcurement),
    ])
      .then(([workflowResult, historyResult]) => {
        if (!active) return;
        const workflow = workflowResult.status === "fulfilled"
          ? workflowResult.value
          : isApprovalNotStarted(workflowResult.reason)
            ? null
            : (() => { throw workflowResult.reason; })();
        const history = historyResult.status === "fulfilled"
          ? historyResult.value
          : isApprovalNotStarted(historyResult.reason)
            ? []
            : (() => { throw historyResult.reason; })();
        setApproval(workflow);
        setApprovalHistory(history);
      })
      .catch((error) => {
        if (active) setApprovalError(userFacingError(error, "审批流程加载失败，请稍后重试。"));
      })
      .finally(() => {
        if (active) setApprovalLoading(false);
      });
    return () => {
      active = false;
    };
  }, [procurementProjects, selectedProcurement, reviewWizardOpen]);

  function isApprovalNotStarted(error: unknown): boolean {
    return error instanceof ApiError && error.status === 404;
  }

  function selectProcurement(id: string) {
    setSelectedProcurement(id);
    setReviewWizardOpen(true);
    setBlacklistResult(null);
    setBlacklistError(null);
    setQuoteFile(null);
    setQuoteResult(null);
    setQuoteError(null);
    setQuoteHistoryBatchId(id);
    setAgentScanStep(0);
    setFieldConfirmations([]);
    setFieldConfirmationNotes({});
    setFieldConfirmationError(null);
    setApproval(null);
    setApprovalHistory([]);
    setApprovalError(null);
    setApprovalOpinion("");
    setReviewReportNotice(null);
    setHeaderPreviews(null);
  }

  const handleCreate = () => {
    materialPrimaryRef.current = null;
    setEditingProcurementId(null);
    setCreateForm(emptyNewProcurementForm());
    setCreateFormError(null);
    setCreateFormOpen(true);
    void listProcurementSourceProjects()
      .then((projects) => {
        setSourceProjects(projects);
      })
      .catch((error) => {
        setSourceProjects([]);
        setCreateFormError(userFacingError(error, "已解析项目读取失败，请稍后重试。"));
      });
  };

  const clearProcurementMaterials = () => {
    if(!user || createFormLoading) return;
    if(!materialBatch(user.userId).clear()) {
      setCreateFormError('无法清空本次材料，请检查浏览器存储后再试');
      return;
    }
    materialPrimaryRef.current = null;
    setCreateForm(emptyNewProcurementForm());
    setCreateFormError(null);
    setCreateSubmitConfirmOpen(false);
    setMaterialBusy(false);
    setMaterialResetVersion(version => version + 1);
  };

  const refreshMaterialSources = () => {
    void listProcurementSourceProjects().then(setSourceProjects).catch(() => {});
  };
  const useMaterialSource = (source: ProcurementSourceProject) => {
    setSourceProjects((rows) => [...rows.filter(row => row.id !== source.id), source]);
    const primary = materialPrimaryRef.current ?? sourceProjects.find(row => row.id === createForm.sourceProjectId);
    if (primary && primary.id !== source.id && !canCombineProcurementMaterials(primary, source)) {
      setCreateFormError(`“${source.fileName ?? source.name ?? '当前材料'}”与已选采购项目不一致，未合并；请单独创建采购需求。`);
      return;
    }
    if (!primary) materialPrimaryRef.current = source;
    setCreateForm((current) => current.sourceProjectId
      ? mergeProcurementMaterialForm(current, source)
      : procurementMaterialForm(source));
    setCreateFormError(null);
  };

  const handleProcurementFilterChange = (status?: string) => {
    setProcurementStatusFilter(status);
    setProcurementPage(1);
  };

  const handleProcurementKeywordChange = (keyword: string) => {
    setProcurementKeyword(keyword);
    setProcurementPage(1);
  };

  const hasProcurementQuery = Boolean(procurementStatusFilter || procurementKeyword.trim());

  const handleEdit = (project: ProcurementProject) => {
    materialPrimaryRef.current = null;
    if (project.status.toUpperCase() !== "DRAFT") {
      setEditBlockedProject(project);
      return;
    }
    setEditingProcurementId(project.id);
    setCreateForm({
      sourceProjectId: project.sourceProjectId ?? "",
      sourceMaterialIds: project.sourceMaterialIds?.length
        ? project.sourceMaterialIds : project.sourceProjectId ? [project.sourceProjectId] : [],
      projectName: project.projectName,
      departmentName: project.departmentName ?? "",
      procurementType: project.procurementType ?? "",
      budgetAmount: project.budgetAmount == null ? "" : String(project.budgetAmount),
      subjectCode: project.subjectCode ?? "",
      subjectName: project.subjectName ?? "",
      procurementMethod: project.procurementMethod ?? "",
      requirementDescription: project.requirementDescription ?? "",
      applicantName: project.applicantName ?? "",
      attachmentFile: null,
    });
    setCreateFormError(null);
    setCreateFormOpen(true);
  };

  const submitProcurementForm = async (submitForApproval: boolean) => {
    const editing = editingProcurementId !== null;
    const commonPayload = {
      departmentName: createForm.departmentName.trim(),
      procurementType: createForm.procurementType,
      budgetAmount: Number(createForm.budgetAmount),
      subjectCode: createForm.subjectCode.trim(),
      subjectName: createForm.subjectName.trim(),
      procurementMethod: createForm.procurementMethod,
      requirementDescription: createForm.requirementDescription.trim() || undefined,
      applicantName: createForm.applicantName.trim(),
      attachmentName: createForm.attachmentFile?.name,
    };
    setCreateFormLoading(true);
    setCreateFormError(null);
    try {
      if (editingProcurementId) {
        await updateProcurementProject(editingProcurementId, {
          ...commonPayload,
          projectName: createForm.projectName.trim(),
        });
      } else {
        const created = await createProcurementProject({
          ...commonPayload,
          sourceProjectId: createForm.sourceProjectId,
          sourceMaterialIds: createForm.sourceMaterialIds,
          projectName: createForm.projectName.trim(),
          submitForApproval,
        });
        // Explicit save confirms the scope; navigation never starts model calls.
        if (user) {
          if (user.roles.some(role => ['SYSTEM_ADMIN', 'PROCUREMENT_MANAGER', 'AUDITOR'].includes(role)))
            void intakeReviewOperation(user.userId, created.id).start();
          materialBatch(user.userId).clear();
        }
        materialPrimaryRef.current = null;
        setCreateFormOpen(false);
        setEditingProcurementId(null);
        setCreateForm(emptyNewProcurementForm());
        await loadProcurementProjects();
        selectProcurement(created.id);
        return;
      }
      setCreateFormOpen(false);
      setEditingProcurementId(null);
      setCreateForm(emptyNewProcurementForm());
      await loadProcurementProjects();
    } catch (error) {
      setCreateFormError(userFacingError(error, editing ? "采购项目更新失败，请稍后重试。" : submitForApproval ? "提交审批失败，项目仍保留为草稿。" : "采购需求保存失败，请稍后重试。"));
    } finally {
      setCreateFormLoading(false);
    }
  };

  const handleCreateSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (materialBusy || createFormLoading) return;
    const requiredFields: Array<[keyof NewProcurementForm, string]> = [
      ...(!editingProcurementId ? [["sourceProjectId", "已解析项目"]] as Array<[keyof NewProcurementForm, string]> : []),
      ["projectName", "项目名称"],
      ["departmentName", "申请部门"],
      ["procurementType", "采购类型"],
      ["budgetAmount", "预算金额"],
      ["subjectCode", "预算科目编码"],
      ["subjectName", "预算科目名称"],
      ["procurementMethod", "采购方式"],
      ["applicantName", "申请人"],
    ];
    const missing = requiredFields.find(([field]) => !String(createForm[field] ?? "").trim());
    if (missing) {
      setCreateFormError(`请填写${missing[1]}。`);
      return;
    }
    const budgetAmount = Number(createForm.budgetAmount);
    if (!Number.isFinite(budgetAmount) || budgetAmount <= 0) {
      setCreateFormError("预算金额必须是大于 0 的数字。");
      return;
    }
    if (!editingProcurementId && (createForm.sourceMaterialIds.length < 1
      || createForm.sourceMaterialIds.length > 100
      || !createForm.sourceMaterialIds.includes(createForm.sourceProjectId))) {
      setCreateFormError("请关联 1 至 100 份同一项目的采购材料，等待全部材料提取完成后再保存。");
      return;
    }

    if (editingProcurementId) {
      void submitProcurementForm(false);
      return;
    }
    void submitProcurementForm(false);
  };

  const refreshApproval = async () => {
    if (!selectedProcurement) return;
    const [workflow, history] = await Promise.all([
      getProcurementApproval(selectedProcurement),
      getProcurementApprovalHistory(selectedProcurement),
    ]);
    setApproval(workflow);
    setApprovalHistory(history);
  };

  const handleStartApproval = async (note?: string) => {
    if (!selectedProcurement) return;
    setApprovalActionLoading(true);
    setApprovalError(null);
    try {
      await startProcurementApproval(selectedProcurement, note);
      await refreshApproval();
      await loadProcurementProjects();
      setResubmitOpen(false);
      setModificationNote("");
      setResubmitError(null);
    } catch (error) {
      const message = userFacingError(error, "审批流程发起失败，请稍后重试。");
      if (resubmitOpen || note !== undefined) setResubmitError(message);
      else setApprovalError(message);
    } finally {
      setApprovalActionLoading(false);
    }
  };

  const handleApprovalAction = async (action: "approve" | "return" | "comment") => {
    if (!selectedProcurement) return;
    const opinion = approvalOpinion.trim();
    if ((action === "return" || action === "comment") && !opinion) {
      setApprovalError(action === "return" ? "退回修改必须填写审批意见。" : "审查意见不能为空。");
      return;
    }
    setApprovalActionLoading(true);
    setApprovalError(null);
    try {
      if (action === "approve") await approveProcurementApproval(selectedProcurement, opinion || undefined);
      if (action === "return") await returnProcurementApproval(selectedProcurement, opinion);
      if (action === "comment") await addProcurementApprovalComment(selectedProcurement, opinion);
      setApprovalOpinion("");
      await refreshApproval();
      await loadProcurementProjects();
    } catch (error) {
      setApprovalError(userFacingError(error, "审批操作失败，请稍后重试。"));
    } finally {
      setApprovalActionLoading(false);
    }
  };
  const handleDelete = (project: ProcurementProject) => {
    setDeleteError(null);
    setDeleteSuccess(null);
    setDeleteTarget(project);
  };

  const confirmDraftSubmission = async () => {
    if (!selectedProcurement) return;
    try {
      await handleStartApproval();
      setDraftSubmitConfirmOpen(false);
    } catch {
      // handleStartApproval already exposes the user-facing error in the approval panel.
    }
  };

  const openResubmit = () => {
    setModificationNote("");
    setResubmitError(null);
    setResubmitOpen(true);
  };

  const confirmDelete = async () => {
    if (!deleteTarget) return;
    const target = deleteTarget;
    setProcurementDeleteLoadingId(target.id);
    setProcurementListError(null);
    try {
      await deleteProcurementProject(target.id);
      setDeleteTarget(null);
      setDeleteError(null);
      setDeleteSuccess(`项目“${target.projectName}”已删除。`);
      if (selectedProcurement === target.id) setSelectedProcurement(null);
      await loadProcurementProjects();
    } catch (error) {
      const backendMessage = error instanceof ApiError && error.status > 0 && error.message.trim()
        ? error.message
        : null;
      setDeleteError(backendMessage ?? userFacingError(error, "采购项目删除失败，请稍后重试。"));
    } finally {
      setProcurementDeleteLoadingId(null);
    }
  };

  const openReassign = () => {
    setReassignTargetUserId("");
    setReassignOpinion("");
    setReassignError(null);
    setReassignOpen(true);
  };

  const handleReassign = async () => {
    if (!selectedProcurement || !approval?.currentNodeCode) return;
    if (!reassignTargetUserId.trim()) {
      setReassignError("请输入目标审批人用户 ID。");
      return;
    }
    setReassignLoading(true);
    setReassignError(null);
    try {
      await reassignProcurementApproval(selectedProcurement, approval.currentNodeCode, reassignTargetUserId, reassignOpinion);
      setReassignOpen(false);
      await refreshApproval();
    } catch (cause) {
      setReassignError(userFacingError(cause, "审批人改派失败，请检查用户 ID 后重试。"));
    } finally {
      setReassignLoading(false);
    }
  };

  const selectedProject = procurementProjects.find((project) => project.id === selectedProcurement) ?? null;
  const currentApprovalNode = approval?.nodes.find((node) => node.nodeCode === approval.currentNodeCode) ?? null;
  const isSystemAdmin = user?.roles.includes("SYSTEM_ADMIN") ?? false;
  const isCurrentAssignee = Boolean(currentApprovalNode?.assigneeUserId && currentApprovalNode.assigneeUserId === user?.userId);
  const applicantSelfApprovalBlocked = approval?.startedByUserId === user?.userId && !isSystemAdmin;
  const canOperateApproval = Boolean(approval?.status === "IN_PROGRESS" && (isSystemAdmin || (isCurrentAssignee && !applicantSelfApprovalBlocked)));
  const approvalDisabledReason = currentApprovalNode?.assigneeUserId == null
    ? "当前节点尚未指派审批人，请联系系统管理员改派。"
    : applicantSelfApprovalBlocked
      ? "申请人不能审批自己发起的流程。"
      : !isCurrentAssignee && !isSystemAdmin
        ? `当前节点由 ${currentApprovalNode.assigneeName ?? "其他审批人"} 处理。`
        : null;
  const confirmedFieldCodes = new Set(fieldConfirmations.map((confirmation) => confirmation.fieldCode));
  const pendingConfirmationItems = agentReview?.items.filter((item) => item.needsConfirmation && !confirmedFieldCodes.has(item.checkType)) ?? [];
  const canConfirmReviewFields = user?.roles.some((role) => ["SYSTEM_ADMIN", "PROCUREMENT_MANAGER", "PROCUREMENT_APPLICANT"].includes(role)) ?? false;

  const runQuoteUpload = async (
    quotePreview: SpreadsheetHeaderPreview,
    blacklistPreview: SpreadsheetHeaderPreview,
    selections?: Record<"quote" | "blacklist", HeaderMappingSelection>,
  ) => {
    if (!quoteFile || !selectedProcurement) return;
    const quoteSelection = selections?.quote;
    const blacklistSelection = selections?.blacklist;
    setQuoteLoading(true);
    setQuoteError(null);
    setBlacklistError(null);
    try {
      const [quoteAnalysis, blacklistCheck] = await Promise.allSettled([
        quoteSelection
          ? uploadProcurementQuoteCheckConfirmed(quoteFile, selectedProcurement, quoteSelection.headerRowNumber, quoteSelection.mappings)
          : uploadProcurementQuoteCheck(quoteFile, selectedProcurement),
        blacklistSelection
          ? uploadBlacklistBatchConfirmed(quoteFile, selectedProcurement, blacklistSelection.headerRowNumber, blacklistSelection.mappings)
          : uploadBlacklistBatch(quoteFile, selectedProcurement),
      ]);

      if (quoteAnalysis.status === "fulfilled") {
        setQuoteResult(quoteAnalysis.value);
        setQuoteHistoryBatchId(quoteAnalysis.value.batchId);
      } else {
        setQuoteResult(null);
        setQuoteError(userFacingError(quoteAnalysis.reason, "询比价异常分析失败，请检查 Excel 或稍后重试。"));
      }
      if (blacklistCheck.status === "fulfilled") {
        setBlacklistResult(blacklistCheck.value);
      } else {
        setBlacklistResult(null);
        setBlacklistError(userFacingError(blacklistCheck.reason, "黑名单库匹配失败，请稍后重试。"));
      }
    } finally {
      setQuoteLoading(false);
    }
  };

  const handleQuoteUpload = async () => {
    if (!quoteFile || !selectedProcurement) {
      setQuoteError("请先选择询比价 Excel 文件。");
      return;
    }
    setQuoteLoading(true);
    setQuoteError(null);
    setBlacklistError(null);
    try {
      const previews = await Promise.allSettled([
        previewProcurementQuoteHeaders(quoteFile),
        previewBlacklistBatchHeaders(quoteFile),
      ]);
      const quotePreview = previews[0];
      const blacklistPreview = previews[1];
      if (quotePreview.status === "rejected") {
        setQuoteError(userFacingError(quotePreview.reason, "询比价 Excel 预解析失败，请检查文件后重试。"));
        return;
      }
      if (blacklistPreview.status === "rejected") {
        setBlacklistError(userFacingError(blacklistPreview.reason, "黑名单 Excel 预解析失败，请检查文件后重试。"));
        return;
      }
      const nextPreviews = { quote: quotePreview.value, blacklist: blacklistPreview.value };
      const needsConfirmation = [quotePreview.value, blacklistPreview.value]
        .some((preview) => preview.aiAvailable || preview.mappings.length === 0);
      if (needsConfirmation) {
        setHeaderPreviews(nextPreviews);
        return;
      }
      await runQuoteUpload(nextPreviews.quote, nextPreviews.blacklist);
    } finally {
      setQuoteLoading(false);
    }
  };

  const handleHeaderMappingConfirm = async (
    selections: Record<"quote" | "blacklist", HeaderMappingSelection>,
  ) => {
    if (!headerPreviews) return;
    const previews = headerPreviews;
    setHeaderPreviews(null);
    await runQuoteUpload(previews.quote, previews.blacklist, selections);
  };

  const handleQuoteHistoryQuery = async () => {
    const batchId = quoteHistoryBatchId.trim();
    if (!batchId) {
      setQuoteError("请输入要查询的批次号。");
      return;
    }

    setQuoteHistoryLoading(true);
    setQuoteError(null);
    try {
      setQuoteResult(await getProcurementQuoteCheck(batchId));
    } catch (error) {
      setQuoteResult(null);
      setQuoteError(userFacingError(error, "历史询比价结果查询失败，请确认批次号。"));
    } finally {
      setQuoteHistoryLoading(false);
    }
  };

  const handleReviewReport = async (action: "generate" | "query") => {
    if (!selectedProcurement) return;
    setReviewReportLoading(true);
    setReviewReportError(null);
    setReviewReportNotice(null);
    try {
      const result = action === "generate"
        ? await generateProcurementReviewReport(selectedProcurement)
        : await getProcurementReviewReport(selectedProcurement);
      setReviewReport(result);
      if (action === "query" && !result) {
        setReviewReportNotice("暂无报告，请先生成采购审查报告");
      }
    } catch (error) {
      setReviewReport(null);
      if (action === "query" && error instanceof ApiError && error.status === 404) {
        setReviewReportNotice("暂无报告，请先生成采购审查报告");
      } else {
        setReviewReportError(userFacingError(error, action === "generate" ? "采购审查报告生成失败，请稍后重试。" : "采购审查报告查询失败，请稍后重试。"));
      }
    } finally {
      setReviewReportLoading(false);
    }
  };

  const handleProjectExport = async () => {
    if (!selectedProject) return;
    setProjectExportLoading(true);
    setProjectExportError(null);
    try {
      const result = await exportProcurementProject(selectedProject.id);
      const objectUrl = URL.createObjectURL(result.blob);
      const today = new Date();
      const date = `${today.getFullYear()}${String(today.getMonth() + 1).padStart(2, "0")}${String(today.getDate()).padStart(2, "0")}`;
      const anchor = document.createElement("a");
      anchor.href = objectUrl;
      anchor.download = result.filename || `采购需求_${selectedProject.projectCode}_${date}.xlsx`;
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      URL.revokeObjectURL(objectUrl);
    } catch (error) {
      setProjectExportError(userFacingError(error, "采购需求导出失败，请稍后重试。"));
    } finally {
      setProjectExportLoading(false);
    }
  };

  if (!selectedProcurement) {
    return (
      <div className="p-6 space-y-4">
          {user && !showFormalProcurementManagement && <AutomaticCasePanel ownerId={user.userId} />}

        {showFormalProcurementManagement && <section id="formal-procurement-management" aria-label="正式采购需求与审批管理" className="space-y-4 border-t border-slate-200 pt-5">
        <div className="flex items-center justify-between gap-3">
          <div>
            <h3 className="text-sm font-semibold text-slate-900">早期采购记录</h3>
            <p className="mt-1 text-xs text-slate-500">已有记录继续保留，可打开查看；新材料请使用采购首页的批次上传入口。</p>
          </div>
          <div className="flex gap-2">
            <button type="button" onClick={() => setShowFormalProcurementManagement(false)} className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm">返回采购首页</button>
          </div>
        </div>
        {/* Filters */}
        <div className="flex gap-2 flex-wrap">
          <div className="ml-auto flex items-center gap-2 bg-card border border-slate-200 rounded px-3 py-1.5">
            <Search size={12} className="text-muted-foreground" />
            <input
              value={procurementKeyword}
              onChange={(event) => handleProcurementKeywordChange(event.target.value)}
              className="bg-transparent text-xs text-slate-900 placeholder:text-muted-foreground focus:outline-none w-40"
              placeholder="搜索项目名称…"
              aria-label="按项目名称搜索"
            />
            {procurementKeyword && (
              <button
                type="button"
                onClick={() => handleProcurementKeywordChange("")}
                className="text-muted-foreground hover:text-slate-700"
                title="清空搜索"
                aria-label="清空搜索"
              >
                <XCircle size={13} />
              </button>
            )}
          </div>
        </div>

        {/* Stats */}
        <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
          <StatCard label="查询项目数" value={procurementListLoading ? "—" : String(procurementTotal)} sub="当前查询条件下的项目" />
          <StatCard label="本页项目" value={procurementListLoading ? "—" : String(procurementProjects.length)} sub="当前列表展示的项目" />
          <StatCard label="本页预算合计" value={procurementListLoading ? "—" : formatQuoteMoney(procurementProjects.reduce((sum, project) => sum + Math.round((project.budgetAmount ?? 0) * 100), 0) / 100)} sub="仅合计当前页项目预算" accent="text-blue-700" />
        </div>

        {procurementListError && (
          <div className="rounded border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700">
            {procurementListError}
          </div>
        )}
        {deleteSuccess && (
          <div role="status" className="rounded border border-emerald-200 bg-emerald-50 px-3 py-2 text-xs text-emerald-700">
            {deleteSuccess}
          </div>
        )}

        {/* Table */}
        <div className="bg-card border border-slate-200 rounded-lg overflow-hidden">
          <div className="overflow-x-auto">
            <table className="procurement-demand-table w-full text-xs">
              <thead>
                <tr className="border-b border-slate-200 text-muted-foreground">
                  {["需求编号", "项目名称", "申请部门", "采购类型", "预算金额", "智能体预审", "询比价报警", "创建时间", "操作"].map(h => (
                    <th key={h} className="px-4 py-3 text-left font-medium whitespace-nowrap">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-200">
                {procurementListLoading ? (
                  <tr><td colSpan={9} className="px-4 py-8 text-center text-xs text-muted-foreground">正在加载采购项目…</td></tr>
                ) : procurementProjects.length === 0 ? (
                  <tr><td colSpan={9} className="px-4 py-8 text-center text-xs text-muted-foreground">{hasProcurementQuery ? "暂无符合条件的项目" : "暂无采购项目"}</td></tr>
                ) : procurementProjects.map((p) => {
                  return (
                    <tr key={p.id} onClick={() => selectProcurement(p.id)} className="hover:bg-slate-50 transition-colors cursor-pointer">
                      <td className="px-4 py-3 font-mono text-muted-foreground">{p.projectCode}</td>
                      <td className="px-4 py-3 text-slate-900 font-medium">{p.projectName}</td>
                      <td className="px-4 py-3 text-muted-foreground">{p.departmentName || "-"}</td>
                      <td className="px-4 py-3">
                        <span className="px-2 py-0.5 rounded bg-slate-100 text-slate-500 font-mono">{p.procurementType || "-"}</span>
                      </td>
                      <td className="px-4 py-3 font-mono text-slate-600">{formatQuoteMoney(p.budgetAmount ?? 0)}</td>

                      <td className="px-4 py-3">{procurementReviewBadge(p)}</td>
                      <td className="px-4 py-3">
                        <button
                          onClick={(event) => { event.stopPropagation(); selectProcurement(p.id); }}
                          className="inline-flex items-center gap-1.5 rounded border border-indigo-200 bg-indigo-50 px-2.5 py-1.5 text-xs text-indigo-700 hover:bg-indigo-100 transition-colors"
                          title="进入询比价异常报警分析"
                        >
                          <BarChart3 size={12} />
                          进入分析
                        </button>
                      </td>
                      <td className="px-4 py-3 font-mono text-muted-foreground">{formatProcurementDate(p.createdTime)}</td>
                      <td className="px-4 py-3">
                        <div className="flex gap-2">
                          <button
                            onClick={(event) => { event.stopPropagation(); selectProcurement(p.id); }}
                            className="text-blue-700 hover:text-blue-700 transition-colors"
                            title="查看采购项目详情"
                            aria-label="查看采购项目详情"
                          >
                            <Eye size={13} />
                          </button>
                          <button
                            onClick={(event) => { event.stopPropagation(); handleEdit(p); }}
                            className="text-emerald-700 hover:text-emerald-700 transition-colors"
                            title="编辑采购项目"
                            aria-label="编辑采购项目"
                          >
                            <Pencil size={13} />
                          </button>
                          <button
                            onClick={(event) => { event.stopPropagation(); handleDelete(p); }}
                            disabled={procurementDeleteLoadingId === p.id}
                            className="text-red-700 hover:text-red-700 transition-colors disabled:cursor-wait disabled:opacity-50"
                            title="删除采购项目"
                            aria-label="删除采购项目"
                          >
                            <XCircle size={13} />
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          {!procurementListLoading && procurementTotalPages > 0 && (
            <div className="flex items-center justify-between gap-3 border-t border-slate-200 px-4 py-3 text-xs text-muted-foreground">
              <span>共 {procurementTotal} 个项目，第 {procurementPage} / {procurementTotalPages} 页</span>
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={() => setProcurementPage((page) => Math.max(1, page - 1))}
                  disabled={procurementPage <= 1}
                  className="rounded border border-slate-200 bg-white px-3 py-1.5 text-xs text-slate-700 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  上一页
                </button>
                <button
                  type="button"
                  onClick={() => setProcurementPage((page) => Math.min(procurementTotalPages, page + 1))}
                  disabled={procurementPage >= procurementTotalPages}
                  className="rounded border border-slate-200 bg-white px-3 py-1.5 text-xs text-slate-700 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  下一页
                </button>
              </div>
            </div>
          )}
        </div>
        <NewProcurementDialog
          open={createFormOpen}
          mode={editingProcurementId ? "edit" : "create"}
          form={createForm}
          materialResetVersion={materialResetVersion}
          onClearMaterials={clearProcurementMaterials}
          actorId={user?.userId ?? ""}
          onMaterialParsed={refreshMaterialSources}
          onMaterialUse={useMaterialSource}
          onMaterialBusy={setMaterialBusy}
          existingAttachmentName={editingProcurementId
            ? procurementProjects.find((project) => project.id === editingProcurementId)?.attachmentName
            : null}
          loading={createFormLoading || materialBusy}
          closingBlocked={createFormLoading}
          error={createFormError || (createForm.materialConflicts?.some(field => createForm[field as keyof NewProcurementForm] === '') ? '材料中的部分信息不一致，已留空，请核对原文后填写。' : null)}
          onChange={(field, value) => setCreateForm((current) => ({ ...current, [field]: value }))}
          onClose={() => {
            materialPrimaryRef.current = null;
            setCreateFormOpen(false);
            setEditingProcurementId(null);
            setCreateFormError(null);
          }}
          onSubmit={handleCreateSubmit}
        />
        <ConfirmDialog
          open={createSubmitConfirmOpen}
          title="确认提交审批？"
          description="提交后将进入审批流程，无法编辑。请确认采购需求信息已填写完整。"
          loading={createFormLoading}
          onOpenChange={(open) => {
            if (!createFormLoading) setCreateSubmitConfirmOpen(open);
          }}
          onConfirm={() => {
            void submitProcurementForm(true)
              .then(() => setCreateSubmitConfirmOpen(false))
              .catch(() => setCreateSubmitConfirmOpen(false));
          }}
        />
        <ConfirmDialog
          open={deleteTarget !== null}
          title="确认删除"
          description={deleteTarget ? <>确认删除项目“{deleteTarget.projectName}”吗？删除后不可恢复。</> : ""}
          destructive
          error={deleteError}
          errorTitle="无法删除"
          errorActionLabel="关闭"
          loading={procurementDeleteLoadingId !== null}
          onOpenChange={(open) => {
            if (!open && procurementDeleteLoadingId === null) {
              setDeleteTarget(null);
              setDeleteError(null);
            }
          }}
          onConfirm={() => { void confirmDelete(); }}
        />
        <NoticeDialog
          open={editBlockedProject !== null}
          title="无法编辑"
          description="当前记录暂不支持修改，请保留原记录并核对材料。"
          onOpenChange={(open) => { if (!open) setEditBlockedProject(null); }}
        />
        </section>}
      </div>
    );
  }

  if (!selectedProject) {
    return (
      <div className="p-6">
        <div className="rounded border border-red-200 bg-red-50 px-4 py-3 text-xs text-red-700">
          未找到该采购项目，请返回列表后重新选择。
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-5 p-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex min-w-0 items-center gap-3">
          <button type="button" onClick={() => setSelectedProcurement(null)} className="shrink-0 text-slate-500 hover:text-slate-700" aria-label="返回采购项目列表" title="返回采购项目列表"><ArrowRight size={16} className="rotate-180" /></button>
          <div className="min-w-0"><h2 className="text-base font-semibold text-slate-900">采购项目详情</h2><p className="mt-1 truncate text-xs text-slate-500">{selectedProject.projectCode} · {selectedProject.projectName}</p></div>
        </div>
      </div>

      <section className="rounded-lg border border-slate-200 bg-white px-5 py-4" aria-labelledby="procurement-project-summary">
        <h3 id="procurement-project-summary" className="text-sm font-semibold text-slate-900">项目基本信息</h3>
        <dl className="mt-4 grid gap-x-6 gap-y-4 text-xs sm:grid-cols-2 xl:grid-cols-4">
          {[["需求编号", selectedProject.projectCode], ["项目名称", selectedProject.projectName], ["申请部门", selectedProject.departmentName || "-"], ["申请人", selectedProject.applicantName || "-"], ["采购类型", selectedProject.procurementType || "-"], ["采购方式", selectedProject.procurementMethod || "公开招标"], ["预算金额", formatQuoteMoney(selectedProject.budgetAmount ?? 0)], ["创建时间", formatProcurementDateTime(selectedProject.createdTime)]].map(([label, value]) => <div key={label} className="min-w-0 border-b border-slate-100 pb-3"><dt className="text-slate-500">{label}</dt><dd className="mt-1 break-words font-medium text-slate-900">{value}</dd></div>)}
        </dl>
        {selectedProject.requirementDescription && <p className="mt-4 whitespace-pre-wrap border-t border-slate-100 pt-4 text-xs leading-5 text-slate-600"><strong className="font-medium text-slate-800">需求说明：</strong>{selectedProject.requirementDescription}</p>}
      </section>

      <ProcurementReviewWizard
        project={selectedProject}
        embedded
      />
    </div>
  );

  // 详情页面
  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <button onClick={() => setSelectedProcurement(null)} className="text-muted-foreground hover:text-slate-600 transition-colors">
            <ArrowRight size={16} className="rotate-180" />
          </button>
          <div>
            <h2 className="text-base font-semibold text-slate-900">采购需求详情</h2>
          </div>
        </div>
        <div className="flex gap-2">
          <button
            type="button"
            onClick={() => void handleReviewReport("generate")}
            disabled={reviewReportLoading}
            className="text-xs px-3 py-1.5 rounded bg-indigo-50 text-indigo-700 border border-indigo-200 hover:bg-indigo-100 disabled:cursor-not-allowed disabled:opacity-50 transition-colors"
          >
            {reviewReportLoading ? "报告处理中…" : "生成采购审查报告"}
          </button>
          <button
            type="button"
            onClick={() => void handleProjectExport()}
            disabled={projectExportLoading}
            className="text-xs px-3 py-1.5 rounded bg-slate-100 text-slate-600 hover:bg-slate-200 border border-slate-200 disabled:cursor-not-allowed disabled:opacity-50 transition-colors"
          >
            {projectExportLoading
              ? <RefreshCw size={11} className="inline mr-1 animate-spin" />
              : <Download size={11} className="inline mr-1" />}
            {projectExportLoading ? "导出中…" : "导出需求"}
          </button>
        </div>
      </div>

      {projectExportError && (
        <div className="rounded border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700">
          {projectExportError}
        </div>
      )}

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* 需求信息 */}
        <div className="lg:col-span-2 space-y-6">
          <div className="bg-card border border-slate-200 rounded-lg p-5">
            <h3 className="text-sm font-semibold text-slate-900 mb-4">基本信息</h3>
            <div className="grid grid-cols-2 gap-4 text-xs">
              {[
                ["需求编号", selectedProject.projectCode],
                ["项目名称", selectedProject.projectName],
                ["申请部门", selectedProject.departmentName || "-"],
                ["申请人", selectedProject.applicantName || "-"],
                ["采购类型", selectedProject.procurementType || "-"],
                ["采购方式", selectedProject.procurementMethod || "公开招标"],
                ["预算金额", formatQuoteMoney(selectedProject.budgetAmount ?? 0)],
                ["创建时间", formatProcurementDateTime(selectedProject.createdTime)],
              ].map(([k, v]) => (
                <div key={k} className="flex justify-between py-2 border-b border-slate-200">
                  <span className="text-muted-foreground">{k}</span>
                  <span className="font-mono text-slate-900">{v}</span>
                </div>
              ))}
            </div>
          </div>

          <div className="bg-card border border-indigo-200 rounded-lg overflow-hidden">
            <div className="px-5 py-4 border-b border-indigo-100 flex items-center justify-between gap-3">
              <div>
                <h3 className="text-sm font-semibold text-slate-900">采购审查报告</h3>
                <p className="mt-0.5 text-[11px] text-muted-foreground">汇总智能体预审、询比价分析和黑名单比对结果</p>
              </div>
              <button
                type="button"
                onClick={() => void handleReviewReport("query")}
                disabled={reviewReportLoading}
                className="rounded border border-slate-200 bg-white px-2.5 py-1.5 text-xs text-slate-600 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50"
              >
                查询已有报告
              </button>
            </div>
            <div className="p-4">
              {reviewReportError && <div className="mb-3 rounded border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700">{reviewReportError}</div>}
              {reviewReportNotice && <div className="mb-3 rounded border border-blue-200 bg-blue-50 px-3 py-2 text-xs text-blue-700">{reviewReportNotice}</div>}
              {reviewReport ? <ProcurementReviewReportPanel report={reviewReport} /> : <div className="text-xs text-muted-foreground">尚未生成报告。完成询比价和黑名单比对后，可生成统一采购审查报告。</div>}
            </div>
          </div>

          <div className="bg-card border border-slate-200 rounded-lg p-5">
            <h3 className="text-sm font-semibold text-slate-900 mb-4">需求说明</h3>
            <div className="space-y-3 text-sm text-slate-600 leading-relaxed">
              {selectedProject.requirementDescription ? (
                <p className="whitespace-pre-wrap"><strong className="text-slate-600">需求说明：</strong>{selectedProject.requirementDescription}</p>
              ) : (
                <p><strong className="text-slate-600">需求说明：</strong>暂未填写需求说明和技术要求。</p>
              )}
            </div>
          </div>

          <div className="bg-card border border-slate-200 rounded-lg overflow-hidden">
            <div className="px-5 py-4 border-b border-slate-200 flex items-center gap-2">
              <Brain size={14} className={agentScanPhase === "scanning" ? "text-blue-700 animate-pulse" : "text-blue-700"} />
              <h3 className="text-sm font-semibold text-slate-900">项目规则预审</h3>
              {agentScanPhase === "done" && agentReview && (
                <button
                  type="button"
                  onClick={(event) => { event.stopPropagation(); void handleRefreshAgentReview(); }}
                  disabled={agentScanPhase === "scanning"}
                  className="ml-auto inline-flex items-center gap-1 rounded border border-blue-200 bg-white px-2 py-1 text-xs text-blue-700 hover:bg-blue-50 disabled:opacity-50"
                  title="重新执行智能体预审"
                >
                  <RefreshCw size={11} />重新预审
                </button>
              )}
              {agentScanPhase === "scanning" && (
                <span className="flex items-center gap-1.5 ml-auto text-xs font-mono text-blue-700">
                  <RefreshCw size={11} className="animate-spin" />预审引擎分析中…
                </span>
              )}
              {agentScanPhase === "done" && (
                <span className={`ml-auto text-xs font-mono px-2 py-0.5 rounded border ${agentReviewError || agentReview?.items.some((item) => item.status === "FAILED") ? "text-red-700 bg-red-50 border-red-200" : agentReview?.items.some((item) => item.needsConfirmation) ? "text-amber-700 bg-amber-50 border-amber-200" : "text-emerald-700 bg-emerald-50 border-emerald-200"}`}>
                  {agentReviewError ? "加载失败" : agentReview ? `${agentReview.items.filter((item) => item.status === "FAILED").length} 项需处理，${pendingConfirmationItems.length} 项待确认` : "暂无结果"}
                </span>
              )}
            </div>
            {agentScanPhase === "scanning" && (
              <div className="p-4 space-y-2">
                {agentScanSteps.map((step, i) => (
                  <div key={i} className="flex items-center gap-2 text-xs">
                    <div className={`size-4 rounded flex items-center justify-center shrink-0 ${i < agentScanStep ? "bg-emerald-100" : i === agentScanStep ? "bg-blue-100" : "bg-slate-50"}`}>
                      {i < agentScanStep
                        ? <CheckCircle size={9} className="text-emerald-700" />
                        : i === agentScanStep
                          ? <RefreshCw size={9} className="text-blue-700 animate-spin" />
                          : <span className="size-1 rounded-full bg-slate-200 block" />}
                    </div>
                    <span className={i < agentScanStep ? "text-emerald-700" : i === agentScanStep ? "text-slate-900" : "text-slate-400"}>{step}</span>
                  </div>
                ))}
              </div>
            )}
              {agentScanPhase === "done" && (
                <div className="p-4 space-y-3">
                  {agentReviewError ? (
                    <div className="rounded border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700">
                      {agentReviewError}
                    </div>
                  ) : agentReview ? (
                    <>
                      <div className="rounded border border-blue-100 bg-blue-50/60 px-3 py-2 text-xs text-slate-600">
                  <div className="flex items-center justify-between gap-3">
                    <span>规则预审结论：<span className="text-slate-900">{agentReview.suggestion}</span></span>
                    <span className="shrink-0 text-slate-500">上次预审：{formatProcurementDateTime(agentReview.generatedTime)}</span>
                  </div>
                        {agentReview.aiSummary && (
                          <div className="mt-1 border-t border-blue-100 pt-1 text-slate-700">
                            AI 预审摘要：<span className="text-slate-900">{agentReview.aiSummary}</span>
                          </div>
                        )}
                      </div>
                      {fieldConfirmationError && (
                        <div role="alert" className="rounded border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700">{fieldConfirmationError}</div>
                      )}
                      {toAgentResults(agentReview, fieldConfirmations, {
                        canConfirm: canConfirmReviewFields,
                        confirmationNotes: fieldConfirmationNotes,
                        confirmationLoading: fieldConfirmationLoading,
                        confirmationError: fieldConfirmationError,
                        onConfirmationNoteChange: (fieldCode, value) => setFieldConfirmationNotes((current) => ({ ...current, [fieldCode]: value })),
                        onConfirm: (fieldCode) => void handleConfirmReviewField(fieldCode),
                        onEdit: () => selectedProject && handleEdit(selectedProject),
                      }).map((result, index) => (
                        <AgentCard key={`${agentReview.projectId}-${result.title}`} result={result} expanded={index === 0} />
                      ))}
                    </>
                  ) : (
                    <div className="rounded border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-muted-foreground">
                      暂无预审结果。
                    </div>
                  )}
                </div>
              )}
          </div>

          <ProcurementTenderReviewPanel projectId={selectedProject.id} />

          <div className="rounded-lg border border-blue-200 bg-blue-50/60 px-4 py-3 text-xs text-slate-700">
            <div className="mb-1 flex items-center gap-2 font-medium text-blue-900">
              <FileSearch size={13} />采购流程说明
            </div>
            <p>
              新建采购需求阶段填写项目基本信息、预算和预算科目，不录入询比价供应商。完成询比价后上传 Excel，系统才会提取材料中的供应商，并查询已维护的黑名单库生成比对结果。
            </p>
          </div>

          <div className="bg-card border border-slate-200 rounded-lg overflow-hidden">
            <div className="px-5 py-4 border-b border-slate-200 flex items-center justify-between gap-3">
              <div className="flex items-center gap-2">
                <ShieldAlert size={14} className="text-red-600" />
                <div>
                  <h3 className="text-sm font-semibold text-slate-900">供应商黑名单比对</h3>
                  <p className="mt-0.5 text-[11px] text-muted-foreground">
                    使用已维护的黑名单库自动匹配，不在此处上传黑名单文件
                  </p>
                </div>
              </div>
              <span className="text-xs text-muted-foreground">
                告警数量：<strong className="font-mono text-red-700">{blacklistResult?.hitCount ?? 0}</strong>
              </span>
            </div>
            <div className="p-4 space-y-3">
              <div className="rounded border border-blue-100 bg-blue-50/60 px-3 py-2 text-xs text-slate-600">
                上传下方询比价材料后，系统会提取其中的供应商信息，自动查询现有黑名单数据库并生成命中告警。
              </div>

              {blacklistError && (
                <div className="rounded border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700">
                  {blacklistError}
                </div>
              )}

              {blacklistResult && (
                <div className="space-y-3">
                  <div className="grid grid-cols-3 gap-2 text-xs">
                    <div className="rounded border border-slate-200 bg-slate-50 px-3 py-2">
                      <span className="text-muted-foreground">供应商总数</span>
                      <strong className="ml-2 font-mono text-slate-900">{blacklistResult.totalCount}</strong>
                    </div>
                    <div className="rounded border border-red-200 bg-red-50 px-3 py-2">
                      <span className="text-red-700">命中</span>
                      <strong className="ml-2 font-mono text-red-700">{blacklistResult.hitCount}</strong>
                    </div>
                    <div className="rounded border border-emerald-200 bg-emerald-50 px-3 py-2">
                      <span className="text-emerald-700">未命中</span>
                      <strong className="ml-2 font-mono text-emerald-700">{blacklistResult.missCount}</strong>
                    </div>
                  </div>

                  {blacklistResult.hits.length > 0 && (
                    <div>
                      <h4 className="mb-1.5 text-xs font-medium text-red-700">命中供应商</h4>
                      <div className="overflow-x-auto rounded border border-red-200">
                        <table className="w-full text-xs">
                          <thead className="bg-red-50 text-red-800">
                            <tr>
                              {['行号', '供应商名称', '统一社会信用代码', '匹配方式', '黑名单原因'].map((header) => (
                                <th key={header} className="px-3 py-2 text-left font-medium whitespace-nowrap">{header}</th>
                              ))}
                            </tr>
                          </thead>
                          <tbody className="divide-y divide-red-100">
                            {blacklistResult.hits.map((hit) => (
                              <tr key={`${hit.rowNumber}-${hit.alertId}`} className="bg-red-50/60 text-red-950">
                                <td className="px-3 py-2 font-mono">{hit.rowNumber}</td>
                                <td className="px-3 py-2 font-medium">{hit.supplierName}</td>
                                <td className="px-3 py-2 font-mono">{hit.supplierCode || "-"}</td>
                                <td className="px-3 py-2">{enumLabel(hit.matchType)}（{hit.matchScore}分）</td>
                                <td className="px-3 py-2">{hit.blacklistReason || "已命中黑名单"}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    </div>
                  )}

                  {blacklistResult.misses.length > 0 && (
                    <div>
                      <h4 className="mb-1.5 text-xs font-medium text-emerald-700">未命中供应商</h4>
                      <div className="overflow-x-auto rounded border border-emerald-200">
                        <table className="w-full text-xs">
                          <thead className="bg-emerald-50 text-emerald-800">
                            <tr>
                              {['行号', '供应商名称', '统一社会信用代码'].map((header) => (
                                <th key={header} className="px-3 py-2 text-left font-medium whitespace-nowrap">{header}</th>
                              ))}
                            </tr>
                          </thead>
                          <tbody className="divide-y divide-emerald-100">
                            {blacklistResult.misses.map((miss) => (
                              <tr key={`${miss.rowNumber}-${miss.supplierName}`} className="bg-emerald-50/40 text-emerald-950">
                                <td className="px-3 py-2 font-mono">{miss.rowNumber}</td>
                                <td className="px-3 py-2">{miss.supplierName}</td>
                                <td className="px-3 py-2 font-mono">{miss.supplierCode || "-"}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    </div>
                  )}
                </div>
              )}
            </div>
          </div>

          <div className="bg-card border border-slate-200 rounded-lg overflow-hidden">
            <div className="px-5 py-4 border-b border-slate-200 flex items-center justify-between gap-3">
              <div className="flex items-center gap-2">
                <BarChart3 size={14} className="text-blue-700" />
                <div>
                  <h3 className="text-sm font-semibold text-slate-900">询比价异常报警</h3>
                  <p className="mt-0.5 text-[11px] text-muted-foreground">分析报价超预算、离散程度与可疑供应商</p>
                </div>
              </div>
              {quoteResult && (
                <span className={`rounded border px-2 py-0.5 text-xs font-medium ${
                  quoteResult.riskLevel === "CRITICAL"
                    ? "border-red-200 bg-red-50 text-red-700"
                    : quoteResult.riskLevel === "WARNING"
                      ? "border-amber-200 bg-amber-50 text-amber-700"
                      : "border-emerald-200 bg-emerald-50 text-emerald-700"
                }`}>
                  {quoteResult.riskLevel === "CRITICAL" ? "严重异常" : quoteResult.riskLevel === "WARNING" ? "一般异常" : "正常"}
                </span>
              )}
            </div>

            <div className="p-4 space-y-4">
              <div className="grid gap-3 lg:grid-cols-[minmax(0,1fr)_180px_auto]">
                <label className="flex h-10 min-w-0 items-center gap-2 overflow-hidden rounded border border-dashed border-slate-300 bg-slate-50 px-3 text-xs text-muted-foreground cursor-pointer hover:border-blue-300 hover:bg-blue-50 transition-colors">
                  <Paperclip size={13} className="shrink-0" />
                  <span className="min-w-0 flex-1 truncate">{quoteFile?.name ?? "选择询比价 Excel（供应商名称、报价金额为必填列）"}</span>
                  <input
                    type="file"
                    accept=".xlsx,.xls"
                    className="sr-only"
                    onChange={(event) => {
                      setQuoteFile(event.target.files?.[0] ?? null);
                      setQuoteResult(null);
                      setQuoteError(null);
                    }}
                  />
                </label>
                <div className="flex h-10 min-w-0 items-center gap-2 overflow-hidden rounded border border-slate-200 bg-slate-50 px-3 text-xs text-slate-600">
                  <span className="shrink-0 text-muted-foreground">预算金额</span>
                  <span className="min-w-0 flex-1 truncate font-mono text-slate-900">
                    {selectedProject?.budgetAmount == null
                      ? "未填写"
                      : `¥${Number(selectedProject.budgetAmount).toLocaleString("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`}
                  </span>
                </div>
                <button
                  type="button"
                  onClick={handleQuoteUpload}
                  disabled={quoteLoading || !quoteFile}
                  className="inline-flex items-center justify-center gap-1.5 rounded bg-blue-50 px-3 py-2 text-xs text-blue-700 border border-blue-200 hover:bg-blue-100 disabled:cursor-not-allowed disabled:opacity-50 transition-colors"
                >
                  {quoteLoading ? <RefreshCw size={12} className="animate-spin" /> : <BarChart3 size={12} />}
                  {quoteLoading ? "分析中…" : "开始分析"}
                </button>
              </div>

              <div className="flex flex-wrap items-center gap-2 border-t border-slate-100 pt-3">
                <Clock size={13} className="text-muted-foreground" />
                <span className="text-xs text-muted-foreground">历史批次</span>
                <input
                  value={quoteHistoryBatchId}
                  onChange={(event) => setQuoteHistoryBatchId(event.target.value)}
                  placeholder="输入批次号"
                  className="min-w-48 rounded border border-slate-200 bg-white px-2.5 py-1.5 text-xs font-mono text-slate-900 outline-none focus:border-blue-300"
                />
                <button
                  type="button"
                  onClick={handleQuoteHistoryQuery}
                  disabled={quoteHistoryLoading}
                  className="inline-flex items-center gap-1.5 rounded border border-slate-200 bg-white px-3 py-1.5 text-xs text-slate-600 hover:border-blue-300 hover:text-blue-700 disabled:cursor-not-allowed disabled:opacity-50 transition-colors"
                >
                  {quoteHistoryLoading ? <RefreshCw size={11} className="animate-spin" /> : <Search size={11} />}
                  {quoteHistoryLoading ? "查询中…" : "查询历史"}
                </button>
              </div>

              {quoteLoading && (
                <div role="status" aria-live="polite" className="flex items-center gap-2 rounded border border-blue-200 bg-blue-50 px-3 py-2 text-xs text-blue-700">
                  <RefreshCw size={13} className="animate-spin" />
                  正在分析，请稍后...
                </div>
              )}

              {quoteError && (
                <div className="rounded border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700">
                  {quoteError}
                </div>
              )}

              {!quoteResult && !quoteError && (
                <div className="rounded border border-slate-200 bg-slate-50 px-3 py-3 text-xs text-muted-foreground">
                  上传询比价 Excel 后，系统会自动计算预算超支、报价离散系数和异常等级。
                </div>
              )}

              {quoteResult && <ProcurementQuoteCheckResult result={quoteResult} />}
            </div>
          </div>
        </div>

        {/* 右侧栏 */}
        <div className="space-y-4">
          <div className="bg-card border border-slate-200 rounded-lg p-5">
            <div className="mb-4 flex items-center justify-between gap-2">
              <h3 className="text-sm font-semibold text-slate-900">审批流程</h3>
              {approval && <span className="text-xs text-muted-foreground">{approval.status === "IN_PROGRESS" ? "审批中" : approval.status === "APPROVED" ? "已通过" : "已退回"}</span>}
            </div>
            {approvalLoading ? (
              <div className="py-5 text-center text-xs text-muted-foreground">正在加载审批状态…</div>
            ) : approval ? (
              <div className="space-y-3">
                {approval.status === "RETURNED" && (
                  <div className="rounded border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800">
                    <p className="font-medium">已退回，请修改后重新提交</p>
                    <p className="mt-1">采购需求已恢复为草稿状态，修改完成后可重新发起审批。</p>
                    {approvalHistory.findLast((operation) => operation.operationType === "RETURN")?.opinion && (
                      <p className="mt-1">退回意见：{approvalHistory.findLast((operation) => operation.operationType === "RETURN")?.opinion}</p>
                    )}
                  </div>
                )}
                <div className="rounded border border-blue-100 bg-blue-50 px-3 py-2 text-xs text-blue-700">
                  按预算金额 ¥{selectedProject?.budgetAmount == null ? "-" : Number(selectedProject.budgetAmount).toLocaleString("zh-CN", { minimumFractionDigits: 2 })} 生成的实际审批路径，共 {approval.nodes.length} 个节点
                </div>
                {(pendingConfirmationItems.length > 0 || fieldConfirmations.length > 0) && (
                  <div className="rounded border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-900">
                    <div className="mb-1 font-medium">预审信息确认情况</div>
                    <p>待确认 {pendingConfirmationItems.length} 项，已确认 {fieldConfirmations.length} 项。</p>
                    <p className="mt-1 text-amber-800">请在左侧对应预审项的详情中查看提示和确认记录。</p>
                  </div>
                )}
                {approval.nodes.map((node) => {
                  const done = node.status === "COMPLETED";
                  const active = node.status === "IN_PROGRESS";
                  const returned = node.status === "RETURNED";
                  return (
                    <div key={node.id} className={`flex gap-3 rounded border p-3 text-xs ${active ? "border-blue-200 bg-blue-50" : returned ? "border-red-200 bg-red-50" : done ? "border-slate-200 bg-slate-50" : "border-slate-200"}`}>
                      <div className={`mt-0.5 flex size-5 shrink-0 items-center justify-center rounded-full text-[9px] ${done ? "bg-emerald-500 text-white" : active ? "bg-blue-500 text-white ring-2 ring-blue-500/30" : returned ? "bg-red-500 text-white" : "bg-slate-100 text-slate-400"}`}>
                        {done ? "✓" : returned ? "!" : node.nodeOrder}
                      </div>
                      <div className="min-w-0 flex-1">
                        <div className="mb-0.5 flex justify-between gap-2">
                          <span className="font-medium text-slate-900">{node.nodeName}</span>
                          <span className="shrink-0 font-mono text-muted-foreground">{node.completedTime ? formatProcurementDateTime(node.completedTime) : active ? "处理中" : "待处理"}</span>
                        </div>
                        <p className="text-muted-foreground">负责角色：{procurementApprovalRoleLabel(node.requiredRoleCode)}</p>
                        <p className={node.assigneeName ? "text-slate-700" : "font-medium text-amber-700"}>审批人：{node.assigneeName || "待指派"}</p>
                        {node.assignmentMessage && <p className="mt-1 text-amber-700">{node.assignmentMessage}</p>}
                      </div>
                    </div>
                  );
                })}
                {approval.status === "RETURNED" && user?.roles.some((role) => role === "SYSTEM_ADMIN" || role === "PROCUREMENT_MANAGER" || role === "PROCUREMENT_APPLICANT") && (
                  <button type="button" onClick={openResubmit} disabled={approvalActionLoading} className="w-full rounded border border-blue-200 bg-blue-50 px-3 py-2 text-xs text-blue-700 hover:bg-blue-100 disabled:cursor-not-allowed disabled:opacity-50">
                    {approvalActionLoading ? "重新发起中…" : "修改后重新发起审批"}
                  </button>
                )}
              </div>
            ) : (
              <div className="space-y-3">
                {approvalError && <div className="rounded border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700">{approvalError}</div>}
                {selectedProject?.status.toUpperCase() === "DRAFT" && user?.roles.some((role) => role === "SYSTEM_ADMIN" || role === "PROCUREMENT_MANAGER" || role === "PROCUREMENT_APPLICANT") && (
                  <button type="button" onClick={() => setDraftSubmitConfirmOpen(true)} disabled={approvalActionLoading} className="w-full rounded border border-blue-200 bg-blue-50 px-3 py-2 text-xs text-blue-700 hover:bg-blue-100 disabled:cursor-not-allowed disabled:opacity-50">
                    {approvalActionLoading ? "提交中…" : "提交审批"}
                  </button>
                )}
              </div>
            )}
          </div>

          <div className="space-y-2">
            {approval && approval.status === "IN_PROGRESS" && (() => {
              return (
                <>
                  <textarea value={approvalOpinion} onChange={(event) => setApprovalOpinion(event.target.value)} placeholder="填写审批意见（退回修改必填）" rows={3} className="w-full resize-y rounded border border-slate-200 bg-white px-3 py-2 text-xs text-slate-900 outline-none placeholder:text-slate-400 focus:border-blue-300" />
                  {approvalError && <div className="rounded border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700">{approvalError}</div>}
                  {approvalDisabledReason && <div className="rounded border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-700">{approvalDisabledReason}</div>}
                  {isSystemAdmin && <div className="rounded border border-blue-200 bg-blue-50 px-3 py-2 text-xs text-blue-700">当前以管理员代审身份操作，审批记录将标注“管理员代审”。</div>}
                  <button type="button" onClick={() => handleApprovalAction("return")} disabled={!canOperateApproval || approvalActionLoading} className="w-full rounded border border-red-200 bg-red-50 py-2.5 text-sm font-medium text-red-700 transition-colors hover:bg-red-100 disabled:cursor-not-allowed disabled:opacity-50">退回修改</button>
                  <button type="button" onClick={() => handleApprovalAction("comment")} disabled={!canOperateApproval || approvalActionLoading} className="w-full rounded border border-blue-200 bg-blue-50 py-2.5 text-sm font-medium text-blue-700 transition-colors hover:bg-blue-100 disabled:cursor-not-allowed disabled:opacity-50">添加审查意见</button>
                  <button type="button" onClick={() => handleApprovalAction("approve")} disabled={!canOperateApproval || approvalActionLoading} className="w-full rounded border border-emerald-200 bg-emerald-50 py-2.5 text-sm font-medium text-emerald-700 transition-colors hover:bg-emerald-100 disabled:cursor-not-allowed disabled:opacity-50">审核通过</button>
                  {isSystemAdmin && currentApprovalNode && <button type="button" onClick={openReassign} disabled={reassignLoading} className="inline-flex w-full items-center justify-center gap-1.5 rounded border border-slate-200 bg-white py-2.5 text-sm font-medium text-slate-700 hover:border-blue-300 hover:text-blue-700 disabled:opacity-50"><UserRoundCog size={14} />改派当前节点审批人</button>}
                </>
              );
            })()}
            {approvalHistory.length > 0 && (
              <div className="mt-3 border-t border-slate-200 pt-3">
                <h4 className="mb-2 text-xs font-medium text-slate-700">审批历史</h4>
                <div className="space-y-2">
                  {approvalHistory.map((operation) => (
                    <div key={operation.id} className="rounded border border-slate-200 bg-slate-50 px-3 py-2 text-xs">
                      <div className="flex justify-between gap-2 text-slate-700">
                        <span>{operation.operatorName} · {procurementApprovalOperationLabel(operation.operationType)}{operation.adminActing ? " · 管理员代审" : ""}</span>
                        <span className="shrink-0 font-mono text-muted-foreground">{formatProcurementDateTime(operation.operatedTime)}</span>
                      </div>
                      {operation.opinion && <p className="mt-1 whitespace-pre-wrap text-muted-foreground">{operation.opinion}</p>}
                      {operation.operationType === "REASSIGN" && <p className="mt-1 text-slate-600">{operation.originalAssigneeName ?? operation.originalAssigneeUserId ?? "待指派"} → {operation.newAssigneeName ?? operation.newAssigneeUserId ?? "待指派"}</p>}
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        </div>
      </div>
      <FormDialog
        open={resubmitOpen}
        title="重新发起审批"
        description="请先修改被退回的采购需求，并填写本次修改说明。系统会校验关键信息是否发生变化。"
        submitLabel="提交审批"
        loading={approvalActionLoading}
        submitDisabled={!modificationNote.trim()}
        onOpenChange={(open) => { setResubmitOpen(open); if (!open) setResubmitError(null); }}
        onSubmit={() => void handleStartApproval(modificationNote.trim())}
      >
        <label className="block space-y-1.5 text-sm text-slate-700">
          <span>修改说明 <span className="text-red-600">*</span></span>
          <textarea value={modificationNote} onChange={(event) => setModificationNote(event.target.value)} rows={4} placeholder="说明针对退回意见修改了哪些内容" className="w-full resize-y rounded border border-slate-200 bg-white px-3 py-2 text-sm outline-none focus:border-blue-400" />
        </label>
        {resubmitError && <div role="alert" className="rounded border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700">{resubmitError}</div>}
      </FormDialog>
      <FormDialog
        open={reassignOpen}
        title="改派审批人"
        description={`当前节点：${currentApprovalNode?.nodeName ?? "-"}（${currentApprovalNode ? procurementApprovalRoleLabel(currentApprovalNode.requiredRoleCode) : "-"}）`}
        submitLabel="确认改派"
        loading={reassignLoading}
        submitDisabled={!reassignTargetUserId.trim()}
        onOpenChange={setReassignOpen}
        onSubmit={() => void handleReassign()}
      >
        <label className="block space-y-1.5 text-sm text-slate-700">
          <span>目标审批人用户 ID</span>
          <input value={reassignTargetUserId} onChange={(event) => setReassignTargetUserId(event.target.value)} placeholder="请输入已分配该节点角色的启用用户 ID" className="w-full rounded border border-slate-200 bg-white px-3 py-2 text-sm outline-none focus:border-blue-400" />
        </label>
        <label className="block space-y-1.5 text-sm text-slate-700">
          <span>改派原因（可选）</span>
          <textarea value={reassignOpinion} onChange={(event) => setReassignOpinion(event.target.value)} rows={3} placeholder="填写改派原因" className="w-full resize-y rounded border border-slate-200 bg-white px-3 py-2 text-sm outline-none focus:border-blue-400" />
        </label>
        {reassignError && <div role="alert" className="rounded border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700">{reassignError}</div>}
      </FormDialog>
      <ConfirmDialog
        open={draftSubmitConfirmOpen}
        title="确认提交审批？"
        description="提交后将进入审批流程，无法编辑。请确认采购需求信息已填写完整。"
        loading={approvalActionLoading}
        onOpenChange={(open) => {
          if (!approvalActionLoading) setDraftSubmitConfirmOpen(open);
        }}
        onConfirm={() => { void confirmDraftSubmission(); }}
      />
      {headerPreviews && (
        <SpreadsheetHeaderMappingDialog
          key={`${headerPreviews.quote.rowCount}-${headerPreviews.blacklist.rowCount}-${headerPreviews.quote.aiModel ?? "manual"}`}
          open
          sections={[
            {
              key: "quote",
              title: "询比价",
              preview: headerPreviews.quote,
              fields: {
                SUPPLIER_NAME: "供应商名称",
                QUOTE_AMOUNT: "报价金额",
                PRODUCT_DESCRIPTION: "产品描述",
              },
              requiredFields: ["SUPPLIER_NAME", "QUOTE_AMOUNT"],
            },
            {
              key: "blacklist",
              title: "黑名单比对",
              preview: headerPreviews.blacklist,
              fields: {
                SUPPLIER_NAME: "供应商名称",
                SUPPLIER_CODE: "统一社会信用代码",
                PROCUREMENT_ID: "采购编号",
              },
              requiredFields: ["SUPPLIER_NAME"],
            },
          ]}
          onCancel={() => setHeaderPreviews(null)}
          onConfirm={(selections) => { void handleHeaderMappingConfirm(selections); }}
        />
      )}
    </div>
  );
}

// ── Bidding Page ───────────────────────────────────────────────────────────
const biddingProjects = [
  { id: "BD-2024-0056", name: "智能卡口设备采购", type: "公开招标", budget: "¥312万", bidders: 5, status: "评标中", agent: "red" as RiskLevel, date: "2026-07-13", openDate: "2026-07-15 09:00" },
  { id: "BD-2024-0054", name: "综合通信系统升级", type: "竞争性谈判", budget: "¥180万", bidders: 3, status: "开标完成", agent: "yellow" as RiskLevel, date: "2026-07-11", openDate: "2026-07-12 14:30" },
  { id: "BD-2024-0052", name: "移动采集终端批量采购", type: "公开招标", budget: "¥45.2万", bidders: 4, status: "资格审查", agent: "red" as RiskLevel, date: "2026-07-10", openDate: "2026-07-16 09:00" },
  { id: "BD-2024-0049", name: "应急训练设施建设", type: "公开招标", budget: "¥180万", bidders: 6, status: "投标截止", agent: "green" as RiskLevel, date: "2026-07-08", openDate: "2026-07-14 10:00" },
  { id: "BD-2024-0047", name: "办公楼安防系统改造", type: "邀请招标", budget: "¥88万", bidders: 3, status: "评审完成", agent: "green" as RiskLevel, date: "2026-07-05", openDate: "2026-07-08 09:00" },
  { id: "BD-2024-0045", name: "网络安全设备更新", type: "公开招标", budget: "¥127万", bidders: 7, status: "评标中", agent: "yellow" as RiskLevel, date: "2026-07-03", openDate: "2026-07-10 09:00" },
  { id: "BD-2024-0042", name: "应急通信车辆采购", type: "公开招标", budget: "¥256万", bidders: 4, status: "资格审查", agent: "green" as RiskLevel, date: "2026-07-01", openDate: "2026-07-18 14:00" },
  { id: "BD-2024-0040", name: "监控摄像头增补项目", type: "竞争性磋商", budget: "¥92万", bidders: 5, status: "评审完成", agent: "green" as RiskLevel, date: "2026-06-28", openDate: "2026-07-05 09:30" },
];

interface BiddingBidder { rank: number; name: string; tech: number; business: number; price: number; service: number; credit: number; total: number; bid: number; status: RiskLevel; }
interface BiddingExpertScore { expert: string; tech: number; business: number; price: number; total: number; deviation: number; }
interface BiddingScoringCriteria { category: string; weight: number; color: string; source: string; method: string; aiNote?: string; subcriteria: { name: string; maxScore: number; desc: string }[]; }
interface BiddingAnomaly { id: string; type: string; level: RiskLevel; title: string; detail: string; evidence: string[]; recommendation: string; }
interface BiddingHistoryProject { year: string; project: string; amount: number; winner: string; techScore: number; priceScore: number; totalScore: number; }
interface BiddingDuplicateCheck { a: string; b: string; rate: number; level: RiskLevel; note: string; }
interface BiddingDetail { bidders: BiddingBidder[]; expertScores: BiddingExpertScore[]; scoringCriteria: BiddingScoringCriteria[]; anomalies: BiddingAnomaly[]; historyProjects: BiddingHistoryProject[]; duplicateCheck: BiddingDuplicateCheck[]; }

const biddingProjectDetails: Record<string, BiddingDetail> = {
  "BD-2024-0056": {
    bidders: [
      { rank: 1, name: "海康威视数字技术股份有限公司", tech: 92, business: 88, price: 37.8, service: 13.2, credit: 8.8, total: 91.8, bid: 298.5, status: "green" },
      { rank: 2, name: "大华技术股份有限公司",         tech: 89, business: 85, price: 36.5, service: 12.8, credit: 8.5, total: 88.2, bid: 305.0, status: "green" },
      { rank: 3, name: "宇视科技有限公司",             tech: 85, business: 82, price: 38.2, service: 12.2, credit: 8.0, total: 86.5, bid: 299.8, status: "yellow" },
      { rank: 4, name: "东方网力科技股份有限公司",     tech: 78, business: 75, price: 40.0, service: 11.0, credit: 7.2, total: 82.1, bid: 287.0, status: "yellow" },
      { rank: 5, name: "天地伟业技术有限公司",         tech: 70, business: 68, price: 35.5, service:  9.8, credit: 6.5, total: 74.6, bid: 295.0, status: "red"   },
    ],
    expertScores: [
      { expert: "张建国（技术专家）", tech: 91, business: 87, price: 95, total: 91.5, deviation:  0.3 },
      { expert: "李明华（采购专家）", tech: 90, business: 89, price: 94, total: 91.2, deviation:  0.6 },
      { expert: "王秀芳（行业专家）", tech: 93, business: 88, price: 96, total: 92.4, deviation:  1.2 },
      { expert: "陈志远（法律专家）", tech: 78, business: 85, price: 82, total: 81.8, deviation: -8.6 },
      { expert: "刘晓峰（财务专家）", tech: 94, business: 90, price: 95, total: 92.2, deviation:  1.0 },
    ],
    scoringCriteria: [
      { category: "价格", weight: 40, color: "bg-blue-500", source: "第五章§5.3.1", method: "最低价满分，每高 1% 扣 0.5 分", aiNote: "发现 2 处评分规则表述矛盾，已标注",
        subcriteria: [{ name: "投标总价", maxScore: 35, desc: "评标基准价±15% 内有效" }, { name: "分项报价合理性", maxScore: 5, desc: "单价偏差超 30% 视为异常" }] },
      { category: "技术", weight: 35, color: "bg-purple-500", source: "第五章§5.3.2", method: "专家评分取均值，去最高最低后计算", aiNote: "投标人3与投标人5技术方案相似度 67%，已标记",
        subcriteria: [{ name: "设备技术参数符合度", maxScore: 15, desc: "与招标技术规格书逐项比对" }, { name: "系统集成方案", maxScore: 10, desc: "与现有综合业务系统兼容性" }, { name: "产品安全认证", maxScore: 6, desc: "需具备国家认可机构相关认证" }, { name: "售后服务网络", maxScore: 4, desc: "本地服务团队及响应时间" }] },
      { category: "服务", weight: 15, color: "bg-emerald-500", source: "第五章§5.3.3", method: "按承诺等级打分，现场答辩验证",
        subcriteria: [{ name: "维保响应时间", maxScore: 8, desc: "故障响应承诺 ≤2h 得满分" }, { name: "培训计划", maxScore: 4, desc: "操作培训课时及覆盖人数" }, { name: "备品备件保障", maxScore: 3, desc: "常用备件库存及供货周期" }] },
      { category: "信用", weight: 10, color: "bg-amber-500", source: "第五章§5.3.4", method: "查询政府采购信用记录，扣分制",
        subcriteria: [{ name: "企业信用等级", maxScore: 6, desc: "纳税信用 A 级加分，D 级取消资格" }, { name: "合同履约记录", maxScore: 4, desc: "近3年履约情况，违约记录扣分" }] },
    ],
    anomalies: [
      { id: "AN-01", type: "串标风险", level: "red", title: "宇视科技与天地伟业 IP 地址相同",
        detail: "系统检测投标人3（宇视科技）与投标人5（天地伟业）的电子标书上传 IP 均为 192.0.2.45（文档保留地址），两次上传间隔仅 28 分钟。",
        evidence: ["上传日志 IP: 192.0.2.45 — 宇视科技 14:23", "上传日志 IP: 192.0.2.45 — 天地伟业 14:51", "时间差仅 28 分钟"],
        recommendation: "立即暂停评标，通知纪检监察部门介入调查，要求两家企业分别说明情况。" },
      { id: "AN-02", type: "低价风险", level: "red", title: "东方网力报价异常低于成本线",
        detail: "东方网力报价 ¥287万，低于控制价 ¥312万 约 8%，AI 成本模型估算合理底线为 ¥291万，存在低价倾销风险。",
        evidence: ["报价 ¥287万 < AI 成本底线 ¥291万", "市场同类均价约 ¥320万", "该企业近3年利润率仅 2.1%"],
        recommendation: "要求提交详细成本分析报告；无法说明合理性则按低于成本价认定，取消资格。" },
      { id: "AN-03", type: "文件雷同", level: "yellow", title: "技术方案雷同度超阈值 60%",
        detail: "AI 文本比对显示投标人1与投标人2技术方案书第三章相似度达 67%，超出预警阈值 60%。",
        evidence: ['第三章"系统集成"段落相似度 67%', "图表编号与样式高度一致", "部分参数描述语句完全相同"],
        recommendation: "要求两家分别当场答辩说明设计思路，判断是否存在合作串标。" },
      { id: "AN-04", type: "控制价偏高", level: "blue", title: "控制价高于历史均价 22.4%",
        detail: "本次控制价 ¥312万，历史同类项目近3年平均成交价 ¥255万，偏高 22.4%，建议复核。",
        evidence: ["控制价 ¥312万", "历史均价 ¥255万（共4项）", "偏差 +22.4%"],
        recommendation: "建议财务部门核实控制价编制依据，必要时启动独立估价。" },
    ],
    historyProjects: [
      { year: "2022", project: "城区道路智能卡口建设", amount: 280, winner: "海康威视", techScore: 91, priceScore: 88, totalScore: 90.1 },
      { year: "2023", project: "园区车辆识别系统升级", amount: 145, winner: "宇视科技", techScore: 85, priceScore: 90, totalScore: 87.2 },
      { year: "2024", project: "ZZ区公共服务中心卡口改造", amount: 280, winner: "海康威视", techScore: 91, priceScore: 88.5, totalScore: 90.2 },
      { year: "2026 本次", project: "XX市公共服务中心智能卡口（进行中）", amount: 298, winner: "—", techScore: 91.8, priceScore: 37.8, totalScore: 91.8 },
    ],
    duplicateCheck: [
      { a: "宇视科技有限公司", b: "天地伟业技术有限公司", rate: 78, level: "red",   note: "IP 地址相同，疑似串通投标" },
      { a: "海康威视数字技术股份有限公司", b: "大华技术股份有限公司", rate: 67, level: "yellow", note: "技术方案文本相似度超阈值，需专家核查" },
      { a: "大华技术股份有限公司", b: "宇视科技有限公司", rate: 32, level: "green",  note: "相似度在正常范围内" },
    ],
  },

  "BD-2024-0054": {
    bidders: [
      { rank: 1, name: "华为技术有限公司",           tech: 95, business: 92, price: 26.5, service: 18.2, credit: 9.2, total: 91.4, bid: 172, status: "green" },
      { rank: 2, name: "中兴通讯股份有限公司",       tech: 91, business: 88, price: 27.8, service: 17.5, credit: 8.8, total: 89.8, bid: 168, status: "green" },
      { rank: 3, name: "烽火通信科技股份有限公司",   tech: 83, business: 80, price: 30.2, service: 15.0, credit: 7.8, total: 84.6, bid: 161, status: "yellow" },
    ],
    expertScores: [
      { expert: "孙磊（通信技术专家）", tech: 94, business: 91, price: 82, total: 90.8, deviation:  0.8 },
      { expert: "赵丽（系统集成专家）", tech: 96, business: 93, price: 83, total: 92.2, deviation:  1.4 },
      { expert: "周建平（采购专家）",   tech: 95, business: 92, price: 81, total: 91.2, deviation:  0.2 },
    ],
    scoringCriteria: [
      { category: "技术", weight: 40, color: "bg-purple-500", source: "第四章§4.2.1", method: "专家独立评分，取算术平均值", aiNote: "AI 评估华为方案技术先进性最高，与其他方案差距明显",
        subcriteria: [{ name: "系统架构设计", maxScore: 15, desc: "架构合理性、可扩展性、兼容性" }, { name: "技术指标符合度", maxScore: 12, desc: "通信带宽、延迟、覆盖率等关键指标" }, { name: "网络安全防护", maxScore: 8, desc: "加密等级、入侵检测、数据保护" }, { name: "技术成熟度", maxScore: 5, desc: "产品市场应用案例评估" }] },
      { category: "价格", weight: 30, color: "bg-blue-500", source: "第四章§4.2.2", method: "竞争性谈判后最终报价计算",
        subcriteria: [{ name: "系统采购价格", maxScore: 22, desc: "硬件及软件许可费用" }, { name: "实施及集成费用", maxScore: 5, desc: "安装调试及系统集成工时费" }, { name: "首年运维费用", maxScore: 3, desc: "技术支持及维保服务年费" }] },
      { category: "服务", weight: 20, color: "bg-emerald-500", source: "第四章§4.2.3", method: "量化指标评分与专家评议结合",
        subcriteria: [{ name: "实施团队资质", maxScore: 8, desc: "项目经理资质及团队成员认证" }, { name: "运维服务体系", maxScore: 7, desc: "7×24 小时支持能力及本地化服务" }, { name: "升级迭代保障", maxScore: 5, desc: "版本升级计划及长期支持承诺" }] },
      { category: "信用", weight: 10, color: "bg-amber-500", source: "第四章§4.2.4", method: "查询企业信用数据库，客观评分",
        subcriteria: [{ name: "企业综合信用", maxScore: 6, desc: "工商、税务、银行信用综合评定" }, { name: "信息化项目业绩", maxScore: 4, desc: "近5年政务信息系统通信项目业绩" }] },
    ],
    anomalies: [
      { id: "AN-01", type: "价格合理性", level: "yellow", title: "三家报价差异 6.8%，需核实低价可行性",
        detail: "最高报价（华为 ¥172万）与最低报价（烽火 ¥161万）相差 ¥11万，烽火通信本地服务团队仅2人，服务承诺 4h 响应高于其他供应商 2h，承诺可行性存疑。",
        evidence: ["烽火通信报价 ¥161万，低于均值约 5.8%", "服务响应承诺 4h（华为/中兴均为 2h）", "烽火本地服务团队仅 2 人"],
        recommendation: "谈判小组重点核实烽火通信服务能力，若确实无法满足 4h 内响应，在服务评分中予以体现。" },
    ],
    historyProjects: [
      { year: "2021", project: "综合无线通信网络建设",   amount: 210, winner: "华为技术有限公司",     techScore: 94, priceScore: 80, totalScore: 89.6 },
      { year: "2022", project: "基层服务站视频会议系统采购", amount:  85, winner: "中兴通讯股份有限公司", techScore: 88, priceScore: 88, totalScore: 88.0 },
      { year: "2023", project: "指挥中心通信设备更新",   amount: 165, winner: "华为技术有限公司",     techScore: 95, priceScore: 78, totalScore: 89.3 },
    ],
    duplicateCheck: [
      { a: "华为技术有限公司",         b: "中兴通讯股份有限公司",     rate: 28, level: "green", note: "两家方案差异显著，竞争充分" },
      { a: "中兴通讯股份有限公司",     b: "烽火通信科技股份有限公司", rate: 35, level: "green", note: "相似度在合理范围内" },
    ],
  },

  "BD-2024-0052": {
    bidders: [
      { rank: 1, name: "北京信路威科技股份有限公司",     tech: 88, business: 84, price: 36.4, service: 12.9, credit: 8.2, total: 87.7, bid: 43.5, status: "green"  },
      { rank: 2, name: "苏州科达科技股份有限公司",       tech: 85, business: 81, price: 35.2, service: 12.5, credit: 8.0, total: 85.1, bid: 44.0, status: "green"  },
      { rank: 3, name: "深圳市金溢科技股份有限公司",     tech: 80, business: 76, price: 37.8, service: 11.5, credit: 7.4, total: 82.4, bid: 41.8, status: "yellow" },
      { rank: 4, name: "北京瑞安天下网络科技有限公司",   tech: 72, business: 68, price: 34.0, service: 10.5, credit: 6.5, total: 74.9, bid: 42.5, status: "red"    },
    ],
    expertScores: [
      { expert: "马建军（专用设备专家）", tech: 87, business: 83, price: 91, total: 87.4, deviation:  0.4 },
      { expert: "林小燕（采购管理专家）", tech: 89, business: 85, price: 90, total: 88.0, deviation:  0.6 },
      { expert: "郑国强（技术鉴定专家）", tech: 88, business: 84, price: 92, total: 87.8, deviation: -0.1 },
    ],
    scoringCriteria: [
      { category: "价格", weight: 40, color: "bg-blue-500", source: "第三章§3.4.1", method: "最低评标价法，低价优先", aiNote: "AI 检测投标人4信用记录存在异常，已标记",
        subcriteria: [{ name: "单台设备单价", maxScore: 30, desc: "移动采集终端含配件的单台完整价格" }, { name: "备品备件报价", maxScore: 6, desc: "耗材及易损件3年价格锁定" }, { name: "运输安装费", maxScore: 4, desc: "交货到用户指定地点费用" }] },
      { category: "技术", weight: 35, color: "bg-purple-500", source: "第三章§3.4.2", method: "技术参数逐项对比，需提供样品测试",
        subcriteria: [{ name: "视频分辨率及码率", maxScore: 12, desc: "不低于 1080P@30fps，支持 H.265" }, { name: "防护等级", maxScore: 8, desc: "IP68，跌落防护标准" }, { name: "电池续航", maxScore: 8, desc: "连续录制不低于8小时" }, { name: "数据加密防篡改", maxScore: 7, desc: "符合国家认可机构移动采集终端技术标准" }] },
      { category: "服务", weight: 15, color: "bg-emerald-500", source: "第三章§3.4.3", method: "按承诺条款量化打分",
        subcriteria: [{ name: "质保期限", maxScore: 7, desc: "整机质保3年以上得满分" }, { name: "维修响应", maxScore: 5, desc: "故障设备48小时内更换" }, { name: "固件升级服务", maxScore: 3, desc: "免费升级至停产后3年" }] },
      { category: "信用", weight: 10, color: "bg-amber-500", source: "第三章§3.4.4", method: "信用评级查询，客观扣分",
        subcriteria: [{ name: "企业合规记录", maxScore: 6, desc: "无行政处罚、无质量投诉记录" }, { name: "专用设备供货业绩", maxScore: 4, desc: "近3年政务信息系统同类产品供货证明" }] },
    ],
    anomalies: [
      { id: "AN-01", type: "资质不符", level: "red", title: "投标人4营业执照经营范围不符合要求",
        detail: '北京瑞安天下网络科技有限公司主营业务为"网络技术咨询"，不包含专用设备生产或授权销售，不满足招标文件资质要求。',
        evidence: ["营业执照经营范围：软件开发、网络技术咨询", "招标文件要求：需具备专用设备生产许可证或授权销售证明", "工商信息查询结果附件第2页"],
        recommendation: "资格审查阶段以不满足资质条件为由取消其投标资格，并出具书面通知。" },
      { id: "AN-02", type: "信用风险", level: "yellow", title: "投标人3曾有合同逾期履约记录",
        detail: "深圳金溢科技在政府采购信用平台存在1条2022年合同逾期履约记录（逾期45天），已过处罚期但仍需关注。",
        evidence: ["政府采购信用平台：2022年深圳某项目逾期交货45天", "已于2023年解除不良记录名单"],
        recommendation: "可继续参与评审，但在服务评分环节重点考察其履约保障措施。" },
    ],
    historyProjects: [
      { year: "2021", project: "移动采集终端首批采购",        amount: 32, winner: "北京信路威科技",   techScore: 86, priceScore: 88, totalScore: 87.0 },
      { year: "2022", project: "移动采集设备采购（含终端）", amount: 58, winner: "苏州科达科技",     techScore: 84, priceScore: 91, totalScore: 87.2 },
      { year: "2023", project: "移动业务终端采购",           amount: 41, winner: "北京信路威科技",   techScore: 89, priceScore: 85, totalScore: 87.6 },
    ],
    duplicateCheck: [
      { a: "北京信路威科技股份有限公司", b: "苏州科达科技股份有限公司",   rate: 41, level: "green",  note: "相似度正常，两家均为主流供应商" },
      { a: "深圳市金溢科技股份有限公司", b: "北京瑞安天下网络科技有限公司", rate: 55, level: "yellow", note: "部分条款描述相似，建议核查是否为同一代理" },
    ],
  },

  "BD-2024-0049": {
    bidders: [
      { rank: 1, name: "中国建筑第八工程局有限公司", tech: 27.3, business: 22.4, price: 30.5, service: 22.1, credit: 9.0, total: 89.9, bid: 172.5, status: "green" },
      { rank: 2, name: "北京城建集团有限责任公司",   tech: 26.4, business: 21.3, price: 31.5, service: 21.0, credit: 8.7, total: 88.4, bid: 170.0, status: "green" },
      { rank: 3, name: "上海建工集团股份有限公司",   tech: 25.8, business: 20.9, price: 30.8, service: 20.4, credit: 8.4, total: 86.2, bid: 173.5, status: "green" },
      { rank: 4, name: "广州市建筑集团有限公司",     tech: 24.6, business: 19.9, price: 32.2, service: 19.2, credit: 7.8, total: 83.7, bid: 167.0, status: "green" },
      { rank: 5, name: "四川华西集团有限公司",       tech: 23.4, business: 18.6, price: 30.1, service: 18.3, credit: 7.2, total: 79.6, bid: 174.0, status: "green" },
      { rank: 6, name: "湖南建工集团有限公司",       tech: 22.2, business: 17.5, price: 31.9, service: 17.3, credit: 6.8, total: 77.2, bid: 165.5, status: "green" },
    ],
    expertScores: [
      { expert: "吴庆华（建筑工程专家）", tech: 90, business: 88, price: 87, total: 89.5, deviation:  0.4 },
      { expert: "徐文杰（特种设施专家）", tech: 92, business: 90, price: 88, total: 90.6, deviation:  0.7 },
      { expert: "沈春梅（造价审计专家）", tech: 90, business: 89, price: 86, total: 89.4, deviation:  0.5 },
      { expert: "曹立军（项目管理专家）", tech: 91, business: 88, price: 88, total: 89.8, deviation:  0.3 },
      { expert: "蒋国平（公共设施专家）", tech: 92, business: 90, price: 87, total: 90.2, deviation:  0.8 },
    ],
    scoringCriteria: [
      { category: "技术", weight: 30, color: "bg-purple-500", source: "第六章§6.1.1", method: "专家评审施工方案合理性及专业性",
        subcriteria: [{ name: "应急训练设施专业性", maxScore: 12, desc: "训练场地布局、障碍设施、分区训练设计" }, { name: "施工技术方案", maxScore: 10, desc: "施工工艺、材料选型、工期安排" }, { name: "配套保障设施", maxScore: 5, desc: "场地通风、防疫隔离、活动空间标准" }, { name: "绿色施工措施", maxScore: 3, desc: "噪音控制、废弃物处置方案" }] },
      { category: "价格", weight: 35, color: "bg-blue-500", source: "第六章§6.1.2", method: "合理低价法，不接受明显低于成本价的投标", aiNote: "投标人6报价最低（¥165.5万），属合理竞争范围",
        subcriteria: [{ name: "土建工程造价", maxScore: 20, desc: "各分部分项工程清单单价合理性" }, { name: "专项设施造价", maxScore: 10, desc: "训练器械、配套设备费用" }, { name: "措施费及管理费", maxScore: 5, desc: "临时设施、安全文明施工费用" }] },
      { category: "服务", weight: 25, color: "bg-emerald-500", source: "第六章§6.1.3", method: "量化评分，重点评估工期保障和质量控制",
        subcriteria: [{ name: "项目管理团队", maxScore: 10, desc: "项目经理资质及同类工程经验" }, { name: "工期保障措施", maxScore: 8, desc: "进度计划合理性及应急预案" }, { name: "质量保修承诺", maxScore: 7, desc: "工程质量保修期及保修条款" }] },
      { category: "信用", weight: 10, color: "bg-amber-500", source: "第六章§6.1.4", method: "资质等级与业绩综合评定",
        subcriteria: [{ name: "施工资质等级", maxScore: 5, desc: "建筑工程施工总承包资质等级" }, { name: "类似工程业绩", maxScore: 5, desc: "近5年公共服务、工业或特种设施建设业绩" }] },
    ],
    anomalies: [
      { id: "AN-01", type: "工期差异", level: "blue", title: "广州建筑集团工期承诺显著低于其他投标人",
        detail: "6家投标人工期承诺在 120–180 天之间，广州建筑集团承诺 120 天，显著低于均值 155 天，需评审委员会核实可行性。",
        evidence: ["广州建筑集团承诺 120 天", "其余5家均值 158 天", "工程量清单显示基础施工约需 80 天"],
        recommendation: "专家要求广州建筑集团现场说明人员和设备投入计划，审查 120 天工期压缩方案的合理性。" },
    ],
    historyProjects: [
      { year: "2020", project: "训练基地配套设施改造",  amount:  95, winner: "北京城建集团",   techScore: 86, priceScore: 90, totalScore: 88.0 },
      { year: "2021", project: "综合业务培训楼建设",    amount: 235, winner: "中国建筑第八工程局", techScore: 92, priceScore: 84, totalScore: 89.2 },
      { year: "2022", project: "射击训练场改扩建",      amount: 120, winner: "上海建工集团",   techScore: 85, priceScore: 87, totalScore: 85.9 },
      { year: "2023", project: "应急培训基地基础设施建设", amount: 160, winner: "中国建筑第八工程局", techScore: 90, priceScore: 86, totalScore: 88.6 },
    ],
    duplicateCheck: [
      { a: "北京城建集团有限责任公司",   b: "上海建工集团股份有限公司", rate: 29, level: "green", note: "两家均为大型国企，方案独立性高" },
      { a: "四川华西集团有限公司",       b: "湖南建工集团有限公司",     rate: 38, level: "green", note: "相似度在正常范围内" },
      { a: "广州市建筑集团有限公司",     b: "湖南建工集团有限公司",     rate: 33, level: "green", note: "报价相近但方案独立" },
    ],
  },

  "BD-2024-0047": {
    bidders: [
      { rank: 1, name: "中国电子科技集团公司第五十四研究所", tech: 27.9, business: 22.8, price: 29.8, service: 22.3, credit: 9.2, total: 91.1, bid: 83.5, status: "green" },
      { rank: 2, name: "深圳英飞拓科技股份有限公司",         tech: 26.7, business: 21.5, price: 30.8, service: 21.1, credit: 8.5, total: 88.2, bid: 85.0, status: "green" },
      { rank: 3, name: "北京东方联星科技有限公司",           tech: 24.3, business: 19.5, price: 32.6, service: 18.9, credit: 7.5, total: 82.0, bid: 80.5, status: "green" },
    ],
    expertScores: [
      { expert: "冯建国（安防系统专家）", tech: 92, business: 90, price: 85, total: 90.5, deviation:  0.6 },
      { expert: "郭丽华（建筑智能化专家）", tech: 94, business: 92, price: 86, total: 91.6, deviation:  0.5 },
      { expert: "何志刚（信息化专家）", tech: 93, business: 91, price: 84, total: 90.8, deviation:  0.3 },
    ],
    scoringCriteria: [
      { category: "技术", weight: 30, color: "bg-purple-500", source: "第三章§3.2.1", method: "专家评审，重点考察与原有系统兼容性", aiNote: "AI 评估中电科54所方案与现有楼宇系统兼容性最佳",
        subcriteria: [{ name: "系统整体设计", maxScore: 10, desc: "新旧系统无缝对接方案" }, { name: "设备技术规格", maxScore: 8, desc: "摄像头分辨率、门禁系统参数" }, { name: "智能化功能", maxScore: 7, desc: "人脸识别、入侵检测、联动报警" }, { name: "施工方案", maxScore: 5, desc: "不影响正常办公的施工组织方案" }] },
      { category: "价格", weight: 35, color: "bg-blue-500", source: "第三章§3.2.2", method: "邀请招标价格评分，以竞争价格为基准",
        subcriteria: [{ name: "设备采购费", maxScore: 22, desc: "各类安防设备含税报价" }, { name: "施工及集成费", maxScore: 8, desc: "布线、安装、调试及系统集成" }, { name: "3年维保费", maxScore: 5, desc: "系统运行维护年费及备件费" }] },
      { category: "服务", weight: 25, color: "bg-emerald-500", source: "第三章§3.2.3", method: "重点评估驻场服务和应急响应能力",
        subcriteria: [{ name: "项目实施团队", maxScore: 10, desc: "系统集成工程师资质及项目经验" }, { name: "质保与运维服务", maxScore: 9, desc: "驻场运维、故障响应及预防性维护" }, { name: "用户培训", maxScore: 6, desc: "操作人员培训及技术文档完整性" }] },
      { category: "信用", weight: 10, color: "bg-amber-500", source: "第三章§3.2.4", method: "业绩加分，无不良记录为基准分",
        subcriteria: [{ name: "同类工程业绩", maxScore: 6, desc: "近3年政府机关安防改造项目业绩" }, { name: "企业信用等级", maxScore: 4, desc: "税务、工商信用综合评定" }] },
    ],
    anomalies: [
      { id: "AN-01", type: "竞争充分性", level: "blue", title: "邀请招标供应商数量偏少",
        detail: "本项目采用邀请招标，仅邀请3家，评审委员会建议未来同类项目扩大竞争范围。",
        evidence: ["邀请3家，均为已合作供应商", "市场具备资质供应商约15家"],
        recommendation: "本次按规定执行；下次同类项目建议公开招标或至少邀请5家以上供应商。" },
    ],
    historyProjects: [
      { year: "2019", project: "办公楼门禁系统安装",        amount:  22, winner: "北京东方联星科技",     techScore: 80, priceScore: 92, totalScore: 84.4 },
      { year: "2021", project: "基层服务站安防监控系统建设",    amount:  65, winner: "深圳英飞拓科技",       techScore: 88, priceScore: 86, totalScore: 87.2 },
      { year: "2022", project: "营区周界防护系统改造",      amount:  78, winner: "中国电子科技集团54所", techScore: 93, priceScore: 82, totalScore: 89.1 },
    ],
    duplicateCheck: [
      { a: "中国电子科技集团公司第五十四研究所", b: "深圳英飞拓科技股份有限公司", rate: 24, level: "green", note: "两家方案差异明显，竞争充分" },
      { a: "深圳英飞拓科技股份有限公司",         b: "北京东方联星科技有限公司",   rate: 31, level: "green", note: "相似度在正常范围内" },
    ],
  },

  "BD-2024-0045": {
    bidders: [
      { rank: 1, name: "奇安信科技集团股份有限公司",         tech: 37.6, business: 18.2, price: 25.2, service: 18.6, credit: 9.1, total: 91.5, bid: 121.5, status: "green"  },
      { rank: 2, name: "深信服科技股份有限公司",             tech: 36.8, business: 17.8, price: 25.8, service: 18.0, credit: 8.8, total: 90.1, bid: 119.0, status: "green"  },
      { rank: 3, name: "启明星辰信息技术集团股份有限公司",   tech: 36.0, business: 17.4, price: 26.4, service: 17.6, credit: 8.6, total: 88.8, bid: 117.5, status: "green"  },
      { rank: 4, name: "天融信科技集团股份有限公司",         tech: 34.4, business: 16.6, price: 27.3, service: 16.8, credit: 8.2, total: 86.4, bid: 115.0, status: "yellow" },
      { rank: 5, name: "安恒信息技术股份有限公司",           tech: 33.2, business: 16.0, price: 26.1, service: 16.4, credit: 7.9, total: 83.6, bid: 120.0, status: "yellow" },
      { rank: 6, name: "绿盟科技集团股份有限公司",           tech: 35.2, business: 17.0, price: 26.7, service: 17.4, credit: 8.4, total: 87.5, bid: 116.0, status: "green"  },
      { rank: 7, name: "北京神州绿盟科技有限公司",           tech: 30.0, business: 14.4, price: 27.9, service: 14.6, credit: 6.8, total: 77.8, bid: 113.0, status: "yellow" },
    ],
    expertScores: [
      { expert: "任晓峰（网络安全专家）", tech: 93, business: 90, price: 84, total: 91.0, deviation:  0.5 },
      { expert: "杨丽萍（信息安全专家）", tech: 95, business: 92, price: 85, total: 92.2, deviation:  0.7 },
      { expert: "韩建明（信息化专家）", tech: 80, business: 76, price: 79, total: 79.2, deviation: -9.8 },
      { expert: "周磊（采购法律专家）",   tech: 93, business: 90, price: 84, total: 91.0, deviation:  0.5 },
      { expert: "钱伟（网络工程专家）",   tech: 94, business: 91, price: 85, total: 91.5, deviation:  0.5 },
    ],
    scoringCriteria: [
      { category: "技术", weight: 40, color: "bg-purple-500", source: "第七章§7.2.1", method: "专家评审，重点考察安全防护能力和合规性", aiNote: "AI 发现投标人7（神州绿盟）与投标人6（绿盟科技）可能存在关联关系，已标记",
        subcriteria: [{ name: "安全防护体系", maxScore: 15, desc: "边界防护、入侵检测、态势感知能力" }, { name: "等保合规性", maxScore: 12, desc: "满足政务信息系统等保三级要求" }, { name: "产品安全认证", maxScore: 8, desc: "CC认证、FIPS认证等安全认证" }, { name: "应急响应能力", maxScore: 5, desc: "安全事件响应时间及处置流程" }] },
      { category: "价格", weight: 30, color: "bg-blue-500", source: "第七章§7.2.2", method: "竞争性报价，以有效最低价为基准计算",
        subcriteria: [{ name: "硬件设备费", maxScore: 18, desc: "防火墙、IDS/IPS、WAF 等硬件含税报价" }, { name: "软件许可费", maxScore: 7, desc: "安全软件 License 及订阅服务费" }, { name: "实施及调试费", maxScore: 5, desc: "系统部署调试及安全加固费用" }] },
      { category: "服务", weight: 20, color: "bg-emerald-500", source: "第七章§7.2.3", method: "量化打分，重点评估持续安全运营能力",
        subcriteria: [{ name: "安全运营服务", maxScore: 8, desc: "7×24 小时安全监测及威胁情报支持" }, { name: "漏洞管理服务", maxScore: 7, desc: "漏洞扫描、补丁管理及修复方案" }, { name: "安全培训", maxScore: 5, desc: "管理员培训及安全意识提升课程" }] },
      { category: "信用", weight: 10, color: "bg-amber-500", source: "第七章§7.2.4", method: "政务信息化行业业绩加分",
        subcriteria: [{ name: "政务网络安全业绩", maxScore: 6, desc: "近3年政务信息系统网络安全项目案例" }, { name: "企业资质认证", maxScore: 4, desc: "CISP、CCRC 等网络安全服务资质" }] },
    ],
    anomalies: [
      { id: "AN-01", type: "关联企业", level: "yellow", title: "疑似关联企业同时投标",
        detail: "投标人6（绿盟科技集团）与投标人7（北京神州绿盟科技）名称高度相似，工商信息显示存在共同股东，涉嫌违反关联企业不得同时投标规定。",
        evidence: ["企业名称相似：绿盟科技集团 vs 神州绿盟科技", "工商登记显示存在共同股东", "两家企业办公地址相邻"],
        recommendation: "立即核查两家企业工商关系，若确认为关联企业，取消投标人7的投标资格，保留绿盟科技集团。" },
      { id: "AN-02", type: "专家偏差", level: "yellow", title: "韩建明专家打分偏差超阈值",
        detail: "韩建明（信息化专家）评分均值 79.2 分，低于其余4位专家均值 89.2 分，偏差 -9.8%，超出预警阈值 ±5%。",
        evidence: ["韩建明评分：技术 80、商务 76、价格 79，均值 79.2", "其余4位专家均值：91.4", "偏差 -9.8%，超阈值"],
        recommendation: "要求韩建明书面说明评分依据；如无合理理由，可申请复核该专家评分。" },
    ],
    historyProjects: [
      { year: "2020", project: "政务网络安全评估与加固",  amount:  65, winner: "奇安信科技集团", techScore: 93, priceScore: 82, totalScore: 89.3 },
      { year: "2021", project: "政务内网安全审计系统采购", amount:  98, winner: "启明星辰信息技术集团", techScore: 89, priceScore: 88, totalScore: 88.7 },
      { year: "2022", project: "边界安全防护设备更新",    amount: 110, winner: "深信服科技",       techScore: 91, priceScore: 86, totalScore: 89.5 },
      { year: "2023", project: "态势感知平台建设",        amount: 145, winner: "奇安信科技集团",   techScore: 95, priceScore: 80, totalScore: 90.1 },
    ],
    duplicateCheck: [
      { a: "绿盟科技集团股份有限公司",         b: "北京神州绿盟科技有限公司",   rate: 72, level: "yellow", note: "疑似关联企业，文件内容高度相似，待核查" },
      { a: "奇安信科技集团股份有限公司",       b: "深信服科技股份有限公司",     rate: 31, level: "green",  note: "方案差异明显，竞争独立" },
      { a: "启明星辰信息技术集团股份有限公司", b: "天融信科技集团股份有限公司", rate: 35, level: "green",  note: "相似度在正常范围内" },
    ],
  },

  "BD-2024-0042": {
    bidders: [
      { rank: 1, name: "中国重汽集团有限公司",     tech: 31.5, business: 13.9, price: 35.2, service: 13.5, credit: 8.9, total: 89.1, bid: 245, status: "green" },
      { rank: 2, name: "一汽解放汽车有限公司",     tech: 30.8, business: 13.6, price: 36.0, service: 13.1, credit: 8.6, total: 87.9, bid: 242, status: "green" },
      { rank: 3, name: "东风商用车有限公司",       tech: 30.1, business: 13.3, price: 36.4, service: 12.8, credit: 8.3, total: 86.6, bid: 240, status: "green" },
      { rank: 4, name: "北汽福田汽车股份有限公司", tech: 28.7, business: 12.6, price: 37.2, service: 12.0, credit: 7.7, total: 83.5, bid: 237, status: "green" },
    ],
    expertScores: [
      { expert: "胡建伟（特种车辆专家）", tech: 89, business: 86, price: 88, total: 88.3, deviation:  0.8 },
      { expert: "魏晓燕（设备专家）", tech: 91, business: 88, price: 89, total: 89.8, deviation:  0.7 },
      { expert: "石磊（采购管理专家）",   tech: 90, business: 87, price: 87, total: 89.0, deviation: -0.1 },
    ],
    scoringCriteria: [
      { category: "价格", weight: 40, color: "bg-blue-500", source: "第五章§5.1.1", method: "综合报价评分，含整车及通信改装配置", aiNote: "北汽福田报价最低，但通信改装资质材料尚未提交",
        subcriteria: [{ name: "基础车辆报价", maxScore: 25, desc: "底盘车辆含税出厂价" }, { name: "通信改装费用", maxScore: 10, desc: "应急通信设备集成及改装费" }, { name: "配套工具及备件", maxScore: 5, desc: "随车工具、备用零件包价格" }] },
      { category: "技术", weight: 35, color: "bg-purple-500", source: "第五章§5.1.2", method: "技术参数逐项对比，重点核查通信改装能力",
        subcriteria: [{ name: "底盘技术性能", maxScore: 12, desc: "发动机功率、越野能力、载重参数" }, { name: "应急通信系统", maxScore: 13, desc: "通信覆盖范围、频段支持、独立供电" }, { name: "特种车辆认证", maxScore: 6, desc: "国家认可机构特种车辆认证及改装资质" }, { name: "节能环保指标", maxScore: 4, desc: "国六排放标准符合情况" }] },
      { category: "服务", weight: 15, color: "bg-emerald-500", source: "第五章§5.1.3", method: "量化服务指标评分",
        subcriteria: [{ name: "售后网络覆盖", maxScore: 7, desc: "本地授权服务网点及响应时间" }, { name: "整车质保", maxScore: 5, desc: "整车3年或20万公里质保" }, { name: "配件保障", maxScore: 3, desc: "原厂配件供货周期及价格保证" }] },
      { category: "信用", weight: 10, color: "bg-amber-500", source: "第五章§5.1.4", method: "整车厂资质与政府采购记录",
        subcriteria: [{ name: "专用保障车辆供货业绩", maxScore: 6, desc: "近5年公共服务车辆采购业绩" }, { name: "企业信用记录", maxScore: 4, desc: "质量投诉、召回记录核查" }] },
    ],
    anomalies: [
      { id: "AN-01", type: "资质待核实", level: "blue", title: "北汽福田通信改装资质材料缺失",
        detail: "北汽福田报价最低（¥237万），但资格审查阶段尚未提交应急通信车辆改装专项资质证明，需限期补交。",
        evidence: ["招标文件要求提供应急通信改装资质", "北汽福田标书中该资质证明缺失", "已发函要求5个工作日内补充"],
        recommendation: "设定补交截止时间，逾期未提交则取消资格；其余三家资质齐全，流程可正常推进。" },
    ],
    historyProjects: [
      { year: "2020", project: "专用保障车辆采购",   amount: 185, winner: "中国重汽集团",     techScore: 88, priceScore: 87, totalScore: 87.6 },
      { year: "2021", project: "应急保障车辆采购",   amount: 310, winner: "东风商用车有限公司", techScore: 87, priceScore: 90, totalScore: 88.3 },
      { year: "2022", project: "移动指挥车采购",     amount: 198, winner: "一汽解放汽车有限公司", techScore: 86, priceScore: 89, totalScore: 87.2 },
      { year: "2023", project: "工程保障车辆采购",   amount: 142, winner: "北汽福田汽车股份有限公司", techScore: 82, priceScore: 93, totalScore: 86.2 },
    ],
    duplicateCheck: [
      { a: "中国重汽集团有限公司",     b: "一汽解放汽车有限公司",     rate: 27, level: "green", note: "两家整车企业方案各具特色" },
      { a: "东风商用车有限公司",       b: "北汽福田汽车股份有限公司", rate: 33, level: "green", note: "相似度在正常范围内" },
    ],
  },

  "BD-2024-0040": {
    bidders: [
      { rank: 1, name: "海康威视数字技术股份有限公司", tech: 32.6, business: 13.5, price: 34.8, service: 13.8, credit: 9.0, total: 91.2, bid: 88.5, status: "green" },
      { rank: 2, name: "大华技术股份有限公司",         tech: 31.5, business: 13.1, price: 35.6, service: 13.4, credit: 8.7, total: 89.4, bid: 87.0, status: "green" },
      { rank: 3, name: "宇视科技有限公司",             tech: 30.5, business: 12.6, price: 36.4, service: 12.8, credit: 8.3, total: 87.3, bid: 86.0, status: "green" },
      { rank: 4, name: "天地伟业技术有限公司",         tech: 29.4, business: 12.2, price: 36.8, service: 12.3, credit: 8.0, total: 85.5, bid: 85.5, status: "green" },
      { rank: 5, name: "中维世纪科技股份有限公司",     tech: 28.0, business: 11.6, price: 35.6, service: 11.7, credit: 7.5, total: 81.6, bid: 88.0, status: "green" },
    ],
    expertScores: [
      { expert: "张伟（视频监控专家）",   tech: 92, business: 89, price: 87, total: 90.7, deviation:  0.5 },
      { expert: "刘艳（信息技术专家）",   tech: 94, business: 91, price: 88, total: 91.9, deviation:  0.7 },
      { expert: "陈明（采购评审专家）",   tech: 93, business: 90, price: 86, total: 91.0, deviation: -0.2 },
    ],
    scoringCriteria: [
      { category: "价格", weight: 40, color: "bg-blue-500", source: "第二章§2.3.1", method: "竞争性磋商后最终报价计算", aiNote: "AI 综合评估海康威视性价比最优",
        subcriteria: [{ name: "摄像头单价", maxScore: 28, desc: "各型号摄像头含税单价" }, { name: "安装及调试费", maxScore: 7, desc: "现场安装调试人工费" }, { name: "线缆及辅材", maxScore: 5, desc: "综合布线材料费用" }] },
      { category: "技术", weight: 35, color: "bg-purple-500", source: "第二章§2.3.2", method: "技术参数评分，重点考察图像质量和智能分析",
        subcriteria: [{ name: "图像分辨率", maxScore: 12, desc: "不低于4K超高清，夜视效果达标" }, { name: "智能分析功能", maxScore: 12, desc: "人脸识别、行为分析、车牌识别" }, { name: "环境适应性", maxScore: 7, desc: "宽温工作范围，防水防尘 IP67" }, { name: "平台对接能力", maxScore: 4, desc: "与现有视频监控平台兼容性" }] },
      { category: "服务", weight: 15, color: "bg-emerald-500", source: "第二章§2.3.3", method: "量化评分",
        subcriteria: [{ name: "产品质保", maxScore: 7, desc: "整机质保3年，镜头质保5年" }, { name: "技术支持", maxScore: 5, desc: "安装完成后的技术支持响应" }, { name: "维修服务", maxScore: 3, desc: "故障摄像头24小时内更换" }] },
      { category: "信用", weight: 10, color: "bg-amber-500", source: "第二章§2.3.4", method: "行业地位与历史业绩综合评定",
        subcriteria: [{ name: "政务信息系统供货业绩", maxScore: 6, desc: "近3年公共服务视频监控项目供货证明" }, { name: "产品质量记录", maxScore: 4, desc: "无重大质量投诉或产品召回记录" }] },
    ],
    anomalies: [
      { id: "AN-01", type: "流程说明", level: "blue", title: "竞争性磋商流程规范，评审结论无异议",
        detail: "5家供应商均为行业知名企业，磋商后报价差异仅 3.5%，评审委员会无重大异议，流程规范。",
        evidence: ["5家均具备完整资质", "报价区间 ¥85.5万—¥88.5万，差异 3.5%", "技术分差异在合理范围内"],
        recommendation: "本项目评审流程规范，建议按评审结论推进后续采购程序，无需特殊处理。" },
    ],
    historyProjects: [
      { year: "2020", project: "重点区域监控摄像头安装",    amount: 68, winner: "大华技术股份有限公司",   techScore: 88, priceScore: 90, totalScore: 88.9 },
      { year: "2021", project: "道路卡口补点工程",          amount: 55, winner: "海康威视数字技术股份有限公司", techScore: 91, priceScore: 86, totalScore: 89.3 },
      { year: "2022", project: "校园周边视频监控增补",      amount: 42, winner: "宇视科技有限公司",       techScore: 86, priceScore: 91, totalScore: 88.1 },
      { year: "2023", project: "城乡结合部监控盲区补点",    amount: 78, winner: "海康威视数字技术股份有限公司", techScore: 92, priceScore: 87, totalScore: 90.1 },
    ],
    duplicateCheck: [
      { a: "海康威视数字技术股份有限公司", b: "大华技术股份有限公司", rate: 38, level: "green", note: "两家头部企业方案各有特色，相似度正常" },
      { a: "宇视科技有限公司",           b: "天地伟业技术有限公司", rate: 30, level: "green", note: "方案独立，竞争规范" },
      { a: "大华技术股份有限公司",       b: "中维世纪科技股份有限公司", rate: 25, level: "green", note: "相似度低，无异常" },
    ],
  },
};

function getBiddingDetail(id: string): BiddingDetail {
  return biddingProjectDetails[id] ?? biddingProjectDetails["BD-2024-0056"];
}

type DetailTab = "overview" | "criteria" | "anomaly" | "reasoning" | "evidence";

function BiddingPage() {
  const [selectedProject, setSelectedProject] = useState<string | null>(null);
  const [detailTab, setDetailTab] = useState<DetailTab>("overview");
  const [reasoningTarget, setReasoningTarget] = useState(getBiddingDetail("BD-2024-0056").bidders[0]);
  const [reasoningModal, setReasoningModal] = useState(false);

  useEffect(() => {
    if (selectedProject) {
      setReasoningTarget(getBiddingDetail(selectedProject).bidders[0]);
      setDetailTab("overview");
    }
  }, [selectedProject]);

  if (!selectedProject) {
    return (
      <div className="p-6 space-y-4">
        <div className="flex items-center justify-between">
          <h2 className="text-base font-semibold text-slate-900">招标评审管理</h2>
          <div className="flex gap-2">
            <button className="flex items-center gap-1.5 text-xs px-3 py-2 rounded bg-slate-50 text-muted-foreground border border-slate-200 hover:bg-slate-100 transition-colors">
              <Filter size={13} />筛选
            </button>
            <button className="flex items-center gap-1.5 text-xs px-3 py-2 rounded bg-blue-50 text-blue-700 border border-blue-200 hover:bg-blue-100 transition-colors">
              <Plus size={13} />发起招标
            </button>
          </div>
        </div>

        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <StatCard label="进行中项目" value="5" sub="本周新增 2" />
          <StatCard label="待开标" value="3" sub="最近一次明天 09:00" accent="text-blue-700" />
          <StatCard label="智能体查重" value="12" sub="发现雷同 2 起" accent="text-amber-700" />
          <StatCard label="本月完成" value="8" sub="平均周期 18 天" />
        </div>

        <div className="bg-card border border-slate-200 rounded-lg overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr className="border-b border-slate-200 text-muted-foreground">
                  {["项目编号", "项目名称", "招标方式", "预算金额", "投标人数", "当前状态", "智能体审查", "开标时间", "操作"].map(h => (
                    <th key={h} className="px-4 py-3 text-left font-mono font-medium whitespace-nowrap">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-200">
                {biddingProjects.map(p => (
                  <tr key={p.id} className="hover:bg-slate-50 transition-colors">
                    <td className="px-4 py-3 font-mono text-muted-foreground">{p.id}</td>
                    <td className="px-4 py-3 text-slate-900 font-medium">{p.name}</td>
                    <td className="px-4 py-3">
                      <span className="px-2 py-0.5 rounded bg-slate-100 text-slate-500 font-mono text-[10px]">{p.type}</span>
                    </td>
                    <td className="px-4 py-3 font-mono text-slate-600">{p.budget}</td>
                    <td className="px-4 py-3 font-mono text-slate-900">{p.bidders}</td>
                    <td className="px-4 py-3">
                      <span className={`px-2 py-0.5 rounded text-[10px] font-mono border ${
                        p.status === "评审完成" ? "bg-emerald-50 text-emerald-700 border-emerald-200" :
                        p.status === "评标中" ? "bg-blue-50 text-blue-700 border-blue-200" :
                        "bg-amber-50 text-amber-700 border-amber-200"
                      }`}>
                        {p.status}
                      </span>
                    </td>
                    <td className="px-4 py-3"><RiskBadge level={p.agent} /></td>
                    <td className="px-4 py-3 font-mono text-muted-foreground whitespace-nowrap">{p.openDate}</td>
                    <td className="px-4 py-3">
                      <div className="flex gap-2">
                        <button onClick={() => setSelectedProject(p.id)} className="text-blue-700 hover:text-blue-700 transition-colors">
                          <Eye size={13} />
                        </button>
                        <button className="text-muted-foreground hover:text-slate-600 transition-colors">
                          <Download size={13} />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    );
  }

  // ── Bidding detail data ────────────────────────────────────────────────────
  const detail = getBiddingDetail(selectedProject!);
  const bidders = detail.bidders;
  const scoringCriteria = detail.scoringCriteria;
  const anomalies = detail.anomalies;
  const historyProjects = detail.historyProjects;

  const proj = biddingProjects.find(p => p.id === selectedProject)!;

  return (
    <div className="flex flex-col h-full min-h-0">
      {/* Top bar */}
      <div className="px-6 py-4 border-b border-slate-200 flex items-center justify-between shrink-0">
        <div className="flex items-center gap-3">
          <button onClick={() => setSelectedProject(null)} className="text-muted-foreground hover:text-slate-600 transition-colors">
            <ArrowRight size={16} className="rotate-180" />
          </button>
          <div>
            <div className="flex items-center gap-2 text-xs text-muted-foreground mb-1">
              <span className="font-mono">{proj.id}</span>
              <ChevronRight size={10} />
              <span>{proj.name}</span>
            </div>
            <h2 className="text-base font-semibold text-slate-900">评审分析工作台</h2>
          </div>
        </div>
        <div className="flex gap-2">
          <span className="text-xs px-2 py-1 rounded font-mono border bg-blue-50 text-blue-700 border-blue-200 self-center">{proj.status}</span>
          <button className="text-xs px-3 py-1.5 rounded bg-slate-100 text-slate-600 hover:bg-slate-100 border border-slate-200 transition-colors">
            <Download size={11} className="inline mr-1" />导出评审报告
          </button>
          <button className="text-xs px-3 py-1.5 rounded bg-blue-50 text-blue-700 border border-blue-200 hover:bg-blue-100 transition-colors">
            完成评审
          </button>
        </div>
      </div>

      {/* Tab bar */}
      <div className="flex border-b border-slate-200 px-6 shrink-0 bg-slate-50">
        {([
          ["overview",  <BarChart3 size={13} />,   "综合评分"],
          ["criteria",  <Brain size={13} />,       "评分标准解析"],
          ["anomaly",   <ShieldAlert size={13} />,  "异常检测"],
          ["reasoning", <Zap size={13} />,          "推理路径追溯"],
          ["evidence",  <Link size={13} />,         "证据链分析"],
        ] as [DetailTab, React.ReactNode, string][]).map(([id, icon, label]) => (
          <button key={id} onClick={() => setDetailTab(id)}
            className={`flex items-center gap-1.5 px-4 py-3 text-xs font-medium border-b-2 transition-colors ${detailTab === id ? "border-blue-500 text-blue-700" : "border-transparent text-muted-foreground hover:text-slate-700"}`}>
            {icon}{label}
            {id === "anomaly" && <span className="ml-1 text-[9px] font-mono bg-red-500 text-white rounded-full px-1 leading-4">{detail.anomalies.length}</span>}
          </button>
        ))}
      </div>

      {/* Tab content */}
      <div className="flex-1 overflow-y-auto no-scrollbar">

        {/* ── TAB: 综合评分 ── */}
        {detailTab === "overview" && (
          <div className="p-6 space-y-6">
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
              {/* 专家打分 */}
              <div className="bg-card border border-slate-200 rounded-lg overflow-hidden">
                <div className="px-5 py-4 border-b border-slate-200 flex items-center justify-between">
                  <h3 className="text-sm font-semibold text-slate-900">专家评分明细</h3>
                  <span className="text-xs font-mono text-muted-foreground">{detail.expertScores.length} 位专家 · 均值 {(detail.expertScores.reduce((s, e) => s + e.total, 0) / detail.expertScores.length).toFixed(1)}</span>
                </div>
                <div className="overflow-x-auto">
                  <table className="w-full text-xs">
                    <thead>
                      <tr className="border-b border-slate-200 text-muted-foreground">
                        {["专家", "技术(35)", "商务(15)", "价格(40)", "总分", "偏差"].map(h => (
                          <th key={h} className="px-4 py-3 text-left font-mono font-medium whitespace-nowrap">{h}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-200">
                      {detail.expertScores.map((s, i) => (
                        <tr key={i} className={Math.abs(s.deviation) > 5 ? "bg-amber-50" : ""}>
                          <td className="px-4 py-3 text-slate-900 flex items-center gap-2">
                            {Math.abs(s.deviation) > 5 && <AlertTriangle size={11} className="text-amber-700 shrink-0" />}
                            {s.expert}
                          </td>
                          <td className="px-4 py-3 font-mono text-slate-600">{s.tech}</td>
                          <td className="px-4 py-3 font-mono text-slate-600">{s.business}</td>
                          <td className="px-4 py-3 font-mono text-slate-600">{s.price}</td>
                          <td className="px-4 py-3 font-mono text-slate-900 font-semibold">{s.total}</td>
                          <td className="px-4 py-3 font-mono">
                            <span className={Math.abs(s.deviation) > 5 ? "text-amber-700" : "text-emerald-700"}>
                              {s.deviation > 0 ? "+" : ""}{s.deviation}%
                            </span>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                {detail.expertScores.some(e => Math.abs(e.deviation) > 5) && (
                  <div className="px-5 py-3 border-t border-slate-200 bg-amber-50 flex items-center gap-2">
                    <AlertTriangle size={12} className="text-amber-700" />
                    <span className="text-xs text-amber-700">{detail.expertScores.filter(e => Math.abs(e.deviation) > 5).map(e => e.expert).join("、")} 打分偏差超阈值，已触发黄色预警 · </span>
                    <button onClick={() => setDetailTab("anomaly")} className="text-xs text-blue-700 hover:underline">查看异常检测 →</button>
                  </div>
                )}
              </div>

              {/* 投标人综合排名（带可视化评分条） */}
              <div className="bg-card border border-slate-200 rounded-lg overflow-hidden">
                <div className="px-5 py-4 border-b border-slate-200 flex items-center justify-between">
                  <h3 className="text-sm font-semibold text-slate-900">综合得分排名</h3>
                  <button onClick={() => setDetailTab("reasoning")} className="text-xs text-blue-700 hover:underline">查看推理路径 →</button>
                </div>
                <div className="p-4 space-y-3">
                  {bidders.map((b, i) => (
                    <div key={i} className={`p-3 rounded border ${b.status === "red" ? "border-red-200 bg-red-50" : "border-slate-200 bg-slate-50"}`}>
                      <div className="flex items-center justify-between mb-2">
                        <div className="flex items-center gap-2">
                          <span className={`text-sm font-mono font-semibold ${i === 0 ? "text-emerald-700" : i === 1 ? "text-blue-700" : b.status === "red" ? "text-red-700" : "text-slate-600"}`}>
                            #{b.rank}
                          </span>
                          <span className="text-sm text-slate-900">{b.name}</span>
                          {b.status === "red" && <RiskBadge level="red" compact />}
                        </div>
                        <div className="flex items-center gap-2">
                          <span className="text-sm font-mono font-semibold text-slate-900">{b.total}</span>
                          <button onClick={() => { setReasoningTarget(b); setReasoningModal(true); }}
                            className="text-[10px] px-2 py-0.5 rounded bg-blue-50 text-blue-700 border border-blue-200 hover:bg-blue-100 transition-colors font-mono">
                            溯源
                          </button>
                        </div>
                      </div>
                      {/* Score breakdown bar */}
                      <div className="flex h-2 rounded overflow-hidden gap-px">
                        <div className="bg-purple-500/70" style={{ width: `${(b.tech / 35) * 35}%` }} title={`技术 ${b.tech}`} />
                        <div className="bg-emerald-500/70" style={{ width: `${(b.service / 15) * 15}%` }} title={`服务 ${b.service}`} />
                        <div className="bg-blue-500/70"   style={{ width: `${(b.price / 40) * 40}%` }} title={`价格 ${b.price}`} />
                        <div className="bg-amber-500/70"  style={{ width: `${(b.credit / 10) * 10}%` }} title={`信誉 ${b.credit}`} />
                      </div>
                      <div className="flex gap-3 mt-1.5 text-[10px] font-mono text-muted-foreground">
                        <span className="text-purple-700">技术 {b.tech}</span>
                        <span className="text-emerald-700">服务 {b.service}</span>
                        <span className="text-blue-700">价格 {b.price}</span>
                        <span className="text-amber-700">信誉 {b.credit}</span>
                        <span className="ml-auto text-slate-600">报价 ¥{b.bid}万</span>
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            </div>

            {/* 文件查重 */}
            <div className="bg-card border border-slate-200 rounded-lg overflow-hidden">
              <div className="px-5 py-4 border-b border-slate-200 flex items-center gap-2">
                <Brain size={14} className="text-blue-700" />
                <h3 className="text-sm font-semibold text-slate-900">智能体文件查重</h3>
                <span className="ml-auto text-xs font-mono text-red-700 bg-red-50 px-2 py-0.5 rounded border border-red-200">发现异常 {detail.duplicateCheck.filter(r => r.level !== "green").length} 起</span>
              </div>
              <div className="p-5 grid grid-cols-1 md:grid-cols-3 gap-4">
                {detail.duplicateCheck.map((r, i) => (
                  <div key={i} className={`p-4 rounded border space-y-2 ${r.level === "red" ? "border-red-200 bg-red-50" : "border-slate-200 bg-slate-50"}`}>
                    <div className="flex items-center justify-between">
                      <span className={`text-sm font-medium ${r.level === "red" ? "text-red-700" : "text-slate-900"}`}>{r.a} vs {r.b}</span>
                      <span className={`text-xs font-mono ${r.level === "red" ? "text-red-700" : "text-emerald-700"}`}>雷同率 {r.rate}%</span>
                    </div>
                    <div className="h-1.5 rounded bg-slate-100 overflow-hidden">
                      <div className={`h-full rounded ${r.level === "red" ? "bg-red-500" : "bg-emerald-500"}`} style={{ width: `${r.rate}%` }} />
                    </div>
                    <p className="text-xs text-slate-600">{r.note}</p>
                  </div>
                ))}
              </div>
            </div>
          </div>
        )}

        {/* ── TAB: 评分标准解析 ── */}
        {detailTab === "criteria" && (
          <div className="p-6 space-y-5">
            <div className="flex items-start gap-3 p-4 rounded border border-blue-200 bg-blue-50">
              <Brain size={15} className="text-blue-700 shrink-0 mt-0.5" />
              <div className="text-xs text-slate-600 space-y-1">
                <p className="text-blue-700 font-medium">AI 结构化解析说明</p>
                <p>智能体已从招标文件（ZB-2024-0056，共 124 页）中自动提取评分标准，识别出 4 个评分大项、11 个子项，置信度 96.3%。3 处模糊表述已标记供人工复核。</p>
              </div>
            </div>

            {scoringCriteria.map((cat, ci) => (
              <div key={ci} className="bg-card border border-slate-200 rounded-lg overflow-hidden">
                <div className="px-5 py-4 border-b border-slate-200">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-3">
                      <div className={`w-3 h-3 rounded-full ${cat.color}`} />
                      <h3 className="text-sm font-semibold text-slate-900">{cat.category}</h3>
                      <span className="text-xs font-mono text-muted-foreground">权重 {cat.weight}%</span>
                    </div>
                    <div className="flex items-center gap-2">
                      <span className="text-xs font-mono text-blue-700 bg-blue-50 px-2 py-0.5 rounded border border-blue-200">
                        来源：{enumLabel(cat.source)}
                      </span>
                      {cat.aiNote && (
                        <span className="text-xs font-mono text-amber-700 bg-amber-50 px-2 py-0.5 rounded border border-amber-200">
                          ⚠ {cat.aiNote}
                        </span>
                      )}
                    </div>
                  </div>
                  {/* Weight bar */}
                  <div className="mt-3 flex items-center gap-3">
                    <div className="flex-1 h-2 bg-slate-50 rounded overflow-hidden">
                      <div className={`h-full ${cat.color} opacity-70 rounded transition-all`} style={{ width: `${cat.weight}%` }} />
                    </div>
                    <span className="text-xs font-mono text-slate-900 w-8 text-right">{cat.weight}分</span>
                  </div>
                  <p className="text-xs text-muted-foreground mt-2 font-mono">{cat.method}</p>
                </div>
                <div className="p-4 space-y-2">
                  {cat.subcriteria.map((sub, si) => (
                    <div key={si} className="flex items-center gap-4 px-3 py-2.5 rounded bg-slate-50 border border-slate-200 text-xs">
                      <div className={`size-1.5 rounded-full ${cat.color} shrink-0`} />
                      <span className="text-slate-900 w-24 shrink-0">{sub.name}</span>
                      <div className="flex-1 h-1 bg-slate-100 rounded overflow-hidden">
                        <div className={`h-full ${cat.color} opacity-60`} style={{ width: `${(sub.maxScore / cat.weight) * 100}%` }} />
                      </div>
                      <span className="font-mono text-slate-600 w-10 text-right shrink-0">{sub.maxScore} 分</span>
                      <span className="text-muted-foreground flex-1 text-right">{sub.desc}</span>
                    </div>
                  ))}
                </div>

                {/* Per-bidder scores for this category */}
                <div className="px-4 pb-4">
                  <p className="text-xs text-muted-foreground font-mono mb-2">各投标人本项得分对比</p>
                  <div className="flex gap-2 flex-wrap">
                    {bidders.map((b, bi) => {
                      const score = ci === 0 ? b.price : ci === 1 ? b.tech : ci === 2 ? b.service : b.credit;
                      const pct = (score / cat.weight) * 100;
                      return (
                        <div key={bi} className="flex-1 min-w-24 p-2 rounded bg-slate-50 border border-slate-200 text-center">
                          <p className="text-[10px] text-muted-foreground mb-1 truncate">{b.name.replace("公司", "")}</p>
                          <div className="h-1 bg-slate-100 rounded overflow-hidden mb-1">
                            <div className={`h-full ${cat.color} opacity-60`} style={{ width: `${pct}%` }} />
                          </div>
                          <p className={`text-xs font-mono font-semibold ${bi === 0 ? "text-emerald-700" : "text-slate-600"}`}>{score}</p>
                        </div>
                      );
                    })}
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}

        {/* ── TAB: 异常检测 ── */}
        {detailTab === "anomaly" && (
          <div className="p-6 space-y-5">
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
              {[
                { label: "红色拦截", count: 1, color: "text-red-700", bg: "bg-red-50 border-red-200" },
                { label: "黄色提示", count: 2, color: "text-amber-700", bg: "bg-amber-50 border-amber-200" },
                { label: "蓝色提醒", count: 1, color: "text-blue-700",  bg: "bg-blue-50 border-blue-200" },
                { label: "正常通过", count: "18", color: "text-emerald-700", bg: "bg-emerald-50 border-emerald-200" },
              ].map((c, i) => (
                <div key={i} className={`rounded-lg border p-4 ${c.bg}`}>
                  <p className={`text-2xl font-semibold font-mono ${c.color}`}>{c.count}</p>
                  <p className="text-xs text-muted-foreground mt-1">{c.label}</p>
                </div>
              ))}
            </div>

            {anomalies.map((a) => (
              <div key={a.id} className={`bg-card rounded-lg border overflow-hidden ${a.level === "red" ? "border-red-200" : a.level === "yellow" ? "border-amber-200" : "border-blue-200"}`}>
                <div className={`px-5 py-4 flex items-center gap-3 ${a.level === "red" ? "bg-red-50" : a.level === "yellow" ? "bg-amber-50" : "bg-blue-50"}`}>
                  <RiskBadge level={a.level} />
                  <span className="text-xs font-mono text-muted-foreground">{a.id}</span>
                  <span className="text-sm font-semibold text-slate-900">{a.title}</span>
                  <span className={`ml-auto text-xs font-mono ${a.level === "red" ? "text-red-700" : a.level === "yellow" ? "text-amber-700" : "text-blue-700"}`}>{a.type}</span>
                </div>
                <div className="px-5 py-4 space-y-3">
                  <p className="text-sm text-slate-600 leading-relaxed">{a.detail}</p>
                  <div>
                    <p className="text-xs text-muted-foreground font-mono mb-2">AI 推理依据</p>
                    <div className="flex flex-wrap gap-2">
                      {a.evidence.map((e, ei) => (
                        <div key={ei} className="flex items-center gap-1.5 text-xs px-2.5 py-1 rounded bg-slate-50 border border-slate-200 text-slate-600">
                          <span className="text-blue-700 font-mono text-[10px]">#{ei + 1}</span>
                          {e}
                        </div>
                      ))}
                    </div>
                  </div>
                  <div className={`flex items-start gap-2 p-3 rounded border text-xs ${a.level === "red" ? "bg-red-50 border-red-200 text-red-700" : a.level === "yellow" ? "bg-amber-50 border-amber-200 text-amber-700" : "bg-blue-50 border-blue-200 text-blue-700"}`}>
                    <Zap size={12} className="shrink-0 mt-0.5" />
                    <span><strong>建议处置：</strong>{a.recommendation}</span>
                  </div>
                </div>
              </div>
            ))}

            {/* 历史对比 */}
            <div className="bg-card border border-slate-200 rounded-lg overflow-hidden">
              <div className="px-5 py-4 border-b border-slate-200 flex items-center gap-2">
                <Activity size={14} className="text-blue-700" />
                <h3 className="text-sm font-semibold text-slate-900">历史同类项目对比</h3>
                <span className="ml-auto text-xs font-mono text-muted-foreground">近 5 年政务/园区卡口采购</span>
              </div>
              <div className="p-5">
                <ResponsiveContainer width="100%" height={180}>
                  <BarChart data={historyProjects} margin={{ top: 4, right: 16, left: -20, bottom: 0 }}>
                    <XAxis dataKey="year" tick={{ fill: "#6b84a0", fontSize: 11 }} />
                    <YAxis tick={{ fill: "#6b84a0", fontSize: 11 }} />
                    <Tooltip contentStyle={{ background: "#131a24", border: "1px solid rgba(255,255,255,0.1)", borderRadius: 6, fontSize: 12 }} />
                    <Bar dataKey="amount" name="成交金额(万)" fill="#3b82f6" fillOpacity={0.7} radius={[3, 3, 0, 0]} />
                  </BarChart>
                </ResponsiveContainer>
                <div className="mt-4 space-y-2">
                  {historyProjects.map((h, i) => (
                    <div key={i} className={`flex items-center gap-4 px-3 py-2 rounded text-xs border ${i === historyProjects.length - 1 ? "border-blue-200 bg-blue-50" : "border-slate-200 bg-slate-50"}`}>
                      <span className={`font-mono w-16 shrink-0 ${i === historyProjects.length - 1 ? "text-blue-700 font-semibold" : "text-muted-foreground"}`}>{h.year}</span>
                      <span className="flex-1 text-slate-900">{h.project}</span>
                      <span className="font-mono text-emerald-700 w-14 text-right shrink-0">¥{h.amount}万</span>
                      <span className="text-muted-foreground w-16 text-right shrink-0">中标：{h.winner}</span>
                      <span className="font-mono text-slate-600 w-16 text-right shrink-0">综合 {h.totalScore}</span>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          </div>
        )}

        {/* ── TAB: 推理路径追溯 ── */}
        {detailTab === "reasoning" && (
          <div className="p-6 space-y-5">
            <div className="flex items-center gap-3 flex-wrap">
              <span className="text-xs text-muted-foreground">选择投标人查看评分推导过程：</span>
              {bidders.map((b, i) => (
                <button key={i} onClick={() => setReasoningTarget(b)}
                  className={`text-xs px-3 py-1.5 rounded border font-medium transition-colors ${reasoningTarget.name === b.name ? "bg-blue-100 border-blue-200 text-blue-700" : "bg-slate-50 border-slate-200 text-muted-foreground hover:bg-slate-100"}`}>
                  {b.name} <span className="font-mono ml-1 opacity-70">{b.total}</span>
                </button>
              ))}
            </div>

            {/* Reasoning path visualization */}
            <div className="bg-card border border-slate-200 rounded-lg overflow-hidden">
              <div className="px-5 py-4 border-b border-slate-200 flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <Zap size={14} className="text-blue-700" />
                  <h3 className="text-sm font-semibold text-slate-900">{reasoningTarget.name} · 评分推理路径</h3>
                </div>
                <span className="text-lg font-semibold font-mono text-slate-900">综合得分 {reasoningTarget.total}</span>
              </div>

              <div className="p-5 space-y-4">
                {/* Input node */}
                <div className="flex items-center gap-3 p-3 rounded border border-slate-200 bg-slate-50 text-xs">
                  <Database size={13} className="text-blue-700 shrink-0" />
                  <div className="flex-1">
                    <p className="text-slate-900 font-medium">输入：投标文件 + 招标文件 + 资质数据库</p>
                    <p className="text-muted-foreground mt-0.5">文件解析完成 · 共提取 47 个评分相关字段 · {new Date().toLocaleDateString()}</p>
                  </div>
                  <CheckCircle size={13} className="text-emerald-700 shrink-0" />
                </div>

                <div className="flex items-center justify-center text-muted-foreground text-xs gap-1">
                  <ArrowRight size={12} className="rotate-90" />
                  <span className="font-mono">逐项评分推导</span>
                </div>

                {/* Scoring steps */}
                {[
                  {
                    label: "① 价格分（满分 40 分）", color: "border-blue-200 bg-blue-50",
                    score: reasoningTarget.price, maxScore: 40, barColor: "bg-blue-500",
                    steps: [
                      `基准价（最低报价）：¥${Math.min(...bidders.map(b => b.bid))}万 — D信息技术公司`,
                      `本次报价：¥${reasoningTarget.bid}万`,
                      `溢价率：${(((reasoningTarget.bid / Math.min(...bidders.map(b => b.bid))) - 1) * 100).toFixed(1)}%`,
                      `得分公式：40 × (${Math.min(...bidders.map(b => b.bid))} ÷ ${reasoningTarget.bid}) = ${reasoningTarget.price}`,
                    ],
                    anomaly: reasoningTarget.bid < 285 ? "⚠ 报价低于 AI 成本估算底线 ¥285万，已触发低价异常检测" : null,
                  },
                  {
                    label: "② 技术方案（满分 35 分）", color: "border-purple-200 bg-purple-50",
                    score: reasoningTarget.tech, maxScore: 35, barColor: "bg-purple-500",
                    steps: [
                      `方案完整性：${(reasoningTarget.tech * 0.286).toFixed(1)} / 10 — 文档结构评估通过`,
                      `技术先进性：${(reasoningTarget.tech * 0.343).toFixed(1)} / 12 — AI识别率≥98.5%，4K分辨率`,
                      `交付能力：${(reasoningTarget.tech * 0.229).toFixed(1)} / 8 — 工期承诺 ${reasoningTarget.rank === 1 ? "85" : "90"} 天`,
                      `售后服务：${(reasoningTarget.tech * 0.143).toFixed(1)} / 5 — 驻场工程师 ${reasoningTarget.rank === 1 ? "2" : "1"} 名`,
                    ],
                    anomaly: reasoningTarget.name === "李高工打分" ? "⚠ 李高工单项打分偏差超阈值" : null,
                  },
                  {
                    label: "③ 服务能力（满分 15 分）", color: "border-emerald-200 bg-emerald-50",
                    score: reasoningTarget.service, maxScore: 15, barColor: "bg-emerald-500",
                    steps: [
                      `团队资质：${(reasoningTarget.service * 0.533).toFixed(1)} / 8 — 项目经理PMP认证，${reasoningTarget.rank <= 2 ? "高级工程师" : "中级工程师"}`,
                      `历史业绩：${(reasoningTarget.service * 0.467).toFixed(1)} / 7 — 近3年${reasoningTarget.rank <= 2 ? "4" : "2"}项同类业绩，已核实`,
                    ],
                    anomaly: null,
                  },
                  {
                    label: "④ 社会信誉（满分 10 分）", color: "border-amber-200 bg-amber-50",
                    score: reasoningTarget.credit, maxScore: 10, barColor: "bg-amber-500",
                    steps: [
                      `荣誉资质：${(reasoningTarget.credit * 0.5).toFixed(1)} / 5 — ${reasoningTarget.rank <= 2 ? "国家高新技术企业，行业一等奖" : "省级资质，无重大奖项"}`,
                      `合同履约率：${(reasoningTarget.credit * 0.5).toFixed(1)} / 5 — 近3年履约率 ${(93 + reasoningTarget.rank).toFixed(0)}%，评价"良好"`,
                    ],
                    anomaly: null,
                  },
                ].map((step, si) => (
                  <div key={si} className={`rounded-lg border ${step.color} overflow-hidden`}>
                    <div className="px-4 py-3 flex items-center justify-between">
                      <span className="text-sm font-medium text-slate-900">{step.label}</span>
                      <div className="flex items-center gap-3">
                        <div className="w-32 h-1.5 bg-slate-100 rounded overflow-hidden">
                          <div className={`h-full ${step.barColor} opacity-70 rounded`}
                            style={{ width: `${(step.score / step.maxScore) * 100}%` }} />
                        </div>
                        <span className="text-sm font-mono font-semibold text-slate-900 w-16 text-right">
                          {step.score} <span className="text-muted-foreground font-normal">/ {step.maxScore}</span>
                        </span>
                      </div>
                    </div>
                    <div className="px-4 pb-3 space-y-1.5">
                      {step.steps.map((s, i) => (
                        <div key={i} className="flex items-start gap-2 text-xs">
                          <span className="text-blue-700 font-mono shrink-0 mt-0.5">→</span>
                          <span className="text-slate-600">{s}</span>
                        </div>
                      ))}
                      {step.anomaly && (
                        <div className="flex items-center gap-2 mt-1 text-xs text-amber-700 bg-amber-50 border border-amber-200 rounded px-2 py-1">
                          <AlertTriangle size={11} className="shrink-0" />
                          {step.anomaly}
                        </div>
                      )}
                    </div>
                  </div>
                ))}

                {/* Output node */}
                <div className="flex items-center justify-center text-muted-foreground text-xs gap-1">
                  <ArrowRight size={12} className="rotate-90" />
                </div>
                <div className={`flex items-center gap-4 p-4 rounded-lg border ${reasoningTarget.status === "red" ? "border-red-200 bg-red-50" : "border-emerald-200 bg-emerald-50"}`}>
                  {reasoningTarget.status === "red"
                    ? <XCircle size={18} className="text-red-700 shrink-0" />
                    : <CheckCircle size={18} className="text-emerald-700 shrink-0" />}
                  <div className="flex-1">
                    <p className="text-sm font-semibold text-slate-900">
                      综合得分：{reasoningTarget.total} / 100 &nbsp;·&nbsp; 排名第 {reasoningTarget.rank} 位
                    </p>
                    <p className="text-xs text-muted-foreground mt-0.5">
                      {reasoningTarget.status === "red"
                        ? "存在供应商关联/雷同问题，建议取消投标资格，不参与排名"
                        : `评分有效，${reasoningTarget.rank === 1 ? "推荐中标候选人（第一顺位）" : `建议中标候选人（第 ${reasoningTarget.rank} 顺位）`}`}
                    </p>
                  </div>
                  <RiskBadge level={reasoningTarget.status} />
                </div>
              </div>
            </div>
          </div>
        )}
      </div>

        {/* ── TAB: 证据链分析 ── */}
        {detailTab === "evidence" && (() => {
          // ── Section 1: 证据链自动化固化 ──
          const chains = detail.anomalies.map((a, ai) => ({
            anomalyId: a.id,
            title: a.title,
            level: a.level,
            type: a.type,
            nodes: [
              { step: 1, label: "线索触发", source: "AI 智能体扫描", desc: `${a.type}检测算法命中`, time: "自动", icon: "trigger" },
              ...a.evidence.map((ev, ei) => ({
                step: ei + 2, label: `证据 ${ei + 1}`, source: "系统采集", desc: ev, time: "自动固化", icon: "doc",
              })),
              { step: a.evidence.length + 2, label: "风险定级", source: "规则引擎", desc: `定级为${a.level === "red" ? "红色" : a.level === "yellow" ? "黄色" : "蓝色"}风险 · ${a.type}`, time: "自动", icon: "flag" },
              { step: a.evidence.length + 3, label: "处置建议", source: "AI 综合分析", desc: a.recommendation, time: "自动", icon: "action" },
            ],
          }));

          // ── Section 2: 资料交叉引用检测 ──
          const crossRefs = [
            ...detail.duplicateCheck.map((dc, i) => ({
              id: `CR-${String(i + 1).padStart(2, "0")}`,
              docA: `${dc.a} · 投标书`,
              docB: `${dc.b} · 投标书`,
              field: "技术方案正文",
              similarity: dc.rate,
              level: dc.level,
              finding: dc.note,
              action: dc.level === "red" ? "立即核查" : dc.level === "yellow" ? "人工复核" : "已通过",
            })),
            {
              id: `CR-${String(detail.duplicateCheck.length + 1).padStart(2, "0")}`,
              docA: "招标文件 · 技术规格书",
              docB: `${detail.bidders[0]?.name ?? "投标人1"} · 投标技术方案`,
              field: "设备参数引用",
              similarity: 94,
              level: "green" as RiskLevel,
              finding: "投标技术方案完整引用招标规格，符合要求",
              action: "已通过",
            },
            {
              id: `CR-${String(detail.duplicateCheck.length + 2).padStart(2, "0")}`,
              docA: "控制价测算报告",
              docB: detail.anomalies.find(a => a.type.includes("价格") || a.type.includes("低价") || a.type.includes("控制价")) ? "异常检测报告" : "投标报价汇总",
              field: "价格基准数据",
              similarity: 88,
              level: "green" as RiskLevel,
              finding: "控制价与市场基准数据交叉验证一致",
              action: "已通过",
            },
          ];

          // ── Section 3: 非合理一致性预警 ──
          const consistencyWarnings: { id: string; pattern: string; scope: string; level: RiskLevel; detail: string; evidence: string; suggestion: string }[] = [];
          detail.duplicateCheck.filter(dc => dc.level !== "green").forEach((dc, i) => {
            consistencyWarnings.push({
              id: `CW-${String(i + 1).padStart(2, "0")}`,
              pattern: "文件内容高度雷同",
              scope: `${dc.a} ↔ ${dc.b}`,
              level: dc.level,
              detail: `两家投标文件整体相似度 ${dc.rate}%，超过合理独立竞标阈值（60%）`,
              evidence: dc.note,
              suggestion: dc.level === "red" ? "暂停评标，申请监察介入" : "专家现场答辩核实，书面说明独立性",
            });
          });
          if (detail.expertScores.some(e => Math.abs(e.deviation) > 5)) {
            const outliers = detail.expertScores.filter(e => Math.abs(e.deviation) > 5);
            consistencyWarnings.push({
              id: `CW-${String(consistencyWarnings.length + 1).padStart(2, "0")}`,
              pattern: "专家评分异常一致/偏离",
              scope: outliers.map(e => e.expert).join("、"),
              level: "yellow",
              detail: `${outliers.length}位专家打分偏差超阈值±5%，偏差值：${outliers.map(e => `${e.deviation > 0 ? "+" : ""}${e.deviation}%`).join("、")}`,
              evidence: `其余专家均值 ${(detail.expertScores.filter(e => Math.abs(e.deviation) <= 5).reduce((s, e) => s + e.total, 0) / Math.max(1, detail.expertScores.filter(e => Math.abs(e.deviation) <= 5).length)).toFixed(1)} 分`,
              suggestion: "要求偏差专家书面说明评分依据，必要时申请重新评审",
            });
          }
          const redBidders = detail.bidders.filter(b => b.status === "red");
          if (redBidders.length >= 2) {
            consistencyWarnings.push({
              id: `CW-${String(consistencyWarnings.length + 1).padStart(2, "0")}`,
              pattern: "多投标人同时触发红色预警",
              scope: redBidders.map(b => b.name).join("、"),
              level: "red",
              detail: `${redBidders.length} 家投标人同时存在红色风险标记，需核查是否存在关联或协同行为`,
              evidence: `红色预警投标人：${redBidders.map(b => `${b.name}（总分 ${b.total}）`).join("；")}`,
              suggestion: "评标委员会逐一核查各红色预警来源，发现关联证据立即上报",
            });
          }
          if (consistencyWarnings.length === 0) {
            consistencyWarnings.push({
              id: "CW-01",
              pattern: "无异常一致性问题",
              scope: "全部投标文件",
              level: "green",
              detail: "AI 非合理一致性检测未发现可疑模式，所有投标文件独立性良好",
              evidence: "文件相似度均在合理范围内，专家打分偏差正常",
              suggestion: "本项目可正常推进评标程序",
            });
          }

          const levelColor = (l: RiskLevel) => l === "red" ? "text-red-700 border-red-200 bg-red-50" : l === "yellow" ? "text-amber-700 border-amber-200 bg-amber-50" : l === "blue" ? "text-blue-700 border-blue-200 bg-blue-50" : "text-emerald-700 border-emerald-200 bg-emerald-50";
          const levelDot = (l: RiskLevel) => l === "red" ? "bg-red-500" : l === "yellow" ? "bg-amber-500" : l === "blue" ? "bg-blue-500" : "bg-emerald-500";
          const nodeIcon = (icon: string, l: RiskLevel) => {
            if (icon === "trigger") return <Zap size={11} className="text-blue-700" />;
            if (icon === "flag") return <ShieldAlert size={11} className={l === "red" ? "text-red-700" : l === "yellow" ? "text-amber-700" : "text-blue-700"} />;
            if (icon === "action") return <CheckCircle size={11} className="text-emerald-700" />;
            return <Database size={11} className="text-slate-600" />;
          };

          return (
            <div className="p-6 space-y-6">
              {/* ① 证据链自动化固化 */}
              <div>
                <div className="flex items-center gap-2 mb-4">
                  <Link size={14} className="text-blue-700" />
                  <h3 className="text-sm font-semibold text-slate-900">证据链自动化固化</h3>
                  <span className="text-xs font-mono text-muted-foreground ml-1">— 每条风险线索完整证据链自动归档，不可篡改</span>
                  <span className="ml-auto text-[10px] font-mono px-2 py-0.5 rounded border bg-blue-50 text-blue-700 border-blue-200">{chains.length} 条证据链</span>
                </div>
                <div className="space-y-4">
                  {chains.map((chain, ci) => (
                    <div key={ci} className={`rounded-lg border overflow-hidden ${levelColor(chain.level)}`}>
                      <div className="px-5 py-3 border-b border-slate-200 flex items-center gap-3">
                        <div className={`size-2 rounded-full shrink-0 ${levelDot(chain.level)}`} />
                        <span className="text-xs font-mono text-muted-foreground">{chain.anomalyId}</span>
                        <span className="text-sm font-medium text-slate-900">{chain.title}</span>
                        <span className="ml-auto text-[10px] font-mono px-2 py-0.5 rounded bg-slate-50 border border-slate-200 text-muted-foreground">{chain.nodes.length} 个节点</span>
                      </div>
                      <div className="p-4">
                        <div className="flex items-start gap-0 overflow-x-auto no-scrollbar pb-2">
                          {chain.nodes.map((node, ni) => (
                            <div key={ni} className="flex items-start shrink-0">
                              <div className="flex flex-col items-center w-36">
                                <div className="flex items-center justify-center size-7 rounded-full bg-blue-50 border border-blue-200 mb-2">
                                  {nodeIcon(node.icon, chain.level)}
                                </div>
                                <div className="text-center px-1">
                                  <p className="text-[10px] font-semibold text-slate-900 mb-0.5">{node.label}</p>
                                  <p className="text-[9px] text-blue-700 font-mono mb-1">{enumLabel(node.source)}</p>
                                  <p className="text-[9px] text-slate-600 leading-relaxed">{node.desc}</p>
                                </div>
                              </div>
                              {ni < chain.nodes.length - 1 && (
                                <div className="flex items-center self-start mt-3 mx-1 shrink-0">
                                  <div className="w-6 h-px bg-slate-100" />
                                  <ArrowRight size={8} className="text-slate-400 -ml-1" />
                                </div>
                              )}
                            </div>
                          ))}
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              </div>

              {/* ② 资料交叉引用检测 */}
              <div>
                <div className="flex items-center gap-2 mb-4">
                  <Database size={14} className="text-purple-700" />
                  <h3 className="text-sm font-semibold text-slate-900">资料交叉引用检测</h3>
                  <span className="text-xs font-mono text-muted-foreground ml-1">— 跨文档数据一致性核验</span>
                  <span className={`ml-auto text-[10px] font-mono px-2 py-0.5 rounded border ${crossRefs.some(r => r.level !== "green") ? "bg-amber-50 text-amber-700 border-amber-200" : "bg-emerald-50 text-emerald-700 border-emerald-200"}`}>
                    {crossRefs.filter(r => r.level !== "green").length > 0 ? `${crossRefs.filter(r => r.level !== "green").length} 处需核查` : "全部通过"}
                  </span>
                </div>
                <div className="bg-card border border-slate-200 rounded-lg overflow-hidden">
                  <table className="w-full text-xs">
                    <thead>
                      <tr className="border-b border-slate-200 text-muted-foreground">
                        {["编号", "文档 A", "文档 B", "引用字段", "相似度", "核验结论", "处置"].map(h => (
                          <th key={h} className="px-4 py-3 text-left font-mono font-medium whitespace-nowrap">{h}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-200">
                      {crossRefs.map((r, i) => (
                        <tr key={i} className={`transition-colors ${r.level !== "green" ? "bg-amber-50" : "hover:bg-slate-50"}`}>
                          <td className="px-4 py-3 font-mono text-muted-foreground">{r.id}</td>
                          <td className="px-4 py-3 text-slate-900 max-w-[160px] truncate" title={r.docA}>{r.docA}</td>
                          <td className="px-4 py-3 text-slate-900 max-w-[160px] truncate" title={r.docB}>{r.docB}</td>
                          <td className="px-4 py-3 text-slate-600">{r.field}</td>
                          <td className="px-4 py-3">
                            <div className="flex items-center gap-2">
                              <div className="w-16 h-1.5 bg-slate-100 rounded overflow-hidden">
                                <div className={`h-full rounded ${r.level === "red" ? "bg-red-500" : r.level === "yellow" ? "bg-amber-500" : "bg-emerald-500"}`} style={{ width: `${r.similarity}%` }} />
                              </div>
                              <span className={`font-mono ${r.level !== "green" ? "text-amber-700" : "text-emerald-700"}`}>{r.similarity}%</span>
                            </div>
                          </td>
                          <td className="px-4 py-3 text-slate-600 max-w-[200px]">{r.finding}</td>
                          <td className="px-4 py-3">
                            <span className={`px-2 py-0.5 rounded text-[10px] font-mono border ${r.level === "red" ? "bg-red-50 text-red-700 border-red-200" : r.level === "yellow" ? "bg-amber-50 text-amber-700 border-amber-200" : "bg-emerald-50 text-emerald-700 border-emerald-200"}`}>
                              {r.action}
                            </span>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>

              {/* ③ 非合理一致性预警 */}
              <div>
                <div className="flex items-center gap-2 mb-4">
                  <ShieldAlert size={14} className="text-amber-700" />
                  <h3 className="text-sm font-semibold text-slate-900">非合理一致性预警</h3>
                  <span className="text-xs font-mono text-muted-foreground ml-1">— 可疑行为模式识别与预警</span>
                  <span className={`ml-auto text-[10px] font-mono px-2 py-0.5 rounded border ${consistencyWarnings.some(w => w.level === "red") ? "bg-red-50 text-red-700 border-red-200" : consistencyWarnings.some(w => w.level !== "green") ? "bg-amber-50 text-amber-700 border-amber-200" : "bg-emerald-50 text-emerald-700 border-emerald-200"}`}>
                    {consistencyWarnings.filter(w => w.level !== "green").length > 0 ? `${consistencyWarnings.filter(w => w.level !== "green").length} 项预警` : "无异常"}
                  </span>
                </div>
                <div className="space-y-3">
                  {consistencyWarnings.map((w, wi) => (
                    <div key={wi} className={`rounded-lg border p-4 space-y-3 ${w.level === "red" ? "border-red-200 bg-red-50" : w.level === "yellow" ? "border-amber-200 bg-amber-50" : w.level === "blue" ? "border-blue-200 bg-blue-50" : "border-slate-200 bg-slate-50"}`}>
                      <div className="flex items-start justify-between gap-3">
                        <div className="flex items-center gap-2">
                          <div className={`size-2 rounded-full shrink-0 mt-0.5 ${levelDot(w.level)}`} />
                          <div>
                            <p className="text-sm font-semibold text-slate-900">{w.pattern}</p>
                            <p className="text-xs text-muted-foreground font-mono mt-0.5">{w.id} · 涉及：{w.scope}</p>
                          </div>
                        </div>
                        <RiskBadge level={w.level} />
                      </div>
                      <p className="text-xs text-slate-600 leading-relaxed">{w.detail}</p>
                      <div className="flex items-start gap-2 p-2.5 rounded bg-slate-50 border border-slate-200">
                        <Database size={11} className="text-muted-foreground shrink-0 mt-0.5" />
                        <p className="text-[11px] text-muted-foreground font-mono leading-relaxed">{w.evidence}</p>
                      </div>
                      <div className="flex items-center gap-2">
                        <CheckCircle size={11} className="text-blue-700 shrink-0" />
                        <p className="text-xs text-blue-700">{w.suggestion}</p>
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          );
        })()}

      {/* Reasoning quick modal (from overview tab "溯源" button) */}
      {reasoningModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div className="absolute inset-0 bg-black/60 backdrop-blur-sm" onClick={() => setReasoningModal(false)} />
          <div className="relative z-10 w-full max-w-lg mx-4 bg-white border border-slate-200 rounded-xl shadow-2xl overflow-hidden max-h-[75vh] flex flex-col">
            <div className="flex items-center justify-between px-5 py-4 border-b border-slate-200 shrink-0">
              <div className="flex items-center gap-2">
                <Zap size={14} className="text-blue-700" />
                <span className="text-sm font-semibold text-slate-900">评分依据快速溯源</span>
                <span className="text-xs text-muted-foreground">· {reasoningTarget.name}</span>
              </div>
              <button onClick={() => setReasoningModal(false)} className="text-muted-foreground hover:text-slate-700 p-1 transition-colors">
                <XCircle size={15} />
              </button>
            </div>
            <div className="overflow-y-auto no-scrollbar p-4 space-y-2">
              {[
                { label: "价格分", score: reasoningTarget.price, max: 40, color: "bg-blue-500",    note: `报价 ¥${reasoningTarget.bid}万，基准 ¥${Math.min(...bidders.map(b => b.bid))}万` },
                { label: "技术方案", score: reasoningTarget.tech,    max: 35, color: "bg-purple-500", note: "AI识别率、分辨率、交付能力综合评估" },
                { label: "服务能力", score: reasoningTarget.service, max: 15, color: "bg-emerald-500",note: `业绩 ${reasoningTarget.rank <= 2 ? 4 : 2} 项，项目经理已核实` },
                { label: "社会信誉", score: reasoningTarget.credit,  max: 10, color: "bg-amber-500",  note: `履约率 ${(93 + reasoningTarget.rank).toFixed(0)}%，信用良好` },
              ].map((row, i) => (
                <div key={i} className="flex items-center gap-3 p-3 rounded bg-slate-50 border border-slate-200 text-xs">
                  <span className="text-slate-900 w-16 shrink-0">{row.label}</span>
                  <div className="flex-1 h-1.5 bg-slate-100 rounded overflow-hidden">
                    <div className={`h-full ${row.color} opacity-70`} style={{ width: `${(row.score / row.max) * 100}%` }} />
                  </div>
                  <span className="font-mono text-slate-900 w-12 text-right shrink-0">{row.score} / {row.max}</span>
                  <span className="text-muted-foreground flex-1 text-right">{row.note}</span>
                </div>
              ))}
              <div className="flex items-center justify-between p-3 rounded border border-slate-200 bg-slate-50 text-sm mt-2">
                <span className="text-muted-foreground font-mono">综合得分</span>
                <span className="font-semibold text-slate-900 font-mono">{reasoningTarget.total} / 100</span>
              </div>
              <button onClick={() => { setReasoningModal(false); setReasoningTarget(reasoningTarget); setDetailTab("reasoning"); }}
                className="w-full text-xs py-2 rounded bg-blue-50 text-blue-700 border border-blue-200 hover:bg-blue-100 transition-colors mt-1">
                查看完整推理路径 →
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

// ── Settlement Page ────────────────────────────────────────────────────────
const settlements = [
  { id: "ST-2024-0031", name: "车辆GPS定位系统验收", contract: "CT-2024-0089", amount: "¥67.8万", payable: "¥61.02万", status: "验收中", agent: "blue" as RiskLevel, date: "2026-07-13" },
  { id: "ST-2024-0029", name: "综合业务系统运维服务结算", contract: "CT-2024-0082", amount: "¥128万", payable: "¥115.2万", status: "财务审核", agent: "yellow" as RiskLevel, date: "2026-07-11" },
  { id: "ST-2024-0027", name: "办公楼安防系统验收", contract: "CT-2024-0076", amount: "¥88万", payable: "¥79.2万", status: "已完成", agent: "green" as RiskLevel, date: "2026-07-09" },
  { id: "ST-2024-0025", name: "移动采集终端验收结算", contract: "CT-2024-0071", amount: "¥45.2万", payable: "¥40.68万", status: "材料补充", agent: "blue" as RiskLevel, date: "2026-07-07" },
  { id: "ST-2024-0023", name: "通信指挥系统结算", contract: "CT-2024-0065", amount: "¥312万", payable: "¥280.8万", status: "验收中", agent: "green" as RiskLevel, date: "2026-07-05" },
  { id: "ST-2024-0021", name: "网络安全设备验收", contract: "CT-2024-0080", amount: "¥127万", payable: "¥114.3万", status: "已完成", agent: "green" as RiskLevel, date: "2026-07-03" },
  { id: "ST-2024-0019", name: "食堂餐饮服务结算", contract: "CT-2024-0082", amount: "¥55万", payable: "¥49.5万", status: "财务审核", agent: "green" as RiskLevel, date: "2026-07-01" },
  { id: "ST-2024-0017", name: "警车维修保养验收", contract: "CT-2024-0076", amount: "¥38万", payable: "¥34.2万", status: "已完成", agent: "green" as RiskLevel, date: "2026-06-28" },
];

const paymentRecords = [
  { phase: "预付款", ratio: "30%", amount: "¥20.34万", status: "已支付", date: "2026-06-15", invoice: "已开票" },
  { phase: "验收款", ratio: "60%", amount: "¥40.68万", status: "待支付", date: "-", invoice: "待开票" },
  { phase: "质保金", ratio: "10%", amount: "¥6.78万", status: "质保期后", date: "2027-07-15", invoice: "-" },
];

function SettlementPage() {
  const [selectedSettlement, setSelectedSettlement] = useState<string | null>(null);

  if (!selectedSettlement) {
    return (
      <div className="p-6 space-y-4">
        <div className="flex items-center justify-between">
          <h2 className="text-base font-semibold text-slate-900">验收结算管理</h2>
          <div className="flex gap-2">
            <button className="flex items-center gap-1.5 text-xs px-3 py-2 rounded bg-slate-50 text-muted-foreground border border-slate-200 hover:bg-slate-100 transition-colors">
              <Filter size={13} />筛选
            </button>
            <button className="flex items-center gap-1.5 text-xs px-3 py-2 rounded bg-blue-50 text-blue-700 border border-blue-200 hover:bg-blue-100 transition-colors">
              <Plus size={13} />新建验收
            </button>
          </div>
        </div>

        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <StatCard label="验收中项目" value="5" sub="待处理 2 项" />
          <StatCard label="本月结算额" value="¥156万" sub="较上月 +12%" accent="text-emerald-700" trend="up" />
          <StatCard label="待支付款项" value="¥89万" sub="3 笔待审批" accent="text-amber-700" />
          <StatCard label="平均验收周期" value="5.8天" sub="较上月 -1.2天" trend="down" />
        </div>

        <div className="bg-card border border-slate-200 rounded-lg overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr className="border-b border-slate-200 text-muted-foreground">
                  {["结算编号", "项目名称", "关联合同", "合同金额", "本次应付", "当前状态", "智能体核对", "创建时间", "操作"].map(h => (
                    <th key={h} className="px-4 py-3 text-left font-mono font-medium whitespace-nowrap">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-200">
                {settlements.map(s => (
                  <tr key={s.id} className="hover:bg-slate-50 transition-colors">
                    <td className="px-4 py-3 font-mono text-muted-foreground">{s.id}</td>
                    <td className="px-4 py-3 text-slate-900 font-medium">{s.name}</td>
                    <td className="px-4 py-3 font-mono text-blue-700">{s.contract}</td>
                    <td className="px-4 py-3 font-mono text-slate-600">{s.amount}</td>
                    <td className="px-4 py-3 font-mono text-emerald-700 font-semibold">{s.payable}</td>
                    <td className="px-4 py-3">
                      <span className={`px-2 py-0.5 rounded text-[10px] font-mono border ${
                        s.status === "已完成" ? "bg-emerald-50 text-emerald-700 border-emerald-200" :
                        s.status === "财务审核" ? "bg-blue-50 text-blue-700 border-blue-200" :
                        "bg-amber-50 text-amber-700 border-amber-200"
                      }`}>
                        {s.status}
                      </span>
                    </td>
                    <td className="px-4 py-3"><RiskBadge level={s.agent} /></td>
                    <td className="px-4 py-3 font-mono text-muted-foreground">{s.date}</td>
                    <td className="px-4 py-3">
                      <div className="flex gap-2">
                        <button onClick={() => setSelectedSettlement(s.id)} className="text-blue-700 hover:text-blue-700 transition-colors">
                          <Eye size={13} />
                        </button>
                        <button className="text-muted-foreground hover:text-slate-600 transition-colors">
                          <Download size={13} />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    );
  }

  // 详情页面
  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <button onClick={() => setSelectedSettlement(null)} className="text-muted-foreground hover:text-slate-600 transition-colors">
            <ArrowRight size={16} className="rotate-180" />
          </button>
          <div>
            <div className="flex items-center gap-2 text-xs text-muted-foreground mb-1">
              <span className="font-mono">ST-2024-0031</span>
              <ChevronRight size={10} />
              <span>车辆GPS定位系统验收</span>
            </div>
            <h2 className="text-base font-semibold text-slate-900">验收结算详情</h2>
          </div>
        </div>
        <div className="flex gap-2">
          <button className="text-xs px-3 py-1.5 rounded bg-slate-100 text-slate-600 hover:bg-slate-100 border border-slate-200 transition-colors">
            <Download size={11} className="inline mr-1" />导出结算单
          </button>
          <button className="text-xs px-3 py-1.5 rounded bg-emerald-50 text-emerald-700 border border-emerald-200 hover:bg-emerald-100 transition-colors">
            提交审批
          </button>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* 付款计划 */}
        <div className="lg:col-span-2 bg-card border border-slate-200 rounded-lg overflow-hidden">
          <div className="px-5 py-4 border-b border-slate-200">
            <h3 className="text-sm font-semibold text-slate-900">付款计划与记录</h3>
          </div>
          <div className="p-5 space-y-3">
            {paymentRecords.map((p, i) => (
              <div key={i} className={`p-4 rounded border ${
                p.status === "已支付" ? "border-slate-200 bg-slate-50" :
                p.status === "待支付" ? "border-blue-200 bg-blue-50" :
                "border-slate-200 bg-slate-50"
              }`}>
                <div className="flex items-center justify-between mb-2">
                  <div className="flex items-center gap-3">
                    <span className="text-sm text-slate-900 font-medium">{p.phase}</span>
                    <span className="text-xs font-mono text-muted-foreground">{p.ratio}</span>
                  </div>
                  <span className="text-base font-mono text-emerald-700 font-semibold">{p.amount}</span>
                </div>
                <div className="flex items-center justify-between text-xs">
                  <div className="flex items-center gap-3">
                    <span className={`px-2 py-0.5 rounded font-mono ${
                      p.status === "已支付" ? "bg-emerald-50 text-emerald-700" :
                      p.status === "待支付" ? "bg-blue-50 text-blue-700" :
                      "bg-slate-100 text-slate-500"
                    }`}>
                      {p.status}
                    </span>
                    <span className="text-muted-foreground">{p.date}</span>
                  </div>
                  <span className={`px-2 py-0.5 rounded font-mono ${
                    p.invoice === "已开票" ? "bg-slate-100 text-slate-500" : "bg-slate-50 text-slate-400"
                  }`}>
                    {p.invoice}
                  </span>
                </div>
              </div>
            ))}
          </div>
        </div>

        {/* 智能体核对 */}
        <div className="bg-card border border-slate-200 rounded-lg">
          <div className="px-5 py-4 border-b border-slate-200 flex items-center gap-2">
            <Brain size={14} className="text-blue-700" />
            <h3 className="text-sm font-semibold text-slate-900">智能体核对</h3>
          </div>
          <div className="p-5 space-y-3">
            <div className="space-y-2">
              <p className="text-xs text-muted-foreground font-mono">验收材料完整性</p>
              {[
                { item: "验收报告", status: "ok" },
                { item: "设备清单", status: "ok" },
                { item: "安装照片", status: "warn" },
                { item: "培训记录", status: "ok" },
                { item: "质保书", status: "ok" },
              ].map((c, i) => (
                <div key={i} className="flex items-center justify-between text-xs">
                  <span className="text-slate-600">{c.item}</span>
                  {c.status === "ok" ? (
                    <CheckCircle size={12} className="text-emerald-700" />
                  ) : (
                    <AlertTriangle size={12} className="text-amber-700" />
                  )}
                </div>
              ))}
            </div>

            <div className="pt-3 border-t border-slate-200 space-y-2">
              <p className="text-xs text-muted-foreground font-mono">金额核对</p>
              <div className="space-y-1.5 text-xs">
                <div className="flex justify-between">
                  <span className="text-slate-600">合同金额</span>
                  <span className="font-mono text-slate-900">¥67.8万</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-slate-600">已付 30%</span>
                  <span className="font-mono text-emerald-700">¥20.34万</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-slate-600">本次应付 60%</span>
                  <span className="font-mono text-blue-700">¥40.68万</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-slate-600">质保金 10%</span>
                  <span className="font-mono text-muted-foreground">¥6.78万</span>
                </div>
              </div>
            </div>

            <div className="pt-3 border-t border-slate-200">
              <div className="p-3 rounded bg-blue-50 border border-blue-200 text-xs">
                <p className="text-blue-700 mb-1 flex items-center gap-1">
                  <AlertTriangle size={11} />
                  <span>蓝色提醒</span>
                </p>
                <p className="text-slate-600">安装照片缺少2张，已通知供应商补传</p>
              </div>
            </div>

            <button className="w-full py-2 text-xs rounded bg-emerald-50 text-emerald-700 border border-emerald-200 hover:bg-emerald-100 transition-colors">
              核对通过，提交审批
            </button>
          </div>
        </div>
      </div>

      {/* 验收清单 */}
      <div className="bg-card border border-slate-200 rounded-lg overflow-hidden">
        <div className="px-5 py-4 border-b border-slate-200">
          <h3 className="text-sm font-semibold text-slate-900">验收清单</h3>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead>
              <tr className="border-b border-slate-200 text-muted-foreground">
                {["序号", "设备名称", "规格型号", "合同数量", "实际数量", "单价", "小计", "验收结果"].map(h => (
                  <th key={h} className="px-4 py-3 text-left font-mono font-medium">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-200">
              {[
                { no: 1, name: "车载GPS终端", model: "GPS-8800", contract: 120, actual: 120, price: "¥4,200", total: "¥504,000", result: "ok" },
                { no: 2, name: "监控平台软件", model: "V3.5企业版", contract: 1, actual: 1, price: "¥86,000", total: "¥86,000", result: "ok" },
                { no: 3, name: "服务器", model: "Dell R740", contract: 2, actual: 2, price: "¥38,000", total: "¥76,000", result: "ok" },
                { no: 4, name: "安装调试服务", model: "-", contract: 1, actual: 1, price: "¥12,000", total: "¥12,000", result: "ok" },
              ].map((item) => (
                <tr key={item.no} className="hover:bg-slate-50">
                  <td className="px-4 py-3 font-mono text-muted-foreground">{item.no}</td>
                  <td className="px-4 py-3 text-slate-900">{item.name}</td>
                  <td className="px-4 py-3 text-muted-foreground font-mono">{item.model}</td>
                  <td className="px-4 py-3 font-mono text-slate-600">{item.contract}</td>
                  <td className="px-4 py-3 font-mono text-emerald-700">{item.actual}</td>
                  <td className="px-4 py-3 font-mono text-slate-600">{item.price}</td>
                  <td className="px-4 py-3 font-mono text-slate-900 font-semibold">{item.total}</td>
                  <td className="px-4 py-3">
                    <CheckCircle size={12} className="text-emerald-700" />
                  </td>
                </tr>
              ))}
              <tr className="bg-slate-50">
                <td colSpan={6} className="px-4 py-3 text-right text-muted-foreground">合计</td>
                <td className="px-4 py-3 font-mono text-emerald-700 font-semibold">¥678,000</td>
                <td></td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}

// ── System Page ────────────────────────────────────────────────────────────
const systemUsers = [
  { id: 1, name: "李建国", dept: "综合保障部", role: "采购管理员", status: "active", lastLogin: "2026-07-14 09:15" },
  { id: 2, name: "王晓明", dept: "综合保障部", role: "合同经办人", status: "active", lastLogin: "2026-07-14 08:30" },
  { id: 3, name: "赵工程师", dept: "科信办", role: "技术审查员", status: "active", lastLogin: "2026-07-13 16:45" },
  { id: 4, name: "刘主任", dept: "审计处", role: "审计员", status: "active", lastLogin: "2026-07-13 14:20" },
  { id: 5, name: "张局长", dept: "局领导", role: "系统管理员", status: "active", lastLogin: "2026-07-12 10:05" },
  { id: 6, name: "陈科长", dept: "法制办", role: "法律审查员", status: "active", lastLogin: "2026-07-12 09:20" },
  { id: 7, name: "周工程师", dept: "网安支队", role: "技术审查员", status: "active", lastLogin: "2026-07-11 15:30" },
  { id: 8, name: "吴队长", dept: "车辆管理", role: "验收专员", status: "active", lastLogin: "2026-07-11 11:15" },
  { id: 9, name: "郑主任", dept: "技防办", role: "采购专员", status: "active", lastLogin: "2026-07-10 16:00" },
  { id: 10, name: "林主任", dept: "指挥中心", role: "采购专员", status: "active", lastLogin: "2026-07-10 10:45" },
  { id: 11, name: "孙主任", dept: "后勤处", role: "合同经办人", status: "inactive", lastLogin: "2026-06-28 11:30" },
  { id: 12, name: "钱会计", dept: "财务处", role: "财务审核员", status: "active", lastLogin: "2026-07-14 08:50" },
];

const systemLogs = [
  { time: "2026-07-14 09:23:15", user: "李建国", action: "查看", module: "合同内审", detail: "CT-2024-0094 综合业务系统运维服务合同" },
  { time: "2026-07-14 09:15:42", user: "李建国", action: "登录", module: "系统", detail: "IP: 10.12.34.56" },
  { time: "2026-07-14 08:47:33", user: "智能体引擎", action: "预警", module: "风险预警", detail: "RK-001 供应商关联关系检测" },
  { time: "2026-07-14 08:30:18", user: "王晓明", action: "提交", module: "合同内审", detail: "CT-2024-0094 提交商务审查" },
  { time: "2026-07-14 08:15:09", user: "智能体引擎", action: "审核", module: "合同内审", detail: "CT-2024-0094 三方一致性审查完成" },
  { time: "2026-07-14 08:05:23", user: "钱会计", action: "审批", module: "验收结算", detail: "ST-2024-0029 财务审核通过" },
  { time: "2026-07-13 17:22:45", user: "赵工程师", action: "审批", module: "技术审查", detail: "CT-2024-0094 技术审查通过" },
  { time: "2026-07-13 16:40:12", user: "智能体引擎", action: "预警", module: "投标评审", detail: "BD-2024-0056 投标文件雷同检测" },
  { time: "2026-07-13 15:18:34", user: "郑主任", action: "提交", module: "采购需求", detail: "PR-2024-0185 综合通信指挥系统升级" },
  { time: "2026-07-13 14:52:10", user: "智能体引擎", action: "审核", module: "采购需求", detail: "PR-2024-0187 预算合规性审查" },
  { time: "2026-07-13 11:23:45", user: "周工程师", action: "审批", module: "技术审查", detail: "PR-2024-0166 技术可行性通过" },
  { time: "2026-07-13 09:45:22", user: "吴队长", action: "提交", module: "验收结算", detail: "ST-2024-0031 提交验收报告" },
  { time: "2026-07-12 16:30:15", user: "智能体引擎", action: "预警", module: "风险预警", detail: "RK-004 控制价偏高预警" },
  { time: "2026-07-12 14:20:33", user: "林主任", action: "提交", module: "采购需求", detail: "PR-2024-0155 应急通信车辆采购" },
  { time: "2026-07-12 10:05:18", user: "张局长", action: "登录", module: "系统", detail: "IP: 10.12.34.12" },
];

// ── Model Eval Data ────────────────────────────────────────────────────────
const modelWeeklyPerf = [
  { week: "W19", accuracy: 87.2, precision: 85.1, recall: 83.4, f1: 84.2, redAcc: 91.0, falsePos: 6.8 },
  { week: "W20", accuracy: 88.4, precision: 86.9, recall: 84.7, f1: 85.8, redAcc: 92.3, falsePos: 6.1 },
  { week: "W21", accuracy: 87.9, precision: 86.2, recall: 85.1, f1: 85.6, redAcc: 91.8, falsePos: 6.4 },
  { week: "W22", accuracy: 89.1, precision: 88.0, recall: 86.3, f1: 87.1, redAcc: 93.1, falsePos: 5.7 },
  { week: "W23", accuracy: 90.3, precision: 89.4, recall: 87.8, f1: 88.6, redAcc: 94.2, falsePos: 5.2 },
  { week: "W24", accuracy: 91.0, precision: 90.1, recall: 88.5, f1: 89.3, redAcc: 94.7, falsePos: 4.9 },
  { week: "W25", accuracy: 90.6, precision: 89.8, recall: 89.1, f1: 89.4, redAcc: 94.5, falsePos: 5.1 },
  { week: "W26", accuracy: 92.1, precision: 91.3, recall: 90.2, f1: 90.7, redAcc: 95.8, falsePos: 4.3 },
];
const modulePerf = [
  { module: "采购需求审查", accuracy: 93.2, total: 412, correct: 384, drift: false },
  { module: "投标评审分析", accuracy: 91.7, total: 287, correct: 263, drift: false },
  { module: "合同条款审查", accuracy: 89.4, total: 341, correct: 305, drift: true },
  { module: "结算核价验收", accuracy: 87.8, total: 198, correct: 174, drift: false },
  { module: "风险预警分类", accuracy: 94.1, total: 1024, correct: 964, drift: false },
  { module: "供应商关联分析", accuracy: 88.6, total: 156, correct: 138, drift: true },
];
const feedbackStats = [
  { month: "2月", accepted: 142, corrected: 23, rejected: 8 },
  { month: "3月", accepted: 168, corrected: 19, rejected: 5 },
  { month: "4月", accepted: 195, corrected: 15, rejected: 7 },
  { month: "5月", accepted: 221, corrected: 12, rejected: 4 },
  { month: "6月", accepted: 238, corrected: 10, rejected: 3 },
  { month: "7月", accepted: 187, corrected: 8, rejected: 2 },
];
const modelVersions = [
  { ver: "v2.4.1", date: "2026-07-08", type: "热更新", modules: ["风险预警"], accuracy: 92.1, status: "active", notes: "修正供应商串通判断阈值" },
  { ver: "v2.4.0", date: "2026-06-15", type: "小版本", modules: ["合同审查", "投标评审"], accuracy: 91.0, status: "previous", notes: "新增三层结构语义解析" },
  { ver: "v2.3.2", date: "2026-05-20", type: "热更新", modules: ["采购需求"], accuracy: 90.3, status: "archived", notes: "预算超标检测精度优化" },
  { ver: "v2.3.0", date: "2026-04-08", type: "大版本", modules: ["全模块"], accuracy: 89.1, status: "archived", notes: "引入多模态文档解析能力" },
  { ver: "v2.2.1", date: "2026-03-01", type: "热更新", modules: ["风险预警"], accuracy: 88.4, status: "archived", notes: "红色拦截误报率下降1.2pp" },
];
const driftAlerts = [
  { module: "合同条款审查", type: "数据漂移", detail: "近30天合同模板分布偏移，新式电子合同占比从12%升至34%，建议补充训练数据", level: "yellow" as RiskLevel },
  { module: "供应商关联分析", type: "标签偏移", detail: '人工纠正中"关联关系误判"类型连续4周占比超20%，触发再训练阈值', level: "yellow" as RiskLevel },
];

// ── AI Tools Data ──────────────────────────────────────────────────────────
const officialDocTemplates = [
  { id: "notice", name: "通知", desc: "适用于上级对下级发出的指示、要求", example: "关于开展XX专项工作的通知" },
  { id: "report", name: "报告", desc: "向上级汇报工作、反映情况、提出意见", example: "关于XX项目采购工作完成情况的报告" },
  { id: "reply", name: "批复", desc: "答复下级机关请示事项", example: "关于同意XX采购方案的批复" },
  { id: "letter", name: "函", desc: "同级或不相隶属机关之间相互商洽事项", example: "关于商请提供XX材料的函" },
  { id: "minutes", name: "会议纪要", desc: "记录会议主要精神和议定事项", example: "XX采购评审委员会第X次会议纪要" },
  { id: "plan", name: "方案", desc: "针对某一行动提出具体安排的文件", example: "XX设备采购工作实施方案" },
];

const demoProofreadResult = [
  { line: 3, type: "格式问题", level: "yellow" as RiskLevel, original: "关于2024年度移动采集终端采购的报告", suggestion: "标题应使用三号仿宋字体，居中排版，不加标点符号", fixed: "关于2026年度移动采集终端采购的报告" },
  { line: 8, type: "表述不规范", level: "yellow" as RiskLevel, original: "据了解，此次采购价格比较合理", suggestion: '公文应使用肯定性表述，避免"据了解""比较"等模糊词语', fixed: "经市场调研，此次采购价格符合市场行情" },
  { line: 14, type: "合规风险", level: "red" as RiskLevel, original: "采购金额45万元，拟采用询价采购方式", suggestion: "根据《政府采购法》第26条，货物类采购超过公开招标限额标准（本地区20万元）须采用公开招标，不得采用询价采购", fixed: "采购金额45万元，依法采用公开招标方式" },
  { line: 19, type: "数据错误", level: "red" as RiskLevel, original: "预计节省资金约12%，折合人民币3.8万元", suggestion: "数字逻辑错误：45万×12%=5.4万，与3.8万不符", fixed: "预计节省资金约12%，折合人民币5.4万元" },
  { line: 23, type: "格式问题", level: "blue" as RiskLevel, original: "此致  敬礼", suggestion: '请示类公文结尾用"以上请示，请批示"，报告类用"特此报告"，不用"此致敬礼"', fixed: "特此报告。" },
];

const demoReadingDoc = `关于2026年度移动智能终端设备集中采购项目的招标公告

一、项目概况
本次招标项目名称：2026年度移动智能终端设备集中采购项目
采购单位：XX市公共服务中心
预算金额：人民币450万元整
采购方式：公开招标
采购内容：移动采集终端（200台）、移动业务终端（150台）、移动智能终端（100台）

二、投标人资格要求
1. 具有独立法人资格，注册资本不低于500万元人民币
2. 近三年（2023-2025年）具有类似项目业绩不少于2项
3. 具备国家认可机构颁发的相关产品认证资质
4. 未被列入政府采购严重违法失信行为记录名单

三、报名及购买招标文件
报名时间：2026年7月20日至7月25日，每天9:00-17:00
报名地点：XX市公共服务中心采购管理科（XX路XX号XX楼）
招标文件：每套人民币500元，售后不退

四、投标截止时间及开标时间
投标截止时间：2026年8月10日14:00
开标时间：2026年8月10日14:30
开标地点：XX市公共服务中心第三会议室`;

const demoReadingQA: { q: string; a: string }[] = [
  { q: "这个项目的预算是多少？", a: "根据招标公告，本次采购项目预算金额为人民币450万元整。" },
  { q: "投标人需要哪些资质？", a: "投标人需满足以下条件：①独立法人资格，注册资本≥500万元；②近三年（2023-2025年）类似项目业绩≥2项；③国家认可机构颁发的相关产品认证资质；④未被列入政府采购严重违法失信行为记录名单。" },
  { q: "什么时候开标？", a: "开标时间为2026年8月10日14:30，地点在XX市公共服务中心第三会议室。请注意，投标截止时间为同日14:00，须在截止时间前递交投标文件。" },
];

const demoIcons = [
  { name: "采购", emoji: "🛒", color: "bg-blue-100 border-blue-200 text-blue-700", desc: "购物车" },
  { name: "合规", emoji: "✅", color: "bg-emerald-100 border-emerald-200 text-emerald-700", desc: "校验勾" },
  { name: "预警", emoji: "⚠️", color: "bg-amber-100 border-amber-200 text-amber-700", desc: "警告标志" },
  { name: "智能体", emoji: "🤖", color: "bg-purple-100 border-purple-200 text-purple-700", desc: "机器人" },
  { name: "文档", emoji: "📄", color: "bg-blue-100 border-blue-200 text-blue-700", desc: "文件页" },
  { name: "数据库", emoji: "🗄️", color: "bg-cyan-500/20 border-cyan-500/30 text-cyan-700", desc: "数据仓库" },
  { name: "分析", emoji: "📊", color: "bg-violet-500/20 border-violet-500/30 text-violet-700", desc: "统计图表" },
  { name: "加密", emoji: "🔒", color: "bg-slate-500/20 border-slate-500/30 text-slate-400", desc: "安全锁" },
  { name: "证书", emoji: "🏅", color: "bg-amber-100 border-amber-200 text-amber-700", desc: "勋章证书" },
  { name: "合同", emoji: "📝", color: "bg-blue-100 border-blue-200 text-blue-700", desc: "合同签署" },
  { name: "风险", emoji: "🛡️", color: "bg-red-100 border-red-200 text-red-700", desc: "防护盾" },
  { name: "时钟", emoji: "⏰", color: "bg-orange-500/20 border-orange-500/30 text-orange-700", desc: "时间提醒" },
];

// ── Model Eval Page ────────────────────────────────────────────────────────
function ModelEvalPage() {
  const [activeTab, setActiveTab] = useState<"overview" | "modules" | "feedback" | "versions">("overview");
  const latest = modelWeeklyPerf[modelWeeklyPerf.length - 1];
  const prev = modelWeeklyPerf[modelWeeklyPerf.length - 2];

  return (
    <div className="p-6 space-y-4 overflow-y-auto h-full">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-base font-semibold text-slate-900">模型持续学习与效果评估看板</h2>
          <p className="text-xs text-muted-foreground mt-0.5">当前模型版本 <span className="font-mono text-blue-700">v2.4.1</span> · 最后评估 <span className="font-mono text-slate-600">2026-07-15 08:30</span></p>
        </div>
        <div className="flex items-center gap-2">
          {driftAlerts.length > 0 && (
            <span className="flex items-center gap-1.5 text-xs px-3 py-1.5 rounded border bg-amber-50 text-amber-700 border-amber-200 font-mono">
              <AlertTriangle size={11} />{driftAlerts.length} 项漂移告警
            </span>
          )}
          <button className="flex items-center gap-1.5 text-xs px-3 py-1.5 rounded bg-blue-50 text-blue-700 border border-blue-200 hover:bg-blue-100 transition-colors">
            <RefreshCw size={12} />重新评估
          </button>
        </div>
      </div>

      {/* KPI Cards */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
        {[
          { label: "整体准确率", value: `${latest.accuracy}%`, delta: +(latest.accuracy - prev.accuracy).toFixed(1), icon: <Target size={14} className="text-blue-700" />, color: "blue" },
          { label: "红色拦截精度", value: `${latest.redAcc}%`, delta: +(latest.redAcc - prev.redAcc).toFixed(1), icon: <ShieldAlert size={14} className="text-red-700" />, color: "red" },
          { label: "F1 Score", value: latest.f1.toFixed(1), delta: +(latest.f1 - prev.f1).toFixed(1), icon: <Award size={14} className="text-emerald-700" />, color: "emerald" },
          { label: "误报率", value: `${latest.falsePos}%`, delta: -(latest.falsePos - prev.falsePos).toFixed(1) as unknown as number, icon: <Percent size={14} className="text-amber-700" />, color: "amber", invert: true },
        ].map((kpi, i) => {
          const up = kpi.invert ? kpi.delta > 0 : kpi.delta > 0;
          return (
            <div key={i} className="bg-card border border-slate-200 rounded-lg p-4">
              <div className="flex items-center justify-between mb-2">
                <span className="text-xs text-muted-foreground">{kpi.label}</span>
                {kpi.icon}
              </div>
              <p className="text-2xl font-bold text-slate-900 font-mono">{kpi.value}</p>
              <div className={`flex items-center gap-1 mt-1 text-xs font-mono ${up ? "text-emerald-700" : "text-red-700"}`}>
                {up ? <TrendingUp size={11} /> : <TrendingDown size={11} />}
                {kpi.delta > 0 ? "+" : ""}{kpi.delta}pp vs 上周
              </div>
            </div>
          );
        })}
      </div>

      {/* Drift Alerts */}
      {driftAlerts.length > 0 && (
        <div className="space-y-2">
          {driftAlerts.map((a, i) => (
            <div key={i} className="flex items-start gap-3 p-3 rounded border border-amber-200 bg-amber-50 text-xs">
              <AlertTriangle size={13} className="text-amber-700 shrink-0 mt-0.5" />
              <div className="flex-1">
                <span className="font-medium text-amber-700">[{a.type}] {a.module}</span>
                <span className="text-muted-foreground ml-2">{a.detail}</span>
              </div>
              <button className="shrink-0 text-amber-700 border border-amber-200 bg-amber-50 rounded px-2 py-0.5 hover:bg-amber-100 transition-colors">
                触发再训练
              </button>
            </div>
          ))}
        </div>
      )}

      {/* Tabs */}
      <div className="flex gap-2 border-b border-slate-200">
        {[
          { id: "overview" as const, label: "整体趋势", icon: <TrendingUp size={12} /> },
          { id: "modules" as const, label: "分模块准确率", icon: <Layers size={12} /> },
          { id: "feedback" as const, label: "人工反馈循环", icon: <ThumbsUp size={12} /> },
          { id: "versions" as const, label: "版本历史", icon: <GitBranch size={12} /> },
        ].map(t => (
          <button key={t.id} onClick={() => setActiveTab(t.id)}
            className={`flex items-center gap-1.5 px-4 py-2.5 text-xs font-medium border-b-2 transition-colors ${activeTab === t.id ? "border-blue-500 text-blue-700" : "border-transparent text-muted-foreground hover:text-slate-700"}`}>
            {t.icon}{t.label}
          </button>
        ))}
      </div>

      {/* Tab: 整体趋势 */}
      {activeTab === "overview" && (
        <div className="grid grid-cols-1 xl:grid-cols-2 gap-4">
          <div className="bg-card border border-slate-200 rounded-lg p-4">
            <p className="text-xs font-semibold text-slate-900 mb-4">准确率 / F1 趋势（近8周）</p>
            <ResponsiveContainer width="100%" height={200}>
              <LineChart data={modelWeeklyPerf}>
                <XAxis dataKey="week" tick={{ fill: "#64748b", fontSize: 10 }} axisLine={false} tickLine={false} />
                <YAxis domain={[82, 96]} tick={{ fill: "#64748b", fontSize: 10 }} axisLine={false} tickLine={false} unit="%" />
                <Tooltip contentStyle={{ background: "#131a24", border: "1px solid rgba(255,255,255,0.08)", borderRadius: 8, fontSize: 11 }} />
                <Line key="accuracy" type="monotone" dataKey="accuracy" stroke="#3b82f6" strokeWidth={2} dot={false} name="准确率" />
                <Line key="f1" type="monotone" dataKey="f1" stroke="#a78bfa" strokeWidth={2} dot={false} name="F1" />
                <Line key="precision" type="monotone" dataKey="precision" stroke="#34d399" strokeWidth={1.5} strokeDasharray="4 2" dot={false} name="精确率" />
                <Line key="recall" type="monotone" dataKey="recall" stroke="#fb923c" strokeWidth={1.5} strokeDasharray="4 2" dot={false} name="召回率" />
              </LineChart>
            </ResponsiveContainer>
            <div className="flex gap-4 mt-2 justify-center">
              {[{ c: "#3b82f6", l: "准确率" }, { c: "#a78bfa", l: "F1" }, { c: "#34d399", l: "精确率" }, { c: "#fb923c", l: "召回率" }].map(x => (
                <div key={x.l} className="flex items-center gap-1.5 text-[10px] text-muted-foreground">
                  <span className="inline-block size-2 rounded-full" style={{ background: x.c }} />{x.l}
                </div>
              ))}
            </div>
          </div>
          <div className="bg-card border border-slate-200 rounded-lg p-4">
            <p className="text-xs font-semibold text-slate-900 mb-4">红色拦截精度 vs 误报率（近8周）</p>
            <ResponsiveContainer width="100%" height={200}>
              <LineChart data={modelWeeklyPerf}>
                <XAxis dataKey="week" tick={{ fill: "#64748b", fontSize: 10 }} axisLine={false} tickLine={false} />
                <YAxis tick={{ fill: "#64748b", fontSize: 10 }} axisLine={false} tickLine={false} />
                <Tooltip contentStyle={{ background: "#131a24", border: "1px solid rgba(255,255,255,0.08)", borderRadius: 8, fontSize: 11 }} />
                <Line key="redAcc" type="monotone" dataKey="redAcc" stroke="#f87171" strokeWidth={2} dot={false} name="红色拦截精度%" />
                <Line key="falsePos" type="monotone" dataKey="falsePos" stroke="#fbbf24" strokeWidth={2} dot={false} name="误报率%" />
              </LineChart>
            </ResponsiveContainer>
            <div className="flex gap-4 mt-2 justify-center">
              {[{ c: "#f87171", l: "红色拦截精度%" }, { c: "#fbbf24", l: "误报率%" }].map(x => (
                <div key={x.l} className="flex items-center gap-1.5 text-[10px] text-muted-foreground">
                  <span className="inline-block size-2 rounded-full" style={{ background: x.c }} />{x.l}
                </div>
              ))}
            </div>
          </div>
        </div>
      )}

      {/* Tab: 分模块准确率 */}
      {activeTab === "modules" && (
        <div className="space-y-3">
          {modulePerf.map((m, i) => (
            <div key={i} className={`bg-card border rounded-lg p-4 ${m.drift ? "border-amber-200" : "border-slate-200"}`}>
              <div className="flex items-center justify-between mb-2">
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium text-slate-900">{m.module}</span>
                  {m.drift && <span className="text-[10px] px-2 py-0.5 rounded bg-amber-50 text-amber-700 border border-amber-200 font-mono">数据漂移</span>}
                </div>
                <span className={`text-sm font-bold font-mono ${m.accuracy >= 92 ? "text-emerald-700" : m.accuracy >= 88 ? "text-blue-700" : "text-amber-700"}`}>{m.accuracy}%</span>
              </div>
              <div className="h-2 bg-slate-50 rounded-full overflow-hidden">
                <div className={`h-full rounded-full transition-all ${m.accuracy >= 92 ? "bg-emerald-500" : m.accuracy >= 88 ? "bg-blue-500" : "bg-amber-500"}`}
                  style={{ width: `${m.accuracy}%` }} />
              </div>
              <div className="flex items-center justify-between mt-2 text-[10px] text-muted-foreground font-mono">
                <span>评估样本 {m.total} 条 · 正确 {m.correct} 条</span>
                <span>误判 {m.total - m.correct} 条</span>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Tab: 人工反馈循环 */}
      {activeTab === "feedback" && (
        <div className="grid grid-cols-1 xl:grid-cols-2 gap-4">
          <div className="bg-card border border-slate-200 rounded-lg p-4">
            <p className="text-xs font-semibold text-slate-900 mb-4">月度人工反馈统计</p>
            <ResponsiveContainer width="100%" height={200}>
              <BarChart data={feedbackStats}>
                <XAxis dataKey="month" tick={{ fill: "#64748b", fontSize: 10 }} axisLine={false} tickLine={false} />
                <YAxis tick={{ fill: "#64748b", fontSize: 10 }} axisLine={false} tickLine={false} />
                <Tooltip contentStyle={{ background: "#131a24", border: "1px solid rgba(255,255,255,0.08)", borderRadius: 8, fontSize: 11 }} />
                <Bar dataKey="accepted" fill="#34d399" name="采纳" radius={[2, 2, 0, 0]} />
                <Bar dataKey="corrected" fill="#fbbf24" name="纠正" radius={[2, 2, 0, 0]} />
                <Bar dataKey="rejected" fill="#f87171" name="拒绝" radius={[2, 2, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
            <div className="flex gap-4 mt-2 justify-center">
              {[{ c: "#34d399", l: "采纳" }, { c: "#fbbf24", l: "纠正（反馈入库）" }, { c: "#f87171", l: "拒绝" }].map(x => (
                <div key={x.l} className="flex items-center gap-1.5 text-[10px] text-muted-foreground">
                  <span className="inline-block size-2 rounded-full" style={{ background: x.c }} />{x.l}
                </div>
              ))}
            </div>
          </div>
          <div className="bg-card border border-slate-200 rounded-lg p-4 space-y-4">
            <p className="text-xs font-semibold text-slate-900">反馈质量指标</p>
            {[
              { label: "7月累计采纳率", value: "94.1%", icon: <ThumbsUp size={12} className="text-emerald-700" />, color: "text-emerald-700" },
              { label: "累计纠正样本入库", value: "87 条", icon: <Database size={12} className="text-blue-700" />, color: "text-blue-700" },
              { label: "纠正触发再训练", value: "2 次", icon: <FlaskConical size={12} className="text-purple-700" />, color: "text-purple-700" },
              { label: "平均用户评分", value: "4.7 / 5", icon: <Award size={12} className="text-amber-700" />, color: "text-amber-700" },
            ].map((item, i) => (
              <div key={i} className="flex items-center justify-between py-2 border-b border-slate-200 last:border-0">
                <div className="flex items-center gap-2 text-xs text-muted-foreground">
                  {item.icon}{item.label}
                </div>
                <span className={`text-sm font-bold font-mono ${item.color}`}>{item.value}</span>
              </div>
            ))}
            <div className="pt-2">
              <p className="text-[10px] text-muted-foreground font-mono mb-2">反馈类型分布（7月）</p>
              <div className="space-y-1.5">
                {[
                  { type: "关联关系误判", count: 3, pct: 38 },
                  { type: "风险等级偏高", count: 2, pct: 25 },
                  { type: "条款解读偏差", count: 2, pct: 25 },
                  { type: "其他", count: 1, pct: 12 },
                ].map((t, j) => (
                  <div key={j} className="flex items-center gap-2 text-[10px]">
                    <span className="text-muted-foreground w-24 shrink-0">{t.type}</span>
                    <div className="flex-1 h-1.5 bg-slate-50 rounded-full overflow-hidden">
                      <div className="h-full bg-amber-500/60 rounded-full" style={{ width: `${t.pct}%` }} />
                    </div>
                    <span className="text-muted-foreground font-mono w-6 text-right">{t.count}</span>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Tab: 版本历史 */}
      {activeTab === "versions" && (
        <div className="bg-card border border-slate-200 rounded-lg overflow-hidden">
          <table className="w-full text-xs">
            <thead>
              <tr className="border-b border-slate-200 text-muted-foreground">
                {["版本", "发布日期", "更新类型", "涉及模块", "整体准确率", "状态", "更新说明"].map(h => (
                  <th key={h} className="px-4 py-3 text-left font-mono font-medium whitespace-nowrap">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-200">
              {modelVersions.map((v, i) => (
                <tr key={i} className={`hover:bg-slate-50 transition-colors ${v.status === "active" ? "bg-blue-50" : ""}`}>
                  <td className="px-4 py-3 font-mono font-bold text-blue-700">{v.ver}</td>
                  <td className="px-4 py-3 font-mono text-muted-foreground">{v.date}</td>
                  <td className="px-4 py-3">
                    <span className={`px-2 py-0.5 rounded text-[10px] font-mono border ${v.type === "大版本" ? "bg-purple-50 text-purple-700 border-purple-200" : v.type === "小版本" ? "bg-blue-50 text-blue-700 border-blue-200" : "bg-emerald-50 text-emerald-700 border-emerald-200"}`}>
                      {v.type}
                    </span>
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex gap-1 flex-wrap">
                      {v.modules.map(m => (
                        <span key={m} className="text-[10px] px-1.5 py-0.5 rounded bg-slate-50 text-muted-foreground border border-slate-200">{m}</span>
                      ))}
                    </div>
                  </td>
                  <td className="px-4 py-3 font-mono text-emerald-700 font-semibold">{v.accuracy}%</td>
                  <td className="px-4 py-3">
                    <span className={`px-2 py-0.5 rounded text-[10px] font-mono border ${v.status === "active" ? "bg-emerald-50 text-emerald-700 border-emerald-200" : v.status === "previous" ? "bg-blue-50 text-blue-700 border-blue-200" : "bg-slate-50 text-muted-foreground border-slate-200"}`}>
                      {v.status === "active" ? "⬤ 当前" : v.status === "previous" ? "上一版" : "已归档"}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-slate-600">{v.notes}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

// ── AI Tools Page ──────────────────────────────────────────────────────────
function AIToolsPage() {
  const [activeTool, setActiveTool] = useState<"proofread" | "writing" | "reading" | "icons">("proofread");
  const [proofreadInput, setProofreadInput] = useState("关于2024年度移动采集终端采购的报告\n\n尊敬的领导：\n\n据了解，此次采购价格比较合理。采购金额45万元，拟采用询价采购方式。预计节省资金约12%，折合人民币3.8万元。\n\n此致  敬礼");
  const [proofreadDone, setProofreadDone] = useState(false);
  const [proofreadLoading, setProofreadLoading] = useState(false);
  const [selectedTemplate, setSelectedTemplate] = useState("report");
  const [writingForm, setWritingForm] = useState({ title: "", subject: "", dept: "", content: "" });
  const [writingOutput, setWritingOutput] = useState("");
  const [writingLoading, setWritingLoading] = useState(false);
  const [readingInput, setReadingInput] = useState(demoReadingDoc);
  const [readingQ, setReadingQ] = useState("");
  const [readingHistory, setReadingHistory] = useState<{ q: string; a: string }[]>([]);
  const [readingLoading, setReadingLoading] = useState(false);
  const [iconPrompt, setIconPrompt] = useState("");
  const [iconResults, setIconResults] = useState(demoIcons.slice(0, 6));
  const [iconLoading, setIconLoading] = useState(false);
  const [selectedIcon, setSelectedIcon] = useState<number | null>(null);

  function runProofread() {
    setProofreadLoading(true);
    setTimeout(() => { setProofreadDone(true); setProofreadLoading(false); }, 1200);
  }

  function runWriting() {
    setWritingLoading(true);
    const tpl = officialDocTemplates.find(t => t.id === selectedTemplate);
    setTimeout(() => {
      setWritingOutput(`${writingForm.dept || "XX单位"}${tpl?.name || "通知"}\n\n${writingForm.title || "关于开展专项工作的通知"}\n\n各相关部门：\n\n${writingForm.content || "根据上级指示精神，结合本单位实际情况，现就有关事项通知如下：\n\n一、工作目标\n认真贯彻落实上级部署要求，扎实推进各项工作任务，确保高质量完成年度目标。\n\n二、工作措施\n（一）加强组织领导，明确责任分工。\n（二）强化过程管控，确保规范运行。\n（三）严格督导检查，做好总结提炼。\n\n三、工作要求\n请各部门高度重视，认真组织实施，如有疑问及时报告。\n\n特此通知。"}\n\n${writingForm.dept || "XX采购管理科"}\n2026年7月15日`);
      setWritingLoading(false);
    }, 1500);
  }

  function sendReadingQ() {
    if (!readingQ.trim()) return;
    const q = readingQ;
    setReadingQ("");
    setReadingLoading(true);
    const preset = demoReadingQA.find(x => q.includes(x.q.slice(0, 6)));
    setTimeout(() => {
      setReadingHistory(prev => [...prev, { q, a: preset?.a ?? "根据文档内容分析，该问题涉及的关键信息已在文档中明确说明。请参考文档相关条款进行查阅，如需进一步解释请继续提问。" }]);
      setReadingLoading(false);
    }, 900);
  }

  function genIcons() {
    setIconLoading(true);
    setTimeout(() => {
      setIconResults(demoIcons.sort(() => Math.random() - 0.5).slice(0, 9));
      setIconLoading(false);
    }, 1000);
  }

  const tools = [
    { id: "proofread" as const, label: "智能核稿", icon: <Eye size={13} />, desc: "公文合规性与格式审查" },
    { id: "writing" as const, label: "公文写作", icon: <PenLine size={13} />, desc: "公文模板智能生成" },
    { id: "reading" as const, label: "阅读理解", icon: <BookOpen size={13} />, desc: "文档问答与信息提取" },
    { id: "icons" as const, label: "图标生成", icon: <ImageIcon size={13} />, desc: "报告配图快速生成" },
  ];

  return (
    <div className="h-full flex min-h-0 overflow-hidden">
      {/* Tool Sidebar */}
      <div className="w-52 shrink-0 border-r border-slate-200 flex flex-col bg-sidebar">
        <div className="px-4 py-3 border-b border-slate-200">
          <p className="text-xs font-semibold text-slate-900">AI 扩展工具</p>
          <p className="text-[10px] text-muted-foreground mt-0.5">4 项智能辅助功能</p>
        </div>
        <div className="flex-1 p-2 space-y-1">
          {tools.map(t => (
            <button key={t.id} onClick={() => setActiveTool(t.id)}
              className={`w-full flex items-start gap-3 px-3 py-3 rounded-lg text-left transition-colors ${activeTool === t.id ? "bg-blue-50 border border-blue-200" : "hover:bg-slate-50 border border-transparent"}`}>
              <span className={`mt-0.5 ${activeTool === t.id ? "text-blue-700" : "text-muted-foreground"}`}>{t.icon}</span>
              <div>
                <p className={`text-xs font-medium ${activeTool === t.id ? "text-blue-700" : "text-slate-900"}`}>{t.label}</p>
                <p className="text-[10px] text-muted-foreground mt-0.5">{t.desc}</p>
              </div>
            </button>
          ))}
        </div>
        <div className="px-4 py-3 border-t border-slate-200">
          <p className="text-[10px] text-muted-foreground">模型：GPT-4o + 本地知识库</p>
          <div className="flex items-center gap-1.5 mt-1">
            <span className="size-1.5 rounded-full bg-emerald-400" />
            <span className="text-[10px] text-emerald-700">服务正常</span>
          </div>
        </div>
      </div>

      {/* Tool Content */}
      <div className="flex-1 flex flex-col min-w-0 min-h-0 overflow-hidden">

        {/* ── 智能核稿 ── */}
        {activeTool === "proofread" && (
          <div className="flex-1 flex min-h-0 overflow-hidden">
            <div className="flex-1 flex flex-col border-r border-slate-200">
              <div className="px-5 py-3 border-b border-slate-200 flex items-center justify-between shrink-0">
                <div>
                  <p className="text-sm font-semibold text-slate-900">智能核稿</p>
                  <p className="text-[10px] text-muted-foreground mt-0.5">检测格式规范、表述合规性、数据一致性</p>
                </div>
                <div className="flex gap-2">
                  <button onClick={() => { setProofreadDone(false); setProofreadInput(""); }} className="text-xs px-3 py-1.5 rounded border border-slate-200 text-muted-foreground hover:text-slate-700 hover:border-slate-300 transition-colors">清空</button>
                  <button onClick={runProofread} disabled={proofreadLoading || !proofreadInput.trim()}
                    className="flex items-center gap-1.5 text-xs px-4 py-1.5 rounded bg-blue-500 hover:bg-blue-400 disabled:opacity-50 text-white transition-colors">
                    {proofreadLoading ? <RefreshCw size={12} className="animate-spin" /> : <Sparkles size={12} />}
                    {proofreadLoading ? "审查中…" : "开始核稿"}
                  </button>
                </div>
              </div>
              <textarea
                value={proofreadInput}
                onChange={e => { setProofreadInput(e.target.value); setProofreadDone(false); }}
                placeholder="粘贴公文正文…"
                className="flex-1 bg-transparent text-sm text-slate-600 p-5 resize-none focus:outline-none font-mono leading-relaxed placeholder:text-muted-foreground"
              />
            </div>
            <div className="w-80 shrink-0 flex flex-col overflow-hidden">
              <div className="px-4 py-3 border-b border-slate-200 shrink-0">
                <p className="text-xs font-semibold text-slate-900">核稿结果</p>
                {proofreadDone && <p className="text-[10px] text-muted-foreground mt-0.5">发现 {demoProofreadResult.length} 处问题</p>}
              </div>
              <div className="flex-1 overflow-y-auto p-4 space-y-3">
                {!proofreadDone && !proofreadLoading && (
                  <div className="flex flex-col items-center justify-center h-32 text-center text-xs text-muted-foreground gap-2">
                    <Eye size={24} className="text-slate-400" />
                    <p>点击"开始核稿"后<br />AI将检查格式与合规性</p>
                  </div>
                )}
                {proofreadLoading && (
                  <div className="flex flex-col items-center justify-center h-32 text-center text-xs text-muted-foreground gap-2">
                    <RefreshCw size={20} className="animate-spin text-blue-700" />
                    <p>AI正在分析文档…</p>
                  </div>
                )}
                {proofreadDone && demoProofreadResult.map((r, i) => (
                  <div key={i} className={`p-3 rounded border text-xs ${r.level === "red" ? "border-red-200 bg-red-50" : r.level === "yellow" ? "border-amber-200 bg-amber-500/4" : "border-blue-200 bg-blue-50"}`}>
                    <div className="flex items-center gap-1.5 mb-1.5">
                      <RiskBadge level={r.level} compact />
                      <span className="font-medium text-slate-900">{r.type}</span>
                      <span className="text-muted-foreground font-mono ml-auto">第{r.line}行</span>
                    </div>
                    <p className="text-muted-foreground line-through text-[10px] mb-1">{r.original}</p>
                    <p className="text-slate-600 text-[10px] mb-1.5">↳ {r.suggestion}</p>
                    <div className="flex items-start gap-1.5 p-1.5 rounded bg-emerald-50 border border-emerald-200">
                      <CheckCircle size={9} className="text-emerald-700 shrink-0 mt-0.5" />
                      <p className="text-emerald-700 text-[10px]">{r.fixed}</p>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          </div>
        )}

        {/* ── 公文写作 ── */}
        {activeTool === "writing" && (
          <div className="flex-1 flex min-h-0 overflow-hidden">
            <div className="w-72 shrink-0 border-r border-slate-200 flex flex-col overflow-hidden">
              <div className="px-4 py-3 border-b border-slate-200 shrink-0">
                <p className="text-xs font-semibold text-slate-900">选择公文类型</p>
              </div>
              <div className="flex-1 overflow-y-auto p-3 space-y-1.5">
                {officialDocTemplates.map(t => (
                  <button key={t.id} onClick={() => setSelectedTemplate(t.id)}
                    className={`w-full text-left p-3 rounded border transition-colors ${selectedTemplate === t.id ? "border-blue-200 bg-blue-50" : "border-slate-200 bg-slate-50 hover:bg-slate-50"}`}>
                    <p className={`text-xs font-semibold ${selectedTemplate === t.id ? "text-blue-700" : "text-slate-900"}`}>{t.name}</p>
                    <p className="text-[10px] text-muted-foreground mt-0.5">{t.desc}</p>
                    <p className="text-[10px] text-blue-700 mt-1 truncate">例：{t.example}</p>
                  </button>
                ))}
              </div>
            </div>
            <div className="flex-1 flex flex-col min-w-0 overflow-hidden border-r border-slate-200">
              <div className="px-5 py-3 border-b border-slate-200 shrink-0 flex items-center justify-between">
                <p className="text-xs font-semibold text-slate-900">填写基本信息</p>
                <button onClick={runWriting} disabled={writingLoading}
                  className="flex items-center gap-1.5 text-xs px-4 py-1.5 rounded bg-blue-500 hover:bg-blue-400 disabled:opacity-50 text-white transition-colors">
                  {writingLoading ? <RefreshCw size={12} className="animate-spin" /> : <Wand2 size={12} />}
                  {writingLoading ? "生成中…" : "AI生成"}
                </button>
              </div>
              <div className="flex-1 overflow-y-auto p-5 space-y-4">
                {[
                  { key: "title" as const, label: "文件标题", placeholder: "例：关于开展2026年设备采购专项检查的通知" },
                  { key: "dept" as const, label: "发文机关", placeholder: "例：XX市公共服务中心采购管理科" },
                  { key: "subject" as const, label: "主题词", placeholder: "例：采购  检查  通知" },
                ].map(f => (
                  <div key={f.key}>
                    <label className="text-xs text-muted-foreground mb-1.5 block">{f.label}</label>
                    <input
                      value={writingForm[f.key]}
                      onChange={e => setWritingForm(prev => ({ ...prev, [f.key]: e.target.value }))}
                      placeholder={f.placeholder}
                      className="w-full bg-card border border-slate-200 rounded px-3 py-2 text-sm text-slate-900 placeholder:text-muted-foreground focus:outline-none focus:border-blue-200 transition-colors"
                    />
                  </div>
                ))}
                <div>
                  <label className="text-xs text-muted-foreground mb-1.5 block">主要内容要点</label>
                  <textarea
                    value={writingForm.content}
                    onChange={e => setWritingForm(prev => ({ ...prev, content: e.target.value }))}
                    placeholder="简述文件要表达的主要内容和关键要点，AI将据此生成完整公文…"
                    rows={5}
                    className="w-full bg-card border border-slate-200 rounded px-3 py-2 text-sm text-slate-900 placeholder:text-muted-foreground focus:outline-none focus:border-blue-200 transition-colors resize-none"
                  />
                </div>
              </div>
            </div>
            <div className="flex-1 flex flex-col min-w-0 overflow-hidden">
              <div className="px-4 py-3 border-b border-slate-200 shrink-0 flex items-center justify-between">
                <p className="text-xs font-semibold text-slate-900">生成结果</p>
                {writingOutput && (
                  <button className="flex items-center gap-1.5 text-xs px-3 py-1 rounded border border-slate-200 text-muted-foreground hover:text-slate-700 transition-colors">
                    <Download size={11} />导出
                  </button>
                )}
              </div>
              <div className="flex-1 overflow-y-auto p-5">
                {writingLoading ? (
                  <div className="flex flex-col items-center justify-center h-32 gap-2 text-xs text-muted-foreground">
                    <RefreshCw size={20} className="animate-spin text-blue-700" />AI正在生成公文…
                  </div>
                ) : writingOutput ? (
                  <pre className="text-sm text-slate-600 whitespace-pre-wrap leading-relaxed font-sans">{writingOutput}</pre>
                ) : (
                  <div className="flex flex-col items-center justify-center h-32 gap-2 text-xs text-muted-foreground">
                    <PenLine size={24} className="text-slate-400" />
                    <p className="text-center">填写信息后点击<br />"AI生成"自动撰写公文</p>
                  </div>
                )}
              </div>
            </div>
          </div>
        )}

        {/* ── 阅读理解 ── */}
        {activeTool === "reading" && (
          <div className="flex-1 flex min-h-0 overflow-hidden">
            <div className="w-96 shrink-0 border-r border-slate-200 flex flex-col overflow-hidden">
              <div className="px-4 py-3 border-b border-slate-200 shrink-0 flex items-center justify-between">
                <p className="text-xs font-semibold text-slate-900">文档内容</p>
                <span className="text-[10px] text-muted-foreground font-mono">{readingInput.length} 字</span>
              </div>
              <textarea
                value={readingInput}
                onChange={e => setReadingInput(e.target.value)}
                placeholder="粘贴要分析的文档内容…"
                className="flex-1 bg-transparent text-xs text-slate-600 p-4 resize-none focus:outline-none font-mono leading-relaxed placeholder:text-muted-foreground"
              />
            </div>
            <div className="flex-1 flex flex-col min-w-0 overflow-hidden">
              <div className="px-5 py-3 border-b border-slate-200 shrink-0">
                <p className="text-xs font-semibold text-slate-900">智能问答</p>
                <p className="text-[10px] text-muted-foreground mt-0.5">基于左侧文档内容进行理解与提取</p>
              </div>
              <div className="flex-1 overflow-y-auto p-5 space-y-4">
                {readingHistory.length === 0 && !readingLoading && (
                  <div className="space-y-2">
                    <p className="text-[10px] text-muted-foreground">快捷问题：</p>
                    {demoReadingQA.map((qa, i) => (
                      <button key={i} onClick={() => { setReadingQ(qa.q); }}
                        className="w-full text-left text-xs px-3 py-2 rounded border border-slate-200 bg-slate-50 text-slate-600 hover:bg-slate-50 hover:border-slate-200 transition-colors">
                        {qa.q}
                      </button>
                    ))}
                  </div>
                )}
                {readingHistory.map((item, i) => (
                  <div key={i} className="space-y-2">
                    <div className="flex gap-2">
                      <div className="size-6 rounded-full bg-blue-100 text-blue-700 border border-blue-200 flex items-center justify-center text-[10px] font-semibold shrink-0">我</div>
                      <div className="flex-1 px-3 py-2 rounded-lg bg-blue-50 border border-blue-200 text-xs text-slate-900">{item.q}</div>
                    </div>
                    <div className="flex gap-2">
                      <div className="size-6 rounded-full bg-purple-100 text-purple-700 border border-purple-200 flex items-center justify-center shrink-0"><Bot size={12} /></div>
                      <div className="flex-1 px-3 py-2 rounded-lg bg-card border border-slate-200 text-xs text-slate-600 leading-relaxed">{item.a}</div>
                    </div>
                  </div>
                ))}
                {readingLoading && (
                  <div className="flex gap-2">
                    <div className="size-6 rounded-full bg-purple-100 text-purple-700 border border-purple-200 flex items-center justify-center shrink-0"><Bot size={12} /></div>
                    <div className="px-3 py-2 rounded-lg bg-card border border-slate-200 text-xs text-muted-foreground flex items-center gap-2">
                      <RefreshCw size={11} className="animate-spin" />分析中…
                    </div>
                  </div>
                )}
              </div>
              <div className="px-5 py-4 border-t border-slate-200 shrink-0">
                <div className="flex gap-2 items-center bg-card border border-slate-200 rounded-lg px-4 py-2.5 focus-within:border-blue-200 transition-colors">
                  <input
                    value={readingQ}
                    onChange={e => setReadingQ(e.target.value)}
                    onKeyDown={e => { if (e.key === "Enter") sendReadingQ(); }}
                    placeholder="针对文档提问…例如：投标截止时间是什么时候？"
                    className="flex-1 bg-transparent text-sm text-slate-900 placeholder:text-muted-foreground focus:outline-none"
                  />
                  <button onClick={sendReadingQ} disabled={readingLoading || !readingQ.trim()}
                    className="size-7 rounded bg-blue-500 hover:bg-blue-400 disabled:opacity-50 flex items-center justify-center transition-colors">
                    <Send size={12} className="text-white" />
                  </button>
                </div>
              </div>
            </div>
          </div>
        )}

        {/* ── 图标生成 ── */}
        {activeTool === "icons" && (
          <div className="flex-1 flex flex-col overflow-hidden">
            <div className="px-5 py-3 border-b border-slate-200 shrink-0 flex items-center gap-3">
              <input
                value={iconPrompt}
                onChange={e => setIconPrompt(e.target.value)}
                placeholder="描述你需要的图标风格或主题…例如：数字科技感图标"
                className="flex-1 bg-card border border-slate-200 rounded px-4 py-2 text-sm text-slate-900 placeholder:text-muted-foreground focus:outline-none focus:border-blue-200 transition-colors"
              />
              <button onClick={genIcons} disabled={iconLoading}
                className="flex items-center gap-1.5 text-xs px-5 py-2 rounded bg-blue-500 hover:bg-blue-400 disabled:opacity-50 text-white transition-colors shrink-0">
                {iconLoading ? <RefreshCw size={12} className="animate-spin" /> : <Sparkles size={12} />}
                {iconLoading ? "生成中…" : "生成图标"}
              </button>
            </div>
            <div className="flex-1 overflow-y-auto p-6">
              {selectedIcon !== null && (
                <div className="mb-4 p-3 rounded border border-blue-200 bg-blue-50 flex items-center gap-3 text-xs">
                  <span className="text-3xl">{iconResults[selectedIcon].emoji}</span>
                  <div>
                    <p className="font-semibold text-blue-700">{iconResults[selectedIcon].name} — {iconResults[selectedIcon].desc}</p>
                    <p className="text-muted-foreground mt-0.5">已选中 · 可复制到报告或导出为PNG/SVG</p>
                  </div>
                  <div className="ml-auto flex gap-2">
                    <button className="px-3 py-1.5 rounded border border-slate-200 text-muted-foreground hover:text-slate-700 transition-colors">复制</button>
                    <button className="px-3 py-1.5 rounded bg-blue-50 text-blue-700 border border-blue-200 hover:bg-blue-100 transition-colors flex items-center gap-1"><Download size={11} />导出</button>
                  </div>
                </div>
              )}
              <div className="grid grid-cols-3 sm:grid-cols-4 lg:grid-cols-6 gap-3">
                {iconResults.map((icon, i) => (
                  <button key={i} onClick={() => setSelectedIcon(i === selectedIcon ? null : i)}
                    className={`flex flex-col items-center gap-2 p-4 rounded-xl border transition-all ${i === selectedIcon ? "border-blue-200 bg-blue-50 scale-105" : `${icon.color} hover:scale-102`}`}>
                    <span className="text-3xl">{icon.emoji}</span>
                    <p className="text-[10px] font-medium text-slate-900">{icon.name}</p>
                    <p className="text-[9px] text-muted-foreground">{icon.desc}</p>
                  </button>
                ))}
              </div>
              <div className="mt-4 flex gap-2 flex-wrap">
                <p className="text-xs text-muted-foreground w-full mb-1">快速筛选：</p>
                {["采购流程", "风险预警", "合规审查", "数据分析", "文件管理", "通信安全"].map(tag => (
                  <button key={tag} onClick={genIcons}
                    className="text-xs px-3 py-1 rounded border border-slate-200 text-muted-foreground hover:text-slate-700 hover:border-slate-300 transition-colors">
                    {tag}
                  </button>
                ))}
              </div>
            </div>
          </div>
        )}

      </div>
    </div>
  );
}

// ── Role & Permission Data ─────────────────────────────────────────────────
const roleDefinitions = [
  {
    id: "superadmin", name: "超级管理员", color: "text-red-700 bg-red-50 border-red-200",
    desc: "系统最高权限，可配置角色、用户及全局参数",
    users: ["系统管理员"],
    modules: {
      "采购需求": ["查看", "审核", "审批", "配置"],
      "投标评审": ["查看", "审核", "审批", "配置"],
      "合同内审": ["查看", "审核", "审批", "配置"],
      "验收结算": ["查看", "审核", "审批", "配置"],
      "风险预警": ["查看", "处置", "配置"],
      "智能体": ["查看", "使用", "配置"],
      "系统管理": ["全部"],
    },
  },
  {
    id: "audit_leader", name: "审计主任", color: "text-orange-700 bg-orange-500/10 border-orange-500/25",
    desc: "负责最终审批，可查看全部模块，对红色风险有一票否决权",
    users: ["王主任"],
    modules: {
      "采购需求": ["查看", "审批"],
      "投标评审": ["查看", "审批"],
      "合同内审": ["查看", "审批"],
      "验收结算": ["查看", "审批"],
      "风险预警": ["查看", "处置"],
      "智能体": ["查看", "使用"],
      "系统管理": ["查看"],
    },
  },
  {
    id: "procurement_mgr", name: "采购管理员", color: "text-blue-700 bg-blue-50 border-blue-200",
    desc: "负责采购全流程操作与提交，不可审批自己发起的项目",
    users: ["李建国", "张队长"],
    modules: {
      "采购需求": ["查看", "新建", "编辑", "提交"],
      "投标评审": ["查看"],
      "合同内审": ["查看", "上传"],
      "验收结算": ["查看", "提交"],
      "风险预警": ["查看"],
      "智能体": ["查看", "使用"],
      "系统管理": [],
    },
  },
  {
    id: "contract_reviewer", name: "合同审查员", color: "text-purple-700 bg-purple-50 border-purple-200",
    desc: "专职合同条款审查，可触发AI辅助核稿与三层结构分析",
    users: ["赵律师", "孙科长"],
    modules: {
      "采购需求": ["查看"],
      "投标评审": ["查看"],
      "合同内审": ["查看", "审核", "批注"],
      "验收结算": ["查看"],
      "风险预警": ["查看"],
      "智能体": ["查看", "使用"],
      "系统管理": [],
    },
  },
  {
    id: "finance", name: "财务审核员", color: "text-emerald-700 bg-emerald-50 border-emerald-200",
    desc: "负责预算与结算金额审核，可查看价格数据，不可修改评分",
    users: ["刘会计", "陈主任"],
    modules: {
      "采购需求": ["查看", "预算审核"],
      "投标评审": ["查看"],
      "合同内审": ["查看"],
      "验收结算": ["查看", "审核"],
      "风险预警": ["查看"],
      "智能体": ["查看"],
      "系统管理": [],
    },
  },
  {
    id: "readonly", name: "只读观察员", color: "text-slate-400 bg-slate-500/10 border-slate-500/25",
    desc: "纪委、监察等外部监督角色，只读访问，不可操作任何流程",
    users: ["监察委员1", "监察委员2"],
    modules: {
      "采购需求": ["查看"],
      "投标评审": ["查看"],
      "合同内审": ["查看"],
      "验收结算": ["查看"],
      "风险预警": ["查看"],
      "智能体": [],
      "系统管理": [],
    },
  },
];

const permissionMatrix = ["采购需求", "投标评审", "合同内审", "验收结算", "风险预警", "智能体", "系统管理"];

// ── Warning Workflow Data ──────────────────────────────────────────────────
const warningLevels = [
  {
    level: "red", label: "红色拦截", icon: "⛔", color: "red",
    trigger: "AI检出高置信度违规，系统自动拦截，流程强制暂停",
    threshold: "违规置信度 ≥ 85% 或触发一票否决规则",
    examples: ["合同价格偏离中标价 >5%", "发现串标关键证据", "资质造假检出", "审批人与供应商存在关联"],
    autoActions: ["立即冻结当前流程节点", "向审计主任和系统管理员推送告警", "记录证据链快照至区块链存证", "生成红色拦截报告"],
    manualActions: ["审计主任审阅证据后可解除或升级", "解除须填写书面理由并留存记录", "升级可转交纪委或上级部门"],
    sla: "4小时内必须响应",
    escalation: "超时自动升级至局长室",
  },
  {
    level: "yellow", label: "黄色提示", icon: "⚠️", color: "amber",
    trigger: "AI发现疑点但置信度不足，提示人工复核，流程可继续但需留痕",
    threshold: "违规置信度 40%–85% 或触发软规则",
    examples: ["投标文件局部相似 40–60%", "专家打分偏差超均值", "预算超配置标准 10–20%", "合同条款与招标文件存在非实质性偏差"],
    autoActions: ["在流程节点插入黄色复核标记", "向经办人和直属领导推送提示", "触发AI辅助分析并附建议", "要求复核人签字留痕"],
    manualActions: ["经办人可选择接受或提交说明", "直属领导确认后流程继续", "如升级为红色需重走审批流"],
    sla: "24小时内必须处置",
    escalation: "超时自动提醒，48小时升级为红色",
  },
  {
    level: "blue", label: "蓝色提醒", icon: "ℹ️", color: "blue",
    trigger: "低风险信息提示，不影响流程，供参考改进",
    threshold: "置信度 <40% 或纯信息性规则",
    examples: ["历史供应商合同履约较差", "采购周期偏长于同类项目", "文档格式不规范", "技术参数表述模糊"],
    autoActions: ["在详情页边栏显示提示卡片", "生成可选改进建议", "计入项目风险评分"],
    manualActions: ["经办人可选择忽略或采纳建议", "忽略原因可选填"],
    sla: "无强制处置时限",
    escalation: "无",
  },
];

const workflowNodes = [
  { stage: "采购需求", checks: ["预算合规性", "配置标准符合性", "需求描述规范性"], redTriggers: ["超标 >20%", "禁止采购品目"], yellowTriggers: ["超标 5–20%", "表述模糊"] },
  { stage: "招标文件", checks: ["歧视性条款", "技术参数倾向性", "评分标准合法性"], redTriggers: ["指向性条款", "量身定制指标"], yellowTriggers: ["参数偏严", "评分权重异常"] },
  { stage: "开标评审", checks: ["串标关联分析", "专家打分偏差", "价格异常"], redTriggers: ["串标证据", "价格围标"], yellowTriggers: ["打分偏差 >25%", "价格低于成本估算"] },
  { stage: "合同审查", checks: ["三层结构完整性", "实质性偏差", "签订前校验"], redTriggers: ["价格偏离 >5%", "关键条款缺失"], yellowTriggers: ["非实质偏差", "条款不利于甲方"] },
  { stage: "验收结算", checks: ["核价准确性", "实物与合同一致性", "付款合规"], redTriggers: ["超合同额付款", "虚开发票"], yellowTriggers: ["核价偏差 >3%", "交付内容不符"] },
];

function SystemPage() {
  const { user } = useAuth();
  const [activeTab, setActiveTab] = useState<"users" | "logs" | "config" | "roles" | "workflow">("users");
  const [expandedRole, setExpandedRole] = useState<string | null>("procurement_mgr");
  const [expandedWarning, setExpandedWarning] = useState<string | null>("red");
  const [auditRecords, setAuditRecords] = useState<OperationAuditRecord[]>([]);
  const [auditLoading, setAuditLoading] = useState(false);
  const [auditError, setAuditError] = useState<string | null>(null);
  const [auditFilters, setAuditFilters] = useState({ userId: "", action: "", resourceType: "", from: "", to: "" });

  const loadAuditRecords = async () => {
    if (!user?.roles.includes("SYSTEM_ADMIN")) return;
    setAuditLoading(true);
    setAuditError(null);
    try {
      setAuditRecords(await listOperationAudits({
        userId: auditFilters.userId.trim() || undefined,
        action: auditFilters.action.trim() || undefined,
        resourceType: auditFilters.resourceType || undefined,
        from: auditFilters.from ? new Date(auditFilters.from).toISOString() : undefined,
        to: auditFilters.to ? new Date(auditFilters.to).toISOString() : undefined,
      }));
    } catch (error) {
      setAuditError(userFacingError(error, "审计日志加载失败，请稍后重试。"));
    } finally {
      setAuditLoading(false);
    }
  };

  useEffect(() => {
    if (activeTab === "logs") void loadAuditRecords();
  }, [activeTab]);

  return (
    <div className="p-6 space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-base font-semibold text-slate-900">系统管理</h2>
        {activeTab === "users" && (
          <button className="flex items-center gap-1.5 text-xs px-3 py-2 rounded bg-blue-50 text-blue-700 border border-blue-200 hover:bg-blue-100 transition-colors">
            <Plus size={13} />添加用户
          </button>
        )}
      </div>

      {/* Tabs */}
      <div className="flex gap-2 border-b border-slate-200">
        {[
          { id: "users" as const, label: "用户管理", icon: <Users size={13} /> },
          { id: "roles" as const, label: "角色与权限", icon: <ShieldAlert size={13} /> },
          { id: "workflow" as const, label: "三级预警联动", icon: <Zap size={13} /> },
          { id: "logs" as const, label: "操作日志", icon: <Activity size={13} /> },
          { id: "config" as const, label: "系统配置", icon: <Settings size={13} /> },
        ].map(tab => (
          <button
            key={tab.id}
            onClick={() => setActiveTab(tab.id)}
            className={`flex items-center gap-2 px-4 py-2.5 text-xs font-medium transition-colors border-b-2 ${
              activeTab === tab.id
                ? "border-blue-500 text-blue-700"
                : "border-transparent text-muted-foreground hover:text-slate-700"
            }`}
          >
            {tab.icon}
            {tab.label}
          </button>
        ))}
      </div>

      {/* Users tab */}
      {activeTab === "users" && (
        <div className="bg-card border border-slate-200 rounded-lg overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr className="border-b border-slate-200 text-muted-foreground">
                  {["ID", "姓名", "部门", "角色", "状态", "最后登录", "操作"].map(h => (
                    <th key={h} className="px-4 py-3 text-left font-mono font-medium whitespace-nowrap">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-200">
                {systemUsers.map(u => (
                  <tr key={u.id} className="hover:bg-slate-50 transition-colors">
                    <td className="px-4 py-3 font-mono text-muted-foreground">{u.id}</td>
                    <td className="px-4 py-3 text-slate-900 font-medium">{u.name}</td>
                    <td className="px-4 py-3 text-muted-foreground">{u.dept}</td>
                    <td className="px-4 py-3">
                      <span className="px-2 py-0.5 rounded bg-blue-50 text-blue-700 border border-blue-200 font-mono text-[10px]">
                        {u.role}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className={`px-2 py-0.5 rounded font-mono text-[10px] ${
                        u.status === "active"
                          ? "bg-emerald-50 text-emerald-700 border border-emerald-200"
                          : "bg-slate-100 text-slate-500 border border-slate-200"
                      }`}>
                        {u.status === "active" ? "正常" : "停用"}
                      </span>
                    </td>
                    <td className="px-4 py-3 font-mono text-muted-foreground whitespace-nowrap">{u.lastLogin}</td>
                    <td className="px-4 py-3">
                      <div className="flex gap-2">
                        <button className="text-blue-700 hover:text-blue-700 transition-colors">编辑</button>
                        <button className="text-muted-foreground hover:text-slate-600 transition-colors">权限</button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Roles tab */}
      {activeTab === "roles" && (
        <div className="space-y-4">
          {/* Permission matrix header */}
          <div className="bg-card border border-slate-200 rounded-lg overflow-hidden">
            <div className="px-4 py-3 border-b border-slate-200 flex items-center gap-2">
              <ShieldAlert size={13} className="text-blue-700" />
              <span className="text-xs font-semibold text-slate-900">权限矩阵总览</span>
              <span className="text-[10px] text-muted-foreground ml-1">— 6 角色 × 7 模块</span>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-xs">
                <thead>
                  <tr className="border-b border-slate-200">
                    <th className="px-4 py-2.5 text-left text-muted-foreground font-mono font-medium w-36">角色</th>
                    {permissionMatrix.map(m => (
                      <th key={m} className="px-3 py-2.5 text-center text-muted-foreground font-mono font-medium whitespace-nowrap">{m}</th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-200">
                  {roleDefinitions.map(role => (
                    <tr key={role.id} className="hover:bg-slate-50 transition-colors">
                      <td className="px-4 py-2.5">
                        <span className={`px-2 py-0.5 rounded border text-[10px] font-mono ${role.color}`}>{role.name}</span>
                      </td>
                      {permissionMatrix.map(m => {
                        const perms = role.modules[m as keyof typeof role.modules] ?? [];
                        const hasAll = perms.includes("全部");
                        const hasWrite = perms.some(p => ["审核", "审批", "新建", "编辑", "提交", "上传", "批注", "处置", "配置", "预算审核"].includes(p));
                        const hasRead = perms.includes("查看");
                        return (
                          <td key={m} className="px-3 py-2.5 text-center">
                            {hasAll ? (
                              <span className="inline-flex items-center justify-center size-5 rounded-full bg-red-100 text-red-700 text-[10px]">✦</span>
                            ) : hasWrite ? (
                              <span className="inline-flex items-center justify-center size-5 rounded-full bg-emerald-100 text-emerald-700 text-[10px]">✓</span>
                            ) : hasRead ? (
                              <span className="inline-flex items-center justify-center size-5 rounded-full bg-blue-50 text-blue-700 text-[9px]">R</span>
                            ) : (
                              <span className="inline-flex items-center justify-center size-5 rounded-full bg-slate-50 text-slate-400 text-[10px]">—</span>
                            )}
                          </td>
                        );
                      })}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="px-4 py-2.5 border-t border-slate-200 flex gap-4">
              {[
                { icon: "✦", color: "text-red-700 bg-red-100", label: "全部权限" },
                { icon: "✓", color: "text-emerald-700 bg-emerald-100", label: "读写权限" },
                { icon: "R", color: "text-blue-700 bg-blue-50 text-[9px]", label: "只读" },
                { icon: "—", color: "text-slate-400 bg-slate-50", label: "无权限" },
              ].map(l => (
                <div key={l.label} className="flex items-center gap-1.5 text-[10px] text-muted-foreground">
                  <span className={`inline-flex items-center justify-center size-4 rounded-full ${l.color}`}>{l.icon}</span>{l.label}
                </div>
              ))}
            </div>
          </div>

          {/* Role detail cards */}
          <div className="space-y-2">
            <p className="text-xs font-semibold text-muted-foreground font-mono">角色详情 — 点击展开</p>
            {roleDefinitions.map(role => (
              <div key={role.id} className={`rounded-lg border overflow-hidden transition-all ${expandedRole === role.id ? "border-slate-200" : "border-slate-200"}`}>
                <button
                  onClick={() => setExpandedRole(expandedRole === role.id ? null : role.id)}
                  className="w-full flex items-center gap-3 px-4 py-3 hover:bg-slate-50 transition-colors text-left"
                >
                  <span className={`px-2 py-0.5 rounded border text-[10px] font-mono shrink-0 ${role.color}`}>{role.name}</span>
                  <span className="text-xs text-slate-600 flex-1">{role.desc}</span>
                  <div className="flex items-center gap-2 shrink-0">
                    <span className="text-[10px] text-muted-foreground font-mono">{role.users.length} 名用户</span>
                    <ChevronDown size={12} className={`text-muted-foreground transition-transform ${expandedRole === role.id ? "rotate-180" : ""}`} />
                  </div>
                </button>
                {expandedRole === role.id && (
                  <div className="border-t border-slate-200 px-4 pb-4 pt-3 bg-slate-50 space-y-3">
                    <div className="flex gap-6">
                      <div>
                        <p className="text-[10px] text-muted-foreground font-mono mb-1.5">当前用户</p>
                        <div className="flex gap-1.5 flex-wrap">
                          {role.users.map(u => (
                            <span key={u} className="text-[10px] px-2 py-0.5 rounded bg-slate-50 border border-slate-200 text-slate-600">{u}</span>
                          ))}
                        </div>
                      </div>
                    </div>
                    <div>
                      <p className="text-[10px] text-muted-foreground font-mono mb-2">细粒度权限</p>
                      <div className="grid grid-cols-2 lg:grid-cols-4 gap-2">
                        {Object.entries(role.modules).filter(([, perms]) => perms.length > 0).map(([mod, perms]) => (
                          <div key={mod} className="p-2 rounded bg-slate-50 border border-slate-200">
                            <p className="text-[10px] font-semibold text-slate-900 mb-1">{mod}</p>
                            <div className="flex flex-wrap gap-1">
                              {(perms as string[]).map(p => (
                                <span key={p} className={`text-[9px] px-1.5 py-0.5 rounded ${p === "全部" ? "bg-red-100 text-red-700" : ["审批", "配置"].includes(p) ? "bg-orange-500/15 text-orange-700" : ["审核", "新建", "处置"].includes(p) ? "bg-emerald-50 text-emerald-700" : "bg-blue-50 text-blue-700"}`}>
                                  {p}
                                </span>
                              ))}
                            </div>
                          </div>
                        ))}
                      </div>
                    </div>
                    <div className="flex gap-2">
                      <button className="text-xs px-3 py-1.5 rounded bg-blue-50 text-blue-700 border border-blue-200 hover:bg-blue-100 transition-colors">编辑角色</button>
                      <button className="text-xs px-3 py-1.5 rounded border border-slate-200 text-muted-foreground hover:text-slate-600 transition-colors">分配用户</button>
                      <button className="text-xs px-3 py-1.5 rounded border border-slate-200 text-muted-foreground hover:text-slate-600 transition-colors">权限日志</button>
                    </div>
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Workflow tab */}
      {activeTab === "workflow" && (
        <div className="space-y-4">
          {/* Three level overview */}
          <div className="grid grid-cols-3 gap-3">
            {warningLevels.map(w => (
              <button
                key={w.level}
                onClick={() => setExpandedWarning(expandedWarning === w.level ? null : w.level)}
                className={`text-left p-4 rounded-lg border transition-all ${expandedWarning === w.level
                  ? w.level === "red" ? "border-red-200 bg-red-50" : w.level === "yellow" ? "border-amber-500/35 bg-amber-500/6" : "border-blue-500/35 bg-blue-50"
                  : "border-slate-200 bg-card hover:border-slate-200"}`}
              >
                <div className="flex items-center gap-2 mb-2">
                  <span className="text-lg">{w.icon}</span>
                  <span className={`text-sm font-bold ${w.level === "red" ? "text-red-700" : w.level === "yellow" ? "text-amber-700" : "text-blue-700"}`}>{w.label}</span>
                </div>
                <p className="text-[10px] text-muted-foreground leading-relaxed">{w.trigger}</p>
                <div className={`mt-2 text-[10px] font-mono px-2 py-0.5 rounded inline-block ${w.level === "red" ? "bg-red-50 text-red-700" : w.level === "yellow" ? "bg-amber-500/12 text-amber-700" : "bg-blue-50 text-blue-700"}`}>
                  SLA: {w.sla}
                </div>
              </button>
            ))}
          </div>

          {/* Expanded detail */}
          {warningLevels.filter(w => w.level === expandedWarning).map(w => (
            <div key={w.level} className={`rounded-lg border p-5 space-y-4 ${w.level === "red" ? "border-red-200 bg-red-50" : w.level === "yellow" ? "border-amber-200 bg-amber-50" : "border-blue-200 bg-blue-50"}`}>
              <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
                <div>
                  <p className="text-[10px] text-muted-foreground font-mono mb-2">触发阈值</p>
                  <p className={`text-xs ${w.level === "red" ? "text-red-700" : w.level === "yellow" ? "text-amber-700" : "text-blue-700"}`}>{w.threshold}</p>
                  <p className="text-[10px] text-muted-foreground font-mono mt-3 mb-2">典型触发场景</p>
                  <div className="space-y-1">
                    {w.examples.map((ex, i) => (
                      <div key={i} className="flex items-start gap-2 text-[10px] text-slate-600">
                        <span className={`mt-0.5 shrink-0 ${w.level === "red" ? "text-red-700" : w.level === "yellow" ? "text-amber-700" : "text-blue-700"}`}>·</span>
                        {ex}
                      </div>
                    ))}
                  </div>
                </div>
                <div>
                  <p className="text-[10px] text-muted-foreground font-mono mb-2">系统自动动作</p>
                  <div className="space-y-1 mb-3">
                    {w.autoActions.map((a, i) => (
                      <div key={i} className="flex items-start gap-2 text-[10px]">
                        <Zap size={9} className={`mt-0.5 shrink-0 ${w.level === "red" ? "text-red-700" : w.level === "yellow" ? "text-amber-700" : "text-blue-700"}`} />
                        <span className="text-slate-600">{a}</span>
                      </div>
                    ))}
                  </div>
                  <p className="text-[10px] text-muted-foreground font-mono mb-2">人工处置选项</p>
                  <div className="space-y-1">
                    {w.manualActions.map((a, i) => (
                      <div key={i} className="flex items-start gap-2 text-[10px]">
                        <Users size={9} className="mt-0.5 shrink-0 text-muted-foreground" />
                        <span className="text-muted-foreground">{a}</span>
                      </div>
                    ))}
                  </div>
                  {w.escalation !== "无" && (
                    <div className={`mt-3 p-2 rounded border text-[10px] ${w.level === "red" ? "border-red-200 bg-red-50 text-red-700" : "border-amber-200 bg-amber-50 text-amber-700"}`}>
                      ⬆ 升级规则：{w.escalation}
                    </div>
                  )}
                </div>
              </div>
            </div>
          ))}

          {/* Flow linkage diagram */}
          <div className="bg-card border border-slate-200 rounded-lg p-5">
            <p className="text-xs font-semibold text-slate-900 mb-4">预警与采购流程联动示意</p>
            <div className="flex items-start gap-0 overflow-x-auto pb-2">
              {workflowNodes.map((node, i) => (
                <div key={i} className="flex items-start shrink-0">
                  <div className="w-44 space-y-2">
                    <div className="bg-blue-50 border border-blue-200 rounded px-3 py-2 text-center">
                      <p className="text-xs font-semibold text-blue-700">{node.stage}</p>
                    </div>
                    <div className="space-y-1">
                      {node.checks.map((c, j) => (
                        <div key={j} className="flex items-center gap-1.5 text-[10px] text-muted-foreground">
                          <Brain size={8} className="text-purple-700 shrink-0" />
                          <span>{c}</span>
                        </div>
                      ))}
                    </div>
                    {node.redTriggers.length > 0 && (
                      <div className="p-2 rounded border border-red-200 bg-red-50 space-y-0.5">
                        {node.redTriggers.map((t, j) => (
                          <p key={j} className="text-[9px] text-red-700">⛔ {t}</p>
                        ))}
                      </div>
                    )}
                    {node.yellowTriggers.length > 0 && (
                      <div className="p-2 rounded border border-amber-200 bg-amber-500/4 space-y-0.5">
                        {node.yellowTriggers.map((t, j) => (
                          <p key={j} className="text-[9px] text-amber-700">⚠ {t}</p>
                        ))}
                      </div>
                    )}
                  </div>
                  {i < workflowNodes.length - 1 && (
                    <div className="flex items-center pt-4 px-1">
                      <ArrowRight size={14} className="text-slate-400 shrink-0" />
                    </div>
                  )}
                </div>
              ))}
            </div>
          </div>

          {/* Response time SLA table */}
          <div className="bg-card border border-slate-200 rounded-lg overflow-hidden">
            <div className="px-4 py-3 border-b border-slate-200">
              <p className="text-xs font-semibold text-slate-900">响应时效与升级矩阵</p>
            </div>
            <table className="w-full text-xs">
              <thead>
                <tr className="border-b border-slate-200 text-muted-foreground">
                  {["预警级别", "初始响应", "处置时限", "超时动作", "升级接收方", "留存要求"].map(h => (
                    <th key={h} className="px-4 py-2.5 text-left font-mono font-medium whitespace-nowrap">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-200">
                {[
                  { level: "红色拦截", lc: "red", resp: "30 分钟", handle: "4 小时", overtime: "自动冻结并告警局长室", escalate: "局长室 / 纪委", retain: "永久留存，区块链存证" },
                  { level: "黄色提示", lc: "amber", resp: "2 小时", handle: "24 小时", overtime: "升级为红色，通知审计主任", escalate: "审计主任", retain: "5 年" },
                  { level: "蓝色提醒", lc: "blue", resp: "无强制", handle: "无强制", overtime: "无", escalate: "无", retain: "2 年" },
                ].map((row, i) => (
                  <tr key={i} className="hover:bg-slate-50 transition-colors">
                    <td className="px-4 py-2.5">
                      <span className={`px-2 py-0.5 rounded text-[10px] font-mono border ${row.lc === "red" ? "bg-red-50 text-red-700 border-red-200" : row.lc === "amber" ? "bg-amber-50 text-amber-700 border-amber-200" : "bg-blue-50 text-blue-700 border-blue-200"}`}>
                        {row.level}
                      </span>
                    </td>
                    <td className="px-4 py-2.5 font-mono text-slate-900">{row.resp}</td>
                    <td className="px-4 py-2.5 font-mono text-slate-900">{row.handle}</td>
                    <td className="px-4 py-2.5 text-slate-600">{row.overtime}</td>
                    <td className="px-4 py-2.5 text-slate-600">{row.escalate}</td>
                    <td className="px-4 py-2.5 text-muted-foreground">{row.retain}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Logs tab */}
      {activeTab === "logs" && (
        <div className="space-y-4">
          {!user?.roles.includes("SYSTEM_ADMIN") ? (
            <div className="rounded border border-amber-200 bg-amber-50 px-4 py-3 text-xs text-amber-700">仅系统管理员可以查询审计日志。</div>
          ) : <>
          <div className="grid grid-cols-1 gap-2 rounded border border-slate-200 bg-white p-3 md:grid-cols-2 xl:grid-cols-6">
            <input value={auditFilters.userId} onChange={(event) => setAuditFilters((current) => ({ ...current, userId: event.target.value }))} placeholder="操作人 ID" className="rounded border border-slate-200 px-3 py-2 text-xs outline-none focus:border-blue-400" />
            <input value={auditFilters.action} onChange={(event) => setAuditFilters((current) => ({ ...current, action: event.target.value }))} placeholder="操作类型，如 UPDATE_PROJECT" className="rounded border border-slate-200 px-3 py-2 text-xs outline-none focus:border-blue-400" />
            <select value={auditFilters.resourceType} onChange={(event) => setAuditFilters((current) => ({ ...current, resourceType: event.target.value }))} className="rounded border border-slate-200 bg-white px-3 py-2 text-xs outline-none focus:border-blue-400"><option value="">全部对象</option><option value="PROJECT">采购项目</option><option value="APPROVAL">审批</option><option value="USER">用户</option><option value="FILE">文件</option></select>
            <input type="datetime-local" value={auditFilters.from} onChange={(event) => setAuditFilters((current) => ({ ...current, from: event.target.value }))} title="开始时间" className="rounded border border-slate-200 px-3 py-2 text-xs outline-none focus:border-blue-400" />
            <input type="datetime-local" value={auditFilters.to} onChange={(event) => setAuditFilters((current) => ({ ...current, to: event.target.value }))} title="结束时间" className="rounded border border-slate-200 px-3 py-2 text-xs outline-none focus:border-blue-400" />
            <button type="button" onClick={() => void loadAuditRecords()} disabled={auditLoading} className="inline-flex items-center justify-center gap-1.5 rounded bg-blue-600 px-3 py-2 text-xs text-white hover:bg-blue-700 disabled:opacity-50"><Search size={12} />查询</button>
          </div>
          {auditError && <div role="alert" className="rounded border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700">{auditError}</div>}

          <div className="bg-card border border-slate-200 rounded-lg overflow-hidden">
            <div className="overflow-x-auto">
              <table className="w-full min-w-[980px] text-xs">
                <thead>
                  <tr className="border-b border-slate-200 text-muted-foreground">
                    {["时间", "操作人", "操作类型", "对象", "对象名称", "结果", "请求 ID / IP", "详细信息"].map(h => (
                      <th key={h} className="px-4 py-3 text-left font-mono font-medium whitespace-nowrap">{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-200">
                  {auditLoading ? <tr><td colSpan={8} className="px-4 py-8 text-center text-muted-foreground">正在加载审计日志…</td></tr> : auditRecords.length === 0 ? <tr><td colSpan={8} className="px-4 py-8 text-center text-muted-foreground">暂无符合条件的审计日志</td></tr> : auditRecords.map((record, index) => (
                    <tr key={`${record.occurredAt}-${record.requestId ?? index}`} className="align-top hover:bg-slate-50 transition-colors">
                      <td className="px-4 py-3 font-mono text-muted-foreground whitespace-nowrap">{formatProcurementDateTime(record.occurredAt)}</td>
                      <td className="px-4 py-3 text-slate-900"><p>{record.username ?? "-"}</p><p className="mt-0.5 font-mono text-[10px] text-muted-foreground">{record.userId ?? "-"}</p></td>
                      <td className="px-4 py-3 font-mono text-blue-700">{record.action}</td>
                      <td className="px-4 py-3"><p>{record.resourceType ?? "-"}</p><p className="mt-0.5 max-w-36 truncate font-mono text-[10px] text-muted-foreground" title={record.resourceId ?? undefined}>{record.resourceId ?? "-"}</p></td>
                      <td className="px-4 py-3 text-slate-700">{record.resourceName ?? "-"}</td>
                      <td className="px-4 py-3"><span className={`rounded border px-2 py-0.5 font-mono text-[10px] ${record.resultStatus === "SUCCESS" ? "border-emerald-200 bg-emerald-50 text-emerald-700" : "border-red-200 bg-red-50 text-red-700"}`}>{record.resultStatus}</span>{record.failureReason && <p className="mt-1 max-w-48 text-red-700">{record.failureReason}</p>}</td>
                      <td className="px-4 py-3 font-mono text-[10px] text-muted-foreground"><p>{record.requestId ?? "-"}</p><p className="mt-1">{record.sourceIp ?? "-"}</p></td>
                      <td className="max-w-72 px-4 py-3 text-slate-600"><p>{record.actionDesc}</p>{record.beforeData && <details className="mt-1"><summary className="cursor-pointer text-blue-700">修改前</summary><pre className="mt-1 max-h-32 overflow-auto whitespace-pre-wrap break-all rounded bg-slate-100 p-2 text-[10px]">{record.beforeData}</pre></details>}{record.afterData && <details className="mt-1"><summary className="cursor-pointer text-blue-700">修改后</summary><pre className="mt-1 max-h-32 overflow-auto whitespace-pre-wrap break-all rounded bg-slate-100 p-2 text-[10px]">{record.afterData}</pre></details>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
          </>}
        </div>
      )}

      {/* Config tab */}
      {activeTab === "config" && (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          <div className="bg-card border border-slate-200 rounded-lg p-5 space-y-4">
            <h3 className="text-sm font-semibold text-slate-900">智能体引擎配置</h3>
            {[
              { label: "需求合规引擎", enabled: true },
              { label: "供应商关联分析", enabled: true },
              { label: "投标文件查重", enabled: true },
              { label: "合同三方比对", enabled: true },
              { label: "结算核对引擎", enabled: true },
              { label: "风险预警引擎", enabled: true },
            ].map((engine, i) => (
              <div key={i} className="flex items-center justify-between py-2 border-b border-slate-200 last:border-0">
                <span className="text-xs text-slate-600">{engine.label}</span>
                <button className={`px-3 py-1 rounded text-[10px] font-mono transition-colors ${
                  engine.enabled
                    ? "bg-emerald-50 text-emerald-700 border border-emerald-200"
                    : "bg-slate-100 text-slate-500 border border-slate-200"
                }`}>
                  {engine.enabled ? "已启用" : "已停用"}
                </button>
              </div>
            ))}
          </div>

          <div className="bg-card border border-slate-200 rounded-lg p-5 space-y-4">
            <h3 className="text-sm font-semibold text-slate-900">预警阈值设置</h3>
            {[
              { label: "投标文件雷同率", value: "60%", type: "红色预警" },
              { label: "专家打分偏差", value: "25%", type: "黄色提示" },
              { label: "控制价偏离历史均价", value: "20%", type: "黄色提示" },
              { label: "预算超配置标准", value: "10%", type: "黄色提示" },
              { label: "合同价款偏离中标价", value: "3%", type: "红色预警" },
            ].map((threshold, i) => (
              <div key={i} className="space-y-1.5">
                <div className="flex items-center justify-between">
                  <span className="text-xs text-slate-600">{threshold.label}</span>
                  <span className={`text-[10px] px-2 py-0.5 rounded font-mono ${
                    threshold.type === "红色预警"
                      ? "bg-red-50 text-red-700 border border-red-200"
                      : "bg-amber-50 text-amber-700 border border-amber-200"
                  }`}>
                    {threshold.type}
                  </span>
                </div>
                <input
                  type="text"
                  defaultValue={threshold.value}
                  className="w-full px-3 py-1.5 rounded bg-slate-50 border border-slate-200 text-xs text-slate-900 font-mono focus:outline-none focus:border-blue-200"
                />
              </div>
            ))}
            <button className="w-full py-2 text-xs rounded bg-blue-50 text-blue-700 border border-blue-200 hover:bg-blue-100 transition-colors mt-2">
              保存配置
            </button>
          </div>

          <div className="bg-card border border-slate-200 rounded-lg p-5">
            <h3 className="text-sm font-semibold text-slate-900 mb-4">系统信息</h3>
            <div className="space-y-2 text-xs">
              {[
                ["系统版本", "v2.3.1"],
                ["部署环境", "生产环境"],
                ["数据库版本", "PostgreSQL 14.5"],
                ["智能体引擎版本", "v1.8.2"],
                ["最后更新", "2026-07-01"],
                ["运行时长", "43 天"],
              ].map(([k, v]) => (
                <div key={k} className="flex justify-between py-1.5 border-b border-slate-200">
                  <span className="text-muted-foreground">{k}</span>
                  <span className="font-mono text-slate-900">{v}</span>
                </div>
              ))}
            </div>
          </div>

          <div className="bg-card border border-slate-200 rounded-lg p-5">
            <h3 className="text-sm font-semibold text-slate-900 mb-4">数据统计</h3>
            <div className="space-y-2 text-xs">
              {[
                ["项目总数", "1,847"],
                ["合同总数", "1,203"],
                ["供应商数量", "456"],
                ["用户数量", "68"],
                ["智能体处理次数", "23,481"],
                ["预警触发次数", "1,092"],
              ].map(([k, v]) => (
                <div key={k} className="flex justify-between py-1.5 border-b border-slate-200">
                  <span className="text-muted-foreground">{k}</span>
                  <span className="font-mono text-blue-700 font-semibold">{v}</span>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function PlaceholderPage({ title, icon }: { title: string; icon: React.ReactNode }) {
  return (
    <div className="flex-1 flex items-center justify-center flex-col gap-4 text-center p-12">
      <div className="size-14 rounded-2xl bg-card border border-slate-200 flex items-center justify-center text-muted-foreground">
        {icon}
      </div>
      <div>
        <h3 className="text-base font-semibold text-slate-900 mb-1">{title}</h3>
        <p className="text-sm text-muted-foreground">该模块功能完整，点击左侧其他模块浏览已实现的页面</p>
      </div>
    </div>
  );
}

// ── Root App ───────────────────────────────────────────────────────────────
export default function App() {
  const { user, logout } = useAuth();
  const [restoredContractNavigation] = useState(() => {
    if (!user?.userId || typeof window === "undefined") return null;
    return loadContractReviewNavigation(window.sessionStorage, user.userId);
  });
  const [page, setPage] = useState<Page>(() => restoredContractNavigation ? "project" : "dashboard");
  const [logoutLoading, setLogoutLoading] = useState(false);
  const [logoutError, setLogoutError] = useState<string | null>(null);
  const [contractNavigationTarget, setContractNavigationTarget] = useState<ContractNavigationIntent | null>(null);
  const [projectNavigationTarget, setProjectNavigationTarget] = useState<string | null>(
    restoredContractNavigation?.projectId ?? null,
  );
  const [procurementNavigationProjectId, setProcurementNavigationProjectId] = useState<string | null>(null);
  const [notifications, setNotifications] = useState<ProcurementNotification[]>([]);
  const [notificationCount, setNotificationCount] = useState(0);
  const [notificationOpen, setNotificationOpen] = useState(false);
  const [notificationLoading, setNotificationLoading] = useState(false);
  const [notificationError, setNotificationError] = useState<string | null>(null);
  const presentedRoles = presentRoleLabels(user?.roles ?? []);

  useEffect(() => {
    if (page !== "project" && user?.userId && typeof window !== "undefined") {
      clearContractReviewNavigation(window.sessionStorage, user.userId);
    }
  }, [page, user?.userId]);

  const refreshNotifications = async (includeList = false) => {
    setNotificationLoading(true);
    setNotificationError(null);
    try {
      const [count, items] = await Promise.all([
        getUnreadNotificationCount(),
        includeList ? listNotifications(false) : Promise.resolve<ProcurementNotification[] | null>(null),
      ]);
      setNotificationCount(count);
      if (items) setNotifications(items);
    } catch (error) {
      setNotificationError(userFacingError(error, "通知加载失败，请稍后重试。"));
    } finally {
      setNotificationLoading(false);
    }
  };

  useEffect(() => { void refreshNotifications(false); }, [user?.userId]);

  const toggleNotifications = () => {
    const next = !notificationOpen;
    setNotificationOpen(next);
    if (next) void refreshNotifications(true);
  };

  const openNotification = async (notification: ProcurementNotification) => {
    if (!notification.read) {
      try {
        await markNotificationRead(notification.id);
        setNotifications((items) => items.map((item) => item.id === notification.id ? { ...item, read: true } : item));
        setNotificationCount((count) => Math.max(0, count - 1));
      } catch (error) {
        setNotificationError(userFacingError(error, "通知状态更新失败，请稍后重试。"));
        return;
      }
    }
    setNotificationOpen(false);
    if (notification.type === "BLACKLIST_CASE") setPage("blacklist");
    else if (notification.projectId) openProcurementTask(notification.projectId);
  };

  const handleLogout = async () => {
    setLogoutLoading(true);
    setLogoutError(null);
    try {
      await logout();
    } catch (error) {
      setLogoutError(userFacingError(error, "暂时无法退出，请稍后重试。"));
    } finally {
      setLogoutLoading(false);
    }
  };

  const navigateContract = (intent: ContractNavigationIntent) => {
    if (intent.type === "CREATE_CONTRACT") {
      setContractNavigationTarget(null);
      setProjectNavigationTarget(null);
      setPage("contract");
      return;
    }
    setContractNavigationTarget(intent);
    setPage("contract");
  };
  const openProcurementTask = (projectId: string) => {
    setProcurementNavigationProjectId(projectId);
    setPage("procurement");
  };

  const openProject = (projectId: string | null) => {
    setProjectNavigationTarget(projectId);
    setPage("project");
  };

  const pageComponents: Record<Page, React.ReactNode> = {
    dashboard: <DeliveryWorkbenchPage
      onOpenContract={() => {
        setContractNavigationTarget(null);
        setPage("contract");
      }}
      onOpenProcurement={() => setPage("procurement")}
      onOpenBudget={() => setPage("budget-check")}
      onOpenBlacklist={() => setPage("blacklist")}
      onOpenRisk={() => setPage("risk")}
    />,
    project: <ProjectWorkspace key={projectNavigationTarget ?? "project-list"}
      initialProjectId={projectNavigationTarget} entryMode="CONTRACT_REVIEW" />,
    procurement: <ProcurementPage initialProjectId={procurementNavigationProjectId} onNavigationConsumed={() => setProcurementNavigationProjectId(null)} />,
    "procurement-tasks": <ProcurementApprovalTasksPage onOpenProject={openProcurementTask} />,
    "budget-check": <BudgetAuditWorkspace onOpenProcurement={openProcurementTask} />,
    bidding: <BiddingPage />,
    contract: <ContractPage initialNavigation={contractNavigationTarget}
      onOpenProject={(projectId) => openProject(projectId)}
      onNavigationConsumed={() => setContractNavigationTarget(null)} />,
    settlement: <SettlementPage />,
    risk: <RiskAssessmentPage onOpenTask={(task) => navigateContract({ type: "OPEN_TASK", task })} />,
    blacklist: <BlacklistAlertPage />,
    agent: <AgentPage />,
    "ai-tools": <AIToolsPage />,
    "model-eval": <ModelEvalPage />,
    "api-test": <ApiTestPage />,
    system: <SystemPage />,
  };

  const activePortalPage: Page = page === "project"
    ? "contract"
    : page === "procurement-tasks" ? "procurement" : page;

  const notificationSlot = (
    <div className="relative">
      <button
        type="button"
        onClick={toggleNotifications}
        className="portal-icon-action relative"
        title="通知"
        aria-label={`通知，${notificationCount} 条未读`}
        aria-expanded={notificationOpen}
      >
        <Bell size={16}/>
        {notificationCount > 0 && <span className="portal-notification-count">{notificationCount > 99 ? "99+" : notificationCount}</span>}
      </button>
      {notificationOpen && (
        <div className="portal-notification-panel">
          <div className="flex items-center justify-between border-b border-slate-200 px-3 py-2.5">
            <div><p className="text-xs font-semibold text-slate-900">站内通知</p><p className="mt-0.5 text-[10px] text-muted-foreground">{notificationCount} 条未读</p></div>
            <button type="button" onClick={() => void refreshNotifications(true)} disabled={notificationLoading} title="刷新通知" className="rounded p-1.5 text-slate-500 hover:bg-slate-100 disabled:opacity-50"><RefreshCw size={13} className={notificationLoading ? "animate-spin" : undefined}/></button>
          </div>
          {notificationError && <div className="m-3 rounded border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700">{notificationError}</div>}
          <div className="max-h-80 overflow-y-auto">
            {notificationLoading && notifications.length === 0
              ? <div className="p-6 text-center text-xs text-muted-foreground">正在加载通知…</div>
              : notifications.length === 0
                ? <div className="p-6 text-center text-xs text-muted-foreground">暂无通知</div>
                : notifications.map((notification) => (
                  <button key={notification.id} type="button" onClick={() => void openNotification(notification)}
                    className={`block w-full border-b border-slate-100 px-3 py-3 text-left last:border-0 hover:bg-orange-50 ${notification.read ? "bg-white" : "bg-orange-50/60"}`}>
                    <div className="flex items-start justify-between gap-3"><span className="text-xs font-medium text-slate-900">{notification.title}</span>{!notification.read && <span className="mt-1 size-2 shrink-0 rounded-full bg-orange-500"/>}</div>
                    <p className="mt-1 line-clamp-2 text-xs leading-5 text-slate-600">{notification.content}</p>
                    <p className="mt-1.5 font-mono text-[10px] text-muted-foreground">{formatProcurementDateTime(notification.createdTime)}</p>
                  </button>
                ))}
          </div>
        </div>
      )}
    </div>
  );

  return (
    <PortalShell
      items={demoVisibleNavigationItems(NAV_ITEMS)}
      activePage={activePortalPage}
      onNavigate={setPage}
      user={user}
      roleLabel={presentedRoles}
      onLogout={() => void handleLogout()}
      logoutLoading={logoutLoading}
      logoutError={logoutError}
      notificationSlot={notificationSlot}
    >
      {user?.userId && <ProcurementMaterialBackground actorId={user.userId} />}
      {user?.userId && <BudgetMaterialBackground actorId={user.userId} />}
      {pageComponents[page]}
    </PortalShell>
  );
}
