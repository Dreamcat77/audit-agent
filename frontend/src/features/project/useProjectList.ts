import { useCallback, useEffect, useMemo,useRef, useState } from "react";
import { userFacingError } from "../../api/userFacingError";
import type { ProjectDataSource } from "./data/projectDataSource";
import { createRetainedOperation } from "./projectOperation";
import { createProjectIntakeOperation, type ProjectIntakeSnapshot } from "./projectIntakeOperation";
import { completedProjectIntake, type CompletedProjectIntake } from "./projectIntakeCompletion";
import { loadProjectCollections, projectIntakeError, type ProjectCollectionPages } from "./projectListViewModel";
import type { Project, ProjectIntakeDraft, ProjectPreparationDraft } from "./types";
import {createContractMaterialIntakeOperation} from "../contract/contractMaterialIntakeOperation.ts";

export function useProjectList(dataSource: ProjectDataSource,actorId="") {
  const [projects, setProjects] = useState<Project[]>([]);
  const [intakeDrafts, setIntakeDrafts] = useState<ProjectIntakeDraft[]>([]);
  const [preparationDrafts, setPreparationDrafts] = useState<ProjectPreparationDraft[]>([]);
  const [projectTotal, setProjectTotal] = useState<number | null>(null);
  const [intakeDraftTotal, setIntakeDraftTotal] = useState<number | null>(null);
  const [preparationDraftTotal, setPreparationDraftTotal] = useState<number | null>(null);
  const [intakeDraftPage, setIntakeDraftPage] = useState(1);
  const [intakeDraftPageSize, setIntakeDraftPageSize] = useState(20);
  const [preparationDraftPage, setPreparationDraftPage] = useState(1);
  const [preparationDraftPageSize, setPreparationDraftPageSize] = useState(20);
  const draftPages = useRef<ProjectCollectionPages>({ intakeDraftPage: 1, preparationDraftPage: 1 });
  const [loading, setLoading] = useState(true);
  const [collectionsAvailable, setCollectionsAvailable] = useState(false);
  const [collectionError, setCollectionError] = useState<string | null>(null);
  const collectionLoad = useRef<AbortController | null>(null);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [completedIntake, setCompletedIntake] = useState<CompletedProjectIntake | null>(null);
  const [acceptedIntake,setAcceptedIntake]=useState<{projectId:string;filename:string|null}|null>(null);
  const durableIntake=useMemo(()=>{
    if(!actorId||!dataSource.uploadAsync||typeof window==="undefined")return null;
    return createContractMaterialIntakeOperation({dataSource,retained:{actorId,storage:{
      get length(){return window.localStorage.length;},key:index=>window.localStorage.key(index),
      getItem:key=>window.localStorage.getItem(key),setItem:(key,value)=>window.localStorage.setItem(key,value),
      removeItem:key=>window.localStorage.removeItem(key)}}});
  },[dataSource,actorId]);
  const createOperation = useRef(createRetainedOperation());
  const [intakeSnapshot, setIntakeSnapshot] = useState<ProjectIntakeSnapshot>({
    stage: "IDLE", filename: null, projectId: null,
  });
  const intakeOperation = useRef<ReturnType<typeof createProjectIntakeOperation> | null>(null);
  if (!intakeOperation.current) {
    intakeOperation.current = createProjectIntakeOperation(undefined, (stage) => {
      setIntakeSnapshot((current) => ({
        stage,
        filename: intakeOperation.current?.snapshot().filename ?? current.filename,
        projectId: intakeOperation.current?.snapshot().projectId ?? current.projectId,
      }));
    });
  }

  const loadCollections = useCallback(async (pages: ProjectCollectionPages) => {
    collectionLoad.current?.abort();
    const controller = new AbortController();
    collectionLoad.current = controller;
    setLoading(true); setCollectionError(null); setCollectionsAvailable(false);
    try {
      const collections = await loadProjectCollections(dataSource, controller.signal, pages);
      if (controller.signal.aborted) return;
      setProjects(collections.projects);
      setIntakeDrafts(collections.intakeDrafts);
      setPreparationDrafts(collections.preparationDrafts);
      setProjectTotal(collections.projectTotal);
      setIntakeDraftTotal(collections.intakeDraftTotal);
      setPreparationDraftTotal(collections.preparationDraftTotal);
      setIntakeDraftPage(collections.intakeDraftPage);
      setIntakeDraftPageSize(collections.intakeDraftPageSize);
      setPreparationDraftPage(collections.preparationDraftPage);
      setPreparationDraftPageSize(collections.preparationDraftPageSize);
      draftPages.current = {
        intakeDraftPage: collections.intakeDraftPage,
        preparationDraftPage: collections.preparationDraftPage,
      };
      setCollectionsAvailable(true);
    }
    catch (cause) {
      if (controller.signal.aborted) return;
      setProjects([]); setIntakeDrafts([]); setPreparationDrafts([]);
      setProjectTotal(null); setIntakeDraftTotal(null); setPreparationDraftTotal(null);
      setCollectionsAvailable(false);
      setCollectionError(userFacingError(cause, "项目列表暂时无法读取，请稍后重试。"));
    }
    finally { if (!controller.signal.aborted) setLoading(false); }
  }, [dataSource]);

  const reload = useCallback(async () => loadCollections(draftPages.current), [loadCollections]);

  const loadIntakeDraftPage = useCallback(async (page: number) => {
    if (!Number.isInteger(page) || page < 1) return;
    await loadCollections({ ...draftPages.current, intakeDraftPage: page });
  }, [loadCollections]);

  const loadPreparationDraftPage = useCallback(async (page: number) => {
    if (!Number.isInteger(page) || page < 1) return;
    await loadCollections({ ...draftPages.current, preparationDraftPage: page });
  }, [loadCollections]);

  useEffect(() => {
    void reload();
    return () => collectionLoad.current?.abort();
  }, [reload]);
  useEffect(()=>{
    let active=true;
    if(durableIntake)void durableIntake.restore().then(result=>{
      if(!active||!result)return;const saved=durableIntake.snapshot();
      setIntakeSnapshot({stage:result.kind==="COMPLETED"?"ACCEPTED":"UNCERTAIN",filename:saved.filenames[0]??null,projectId:result.projectId});
      if(result.kind==="COMPLETED")setAcceptedIntake({projectId:result.projectId,filename:null});
    }).catch(()=>{if(active)setError("上次资料包接收结果暂时无法核对，请先继续原操作。");});
    return()=>{active=false;};
  },[durableIntake]);

  const createBlank = useCallback(async () => {
    setCreating(true); setError(null);
    try {
      const project = await dataSource.createBlank({ idempotencyKey: createOperation.current.key() });
      createOperation.current.clear();
      return project;
    } catch (cause) {
      setError(userFacingError(cause, "项目暂时无法创建，请重试。"));
      return null;
    } finally { setCreating(false); }
  }, [dataSource]);

  const startPackageIntake = useCallback(async (file?: File) => {
    setError(null);
    if(durableIntake){
      const before=durableIntake.snapshot();
      setIntakeSnapshot({stage:"UPLOADING_PACKAGE",filename:file?.name??before.filenames[0]??null,projectId:before.projectId});
      try{
        const result=before.pendingOperation?await durableIntake.retry(file?[file]:[]):await durableIntake.start(file?[file]:[]);
        const saved=durableIntake.snapshot();
        setIntakeSnapshot({stage:result.kind==="COMPLETED"?"ACCEPTED":"UNCERTAIN",filename:file?.name??saved.filenames[0]??null,projectId:result.kind==="CANCELLED"?null:result.projectId});
        if(result.kind==="COMPLETED"){setAcceptedIntake({projectId:result.projectId,filename:file?.name??null});setCompletedIntake(null);}
        await reload();return result;
      }catch(cause){setIntakeSnapshot({stage:"UNCERTAIN",filename:file?.name??before.filenames[0]??null,projectId:before.projectId});
        setError(userFacingError(cause,"请重新选择原资料包继续核对，不能替换上次未确认的材料。"));return null;}
    }
    try {
      if (file) {
        setCompletedIntake(null);
        intakeOperation.current!.select(file);
      }
      const result = await intakeOperation.current!.run(dataSource);
      if(result.acceptedAsync){setAcceptedIntake({projectId:result.project.id,filename:result.file.originalFilename});setCompletedIntake(null);}
      else setCompletedIntake(completedProjectIntake(result));
      setIntakeSnapshot(intakeOperation.current!.snapshot());
      await reload();
      return result;
    } catch (cause) {
      const snapshot = intakeOperation.current!.snapshot();
      setIntakeSnapshot(snapshot);
      setError(userFacingError(cause, projectIntakeError(snapshot.stage)));
      return null;
    }
  }, [dataSource, reload,durableIntake]);

  return { projects, intakeDrafts, preparationDrafts, projectTotal, intakeDraftTotal, preparationDraftTotal,
    intakeDraftPage, intakeDraftPageSize, preparationDraftPage, preparationDraftPageSize,
    loading, collectionsAvailable, collectionError,
    creating, error, reload, loadIntakeDraftPage, loadPreparationDraftPage, createBlank, intakeSnapshot, completedIntake,acceptedIntake,
    startPackageIntake,
    retryPackageIntake: intakeSnapshot.stage === "UNCERTAIN" ? () => startPackageIntake() : null };
}
