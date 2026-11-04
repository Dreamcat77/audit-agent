package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.DocumentRoleSignal;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleAssessment;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleAssessmentSource;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleDecision;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleEvidence;
import com.fuquan.audit.contract.domain.project.RoleDecisionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class Dm8ProjectFileRoleRepositoriesTest {
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID FILE = UUID.randomUUID();
    private static final UUID PARSE = UUID.randomUUID();
    private static final UUID ASSESSMENT = UUID.randomUUID();
    private static final String SHA = sha("file");
    private static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");
    @Mock private ProjectFileRoleAssessmentMapper assessmentMapper;
    @Mock private ProjectFileRoleEvidenceMapper evidenceMapper;
    @Mock private ProjectFileRoleDecisionMapper decisionMapper;

    @Test
    void assessmentDuplicateRaceReturnsValidatedWinnerAndRejectsCollision() {
        UUID assessmentId = UUID.randomUUID();
        ProjectFileRoleAssessment attempted = pending(assessmentId, SHA);
        ProjectFileRoleAssessment winner = pending(assessmentId, SHA);
        when(assessmentMapper.insert(any())).thenThrow(new DuplicateKeyException("duplicate"));
        when(assessmentMapper.findById(assessmentId.toString())).thenReturn(assessmentRow(winner));
        Dm8ProjectFileRoleAssessmentRepository repository = new Dm8ProjectFileRoleAssessmentRepository(assessmentMapper);

        assertThat(repository.insertIfAbsent(attempted)).isEqualTo(winner);

        ProjectFileRoleAssessment conflicting = pending(assessmentId, sha("other-file"));
        assertThatThrownBy(() -> repository.insertIfAbsent(conflicting)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("assessment input conflict");
    }

    @Test
    void assessmentActiveFenceRaceReloadsValidatedWinnerAfterPrimaryKeyMiss() {
        ProjectFileRoleAssessment attempted = pending(UUID.randomUUID(), SHA);
        ProjectFileRoleAssessment winner = pending(UUID.randomUUID(), SHA);
        when(assessmentMapper.insert(any())).thenThrow(new DuplicateKeyException("active fence"));
        when(assessmentMapper.findById(attempted.assessmentId().toString())).thenReturn(null);
        when(assessmentMapper.findActiveByFileIdAndInputFingerprint(
                FILE.toString(), attempted.inputFingerprint())).thenReturn(assessmentRow(winner));

        ProjectFileRoleAssessment stored = new Dm8ProjectFileRoleAssessmentRepository(assessmentMapper)
                .insertIfAbsent(attempted);

        assertThat(stored).isEqualTo(winner);
        verify(assessmentMapper).findById(attempted.assessmentId().toString());
        verify(assessmentMapper).findActiveByFileIdAndInputFingerprint(
                FILE.toString(), attempted.inputFingerprint());
    }

    @Test
    void assessmentUsesStatusAndVersionCasAndPropagatesConnectivityFailure() {
        ProjectFileRoleAssessment pending = pending(UUID.randomUUID(), SHA);
        ProjectFileRoleAssessment running = pending.start(NOW.plusSeconds(1));
        when(assessmentMapper.compareAndSet(any(), any())).thenReturn(1);
        Dm8ProjectFileRoleAssessmentRepository repository = new Dm8ProjectFileRoleAssessmentRepository(assessmentMapper);
        assertThat(repository.compareAndSet(pending, running)).isTrue();
        verify(assessmentMapper).compareAndSet(any(), any());

        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("dm8 unavailable");
        when(assessmentMapper.insert(any())).thenThrow(failure);
        assertThatThrownBy(() -> repository.insertIfAbsent(pending(UUID.randomUUID(), SHA))).isSameAs(failure);
    }

    @Test
    void assessmentCasCarriesExpectedImmutableDatabaseFenceAndReturnsFalseOnDrift() {
        ProjectFileRoleAssessment expected = pending(UUID.randomUUID(), SHA);
        ProjectFileRoleAssessment updated = expected.start(NOW.plusSeconds(1));
        when(assessmentMapper.compareAndSet(any(), any())).thenReturn(0);
        Dm8ProjectFileRoleAssessmentRepository repository = new Dm8ProjectFileRoleAssessmentRepository(assessmentMapper);

        assertThat(repository.compareAndSet(expected, updated)).isFalse();
        var expectedCaptor = org.mockito.ArgumentCaptor.forClass(ProjectFileRoleAssessmentPersistenceRow.class);
        verify(assessmentMapper).compareAndSet(any(), expectedCaptor.capture());
        assertThat(expectedCaptor.getValue().parseResultId()).isEqualTo(PARSE.toString());
        assertThat(expectedCaptor.getValue().inputFingerprint()).isEqualTo(expected.inputFingerprint());
        assertThat(expectedCaptor.getValue().fileSha256()).isEqualTo(SHA);
    }

    @Test
    void evidenceDuplicateRaceDistinguishesReplayCollisionAndConnectivityFailure() {
        ProjectFileRoleEvidence evidence = evidence(UUID.randomUUID());
        when(evidenceMapper.insert(any())).thenThrow(new DuplicateKeyException("duplicate"));
        when(evidenceMapper.findById(evidence.evidenceId().toString())).thenReturn(evidenceRow(evidence));
        Dm8ProjectFileRoleEvidenceRepository repository = new Dm8ProjectFileRoleEvidenceRepository(evidenceMapper);
        assertThat(repository.insertIfAbsent(evidence)).isEqualTo(evidence);

        ProjectFileRoleEvidence conflicting = new ProjectFileRoleEvidence(evidence.evidenceId(), evidence.assessmentId(),
                evidence.projectId(), evidence.fileId(), evidence.fileSha256(), evidence.parseResultId(), evidence.parseVersion(),
                DocumentRole.AWARD_NOTICE, evidence.signalType(), evidence.normalizedValue(), evidence.ruleCode(), evidence.weight(),
                evidence.sourceLocator(), evidence.charStart(), evidence.charEnd(), evidence.textHash(), evidence.boundedExcerpt(),
                evidence.extractorVersion(), evidence.createdAt());
        assertThatThrownBy(() -> repository.insertIfAbsent(conflicting)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("evidence identity conflict");

        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("dm8 unavailable");
        when(evidenceMapper.findById(evidence.evidenceId().toString())).thenThrow(failure);
        assertThatThrownBy(() -> repository.insertIfAbsent(evidence)).isSameAs(failure);
    }

    @Test
    void evidenceUniqueSignalRaceReloadsWinnerWithDifferentGeneratedId() {
        ProjectFileRoleEvidence attempted = evidence(UUID.randomUUID());
        ProjectFileRoleEvidence winner = evidence(UUID.randomUUID());
        when(evidenceMapper.insert(any())).thenThrow(new DuplicateKeyException("duplicate"));
        when(evidenceMapper.findBySignal(ASSESSMENT.toString(), DocumentRole.CONTRACT_BODY.name(),
                "ROLE_CONTRACT_STRUCTURE", attempted.sourceLocator())).thenReturn(evidenceRow(winner));
        Dm8ProjectFileRoleEvidenceRepository repository = new Dm8ProjectFileRoleEvidenceRepository(evidenceMapper);

        assertThat(repository.insertIfAbsent(attempted)).isEqualTo(winner);
    }

    @Test
    void evidencePrimaryKeyCollisionIsCheckedBeforeBusinessSignalWinner() {
        ProjectFileRoleEvidence attempted = evidence(UUID.randomUUID());
        ProjectFileRoleEvidence collision = new ProjectFileRoleEvidence(attempted.evidenceId(), ASSESSMENT, PROJECT,
                FILE, SHA, PARSE, 1, DocumentRole.AWARD_NOTICE, attempted.signalType(), attempted.normalizedValue(),
                "ROLE_AWARD_STRUCTURE", attempted.weight(), attempted.sourceLocator(), attempted.charStart(),
                attempted.charEnd(), attempted.textHash(), attempted.boundedExcerpt(), attempted.extractorVersion(), NOW);
        when(evidenceMapper.insert(any())).thenThrow(new DuplicateKeyException("duplicate"));
        when(evidenceMapper.findById(attempted.evidenceId().toString())).thenReturn(evidenceRow(collision));

        assertThatThrownBy(() -> new Dm8ProjectFileRoleEvidenceRepository(evidenceMapper).insertIfAbsent(attempted))
                .isInstanceOf(IllegalStateException.class);
        verify(evidenceMapper, never()).findBySignal(any(), any(), any(), any());
    }

    @Test
    void completedAssessmentCandidateProjectionRoundTripsAsStructuredData() {
        ProjectFileRoleAssessment pending = pending(UUID.randomUUID(), SHA);
        ProjectFileRoleAssessment completed = pending.start(NOW.plusSeconds(1)).complete(DocumentRole.CONTRACT_BODY,
                new BigDecimal("0.9200"), false,
                java.util.List.of(new com.fuquan.audit.contract.domain.project.ProjectFileRoleCandidateProjection(
                        DocumentRole.CONTRACT_BODY, new BigDecimal("8.50"), new BigDecimal("0.9200"),
                        "CONTRACT_STRUCTURE")), null, NOW.plusSeconds(2));
        ProjectFileRoleAssessmentPersistenceRow row = new ProjectFileRoleAssessmentPersistenceRow(
                completed.assessmentId().toString(), PROJECT.toString(), FILE.toString(), SHA, PARSE.toString(), 1,
                "COMPLETED", "CONTRACT_BODY", new BigDecimal("0.9200"), false, "MACHINE_DETERMINISTIC",
                "role-assessment-v1", "manual-confirmation-v1",
                "[{\"role\":\"CONTRACT_BODY\",\"score\":8.50,\"confidence\":0.9200,\"reasonCode\":\"CONTRACT_STRUCTURE\"}]",
                null, null, null, null, null, null, null, completed.inputFingerprint(),
                completed.assessmentId().toString(), null,
                time(NOW), time(NOW.plusSeconds(1)), time(NOW.plusSeconds(2)), 1, 2);
        when(assessmentMapper.findById(completed.assessmentId().toString())).thenReturn(row);

        assertThat(new Dm8ProjectFileRoleAssessmentRepository(assessmentMapper).findById(completed.assessmentId()))
                .contains(completed);
    }

    @Test
    void sixDecimalAssessmentConfidenceRoundTripsWithoutCandidateProjectionDrift() {
        ProjectFileRoleAssessment completed = pending(UUID.randomUUID(), SHA).start(NOW.plusSeconds(1)).complete(
                DocumentRole.CONTRACT_BODY, new BigDecimal("0.666667"), false,
                java.util.List.of(new com.fuquan.audit.contract.domain.project.ProjectFileRoleCandidateProjection(
                        DocumentRole.CONTRACT_BODY, new BigDecimal("2"), new BigDecimal("0.666667"), "RATIO")),
                null, NOW.plusSeconds(2));
        ProjectFileRoleAssessmentPersistenceRow row = new ProjectFileRoleAssessmentPersistenceRow(
                completed.assessmentId().toString(), PROJECT.toString(), FILE.toString(), SHA, PARSE.toString(), 1,
                "COMPLETED", "CONTRACT_BODY", new BigDecimal("0.666667"), false, "MACHINE_DETERMINISTIC",
                "role-assessment-v1", "manual-confirmation-v1",
                "[{\"role\":\"CONTRACT_BODY\",\"score\":2,\"confidence\":0.666667,\"reasonCode\":\"RATIO\"}]",
                null, null, null, null, null, null, null, completed.inputFingerprint(),
                completed.assessmentId().toString(), null,
                time(NOW), time(NOW.plusSeconds(1)), time(NOW.plusSeconds(2)), 1, 2);
        when(assessmentMapper.findById(completed.assessmentId().toString())).thenReturn(row);

        ProjectFileRoleAssessment loaded = new Dm8ProjectFileRoleAssessmentRepository(assessmentMapper)
                .findById(completed.assessmentId()).orElseThrow();
        assertThat(loaded.confidence()).isEqualByComparingTo("0.666667");
        assertThat(loaded.candidateRoles().get(0).confidence()).isEqualByComparingTo("0.666667");
    }

    @Test
    void aiAssessmentFailsClosedWhenPersistedPredecessorSourceIsNotDeterministic() {
        UUID assessmentId = UUID.randomUUID();
        ProjectFileRoleAssessmentPersistenceRow row = new ProjectFileRoleAssessmentPersistenceRow(
                assessmentId.toString(), PROJECT.toString(), FILE.toString(), SHA, PARSE.toString(), 1,
                "PENDING", null, null, false, "MACHINE_AI", "role-assessment-v1", "manual-confirmation-v1",
                null, UUID.randomUUID().toString(), "MACHINE_AI", "qwen", "role-model", "revision-1",
                "prompt-v1", "schema-v1", sha("ai-fingerprint"), sha("ai-fingerprint"), null,
                time(NOW), null, null, 0, 0);
        when(assessmentMapper.findById(assessmentId.toString())).thenReturn(row);

        assertThatThrownBy(() -> new Dm8ProjectFileRoleAssessmentRepository(assessmentMapper).findById(assessmentId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("preceding role assessment source");
    }

    @Test
    void decisionDuplicateRaceReturnsDifferentGeneratedIdWinnerAndRejectsBusinessConflict() {
        ProjectFileRoleDecision attempted = decision(UUID.randomUUID(), DocumentRole.CONTRACT_BODY);
        ProjectFileRoleDecision winner = decision(UUID.randomUUID(), DocumentRole.CONTRACT_BODY);
        when(decisionMapper.insert(any())).thenThrow(new DuplicateKeyException("duplicate"));
        when(decisionMapper.findByFileIdAndVersion(FILE.toString(), 1)).thenReturn(decisionRow(winner));
        Dm8ProjectFileRoleDecisionRepository repository = new Dm8ProjectFileRoleDecisionRepository(decisionMapper);

        assertThat(repository.insertIfAbsent(attempted)).isEqualTo(winner);
        ProjectFileRoleDecision conflicting = decision(UUID.randomUUID(), DocumentRole.AWARD_NOTICE);
        assertThatThrownBy(() -> repository.insertIfAbsent(conflicting)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decision stream conflict");
    }

    @Test
    void decisionPrimaryKeyCollisionIsCheckedBeforeStreamVersionWinner() {
        ProjectFileRoleDecision attempted = decision(UUID.randomUUID(), DocumentRole.CONTRACT_BODY);
        ProjectFileRoleDecision collision = new ProjectFileRoleDecision(attempted.decisionId(), PROJECT,
                UUID.randomUUID(), SHA, PARSE, 1, ASSESSMENT, attempted.selectedRole(), RoleDecisionStatus.CONFIRMED,
                attempted.decidedBy(), NOW, null, attempted.reason(), 1);
        when(decisionMapper.insert(any())).thenThrow(new DuplicateKeyException("duplicate"));
        when(decisionMapper.findById(attempted.decisionId().toString())).thenReturn(decisionRow(collision));

        assertThatThrownBy(() -> new Dm8ProjectFileRoleDecisionRepository(decisionMapper).insertIfAbsent(attempted))
                .isInstanceOf(IllegalStateException.class);
        verify(decisionMapper, never()).findByFileIdAndVersion(any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void decisionReadsByIdAndLatestAndDoesNotMisclassifyConnectivityAsDuplicate() {
        ProjectFileRoleDecision decision = decision(UUID.randomUUID(), DocumentRole.CONTRACT_BODY);
        when(decisionMapper.findById(decision.decisionId().toString())).thenReturn(decisionRow(decision));
        when(decisionMapper.findLatestByFileId(FILE.toString())).thenReturn(decisionRow(decision));
        Dm8ProjectFileRoleDecisionRepository repository = new Dm8ProjectFileRoleDecisionRepository(decisionMapper);
        assertThat(repository.findById(decision.decisionId())).contains(decision);
        assertThat(repository.findLatestByFileId(FILE)).contains(decision);

        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("dm8 unavailable");
        when(decisionMapper.insert(any())).thenThrow(failure);
        assertThatThrownBy(() -> repository.insertIfAbsent(decision)).isSameAs(failure);
    }

    private static ProjectFileRoleAssessment pending(UUID id, String sha) {
        return ProjectFileRoleAssessment.pending(id, PROJECT, FILE, sha, PARSE, 1,
                ProjectFileRoleAssessmentSource.MACHINE_DETERMINISTIC, "role-assessment-v1",
                "manual-confirmation-v1", Dm8ProjectFileRoleRepositoriesTest.sha("fingerprint"), null, null, NOW);
    }

    private static ProjectFileRoleEvidence evidence(UUID id) {
        return new ProjectFileRoleEvidence(id, ASSESSMENT, PROJECT, FILE, SHA, PARSE, 1,
                DocumentRole.CONTRACT_BODY, DocumentRoleSignal.SignalType.STRUCTURE, "contract-structure",
                "ROLE_CONTRACT_STRUCTURE", new BigDecimal("3.00"),
                "parse-result:%s:canonical-codepoint:0-6".formatted(PARSE), 0, 6, sha("合同正文"),
                "合同正文", "deterministic-role-v1", NOW);
    }

    private static ProjectFileRoleDecision decision(UUID id, DocumentRole role) {
        return new ProjectFileRoleDecision(id, PROJECT, FILE, SHA, PARSE, 1, ASSESSMENT, role,
                RoleDecisionStatus.CONFIRMED, "auditor-1", NOW, null, "confirmed", 1);
    }

    private static ProjectFileRoleAssessmentPersistenceRow assessmentRow(ProjectFileRoleAssessment value) {
        return new ProjectFileRoleAssessmentPersistenceRow(value.assessmentId().toString(), PROJECT.toString(), FILE.toString(),
                value.fileSha256(), PARSE.toString(), 1, value.executionStatus().name(), null, null, false,
                value.assessmentSource().name(), value.assessmentVersion(), value.rolePolicyVersion(), "[]",
                null, null, null, null, null, null, null, value.inputFingerprint(),
                value.executionStatus() == com.fuquan.audit.contract.domain.project.RoleAssessmentExecutionStatus.COMPLETED
                        || value.executionStatus() == com.fuquan.audit.contract.domain.project.RoleAssessmentExecutionStatus.FAILED
                        ? value.assessmentId().toString() : value.inputFingerprint(), null,
                time(value.createdAt()), value.startedAt() == null ? null : time(value.startedAt()),
                value.completedAt() == null ? null : time(value.completedAt()), value.attemptNo(), value.versionNo());
    }

    private static ProjectFileRoleEvidencePersistenceRow evidenceRow(ProjectFileRoleEvidence value) {
        return new ProjectFileRoleEvidencePersistenceRow(value.evidenceId().toString(), value.assessmentId().toString(),
                PROJECT.toString(), FILE.toString(), SHA, PARSE.toString(), 1, value.role().name(), value.signalType().name(),
                value.normalizedValue(), value.ruleCode(), value.weight(), value.sourceLocator(), value.charStart(), value.charEnd(),
                value.textHash(), value.boundedExcerpt(), value.extractorVersion(), time(value.createdAt()));
    }

    private static ProjectFileRoleDecisionPersistenceRow decisionRow(ProjectFileRoleDecision value) {
        return new ProjectFileRoleDecisionPersistenceRow(value.decisionId().toString(), value.projectId().toString(),
                value.fileId().toString(), value.fileSha256(), value.parseResultId().toString(), value.parseVersion(),
                value.assessmentId().toString(), value.selectedRole().name(), value.decisionStatus().name(),
                value.decidedBy(), time(value.decidedAt()), null, value.reason(), value.versionNo());
    }

    private static LocalDateTime time(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }

    private static String sha(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
