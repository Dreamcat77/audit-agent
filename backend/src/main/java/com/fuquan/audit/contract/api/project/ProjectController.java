package com.fuquan.audit.contract.api.project;

import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.web.RequestIdFilter;
import com.fuquan.audit.contract.application.project.ProjectApplicationService;
import com.fuquan.audit.contract.application.project.ProjectCommandTransactionCoordinator;
import com.fuquan.audit.contract.application.project.ProjectFileIngestionService;
import com.fuquan.audit.contract.application.project.ProjectFileRecoveryService;
import com.fuquan.audit.contract.application.project.ProjectFileUploadCommand;
import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.application.project.ProjectParsedTextReadService;
import com.fuquan.audit.contract.application.project.ProjectFileContentReadService;
import com.fuquan.audit.contract.application.project.ProjectFileProgressionProjection;
import com.fuquan.audit.contract.application.project.ProjectFileProgressionProjectionService;
import com.fuquan.audit.contract.application.project.ProjectFileProgressionRecoveryCommand;
import com.fuquan.audit.contract.application.project.ProjectFileProgressionRecoveryException;
import com.fuquan.audit.contract.application.project.ProjectFileProgressionRecoveryService;
import com.fuquan.audit.contract.application.project.ConfirmedContractPayload;
import com.fuquan.audit.contract.application.project.ContractCandidatePromotionResult;
import com.fuquan.audit.contract.application.project.ContractCandidatePromotionService;
import com.fuquan.audit.contract.application.project.ContractCandidateConfirmationService;
import com.fuquan.audit.contract.application.project.ContractCandidateConfirmationResult;
import com.fuquan.audit.contract.application.project.ConfirmContractCandidateCommand;
import com.fuquan.audit.contract.application.project.PromoteContractCandidateCommand;
import com.fuquan.audit.contract.application.project.ContractCandidateSupportReplacementException;
import com.fuquan.audit.contract.application.project.ContractCandidateSupportReplacementResult;
import com.fuquan.audit.contract.application.project.ContractCandidateSupportReplacementService;
import com.fuquan.audit.contract.application.project.ReplaceContractCandidateSupportAuthorityCommand;
import com.fuquan.audit.contract.application.project.CandidateSupportAuthorityReadService.SupportAuthorityProjection;
import com.fuquan.audit.contract.application.project.role.ProjectFileRoleProjection;
import com.fuquan.audit.contract.application.project.role.ProjectFileRoleProjectionService;
import com.fuquan.audit.contract.application.project.role.RoleAssessmentCommand;
import com.fuquan.audit.contract.application.project.role.RoleAssessmentResult;
import com.fuquan.audit.contract.application.project.role.RoleAssessmentService;
import com.fuquan.audit.contract.application.project.role.RoleConfirmationCommand;
import com.fuquan.audit.contract.application.project.role.RoleConfirmationResult;
import com.fuquan.audit.contract.application.project.role.RoleConfirmationService;
import com.fuquan.audit.contract.application.project.identity.EffectiveProjectIdentity;
import com.fuquan.audit.contract.application.project.identity.IdentityAssessmentCommand;
import com.fuquan.audit.contract.application.project.identity.IdentityAssessmentResult;
import com.fuquan.audit.contract.application.project.identity.IdentityAssessmentService;
import com.fuquan.audit.contract.application.project.identity.IdentityConfirmationCommand;
import com.fuquan.audit.contract.application.project.identity.IdentityConfirmationResult;
import com.fuquan.audit.contract.application.project.identity.IdentityConfirmationService;
import com.fuquan.audit.contract.application.project.identity.ProjectFileIdentityProjectionService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.port.StoredFileContent;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.ContractCandidateSupportAuthority;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleCandidateProjection;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleEvidence;
import com.fuquan.audit.contract.domain.project.ProjectFileIdentityEvidence;
import com.fuquan.audit.contract.domain.project.ProjectIdentityResolution;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.MDC;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/projects")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','AUDITOR','LEGAL')")
public class ProjectController {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.fuquan.audit.contract.application.project.port.ProjectFileJobRepository fileJobs;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.fuquan.audit.security.application.AuthContext jobAuth;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.fuquan.audit.contract.application.project.ProjectFileProcessingService fileProcessing;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.fuquan.audit.contract.application.project.port.ProjectFileProcessingAdmission localFileWorker;
    @org.springframework.beans.factory.annotation.Value("${audit.contract.file-processing.worker-enabled:false}")
    private boolean fileWorkerEnabled;
    private final ProjectApplicationService service;
    private final ProjectCommandTransactionCoordinator commands;
    private final ProjectFileIngestionService ingestion;
    private final ProjectFileRecoveryService recovery;
    private final ProjectFileContentReadService contentReader;
    private final ProjectParsedTextReadService parsedText;
    private final ContractCandidatePromotionService promotions;
    private final ContractCandidateConfirmationService candidateConfirmations;
    private final ContractCandidateSupportReplacementService supportReplacements;
    private final RoleAssessmentService roleAssessments;
    private final RoleConfirmationService roleConfirmations;
    private final ProjectFileRoleProjectionService roleProjections;
    private final IdentityAssessmentService identityAssessments;
    private final IdentityConfirmationService identityConfirmations;
    private final ProjectFileIdentityProjectionService identityProjections;
    private final ProjectFileProgressionProjectionService progressionProjections;
    private final ProjectFileProgressionRecoveryService progressionRecovery;

    public ProjectController(ProjectApplicationService service, ProjectCommandTransactionCoordinator commands,
                             ProjectFileIngestionService ingestion, ProjectFileRecoveryService recovery,
                             ProjectFileContentReadService contentReader, ProjectParsedTextReadService parsedText,
                             ContractCandidatePromotionService promotions,
                             ContractCandidateConfirmationService candidateConfirmations,
                             ContractCandidateSupportReplacementService supportReplacements,
                             RoleAssessmentService roleAssessments, RoleConfirmationService roleConfirmations,
                             ProjectFileRoleProjectionService roleProjections,
                             IdentityAssessmentService identityAssessments,
                             IdentityConfirmationService identityConfirmations,
                             ProjectFileIdentityProjectionService identityProjections,
                             ProjectFileProgressionProjectionService progressionProjections,
                             ProjectFileProgressionRecoveryService progressionRecovery) {
        this.service = service;
        this.commands = commands;
        this.ingestion = ingestion;
        this.recovery = recovery;
        this.contentReader = contentReader;
        this.parsedText = parsedText;
        this.promotions = promotions;
        this.candidateConfirmations = candidateConfirmations;
        this.supportReplacements = supportReplacements;
        this.roleAssessments = roleAssessments;
        this.roleConfirmations = roleConfirmations;
        this.roleProjections = roleProjections;
        this.identityAssessments = identityAssessments;
        this.identityConfirmations = identityConfirmations;
        this.identityProjections = identityProjections;
        this.progressionProjections = progressionProjections;
        this.progressionRecovery = progressionRecovery;
    }

    @PostMapping
    public ApiResponse<ProjectResponse> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey, HttpServletRequest request) {
        return ApiResponse.success(ProjectResponse.from(commands.create(idempotencyKey,
                requestId(), traceId(), request.getRemoteAddr())));
    }

    @GetMapping
    public ApiResponse<ProjectPageResponse> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 200) String keyword) {
        ProjectApplicationService.ProjectPage result = service.list(page, size, keyword);
        return ApiResponse.success(new ProjectPageResponse(
                result.items().stream().map(ProjectResponse::from).toList(),
                result.page(), result.size(), result.total()));
    }

    @GetMapping("/intake-drafts")
    public ApiResponse<IntakeDraftPageResponse> intakeDrafts(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        ProjectApplicationService.IntakeDraftPage result = service.listIntakeDrafts(page, size);
        return ApiResponse.success(new IntakeDraftPageResponse(
                result.items().stream().map(IntakeDraftResponse::from).toList(),
                result.page(), result.size(), result.total()));
    }

    @GetMapping("/creation-operations")
    public ApiResponse<ProjectResponse> createOperation(@RequestParam @Pattern(regexp="^[A-Za-z0-9._:-]{1,128}$") String key) {
        return ApiResponse.success(ProjectResponse.from(commands.readCreateOperation(key)));
    }

    @GetMapping("/preparation-drafts")
    public ApiResponse<PreparationDraftPageResponse> preparationDrafts(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        ProjectApplicationService.PreparationDraftPage result = service.listPreparationDrafts(page, size);
        return ApiResponse.success(new PreparationDraftPageResponse(
                result.items().stream().map(PreparationDraftResponse::from).toList(),
                result.page(), result.size(), result.total()));
    }

    @GetMapping("/{projectId}")
    public ApiResponse<ProjectDetailResponse> detail(@PathVariable UUID projectId) {
        return ApiResponse.success(ProjectDetailResponse.from(service.detail(projectId), this::fileResponse));
    }

    @PatchMapping("/{projectId}")
    public ApiResponse<ProjectResponse> confirm(
            @PathVariable UUID projectId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ProjectMetadataRequest body,
            HttpServletRequest request) {
        return ApiResponse.success(ProjectResponse.from(commands.confirm(projectId, body.expectedVersion(),
                body.name(), body.projectNo(), body.category(), idempotencyKey,
                requestId(), traceId(), request.getRemoteAddr())));
    }

    @PostMapping(value = "/{projectId}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ProjectFileResponse> upload(
            @PathVariable UUID projectId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestPart("file") MultipartFile file,
            HttpServletRequest request) throws IOException {
        ProjectFile uploaded = ingestion.upload(new ProjectFileUploadCommand(projectId,
                file.getOriginalFilename(), file.getContentType(), file.getSize(), file.getInputStream(),
                idempotencyKey, metadata(request)));
        return ApiResponse.success(fileResponse(uploaded));
    }

    @GetMapping("/{projectId}/files")
    public ApiResponse<List<ProjectFileResponse>> files(@PathVariable UUID projectId) {
        return ApiResponse.success(service.files(projectId).stream().map(this::fileResponse).toList());
    }

    @PostMapping(value="/{projectId}/file-jobs",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<AcceptedFileResponse>> acceptFile(@PathVariable UUID projectId,
            @RequestHeader("Idempotency-Key") @Pattern(regexp="^[A-Za-z0-9._:-]{1,128}$") String key,
            @RequestPart("file") MultipartFile file,HttpServletRequest request) throws IOException {
        requireFileJobs();
        ProjectFile accepted=ingestion.acceptAsync(new ProjectFileUploadCommand(projectId,file.getOriginalFilename(),
                file.getContentType(),file.getSize(),file.getInputStream(),key,metadata(request)),fileJobs);
        var job=fileJobs.byFile(projectId,accepted.id()).orElseThrow(()->new BusinessException(
                BusinessErrorCode.CONFLICT,"accepted processing identity is not yet proven"));
        if(localFileWorker!=null)localFileWorker.accepted(job.id(),jobAuth.currentUser().orElseThrow(
                ()->new BusinessException(BusinessErrorCode.UNAUTHENTICATED)));
        return ResponseEntity.accepted().body(ApiResponse.success(new AcceptedFileResponse(projectId,accepted.id(),
                job.id(),fileResponse(accepted),FileJobResponse.from(job))));
    }

    @GetMapping("/{projectId}/file-jobs")
    public ApiResponse<FileJobPageResponse> fileJobs(@PathVariable UUID projectId) {
        requireFileJobs();service.get(projectId);
        var jobs=fileJobs.byProject(projectId);
        return ApiResponse.success(new FileJobPageResponse(projectId,fileWorkerEnabled||localFileWorker!=null,
                jobs.stream().map(FileJobResponse::from).toList(),jobs.size(),
                jobs.stream().filter(j->"SUCCEEDED".equals(j.status())).count(),
                jobs.stream().filter(j->"FAILED".equals(j.status())).count(),
                jobs.stream().filter(j->"UNKNOWN".equals(j.status())).count()));
    }

    @GetMapping("/{projectId}/files/{fileId}/processing")
    public ApiResponse<FileJobResponse> processing(@PathVariable UUID projectId,@PathVariable UUID fileId) {
        requireFileJobs();service.file(projectId,fileId);
        return ApiResponse.success(FileJobResponse.from(fileJobs.byFile(projectId,fileId).orElseThrow(
                ()->new BusinessException(BusinessErrorCode.NOT_FOUND,"file has no background processing job"))));
    }

    @PostMapping("/{projectId}/files/{fileId}/processing/retry")
    public ApiResponse<FileJobResponse> retryProcessing(@PathVariable UUID projectId,@PathVariable UUID fileId,HttpServletRequest request) {
        requireFileJobs();service.file(projectId,fileId);
        var actor=jobAuth.currentUser().orElseThrow(()->new BusinessException(BusinessErrorCode.UNAUTHENTICATED));
        return ApiResponse.success(FileJobResponse.from(fileProcessing.retry(projectId,fileId,actor,metadata(request))));
    }

    @GetMapping("/{projectId}/file-jobs/by-operation")
    public ApiResponse<AcceptedFileResponse> byUploadOperation(@PathVariable UUID projectId,
            @RequestParam @Pattern(regexp="^[A-Za-z0-9._:-]{1,128}$") String key) {
        requireFileJobs();
        var actor=jobAuth.currentUser().orElseThrow(()->new BusinessException(BusinessErrorCode.UNAUTHENTICATED));
        var original=ingestion.asyncOperation(key).orElseThrow(()->new BusinessException(BusinessErrorCode.NOT_FOUND));
        if(!original.actorId().equals(actor.userId())||!original.resourceId().equals(projectId.toString())) {
            throw new BusinessException(BusinessErrorCode.NOT_FOUND);
        }
        ProjectFile file=service.file(projectId,UUID.fromString(original.resultResourceId()));
        var job=fileJobs.byFile(projectId,file.id()).orElse(null);
        return ApiResponse.success(new AcceptedFileResponse(projectId,file.id(),job==null?null:job.id(),
                fileResponse(file),job==null?null:FileJobResponse.from(job)));
    }

    private void requireFileJobs() {
        if(fileJobs==null)throw new BusinessException(BusinessErrorCode.CONFLICT,"background file processing is unavailable");
    }

    public record AcceptedFileResponse(UUID projectId,UUID fileId,UUID jobId,ProjectFileResponse file,FileJobResponse job) {}
    public record FileJobResponse(UUID jobId,UUID projectId,UUID fileId,String status,int attemptNo,String reasonCode,
                                  UUID parseResultId,java.time.Instant createdAt,java.time.Instant updatedAt) {
        static FileJobResponse from(com.fuquan.audit.contract.domain.project.ProjectFileJob job) {
            return new FileJobResponse(job.id(),job.projectId(),job.fileId(),job.status(),job.attemptNo(),job.reasonCode(),
                    job.parseResultId(),job.createdAt(),job.updatedAt());
        }
    }
    public record FileJobPageResponse(UUID projectId,boolean workerEnabled,List<FileJobResponse> items,long total,
                                      long completed,long failed,long unknown) {}

    @PostMapping("/{projectId}/files/{fileId}/role-assessments")
    public ApiResponse<RoleAssessmentResponse> assessRole(
            @PathVariable UUID projectId, @PathVariable UUID fileId,
            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = "^[A-Za-z0-9._:-]{1,128}$") String idempotencyKey,
            HttpServletRequest request) {
        RoleAssessmentResult result = roleAssessments.assess(new RoleAssessmentCommand(
                projectId, fileId, idempotencyKey, metadata(request)));
        return ApiResponse.success(RoleAssessmentResponse.from(result));
    }

    @GetMapping("/{projectId}/files/{fileId}/role-assessments/{assessmentId}")
    public ApiResponse<RoleAssessmentResponse> roleAssessment(
            @PathVariable UUID projectId, @PathVariable UUID fileId, @PathVariable UUID assessmentId) {
        return ApiResponse.success(RoleAssessmentResponse.from(
                roleAssessments.get(projectId, fileId, assessmentId)));
    }

    @PostMapping("/{projectId}/files/{fileId}/role-confirmations")
    public ApiResponse<RoleConfirmationResponse> confirmRole(
            @PathVariable UUID projectId, @PathVariable UUID fileId,
            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = "^[A-Za-z0-9._:-]{1,128}$") String idempotencyKey,
            @Valid @RequestBody RoleConfirmationRequest body, HttpServletRequest request) {
        RoleConfirmationResult result = roleConfirmations.confirm(new RoleConfirmationCommand(
                projectId, fileId, body.assessmentId(), body.selectedRole(), body.expectedVersion(),
                body.reason(), idempotencyKey, metadata(request)));
        ProjectFileRoleProjection effective = roleProjections.project(service.file(projectId, fileId));
        return ApiResponse.success(RoleConfirmationResponse.from(result, effective));
    }

    @GetMapping("/{projectId}/files/{fileId}/role-decisions/{decisionId}")
    public ApiResponse<RoleConfirmationResponse> roleDecision(
            @PathVariable UUID projectId, @PathVariable UUID fileId, @PathVariable UUID decisionId) {
        RoleConfirmationResult result = roleConfirmations.get(projectId, fileId, decisionId);
        ProjectFileRoleProjection effective = roleProjections.project(service.file(projectId, fileId));
        return ApiResponse.success(RoleConfirmationResponse.from(result, effective));
    }

    @PostMapping("/{projectId}/files/{fileId}/identity-assessments")
    public ApiResponse<IdentityAssessmentResponse> assessIdentity(
            @PathVariable UUID projectId, @PathVariable UUID fileId,
            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = "^[A-Za-z0-9._:-]{1,128}$") String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.success(IdentityAssessmentResponse.from(identityAssessments.assess(
                new IdentityAssessmentCommand(projectId, fileId, idempotencyKey, metadata(request)))));
    }

    @GetMapping("/{projectId}/files/{fileId}/identity-assessments/{assessmentId}")
    public ApiResponse<IdentityAssessmentResponse> identityAssessment(
            @PathVariable UUID projectId, @PathVariable UUID fileId, @PathVariable UUID assessmentId) {
        return ApiResponse.success(IdentityAssessmentResponse.from(
                identityAssessments.get(projectId, fileId, assessmentId)));
    }

    @PostMapping("/{projectId}/files/{fileId}/identity-confirmations")
    public ApiResponse<IdentityConfirmationResponse> confirmIdentity(
            @PathVariable UUID projectId, @PathVariable UUID fileId,
            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = "^[A-Za-z0-9._:-]{1,128}$") String idempotencyKey,
            @Valid @RequestBody IdentityConfirmationRequest body, HttpServletRequest request) {
        IdentityConfirmationResult result = identityConfirmations.confirm(new IdentityConfirmationCommand(
                projectId, fileId, body.assessmentId(), body.resolution(), body.expectedVersion(),
                body.reason(), idempotencyKey, metadata(request)));
        EffectiveProjectIdentity effective = identityProjections.project(service.file(projectId, fileId));
        return ApiResponse.success(IdentityConfirmationResponse.from(result, effective));
    }

    @GetMapping("/{projectId}/files/{fileId}/identity-decisions/{decisionId}")
    public ApiResponse<IdentityConfirmationResponse> identityDecision(
            @PathVariable UUID projectId, @PathVariable UUID fileId, @PathVariable UUID decisionId) {
        IdentityConfirmationResult result = identityConfirmations.get(projectId, fileId, decisionId);
        EffectiveProjectIdentity effective = identityProjections.project(service.file(projectId, fileId));
        return ApiResponse.success(IdentityConfirmationResponse.from(result, effective));
    }

    @GetMapping("/{projectId}/files/{fileId}/content")
    public ResponseEntity<InputStreamResource> content(
            @PathVariable UUID projectId, @PathVariable UUID fileId) {
        ProjectFile file = service.file(projectId, fileId);
        StoredFileContent content = contentReader.openVerified(file);
        MediaType mediaType;
        try { mediaType = MediaType.parseMediaType(file.mediaType()); }
        catch (IllegalArgumentException ignored) { mediaType = MediaType.APPLICATION_OCTET_STREAM; }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(content.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.originalFilename(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new InputStreamResource(content.inputStream()));
    }

    @PostMapping("/{projectId}/files/{fileId}/recover")
    public ApiResponse<ProjectFileResponse> recover(
            @PathVariable UUID projectId, @PathVariable UUID fileId, HttpServletRequest request) {
        return ApiResponse.success(fileResponse(recovery.recover(projectId, fileId, metadata(request))));
    }

    @PostMapping("/{projectId}/files/{fileId}/progression/resume")
    public ApiResponse<ProjectFileResponse> resumeProgression(
            @PathVariable UUID projectId, @PathVariable UUID fileId,
            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = "^[A-Za-z0-9._:-]{1,128}$") String idempotencyKey,
            @Valid @RequestBody ProgressionRecoveryRequest body,
            HttpServletRequest request) {
        var result = progressionRecovery.resume(new ProjectFileProgressionRecoveryCommand(
                projectId, fileId, body.expectedBlocker(), body.expectedRecoveryInputFingerprint(),
                idempotencyKey, metadata(request)));
        return ApiResponse.success(fileResponse(result.file()));
    }

    @GetMapping("/{projectId}/contract-candidates")
    public ApiResponse<List<ContractCandidate>> candidates(@PathVariable UUID projectId) {
        return ApiResponse.success(service.candidates(projectId));
    }

    @GetMapping("/{projectId}/contract-candidates/{candidateId}")
    public ApiResponse<CandidateDetailResponse> candidate(
            @PathVariable UUID projectId, @PathVariable UUID candidateId) {
        ProjectApplicationService.CandidateDetail detail = service.candidate(projectId, candidateId);
        return ApiResponse.success(new CandidateDetailResponse(detail.candidate(),
                fileResponse(detail.sourceFile()), detail.evidence(), detail.fieldStates(),
                detail.lineItemFieldStates(), detail.sourceParseResultId(), detail.sourceRoleDecisionId(),
                detail.sourceIdentityDecisionId(),
                detail.candidateAuthoritySource().name(), detail.sourceFactFingerprint(),
                detail.candidate().promotionProvenanceSource(), detail.authorityMode().name(),
                detail.supportAuthorityVersionNo(), detail.supportAuthorityFingerprint(), detail.supportAuthorities()));
    }

    @PostMapping("/{projectId}/contract-candidates/{candidateId}/support-authority-replacements")
    public ApiResponse<SupportAuthorityReplacementResponse> replaceSupportAuthority(
            @PathVariable UUID projectId, @PathVariable UUID candidateId,
            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = "^[A-Za-z0-9._:-]{1,128}$") String idempotencyKey,
            @RequestBody com.fasterxml.jackson.databind.JsonNode input, HttpServletRequest request) {
        SupportAuthorityReplacementRequest body;
        try {
            body = SupportAuthorityReplacementRequest.from(input);
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "invalid candidate support replacement payload");
        }
        var result = supportReplacements.replace(new ReplaceContractCandidateSupportAuthorityCommand(projectId,
                candidateId, body.expectedCandidateVersion(), body.supportFileIds(), idempotencyKey, metadata(request)));
        return ApiResponse.success(SupportAuthorityReplacementResponse.from(result));
    }

    @ExceptionHandler(ContractCandidateSupportReplacementException.class)
    public ResponseEntity<ApiResponse<SupportAuthorityReplacementErrorData>> handleSupportReplacement(
            ContractCandidateSupportReplacementException exception) {
        var status = exception.errorCode().httpStatus();
        return ResponseEntity.status(status).body(ApiResponse.failure(status.value(), exception.getMessage(),
                new SupportAuthorityReplacementErrorData(exception.classification())));
    }

    @ExceptionHandler(ProjectFileProgressionRecoveryException.class)
    public ResponseEntity<ApiResponse<ProgressionRecoveryErrorData>> handleProgressionRecovery(
            ProjectFileProgressionRecoveryException exception) {
        var status = exception.errorCode().httpStatus();
        return ResponseEntity.status(status).body(ApiResponse.failure(status.value(), exception.getMessage(),
                new ProgressionRecoveryErrorData(exception.classification().name())));
    }

    @PostMapping("/{projectId}/contract-candidates/{candidateId}/confirmations")
    public ApiResponse<ConfirmationResponse> confirmCandidate(
            @PathVariable UUID projectId, @PathVariable UUID candidateId,
            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = "^[A-Za-z0-9._:-]{1,128}$") String idempotencyKey,
            @Valid @RequestBody ConfirmationRequest body, HttpServletRequest request) {
        try {
            var result = candidateConfirmations.confirm(new ConfirmContractCandidateCommand(projectId,
                    candidateId, body.expectedVersion(), body.payload(), idempotencyKey, metadata(request)));
            return ApiResponse.success(ConfirmationResponse.from(result));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "invalid candidate confirmation payload");
        }
    }

    @GetMapping("/{projectId}/contract-candidates/{candidateId}/confirmations/latest")
    public ApiResponse<ConfirmationResponse> latestCandidateConfirmation(
            @PathVariable UUID projectId, @PathVariable UUID candidateId) {
        return ApiResponse.success(ConfirmationResponse.from(candidateConfirmations.latest(projectId, candidateId)));
    }

    @GetMapping("/{projectId}/contract-candidates/{candidateId}/confirmations/{confirmationId}")
    public ApiResponse<ConfirmationResponse> candidateConfirmation(
            @PathVariable UUID projectId, @PathVariable UUID candidateId,
            @PathVariable UUID confirmationId) {
        return ApiResponse.success(ConfirmationResponse.from(candidateConfirmations.get(
                projectId, candidateId, confirmationId)));
    }

    @PostMapping("/{projectId}/contract-candidates/{candidateId}/promote")
    public ApiResponse<PromotionResponse> promote(
            @PathVariable UUID projectId,
            @PathVariable UUID candidateId,
            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = "^[A-Za-z0-9._:-]{1,128}$") String idempotencyKey,
            @RequestBody com.fasterxml.jackson.databind.JsonNode input,
            HttpServletRequest request) {
        try {
            PromotionRequest body = PromotionRequest.from(input);
            ContractCandidatePromotionResult result = promotions.promote(new PromoteContractCandidateCommand(
                    projectId, candidateId, body.expectedVersion(), body.confirmationId(), idempotencyKey,
                    metadata(request)));
            return ApiResponse.success(PromotionResponse.from(result));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "invalid promotion confirmation identity");
        }
    }

    private ProjectFileResponse fileResponse(ProjectFile file) {
        ProjectFileRoleProjection role = roleProjections.project(file);
        EffectiveProjectIdentity identity = identityProjections.project(file);
        return ProjectFileResponse.from(file, parsedText.read(file), role, identity,
                progressionProjections.project(file, role, identity));
    }

    private static String requestId() {
        String value = MDC.get(RequestIdFilter.MDC_KEY);
        return value == null ? "unavailable" : value;
    }

    private static String traceId() {
        String value = MDC.get("traceId");
        return value == null ? requestId() : value;
    }

    private static ProjectRequestMetadata metadata(HttpServletRequest request) {
        return new ProjectRequestMetadata(requestId(), traceId(), request.getRemoteAddr());
    }

    public record ProjectMetadataRequest(
            @Min(0) long expectedVersion,
            @Size(max = 200) String name,
            @Size(max = 100) String projectNo,
            @Size(max = 100) String category) {
    }

    public record ProgressionRecoveryRequest(
            @NotNull
            @Pattern(regexp = "^(ROLE_ASSESSMENT_PENDING|IDENTITY_ASSESSMENT_PENDING|IDENTITY_ASSESSMENT_STALE|CANDIDATE_MATERIALIZATION_PENDING|FACT_EXTRACTION_PENDING)$")
            String expectedBlocker,
            @NotNull @Pattern(regexp = "^[0-9a-f]{64}$")
            String expectedRecoveryInputFingerprint) { }

    public record ProgressionRecoveryErrorData(String classification) { }

    public record ProjectResponse(UUID id, String name, String projectNo, String category,
                                  boolean metadataConfirmed, long versionNo,
                                  java.time.Instant createdAt, java.time.Instant updatedAt) {
        static ProjectResponse from(Project project) {
            return new ProjectResponse(project.id(), project.name(), project.projectNo(), project.category(),
                    project.metadataConfirmed(), project.versionNo(), project.createdAt(), project.updatedAt());
        }
    }

    public record ProjectPageResponse(List<ProjectResponse> items, int page, int size, long total) {
    }

    public record IntakeDraftPageResponse(List<IntakeDraftResponse> items, int page, int size, long total) {
    }

    public record IntakeDraftResponse(
            UUID projectId, long versionNo, String packageFilename, String intakeStatus,
            long parsedFileCount, long ocrFileCount, long failedFileCount,
            String candidateProjectName, String candidateProjectNo, java.time.Instant createdAt) {
        static IntakeDraftResponse from(ProjectApplicationService.IntakeDraft draft) {
            return new IntakeDraftResponse(draft.projectId(), draft.versionNo(), draft.packageFilename(),
                    draft.intakeStatus(), draft.parsedFileCount(), draft.ocrFileCount(), draft.failedFileCount(),
                    draft.candidateProjectName(), draft.candidateProjectNo(), draft.createdAt());
        }
    }

    public record PreparationDraftPageResponse(
            List<PreparationDraftResponse> items, int page, int size, long total) {
    }

    public record PreparationDraftResponse(UUID projectId, java.time.Instant createdAt, long uploadedFileCount) {
        static PreparationDraftResponse from(ProjectApplicationService.PreparationDraft draft) {
            return new PreparationDraftResponse(
                    draft.projectId(), draft.createdAt(), draft.uploadedFileCount());
        }
    }

    public record ProjectDetailResponse(
            ProjectResponse project,
            String intakeStatus,
            List<ProjectFileResponse> files,
            List<ContractCandidate> candidates,
            List<ExtractionResult> extractionResults,
            List<ProjectApplicationService.AssociatedContractSummary> associatedContracts,
            long failedFileCount,
            long unsupportedFileCount) {
        static ProjectDetailResponse from(ProjectApplicationService.ProjectDetail detail,
                                          java.util.function.Function<ProjectFile, ProjectFileResponse> mapper) {
            return new ProjectDetailResponse(ProjectResponse.from(detail.project()), detail.intakeStatus(),
                    detail.files().stream().map(mapper).toList(),
                    detail.candidates(), detail.extractions(),
                    detail.associatedContracts(),
                    detail.failedFileCount(), detail.unsupportedFileCount());
        }
    }

    public record ProjectFileResponse(UUID id, UUID projectId, UUID parentArchiveFileId,
                                      String normalizedRelativePath, String originalFilename,
                                      String mediaType, String category, String ingestionStatus,
                                      String parseStatus, long sizeBytes, String parsedText,
                                      String parseMethod, String parseEngine, BigDecimal parseConfidence,
                                      Integer pageCount, Long parseDurationMs,
                                      String safeReasonCode, java.time.Instant createdAt,
                                      java.time.Instant updatedAt,
                                      String fileSha256, String canonicalTextSha256,
                                      UUID parseResultId, Integer parseVersion, String parseOrigin,
                                      UUID roleAssessmentId, String suggestedRole, BigDecimal roleConfidence,
                                      String roleExecutionStatus, String roleAssessmentSource,
                                      String roleSafeReasonCode, String roleModelProvider,
                                      String roleModelName, String roleModelRevision,
                                      String rolePromptVersion, String roleSchemaVersion,
                                      UUID aiRoleAssessmentId, String aiRoleExecutionStatus,
                                      String aiRoleSafeReasonCode, String roleDecisionStatus,
                                      String effectiveRole, String effectiveRoleSource,
                                      UUID roleDecisionId, Long roleDecisionVersion,
                                      UUID identityAssessmentId, String identityAssessmentStatus,
                                      String identityDecisionStatus, String effectiveIdentityStatus,
                                      UUID identityDecisionId, Long identityDecisionVersion,
                                      boolean identityCurrent, boolean excludedFromProjectUse,
                                      ProjectFileProgressionProjection progression) {
        static ProjectFileResponse from(ProjectFile file, String verifiedParsedText,
                                        ProjectFileRoleProjection role, EffectiveProjectIdentity identity,
                                        ProjectFileProgressionProjection progression) {
            var provenance = file.parseProvenance();
            return new ProjectFileResponse(file.id(), file.projectId(), file.parentArchiveFileId(),
                    file.normalizedRelativePath(), file.originalFilename(), file.mediaType(),
                    file.category().name(), file.ingestionStatus().name(), file.parseStatus().name(),
                    file.sizeBytes(), verifiedParsedText,
                    provenance.method() == null ? null : provenance.method().name(), provenance.engine(),
                    provenance.confidence(), provenance.pageCount(), provenance.durationMs(),
                    file.safeReasonCode(), file.createdAt(), file.updatedAt(), file.sha256(),
                    verifiedParsedText == null ? null
                            : com.fuquan.audit.contract.application.reviewrun.ContractSnapshotCanonicalizer
                                    .sha256(verifiedParsedText),
                    role.parseResultId(), role.parseVersion(), role.parseOrigin(), role.roleAssessmentId(),
                    role.suggestedRole() == null ? null : role.suggestedRole().name(), role.roleConfidence(),
                    role.roleExecutionStatus(), role.roleAssessmentSource(), role.roleSafeReasonCode(),
                    role.roleModelProvider(), role.roleModelName(), role.roleModelRevision(),
                    role.rolePromptVersion(), role.roleSchemaVersion(), role.aiRoleAssessmentId(),
                    role.aiRoleExecutionStatus(), role.aiRoleSafeReasonCode(), role.roleDecisionStatus(),
                    role.effectiveRole().name(),
                    role.effectiveRoleSource().name(), role.roleDecisionId(), role.roleDecisionVersion(),
                    identity.assessmentId(), identity.assessmentStatus() == null ? null
                            : identity.assessmentStatus().name(),
                    identity.decisionStatus() == null ? null : identity.decisionStatus().name(),
                    identity.status().name(), identity.decisionId(), identity.decisionVersion(),
                    identity.identityCurrent(), identity.excludedFromProjectUse(), progression);
        }
    }

    @JsonIgnoreProperties(value = {"userId", "decidedBy", "projectName", "projectNo", "fileSha256",
            "parseResultId", "projectId", "fileId"}, ignoreUnknown = true)
    public record IdentityConfirmationRequest(@NotNull UUID assessmentId,
            @NotNull ProjectIdentityResolution resolution, @NotNull @Min(0) Long expectedVersion,
            String reason) { }

    public record IdentityAssessmentResponse(UUID assessmentId, UUID projectId, long projectVersionNo,
            UUID fileId, String fileSha256, UUID parseResultId, int parseVersion,
            String assessmentSource, String assessmentVersion, String identityPolicyVersion,
            String status, String safeReasonCode, List<IdentityEvidenceResponse> evidence,
            java.time.Instant createdAt, java.time.Instant completedAt, long versionNo, boolean replayed) {
        static IdentityAssessmentResponse from(IdentityAssessmentResult result) {
            var value = result.assessment();
            return new IdentityAssessmentResponse(value.assessmentId(), value.projectId(),
                    value.projectVersionNo(), value.fileId(), value.fileSha256(), value.parseResultId(),
                    value.parseVersion(), value.assessmentSource().name(), value.assessmentVersion(),
                    value.identityPolicyVersion(), value.status().name(), value.safeReasonCode(),
                    result.evidence().stream().map(IdentityEvidenceResponse::from).toList(),
                    value.createdAt(), value.completedAt(), value.versionNo(), result.replayed());
        }
    }

    public record IdentityEvidenceResponse(UUID evidenceId, String factType, UUID extractionResultId,
            String leftSource, String rightSource, String leftNormalizedHash, String rightNormalizedHash,
            String leftBoundedValue, String rightBoundedValue, String comparisonOutcome,
            String significanceCode, String sourceLocator) {
        static IdentityEvidenceResponse from(ProjectFileIdentityEvidence value) {
            return new IdentityEvidenceResponse(value.evidenceId(), value.factType(), value.extractionResultId(),
                    value.leftSource(), value.rightSource(), value.leftNormalizedHash(), value.rightNormalizedHash(),
                    value.leftBoundedValue(), value.rightBoundedValue(), value.comparisonOutcome().name(),
                    value.significanceCode(), value.sourceLocator());
        }
    }

    public record IdentityConfirmationResponse(UUID decisionId, UUID projectId, long projectVersionNo,
            UUID fileId, String fileSha256, UUID parseResultId, int parseVersion, UUID assessmentId,
            String resolution, String decidedBy, java.time.Instant decidedAt,
            UUID supersedesDecisionId, String reason, long versionNo, boolean replayed,
            String effectiveIdentityStatus, boolean identityCurrent, boolean excludedFromProjectUse,
            UUID candidateId, String candidateMaterializationStatus) {
        static IdentityConfirmationResponse from(IdentityConfirmationResult result,
                EffectiveProjectIdentity effective) {
            var value = result.decision();
            return new IdentityConfirmationResponse(value.decisionId(), value.projectId(),
                    value.projectVersionNo(), value.fileId(), value.fileSha256(), value.parseResultId(),
                    value.parseVersion(), value.assessmentId(), value.resolution().name(), value.decidedBy(),
                    value.decidedAt(), value.supersedesDecisionId(), value.reason(), value.versionNo(),
                    result.replayed(), effective.status().name(), effective.identityCurrent(),
                    effective.excludedFromProjectUse(), result.candidateId(),
                    result.candidateMaterializationStatus() == null ? null
                            : result.candidateMaterializationStatus().name());
        }
    }

    @JsonIgnoreProperties(value = {"confirmedBy", "decidedBy", "actor", "userId"}, ignoreUnknown = true)
    public record RoleConfirmationRequest(
            @NotNull UUID assessmentId,
            @NotNull DocumentRole selectedRole,
            @Min(0) long expectedVersion,
            String reason) {
    }

    public record RoleAssessmentResponse(
            UUID assessmentId, UUID projectId, UUID fileId, UUID parseResultId, int parseVersion,
            String executionStatus, String proposedRole, BigDecimal confidence, boolean conflict,
            List<ProjectFileRoleCandidateProjection> candidateRoles,
            List<RoleEvidenceResponse> evidence, String decisionStatus, boolean replayed) {
        static RoleAssessmentResponse from(RoleAssessmentResult result) {
            var assessment = result.assessment();
            return new RoleAssessmentResponse(assessment.assessmentId(), assessment.projectId(), assessment.fileId(),
                    assessment.parseResultId(), assessment.parseVersion(), assessment.executionStatus().name(),
                    assessment.proposedRole() == null ? null : assessment.proposedRole().name(),
                    assessment.confidence(), assessment.conflict(), assessment.candidateRoles(),
                    result.evidence().stream().map(RoleEvidenceResponse::from).toList(),
                    result.decisionStatus() == null ? null : result.decisionStatus().name(), result.replayed());
        }
    }

    public record RoleEvidenceResponse(
            UUID evidenceId, String role, String signalType, String ruleCode, BigDecimal weight,
            String sourceLocator, int charStart, int charEnd, String textHash,
            String boundedExcerpt, String extractorVersion) {
        static RoleEvidenceResponse from(ProjectFileRoleEvidence evidence) {
            return new RoleEvidenceResponse(evidence.evidenceId(), evidence.role().name(),
                    evidence.signalType().name(), evidence.ruleCode(), evidence.weight(), evidence.sourceLocator(),
                    evidence.charStart(), evidence.charEnd(), evidence.textHash(), evidence.boundedExcerpt(),
                    evidence.extractorVersion());
        }
    }

    public record RoleConfirmationResponse(
            UUID decisionId, UUID projectId, UUID fileId, UUID assessmentId,
            String selectedRole, String decidedBy, java.time.Instant decidedAt,
            UUID supersedesDecisionId, String reason, long versionNo, boolean replayed,
            String effectiveRole, String effectiveRoleSource,
            UUID candidateId, String candidateMaterializationStatus) {
        static RoleConfirmationResponse from(RoleConfirmationResult result, ProjectFileRoleProjection role) {
            var decision = result.decision();
            return new RoleConfirmationResponse(decision.decisionId(), decision.projectId(), decision.fileId(),
                    decision.assessmentId(), decision.selectedRole().name(), decision.decidedBy(),
                    decision.decidedAt(), decision.supersedesDecisionId(), decision.reason(), decision.versionNo(),
                    result.replayed(), role.effectiveRole().name(), role.effectiveRoleSource().name(),
                    result.candidateId(), result.candidateMaterializationStatus() == null ? null
                            : result.candidateMaterializationStatus().name());
        }
    }

    public record CandidateDetailResponse(ContractCandidate candidate,
                                          ProjectFileResponse sourceFile,
                                          List<ExtractionResult> evidence,
                                          java.util.Map<String, String> fieldStates,
                                          java.util.Map<String, java.util.Map<String, String>> lineItemFieldStates,
                                          UUID sourceParseResultId,
                                          UUID sourceRoleDecisionId,
                                          UUID sourceIdentityDecisionId,
                                          String candidateAuthoritySource,
                                          String sourceFactFingerprint,
                                          String promotionProvenanceSource,
                                          String authorityMode, Long supportAuthorityVersionNo,
                                          String supportAuthorityFingerprint,
                                          List<SupportAuthorityProjection> supportAuthorities) { }

    public record SupportAuthorityReplacementRequest(long expectedCandidateVersion, List<UUID> supportFileIds) {
        static SupportAuthorityReplacementRequest from(com.fasterxml.jackson.databind.JsonNode node) {
            if (node == null || !node.isObject() || node.size() != 2
                    || !node.has("expectedCandidateVersion") || !node.has("supportFileIds")
                    || !node.get("expectedCandidateVersion").isIntegralNumber()
                    || !node.get("expectedCandidateVersion").canConvertToLong()
                    || node.get("expectedCandidateVersion").longValue() < 0
                    || !node.get("supportFileIds").isArray()) {
                throw new IllegalArgumentException("support replacement requires only version and supportFileIds");
            }
            List<UUID> ids = new java.util.ArrayList<>();
            for (var value : node.get("supportFileIds")) {
                if (!value.isTextual()) throw new IllegalArgumentException("support file ID must be a UUID string");
                UUID id = UUID.fromString(value.textValue());
                if (!id.toString().equalsIgnoreCase(value.textValue())) {
                    throw new IllegalArgumentException("support file ID must be a full UUID");
                }
                ids.add(id);
            }
            return new SupportAuthorityReplacementRequest(node.get("expectedCandidateVersion").longValue(), List.copyOf(ids));
        }
    }

    public record SupportAuthorityReplacementResponse(UUID candidateId, long candidateVersion,
            long supportAuthorityVersion, String authorityMode,
            List<ContractCandidateSupportAuthority> supportAssociations, boolean replayed) {
        static SupportAuthorityReplacementResponse from(ContractCandidateSupportReplacementResult result) {
            return new SupportAuthorityReplacementResponse(result.candidateId(), result.candidateVersion(),
                    result.supportAuthorityVersion(), result.authorityMode().name(), result.supportAssociations(), result.replayed());
        }
    }

    public record SupportAuthorityReplacementErrorData(
            ContractCandidateSupportReplacementException.Classification classification) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConfirmationRequest(
            @NotNull @Min(0) Long expectedVersion,
            String contractNo, String contractName, String contractType,
            String partyA, String partyB, String partyBCreditCode,
            BigDecimal contractAmount, String amountInWords,
            List<PromotionLineItemRequest> lineItems, PromotionAwardRequest awardNotice) {
        ConfirmedContractPayload payload() {
            List<ConfirmedContractPayload.ConfirmedLineItem> lines = lineItems == null ? List.of()
                    : lineItems.stream().map(PromotionLineItemRequest::payload).toList();
            return new ConfirmedContractPayload(contractNo, contractName, contractType, partyA, partyB,
                    partyBCreditCode, contractAmount, amountInWords, lines,
                    awardNotice == null ? null : awardNotice.payload());
        }
    }

    public record ConfirmationResponse(UUID confirmationId, UUID projectId, UUID candidateId,
            long baseCandidateVersion, long resultCandidateVersion,
            UUID sourceFileId, UUID sourceParseResultId, UUID sourceRoleDecisionId,
            UUID sourceIdentityDecisionId, String payloadFingerprint, int payloadSchemaVersion,
            ConfirmedContractPayload payload, String confirmedBy, java.time.Instant confirmedAt,
            UUID supersedesConfirmationId, boolean replayed) {
        static ConfirmationResponse from(ContractCandidateConfirmationResult result) {
            var value = result.confirmation();
            return new ConfirmationResponse(value.confirmationId(), value.projectId(), value.candidateId(),
                    value.baseCandidateVersionNo(), value.resultCandidateVersionNo(), value.sourceFileId(),
                    value.sourceParseResultId(), value.sourceRoleDecisionId(), value.sourceIdentityDecisionId(),
                    value.payloadFingerprint(), value.payloadSchemaVersion(), result.payload(),
                    value.confirmedBy(), value.confirmedAt(), value.supersedesConfirmationId(), result.replayed());
        }
    }

    public record PromotionRequest(long expectedVersion, UUID confirmationId) {
        static PromotionRequest from(com.fasterxml.jackson.databind.JsonNode node) {
            if (node == null || !node.isObject() || node.size() != 2
                    || !node.has("expectedVersion") || !node.has("confirmationId")
                    || !node.get("expectedVersion").isIntegralNumber()
                    || !node.get("expectedVersion").canConvertToLong()
                    || node.get("expectedVersion").longValue() < 0
                    || !node.get("confirmationId").isTextual()) {
                throw new IllegalArgumentException("promotion request must contain only version and confirmationId");
            }
            return new PromotionRequest(node.get("expectedVersion").longValue(),
                    UUID.fromString(node.get("confirmationId").textValue()));
        }
    }

    @JsonIgnoreProperties(value = {"id", "contractId"}, ignoreUnknown = true)
    public record PromotionLineItemRequest(String itemKey, String itemName,
                                           com.fuquan.audit.contract.domain.ContractLineItemBasis basis,
                                           BigDecimal quantity,
                                           BigDecimal unitPrice, BigDecimal declaredAmount, int sortNo) {
        ConfirmedContractPayload.ConfirmedLineItem payload() {
            return new ConfirmedContractPayload.ConfirmedLineItem(itemKey, itemName,
                    basis == null ? com.fuquan.audit.contract.domain.ContractLineItemBasis.QUANTITY_PRICE : basis,
                    quantity,
                    unitPrice, declaredAmount, sortNo);
        }
    }

    @JsonIgnoreProperties(value = {"id", "contractId", "createdAt"}, ignoreUnknown = true)
    public record PromotionAwardRequest(String noticeNo, String projectName, String winningBidder,
                                        String winningBidderCreditCode, BigDecimal winningAmount,
                                        LocalDate winningDate, String sourceRef) {
        ConfirmedContractPayload.ConfirmedAwardNotice payload() {
            return new ConfirmedContractPayload.ConfirmedAwardNotice(noticeNo, projectName, winningBidder,
                    winningBidderCreditCode, winningAmount, winningDate, sourceRef);
        }
    }

    public record PromotionResponse(UUID contractId, String contractNo, String contractName,
                                    String status, long versionNo, UUID projectId, UUID candidateId) {
        static PromotionResponse from(ContractCandidatePromotionResult result) {
            return new PromotionResponse(result.contract().id(), result.contract().contractNo(),
                    result.contract().contractName(), result.contract().status(), result.contract().versionNo(),
                    result.association().projectId(), result.association().candidateId());
        }
    }
}
