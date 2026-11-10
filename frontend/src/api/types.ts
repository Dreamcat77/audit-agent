/**
 * 审计系统 TypeScript 类型定义
 * 对应后端 DTO 和实体类
 */

// ==================== 通用类型 ====================

/** 风险等级 */
export type RiskLevel = 'red' | 'yellow' | 'blue' | 'green';

/** 审查状态 */
export type CheckStatus = 'pass' | 'fail' | 'warning';

// ==================== F01 预算审批校验 ====================

export interface BudgetCheckRequest {
  procurementId: string;
}

/** 单条校验结果 */
export interface CheckItemResponse {
  id: string;
  checkType: 'SUBJECT_CODE' | 'BUDGET_AMOUNT' | 'SUPPLIER_NAME' | 'SUBJECT_NAME';
  subjectCode: string | null;
  expectedValue: string | null;
  actualValue: string | null;
  passed: boolean;
  errorDetail: string | null;
  checkTime: string;
}

/** 预算校验总结果 */
export interface BudgetCheckResponse {
  procurementId: string;
  allPassed: boolean;
  totalChecks: number;
  passedChecks: number;
  items: CheckItemResponse[];
}

export interface BudgetReviewTaskResponse {
  taskId: string;
  fileName: string;
  contentType: string | null;
  fileSize: number;
  status: 'UPLOADED' | 'PARSED' | 'REVIEWED' | 'FAILED' | string;
  parsedLineCount: number;
  errorDetail: string | null;
  budgetYear: string | null;
  departmentCode: string | null;
  reviewCursor: number;
  pendingRowNumber: number | null;
  pendingStep: string | null;
  pendingReason: string | null;
  createdTime: string;
  updatedTime: string;
}

export interface BudgetReviewItemResponse {
  id: string;
  taskId: string;
  rowNumber: number | null;
  currentStep?: number;
  subjectCode: string | null;
  subjectName: string | null;
  declaredAmount: number | null;
  supplierName: string | null;
  projectCode: string | null;
  approvalSubjectCode: string | null;
  approvalSubjectName: string | null;
  approvalAmount: number | null;
  approvalSupplierName: string | null;
  matchStatus: 'MATCHED' | 'SUSPECTED' | 'UNMATCHED' | 'DATA_ERROR' | string;
  matchMethod: 'EXACT' | 'FUZZY' | null | string;
  matchDistance: number | null;
  amountStatus: 'PASSED' | 'OVER_BUDGET' | 'NOT_CHECKED' | 'NEED_CONFIRMATION' | 'DATA_FORMAT_ERROR' | string;
  differenceAmount: number | null;
  exceptionType: string | null;
  errorDetail: string | null;
  createdTime: string;
  riskLevel: 'CRITICAL' | 'WARNING' | 'INFO' | string;
  missingFields: string | null;
  amountFormatErrors: string | null;
  codeFormatError: string | null;
  requiredFieldsStatus: 'PASSED' | 'FAILED' | 'NOT_CHECKED' | string;
  amountFormatStatus: 'PASSED' | 'FAILED' | 'NOT_CHECKED' | string;
  categoryAttributionStatus: 'PASSED' | 'FAILED' | 'NOT_CHECKED' | string;
  categorySuggestion: string | null;
  projectCodeStatus: 'PASSED' | 'FAILED' | 'NOT_CHECKED' | string;
  projectDepartmentStatus: 'PASSED' | 'FAILED' | 'NOT_CHECKED' | string;
  budgetYearValidityStatus: 'PASSED' | 'FAILED' | 'NOT_CHECKED' | string;
  abnormalReasons: string[];
  checks: BudgetReviewCheckResponse[];
  moduleGroups: ValidationModuleGroup[];
  aiSemanticAnalysis: AiSemanticAnalysisResponse;
}

export interface AiSemanticAnalysisResponse {
  operation: 'BUDGET_SEMANTIC_ANALYSIS' | string;
  available: boolean;
  suggestedCategory: string | null;
  hint: string | null;
  model: string | null;
  durationMs: number | null;
  confidence: number | null;
  judgment: string | null;
  similarityScore: number | null;
  enhancedDescription: string | null;
  suggestion: string | null;
}

export interface BudgetReviewCheckResponse {
  checkType:
    | 'REQUIRED_FIELDS'
    | 'AMOUNT_FORMAT'
    | 'SUBJECT_CODE'
    | 'SUBJECT_NAME'
    | 'CATEGORY_ATTRIBUTION'
    | 'BUDGET_AMOUNT'
    | 'SUPPLIER_NAME'
    | 'PROJECT_CODE'
    | 'PROJECT_DEPARTMENT'
    | 'BUDGET_YEAR_VALIDITY'
    | string;
  expectedValue: string | null;
  actualValue: string | null;
  passed: boolean;
  status: 'PASSED' | 'FAILED' | 'PENDING' | 'NOT_CHECKED' | string;
  errorDetail: string;
}

export interface ValidationModuleGroup {
  moduleKey:
    | 'DATA_VALIDATION'
    | 'SUBJECT_CONSISTENCY'
    | 'AMOUNT_COMPLIANCE'
    | 'SUPPLIER_CHECK'
    | 'PROJECT_COMPLIANCE'
    | string;
  moduleName: string;
  moduleStatus: 'PASS' | 'ERROR' | 'PENDING' | 'WAITING' | string;
  children: BudgetReviewCheckResponse[];
  exceptionSummary: string;
}

export interface BudgetReviewResponse {
  materialReference?: BudgetMaterialReference | null;
  task: BudgetReviewTaskResponse;
  allPassed: boolean;
  totalItems: number;
  passedItems: number;
  exceptionItems: number;
  items: BudgetReviewItemResponse[];
}

export interface BudgetMaterialBatchView {
  batchId: string;
  status: string;
  budgetYear: string | null;
  departmentCode: string | null;
  createdAt: string;
  files: { taskId: string | null; fileName: string; status: string }[];
}

export interface BudgetMaterialBatchHistoryRecord {
  projectName?: string;
  batchId: string | null;
  taskId: string | null;
  status: string;
  budgetYear: string | null;
  departmentCode: string | null;
  createdAt: string;
  fileCount: number;
  previewFileNames: string[];
}

export interface BudgetMaterialBatchHistoryPage {
  records: BudgetMaterialBatchHistoryRecord[];
  page: number;
  size: number;
  total: number;
}

export interface BudgetMaterialReference {
  status: 'PENDING' | 'COMPLETED';
  coverage?: 'COMPLETE' | 'PARTIAL' | null;
  summary: string;
  projectName?: string | null;
  analysisCoverage?: 'COMPLETE'|'PARTIAL'|null;
  totals?: {role:string;scope:string;year:string|null;amount:number;sources:BudgetSourceEvidence[]}[];
  assessments?: {code:string;level:string;title:string;explanation:string;basis:string;
    sources:BudgetSourceEvidence[];}[];
  lines: {project:string|null;subject:string|null;subjectCode:string|null;year:string|null;department:string|null;
    declaredAmount:number|null;approvalAmount:number|null;status:string;opinion:string;
    sources:BudgetSourceEvidence[];}[];
}

export interface BudgetSourceEvidence {taskId:string;fileName:string;field:string;value:string;locator:string;excerpt:string;start?:number|null;end?:number|null;pageNumber?:number|null;}

export interface BudgetConfirmationProgress {
  confirmed: boolean;
  nextStep: string | null;
  executionStatus: 'PAUSED' | 'COMPLETED' | string;
  hasMoreException: boolean;
  nextExceptionRow: number | null;
  updatedRow: BudgetReviewItemResponse | null;
  nextRow: BudgetReviewItemResponse | null;
}

export interface BudgetPendingConfirmation {
  rowNumber: number | null;
  subjectName: string | null;
  abnormalReason: string | null;
  expectAmount: number | null;
  actualAmount: number | null;
  differenceAmount: number | null;
}

export interface BudgetPendingConfirmationResponse {
  hasPending: boolean;
  pendingList: BudgetPendingConfirmation[];
}

export interface BudgetConfirmLog {
  id: number;
  confirmTime: string;
  departmentName: string | null;
  confirmerName: string;
  rowNumber: number;
  confirmStep: string;
  abnormalReason: string | null;
  remark: string | null;
}

export interface BudgetValidationSummary {
  totalDeclareAmount: number;
  totalBudgetAmount: number;
  overBudgetCount: number;
  matchFailedCount: number;
}

export interface BudgetValidationBatchResponse {
  batchId: string;
  totalRows: number;
  validRows: number;
  criticalCount: number;
  warningCount: number;
  infoCount: number;
  details: BudgetReviewItemResponse[];
  summary: BudgetValidationSummary;
}

export interface BudgetValidationRecordSummary {
  batchId: string;
  fileName: string;
  status: string;
  budgetYear: string | null;
  departmentCode: string | null;
  createdTime: string;
  totalRows: number;
  validRows: number;
  criticalCount: number;
  warningCount: number;
  infoCount: number;
  processingExplanation?: string | null;
}

export interface BudgetValidationRecordPage {
  records: BudgetValidationRecordSummary[];
  page: number;
  size: number;
  total: number;
}

// ==================== F03 供应商黑名单报警 ====================

export interface BlacklistCheckRequest {
  supplierName: string;
  supplierCode?: string;
  batchId?: string;
  rowNumber?: number;
  procurementId?: string;
}

export interface BlacklistCheckResponse {
  supplierName: string;
  matched: boolean;
  matchCount: number;
  items: BlacklistMatchItem[];
}

export interface BlacklistBatchSupplier {
  rowNumber: number;
  supplierName: string;
  supplierCode?: string | null;
  procurementId?: string | null;
}

export interface BlacklistBatchCheckRequest {
  batchId: string;
  suppliers: BlacklistBatchSupplier[];
}

export interface BlacklistBatchHit {
  rowNumber: number;
  supplierName: string;
  supplierCode: string | null;
  blacklistId: string;
  blacklistType: string;
  blacklistReason: string;
  matchType: string;
  matchScore: number;
  alertId: string;
}

export interface BlacklistBatchMiss {
  rowNumber: number;
  supplierName: string;
  supplierCode: string | null;
}

export interface BlacklistBatchCheckResponse {
  batchId: string;
  totalCount: number;
  hitCount: number;
  missCount: number;
  hits: BlacklistBatchHit[];
  misses: BlacklistBatchMiss[];
}

export interface BlacklistMatchItem {
  supplierName: string;
  supplierCode: string | null;
  matchedName: string | null;
  matchType: string | null;
  matchScore: number;
  matched: boolean;
  blacklistId?: string;
  blacklistType?: string;
  blacklistReason?: string;
  alertLevel?: 'CRITICAL' | 'WARNING' | 'INFO' | string;
}

export interface BlacklistAlertItem {
  id: string;
  batchId: string;
  rowNumber: number | null;
  procurementId: string | null;
  supplierName: string;
  supplierCode: string | null;
  blacklistId: string;
  matchedName: string | null;
  blacklistType: string;
  blacklistReason: string;
  matchType: string | null;
  matchScore: number;
  alertLevel: 'CRITICAL' | 'WARNING' | 'INFO' | string;
  alertStatus: string;
  status: 'PENDING' | 'RESOLVED' | 'PROCESSED' | 'IGNORED' | 'FALSE_POSITIVE' | string;
  resolvedBy: string | null;
  handler: string | null;
  remark: string | null;
  handleRemark: string | null;
  resolvedTime: string | null;
  handleTime: string | null;
  createdTime: string;
}

export interface BlacklistEntry {
  id: string;
  supplierName: string;
  supplierCode: string | null;
  creditCode: string | null;
  blacklistType: string;
  reason: string | null;
  source: string;
  effectiveDate: string;
  expiryDate: string | null;
  active: boolean;
  status: string;
  createdBy: string;
  createdTime: string;
  updatedTime: string;
}

export interface BlacklistEntryRequest {
  supplierName: string;
  supplierCode?: string;
  creditCode?: string;
  blacklistType: string;
  reason: string;
  source: string;
  effectiveDate?: string;
  expiryDate?: string;
}

export interface BlacklistProcessRequest {
  handler: string;
  remark?: string;
  action?: 'RESOLVED' | 'PROCESSED' | 'IGNORED' | 'FALSE_POSITIVE';
}

export interface BlacklistPage<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

export interface BlacklistRule {
  id: string;
  ruleName: string;
  ruleType: string;
  blacklistTypes: string | null;
  alertLevel: 'CRITICAL' | 'WARNING' | 'INFO' | string;
  autoResolve: boolean;
  active: boolean;
  createdTime: string;
  updatedTime: string;
}

export interface BlacklistRuleRequest {
  ruleName: string;
  ruleType: string;
  blacklistTypes?: string;
  alertLevel: 'CRITICAL' | 'WARNING' | 'INFO';
  autoResolve: boolean;
}

// ==================== F06 询比价异常报警 ====================

export interface ProcurementQuoteCheckResponse {
  id: string;
  batchId: string;
  budgetAmount: number;
  averageQuote: number;
  standardDeviation: number;
  coefficientOfVariation: number;
  highlyConcentrated: boolean;
  riskLevel: 'CRITICAL' | 'WARNING' | 'INFO' | string;
  createdTime: string;
  quotes: ProcurementQuoteResponse[];
  suspiciousSuppliers: ProcurementQuoteResponse[];
  comparison?: ProcurementQuoteComparison | null;
}

export interface ProcurementQuoteComparison {
  format: string;
  materialBudget: number | null;
  effectiveCeiling: number;
  conditions: string[];
  items: { rowNumber: number; itemCode: string; itemName: string; specification: string;
    unit: string; quantity: number; offers: { supplierName: string; unitPrice: number; totalPrice: number }[];
    lowestAmount: number; lowestSuppliers: string[] }[];
  recommendedSuppliers: string[];
  recommendedAmount: number | null;
  savingAmount: number | null;
  recommendationStatus: string;
  notes: string[];
  excludedSuppliers: string[];
}

export interface ProcurementQuoteResponse {
  id: string;
  rowNumber: number;
  supplierName: string;
  quoteAmount: number;
  productDescription: string | null;
  overBudget: boolean;
  overBudgetAmount: number;
  overBudgetRatio: number;
  deviationRatio: number;
  suspicious: boolean;
  suspiciousReason: string | null;
}

export interface SpreadsheetHeaderMapping {
  sourceHeader: string;
  sourceColumnIndex: number;
  standardField: string | null;
  confidence: number | null;
}

export interface SpreadsheetPreviewRow {
  rowNumber: number;
  cells: string[];
}

export interface SpreadsheetHeaderPreview {
  firstRowNumber: number;
  rowCount: number;
  rows: SpreadsheetPreviewRow[];
  suggestedHeaderRowNumber: number | null;
  mappings: SpreadsheetHeaderMapping[];
  aiAvailable: boolean;
  aiModel: string | null;
}

// ==================== 采购项目查询和规则预审 ====================

export interface ProcurementProject {
  id: string;
  projectCode: string;
  projectName: string;
  subjectCode: string | null;
  subjectName: string | null;
  budgetAmount: number | null;
  supplierName: string | null;
  status: string;
  createdTime: string;
  departmentName: string | null;
  procurementType: string | null;
  procurementMethod: string | null;
  requirementDescription: string | null;
  applicantName: string | null;
  attachmentName: string | null;
  sourceProjectId: string | null;
  sourceMaterialIds: string[];
  agentReviewStatus: 'AVAILABLE' | 'NOT_CHECKED' | string;
  agentRiskLevel: 'INFO' | 'WARNING' | 'CRITICAL' | string | null;
  agentReviewTime: string | null;
}

export interface ProcurementProjectPage {
  items: ProcurementProject[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

// ==================== F10 招标文件合规审查 ====================

export interface ProcurementTenderParseSegment {
  sourceLocator: string;
  text: string;
  pageNumber: number | null;
  confidence: number | null;
}

export interface ProcurementTenderParseResult {
  fullText: string;
  segments: ProcurementTenderParseSegment[];
  extractedFields?: { field: string; value: string; locator: string; excerpt: string }[];
}

export interface ProcurementTenderDocument {
  id: string;
  projectId: string;
  fileName: string;
  fileExtension: string;
  contentType: string | null;
  fileSize: number;
  sha256: string;
  status: 'UPLOADING' | 'AVAILABLE' | 'PARSE_FAILED' | string;
  parseMethod: 'DOCX_TEXT' | 'PDF_TEXT' | 'OCR' | string | null;
  parseEngine: string | null;
  parseConfidence: number | null;
  pageCount: number | null;
  parseError: string | null;
  parseResult: ProcurementTenderParseResult | null;
  createdTime: string;
  updatedTime: string;
}

export interface ProcurementTenderFinding {
  id: string;
  projectId: string;
  reviewId: string;
  ruleCode: string;
  ruleName: string;
  riskCategory: string;
  riskLevel: 'CRITICAL' | 'WARNING' | 'INFO' | string;
  matchedText: string;
  sourceLocator: string;
  pageNumber: number | null;
  remediationSuggestion: string;
  legalBasis: string | null;
  ruleExplanation: string | null;
  source: 'RULE' | 'AI' | 'RULE_AND_AI' | string;
  riskReason: string | null;
  createdTime: string;
}

export interface ProcurementTenderScanResponse {
  projectId: string;
  reviewId: string;
  findingCount: number;
  aiStatus: 'AVAILABLE' | 'AI_TIMEOUT' | 'AI_UNAVAILABLE' | 'AI_INVALID_RESPONSE' | string;
  findings: ProcurementTenderFinding[];
}

export interface ProcurementAgentReviewItem {
  checkType: string;
  checkName: string;
  status: string;
  riskLevel: 'CRITICAL' | 'WARNING' | 'INFO' | string;
  detail: string;
  suggestion: string | null;
  expectedValue: string | null;
  actualValue: string | null;
  aiExplanation: string | null;
  aiSuggestion: string | null;
  needsConfirmation: boolean;
}

export interface ProcurementReviewWorkflowStep {
  stepCode: string;
  stepOrder: number;
  stepName: string;
  status: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | string;
  completedTime: string | null;
  resultSummary: string | null;
}

export interface ProcurementReviewWorkflow {
  id: string;
  projectId: string;
  currentStepOrder: number;
  status: 'IN_PROGRESS' | 'COMPLETED' | string;
  createdTime: string;
  updatedTime: string;
  steps: ProcurementReviewWorkflowStep[];
}

export interface ProcurementCollusionDocument {
  id: string;
  bidderName: string;
  ipAddress: string | null;
  handlerName: string | null;
  fileName: string;
  fileExtension: string;
  fileSize: number;
  createdTime: string;
}

export interface ProcurementCollusionSimilarity {
  documentAId: string | null;
  documentBId: string | null;
  bidderA: string;
  bidderB: string;
  fileNameA: string | null;
  fileNameB: string | null;
  similarity: number;
}

export interface ProcurementCollusionReview {
  id: string;
  projectId: string;
  riskScore: number;
  riskLevel: 'CRITICAL' | 'WARNING' | 'INFO' | string;
  quoteCoefficientOfVariation: number | null;
  maxDocumentSimilarity: number;
  duplicateIpGroups: number;
  duplicateHandlerGroups: number;
  similarities: ProcurementCollusionSimilarity[];
  assessedTime: string;
  documents: ProcurementCollusionDocument[];
  splitPurchase: ProcurementSplitPurchaseAssessment | null;
}

export interface ProcurementSplitPurchaseAssessment {
  riskScore: number;
  riskLevel: 'CRITICAL' | 'WARNING' | 'INFO' | string;
  assessedTime: string;
  findings: ProcurementSplitPurchaseFinding[];
}

export interface ProcurementSplitPurchaseFinding {
  supplierName: string;
  windowStart: string;
  windowEnd: string;
  totalAmount: number;
  thresholdAmount: number;
  thresholdName: string;
  riskScore: number;
  riskLevel: 'CRITICAL' | 'WARNING' | 'INFO' | string;
  records: Array<{
    projectId: string;
    projectCode: string;
    projectName: string;
    quoteAmount: number;
    quoteTime: string;
  }>;
}

export interface ProcurementFieldConfirmation {
  confirmerUserId: string;
  confirmerName: string;
  confirmedTime: string;
  projectId: string;
  fieldCode: string;
  fieldName: string;
  fieldValue: string | null;
  rulePrompt: string;
  aiPrompt: string | null;
  confirmationNote: string | null;
  requestId: string | null;
}

export interface ProcurementAgentReview {
  projectId: string;
  projectCode: string;
  projectName: string;
  riskLevel: 'CRITICAL' | 'WARNING' | 'INFO' | string;
  suggestion: string;
  aiSummary: string | null;
  generatedTime: string;
  items: ProcurementAgentReviewItem[];
}

export interface ProcurementReviewReportBlacklistHit {
  alertId: string;
  rowNumber: number;
  supplierName: string;
  supplierCode: string | null;
  blacklistId: string;
  blacklistType: string;
  blacklistReason: string;
  matchType: string;
  matchScore: number;
  alertLevel: 'CRITICAL' | 'WARNING' | 'INFO' | string;
  status: string;
}

export interface ProcurementReviewReport {
  id: string;
  projectId: string;
  reportNumber: string;
  generatedTime: string;
  riskLevel: 'CRITICAL' | 'WARNING' | 'INFO' | string;
  overallConclusion: string;
  project: {
    id: string;
    projectCode: string;
    projectName: string;
    budgetAmount: number | null;
    status: string;
    subjectCode: string | null;
    subjectName: string | null;
    createdTime: string;
  };
  agentReview: {
    riskLevel: 'CRITICAL' | 'WARNING' | 'INFO' | string;
    suggestion: string;
    aiSummary: string | null;
    generatedTime: string;
    items: ProcurementAgentReviewItem[];
  };
  quoteCheck: ProcurementQuoteCheckResponse | null;
  blacklist: {
    alertCount: number;
    hits: ProcurementReviewReportBlacklistHit[];
  };
  tender: {
    findingCount: number;
    findings: ProcurementReviewReportTenderFinding[];
  };
  collusion: {
    riskScore: number;
    riskLevel: 'CRITICAL' | 'WARNING' | 'INFO' | string;
    maxDocumentSimilarity: number;
    duplicateIpGroups: number;
    duplicateHandlerGroups: number;
  } | null;
  splitPurchase: {
    riskScore: number;
    riskLevel: 'CRITICAL' | 'WARNING' | 'INFO' | string;
    findingCount: number;
    findings: Array<{
      supplierName: string;
      totalAmount: number;
      thresholdAmount: number;
      thresholdName: string;
      riskScore: number;
      riskLevel: 'CRITICAL' | 'WARNING' | 'INFO' | string;
    }>;
  } | null;
}

export interface ProcurementReviewMaterialResponse {
  status: string;
  message: string;
  document: ProcurementTenderDocument | null;
  scan: ProcurementTenderScanResponse | null;
  quote: ProcurementQuoteCheckResponse | null;
}

export interface ProcurementMaterialFileResult {
  fileName: string; kind: string; status: string; message: string;
  document: ProcurementTenderDocument | null;
  scan: ProcurementTenderScanResponse | null;
}
export interface ProcurementMaterialBatchResponse {
  files: ProcurementMaterialFileResult[];
  quote: ProcurementQuoteCheckResponse | null;
  collusion: ProcurementCollusionReview | null;
  blacklist: BlacklistBatchCheckResponse | null;
  notices: string[];
  report?: ProcurementReviewReport | null;
}

export interface ProcurementSourceProject {
  id: string;
  name: string | null;
  projectNo: string | null;
  category: string | null;
  updatedAt: string | null;
  fileName?: string | null;
  fields?: ProcurementMaterialFields | null;
}

export interface ProcurementMaterialFields {
  projectName: string | null;
  projectNo: string | null;
  departmentName: string | null;
  procurementType: string | null;
  budgetAmount: string | number | null;
  subjectCode: string | null;
  subjectName: string | null;
  procurementMethod: string | null;
  requirementDescription: string | null;
  applicantName: string | null;
  evidence: Record<string, { value: string; locator: string; excerpt: string }[]>;
  conflicts: string[];
  aiModel: string;
  promptVersion: string;
}

export interface ProcurementMaterialResult {
  id: string;
  fileName: string;
  status: 'QUEUED' | 'PROCESSING' | 'PARSED' | 'FAILED' | 'UNKNOWN';
  parseMethod: string | null;
  fields: ProcurementMaterialFields | null;
  errorCode: string | null;
  createdAt: string;
  updatedAt: string;
  leaseUntil: string;
}

export interface ProcurementCriticalRiskConfirmation {
  projectId: string;
  reportId: string;
  reportNumber: string;
  riskLevel: 'CRITICAL';
  confirmedContent: string;
  confirmerUserId: string;
  confirmerName: string;
  confirmedTime: string;
  confirmationNote: string | null;
  requestId: string | null;
}

export interface ProcurementReviewReportTenderFinding {
  riskReason?: string | null;
  riskCategory: string;
  riskLevel: 'CRITICAL' | 'WARNING' | 'INFO' | string;
  matchedText: string;
  sourceLocator: string;
  remediationSuggestion: string;
}

export interface CreateProcurementProjectRequest {
  sourceProjectId: string;
  sourceMaterialIds: string[];
  projectName?: string;
  departmentName: string;
  procurementType: string;
  budgetAmount: number;
  subjectCode: string;
  subjectName: string;
  procurementMethod: string;
  requirementDescription?: string;
  applicantName: string;
  attachmentName?: string;
  submitForApproval?: boolean;
}

export interface UpdateProcurementProjectRequest {
  projectName: string;
  departmentName: string;
  procurementType: string;
  budgetAmount: number;
  subjectCode: string;
  subjectName: string;
  procurementMethod: string;
  requirementDescription?: string;
  applicantName: string;
  attachmentName?: string;
}

export interface CreateProcurementProjectResponse {
  id: string;
}

export interface ProcurementApprovalNode {
  id: string;
  instanceId: string;
  nodeCode: string;
  nodeName: string;
  nodeOrder: number;
  status: string;
  requiredRoleCode: string;
  assigneeUserId: string | null;
  assigneeName: string | null;
  startedTime: string | null;
  completedTime: string | null;
  assignmentMessage: string | null;
}

export interface ProcurementApproval {
  id: string;
  projectId: string;
  status: string;
  currentNodeCode: string | null;
  startedByUserId: string;
  startedByName: string;
  startedTime: string;
  completedTime: string | null;
  createdTime: string;
  updatedTime: string;
  nodes: ProcurementApprovalNode[];
}

export interface ProcurementApprovalOperation {
  id: string;
  instanceId: string;
  projectId: string;
  nodeCode: string;
  operationType: string;
  operatorUserId: string;
  operatorName: string;
  opinion: string | null;
  fromStatus: string | null;
  toStatus: string | null;
  operatedTime: string;
  createdTime: string;
  originalAssigneeUserId: string | null;
  originalAssigneeName: string | null;
  newAssigneeUserId: string | null;
  newAssigneeName: string | null;
  adminActing: boolean;
}

export interface ProcurementApprovalTask {
  taskType: "APPROVAL" | "PENDING_MODIFICATION";
  projectId: string;
  projectCode: string | null;
  projectName: string | null;
  budgetAmount: number | null;
  instanceId: string | null;
  instanceStatus: string;
  nodeId: string;
  nodeCode: string;
  nodeName: string;
  requiredRoleCode: string;
  assigneeUserId: string;
  assigneeName: string | null;
  startedTime: string | null;
  returnOperatorName: string | null;
  returnOpinion: string | null;
}

export interface ProcurementNotification {
  id: string;
  projectId: string;
  projectName: string;
  type: string;
  title: string;
  content: string;
  operatorName: string | null;
  opinion: string | null;
  read: boolean;
  createdTime: string;
}

export interface OperationAuditRecord {
  eventType: string;
  module: string;
  action: string;
  userId: string | null;
  username: string | null;
  resourceType: string | null;
  resourceId: string | null;
  taskId: string | null;
  resultStatus: "SUCCESS" | "FAILURE";
  businessResult: string | null;
  actionDesc: string;
  detail: string | null;
  requestId: string | null;
  traceId: string | null;
  sourceIp: string | null;
  occurredAt: string;
  resourceName: string | null;
  beforeData: string | null;
  afterData: string | null;
  failureReason: string | null;
}

// ==================== F05 项目拆分风险 ====================

export interface SplitRiskCheckRequest {
  procurementIds: string[];
}

export interface SplitRiskCheckResponse {
  assessmentId: string;
  procurementIds: string[];
  totalAmount: number;
  riskLevel: string;
  riskScore: number;
  splitRatio: number;
  timeConcentration: number;
  supplierConcentration: number;
  records: SplitRiskRecord[];
  createdAt: string;
}

export interface SplitRiskRecord {
  ruleCode: string;
  budgetSubject: string;
  supplierName: string | null;
  totalAmount: number;
  procurementCount: number;
  riskScore: number;
  riskLevel: string;
  windowStart: string;
  windowEnd: string;
  procurementIds: string[];
}

// ==================== F07 合同结构对标 ====================

export interface ContractStructureCheckRequest {
  contractId: string;
  tenderId: string;
}

export interface ContractStructureCheckResponse {
  contractId: string;
  tenderId: string;
  checkResult: string;
  missingChapters: string[];
  extraChapters: string[];
  orderExceptions: string[];
  matchRate: number;
}

// ==================== F09 合同期限审查 ====================

export interface ContractPeriodCheckRequest {
  contractId: string;
  contractText: string;
}

export interface ContractPeriodCheckResponse {
  contractId: string;
  signDate: string;
  effectiveDate: string;
  deliveryDate: string;
  acceptanceDate: string;
  hasTerminationClause: boolean;
  riskLevel: string;
  riskDetail: string;
}

// ==================== F11 围串标识别 ====================

export interface CollusionCheckRequest {
  tenderId: string;
  bidDocuments: BidDocument[];
}

export interface BidDocument {
  supplierName: string;
  bidPrice: number;
  bidText: string;
  uploadIp: string;
}

export interface CollusionCheckResponse {
  tenderId: string;
  textSimilarityScore: number;
  priceAnomalyScore: number;
  ipCorrelationScore: number;
  riskScore: number;
  riskLevel: string;
  detail: string;
}

// ==================== F13 附件审查 ====================

export interface AttachmentCheckRequest {
  contractId: string;
  contractText: string;
  attachments: string[];
}

export interface AttachmentCheckResponse {
  contractId: string;
  checkResult: string;
  missingAttachments: string[];
  extraAttachments: string[];
  detail: string;
}

// ==================== F15 条款风险提示 ====================

export interface ClauseRiskCheckRequest {
  contractId: string;
  contractText: string;
  tenderText: string;
}

export interface ClauseRiskCheckResponse {
  deviationCount: number;
  overallRiskLevel: string;
  deviations: ClauseDeviation[];
}

export interface ClauseDeviation {
  clauseType: string;
  contractClause: string;
  tenderClause: string;
  deviationDetail: string;
  riskLevel: string;
  legalReference: string;
}
