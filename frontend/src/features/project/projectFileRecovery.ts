import type { ProjectDataSource } from "./data/projectDataSource";
import type { ProjectDetail } from "./types";

export async function recoverProjectFileAndReload(
  dataSource: Pick<ProjectDataSource, "recover" | "getProject" | "listFiles">,
  projectId: string,
  fileId: string,
): Promise<ProjectDetail> {
  await dataSource.recover(projectId, fileId);
  const detail = await dataSource.getProject(projectId);
  await dataSource.listFiles(projectId);
  return detail;
}
