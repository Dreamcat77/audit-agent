package com.fuquan.audit.contract.application.project.factai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.identity.EffectiveProjectIdentity;
import com.fuquan.audit.contract.application.project.identity.EffectiveProjectIdentityResolver;
import com.fuquan.audit.contract.application.project.port.ProjectFileParseResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleResolver;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleResult;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleSource;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class DefaultContractFactExtractionAuthorityResolverTest {
    private static final UUID PROJECT_ID = UUID.fromString("37000000-0000-0000-0000-000000000001");
    private static final UUID FILE_ID = UUID.fromString("37000000-0000-0000-0000-000000000002");
    private static final UUID PARSE_ID = UUID.fromString("37000000-0000-0000-0000-000000000003");
    private static final UUID ROLE_DECISION_ID = UUID.fromString("37000000-0000-0000-0000-000000000004");
    private static final Instant NOW = Instant.parse("2026-10-10T00:00:00Z");
    private static final String TEXT = "safe canonical text";
    private static final String FILE_SHA = "a".repeat(64);

    @Test void oldDocxParseRemainsReopenableButCannotClaimProvedRowsForNewFactExtraction() {
        var projects=mock(ProjectRepository.class);var files=mock(ProjectFileRepository.class);
        var parses=mock(ProjectFileParseResultRepository.class);var roles=mock(EffectiveDocumentRoleResolver.class);
        var identities=mock(EffectiveProjectIdentityResolver.class);
        var project=Project.blank(PROJECT_ID,"actor-1",NOW).confirmMetadata(0,"Synthetic project","SYN-1",null,NOW);
        var file=file();var legacy=parse();
        var decision=new com.fuquan.audit.contract.domain.project.ProjectFileIdentityDecision(UUID.randomUUID(),PROJECT_ID,1,
                FILE_ID,FILE_SHA,PARSE_ID,1,UUID.randomUUID(),com.fuquan.audit.contract.domain.project.ProjectIdentityResolution.CONFIRM_SAME_PROJECT,
                "actor-1",NOW,null,"Synthetic local authority",1);
        when(projects.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(files.findByProjectIdAndId(PROJECT_ID,FILE_ID)).thenReturn(Optional.of(file));
        when(parses.findLatestByFileId(FILE_ID)).thenReturn(Optional.of(legacy));
        when(roles.resolve(file)).thenReturn(new EffectiveDocumentRoleResult(DocumentRole.CONTRACT_BODY,
                EffectiveDocumentRoleSource.HUMAN_CONFIRMATION,ROLE_DECISION_ID,1L,PARSE_ID,1));
        when(identities.resolve(project,file)).thenReturn(new EffectiveProjectIdentity(EffectiveProjectIdentityStatus.SAME_PROJECT,
                null,null,null,decision.decisionId(),1L,true,false,decision));
        var resolver=new DefaultContractFactExtractionAuthorityResolver(projects,files,parses,roles,identities);
        var resolved=resolver.resolve(PROJECT_ID,FILE_ID);
        var provider=mock(ContractFactCandidatePort.class);
        when(provider.configuredProvenance()).thenReturn(new ContractFactCandidatePort.Provenance(
                "synthetic","synthetic-model","synthetic-revision","prompt-v1","contract-fact-candidates-v2"));
        var coordinator=new DefaultContractFactExtractionTransactionCoordinator(
                new com.fuquan.audit.contract.infra.memory.project.InMemoryContractFactExtractionRepository(),
                new com.fuquan.audit.contract.infra.memory.project.InMemoryProjectRepositories().extractionResultRepository(),
                (projectId,fileId)->resolved,provider);
        assertThatThrownBy(()->coordinator.prepare(PROJECT_ID,FILE_ID,"legacy-row-operation",
                new com.fuquan.audit.security.domain.CurrentUser("actor-1","actor","Synthetic",java.util.Set.of("AUDITOR")),
                new com.fuquan.audit.contract.application.project.ProjectRequestMetadata("req","trace","127.0.0.1"),NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("row layout");
        assertThat(parses.findLatestByFileId(FILE_ID)).contains(legacy);
        assertThat(legacy.canonicalText()).isEqualTo(TEXT);assertThat(legacy.parseResultId()).isEqualTo(PARSE_ID);
    }

    @Test
    void reportsTheFirstAuthorityFailureWithoutDocumentContent(CapturedOutput output) {
        ProjectRepository projects = mock(ProjectRepository.class);
        ProjectFileRepository files = mock(ProjectFileRepository.class);
        ProjectFileParseResultRepository parses = mock(ProjectFileParseResultRepository.class);
        EffectiveDocumentRoleResolver roles = mock(EffectiveDocumentRoleResolver.class);
        EffectiveProjectIdentityResolver identities = mock(EffectiveProjectIdentityResolver.class);
        Project project = Project.blank(PROJECT_ID, "actor-1", NOW);
        ProjectFile file = file();
        ProjectFileParseResult parse = parse();
        when(projects.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(files.findByProjectIdAndId(PROJECT_ID, FILE_ID)).thenReturn(Optional.of(file));
        when(parses.findLatestByFileId(FILE_ID)).thenReturn(Optional.of(parse));
        when(roles.resolve(file)).thenReturn(new EffectiveDocumentRoleResult(
                DocumentRole.CONTRACT_BODY, EffectiveDocumentRoleSource.HUMAN_CONFIRMATION,
                ROLE_DECISION_ID, 1L, PARSE_ID, 1));
        when(identities.resolve(project, file)).thenReturn(new EffectiveProjectIdentity(
                EffectiveProjectIdentityStatus.INSUFFICIENT, null, null, null,
                null, null, false, false, null));
        var resolver = new DefaultContractFactExtractionAuthorityResolver(
                projects, files, parses, roles, identities);

        assertThatThrownBy(() -> resolver.resolve(PROJECT_ID, FILE_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage("contract fact extraction authority is not current");

        assertThat(output).contains("event=AUTHORITY_CHECK")
                .contains("reasonCode=PROJECT_METADATA_UNCONFIRMED")
                .contains("projectId=" + PROJECT_ID)
                .contains("fileId=" + FILE_ID)
                .doesNotContain(TEXT);
    }

    private static ProjectFile file() {
        return new ProjectFile(FILE_ID, PROJECT_ID, null, "contract.docx", "contract.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                ProjectFileCategory.CONTRACT_BODY, ProjectFileIngestionStatus.AVAILABLE,
                ProjectFileParseStatus.PARSED, "b".repeat(64), FILE_SHA, 100, TEXT,
                null, NOW, NOW);
    }

    private static ProjectFileParseResult parse() {
        return new ProjectFileParseResult(PARSE_ID, PROJECT_ID, FILE_ID, FILE_SHA, 1,
                ProjectFileParseOrigin.CURRENT_PIPELINE, ProjectFileParseStatus.PARSED,
                ProjectFileParseMethod.DOCX_TEXT, "DOCX", "1", TEXT, sha256(TEXT),
                null, 1L, null, null, NOW, NOW, 0);
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
