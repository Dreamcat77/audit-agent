import { useEffect, useMemo, useState } from "react";
import type { DirectF02AuditTaskRow } from "../auditTaskViewModel";
import { HttpContractDataSource } from "../data/httpContractDataSource";
import { httpProjectDataSource } from "../../project/data/httpProjectDataSource";
import { projectMaterialTabs } from "../../project/projectMaterialPresentation";
import type { ProjectDetail } from "../../project/types";
import ContractAuditWorkspace from "./ContractReviewWorkspace";

const directF02DataSource = new HttpContractDataSource();

export default function DirectF02AuditTaskWorkspace({ row, onBack }: {
  row: DirectF02AuditTaskRow;
  onBack: () => void;
}) {
  const [project, setProject] = useState<ProjectDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(Boolean(row.projectId));

  useEffect(() => {
    if (!row.projectId) {
      setLoading(false);
      return;
    }
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    void httpProjectDataSource.getProject(row.projectId, controller.signal)
      .then(setProject)
      .catch((cause: unknown) => {
        if (cause instanceof Error && cause.name === "AbortError") return;
        setError(cause instanceof Error ? cause.message : "项目材料读取失败");
      })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [row.projectId]);

  const materials = useMemo(() => project ? projectMaterialTabs(project.files).map((tab) => ({
    ...tab,
    files: tab.files.map((file) => ({
      ...file,
      originalUrl: httpProjectDataSource.originalContentUrl(project.project.id, file.id),
    })),
  })) : undefined, [project]);

  const navigation = row.reviewRun;
  const historicalRunId = navigation?.kind === "INTEGRATED"
    && navigation.projectId === row.projectId && navigation.runId
    ? navigation.runId : null;
  const navigationUnavailable = navigation?.kind === "UNAVAILABLE"
    || navigation?.kind === "INTEGRATED" && !historicalRunId
    || !navigation && Boolean(row.projectId);

  if (navigationUnavailable) return <section className="p-6 text-sm text-red-700">
    <button type="button" onClick={onBack} className="mb-4 text-blue-700">返回内审任务列表</button>
    <p role="alert">无法验证这条任务对应的完整审核记录，请从项目入口核对历史记录。</p>
  </section>;

  if (loading) return <p className="p-6 text-sm text-slate-600">正在读取真实项目与审核材料…</p>;
  if (row.projectId && (!project || error)) return <section className="p-6 text-sm text-red-700">
    <button type="button" onClick={onBack} className="mb-4 text-blue-700">返回内审任务列表</button>
    <p role="alert">无法验证任务所属项目材料：{error ?? "项目事实不存在"}</p>
  </section>;

  return <ContractAuditWorkspace
    contractId={row.contractApiId}
    contractTitle={row.contract.contractName}
    contractStatus={row.contract.status}
    supplier={row.contract.partyB}
    projectName={project?.project.name ?? row.projectName}
    projectNo={project?.project.projectNo ?? row.projectNo}
    projectCategory={project?.project.category ?? null}
    initiatedBy={row.initiatedBy}
    createdAt={row.createdAt}
    onBack={onBack}
    dataSource={directF02DataSource}
    projectMaterials={materials}
    projectId={historicalRunId ? row.projectId : null}
    historicalRunId={historicalRunId}
  />;
}
