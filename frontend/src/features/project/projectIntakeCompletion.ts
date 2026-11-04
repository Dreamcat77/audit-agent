import type { ProjectIntakeResult } from "./projectIntakeOperation";
import { projectMetadataDraft } from "./projectMetadataConfirmation";

export interface CompletedProjectIntake {
  projectId: string;
  projectName: string | null;
  projectNo: string | null;
  packageFilename: string;
  processedFileCount: number;
  textParsedFileCount: number;
  ocrFileCount: number;
  failedFileCount: number;
}

export function completedProjectIntake(result: ProjectIntakeResult): CompletedProjectIntake {
  const processedFiles = result.detail.files.filter((file) => file.category !== "PACKAGE");
  const metadata = projectMetadataDraft(result.detail);
  return {
    projectId: result.project.id,
    projectName: metadata.name || null,
    projectNo: metadata.projectNo || null,
    packageFilename: result.file.originalFilename,
    processedFileCount: processedFiles.length,
    textParsedFileCount: processedFiles.filter((file) =>
      file.parseStatus === "PARSED"
      && (file.parseMethod === "DOC_TEXT" || file.parseMethod === "DOCX_TEXT" || file.parseMethod === "PDF_TEXT")
    ).length,
    ocrFileCount: processedFiles.filter((file) =>
      file.parseStatus === "PARSED" && file.parseMethod === "OCR"
    ).length,
    failedFileCount: result.detail.failedFileCount,
  };
}
