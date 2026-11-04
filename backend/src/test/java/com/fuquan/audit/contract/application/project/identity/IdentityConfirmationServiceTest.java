package com.fuquan.audit.contract.application.project.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.application.project.port.ContractCandidateRepository;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIdentityAssessment;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseMethod;
import com.fuquan.audit.contract.domain.project.ProjectFileParseOrigin;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.contract.domain.project.ProjectIdentityAssessmentSource;
import com.fuquan.audit.contract.domain.project.ProjectIdentityAssessmentStatus;
import com.fuquan.audit.contract.domain.project.ProjectIdentityResolution;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectFileIdentityAssessmentRepository;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectFileIdentityDecisionRepository;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectFileParseResultRepository;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectRepositories;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import com.fuquan.audit.security.domain.CurrentUser;
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
import org.junit.jupiter.api.Test;

class IdentityConfirmationServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");
    private final UUID projectId = UUID.randomUUID(), fileId = UUID.randomUUID(), parseId = UUID.randomUUID();
    private final String text = "项目编号 P-2026-001";
    private final InMemoryProjectRepositories source = new InMemoryProjectRepositories();
    private final ProjectRepository projects = source.projectRepository();
    private final ContractCandidateRepository candidates = source.contractCandidateRepository();
    private final InMemoryProjectFileParseResultRepository parses = new InMemoryProjectFileParseResultRepository();
    private final InMemoryProjectFileIdentityAssessmentRepository assessments = new InMemoryProjectFileIdentityAssessmentRepository();
    private final InMemoryProjectFileIdentityDecisionRepository decisions = new InMemoryProjectFileIdentityDecisionRepository();
    private final InMemoryIdempotencyAdapter idempotency = new InMemoryIdempotencyAdapter();
    private final List<OperationAuditEvent> audits = new ArrayList<>();
    private final CurrentUser actor = new CurrentUser(UUID.randomUUID().toString(), "tester", "Tester",
            Set.of("AUDITOR"));
    private final IdentityConfirmationService service = new IdentityConfirmationService(
            source.projectFileRepository(), new IdentityConfirmationTransactionCoordinator(projects,
                    source.projectFileRepository(), parses, assessments, decisions, candidates,
                    idempotency, audits::add), () -> Optional.of(actor), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void sameKeyReplaysAppendOnlyHumanDecisionAndGetIsReadOnly() {
        var a = seed();
        var command = command(a, "same-key", ProjectIdentityResolution.CONFIRM_SAME_PROJECT, 0, " checked ");
        var first = service.confirm(command);
        var replay = service.confirm(command);
        int claimCount = idempotency.size(), auditCount = audits.size();
        var reopened = service.get(projectId, fileId, first.decision().decisionId());
        assertThat(replay.decision()).isEqualTo(first.decision());
        assertThat(replay.replayed()).isTrue();
        assertThat(reopened.decision()).isEqualTo(first.decision());
        assertThat(reopened.replayed()).isFalse();
        assertThat(first.decision().decidedBy()).isEqualTo(actor.userId());
        assertThat(first.decision().versionNo()).isEqualTo(1);
        assertThat(idempotency.size()).isEqualTo(claimCount);
        assertThat(audits).hasSize(auditCount);
        assertThatThrownBy(() -> service.get(UUID.randomUUID(), fileId,
                first.decision().decisionId())).isInstanceOf(BusinessException.class);
    }

    @Test
    void sameKeyChangedResolutionAndConcurrentExpectedVersionFailClosed() {
        var a = seed();
        service.confirm(command(a, "conflict-key", ProjectIdentityResolution.CONFIRM_SAME_PROJECT, 0, null));
        assertThatThrownBy(() -> service.confirm(command(a, "conflict-key",
                ProjectIdentityResolution.CONFIRM_NOT_SAME_PROJECT, 0, null)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.confirm(command(a, "other-key",
                ProjectIdentityResolution.CONFIRM_NOT_SAME_PROJECT, 0, null)))
                .isInstanceOf(BusinessException.class);
        assertThat(decisions.findLatestByFileId(fileId).orElseThrow().versionNo()).isEqualTo(1);
    }

    @Test
    void simultaneousDistinctHumanResolutionsWithSameExpectedVersionHaveExactlyOneWinner()
            throws Exception {
        var assessment = seed();
        CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var same = executor.submit(() -> racedConfirmation(command(assessment, "race-same",
                    ProjectIdentityResolution.CONFIRM_SAME_PROJECT, 0, null), ready, go));
            var notSame = executor.submit(() -> racedConfirmation(command(assessment, "race-not-same",
                    ProjectIdentityResolution.CONFIRM_NOT_SAME_PROJECT, 0, null), ready, go));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            var outcomes = List.of(same.get(5, TimeUnit.SECONDS), notSame.get(5, TimeUnit.SECONDS));
            assertThat(outcomes).filteredOn(IdentityConfirmationResult.class::isInstance).hasSize(1);
            assertThat(outcomes).filteredOn(BusinessException.class::isInstance).hasSize(1);
            assertThat(decisions.findLatestByFileId(fileId).orElseThrow().versionNo()).isEqualTo(1);
            assertThat(idempotency.size()).isEqualTo(1);
            assertThat(audits).hasSize(1);
        } finally {
            go.countDown(); executor.shutdownNow();
        }
    }

    private Object racedConfirmation(IdentityConfirmationCommand command,
            CountDownLatch ready, CountDownLatch go) {
        ready.countDown();
        try {
            if (!go.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("race fixture timeout");
            return service.confirm(command);
        } catch (BusinessException expectedConflict) {
            return expectedConflict;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    @Test
    void negativeDecisionPreservesUploadedFileAndDraftButPromotedSourceRejectsDivergence() {
        var a = seed();
        var draft = ContractCandidate.draft(UUID.randomUUID(), projectId, fileId, "C-001", "Contract",
                "GOODS", "A", "B", null, null, null, null, null, null, List.of(), NOW);
        candidates.insertIfAbsent(draft);
        var negative = service.confirm(command(a, "negative-key",
                ProjectIdentityResolution.CONFIRM_NOT_SAME_PROJECT, 0, "not this project"));
        assertThat(negative.decision().resolution()).isEqualTo(ProjectIdentityResolution.CONFIRM_NOT_SAME_PROJECT);
        assertThat(candidates.findByProjectIdAndId(projectId, draft.id())).contains(draft);
        assertThat(source.projectFileRepository().findByProjectIdAndId(projectId, fileId).orElseThrow()
                .ingestionStatus()).isEqualTo(ProjectFileIngestionStatus.AVAILABLE);
        candidates.promoteIfVersionMatches(projectId, draft.id(), 0, UUID.randomUUID(), actor.userId(), NOW);
        assertThatThrownBy(() -> service.confirm(command(a, "forbidden-key",
                ProjectIdentityResolution.CONFIRM_NOT_SAME_PROJECT, 1, null)))
                .isInstanceOf(BusinessException.class);
        assertThat(decisions.findLatestByFileId(fileId)).contains(negative.decision());
    }

    @Test
    void projectMetadataUpdateKeepsOldDecisionReopenableButBlocksNewConfirmationFromStaleAssessment() {
        var a = seed();
        var old = service.confirm(command(a, "old-key", ProjectIdentityResolution.CONFIRM_SAME_PROJECT, 0, null));
        var project = projects.findById(projectId).orElseThrow();
        assertThat(projects.compareAndSet(project, project.confirmMetadata(1,
                "updated", "OTHER-2026-999", null, NOW.plusSeconds(1)))).isTrue();
        assertThat(service.get(projectId, fileId, old.decision().decisionId()).decision().projectVersionNo())
                .isEqualTo(1);
        assertThatThrownBy(() -> service.confirm(command(a, "stale-assessment",
                ProjectIdentityResolution.CONFIRM_SAME_PROJECT, 1, null)))
                .isInstanceOf(BusinessException.class);
    }

    private ProjectFileIdentityAssessment seed() {
        projects.insert(new Project(projectId, "合成项目", "P-2026-001", null,
                actor.userId(), 1, NOW, NOW));
        var file = new ProjectFile(fileId, projectId, null, null, "材料2.pdf", "application/pdf",
                ProjectFileCategory.UNKNOWN, ProjectFileIngestionStatus.AVAILABLE,
                ProjectFileParseStatus.PARSED, sha("file"), sha("file"), 100, text, null, NOW, NOW);
        source.projectFileRepository().insert(file);
        parses.insertIfAbsent(new ProjectFileParseResult(parseId, projectId, fileId, file.sha256(), 1,
                ProjectFileParseOrigin.CURRENT_PIPELINE, ProjectFileParseStatus.PARSED,
                ProjectFileParseMethod.PDF_TEXT, "pdfbox", "v1", text, sha(text), 1, 1L,
                null, null, NOW, NOW, 0));
        var a = new ProjectFileIdentityAssessment(UUID.randomUUID(), projectId, 1, fileId, file.sha256(),
                parseId, 1, ProjectIdentityAssessmentSource.DETERMINISTIC,
                "identity-assessment-v1", "human-same-only-v1", ProjectIdentityAssessmentStatus.LIKELY_SAME_PROJECT,
                sha("input"), null, NOW, NOW, 0);
        assessments.insertIfAbsent(a);
        return a;
    }
    private IdentityConfirmationCommand command(ProjectFileIdentityAssessment a, String key,
            ProjectIdentityResolution resolution, long version, String reason) {
        return new IdentityConfirmationCommand(projectId, fileId, a.assessmentId(), resolution,
                version, reason, key, new ProjectRequestMetadata("request-1", "trace-1", null));
    }
    private static String sha(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
