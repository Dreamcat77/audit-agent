package com.fuquan.audit.contract.application.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.extraction.ContractCandidateAssembler;
import com.fuquan.audit.contract.application.project.identity.EffectiveProjectIdentityResolver;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleResolver;
import com.fuquan.audit.contract.domain.project.ContractCandidateAuthoritySource;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseMethod;
import com.fuquan.audit.contract.domain.project.ProjectFileParseOrigin;
import com.fuquan.audit.contract.domain.project.ProjectFileParseProvenance;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleDecision;
import com.fuquan.audit.contract.domain.project.ProjectFileIdentityAssessment;
import com.fuquan.audit.contract.domain.project.ProjectFileIdentityDecision;
import com.fuquan.audit.contract.domain.project.ProjectIdentityAssessmentSource;
import com.fuquan.audit.contract.domain.project.ProjectIdentityAssessmentStatus;
import com.fuquan.audit.contract.domain.project.ProjectIdentityResolution;
import com.fuquan.audit.contract.domain.project.RoleDecisionStatus;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectFileParseResultRepository;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectFileRoleDecisionRepository;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectFileIdentityAssessmentRepository;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectFileIdentityDecisionRepository;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectRepositories;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import com.fuquan.audit.security.domain.CurrentUser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ContractCandidateMaterializationServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-15T01:00:00Z");
    private static final CurrentUser ACTOR =
            new CurrentUser("actor-1", "auditor", "Auditor", Set.of("AUDITOR"));
    private static final ProjectRequestMetadata META =
            new ProjectRequestMetadata("request-1", "trace-1", "127.0.0.1");

    @Test
    void materializesExactParseFactsOnceAndReplaysSameCandidateWithEvidence() {
        Support support = new Support();
        ProjectFileRoleDecision decision = support.append(DocumentRole.CONTRACT_BODY, 1, null);

        ContractCandidateMaterializationResult first =
                support.materialize(decision);
        ContractCandidateMaterializationResult replay =
                support.materialize(decision);

        assertThat(first.status()).isEqualTo(ContractCandidateMaterializationStatus.MATERIALIZED);
        assertThat(replay.status()).isEqualTo(ContractCandidateMaterializationStatus.REPLAYED);
        assertThat(replay.candidate().id()).isEqualTo(first.candidate().id());
        assertThat(first.candidate().sourceParseResultId()).isEqualTo(support.parse.parseResultId());
        assertThat(first.candidate().sourceRoleDecisionId()).isEqualTo(decision.decisionId());
        assertThat(first.candidate().sourceAuthoritySource())
                .isEqualTo(ContractCandidateAuthoritySource.HUMAN_CONFIRMATION);
        assertThat(first.candidate().sourceFactFingerprint()).matches("[0-9a-f]{64}");
        assertThat(support.repositories.contractCandidateRepository().findByProjectId(support.project.id()))
                .containsExactly(first.candidate());
        assertThat(support.repositories.extractionResultRepository()
                .findByCandidateId(support.project.id(), first.candidate().id()))
                .isNotEmpty().allSatisfy(fact -> assertThat(fact.parseResultId())
                        .isEqualTo(support.parse.parseResultId()));
        assertThat(support.audits).extracting(OperationAuditEvent::eventType)
                .containsExactly("CONTRACT_CANDIDATE_MATERIALIZED");
    }

    @Test
    void laterContractDecisionReauthorizesSameDraftCandidateWithoutDuplicate() {
        Support support = new Support();
        ProjectFileRoleDecision firstDecision = support.append(DocumentRole.CONTRACT_BODY, 1, null);
        var first = support.materialize(firstDecision);
        ProjectFileRoleDecision other = support.append(DocumentRole.OTHER, 2, firstDecision.decisionId());
        assertConflict(() -> support.materialize(firstDecision));
        ProjectFileRoleDecision restored = support.append(DocumentRole.CONTRACT_BODY, 3, other.decisionId());

        var replay = support.materialize(restored);

        assertThat(replay.status()).isEqualTo(ContractCandidateMaterializationStatus.REPLAYED);
        assertThat(replay.candidate().id()).isEqualTo(first.candidate().id());
        assertThat(replay.candidate().sourceRoleDecisionId()).isEqualTo(restored.decisionId());
        assertThat(replay.candidate().versionNo()).isEqualTo(first.candidate().versionNo() + 1);
        assertConflict(() -> support.materialize(firstDecision));
        assertThat(support.repositories.contractCandidateRepository().findByProjectId(support.project.id()))
                .hasSize(1);
    }

    @Test
    void nonContractOrNonLatestDecisionCannotMaterialize() {
        Support support = new Support();
        ProjectFileRoleDecision other = support.append(DocumentRole.AWARD_NOTICE, 1, null);

        assertConflict(() -> support.materialize(other));
        assertThat(support.repositories.contractCandidateRepository().findByProjectId(support.project.id()))
                .isEmpty();
    }

    @Test
    void parsedContractWithoutRecognizedFactsCreatesAnEmptyResultButNoFormalContract() {
        Support support = new Support(false);
        ProjectFileRoleDecision contract = support.append(DocumentRole.CONTRACT_BODY, 1, null);

        var result = support.materialize(contract);

        assertThat(result.status()).isEqualTo(ContractCandidateMaterializationStatus.MATERIALIZED);
        assertThat(result.candidate().contractNo()).isNull();
        assertThat(result.candidate().contractName()).isNull();
        assertThat(result.candidate().contractAmount()).isNull();
        assertThat(result.candidate().lineItems()).isEmpty();
        assertThat(support.repositories.contractCandidateRepository().findByProjectId(support.project.id()))
                .containsExactly(result.candidate());
        assertThat(support.idempotency.size()).isEqualTo(1);
        assertThat(support.audits).extracting(OperationAuditEvent::eventType)
                .containsExactly("CONTRACT_CANDIDATE_MATERIALIZED");
    }

    @Test
    void legacyDraftWithNewParseBoundFactsCannotAcquireFakeAuthorityWhileNoFactsLegacyRemainsCompatible() {
        Support conflicting = new Support(true, ProjectFileParseOrigin.LEGACY_IMPORTED);
        ContractCandidate old = ContractCandidate.draft(UUID.randomUUID(), conflicting.project.id(),
                conflicting.file.id(), "UNRELATED-OLD-NO", null, null, null, null, null,
                null, null, null, null, null, List.of(), NOW);
        conflicting.repositories.contractCandidateRepository().insertIfAbsent(old);
        assertConflict(() -> conflicting.materialize(conflicting.append(DocumentRole.CONTRACT_BODY, 1, null)));
        assertThat(conflicting.repositories.contractCandidateRepository()
                .findByProjectIdAndId(conflicting.project.id(), old.id())).contains(old);

        Support compatible = new Support(false, ProjectFileParseOrigin.LEGACY_IMPORTED);
        ContractCandidate historical = ContractCandidate.draft(UUID.randomUUID(), compatible.project.id(),
                compatible.file.id(), "LEGACY-001", null, null, null, null, null,
                null, null, null, null, null, List.of(), NOW);
        compatible.repositories.contractCandidateRepository().insertIfAbsent(historical);
        var result = compatible.materialize(compatible.append(DocumentRole.CONTRACT_BODY, 1, null));
        assertThat(result.candidate().id()).isEqualTo(historical.id());
        assertThat(result.candidate().sourceIdentityDecisionId()).isEqualTo(compatible.identityDecision.decisionId());
    }

    @Test
    void supersedingDecisionCommittedBeforeSourceFileFenceBlocksStaleMaterialization() throws Exception {
        CountDownLatch enteredFence = new CountDownLatch(1);
        CountDownLatch releaseFence = new CountDownLatch(1);
        Support support = new Support(true, enteredFence, releaseFence);
        ProjectFileRoleDecision original = support.append(DocumentRole.CONTRACT_BODY, 1, null);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var stale = executor.submit(() -> support.materialize(original));
            assertThat(enteredFence.await(5, TimeUnit.SECONDS)).isTrue();
            support.append(DocumentRole.OTHER, 2, original.decisionId());
            releaseFence.countDown();
            assertThatThrownBy(() -> stale.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(BusinessException.class);
            assertThat(support.repositories.contractCandidateRepository().findByProjectId(support.project.id()))
                    .isEmpty();
        } finally {
            releaseFence.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void localCommandFenceHoldsTheSameFileAcrossEntireCommandWithoutTransactionManager() throws Exception {
        Support support = new Support();
        ProjectFileRepository files = support.repositories.projectFileRepository();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> files.withFileAuthorityFence(support.project.id(),
                    support.file.id(), () -> {
                        firstEntered.countDown();
                        try {
                            if (!releaseFirst.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                        return "first";
                    }));
            assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> {
                secondStarted.countDown();
                return files.withFileAuthorityFence(support.project.id(), support.file.id(), () -> {
                    secondEntered.countDown();
                    return "second";
                });
            });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(secondEntered.await(150, TimeUnit.MILLISECONDS)).isFalse();
            releaseFirst.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("first");
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("second");
            assertThat(secondEntered.getCount()).isZero();
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(BusinessErrorCode.CONFLICT));
    }

    private static final class Support {
        final InMemoryProjectRepositories repositories = new InMemoryProjectRepositories();
        final InMemoryProjectFileParseResultRepository parses = new InMemoryProjectFileParseResultRepository();
        final InMemoryProjectFileRoleDecisionRepository decisions =
                new InMemoryProjectFileRoleDecisionRepository();
        final InMemoryProjectFileIdentityAssessmentRepository identityAssessments =
                new InMemoryProjectFileIdentityAssessmentRepository();
        final InMemoryProjectFileIdentityDecisionRepository identityDecisions =
                new InMemoryProjectFileIdentityDecisionRepository();
        final InMemoryIdempotencyAdapter idempotency = new InMemoryIdempotencyAdapter();
        final List<OperationAuditEvent> audits = new ArrayList<>();
        final Project project = Project.blank(UUID.randomUUID(), ACTOR.userId(), NOW)
                .confirmMetadata(0, "Synthetic equipment project", "SYN-2026-001", null, NOW);
        final ProjectFile file;
        final ProjectFileParseResult parse;
        final ProjectFileIdentityDecision identityDecision;
        final ContractCandidateMaterializationService service;

        Support() {
            this(true);
        }

        Support(boolean seedFacts) {
            this(seedFacts, null, null);
        }

        Support(boolean seedFacts, ProjectFileParseOrigin origin) {
            this(seedFacts, null, null, origin);
        }

        Support(boolean seedFacts, CountDownLatch enteredFence, CountDownLatch releaseFence) {
            this(seedFacts, enteredFence, releaseFence, ProjectFileParseOrigin.CURRENT_PIPELINE);
        }

        Support(boolean seedFacts, CountDownLatch enteredFence, CountDownLatch releaseFence,
                ProjectFileParseOrigin origin) {
            repositories.projectRepository().insert(project);
            UUID fileId = UUID.randomUUID();
            String sha = "a".repeat(64);
            String text = "Contract No: C-001\nParty A: Buyer\nParty B: Supplier\nContract Amount: 42.50";
            file = new ProjectFile(fileId, project.id(), null, null, "001.pdf", "application/pdf",
                    ProjectFileCategory.UNKNOWN, ProjectFileIngestionStatus.AVAILABLE,
                    ProjectFileParseStatus.PARSED, sha, sha, 100, text, null,
                    NOW.minusSeconds(2), NOW.minusSeconds(1), new ProjectFileParseProvenance(
                    ProjectFileParseMethod.PDF_TEXT, "synthetic-parser", BigDecimal.ONE, 1, 1L));
            repositories.projectFileRepository().insert(file);
            parse = new ProjectFileParseResult(UUID.randomUUID(), project.id(), file.id(), sha, 1,
                    origin, ProjectFileParseStatus.PARSED,
                    ProjectFileParseMethod.PDF_TEXT, "synthetic-parser", "1", text,
                    sha256(text), 1, 1L, BigDecimal.ONE, null,
                    NOW.minusSeconds(2), NOW.minusSeconds(1), 0);
            parses.insertIfAbsent(parse);
            UUID assessmentId = UUID.randomUUID();
            identityAssessments.insertIfAbsent(new ProjectFileIdentityAssessment(assessmentId,
                    project.id(), project.versionNo(), file.id(), file.sha256(), parse.parseResultId(),
                    parse.parseVersion(), ProjectIdentityAssessmentSource.DETERMINISTIC, "identity-v1",
                    "human-only-v1", ProjectIdentityAssessmentStatus.INSUFFICIENT, "b".repeat(64),
                    null, NOW.minusSeconds(1), NOW, 0));
            identityDecision = new ProjectFileIdentityDecision(UUID.randomUUID(), project.id(),
                    project.versionNo(), file.id(), file.sha256(), parse.parseResultId(),
                    parse.parseVersion(), assessmentId, ProjectIdentityResolution.CONFIRM_SAME_PROJECT,
                    ACTOR.userId(), NOW, null, "synthetic project ownership", 1);
            identityDecisions.insertIfAbsent(identityDecision);
            if (seedFacts) {
                repositories.extractionResultRepository().insertIfAbsent(fact(
                        "CONTRACT_NO", "C-001", null, "1"));
                repositories.extractionResultRepository().insertIfAbsent(fact(
                        "AMOUNT_NUMERIC", "42.50", new BigDecimal("42.50"), "2"));
            }
            ContractCandidateMaterializationTransactionCoordinator coordinator =
                    new ContractCandidateMaterializationTransactionCoordinator(
                            authorityFileRepository(enteredFence, releaseFence), parses, decisions,
                            identityDecisions, repositories.projectRepository(),
                            new EffectiveDocumentRoleResolver(parses, decisions),
                            new EffectiveProjectIdentityResolver(parses, identityAssessments, identityDecisions),
                            repositories.extractionResultRepository(),
                            repositories.contractCandidateRepository(),
                            new ContractCandidateAssembler(Clock.fixed(NOW, ZoneOffset.UTC)),
                            idempotency, audits::add, Clock.fixed(NOW, ZoneOffset.UTC));
            service = new ContractCandidateMaterializationService(
                    coordinator, Clock.fixed(NOW, ZoneOffset.UTC));
        }

        ContractCandidateMaterializationResult materialize(ProjectFileRoleDecision roleDecision) {
            return service.materializeFromDecision(roleDecision, identityDecision, ACTOR, META);
        }

        private ProjectFileRepository authorityFileRepository(CountDownLatch entered, CountDownLatch release) {
            ProjectFileRepository delegate = repositories.projectFileRepository();
            if (entered == null) return delegate;
            return new ProjectFileRepository() {
                @Override public void insert(ProjectFile value) { delegate.insert(value); }
                @Override public void update(ProjectFile value) { delegate.update(value); }
                @Override public java.util.Optional<ProjectFile> findByProjectIdAndId(UUID projectId, UUID fileId) {
                    return delegate.findByProjectIdAndId(projectId, fileId);
                }
                @Override public java.util.Optional<ProjectFile> findByProjectIdAndIdForUpdate(UUID projectId, UUID fileId) {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fence fixture timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("fence fixture interrupted", interrupted);
                    }
                    return delegate.findByProjectIdAndIdForUpdate(projectId, fileId);
                }
                @Override public java.util.Optional<ProjectFile> findByProjectIdAndParentArchiveFileIdAndRelativePath(
                        UUID projectId, UUID parentId, String path) {
                    return delegate.findByProjectIdAndParentArchiveFileIdAndRelativePath(projectId, parentId, path);
                }
                @Override public List<ProjectFile> findByProjectId(UUID projectId) {
                    return delegate.findByProjectId(projectId);
                }
            };
        }

        ProjectFileRoleDecision append(DocumentRole role, long version, UUID supersedes) {
            ProjectFileRoleDecision decision = new ProjectFileRoleDecision(UUID.randomUUID(), project.id(),
                    file.id(), file.sha256(), parse.parseResultId(), parse.parseVersion(), UUID.randomUUID(),
                    role, RoleDecisionStatus.CONFIRMED, ACTOR.userId(), NOW.plusSeconds(version),
                    supersedes, "synthetic decision", version);
            decisions.insertIfAbsent(decision);
            return decision;
        }

        ExtractionResult fact(String type, String value, BigDecimal amount, String suffix) {
            String locator = "pdf/page:1/fact:" + type + "/occurrence:1";
            String key = new ExtractionKeyFactoryV2().create(file.id(), parse.parseResultId(),
                    "project-extraction-v2", type, null, locator);
            return new ExtractionResult(UUID.randomUUID(), project.id(), file.id(), parse.parseResultId(),
                    null, key, "project-extraction-v2", type, null, value, value, amount,
                    type + ": " + value, 1, null, locator, NOW.plusSeconds(Long.parseLong(suffix)));
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
