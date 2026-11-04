import type { ProjectDataSource } from "../data/projectDataSource";
import { ProjectManagementLandingPage } from "./ProjectManagementLandingPage";

export function ProjectListPage({ dataSource, onOpenProject }:
  { dataSource: ProjectDataSource; onOpenProject: (projectId: string) => void }) {
  return <ProjectManagementLandingPage dataSource={dataSource} onOpenProject={onOpenProject}/>;
}
