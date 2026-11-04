import { createOperationKey } from "../../api/operationKey.ts";
import type { ProjectDataSource } from "./data/projectDataSource";
import { createRetainedOperation } from "./projectOperation";
import type { Project, ProjectDetail, ProjectFile } from "./types";

export type ProjectIntakeStage =
  | "IDLE"
  | "FILE_SELECTED"
  | "CREATING_PROJECT"
  | "UPLOADING_PACKAGE"
  | "REFRESHING_DURABLE_STATE"
  | "COMPLETE"
  | "ACCEPTED"
  | "FAILED"
  | "UNCERTAIN";

export interface ProjectIntakeSnapshot {
  stage: ProjectIntakeStage;
  filename: string | null;
  projectId: string | null;
}

export interface ProjectIntakeResult {
  project: Project;
  file: ProjectFile;
  detail: ProjectDetail;
  acceptedAsync?:boolean;
}

type IntakeDataSource = Pick<ProjectDataSource, "createBlank" | "upload" | "uploadAsync" | "getProject">;

export interface ProjectIntakeOperation {
  select(file: File): void;
  run(dataSource: IntakeDataSource): Promise<ProjectIntakeResult>;
  snapshot(): ProjectIntakeSnapshot;
}

export function createProjectIntakeOperation(
  createKey: () => string = createOperationKey,
  onStage: (stage: ProjectIntakeStage) => void = () => undefined,
  automaticAnalysisFileCount?: number,
): ProjectIntakeOperation {
  const createOperation = createRetainedOperation(createKey);
  const uploadOperation = createRetainedOperation(createKey);
  let selectedFile: File | null = null;
  let retainedFilename: string | null = null;
  let project: Project | null = null;
  let uploadedFile: ProjectFile | null = null;
  let stage: ProjectIntakeStage = "IDLE";

  function transition(next: ProjectIntakeStage) {
    stage = next;
    onStage(next);
  }

  return {
    select(file) {
      if (selectedFile) throw new Error("project intake outcome is still uncertain");
      if (!file.name.toLocaleLowerCase().endsWith(".zip")) {
        throw new Error("project intake requires a ZIP package");
      }
      selectedFile = file;
      retainedFilename = file.name;
      project = null;
      uploadedFile = null;
      transition("FILE_SELECTED");
    },

    async run(dataSource) {
      if (!selectedFile) throw new Error("project intake package is not selected");
      try {
        if (!project) {
          transition("CREATING_PROJECT");
          project = await dataSource.createBlank({ idempotencyKey: createOperation.key(),
            ...(automaticAnalysisFileCount ? {automaticAnalysisFileCount} : {}) });
          createOperation.clear();
        }
        if (!uploadedFile) {
          transition("UPLOADING_PACKAGE");
          uploadedFile = dataSource.uploadAsync
            ? (await dataSource.uploadAsync(project.id, selectedFile,{ idempotencyKey: uploadOperation.key() })).file
            : await dataSource.upload(project.id, selectedFile,{ idempotencyKey: uploadOperation.key() });
        }
        transition("REFRESHING_DURABLE_STATE");
        const detail = await dataSource.getProject(project.id);
        const durableFile = detail.files.find((file) => file.id === uploadedFile?.id);
        if (!durableFile || durableFile.ingestionStatus === "UPLOAD_PENDING") {
          throw new Error("durable project file not proven");
        }
        if (durableFile.ingestionStatus === "UPLOAD_FAILED") {
          uploadOperation.clear();
          selectedFile = null;
          uploadedFile = null;
          transition("FAILED");
          throw new Error("durable project file upload failed");
        }
        const result = { project: detail.project, file: durableFile, detail,acceptedAsync:Boolean(dataSource.uploadAsync) };
        uploadOperation.clear();
        selectedFile = null;
        project = null;
        uploadedFile = null;
        transition(dataSource.uploadAsync?"ACCEPTED":"COMPLETE");
        return result;
      } catch (cause) {
        if (stage !== "FAILED") transition("UNCERTAIN");
        throw cause;
      }
    },

    snapshot() {
      return { stage, filename: retainedFilename, projectId: project?.id ?? null };
    },
  };
}
