package com.fuquan.audit.contract.application.project;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuquan.audit.contract.application.project.identity.EffectiveProjectIdentity;
import com.fuquan.audit.contract.application.project.identity.EffectiveProjectIdentityRecoveryReason;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleSource;
import com.fuquan.audit.contract.application.project.role.ProjectFileRoleProjection;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.EffectiveProjectIdentityStatus;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseMethod;
import com.fuquan.audit.contract.domain.project.ProjectFileParseOrigin;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectFileRecoveryInputFingerprintTest {
    private static final Instant NOW = Instant.parse("2026-09-23T00:00:00Z");
    private static final UUID PROJECT_ID = UUID.fromString("31000000-0000-0000-0000-000000000001");
    private static final UUID FILE_ID = UUID.fromString("31000000-0000-0000-0000-000000000002");
    private static final UUID PARSE_ID = UUID.fromString("31000000-0000-0000-0000-000000000003");
    private static final UUID ROLE_DECISION_ID = UUID.fromString("31000000-0000-0000-0000-000000000004");
    private static final UUID IDENTITY_DECISION_ID = UUID.fromString("31000000-0000-0000-0000-000000000005");
    private static final UUID IDENTITY_ASSESSMENT_ID = UUID.fromString("31000000-0000-0000-0000-000000000006");

    @Test
    void canonicalFingerprintChangesWhenAnyAuthoritativeInputChanges() {
        Project project = project(1);
        ProjectFile file = file(sha("file-a"));
        ProjectFileParseResult parse = parse(file, PARSE_ID, 1);
        ProjectFileRoleProjection role = role(ROLE_DECISION_ID, 1);
        EffectiveProjectIdentity identity = identity(IDENTITY_DECISION_ID, 1, 1);

        String baseline = fingerprint(project, file, parse, role, identity);

        assertThat(fingerprint(project, file, parse, role, identity)).isEqualTo(baseline);
        assertThat(fingerprint(project(2), file, parse, role, identity)).isNotEqualTo(baseline);
        ProjectFile changedFile = file(sha("file-b"));
        assertThat(fingerprint(project, changedFile, parse(changedFile, PARSE_ID, 1), role, identity))
                .isNotEqualTo(baseline);
        assertThat(fingerprint(project, file, parse(file, UUID.randomUUID(), 2), role, identity))
                .isNotEqualTo(baseline);
        assertThat(fingerprint(project, file, parse, role(UUID.randomUUID(), 1), identity))
                .isNotEqualTo(baseline);
        assertThat(fingerprint(project, file, parse, role, identity(UUID.randomUUID(), 1, 1)))
                .isNotEqualTo(baseline);
        assertThat(fingerprint(project, file, parse, role, identity(IDENTITY_DECISION_ID, 1, 2)))
                .isNotEqualTo(baseline);
    }

    private static String fingerprint(Project project, ProjectFile file, ProjectFileParseResult parse,
            ProjectFileRoleProjection role, EffectiveProjectIdentity identity) {
        return ProjectFileRecoveryInputFingerprint.canonicalFingerprint("actor-1", project, file, parse,
                role, identity, "IDENTITY_ASSESSMENT_STALE", "RESUME_IDENTITY_ASSESSMENT");
    }

    private static Project project(long version) {
        return new Project(PROJECT_ID, "project", "P-1", null, "creator", version, NOW, NOW);
    }

    private static ProjectFile file(String sha) {
        return new ProjectFile(FILE_ID, PROJECT_ID, null, null, "contract.docx", "application/docx",
                ProjectFileCategory.CONTRACT_BODY, ProjectFileIngestionStatus.AVAILABLE,
                ProjectFileParseStatus.PARSED, sha("storage"), sha, 8, "contract", null, NOW, NOW);
    }

    private static ProjectFileParseResult parse(ProjectFile file, UUID parseId, int version) {
        return new ProjectFileParseResult(parseId, PROJECT_ID, FILE_ID, file.sha256(), version,
                ProjectFileParseOrigin.CURRENT_PIPELINE, ProjectFileParseStatus.PARSED,
                ProjectFileParseMethod.DOCX_TEXT, "poi", "1", "contract", sha("contract"),
                1, 1L, null, null, NOW, NOW, 0);
    }

    private static ProjectFileRoleProjection role(UUID decisionId, long version) {
        return new ProjectFileRoleProjection(PARSE_ID, 1, "CURRENT_PIPELINE", UUID.randomUUID(),
                DocumentRole.CONTRACT_BODY, null, "COMPLETED", "DETERMINISTIC", null,
                null, null, null, null, null, null, null, null, "CONFIRMED",
                DocumentRole.CONTRACT_BODY, EffectiveDocumentRoleSource.HUMAN_CONFIRMATION,
                decisionId, version);
    }

    private static EffectiveProjectIdentity identity(UUID decisionId, long version, long assessmentVersion) {
        return new EffectiveProjectIdentity(EffectiveProjectIdentityStatus.INSUFFICIENT,
                IDENTITY_ASSESSMENT_ID, assessmentVersion, null, null, decisionId, version, false, false, null,
                EffectiveProjectIdentityRecoveryReason.IDENTITY_ASSESSMENT_STALE);
    }

    private static String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
