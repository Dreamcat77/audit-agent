package com.fuquan.audit.procurement.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import com.fuquan.audit.security.domain.CurrentUser;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Set;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ProcurementProjectApiIntegrationTest {

    private static final String PROJECT_ID = "00000000-0000-0000-0000-000000000101";
    private static final String SOURCE_PROJECT_ID = "00000000-0000-0000-0000-000000000901";
    private static final String SOURCE_PROJECT_NAME = "本地已解析采购项目";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.fuquan.audit.common.material.application.port.MaterialIntakeRepository materialRepository;
    @Autowired private com.fuquan.audit.procurement.infra.memory.InMemoryProcurementProjectQueryAdapter localProjects;

    @org.junit.jupiter.api.BeforeEach
    void seedTestOnlyParsedMaterial() {
        seedMaterial(SOURCE_PROJECT_ID, "procurement-project-test");
        seedMaterial("00000000-0000-0000-0000-000000000902", "same-user");
        seedMaterial("00000000-0000-0000-0000-000000000903", "applicant-user");
    }
    private void seedMaterial(String sourceId, String actorId) {
        var id = java.util.UUID.fromString(sourceId);
        localProjects.findPage(null, null, 0, Integer.MAX_VALUE).items().stream()
                .filter(p -> id.equals(p.sourceProjectId())).forEach(p -> localProjects.delete(p.id()));
        if (materialRepository.find(id).isPresent()) return;
        var now = java.time.Instant.now();
        var fields = new com.fuquan.audit.common.material.domain.MaterialIntakeFields(SOURCE_PROJECT_NAME,
                "SRC-LOCAL-001", null, "货物", null, null, null, null, null, null,
                java.util.Map.of(), List.of(), "test-model", "test-prompt");
        materialRepository.insert(new com.fuquan.audit.common.material.domain.MaterialIntake(id, actorId,
                id.toString(), "test-source.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                1, "a".repeat(64), "b".repeat(64), "PARSED", id.toString(), now,
                fields, "DOCX_TEXT", null, now, now));
    }

    @Test
    void sourceProjectListReturnsOnlyEligibleProjectIdentity() throws Exception {
        mockMvc.perform(get("/api/v1/procurement/projects/source-projects")
                        .with(authentication(authForRole("PROCUREMENT_APPLICANT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(SOURCE_PROJECT_ID))
                .andExpect(jsonPath("$.data[0].name").value(SOURCE_PROJECT_NAME))
                .andExpect(jsonPath("$.data[0].projectNo").value("SRC-LOCAL-001"));
    }

    @Test
    void listReturnsProjectsWithPaginationAndFilters() throws Exception {
        mockMvc.perform(get("/api/v1/procurement/projects")
                        .param("page", "1")
                        .param("size", "1")
                        .param("status", "APPROVED")
                        .param("keyword", "办公")
                        .with(authentication(authForRole("AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(PROJECT_ID))
                .andExpect(jsonPath("$.data.items[0].projectCode").value("BA-2024-001"))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void agentReviewIsGeneratedByPostAndThenReturnedFromGet() throws Exception {
        mockMvc.perform(post("/api/v1/procurement/projects/{projectId}/agent-review", PROJECT_ID)
                        .param("refresh", "true")
                        .with(authentication(authForRole("AUDITOR")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.projectId").value(PROJECT_ID));

        mockMvc.perform(get("/api/v1/procurement/projects/{projectId}/agent-review", PROJECT_ID)
                        .with(authentication(authForRole("AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.projectId").value(PROJECT_ID))
                .andExpect(jsonPath("$.data.projectName").value("2024年办公设备采购"))
                .andExpect(jsonPath("$.data.riskLevel").value("INFO"))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.suggestion").value("已检查的基础信息未发现需要核对的问题"));
    }

    @Test
    void exportReturnsProcurementDemandExcelWithExpectedFields() throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/api/v1/procurement/projects/{projectId}/export", PROJECT_ID)
                                .with(authentication(authForRole("AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(content().contentType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        Matchers.containsString("BA-2024-001_")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        Matchers.containsString(".xlsx")))
                .andReturn();

        try (XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            var sheet = workbook.getSheet("采购需求");
            org.assertj.core.api.Assertions.assertThat(sheet).isNotNull();
            org.assertj.core.api.Assertions.assertThat(sheet.getRow(1).getCell(0).getStringCellValue())
                    .isEqualTo("项目编号");
            org.assertj.core.api.Assertions.assertThat(sheet.getRow(1).getCell(1).getStringCellValue())
                    .isEqualTo("BA-2024-001");
            org.assertj.core.api.Assertions.assertThat(sheet.getRow(2).getCell(1).getStringCellValue())
                    .isEqualTo("2024年办公设备采购");
            org.assertj.core.api.Assertions.assertThat(sheet.getRow(5).getCell(1).getStringCellValue())
                    .isEqualTo("150000.00");
            org.assertj.core.api.Assertions.assertThat(sheet.getRow(12).getCell(0).getStringCellValue())
                    .isEqualTo("项目状态");
        }
    }

    @Test
    void createReturnsIdAndCreatedProjectCanBeListedAndReviewed() throws Exception {
        String projectName = "接口测试采购需求-" + System.nanoTime();
        String request = """
                {
                  "sourceProjectId": "00000000-0000-0000-0000-000000000901",
                  "departmentName": "信息中心",
                  "procurementType": "货物",
                  "budgetAmount": 128000.00,
                  "subjectCode": "A1001",
                  "subjectName": "办公设备",
                  "procurementMethod": "公开招标",
                  "requirementDescription": "采购测试用办公设备",
                  "applicantName": "测试申请人",
                  "attachmentName": "requirements.pdf"
                }
                """.formatted(projectName);

        MvcResult created = mockMvc.perform(post("/api/v1/procurement/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .with(authentication(authForRole("PROCUREMENT_MANAGER")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").isString())
                .andReturn();

        String projectId = com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.data.id");

        mockMvc.perform(get("/api/v1/procurement/projects")
                        .param("page", "1")
                        .param("size", "10")
                        .param("keyword", SOURCE_PROJECT_NAME)
                        .with(authentication(authForRole("AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(projectId))
                .andExpect(jsonPath("$.data.items[0].projectName").value(SOURCE_PROJECT_NAME))
                .andExpect(jsonPath("$.data.items[0].sourceProjectId").value(SOURCE_PROJECT_ID))
                .andExpect(jsonPath("$.data.items[0].sourceMaterialIds[0]").value(SOURCE_PROJECT_ID))
                .andExpect(jsonPath("$.data.items[0].departmentName").value("信息中心"))
                .andExpect(jsonPath("$.data.items[0].supplierName").isEmpty())
                .andExpect(jsonPath("$.data.items[0].status").value("DRAFT"));

        mockMvc.perform(get("/api/v1/procurement/projects/{projectId}/approval", projectId)
                        .with(authentication(authForRole("AUDITOR"))))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/procurement/projects/{projectId}/agent-review", projectId)
                        .with(authentication(authForRole("AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());

        mockMvc.perform(post("/api/v1/procurement/projects/{projectId}/agent-review", projectId)
                        .param("refresh", "true")
                        .with(authentication(authForRole("AUDITOR")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.projectId").value(projectId))
                .andExpect(jsonPath("$.data.projectName").value(SOURCE_PROJECT_NAME));

        mockMvc.perform(get("/api/v1/procurement/projects/{projectId}/agent-review", projectId)
                        .with(authentication(authForRole("AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.projectId").value(projectId));
    }

    @Test
    void createWithSubmitForApprovalStartsWorkflowAndMovesProjectToReviewing() throws Exception {
        String projectName = "创建直提审批测试-" + System.nanoTime();
        String request = """
                {
                  "sourceProjectId": "00000000-0000-0000-0000-000000000901",
                  "departmentName": "信息中心",
                  "procurementType": "货物",
                  "budgetAmount": 128000.00,
                  "subjectCode": "A1001",
                  "subjectName": "办公设备",
                  "procurementMethod": "公开招标",
                  "requirementDescription": "创建后直接进入审批",
                  "applicantName": "测试申请人",
                  "submitForApproval": true
                }
                """.formatted(projectName);

        MvcResult created = mockMvc.perform(post("/api/v1/procurement/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .with(authentication(authForRole("PROCUREMENT_MANAGER")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        String projectId = com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.data.id");

        mockMvc.perform(get("/api/v1/procurement/projects")
                        .param("page", "1")
                        .param("size", "10")
                        .param("keyword", SOURCE_PROJECT_NAME)
                        .with(authentication(authForRole("AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].status").value("REVIEWING"));

        mockMvc.perform(get("/api/v1/procurement/projects/{projectId}/approval", projectId)
                        .with(authentication(authForRole("AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
    }

    @Test
    void createRejectsMissingRequiredFields() throws Exception {
        mockMvc.perform(post("/api/v1/procurement/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectName\":\"缺少信息\"}")
                        .with(authentication(authForRole("PROCUREMENT_MANAGER")))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.data.departmentName").value("must not be blank"));
    }

    @Test
    void approvalApiMovesNodeAndReturnsHistory() throws Exception {
        String projectName = "审批接口测试-" + System.nanoTime();
        String request = """
                {
                  "sourceProjectId": "00000000-0000-0000-0000-000000000901",
                  "departmentName": "审计中心",
                  "procurementType": "服务",
                  "budgetAmount": 9000.00,
                  "subjectCode": "S1001",
                  "subjectName": "审计服务",
                  "procurementMethod": "竞争性磋商",
                  "applicantName": "审批接口申请人"
                }
                """.formatted(projectName);

        MvcResult created = mockMvc.perform(post("/api/v1/procurement/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .with(authentication(authForRole("PROCUREMENT_MANAGER")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        String projectId = com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.data.id");

        mockMvc.perform(post("/api/v1/procurement/projects/{projectId}/approval/start", projectId)
                        .with(authentication(authForRole("PROCUREMENT_MANAGER")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));

        mockMvc.perform(post("/api/v1/procurement/projects/{projectId}/approval/approve", projectId)
                        .with(authentication(authForRole("SYSTEM_ADMIN", "different-admin")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.currentNodeCode").value("BUDGET_REVIEW"));

        mockMvc.perform(get("/api/v1/procurement/projects/{projectId}/approval/history", projectId)
                        .with(authentication(authForRole("AUDITOR", "different-auditor"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()" ).value(2))
                .andExpect(jsonPath("$.data[1].operationType").value("APPROVE"));
    }

    @Test
    void applicantCannotApproveOwnWorkflow() throws Exception {
        String projectName = "审批权限测试-" + System.nanoTime();
        String request = """
                {
                  "sourceProjectId": "00000000-0000-0000-0000-000000000901",
                  "departmentName": "审计中心",
                  "procurementType": "服务",
                  "budgetAmount": 9000.00,
                  "subjectCode": "S1001",
                  "subjectName": "审计服务",
                  "procurementMethod": "竞争性磋商",
                  "applicantName": "审批权限申请人"
                }
                """.formatted(projectName);
        MvcResult created = mockMvc.perform(post("/api/v1/procurement/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request.replace(SOURCE_PROJECT_ID, "00000000-0000-0000-0000-000000000902"))
                        .with(authentication(authForRole("PROCUREMENT_MANAGER", "same-user")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        String projectId = com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.data.id");

        mockMvc.perform(post("/api/v1/procurement/projects/{projectId}/approval/start", projectId)
                        .with(authentication(authForRole("PROCUREMENT_MANAGER", "same-user")))
                        .with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/procurement/projects/{projectId}/approval/approve", projectId)
                        .with(authentication(authForRole("AUDITOR", "same-user")))
                        .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void returnedProjectCanBeEditedAndStartsANewApprovalInstance() throws Exception {
        String projectName = "退回重提测试-" + System.nanoTime();
        String createRequest = """
                {
                  "sourceProjectId": "00000000-0000-0000-0000-000000000901",
                  "departmentName": "采购中心",
                  "procurementType": "货物",
                  "budgetAmount": 30000.00,
                  "subjectCode": "R1001",
                  "subjectName": "退回重提测试",
                  "procurementMethod": "询价",
                  "applicantName": "测试申请人"
                }
                """.formatted(projectName);
        MvcResult created = mockMvc.perform(post("/api/v1/procurement/projects")
                        .contentType(MediaType.APPLICATION_JSON).content(createRequest.replace(SOURCE_PROJECT_ID, "00000000-0000-0000-0000-000000000903"))
                        .with(authentication(authForRole("PROCUREMENT_MANAGER", "applicant-user"))).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        String projectId = com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.data.id");

        MvcResult firstStart = mockMvc.perform(post(
                        "/api/v1/procurement/projects/{projectId}/approval/start", projectId)
                        .with(authentication(authForRole("PROCUREMENT_APPLICANT", "applicant-user"))).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        String firstInstanceId = com.jayway.jsonpath.JsonPath.read(
                firstStart.getResponse().getContentAsString(), "$.data.id");

        mockMvc.perform(post("/api/v1/procurement/projects/{projectId}/approval/return", projectId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"opinion\":\"请补充技术要求\"}")
                        .with(authentication(authForRole("SYSTEM_ADMIN", "admin-user"))).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RETURNED"));

        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .with(authentication(authForRole("PROCUREMENT_APPLICANT", "applicant-user"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
        mockMvc.perform(get("/api/v1/notifications").param("unreadOnly", "true")
                        .with(authentication(authForRole("PROCUREMENT_APPLICANT", "applicant-user"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].type").value("PENDING_MODIFICATION"))
                .andExpect(jsonPath("$.data[0].opinion").value("请补充技术要求"));
        mockMvc.perform(get("/api/v1/procurement/approval/tasks")
                        .with(authentication(authForRole("PROCUREMENT_APPLICANT", "applicant-user"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.projectId == '%s')].taskType".formatted(projectId))
                        .value(org.hamcrest.Matchers.hasItem("PENDING_MODIFICATION")));

        mockMvc.perform(get("/api/v1/procurement/projects").param("keyword", SOURCE_PROJECT_NAME)
                        .with(authentication(authForRole("PROCUREMENT_APPLICANT", "applicant-user"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].status").value("DRAFT"));

        mockMvc.perform(post("/api/v1/procurement/projects/{projectId}/approval/start", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modificationNote\":\"已按意见处理\"}")
                        .with(authentication(authForRole("PROCUREMENT_APPLICANT", "applicant-user"))).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("未检测到针对退回意见的修改，请修改后再提交"));

        mockMvc.perform(get("/api/v1/audit/operations")
                        .param("userId", "applicant-user")
                        .param("action", "START_APPROVAL")
                        .param("resourceType", "APPROVAL")
                        .with(authentication(authForRole("SYSTEM_ADMIN", "admin-user"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].resultStatus")
                        .value(org.hamcrest.Matchers.hasItem("FAILURE")))
                .andExpect(jsonPath("$.data[?(@.resultStatus == 'FAILURE')].failureReason")
                        .value(org.hamcrest.Matchers.hasItem(
                                "未检测到针对退回意见的修改，请修改后再提交")));

        String updateRequest = """
                {
                  "projectName": "本地已解析采购项目",
                  "departmentName": "采购中心",
                  "procurementType": "货物",
                  "budgetAmount": 30000.00,
                  "subjectCode": "R1001",
                  "subjectName": "退回重提测试（已修改）",
                  "procurementMethod": "询价",
                  "applicantName": "测试申请人"
                }
                """;
        mockMvc.perform(put("/api/v1/procurement/projects/{projectId}", projectId)
                        .contentType(MediaType.APPLICATION_JSON).content(updateRequest)
                        .with(authentication(authForRole("PROCUREMENT_APPLICANT", "applicant-user"))).with(csrf()))
                .andExpect(status().isOk());

        MvcResult secondStart = mockMvc.perform(post(
                        "/api/v1/procurement/projects/{projectId}/approval/start", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modificationNote\":\"补充预算科目名称\"}")
                        .with(authentication(authForRole("PROCUREMENT_APPLICANT", "applicant-user"))).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.nodes[0].status").value("COMPLETED"))
                .andReturn();
        String secondInstanceId = com.jayway.jsonpath.JsonPath.read(
                secondStart.getResponse().getContentAsString(), "$.data.id");
        org.assertj.core.api.Assertions.assertThat(secondInstanceId).isNotEqualTo(firstInstanceId);

        mockMvc.perform(get("/api/v1/procurement/approval/tasks")
                        .with(authentication(authForRole("PROCUREMENT_APPLICANT", "applicant-user"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.projectId == '%s')]".formatted(projectId)).isEmpty());

        mockMvc.perform(get("/api/v1/procurement/projects/{projectId}/approval/history", projectId)
                        .with(authentication(authForRole("PROCUREMENT_APPLICANT", "applicant-user"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].operationType").value("START"))
                .andExpect(jsonPath("$.data[1].operationType").value("RETURN"))
                .andExpect(jsonPath("$.data[2].operationType").value("START"))
                .andExpect(jsonPath("$.data[2].opinion").value("修改说明：补充预算科目名称"));
    }

    @Test
    void reviewReportAggregatesExistingModulesAndCanBeQueried() throws Exception {
        mockMvc.perform(post("/api/v1/procurement/projects/{projectId}/review-report", PROJECT_ID)
                        .with(authentication(authForRole("AUDITOR")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.projectId").value(PROJECT_ID))
                .andExpect(jsonPath("$.data.reportNumber").value(org.hamcrest.Matchers.startsWith("PCR-")))
                .andExpect(jsonPath("$.data.riskLevel").value("INFO"))
                .andExpect(jsonPath("$.data.overallConclusion").isNotEmpty())
                .andExpect(jsonPath("$.data.agentReview.items").isArray())
                .andExpect(jsonPath("$.data.quoteCheck").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.blacklist.alertCount").value(0));

        mockMvc.perform(get("/api/v1/procurement/projects/{projectId}/review-report", PROJECT_ID)
                        .with(authentication(authForRole("AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.projectId").value(PROJECT_ID))
                .andExpect(jsonPath("$.data.reportNumber").value(org.hamcrest.Matchers.startsWith("PCR-")));
    }

    @Test
    void criticalRiskConfirmationRejectsNonCriticalReportAndUnauthorizedRole() throws Exception {
        MvcResult generated = mockMvc.perform(
                        post("/api/v1/procurement/projects/{projectId}/review-report", PROJECT_ID)
                                .with(authentication(authForRole("AUDITOR")))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.riskLevel").value("INFO"))
                .andReturn();
        String reportId = com.jayway.jsonpath.JsonPath.read(
                generated.getResponse().getContentAsString(), "$.data.id");
        String request = """
                {
                  "idempotencyKey": "critical-risk-confirmation-test",
                  "confirmationNote": "已阅读报告"
                }
                """;

        mockMvc.perform(post(
                        "/api/v1/procurement/projects/{projectId}/review-report/{reportId}/critical-risk-confirmation",
                        PROJECT_ID, reportId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .with(authentication(authForRole("AUDITOR")))
                        .with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("仅严重风险采购审查报告需要人工确认"));

        mockMvc.perform(post(
                        "/api/v1/procurement/projects/{projectId}/review-report/{reportId}/critical-risk-confirmation",
                        PROJECT_ID, reportId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .with(authentication(authForRole("PROCUREMENT_APPLICANT")))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void approvedProjectCannotBeUpdated() throws Exception {
        String request = """
                {
                  "projectName": "不应被修改",
                  "departmentName": "信息中心",
                  "procurementType": "货物",
                  "budgetAmount": 1000.00,
                  "subjectCode": "M1001",
                  "subjectName": "办公设备及耗材采购",
                  "procurementMethod": "询价",
                  "requirementDescription": "不应被修改",
                  "applicantName": "测试申请人"
                }
                """;

        mockMvc.perform(put("/api/v1/procurement/projects/{projectId}", PROJECT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .with(authentication(authForRole("PROCUREMENT_MANAGER")))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void deleteProjectSoftDeletesItFromListAndLookup() throws Exception {
        String projectName = "删除接口测试-" + System.nanoTime();
        String request = """
                {
                  "sourceProjectId": "00000000-0000-0000-0000-000000000901",
                  "departmentName": "信息中心",
                  "procurementType": "货物",
                  "budgetAmount": 1000.00,
                  "subjectCode": "D1001",
                  "subjectName": "删除测试",
                  "procurementMethod": "询价",
                  "applicantName": "测试申请人"
                }
                """.formatted(projectName);

        MvcResult created = mockMvc.perform(post("/api/v1/procurement/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .with(authentication(authForRole("PROCUREMENT_MANAGER")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        String projectId = com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.data.id");

        mockMvc.perform(delete("/api/v1/procurement/projects/{projectId}", projectId)
                        .with(authentication(authForRole("PROCUREMENT_MANAGER")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/v1/procurement/projects")
                        .param("page", "1")
                        .param("size", "10")
                        .param("keyword", SOURCE_PROJECT_NAME)
                .with(authentication(authForRole("AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.id == '%s')]".formatted(projectId)).isEmpty());

        mockMvc.perform(get("/api/v1/procurement/projects/{projectId}/agent-review", projectId)
                        .with(authentication(authForRole("AUDITOR"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteRejectsNonDraftProject() throws Exception {
        mockMvc.perform(delete("/api/v1/procurement/projects/{projectId}", PROJECT_ID)
                        .with(authentication(authForRole("PROCUREMENT_MANAGER")))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("草稿")));
    }

    private static Authentication authForRole(String role) {
        return authForRole(role, "procurement-project-test");
    }

    private static Authentication authForRole(String role, String userId) {
        CurrentUser user = new CurrentUser(
                userId, "project.test", "采购项目测试", Set.of(role));
        return UsernamePasswordAuthenticationToken.authenticated(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
