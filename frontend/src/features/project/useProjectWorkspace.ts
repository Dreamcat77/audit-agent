import { useCallback, useEffect, useMemo,useRef, useState } from "react";
import { progressionRecoveryUserMessage, userFacingError } from "../../api/userFacingError";
import type { ProjectDataSource,ProjectFileJobs } from "./data/projectDataSource";
import { createBrowserRetainedOperation, createRetainedOperation, createSingleFlightGuard } from
  "./projectOperation";
import { recoverProjectFileAndReload } from "./projectFileRecovery";
import type { CandidateDetail, ContractFactExtractionProjection, DocumentRole, ProjectDetail, ProjectFile,
  ProjectIdentityResolution } from "./types";
import type { CandidateConfirmation, ConfirmedContractPayload } from "./candidateConfirmation";
import { discoverLatestCurrentConfirmation, retrySavedPromotion,
  saveCandidateConfirmation } from "./candidateConfirmationFlow.ts";
import { provenPromotedContractId } from "./projectDetailViewModel";
import type { ProjectMetadataConfirmation } from "./data/projectDataSource";
import { confirmMetadataAndReload } from "./projectMetadataConfirmation";
import { acceptedSupplementFiles, uploadProjectSupplements,
  type SupplementUploadOperation } from "./projectSupplementUpload";
import { hasProgressionAction } from "./projectProgressionPresentation";
import { candidateSupportAuthorityPresentation } from "./candidateSupportAuthorityPresentation.ts";
import { replaceCandidateSupportAndReconcile } from "./candidateSupportAuthorityFlow.ts";
import { createSessionStorageCandidateSupportOperationPersistence, loadCandidateSupportOperation,
  loadOrCreateCandidateSupportOperation, parseCandidateSupportAuthorityClassification,
  type CandidateSupportOperation } from "./candidateSupportAuthorityOperation.ts";
import { createBrowserProgressionRecoveryPersistence, findRetainedProgressionRecovery,
  resumeProjectFileProgression, type ProjectProgressionRecoveryPersistence } from
  "./projectProgressionRecovery.ts";
import {createContractMaterialIntakeOperation} from "../contract/contractMaterialIntakeOperation.ts";

export function useProjectWorkspace(projectId: string, dataSource: ProjectDataSource, actorId = "") {
  const workspaceIdentity=useMemo(()=>({}),[projectId,dataSource,actorId]);
  const activeWorkspace=useRef(workspaceIdentity);activeWorkspace.current=workspaceIdentity;
  const [detail, setDetail] = useState<ProjectDetail | null>(null);
  const [candidate, setCandidate] = useState<CandidateDetail | null>(null);
  const [candidateLoadState, setCandidateLoadState] = useState<"LOADING" | "ABSENT" | "READY" | "ERROR">("LOADING");
  const [candidateError, setCandidateError] = useState<string | null>(null);
  const [savedConfirmation, setSavedConfirmation] = useState<CandidateConfirmation | null>(null);
  const [loading, setLoading] = useState(true);
  const [backgroundRefreshing, setBackgroundRefreshing] = useState(false);
  const [draftReloadVersion, setDraftReloadVersion] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [promotionLoading, setPromotionLoading] = useState(false);
  const [promotionError, setPromotionError] = useState<string | null>(null);
  const [promotionNotice, setPromotionNotice] = useState<string | null>(null);
  const [metadataLoading, setMetadataLoading] = useState(false);
  const [metadataError, setMetadataError] = useState<string | null>(null);
  const [metadataConflict, setMetadataConflict] = useState<string | null>(null);
  const [promotedContractId, setPromotedContractId] = useState<string | null>(null);
  const [uploadProgress, setUploadProgress] = useState({ total: 0, completed: 0, failed: 0 });
  const [uploadNotice, setUploadNotice] = useState<string | null>(null);
  const [supportLoading, setSupportLoading] = useState(false);
  const [supportPending, setSupportPending] = useState(false);
  const [supportError, setSupportError] = useState<string | null>(null);
  const [progressionRecoveryPendingFileIds, setProgressionRecoveryPendingFileIds] = useState<string[]>([]);
  const [factExtractions, setFactExtractions] = useState<Record<string, ContractFactExtractionProjection>>({});
  const [processingJobs,setProcessingJobs]=useState<ProjectFileJobs|null>(null);
  const [processingError,setProcessingError]=useState<string|null>(null);
  const [uploadPending,setUploadPending]=useState(false);
  const [uploadNeedsOriginalFiles,setUploadNeedsOriginalFiles]=useState(false);
  const processingRetries=useRef(new Set<string>());
  const durableUpload=useMemo(()=>{
    if(!actorId||!dataSource.uploadAsync||!dataSource.getUploadOperation||typeof window==="undefined")return null;
    return createContractMaterialIntakeOperation({dataSource,retained:{actorId,projectId,storage:{
      get length(){return window.localStorage.length;},key:index=>window.localStorage.key(index),
      getItem:key=>window.localStorage.getItem(key),setItem:(key,value)=>window.localStorage.setItem(key,value),
      removeItem:key=>window.localStorage.removeItem(key)}}});
  },[actorId,projectId,dataSource]);
  const supportFlight = useRef(createSingleFlightGuard());
  const supportReloadRequired = useRef(false);
  const metadataOperation = useRef(createRetainedOperation());
  const metadataFlight = useRef(createSingleFlightGuard());
  const confirmationOperations = useRef(new Map<string, ReturnType<typeof createRetainedOperation>>());
  const promotionOperations = useRef(new Map<string, ReturnType<typeof createRetainedOperation>>());
  const roleOperations = useRef(new Map<string, ReturnType<typeof createRetainedOperation>>());
  const identityOperations = useRef(new Map<string, ReturnType<typeof createRetainedOperation>>());
  const progressionFlights = useRef(new Set<string>());
  const promotionFlight = useRef(createSingleFlightGuard());
  const retainedUploads = useRef<SupplementUploadOperation[]>([]);
  const activeCandidateId = useRef<string | null>(null);
  const candidateLoadGeneration = useRef(0);
  const progressionPersistence = useRef<ProjectProgressionRecoveryPersistence | null>(null);
  if (!progressionPersistence.current) {
    let storage: Storage | undefined;
    try { storage = typeof window === "undefined" ? undefined : window.localStorage; }
    catch { /* A browser can deny storage access; the in-memory fallback still preserves this page attempt. */ }
    progressionPersistence.current = createBrowserProgressionRecoveryPersistence(storage);
  }

  const rediscoverProgressionRecoveries = useCallback((next: ProjectDetail) => {
    if (!actorId) { setProgressionRecoveryPendingFileIds([]); return; }
    try {
      const pending = next.files.filter((file) => Boolean(findRetainedProgressionRecovery(
        progressionPersistence.current!, actorId, projectId, file.id))).map((file) => file.id);
      setProgressionRecoveryPendingFileIds(pending);
    } catch (cause) {
      setError(userFacingError(cause, "接续操作身份无法核对，已停止提交，请重新加载后核对。"));
    }
  }, [actorId, projectId]);

  const supportPersistence = useCallback(() =>
    createSessionStorageCandidateSupportOperationPersistence(window.sessionStorage), []);
  const rediscoverSupport = useCallback((candidateId: string) => {
    try {
      setSupportPending(Boolean(actorId && loadCandidateSupportOperation(
        supportPersistence(), actorId, projectId, candidateId)));
    } catch (cause) {
      setSupportPending(true);
      setSupportError(userFacingError(cause, "材料关联操作无法读取，已停止修改，请重新加载后核对。"));
    }
  }, [actorId, projectId, supportPersistence]);

  const loadCandidate = useCallback(async (next: ProjectDetail, preferredCandidateId?: string,
    generation = ++candidateLoadGeneration.current, background = false) => {
    if (generation === candidateLoadGeneration.current && !background) {
      setCandidateLoadState("LOADING");
      setCandidateError(null);
    }
    const selected = next.candidates.find((value) => value.id === (preferredCandidateId ?? activeCandidateId.current))
      ?? next.candidates[0];
    if (!selected) {
      if (generation !== candidateLoadGeneration.current) return null;
      activeCandidateId.current = null;
      setCandidate(null);
      setSavedConfirmation(null);
      setPromotedContractId(null);
      setSupportPending(false);
      setCandidateLoadState("ABSENT");
      return null;
    }
    try {
      const loaded = await dataSource.getCandidate(projectId, selected.id);
      const confirmation = await discoverLatestCurrentConfirmation(dataSource, projectId, selected.id);
      if (generation !== candidateLoadGeneration.current) return null;
      activeCandidateId.current = selected.id;
      setCandidate(loaded);
      setSavedConfirmation(confirmation);
      setPromotedContractId(provenPromotedContractId(next, loaded));
      rediscoverSupport(selected.id);
      if (!background) supportReloadRequired.current = false;
      setCandidateLoadState("READY");
      setCandidateError(null);
      return loaded;
    } catch (cause) {
      if (generation !== candidateLoadGeneration.current) return null;
      if (!background) {
        activeCandidateId.current = null;
        setCandidate(null);
        setSavedConfirmation(null);
        setPromotedContractId(null);
        setSupportPending(false);
      }
      setCandidateLoadState("ERROR");
      setCandidateError(userFacingError(cause, "合同信息暂时无法读取，请重新加载后继续。"));
      return null;
    }
  }, [dataSource, projectId, rediscoverSupport]);

  const reload = useCallback(async (preferredCandidateId?: string, background = false) => {
    const generation = ++candidateLoadGeneration.current;
    if (background) setBackgroundRefreshing(true);
    if (!background) {
      setLoading(true);
      setPromotedContractId(null);
      setCandidateLoadState("LOADING"); setCandidateError(null);
    }
    setError(null);
    try {
      const [projectResult,jobsResult]=await Promise.allSettled([dataSource.getProject(projectId),
        dataSource.listFileJobs?dataSource.listFileJobs(projectId):Promise.resolve(null)]);
      if (generation !== candidateLoadGeneration.current) return null;
      if(projectResult.status==="rejected")throw projectResult.reason;
      const next=projectResult.value;
      if(jobsResult.status==="fulfilled") {
        const jobs=jobsResult.value;
        if(jobs&&(jobs.projectId!==projectId||jobs.items.some(job=>job.projectId!==projectId)))
          throw new Error("当前材料任务无法核对，请重新读取。");
        setProcessingJobs(jobs);setProcessingError(null);
      } else {setProcessingJobs(null);setProcessingError("材料处理状态暂时无法读取，请重新加载后核对。");}
      setDetail(next);
      if (dataSource.getLatestFactExtraction) {
        const eligible = next.files.filter((file) => file.parseStatus === "PARSED"
          && !file.excludedFromProjectUse
          && (file.effectiveRole === "CONTRACT_BODY" || file.effectiveRole === "AWARD_NOTICE"));
        const loaded = await Promise.allSettled(eligible.map(async (file) => [file.id,
          await dataSource.getLatestFactExtraction!(projectId, file.id)] as const));
        if (generation !== candidateLoadGeneration.current) return null;
        const projections: Record<string, ContractFactExtractionProjection> = {};
        for (const value of loaded) {
          if (value.status === "fulfilled") projections[value.value[0]] = value.value[1];
        }
        setFactExtractions(projections);
      } else setFactExtractions({});
      rediscoverProgressionRecoveries(next);
      const loaded = await loadCandidate(next, preferredCandidateId, generation, background);
      if (generation !== candidateLoadGeneration.current) return null;
      if (next.candidates.length > 0 && !loaded) return null;
      if (!background) setDraftReloadVersion((value) => value + 1);
      return next;
    } catch (cause) {
      if (generation !== candidateLoadGeneration.current) return null;
      setError(userFacingError(cause, "项目详情暂时无法读取，请稍后重试。"));
      return null;
    } finally {
      if (background) setBackgroundRefreshing(false);
      if (!background && generation === candidateLoadGeneration.current) setLoading(false);
    }
  }, [dataSource, loadCandidate, projectId, rediscoverProgressionRecoveries]);

  useEffect(() => {
    setBusy(false);
    void reload();
    return () => { candidateLoadGeneration.current += 1; };
  }, [reload]);
  useEffect(()=>{
    let active=true;setUploadPending(false);setUploadNeedsOriginalFiles(false);
    if(durableUpload)void durableUpload.restore().then(result=>{
      if(!active)return;
      const snapshot=durableUpload.snapshot();setUploadPending(snapshot.pendingOperation??false);
      setUploadNeedsOriginalFiles(snapshot.needsFileSelection??false);
      if(result?.kind==="COMPLETED")void reload();
      else if(result)setUploadNotice("上次文件接收结果尚未确认，请继续核对；需要时重新选择原文件。");
    }).catch(()=>{if(active){setUploadPending(true);setError("上次文件接收结果暂时无法核对，请重新读取后继续。");}});
    return()=>{active=false;};
  },[durableUpload,reload]);

  const scopedProcessingJobs=processingJobs?.projectId===projectId?processingJobs:null;
  useEffect(()=>{
    if(!scopedProcessingJobs||loading||backgroundRefreshing||busy||metadataLoading||supportLoading||promotionLoading)return;
    const active=scopedProcessingJobs.items.some(job=>job.status==="QUEUED"||job.status==="PROCESSING")
      ||scopedProcessingJobs.unknown===0&&scopedProcessingJobs.failed===0&&detail?.project.id===projectId
        &&detail.files.some(file=>file.parseStatus==="PENDING"||file.roleExecutionStatus==="PENDING");
    if(!active)return;
    const timer=setTimeout(()=>void reload(undefined,true),2000);
    return()=>clearTimeout(timer);
  },[scopedProcessingJobs,detail,projectId,reload,loading,backgroundRefreshing,busy,metadataLoading,supportLoading,promotionLoading]);

  const retryProcessing=useCallback(async(fileId:string)=>{
    const job=processingJobs?.projectId===projectId?processingJobs.items.find(job=>job.fileId===fileId):null;
    if(job?.status!=="FAILED"||!dataSource.retryFileProcessing||processingRetries.current.has(fileId))return;
    processingRetries.current.add(fileId);setProcessingError(null);
    try{await dataSource.retryFileProcessing(projectId,fileId);await reload();}
    catch{setProcessingError("重试结果尚未确认，请重新读取材料任务后继续。");}
    finally{processingRetries.current.delete(fileId);}
  },[processingJobs,projectId,dataSource,reload]);

  // Ordinary Save creates one operation; explicit reconciliation can only reopen the exact stored one.
  const runSupportAction = useCallback(async (selection?: readonly string[]) => {
    if (!candidate || !detail || !actorId || loading || candidateLoadState === "ERROR" || !supportFlight.current.enter()) return null;
    const candidateId = candidate.candidate.id;
    let sent: CandidateSupportOperation | null = null;
    setSupportLoading(true); setSupportError(null);
    try {
      const persistence = supportPersistence();
      const retained = loadCandidateSupportOperation(persistence, actorId, projectId, candidateId);
      if (selection !== undefined) {
        if (retained) {
          setSupportPending(true);
          setSupportError("请先核对上次关联结果，再修改材料选择。");
          return null;
        }
        if (supportReloadRequired.current) {
          setSupportError("请重新加载并核对候选合同后保存。");
          return null;
        }
        const view = candidateSupportAuthorityPresentation(candidate, detail.files, selection);
        if (!view.canSave) return null;
        sent = loadOrCreateCandidateSupportOperation(persistence, actorId, projectId, candidateId,
          candidate.candidate.versionNo, view.selectedIds);
      } else {
        if (!retained) return null;
        sent = retained;
      }
      setSupportPending(true);
      supportReloadRequired.current = true;
      const reconciled = await replaceCandidateSupportAndReconcile(dataSource, persistence, actorId,
        projectId, candidateId, sent);
      setCandidate(reconciled);
      if (!await reload(candidateId)) {
        setSupportError("关联结果已核对，但项目详情暂不可读取，请重新加载后继续。");
      }
      return reconciled;
    } catch (cause) {
      let unresolved = true;
      try {
        unresolved = Boolean(loadCandidateSupportOperation(supportPersistence(), actorId, projectId, candidateId));
      } catch { /* Unreadable storage cannot prove completion. */ }
      const rejected = sent && !unresolved && parseCandidateSupportAuthorityClassification(cause) === "REQUEST_REJECTED";
      setSupportError(userFacingError(cause, rejected
        ? "本次关联未保存，候选合同或材料状态已变化，请核对最新内容后再保存。"
        : sent && !unresolved
          ? "材料关联状态已变化，请重新加载并核对后再保存。"
          : "材料关联结果尚未确认，已保留原操作，请重新加载后核对上次关联结果。"));
      if (sent) await reload(candidateId);
      return null;
    } finally {
      rediscoverSupport(candidateId);
      supportFlight.current.leave();
      setSupportLoading(false);
    }
  }, [actorId, candidate, candidateLoadState, dataSource, detail, loading, projectId, rediscoverSupport, reload, supportPersistence]);

  const saveCandidateSupport = useCallback((ids: readonly string[]) => runSupportAction(ids), [runSupportAction]);
  const reconcileCandidateSupport = useCallback(() => runSupportAction(), [runSupportAction]);

  const upload = useCallback(async (files?: Iterable<File>) => {
    if(durableUpload) {
      const selection=files?acceptedSupplementFiles(files):null;
      if(selection&&(selection.rejected.length>0||selection.accepted.length===0)){
        setError("请选择上次原文件或PDF、DOC（Word 97-2003）、DOCX、PNG、JPG/JPEG材料。");return;
      }
      setBusy(true);setError(null);
      const identity=workspaceIdentity;
      try{
        const result=durableUpload.snapshot().pendingOperation
          ?await durableUpload.retry(selection?.accepted):await durableUpload.start(selection?.accepted??[]);
        if(activeWorkspace.current!==identity)return;
        const snapshot=durableUpload.snapshot();setUploadPending(snapshot.pendingOperation??false);
        setUploadNeedsOriginalFiles(snapshot.needsFileSelection??false);
        setBusy(false);
        if(result.kind==="COMPLETED"){
          setUploadNotice("文件已接收，解析/OCR在后台处理，可离开页面后查看真实进度。");await reload();
        }else if(result.kind!=="CANCELLED")setUploadNotice("文件接收结果尚未确认，请继续核对原操作；刷新不会重复创建项目。");
      }catch(cause){if(activeWorkspace.current===identity)setError(userFacingError(cause,"请先核对上次原文件接收结果。"));}
      finally{if(activeWorkspace.current===identity)setBusy(false);}
      return;
    }
    if (files) {
      if (retainedUploads.current.length > 0) {
        setError("当前仍有结果未确认的资料，请先继续重试这些资料。");
        return;
      }
      const selection = acceptedSupplementFiles(files);
      setUploadNotice(null);
      if (selection.accepted.length === 0) {
        setError("请选择 PDF、DOC（Word 97-2003）、DOCX、PNG、JPG 或 JPEG 文件。");
        return;
      }
      retainedUploads.current = selection.accepted.map((file) => ({
        file,
        idempotencyKey: createRetainedOperation().key(),
      }));
      if (selection.rejected.length > 0) {
        setUploadNotice(`已忽略不支持的文件：${selection.rejected.map((file) => file.name).join("、")}。`);
      }
    }
    if (retainedUploads.current.length === 0) return;
    const attempted = [...retainedUploads.current];
    setBusy(true); setError(null);
    setUploadProgress({ total: attempted.length, completed: 0, failed: 0 });
    try {
      let completed = 0;
      let failed = 0;
      const result = await uploadProjectSupplements(dataSource, projectId, attempted, (_operation, succeeded) => {
        completed += 1;
        if (!succeeded) failed += 1;
        setUploadProgress({ total: attempted.length, completed, failed });
      });
      setBusy(false);
      const durable = await reload();
      if (!durable) {
        retainedUploads.current = attempted;
        setError("资料处理结果尚未确认；再次处理会继续确认同一批资料。");
      } else {
        retainedUploads.current = result.failed;
        if (result.failed.length > 0) {
          setError(`${result.succeeded.length} 个文件已处理，${result.failed.length} 个文件未确认；可单独重试失败文件。`);
        }
      }
    } catch (cause) {
      retainedUploads.current = attempted;
      setError(userFacingError(cause, "资料处理结果尚未确认；再次处理会继续确认同一批资料。"));
    } finally { setBusy(false); }
  }, [dataSource, projectId, reload,durableUpload,workspaceIdentity]);

  const recover = useCallback(async (fileId: string) => {
    setBusy(true); setError(null);
    try {
      const durable = await recoverProjectFileAndReload(dataSource, projectId, fileId);
      setDetail(durable);
      await loadCandidate(durable);
    } catch (cause) {
      setError(userFacingError(cause, "处理结果仍无法确认，请稍后继续处理。"));
    } finally { setBusy(false); }
  }, [dataSource, loadCandidate, projectId]);

  const resumeProgression = useCallback(async (file: ProjectFile) => {
    if (!actorId) {
      setError("当前登录身份无法核对，请重新登录后继续。");
      return null;
    }
    const scope = `${actorId}:${projectId}:${file.id}`;
    if (progressionFlights.current.has(scope)) return null;
    progressionFlights.current.add(scope);
    const generation = candidateLoadGeneration.current;
    let busyGeneration = generation;
    setBusy(true); setError(null);
    try {
      const recovered = await resumeProjectFileProgression(dataSource,
        progressionPersistence.current!, actorId, file);
      if (generation !== candidateLoadGeneration.current) return null;
      busyGeneration = generation + 1;
      await reload();
      return recovered;
    } catch (cause) {
      if (generation === candidateLoadGeneration.current) {
        setError(progressionRecoveryUserMessage(cause) ?? userFacingError(cause,
          "接续结果尚未确认；已保留原操作，再次操作只会核对同一次处理。"));
        if (detail) rediscoverProgressionRecoveries(detail);
      }
      return null;
    } finally {
      progressionFlights.current.delete(scope);
      if (busyGeneration === candidateLoadGeneration.current) setBusy(false);
    }
  }, [actorId, dataSource, detail, projectId, rediscoverProgressionRecoveries, reload]);

  const selectCandidate = useCallback(async (candidateId: string) => {
    if (candidate?.candidate.id === candidateId) return;
    setBusy(true);
    try { await reload(candidateId); }
    finally { setBusy(false); }
  }, [candidate, reload]);

  const confirmMetadata = useCallback(async (payload: ProjectMetadataConfirmation) => {
    if (metadataLoading || !metadataFlight.current.enter()) return null;
    setMetadataLoading(true); setMetadataError(null); setMetadataConflict(null);
    try {
      const reconciled = await confirmMetadataAndReload(dataSource, projectId, payload,
        metadataOperation.current.key(), metadataOperation.current);
      setDetail(reconciled.project);
      if (reconciled.outcome === "CONFLICT") {
        setMetadataConflict("项目信息已发生变化；当前已显示最新内容，未自动覆盖。请核对后继续。");
      }
      await loadCandidate(reconciled.project);
      return reconciled.outcome;
    } catch (cause) {
      setMetadataError(userFacingError(cause,
        "项目信息确认结果尚未确定；再次确认会接续本次处理，不会重复提交。"));
      return null;
    } finally {
      metadataFlight.current.leave();
      setMetadataLoading(false);
    }
  }, [dataSource, loadCandidate, metadataLoading, projectId]);

  const confirmRole = useCallback(async (file: ProjectFile, selectedRole: DocumentRole) => {
    if (!file.roleAssessmentId || !hasProgressionAction(file, "CONFIRM_ROLE")) return;
    const scope = `${file.id}:${file.roleAssessmentId}`;
    if (progressionFlights.current.has(scope)) return;
    progressionFlights.current.add(scope);
    let operation = roleOperations.current.get(scope);
    if (!operation) {
      operation = createBrowserRetainedOperation(`role:${projectId}:${scope}`);
      roleOperations.current.set(scope, operation);
    }
    setBusy(true); setError(null);
    try {
      await dataSource.confirmRole(projectId, file.id, {
        assessmentId: file.roleAssessmentId, selectedRole,
        expectedVersion: file.roleDecisionVersion ?? 0, reason: "人工核对原始材料",
      }, { idempotencyKey: operation.key() });
      operation.clear();
      await reload();
    } catch (cause) {
      setError(userFacingError(cause,
        "材料类型确认结果尚未确定；再次确认会接续本次处理，不会重复提交。"));
    } finally {
      progressionFlights.current.delete(scope);
      setBusy(false);
    }
  }, [dataSource, projectId, reload]);

  const confirmProjectIdentity = useCallback(async (file: ProjectFile,
    resolution: ProjectIdentityResolution) => {
    if (!file.identityAssessmentId || !hasProgressionAction(file, "CONFIRM_PROJECT_IDENTITY")) return;
    const scope = `${file.id}:${file.identityAssessmentId}`;
    if (progressionFlights.current.has(scope)) return;
    progressionFlights.current.add(scope);
    let operation = identityOperations.current.get(scope);
    if (!operation) {
      operation = createBrowserRetainedOperation(`identity:${projectId}:${scope}`);
      identityOperations.current.set(scope, operation);
    }
    setBusy(true); setError(null);
    try {
      await dataSource.confirmProjectIdentity(projectId, file.id, {
        assessmentId: file.identityAssessmentId, resolution,
        expectedVersion: file.identityDecisionVersion ?? 0,
        reason: resolution === "CONFIRM_SAME_PROJECT"
          ? "人工确认属于当前项目" : "人工确认不属于当前项目",
      }, { idempotencyKey: operation.key() });
      operation.clear();
      await reload();
    } catch (cause) {
      setError(userFacingError(cause,
        "项目归属确认结果尚未确定；再次确认会接续本次处理，不会重复提交。"));
    } finally {
      progressionFlights.current.delete(scope);
      setBusy(false);
    }
  }, [dataSource, projectId, reload]);

  const confirmCandidateContent = useCallback(async (payload: ConfirmedContractPayload) => {
    if (!candidate || promotionLoading || !promotionFlight.current.enter()) return null;
    const candidateId = candidate.candidate.id;
    let confirmationOperation = confirmationOperations.current.get(candidateId);
    if (!confirmationOperation) {
      confirmationOperation = createBrowserRetainedOperation(`confirm:${projectId}:${candidateId}`);
      confirmationOperations.current.set(candidateId, confirmationOperation);
    }
    setPromotionLoading(true); setPromotionError(null); setPromotionNotice(null);
    try {
      const saved = await saveCandidateConfirmation(dataSource, projectId, candidateId,
        payload, confirmationOperation.key());
      confirmationOperation.clear();
      setSavedConfirmation(saved);
      await reload(candidateId);
      return saved.confirmationId;
    } catch (cause) {
      setPromotionError(userFacingError(cause,
        "合同内容确认结果尚未确定；再次确认会接续本次处理，不会重复提交。"));
      return null;
    } finally {
      promotionFlight.current.leave();
      setPromotionLoading(false);
    }
  }, [candidate, dataSource, projectId, promotionLoading, reload]);

  const retryPromotion = useCallback(async () => {
    if (!savedConfirmation || !candidate || promotionLoading || !promotionFlight.current.enter()) return null;
    const candidateId = candidate.candidate.id;
    let operation = promotionOperations.current.get(savedConfirmation.confirmationId);
    if (!operation) {
      operation = createBrowserRetainedOperation(
        `promote:${projectId}:${candidateId}:${savedConfirmation.confirmationId}`);
      promotionOperations.current.set(savedConfirmation.confirmationId, operation);
    }
    setPromotionLoading(true); setPromotionError(null);
    try {
      const result = await retrySavedPromotion(dataSource, projectId, candidateId,
        savedConfirmation, operation.key());
      operation.clear();
      setDetail(result.project); setCandidate(result.candidate); setSavedConfirmation(null);
      setPromotedContractId(result.contractId);
      return result.contractId;
    } catch (cause) {
      setPromotionError(userFacingError(cause, "合同确认已保存，可重试生成正式合同。"));
      return null;
    } finally { promotionFlight.current.leave(); setPromotionLoading(false); }
  }, [candidate, dataSource, projectId, promotionLoading, savedConfirmation]);

  const scopedDetail = detail?.project.id === projectId ? detail : null;
  const scopedCandidate = candidate?.candidate.projectId === projectId ? candidate : null;
  const scopedCandidateLoadState = scopedDetail ? candidateLoadState : "LOADING";

  return { detail: scopedDetail, candidate: scopedCandidate,
    candidateLoadState: scopedCandidateLoadState, candidateError, loading, busy, error, reload, draftReloadVersion,
    upload, uploadProgress, uploadNotice, recover,processingJobs:scopedProcessingJobs,processingError,retryProcessing,
    uploadNeedsOriginalFiles,
    retryUncertainUpload: uploadPending||retainedUploads.current.length > 0 ? () => upload() : null,
    metadataLoading, metadataError, metadataConflict, confirmMetadata,
    promotionLoading, promotionError, promotionNotice, promotedContractId,
    supportLoading, supportPending, supportError, saveCandidateSupport, reconcileCandidateSupport,
    progressionRecoveryPendingFileIds, factExtractions, resumeProgression,
    confirmRole, confirmProjectIdentity, confirmCandidateContent,
    retryPromotion, savedConfirmation, selectCandidate };
}
