package com.fuquan.audit.contract.application.project.identity;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRoleDecisionRepository;
import com.fuquan.audit.contract.application.project.port.ContractCandidateRepository;
import com.fuquan.audit.contract.application.project.ContractCandidateMaterializationService;
import com.fuquan.audit.contract.application.project.ContractCandidateMaterializationStatus;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleResolver;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleSource;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.CandidatePromotionState;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.ProjectIdentityResolution;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import java.text.Normalizer;
import java.time.Clock;
import java.util.UUID;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

@Service
public class IdentityConfirmationService {
    private static final Pattern SAFE_KEY = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private static final Pattern WHITESPACE = Pattern.compile("[\\p{Z}\\s]+");
    private final ProjectFileRepository files;
    private final IdentityConfirmationTransactionCoordinator transactions;
    private final AuthContext authContext;
    private final Clock clock;
    private final ProjectFileRoleDecisionRepository roleDecisions;
    private final EffectiveDocumentRoleResolver effectiveRoles;
    private final ContractCandidateMaterializationService candidateMaterializer;
    private final ContractCandidateRepository candidates;
    private final ProjectFileIdentityProjectionService identityProjections;
    private final boolean factExtractionEnabled;

    @Autowired
    public IdentityConfirmationService(ProjectFileRepository files,
            IdentityConfirmationTransactionCoordinator transactions,
            ProjectFileRoleDecisionRepository roleDecisions, EffectiveDocumentRoleResolver effectiveRoles,
            ContractCandidateMaterializationService candidateMaterializer,
            ContractCandidateRepository candidates, ProjectFileIdentityProjectionService identityProjections,
            AuthContext authContext, Clock clock,
            @Value("${AUDIT_AI_CONTRACT_FACT_EXTRACTION_ENABLED:false}") boolean factExtractionEnabled) {
        this.files = files; this.transactions = transactions; this.roleDecisions = roleDecisions;
        this.effectiveRoles = effectiveRoles; this.candidateMaterializer = candidateMaterializer;
        this.candidates = candidates; this.identityProjections = identityProjections;
        this.authContext = authContext; this.clock = clock;
        this.factExtractionEnabled = factExtractionEnabled;
    }

    /** Focused decision tests do not exercise downstream candidate readiness. */
    public IdentityConfirmationService(ProjectFileRepository files,
            IdentityConfirmationTransactionCoordinator transactions, AuthContext authContext, Clock clock) {
        this(files, transactions, null, null, null, null, null, authContext, clock, false);
    }
    public IdentityConfirmationResult confirm(IdentityConfirmationCommand command) {
        return confirm(command, actor());
    }

    public IdentityConfirmationResult confirm(IdentityConfirmationCommand command, CurrentUser actor) {
        if (command.idempotencyKey() == null || !SAFE_KEY.matcher(command.idempotencyKey()).matches())
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "invalid Idempotency-Key");
        String reason = normalizeReason(command.reason());
        if (reason != null && reason.length() > 500)
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "identity confirmation reason too long");
        var normalized = new IdentityConfirmationCommand(command.projectId(), command.fileId(),
                command.assessmentId(), command.resolution(), command.expectedVersion(), reason,
                command.idempotencyKey(), command.metadata());
        return files.withFileAuthorityFence(command.projectId(), command.fileId(), () -> {
            IdentityConfirmationResult confirmed;
            try { confirmed = transactions.confirm(normalized, actor, UUID.randomUUID(), clock.instant()); }
            catch (BusinessException known) { throw known; }
            catch (RuntimeException uncertain) {
                try { confirmed = transactions.reconcile(normalized, actor, uncertain, clock.instant()); }
                catch (RuntimeException failure) { uncertain.addSuppressed(failure); throw uncertain; }
            }
            return materialize(confirmed, actor, normalized);
        });
    }
    public IdentityConfirmationResult get(UUID projectId, UUID fileId, UUID decisionId) {
        actor();
        var file = files.findByProjectIdAndId(projectId, fileId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "project file not found"));
        var reopened = transactions.reopen(projectId, fileId, decisionId, false);
        if (candidates == null) return reopened;
        if (reopened.decision().resolution() != ProjectIdentityResolution.CONFIRM_SAME_PROJECT)
            return waitingForIdentity(reopened, file);
        if (identityProjections != null) {
            var current = identityProjections.project(file).currentHumanSameDecision();
            if (current == null || !decisionId.equals(current.decisionId()))
                return waitingForIdentity(reopened, file);
        }
        var role = effectiveRoles == null ? null : effectiveRoles.resolve(file);
        if (role != null && (role.effectiveRole() != DocumentRole.CONTRACT_BODY
                || !role.source().isAuthoritativeDecision()
                || role.roleDecisionId() == null))
            return new IdentityConfirmationResult(reopened.decision(), false, null,
                    ContractCandidateMaterializationStatus.NOT_APPLICABLE);
        var candidate = candidates.findBySourceContractFileId(projectId, fileId)
                .filter(value -> decisionId.equals(value.sourceIdentityDecisionId())
                        && (role == null || role.roleDecisionId().equals(value.sourceRoleDecisionId())));
        return candidate.map(value -> new IdentityConfirmationResult(reopened.decision(), false,
                value.id(), value.promotionState() == CandidatePromotionState.PROMOTED
                        ? ContractCandidateMaterializationStatus.ALREADY_PROMOTED
                        : ContractCandidateMaterializationStatus.REPLAYED))
                .orElseGet(() -> factExtractionEnabled
                        ? waitingForFactExtraction(reopened)
                        : reopened);
    }

    private IdentityConfirmationResult materialize(IdentityConfirmationResult confirmed, CurrentUser actor,
            IdentityConfirmationCommand command) {
        if (confirmed.decision().resolution() != ProjectIdentityResolution.CONFIRM_SAME_PROJECT) {
            var file = files.findByProjectIdAndId(command.projectId(), command.fileId())
                    .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND,
                            "project file not found"));
            return waitingForIdentity(confirmed, file);
        }
        if (candidateMaterializer == null) return confirmed;
        var file = files.findByProjectIdAndId(command.projectId(), command.fileId())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "project file not found"));
        var identity = identityProjections.project(file);
        if (!identity.eligibleForProjectUse()
                || !confirmed.decision().decisionId().equals(identity.currentHumanSameDecision().decisionId()))
            return waitingForIdentity(confirmed, file);
        var role = effectiveRoles.resolve(file);
        if (role.effectiveRole() != DocumentRole.CONTRACT_BODY
                || !role.source().isAuthoritativeDecision()
                || role.roleDecisionId() == null) return confirmed;
        Optional<ContractCandidate> existing = candidates.findBySourceContractFileId(file.projectId(), file.id());
        Optional<ContractCandidate> promoted = existing
                .filter(value -> value.promotionState() == CandidatePromotionState.PROMOTED);
        if (promoted.isPresent()) return new IdentityConfirmationResult(confirmed.decision(), confirmed.replayed(),
                promoted.orElseThrow().id(), ContractCandidateMaterializationStatus.ALREADY_PROMOTED);
        if (factExtractionEnabled && existing.isEmpty()) return waitingForFactExtraction(confirmed);
        var roleDecision = roleDecisions.findById(role.roleDecisionId())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.CONFLICT,
                        "current role decision is unproven"));
        var result = candidateMaterializer.materializeFromDecision(roleDecision, confirmed.decision(),
                actor, command.metadata());
        return new IdentityConfirmationResult(confirmed.decision(), confirmed.replayed(),
                result.candidate().id(), result.status());
    }
    private static IdentityConfirmationResult waitingForFactExtraction(IdentityConfirmationResult result) {
        return new IdentityConfirmationResult(result.decision(), result.replayed(), null,
                ContractCandidateMaterializationStatus.WAITING_FOR_FACT_EXTRACTION);
    }
    private IdentityConfirmationResult waitingForIdentity(IdentityConfirmationResult result,
            com.fuquan.audit.contract.domain.project.ProjectFile file) {
        if (effectiveRoles == null || candidates == null)
            return new IdentityConfirmationResult(result.decision(), result.replayed(), null,
                    ContractCandidateMaterializationStatus.NOT_APPLICABLE);
        var role = effectiveRoles.resolve(file);
        if (role.effectiveRole() != DocumentRole.CONTRACT_BODY
                || !role.source().isAuthoritativeDecision())
            return new IdentityConfirmationResult(result.decision(), result.replayed(), null,
                    ContractCandidateMaterializationStatus.NOT_APPLICABLE);
        UUID draftId = candidates.findBySourceContractFileId(file.projectId(), file.id())
                .filter(value -> value.promotionState() == CandidatePromotionState.DRAFT)
                .map(ContractCandidate::id).orElse(null);
        return new IdentityConfirmationResult(result.decision(), result.replayed(), draftId,
                ContractCandidateMaterializationStatus.WAITING_FOR_PROJECT_IDENTITY);
    }
    static String normalizeReason(String raw) {
        if (raw == null) return null;
        String value = WHITESPACE.matcher(Normalizer.normalize(raw, Normalizer.Form.NFKC).strip())
                .replaceAll(" ").strip();
        return value.isEmpty() ? null : value;
    }
    private CurrentUser actor() {
        return authContext.currentUser().orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED));
    }
}
