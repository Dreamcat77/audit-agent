package com.fuquan.audit.contract.application.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuquan.audit.common.application.port.OperationAuditPort;
import com.fuquan.audit.contract.application.project.port.ExtractionResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AutomaticProjectMetadataServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");
    private static final UUID PROJECT_ID = UUID.fromString("c2c4f412-18ed-46b6-a0cf-66035e3c9d92");
    private static final CurrentUser ACTOR = new CurrentUser("user-1", "auditor", "Auditor", Set.of("AUDITOR"));

    @Test
    void usesOnlyUniqueTypedMaterialFactsAndAuditsSystemEstablishment() {
        ProjectRepository projects = org.mockito.Mockito.mock(ProjectRepository.class);
        ExtractionResultRepository extractions = org.mockito.Mockito.mock(ExtractionResultRepository.class);
        OperationAuditPort audit = org.mockito.Mockito.mock(OperationAuditPort.class);
        Project blank = Project.blank(PROJECT_ID, ACTOR.userId(), NOW);
        when(projects.findByIdForUpdate(PROJECT_ID)).thenReturn(Optional.of(blank));
        when(projects.compareAndSet(any(), any())).thenReturn(true);
        when(extractions.findByProjectId(PROJECT_ID)).thenReturn(List.of(
                fact("PROJECT_NAME", "办公设备采购项目"), fact("PROJECT_NAME", "办公设备采购项目"),
                fact("PROJECT_NO", "CG-2026-01")));

        boolean changed = new AutomaticProjectMetadataService(projects, extractions, audit,
                Clock.fixed(NOW, ZoneOffset.UTC)).ensure(PROJECT_ID, ACTOR,
                        new ProjectRequestMetadata("request-1", "trace-1", "127.0.0.1"));

        assertThat(changed).isTrue();
        ArgumentCaptor<Project> updated = ArgumentCaptor.forClass(Project.class);
        verify(projects).compareAndSet(org.mockito.ArgumentMatchers.eq(blank), updated.capture());
        assertThat(updated.getValue().name()).isEqualTo("办公设备采购项目");
        assertThat(updated.getValue().projectNo()).isEqualTo("CG-2026-01");
        assertThat(updated.getValue().metadataConfirmed()).isTrue();
        verify(audit).append(any());
    }

    @Test
    void usesFirstFoundDisplayNameWithoutInventingAConflictingProjectNumber() {
        ProjectRepository projects = org.mockito.Mockito.mock(ProjectRepository.class);
        ExtractionResultRepository extractions = org.mockito.Mockito.mock(ExtractionResultRepository.class);
        OperationAuditPort audit = org.mockito.Mockito.mock(OperationAuditPort.class);
        Project blank = Project.blank(PROJECT_ID, ACTOR.userId(), NOW);
        when(projects.findByIdForUpdate(PROJECT_ID)).thenReturn(Optional.of(blank));
        when(projects.compareAndSet(any(), any())).thenReturn(true);
        when(extractions.findByProjectId(PROJECT_ID)).thenReturn(List.of(
                fact("PROJECT_NAME", "名称甲"), fact("PROJECT_NAME", "名称乙"),
                fact("PROJECT_NO", "编号甲"), fact("PROJECT_NO", "编号乙")));

        new AutomaticProjectMetadataService(projects, extractions, audit,
                Clock.fixed(NOW, ZoneOffset.UTC)).ensure(PROJECT_ID, ACTOR,
                        new ProjectRequestMetadata("request-1", "trace-1", null));

        ArgumentCaptor<Project> updated = ArgumentCaptor.forClass(Project.class);
        verify(projects).compareAndSet(org.mockito.ArgumentMatchers.eq(blank), updated.capture());
        assertThat(updated.getValue().name()).isEqualTo("名称甲");
        assertThat(updated.getValue().projectNo()).isNull();
    }

    @Test
    void contractTitleDoesNotEstablishProjectNameButAnExplicitProjectLabelCan() {
        for(boolean named:List.of(false,true)) {
            var projects=org.mockito.Mockito.mock(ProjectRepository.class);
            var extractions=org.mockito.Mockito.mock(ExtractionResultRepository.class);
            var audit=org.mockito.Mockito.mock(OperationAuditPort.class);
            var blank=Project.blank(PROJECT_ID,ACTOR.userId(),NOW);
            when(projects.findByIdForUpdate(PROJECT_ID)).thenReturn(Optional.of(blank));
            when(projects.compareAndSet(any(),any())).thenReturn(true);
            when(extractions.findByProjectId(PROJECT_ID)).thenReturn(named
                    ?List.of(fact("CONTRACT_NAME","档案维护服务合同书"),fact("PROJECT_NAME","档案维护服务项目"))
                    :List.of(fact("CONTRACT_NAME","采购委托协议书"),fact("PROJECT_NAME","采购委托协议书")));
            new AutomaticProjectMetadataService(projects,extractions,audit,Clock.fixed(NOW,ZoneOffset.UTC))
                    .ensure(PROJECT_ID,ACTOR,new ProjectRequestMetadata("request-1","trace-1",null));
            var updated=ArgumentCaptor.forClass(Project.class);
            verify(projects).compareAndSet(org.mockito.ArgumentMatchers.eq(blank),updated.capture());
            assertThat(updated.getValue().name()).isEqualTo(named?"档案维护服务项目":"自动整理项目-"+PROJECT_ID.toString().substring(0,8));
        }
    }

    @Test
    void neverOverwritesPreviouslyEstablishedMetadata() {
        ProjectRepository projects = org.mockito.Mockito.mock(ProjectRepository.class);
        ExtractionResultRepository extractions = org.mockito.Mockito.mock(ExtractionResultRepository.class);
        OperationAuditPort audit = org.mockito.Mockito.mock(OperationAuditPort.class);
        Project existing = Project.blank(PROJECT_ID, ACTOR.userId(), NOW)
                .confirmMetadata(0, "existing", "P-1", null, NOW);
        when(projects.findByIdForUpdate(PROJECT_ID)).thenReturn(Optional.of(existing));

        boolean changed = new AutomaticProjectMetadataService(projects, extractions, audit,
                Clock.fixed(NOW, ZoneOffset.UTC)).ensure(PROJECT_ID, ACTOR,
                        new ProjectRequestMetadata("request-1", "trace-1", null));

        assertThat(changed).isFalse();
        verify(extractions, never()).findByProjectId(PROJECT_ID);
        verify(projects, never()).compareAndSet(any(), any());
        verify(audit, never()).append(any());
    }

    private static ExtractionResult fact(String type, String value) {
        return new ExtractionResult(UUID.randomUUID(), PROJECT_ID, UUID.randomUUID(), UUID.randomUUID(), null,
                "a".repeat(64), "project-extraction-v2", type, null, value, value, null,
                value, 1, 1, "page:1/paragraph:1", NOW);
    }
}
