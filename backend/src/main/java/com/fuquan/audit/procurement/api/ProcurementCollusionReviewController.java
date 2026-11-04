package com.fuquan.audit.procurement.api;

import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.procurement.application.ProcurementCollusionReviewService;
import com.fuquan.audit.procurement.domain.ProcurementCollusionAssessment;
import com.fuquan.audit.procurement.domain.ProcurementCollusionDocument;
import com.fuquan.audit.procurement.domain.ProcurementSplitPurchaseAssessment;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/procurement/projects/{projectId}/collusion-reviews")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','PROCUREMENT_APPLICANT','AUDITOR')")
public class ProcurementCollusionReviewController {
    private final ProcurementCollusionReviewService service;
    public ProcurementCollusionReviewController(ProcurementCollusionReviewService service) { this.service = service; }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<DocumentResponse> upload(@PathVariable UUID projectId, @RequestPart("file") MultipartFile file,
            @RequestParam String bidderName, @RequestParam(required = false) String ipAddress,
            @RequestParam(required = false) String handlerName) {
        if (file == null || file.isEmpty()) throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "投标文件不能为空");
        try {
            return ApiResponse.success(DocumentResponse.from(service.uploadDocument(projectId, bidderName, ipAddress,
                    handlerName, file.getOriginalFilename(), file.getContentType(), file.getSize(), file.getInputStream())));
        } catch (IOException exception) {
            throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED, "投标文件读取失败", exception);
        }
    }

    @PostMapping("/score")
    public ApiResponse<ReviewResponse> score(@PathVariable UUID projectId) {
        return ApiResponse.success(ReviewResponse.from(service.score(projectId)));
    }

    @GetMapping("/latest")
    public ApiResponse<ReviewResponse> latest(@PathVariable UUID projectId) {
        return ApiResponse.success(ReviewResponse.from(service.requireLatest(projectId)));
    }

    public record DocumentResponse(UUID id, String bidderName, String ipAddress, String handlerName,
            String fileName, String fileExtension, long fileSize, Instant createdTime) {
        static DocumentResponse from(ProcurementCollusionDocument document) { return new DocumentResponse(document.id(), document.bidderName(), document.ipAddress(), document.handlerName(), document.fileName(), document.fileExtension(), document.fileSize(), document.createdTime()); }
    }
    public record ReviewResponse(UUID id, UUID projectId, int riskScore, String riskLevel, BigDecimal quoteCoefficientOfVariation,
            BigDecimal maxDocumentSimilarity, int duplicateIpGroups, int duplicateHandlerGroups,
            List<PairSimilarityResponse> similarities, Instant assessedTime, List<DocumentResponse> documents,
            SplitPurchaseResponse splitPurchase) {
        static ReviewResponse from(ProcurementCollusionReviewService.ReviewData value) {
            ProcurementCollusionAssessment assessment = value.assessment();
            Map<UUID, String> fileNames = value.documents().stream().collect(Collectors.toMap(
                    ProcurementCollusionDocument::id, ProcurementCollusionDocument::fileName));
            return new ReviewResponse(assessment.id(), assessment.projectId(), assessment.riskScore(), assessment.riskLevel(), assessment.quoteCoefficientOfVariation(), assessment.maxDocumentSimilarity(), assessment.duplicateIpGroups(), assessment.duplicateHandlerGroups(), assessment.similarities().stream().map(item -> PairSimilarityResponse.from(item, fileNames)).toList(), assessment.assessedTime(), value.documents().stream().map(DocumentResponse::from).toList(), SplitPurchaseResponse.from(value.splitPurchaseAssessment()));
        }
    }
    public record PairSimilarityResponse(UUID documentAId, UUID documentBId, String bidderA, String bidderB,
            String fileNameA, String fileNameB, BigDecimal similarity) {
        static PairSimilarityResponse from(ProcurementCollusionAssessment.PairSimilarity value,
                Map<UUID, String> fileNames) {
            return new PairSimilarityResponse(value.documentAId(), value.documentBId(), value.bidderA(),
                    value.bidderB(), fileNames.get(value.documentAId()), fileNames.get(value.documentBId()),
                    value.similarity());
        }
    }
    public record SplitPurchaseResponse(String riskLevel, int riskScore, Instant assessedTime,
            List<SplitPurchaseFindingResponse> findings) {
        static SplitPurchaseResponse from(ProcurementSplitPurchaseAssessment value) {
            return value == null ? null : new SplitPurchaseResponse(value.riskLevel(), value.riskScore(),
                    value.assessedTime(), value.findings().stream().map(SplitPurchaseFindingResponse::from).toList());
        }
    }
    public record SplitPurchaseFindingResponse(String supplierName, Instant windowStart, Instant windowEnd,
            BigDecimal totalAmount, BigDecimal thresholdAmount, String thresholdName, int riskScore,
            String riskLevel, List<SplitPurchaseRecordResponse> records) {
        static SplitPurchaseFindingResponse from(ProcurementSplitPurchaseAssessment.Finding value) {
            return new SplitPurchaseFindingResponse(value.supplierName(), value.windowStart(), value.windowEnd(),
                    value.totalAmount(), value.thresholdAmount(), value.thresholdName(), value.riskScore(),
                    value.riskLevel(), value.records().stream().map(SplitPurchaseRecordResponse::from).toList());
        }
    }
    public record SplitPurchaseRecordResponse(UUID projectId, String projectCode, String projectName,
            BigDecimal quoteAmount, Instant quoteTime) {
        static SplitPurchaseRecordResponse from(ProcurementSplitPurchaseAssessment.Record value) {
            return new SplitPurchaseRecordResponse(value.projectId(), value.projectCode(), value.projectName(),
                    value.quoteAmount(), value.quoteTime());
        }
    }
}
