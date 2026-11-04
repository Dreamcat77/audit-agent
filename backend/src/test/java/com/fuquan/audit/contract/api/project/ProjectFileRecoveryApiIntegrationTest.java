package com.fuquan.audit.contract.api.project;

import com.fuquan.audit.common.application.port.IdempotencyStatus;
import com.fuquan.audit.contract.application.project.ProjectApplicationService;
import com.fuquan.audit.contract.application.project.ProjectFileIngestionTransactionCoordinator;
import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ProjectFileRecoveryApiIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ProjectApplicationService projects;
    @Autowired ProjectFileIngestionTransactionCoordinator transactions;

    @Test
    void getRoutesNeverRecoverOrMutatePendingUpload() throws Exception {
        CurrentUser actor = new CurrentUser("project-recovery-api", "recovery.api", "Recovery API", Set.of("AUDITOR"));
        var project = projects.createBlank(actor.userId());
        UUID fileId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-26T00:00:00Z");
        ProjectFile pending = new ProjectFile(fileId, project.id(), null, null, "contract.pdf", "application/pdf",
                ProjectFileCategory.CONTRACT_BODY, ProjectFileIngestionStatus.UPLOAD_PENDING,
                ProjectFileParseStatus.PENDING, "1".repeat(64), "2".repeat(64), 10, null, null, now, now);
        transactions.startUpload(pending, actor, "get-no-recovery-" + fileId, "fingerprint",
                new ProjectRequestMetadata("request", "trace", "127.0.0.1"));
        var authentication = UsernamePasswordAuthenticationToken.authenticated(actor, null,
                List.of(new SimpleGrantedAuthority("ROLE_AUDITOR")));

        mockMvc.perform(get("/api/v1/projects/{projectId}/files", project.id())
                        .with(authentication(authentication)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].ingestionStatus").value("UPLOAD_PENDING"));
        mockMvc.perform(get("/api/v1/projects/{projectId}/files/{fileId}/content", project.id(), fileId)
                        .with(authentication(authentication)))
                .andExpect(status().isConflict());

        assertThat(projects.file(project.id(), fileId).ingestionStatus())
                .isEqualTo(ProjectFileIngestionStatus.UPLOAD_PENDING);
        assertThat(transactions.findOriginal(fileId)).get()
                .extracting(record -> record.status()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
    }
}
