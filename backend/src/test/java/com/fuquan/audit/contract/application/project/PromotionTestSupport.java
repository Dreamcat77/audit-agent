package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.common.observability.ObservabilityProperties;
import com.fuquan.audit.contract.application.port.ContractRepository;
import com.fuquan.audit.contract.application.project.port.ContractCandidateRepository;
import com.fuquan.audit.contract.application.project.port.ContractCreationPort;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.ContractCandidateConfirmation;
import com.fuquan.audit.contract.domain.project.ContractCandidateAuthoritySource;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.EffectiveProjectIdentityStatus;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleSource;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectContractAdapter;
import com.fuquan.audit.contract.infra.memory.project.InMemoryContractCandidateConfirmationRepository;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import com.fuquan.audit.security.domain.CurrentUser;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

final class PromotionTestSupport {
    static final Instant NOW = Instant.parse("2026-08-26T00:00:00Z");
    static final ProjectRequestMetadata META = new ProjectRequestMetadata("request", "trace", "127.0.0.1");
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final Project project = Project.blank(UUID.randomUUID(), "actor", NOW);
    final UUID parseResultId = UUID.randomUUID(), roleDecisionId = UUID.randomUUID(),
            identityDecisionId = UUID.randomUUID();
    final AtomicReference<ContractCandidate> candidate = new AtomicReference<>(ContractCandidate.draftWithAuthority(
            UUID.randomUUID(), project.id(), UUID.randomUUID(), parseResultId, roleDecisionId,
            identityDecisionId, ContractCandidateAuthoritySource.HUMAN_CONFIRMATION, "a".repeat(64),
            "C-1", "Confirmed Contract", "GOODS",
            "Buyer", "Supplier", "913100000000000001", new BigDecimal("100.00"), "壹佰元整",
            new BigDecimal("100.00"), "Supplier", "913100000000000001", List.of(), NOW).confirmed(NOW));
    final AtomicReference<Contract> formal = new AtomicReference<>();
    final InMemoryProjectContractAdapter associations = new InMemoryProjectContractAdapter();
    final InMemoryIdempotencyAdapter idempotency = new InMemoryIdempotencyAdapter();
    final InMemoryContractCandidateConfirmationRepository confirmations =
            new InMemoryContractCandidateConfirmationRepository();
    final UUID confirmationId = UUID.randomUUID();
    final List<OperationAuditEvent> audits = new ArrayList<>();
    final CurrentUser actor = new CurrentUser("actor", "actor.name", "Actor", Set.of("AUDITOR"));
    final ContractCandidateRepository candidates = new CandidateRepository();
    final AtomicReference<CandidateSourceAuthority> sourceAuthority = new AtomicReference<>(
            new CandidateSourceAuthority(candidate.get().sourceContractFileId(), "c".repeat(64), parseResultId, 1,
                    DocumentRole.CONTRACT_BODY, EffectiveDocumentRoleSource.HUMAN_CONFIRMATION,
                    roleDecisionId, EffectiveProjectIdentityStatus.SAME_PROJECT, identityDecisionId,
                    candidate.get().sourceFactFingerprint(), true, null));
    final CandidateSourceAuthorityValidator authorityValidator =
            org.mockito.Mockito.mock(CandidateSourceAuthorityValidator.class);
    final ContractCandidatePromotionTransactionCoordinator coordinator;
    final ContractCandidatePromotionService service;

    PromotionTestSupport() {
        var canonicalizer = new ContractCandidateConfirmationPayloadCanonicalizer();
        var canonical = canonicalizer.canonicalize(payload("C-1"));
        confirmations.insert(new ContractCandidateConfirmation(confirmationId, project.id(), candidate.get().id(),
                0, 1, candidate.get().sourceContractFileId(), parseResultId, roleDecisionId, identityDecisionId,
                canonical.schemaVersion(), canonicalizer.fingerprint(project.id(), candidate.get().id(),
                        candidate.get().sourceContractFileId(), parseResultId, roleDecisionId,
                        identityDecisionId, 0, canonical.json()), canonical.json(), actor.userId(),
                NOW, null, NOW));
        ProjectRepository projects = org.mockito.Mockito.mock(ProjectRepository.class);
        org.mockito.Mockito.when(projects.findById(project.id())).thenReturn(Optional.of(project));
        org.mockito.Mockito.when(projects.findByIdForUpdate(project.id())).thenReturn(Optional.of(project));
        ContractCreationPort creation = contract -> {
            Contract existing = formal.get();
            if (existing != null || !formal.compareAndSet(null, contract)) {
                throw new BusinessException(BusinessErrorCode.CONFLICT, "contract number conflict");
            }
        };
        ContractRepository reads = id -> Optional.ofNullable(formal.get()).filter(value -> value.id().equals(id));
        org.mockito.Mockito.when(authorityValidator.snapshotWithCurrentSupport(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(ignored -> sourceAuthority.get());
        ProjectFileRepository files = org.mockito.Mockito.mock(ProjectFileRepository.class);
        org.mockito.Mockito.when(files.findByProjectIdAndIdForUpdate(
                project.id(), candidate.get().sourceContractFileId()))
                .thenReturn(Optional.of(org.mockito.Mockito.mock(ProjectFile.class)));
        org.mockito.Mockito.when(files.withFileAuthorityFence(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(2)).get());
        coordinator =
                new ContractCandidatePromotionTransactionCoordinator(projects, candidates, files,
                        authorityValidator, new com.fuquan.audit.contract.infra.memory.project.InMemoryContractCandidateSupportAuthorityRepository(),
                        confirmations, canonicalizer, creation,
                        associations, associations, reads, idempotency, audits::add, clock,
                        new FlowDebugLogger(new ObservabilityProperties()));
        service = new ContractCandidatePromotionService(coordinator, () -> Optional.of(actor),
                new FlowDebugLogger(new ObservabilityProperties()));
    }

    PromoteContractCandidateCommand command(String key) {
        return new PromoteContractCandidateCommand(project.id(), candidate.get().id(), 1,
                confirmationId, key, META);
    }

    PromoteContractCandidateCommand command(String key, long expectedVersion, ConfirmedContractPayload payload) {
        if (payload.contractNo().equals("C-1")) {
            return new PromoteContractCandidateCommand(project.id(), candidate.get().id(), expectedVersion,
                    confirmationId, key, META);
        }
        var canonicalizer = new ContractCandidateConfirmationPayloadCanonicalizer();
        var canonical = canonicalizer.canonicalize(payload);
        UUID otherId = UUID.randomUUID();
        confirmations.insert(new ContractCandidateConfirmation(otherId, project.id(), candidate.get().id(),
                1, 2, candidate.get().sourceContractFileId(), parseResultId, roleDecisionId, identityDecisionId,
                canonical.schemaVersion(), canonicalizer.fingerprint(project.id(), candidate.get().id(),
                        candidate.get().sourceContractFileId(), parseResultId, roleDecisionId,
                        identityDecisionId, 1, canonical.json()), canonical.json(), actor.userId(),
                NOW, confirmationId, NOW));
        return new PromoteContractCandidateCommand(project.id(), candidate.get().id(), expectedVersion,
                otherId, key, META);
    }

    void changeSourceAuthority(DocumentRole role, EffectiveDocumentRoleSource source, UUID decisionId) {
        sourceAuthority.set(new CandidateSourceAuthority(candidate.get().sourceContractFileId(),
                "c".repeat(64), parseResultId, 1, role, source, decisionId,
                EffectiveProjectIdentityStatus.SAME_PROJECT,
                sourceAuthority.get().currentIdentityDecisionId(),
                candidate.get().sourceFactFingerprint(), false, "candidate source role not authoritative"));
    }

    void changeCurrentIdentityDecision(UUID identityDecisionId) {
        CandidateSourceAuthority old = sourceAuthority.get();
        sourceAuthority.set(new CandidateSourceAuthority(old.sourceFileId(), old.fileSha256(),
                old.parseResultId(), old.projectVersionNo(), old.effectiveRole(), old.effectiveRoleSource(),
                old.currentRoleDecisionId(), EffectiveProjectIdentityStatus.SAME_PROJECT,
                identityDecisionId, old.sourceFactFingerprint(), true, null));
    }

    static ConfirmedContractPayload payload(String contractNo) {
        return new ConfirmedContractPayload(contractNo, "Confirmed Contract", "GOODS", "Buyer", "Supplier",
                "913100000000000001", new BigDecimal("100.00"), "壹佰元整",
                List.of(new ConfirmedContractPayload.ConfirmedLineItem("ITEM-1", "Server",
                        new BigDecimal("2"), new BigDecimal("50.00"), new BigDecimal("100.00"), 1)),
                new ConfirmedContractPayload.ConfirmedAwardNotice("AWARD-1", "Project", "Supplier",
                        "913100000000000001", new BigDecimal("100.00"), LocalDate.of(2026, 8, 20), null));
    }

    private final class CandidateRepository implements ContractCandidateRepository {
        @Override public ContractCandidate insertIfAbsent(ContractCandidate value) { candidate.set(value); return value; }
        @Override public Optional<ContractCandidate> findByProjectIdAndId(UUID projectId, UUID id) {
            return Optional.ofNullable(candidate.get()).filter(value -> value.projectId().equals(projectId)
                    && value.id().equals(id));
        }
        @Override public List<ContractCandidate> findByProjectId(UUID projectId) {
            return findByProjectIdAndId(projectId, candidate.get().id()).stream().toList();
        }
        @Override public Optional<ContractCandidate> findBySourceContractFileId(UUID projectId, UUID sourceFileId) {
            return Optional.ofNullable(candidate.get()).filter(value -> value.projectId().equals(projectId)
                    && value.sourceContractFileId().equals(sourceFileId));
        }
        @Override public synchronized ContractCandidate promoteIfVersionMatches(
                UUID projectId, UUID candidateId, long expectedVersion, UUID contractId,
                String promotedBy, Instant promotedAt) {
            ContractCandidate current = candidate.get();
            if (!current.projectId().equals(projectId) || !current.id().equals(candidateId)) {
                throw new BusinessException(BusinessErrorCode.NOT_FOUND);
            }
            if (current.promotionState() != com.fuquan.audit.contract.domain.project.CandidatePromotionState.DRAFT
                    || current.versionNo() != expectedVersion) {
                throw new BusinessException(BusinessErrorCode.CONFLICT, "candidate promotion conflict");
            }
            ContractCandidate promoted = current.promote(contractId, promotedBy, promotedAt);
            candidate.set(promoted);
            return promoted;
        }
        @Override public synchronized ContractCandidate promoteIfVersionMatches(
                UUID projectId, UUID candidateId, long expectedVersion, UUID contractId,
                UUID confirmedId, String promotedBy, Instant promotedAt) {
            ContractCandidate current = candidate.get();
            if (!current.projectId().equals(projectId) || !current.id().equals(candidateId)) {
                throw new BusinessException(BusinessErrorCode.NOT_FOUND);
            }
            if (current.promotionState() != com.fuquan.audit.contract.domain.project.CandidatePromotionState.DRAFT
                    || current.versionNo() != expectedVersion) {
                throw new BusinessException(BusinessErrorCode.CONFLICT, "candidate promotion conflict");
            }
            ContractCandidate promoted = current.promote(contractId, confirmedId, promotedBy, promotedAt);
            candidate.set(promoted);
            return promoted;
        }
    }
}
