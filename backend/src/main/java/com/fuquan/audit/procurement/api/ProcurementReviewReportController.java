package com.fuquan.audit.procurement.api;

import com.fuquan.audit.blacklist.domain.BlacklistAlert;
import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.procurement.application.ProcurementAgentReviewService;
import com.fuquan.audit.procurement.application.ProcurementCriticalRiskConfirmationService;
import com.fuquan.audit.procurement.application.ProcurementReviewReportService;
import com.fuquan.audit.procurement.domain.ProcurementProject;
import com.fuquan.audit.procurement.domain.ProcurementQuoteCheck;
import com.fuquan.audit.procurement.domain.ProcurementTenderFinding;
import com.fuquan.audit.procurement.domain.ProcurementCollusionAssessment;
import com.fuquan.audit.procurement.domain.ProcurementSplitPurchaseAssessment;
import com.fuquan.audit.security.application.AuthContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 采购审查报告 REST API。 */
@RestController
@RequestMapping("/api/v1/procurement/projects/{projectId}/review-report")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','AUDITOR')")
public class ProcurementReviewReportController {

    private final ProcurementReviewReportService service;
    private final ProcurementCriticalRiskConfirmationService criticalRiskConfirmationService;
    private final AuthContext authContext;

    public ProcurementReviewReportController(
            ProcurementReviewReportService service,
            ProcurementCriticalRiskConfirmationService criticalRiskConfirmationService,
            AuthContext authContext) {
        this.service = service;
        this.criticalRiskConfirmationService = criticalRiskConfirmationService;
        this.authContext = authContext;
    }

    @PostMapping
    public ApiResponse<ReportResponse> generate(@PathVariable UUID projectId) {
        return ApiResponse.success(ReportResponse.from(service.generate(projectId)));
    }

    @GetMapping
    public ApiResponse<ReportResponse> get(@PathVariable UUID projectId) {
        return ApiResponse.success(ReportResponse.from(service.requireLatest(projectId)));
    }

    @GetMapping("/{reportId}/critical-risk-confirmation")
    public ApiResponse<ProcurementCriticalRiskConfirmationService.Confirmation> getCriticalRiskConfirmation(
            @PathVariable UUID projectId,
            @PathVariable UUID reportId) {
        return ApiResponse.success(criticalRiskConfirmationService.find(projectId, reportId).orElse(null));
    }

    @PostMapping("/{reportId}/critical-risk-confirmation")
    public ApiResponse<ProcurementCriticalRiskConfirmationService.Confirmation> confirmCriticalRisk(
            @PathVariable UUID projectId,
            @PathVariable UUID reportId,
            @Valid @RequestBody CriticalRiskConfirmationRequest request) {
        return ApiResponse.success(criticalRiskConfirmationService.confirm(
                projectId,
                reportId,
                request.idempotencyKey(),
                request.confirmationNote(),
                authContext.currentUser().orElseThrow(() ->
                        new BusinessException(BusinessErrorCode.UNAUTHENTICATED))));
    }

    public record CriticalRiskConfirmationRequest(
            @NotBlank @Size(max = 100) String idempotencyKey,
            @Size(max = 1000) String confirmationNote) {}

    public record ReportResponse(
            UUID id,
            UUID projectId,
            String reportNumber,
            Instant generatedTime,
            String riskLevel,
            String overallConclusion,
            ProjectInfo project,
            AgentReviewInfo agentReview,
            QuoteCheckInfo quoteCheck,
            BlacklistInfo blacklist,
            TenderInfo tender,
            CollusionInfo collusion,
            SplitPurchaseInfo splitPurchase) {

        static ReportResponse from(ProcurementReviewReportService.ReportData data) {
            return new ReportResponse(
                    data.metadata().id(), data.project().id(), data.metadata().reportNumber(),
                    data.metadata().generatedTime(), data.riskLevel(), data.overallConclusion(),
                    ProjectInfo.from(data.project()),
                    AgentReviewInfo.from(data.agentReview()), QuoteCheckInfo.from(data.quoteCheck()),
                    BlacklistInfo.from(data.blacklistAlerts()), TenderInfo.from(data.tenderFindings()),
                    CollusionInfo.from(data.collusionAssessment()), SplitPurchaseInfo.from(data.splitPurchaseAssessment()));
        }
    }

    public record ProjectInfo(UUID id, String projectCode, String projectName,
                              BigDecimal budgetAmount, String status,
                              String subjectCode, String subjectName, Instant createdTime) {
        static ProjectInfo from(ProcurementProject project) {
            return new ProjectInfo(project.id(), project.projectCode(), project.projectName(),
                    project.budgetAmount(), project.status(), project.subjectCode(),
                    project.subjectName(), project.createdTime());
        }
    }

    public record AgentReviewInfo(String riskLevel, String suggestion, String aiSummary,
                                  Instant generatedTime, List<AgentReviewItemInfo> items) {
        static AgentReviewInfo from(ProcurementAgentReviewService.ReviewResult result) {
            return new AgentReviewInfo(result.riskLevel(), result.suggestion(), result.aiSummary(),
                    result.generatedTime(), result.items().stream().map(AgentReviewItemInfo::from).toList());
        }
    }

    public record AgentReviewItemInfo(String checkType, String checkName, String status,
                                      String riskLevel, String detail, String suggestion,
                                      String expectedValue, String actualValue,
                                      String aiExplanation, String aiSuggestion) {
        static AgentReviewItemInfo from(ProcurementAgentReviewService.ReviewItem item) {
            return new AgentReviewItemInfo(item.checkType(), item.checkName(), item.status(),
                    item.riskLevel(), item.detail(), item.suggestion(), item.expectedValue(),
                    item.actualValue(), item.aiExplanation(), item.aiSuggestion());
        }
    }

    public record QuoteCheckInfo(UUID id, String batchId, BigDecimal budgetAmount,
                                 BigDecimal averageQuote, BigDecimal standardDeviation,
                                 BigDecimal coefficientOfVariation, boolean highlyConcentrated,
                                 String riskLevel, Instant createdTime,
                                 List<QuoteInfo> quotes, List<QuoteInfo> suspiciousSuppliers,
                                 com.fuquan.audit.procurement.domain.ProcurementQuoteComparison comparison) {
        static QuoteCheckInfo from(ProcurementQuoteCheck check) {
            if (check == null) return null;
            List<QuoteInfo> quotes = check.quotes().stream().map(QuoteInfo::from).toList();
            return new QuoteCheckInfo(check.id(), check.batchId(), check.budgetAmount(),
                    check.averageQuote(), check.standardDeviation(), check.coefficientOfVariation(),
                    check.highlyConcentrated(), check.riskLevel(), check.createdTime(), quotes,
                    quotes.stream().filter(QuoteInfo::suspicious).toList(), check.comparison());
        }
    }

    public record QuoteInfo(UUID id, int rowNumber, String supplierName, BigDecimal quoteAmount,
                            String productDescription, boolean overBudget, BigDecimal overBudgetAmount,
                            BigDecimal overBudgetRatio, BigDecimal deviationRatio, boolean suspicious,
                            String suspiciousReason) {
        static QuoteInfo from(ProcurementQuoteCheck.Quote quote) {
            return new QuoteInfo(quote.id(), quote.rowNumber(), quote.supplierName(), quote.quoteAmount(),
                    quote.productDescription(), quote.overBudget(), quote.overBudgetAmount(),
                    quote.overBudgetRatio(), quote.deviationRatio(), quote.suspicious(),
                    quote.suspiciousReason());
        }
    }

    public record BlacklistInfo(int alertCount, List<BlacklistHitInfo> hits) {
        static BlacklistInfo from(List<BlacklistAlert> alerts) {
            return new BlacklistInfo(alerts.size(), alerts.stream().map(BlacklistHitInfo::from).toList());
        }
    }

    public record TenderInfo(int findingCount, List<TenderFindingInfo> findings) {
        static TenderInfo from(List<ProcurementTenderFinding> findings) {
            return new TenderInfo(findings.size(), findings.stream().map(TenderFindingInfo::from).toList());
        }
    }
    public record TenderFindingInfo(String riskCategory, String riskLevel, String matchedText,
                                    String sourceLocator, String remediationSuggestion, String riskReason) {
        static TenderFindingInfo from(ProcurementTenderFinding value) {
            return new TenderFindingInfo(value.riskCategory(), value.riskLevel(), value.matchedText(),
                    value.sourceLocator(), value.remediationSuggestion(), value.riskReason());
        }
    }
    public record CollusionInfo(Integer riskScore, String riskLevel, BigDecimal maxDocumentSimilarity,
                                int duplicateIpGroups, int duplicateHandlerGroups) {
        static CollusionInfo from(ProcurementCollusionAssessment value) {
            return value == null ? null : new CollusionInfo(value.riskScore(), value.riskLevel(),
                    value.maxDocumentSimilarity(), value.duplicateIpGroups(), value.duplicateHandlerGroups());
        }
    }
    public record SplitPurchaseInfo(Integer riskScore, String riskLevel, int findingCount,
                                    List<SplitPurchaseFindingInfo> findings) {
        static SplitPurchaseInfo from(ProcurementSplitPurchaseAssessment value) {
            return value == null ? null : new SplitPurchaseInfo(value.riskScore(), value.riskLevel(),
                    value.findings().size(), value.findings().stream().map(SplitPurchaseFindingInfo::from).toList());
        }
    }
    public record SplitPurchaseFindingInfo(String supplierName, BigDecimal totalAmount,
                                           BigDecimal thresholdAmount, String thresholdName,
                                           int riskScore, String riskLevel) {
        static SplitPurchaseFindingInfo from(ProcurementSplitPurchaseAssessment.Finding value) {
            return new SplitPurchaseFindingInfo(value.supplierName(), value.totalAmount(), value.thresholdAmount(),
                    value.thresholdName(), value.riskScore(), value.riskLevel());
        }
    }

    public record BlacklistHitInfo(UUID alertId, int rowNumber, String supplierName,
                                   String supplierCode, UUID blacklistId, String blacklistType,
                                   String blacklistReason, String matchType, int matchScore,
                                   String alertLevel, String status) {
        static BlacklistHitInfo from(BlacklistAlert alert) {
            return new BlacklistHitInfo(alert.id(), alert.rowNumber(), alert.supplierName(),
                    alert.supplierCode(), alert.blacklistId(), alert.blacklistType(),
                    alert.blacklistReason(), alert.matchType(), alert.matchScore(),
                    alert.alertLevel(), alert.status());
        }
    }
}
