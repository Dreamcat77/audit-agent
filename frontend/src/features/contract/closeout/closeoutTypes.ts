export interface CloseoutContext { actorId: string; projectId: string; contractId: string; runId: string }
export interface FindingAcknowledgement {
  acknowledgementId: string; findingId: string; findingVersionNo: number;
  acknowledgedBy: string; acknowledgedAt: string; handlingNote: string | null;
}
export type ReportStatus = "PENDING" | "GENERATING" | "AVAILABLE" | "FAILED" | "UNKNOWN";
export interface ReviewReport {
  reportId: string; reviewRunId: string; reportVersion: number; reportSchemaVersion: number;
  status: ReportStatus; generatedBy: string; requestedAt: string; generatedAt: string | null;
  artifactMediaType: string | null; artifactSize: number | null;
}
