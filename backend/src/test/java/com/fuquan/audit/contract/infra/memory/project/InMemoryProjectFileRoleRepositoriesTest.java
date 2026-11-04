package com.fuquan.audit.contract.infra.memory.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.DocumentRoleSignal;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleAssessment;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleAssessmentSource;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleDecision;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleEvidence;
import com.fuquan.audit.contract.domain.project.RoleDecisionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class InMemoryProjectFileRoleRepositoriesTest {
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID FILE = UUID.randomUUID();
    private static final UUID PARSE = UUID.randomUUID();
    private static final UUID ASSESSMENT = UUID.randomUUID();
    private static final String SHA = sha("file");
    private static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");

    @Test
    void assessmentCasRejectsStaleVersionAndInputIdentityDrift() {
        InMemoryProjectFileRoleAssessmentRepository repository = new InMemoryProjectFileRoleAssessmentRepository();
        ProjectFileRoleAssessment pending = pending();
        repository.insertIfAbsent(pending);
        ProjectFileRoleAssessment running = pending.start(NOW.plusSeconds(1));

        assertThat(repository.compareAndSet(pending, running)).isTrue();
        assertThat(repository.compareAndSet(pending, running)).isFalse();
        assertThat(repository.findById(pending.assessmentId())).contains(running);
        ProjectFileRoleAssessment drifted = ProjectFileRoleAssessment.pending(pending.assessmentId(), PROJECT, FILE,
                SHA, UUID.randomUUID(), 2, ProjectFileRoleAssessmentSource.MACHINE_DETERMINISTIC,
                "role-assessment-v1", "manual-confirmation-v1", sha("different"), null, null, NOW);
        assertThatThrownBy(() -> repository.compareAndSet(running, drifted))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("input identity");
    }

    @Test
    void stagedPendingAssessmentCannotAdvanceUntilStartTransactionCommitsIt() {
        InMemoryProjectFileRoleAssessmentRepository repository = new InMemoryProjectFileRoleAssessmentRepository();
        ProjectFileRoleAssessment pending = pending();
        ProjectFileRoleAssessment running = pending.start(NOW.plusSeconds(1));

        assertThat(repository.stagePendingInsertIfAbsent(pending)).isSameAs(pending);
        assertThat(repository.compareAndSet(pending, running)).isFalse();
        assertThat(repository.findById(pending.assessmentId())).contains(pending);

        repository.commitStagedInsert(pending);

        assertThat(repository.compareAndSet(pending, running)).isTrue();
        assertThat(repository.findById(pending.assessmentId())).contains(running);
    }

    @Test
    void provenFailureRetryMayCreateNewAssessmentIdentityForSameBusinessInput() {
        InMemoryProjectFileRoleAssessmentRepository repository = new InMemoryProjectFileRoleAssessmentRepository();
        ProjectFileRoleAssessment first = pending();
        repository.insertIfAbsent(first);
        ProjectFileRoleAssessment running = first.start(NOW.plusSeconds(1));
        assertThat(repository.compareAndSet(first, running)).isTrue();
        ProjectFileRoleAssessment failed = running.finishWithoutProposal(
                com.fuquan.audit.contract.domain.project.RoleAssessmentExecutionStatus.FAILED,
                "PROVEN_FAILURE", NOW.plusSeconds(2));
        assertThat(repository.compareAndSet(running, failed)).isTrue();
        ProjectFileRoleAssessment retry = ProjectFileRoleAssessment.pending(UUID.randomUUID(), first.projectId(),
                first.fileId(), first.fileSha256(), first.parseResultId(), first.parseVersion(), first.assessmentSource(),
                first.assessmentVersion(), first.rolePolicyVersion(), first.inputFingerprint(),
                first.precedingDeterministicAssessmentId(), first.modelProvenance(), NOW.plusSeconds(10));

        assertThat(repository.insertIfAbsent(retry)).isSameAs(retry);
        assertThat(repository.findById(first.assessmentId())).contains(failed);
        assertThat(repository.findById(retry.assessmentId())).contains(retry);
    }

    @Test
    void directlyInsertedTerminalAssessmentDoesNotOccupyActiveFence() {
        InMemoryProjectFileRoleAssessmentRepository repository = new InMemoryProjectFileRoleAssessmentRepository();
        ProjectFileRoleAssessment pending = pending();
        ProjectFileRoleAssessment failed = pending.start(NOW.plusSeconds(1)).finishWithoutProposal(
                com.fuquan.audit.contract.domain.project.RoleAssessmentExecutionStatus.FAILED,
                "PROVEN_FAILURE", NOW.plusSeconds(2));
        repository.insertIfAbsent(failed);
        ProjectFileRoleAssessment retry = ProjectFileRoleAssessment.pending(UUID.randomUUID(), failed.projectId(),
                failed.fileId(), failed.fileSha256(), failed.parseResultId(), failed.parseVersion(),
                failed.assessmentSource(), failed.assessmentVersion(), failed.rolePolicyVersion(),
                failed.inputFingerprint(), null, null, NOW.plusSeconds(3));

        assertThat(repository.insertIfAbsent(retry)).isSameAs(retry);
    }

    @Test
    void aiAssessmentSupersedesItsDeterministicPredecessorWhenCreationTimestampsTie() {
        InMemoryProjectFileRoleAssessmentRepository repository = new InMemoryProjectFileRoleAssessmentRepository();
        ProjectFileRoleAssessment deterministic = ProjectFileRoleAssessment.pending(
                UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"), PROJECT, FILE, SHA, PARSE, 1,
                ProjectFileRoleAssessmentSource.MACHINE_DETERMINISTIC, "role-assessment-v1",
                "manual-confirmation-v1", sha("deterministic"), null, null, NOW);
        ProjectFileRoleAssessment ai = ProjectFileRoleAssessment.pending(
                UUID.fromString("00000000-0000-0000-0000-000000000001"), PROJECT, FILE, SHA, PARSE, 1,
                ProjectFileRoleAssessmentSource.MACHINE_AI, "ai-role-assessment-v1",
                "manual-confirmation-v1", sha("ai"), deterministic.assessmentId(),
                new com.fuquan.audit.contract.domain.project.ProjectFileRoleModelProvenance(
                        "OPENAI_COMPATIBLE", "qwen-test", "NOT_DECLARED_BY_PROVIDER",
                        "document-role-prompt-v1", "document-role-v1"), NOW);

        repository.insertIfAbsent(deterministic);
        repository.insertIfAbsent(ai);

        assertThat(repository.findLatestByFileId(FILE)).contains(ai);
    }

    @Test
    void activeAssessmentFenceReturnsSameWinnerForConcurrentDistinctIds() throws Exception {
        InMemoryProjectFileRoleAssessmentRepository repository = new InMemoryProjectFileRoleAssessmentRepository();
        int contenders = 20;
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(contenders);
        try {
            var futures = IntStream.range(0, contenders).mapToObj(index -> executor.submit(() -> {
                start.await();
                ProjectFileRoleAssessment proposed = pending();
                return repository.insertIfAbsent(proposed);
            })).toList();
            start.countDown();

            var winners = futures.stream().map(value -> {
                try { return value.get(); } catch (Exception e) { throw new AssertionError(e); }
            }).toList();
            assertThat(winners).extracting(ProjectFileRoleAssessment::assessmentId).containsOnly(
                    winners.get(0).assessmentId());
            assertThat(repository.findById(winners.get(0).assessmentId())).contains(winners.get(0));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void evidenceIsImmutableParseBoundAndInsertOnly() {
        InMemoryProjectFileRoleEvidenceRepository repository = new InMemoryProjectFileRoleEvidenceRepository();
        ProjectFileRoleEvidence stored = evidence(UUID.randomUUID());
        assertThat(repository.insertIfAbsent(stored)).isSameAs(stored);
        assertThat(repository.findByAssessmentId(stored.assessmentId())).containsExactly(stored);

        ProjectFileRoleEvidence conflicting = new ProjectFileRoleEvidence(stored.evidenceId(), stored.assessmentId(),
                stored.projectId(), stored.fileId(), stored.fileSha256(), stored.parseResultId(), stored.parseVersion(),
                DocumentRole.AWARD_NOTICE, stored.signalType(), stored.normalizedValue(), stored.ruleCode(), stored.weight(),
                stored.sourceLocator(), stored.charStart(), stored.charEnd(), stored.textHash(), stored.boundedExcerpt(),
                stored.extractorVersion(), stored.createdAt());
        assertThatThrownBy(() -> repository.insertIfAbsent(conflicting)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void evidenceBatchValidationFailurePublishesNoneOfTheBatch() {
        InMemoryProjectFileRoleEvidenceRepository repository = new InMemoryProjectFileRoleEvidenceRepository();
        ProjectFileRoleEvidence occupied = evidenceForSignal(
                UUID.randomUUID(), DocumentRole.AWARD_NOTICE, "ROLE_AWARD_STRUCTURE", 20, 24);
        repository.insertIfAbsent(occupied);
        ProjectFileRoleEvidence first = evidenceForSignal(
                UUID.randomUUID(), DocumentRole.CONTRACT_BODY, "ROLE_CONTRACT_FIRST", 0, 4);
        ProjectFileRoleEvidence conflicting = new ProjectFileRoleEvidence(UUID.randomUUID(), occupied.assessmentId(),
                occupied.projectId(), occupied.fileId(), occupied.fileSha256(), occupied.parseResultId(),
                occupied.parseVersion(), occupied.role(), occupied.signalType(), "conflicting-value",
                occupied.ruleCode(), occupied.weight().add(BigDecimal.ONE), occupied.sourceLocator(),
                occupied.charStart(), occupied.charEnd(), occupied.textHash(), occupied.boundedExcerpt(),
                occupied.extractorVersion(), occupied.createdAt());

        assertThatThrownBy(() -> repository.insertBatchIfAbsent(List.of(first, conflicting)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.findById(first.evidenceId())).isEmpty();
        assertThat(repository.findByAssessmentId(ASSESSMENT)).containsExactly(occupied);
    }

    @Test
    void evidenceBusinessKeyRaceReturnsOneWinnerEvenWhenGeneratedIdsDiffer() throws Exception {
        InMemoryProjectFileRoleEvidenceRepository repository = new InMemoryProjectFileRoleEvidenceRepository();
        int contenders = 20;
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(contenders);
        try {
            var futures = IntStream.range(0, contenders).mapToObj(index -> executor.submit(() -> {
                start.await();
                ProjectFileRoleEvidence winner = repository.insertIfAbsent(evidence(UUID.randomUUID()));
                return new EvidencePublication(winner,
                        repository.findById(winner.evidenceId()).equals(java.util.Optional.of(winner)));
            })).toList();
            start.countDown();
            var publications = futures.stream().map(InMemoryProjectFileRoleRepositoriesTest::getEvidencePublication)
                    .toList();
            assertThat(publications).allMatch(EvidencePublication::visibleByIdOnReturn);
            var results = publications.stream().map(EvidencePublication::winner).toList();
            assertThat(results.stream().map(ProjectFileRoleEvidence::evidenceId).distinct()).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void evidenceSameGeneratedIdAcrossDifferentSignalsFailsClosed() {
        InMemoryProjectFileRoleEvidenceRepository repository = new InMemoryProjectFileRoleEvidenceRepository();
        ProjectFileRoleEvidence first = evidence(UUID.randomUUID());
        repository.insertIfAbsent(first);
        ProjectFileRoleEvidence collision = new ProjectFileRoleEvidence(first.evidenceId(), first.assessmentId(),
                first.projectId(), first.fileId(), first.fileSha256(), first.parseResultId(), first.parseVersion(),
                DocumentRole.AWARD_NOTICE, first.signalType(), first.normalizedValue(), "ROLE_AWARD_STRUCTURE",
                first.weight(), first.sourceLocator(), first.charStart(), first.charEnd(), first.textHash(),
                first.boundedExcerpt(), first.extractorVersion(), first.createdAt());
        assertThatThrownBy(() -> repository.insertIfAbsent(collision)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void evidenceBusinessWinnerCannotMaskProposedIdOccupiedByAnotherSignal() {
        InMemoryProjectFileRoleEvidenceRepository repository = new InMemoryProjectFileRoleEvidenceRepository();
        ProjectFileRoleEvidence businessWinner = evidence(UUID.randomUUID());
        repository.insertIfAbsent(businessWinner);
        UUID occupiedId = UUID.randomUUID();
        ProjectFileRoleEvidence idOccupant = evidenceForSignal(occupiedId, DocumentRole.AWARD_NOTICE,
                "ROLE_AWARD_STRUCTURE", 6, 10);
        repository.insertIfAbsent(idOccupant);
        ProjectFileRoleEvidence attempted = new ProjectFileRoleEvidence(occupiedId, businessWinner.assessmentId(),
                businessWinner.projectId(), businessWinner.fileId(), businessWinner.fileSha256(),
                businessWinner.parseResultId(), businessWinner.parseVersion(), businessWinner.role(),
                businessWinner.signalType(), businessWinner.normalizedValue(), businessWinner.ruleCode(),
                businessWinner.weight(), businessWinner.sourceLocator(), businessWinner.charStart(), businessWinner.charEnd(),
                businessWinner.textHash(), businessWinner.boundedExcerpt(), businessWinner.extractorVersion(),
                businessWinner.createdAt());

        assertThatThrownBy(() -> repository.insertIfAbsent(attempted))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("identity conflict");
        assertThat(repository.findById(occupiedId)).contains(idOccupant);
    }

    @Test
    void decisionStreamReturnsOneValidatedWinnerForConcurrentFileVersion() throws Exception {
        InMemoryProjectFileRoleDecisionRepository repository = new InMemoryProjectFileRoleDecisionRepository();
        int contenders = 24;
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(contenders);
        try {
            var futures = IntStream.range(0, contenders).mapToObj(index -> executor.submit(() -> {
                start.await();
                ProjectFileRoleDecision winner = repository.insertIfAbsent(
                        decision(UUID.randomUUID(), 1, DocumentRole.CONTRACT_BODY));
                return new DecisionPublication(winner,
                        repository.findById(winner.decisionId()).equals(java.util.Optional.of(winner)));
            })).toList();
            start.countDown();
            var publications = futures.stream().map(InMemoryProjectFileRoleRepositoriesTest::getDecisionPublication)
                    .toList();
            assertThat(publications).allMatch(DecisionPublication::visibleByIdOnReturn);
            var results = publications.stream().map(DecisionPublication::winner).toList();
            assertThat(results.stream().map(ProjectFileRoleDecision::decisionId).distinct()).hasSize(1);
            assertThat(repository.findLatestByFileId(FILE)).contains(results.get(0));
            assertThat(repository.findById(results.get(0).decisionId())).contains(results.get(0));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void humanDecisionCannotOverwriteMachineAssessmentAndConflictIsRejected() {
        InMemoryProjectFileRoleAssessmentRepository assessments = new InMemoryProjectFileRoleAssessmentRepository();
        InMemoryProjectFileRoleDecisionRepository decisions = new InMemoryProjectFileRoleDecisionRepository();
        ProjectFileRoleAssessment machine = pending();
        assessments.insertIfAbsent(machine);
        ProjectFileRoleDecision first = decision(UUID.randomUUID(), 1, DocumentRole.CONTRACT_BODY);
        decisions.insertIfAbsent(first);

        assertThat(assessments.findById(machine.assessmentId())).contains(machine);
        assertThatThrownBy(() -> decisions.insertIfAbsent(decision(UUID.randomUUID(), 1, DocumentRole.AWARD_NOTICE)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("decision stream conflict");
    }

    @Test
    void decisionSameGeneratedIdAcrossDifferentFileStreamsFailsClosed() {
        InMemoryProjectFileRoleDecisionRepository repository = new InMemoryProjectFileRoleDecisionRepository();
        ProjectFileRoleDecision first = decision(UUID.randomUUID(), 1, DocumentRole.CONTRACT_BODY);
        repository.insertIfAbsent(first);
        ProjectFileRoleDecision collision = new ProjectFileRoleDecision(first.decisionId(), PROJECT, UUID.randomUUID(),
                SHA, PARSE, 1, ASSESSMENT, DocumentRole.CONTRACT_BODY, RoleDecisionStatus.CONFIRMED,
                "auditor-1", NOW, null, "confirmed", 1);
        assertThatThrownBy(() -> repository.insertIfAbsent(collision)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decisionStreamWinnerCannotMaskProposedIdOccupiedByAnotherStream() {
        InMemoryProjectFileRoleDecisionRepository repository = new InMemoryProjectFileRoleDecisionRepository();
        ProjectFileRoleDecision streamWinner = decision(UUID.randomUUID(), 1, DocumentRole.CONTRACT_BODY);
        repository.insertIfAbsent(streamWinner);
        UUID occupiedId = UUID.randomUUID();
        ProjectFileRoleDecision idOccupant = new ProjectFileRoleDecision(occupiedId, PROJECT, UUID.randomUUID(), SHA,
                PARSE, 1, ASSESSMENT, DocumentRole.AWARD_NOTICE, RoleDecisionStatus.CONFIRMED,
                "auditor-2", NOW.plusSeconds(1), null, "other stream", 1);
        repository.insertIfAbsent(idOccupant);
        ProjectFileRoleDecision attempted = new ProjectFileRoleDecision(occupiedId, streamWinner.projectId(),
                streamWinner.fileId(), streamWinner.fileSha256(), streamWinner.parseResultId(), streamWinner.parseVersion(),
                streamWinner.assessmentId(), streamWinner.selectedRole(), streamWinner.decisionStatus(),
                streamWinner.decidedBy(), streamWinner.decidedAt(), streamWinner.supersedesDecisionId(),
                streamWinner.reason(), streamWinner.versionNo());

        assertThatThrownBy(() -> repository.insertIfAbsent(attempted))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("identity conflict");
        assertThat(repository.findById(occupiedId)).contains(idOccupant);
    }

    private static ProjectFileRoleAssessment pending() {
        return ProjectFileRoleAssessment.pending(UUID.randomUUID(), PROJECT, FILE, SHA, PARSE, 1,
                ProjectFileRoleAssessmentSource.MACHINE_DETERMINISTIC, "role-assessment-v1",
                "manual-confirmation-v1", sha("fingerprint"), null, null, NOW);
    }

    private static ProjectFileRoleEvidence evidence(UUID id) {
        return new ProjectFileRoleEvidence(id, ASSESSMENT, PROJECT, FILE, SHA, PARSE, 1,
                DocumentRole.CONTRACT_BODY, DocumentRoleSignal.SignalType.STRUCTURE, "contract-structure",
                "ROLE_CONTRACT_STRUCTURE", new BigDecimal("3.00"),
                "parse-result:%s:canonical-codepoint:0-6".formatted(PARSE), 0, 6,
                sha("合同正文结构"),
                "合同正文结构", "deterministic-role-v1", NOW);
    }

    private static ProjectFileRoleEvidence evidenceForSignal(UUID id, DocumentRole role, String rule,
                                                              int start, int end) {
        return new ProjectFileRoleEvidence(id, ASSESSMENT, PROJECT, FILE, SHA, PARSE, 1, role,
                DocumentRoleSignal.SignalType.STRUCTURE, "other-structure", rule, new BigDecimal("2.00"),
                "parse-result:%s:canonical-codepoint:%d-%d".formatted(PARSE, start, end), start, end,
                sha("其他"), "其他", "deterministic-role-v1", NOW);
    }

    private static ProjectFileRoleDecision decision(UUID id, long version, DocumentRole role) {
        return new ProjectFileRoleDecision(id, PROJECT, FILE, SHA, PARSE, 1, ASSESSMENT, role,
                RoleDecisionStatus.CONFIRMED, "auditor-1", NOW, null, "confirmed", version);
    }

    private static DecisionPublication getDecisionPublication(Future<DecisionPublication> value) {
        try { return value.get(); } catch (Exception e) { throw new AssertionError(e); }
    }

    private static EvidencePublication getEvidencePublication(Future<EvidencePublication> value) {
        try { return value.get(); } catch (Exception e) { throw new AssertionError(e); }
    }

    private record DecisionPublication(ProjectFileRoleDecision winner, boolean visibleByIdOnReturn) { }

    private record EvidencePublication(ProjectFileRoleEvidence winner, boolean visibleByIdOnReturn) { }

    private static String sha(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
