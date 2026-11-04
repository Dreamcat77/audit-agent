import type { ProjectDataSource } from "./data/projectDataSource";

const SUPPLEMENT_EXTENSION = /\.(?:pdf|docx?|png|jpe?g)$/i;

export interface SupplementUploadOperation {
  file: File;
  idempotencyKey: string;
}

export interface SupplementUploadResult {
  succeeded: SupplementUploadOperation[];
  failed: SupplementUploadOperation[];
}

type SupplementUploadPort = Pick<ProjectDataSource, "upload" | "uploadAsync">;

export function acceptedSupplementFiles(files: Iterable<File>) {
  const accepted: File[] = [];
  const rejected: File[] = [];
  for (const file of files) {
    (SUPPLEMENT_EXTENSION.test(file.name) ? accepted : rejected).push(file);
  }
  return { accepted, rejected };
}

/**
 * Await byte acceptance for each independent operation. The async port returns
 * before parsing; the legacy port remains available to existing integrations.
 */
export async function uploadProjectSupplements(
  dataSource: SupplementUploadPort,
  projectId: string,
  operations: readonly SupplementUploadOperation[],
  onSettled?: (operation: SupplementUploadOperation, succeeded: boolean) => Promise<void> | void,
): Promise<SupplementUploadResult> {
  const succeeded: SupplementUploadOperation[] = [];
  const failed: SupplementUploadOperation[] = [];
  for (const operation of operations) {
    try {
      if(dataSource.uploadAsync) await dataSource.uploadAsync(projectId,operation.file,{idempotencyKey:operation.idempotencyKey});
      else await dataSource.upload(projectId, operation.file, { idempotencyKey: operation.idempotencyKey });
      succeeded.push(operation);
      await onSettled?.(operation, true);
    } catch {
      failed.push(operation);
      await onSettled?.(operation, false);
    }
  }
  return { succeeded, failed };
}
