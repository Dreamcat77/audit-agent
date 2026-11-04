package com.fuquan.audit.contract.application.project.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseMethod;
import com.fuquan.audit.contract.domain.project.ProjectFileParseOrigin;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.contract.domain.project.ProjectIdentityAssessmentStatus;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectFileIdentityAssessmentRepository;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectFileIdentityEvidenceRepository;
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
import org.junit.jupiter.api.Test;

class IdentityAssessmentServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");
    private static final String TEXT = "项目编号 GZU-2026-001";
    private final UUID projectId = UUID.randomUUID(), fileId = UUID.randomUUID(), parseId = UUID.randomUUID();
    private final InMemoryProjectRepositories source = new InMemoryProjectRepositories();
    private final ProjectRepository projects = source.projectRepository();
    private final InMemoryProjectFileParseResultRepository parses = new InMemoryProjectFileParseResultRepository();
    private final InMemoryProjectFileIdentityAssessmentRepository assessments = new InMemoryProjectFileIdentityAssessmentRepository();
    private final InMemoryProjectFileIdentityEvidenceRepository evidence = new InMemoryProjectFileIdentityEvidenceRepository();
    private final InMemoryIdempotencyAdapter idempotency = new InMemoryIdempotencyAdapter();
    private final List<OperationAuditEvent> audit = new ArrayList<>();
    private final CurrentUser actor = new CurrentUser(UUID.randomUUID().toString(), "tester", "Tester",
            Set.of("AUDITOR"));
    private final IdentityAssessmentService service = new IdentityAssessmentService(
            source.projectFileRepository(), new IdentityAssessmentTransactionCoordinator(projects,
                    source.projectFileRepository(), parses, source.extractionResultRepository(),
                    assessments, evidence, new DeterministicProjectIdentityResolver(), idempotency,
                    audit::add), () -> Optional.of(actor), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void sameKeyReopensSameDurableAssessmentAndGetDoesNotCreateBusinessWrite() {
        seed();
        var command = new IdentityAssessmentCommand(projectId, fileId, "identity-a",
                new ProjectRequestMetadata("request-1", "trace-1", null));
        var first = service.assess(command);
        var replay = service.assess(command);
        int claimCount = idempotency.size(), auditCount = audit.size();
        var reopened = service.get(projectId, fileId, first.assessment().assessmentId());
        assertThat(first.assessment().status()).isEqualTo(ProjectIdentityAssessmentStatus.LIKELY_SAME_PROJECT);
        assertThat(replay.assessment().assessmentId()).isEqualTo(first.assessment().assessmentId());
        assertThat(replay.replayed()).isTrue();
        assertThat(reopened.assessment().assessmentId()).isEqualTo(first.assessment().assessmentId());
        assertThat(reopened.evidence()).isEqualTo(first.evidence());
        assertThat(reopened.replayed()).isFalse();
        assertThat(idempotency.size()).isEqualTo(claimCount);
        assertThat(audit).hasSize(auditCount);
        assertThatThrownBy(() -> service.get(UUID.randomUUID(), fileId,
                first.assessment().assessmentId())).isInstanceOf(BusinessException.class);
    }

    @Test
    void sameKeyWithUpdatedProjectMetadataCannotReplayOldAssessment() {
        Project initial = seed();
        var command = new IdentityAssessmentCommand(projectId, fileId, "identity-version",
                new ProjectRequestMetadata("request-1", "trace-1", null));
        var old = service.assess(command);
        assertThat(projects.compareAndSet(initial, initial.confirmMetadata(1, "另一个项目",
                "OTHER-2026-999", null, NOW.plusSeconds(1)))).isTrue();
        assertThatThrownBy(() -> service.assess(command)).isInstanceOf(BusinessException.class);
        assertThat(service.get(projectId, fileId, old.assessment().assessmentId()).assessment().projectVersionNo())
                .isEqualTo(1);
    }

    private Project seed() {
        Project project = new Project(projectId, "贵州大学实验室设备采购项目", "GZU-2026-001",
                null, actor.userId(), 1, NOW, NOW);
        projects.insert(project);
        ProjectFile file = new ProjectFile(fileId, projectId, null, null, "001.pdf", "application/pdf",
                ProjectFileCategory.UNKNOWN, ProjectFileIngestionStatus.AVAILABLE, ProjectFileParseStatus.PARSED,
                sha("file"), sha("file"), 100, TEXT, null, NOW, NOW);
        source.projectFileRepository().insert(file);
        parses.insertIfAbsent(new ProjectFileParseResult(parseId, projectId, fileId, file.sha256(), 1,
                ProjectFileParseOrigin.CURRENT_PIPELINE, ProjectFileParseStatus.PARSED,
                ProjectFileParseMethod.PDF_TEXT, "pdfbox", "v1", TEXT, sha(TEXT), 1, 1L,
                null, null, NOW, NOW, 0));
        source.extractionResultRepository().insertIfAbsent(new ExtractionResult(UUID.randomUUID(), projectId,
                fileId, parseId, null, sha("fact-key"), "facts-v2", "PROJECT_NO", null,
                "GZU-2026-001", null, null, TEXT, 1, 1, "paragraph:1", NOW));
        return project;
    }
    private static String sha(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
