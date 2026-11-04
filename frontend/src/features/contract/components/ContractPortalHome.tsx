import { useEffect,useMemo,useRef, useState, type ReactNode } from "react";
import { AlertTriangle, Building2, History, RotateCcw, UploadCloud } from "lucide-react";
import { userFacingError } from "../../../api/userFacingError";
import { ProjectWorkspace } from "../../project/components/ProjectWorkspace";
import type { ProjectDataSource } from "../../project/data/projectDataSource";
import { httpProjectDataSource } from "../../project/data/httpProjectDataSource";
import type { DirectF02AuditTaskRow, ShowcaseAuditTaskRow } from "../auditTaskViewModel";
import { createContractMaterialIntakeOperation, type ContractMaterialIntakeOperation } from "../contractMaterialIntakeOperation";
import { contractPortalUploadNotice, type ContractPortalUploadNotice } from "../contractPortalPresentation";
import ContractOverviewList from "./ContractOverviewList";
import {useAuth} from "../../../auth/AuthProvider";
import {ContractAnalysisWorkspace} from "./ContractAnalysisWorkspace";

export type ContractPortalTab = "RECORDS" | "PENDING";

export interface ContractPortalContentProps {
  activeTab: ContractPortalTab;
  busy: boolean;
  notice: ContractPortalUploadNotice | null;
  error?: string | null;
  onTabChange: (tab: ContractPortalTab) => void;
  onSelectFiles: (files: readonly File[]) => void;
  onRetry: () => void;
  onOpenNoticeProject: () => void;
  recordsContent: ReactNode;
  pendingContent: ReactNode;
  needsOriginalFiles?:boolean;
}

export function ContractPortalContent({
  activeTab, busy, notice, error, onTabChange, onSelectFiles, onRetry,
  onOpenNoticeProject, recordsContent, pendingContent,needsOriginalFiles=false,
}: ContractPortalContentProps) {
  return <section className="delivery-page space-y-5">
    <header className="delivery-page-header">
      <div><h1 className="delivery-page-title">合同审核</h1><p className="delivery-page-subtitle">上传并核验材料，确认正式合同后发起审核</p></div>
      <div className="flex flex-wrap gap-2">
        <button type="button" disabled className="delivery-secondary-action cursor-not-allowed opacity-55" title="OA 集成暂未接入">
          <Building2 size={15}/>从 OA 导入（暂未接入）
        </button>
        <label className={`delivery-primary-action ${busy ? "cursor-not-allowed opacity-60" : "cursor-pointer"}`}>
          <UploadCloud size={16}/>{busy ? "正在上传文件…" : needsOriginalFiles?"选择原文件继续":"上传材料"}
          <input type="file" multiple className="sr-only" disabled={busy}
            accept=".zip,.pdf,.doc,.docx,.png,.jpg,.jpeg,application/zip,application/pdf,application/msword,image/png,image/jpeg"
            onChange={(event) => {
              onSelectFiles(event.target.files ? Array.from(event.target.files) : []);
              event.target.value = "";
            }}/>
        </label>
      </div>
    </header>

    {error && <div role="alert" className="delivery-alert delivery-alert-danger"><span className="flex items-center gap-2"><AlertTriangle size={17}/>{error}</span></div>}
    {notice && <div className={`delivery-alert ${notice.tone === "success" ? "delivery-alert-neutral" : "delivery-alert-warning"}`} aria-live="polite">
      <span>{notice.message}</span>
      <span className="flex flex-wrap gap-2">
        {notice.retryable && <button type="button" onClick={onRetry} disabled={busy} className="delivery-secondary-action"><RotateCcw size={14}/>继续原操作</button>}
        {notice.projectId && <button type="button" onClick={onOpenNoticeProject} className="delivery-secondary-action">查看材料处理进度</button>}
      </span>
    </div>}

    <nav className="contract-portal-tabs" aria-label="合同审核中心">
      <button type="button" data-active={activeTab === "RECORDS"} aria-current={activeTab === "RECORDS" ? "page" : undefined} onClick={() => onTabChange("RECORDS")}><History size={15}/>审核记录</button>
      <button type="button" data-active={activeTab === "PENDING"} aria-current={activeTab === "PENDING" ? "page" : undefined} onClick={() => onTabChange("PENDING")}><UploadCloud size={15}/>待继续</button>
    </nav>

    <div>{activeTab === "RECORDS" ? recordsContent : pendingContent}</div>
  </section>;
}

export function ContractPortalHome(props:{dataSource?:ProjectDataSource;onOpenShowcase:(row:ShowcaseAuditTaskRow)=>void;onOpenDirectF02:(row:DirectF02AuditTaskRow)=>void;onOpenProject:(projectId:string)=>void}){
  const [legacy,setLegacy]=useState(false);
  if(!legacy&&!props.dataSource)
    return <ContractAnalysisWorkspace onOpenLegacy={()=>setLegacy(true)}/>;
  return <LegacyContractPortalHome {...props}/>;
}

function LegacyContractPortalHome({
  dataSource = httpProjectDataSource,
  onOpenShowcase,
  onOpenDirectF02,
  onOpenProject,
}: {
  dataSource?: ProjectDataSource;
  onOpenShowcase: (row: ShowcaseAuditTaskRow) => void;
  onOpenDirectF02: (row: DirectF02AuditTaskRow) => void;
  onOpenProject: (projectId: string) => void;
}) {
  const {user}=useAuth();
  const [activeTab, setActiveTab] = useState<ContractPortalTab>("RECORDS");
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<ContractPortalUploadNotice | null>(null);
  const [error, setError] = useState<string | null>(null);
  const alive=useRef(true);
  const operation=useMemo(()=>{
    if(typeof window==="undefined"||!user?.userId)return createContractMaterialIntakeOperation({dataSource});
    return createContractMaterialIntakeOperation({dataSource,retained:{actorId:user.userId,storage:{
      get length(){return window.localStorage.length;},key:index=>window.localStorage.key(index),
      getItem:key=>window.localStorage.getItem(key),setItem:(key,value)=>window.localStorage.setItem(key,value),
      removeItem:key=>window.localStorage.removeItem(key)}}});
  },[dataSource,user?.userId]);
  const currentOperation=useRef(operation);currentOperation.current=operation;
  const [needsOriginalFiles,setNeedsOriginalFiles]=useState(false);
  useEffect(()=>{
    alive.current=true;let current=true;
    void operation.restore().then(result=>{
      if(!current||!result)return;
      setNotice(contractPortalUploadNotice(result));setNeedsOriginalFiles(operation.snapshot().needsFileSelection??false);
    }).catch(cause=>{if(current)setError(userFacingError(cause,"上次上传暂时无法核对，请先继续原操作。"));});
    return()=>{current=false;alive.current=false;};
  },[operation]);

  const settle = async (promise: ReturnType<ContractMaterialIntakeOperation["start"]>) => {
    setBusy(true); setError(null);
    try {
      const result = await promise;
      if(!alive.current||currentOperation.current!==operation)return;
      const nextNotice = contractPortalUploadNotice(result);
      setNotice(nextNotice);
      if (result.kind === "COMPLETED") onOpenProject(result.projectId);
      setNeedsOriginalFiles(operation.snapshot().needsFileSelection??false);
    } catch (cause) {
      if(alive.current&&currentOperation.current===operation)setError(userFacingError(cause, "材料暂时无法保存，请检查文件后重试。"));
    } finally {
      if(alive.current&&currentOperation.current===operation)setBusy(false);
    }
  };

  return <ContractPortalContent activeTab={activeTab} busy={busy} notice={notice} error={error}
    needsOriginalFiles={needsOriginalFiles}
    onTabChange={setActiveTab}
    onSelectFiles={(files) => { if (files.length > 0) {
      try{void settle(operation.snapshot().pendingOperation?operation.retry(files):operation.start(files));}
      catch(cause){setError(userFacingError(cause,"上次上传暂时无法核对，请先继续原操作。"));}
    } }}
    onRetry={() => void settle(operation.retry())}
    onOpenNoticeProject={() => { if (notice?.projectId) onOpenProject(notice.projectId); }}
    recordsContent={<ContractOverviewList embedded onOpenShowcase={onOpenShowcase}
      onOpenDirectF02={onOpenDirectF02} onOpenPending={() => setActiveTab("PENDING")}/>}
    pendingContent={<ProjectWorkspace dataSource={dataSource} entryMode="CONTRACT_REVIEW" embeddedQueue/>}/>;
}
