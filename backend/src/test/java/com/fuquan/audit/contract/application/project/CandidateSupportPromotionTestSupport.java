package com.fuquan.audit.contract.application.project;

import static com.fuquan.audit.contract.application.project.ContractCandidateSupportTestSupport.*;
import static org.mockito.Mockito.spy;

import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.common.observability.ObservabilityProperties;
import com.fuquan.audit.contract.application.project.extraction.ContractCandidateAssembler;
import com.fuquan.audit.contract.application.project.identity.*;
import com.fuquan.audit.contract.application.project.port.*;
import com.fuquan.audit.contract.application.project.role.*;
import com.fuquan.audit.contract.domain.project.*;
import com.fuquan.audit.contract.infra.memory.*;
import com.fuquan.audit.contract.infra.memory.project.*;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

/** Local production repositories and decision writers; transaction callbacks are not DM8 proof. */
final class CandidateSupportPromotionTestSupport {
    final InMemoryProjectRepositories memory = new InMemoryProjectRepositories();
    final ProjectRepository projects = spy(memory.projectRepository());
    final ProjectFileRepository files = spy(memory.projectFileRepository());
    final ContractCandidateRepository candidates = spy(memory.contractCandidateRepository());
    final ExtractionResultRepository extractions = memory.extractionResultRepository();
    final InMemoryProjectFileParseResultRepository parses = new InMemoryProjectFileParseResultRepository();
    final InMemoryProjectFileRoleDecisionRepository roles = new InMemoryProjectFileRoleDecisionRepository();
    final InMemoryProjectFileIdentityDecisionRepository identities = new InMemoryProjectFileIdentityDecisionRepository();
    final InMemoryProjectFileRoleAssessmentRepository roleAssessments = new InMemoryProjectFileRoleAssessmentRepository();
    final InMemoryProjectFileIdentityAssessmentRepository identityAssessments = new InMemoryProjectFileIdentityAssessmentRepository();
    final InMemoryContractCandidateSupportAuthorityRepository supports = spy(new InMemoryContractCandidateSupportAuthorityRepository());
    final InMemoryContractCandidateConfirmationRepository confirmations = new InMemoryContractCandidateConfirmationRepository();
    final InMemoryIdempotencyAdapter idempotency = spy(new InMemoryIdempotencyAdapter());
    final InMemoryProjectContractAdapter associations = new InMemoryProjectContractAdapter();
    final LocalContractStore contractStore = new LocalContractStore();
    final FlowDebugLogger flow = new FlowDebugLogger(new ObservabilityProperties());
    final InMemoryContractCreationAdapter creation = spy(new InMemoryContractCreationAdapter(contractStore));
    final InMemoryContractRepository contracts = spy(new InMemoryContractRepository(contractStore, flow));
    final List<OperationAuditEvent> audits = new CopyOnWriteArrayList<>();
    final List<Source> sources = List.of(new Source(PRIMARY, DocumentRole.CONTRACT_BODY, "100"),
            new Source(A, DocumentRole.AWARD_NOTICE, "98"), new Source(B, DocumentRole.AWARD_NOTICE, "98"));
    final Clock clock = Clock.fixed(NOW.plusSeconds(5), ZoneOffset.UTC);
    final CandidateSourceAuthorityValidator sourceAuthority;
    final CandidateSupportAuthorityValidator supportAuthority;
    final ContractCandidateSupportReplacementTransactionCoordinator replacement;
    final ContractCandidateConfirmationTransactionCoordinator confirmation;
    final ContractCandidatePromotionTransactionCoordinator promotion;
    final ContractCandidatePromotionService service;
    final RoleConfirmationTransactionCoordinator roleWriter;
    final IdentityConfirmationTransactionCoordinator identityWriter;

    CandidateSupportPromotionTestSupport() {
        projects.insert(new Project(PROJECT, "Synthetic project", "SYN-1", null, ACTOR.userId(), 1, NOW, NOW));
        for (var source : sources) {
            files.insert(source.file());
            parses.insertIfAbsent(source.parse());
            roles.insertIfAbsent(source.role());
            identities.insertIfAbsent(source.identity());
            identityAssessments.insertIfAbsent(source.assessment());
            source.facts.forEach(extractions::insertIfAbsent);
            var assessment = ProjectFileRoleAssessment.pending(source.assessmentId, PROJECT, source.id,
                    source.hash, source.parseId, 1, ProjectFileRoleAssessmentSource.MACHINE_DETERMINISTIC,
                    "test-v1", "test-v1", "a".repeat(64), null, null, NOW)
                    .start(NOW).complete(source.selectedRole, BigDecimal.ONE, false,
                            List.of(new ProjectFileRoleCandidateProjection(source.selectedRole,
                                    BigDecimal.ONE, BigDecimal.ONE, "SYNTHETIC")), null, NOW);
            roleAssessments.insertIfAbsent(assessment);
        }
        var effectiveRoles = new EffectiveDocumentRoleResolver(parses, roles);
        var effectiveIdentities = new EffectiveProjectIdentityResolver(parses, identityAssessments, identities);
        supportAuthority = spy(new CandidateSupportAuthorityValidator(projects, files, parses, roles, identities,
                effectiveRoles, effectiveIdentities, extractions, supports));
        sourceAuthority = spy(new CandidateSourceAuthorityValidator(files, parses, roles, effectiveRoles,
                projects, identities, effectiveIdentities, supportAuthority));
        var assembler = new ContractCandidateAssembler(clock);
        var primary = sources.get(0);
        candidates.insertIfAbsent(assembler.assemble(CANDIDATE, PROJECT, primary.file(), primary.parseId,
                primary.roleId, primary.identityId, "a".repeat(64), primary.facts));
        replacement = new ContractCandidateSupportReplacementTransactionCoordinator(projects, files, candidates,
                sourceAuthority, supportAuthority, extractions, supports, assembler, idempotency, audits::add, clock);
        var canonicalizer = new ContractCandidateConfirmationPayloadCanonicalizer();
        confirmation = new ContractCandidateConfirmationTransactionCoordinator(projects, candidates, files,
                sourceAuthority, supports, confirmations, canonicalizer, idempotency,
                audits::add, clock, flow);
        promotion = newPromotionCoordinator();
        service = promotionService(promotion, new PromotionSessionCacheTestSupport.Transactions());
        roleWriter = new RoleConfirmationTransactionCoordinator(files, parses, roleAssessments, roles,
                candidates, idempotency, audits::add);
        identityWriter = new IdentityConfirmationTransactionCoordinator(projects, files, parses,
                identityAssessments, identities, candidates, idempotency, audits::add);
    }

    ContractCandidatePromotionTransactionCoordinator newPromotionCoordinator() {
        return new ContractCandidatePromotionTransactionCoordinator(projects, candidates, files,
                sourceAuthority, supports, confirmations, new ContractCandidateConfirmationPayloadCanonicalizer(),
                creation, associations, associations, contracts, idempotency, audits::add, clock, flow);
    }

    ContractCandidatePromotionService promotionService(ContractCandidatePromotionTransactionCoordinator coordinator,
            org.springframework.transaction.PlatformTransactionManager manager) {
        var proxy = new org.springframework.aop.framework.ProxyFactory(coordinator);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        return new ContractCandidatePromotionService((ContractCandidatePromotionTransactionCoordinator) proxy.getProxy(),
                () -> java.util.Optional.of(ACTOR), flow);
    }

    ContractCandidate current() { return candidates.findByProjectIdAndId(PROJECT, CANDIDATE).orElseThrow(); }

    ContractCandidateSupportReplacementResult replace(List<UUID> ids) {
        return transaction(() -> replacement.replace(new ReplaceContractCandidateSupportAuthorityCommand(
                PROJECT, CANDIDATE, current().versionNo(), ids, "replace-" + UUID.randomUUID(), META), ACTOR));
    }

    ContractCandidateConfirmation confirm() {
        return transaction(() -> confirmation.confirm(new ConfirmContractCandidateCommand(PROJECT, CANDIDATE,
                current().versionNo(), PromotionTestSupport.payload("SUPPORT-PROMOTION"),
                "confirm-" + UUID.randomUUID(), META), ACTOR)).confirmation();
    }

    ContractCandidatePromotionResult promote(ContractCandidateConfirmation confirmed) {
        return service.promote(command(confirmed, "promote-" + UUID.randomUUID()));
    }

    PromoteContractCandidateCommand command(ContractCandidateConfirmation confirmed, String key) {
        return new PromoteContractCandidateCommand(PROJECT, CANDIDATE, current().versionNo(),
                confirmed.confirmationId(), key, META);
    }

    void changeRole() {
        transaction(() -> roleWriter.confirm(new RoleConfirmationCommand(PROJECT, A, sources.get(1).assessmentId,
                DocumentRole.OTHER, roles.findLatestByFileId(A).orElseThrow().versionNo(),
                "synthetic role change", "role-" + UUID.randomUUID(), META), ACTOR, UUID.randomUUID(),
                NOW.plusSeconds(10)));
    }

    void changeIdentity() {
        transaction(() -> identityWriter.confirm(new IdentityConfirmationCommand(PROJECT, A,
                sources.get(1).assessmentId, ProjectIdentityResolution.CONFIRM_NOT_SAME_PROJECT,
                identities.findLatestByFileId(A).orElseThrow().versionNo(), "synthetic identity change",
                "identity-" + UUID.randomUUID(), META), ACTOR, UUID.randomUUID(), NOW.plusSeconds(10)));
    }

    void drift(String kind) {
        switch (kind) {
            case "role" -> changeRole();
            case "identity" -> changeIdentity();
            case "missing" -> supports.deleteCurrentByProjectIdAndCandidateId(PROJECT, CANDIDATE);
            case "parse" -> {
                var previous = parses.findLatestByFileId(A).orElseThrow();
                parses.insertIfAbsent(new ProjectFileParseResult(UUID.randomUUID(), PROJECT, A,
                        previous.fileSha256(), 2, previous.parseOrigin(), previous.parseStatus(), previous.parseMethod(),
                        previous.engine(), previous.engineVersion(), previous.canonicalText(), previous.canonicalTextSha256(),
                        previous.pageCount(), previous.durationMs(), previous.confidence(), previous.parseResultId(),
                        NOW.plusSeconds(10), NOW.plusSeconds(10), 0));
            }
            case "fingerprint", "cohort" -> {
                var row = supports.findCurrentByProjectIdAndCandidateId(PROJECT, CANDIDATE).get(0);
                supports.replaceCurrentCohort(PROJECT, CANDIDATE, List.of(new ContractCandidateSupportAuthority(
                        UUID.randomUUID(), PROJECT, CANDIDATE, A, row.fileSha256(), row.parseResultId(), row.parseVersion(),
                        row.projectVersionNo(), row.effectiveRole(), row.roleDecisionId(), row.identityResolution(),
                        row.identityDecisionId(), kind.equals("fingerprint") ? "b".repeat(64) : row.sourceFactFingerprint(),
                        kind.equals("cohort") ? 2 : row.authorityVersion(), row.createdBy(), row.createdAt())));
            }
            default -> throw new IllegalArgumentException(kind);
        }
    }

    static <T> T transaction(Supplier<T> action) {
        return new TransactionTemplate(new BoundaryTransactions()).execute(ignored -> action.get());
    }
}
