import { request, apiUrl } from "../../../api/client.ts";
import type { CloseoutContext, FindingAcknowledgement, ReviewReport } from "./closeoutTypes.ts";

export class CloseoutDataSource {
  private readonly http: typeof request;
  constructor(http: typeof request = request) { this.http = http; }
  private root(c: CloseoutContext) {
    return `/api/v1/projects/${encodeURIComponent(c.projectId)}/contracts/${encodeURIComponent(c.contractId)}/review-runs/${encodeURIComponent(c.runId)}`;
  }
  acknowledge(c: CloseoutContext, finding: string, version: number, note: string | null, key: string) {
    return this.http<FindingAcknowledgement>(`${this.root(c)}/findings/${encodeURIComponent(finding)}/acknowledgements`,
      { method: "POST", headers: { "Idempotency-Key": key }, body: { findingVersionNo: version, handlingNote: note } });
  }
  acknowledgements(c: CloseoutContext, finding: string) {
    return this.http<FindingAcknowledgement[]>(`${this.root(c)}/findings/${encodeURIComponent(finding)}/acknowledgements`);
  }
  generate(c: CloseoutContext, key: string) {
    return this.http<ReviewReport>(`${this.root(c)}/reports`, { method: "POST", headers: { "Idempotency-Key": key }, body: {} });
  }
  reports(c: CloseoutContext) { return this.http<ReviewReport[]>(`${this.root(c)}/reports`); }
  report(c: CloseoutContext, id: string) { return this.http<ReviewReport>(`${this.root(c)}/reports/${encodeURIComponent(id)}`); }
  artifactUrl(c: CloseoutContext, id: string) { return apiUrl(`${this.root(c)}/reports/${encodeURIComponent(id)}/artifact`); }
}
export const closeoutDataSource = new CloseoutDataSource();
