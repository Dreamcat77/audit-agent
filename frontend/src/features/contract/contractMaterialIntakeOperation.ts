import { createOperationKey } from "../../api/operationKey.ts";
import type { ProjectDataSource } from "../project/data/projectDataSource.ts";
import { createProjectIntakeOperation } from "../project/projectIntakeOperation.ts";
import { createRetainedOperation } from "../project/projectOperation.ts";
import {
  acceptedSupplementFiles,
  uploadProjectSupplements,
  type SupplementUploadOperation,
} from "../project/projectSupplementUpload.ts";
import type { Project } from "../project/types.ts";
import {createRetainedContractMaterialIntake,type IntakeKeyStorage} from "./retainedContractMaterialIntake.ts";

type IntakeDataSource = Pick<ProjectDataSource, "createBlank" | "upload" | "uploadAsync" | "getProject"
  | "getCreationOperation" | "getUploadOperation">;

export interface ContractMaterialIntakeDependencies {
  dataSource: IntakeDataSource;
  retained?:{actorId:string;storage:IntakeKeyStorage;projectId?:string};
  automaticAnalysis?:boolean;
  /** Each explicit upload selection starts one new project; old receipts remain recoverable. */
  newProjectPerBatch?:boolean;
}

export type ContractMaterialIntakeResult =
  | { kind: "CANCELLED" }
  | { kind: "COMPLETED"; projectId: string; succeeded: readonly SupplementUploadOperation[]; unresolved: readonly SupplementUploadOperation[];acceptedAsync?:boolean }
  | { kind: "PARTIAL"; projectId: string; succeeded: readonly SupplementUploadOperation[]; unresolved: readonly SupplementUploadOperation[] }
  | { kind: "OUTCOME_UNCERTAIN"; projectId: string | null; succeeded: readonly SupplementUploadOperation[]; unresolved: readonly SupplementUploadOperation[];unresolvedCount?:number };

export interface ContractMaterialIntakeSnapshot {
  running: boolean;
  projectId: string | null;
  filenames: readonly string[];
  unresolvedFilenames: readonly string[];
  pendingOperation?:boolean;
  needsFileSelection?:boolean;
}

export interface ContractMaterialIntakeOperation {
  start(files: readonly File[]): Promise<ContractMaterialIntakeResult>;
  retry(files?:readonly File[]): Promise<ContractMaterialIntakeResult>;
  restore():Promise<ContractMaterialIntakeResult|null>;
  snapshot(): ContractMaterialIntakeSnapshot;
}

export function createContractMaterialIntakeOperation(
  dependencies: ContractMaterialIntakeDependencies,
  createKey: () => string = createOperationKey,
): ContractMaterialIntakeOperation {
  if(dependencies.retained)return createRetainedContractMaterialIntake(dependencies,createKey);
  const createProjectOperation = createRetainedOperation(createKey);
  let project: Project | null = null;
  let selectedFiles: readonly File[] = [];
  let unresolved: SupplementUploadOperation[] = [];
  let succeeded: SupplementUploadOperation[] = [];
  let zipOperation: ReturnType<typeof createProjectIntakeOperation> | null = null;
  let mode: "ZIP" | "SUPPLEMENTS" | null = null;
  let inFlight: Promise<ContractMaterialIntakeResult> | null = null;

  function executeZip(): Promise<ContractMaterialIntakeResult> {
    return zipOperation!.run(dependencies.dataSource).then((result) => {
      const completed = { kind: "COMPLETED" as const, projectId: result.project.id,
        succeeded: [] as SupplementUploadOperation[], unresolved: [] as SupplementUploadOperation[] };
      selectedFiles = [];
      project = null;
      mode = null;
      zipOperation = null;
      return completed;
    }).catch((cause: unknown) => {
      const snapshot = zipOperation!.snapshot();
      if (snapshot.stage === "FAILED") {
        selectedFiles = [];
        project = null;
        mode = null;
        zipOperation = null;
        throw cause;
      }
      return {
        kind: "OUTCOME_UNCERTAIN" as const,
        projectId: snapshot.projectId,
        succeeded: [] as SupplementUploadOperation[],
        unresolved: [] as SupplementUploadOperation[],
      };
    });
  }

  async function executeSupplements(): Promise<ContractMaterialIntakeResult> {
    if (!project) {
      try {
        project = await dependencies.dataSource.createBlank({ idempotencyKey: createProjectOperation.key(),
          ...(dependencies.automaticAnalysis?{automaticAnalysisFileCount:selectedFiles.length}:{}) });
        createProjectOperation.clear();
      } catch {
        return { kind: "OUTCOME_UNCERTAIN", projectId: null, succeeded: [...succeeded], unresolved: [...unresolved] };
      }
    }

    const settled = await uploadProjectSupplements(dependencies.dataSource, project.id, unresolved);
    succeeded = [...succeeded, ...settled.succeeded];
    unresolved = [...settled.failed];
    if (unresolved.length > 0) {
      return {
        kind: succeeded.length > 0 ? "PARTIAL" : "OUTCOME_UNCERTAIN",
        projectId: project.id,
        succeeded: [...succeeded],
        unresolved: [...unresolved],
      };
    }
    const completedProjectId = project.id;
    const completed = {
      kind: "COMPLETED" as const,
      projectId: completedProjectId,
      succeeded: [...succeeded],
      unresolved: [] as SupplementUploadOperation[],
    };
    selectedFiles = [];
    project = null;
    succeeded = [];
    unresolved = [];
    mode = null;
    return completed;
  }

  function execute(): Promise<ContractMaterialIntakeResult> {
    if (inFlight) return inFlight;
    const running = mode === "ZIP" ? executeZip() : executeSupplements();
    inFlight = running.finally(() => { inFlight = null; });
    return inFlight;
  }

  return {
    start(files) {
      if (inFlight) return inFlight;
      if (mode && !dependencies.newProjectPerBatch) return Promise.reject(new Error("当前材料上传结果尚未确认，请继续原操作"));
      if (files.length === 0) return Promise.resolve({ kind: "CANCELLED" });

      const zips = files.filter((file) => file.name.toLocaleLowerCase().endsWith(".zip"));
      if (zips.length > 0) {
        if (files.length !== 1) return Promise.reject(new Error("ZIP 资料包不能与普通材料同时上传"));
        if (dependencies.newProjectPerBatch) { project = null; createProjectOperation.clear(); succeeded = []; unresolved = []; }
        selectedFiles = files;
        mode = "ZIP";
        zipOperation = createProjectIntakeOperation(createKey, undefined, dependencies.automaticAnalysis ? 1 : undefined);
        zipOperation.select(zips[0]);
        return execute();
      }

      const accepted = acceptedSupplementFiles(files);
      if (accepted.rejected.length > 0 || accepted.accepted.length !== files.length) {
        return Promise.reject(new Error("包含不支持的材料格式，仅支持 PDF、DOC（Word 97-2003）、DOCX、PNG、JPG/JPEG"));
      }
      if (dependencies.newProjectPerBatch) { project = null; createProjectOperation.clear(); zipOperation = null; }
      selectedFiles = files;
      mode = "SUPPLEMENTS";
      createProjectOperation.key();
      unresolved = accepted.accepted.map((file) => ({ file, idempotencyKey: createKey() }));
      succeeded = [];
      return execute();
    },

    retry() {
      if (inFlight) return inFlight;
      if (!mode) return Promise.reject(new Error("没有需要继续的材料上传"));
      return execute();
    },
    async restore(){return null;},

    snapshot() {
      return {
        running: inFlight !== null,
        projectId: project?.id ?? zipOperation?.snapshot().projectId ?? null,
        filenames: selectedFiles.map((file) => file.name),
        unresolvedFilenames: unresolved.map((operation) => operation.file.name),
      };
    },
  };
}
