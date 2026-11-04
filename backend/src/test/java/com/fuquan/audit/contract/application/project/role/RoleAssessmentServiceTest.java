package com.fuquan.audit.contract.application.project.role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.application.port.OperationAuditPort;
import com.fuquan.audit.common.application.port.IdempotencyClaim;
import com.fuquan.audit.common.application.port.IdempotencyPort;
import com.fuquan.audit.common.application.port.IdempotencyRecord;
import com.fuquan.audit.common.application.port.IdempotencyRequest;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.common.observability.ObservabilityProperties;
import com.fuquan.audit.contract.application.project.LegacyProjectFileParseResultMaterializer;
import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.application.project.port.ProjectFileParseResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRoleAssessmentRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRoleEvidenceRepository;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseMethod;
import com.fuquan.audit.contract.domain.project.ProjectFileParseOrigin;
import com.fuquan.audit.contract.domain.project.ProjectFileParseProvenance;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleEvidence;
import com.fuquan.audit.contract.domain.project.RoleAssessmentExecutionStatus;
import com.fuquan.audit.contract.domain.project.RoleDecisionStatus;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectFileParseResultRepository;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectFileRoleAssessmentRepository;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectFileRoleEvidenceRepository;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import com.fuquan.audit.security.domain.CurrentUser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class RoleAssessmentServiceTest {
    private static final UUID PROJECT = UUID.fromString("10000000-0000-0000-0000-000000000010");
    private static final UUID FILE = UUID.fromString("20000000-0000-0000-0000-000000000010");
    private static final UUID PARSE = UUID.fromString("30000000-0000-0000-0000-000000000010");
    private static final Instant NOW = Instant.parse("2026-09-14T08:00:00Z");
    private static final String FILE_SHA = "a".repeat(64);
    private static final String TEXT = """
            采购合同
            甲方：合成采购单位
            乙方：合成供应商
            第一条 合同价款
            第二条 违约责任
            双方签字盖章后生效。
            """;
    private static final ProjectRequestMetadata META = new ProjectRequestMetadata("request-10", "trace-10", "127.0.0.1");

    @Test
    void legacyFileIsMaterializedThenCompletesWithDurableEvidenceAndNoFileMutation() {
        Support support = Support.legacy();
        ProjectFile before = support.file.get();

        RoleAssessmentResult result = support.service.assess(command("role-key"));

        assertThat(result.assessment().executionStatus()).isEqualTo(RoleAssessmentExecutionStatus.COMPLETED);
        assertThat(support.assessments.transitions).containsExactly(
                RoleAssessmentExecutionStatus.PENDING,
                RoleAssessmentExecutionStatus.RUNNING,
                RoleAssessmentExecutionStatus.COMPLETED);
        assertThat(result.assessment().proposedRole()).isEqualTo(DocumentRole.CONTRACT_BODY);
        assertThat(result.assessment().parseResultId()).isEqualTo(
                support.parseResults.findLatestByFileId(FILE).orElseThrow().parseResultId());
        assertThat(result.decisionStatus()).isEqualTo(RoleDecisionStatus.NEEDS_CONFIRMATION);
        assertThat(result.evidence()).isNotEmpty().allSatisfy(evidence -> {
            assertThat(evidence.assessmentId()).isEqualTo(result.assessment().assessmentId());
            assertThat(evidence.fileSha256()).isEqualTo(FILE_SHA);
            assertThat(evidence.parseResultId()).isEqualTo(result.assessment().parseResultId());
            assertThat(evidence.parseVersion()).isEqualTo(1);
        });
        assertThat(support.assessments.findById(result.assessment().assessmentId())).contains(result.assessment());
        assertThat(support.evidence.findByAssessmentId(result.assessment().assessmentId())).isEqualTo(result.evidence());
        assertThat(support.file.get()).isEqualTo(before);
        assertThat(support.audits).extracting(OperationAuditEvent::eventType)
                .containsExactly("PROJECT_FILE_ROLE_ASSESSMENT_STARTED", "PROJECT_FILE_ROLE_ASSESSMENT_COMPLETED");
        var idempotency = support.idempotency.findByScopeAndKey("ROLE_ASSESSMENT", "role-key").orElseThrow();
        assertThat(idempotency.resultResourceType()).isEqualTo("PROJECT_FILE_ROLE_ASSESSMENT");
        assertThat(idempotency.resultResourceId()).isEqualTo(result.assessment().assessmentId().toString());
    }

    @Test
    void exactFingerprintBindsActorFileParseAndServerPolicyVersions() {
        Support support = Support.current();

        support.service.assess(command("fingerprint-key"));

        String fingerprint = support.idempotency.findByScopeAndKey("ROLE_ASSESSMENT", "fingerprint-key")
                .orElseThrow().requestFingerprint();
        assertThat(fingerprint).isEqualTo("419d7f8f9b1d93fc4a75ba070d1ed527d2b324a11e7d26e3d9b1bc7b5de33924");
    }

    @Test
    void sameKeyAndFingerprintReplaysSameAssessmentAndReopenUsesDurableRepositories() {
        Support support = Support.current();
        RoleAssessmentResult first = support.service.assess(command("same-key"));

        RoleAssessmentService restarted = support.newService();
        RoleAssessmentResult replay = restarted.assess(command("same-key"));
        RoleAssessmentResult reopened = restarted.get(PROJECT, FILE, first.assessment().assessmentId());

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.assessment().assessmentId()).isEqualTo(first.assessment().assessmentId());
        assertThat(replay.evidence()).isEqualTo(first.evidence());
        assertThat(reopened.replayed()).isFalse();
        assertThat(reopened.assessment()).isEqualTo(first.assessment());
        assertThat(reopened.evidence()).isEqualTo(first.evidence());
        assertThat(support.audits).hasSize(2);
    }

    @Test
    void sameKeyLiveRunningRequestReopensWithoutPreemptionOrComputation() {
        AtomicInteger computations = new AtomicInteger();
        Support support = Support.current(new InMemoryProjectFileRoleEvidenceRepository(), parse -> {
            computations.incrementAndGet();
            return new DeterministicDocumentRoleResolver().resolve(new DocumentRoleSignalExtractor().extract(parse));
        });
        ProjectFileParseResult parse = support.parseResults.findLatestByFileId(FILE).orElseThrow();
        String fingerprint = RoleAssessmentService.fingerprint(support.actor, command("running-key"), parse);
        var start = support.transactions.start(command("running-key"), support.actor, parse, fingerprint,
                UUID.randomUUID(), NOW);
        var running = support.transactions.markRunning(start, NOW);

        RoleAssessmentResult replay = support.service.assess(command("running-key"));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.assessment()).isEqualTo(running.assessment());
        assertThat(computations).hasValue(0);
        assertThat(support.assessments.transitions).containsExactly(
                RoleAssessmentExecutionStatus.PENDING, RoleAssessmentExecutionStatus.RUNNING);
        assertThat(support.audits).extracting(OperationAuditEvent::eventType).containsExactly(
                "PROJECT_FILE_ROLE_ASSESSMENT_STARTED");
    }

    @Test
    void concurrentSameKeyRetryDoesNotPreemptLiveRunningComputation() throws Exception {
        CountDownLatch computeEntered = new CountDownLatch(1);
        CountDownLatch releaseCompute = new CountDownLatch(1);
        AtomicInteger computations = new AtomicInteger();
        Support support = Support.current(new InMemoryProjectFileRoleEvidenceRepository(), parse -> {
            computations.incrementAndGet();
            computeEntered.countDown();
            try {
                if (!releaseCompute.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timeout");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return new DeterministicDocumentRoleResolver().resolve(new DocumentRoleSignalExtractor().extract(parse));
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> support.service.assess(command("live-compute-key")));
            assertThat(computeEntered.await(5, TimeUnit.SECONDS)).isTrue();

            RoleAssessmentResult live = support.service.assess(command("live-compute-key"));

            assertThat(live.replayed()).isTrue();
            assertThat(live.assessment().executionStatus()).isEqualTo(RoleAssessmentExecutionStatus.RUNNING);
            assertThat(live.assessment().attemptNo()).isEqualTo(1);
            assertThat(computations).hasValue(1);
            releaseCompute.countDown();
            RoleAssessmentResult completed = first.get(5, TimeUnit.SECONDS);
            assertThat(completed.assessment().assessmentId()).isEqualTo(live.assessment().assessmentId());
            assertThat(completed.assessment().executionStatus()).isEqualTo(RoleAssessmentExecutionStatus.COMPLETED);
        } finally {
            releaseCompute.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void sameKeyRunningRequestTakesOverAtExactConfiguredExpiry() {
        Support support = Support.current();
        ProjectFileParseResult parse = support.parseResults.findLatestByFileId(FILE).orElseThrow();
        String fingerprint = RoleAssessmentService.fingerprint(support.actor, command("expired-running-key"), parse);
        var start = support.transactions.start(command("expired-running-key"), support.actor, parse, fingerprint,
                UUID.randomUUID(), NOW);
        var running = support.transactions.markRunning(start, NOW);

        RoleAssessmentService atExactExpiry = support.newService(Clock.fixed(
                NOW.plus(RoleAssessmentRecoveryPolicy.defaults().runningRecoveryAge()), ZoneOffset.UTC));
        RoleAssessmentResult recovered = atExactExpiry.assess(command("expired-running-key"));

        assertThat(recovered.assessment().assessmentId()).isEqualTo(running.assessment().assessmentId());
        assertThat(recovered.assessment().executionStatus()).isEqualTo(RoleAssessmentExecutionStatus.COMPLETED);
        assertThat(recovered.assessment().attemptNo()).isEqualTo(2);
        assertThat(support.assessments.transitions).containsExactly(
                RoleAssessmentExecutionStatus.PENDING, RoleAssessmentExecutionStatus.RUNNING,
                RoleAssessmentExecutionStatus.UNKNOWN, RoleAssessmentExecutionStatus.RUNNING,
                RoleAssessmentExecutionStatus.COMPLETED);
    }

    @Test
    void processRestartWithAdvancedClockRecoversExpiredRunningAssessment() {
        Support support = Support.current();
        ProjectFileParseResult parse = support.parseResults.findLatestByFileId(FILE).orElseThrow();
        String fingerprint = RoleAssessmentService.fingerprint(support.actor, command("restart-running-key"), parse);
        var start = support.transactions.start(command("restart-running-key"), support.actor, parse, fingerprint,
                UUID.randomUUID(), NOW);
        var running = support.transactions.markRunning(start, NOW);

        RoleAssessmentService restarted = support.newService(Clock.fixed(NOW.plus(Duration.ofHours(1)), ZoneOffset.UTC));
        RoleAssessmentResult recovered = restarted.assess(command("restart-running-key"));

        assertThat(recovered.assessment().assessmentId()).isEqualTo(running.assessment().assessmentId());
        assertThat(recovered.assessment().attemptNo()).isEqualTo(2);
        assertThat(recovered.assessment().executionStatus()).isEqualTo(RoleAssessmentExecutionStatus.COMPLETED);
    }

    @Test
    void recoveryPolicyRequiresPositiveOperationalLeaseAge() {
        assertThat(RoleAssessmentRecoveryPolicy.defaults().runningRecoveryAge()).isEqualTo(Duration.ofMinutes(15));
        assertThat(new RoleAssessmentRecoveryPolicy(Duration.ofSeconds(30)).runningRecoveryAge())
                .isEqualTo(Duration.ofSeconds(30));
        assertThatThrownBy(() -> new RoleAssessmentRecoveryPolicy(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
        assertThatThrownBy(() -> new RoleAssessmentRecoveryPolicy(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
    }

    @Test
    void sameKeyUnknownRecoveryReusesAssessmentIdentityAndIncrementsAttempt() {
        Support support = Support.current();
        ProjectFileParseResult parse = support.parseResults.findLatestByFileId(FILE).orElseThrow();
        String fingerprint = RoleAssessmentService.fingerprint(support.actor, command("unknown-key"), parse);
        var start = support.transactions.start(command("unknown-key"), support.actor, parse, fingerprint,
                UUID.randomUUID(), NOW);
        var running = support.transactions.markRunning(start, NOW);
        var unknown = running.assessment().finishWithoutProposal(RoleAssessmentExecutionStatus.UNKNOWN,
                "OUTCOME_UNCERTAIN", NOW);
        assertThat(support.assessments.compareAndSet(running.assessment(), unknown)).isTrue();

        RoleAssessmentResult recovered = support.service.assess(command("unknown-key"));

        assertThat(recovered.assessment().assessmentId()).isEqualTo(unknown.assessmentId());
        assertThat(recovered.assessment().executionStatus()).isEqualTo(RoleAssessmentExecutionStatus.COMPLETED);
        assertThat(recovered.assessment().attemptNo()).isEqualTo(2);
        assertThat(support.assessments.transitions).containsExactly(
                RoleAssessmentExecutionStatus.PENDING, RoleAssessmentExecutionStatus.RUNNING,
                RoleAssessmentExecutionStatus.UNKNOWN, RoleAssessmentExecutionStatus.RUNNING,
                RoleAssessmentExecutionStatus.COMPLETED);
    }

    @Test
    void differentKeyCannotBypassActiveAssessmentForSameImmutableFingerprint() {
        Support support = Support.current();
        ProjectFileParseResult parse = support.parseResults.findLatestByFileId(FILE).orElseThrow();
        RoleAssessmentCommand original = command("active-key");
        String fingerprint = RoleAssessmentService.fingerprint(support.actor, original, parse);
        var active = support.transactions.start(original, support.actor, parse, fingerprint,
                UUID.randomUUID(), NOW);

        assertThatThrownBy(() -> support.service.assess(command("replacement-key")))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(BusinessErrorCode.CONFLICT));
        assertThat(support.assessments.findLatestByFileId(FILE)).contains(active.assessment());
        assertThat(support.assessments.transitions).containsExactly(RoleAssessmentExecutionStatus.PENDING);
        assertThat(support.idempotency.findByScopeAndKey("ROLE_ASSESSMENT", "replacement-key")).isEmpty();
    }

    @Test
    void differentKeyCannotBypassRunningAssessmentForSameImmutableFingerprint() {
        Support support = Support.current();
        ProjectFileParseResult parse = support.parseResults.findLatestByFileId(FILE).orElseThrow();
        RoleAssessmentCommand original = command("running-active-key");
        String fingerprint = RoleAssessmentService.fingerprint(support.actor, original, parse);
        var pending = support.transactions.start(original, support.actor, parse, fingerprint, UUID.randomUUID(), NOW);
        var running = support.transactions.markRunning(pending, NOW);

        assertThatThrownBy(() -> support.service.assess(command("running-replacement-key")))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(BusinessErrorCode.CONFLICT));
        assertThat(support.assessments.findLatestByFileId(FILE)).contains(running.assessment());
    }

    @Test
    void differentKeyCannotBypassUnknownAssessmentForSameImmutableFingerprint() {
        Support support = Support.current();
        ProjectFileParseResult parse = support.parseResults.findLatestByFileId(FILE).orElseThrow();
        RoleAssessmentCommand original = command("unknown-active-key");
        String fingerprint = RoleAssessmentService.fingerprint(support.actor, original, parse);
        var pending = support.transactions.start(original, support.actor, parse, fingerprint, UUID.randomUUID(), NOW);
        var running = support.transactions.markRunning(pending, NOW);
        var unknown = running.assessment().finishWithoutProposal(RoleAssessmentExecutionStatus.UNKNOWN,
                "OUTCOME_UNCERTAIN", NOW);
        assertThat(support.assessments.compareAndSet(running.assessment(), unknown)).isTrue();

        assertThatThrownBy(() -> support.service.assess(command("unknown-replacement-key")))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(BusinessErrorCode.CONFLICT));
        assertThat(support.assessments.findLatestByFileId(FILE)).contains(unknown);
        assertThat(support.idempotency.findByScopeAndKey("ROLE_ASSESSMENT", "unknown-replacement-key")).isEmpty();
    }

    @Test
    void concurrentDistinctKeysPublishExactlyOneActiveAssessmentAndRollbackLoserClaim() throws Exception {
        Support support = Support.current();
        ProjectFileParseResult parse = support.parseResults.findLatestByFileId(FILE).orElseThrow();
        RoleAssessmentCommand first = command("concurrent-key-a");
        RoleAssessmentCommand second = command("concurrent-key-b");
        String fingerprint = RoleAssessmentService.fingerprint(support.actor, first, parse);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var attempts = List.of(first, second).stream().map(command -> executor.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    return support.transactions.start(command, support.actor, parse, fingerprint,
                            UUID.randomUUID(), NOW).assessment();
                } catch (BusinessException conflict) {
                    assertThat(conflict.errorCode()).isEqualTo(BusinessErrorCode.CONFLICT);
                    return null;
                }
            })).toList();
            ready.await();
            go.countDown();
            var results = attempts.stream().map(value -> {
                try { return value.get(); } catch (Exception e) { throw new AssertionError(e); }
            }).toList();

            assertThat(results).filteredOn(java.util.Objects::nonNull).hasSize(1);
            assertThat(support.assessments.transitions).containsExactly(RoleAssessmentExecutionStatus.PENDING);
            long durableClaims = List.of("concurrent-key-a", "concurrent-key-b").stream()
                    .filter(key -> support.idempotency.findByScopeAndKey("ROLE_ASSESSMENT", key).isPresent())
                    .count();
            assertThat(durableClaims).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sameKeyWithChangedFileAndParseIdentityConflictsWithoutNewAssessment() {
        Support support = Support.current();
        RoleAssessmentResult first = support.service.assess(command("stable-key"));
        String changedText = TEXT + "\n第四条 争议解决";
        String changedSha = "b".repeat(64);
        support.file.set(copyFile(support.file.get(), changedSha, changedText));
        support.parseResults.insertIfAbsent(parsed(UUID.randomUUID(), changedSha, changedText, 2));

        assertThatThrownBy(() -> support.service.assess(command("stable-key")))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(BusinessErrorCode.CONFLICT))
                .hasMessageContaining("fingerprint");
        assertThat(support.assessments.findLatestByFileId(FILE)).contains(first.assessment());
        assertThat(support.audits).hasSize(2);
    }

    @Test
    void completePersistenceFailureBecomesUnknownWithoutPartialEvidenceOrFileMutation() {
        Support support = Support.current(failingEvidenceRepository());
        ProjectFile before = support.file.get();

        RoleAssessmentResult outcome = support.service.assess(command("failure-key"));

        var unknown = support.assessments.findLatestByFileId(FILE).orElseThrow();
        assertThat(outcome.assessment()).isEqualTo(unknown);
        assertThat(unknown.executionStatus()).isEqualTo(RoleAssessmentExecutionStatus.UNKNOWN);
        assertThat(support.assessments.transitions).containsExactly(
                RoleAssessmentExecutionStatus.PENDING,
                RoleAssessmentExecutionStatus.RUNNING,
                RoleAssessmentExecutionStatus.UNKNOWN);
        assertThat(unknown.safeReasonCode()).isEqualTo("ROLE_ASSESSMENT_COMPLETE_OUTCOME_UNKNOWN");
        assertThat(support.idempotency.findByScopeAndKey("ROLE_ASSESSMENT", "failure-key").orElseThrow().status().name())
                .isEqualTo("IN_PROGRESS");
        assertThat(support.audits).extracting(OperationAuditEvent::eventType)
                .containsExactly("PROJECT_FILE_ROLE_ASSESSMENT_STARTED", "PROJECT_FILE_ROLE_ASSESSMENT_UNKNOWN");
        assertThat(support.evidence.findByAssessmentId(unknown.assessmentId())).isEmpty();
        assertThat(support.file.get()).isEqualTo(before);
    }

    @Test
    void sameKeyRetriesUnknownAfterAtomicEvidenceBatchFailureBeforePublication() {
        InMemoryProjectFileRoleEvidenceRepository durable = new InMemoryProjectFileRoleEvidenceRepository();
        AtomicBoolean failFirstBatch = new AtomicBoolean(true);
        ProjectFileRoleEvidenceRepository stagedFailure = new ProjectFileRoleEvidenceRepository() {
            @Override public ProjectFileRoleEvidence insertIfAbsent(ProjectFileRoleEvidence item) {
                return durable.insertIfAbsent(item);
            }
            @Override public List<ProjectFileRoleEvidence> insertBatchIfAbsent(List<ProjectFileRoleEvidence> items) {
                if (failFirstBatch.getAndSet(false)) {
                    assertThat(items).hasSizeGreaterThan(1);
                    throw new IllegalStateException("synthetic staged evidence validation failure");
                }
                return durable.insertBatchIfAbsent(items);
            }
            @Override public Optional<ProjectFileRoleEvidence> findById(UUID id) { return durable.findById(id); }
            @Override public List<ProjectFileRoleEvidence> findByAssessmentId(UUID id) {
                return durable.findByAssessmentId(id);
            }
        };
        Support support = Support.current(stagedFailure);

        RoleAssessmentResult unknown = support.service.assess(command("staged-evidence-key"));
        assertThat(unknown.assessment().executionStatus()).isEqualTo(RoleAssessmentExecutionStatus.UNKNOWN);
        assertThat(durable.findByAssessmentId(unknown.assessment().assessmentId())).isEmpty();

        RoleAssessmentResult recovered = support.service.assess(command("staged-evidence-key"));
        assertThat(recovered.assessment().assessmentId()).isEqualTo(unknown.assessment().assessmentId());
        assertThat(recovered.assessment().executionStatus()).isEqualTo(RoleAssessmentExecutionStatus.COMPLETED);
        assertThat(recovered.assessment().attemptNo()).isEqualTo(2);
        assertThat(durable.findByAssessmentId(recovered.assessment().assessmentId())).isNotEmpty();
    }

    @Test
    void downstreamCasFailureWithAdvancedClockReusesAttemptIndependentEvidenceAndCompletes() {
        Support support = Support.current();
        support.assessments.failNextCompletionCas();

        RoleAssessmentResult unknown = support.service.assess(command("downstream-cas-key"));

        assertThat(unknown.assessment().executionStatus()).isEqualTo(RoleAssessmentExecutionStatus.UNKNOWN);
        assertThat(unknown.evidence()).isEmpty();
        assertThat(support.evidence.findByAssessmentId(unknown.assessment().assessmentId())).isNotEmpty();

        RoleAssessmentService restarted = support.newService(Clock.fixed(NOW.plus(Duration.ofHours(1)), ZoneOffset.UTC));
        RoleAssessmentResult recovered = restarted.assess(command("downstream-cas-key"));
        assertThat(recovered.assessment().assessmentId()).isEqualTo(unknown.assessment().assessmentId());
        assertThat(recovered.assessment().executionStatus()).isEqualTo(RoleAssessmentExecutionStatus.COMPLETED);
        assertThat(recovered.evidence()).isNotEmpty();
        assertThat(recovered.evidence()).allSatisfy(item -> assertThat(item.createdAt()).isEqualTo(NOW));
    }

    @Test
    void sameKeyCannotAdvanceLocallyStagedPendingBeforeStartAuditCommits() throws Exception {
        CountDownLatch auditEntered = new CountDownLatch(1);
        CountDownLatch releaseAudit = new CountDownLatch(1);
        AtomicBoolean blockFirstStartAudit = new AtomicBoolean(true);
        List<OperationAuditEvent> durableAudits = new java.util.concurrent.CopyOnWriteArrayList<>();
        OperationAuditPort latchedAudit = event -> {
            if (event.eventType().equals("PROJECT_FILE_ROLE_ASSESSMENT_STARTED")
                    && blockFirstStartAudit.compareAndSet(true, false)) {
                auditEntered.countDown();
                try {
                    if (!releaseAudit.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timeout");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
            durableAudits.add(event);
        };
        Support support = Support.current(new InMemoryProjectFileRoleEvidenceRepository(), null, latchedAudit, null);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> support.service.assess(command("staged-start-key")));
            assertThat(auditEntered.await(5, TimeUnit.SECONDS)).isTrue();

            Throwable retryFailure = catchThrowable(() -> support.service.assess(command("staged-start-key")));
            releaseAudit.countDown();
            RoleAssessmentResult completed = first.get(5, TimeUnit.SECONDS);

            assertThat(retryFailure).isInstanceOfSatisfying(BusinessException.class,
                    failure -> assertThat(failure.errorCode()).isEqualTo(BusinessErrorCode.CONFLICT));
            assertThat(completed.assessment().executionStatus()).isEqualTo(RoleAssessmentExecutionStatus.COMPLETED);
            assertThat(support.idempotency.findByScopeAndKey("ROLE_ASSESSMENT", "staged-start-key").orElseThrow()
                    .status().name()).isEqualTo("SUCCEEDED");
            assertThat(durableAudits).extracting(OperationAuditEvent::eventType).containsExactly(
                    "PROJECT_FILE_ROLE_ASSESSMENT_STARTED", "PROJECT_FILE_ROLE_ASSESSMENT_COMPLETED");
        } finally {
            releaseAudit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void deterministicComputeFailureIsProvenFailedAndSameKeyReplaysFailedIdentity() {
        RuntimeException computeFailure = new IllegalStateException("synthetic deterministic failure");
        Support support = Support.current(new InMemoryProjectFileRoleEvidenceRepository(), parse -> {
            throw computeFailure;
        });

        assertThatThrownBy(() -> support.service.assess(command("compute-failure-key"))).isSameAs(computeFailure);
        var failed = support.assessments.findLatestByFileId(FILE).orElseThrow();
        int auditCount = support.audits.size();

        RoleAssessmentResult replay = support.service.assess(command("compute-failure-key"));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.assessment()).isEqualTo(failed);
        assertThat(replay.assessment().executionStatus()).isEqualTo(RoleAssessmentExecutionStatus.FAILED);
        assertThat(support.audits).hasSize(auditCount);
        assertThat(support.assessments.transitions).containsExactly(
                RoleAssessmentExecutionStatus.PENDING, RoleAssessmentExecutionStatus.RUNNING,
                RoleAssessmentExecutionStatus.FAILED);
    }

    @Test
    void newKeyMayRetryAfterProvenComputeFailure() {
        AtomicBoolean failFirst = new AtomicBoolean(true);
        Support support = Support.current(new InMemoryProjectFileRoleEvidenceRepository(), parse -> {
            if (failFirst.getAndSet(false)) throw new IllegalStateException("first compute failed");
            return new DeterministicDocumentRoleResolver().resolve(new DocumentRoleSignalExtractor().extract(parse));
        });
        assertThatThrownBy(() -> support.service.assess(command("failed-attempt-key")))
                .isInstanceOf(IllegalStateException.class);

        RoleAssessmentResult retry = support.service.assess(command("explicit-retry-key"));

        assertThat(retry.assessment().executionStatus()).isEqualTo(RoleAssessmentExecutionStatus.COMPLETED);
        assertThat(support.idempotency.findByScopeAndKey("ROLE_ASSESSMENT", "failed-attempt-key").orElseThrow()
                .status().name()).isEqualTo("FAILED");
        assertThat(retry.assessment().assessmentId()).isNotEqualTo(
                UUID.fromString(support.idempotency.findByScopeAndKey(
                        "ROLE_ASSESSMENT", "failed-attempt-key").orElseThrow().resultResourceId()));
    }

    @Test
    void startAuditFailureCompensatesLocalPendingFenceAndIdempotencyClaim() {
        AtomicBoolean failFirstAudit = new AtomicBoolean(true);
        Support support = Support.current(new InMemoryProjectFileRoleEvidenceRepository(), null,
                event -> {
                    if (failFirstAudit.getAndSet(false)) throw new IllegalStateException("synthetic audit outage");
                }, null);

        assertThatThrownBy(() -> support.service.assess(command("audit-failure-key")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("audit outage");

        assertThat(support.assessments.findLatestByFileId(FILE)).isEmpty();
        assertThat(support.idempotency.findByScopeAndKey("ROLE_ASSESSMENT", "audit-failure-key")).isEmpty();
        assertThat(support.service.assess(command("audit-failure-key"))
                .assessment().executionStatus()).isEqualTo(RoleAssessmentExecutionStatus.COMPLETED);
    }

    @Test
    void committedCompleteExceptionReconcilesDurableSuccess() {
        InMemoryIdempotencyAdapter durable = new InMemoryIdempotencyAdapter();
        IdempotencyPort throwsAfterCommit = new IdempotencyPort() {
            @Override public IdempotencyClaim claim(IdempotencyRequest request) { return durable.claim(request); }
            @Override public IdempotencyRecord markSucceeded(IdempotencyRecord record, String type, String id,
                                                               Instant at) {
                durable.markSucceeded(record, type, id, at);
                throw new IllegalStateException("synthetic response loss after commit");
            }
            @Override public IdempotencyRecord markFailed(IdempotencyRecord record, Instant at) {
                return durable.markFailed(record, at);
            }
            @Override public Optional<IdempotencyRecord> findByScopeAndKey(String scope, String key) {
                return durable.findByScopeAndKey(scope, key);
            }
            @Override public void rollbackUncommittedClaim(IdempotencyRecord record) {
                durable.rollbackUncommittedClaim(record);
            }
        };
        Support support = Support.current(new InMemoryProjectFileRoleEvidenceRepository(), null, null,
                throwsAfterCommit);

        RoleAssessmentResult result = support.service.assess(command("complete-response-loss"));

        assertThat(result.assessment().executionStatus()).isEqualTo(RoleAssessmentExecutionStatus.COMPLETED);
        assertThat(durable.findByScopeAndKey("ROLE_ASSESSMENT", "complete-response-loss").orElseThrow()
                .status().name()).isEqualTo("SUCCEEDED");
    }

    @Test
    void completionAuditFailureRequiresReconciliationAuditBeforeIdempotencySuccess() {
        AtomicBoolean failCompletionAudit = new AtomicBoolean(true);
        List<OperationAuditEvent> durableAudit = new ArrayList<>();
        OperationAuditPort audit = event -> {
            if (event.eventType().equals("PROJECT_FILE_ROLE_ASSESSMENT_COMPLETED")
                    && failCompletionAudit.getAndSet(false)) {
                throw new IllegalStateException("synthetic completion audit outage");
            }
            durableAudit.add(event);
        };
        Support support = Support.current(new InMemoryProjectFileRoleEvidenceRepository(), null, audit, null);

        RoleAssessmentResult result = support.service.assess(command("completion-audit-key"));

        assertThat(result.assessment().executionStatus()).isEqualTo(RoleAssessmentExecutionStatus.COMPLETED);
        assertThat(durableAudit).extracting(OperationAuditEvent::eventType).containsExactly(
                "PROJECT_FILE_ROLE_ASSESSMENT_STARTED",
                "PROJECT_FILE_ROLE_ASSESSMENT_COMPLETION_RECONCILED");
        assertThat(support.idempotency.findByScopeAndKey("ROLE_ASSESSMENT", "completion-audit-key")
                .orElseThrow().status().name()).isEqualTo("SUCCEEDED");
    }

    @Test
    void unreadableCompletionStateFailsClosedAndPreservesRunningOperationIdentity() {
        InMemoryIdempotencyAdapter durable = new InMemoryIdempotencyAdapter();
        IdempotencyPort unreadable = new IdempotencyPort() {
            @Override public IdempotencyClaim claim(IdempotencyRequest request) { return durable.claim(request); }
            @Override public IdempotencyRecord markSucceeded(IdempotencyRecord record, String type, String id,
                                                               Instant at) {
                return durable.markSucceeded(record, type, id, at);
            }
            @Override public IdempotencyRecord markFailed(IdempotencyRecord record, Instant at) {
                return durable.markFailed(record, at);
            }
            @Override public Optional<IdempotencyRecord> findByScopeAndKey(String scope, String key) {
                return Optional.empty();
            }
            @Override public void rollbackUncommittedClaim(IdempotencyRecord record) {
                durable.rollbackUncommittedClaim(record);
            }
        };
        Support support = Support.current(failingEvidenceRepository(), null, null, unreadable);

        assertThatThrownBy(() -> support.service.assess(command("unreadable-complete-key")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("synthetic evidence outage")
                .satisfies(failure -> assertThat(failure.getSuppressed()).anySatisfy(suppressed ->
                        assertThat(suppressed).hasMessageContaining("outcome cannot be proven")));
        var running = support.assessments.findLatestByFileId(FILE).orElseThrow();
        assertThat(running.executionStatus()).isEqualTo(RoleAssessmentExecutionStatus.RUNNING);
        assertThat(durable.findByScopeAndKey("ROLE_ASSESSMENT", "unreadable-complete-key").orElseThrow()
                .resultResourceId()).isEqualTo(running.assessmentId().toString());
    }

    private static RoleAssessmentCommand command(String key) {
        return new RoleAssessmentCommand(PROJECT, FILE, key, META);
    }

    private static ProjectFileRoleEvidenceRepository failingEvidenceRepository() {
        return new ProjectFileRoleEvidenceRepository() {
            @Override public ProjectFileRoleEvidence insertIfAbsent(ProjectFileRoleEvidence evidence) {
                throw new IllegalStateException("synthetic evidence outage");
            }
            @Override public Optional<ProjectFileRoleEvidence> findById(UUID evidenceId) { return Optional.empty(); }
            @Override public List<ProjectFileRoleEvidence> findByAssessmentId(UUID assessmentId) { return List.of(); }
        };
    }

    private static final class Support {
        final AtomicReference<ProjectFile> file;
        final ProjectFileRepository files;
        final InMemoryProjectFileParseResultRepository parseResults;
        final TrackingAssessments assessments = new TrackingAssessments();
        final ProjectFileRoleEvidenceRepository evidence;
        final InMemoryIdempotencyAdapter idempotency = new InMemoryIdempotencyAdapter();
        final List<OperationAuditEvent> audits = new ArrayList<>();
        final CurrentUser actor = new CurrentUser("actor-10", "auditor", "Auditor", Set.of("AUDITOR"));
        final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        final FlowDebugLogger flow = new FlowDebugLogger(new ObservabilityProperties());
        final RoleAssessmentTransactionCoordinator transactions;
        final RoleAssessmentService service;

        private final Function<ProjectFileParseResult, com.fuquan.audit.contract.domain.project.DeterministicDocumentRoleResult>
                computation;
        private Support(boolean existingParse, ProjectFileRoleEvidenceRepository evidence,
                        Function<ProjectFileParseResult,
                                com.fuquan.audit.contract.domain.project.DeterministicDocumentRoleResult> computation,
                        OperationAuditPort auditPort, IdempotencyPort idempotencyPort) {
            file = new AtomicReference<>(file());
            files = files(file);
            parseResults = new InMemoryProjectFileParseResultRepository();
            if (existingParse) parseResults.insertIfAbsent(parsed(PARSE, FILE_SHA, TEXT, 1));
            this.evidence = evidence;
            this.computation = computation;
            transactions = new RoleAssessmentTransactionCoordinator(
                    assessments, evidence, idempotencyPort == null ? idempotency : idempotencyPort,
                    auditPort == null ? audits::add : auditPort, RoleAssessmentRecoveryPolicy.defaults(), flow);
            service = newService();
        }

        static Support legacy() { return new Support(false, new InMemoryProjectFileRoleEvidenceRepository(), null, null, null); }
        static Support current() { return new Support(true, new InMemoryProjectFileRoleEvidenceRepository(), null, null, null); }
        static Support current(ProjectFileRoleEvidenceRepository evidence) { return new Support(true, evidence, null, null, null); }
        static Support current(ProjectFileRoleEvidenceRepository evidence,
                               Function<ProjectFileParseResult,
                                       com.fuquan.audit.contract.domain.project.DeterministicDocumentRoleResult> computation) {
            return new Support(true, evidence, computation, null, null);
        }
        static Support current(ProjectFileRoleEvidenceRepository evidence,
                               Function<ProjectFileParseResult,
                                       com.fuquan.audit.contract.domain.project.DeterministicDocumentRoleResult> computation,
                               OperationAuditPort auditPort, IdempotencyPort idempotencyPort) {
            return new Support(true, evidence, computation, auditPort, idempotencyPort);
        }

        RoleAssessmentService newService() {
            return newService(clock);
        }

        RoleAssessmentService newService(Clock serviceClock) {
            LegacyProjectFileParseResultMaterializer materializer =
                    new LegacyProjectFileParseResultMaterializer(files, parseResults);
            return computation == null
                    ? new RoleAssessmentService(files, parseResults, materializer, transactions,
                    () -> Optional.of(actor), serviceClock, flow)
                    : new RoleAssessmentService(files, parseResults, materializer, transactions,
                    () -> Optional.of(actor), serviceClock, flow, computation);
        }
    }

    private static final class TrackingAssessments implements ProjectFileRoleAssessmentRepository {
        private final InMemoryProjectFileRoleAssessmentRepository delegate =
                new InMemoryProjectFileRoleAssessmentRepository();
        private final List<RoleAssessmentExecutionStatus> transitions = new ArrayList<>();
        private final AtomicBoolean failCompletionCas = new AtomicBoolean();

        void failNextCompletionCas() { failCompletionCas.set(true); }

        @Override public com.fuquan.audit.contract.domain.project.ProjectFileRoleAssessment insertIfAbsent(
                com.fuquan.audit.contract.domain.project.ProjectFileRoleAssessment assessment) {
            var stored = delegate.insertIfAbsent(assessment);
            if (stored.assessmentId().equals(assessment.assessmentId())) {
                transitions.add(stored.executionStatus());
            }
            return stored;
        }
        @Override public com.fuquan.audit.contract.domain.project.ProjectFileRoleAssessment stagePendingInsertIfAbsent(
                com.fuquan.audit.contract.domain.project.ProjectFileRoleAssessment assessment) {
            var stored = delegate.stagePendingInsertIfAbsent(assessment);
            if (stored.assessmentId().equals(assessment.assessmentId())) {
                transitions.add(stored.executionStatus());
            }
            return stored;
        }
        @Override public void commitStagedInsert(
                com.fuquan.audit.contract.domain.project.ProjectFileRoleAssessment assessment) {
            delegate.commitStagedInsert(assessment);
        }
        @Override public boolean compareAndSet(
                com.fuquan.audit.contract.domain.project.ProjectFileRoleAssessment expected,
                com.fuquan.audit.contract.domain.project.ProjectFileRoleAssessment updated) {
            if (updated.executionStatus() == RoleAssessmentExecutionStatus.COMPLETED
                    && failCompletionCas.compareAndSet(true, false)) return false;
            boolean changed = delegate.compareAndSet(expected, updated);
            if (changed) transitions.add(updated.executionStatus());
            return changed;
        }
        @Override public Optional<com.fuquan.audit.contract.domain.project.ProjectFileRoleAssessment> findById(UUID id) {
            return delegate.findById(id);
        }
        @Override public Optional<com.fuquan.audit.contract.domain.project.ProjectFileRoleAssessment> findLatestByFileId(
                UUID fileId) {
            return delegate.findLatestByFileId(fileId);
        }
        @Override public void rollbackUncommittedInsert(
                com.fuquan.audit.contract.domain.project.ProjectFileRoleAssessment assessment) {
            delegate.rollbackUncommittedInsert(assessment);
            transitions.remove(assessment.executionStatus());
        }
    }

    private static ProjectFileRepository files(AtomicReference<ProjectFile> value) {
        return new ProjectFileRepository() {
            @Override public void insert(ProjectFile file) { value.set(file); }
            @Override public void update(ProjectFile file) { value.set(file); }
            @Override public Optional<ProjectFile> findByProjectIdAndId(UUID projectId, UUID id) {
                return Optional.ofNullable(value.get()).filter(file -> file.projectId().equals(projectId)
                        && file.id().equals(id));
            }
            @Override public Optional<ProjectFile> findByProjectIdAndParentArchiveFileIdAndRelativePath(
                    UUID projectId, UUID parentArchiveFileId, String normalizedRelativePath) { return Optional.empty(); }
            @Override public List<ProjectFile> findByProjectId(UUID projectId) { return List.of(value.get()); }
        };
    }

    private static ProjectFile file() {
        ProjectFileParseProvenance provenance = new ProjectFileParseProvenance(
                ProjectFileParseMethod.PDF_TEXT, "synthetic-parser", BigDecimal.ONE, 1, 5L);
        return new ProjectFile(FILE, PROJECT, null, null, "001.pdf", "application/pdf",
                ProjectFileCategory.UNKNOWN, ProjectFileIngestionStatus.AVAILABLE, ProjectFileParseStatus.PARSED,
                FILE_SHA, FILE_SHA, 512, TEXT, null, NOW.minusSeconds(2), NOW.minusSeconds(1), provenance);
    }

    private static ProjectFile copyFile(ProjectFile value, String sha, String text) {
        return new ProjectFile(value.id(), value.projectId(), value.parentArchiveFileId(), value.normalizedRelativePath(),
                value.originalFilename(), value.mediaType(), value.category(), value.ingestionStatus(), value.parseStatus(),
                sha, sha, value.sizeBytes(), text, value.safeReasonCode(), value.createdAt(), value.updatedAt(),
                value.parseProvenance());
    }

    private static ProjectFileParseResult parsed(UUID id, String sha, String text, int version) {
        return new ProjectFileParseResult(id, PROJECT, FILE, sha, version, ProjectFileParseOrigin.CURRENT_PIPELINE,
                ProjectFileParseStatus.PARSED, ProjectFileParseMethod.PDF_TEXT, "synthetic-parser", "1", text,
                sha256(text), 1, 5L, BigDecimal.ONE, version == 1 ? null : PARSE,
                NOW.minusSeconds(2), NOW.minusSeconds(1), 1);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
