package com.fuquan.audit.contract.application.project.role;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.application.port.IdempotencyPort;
import com.fuquan.audit.common.application.port.IdempotencyStatus;
import com.fuquan.audit.contract.application.project.ContractCandidateMaterializationResult;
import com.fuquan.audit.contract.application.project.ContractCandidateMaterializationService;
import com.fuquan.audit.contract.application.project.ContractCandidateMaterializationStatus;
import com.fuquan.audit.contract.application.project.ContractCandidateMaterializationTransactionCoordinator;
import com.fuquan.audit.contract.application.project.ProjectIntakeProgressionService;
import com.fuquan.audit.contract.application.project.identity.ProjectFileIdentityProjectionService;
import com.fuquan.audit.contract.application.project.port.ContractCandidateRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.CandidatePromotionState;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import java.text.Normalizer;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RoleConfirmationService {
    private static final Pattern SAFE_IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private final RoleConfirmationTransactionCoordinator transactions;
    private final ContractCandidateMaterializationService candidateMaterializer;
    private final ContractCandidateRepository candidates;
    private final IdempotencyPort idempotency;
    private final ProjectFileRepository files;
    private final ProjectFileIdentityProjectionService identityProjections;
    private final EffectiveDocumentRoleResolver effectiveRoles;
    private final ProjectIntakeProgressionService progression;
    private final AuthContext authContext;
    private final Clock clock;

    @Autowired
    public RoleConfirmationService(RoleConfirmationTransactionCoordinator transactions,
                                   ContractCandidateMaterializationService candidateMaterializer,
                                   ContractCandidateRepository candidates,
                                   IdempotencyPort idempotency,
                                   ProjectFileRepository files,
                                   ProjectFileIdentityProjectionService identityProjections,
                                   EffectiveDocumentRoleResolver effectiveRoles,
                                   ProjectIntakeProgressionService progression,
                                   AuthContext authContext, Clock clock) {
        this.transactions = transactions;
        this.candidateMaterializer = candidateMaterializer;
        this.candidates = candidates;
        this.idempotency = idempotency;
        this.files = files;
        this.identityProjections = identityProjections;
        this.effectiveRoles = effectiveRoles;
        this.progression = progression;
        this.authContext = authContext;
        this.clock = clock;
    }

    /** Historical focused test constructor; production wiring always injects identity projection. */
    public RoleConfirmationService(RoleConfirmationTransactionCoordinator transactions,
                                   ContractCandidateMaterializationService candidateMaterializer,
                                   ContractCandidateRepository candidates, IdempotencyPort idempotency,
                                   ProjectFileRepository files,
                                   ProjectFileIdentityProjectionService identityProjections,
                                   AuthContext authContext, Clock clock) {
        this(transactions, candidateMaterializer, candidates, idempotency, files,
                identityProjections, null, null, authContext, clock);
    }

    public RoleConfirmationService(RoleConfirmationTransactionCoordinator transactions,
                                   ContractCandidateMaterializationService candidateMaterializer,
                                   ContractCandidateRepository candidates, IdempotencyPort idempotency,
                                   ProjectFileRepository files, AuthContext authContext, Clock clock) {
        this(transactions, candidateMaterializer, candidates, idempotency, files,
                null, null, null, authContext, clock);
    }

    /** Compatibility constructor for focused role-decision tests that do not exercise candidate materialization. */
    public RoleConfirmationService(RoleConfirmationTransactionCoordinator transactions,
                                   AuthContext authContext, Clock clock) {
        this(transactions, null, null, null, null, null, null, null, authContext, clock);
    }

    public RoleConfirmationResult confirm(RoleConfirmationCommand command) {
        return confirm(command, currentUser());
    }

    public RoleConfirmationResult confirm(RoleConfirmationCommand command, CurrentUser actor) {
        validateKey(command.idempotencyKey());
        String normalizedReason = normalizeReason(command.reason());
        if (normalizedReason != null && normalizedReason.length() > 500) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "confirmation reason is too long");
        }
        RoleConfirmationCommand normalized = new RoleConfirmationCommand(command.projectId(), command.fileId(),
                command.assessmentId(), command.selectedRole(), command.expectedVersion(), normalizedReason,
                command.idempotencyKey(), command.metadata());
        if (files == null) return confirmUnderFence(normalized, actor);
        return files.withFileAuthorityFence(normalized.projectId(), normalized.fileId(),
                () -> confirmUnderFence(normalized, actor));
    }

    private RoleConfirmationResult confirmUnderFence(RoleConfirmationCommand normalized, CurrentUser actor) {
        RoleConfirmationResult confirmed;
        try {
            confirmed = transactions.confirm(normalized, actor, UUID.randomUUID(), clock.instant());
        } catch (BusinessException proven) {
            throw proven;
        } catch (RuntimeException uncertain) {
            try {
                confirmed = transactions.reconcile(normalized, actor, uncertain, clock.instant());
            } catch (RuntimeException reconciliationFailure) {
                uncertain.addSuppressed(reconciliationFailure);
                throw uncertain;
            }
        }
        if (progression != null) {
            progression.ensureIdentityAssessment(confirmed.decision().projectId(),
                    confirmed.decision().fileId(), confirmed.decision().decisionId(), normalized.metadata(), actor);
        }
        return materialize(confirmed, actor, normalized);
    }

    public RoleConfirmationResult get(UUID projectId, UUID fileId, UUID decisionId) {
        currentUser();
        RoleConfirmationResult reopened = transactions.reopen(projectId, fileId, decisionId, false);
        if (reopened.decision().selectedRole() != DocumentRole.CONTRACT_BODY) {
            return withCandidate(reopened, null, ContractCandidateMaterializationStatus.NOT_APPLICABLE);
        }
        if (!isCurrentContractRole(reopened)) return withCandidate(reopened, null, null);
        Optional<ContractCandidate> promoted = alreadyPromoted(projectId, fileId);
        if (promoted.isPresent()) {
            return withCandidate(reopened, promoted.orElseThrow().id(),
                    ContractCandidateMaterializationStatus.ALREADY_PROMOTED);
        }
        if (candidates == null || idempotency == null) return reopened;
        var file = files.findByProjectIdAndId(projectId, fileId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "project file not found"));
        var same = identityProjections == null ? null
                : identityProjections.project(file).currentHumanSameDecision();
        if (identityProjections != null && same == null) {
            UUID draftId = candidates.findBySourceContractFileId(projectId, fileId)
                    .filter(value -> value.promotionState() == CandidatePromotionState.DRAFT)
                    .map(ContractCandidate::id).orElse(null);
            return withCandidate(reopened, draftId,
                    ContractCandidateMaterializationStatus.WAITING_FOR_PROJECT_IDENTITY);
        }
        var pairCandidate = candidates.findBySourceContractFileId(projectId, fileId)
                .filter(value -> decisionId.equals(value.sourceRoleDecisionId())
                        && same != null && same.decisionId().equals(value.sourceIdentityDecisionId()));
        if (pairCandidate.isPresent()) return withCandidate(reopened,
                pairCandidate.orElseThrow().id(), ContractCandidateMaterializationStatus.REPLAYED);
        String pairKey = null;
        if (same != null) pairKey = "role-decision:" + decisionId
                + ":identity-decision:" + same.decisionId();
        Optional<com.fuquan.audit.common.application.port.IdempotencyRecord> historicalPair = pairKey == null
                ? Optional.empty() : idempotency.findByScopeAndKey(
                        ContractCandidateMaterializationTransactionCoordinator.SCOPE, pairKey);
        return historicalPair.or(() -> idempotency.findByScopeAndKey(
                        ContractCandidateMaterializationTransactionCoordinator.SCOPE,
                        "role-decision:" + decisionId))
                .filter(record -> record.status() == IdempotencyStatus.SUCCEEDED)
                .map(record -> {
                    if (!decisionId.toString().equals(record.resourceId())
                            || !ContractCandidateMaterializationTransactionCoordinator.RESULT_RESOURCE
                                .equals(record.resultResourceType())) {
                        throw new BusinessException(BusinessErrorCode.CONFLICT,
                                "role decision candidate durable link is inconsistent");
                    }
                    UUID candidateId;
                    try {
                        candidateId = UUID.fromString(record.resultResourceId());
                    } catch (RuntimeException invalid) {
                        throw new BusinessException(BusinessErrorCode.CONFLICT,
                                "role decision candidate durable identity is invalid");
                    }
                    var candidate = candidates.findByProjectIdAndId(projectId, candidateId)
                            .filter(value -> value.sourceContractFileId().equals(fileId))
                            .orElseThrow(() -> new BusinessException(BusinessErrorCode.CONFLICT,
                                    "role decision candidate durable link is missing"));
                    if (same == null || !same.decisionId().equals(candidate.sourceIdentityDecisionId())
                            || !decisionId.equals(candidate.sourceRoleDecisionId())) {
                        return withCandidate(reopened, candidate.id(), null);
                    }
                    return withCandidate(reopened, candidate.id(),
                            ContractCandidateMaterializationStatus.REPLAYED);
                }).orElse(reopened);
    }

    private RoleConfirmationResult materialize(RoleConfirmationResult confirmed, CurrentUser actor,
                                               RoleConfirmationCommand command) {
        if (confirmed.decision().selectedRole() != DocumentRole.CONTRACT_BODY) {
            return withCandidate(confirmed, null, ContractCandidateMaterializationStatus.NOT_APPLICABLE);
        }
        if (!isCurrentContractRole(confirmed)) return withCandidate(confirmed, null, null);
        Optional<ContractCandidate> promoted = alreadyPromoted(confirmed.decision().projectId(),
                confirmed.decision().fileId());
        if (promoted.isPresent()) {
            return withCandidate(confirmed, promoted.orElseThrow().id(),
                    ContractCandidateMaterializationStatus.ALREADY_PROMOTED);
        }
        if (identityProjections == null) return confirmed;
        var file = files.findByProjectIdAndId(confirmed.decision().projectId(), confirmed.decision().fileId())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "project file not found"));
        var identity = identityProjections.project(file);
        if (!identity.eligibleForProjectUse()) {
            UUID existingDraftId = candidates == null ? null
                    : candidates.findBySourceContractFileId(file.projectId(), file.id())
                    .filter(value -> value.promotionState() == CandidatePromotionState.DRAFT)
                    .map(ContractCandidate::id).orElse(null);
            return withCandidate(confirmed, existingDraftId,
                    ContractCandidateMaterializationStatus.WAITING_FOR_PROJECT_IDENTITY);
        }
        if (candidateMaterializer == null) return confirmed;
        ContractCandidateMaterializationResult result = candidateMaterializer.materializeFromDecision(
                confirmed.decision(), identity.currentHumanSameDecision(), actor, command.metadata());
        return withCandidate(confirmed, result.candidate().id(), result.status());
    }

    private Optional<ContractCandidate> alreadyPromoted(UUID projectId, UUID fileId) {
        if (candidates == null) return Optional.empty();
        return candidates.findBySourceContractFileId(projectId, fileId)
                .filter(candidate -> candidate.promotionState() == CandidatePromotionState.PROMOTED);
    }

    private boolean isCurrentContractRole(RoleConfirmationResult result) {
        if (effectiveRoles == null || files == null) return true;
        var file = files.findByProjectIdAndId(result.decision().projectId(), result.decision().fileId())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "project file not found"));
        var role = effectiveRoles.resolve(file);
        return role.effectiveRole() == DocumentRole.CONTRACT_BODY
                && role.source().isAuthoritativeDecision()
                && result.decision().decisionId().equals(role.roleDecisionId());
    }

    private static RoleConfirmationResult withCandidate(RoleConfirmationResult result, UUID candidateId,
            ContractCandidateMaterializationStatus status) {
        return new RoleConfirmationResult(result.decision(), result.replayed(), candidateId, status);
    }

    static String normalizeReason(String reason) {
        if (reason == null) return null;
        String normalized = Normalizer.normalize(reason, Normalizer.Form.NFKC).strip();
        normalized = WHITESPACE.matcher(normalized).replaceAll(" ");
        return normalized.isEmpty() ? null : normalized;
    }

    private CurrentUser currentUser() {
        Optional<CurrentUser> current = authContext.currentUser();
        return current.orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED));
    }

    private static void validateKey(String key) {
        if (!SAFE_IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "invalid Idempotency-Key");
        }
    }
}
