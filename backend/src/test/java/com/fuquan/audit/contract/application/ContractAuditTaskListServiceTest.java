package com.fuquan.audit.contract.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuquan.audit.contract.application.port.ContractRepository;
import com.fuquan.audit.contract.application.project.port.ProjectContractQueryPort;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.contract.domain.project.ProjectContract;
import com.fuquan.audit.contract.infra.memory.LocalContractFixtures;
import com.fuquan.audit.review.application.port.AuditTaskReadQueryPort;
import com.fuquan.audit.review.domain.AuditTask;
import com.fuquan.audit.review.domain.AuditTaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContractAuditTaskListServiceTest {

    @Test
    void optInHistoryKeepsBothF02TasksForOneContractSoTheOlderRunRemainsClickable() {
        AuditTaskReadQueryPort tasks = mock(AuditTaskReadQueryPort.class);
        ContractRepository contracts = mock(ContractRepository.class);
        ProjectContractQueryPort associations = mock(ProjectContractQueryPort.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        F02ReviewProgressService progress = mock(F02ReviewProgressService.class);
        Contract contract = LocalContractFixtures.contracts().get(0);
        AuditTask old = task(contract, AuditTaskStatus.DONE, "70000000-0000-0000-0000-000000000030");
        AuditTask current = task(contract, AuditTaskStatus.DONE, "70000000-0000-0000-0000-000000000031");
        when(tasks.findAllPage("CONTRACT", ContractReviewService.REVIEW_TYPE, 1, 20))
                .thenReturn(new AuditTaskReadQueryPort.Page(List.of(current, old), 1, 20, 2));
        when(contracts.findById(contract.id())).thenReturn(Optional.of(contract));
        when(associations.findByContractId(contract.id())).thenReturn(Optional.empty());
        when(progress.summarize(contract.id(), current))
                .thenReturn(new F02ReviewProgressSummary(5, 5, true, false, 0, false, "无风险"));
        when(progress.summarize(contract.id(), old))
                .thenReturn(new F02ReviewProgressSummary(5, 5, true, false, 0, false, "无风险"));

        ContractAuditTaskPage page = new ContractAuditTaskListService(
                tasks, contracts, associations, projects, progress).list(
                        new ContractAuditTaskListQuery(1, 20, null, null, null, null, null), true);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).extracting(row -> row.task().id())
                .containsExactly(current.id(), old.id());
    }

    @Test
    void summaryCountsEveryLatestDurableTaskWithoutPagingOrShowcaseRows() {
        AuditTaskReadQueryPort tasks = mock(AuditTaskReadQueryPort.class);
        ContractRepository contracts = mock(ContractRepository.class);
        ProjectContractQueryPort associations = mock(ProjectContractQueryPort.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        F02ReviewProgressService progress = mock(F02ReviewProgressService.class);
        Contract first = LocalContractFixtures.contracts().get(0);
        Contract second = LocalContractFixtures.contracts().get(1);
        AuditTask pending = task(first, AuditTaskStatus.PROCESSING, "70000000-0000-0000-0000-000000000010");
        AuditTask completed = task(second, AuditTaskStatus.DONE, "70000000-0000-0000-0000-000000000011");
        when(tasks.findAllLatest("CONTRACT", ContractReviewService.REVIEW_TYPE))
                .thenReturn(List.of(pending, completed));
        when(contracts.findById(first.id())).thenReturn(Optional.of(first));
        when(contracts.findById(second.id())).thenReturn(Optional.of(second));
        when(associations.findByContractId(first.id())).thenReturn(Optional.empty());
        when(associations.findByContractId(second.id())).thenReturn(Optional.empty());
        when(progress.summarize(first.id(), pending))
                .thenReturn(new F02ReviewProgressSummary(1, 6, false, false, 0, false, "无风险"));
        when(progress.summarize(second.id(), completed))
                .thenReturn(new F02ReviewProgressSummary(6, 6, true, false, 1, false, "低风险"));

        ContractAuditTaskSummary summary = new ContractAuditTaskListService(
                tasks, contracts, associations, projects, progress).summary();

        assertThat(summary).isEqualTo(new ContractAuditTaskSummary(2, 1, 1, 1));
        verify(tasks).findAllLatest("CONTRACT", ContractReviewService.REVIEW_TYPE);
    }

    @Test
    void filtersTheCompleteDurableProjectionBeforePaging() {
        AuditTaskReadQueryPort tasks = mock(AuditTaskReadQueryPort.class);
        ContractRepository contracts = mock(ContractRepository.class);
        ProjectContractQueryPort associations = mock(ProjectContractQueryPort.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        F02ReviewProgressService progress = mock(F02ReviewProgressService.class);
        Contract first = LocalContractFixtures.contracts().get(0);
        Contract second = LocalContractFixtures.contracts().get(1);
        AuditTask insufficient = task(first, AuditTaskStatus.DONE, "70000000-0000-0000-0000-000000000020");
        AuditTask normal = task(second, AuditTaskStatus.DONE, "70000000-0000-0000-0000-000000000021");
        Project project = new Project(UUID.randomUUID(), "智慧维修项目", "XM-WX-001", "服务", "creator", 1,
                Instant.parse("2026-08-26T08:00:00Z"), Instant.parse("2026-08-26T08:30:00Z"));
        ProjectContract association = new ProjectContract(project.id(), first.id(), UUID.randomUUID(),
                "creator", Instant.parse("2026-08-26T08:45:00Z"));
        when(tasks.findAllLatest("CONTRACT", ContractReviewService.REVIEW_TYPE))
                .thenReturn(List.of(insufficient, normal));
        when(contracts.findById(first.id())).thenReturn(Optional.of(first));
        when(contracts.findById(second.id())).thenReturn(Optional.of(second));
        when(associations.findByContractId(first.id())).thenReturn(Optional.of(association));
        when(associations.findByContractId(second.id())).thenReturn(Optional.empty());
        when(projects.findById(project.id())).thenReturn(Optional.of(project));
        when(progress.summarize(first.id(), insufficient))
                .thenReturn(new F02ReviewProgressSummary(4, 6, false, true, 1, true, "低风险"));
        when(progress.summarize(second.id(), normal))
                .thenReturn(new F02ReviewProgressSummary(6, 6, true, false, 0, false, "无风险"));

        ContractAuditTaskPage page = new ContractAuditTaskListService(
                tasks, contracts, associations, projects, progress).list(new ContractAuditTaskListQuery(
                        1, 20, "智慧维修", null, null,
                        ContractAuditTaskListQuery.RiskFilter.INSUFFICIENT,
                        ContractAuditTaskListQuery.StatusFilter.NEEDS_REVIEW));

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).singleElement().satisfies(row -> {
            assertThat(row.task()).isEqualTo(insufficient);
            assertThat(row.project()).isEqualTo(project);
        });
    }

    @Test
    void composesTheLatestDirectF02TaskWithItsRealContractProjectAndDurableProgress() {
        AuditTaskReadQueryPort tasks = mock(AuditTaskReadQueryPort.class);
        ContractRepository contracts = mock(ContractRepository.class);
        ProjectContractQueryPort associations = mock(ProjectContractQueryPort.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        F02ReviewProgressService progress = mock(F02ReviewProgressService.class);
        Contract contract = LocalContractFixtures.contracts().get(0);
        UUID taskId = UUID.fromString("70000000-0000-0000-0000-000000000001");
        UUID projectId = UUID.fromString("71000000-0000-0000-0000-000000000001");
        AuditTask task = new AuditTask(taskId, "CONTRACT", contract.id(), contract.versionNo(),
                ContractReviewService.REVIEW_TYPE, AuditTaskStatus.DONE, "auditor", "request", "trace",
                Instant.parse("2026-08-26T09:00:00Z"), Instant.parse("2026-08-26T09:00:01Z"),
                Instant.parse("2026-08-26T09:00:02Z"), 2);
        Project project = new Project(projectId, "真实项目", "XM-001", "采购", "creator", 1,
                Instant.parse("2026-08-26T08:00:00Z"), Instant.parse("2026-08-26T08:30:00Z"));
        ProjectContract association = new ProjectContract(projectId, contract.id(), UUID.randomUUID(),
                "creator", Instant.parse("2026-08-26T08:45:00Z"));
        F02ReviewProgressSummary summary = new F02ReviewProgressSummary(5, 8, false, true, 1, "低风险");

        when(tasks.findLatest("CONTRACT", ContractReviewService.REVIEW_TYPE, 1, 20))
                .thenReturn(new AuditTaskReadQueryPort.Page(List.of(task), 1, 20, 1));
        when(contracts.findById(contract.id())).thenReturn(Optional.of(contract));
        when(associations.findByContractId(contract.id())).thenReturn(Optional.of(association));
        when(projects.findById(projectId)).thenReturn(Optional.of(project));
        when(progress.summarize(contract.id(), task)).thenReturn(summary);

        ContractAuditTaskPage page = new ContractAuditTaskListService(
                tasks, contracts, associations, projects, progress).list(1, 20);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).singleElement().satisfies(row -> {
            assertThat(row.task()).isEqualTo(task);
            assertThat(row.contract()).isEqualTo(contract);
            assertThat(row.project()).isEqualTo(project);
            assertThat(row.reviewProgress()).isEqualTo(summary);
        });
        verify(tasks).findLatest("CONTRACT", "F02_DETERMINISTIC", 1, 20);
    }

    @Test
    void leavesProjectNullWhenTheFormalContractHasNoProjectAssociation() {
        AuditTaskReadQueryPort tasks = mock(AuditTaskReadQueryPort.class);
        ContractRepository contracts = mock(ContractRepository.class);
        ProjectContractQueryPort associations = mock(ProjectContractQueryPort.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        F02ReviewProgressService progress = mock(F02ReviewProgressService.class);
        Contract contract = LocalContractFixtures.contracts().get(0);
        AuditTask task = new AuditTask(UUID.randomUUID(), "CONTRACT", contract.id(), contract.versionNo(),
                ContractReviewService.REVIEW_TYPE, AuditTaskStatus.PENDING, "auditor", "request", "trace",
                Instant.parse("2026-08-26T09:00:00Z"), null, null, 0);
        F02ReviewProgressSummary summary = new F02ReviewProgressSummary(0, 1, false, false, 0, "无风险");

        when(tasks.findLatest("CONTRACT", ContractReviewService.REVIEW_TYPE, 1, 20))
                .thenReturn(new AuditTaskReadQueryPort.Page(List.of(task), 1, 20, 1));
        when(contracts.findById(contract.id())).thenReturn(Optional.of(contract));
        when(associations.findByContractId(contract.id())).thenReturn(Optional.empty());
        when(progress.summarize(contract.id(), task)).thenReturn(summary);

        ContractAuditTaskPage page = new ContractAuditTaskListService(
                tasks, contracts, associations, projects, progress).list(1, 20);

        assertThat(page.items()).singleElement().extracting(ContractAuditTaskView::project).isNull();
    }

    private static AuditTask task(Contract contract, AuditTaskStatus status, String id) {
        Instant createdAt = Instant.parse("2026-08-26T09:00:00Z");
        Instant startedAt = status == AuditTaskStatus.PENDING ? null : createdAt.plusSeconds(1);
        Instant finishedAt = status == AuditTaskStatus.DONE || status == AuditTaskStatus.FAILED
                ? createdAt.plusSeconds(2) : null;
        return new AuditTask(UUID.fromString(id), "CONTRACT", contract.id(), contract.versionNo(),
                ContractReviewService.REVIEW_TYPE, status, "auditor", "request", "trace",
                createdAt, startedAt, finishedAt, status == AuditTaskStatus.PENDING ? 0 : 1);
    }
}
