import {
  AlertTriangle,
  ArrowRight,
  CalendarDays,
  CheckCircle2,
  FileWarning,
  Files,
  FolderOpen,
  Inbox,
  Plus,
  RefreshCw,
  RotateCcw,
  UploadCloud,
} from "lucide-react";
import type { ProjectDataSource } from "../data/projectDataSource";
import {
  intakeDraftRow,
  preparationDraftRow,
  projectLandingIntake,
  projectListRow,
  projectManagementPresentation,
  projectManagementSections,
} from "../projectListViewModel";
import { useProjectList } from "../useProjectList";

export function ProjectManagementLandingPage({ dataSource, onOpenProject, contractReviewEntry = false, embeddedContractQueue = false,actorId="" }:
  { dataSource: ProjectDataSource; onOpenProject: (projectId: string) => void; contractReviewEntry?: boolean; embeddedContractQueue?: boolean;actorId?:string }) {
  const state = useProjectList(dataSource,actorId);
  const intake = projectLandingIntake(state.intakeSnapshot.stage, state.intakeSnapshot.filename);
  const sections = projectManagementSections();
  const view = projectManagementPresentation(state.collectionsAvailable ? {
    projects: state.projects,
    intakeDrafts: state.intakeDrafts,
    projectTotal: state.projectTotal,
    intakeDraftTotal: state.intakeDraftTotal,
  } : null, new Date());

  return <section className="delivery-page space-y-6">
    {!embeddedContractQueue && <header className="delivery-page-header">
      <div>
        <h1 className="delivery-page-title">{contractReviewEntry ? "合同材料核验" : "项目管理"}</h1>
        <p className="delivery-page-subtitle">{contractReviewEntry ? "集中上传材料、核对归属和关键事实，再进入合同审核" : "资料预处理工作区 · 项目确认 · 合同候选处理"}</p>
      </div>
      <div className="flex flex-wrap gap-2">
        <button type="button" onClick={() => void state.reload()} disabled={state.loading}
          className="delivery-secondary-action disabled:opacity-60">
          <RefreshCw size={15}/>刷新
        </button>
        <label className={`delivery-primary-action ${intake.busy ? "cursor-not-allowed opacity-60" : "cursor-pointer"}`}>
          <UploadCloud size={16}/>{contractReviewEntry ? "导入合同审核资料包" : "导入项目资料包"}
          <input id="project-package-input" type="file" accept=".zip,application/zip,application/x-zip-compressed" className="sr-only"
            disabled={intake.busy}
            onChange={(event) => {
              const file = event.target.files?.[0];
              if (file) void state.startPackageIntake(file);
              event.target.value = "";
            }}/>
        </label>
      </div>
    </header>}
    {embeddedContractQueue && <div className="flex flex-wrap items-center justify-between gap-3">
      <div><h2 className="text-base font-semibold text-stone-800">待继续的材料核验</h2><p className="mt-1 text-xs text-stone-500">从真实持久化状态继续未完成步骤</p></div>
      <button type="button" onClick={() => void state.reload()} disabled={state.loading} className="delivery-secondary-action disabled:opacity-60"><RefreshCw size={15}/>刷新</button>
    </div>}

    {state.collectionError && <div role="alert" className="delivery-alert delivery-alert-danger"><span className="flex items-center gap-2"><AlertTriangle size={17}/>{state.collectionError}</span></div>}
    {state.error && <div role="alert" className="delivery-alert delivery-alert-danger"><span className="flex items-center gap-2"><AlertTriangle size={17}/>{state.error}</span></div>}

    <section className={`delivery-alert ${view.reminder ? "delivery-alert-warning" : "delivery-alert-neutral"}`} aria-labelledby="project-reminder-heading">
      <div className="flex items-center gap-3">
        <span className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-white/75"><AlertTriangle size={19}/></span>
        <div>
          <h2 id="project-reminder-heading" className="font-semibold">待处理提醒</h2>
          <p className="mt-1 text-sm">
            {view.reminder?.reason ?? (state.collectionsAvailable ? "当前暂无需要处理的异常资料" : "项目资料状态暂不可用，请刷新重试")}
          </p>
        </div>
      </div>
      {view.reminder && <button type="button" onClick={() => onOpenProject(view.reminder!.projectId)} className="delivery-primary-action">
        继续处理<ArrowRight size={15}/>
      </button>}
    </section>

    {!embeddedContractQueue && <section className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4" aria-label={contractReviewEntry ? "合同材料概览" : "项目资料概览"}>
      <SummaryCard label={contractReviewEntry ? "已确认审核对象" : "正式项目"} value={view.stats.formalTotal} tone="success"/>
      <SummaryCard label={contractReviewEntry ? "待核验资料" : "预处理资料"} value={view.stats.loadedDrafts} tone="warning"/>
      <SummaryCard label="异常资料" value={view.stats.loadedAnomalies} tone="danger"/>
      <SummaryCard label="已整理资料" value={view.stats.loadedMaterials} tone="neutral"/>
    </section>}

    {!embeddedContractQueue && <section className="delivery-surface delivery-summary-card">
      <span className="delivery-icon-chip"><CalendarDays size={18}/></span>
      <div className="min-w-0 flex-1">
        <h2 className="font-semibold text-stone-800">{contractReviewEntry ? "今日材料概要" : "今日项目概要"}</h2>
        <p className="mt-1 text-sm text-stone-500" title="当前数据基于已加载记录统计">{contractReviewEntry ? "合同审核材料动态" : "项目资料动态"}</p>
      </div>
      <div className="grid shrink-0 grid-cols-2 gap-8 text-right">
        <div><p className="text-xs text-stone-500">今日新增正式项目</p><strong className="mt-1 block text-2xl text-stone-800">{view.today.formalProjects ?? "--"}</strong></div>
        <div><p className="text-xs text-stone-500">今日新增预处理资料</p><strong className="mt-1 block text-2xl text-stone-800">{view.today.loadedDrafts ?? "--"}</strong></div>
      </div>
    </section>}

    {!embeddedContractQueue && <a href="#intake-drafts-heading" className="delivery-business-entry">
      <span className="flex items-center gap-3">
        <span className="delivery-icon-chip"><Files size={18}/></span>
        <span><strong className="block text-base text-stone-800">{contractReviewEntry ? "查看合同材料处理结果" : "查看已整理的项目资料"}</strong><span className="mt-1 block text-xs text-stone-500">打开系统自动整理的材料与结果</span></span>
      </span>
      <span className="delivery-business-entry-icon"><ArrowRight size={17}/></span>
    </a>}

    {!embeddedContractQueue && <section className="delivery-intake-workspace">
      <div className="flex flex-col items-center text-center">
        <span className="flex size-14 items-center justify-center rounded-2xl bg-orange-100 text-orange-600"><UploadCloud size={26}/></span>
        <h2 className="mt-4 text-lg font-semibold text-stone-800">{contractReviewEntry ? "导入合同审核材料" : "导入项目资料"}</h2>
        <p className="mt-2 max-w-3xl text-sm leading-6 text-stone-500">{intake.description}</p>
        <p className="mt-3 text-xs text-stone-500" aria-live="polite">{intake.stageLabel}{intake.filename ? ` · ${intake.filename}` : ""}</p>
        <div className="mt-5 flex flex-wrap justify-center gap-3">
          <label htmlFor="project-package-input" className={`delivery-primary-action ${intake.busy ? "cursor-not-allowed opacity-60" : "cursor-pointer"}`}>
            <UploadCloud size={16}/>{contractReviewEntry ? "导入合同审核资料包" : "导入项目资料包"}
          </label>
          {intake.retryable && state.retryPackageIntake && <button type="button"
            onClick={() => void state.retryPackageIntake!()} className="delivery-secondary-action">
            <RotateCcw size={15}/>使用同一操作重试
          </button>}
          <button type="button" disabled={state.creating || intake.busy || intake.retryable}
            onClick={() => void state.createBlank().then((project) => project && onOpenProject(project.id))}
            className="delivery-secondary-action disabled:opacity-60">
            <Plus size={15}/>{intake.secondaryAction}
          </button>
        </div>
        <p className="mt-3 text-xs text-stone-400">支持现有 ZIP 项目资料包流程；处理结果以系统反馈为准</p>
      </div>
    </section>}

    {!embeddedContractQueue && state.completedIntake && <section className="delivery-surface border-emerald-200 bg-emerald-50/60 p-5">
      <div className="flex items-start gap-3"><span className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-white text-emerald-600"><CheckCircle2 size={20}/></span><div className="flex-1">
        <h2 className="font-semibold text-emerald-900">预处理完成</h2>
        {(state.completedIntake.projectName || state.completedIntake.projectNo) && <p className="mt-1 text-sm text-stone-700">
          {state.completedIntake.projectName && <span>{state.completedIntake.projectName}</span>}
          {state.completedIntake.projectName && state.completedIntake.projectNo && <span> · </span>}
          {state.completedIntake.projectNo && <span>项目编号：{state.completedIntake.projectNo}</span>}
        </p>}
        <p className="mt-2 text-sm text-stone-600">
          已整理文件 {state.completedIntake.processedFileCount} 份 · 可阅读内容 {state.completedIntake.textParsedFileCount + state.completedIntake.ocrFileCount} 份
          {state.completedIntake.failedFileCount > 0 ? ` · ${state.completedIntake.failedFileCount} 份材料建议检查` : ""}
        </p>
        <button type="button" onClick={() => onOpenProject(state.completedIntake!.projectId)} className="delivery-primary-action mt-4">查看项目资料</button>
      </div></div>
    </section>}
    {!embeddedContractQueue&&state.acceptedIntake&&<section role="status" className="delivery-surface border-blue-200 bg-blue-50 p-4">
      <p className="text-sm">{state.acceptedIntake.filename??"资料包"}已接收，系统正在后台解析并自动整理结果。</p>
      <button type="button" onClick={()=>onOpenProject(state.acceptedIntake!.projectId)} className="delivery-primary-action mt-3">查看真实处理进度</button>
    </section>}

    {state.loading ? <section className="delivery-surface p-8 text-center text-sm text-stone-500">正在读取项目资料…</section> : !state.collectionsAvailable
      ? <section className="delivery-surface p-8 text-center text-sm text-stone-500">项目资料暂不可用，请刷新重试。</section> : <>
      {contractReviewEntry && <section className="delivery-surface p-5" aria-labelledby="preparation-drafts-heading">
        <div className="mb-4 flex flex-wrap items-baseline justify-between gap-2">
          <h2 id="preparation-drafts-heading" className="text-base font-semibold text-stone-800">普通材料准备中</h2>
          <p className="text-xs text-stone-500">本页显示 {state.preparationDrafts.length} 条 · 总数 {state.preparationDraftTotal ?? "--"} 条</p>
        </div>
        <div className="grid gap-4 xl:grid-cols-2">
          {state.preparationDrafts.map((draft) => { const row = preparationDraftRow(draft); return <article key={row.projectId}
            className="rounded-2xl border border-stone-200 bg-white p-5 shadow-sm">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2"><span className="font-semibold text-stone-800">{row.title}</span><span className="delivery-status-pill" data-tone="warning">待继续</span></div>
                <div className="mt-2 text-xs text-stone-500">{row.uploadedSummary}</div>
                <time className="mt-3 block text-xs text-stone-400" dateTime={row.createdAt}>{row.createdAt}</time>
              </div>
              <button type="button" onClick={() => onOpenProject(row.projectId)} className="delivery-primary-action">{row.action}</button>
            </div>
          </article>; })}
          {!state.preparationDrafts.length && <div className="delivery-empty-state col-span-full"><Inbox size={24}/><strong className="text-sm text-stone-600">暂无普通材料准备记录</strong><span className="text-xs">通过普通多文件入口上传的未确认材料会显示在这里</span></div>}
        </div>
        <DraftPager page={state.preparationDraftPage} size={state.preparationDraftPageSize}
          total={state.preparationDraftTotal} onPage={state.loadPreparationDraftPage}/>
      </section>}
      <section className="delivery-surface p-5" aria-labelledby="intake-drafts-heading">
        <div className="mb-4 flex flex-wrap items-baseline justify-between gap-2">
          <h2 id="intake-drafts-heading" className="text-base font-semibold text-stone-800">{contractReviewEntry ? "资料包处理中的材料" : "预处理资料"}</h2>
          <p className="text-xs text-stone-500">本页显示 {view.stats.loadedDrafts} 条 · 资料总数 {state.intakeDraftTotal ?? "--"} 条</p>
        </div>
        <div className="grid gap-4 xl:grid-cols-2">
          {state.intakeDrafts.map((draft) => { const row = intakeDraftRow(draft); return <article key={row.projectId}
            className="rounded-2xl border border-stone-200 bg-white p-5 shadow-sm">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2"><span className="truncate font-semibold text-stone-800">{row.packageFilename}</span><span className="delivery-status-pill" data-tone={row.warning ? "warning" : "success"}>{row.statusLabel}</span></div>
                <div className="mt-2 text-xs text-stone-500">{row.parsedSummary}</div>
                {row.candidateProjectName && <div className="mt-3 text-sm text-stone-700">候选项目名称：{row.candidateProjectName}</div>}
                {row.candidateProjectNo && <div className="mt-1 text-sm text-stone-700">候选项目编号：{row.candidateProjectNo}</div>}
                {row.warning && <div className="mt-4 flex items-center gap-2 rounded-xl border-l-4 border-amber-400 bg-amber-50 px-3 py-2 text-xs text-amber-800"><FileWarning size={14}/>{row.warning}</div>}
                <time className="mt-3 block text-xs text-stone-400" dateTime={row.createdAt}>{row.createdAt}</time>
              </div>
              <div className="flex flex-wrap gap-2">
                {contractReviewEntry
                  ? <button type="button" onClick={() => onOpenProject(row.projectId)} className="delivery-primary-action">继续材料核验</button>
                  : <><button type="button" onClick={() => onOpenProject(row.projectId)} className="delivery-secondary-action">继续处理</button><button type="button" onClick={() => onOpenProject(row.projectId)} className="delivery-primary-action">用于新建项目</button></>}
              </div>
            </div>
          </article>; })}
          {!state.intakeDrafts.length && <div className="delivery-empty-state col-span-full"><Inbox size={24}/><strong className="text-sm text-stone-600">暂无已预处理资料</strong><span className="text-xs">可从上方导入项目资料包，完成后在这里继续处理</span></div>}
        </div>
        <DraftPager page={state.intakeDraftPage} size={state.intakeDraftPageSize}
          total={state.intakeDraftTotal} onPage={state.loadIntakeDraftPage}/>
      </section>

      <section className="delivery-surface p-5" aria-labelledby="formal-projects-heading">
        <div className="mb-4 flex items-center gap-3"><span className="delivery-icon-chip"><FolderOpen size={18}/></span><h2 id="formal-projects-heading" className="text-base font-semibold text-stone-800">{contractReviewEntry ? "已确认审核对象" : sections.formalProjects}</h2></div>
        <div className="grid gap-3 xl:grid-cols-2">
          {state.projects.map((project) => { const row = projectListRow(project); return <button key={row.id} type="button"
            onClick={() => onOpenProject(row.id)} className="rounded-2xl border border-stone-200 bg-stone-50/70 p-4 text-left transition hover:border-orange-300 hover:bg-orange-50/40">
            <div className="flex items-center justify-between gap-3"><div className="font-semibold text-stone-800">{row.name}</div><span className="delivery-status-pill" data-tone="success">{contractReviewEntry ? "归属已确认" : "正式项目"}</span></div>
            <div className="mt-2 text-xs text-stone-500">项目编号：{row.projectNo} · 类目：{row.category}</div>
            <time className="mt-2 block text-xs text-stone-400" dateTime={row.updatedAt}>{row.updatedAt}</time>
          </button>; })}
          {!state.projects.length && <div className="delivery-empty-state col-span-full"><FolderOpen size={24}/><strong className="text-sm text-stone-600">暂无正式项目</strong><span className="text-xs">完成资料确认后，正式项目将在这里展示</span></div>}
        </div>
      </section>
    </>}
  </section>;
}

function DraftPager({ page, size, total, onPage }: {
  page: number; size: number; total: number | null; onPage: (page: number) => Promise<void>;
}) {
  if (total === null || (total <= size && page <= 1)) return null;
  const pageCount = Math.ceil(total / size);
  return <nav className="mt-4 flex items-center justify-end gap-3 text-xs text-stone-500" aria-label="待继续分页">
    <button type="button" className="delivery-secondary-action" disabled={page <= 1}
      onClick={() => void onPage(page - 1)}>上一页</button>
    <span>第 {page} / {pageCount} 页</span>
    <button type="button" className="delivery-secondary-action" disabled={page >= pageCount}
      onClick={() => void onPage(page + 1)}>下一页</button>
  </nav>;
}

function SummaryCard({ label, value, tone }: { label: string; value: number | null; tone: "danger" | "warning" | "success" | "neutral" }) {
  return <section className="delivery-surface delivery-stat-card" data-tone={tone}>
    <p className="text-sm text-stone-500">{label}</p>
    <strong className="delivery-stat-value">{value ?? "--"}</strong>
  </section>;
}
