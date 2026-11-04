package com.fuquan.audit.contract.api;

import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.web.RequestIdFilter;
import com.fuquan.audit.contract.application.ConfirmReviewMaterialsCommand;
import com.fuquan.audit.contract.application.ContractReviewSessionQueryService;
import com.fuquan.audit.contract.application.ContractReviewSessionService;
import com.fuquan.audit.contract.application.CreateReviewSessionCommand;
import com.fuquan.audit.contract.application.RemoveReviewDocumentCommand;
import com.fuquan.audit.contract.application.ReviewDocumentContent;
import com.fuquan.audit.contract.application.ReviewDocumentListResult;
import com.fuquan.audit.contract.application.ReviewDocumentOperationResult;
import com.fuquan.audit.contract.application.ReviewDocumentView;
import com.fuquan.audit.contract.application.ReviewRequestMetadata;
import com.fuquan.audit.contract.application.ReviewSessionOperationResult;
import com.fuquan.audit.contract.application.ReviewSessionTaskPage;
import com.fuquan.audit.contract.application.ReviewSessionTaskQuery;
import com.fuquan.audit.contract.application.ReviewSessionTaskSummary;
import com.fuquan.audit.contract.application.UploadReviewDocumentCommand;
import com.fuquan.audit.contract.domain.ContractReviewSession;
import com.fuquan.audit.contract.domain.MaterialBinding;
import com.fuquan.audit.contract.domain.ReviewDocument;
import com.fuquan.audit.contract.domain.ReviewDocumentKind;
import com.fuquan.audit.contract.domain.ReviewSessionStatus;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 合同审核会话、材料接入与 READY 确认 HTTP API。 */
@Validated
@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','AUDITOR','LEGAL')")
public class ContractReviewSessionController {
    private final ContractReviewSessionService service;
    private final ContractReviewSessionQueryService queryService;
    private final Tracer tracer;

    public ContractReviewSessionController(
            ContractReviewSessionService service,
            ContractReviewSessionQueryService queryService,
            Tracer tracer) {
        this.service = service;
        this.queryService = queryService;
        this.tracer = tracer;
    }

    @PostMapping("/contracts/{contractId}/review-sessions")
    public ApiResponse<SessionResponse> create(
            @PathVariable UUID contractId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateSessionRequest body,
            HttpServletRequest request) {
        ReviewSessionOperationResult result = service.create(new CreateReviewSessionCommand(
                contractId, body.name(), body.remark(), idempotencyKey, metadata(request)));
        return ApiResponse.success(SessionResponse.from(result));
    }

    @GetMapping("/contracts/{contractId}/review-sessions")
    public ApiResponse<List<SessionResponse>> list(@PathVariable UUID contractId) {
        return ApiResponse.success(service.list(contractId).stream()
                .map(session -> SessionResponse.from(session, false)).toList());
    }

    @GetMapping("/contract-review-sessions")
    public ApiResponse<TaskPageResponse> listTasks(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) ReviewSessionStatus status,
            @RequestParam(required = false) @Size(max = 128) String keyword) {
        return ApiResponse.success(TaskPageResponse.from(queryService.list(
                new ReviewSessionTaskQuery(page, size, status, keyword))));
    }

    @GetMapping("/contract-review-sessions/{sessionId}")
    public ApiResponse<SessionResponse> get(@PathVariable UUID sessionId) {
        ContractReviewSession session = service.get(sessionId);
        return ApiResponse.success(SessionResponse.from(
                session, queryService.creatorDisplayName(sessionId).orElse(null), false));
    }

    @PostMapping(
            value = "/contract-review-sessions/{sessionId}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<UploadResponse> upload(
            @PathVariable UUID sessionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestParam ReviewDocumentKind documentType,
            @RequestParam @Min(0) long expectedVersion,
            @RequestPart("file") MultipartFile file,
            HttpServletRequest request) throws IOException {
        ReviewDocumentOperationResult result = service.upload(new UploadReviewDocumentCommand(
                sessionId, documentType, expectedVersion, file.getOriginalFilename(), file.getContentType(),
                file.getInputStream(), idempotencyKey, metadata(request)));
        return ApiResponse.success(UploadResponse.from(result));
    }

    @GetMapping("/contract-review-sessions/{sessionId}/documents")
    public ApiResponse<DocumentListResponse> documents(
            @PathVariable UUID sessionId,
            @RequestParam(defaultValue = "false") boolean includeHistory) {
        return ApiResponse.success(DocumentListResponse.from(service.documents(sessionId, includeHistory)));
    }

    @GetMapping("/contract-review-sessions/{sessionId}/documents/{documentId}/content")
    public ResponseEntity<InputStreamResource> content(
            @PathVariable UUID sessionId,
            @PathVariable UUID documentId,
            @RequestParam(defaultValue = "false") boolean history) {
        ReviewDocumentContent content = service.content(sessionId, documentId, history);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(content.document().contentType()));
        headers.setContentLength(content.content().size());
        headers.setContentDisposition(ContentDisposition.inline()
                .filename(content.document().originalFileName(), StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers)
                .body(new InputStreamResource(content.content().inputStream()));
    }

    @DeleteMapping("/contract-review-sessions/{sessionId}/documents/{documentId}")
    public ApiResponse<SessionResponse> remove(
            @PathVariable UUID sessionId,
            @PathVariable UUID documentId,
            @RequestParam @Min(0) long expectedVersion,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            HttpServletRequest request) {
        ReviewSessionOperationResult result = service.remove(new RemoveReviewDocumentCommand(
                sessionId, documentId, expectedVersion, idempotencyKey, metadata(request)));
        return ApiResponse.success(SessionResponse.from(result));
    }

    @PostMapping("/contract-review-sessions/{sessionId}/confirm-materials")
    public ApiResponse<SessionResponse> confirm(
            @PathVariable UUID sessionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ConfirmMaterialsRequest body,
            HttpServletRequest request) {
        ReviewSessionOperationResult result = service.confirm(new ConfirmReviewMaterialsCommand(
                sessionId, body.expectedVersion(), idempotencyKey, metadata(request)));
        return ApiResponse.success(SessionResponse.from(result));
    }

    @PostMapping("/contract-review-sessions/{sessionId}/documents/{documentId}/recover")
    public ApiResponse<UploadResponse> recover(
            @PathVariable UUID sessionId,
            @PathVariable UUID documentId,
            HttpServletRequest request) {
        return ApiResponse.success(UploadResponse.from(
                service.recover(sessionId, documentId, metadata(request))));
    }

    private ReviewRequestMetadata metadata(HttpServletRequest request) {
        Span span = tracer.currentSpan();
        if (span == null) {
            throw new IllegalStateException("HTTP review-session request has no tracing span");
        }
        return new ReviewRequestMetadata(
                MDC.get(RequestIdFilter.MDC_KEY), span.context().traceId(), request.getRemoteAddr());
    }

    public record CreateSessionRequest(
            @NotBlank @Size(max = 256) String name,
            @Size(max = 1000) String remark) {
    }

    public record ConfirmMaterialsRequest(@Min(0) long expectedVersion) {
    }

    public record SessionResponse(
            UUID sessionId,
            UUID contractId,
            String sourceKind,
            UUID projectId,
            long contractVersion,
            String name,
            String reviewType,
            String status,
            String remark,
            String createdBy,
            String createdByName,
            Instant createdAt,
            Instant updatedAt,
            Instant materialsConfirmedAt,
            long versionNo,
            boolean deleted,
            Instant deletedAt,
            String deletedBy,
            boolean replayed) {
        static SessionResponse from(ReviewSessionOperationResult result) {
            return from(result.session(), result.replayed());
        }

        static SessionResponse from(ContractReviewSession session, boolean replayed) {
            return from(session, null, replayed);
        }

        static SessionResponse from(
                ContractReviewSession session,
                String createdByName,
                boolean replayed) {
            return new SessionResponse(
                    session.id(), session.contractId(), session.sourceKind().name(), session.projectId(),
                    session.contractVersion(), session.name(),
                    session.reviewType(), session.status().name(), session.remark(), session.createdBy(),
                    createdByName,
                    session.createdAt(), session.updatedAt(), session.materialsConfirmedAt(),
                    session.versionNo(), session.deleted(), session.deletedAt(), session.deletedBy(), replayed);
        }
    }

    public record TaskPageResponse(
            List<TaskSummaryResponse> items,
            int page,
            int size,
            long total,
            long totalPages) {
        static TaskPageResponse from(ReviewSessionTaskPage value) {
            return new TaskPageResponse(
                    value.items().stream().map(TaskSummaryResponse::from).toList(),
                    value.page(), value.size(), value.total(), value.totalPages());
        }
    }

    public record TaskSummaryResponse(
            UUID sessionId,
            String name,
            String reviewType,
            String status,
            String remark,
            long contractVersion,
            String createdBy,
            String createdByName,
            Instant createdAt,
            Instant updatedAt,
            Instant materialsConfirmedAt,
            long versionNo,
            Instant deletedAt,
            String deletedBy,
            TaskContractResponse contract) {
        static TaskSummaryResponse from(ReviewSessionTaskSummary value) {
            return new TaskSummaryResponse(
                    value.sessionId(), value.name(), value.reviewType(), value.status().name(),
                    value.remark(), value.contractVersion(), value.createdBy(), value.createdByName(),
                    value.createdAt(),
                    value.updatedAt(), value.materialsConfirmedAt(), value.versionNo(),
                    value.deletedAt(), value.deletedBy(), TaskContractResponse.from(value));
        }
    }

    public record TaskContractResponse(
            UUID id,
            String contractNo,
            String contractName,
            String contractType,
            String partyA,
            String partyB,
            String partyBCreditCode,
            java.math.BigDecimal contractAmount,
            String amountInWords,
            String status,
            long versionNo,
            Instant createdAt,
            Instant updatedAt) {
        static TaskContractResponse from(ReviewSessionTaskSummary value) {
            var contract = value.contract();
            return new TaskContractResponse(
                    contract.id(), contract.contractNo(), contract.contractName(), contract.contractType(),
                    contract.partyA(), contract.partyB(), contract.partyBCreditCode(),
                    contract.contractAmount(), contract.amountInWords(), contract.status(), contract.versionNo(),
                    contract.createdAt(), contract.updatedAt());
        }
    }

    public record DocumentResponse(
            UUID documentId,
            UUID sessionId,
            String documentKind,
            String slotKey,
            String originalFileName,
            String fileExtension,
            String contentType,
            long fileSize,
            String sha256,
            String status,
            UUID replacesDocumentId,
            Instant createdAt,
            Instant updatedAt,
            Instant storedAt,
            Instant failedAt,
            long versionNo,
            boolean current) {
        static DocumentResponse from(ReviewDocument document, boolean current) {
            return new DocumentResponse(
                    document.id(), document.sessionId(), document.documentKind().name(), document.slotKey(),
                    document.originalFileName(), document.fileExtension(), document.contentType(),
                    document.fileSize(), document.sha256(), document.status().name(),
                    document.replacesDocumentId(), document.createdAt(), document.updatedAt(),
                    document.storedAt(), document.failedAt(), document.versionNo(), current);
        }
    }

    public record BindingResponse(
            UUID bindingId,
            String slotKey,
            String documentKind,
            UUID boundDocumentId,
            boolean active,
            long versionNo) {
        static BindingResponse from(MaterialBinding binding) {
            if (binding == null) {
                return null;
            }
            return new BindingResponse(
                    binding.id(), binding.slotKey(), binding.documentKind().name(),
                    binding.boundDocumentId(), binding.active(), binding.versionNo());
        }
    }

    public record UploadResponse(
            DocumentResponse document,
            BindingResponse currentBinding,
            SessionResponse session,
            boolean replayed) {
        static UploadResponse from(ReviewDocumentOperationResult result) {
            boolean current = result.currentBinding() != null
                    && result.currentBinding().active()
                    && result.currentBinding().boundDocumentId().equals(result.document().id());
            return new UploadResponse(
                    DocumentResponse.from(result.document(), current),
                    BindingResponse.from(result.currentBinding()),
                    SessionResponse.from(result.session(), false), result.replayed());
        }
    }

    public record DocumentListResponse(
            List<DocumentResponse> currentMaterials,
            List<DocumentResponse> history) {
        static DocumentListResponse from(ReviewDocumentListResult result) {
            return new DocumentListResponse(
                    result.currentMaterials().stream().map(DocumentListResponse::fromView).toList(),
                    result.history().stream().map(DocumentListResponse::fromView).toList());
        }

        private static DocumentResponse fromView(ReviewDocumentView view) {
            return DocumentResponse.from(view.document(), view.current());
        }
    }
}
