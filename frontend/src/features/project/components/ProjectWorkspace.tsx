import { useState } from "react";
import { httpProjectDataSource } from "../data/httpProjectDataSource";
import { ProjectDetailPage } from "./ProjectDetailPage";
import { ProjectManagementLandingPage } from "./ProjectManagementLandingPage";
import type {ProjectDataSource} from "../data/projectDataSource";
import {useAuth} from "../../../auth/AuthProvider";

export function ProjectWorkspace({ initialProjectId = null, entryMode = "PROJECT", embeddedQueue = false,dataSource=httpProjectDataSource }: {
  initialProjectId?: string | null;
  entryMode?: "PROJECT" | "CONTRACT_REVIEW";
  embeddedQueue?: boolean;
  dataSource?:ProjectDataSource;
}) {
  const [activeProjectId, setActiveProjectId] = useState<string | null>(initialProjectId);
  const {user}=useAuth();
  return activeProjectId
    ? <ProjectDetailPage projectId={activeProjectId} dataSource={dataSource}
        backLabel={entryMode === "CONTRACT_REVIEW" ? "返回材料核验列表" : "返回项目列表"}
        onBack={() => setActiveProjectId(null)}/>
    : <ProjectManagementLandingPage dataSource={dataSource} onOpenProject={setActiveProjectId}
        actorId={user?.userId??""} contractReviewEntry={entryMode === "CONTRACT_REVIEW"} embeddedContractQueue={embeddedQueue}/>;
}
