package com.fuquan.audit.contract.application.project.role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.common.application.port.IdempotencyRecord;
import com.fuquan.audit.common.application.port.IdempotencyClaim;
import com.fuquan.audit.common.application.port.IdempotencyPort;
import com.fuquan.audit.common.application.port.IdempotencyRequest;
import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.application.project.ContractCandidateMaterializationResult;
import com.fuquan.audit.contract.application.project.ContractCandidateMaterializationService;
import com.fuquan.audit.contract.application.project.ContractCandidateMaterializationStatus;
import com.fuquan.audit.contract.application.project.identity.EffectiveProjectIdentity;
import com.fuquan.audit.contract.application.project.identity.ProjectFileIdentityProjectionService;
import com.fuquan.audit.contract.application.project.port.ContractCandidateRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.ContractCandidateAuthoritySource;
import com.fuquan.audit.contract.domain.project.EffectiveProjectIdentityStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileIdentityDecision;
import com.fuquan.audit.contract.domain.project.ProjectIdentityDecisionStatus;
import com.fuquan.audit.contract.domain.project.ProjectIdentityResolution;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseMethod;
import com.fuquan.audit.contract.domain.project.ProjectFileParseOrigin;
import com.fuquan.audit.contract.domain.project.ProjectFileParseProvenance;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleAssessment;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleAssessmentSource;
import com.fuquan.audit.contract.domain.project.RoleAssessmentExecutionStatus;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectFileParseResultRepository;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectFileRoleAssessmentRepository;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectFileRoleDecisionRepository;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RoleConfirmationServiceTest {
    private static final UUID PROJECT = UUID.fromString("10000000-0000-0000-0000-000000000011");
    private static final UUID FILE = UUID.fromString("20000000-0000-0000-0000-000000000011");
    private static final UUID PARSE = UUID.fromString("30000000-0000-0000-0000-000000000011");
    private static final UUID ASSESSMENT = UUID.fromString("40000000-0000-0000-0000-000000000011");
    private static final Instant NOW = Instant.parse("2026-09-14T09:00:00Z");
    private static final String FILE_SHA = "b".repeat(64);
    private static final String TEXT = "采购合同\n甲方：合成采购单位\n乙方：合成供应商\n双方签字盖章后生效。";
    private static final ProjectRequestMetadata META =
            new ProjectRequestMetadata("request-11", "trace-11", "127.0.0.1");

    @Test
    void confirmsCompletedAssessmentWithActorFromAuthContextAndNormalizedReason() {
        Support support = new Support();

        RoleConfirmationResult result = support.service.confirm(command(
                "confirm-key", DocumentRole.CONTRACT_BODY, 0, "  人工　复核 \n 通过  "));

        assertThat(result.replayed()).isFalse();
        assertThat(result.decision().projectId()).isEqualTo(PROJECT);
        assertThat(result.decision().assessmentId()).isEqualTo(ASSESSMENT);
        assertThat(result.decision().selectedRole()).isEqualTo(DocumentRole.CONTRACT_BODY);
        assertThat(result.decision().decidedBy()).isEqualTo("actor-11");
        assertThat(result.decision().reason()).isEqualTo("人工 复核 通过");
        assertThat(result.decision().versionNo()).isEqualTo(1);
        assertThat(result.decision().supersedesDecisionId()).isNull();
        assertThat(support.audits).extracting(OperationAuditEvent::eventType)
                .containsExactly("PROJECT_FILE_ROLE_CONFIRMED");
        IdempotencyRecord idempotency = support.idempotency
                .findByScopeAndKey("ROLE_CONFIRMATION", "confirm-key").orElseThrow();
        assertThat(idempotency.resultResourceType()).isEqualTo("PROJECT_FILE_ROLE_DECISION");
        assertThat(idempotency.resultResourceId()).isEqualTo(result.decision().decisionId().toString());
    }

    @Test
    void normalizedEquivalentReasonReplaysSameDecisionAfterServiceRestart() {
        Support support = new Support();
        RoleConfirmationResult first = support.service.confirm(command(
                "same-key", DocumentRole.AWARD_NOTICE, 0, "  人工　复核 \n 通过  "));

        RoleConfirmationResult replay = support.newService().confirm(command(
                "same-key", DocumentRole.AWARD_NOTICE, 0, "人工 复核 通过"));
        RoleConfirmationResult reopened = support.newService().get(PROJECT, FILE, first.decision().decisionId());

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.decision()).isEqualTo(first.decision());
        assertThat(reopened.replayed()).isFalse();
        assertThat(reopened.decision()).isEqualTo(first.decision());
        assertThat(support.audits).hasSize(1);
    }

    @Test
    void sameKeyWithChangedBusinessInputConflicts() {
        Support support = new Support();
        support.service.confirm(command("immutable-key", DocumentRole.CONTRACT_BODY, 0, "checked"));

        assertConflict(() -> support.service.confirm(command(
                "immutable-key", DocumentRole.OTHER, 0, "checked")));
        assertConflict(() -> support.service.confirm(command(
                "immutable-key", DocumentRole.CONTRACT_BODY, 0, "changed")));
        assertConflict(() -> support.service.confirm(command(
                "immutable-key", DocumentRole.CONTRACT_BODY, 1, "checked")));
        assertThat(support.decisions.findLatestByFileId(FILE).orElseThrow().versionNo()).isEqualTo(1);
    }

    @Test
    void expectedVersionAppendsAndLinksSupersededDecision() {
        Support support = new Support();
        var first = support.service.confirm(command("version-1", DocumentRole.CONTRACT_BODY, 0, null));

        assertConflict(() -> support.service.confirm(command(
                "stale-version", DocumentRole.OTHER, 0, "override")));
        var second = support.service.confirm(command("version-2", DocumentRole.OTHER, 1, "override"));

        assertThat(second.decision().versionNo()).isEqualTo(2);
        assertThat(second.decision().supersedesDecisionId()).isEqualTo(first.decision().decisionId());
        assertThat(support.decisions.findLatestByFileId(FILE)).contains(second.decision());
        assertThat(support.decisions.findById(first.decision().decisionId())).contains(first.decision());
    }

    @Test
    void rejectsIncompleteAssessmentAndAuthoritativeBindingDrift() {
        Support incomplete = new Support(RoleAssessmentExecutionStatus.RUNNING);
        assertConflict(() -> incomplete.service.confirm(command(
                "incomplete", DocumentRole.CONTRACT_BODY, 0, null)));

        Support drift = new Support();
        ProjectFile changed = drift.file.get();
        drift.file.set(new ProjectFile(changed.id(), changed.projectId(), changed.parentArchiveFileId(),
                changed.normalizedRelativePath(), changed.originalFilename(), changed.mediaType(), changed.category(),
                changed.ingestionStatus(), changed.parseStatus(), "c".repeat(64), "c".repeat(64), changed.sizeBytes(),
                changed.parsedText(), changed.safeReasonCode(), changed.createdAt(), changed.updatedAt(),
                changed.parseProvenance()));
        assertConflict(() -> drift.service.confirm(command(
                "binding-drift", DocumentRole.CONTRACT_BODY, 0, null)));
    }

    @Test
    void twoDistinctKeysRacingForOneExpectedVersionHaveExactlyOneWinner() throws Exception {
        Support support = new Support();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> raceConfirm(support, "race-a", DocumentRole.CONTRACT_BODY, ready, go));
            var b = executor.submit(() -> raceConfirm(support, "race-b", DocumentRole.AWARD_NOTICE, ready, go));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<Object> outcomes = List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(RoleConfirmationResult.class::isInstance).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(BusinessException.class::isInstance).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(BusinessException.class::isInstance)
                    .map(BusinessException.class::cast).findFirst().orElseThrow().errorCode())
                    .isEqualTo(BusinessErrorCode.CONFLICT);
            assertThat(support.decisions.findLatestByFileId(FILE).orElseThrow().versionNo()).isEqualTo(1);
        } finally {
            go.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void failedAuditRollsBackLocalDecisionFenceAndUncommittedClaim() {
        AtomicBoolean failFirst = new AtomicBoolean(true);
        Support support = new Support(RoleAssessmentExecutionStatus.COMPLETED, event -> {
            if (failFirst.getAndSet(false)) throw new IllegalStateException("synthetic audit outage");
        });

        assertThatThrownBy(() -> support.service.confirm(command(
                "audit-retry", DocumentRole.CONTRACT_BODY, 0, null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("audit outage");
        assertThat(support.decisions.findLatestByFileId(FILE)).isEmpty();
        assertThat(support.idempotency.findByScopeAndKey("ROLE_CONFIRMATION", "audit-retry")).isEmpty();

        RoleConfirmationResult retry = support.service.confirm(command(
                "audit-retry", DocumentRole.CONTRACT_BODY, 0, null));
        assertThat(retry.decision().versionNo()).isEqualTo(1);
    }

    @Test
    void concurrentSameKeyNeverSeesSucceededClaimWithoutPublishedDecision() throws Exception {
        BlockingSuccessIdempotencyPort blocking = new BlockingSuccessIdempotencyPort();
        Support support = new Support(RoleAssessmentExecutionStatus.COMPLETED, null, blocking);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> support.service.confirm(command(
                    "publication-race", DocumentRole.CONTRACT_BODY, 0, "checked")));
            assertThat(blocking.succeeded.await(5, TimeUnit.SECONDS)).isTrue();

            RoleConfirmationResult replay = support.newService().confirm(command(
                    "publication-race", DocumentRole.CONTRACT_BODY, 0, "checked"));

            assertThat(replay.replayed()).isTrue();
            blocking.release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).decision()).isEqualTo(replay.decision());
        } finally {
            blocking.release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void promotedCandidateSourceRejectsNonContractDecisionBeforeDecisionOrIdempotencyIsWritten() {
        Support support = new Support();
        ContractCandidate promoted = ContractCandidate.draft(UUID.randomUUID(), PROJECT, FILE,
                "LEGACY-1", "Synthetic contract", "GOODS", "Buyer", "Supplier", null,
                new BigDecimal("10.00"), null, null, null, null, List.of(), NOW)
                .promote(UUID.randomUUID(), support.actor.userId(), NOW);
        org.mockito.Mockito.when(support.candidates.findBySourceContractFileIdForUpdate(PROJECT, FILE))
                .thenReturn(Optional.of(promoted));

        assertThatThrownBy(() -> support.service.confirm(command(
                "promoted-divergence", DocumentRole.OTHER, 0, "incorrect source role")))
                .isInstanceOfSatisfying(BusinessException.class, failure -> {
                    assertThat(failure.errorCode()).isEqualTo(BusinessErrorCode.CONFLICT);
                    assertThat(failure.getMessage()).contains("promoted contract source role divergence");
                });
        assertThat(support.decisions.findLatestByFileId(FILE)).isEmpty();
        assertThat(support.idempotency.findByScopeAndKey(
                RoleConfirmationTransactionCoordinator.SCOPE, "promoted-divergence")).isEmpty();
    }

    @Test
    void historicalNonContractConfirmationReplaysAfterSourceIsPromotedButNewKeyStillConflicts() {
        Support support = new Support();
        RoleConfirmationCommand historical = command("historical-other", DocumentRole.OTHER, 0, "reviewed");
        RoleConfirmationResult first = support.service.confirm(historical);
        RoleConfirmationResult contract = support.service.confirm(command(
                "later-contract", DocumentRole.CONTRACT_BODY, 1, "reviewed"));
        ContractCandidate promoted = ContractCandidate.draft(UUID.randomUUID(), PROJECT, FILE,
                "LEGACY-1", "Synthetic contract", "GOODS", "Buyer", "Supplier", null,
                new BigDecimal("10.00"), null, null, null, null, List.of(), NOW)
                .promote(UUID.randomUUID(), support.actor.userId(), NOW);
        org.mockito.Mockito.when(support.candidates.findBySourceContractFileIdForUpdate(PROJECT, FILE))
                .thenReturn(Optional.of(promoted));

        RoleConfirmationResult replay = support.newService().confirm(historical);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.decision()).isEqualTo(first.decision());
        assertThat(support.decisions.findLatestByFileId(FILE)).contains(contract.decision());
        assertThat(support.audits).hasSize(2);
        assertConflict(() -> support.service.confirm(command(
                "new-other", DocumentRole.OTHER, 2, "reviewed")));
        assertThat(support.idempotency.findByScopeAndKey("ROLE_CONFIRMATION", "new-other")).isEmpty();
        assertConflict(() -> support.service.confirm(command(
                "historical-other", DocumentRole.CONTRACT_BODY, 0, "reviewed")));
    }

    @Test
    void promotedSameRoleDecisionReturnsExistingCandidateWithoutMaterializationRecordOrProvenanceRewrite() {
        Support support = new Support();
        ContractCandidate promoted = ContractCandidate.draft(UUID.randomUUID(), PROJECT, FILE,
                "LEGACY-1", "Synthetic contract", "GOODS", "Buyer", "Supplier", null,
                new BigDecimal("10.00"), null, null, null, null, List.of(), NOW)
                .promote(UUID.randomUUID(), support.actor.userId(), NOW);
        org.mockito.Mockito.when(support.candidates.findBySourceContractFileId(PROJECT, FILE))
                .thenReturn(Optional.of(promoted));
        ContractCandidateMaterializationService materializer =
                org.mockito.Mockito.mock(ContractCandidateMaterializationService.class);
        RoleConfirmationService service = new RoleConfirmationService(support.transactions, materializer,
                support.candidates, support.idempotency, support.files,
                () -> Optional.of(support.actor), Clock.fixed(NOW, ZoneOffset.UTC));

        RoleConfirmationResult first = service.confirm(command(
                "promoted-same-role", DocumentRole.CONTRACT_BODY, 0, null));
        RoleConfirmationResult replay = service.confirm(command(
                "promoted-same-role", DocumentRole.CONTRACT_BODY, 0, null));
        RoleConfirmationResult reopen = service.get(PROJECT, FILE, first.decision().decisionId());

        assertThat(first.candidateId()).isEqualTo(promoted.id());
        assertThat(first.candidateMaterializationStatus().name()).isEqualTo("ALREADY_PROMOTED");
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.candidateId()).isEqualTo(promoted.id());
        assertThat(replay.candidateMaterializationStatus().name()).isEqualTo("ALREADY_PROMOTED");
        assertThat(reopen.candidateId()).isEqualTo(promoted.id());
        assertThat(reopen.candidateMaterializationStatus().name()).isEqualTo("ALREADY_PROMOTED");
        assertThat(support.idempotency.findByScopeAndKey(
                "CONTRACT_CANDIDATE_MATERIALIZE", "role-decision:" + first.decision().decisionId())).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(materializer);
    }

    @Test
    void promotedOriginalAuthorityDecisionReplayAlsoNeverReentersCandidateMaterializer() {
        Support support = new Support();
        RoleConfirmationCommand command = command("original-authority", DocumentRole.CONTRACT_BODY, 0, null);
        RoleConfirmationResult original = support.service.confirm(command);
        ContractCandidate promoted = ContractCandidate.draftWithAuthority(UUID.randomUUID(), PROJECT, FILE,
                PARSE, original.decision().decisionId(), ContractCandidateAuthoritySource.HUMAN_CONFIRMATION,
                "f".repeat(64), "SYN-1", "Synthetic contract", "GOODS", "Buyer", "Supplier", null,
                new BigDecimal("10.00"), null, null, null, null, List.of(), NOW)
                .promote(UUID.randomUUID(), support.actor.userId(), NOW);
        org.mockito.Mockito.when(support.candidates.findBySourceContractFileId(PROJECT, FILE))
                .thenReturn(Optional.of(promoted));
        ContractCandidateMaterializationService materializer =
                org.mockito.Mockito.mock(ContractCandidateMaterializationService.class);
        RoleConfirmationService service = new RoleConfirmationService(support.transactions, materializer,
                support.candidates, support.idempotency, support.files,
                () -> Optional.of(support.actor), Clock.fixed(NOW, ZoneOffset.UTC));

        RoleConfirmationResult replay = service.confirm(command);
        RoleConfirmationResult reopened = service.get(PROJECT, FILE, original.decision().decisionId());

        assertThat(replay.decision()).isEqualTo(original.decision());
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.candidateId()).isEqualTo(promoted.id());
        assertThat(replay.candidateMaterializationStatus().name()).isEqualTo("ALREADY_PROMOTED");
        assertThat(reopened.candidateId()).isEqualTo(promoted.id());
        assertThat(reopened.candidateMaterializationStatus().name()).isEqualTo("ALREADY_PROMOTED");
        org.mockito.Mockito.verifyNoInteractions(materializer);
    }

    @Test
    void committedRoleDecisionSurvivesMaterializationFailureAndSameKeyRetriesWithoutNewDecision() {
        Support support = new Support();
        ContractCandidate materialized = ContractCandidate.draft(UUID.randomUUID(), PROJECT, FILE,
                "SYN-RETRY", "Synthetic contract", "GOODS", "Buyer", "Supplier", null,
                new BigDecimal("10.00"), null, null, null, null, List.of(), NOW);
        ContractCandidateMaterializationService materializer =
                org.mockito.Mockito.mock(ContractCandidateMaterializationService.class);
        AtomicInteger attempts = new AtomicInteger();
        org.mockito.Mockito.when(materializer.materializeFromDecision(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(ProjectFileIdentityDecision.class),
                org.mockito.ArgumentMatchers.eq(support.actor),
                org.mockito.ArgumentMatchers.eq(META))).thenAnswer(invocation -> {
                    if (attempts.getAndIncrement() == 0) {
                        throw new IllegalStateException("synthetic materialization write outage");
                    }
                    return new ContractCandidateMaterializationResult(materialized,
                            ContractCandidateMaterializationStatus.REPLAYED);
                });
        RoleConfirmationService service = new RoleConfirmationService(support.transactions, materializer,
                support.candidates, support.idempotency, support.files, sameIdentityProjection(),
                () -> Optional.of(support.actor), Clock.fixed(NOW, ZoneOffset.UTC));
        RoleConfirmationCommand command = command("post-decision-retry", DocumentRole.CONTRACT_BODY, 0, null);

        assertThatThrownBy(() -> service.confirm(command))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("materialization write outage");
        var durableDecision = support.decisions.findLatestByFileId(FILE).orElseThrow();
        assertThat(support.idempotency.findByScopeAndKey("ROLE_CONFIRMATION", "post-decision-retry")
                .orElseThrow().resultResourceId()).isEqualTo(durableDecision.decisionId().toString());

        RoleConfirmationResult replay = service.confirm(command);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.decision()).isEqualTo(durableDecision);
        assertThat(replay.candidateId()).isEqualTo(materialized.id());
        assertThat(replay.candidateMaterializationStatus())
                .isEqualTo(ContractCandidateMaterializationStatus.REPLAYED);
        assertThat(support.decisions.findLatestByFileId(FILE)).contains(durableDecision);
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void historicalRoleOnlyLinkDoesNotClaimCurrentPairMaterializationBeforeIdentityReauthorization() {
        Support support = new Support();
        RoleConfirmationResult body = support.service.confirm(command(
                "legacy-role-only-link", DocumentRole.CONTRACT_BODY, 0, null));
        ContractCandidate old = ContractCandidate.draftWithAuthority(UUID.randomUUID(), PROJECT, FILE,
                PARSE, body.decision().decisionId(), ContractCandidateAuthoritySource.HUMAN_CONFIRMATION,
                "f".repeat(64), "SYN-1", null, null, null, null, null,
                null, null, null, null, null, List.of(), NOW);
        org.mockito.Mockito.when(support.candidates.findBySourceContractFileId(PROJECT, FILE))
                .thenReturn(Optional.of(old));
        org.mockito.Mockito.when(support.candidates.findByProjectIdAndId(PROJECT, old.id()))
                .thenReturn(Optional.of(old));
        String key = "role-decision:" + body.decision().decisionId();
        IdempotencyRecord record = support.idempotency.claim(new IdempotencyRequest(
                "CONTRACT_CANDIDATE_MATERIALIZE", key, support.actor.userId(),
                "PROJECT_FILE_ROLE_DECISION", body.decision().decisionId().toString(), "f".repeat(64),
                "CONTRACT_CANDIDATE", old.id().toString(), "request", "trace", NOW)).record();
        support.idempotency.markSucceeded(record, "CONTRACT_CANDIDATE", old.id().toString(), NOW);
        RoleConfirmationService service = new RoleConfirmationService(support.transactions,
                org.mockito.Mockito.mock(ContractCandidateMaterializationService.class),
                support.candidates, support.idempotency, support.files, sameIdentityProjection(),
                () -> Optional.of(support.actor), Clock.fixed(NOW, ZoneOffset.UTC));

        RoleConfirmationResult reopened = service.get(PROJECT, FILE, body.decision().decisionId());
        assertThat(reopened.candidateId()).isEqualTo(old.id());
        assertThat(reopened.candidateMaterializationStatus()).isNull();
    }

    private static Object raceConfirm(Support support, String key, DocumentRole role,
                                      CountDownLatch ready, CountDownLatch go) {
        ready.countDown();
        try {
            if (!go.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timeout");
            return support.service.confirm(command(key, role, 0, null));
        } catch (BusinessException failure) {
            return failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static RoleConfirmationCommand command(String key, DocumentRole role, long version, String reason) {
        return new RoleConfirmationCommand(PROJECT, FILE, ASSESSMENT, role, version, reason, key, META);
    }

    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(BusinessErrorCode.CONFLICT));
    }

    private static ProjectFileIdentityProjectionService sameIdentityProjection() {
        ProjectFileIdentityProjectionService projection =
                org.mockito.Mockito.mock(ProjectFileIdentityProjectionService.class);
        ProjectFileIdentityDecision decision = new ProjectFileIdentityDecision(UUID.randomUUID(),
                PROJECT, 1, FILE, FILE_SHA, PARSE, 1, UUID.randomUUID(),
                ProjectIdentityResolution.CONFIRM_SAME_PROJECT, "actor-11", NOW, null,
                "synthetic ownership", 1);
        org.mockito.Mockito.when(projection.project(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new EffectiveProjectIdentity(EffectiveProjectIdentityStatus.SAME_PROJECT,
                        decision.assessmentId(), null, ProjectIdentityDecisionStatus.CONFIRMED,
                        decision.decisionId(), decision.versionNo(), true, false, decision));
        return projection;
    }

    private static final class Support {
        final AtomicReference<ProjectFile> file = new AtomicReference<>(file());
        final ProjectFileRepository files = files(file);
        final InMemoryProjectFileParseResultRepository parses = new InMemoryProjectFileParseResultRepository();
        final InMemoryProjectFileRoleAssessmentRepository assessments =
                new InMemoryProjectFileRoleAssessmentRepository();
        final InMemoryProjectFileRoleDecisionRepository decisions =
                new InMemoryProjectFileRoleDecisionRepository();
        final ContractCandidateRepository candidates = org.mockito.Mockito.mock(ContractCandidateRepository.class);
        final IdempotencyPort idempotency;
        final List<OperationAuditEvent> audits = new ArrayList<>();
        final CurrentUser actor = new CurrentUser("actor-11", "auditor", "Auditor", Set.of("AUDITOR"));
        final RoleConfirmationTransactionCoordinator transactions;
        final RoleConfirmationService service;

        Support() { this(RoleAssessmentExecutionStatus.COMPLETED); }

        Support(RoleAssessmentExecutionStatus status) { this(status, null); }

        Support(RoleAssessmentExecutionStatus status,
                com.fuquan.audit.common.application.port.OperationAuditPort auditPort) {
            this(status, auditPort, new InMemoryIdempotencyAdapter());
        }

        Support(RoleAssessmentExecutionStatus status,
                com.fuquan.audit.common.application.port.OperationAuditPort auditPort,
                IdempotencyPort idempotency) {
            this.idempotency = idempotency;
            parses.insertIfAbsent(parse());
            assessments.insertIfAbsent(assessment(status));
            transactions = new RoleConfirmationTransactionCoordinator(
                    files, parses, assessments, decisions, candidates, idempotency,
                    auditPort == null ? audits::add : auditPort);
            service = newService();
        }

        RoleConfirmationService newService() {
            return new RoleConfirmationService(transactions, () -> Optional.of(actor),
                    Clock.fixed(NOW, ZoneOffset.UTC));
        }
    }

    private static final class BlockingSuccessIdempotencyPort implements IdempotencyPort {
        private final InMemoryIdempotencyAdapter delegate = new InMemoryIdempotencyAdapter();
        private final CountDownLatch succeeded = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override public IdempotencyClaim claim(IdempotencyRequest request) {
            return delegate.claim(request);
        }

        @Override public IdempotencyRecord markSucceeded(IdempotencyRecord record, String resourceType,
                                                          String resourceId, Instant completedAt) {
            IdempotencyRecord completed = delegate.markSucceeded(
                    record, resourceType, resourceId, completedAt);
            succeeded.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test latch timeout");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return completed;
        }

        @Override public IdempotencyRecord markFailed(IdempotencyRecord record, Instant completedAt) {
            return delegate.markFailed(record, completedAt);
        }

        @Override public Optional<IdempotencyRecord> findByScopeAndKey(String scope, String key) {
            return delegate.findByScopeAndKey(scope, key);
        }

        @Override public Optional<IdempotencyRecord> findByResultResource(String resourceType, String resourceId) {
            return delegate.findByResultResource(resourceType, resourceId);
        }

        @Override public void rollbackUncommittedClaim(IdempotencyRecord record) {
            delegate.rollbackUncommittedClaim(record);
        }
    }

    private static ProjectFileRoleAssessment assessment(RoleAssessmentExecutionStatus status) {
        ProjectFileRoleAssessment pending = ProjectFileRoleAssessment.pending(ASSESSMENT, PROJECT, FILE, FILE_SHA,
                PARSE, 1, ProjectFileRoleAssessmentSource.MACHINE_DETERMINISTIC,
                "deterministic-role-assessment-v1", "document-role-policy-v1", "d".repeat(64), null, null,
                NOW.minusSeconds(4));
        ProjectFileRoleAssessment running = pending.start(NOW.minusSeconds(3));
        if (status == RoleAssessmentExecutionStatus.RUNNING) return running;
        return running.complete(DocumentRole.CONTRACT_BODY, new BigDecimal("0.950000"), false,
                List.of(new com.fuquan.audit.contract.domain.project.ProjectFileRoleCandidateProjection(
                        DocumentRole.CONTRACT_BODY, new BigDecimal("10.0000"), new BigDecimal("0.950000"),
                        "TITLE_CONTRACT")), null, NOW.minusSeconds(2));
    }

    private static ProjectFile file() {
        return new ProjectFile(FILE, PROJECT, null, null, "001.pdf", "application/pdf",
                ProjectFileCategory.UNKNOWN, ProjectFileIngestionStatus.AVAILABLE, ProjectFileParseStatus.PARSED,
                FILE_SHA, FILE_SHA, 512, TEXT, null, NOW.minusSeconds(6), NOW.minusSeconds(5),
                new ProjectFileParseProvenance(ProjectFileParseMethod.PDF_TEXT, "synthetic-parser",
                        BigDecimal.ONE, 1, 5L));
    }

    private static ProjectFileParseResult parse() {
        return new ProjectFileParseResult(PARSE, PROJECT, FILE, FILE_SHA, 1,
                ProjectFileParseOrigin.CURRENT_PIPELINE, ProjectFileParseStatus.PARSED,
                ProjectFileParseMethod.PDF_TEXT, "synthetic-parser", "1", TEXT, sha256(TEXT),
                1, 5L, BigDecimal.ONE, null, NOW.minusSeconds(6), NOW.minusSeconds(5), 1);
    }

    private static ProjectFileRepository files(AtomicReference<ProjectFile> value) {
        return new ProjectFileRepository() {
            @Override public void insert(ProjectFile file) { value.set(file); }
            @Override public void update(ProjectFile file) { value.set(file); }
            @Override public Optional<ProjectFile> findByProjectIdAndId(UUID projectId, UUID fileId) {
                return Optional.ofNullable(value.get()).filter(file -> file.projectId().equals(projectId)
                        && file.id().equals(fileId));
            }
            @Override public Optional<ProjectFile> findByProjectIdAndIdForUpdate(UUID projectId, UUID fileId) {
                return findByProjectIdAndId(projectId, fileId);
            }
            @Override public <T> T withFileAuthorityFence(UUID projectId, UUID fileId,
                    java.util.function.Supplier<T> command) { return command.get(); }
            @Override public Optional<ProjectFile> findByProjectIdAndParentArchiveFileIdAndRelativePath(
                    UUID projectId, UUID parentArchiveFileId, String relativePath) { return Optional.empty(); }
            @Override public List<ProjectFile> findByProjectId(UUID projectId) { return List.of(value.get()); }
        };
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
