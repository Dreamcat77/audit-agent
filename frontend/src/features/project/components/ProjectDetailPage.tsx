import { ArrowLeft, FileText, Upload } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import type { ProjectDataSource } from "../data/projectDataSource";
import { archivedPackagePresentation, candidateFactGroups, candidateNavigation, candidateWorkspaceMode, canSelectNewUpload,
  filePresentation, formalContractForCandidate, formatProjectMoney, projectMaterialCandidateGroups,
  sourcePresentation } from "../projectDetailViewModel";
import { candidateSourceTarget, navigateToProjectEvidence,
  selectProjectSource } from "../projectSourceSelection";
import type { SelectedProjectSource } from "../types";
import { projectHeader } from "../projectListViewModel";
import { useProjectWorkspace } from "../useProjectWorkspace";
import { useAuth } from "../../../auth/AuthProvider";
import { ProjectEvidencePanel } from "./ProjectEvidencePanel";
import ContractAuditWorkspace from "../../contract/components/ContractReviewWorkspace";
import { HttpContractDataSource } from "../../contract/data/httpContractDataSource";
import { candidatePromotionLabel, factTypeLabel, fieldStateLabel, projectMaterialTabs } from "../projectMaterialPresentation";
import { buildProjectSourcePreparationSections } from "../projectReviewPresentation";
import { contractTypePresentation } from "../../contract/contractDetailsPresentation";
import type { Contract } from "../../contract/types";
import { ProjectFileProgressionPanel } from "./ProjectFileProgressionPanel";
import {ProjectFileProcessingPanel} from "./ProjectFileProcessingPanel";
import {
  clearContractReviewNavigation,
  loadContractReviewNavigation,
  saveContractReviewNavigation,
} from "../../contract/contractReviewNavigationPersistence";

const directF02DataSource = new HttpContractDataSource();

export function ProjectDetailPage({ projectId, dataSource, onBack, backLabel = "返回项目列表" }:
  { projectId: string; dataSource: ProjectDataSource; onBack: () => void; backLabel?: string }) {
  const { user } = useAuth();
  const state = useProjectWorkspace(projectId, dataSource, user?.userId ?? "");
  const chooser = useRef<HTMLInputElement>(null);
  const evidencePanelRef = useRef<HTMLElement>(null);
  const navigationStorage = typeof window === "undefined" ? null : window.sessionStorage;
  const [reviewContractId, setReviewContractId] = useState<string | null>(() => {
    if (!navigationStorage || !user?.userId) return null;
    const restored = loadContractReviewNavigation(navigationStorage, user.userId);
    return restored?.projectId === projectId ? restored.contractId : null;
  });
  const [selectedSource, setSelectedSource] = useState<SelectedProjectSource | null>(null);
  useEffect(() => {
    if (selectedSource) navigateToProjectEvidence(evidencePanelRef.current);
  }, [selectedSource]);
  useEffect(() => {
    if (!reviewContractId || !state.detail) return;
    if (state.detail.associatedContracts.some((value) => value.contractId === reviewContractId)) return;
    if (navigationStorage && user?.userId) clearContractReviewNavigation(navigationStorage, user.userId);
    setReviewContractId(null);
  }, [navigationStorage, projectId, reviewContractId, state.detail, user?.userId]);
  if (state.loading && !state.detail) return <p className="p-6 text-sm text-slate-500">正在读取项目…</p>;
  if (!state.detail) return <section className="p-6"><button onClick={onBack}>返回项目列表</button><p role="alert">{state.error}</p></section>;
  const header = projectHeader(state.detail.project);
  const materialProcessingActive = state.processingJobs?.items.some(job => job.status === "QUEUED" || job.status === "PROCESSING") ?? false;
  const candidate = state.candidate;
  const facts = candidate ? candidateFactGroups(candidate) : null;
  const materialCandidateGroups = projectMaterialCandidateGroups(state.detail);
  const candidateLinks = candidateNavigation(state.detail.candidates, candidate?.candidate.id ?? null);
  const formalContract = candidate
    ? formalContractForCandidate(state.detail, candidate.candidate.id) : null;
  const workspaceMode = candidate ? candidateWorkspaceMode(candidate.candidate, formalContract) : null;
  const reviewContract = reviewContractId
    ? state.detail.associatedContracts.find((contract) => contract.contractId === reviewContractId) ?? null : null;
  const projectReviewContract: Contract | null = reviewContract ? {
    id: reviewContract.contractId,
    contractNo: reviewContract.contractNo,
    contractName: reviewContract.contractName,
    contractType: reviewContract.contractType,
    partyA: reviewContract.partyA,
    partyB: reviewContract.partyB,
    partyBCreditCode: reviewContract.partyBCreditCode,
    contractAmount: reviewContract.contractAmount,
    amountInWords: reviewContract.amountInWords,
    status: reviewContract.status,
    versionNo: reviewContract.versionNo,
    createdAt: reviewContract.createdAt,
    updatedAt: reviewContract.createdAt,
    lineItems: [],
    awardNotice: null,
  } : null;
  const reviewMaterials = projectMaterialTabs(state.detail.files, projectId).map((tab) => ({
    ...tab,
    files: tab.files.map((file) => ({
      ...file,
      originalUrl: dataSource.originalContentUrl(projectId, file.id),
    })),
  }));
  const sourcePreparationSections = candidate
    ? buildProjectSourcePreparationSections({
        project: state.detail.project,
        candidate: candidate.candidate,
        fieldStates: candidate.fieldStates,
        files: state.detail.files,
      })
    : [];
  const archivedPackage = archivedPackagePresentation(state.detail.files);
  const businessFiles = state.detail.files.filter((file) => file.category !== "PACKAGE");
  const candidateSourceFile = candidate
    ? state.detail.files.find((file) => file.id === candidate.candidate.sourceContractFileId) ?? null : null;
  const canStartReview = candidateSourceFile
    ? candidateSourceFile.progression.availableActions.includes("START_REVIEW") : false;
  if (reviewContractId && reviewContract) return <ContractAuditWorkspace
    projectId={projectId}
    projectContract={projectReviewContract}
    contractId={reviewContractId}
    contractTitle={reviewContract.contractName}
    contractStatus={reviewContract.status}
    supplier={reviewContract.partyB}
    projectName={state.detail.project.name}
    projectNo={state.detail.project.projectNo}
    projectCategory={state.detail.project.category}
    onBack={() => {
      if (navigationStorage && user?.userId) clearContractReviewNavigation(navigationStorage, user.userId);
      setReviewContractId(null);
    }}
    dataSource={directF02DataSource}
    projectMaterials={reviewMaterials}
    sourcePreparationSections={sourcePreparationSections}
  />;
  return <section className="p-6 text-slate-900">
    <button type="button" onClick={onBack} className="mb-4 text-sm text-blue-700"><ArrowLeft size={14} className="inline mr-1"/>{backLabel}</button>
    <div className="rounded-lg border border-slate-200 bg-white p-5">
      <h1 className="text-xl font-semibold">{header.name}</h1>
      <p className="mt-2 text-sm text-slate-600">{header.projectNo}</p><p className="mt-1 text-sm text-slate-600">{header.category}</p>
      <input ref={chooser} hidden type="file" multiple accept=".pdf,.doc,.docx,.png,.jpg,.jpeg" onChange={(event) => {
        const files = event.target.files; if (files?.length) void state.upload(Array.from(files)); event.target.value = "";
      }}/>
      <button type="button" disabled={!canSelectNewUpload(state.busy, Boolean(state.retryUncertainUpload))}
        onClick={() => chooser.current?.click()}
        className="mt-4 rounded bg-blue-600 px-4 py-2 text-sm text-white"><Upload size={14} className="inline mr-1"/>补充项目资料</button>
      <span className="ml-3 text-xs text-slate-500">支持多选 PDF、DOC（Word 97-2003）、DOCX、PNG、JPG、JPEG；接收后查看解析进度</span>
      {state.retryUncertainUpload && <button type="button" disabled={state.busy} onClick={state.retryUncertainUpload}
        className="ml-2 rounded border border-amber-300 px-3 py-2 text-sm text-amber-800">重试未确认的资料</button>}
      {state.uploadNeedsOriginalFiles&&<button type="button" disabled={state.busy} onClick={()=>chooser.current?.click()}
        className="ml-2 rounded border border-amber-300 px-3 py-2 text-sm text-amber-800">重新选择原文件继续</button>}
      {state.uploadProgress.total > 0 && <p role="status" className="mt-2 text-xs text-slate-600">
        已接收 {state.uploadProgress.completed-state.uploadProgress.failed}/{state.uploadProgress.total} 个文件
        {state.uploadProgress.failed > 0 ? `，其中 ${state.uploadProgress.failed} 个未确认` : ""}
      </p>}
      {state.uploadNotice && <p role="status" className="mt-2 text-xs text-amber-700">{state.uploadNotice}</p>}
    </div>
    {archivedPackage && <p className="mt-3 text-sm text-slate-600">
      {archivedPackage.label}：{archivedPackage.filenames.join("、")}
    </p>}
    {state.error && <p role="alert" className="mt-4 rounded bg-red-50 p-3 text-sm text-red-700">{state.error}</p>}
    <ProjectFileProcessingPanel jobs={state.processingJobs} files={state.detail.files} error={state.processingError}/>
    {state.candidateLoadState === "ABSENT" && <section role="status"
      className="mt-4 rounded border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
      <p className="font-medium">合同信息尚未形成</p>
      <p className="mt-1">{materialProcessingActive ? "系统正在根据材料内容整理合同信息。" : "本次材料尚未形成可审核的合同信息。"}材料依据不足时不会创建正式合同。</p>
    </section>}
    {state.candidateLoadState === "ERROR" && <section role="alert"
      className="mt-4 rounded border border-red-200 bg-red-50 p-3 text-sm text-red-800">
      <p>{state.candidateError}</p>
      <button type="button" disabled={state.loading} onClick={() => void state.reload()}
        className="mt-2 rounded border border-red-300 bg-white px-3 py-1.5 text-red-800 disabled:opacity-50">
        重新读取合同信息
      </button>
    </section>}
    {selectedSource && <ProjectEvidencePanel ref={evidencePanelRef} source={selectedSource} files={state.detail.files}
      dataSource={dataSource} onClose={() => setSelectedSource(null)}/>} 

    <div className="mt-6 grid gap-4 md:grid-cols-2">
      {businessFiles.map((file) => { const view = filePresentation(file); return <article key={file.id} className="rounded-lg border border-slate-200 bg-white p-4">
        <div className="flex justify-between"><strong className="text-sm">{view.categoryLabel}</strong><span className="text-xs text-slate-500">{view.statusLabel}</span></div>
        <p className="mt-2 text-sm">{file.originalFilename}</p>
        <div className="mt-3 flex gap-3 text-sm"><a href={dataSource.originalContentUrl(projectId, file.id)} className="text-blue-700"><FileText size={13} className="inline mr-1"/>原始文件</a>
          {view.action && <button type="button" onClick={() => void state.recover(file.id)} className="text-amber-700">{view.action}</button>}</div>
        {view.parsedText ? <details className="mt-3 rounded bg-slate-50 p-3">
          <summary className="cursor-pointer text-xs font-medium text-slate-600">查看材料内容</summary>
          <pre className="mt-2 whitespace-pre-wrap select-text text-xs">{view.parsedText}</pre>
        </details> : <p className="mt-3 rounded bg-slate-50 p-3 text-xs text-slate-400">暂无可阅读内容</p>}
        {view.provenance.pageCountLabel && <p className="mt-2 text-xs text-slate-500">{view.provenance.pageCountLabel}</p>}
        <ProjectFileProgressionPanel file={file} factExtraction={state.factExtractions[file.id]}/>
      </article>; })}
    </div>

    {materialCandidateGroups.length > 0 && <section className="mt-6 rounded-lg border border-amber-200 bg-amber-50 p-5">
      <h2 className="font-semibold text-amber-950">其他材料识别信息</h2>
      <p className="mt-1 text-xs text-amber-800">系统从上传材料整理出的相关信息；来源位置可供需要时查看，证据不足的内容不会生成正式合同。</p>
      <div className="mt-4 grid gap-3 md:grid-cols-2">
        {materialCandidateGroups.map((group) => <article
          key={`${group.factType}:${group.groupKey ?? "UNGROUPED"}`}
          className="rounded border border-amber-100 bg-white p-3">
          <div className="flex items-start justify-between gap-3 text-sm">
            <strong>{group.factLabel}</strong>
            <span className={group.conflict ? "text-red-700" : "text-amber-700"}>
              {group.conflict ? "材料表述不一致" : "识别信息"}
            </span>
          </div>
          <p className="mt-1 text-xs text-slate-500">分组：{group.groupKey ?? "未分组"}</p>
          <ul className="mt-2 space-y-1 text-sm">
            {group.values.map((value) => <li key={value} className="rounded bg-slate-50 px-2 py-1">{value}</li>)}
          </ul>
          <div className="mt-3 space-y-1 border-t border-slate-100 pt-2">
            {group.sources.map((source, index) => <button
              key={`${source.sourceFileId}:${source.sourceLocator}:${index}`}
              type="button"
              title={source.excerpt}
              onClick={() => selectProjectSource(candidateSourceTarget(projectId, {
                fileId: source.sourceFileId,
                pageNumber: source.pageNumber,
                paragraphIndex: source.paragraphIndex,
                locator: source.sourceLocator,
                excerpt: source.excerpt,
              }, state.detail.files), setSelectedSource)}
              className="block text-left text-xs text-blue-700 disabled:text-slate-500">
              {source.sourceFileName} · {source.sourceLocator}
            </button>)}
          </div>
        </article>)}
      </div>
    </section>}

    {candidateLinks.length > 1 && <nav aria-label="合同候选" className="mt-6 flex flex-wrap gap-2">
      {candidateLinks.map((item) => <button key={item.id} type="button" disabled={state.busy || item.active}
        onClick={() => void state.selectCandidate(item.id)}
        className={`rounded border px-3 py-2 text-sm ${item.active ? "border-blue-500 bg-blue-50 text-blue-800" : "border-slate-200 bg-white text-slate-700"}`}>
        {item.label} · {item.promotionLabel}
      </button>)}
    </nav>}

    {candidate && facts && <section className="mt-6 rounded-lg border border-slate-200 bg-white p-5">
      <div className="flex justify-between"><h2 className="font-semibold">合同信息识别结果</h2><span className="text-xs text-slate-500">{candidatePromotionLabel(candidate.candidate.promotionState)}</span></div>
      <div className="mt-4 grid gap-3 md:grid-cols-2">
        {Object.entries(candidate.fieldStates).map(([type, status]) => { const source = sourcePresentation(candidate.evidence, type); return <div key={type} className="rounded border border-slate-100 p-3">
          <div className="flex justify-between text-sm"><span>{factTypeLabel(type)}</span><span>{fieldStateLabel(status)}</span></div>
          <button type="button" disabled={!source.source} title={source.source?.excerpt}
            onClick={() => { if (source.source) selectProjectSource(
              candidateSourceTarget(projectId, source.source, state.detail.files), setSelectedSource); }}
            className="mt-1 text-xs text-blue-700 disabled:text-slate-400">{source.label}</button>
        </div>; })}
      </div>
      <div className="mt-5"><h3 className="text-sm font-medium">合同金额：{formatProjectMoney(candidate.candidate.contractAmount)}</h3><p className="text-sm text-slate-600">金额大写：{candidate.candidate.amountInWords || "待识别"}</p></div>
      <div className="mt-4 space-y-2">{facts.lineItems.map((line) => <div key={line.id} className="rounded bg-slate-50 p-3 text-sm">
        <div>{line.itemKey || "未分组"} · {line.itemName || "待识别"}</div>
        <div className="mt-1 text-xs text-slate-600">数量：{line.quantity ?? "待识别"} · 单价：{formatProjectMoney(line.unitPrice)} · 申报金额：{formatProjectMoney(line.declaredAmount)}</div>
        <div className="mt-1 text-xs text-slate-500">{Object.entries(line.fieldStates).map(([key, value]) => `${factTypeLabel(key)}：${fieldStateLabel(value)}`).join(" · ")}</div>
      </div>)}</div>
      <div className="mt-4 text-sm">中标信息：{formatProjectMoney(candidate.candidate.awardAmount)} · {candidate.candidate.awardSupplier || "待识别"}</div>
    </section>}
    {state.promotionNotice && <p role="status" className="mt-4 rounded border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">{state.promotionNotice}</p>}
    {candidate && workspaceMode === "EXTRACTION_AND_FORMAL" && formalContract &&
      <section className="mt-4 rounded-lg border border-emerald-200 bg-emerald-50 p-4">
      <h2 className="font-semibold text-emerald-950">已确认合同信息</h2>
      <dl className="mt-3 grid grid-cols-[7rem_minmax(0,1fr)] gap-y-2 text-sm text-slate-800">
        <dt>合同编号</dt><dd>{formalContract.contractNo}</dd>
        <dt>合同名称</dt><dd>{formalContract.contractName}</dd>
        <dt>合同类型</dt><dd>{contractTypePresentation(formalContract.contractType)}</dd>
        <dt>甲方</dt><dd>{formalContract.partyA}</dd>
        <dt>乙方</dt><dd>{formalContract.partyB}</dd>
        <dt>乙方信用代码</dt><dd>{formalContract.partyBCreditCode || "—"}</dd>
        <dt>合同金额</dt><dd>{formatProjectMoney(formalContract.contractAmount)}</dd>
        <dt>金额大写</dt><dd>{formalContract.amountInWords || "—"}</dd>
      </dl>
      {canStartReview && <button type="button" onClick={() => {
        if (navigationStorage && user?.userId) saveContractReviewNavigation(
          navigationStorage, user.userId, projectId, formalContract.contractId,
        );
        setReviewContractId(formalContract.contractId);
      }}
        className="mt-3 rounded bg-emerald-700 px-4 py-2 text-sm text-white">开始合同审核</button>
      }
    </section>}
    {candidate && workspaceMode === "EXTRACTION_ONLY" && <p role="alert" className="mt-4 rounded border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
      候选已生成正式合同，但当前无法读取正式合同关联事实；已停止展示确认表单并禁止开始审核。
    </p>}
  </section>;
}
