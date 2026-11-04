package com.fuquan.audit.contract.application.project.role;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseMethod;
import com.fuquan.audit.contract.domain.project.ProjectFileParseOrigin;
import com.fuquan.audit.contract.domain.project.ProjectFileParseProvenance;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleDecision;
import com.fuquan.audit.contract.domain.project.RoleDecisionStatus;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectFileParseResultRepository;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectFileRoleDecisionRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EffectiveDocumentRoleResolverTest {
    private static final UUID PROJECT = UUID.fromString("10000000-0000-0000-0000-000000000012");
    private static final UUID FILE = UUID.fromString("20000000-0000-0000-0000-000000000012");
    private static final UUID PARSE = UUID.fromString("30000000-0000-0000-0000-000000000012");
    private static final UUID ASSESSMENT = UUID.fromString("40000000-0000-0000-0000-000000000012");
    private static final Instant NOW = Instant.parse("2026-09-14T10:00:00Z");
    private static final String SHA = "e".repeat(64);
    private static final String TEXT = "synthetic parsed contract body";

    @Test
    void latestHumanDecisionHasPrecedenceAndExposesItsDurableIdentity() {
        Support support = new Support(ProjectFileParseOrigin.LEGACY_IMPORTED);
        ProjectFileRoleDecision first = support.decision(DocumentRole.CONTRACT_BODY, 1, null);
        support.decisions.insertIfAbsent(first);
        ProjectFileRoleDecision latest = support.decision(DocumentRole.SUPPLEMENT, 2, first.decisionId());
        support.decisions.insertIfAbsent(latest);

        EffectiveDocumentRoleResult result = support.resolver.resolve(
                file(ProjectFileCategory.AWARD_NOTICE));

        assertThat(result.effectiveRole()).isEqualTo(DocumentRole.SUPPLEMENT);
        assertThat(result.source()).isEqualTo(EffectiveDocumentRoleSource.HUMAN_CONFIRMATION);
        assertThat(result.roleDecisionId()).isEqualTo(latest.decisionId());
        assertThat(result.decisionVersionNo()).isEqualTo(2);
        assertThat(result.parseResultId()).isEqualTo(PARSE);
        assertThat(result.parseVersion()).isEqualTo(1);
    }

    @Test
    void legacyParsedFileWithoutDecisionUsesCompatibleCategoryOnly() {
        Support support = new Support(ProjectFileParseOrigin.LEGACY_IMPORTED);

        EffectiveDocumentRoleResult result = support.resolver.resolve(
                file(ProjectFileCategory.TENDER_DOCUMENT));

        assertThat(result.effectiveRole()).isEqualTo(DocumentRole.TENDER_DOCUMENT);
        assertThat(result.source()).isEqualTo(EffectiveDocumentRoleSource.LEGACY_EFFECTIVE);
        assertThat(result.roleDecisionId()).isNull();
        assertThat(result.parseResultId()).isEqualTo(PARSE);
    }

    @Test
    void currentPipelineNeverTreatsFilenameCategoryOrMachineProposalAsAuthority() {
        Support support = new Support(ProjectFileParseOrigin.CURRENT_PIPELINE);

        EffectiveDocumentRoleResult result = support.resolver.resolve(
                file(ProjectFileCategory.CONTRACT_BODY));

        assertThat(result.effectiveRole()).isEqualTo(DocumentRole.UNKNOWN);
        assertThat(result.source()).isEqualTo(EffectiveDocumentRoleSource.NO_AUTHORITATIVE_ROLE);
        assertThat(result.roleDecisionId()).isNull();
    }

    @Test
    void packageAndUnknownAreNeverLegacyEffectiveRoles() {
        Support support = new Support(ProjectFileParseOrigin.LEGACY_IMPORTED);

        assertThat(support.resolver.resolve(file(ProjectFileCategory.PACKAGE)).effectiveRole())
                .isEqualTo(DocumentRole.UNKNOWN);
        assertThat(support.resolver.resolve(file(ProjectFileCategory.UNKNOWN)).effectiveRole())
                .isEqualTo(DocumentRole.UNKNOWN);
        assertThat(support.resolver.resolve(file(ProjectFileCategory.PACKAGE)).source())
                .isEqualTo(EffectiveDocumentRoleSource.NO_AUTHORITATIVE_ROLE);
    }

    @Test
    void decisionBoundToSupersededParseDoesNotLeakIntoCurrentEffectiveRole() {
        Support support = new Support(ProjectFileParseOrigin.CURRENT_PIPELINE);
        support.decisions.insertIfAbsent(support.decision(DocumentRole.CONTRACT_BODY, 1, null));
        UUID reparseId = UUID.fromString("30000000-0000-0000-0000-000000000099");
        support.parses.insertIfAbsent(parse(reparseId, 2, ProjectFileParseOrigin.CURRENT_PIPELINE, PARSE));

        EffectiveDocumentRoleResult result = support.resolver.resolve(file(ProjectFileCategory.UNKNOWN));

        assertThat(result.effectiveRole()).isEqualTo(DocumentRole.UNKNOWN);
        assertThat(result.source()).isEqualTo(EffectiveDocumentRoleSource.NO_AUTHORITATIVE_ROLE);
        assertThat(result.parseResultId()).isEqualTo(reparseId);
        assertThat(result.parseVersion()).isEqualTo(2);
    }

    private static final class Support {
        final InMemoryProjectFileParseResultRepository parses = new InMemoryProjectFileParseResultRepository();
        final InMemoryProjectFileRoleDecisionRepository decisions = new InMemoryProjectFileRoleDecisionRepository();
        final EffectiveDocumentRoleResolver resolver = new EffectiveDocumentRoleResolver(parses, decisions);

        Support(ProjectFileParseOrigin origin) {
            parses.insertIfAbsent(parse(PARSE, 1, origin, null));
        }

        ProjectFileRoleDecision decision(DocumentRole role, long version, UUID supersedes) {
            return new ProjectFileRoleDecision(UUID.randomUUID(), PROJECT, FILE, SHA, PARSE, 1, ASSESSMENT,
                    role, RoleDecisionStatus.CONFIRMED, "actor-12", NOW.plusSeconds(version), supersedes,
                    null, version);
        }
    }

    private static ProjectFile file(ProjectFileCategory category) {
        return new ProjectFile(FILE, PROJECT, null, null, "001.pdf", "application/pdf", category,
                ProjectFileIngestionStatus.AVAILABLE, ProjectFileParseStatus.PARSED, SHA, SHA, 100, TEXT,
                null, NOW.minusSeconds(2), NOW.minusSeconds(1), new ProjectFileParseProvenance(
                ProjectFileParseMethod.PDF_TEXT, "synthetic-parser", BigDecimal.ONE, 1, 1L));
    }

    private static ProjectFileParseResult parse(UUID id, int version, ProjectFileParseOrigin origin,
                                                UUID supersedes) {
        return new ProjectFileParseResult(id, PROJECT, FILE, SHA, version, origin, ProjectFileParseStatus.PARSED,
                ProjectFileParseMethod.PDF_TEXT, "synthetic-parser", "1", TEXT, sha256(TEXT), 1, 1L,
                BigDecimal.ONE, supersedes, NOW.minusSeconds(2), NOW.minusSeconds(1), 1);
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
