package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.contract.application.project.identity.EffectiveProjectIdentity;
import com.fuquan.audit.contract.application.project.identity.ProjectFileIdentityProjectionService;
import com.fuquan.audit.contract.application.project.identity.IdentityAssessmentResult;
import com.fuquan.audit.contract.application.project.identity.IdentityConfirmationCommand;
import com.fuquan.audit.contract.application.project.identity.IdentityConfirmationService;
import com.fuquan.audit.contract.application.project.port.ContractCandidateRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRoleDecisionRepository;
import com.fuquan.audit.contract.application.project.port.ContractCandidateConfirmationRepository;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort;
import com.fuquan.audit.contract.application.project.factai.ContractFactExtractionRepository;
import com.fuquan.audit.contract.application.project.factai.ContractFactExtractionService;
import com.fuquan.audit.contract.application.project.factai.ContractFactExtractionAttempt;
import com.fuquan.audit.contract.application.project.role.ProjectFileRoleProjection;
import com.fuquan.audit.contract.application.project.role.ProjectFileRoleProjectionService;
import com.fuquan.audit.contract.application.project.role.RoleConfirmationCommand;
import com.fuquan.audit.contract.application.project.role.RoleConfirmationService;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectIdentityResolution;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

/**
 * Completes the upload-time classification without exposing an approval step to end users.
 * Decisions still use the existing append-only decision tables so old deployments and
 * downstream authority checks remain compatible. A bounded reason marks the decision as
 * system initiated; no document text or model output is logged here.
 */
@Service
public class AutomaticProjectFileClassificationService {
    private static final Logger LOG = LoggerFactory.getLogger(AutomaticProjectFileClassificationService.class);
    private static final BigDecimal MIN_CONFIDENCE = new BigDecimal("0.70");
    private static final String SYSTEM_REASON = "SYSTEM_AUTO_CLASSIFICATION:v1:";

    private final ProjectFileRepository files;
    private final ProjectFileRoleProjectionService roles;
    private final ProjectFileIdentityProjectionService identities;
    private final RoleConfirmationService roleConfirmations;
    private final IdentityConfirmationService identityConfirmations;
    private final ContractCandidateRepository candidates;
    private final ContractCandidateConfirmationPayloadCanonicalizer canonicalizer;
    private final ContractCandidateConfirmationService candidateConfirmations;
    private final ContractCandidatePromotionService promotions;
    private final AutomaticProjectMetadataService projectMetadata;
    private final ProjectIntakeProgressionService intake;
    private final AuthContext auth;
    private final boolean enabled;
    private final boolean factExtractionEnabled;
    private final ContractFactExtractionService factExtraction;
    private final ContractFactExtractionRepository factAttempts;
    private final ContractFactCandidatePort factPort;
    private final ContractCandidateMaterializationService materialization;
    private final ProjectFileRoleDecisionRepository roleDecisions;
    private final ContractCandidateConfirmationRepository confirmations;

    public AutomaticProjectFileClassificationService(ProjectFileRepository files,
            ProjectFileRoleProjectionService roles, ProjectFileIdentityProjectionService identities,
            RoleConfirmationService roleConfirmations, IdentityConfirmationService identityConfirmations,
            ContractCandidateRepository candidates,
            ContractCandidateConfirmationPayloadCanonicalizer canonicalizer,
            ContractCandidateConfirmationService candidateConfirmations,
            ContractCandidatePromotionService promotions, AutomaticProjectMetadataService projectMetadata,
            ProjectIntakeProgressionService intake, AuthContext auth,
            @Value("${audit.contract.auto-intake.enabled}") boolean enabled,
            @Value("${AUDIT_AI_CONTRACT_FACT_EXTRACTION_ENABLED:false}") boolean factExtractionEnabled,
            ContractFactExtractionService factExtraction, ContractFactExtractionRepository factAttempts,
            ContractFactCandidatePort factPort, ContractCandidateMaterializationService materialization,
            ProjectFileRoleDecisionRepository roleDecisions,
            ContractCandidateConfirmationRepository confirmations) {
        this.files = files;
        this.roles = roles;
        this.identities = identities;
        this.roleConfirmations = roleConfirmations;
        this.identityConfirmations = identityConfirmations;
        this.candidates = candidates;
        this.canonicalizer = canonicalizer;
        this.candidateConfirmations = candidateConfirmations;
        this.promotions = promotions;
        this.projectMetadata = projectMetadata;
        this.intake = intake;
        this.auth = auth;
        this.enabled = enabled;
        this.factExtractionEnabled = factExtractionEnabled;
        this.factExtraction = factExtraction;
        this.factAttempts = factAttempts;
        this.factPort = factPort;
        this.materialization = materialization;
        this.roleDecisions = roleDecisions;
        this.confirmations = confirmations;
    }

    /** Idempotently advances all parsed files. A failure is isolated to that file. */
    public boolean enabled() { return enabled; }

    public void advance(UUID projectId, ProjectRequestMetadata metadata) {
        auth.currentUser().ifPresent(actor -> advance(projectId, metadata, actor));
    }

    public void advance(UUID projectId, ProjectRequestMetadata metadata, CurrentUser actor) {
        if (!enabled) return;
        try {
            projectMetadata.ensure(projectId, actor, metadata);
        } catch (RuntimeException failure) {
            LOG.warn("event=PROJECT_METADATA_AUTO_ESTABLISHMENT outcome=DEFERRED reasonCode={} projectId={}",
                    safeReason(failure), safeId(projectId));
            return;
        }
        for (ProjectFile file : files.findByProjectId(projectId)) {
            try { advanceOne(file, metadata, actor); }
            catch (RuntimeException failure) {
                LOG.warn("event=PROJECT_FILE_AUTO_CLASSIFICATION outcome=DEFERRED reasonCode={} projectId={} fileId={}",
                        safeReason(failure), safeId(projectId), safeId(file.id()));
            }
        }
    }

    /** Reuses persisted, source-verified analysis facts; performs no model call. */
    public void advanceAnalyzedFile(UUID projectId, UUID fileId, ProjectRequestMetadata metadata, CurrentUser actor) {
        if(!enabled)return;
        projectMetadata.ensure(projectId,actor,metadata);
        var file=files.findByProjectIdAndId(projectId,fileId).orElseThrow();
        advanceOne(file,metadata,actor,false);
    }

    private void advanceOne(ProjectFile file, ProjectRequestMetadata metadata, CurrentUser actor) {
        advanceOne(file,metadata,actor,true);
    }
    private void advanceOne(ProjectFile file, ProjectRequestMetadata metadata, CurrentUser actor, boolean allowModelExtraction) {
        if (file.ingestionStatus() != com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus.AVAILABLE
                || file.parseStatus() != com.fuquan.audit.contract.domain.project.ProjectFileParseStatus.PARSED) return;
        ProjectFileRoleProjection projection = roles.project(file);
        if (projection.roleAssessmentId() == null || projection.roleExecutionStatus() == null
                || !"COMPLETED".equals(projection.roleExecutionStatus())) return;
        if (projection.roleDecisionId() == null) {
            DocumentRole selected = reliableRole(projection, new ProjectFileClassifier().classify(file.normalizedRelativePath()==null?file.originalFilename():file.normalizedRelativePath()));
            String reason = SYSTEM_REASON + (selected == DocumentRole.OTHER ? "LOW_CONFIDENCE_OR_CONFLICT" : "EVIDENCE_MATCH");
            long expectedVersion = projection.roleDecisionVersion() == null ? 0 : projection.roleDecisionVersion();
            String key = "project-intake-auto-role:" + projection.roleAssessmentId() + ":" + selected.name();
            roleConfirmations.confirm(new RoleConfirmationCommand(file.projectId(), file.id(),
                    projection.roleAssessmentId(), selected, expectedVersion, reason, key, metadata), actor);
            LOG.info("event=PROJECT_FILE_AUTO_ROLE_DECISION outcome=SUCCESS projectId={} fileId={} role={} confidenceBucket={}",
                    safeId(file.projectId()), safeId(file.id()), selected, confidenceBucket(projection.roleConfidence()));
        }

        ProjectFile refreshed = files.findByProjectIdAndId(file.projectId(), file.id()).orElse(file);
        ProjectFileRoleProjection currentRole = roles.project(refreshed);
        // Resume machine decisions only; never turn historic human decisions into automatic actions.
        if (currentRole.effectiveRoleSource()
                != com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleSource.SYSTEM_AUTO_DECISION) return;
        DocumentRole role = currentRole.effectiveRole();
        if (role == null || role == DocumentRole.OTHER || role == DocumentRole.UNKNOWN) return;
        EffectiveProjectIdentity identity = identities.project(refreshed);
        if (identity.assessmentId() == null && currentRole.roleDecisionId() != null) {
            intake.ensureIdentityAssessment(refreshed.projectId(), refreshed.id(),
                    currentRole.roleDecisionId(), metadata, actor);
            refreshed = files.findByProjectIdAndId(refreshed.projectId(), refreshed.id()).orElse(refreshed);
            identity = identities.project(refreshed);
        }
        if (identity.assessmentId() == null) return;
        if (identity.decisionId() == null && identity.status() == com.fuquan.audit.contract.domain.project.EffectiveProjectIdentityStatus.CONFLICT) {
            decideOther(refreshed, role, identity, metadata, actor);
            return;
        }
        if (identity.decisionId() == null) {
        ProjectIdentityResolution resolution = ProjectIdentityResolution.CONFIRM_SAME_PROJECT;
        String identityReason = identity.status()
                == com.fuquan.audit.contract.domain.project.EffectiveProjectIdentityStatus.INSUFFICIENT
                ? SYSTEM_REASON + "INTAKE_SCOPE_NO_CONFLICT"
                : SYSTEM_REASON + resolution.name();
        IdentityConfirmationCommand identityCommand = new IdentityConfirmationCommand(
                refreshed.projectId(), refreshed.id(), identity.assessmentId(), resolution,
                identity.decisionVersion() == null ? 0 : identity.decisionVersion(),
                identityReason,
                "project-intake-auto-identity:" + identity.assessmentId() + ":" + resolution.name(), metadata);
        identityConfirmations.confirm(identityCommand, actor);
        LOG.info("event=PROJECT_FILE_AUTO_IDENTITY_DECISION outcome=SUCCESS projectId={} fileId={} resolution={}",
                safeId(refreshed.projectId()), safeId(refreshed.id()), resolution);
        }
        refreshed = files.findByProjectIdAndId(file.projectId(), file.id()).orElse(refreshed);
        identity = identities.project(refreshed);
        if (!identity.eligibleForProjectUse() || !identity.systemAutoDecision()) return;
        if (allowModelExtraction && factExtractionEnabled && (role == DocumentRole.CONTRACT_BODY || role == DocumentRole.AWARD_NOTICE)) {
            var attempt = factAttempts.findLatestForAuthority(refreshed.projectId(), refreshed.id(),
                    currentRole.parseResultId(), currentRole.roleDecisionId(), identity.decisionId(),
                    role, factPort.configuredProvenance());
            if (attempt.isEmpty()) {
                LOG.info("event=PROJECT_FILE_AUTO_FACT_GATE outcome=START projectId={} fileId={} role={} enabled={} priorAttempt=NONE",
                        safeId(refreshed.projectId()), safeId(refreshed.id()), role, factExtractionEnabled);
                // The authority tuple forms an immutable operation identity. Unknown/failed attempts
                // are deliberately not replaced with a new key or resent on the next upload.
                String operation = "auto-facts:" + currentRole.parseResultId() + ":r:"
                        + currentRole.roleDecisionId() + ":i:" + identity.decisionId();
                try {
                    var result = factExtraction.extract(refreshed.projectId(), refreshed.id(), operation, actor, metadata);
                    LOG.info("event=PROJECT_FILE_AUTO_FACT_GATE outcome={} projectId={} fileId={} role={} enabled={} priorAttempt=NONE reasonCode={}",
                            "SUCCEEDED".equals(result.status()) ? "SUCCESS" : "PARTIAL_RESULT",
                            safeId(refreshed.projectId()), safeId(refreshed.id()), role, factExtractionEnabled,
                            "SUCCEEDED".equals(result.status()) ? "NONE" : "FACT_EXTRACTION_TERMINAL_WITHOUT_COMPLETE_FACTS");
                } catch (RuntimeException failure) {
                    // Extraction errors may still have a durable FAILED/UNKNOWN attempt.
                    // Continue with already-published source facts only when that terminal
                    // state is proven; never resend an uncertain model operation.
                    var terminal = factAttempts.findLatestForAuthority(refreshed.projectId(), refreshed.id(),
                            currentRole.parseResultId(), currentRole.roleDecisionId(), identity.decisionId(),
                            role, factPort.configuredProvenance());
                    if (terminal.isEmpty() || terminal.get().status() == ContractFactExtractionAttempt.Status.IN_PROGRESS) {
                        throw failure;
                    }
                    LOG.info("event=PROJECT_FILE_AUTO_FACT_GATE outcome=PARTIAL_RESULT projectId={} fileId={} role={} enabled={} priorAttempt=NONE reasonCode=FACT_EXTRACTION_TERMINAL_WITHOUT_COMPLETE_FACTS",
                            safeId(refreshed.projectId()), safeId(refreshed.id()), role, factExtractionEnabled);
                }
            } else if (attempt.get().status() == ContractFactExtractionAttempt.Status.IN_PROGRESS) {
                LOG.info("event=PROJECT_FILE_AUTO_FACT_GATE outcome=DEFERRED projectId={} fileId={} role={} enabled={} priorAttempt=IN_PROGRESS reasonCode=FACT_EXTRACTION_STILL_RUNNING",
                        safeId(refreshed.projectId()), safeId(refreshed.id()), role, factExtractionEnabled);
                return;
            } else if (attempt.get().status() != ContractFactExtractionAttempt.Status.SUCCEEDED) {
                // A terminal failure or unknown outcome is not a reason to keep the
                // material in an endless pending state. Materialization can still
                // publish any independently grounded facts already available.
                LOG.info("event=PROJECT_FILE_AUTO_FACT_GATE outcome=PARTIAL_RESULT projectId={} fileId={} role={} enabled={} priorAttempt={} reasonCode=FACT_EXTRACTION_TERMINAL_WITHOUT_COMPLETE_FACTS",
                        safeId(refreshed.projectId()), safeId(refreshed.id()), role, factExtractionEnabled,
                        attempt.get().status());
            }
        }
        if (role == DocumentRole.CONTRACT_BODY) {
            var decision = roleDecisions.findById(currentRole.roleDecisionId()).orElseThrow();
            materialization.materializeFromDecision(decision, identity.currentAuthorizedSameDecision(), actor, metadata);
            createContractWhenEvidenceIsComplete(refreshed, metadata, actor);
        }
    }

    private void decideOther(ProjectFile file, DocumentRole selected, EffectiveProjectIdentity identity,
            ProjectRequestMetadata metadata, CurrentUser actor) {
        if (identity.assessmentId() == null) return;
        identityConfirmations.confirm(new IdentityConfirmationCommand(file.projectId(), file.id(),
                identity.assessmentId(), ProjectIdentityResolution.CONFIRM_NOT_SAME_PROJECT,
                identity.decisionVersion() == null ? 0 : identity.decisionVersion(),
                SYSTEM_REASON + (identity.status() == com.fuquan.audit.contract.domain.project.EffectiveProjectIdentityStatus.CONFLICT
                        ? "PROJECT_IDENTITY_CONFLICT" : "PROJECT_IDENTITY_INSUFFICIENT"),
                "project-intake-auto-identity:" + identity.assessmentId() + ":NOT_SAME", metadata), actor);
        LOG.info("event=PROJECT_FILE_AUTO_IDENTITY_DECISION outcome=EXCLUDED projectId={} fileId={} role={} reasonCode={}",
                safeId(file.projectId()), safeId(file.id()), selected,
                identity.status() == com.fuquan.audit.contract.domain.project.EffectiveProjectIdentityStatus.CONFLICT
                        ? "PROJECT_IDENTITY_CONFLICT" : "PROJECT_IDENTITY_INSUFFICIENT");
    }

    private void createContractWhenEvidenceIsComplete(ProjectFile file, ProjectRequestMetadata metadata,
            CurrentUser actor) {
        var candidate = candidates.findBySourceContractFileId(file.projectId(), file.id()).orElse(null);
        if (candidate == null) {
            LOG.info("event=PROJECT_FILE_AUTO_CONTRACT outcome=NOT_CREATED reasonCode=CANDIDATE_NOT_FOUND projectId={} fileId={}",
                    safeId(file.projectId()), safeId(file.id()));
            return;
        }
        if (candidate.promotionState() != com.fuquan.audit.contract.domain.project.CandidatePromotionState.DRAFT) return;
        var payload = autoPayload(candidate);
        if (payload == null) {
            LOG.info("event=PROJECT_FILE_AUTO_CONTRACT outcome=NOT_CREATED reasonCode=REQUIRED_EVIDENCE_INCOMPLETE projectId={} fileId={} candidateId={}",
                    safeId(file.projectId()), safeId(file.id()), safeId(candidate.id()));
            return;
        }
        var existingConfirmation = confirmations.findLatestByProjectAndCandidate(file.projectId(), candidate.id())
                .filter(value -> value.resultCandidateVersionNo() == candidate.versionNo()
                        && java.util.Objects.equals(value.sourceParseResultId(), candidate.sourceParseResultId())
                        && java.util.Objects.equals(value.sourceRoleDecisionId(), candidate.sourceRoleDecisionId())
                        && java.util.Objects.equals(value.sourceIdentityDecisionId(), candidate.sourceIdentityDecisionId()));
        var confirmationId = existingConfirmation.map(value -> value.confirmationId()).orElseGet(() ->
                candidateConfirmations.confirmAutomatically(new ConfirmContractCandidateCommand(
                file.projectId(), candidate.id(), candidate.versionNo(), payload,
                "project-intake-auto-candidate:" + candidate.id() + ":v" + candidate.versionNo(), metadata), actor)
                        .confirmation().confirmationId());
        var updated = candidates.findByProjectIdAndId(file.projectId(), candidate.id()).orElseThrow();
        promotions.promoteAutomatically(new PromoteContractCandidateCommand(file.projectId(), candidate.id(),
                updated.versionNo(), confirmationId,
                "project-intake-auto-promote:" + candidate.id() + ":v" + updated.versionNo(), metadata), actor);
        LOG.info("event=PROJECT_FILE_AUTO_CONTRACT outcome=CREATED projectId={} fileId={} candidateId={}",
                safeId(file.projectId()), safeId(file.id()), safeId(candidate.id()));
    }

    static ConfirmedContractPayload autoPayload(
            com.fuquan.audit.contract.domain.project.ContractCandidate candidate) {
        if (blank(candidate.contractNo()) || blank(candidate.contractName()) || blank(candidate.contractType())
                || blank(candidate.partyA()) || blank(candidate.partyB()) || candidate.contractAmount() == null) return null;
        var lines = new java.util.ArrayList<ConfirmedContractPayload.ConfirmedLineItem>();
        for (var line : candidate.lineItems()) {
            if (blank(line.itemKey()) || blank(line.itemName()) || line.declaredAmount() == null) return null;
            boolean hasQuantity = line.quantity() != null;
            boolean hasUnitPrice = line.unitPrice() != null;
            if (hasQuantity != hasUnitPrice) return null;
            var basis = hasQuantity
                    ? com.fuquan.audit.contract.domain.ContractLineItemBasis.QUANTITY_PRICE
                    : com.fuquan.audit.contract.domain.ContractLineItemBasis.AMOUNT_ONLY;
            lines.add(new ConfirmedContractPayload.ConfirmedLineItem(line.itemKey(), line.itemName(), basis,
                    line.quantity(), line.unitPrice(), line.declaredAmount(), line.sortNo()));
        }
        try {
            return new ConfirmedContractPayload(candidate.contractNo(), candidate.contractName(),
                    candidate.contractType(), candidate.partyA(), candidate.partyB(),
                    candidate.partyBCreditCode(), candidate.contractAmount(), candidate.amountInWords(),
                    lines, null);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    static DocumentRole reliableRole(ProjectFileRoleProjection projection) {
        return reliableRole(projection, com.fuquan.audit.contract.domain.project.ProjectFileCategory.UNKNOWN);
    }

    /**
     * Uses the upload classifier's filename/path category as a bounded fallback.
     * A strong, non-conflicting body assessment still wins; a weak/unknown body
     * assessment may use the filename category so files such as "合同书.pdf"
     * do not get stranded as OTHER merely because OCR returned little text.
     */
    static DocumentRole reliableRole(ProjectFileRoleProjection projection,
            com.fuquan.audit.contract.domain.project.ProjectFileCategory filenameCategory) {
        DocumentRole filenameRole = roleForCategory(filenameCategory);
        if (projection.roleConflict() || projection.suggestedRole() == null
                || projection.suggestedRole() == DocumentRole.UNKNOWN
                || projection.roleConfidence() == null
                || projection.roleConfidence().compareTo(MIN_CONFIDENCE) < 0) {
            return filenameRole == null ? DocumentRole.OTHER : filenameRole;
        }
        return projection.suggestedRole();
    }

    private static DocumentRole roleForCategory(
            com.fuquan.audit.contract.domain.project.ProjectFileCategory category) {
        if (category == null) return null;
        return switch (category) {
            case CONTRACT_BODY -> DocumentRole.CONTRACT_BODY;
            case AWARD_NOTICE -> DocumentRole.AWARD_NOTICE;
            case BID_RESPONSE -> DocumentRole.BID_RESPONSE;
            case TENDER_DOCUMENT -> DocumentRole.TENDER_DOCUMENT;
            case SUPPLEMENT -> DocumentRole.SUPPLEMENT;
            case OTHER -> DocumentRole.OTHER;
            case PACKAGE, UNKNOWN -> null;
        };
    }

    private static String confidenceBucket(BigDecimal confidence) {
        if (confidence == null) return "NONE";
        return confidence.compareTo(MIN_CONFIDENCE) >= 0 ? "HIGH" : "LOW";
    }

    private static String safeReason(RuntimeException failure) {
        String name = failure.getClass().getSimpleName();
        return name == null || name.isBlank() ? "AUTO_CLASSIFICATION_DEFERRED" : name;
    }

    private static String safeId(UUID value) {
        if (value == null) return "null";
        String raw = value.toString();
        return raw.substring(0, 8);
    }
}
