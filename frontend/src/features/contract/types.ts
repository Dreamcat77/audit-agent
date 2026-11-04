/** RoundB ContractController response DTOs. */
export type RiskLevel = "GREEN" | "YELLOW" | "RED";

export type CheckStatus = "PASS" | "FAIL" | "NOT_EVALUATED";

export type AuditTaskStatus =
  | "PENDING"
  | "PROCESSING"
  | "DONE"
  | "FAILED"
  | "PARSING"
  | "REVIEWING"
  | "NEEDS_MANUAL_REVIEW";

export interface ContractLineItem {
  id: string;
  itemKey: string;
  itemName: string;
  basis?: "QUANTITY_PRICE" | "AMOUNT_ONLY";
  quantity: number | null;
  unitPrice: number | null;
  declaredAmount: number;
  sortNo: number;
}

export interface AwardNotice {
  id: string;
  noticeNo: string;
  projectName: string;
  winningBidder: string;
  winningBidderCreditCode: string | null;
  winningAmount: number;
  winningDate: string;
  sourceRef: string | null;
}

export interface Contract {
  id: string;
  contractNo: string;
  contractName: string;
  contractType: string;
  partyA: string;
  partyB: string;
  partyBCreditCode: string | null;
  contractAmount: number;
  amountInWords: string | null;
  status: string;
  versionNo: number;
  createdAt: string;
  updatedAt: string;
  lineItems: ContractLineItem[];
  awardNotice: AwardNotice | null;
}

export interface Finding {
  id: string;
  ruleCode: string;
  itemKey: string | null;
  checkStatus: CheckStatus;
  riskLevel: RiskLevel;
  blockSign: boolean;
  actualValue: string | null;
  expectedValue: string | null;
  detail: string;
  ruleVersion: number;
  createdAt: string;
}

export interface ReviewResult {
  taskId: string;
  status: AuditTaskStatus;
  reviewType: string;
  overallRisk: RiskLevel;
  blockSign: boolean;
  replayed: boolean;
  findings: Finding[];
}

export interface FindingAcknowledgement {
  id: string;
  checkResultId: string;
  acknowledgedBy: string;
  acknowledgedByDisplayName: string;
  acknowledgedAt: string;
  requestId: string;
  traceId: string;
  ruleVersion: number;
}

export type ReviewProgressItemType = "SOURCE_PREPARATION" | "FINDING";
export type ReviewRiskSummary = "无风险" | "低风险" | "高风险";

export interface FindingSourceAnchor {
  role: string;
  projectId: string;
  fileId: string;
  fileName?: string;
  pageNumber: number | null;
  paragraphIndex: number | null;
  locator: string;
  excerpt: string;
  locatorAvailable?: boolean;
}

export interface FindingSourceProjection {
  available: boolean;
  reason: string | null;
  comparisonMode: "INTERNAL" | "CROSS_FILE" | null;
  sources: FindingSourceAnchor[];
}

export interface ReviewProgressItem {
  type: ReviewProgressItemType;
  checkResultId: string | null;
  title: string;
  displayOrder: number;
  status: AuditTaskStatus | CheckStatus;
  risk: RiskLevel | null;
  completedAt: string | null;
  acknowledgement: FindingAcknowledgement | null;
  source?: FindingSourceProjection | null;
}

export interface ReviewProgress {
  total: number;
  items: ReviewProgressItem[];
  riskSummary: ReviewRiskSummary;
}

export type ContractTextAnalysisCategory =
  | "CONTRACT_STRUCTURE"
  | "PROJECT_IDENTITY"
  | "SUPPLIER_IDENTITY"
  | "DELIVERY_TERMS"
  | "SERVICE_WARRANTY"
  | "SCOPE_AND_SUBCONTRACTING"
  | "BREACH_LIABILITY"
  | "TERM_TERMINATION"
  | "OTHER_TEXT";

export type ContractTextAnalysisAttention =
  | "INFO"
  | "REVIEW"
  | "INSUFFICIENT";

export type ContractTextAnalysisEvidenceStatus = "NORMAL" | "REVIEW" | "INSUFFICIENT";

export interface ContractTextAnalysisHint {
  category: ContractTextAnalysisCategory;
  attention: ContractTextAnalysisAttention;
  title: string;
  description: string;
  evidenceRefs: string[];
  evidenceItems?: ContractTextAnalysisEvidenceItem[];
}

export interface ContractTextAnalysisEvidenceItem {
  fileId: string;
  status: ContractTextAnalysisEvidenceStatus;
  excerpt: string | null;
  locatorAvailable: boolean;
}

export interface ContractTextAnalysis {
  projectId: string;
  operation: "CONTRACT_TEXT_ANALYSIS";
  requestId: string;
  available: boolean;
  summaries: string[];
  hints: ContractTextAnalysisHint[];
  model: string;
  durationMs: number;
}

export interface BreachClauseReview {
  materialInsufficient: boolean;
  items: Array<{
    scenario: string;
    rate: number;
    rateUnit: "PERCENT" | "PER_MILLE";
    frequency: "DAY" | "WEEK" | "MONTH" | "EVENT" | "ONCE" | null;
    calculationBasis: "CONTRACT_AMOUNT" | "LINE_ITEM_AMOUNT" | "OTHER_OR_UNCLEAR";
    correspondingAmount: number | null;
    capRate: number | null;
    capRateUnit: "PERCENT" | "PER_MILLE" | null;
    capAmount: number | null;
    actualPenaltyAmount: number | null;
    terminationCondition: string | null;
    status: "NORMAL" | "REVIEW" | "INSUFFICIENT";
    warning: string | null;
    sourceFileId: string;
    excerpt: string;
  }>;
  comparisons?: ClauseComparison[];
}

export interface ClauseComparison {
  key: string;
  label: string;
  status: "NORMAL" | "REVIEW" | "INSUFFICIENT";
  message: string;
  sources: Array<{ fileId: string; fileName: string; category: string; excerpt: string }>;
}

export interface ContractTermClauseReview {
  materialInsufficient: boolean;
  dates: Array<{
    type: "SIGNING_DATE" | "EFFECTIVE_DATE" | "PERFORMANCE_START_DATE" | "DELIVERY_DEADLINE" | "TERMINATION_DATE";
    source: string | null;
    normalizedDate: string | null;
    status: "NORMAL" | "REVIEW" | "INSUFFICIENT";
    sourceFileId: string;
    excerpt: string;
    locatorAvailable: boolean;
  }>;
  conditions: Array<{
    type: "EFFECTIVE_EVENT" | "TERMINATION";
    description: string;
    status: "NORMAL" | "REVIEW" | "INSUFFICIENT";
    sourceFileId: string;
    excerpt: string;
    locatorAvailable: boolean;
  }>;
  comparisons?: ClauseComparison[];
}
