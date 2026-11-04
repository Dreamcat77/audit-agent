package com.fuquan.audit.procurement.api;

import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.procurement.application.ProcurementQuoteCheckService;
import com.fuquan.audit.procurement.application.ProcurementSpreadsheetHeaderService;
import com.fuquan.audit.procurement.application.port.SpreadsheetHeaderMapping;
import com.fuquan.audit.procurement.application.port.SpreadsheetHeaderPreview;
import com.fuquan.audit.procurement.domain.ProcurementQuoteCheck;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** F06 询比价异常报警 REST API。 */
@RestController
@Validated
@RequestMapping("/api/v1/procurement/quote-checks")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','AUDITOR')")
public class ProcurementQuoteCheckController {

    private final ProcurementQuoteCheckService service;
    private final FlowDebugLogger flowDebugLogger;
    private final ProcurementSpreadsheetHeaderService headerService;
    private final ObjectMapper objectMapper;

    public ProcurementQuoteCheckController(ProcurementQuoteCheckService service,
                                           FlowDebugLogger flowDebugLogger,
                                           ProcurementSpreadsheetHeaderService headerService,
                                           ObjectMapper objectMapper) {
        this.service = service;
        this.flowDebugLogger = flowDebugLogger;
        this.headerService = headerService;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/headers/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<SpreadsheetHeaderPreview> previewHeaders(@RequestPart("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "import file is required");
        }
        try {
            return ApiResponse.success(headerService.previewQuote(file.getInputStream()));
        } catch (IOException exception) {
            throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED,
                    "询比价 Excel 预解析失败: " + exception.getMessage(), exception);
        }
    }

    @PostMapping(value = "/upload-confirmed", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<QuoteCheckResponse> uploadConfirmed(
            @RequestPart("file") MultipartFile file,
            @RequestParam("batchId") @NotBlank String batchId,
            @RequestParam("headerRowNumber") int headerRowNumber,
            @RequestParam("mappings") String mappingsJson) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "import file is required");
        }
        try {
            List<SpreadsheetHeaderMapping> mappings = objectMapper.readValue(
                    mappingsJson, new TypeReference<List<SpreadsheetHeaderMapping>>() {});
            ProcurementQuoteCheck result = service.uploadConfirmed(
                    batchId, file.getInputStream(), headerRowNumber, mappings);
            return ApiResponse.success(QuoteCheckResponse.from(result));
        } catch (IOException exception) {
            throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED,
                    "询比价材料读取失败: " + exception.getMessage(), exception);
        }
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<QuoteCheckResponse> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam("batchId") @NotBlank String batchId) {
        if (file.isEmpty()) {
            throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED,
                    "询比价 Excel 文件不能为空");
        }
        try {
            ProcurementQuoteCheck result = service.upload(batchId, file.getInputStream());
            flowDebugLogger.log("F06_QUOTE_CHECK", "PROCUREMENT", "WEB", "upload");
            return ApiResponse.success(QuoteCheckResponse.from(result));
        } catch (IOException exception) {
            throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED,
                    "询比价材料读取失败: " + exception.getMessage(), exception);
        }
    }

    @GetMapping("/{batchId}")
    public ApiResponse<QuoteCheckResponse> get(@PathVariable String batchId) {
        return ApiResponse.success(QuoteCheckResponse.from(service.requireByBatchId(batchId)));
    }

    public record QuoteCheckResponse(
            UUID id, String batchId, BigDecimal budgetAmount, BigDecimal averageQuote,
            BigDecimal standardDeviation, BigDecimal coefficientOfVariation,
            boolean highlyConcentrated, String riskLevel, Instant createdTime,
            List<QuoteResponse> quotes, List<QuoteResponse> suspiciousSuppliers,
            com.fuquan.audit.procurement.domain.ProcurementQuoteComparison comparison) {
        static QuoteCheckResponse from(ProcurementQuoteCheck check) {
            List<QuoteResponse> quotes = check.quotes().stream().map(QuoteResponse::from).toList();
            return new QuoteCheckResponse(check.id(), check.batchId(), check.budgetAmount(),
                    check.averageQuote(), check.standardDeviation(), check.coefficientOfVariation(),
                    check.highlyConcentrated(), check.riskLevel(), check.createdTime(),
                    quotes, quotes.stream().filter(QuoteResponse::suspicious).toList(), check.comparison());
        }
    }

    public record QuoteResponse(
            UUID id, int rowNumber, String supplierName, BigDecimal quoteAmount,
            String productDescription, boolean overBudget, BigDecimal overBudgetAmount,
            BigDecimal overBudgetRatio, BigDecimal deviationRatio, boolean suspicious,
            String suspiciousReason) {
        static QuoteResponse from(ProcurementQuoteCheck.Quote quote) {
            return new QuoteResponse(quote.id(), quote.rowNumber(), quote.supplierName(),
                    quote.quoteAmount(), quote.productDescription(), quote.overBudget(),
                    quote.overBudgetAmount(), quote.overBudgetRatio(), quote.deviationRatio(),
                    quote.suspicious(), quote.suspiciousReason());
        }
    }
}
