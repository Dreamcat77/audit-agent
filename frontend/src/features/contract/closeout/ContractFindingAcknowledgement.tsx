import { useEffect, useMemo, useRef, useState } from "react";
import type { CloseoutContext, FindingAcknowledgement } from "./closeoutTypes.ts";
import { CloseoutDataSource, closeoutDataSource } from "./closeoutDataSource.ts";
import { CloseoutOperations, commandFingerprint, operationScope } from "./closeoutOperation.ts";
import { closeoutError } from "./closeoutPresentation.ts";

interface Props {
  context: CloseoutContext; findingId: string; findingVersionNo: number;
  readOnly?: boolean;
  dataSource?: CloseoutDataSource; operations?: CloseoutOperations;
}
export function ContractFindingAcknowledgement({ context, findingId, findingVersionNo,
  readOnly = false, dataSource = closeoutDataSource, operations: provided }: Props) {
  const scope = operationScope(context, "ack", findingId);
  const activeScope = useRef(scope); activeScope.current = scope;
  const operations = useMemo(() => {
    try { return provided ?? (typeof window === "undefined" ? null : new CloseoutOperations(window.sessionStorage)); }
    catch { return null; }
  }, [provided]);
  const [ack, setAck] = useState<FindingAcknowledgement | null>(null);
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState("");
  const [refresh, setRefresh] = useState(0);
  useEffect(() => {
    let active = true;
    setAck(null); setNote(""); setLoaded(false); setBusy(false); setError("");
    dataSource.acknowledgements(context, findingId).then(result => {
      if (!active) return;
      const fact = result[0] ?? null;
      if (fact) operations?.observe(scope, fact.acknowledgementId, "ACKNOWLEDGED");
      setAck(fact); setLoaded(true);
    }).catch(error => { if (active) setError(closeoutError(error)); });
    return () => { active = false; };
  }, [scope, refresh, dataSource, operations]);
  async function submit() {
    if (readOnly || !operations || !loaded || busy || ack) return;
    const normalized = note.trim() || null;
    if (normalized && [...normalized].length > 1000) { setError("处理意见最多填写一千个文字或符号。"); return; }
    setBusy(true); setError("");
    try {
      const fingerprint = await commandFingerprint([scope, findingVersionNo, normalized]);
      const key = operations.begin(scope, fingerprint);
      const fact = await dataSource.acknowledge(context, findingId, findingVersionNo, normalized, key);
      operations.observe(scope, fact.acknowledgementId, "ACKNOWLEDGED");
      if (activeScope.current === scope) setAck(fact);
    } catch (error) { if (activeScope.current === scope) setError(closeoutError(error)); }
    finally { if (activeScope.current === scope) setBusy(false); }
  }
  return <section aria-label="审查提示查看确认" className="space-y-2 rounded border p-3">
    <p className="text-xs text-slate-500">仅记录已查看，不代表问题已解决。</p>
    {ack ? <><p>已确认查看</p>{ack.handlingNote && <p>处理意见：{ack.handlingNote}</p>}</> : !readOnly && <>
      <label>处理意见（选填）<textarea aria-label="处理意见" value={note} disabled={busy || !loaded}
        onChange={event => setNote(event.target.value)} className="block w-full rounded border p-2" /></label>
      <button type="button" className="rounded border border-emerald-300 bg-white px-3 py-1.5 text-sm text-emerald-800 disabled:opacity-50" disabled={busy || !loaded || !operations} onClick={submit}>{busy ? "正在确认" : "确认已查看"}</button>
    </>}
    <button type="button" className="ml-2 rounded border border-slate-200 bg-white px-3 py-1.5 text-sm text-slate-600 disabled:opacity-50" disabled={busy} onClick={() => setRefresh(value => value + 1)}>刷新查看状态</button>
    {error && <p role="alert">{error}</p>}
  </section>;
}
