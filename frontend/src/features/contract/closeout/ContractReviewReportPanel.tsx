import { useEffect, useMemo, useRef, useState } from "react";
import type { CloseoutContext, ReviewReport } from "./closeoutTypes.ts";
import { CloseoutDataSource, closeoutDataSource } from "./closeoutDataSource.ts";
import { CloseoutOperations, commandFingerprint, operationScope } from "./closeoutOperation.ts";
import { canGenerateReport, closeoutError, reportStatusLabel } from "./closeoutPresentation.ts";

interface Props {
  context: CloseoutContext; runStatus: string; initialReports?: ReviewReport[];
  readOnly?: boolean;
  dataSource?: CloseoutDataSource; operations?: CloseoutOperations;
}
export function ContractReviewReportPanel({ context, runStatus, initialReports = [],
  readOnly = false, dataSource = closeoutDataSource, operations: provided }: Props) {
  const scope = operationScope(context, "report");
  const activeScope = useRef(scope); activeScope.current = scope;
  const operations = useMemo(() => {
    try { return provided ?? (typeof window === "undefined" ? null : new CloseoutOperations(window.sessionStorage)); }
    catch { return null; }
  }, [provided]);
  const [reports, setReports] = useState<ReviewReport[]>(initialReports);
  const [busy, setBusy] = useState(false), [loaded, setLoaded] = useState(false);
  const [error, setError] = useState(""), [refresh, setRefresh] = useState(0);
  const [hasUncertainOperation, setHasUncertainOperation] = useState(false);
  useEffect(() => {
    let active = true;
    setLoaded(false); setBusy(false); setError(""); setReports([]); setHasUncertainOperation(false);
    dataSource.reports(context).then(result => {
      if (!active) return;
      const operation = operations?.read(scope);
      const own = operation?.resourceId ? result.find(r => r.reportId === operation.resourceId) : null;
      if (own) operations?.observe(scope, own.reportId, own.status);
      setHasUncertainOperation(Boolean(operation && !(own ? ["AVAILABLE", "FAILED"].includes(own.status) : operation.terminal)));
      setReports(result); setLoaded(true);
    }).catch(error => { if (active) setError(closeoutError(error)); });
    return () => { active = false; };
  }, [scope, refresh, operations, dataSource]);
  async function generate() {
    if (readOnly || !operations || !loaded || busy || !canGenerateReport(runStatus)) return;
    setBusy(true); setError("");
    try {
      const previous = operations.read(scope);
      // A resolved command is retired only on this explicit new-version button action.
      if (previous?.terminal) operations.newVersion(scope);
      const key = operations.begin(scope, await commandFingerprint([scope, 1]));
      setHasUncertainOperation(true);
      const report = await dataSource.generate(context, key);
      operations.observe(scope, report.reportId, report.status);
      if (activeScope.current === scope) {
        setReports(items => [...items.filter(item => item.reportId !== report.reportId), report].sort((a, b) => a.reportVersion - b.reportVersion));
        setHasUncertainOperation(!["AVAILABLE", "FAILED"].includes(report.status));
      }
    } catch (error) { if (activeScope.current === scope) setError(closeoutError(error)); }
    finally { if (activeScope.current === scope) setBusy(false); }
  }
  useEffect(() => {
    if (!readOnly && loaded && !busy && !reports.length && !hasUncertainOperation && canGenerateReport(runStatus)) {
      void generate();
    }
  }, [loaded, readOnly, runStatus, reports.length, busy, hasUncertainOperation]);
  return <section aria-label="合同审查报告" className="space-y-2 rounded border p-3">
    <h3>合同审查报告</h3>
    <p>审核结束后系统会自动生成报告；即使材料不足或结果暂未确认，也会保留对应说明。每个版本保留生成时的审查结论和查看确认记录。</p>
    <p>当前下载格式为网页文档，暂不代表正式版式、签章或客户模板验收。</p>
    {!readOnly && <button type="button" onClick={generate} disabled={!loaded || busy || !operations || !canGenerateReport(runStatus)}>
      {busy ? "正在确认报告结果" : hasUncertainOperation ? "继续确认原报告结果" : reports.length ? "生成新版本报告" : "生成报告"}
    </button>}
    <button type="button" disabled={busy} onClick={() => setRefresh(value => value + 1)}>刷新报告列表</button>
    <ul>{reports.map(report => <li key={report.reportId}>
      第 {report.reportVersion} 版 · {reportStatusLabel(report.status)}
      {report.status === "AVAILABLE" && <a className="ml-2 underline" href={dataSource.artifactUrl(context, report.reportId)} download>下载报告</a>}
    </li>)}</ul>
    {error && <p role="alert">{error}</p>}
  </section>;
}
