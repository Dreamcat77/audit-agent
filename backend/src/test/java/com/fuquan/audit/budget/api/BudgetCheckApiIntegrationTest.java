package com.fuquan.audit.budget.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuquan.audit.security.domain.CurrentUser;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class BudgetCheckApiIntegrationTest {

    private static final UUID PROC_PASS = UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.fuquan.audit.budget.application.port.BudgetReviewTaskRepository historyTasks;

    @Test
    void anonymousBudgetCheckIs401() throws Exception {
        mockMvc.perform(post("/api/v1/budget/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"procurementId\":\"" + PROC_PASS + "\"}")
                        .with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void authenticatedUserCanCheckBudget() throws Exception {
        mockMvc.perform(post("/api/v1/budget/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"procurementId\":\"" + PROC_PASS + "\"}")
                        .with(authentication(authForRole("SYSTEM_ADMIN")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.procurementId").value(PROC_PASS.toString()))
                .andExpect(jsonPath("$.data.allPassed").value(true))
                .andExpect(jsonPath("$.data.totalChecks").value(4))
                .andExpect(jsonPath("$.data.passedChecks").value(4))
                .andExpect(jsonPath("$.data.items.length()").value(4))
                .andExpect(jsonPath("$.data.items").isArray());
    }

    @Test
    void userWithoutRoleIs403() throws Exception {
        mockMvc.perform(post("/api/v1/budget/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"procurementId\":\"" + PROC_PASS + "\"}")
                        .with(authentication(authForRole("VIEWER")))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void missingProcurementIdIs400() throws Exception {
        mockMvc.perform(post("/api/v1/budget/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(authentication(authForRole("SYSTEM_ADMIN")))
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void malformedProcurementIdIs400() throws Exception {
        mockMvc.perform(post("/api/v1/budget/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"procurementId\":\"not-a-uuid\"}")
                .with(authentication(authForRole("SYSTEM_ADMIN")))
                .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("procurementId must be a valid UUID"));
    }

    @Test
    void unknownProcurementIdIs404() throws Exception {
        mockMvc.perform(post("/api/v1/budget/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"procurementId\":\"00000000-0000-0000-0000-000000000999\"}")
                        .with(authentication(authForRole("SYSTEM_ADMIN")))
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message")
                        .value("procurement demand not found: 00000000-0000-0000-0000-000000000999"));
    }

    @Test
    void authenticatedUserCanUploadAndReviewExcelMaterial() throws Exception {
        MockMultipartFile material = new MockMultipartFile(
                "file",
                "budget.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                excelMaterial());

        String uploadBody = mockMvc.perform(multipart("/api/v1/budget/materials")
                        .file(material)
                        .with(authentication(authForRole("SYSTEM_ADMIN")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("PARSED"))
                .andExpect(jsonPath("$.data.parsedLineCount").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode upload = objectMapper.readTree(uploadBody);
        String taskId = upload.path("data").path("taskId").asText();

        mockMvc.perform(post("/api/v1/budget/reviews/" + taskId)
                        .with(authentication(authForRole("SYSTEM_ADMIN")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task.status").value("REVIEWED"))
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.passedItems").value(1))
                .andExpect(jsonPath("$.data.items[0].matchStatus").value("MATCHED"))
                .andExpect(jsonPath("$.data.items[0].matchMethod").value("EXACT"))
                .andExpect(jsonPath("$.data.items[0].amountStatus").value("PASSED"))
                .andExpect(jsonPath("$.data.items[0].checks.length()").value(10))
                .andExpect(jsonPath("$.data.items[0].checks[0].checkType").value("REQUIRED_FIELDS"))
                .andExpect(jsonPath("$.data.items[0].checks[0].passed").value(true))
                .andExpect(jsonPath("$.data.items[0].checks[2].checkType").value("SUBJECT_CODE"))
                .andExpect(jsonPath("$.data.items[0].checks[2].passed").value(true))
                .andExpect(jsonPath("$.data.items[0].checks[9].checkType").value("BUDGET_YEAR_VALIDITY"))
                .andExpect(jsonPath("$.data.items[0].checks[9].passed").value(true))
                .andExpect(jsonPath("$.data.items[0].moduleGroups.length()").value(5));
    }

    @Test
    void missingProjectCodeIsNotCountedAsPassed() throws Exception {
        MockMultipartFile material = new MockMultipartFile(
                "file",
                "budget-missing-project.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                excelMaterial(false));

        String uploadBody = mockMvc.perform(multipart("/api/v1/budget/materials")
                        .file(material)
                        .with(authentication(authForRole("SYSTEM_ADMIN")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String taskId = objectMapper.readTree(uploadBody).path("data").path("taskId").asText();

        mockMvc.perform(post("/api/v1/budget/reviews/" + taskId)
                        .with(authentication(authForRole("SYSTEM_ADMIN")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passedItems").value(0))
                .andExpect(jsonPath("$.data.exceptionItems").value(1))
                .andExpect(jsonPath("$.data.items[0].riskLevel").value("WARNING"))
                .andExpect(jsonPath("$.data.items[0].missingFields").value("项目编号"));
    }

    @Test
    void exceptionPausesUntilConfirmedThenWritesConfirmLog() throws Exception {
        MockMultipartFile material = new MockMultipartFile(
                "file",
                "budget-over.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                excelMaterialWithAmount("160000", false));

        String uploadBody = mockMvc.perform(multipart("/api/v1/budget/materials")
                        .file(material)
                        .with(authentication(authForRole("SYSTEM_ADMIN")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String taskId = objectMapper.readTree(uploadBody).path("data").path("taskId").asText();

        mockMvc.perform(post("/api/v1/budget/reviews/" + taskId)
                        .with(authentication(authForRole("SYSTEM_ADMIN")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task.status").value("WAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.data.task.pendingRowNumber").value(1))
                .andExpect(jsonPath("$.data.task.pendingStep").value("REQUIRED_FIELDS"))
                .andExpect(jsonPath("$.data.items[0].amountStatus").value("NOT_CHECKED"))
                .andExpect(jsonPath("$.data.items[0].missingFields").value("项目编号"))
                .andExpect(jsonPath("$.data.items[0].currentStep").value(1))
                .andExpect(jsonPath("$.data.items[0].abnormalReasons.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].checks[0].status").value("FAILED"))
                .andExpect(jsonPath("$.data.items[0].checks[1].status").value("PENDING"))
                .andExpect(jsonPath("$.data.items[0].checks[8].status").value("PENDING"));

        mockMvc.perform(post("/api/v1/budget/validation/confirm-row")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "batchId": "%s",
                                  "rowNumber": 1,
                                  "remark": "已核实"
                                }
                                """.formatted(taskId))
                        .with(authentication(authForRole("SYSTEM_ADMIN")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmed").value(true))
                .andExpect(jsonPath("$.data.executionStatus").value("PAUSED"))
                .andExpect(jsonPath("$.data.hasMoreException").value(true))
                .andExpect(jsonPath("$.data.nextStep").value("BUDGET_COMPARE"))
                .andExpect(jsonPath("$.data.updatedRow.currentStep").value(6));

        mockMvc.perform(post("/api/v1/budget/validation/confirm-row")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "batchId": "%s",
                                  "rowNumber": 1,
                                  "remark": "重复请求"
                                }
                                """.formatted(taskId))
                        .with(authentication(authForRole("SYSTEM_ADMIN")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmed").value(true))
                .andExpect(jsonPath("$.data.executionStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data.hasMoreException").value(false))
                .andExpect(jsonPath("$.data.updatedRow.currentStep").value(10));

        mockMvc.perform(get("/api/v1/budget/reviews/" + taskId)
                        .with(authentication(authForRole("SYSTEM_ADMIN")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task.status").value("REVIEWED"));

        mockMvc.perform(get("/api/v1/budget/validation/confirm-logs")
                        .param("batchId", taskId)
                        .with(authentication(authForRole("SYSTEM_ADMIN")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].rowNumber").value(1))
                .andExpect(jsonPath("$.data[0].confirmStep").value("DATA_VALIDATION"))
                .andExpect(jsonPath("$.data[0].abnormalReason")
                        .value("必填信息缺失：项目编号"))
                .andExpect(jsonPath("$.data[1].confirmStep").value("AMOUNT_COMPLIANCE"))
                .andExpect(jsonPath("$.data[1].abnormalReason")
                        .value("必填信息缺失：项目编号；超预算：申报金额超过预算批复金额"));
    }

    @Test
    void authenticatedUserCanUploadValidateAndQueryValidationRecord() throws Exception {
        MockMultipartFile material = new MockMultipartFile(
                "file",
                "budget.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                excelMaterial());

        String response = mockMvc.perform(multipart("/api/v1/budget/upload-and-validate")
                        .file(material)
                        .param("budgetYear", "2026")
                        .param("departmentCode", "PROCUREMENT")
                        .with(authentication(authForRole("SYSTEM_ADMIN")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalRows").value(1))
                .andExpect(jsonPath("$.data.validRows").value(1))
                .andExpect(jsonPath("$.data.infoCount").value(1))
                .andExpect(jsonPath("$.data.details[0].riskLevel").value("INFO"))
                .andExpect(jsonPath("$.data.summary.overBudgetCount").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String batchId = objectMapper.readTree(response).path("data").path("batchId").asText();
        mockMvc.perform(get("/api/v1/budget/validation-records/" + batchId)
                        .with(authentication(authForRole("SYSTEM_ADMIN")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.batchId").value(batchId))
                .andExpect(jsonPath("$.data.details.length()").value(1));

        mockMvc.perform(get("/api/v1/budget/validation-records")
                        .param("budgetYear", "2026")
                        .param("departmentCode", "PROCUREMENT")
                        .param("page", "1")
                        .param("size", "10")
                        .with(authentication(authForRole("SYSTEM_ADMIN")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    private static byte[] excelMaterial() throws Exception {
        return excelMaterial(true);
    }

    @Test
    void historyNameAndUploadDatesAreValidatedAndReturned() throws Exception {
        var time = java.time.Instant.parse("2026-10-14T15:59:59Z");
        var id = UUID.randomUUID();
        historyTasks.save(new com.fuquan.audit.budget.domain.BudgetReviewTask(id, "专项_100%.xlsx",
                "application/octet-stream", 1L, null, "REVIEWED", 0, null, "2026", "FINANCE", time, time));
        mockMvc.perform(get("/api/v1/budget/validation-records").param("fileName", "专项_100%")
                        .param("createdFrom", "2026-10-14").param("createdTo", "2026-10-14")
                        .with(authentication(authForRole("SYSTEM_ADMIN"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].batchId").value(id.toString()))
                .andExpect(jsonPath("$.data.records[0].createdTime").value(time.toString()));
        mockMvc.perform(get("/api/v1/budget/validation-records").param("createdFrom", "2026-10-15")
                        .param("createdTo", "2026-10-14").with(authentication(authForRole("SYSTEM_ADMIN"))))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/v1/budget/validation-records").param("createdFrom", "invalid")
                        .with(authentication(authForRole("SYSTEM_ADMIN"))))
                .andExpect(status().isBadRequest());
    }

    private static byte[] excelMaterial(boolean includeProjectCode) throws Exception {
        return excelMaterialWithAmount("150000", includeProjectCode);
    }

    private static byte[] excelMaterialWithAmount(String amount) throws Exception {
        return excelMaterialWithAmount(amount, true);
    }

    private static byte[] excelMaterialWithAmount(
            String amount, boolean includeProjectCode) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("预算");
            sheet.createRow(0).createCell(0).setCellValue("科目编码");
            sheet.getRow(0).createCell(1).setCellValue("科目名称");
            sheet.getRow(0).createCell(2).setCellValue("申报金额");
            sheet.getRow(0).createCell(3).setCellValue("供应商名称");
            sheet.getRow(0).createCell(4).setCellValue("项目编号");
            sheet.createRow(1).createCell(0).setCellValue("M1001");
            sheet.getRow(1).createCell(1).setCellValue("办公设备及耗材采购");
            sheet.getRow(1).createCell(2).setCellValue(amount);
            sheet.getRow(1).createCell(3).setCellValue("某供应商A");
            if (includeProjectCode) {
                sheet.getRow(1).createCell(4).setCellValue("P001");
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static Authentication authForRole(String role) {
        CurrentUser user = new CurrentUser(
                "budget-test-user", "budget.test", "Budget Test", Set.of(role));
        return UsernamePasswordAuthenticationToken.authenticated(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
