package com.fuquan.audit.contract.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuquan.audit.contract.application.ContractReviewService;
import com.fuquan.audit.common.application.port.IdempotencyRequest;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import com.fuquan.audit.contract.application.project.port.ContractCreationPort;
import com.fuquan.audit.contract.application.project.port.ProjectContractCommandPort;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.F02RuleCodes;
import com.fuquan.audit.contract.domain.Money;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.contract.domain.project.ProjectContract;
import com.fuquan.audit.review.application.port.AuditCheckResultRepository;
import com.fuquan.audit.review.application.port.AuditTaskRepository;
import com.fuquan.audit.review.domain.AuditCheckResult;
import com.fuquan.audit.review.domain.AuditTask;
import com.fuquan.audit.review.domain.AuditTaskStatus;
import com.fuquan.audit.review.domain.CheckStatus;
import com.fuquan.audit.review.domain.RiskLevel;
import com.fuquan.audit.security.domain.CurrentUser;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class ContractAuditTaskApiIntegrationTest {

    private static final UUID CONTRACT_ID = UUID.fromString("72000000-0000-0000-0000-000000000001");
    private static final UUID PROJECT_ID = UUID.fromString("72000000-0000-0000-0000-000000000002");
    private static final UUID TASK_ID = UUID.fromString("72000000-0000-0000-0000-000000000003");
    private static final Instant CREATED_AT = Instant.parse("2026-08-26T10:00:00Z");

    @Autowired private MockMvc mockMvc;
    @Autowired private ContractCreationPort contractCreation;
    @Autowired private ProjectRepository projects;
    @Autowired private ProjectContractCommandPort associations;
    @Autowired private AuditTaskRepository tasks;
    @Autowired private AuditCheckResultRepository results;
    @Autowired private InMemoryIdempotencyAdapter idempotency;

    @BeforeEach
    void createOneRealDirectF02Task() {
        Contract contract = new Contract(CONTRACT_ID, "TASK-CENTER-001", "任务中心真实合同", "GOODS",
                "采购单位", "真实供应商", "91350000TASKCENTER", Money.of(new BigDecimal("528000.00")),
                "人民币伍拾贰万捌仟元整", "READY_FOR_REVIEW", 1, CREATED_AT, CREATED_AT,
                List.of(), null);
        contractCreation.insertCompleteSnapshot(contract);
        projects.insert(new Project(PROJECT_ID, "真实任务中心项目", "XM-TASK-001", "采购", "creator", 0,
                CREATED_AT.minusSeconds(60), CREATED_AT.minusSeconds(60)));
        associations.insert(new ProjectContract(PROJECT_ID, CONTRACT_ID, UUID.randomUUID(), "creator", CREATED_AT));
        tasks.create(new AuditTask(TASK_ID, "CONTRACT", CONTRACT_ID, 1,
                ContractReviewService.REVIEW_TYPE, AuditTaskStatus.DONE, "task-center-user", "request", "trace",
                CREATED_AT, CREATED_AT.plusSeconds(1), CREATED_AT.plusSeconds(2), 2));
        recordIndependentF02(TASK_ID, CREATED_AT);
        results.appendAll(List.of(new AuditCheckResult(UUID.randomUUID(), TASK_ID,
                F02RuleCodes.AWARD_AMOUNT_MATCH, null, CheckStatus.FAIL, RiskLevel.RED, true,
                "528000.00", "500000.00", "Contract amount differs from award amount", 1,
                CREATED_AT.plusSeconds(2))));
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void realDirectF02TaskIsTheTaskCenterFactAndEndpointKeepsRoleSecurity() throws Exception {
        mockMvc.perform(get("/api/v1/contract-audit-tasks")
                        .with(authentication(authForRole("AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].taskId").value(TASK_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].contract.id").value(CONTRACT_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].contract.contractNo").value("TASK-CENTER-001"))
                .andExpect(jsonPath("$.data.items[0].contract.contractAmount").value(528000.00))
                .andExpect(jsonPath("$.data.items[0].project.projectId").value(PROJECT_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].project.projectName").value("真实任务中心项目"))
                .andExpect(jsonPath("$.data.items[0].taskStatus").value("DONE"))
                .andExpect(jsonPath("$.data.items[0].reviewRun.kind").value("INDEPENDENT_F02"))
                .andExpect(jsonPath("$.data.items[0].reviewProgress.current").value(2))
                .andExpect(jsonPath("$.data.items[0].reviewProgress.total").value(2))
                .andExpect(jsonPath("$.data.items[0].reviewProgress.complete").value(false))
                .andExpect(jsonPath("$.data.items[0].reviewProgress.pendingAcknowledgement").value(true))
                .andExpect(jsonPath("$.data.items[0].risk.riskCount").value(1))
                .andExpect(jsonPath("$.data.items[0].risk.riskSummary").value("低风险"));

        mockMvc.perform(get("/api/v1/contract-audit-tasks/summary")
                        .with(authentication(authForRole("AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.completed").value(1))
                .andExpect(jsonPath("$.data.needsReview").value(1));

        mockMvc.perform(get("/api/v1/contract-audit-tasks")
                        .param("keyword", "TASK-CENTER-001")
                        .param("status", "NEEDS_REVIEW")
                        .with(authentication(authForRole("AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].taskId").value(TASK_ID.toString()));

        mockMvc.perform(get("/api/v1/contract-audit-tasks"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/contract-audit-tasks")
                        .with(authentication(authForRole("VIEWER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void historyOptInRetainsOlderTaskWithoutChangingDefaultLatestContractList() throws Exception {
        UUID newerId = UUID.fromString("72000000-0000-0000-0000-000000000004");
        tasks.create(new AuditTask(newerId, "CONTRACT", CONTRACT_ID, 1,
                ContractReviewService.REVIEW_TYPE, AuditTaskStatus.DONE, "task-center-user", "request-new", "trace-new",
                CREATED_AT.plusSeconds(60), CREATED_AT.plusSeconds(61), CREATED_AT.plusSeconds(62), 2));
        recordIndependentF02(newerId, CREATED_AT.plusSeconds(60));
        results.appendAll(List.of(new AuditCheckResult(UUID.randomUUID(), newerId,
                F02RuleCodes.AWARD_AMOUNT_MATCH, null, CheckStatus.PASS, RiskLevel.GREEN, false,
                "528000.00", "528000.00", "award amount matches", 1, CREATED_AT.plusSeconds(62))));

        mockMvc.perform(get("/api/v1/contract-audit-tasks")
                        .with(authentication(authForRole("AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].taskId").value(newerId.toString()));

        mockMvc.perform(get("/api/v1/contract-audit-tasks")
                        .param("includeHistory", "true")
                        .with(authentication(authForRole("AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].taskId").value(newerId.toString()))
                .andExpect(jsonPath("$.data.items[1].taskId").value(TASK_ID.toString()));
    }

    private Authentication authForRole(String role) {
        CurrentUser user = new CurrentUser("task-center-user", "task.center", "任务中心审核员", Set.of(role));
        return UsernamePasswordAuthenticationToken.authenticated(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private void recordIndependentF02(UUID taskId, Instant startedAt) {
        var claim = idempotency.claim(new IdempotencyRequest(
                ContractReviewService.IDEMPOTENCY_SCOPE, "direct-f02-" + taskId,
                "task-center-user", "CONTRACT", CONTRACT_ID.toString(), "fixture-fingerprint",
                "AUDIT_TASK", taskId.toString(), "request", "trace", startedAt));
        idempotency.markSucceeded(claim.record(), "AUDIT_TASK", taskId.toString(), startedAt.plusSeconds(2));
    }
}
