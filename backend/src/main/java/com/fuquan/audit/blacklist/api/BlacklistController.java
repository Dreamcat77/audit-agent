package com.fuquan.audit.blacklist.api;

import com.fuquan.audit.blacklist.application.port.BlacklistCheckPort;
import com.fuquan.audit.blacklist.application.BlacklistAlertService;
import com.fuquan.audit.blacklist.application.BlacklistBatchCheckService;
import com.fuquan.audit.blacklist.application.BlacklistExportService;
import com.fuquan.audit.blacklist.application.BlacklistQueryService;
import com.fuquan.audit.blacklist.application.BlacklistRuleManagementService;
import com.fuquan.audit.blacklist.application.BlacklistSupplierManagementService;
import com.fuquan.audit.procurement.application.ProcurementSpreadsheetHeaderService;
import com.fuquan.audit.procurement.application.port.SpreadsheetHeaderMapping;
import com.fuquan.audit.procurement.application.port.SpreadsheetHeaderPreview;
import com.fuquan.audit.blacklist.domain.BlacklistAlert;
import com.fuquan.audit.blacklist.domain.BlacklistMatchResult;
import com.fuquan.audit.blacklist.domain.BlacklistRule;
import com.fuquan.audit.blacklist.domain.BlacklistSupplier;
import com.fuquan.audit.blacklist.domain.BlacklistSupplierOperationLog;
import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import jakarta.validation.Valid;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * F03 供应商黑名单管理和风险预警 REST API。
 */
@RestController
@RequestMapping({"/api/v1/blacklist", "/api/blacklist"})
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','AUDITOR','PROCUREMENT_USER','BUDGET_USER','CONTRACT_USER')")
public class BlacklistController {

    private final BlacklistCheckPort blacklistCheckPort;
    private final BlacklistBatchCheckService batchCheckService;
    private final BlacklistQueryService queryService;
    private final BlacklistAlertService alertService;
    private final BlacklistSupplierManagementService supplierManagementService;
    private final BlacklistRuleManagementService ruleManagementService;
    private final BlacklistExportService exportService;
    private final AuthContext authContext;
    private final FlowDebugLogger flowDebugLogger;
    private final ProcurementSpreadsheetHeaderService headerService;
    private final ObjectMapper objectMapper;

    public BlacklistController(
            BlacklistCheckPort blacklistCheckPort,
            BlacklistBatchCheckService batchCheckService,
            BlacklistQueryService queryService,
            BlacklistAlertService alertService,
            BlacklistSupplierManagementService supplierManagementService,
            BlacklistRuleManagementService ruleManagementService,
            BlacklistExportService exportService,
            AuthContext authContext,
            FlowDebugLogger flowDebugLogger,
            ProcurementSpreadsheetHeaderService headerService,
            ObjectMapper objectMapper) {
        this.blacklistCheckPort = blacklistCheckPort;
        this.batchCheckService = batchCheckService;
        this.queryService = queryService;
        this.alertService = alertService;
        this.supplierManagementService = supplierManagementService;
        this.ruleManagementService = ruleManagementService;
        this.exportService = exportService;
        this.authContext = authContext;
        this.flowDebugLogger = flowDebugLogger;
        this.headerService = headerService;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/check-batch/headers/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<SpreadsheetHeaderPreview> previewBatchHeaders(@RequestPart("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "import file is required");
        }
        try {
            return ApiResponse.success(headerService.previewBlacklist(file.getInputStream()));
        } catch (IOException exception) {
            throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED,
                    "黑名单 Excel 预解析失败: " + exception.getMessage(), exception);
        }
    }

    @PostMapping(value = "/check-batch-upload-confirmed", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<BatchCheckResponse> checkBatchUploadConfirmed(
            @RequestParam String batchId, @RequestPart("file") MultipartFile file,
            @RequestParam int headerRowNumber, @RequestParam String mappings) {
        if (batchId == null || batchId.isBlank() || file == null || file.isEmpty()) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "batchId and file are required");
        }
        try {
            List<SpreadsheetHeaderMapping> parsedMappings = objectMapper.readValue(
                    mappings, new TypeReference<List<SpreadsheetHeaderMapping>>() {});
            BlacklistBatchCheckService.BatchResult result = batchCheckService.checkUploadConfirmed(
                    batchId.trim(), file.getInputStream(), headerRowNumber, parsedMappings);
            return ApiResponse.success(BatchCheckResponse.from(result));
        } catch (IOException exception) {
            throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED,
                    "Excel 批量检查失败: " + exception.getMessage(), exception);
        }
    }

    @PostMapping("/check")
    public ApiResponse<CheckResponse> check(@Valid @RequestBody CheckRequest request) {
        flowDebugLogger.log("F03_BLACKLIST_CHECK", "BLACKLIST", "WEB", "check");
        BlacklistCheckPort.CheckOutcome outcome = blacklistCheckPort.check(new BlacklistCheckPort.CheckCommand(
                request.batchId() != null ? request.batchId() : request.procurementId(),
                request.rowNumber(), request.procurementId(), request.supplierName(),
                request.supplierCode()));
        return ApiResponse.success(new CheckResponse(
                request.supplierName(), request.supplierCode(), outcome.batchId(),
                outcome.matched(), outcome.matches().size(),
                outcome.matches().stream().map(MatchItemResponse::from).toList(),
                outcome.persistedAlert() == null ? null : AlertResponse.from(outcome.persistedAlert())));
    }

    @PostMapping("/check-batch")
    public ApiResponse<BatchCheckResponse> checkBatch(
            @Valid @RequestBody BatchCheckRequest request) {
        flowDebugLogger.log("F03_BLACKLIST_BATCH_CHECK", "BLACKLIST", "WEB", "checkBatch");
        BlacklistBatchCheckService.BatchResult result = batchCheckService.check(
                request.batchId(),
                request.suppliers().stream()
                        .map(item -> new BlacklistBatchCheckService.SupplierInput(
                                item.rowNumber(), item.supplierName(), item.supplierCode(),
                                item.procurementId()))
                        .toList());
        return ApiResponse.success(BatchCheckResponse.from(result));
    }

    @PostMapping(value = "/check-batch-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<BatchCheckResponse> checkBatchUpload(
            @RequestParam String batchId, @RequestPart("file") MultipartFile file) {
        if (batchId == null || batchId.isBlank()) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "batchId is required");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "import file is required");
        }
        try (java.io.InputStream inputStream = file.getInputStream()) {
            BlacklistBatchCheckService.BatchResult result = batchCheckService.checkUpload(
                    batchId.trim(), inputStream);
            return ApiResponse.success(BatchCheckResponse.from(result));
        } catch (IOException exception) {
            throw new BusinessException(
                    BusinessErrorCode.BUSINESS_VALIDATION_FAILED,
                    "Excel 批量检查失败: " + exception.getMessage(), exception);
        }
    }

    @GetMapping("/alerts")
    public ApiResponse<List<AlertResponse>> listAlerts(@RequestParam(required = false) String status) {
        return ApiResponse.success(queryService.findAlerts(status).stream()
                .map(AlertResponse::from).toList());
    }

    @PostMapping("/alerts")
    public ApiResponse<PageResponse<AlertResponse>> queryAlerts(
            @Valid @RequestBody AlertQueryRequest request) {
        return ApiResponse.success(toAlertPage(queryService.findAlertPage(
                request.batchId(), request.alertLevel(), request.status(),
                request.supplierName(), request.page(), request.size())));
    }

    @GetMapping("/alerts/{id}")
    public ApiResponse<AlertResponse> getAlert(@PathVariable UUID id) {
        return ApiResponse.success(AlertResponse.from(queryService.requireAlert(id)));
    }

    @PutMapping("/alerts/{id}/process")
    public ApiResponse<AlertResponse> processAlert(
            @PathVariable UUID id, @Valid @RequestBody ProcessRequest request) {
        BlacklistAlert updated = alertService.process(
                id, request.action(), request.handler(), request.remark());
        return ApiResponse.success(AlertResponse.from(updated));
    }

    @PutMapping("/alerts/{id}/handle")
    public ApiResponse<AlertResponse> handleAlert(
            @PathVariable UUID id, @Valid @RequestBody ProcessRequest request) {
        return processAlert(id, request);
    }

    @GetMapping("/alerts/export")
    public ResponseEntity<ByteArrayResource> exportAlerts(
            @RequestParam(required = false) String batchId,
            @RequestParam(required = false) String alertLevel,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String supplierName) {
        List<BlacklistAlert> alerts = queryService.findAlertsForExport(
                batchId, alertLevel, status, supplierName);
        return excelResponse("blacklist-alerts.xlsx", exportService.exportAlerts(alerts));
    }

    @PostMapping("/entries")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','PROCUREMENT_USER','BUDGET_USER','CONTRACT_USER')")
    public ApiResponse<EntryResponse> addEntry(@Valid @RequestBody EntryRequest request) {
        BlacklistSupplier saved = supplierManagementService.add(
                new BlacklistSupplierManagementService.AddCommand(
                        request.supplierName(), request.supplierCodeOrCreditCode(),
                        request.blacklistType(), request.reason(), request.source(),
                        request.effectiveDate()),
                currentUser());
        return ApiResponse.success(EntryResponse.from(saved));
    }

    @PostMapping("/add")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','PROCUREMENT_USER','BUDGET_USER','CONTRACT_USER')")
    public ApiResponse<EntryResponse> addEntryAlias(@Valid @RequestBody EntryRequest request) {
        return addEntry(request);
    }

    @GetMapping("/entries")
    public ApiResponse<List<EntryResponse>> listEntries() {
        return ApiResponse.success(queryService.findActiveEntries().stream()
                .map(EntryResponse::from).toList());
    }

    @PostMapping("/entries/query")
    public ApiResponse<PageResponse<EntryResponse>> queryEntries(
            @Valid @RequestBody EntryQueryRequest request) {
        return ApiResponse.success(toEntryPage(queryService.findEntryPage(
                request.keyword(), request.blacklistType(), request.active(),
                request.page(), request.size())));
    }

    @PostMapping("/query")
    public ApiResponse<PageResponse<EntryResponse>> queryEntriesAlias(
            @Valid @RequestBody EntryQueryRequest request) {
        return queryEntries(request);
    }

    @PutMapping("/entries/{id}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','PROCUREMENT_USER','BUDGET_USER','CONTRACT_USER')")
    public ApiResponse<EntryResponse> updateEntry(
            @PathVariable UUID id, @Valid @RequestBody EntryRequest request) {
        BlacklistSupplier updated = supplierManagementService.update(
                id,
                new BlacklistSupplierManagementService.UpdateCommand(
                        request.supplierName(), request.supplierCodeOrCreditCode(),
                        request.blacklistType(), request.reason(), request.source(),
                        request.effectiveDate()),
                currentUser());
        return ApiResponse.success(EntryResponse.from(updated));
    }

    @PostMapping("/entries/{id}/remove")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','PROCUREMENT_USER','BUDGET_USER','CONTRACT_USER')")
    public ApiResponse<EntryResponse> removeEntry(
            @PathVariable UUID id, @Valid @RequestBody RemoveEntryRequest request) {
        BlacklistSupplierManagementService.RemovalResult result =
                supplierManagementService.remove(id, currentUser(), request.reason());
        return ApiResponse.success(EntryResponse.from(result.entry()));
    }

    @DeleteMapping("/entries/{id}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','PROCUREMENT_USER','BUDGET_USER','CONTRACT_USER')")
    public ApiResponse<Void> deleteEntry(@PathVariable UUID id) {
        supplierManagementService.remove(id, currentUser(), "通过兼容 DELETE 接口人工移除");
        return ApiResponse.success(null);
    }

    @GetMapping("/entries/{id}/operations")
    public ApiResponse<List<OperationLogResponse>> listEntryOperations(@PathVariable UUID id) {
        return ApiResponse.success(queryService.findEntryOperations(id).stream()
                .map(OperationLogResponse::from).toList());
    }

    @PostMapping(value = "/entries/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','PROCUREMENT_USER','BUDGET_USER','CONTRACT_USER')")
    public ApiResponse<ImportResponse> importEntries(@RequestPart("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "import file is required");
        }
        try (java.io.InputStream inputStream = file.getInputStream()) {
            BlacklistSupplierManagementService.ImportResult result =
                    supplierManagementService.importEntries(inputStream, currentUser());
            return ApiResponse.success(new ImportResponse(
                    result.imported(), result.failed(), result.errors()));
        } catch (IOException exception) {
            throw new BusinessException(
                    BusinessErrorCode.BUSINESS_VALIDATION_FAILED,
                    "Excel 导入失败: " + exception.getMessage(), exception);
        }
    }

    @GetMapping("/entries/export")
    public ResponseEntity<ByteArrayResource> exportEntries(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String blacklistType,
            @RequestParam(required = false) Boolean active) {
        List<BlacklistSupplier> entries = queryService.findEntriesForExport(
                keyword, blacklistType, active);
        return excelResponse("blacklist-entries.xlsx", exportService.exportEntries(entries));
    }

    @GetMapping("/rules")
    public ApiResponse<List<RuleResponse>> listRules() {
        return ApiResponse.success(queryService.findRules().stream()
                .map(RuleResponse::from).toList());
    }

    @PostMapping("/rules")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_USER','BUDGET_USER','CONTRACT_USER')")
    public ApiResponse<RuleResponse> addRule(@Valid @RequestBody RuleRequest request) {
        BlacklistRule rule = ruleManagementService.add(new BlacklistRuleManagementService.RuleCommand(
                request.ruleName(), request.ruleType(), request.blacklistTypes(),
                request.alertLevel(), request.autoResolve()));
        return ApiResponse.success(RuleResponse.from(rule));
    }

    @PutMapping("/rules/{id}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_USER','BUDGET_USER','CONTRACT_USER')")
    public ApiResponse<RuleResponse> updateRule(
            @PathVariable UUID id, @Valid @RequestBody RuleRequest request) {
        BlacklistRule updated = ruleManagementService.update(id,
                new BlacklistRuleManagementService.RuleCommand(
                        request.ruleName(), request.ruleType(), request.blacklistTypes(),
                        request.alertLevel(), request.autoResolve()));
        return ApiResponse.success(RuleResponse.from(updated));
    }

    @DeleteMapping("/rules/{id}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_USER','BUDGET_USER','CONTRACT_USER')")
    public ApiResponse<Void> deleteRule(@PathVariable UUID id) {
        ruleManagementService.delete(id);
        return ApiResponse.success(null);
    }

    private CurrentUser currentUser() {
        return authContext.currentUser().orElseThrow(() ->
                new BusinessException(BusinessErrorCode.UNAUTHENTICATED));
    }

    private static PageResponse<EntryResponse> toEntryPage(
            BlacklistQueryService.Page<BlacklistSupplier> page) {
        return new PageResponse<>(
                page.items().stream().map(EntryResponse::from).toList(),
                page.page(), page.size(), page.total(), page.totalPages());
    }

    private static PageResponse<AlertResponse> toAlertPage(
            BlacklistQueryService.Page<BlacklistAlert> page) {
        return new PageResponse<>(
                page.items().stream().map(AlertResponse::from).toList(),
                page.page(), page.size(), page.total(), page.totalPages());
    }

    private static ResponseEntity<ByteArrayResource> excelResponse(String filename, byte[] content) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(new ByteArrayResource(content));
    }

    public record CheckRequest(
            @NotBlank(message = "supplierName is required") String supplierName,
            String supplierCode,
            String procurementId,
            String batchId,
            Integer rowNumber) {}

    public record CheckResponse(
            String supplierName, String supplierCode, String batchId, boolean matched,
            int matchCount, List<MatchItemResponse> items, AlertResponse alert) {}

    public record BatchCheckRequest(
            @NotBlank(message = "batchId is required") String batchId,
            @NotEmpty(message = "suppliers must not be empty")
            @Size(max = 1000, message = "suppliers must not contain more than 1000 rows")
            List<@Valid SupplierCheckRequest> suppliers) {}

    public record SupplierCheckRequest(
            @NotNull(message = "rowNumber is required") @Min(value = 1, message = "rowNumber must be positive")
            Integer rowNumber,
            @NotBlank(message = "supplierName is required") String supplierName,
            String supplierCode,
            String procurementId) {}

    public record BatchCheckResponse(
            String batchId,
            int totalCount,
            int hitCount,
            int missCount,
            List<BatchHitResponse> hits,
            List<BatchMissResponse> misses) {
        static BatchCheckResponse from(BlacklistBatchCheckService.BatchResult result) {
            return new BatchCheckResponse(
                    result.batchId(), result.totalCount(), result.hitCount(), result.missCount(),
                    result.hits().stream().map(BatchHitResponse::from).toList(),
                    result.misses().stream().map(BatchMissResponse::from).toList());
        }
    }

    public record BatchHitResponse(
            int rowNumber,
            String supplierName,
            String supplierCode,
            UUID blacklistId,
            String blacklistType,
            String blacklistReason,
            String matchType,
            int matchScore,
            UUID alertId) {
        static BatchHitResponse from(BlacklistBatchCheckService.Hit hit) {
            return new BatchHitResponse(
                    hit.rowNumber(), hit.supplierName(), hit.supplierCode(), hit.blacklistId(),
                    hit.blacklistType(), hit.blacklistReason(), hit.matchType(),
                    hit.matchScore(), hit.alertId());
        }
    }

    public record BatchMissResponse(
            int rowNumber,
            String supplierName,
            String supplierCode) {
        static BatchMissResponse from(BlacklistBatchCheckService.Miss miss) {
            return new BatchMissResponse(miss.rowNumber(), miss.supplierName(), miss.supplierCode());
        }
    }

    public record MatchItemResponse(
            String supplierName, String supplierCode, String matchedName,
            String matchType, int matchScore, boolean matched, UUID blacklistId,
            String blacklistType, String blacklistReason, String alertLevel) {
        static MatchItemResponse from(BlacklistMatchResult result) {
            return new MatchItemResponse(result.supplierName(), result.supplierCode(),
                    result.matchedName(), result.matchType(), result.matchScore(), result.matched(),
                    result.blacklistId(), result.blacklistType(), result.blacklistReason(),
                    result.alertLevel());
        }
    }

    public record AlertResponse(
            UUID id, String batchId, Integer rowNumber, String procurementId,
            String supplierName, String supplierCode, UUID blacklistId, String matchedName,
            String blacklistType, String blacklistReason, String matchType, int matchScore,
            String alertLevel, String alertStatus, String status, String resolvedBy,
            String handler, String remark, String handleRemark, java.time.Instant resolvedTime,
            java.time.Instant handleTime, java.time.Instant createdTime) {
        static AlertResponse from(BlacklistAlert alert) {
            return new AlertResponse(alert.id(), alert.batchId(), alert.rowNumber(),
                    alert.procurementId(), alert.supplierName(), alert.supplierCode(),
                    alert.blacklistId(), alert.matchedName(), alert.blacklistType(),
                    alert.blacklistReason(), alert.matchType(), alert.matchScore(),
                    alert.alertLevel(), alert.status(), alert.status(), alert.resolvedBy(),
                    alert.handler(), alert.remark(), alert.handleRemark(), alert.resolvedTime(),
                    alert.handleTime(), alert.createdTime());
        }
    }

    public record ProcessRequest(
            @NotBlank(message = "handler is required") String handler,
            String remark, String action) {}

    public record EntryRequest(
            @NotBlank(message = "supplierName is required") String supplierName,
            String supplierCode, String creditCode,
            String blacklistType, String reason, String source,
            LocalDate effectiveDate, LocalDate expiryDate) {
        String supplierCodeOrCreditCode() {
            return supplierCode != null && !supplierCode.isBlank() ? supplierCode : creditCode;
        }
    }

    public record RemoveEntryRequest(
            @NotBlank(message = "remove reason is required") String reason) {}

    public record OperationLogResponse(
            UUID id, UUID blacklistId, String operationType, String operatorUserId,
            String operatorName, String operationReason, java.time.Instant operatedTime,
            java.time.Instant createdTime) {
        static OperationLogResponse from(BlacklistSupplierOperationLog log) {
            return new OperationLogResponse(log.id(), log.blacklistId(), log.operationType(),
                    log.operatorUserId(), log.operatorName(), log.operationReason(),
                    log.operatedTime(), log.createdTime());
        }
    }

    public record EntryQueryRequest(
            String keyword, String blacklistType, Boolean active,
            @Min(1) int page, @Min(1) @Max(100) int size) {
        public EntryQueryRequest {
            if (page == 0) page = 1;
            if (size == 0) size = 20;
        }
    }

    public record AlertQueryRequest(
            String batchId, String alertLevel, String status, String supplierName,
            @Min(1) int page, @Min(1) @Max(100) int size) {
        public AlertQueryRequest {
            if (page == 0) page = 1;
            if (size == 0) size = 20;
        }
    }

    public record PageResponse<T>(List<T> items, int page, int size, long total, long totalPages) {}

    public record ImportResponse(int imported, int failed, List<String> errors) {}

    public record EntryResponse(
            UUID id, String supplierName, String supplierCode, String creditCode,
            String blacklistType, String reason, String source, LocalDate effectiveDate,
            LocalDate expiryDate, boolean active, String status, String createdBy,
            java.time.Instant createdTime, java.time.Instant updatedTime) {
        static EntryResponse from(BlacklistSupplier item) {
            return new EntryResponse(item.id(), item.supplierName(), item.supplierCode(),
                    item.creditCode(), item.blacklistType(), item.reason(), item.source(),
                    item.effectiveDate(), item.expiryDate(), item.active(), item.status(),
                    item.createdBy(), item.createdTime(), item.updatedTime());
        }
    }

    public record RuleRequest(
            @NotBlank String ruleName,
            @NotBlank String ruleType,
            String blacklistTypes,
            @NotBlank String alertLevel,
            boolean autoResolve) {}

    public record RuleResponse(
            UUID id, String ruleName, String ruleType, String blacklistTypes,
            String alertLevel, boolean autoResolve, boolean active,
            java.time.Instant createdTime, java.time.Instant updatedTime) {
        static RuleResponse from(BlacklistRule item) {
            return new RuleResponse(item.id(), item.ruleName(), item.ruleType(),
                    item.blacklistTypes(), item.alertLevel(), item.autoResolve(),
                    item.active(), item.createdTime(), item.updatedTime());
        }
    }

}
