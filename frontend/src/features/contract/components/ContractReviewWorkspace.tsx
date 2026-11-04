import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  AlertTriangle,
  ArrowLeft,
  Brain,
  Database,
  FileCheck2,
  LoaderCircle,
  RefreshCw,
} from "lucide-react";
import { useAuth } from "../../../auth/AuthProvider";
import {
  ResizableHandle,
  ResizablePanel,
  ResizablePanelGroup,
} from "../../../app/components/ui/resizable";
import {
  contractDataSource,
  contractDataSourceMode,
  type ContractDataSource,
} from "../data/contractDataSource";
import { createReviewExecutionSnapshot } from "../reviewExecutionPresentation";
import { reviewHistoryAccessPresentation } from "../reviewHistoryViewModel";
import {
  displayChineseUppercaseAmount,
  formatRmbAmount,
  reviewCompletionStatusText,
} from "../reviewFindingPresentation";
import type { Contract, FindingSourceAnchor } from "../types";
import { createIntelligentReviewPipeline } from "../intelligentReviewPipelinePresentation";
import { startIntelligentReviewBranches } from "../intelligentReviewOperation";
import { deriveProjectReviewMaterialSelection } from "../projectReviewMaterialSelection";
import type { SourcePreparationSection } from "../reviewRulePresentation";
import { getContractDocumentMock } from "../mock/contractDocumentMock";
import {
  ephemeralF02ReviewOperationPersistence,
  sessionStorageF02ReviewOperationPersistence,
} from "../reviewOperation";
import { useContractReviewWorkspace } from "../useContractReviewWorkspace";
import { useContractSemanticAnalysis } from "../useContractSemanticAnalysis";
import { useContractClauseReviews } from "../useContractClauseReviews";
import { useProjectContractReviewRun } from "../useProjectContractReviewRun";
import { adaptDurableF02Output } from "../reviewRunF02Adapter";
import { frozenContractFromReviewRun } from "../reviewRunFrozenContract";
import { frozenReviewMaterialTabs } from "../reviewRunFrozenMaterials";
import { adaptDurableSemanticReview } from "../reviewRunSemanticAdapter";
import { createEightCategoryReviewProjection } from "../eightCategoryReviewProjection.ts";
import { createDurableEvidenceFocus } from "../reviewRunEvidencePresentation";
import { reviewSafeReasonText } from "../reviewRunPresentation";
import { retryableCurrentAttempt } from "../reviewStepRetry.ts";
import { ContractFindingAcknowledgement } from "../closeout/ContractFindingAcknowledgement.tsx";
import { ContractReviewReportPanel } from "../closeout/ContractReviewReportPanel.tsx";
import { useReviewDocuments } from "../useReviewDocuments";
import type {
  ReviewWorkspacePhase,
  StructuredFactVisibility,
  ProjectBackedReviewMaterialTab,
} from "../workspaceTypes";
import AddReviewMaterial from "./AddReviewMaterial";
import StructuredFactsPanel from "./ContractDetailsPanel";
import F02ReviewResultPanel from "./F02ReviewResultPanel";
import F02ReviewHistoryDrawer from "./F02ReviewHistoryDrawer";
import DocumentProcessingProgress from "./MaterialProcessingPanel";
import ReviewProgressTimeline from "./ReviewProgressTimeline";
import DocumentViewerPane from "./SourceDocumentsPanel";
import ProjectMaterialsViewer from "./ProjectMaterialsViewer";
import { ProjectReviewMaterialPicker } from "./ProjectReviewMaterialPicker";
import { IntelligentReviewPipeline } from "./IntelligentReviewPipeline";
import { AiSemanticReviewPanel } from "./AiSemanticReviewPanel";
import { EightCategoryReviewPanel } from "./EightCategoryReviewPanel.tsx";

const RECORDED_FACT_VISIBILITY: StructuredFactVisibility = {
  contractIdentity: true,
  parties: true,
  amount: true,
  lineItems: true,
  awardNotice: true,
};

const phaseUi: Record<ReviewWorkspacePhase, { label: string; className: string }> = {
  PREPARE_MATERIALS: {
    label: "审核材料确认",
    className: "border-amber-200 bg-amber-50 text-amber-800",
  },
  READING_MATERIALS: {
    label: "正在准备审核数据",
    className: "border-blue-200 bg-blue-50 text-blue-700",
  },
  FACTS_READY: {
    label: "合同数据已就绪",
    className: "border-emerald-200 bg-emerald-50 text-emerald-700",
  },
  REVIEWING: {
    label: "正在执行合同审核",
    className: "border-blue-200 bg-blue-50 text-blue-700",
  },
  COMPLETED: {
    label: "审核完成",
    className: "border-emerald-200 bg-emerald-50 text-emerald-700",
  },
  FAILED: {
    label: "处理失败",
    className: "border-red-200 bg-red-50 text-red-700",
  },
};

interface ContractAuditWorkspaceProps {
  projectId?: string | null;
  historicalRunId?: string | null;
  projectContract?: Contract | null;
  contractId: string;
  contractTitle: string;
  contractStatus: string;
  supplier: string;
  projectName?: string | null;
  projectNo?: string | null;
  projectCategory?: string | null;
  initiatedBy?: string | null;
  createdAt?: string | null;
  onBack: () => void;
  mode?: "showcase" | "legacy";
  dataSource?: ContractDataSource;
  onProjectedSourceFocus?: (source: FindingSourceAnchor) => void;
  projectMaterials?: readonly ProjectBackedReviewMaterialTab[];
  sourcePreparationSections?: readonly SourcePreparationSection[];
}

function useDesktopPanels(): boolean {
  const [desktop, setDesktop] = useState(() =>
    typeof window === "undefined" ? true : window.matchMedia("(min-width: 1024px)").matches,
  );

  useEffect(() => {
    const media = window.matchMedia("(min-width: 1024px)");
    const update = () => setDesktop(media.matches);
    update();
    media.addEventListener("change", update);
    return () => media.removeEventListener("change", update);
  }, []);

  return desktop;
}

function AuditTargetSummary({
  contract,
  projectName,
  projectNo,
  projectCategory,
  initiatedBy,
  createdAt,
}: {
  contract: Contract | null;
  projectName: string | null;
  projectNo: string | null;
  projectCategory: string | null;
  initiatedBy: string | null;
  createdAt: string | null;
}) {
  if (!contract) {
    return <div className="rounded-lg border border-slate-200 bg-white p-4 text-xs text-slate-600">正在读取审核对象…</div>;
  }
  const rows = [
    ...(projectName ? [["项目名称", projectName]] : []),
    ...(projectNo ? [["项目编号", projectNo]] : []),
    ...(projectCategory ? [["项目类目", projectCategory]] : []),
    ["合同名称", contract.contractName],
    ["合同编号", contract.contractNo],
    ["供应商名称", contract.partyB],
    ...(contract.partyBCreditCode ? [["统一社会信用代码", contract.partyBCreditCode]] : []),
    ["合同金额", formatRmbAmount(contract.contractAmount)],
    ["金额大写", displayChineseUppercaseAmount(contract.amountInWords)],
    ...(initiatedBy ? [["发起人", initiatedBy]] : []),
    ...(createdAt ? [["创建时间", new Date(createdAt).toLocaleString("zh-CN")]] : []),
  ];
  return (
    <section className="overflow-hidden rounded-lg border border-slate-200 bg-white">
      <div className="border-b border-slate-200 px-4 py-3">
        <h3 className="text-[15px] font-semibold text-slate-900">审核对象摘要</h3>
      </div>
      <dl className="grid grid-cols-[7rem_minmax(0,1fr)] gap-y-2 px-4 py-3 text-[13px]">
        {rows.map(([label, value]) => (
          <div key={label} className="contents">
            <dt className="text-slate-500">{label}</dt>
            <dd className="break-words text-slate-900">{value}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

export default function ContractAuditWorkspace({
  projectId: explicitProjectId = null,
  historicalRunId = null,
  projectContract = null,
  contractId,
  contractTitle,
  contractStatus: _contractStatus,
  supplier,
  projectName = null,
  projectNo = null,
  projectCategory = null,
  initiatedBy = null,
  createdAt = null,
  onBack,
  mode = "legacy",
  dataSource = contractDataSource,
  onProjectedSourceFocus,
  projectMaterials,
  sourcePreparationSections = [],
}: ContractAuditWorkspaceProps) {
  const { user } = useAuth();
  const desktopPanels = useDesktopPanels();
  const showcaseMode = mode === "showcase";
  const mockMode = showcaseMode || contractDataSourceMode === "mock";
  const documentMock = useMemo(
    () => mockMode ? getContractDocumentMock(contractId) : null,
    [contractId, mockMode],
  );
  const documentWorkspace = useReviewDocuments(contractId);
  const projectId = useMemo(() => explicitProjectId ?? projectMaterials
    ?.flatMap((tab) => tab.files)
    .find((file) => Boolean(file.projectId))?.projectId ?? null,
  [explicitProjectId, projectMaterials]);
  const projectBacked = Boolean(projectMaterials && projectId && (projectContract || historicalRunId));
  const legacyReview = useContractReviewWorkspace(
    contractId,
    user?.userId ?? "",
    dataSource,
    showcaseMode
      ? ephemeralF02ReviewOperationPersistence
      : sessionStorageF02ReviewOperationPersistence,
    !projectBacked,
  );
  const [reviewFlowOpened, setReviewFlowOpened] = useState(false);
  const [preparingNewReview, setPreparingNewReview] = useState(false);
  const [focusedProjectSource, setFocusedProjectSource] = useState<FindingSourceAnchor | null>(null);
  const [focusedProjectMaterialId, setFocusedProjectMaterialId] = useState<string | null>(null);
  const [focusedProjectLocatorUnavailable, setFocusedProjectLocatorUnavailable] = useState(false);
  const focusedRunIdRef = useRef<string | null>(null);
  const [requestedMaterialIds, setRequestedMaterialIds] = useState<readonly string[] | undefined>();
  const requestedMaterialSelection = useMemo(
    () => projectId && projectMaterials
      ? deriveProjectReviewMaterialSelection(projectMaterials, projectId, requestedMaterialIds)
      : null,
    [projectId, projectMaterials, requestedMaterialIds],
  );
  const durableReview = useProjectContractReviewRun({
    enabled: projectBacked,
    actorId: user?.userId ?? "",
    projectId,
    contractId,
    expectedContractVersion: projectContract?.versionNo ?? null,
    selectedFileIds: requestedMaterialSelection?.selectedFileIds ?? [],
    historicalRunId,
  });
  const frozenContract = useMemo(() => {
    if (!durableReview.run) return { value: null, error: null };
    try {
      return { value: frozenContractFromReviewRun(durableReview.run), error: null };
    } catch {
      return { value: null, error: "冻结合同快照无法安全展示" };
    }
  }, [durableReview.run]);
  const frozenMaterials = durableReview.run?.materials;
  const frozenMaterialIds = frozenMaterials?.map((material) => material.fileId);
  const displayProjectMaterials = useMemo(() => frozenReviewMaterialTabs(
    projectMaterials ?? [], frozenMaterials ?? null, Boolean(historicalRunId),
  ), [projectMaterials, frozenMaterials, historicalRunId]);
  const materialSelection = useMemo(
    () => projectId && projectMaterials
      ? deriveProjectReviewMaterialSelection(
          displayProjectMaterials,
          projectId,
          frozenMaterialIds ?? requestedMaterialIds,
        )
      : null,
    [displayProjectMaterials, frozenMaterialIds, projectId, projectMaterials, requestedMaterialIds],
  );
  const materialFileNames = useMemo(() => new Map(
    displayProjectMaterials.flatMap((tab) => tab.files)
      .map((material) => [material.id, material.fileName] as const),
  ), [displayProjectMaterials]);
  const availableMaterialIds = useMemo(() => new Set(materialFileNames.keys()), [materialFileNames]);
  const eightCategoryProjection = useMemo(() => {
    if (!durableReview.run) return { categories: null, error: null };
    try {
      return {
        categories: createEightCategoryReviewProjection(durableReview.run, materialFileNames),
        error: null,
      };
    } catch {
      return { categories: null, error: "八项审核结果无法验证，请重新读取本次审核记录。" };
    }
  }, [durableReview.run, materialFileNames]);
  const missingFrozenMaterialCount = historicalRunId && frozenMaterialIds
    ? frozenMaterialIds.filter((fileId) => !availableMaterialIds.has(fileId)).length : 0;
  const durableF02 = useMemo(() => adaptDurableF02Output(
    durableReview.run?.outputs.find((output) => output.step.stepType === "F02_DETERMINISTIC") ?? null,
    durableReview.run?.materials ?? [],
  ), [durableReview.run]);
  const durableF02Step = durableReview.run?.steps.find(
    (step) => step.stepType === "F02_DETERMINISTIC",
  ) ?? null;
  const durableSemantic = useMemo(() => adaptDurableSemanticReview(
    durableReview.run?.outputs ?? [], materialFileNames,
  ), [durableReview.run, materialFileNames]);
  const closeoutContext = useMemo(() => durableReview.run && projectId && user?.userId ? {
    actorId: user.userId,
    projectId,
    contractId,
    runId: durableReview.run.runId,
  } : null, [contractId, durableReview.run, projectId, user?.userId]);
  const retryableSteps = useMemo(() => durableReview.run
    ? durableReview.run.steps.filter((step) => retryableCurrentAttempt(durableReview.run!, step.stepId))
    : [], [durableReview.run]);

  useEffect(() => {
    const runId = durableReview.run?.runId ?? null;
    if (focusedRunIdRef.current === runId) return;
    focusedRunIdRef.current = runId;
    setFocusedProjectSource(null);
    setFocusedProjectMaterialId(null);
    setFocusedProjectLocatorUnavailable(false);
  }, [durableReview.run?.runId]);

  const {
    startReview,
    retryReview,
    startNewReview,
    acknowledgeFinding,
  } = legacyReview;
  const contract = projectBacked
    ? durableReview.run ? frozenContract.value : historicalRunId ? null : projectContract
    : legacyReview.contract;
  const result = projectBacked ? durableF02?.result ?? null : legacyReview.result;
  const progress = projectBacked ? durableF02?.progress ?? null : legacyReview.progress;
  const progressiveReplayState = projectBacked ? null : legacyReview.progressiveReplayState;
  const contractLoading = projectBacked ? false : legacyReview.contractLoading;
  const latestResultLoading = projectBacked ? durableReview.restoring : legacyReview.latestResultLoading;
  const progressLoading = projectBacked ? durableReview.restoring : legacyReview.progressLoading;
  const reviewLoading = projectBacked ? durableReview.executing : legacyReview.reviewLoading;
  const acknowledgementLoadingResultId = projectBacked ? null : legacyReview.acknowledgementLoadingResultId;
  const contractError = projectBacked ? frozenContract.error : legacyReview.contractError;
  const latestResultError = projectBacked ? durableReview.error : legacyReview.latestResultError;
  const progressError = projectBacked ? null : legacyReview.progressError;
  const reviewError = projectBacked ? durableReview.error : legacyReview.reviewError;
  const acknowledgementError = projectBacked ? null : legacyReview.acknowledgementError;
  const operationStoreError = projectBacked ? null : legacyReview.operationStoreError;
  const reviewSupported = projectBacked || legacyReview.reviewSupported;
  const canRetryReview = projectBacked ? false : legacyReview.canRetryReview;
  const reviewConflict = projectBacked ? "OTHER" : legacyReview.reviewConflict;
  const restartRequired = projectBacked
    ? durableReview.restartRequired
    : legacyReview.restartRequired;
  const selectedMaterials = useMemo(() => {
    if (!materialSelection) return [];
    const selectedIds = new Set(materialSelection.selectedFileIds);
    return materialSelection.eligibleMaterials
      .filter((material) => selectedIds.has(material.id))
      .map((material) => ({ id: material.id, fileName: material.fileName }));
  }, [materialSelection]);
  const {
    result: textAnalysis,
    resultMaterials: textAnalysisMaterials,
    loading: textAnalysisLoading,
    error: textAnalysisError,
    notice: textAnalysisNotice,
    analyze: analyzeContractText,
  } = useContractSemanticAnalysis({
    contractId,
    projectId,
    selectedMaterials,
    dataSource,
  });
  const {
    breachResult, termResult,
    analyze: analyzeContractClauses,
  } = useContractClauseReviews(
    projectId,
    contractId,
    materialSelection?.selectedFileIds ?? [],
    dataSource,
  );

  useEffect(() => {
    setReviewFlowOpened(false);
    setPreparingNewReview(false);
    setRequestedMaterialIds(undefined);
    setFocusedProjectMaterialId(null);
    setFocusedProjectLocatorUnavailable(false);
  }, [contractId, projectId]);

  useEffect(() => {
    if (!result || reviewLoading) return;
    setReviewFlowOpened(false);
    setPreparingNewReview(false);
  }, [result?.taskId, reviewLoading]);

  const runReviewOperation = useCallback(() => {
    if (canRetryReview) retryReview();
    else if (preparingNewReview || restartRequired || result) startNewReview();
    else startReview();
  }, [canRetryReview, preparingNewReview, restartRequired, result, retryReview, startNewReview, startReview]);

  const runShowcaseReview = useCallback(() => {
    if (reviewLoading) return;
    setReviewFlowOpened(true);
    if (canRetryReview) {
      retryReview();
      return;
    }
    runReviewOperation();
  }, [canRetryReview, retryReview, reviewLoading, runReviewOperation]);

  const prepareNewReview = () => {
    setReviewFlowOpened(true);
    setPreparingNewReview(true);
  };

  const startIntegratedReview = useCallback(() => {
    if (historicalRunId) return;
    if (projectBacked) {
      if (durableReview.executing || durableReview.restoring) return;
      if (durableReview.run) {
        if (!durableReview.presentation?.canContinue) return;
        setReviewFlowOpened(true);
        void durableReview.continuePending();
        return;
      }
      if (!materialSelection?.canStart) return;
      setReviewFlowOpened(true);
      void durableReview.start();
      return;
    }
    if (!materialSelection?.canStart || textAnalysisLoading || reviewLoading) return;
    setReviewFlowOpened(true);
    void analyzeContractClauses();
    void startIntelligentReviewBranches({
      startSemanticReview: analyzeContractText,
      startDeterministicReview: runReviewOperation,
    });
  }, [analyzeContractClauses, analyzeContractText, durableReview, historicalRunId,
    materialSelection?.canStart, projectBacked, reviewLoading, runReviewOperation, textAnalysisLoading]);

  const reviewInProgress = reviewConflict === "IN_PROGRESS";
  const showingHistoricalResult = projectBacked
    ? Boolean(durableReview.run && !durableReview.executing)
    : Boolean(result && !preparingNewReview && !reviewLoading && !canRetryReview);
  const preparationVisible = reviewFlowOpened || preparingNewReview || canRetryReview || restartRequired
    || projectBacked && Boolean(durableReview.run || durableReview.restoring);
  const workspacePhase: ReviewWorkspacePhase = contractError || !projectBacked && restartRequired
    ? "FAILED"
    : reviewLoading || reviewInProgress
        ? "REVIEWING"
        : showingHistoricalResult
          ? "COMPLETED"
          : contract
            ? preparationVisible ? "PREPARE_MATERIALS" : "FACTS_READY"
            : "PREPARE_MATERIALS";
  const currentPhase = phaseUi[workspacePhase];
  const replayComplete = progressiveReplayState?.complete ?? false;
  const completionStatusText = projectBacked
    ? durableReview.presentation?.statusLabel ?? "待审核"
    : reviewCompletionStatusText(progressiveReplayState);
  const completionStatusClassName = projectBacked && durableReview.run
    ? durableReview.run.status === "COMPLETED"
      ? "border-emerald-200 bg-emerald-50 text-emerald-700"
      : durableReview.run.status === "RUNNING" || durableReview.run.status === "PENDING"
        ? "border-blue-200 bg-blue-50 text-blue-700"
        : "border-amber-200 bg-amber-50 text-amber-800"
    : progressiveReplayState
      ? replayComplete
        ? "border-emerald-200 bg-emerald-50 text-emerald-700"
        : "border-amber-200 bg-amber-50 text-amber-800"
      : "border-slate-200 bg-slate-100 text-slate-700";
  const currentPhaseLabel = projectBacked && durableReview.presentation
    ? durableReview.presentation.statusLabel
    : showingHistoricalResult ? completionStatusText : currentPhase.label;
  const currentPhaseClassName = projectBacked && durableReview.presentation
    ? completionStatusClassName
    : showingHistoricalResult ? completionStatusClassName : currentPhase.className;
  const activeMockText = documentMock?.textByKind[documentWorkspace.activeDocument.kind] ?? null;
  const executionSnapshot = useMemo(() => createReviewExecutionSnapshot({
    result,
    progress,
    state: contractError || !projectBacked && restartRequired
      ? "FAILED"
      : reviewLoading || reviewInProgress
        ? "RUNNING"
        : result ? "COMPLETED" : "NOT_STARTED",
  }), [contractError, progress, restartRequired, reviewInProgress, reviewLoading, result]);
  const fallbackIntelligentReviewPipeline = useMemo(() => createIntelligentReviewPipeline({
    selectedFileCount: materialSelection?.selectedFileIds.length ?? 0,
    aiLoading: textAnalysisLoading,
    aiAvailable: Boolean(textAnalysis?.available),
    aiFailed: Boolean(textAnalysisError || textAnalysis && !textAnalysis.available),
    deterministicLoading: reviewLoading || reviewInProgress,
    deterministicCompleted: Boolean(result) && !reviewLoading,
    deterministicFailed: Boolean(
      restartRequired || contractError || reviewError && !reviewInProgress && !reviewLoading,
    ),
  }), [
    contractError,
    materialSelection?.selectedFileIds.length,
    restartRequired,
    result,
    reviewError,
    reviewInProgress,
    reviewLoading,
    textAnalysis,
    textAnalysisError,
    textAnalysisLoading,
  ]);
  const intelligentReviewPipeline = projectBacked && durableReview.presentation
    ? { steps: durableReview.presentation.steps }
    : fallbackIntelligentReviewPipeline;
  const assistanceStep = intelligentReviewPipeline.steps.find((step) => step.key === "ASSISTANCE");
  const integratedReviewRunning = projectBacked
    ? durableReview.executing || durableReview.restoring || durableReview.observing
    : textAnalysisLoading || reviewLoading || reviewInProgress;
  const projectReviewActionReady = durableReview.run
    ? durableReview.presentation?.canContinue
    : materialSelection?.canStart;
  const canStartIntegratedReview = Boolean(
    !historicalRunId && (projectBacked ? projectReviewActionReady : materialSelection?.canStart)
      && reviewSupported
      && contract
      && !contractLoading
      && !latestResultLoading
      && user?.userId,
  );
  const integratedReviewVisible = projectBacked && Boolean(
    preparationVisible
      || integratedReviewRunning
      || result
      || durableReview.run
      || textAnalysis
      || textAnalysisError
      || durableReview.error,
  );
  const historyAccess = reviewHistoryAccessPresentation(
    progressLoading,
    progressError,
    progress,
  );

  return (
    <div className="contract-light flex h-full min-h-[680px] flex-col bg-[#F6F8FB] text-slate-700 lg:min-h-0">
      <header className="flex shrink-0 items-center justify-between gap-4 border-b border-slate-200 bg-white px-4 py-3">
        <div className="flex items-center gap-3 min-w-0">
          <button
            type="button"
            onClick={onBack}
            className="flex size-9 shrink-0 items-center justify-center rounded border border-slate-200 text-slate-600 hover:bg-slate-50 hover:text-slate-900"
            aria-label="返回内审任务列表"
          >
            <ArrowLeft size={15} />
          </button>
          <div className="min-w-0">
            <div className="flex items-center gap-2 min-w-0">
              <h2 className="truncate text-base font-semibold text-slate-900">
                {contract?.contractName ?? (historicalRunId ? "正在读取冻结合同" : contractTitle)}
              </h2>
            </div>
            <p className="mt-1 truncate text-[13px] text-slate-600">合同编号：{contract?.contractNo ?? "正在读取"} · 供应商：{contract?.partyB ?? (historicalRunId ? "正在读取" : supplier)}</p>
          </div>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          <span className={`whitespace-nowrap rounded border px-2.5 py-1 text-xs ${projectBacked && durableReview.presentation
            ? completionStatusClassName
            : showingHistoricalResult ? completionStatusClassName : "border-slate-200 bg-slate-100 text-slate-600"}`}>
            {projectBacked && durableReview.presentation
              ? durableReview.presentation.statusLabel
              : showingHistoricalResult ? completionStatusText : "待审核"}
          </span>
          {result && historyAccess.state === "READY" && progress && (
            !projectBacked && <F02ReviewHistoryDrawer progress={progress} result={result} />
          )}
          {!projectBacked && result && historyAccess.state !== "READY" && (
            <span
              className={`max-w-56 text-right text-xs ${historyAccess.state === "UNAVAILABLE" ? "text-amber-800" : "text-slate-600"}`}
              role={historyAccess.state === "UNAVAILABLE" ? "alert" : "status"}
            >
              {historyAccess.message}
            </span>
          )}
        </div>
      </header>

      <div className="flex-1 min-h-0">
        <ResizablePanelGroup direction={desktopPanels ? "horizontal" : "vertical"}>
          <ResizablePanel defaultSize={desktopPanels ? 60 : 52} minSize={desktopPanels ? 45 : 32}>
            {projectMaterials ? <ProjectMaterialsViewer
              tabs={displayProjectMaterials}
              focusedSource={focusedProjectSource}
              focusedMaterialId={focusedProjectMaterialId}
              focusedMaterialLocatorUnavailable={focusedProjectLocatorUnavailable}
              onClearSource={() => {
                setFocusedProjectSource(null);
                setFocusedProjectMaterialId(null);
                setFocusedProjectLocatorUnavailable(false);
              }}
            /> : <DocumentViewerPane
              documents={documentWorkspace.documents}
              activeDocument={documentWorkspace.activeDocument}
              activeDocumentId={documentWorkspace.activeDocumentId}
              activeObjectUrl={documentWorkspace.activeObjectUrl}
              viewerMode={documentWorkspace.viewerMode}
              activeEvidence={documentWorkspace.activeEvidence}
              mockText={activeMockText}
              mockMode={mockMode}
              readOnly={showcaseMode}
              onDocumentChange={documentWorkspace.setActiveDocumentId}
              onViewerModeChange={documentWorkspace.setViewerMode}
              onAddFile={documentWorkspace.addLocalFile}
              onRemoveFile={documentWorkspace.removeLocalFile}
              onClearEvidence={documentWorkspace.clearEvidence}
            />}
          </ResizablePanel>
          <ResizableHandle withHandle className="bg-slate-200 transition-colors hover:bg-blue-300" />
          <ResizablePanel defaultSize={desktopPanels ? 40 : 48} minSize={desktopPanels ? 32 : 32}>
            <aside className="h-full min-h-0 overflow-y-auto bg-[#F6F8FB] no-scrollbar">
              <div className="sticky top-0 z-20 flex items-center justify-between gap-3 border-b border-slate-200 bg-white px-4 py-3 backdrop-blur">
                <div className="flex items-center gap-2 min-w-0">
                  <span className="flex size-8 shrink-0 items-center justify-center rounded bg-blue-50 text-blue-700">
                    <Brain size={15} />
                  </span>
                  <div className="min-w-0">
                    <h3 className="text-[15px] font-semibold text-slate-900">合同智能审核</h3>
                    <p className="truncate text-xs text-slate-600">合同条款量化一致性审查</p>
                  </div>
                </div>
                <span className={`whitespace-nowrap rounded border px-2 py-1 text-xs ${currentPhaseClassName}`}>
                  {currentPhaseLabel}
                </span>
              </div>

              <div className="space-y-4 p-4">
                <AuditTargetSummary contract={contract} projectName={projectName} projectNo={projectNo}
                  projectCategory={projectCategory} initiatedBy={initiatedBy} createdAt={createdAt} />

              <section>
                {!historicalRunId && !preparationVisible && !showingHistoricalResult && (
                  <div className="rounded-lg border border-blue-200 bg-blue-50 p-4">
                    <p className="text-sm font-medium text-slate-900">合同智能审核</p>
                    <p className="mt-1 text-xs leading-5 text-slate-600">
                      {projectBacked
                        ? "选择本项目已完成整理的资料，同时启动文字语义比对与精确校验。"
                        : "核对合同金额、合同明细与中标通知书，逐项展示量化一致性过程和复核提示。"}
                    </p>
                    <button
                      type="button"
                      onClick={() => setReviewFlowOpened(true)}
                      disabled={!reviewSupported || contractLoading || latestResultLoading || !user?.userId}
                      className="mt-3 inline-flex items-center gap-2 rounded border border-blue-700 bg-blue-700 px-3 py-2 text-xs text-white hover:bg-blue-800 disabled:cursor-not-allowed disabled:opacity-50"
                    >
                      <Brain size={13} /> {projectBacked ? "确认审核材料" : "开始智能体审核"}
                    </button>
                  </div>
                )}

                {preparationVisible && !reviewLoading && !showingHistoricalResult && !projectBacked && (
                  <div className="rounded-lg border border-slate-200 bg-white p-4">
                    <div className="flex items-start justify-between gap-3">
                      <div>
                        <p className="text-sm font-medium text-slate-900">审核材料确认</p>
                        <p className="mt-1 text-xs leading-5 text-slate-600">
                          比对来源：合同正文、中标通知书；补充材料包括招标文件、投标文件及其他附件。
                        </p>
                      </div>
                      {!showcaseMode && !projectBacked && (
                        <AddReviewMaterial
                          onAddFile={documentWorkspace.addLocalFile}
                          documents={documentWorkspace.documents}
                          compact
                        />
                      )}
                    </div>
                    {canRetryReview && (
                      <div className="mt-3 rounded border border-amber-200 bg-amber-50 px-3 py-2 text-xs leading-5 text-amber-800">
                        检测到上一轮审核结果尚未确认。继续操作会接续该次审核，不会重复发起。
                      </div>
                    )}
                    {mockMode ? (
                      <button
                        type="button"
                        onClick={runShowcaseReview}
                        disabled={!reviewSupported || !contract || contractLoading || latestResultLoading || reviewLoading || !user?.userId}
                        className="mt-3 inline-flex w-full items-center justify-center gap-2 rounded border border-blue-700 bg-blue-700 px-3 py-2 text-xs text-white hover:bg-blue-800 disabled:cursor-not-allowed disabled:opacity-50"
                      >
                        {reviewLoading ? <LoaderCircle size={13} className="animate-spin" /> : <FileCheck2 size={13} />}
                        {canRetryReview
                            ? "继续/重试本次审核"
                            : restartRequired
                              ? "重新发起审核"
                            : showcaseMode ? "重新播放审核" : "确认材料并开始审核"}
                      </button>
                    ) : (
                      <div className="mt-3 space-y-2">
                        {!projectBacked && <button type="button" disabled
                          className="w-full cursor-not-allowed rounded border border-slate-200 bg-slate-100 px-3 py-2 text-xs text-slate-500">
                          材料解析服务待接入
                        </button>}
                        <button
                          type="button"
                          onClick={runReviewOperation}
                          disabled={!reviewSupported || !contract || contractLoading || latestResultLoading || reviewLoading || !user?.userId}
                          className="inline-flex w-full items-center justify-center gap-2 rounded border border-blue-200 bg-blue-50 px-3 py-2 text-xs text-blue-700 hover:bg-blue-100 disabled:cursor-not-allowed disabled:opacity-50"
                        >
                          <Database size={13} /> {canRetryReview
                            ? "继续/重试本次审核"
                            : restartRequired
                              ? "重新发起审核"
                              : "基于已录入数据验证审核"}
                        </button>
                        <p className="text-center text-xs leading-5 text-slate-600">
                          {projectBacked ? "本次审核读取当前项目已保存的合同与来源材料。" : "本次不会使用本地选择文件作为审核输入。"}
                        </p>
                      </div>
                    )}
                  </div>
                )}

                {(reviewLoading || reviewInProgress) && !projectBacked && (
                  <div className="flex items-start gap-3 rounded-lg border border-blue-200 bg-blue-50 p-4">
                    <LoaderCircle size={16} className="mt-0.5 shrink-0 animate-spin text-blue-700" />
                    <div>
                      <p className="text-sm font-medium text-slate-900">正在执行合同审核</p>
                      <p className="mt-1 text-xs leading-5 text-slate-600">
                        {reviewInProgress ? "该审核操作仍在处理中，请稍后继续确认。" : "等待完整审核结果返回；当前不表示每项核对均已完成。"}
                      </p>
                    </div>
                  </div>
                )}

                {!reviewSupported && (
                  <p className="mt-3 flex items-start gap-2 text-xs text-amber-800">
                    <AlertTriangle size={13} className="mt-0.5 shrink-0" />
                    当前合同暂不支持合同量化一致性审核；不会用其他页面数据冒充真实审核结果。
                  </p>
                )}
                {contractError && <p className="mt-3 text-xs text-red-700">合同读取失败：{contractError}</p>}
                {reviewError && <p className={`mt-3 text-xs ${reviewInProgress || projectBacked && restartRequired
                  ? "text-amber-800" : "text-red-700"}`}>{reviewError}</p>}
                {operationStoreError && <p className="mt-3 text-xs text-red-700">暂时无法恢复上次审核进度，请重新发起审核。</p>}
                {latestResultError && <p className="mt-3 text-xs text-amber-800">暂时无法读取最近一次审核结果，请稍后重试。</p>}
              </section>

              {!projectBacked && (preparationVisible || reviewLoading) && (
                <DocumentProcessingProgress
                  documents={documentWorkspace.documents}
                  mockMode={false}
                  mockStages={{}}
                />
              )}

              {integratedReviewVisible && projectMaterials && projectId && materialSelection && (
                <>
                  {missingFrozenMaterialCount > 0 && <p role="alert"
                    className="rounded border border-slate-300 bg-slate-50 px-3 py-2 text-xs text-slate-700">
                    本次冻结材料有 {missingFrozenMaterialCount} 份无法从当前项目读取；相关原件和定位暂不可用。
                  </p>}
                  <ProjectReviewMaterialPicker
                    tabs={displayProjectMaterials}
                    projectId={projectId}
                    selectedFileIds={materialSelection.selectedFileIds}
                    onSelectedFileIdsChange={setRequestedMaterialIds}
                    disabled={Boolean(durableReview.run)}
                  />
                  <IntelligentReviewPipeline
                    steps={intelligentReviewPipeline.steps}
                    canStart={canStartIntegratedReview}
                    running={integratedReviewRunning}
                    buttonLabel={durableReview.run
                      ? durableReview.restartRequired
                        ? "请重新发起审核"
                        : durableReview.presentation?.canContinue
                        ? "继续本次审核"
                        : durableReview.presentation?.canStartNew
                          ? "本次审核已结束"
                          : durableReview.run.status === "UNKNOWN"
                            ? "等待确认本次审核结果"
                            : "本次审核处理中"
                      : "开始智能审核"}
                    onStart={startIntegratedReview}
                  />
                  {!historicalRunId && durableReview.run && durableReview.presentation?.canStartNew && !integratedReviewRunning && (
                    <button type="button" onClick={() => {
                      durableReview.startNew();
                      setRequestedMaterialIds(undefined);
                      setReviewFlowOpened(true);
                    }} className="inline-flex w-full items-center justify-center gap-2 rounded border border-slate-200 bg-white px-3 py-2 text-xs text-slate-700 hover:bg-slate-50">
                      <RefreshCw size={13} /> 重新发起审核
                    </button>
                  )}
                </>
              )}

              {!projectBacked && (<ReviewProgressTimeline
                contractReady={Boolean(contract)}
                contractLoading={contractLoading}
                contractFailed={Boolean(contractError)}
                reviewLoading={reviewLoading}
                result={result}
                progress={progress}
                progressiveReplayState={progressiveReplayState}
                acknowledgementLoadingResultId={acknowledgementLoadingResultId}
                acknowledgementError={acknowledgementError}
                onAcknowledgeFinding={acknowledgeFinding}
                snapshot={executionSnapshot}
                sourcePreparationSections={sourcePreparationSections}
              />)}

              {progressLoading && (
                <p className="rounded border border-slate-200 bg-white px-3 py-2 text-xs text-slate-600" role="status">
                  正在读取审核进度事实…
                </p>
              )}
              {progressError && (
                <p className="rounded border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800" role="alert">
                  审核进度读取失败：{progressError}
                </p>
              )}

              {!projectBacked && <F02ReviewResultPanel
                contract={contract}
                result={result}
                progress={progress}
                progressiveReplayState={progressiveReplayState}
                reviewLoading={reviewLoading}
                resultLoading={latestResultLoading}
                resultUnavailable={Boolean(latestResultError)}
                acknowledgementLoadingResultId={acknowledgementLoadingResultId}
                acknowledgementError={acknowledgementError}
                onAcknowledgeFinding={projectBacked ? undefined : acknowledgeFinding}
                showAllPersistedFindings={projectBacked && Boolean(durableF02)}
                durableStatus={projectBacked ? durableF02Step?.status ?? null : null}
                durableStatusDetail={durableF02Step
                  ? reviewSafeReasonText(durableF02Step.safeReasonCode, durableF02Step.status)
                  : null}
                findingEvidence={documentMock?.findingEvidence}
                onFindingFocus={documentWorkspace.focusEvidence}
                onProjectedSourceFocus={projectBacked ? (source) => {
                  setFocusedProjectMaterialId(source.locatorAvailable === false ? source.fileId : null);
                  setFocusedProjectSource(source.locatorAvailable === false ? null : source);
                  setFocusedProjectLocatorUnavailable(source.locatorAvailable === false);
                } : onProjectedSourceFocus}
              />}

              {projectBacked && durableReview.run && eightCategoryProjection.categories && (
                <EightCategoryReviewPanel
                  categories={eightCategoryProjection.categories}
                  projectInfo={{
                    projectName,
                    projectNo,
                    contractName: contract?.contractName ?? contractTitle,
                    contractNo: contract?.contractNo,
                    supplier: contract?.partyB ?? supplier,
                    contractAmount: contract ? formatRmbAmount(contract.contractAmount) : null,
                  }}
                  runId={durableReview.run.runId}
                  runStatus={durableReview.run.status}
                  onEvidenceOpen={(evidence) => {
                    if (!projectId || !availableMaterialIds.has(evidence.fileId)) return;
                    setFocusedProjectMaterialId(evidence.fileId);
                    setFocusedProjectLocatorUnavailable(!evidence.locatorAvailable);
                    if (!evidence.locatorAvailable) {
                      setFocusedProjectSource(null);
                      return;
                    }
                    const locator = evidence.pageNumber !== null ? `pdf/page:${evidence.pageNumber}`
                      : evidence.startOffset !== null && evidence.endOffset !== null
                        ? `frozen/text:${evidence.startOffset}-${evidence.endOffset}`
                        : evidence.paragraphIndex !== null ? `frozen/paragraph:${evidence.paragraphIndex}`
                          : "frozen:validated-source";
                    setFocusedProjectSource({
                      role: "REVIEW_EVIDENCE",
                      projectId,
                      fileId: evidence.fileId,
                      fileName: evidence.fileName,
                      pageNumber: evidence.pageNumber,
                      paragraphIndex: evidence.paragraphIndex,
                      locator,
                      excerpt: evidence.excerpt,
                      locatorAvailable: true,
                    });
                  }}
                />
              )}
              {projectBacked && eightCategoryProjection.error && (
                <p role="alert" className="rounded border border-rose-200 bg-rose-50 px-3 py-2 text-xs text-rose-800">
                  {eightCategoryProjection.error}
                </p>
              )}

              {integratedReviewVisible && !projectBacked && (
                <section className="space-y-3 rounded-xl border border-violet-200 bg-violet-50/30 p-3"
                  aria-labelledby="ai-review-group-title">
                  <div>
                    <h3 id="ai-review-group-title" className="text-sm font-semibold text-slate-900">4. 智能语义审查</h3>
                    <p className="mt-1 text-xs text-slate-600">一次智能语义分析结果，按三个业务区域展示。</p>
                  </div>
                  <AiSemanticReviewPanel
                  result={textAnalysis}
                  breachResult={breachResult}
                  termResult={termResult}
                  loading={textAnalysisLoading}
                  error={textAnalysisError}
                  notice={textAnalysisNotice}
                  materials={textAnalysisMaterials}
                  canReanalyze={!historicalRunId && Boolean(materialSelection?.canStart)}
                  onReanalyze={() => { if (!historicalRunId) void analyzeContractText(); }}
                  onSourceOpen={(source) => {
                    setFocusedProjectMaterialId(source.id);
                    setFocusedProjectLocatorUnavailable(!source.locatorAvailable);
                    setFocusedProjectSource(source.locatorAvailable && source.excerpt && projectId ? {
                      role: "AI_EVIDENCE",
                      projectId,
                      fileId: source.id,
                      locator: "ai:validated-excerpt",
                      excerpt: source.excerpt,
                      pageNumber: null,
                      paragraphIndex: null,
                    } : null);
                  }}
                  durableReview={durableSemantic}
                  renderDurableAcknowledgement={closeoutContext ? (findingId, findingVersionNo) => (
                    <ContractFindingAcknowledgement context={closeoutContext}
                      findingId={findingId} findingVersionNo={findingVersionNo}
                      readOnly={Boolean(historicalRunId)} />
                  ) : undefined}
                  onDurableEvidenceOpen={(evidence) => {
                    if (!projectId) return;
                    const focus = createDurableEvidenceFocus(evidence, projectId, availableMaterialIds);
                    if (!focus) return;
                    setFocusedProjectMaterialId(focus.materialId);
                    setFocusedProjectLocatorUnavailable(focus.locatorUnavailable);
                    setFocusedProjectSource(focus.source);
                  }}
                  />
                </section>
              )}

              {projectBacked && integratedReviewVisible && durableSemantic && (
                <details className="rounded-xl border border-slate-200 bg-white p-3">
                  <summary className="cursor-pointer text-xs font-medium text-slate-600">审核记录操作与知悉</summary>
                  <div className="mt-3">
                    <AiSemanticReviewPanel
                      result={null}
                      loading={false}
                      error={null}
                      notice={null}
                      materials={textAnalysisMaterials}
                      canReanalyze={false}
                      onReanalyze={() => {}}
                      onSourceOpen={() => {}}
                      durableReview={durableSemantic}
                      renderDurableAcknowledgement={closeoutContext ? (findingId, findingVersionNo) => (
                        <ContractFindingAcknowledgement context={closeoutContext}
                          findingId={findingId} findingVersionNo={findingVersionNo}
                          readOnly={Boolean(historicalRunId)} />
                      ) : undefined}
                      onDurableEvidenceOpen={(evidence) => {
                        if (!projectId) return;
                        const focus = createDurableEvidenceFocus(evidence, projectId, availableMaterialIds);
                        if (!focus) return;
                        setFocusedProjectMaterialId(focus.materialId);
                        setFocusedProjectLocatorUnavailable(focus.locatorUnavailable);
                        setFocusedProjectSource(focus.source);
                      }}
                    />
                  </div>
                </details>
              )}

              {projectBacked && !historicalRunId && retryableSteps.length > 0 && (
                <section className="space-y-2 rounded-xl border border-amber-200 bg-amber-50 p-3"
                  aria-label="未完成审核环节处理">
                  <p className="text-xs text-amber-900">以下审核环节已确认未完成，可由您明确重新提交处理。</p>
                  {retryableSteps.map((step) => <button key={step.stepId} type="button"
                    onClick={() => void durableReview.retryFailedStep(step.stepId)}
                    disabled={durableReview.retryingStepId !== null}
                    className="w-full rounded border border-amber-300 bg-white px-3 py-2 text-xs text-amber-900 disabled:opacity-50">
                    重新处理{reviewSafeReasonText(step.safeReasonCode, step.status)}
                  </button>)}
                </section>
              )}

              {projectBacked && closeoutContext && durableReview.run && (
                <ContractReviewReportPanel context={closeoutContext} runStatus={durableReview.run.status}
                  readOnly={Boolean(historicalRunId)} />
              )}

              {integratedReviewVisible && assistanceStep && (
                <section className={`rounded-xl border p-4 ${assistanceStep.status === "ATTENTION"
                  ? "border-amber-200 bg-amber-50" : "border-emerald-200 bg-emerald-50"}`}
                  aria-labelledby="review-assistance-title">
                  <h3 id="review-assistance-title" className="text-sm font-semibold text-slate-900">5. 审核建议</h3>
                  <p className="mt-2 text-xs leading-5 text-slate-700">{assistanceStep.detail}</p>
                </section>
              )}

              {contractLoading && (
                <div className="flex items-center justify-center gap-2 rounded-lg border border-slate-200 bg-white p-5 text-xs text-slate-600">
                  <LoaderCircle size={14} className="animate-spin text-blue-700" /> 正在读取系统已录入的合同信息…
                </div>
              )}
              {contract && (
                <StructuredFactsPanel
                  contract={contract}
                  visibility={RECORDED_FACT_VISIBILITY}
                  projectName={projectName}
                  projectNo={projectNo}
                  initiatedBy={initiatedBy}
                  createdAt={createdAt}
                  evidenceByFact={documentMock?.factEvidence}
                  onEvidenceFocus={documentWorkspace.focusEvidence}
                />
              )}
              {showingHistoricalResult && !canRetryReview && !projectBacked && (
                <button
                  type="button"
                  onClick={showcaseMode ? runShowcaseReview : prepareNewReview}
                  disabled={!reviewSupported || contractLoading || latestResultLoading || !user?.userId}
                  className="inline-flex w-full items-center justify-center gap-2 rounded border border-slate-200 bg-white px-3 py-2 text-xs text-slate-700 hover:bg-slate-50 disabled:opacity-50"
                >
                  <RefreshCw size={13} /> {showcaseMode ? "重新播放审核" : "重新发起审核"}
                </button>
              )}
              </div>
            </aside>
          </ResizablePanel>
        </ResizablePanelGroup>
      </div>
    </div>
  );
}
