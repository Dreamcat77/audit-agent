package com.fuquan.audit.contract.application.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuquan.audit.contract.application.project.identity.IdentityAssessmentService;
import com.fuquan.audit.contract.application.project.port.ProjectFileParseResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.application.project.role.AiRoleAssessmentService;
import com.fuquan.audit.contract.application.project.role.RoleAssessmentCommand;
import com.fuquan.audit.contract.application.project.role.RoleAssessmentResult;
import com.fuquan.audit.contract.application.project.role.RoleAssessmentService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProjectIntakeProgressionSingleFileTest {
    @Test
    void singleFileRoleResumeNeverScansSiblingFiles() {
        UUID project = UUID.randomUUID();
        UUID fileA = UUID.randomUUID();
        ProjectRepository projects = mock(ProjectRepository.class);
        ProjectFileRepository files = mock(ProjectFileRepository.class);
        ProjectFileParseResultRepository parses = mock(ProjectFileParseResultRepository.class);
        RoleAssessmentService roles = mock(RoleAssessmentService.class);
        AiRoleAssessmentService ai = mock(AiRoleAssessmentService.class);
        IdentityAssessmentService identities = mock(IdentityAssessmentService.class);
        RoleAssessmentResult deterministic = mock(RoleAssessmentResult.class);
        RoleAssessmentResult completed = mock(RoleAssessmentResult.class);
        when(roles.assess(any())).thenReturn(deterministic);
        when(ai.assessIfNeeded(any(), any())).thenReturn(completed);
        var service = new ProjectIntakeProgressionService(projects, files, parses, roles, ai, identities);

        RoleAssessmentResult result = service.ensureRoleAssessment(project, fileA,
                "single-file-key", new ProjectRequestMetadata("request", "trace", "127.0.0.1"));

        assertThat(result).isSameAs(completed);
        ArgumentCaptor<RoleAssessmentCommand> command = ArgumentCaptor.forClass(RoleAssessmentCommand.class);
        verify(roles).assess(command.capture());
        assertThat(command.getValue().projectId()).isEqualTo(project);
        assertThat(command.getValue().fileId()).isEqualTo(fileA);
        assertThat(command.getValue().idempotencyKey()).isEqualTo("single-file-key");
        verify(files, never()).findByProjectId(project);
        verify(parses, never()).findLatestByFileId(any());
    }
}
