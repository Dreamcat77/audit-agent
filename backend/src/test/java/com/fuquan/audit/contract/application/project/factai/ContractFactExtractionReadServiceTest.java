package com.fuquan.audit.contract.application.project.factai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Provenance;
import com.fuquan.audit.contract.application.project.port.ProjectFileParseResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContractFactExtractionReadServiceTest {
    @Test
    void getWithoutAttemptIsReadOnlyAndDoesNotInvokeProvider() {
        UUID projectId = UUID.randomUUID(); UUID fileId = UUID.randomUUID(); UUID parseId = UUID.randomUUID();
        ProjectRepository projects = mock(ProjectRepository.class);
        ProjectFileRepository files = mock(ProjectFileRepository.class);
        ProjectFileParseResultRepository parses = mock(ProjectFileParseResultRepository.class);
        ContractFactExtractionRepository attempts = mock(ContractFactExtractionRepository.class);
        ContractFactCandidatePort provider = mock(ContractFactCandidatePort.class);
        ContractFactExtractionAuthorityResolver authority = mock(ContractFactExtractionAuthorityResolver.class);
        Provenance provenance = new Provenance("provider", "model", "revision", "prompt", "schema");
        Project project = mock(Project.class); ProjectFile file = mock(ProjectFile.class);
        ProjectFileParseResult parse = mock(ProjectFileParseResult.class);
        when(projects.findById(projectId)).thenReturn(Optional.of(project));
        when(files.findByProjectIdAndId(projectId, fileId)).thenReturn(Optional.of(file));
        when(parses.findLatestByFileId(fileId)).thenReturn(Optional.of(parse));
        when(parse.parseResultId()).thenReturn(parseId); when(parse.parseVersion()).thenReturn(2);
        var currentInput = new ContractFactCandidateValidator.CurrentInput(projectId, fileId,
                "f".repeat(64), parseId, 2, "c".repeat(64), "contract", DocumentRole.CONTRACT_BODY, true);
        UUID roleDecisionId = UUID.randomUUID(); UUID identityDecisionId = UUID.randomUUID();
        when(authority.resolve(projectId, fileId)).thenReturn(new ContractFactExtractionAuthority(
                currentInput, roleDecisionId, identityDecisionId));
        when(provider.configuredProvenance()).thenReturn(provenance);
        when(attempts.findLatestForAuthority(projectId, fileId, parseId, roleDecisionId,
                identityDecisionId, DocumentRole.CONTRACT_BODY, provenance)).thenReturn(Optional.empty());

        var result = new ContractFactExtractionReadService(projects, files, parses, attempts,
                authority, provider)
                .latest(projectId, fileId);

        assertThat(result.attempt()).isNull();
        assertThat(result.candidates()).isEmpty();
        verify(provider).configuredProvenance();
        verify(provider, never()).extract(any());
    }
}
