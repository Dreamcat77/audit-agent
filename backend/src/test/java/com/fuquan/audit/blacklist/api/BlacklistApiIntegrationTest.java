package com.fuquan.audit.blacklist.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.security.domain.CurrentUser;
import java.util.List;
import java.util.Set;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class BlacklistApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void anonymousCheckIs401() throws Exception {
        mockMvc.perform(post("/api/v1/blacklist/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierName\":\"blocked supplier\"}")
                        .with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void userWithoutAllowedRoleIs403() throws Exception {
        mockMvc.perform(get("/api/v1/blacklist/entries")
                        .with(authentication(authForRole("VIEWER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void taskBookPathRemainsCompatible() throws Exception {
        mockMvc.perform(get("/api/blacklist/entries")
                        .with(authentication(authForRole("AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void missingCsrfRejectsEntryMutation() throws Exception {
        mockMvc.perform(post("/api/v1/blacklist/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierName\":\"csrf rejected supplier\"}")
                        .with(authentication(authForRole("SYSTEM_ADMIN"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void authorizedUserCanCreateMatchAndProcessAlert() throws Exception {
        String supplierName = "blacklist-api-regression-supplier";

        mockMvc.perform(post("/api/v1/blacklist/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierName\":\"" + supplierName
                                + "\",\"creditCode\":\"REGRESSION-CODE\","
                                + "\"reason\":\"integration regression\"}")
                        .with(authentication(authForRole("SYSTEM_ADMIN")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.supplierName").value(supplierName))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        mockMvc.perform(post("/api/v1/blacklist/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierName\":\"" + supplierName
                                + "\",\"procurementId\":\"PROC-REGRESSION\"}")
                        .with(authentication(authForRole("AUDITOR")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.matched").value(true))
                .andExpect(jsonPath("$.data.matchCount").value(1))
                .andExpect(jsonPath("$.data.items[0].matchType").value("EXACT"));

        String alertsBody = mockMvc.perform(get("/api/v1/blacklist/alerts")
                        .with(authentication(authForRole("PROCUREMENT_MANAGER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode matchingAlert = objectMapper.readTree(alertsBody).path("data").findParents(
                        "supplierName").stream()
                .filter(node -> supplierName.equals(node.path("supplierName").asText()))
                .findFirst()
                .orElseThrow();
        String alertId = matchingAlert.path("id").asText();

        mockMvc.perform(put("/api/v1/blacklist/alerts/{id}/process", alertId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"handler\":\"regression-user\","
                                + "\"remark\":\"reviewed\",\"action\":\"PROCESSED\"}")
                        .with(authentication(authForRole("AUDITOR")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(alertId))
                .andExpect(jsonPath("$.data.status").value("PROCESSED"))
                .andExpect(jsonPath("$.data.handler").value("regression-user"));
    }

    @Test
    void authorizedUserCanRemoveEntryWithReasonAndReadOperationLog() throws Exception {
        String supplierName = "blacklist-remove-regression-supplier";
        String response = mockMvc.perform(post("/api/v1/blacklist/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierName\":\"" + supplierName
                                + "\",\"creditCode\":\"REMOVE-REGRESSION-CODE\","
                                + "\"reason\":\"remove regression\"}")
                        .with(authentication(authForRole("SYSTEM_ADMIN")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String entryId = objectMapper.readTree(response).path("data").path("id").asText();

        mockMvc.perform(post("/api/v1/blacklist/entries/{id}/remove", entryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"已完成整改，人工移除\"}")
                        .with(authentication(authForRole("SYSTEM_ADMIN")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(entryId))
                .andExpect(jsonPath("$.data.active").value(false))
                .andExpect(jsonPath("$.data.status").value("INACTIVE"));

        mockMvc.perform(get("/api/v1/blacklist/entries/{id}/operations", entryId)
                        .with(authentication(authForRole("AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].operationType").value("REMOVE"))
                .andExpect(jsonPath("$.data[0].operatorName").value("Blacklist Test"))
                .andExpect(jsonPath("$.data[0].operationReason").value("已完成整改，人工移除"));
    }

    @Test
    void authorizedUserCanCheckSupplierBatch() throws Exception {
        String supplierName = "blacklist-batch-regression-supplier";
        createEntry(supplierName);

        mockMvc.perform(post("/api/v1/blacklist/check-batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" +
                                "\"batchId\":\"BATCH-REGRESSION\",\"suppliers\":[" +
                                "{\"rowNumber\":1,\"supplierName\":\"" + supplierName + "\","
                                + "\"supplierCode\":\"CODE-BATCH\",\"procurementId\":\"P-1\"}," +
                                "{\"rowNumber\":2,\"supplierName\":\"normal-batch-supplier\"}" +
                                "]}")
                        .with(authentication(authForRole("AUDITOR")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.batchId").value("BATCH-REGRESSION"))
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andExpect(jsonPath("$.data.hitCount").value(1))
                .andExpect(jsonPath("$.data.missCount").value(1))
                .andExpect(jsonPath("$.data.hits[0].rowNumber").value(1))
                .andExpect(jsonPath("$.data.hits[0].alertId").isNotEmpty())
                .andExpect(jsonPath("$.data.misses[0].rowNumber").value(2));
    }

    @Test
    void authorizedUserCanUploadSupplierBatchForCheck() throws Exception {
        String supplierName = "blacklist-upload-regression-supplier";
        createEntry(supplierName);

        byte[] workbook = workbookBytes(
                new String[] {"供应商名称", "统一社会信用代码", "采购编号"},
                new String[][] {{supplierName, "CODE-UPLOAD", "P-UPLOAD"},
                        {"normal-upload-supplier", "", "P-NORMAL"}});
        MockMultipartFile file = new MockMultipartFile(
                "file", "suppliers.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook);

        mockMvc.perform(multipart("/api/v1/blacklist/check-batch-upload")
                        .file(file)
                        .param("batchId", "UPLOAD-REGRESSION")
                        .with(authentication(authForRole("AUDITOR")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.batchId").value("UPLOAD-REGRESSION"))
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andExpect(jsonPath("$.data.hitCount").value(1))
                .andExpect(jsonPath("$.data.missCount").value(1))
                .andExpect(jsonPath("$.data.hits[0].rowNumber").value(2))
                .andExpect(jsonPath("$.data.misses[0].rowNumber").value(3));
    }

    private void createEntry(String supplierName) throws Exception {
        mockMvc.perform(post("/api/v1/blacklist/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierName\":\"" + supplierName
                                + "\",\"reason\":\"batch regression\"}")
                        .with(authentication(authForRole("SYSTEM_ADMIN")))
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    private static byte[] workbookBytes(String[] headers, String[][] rows) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("询比价");
            var header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }
            for (int rowIndex = 0; rowIndex < rows.length; rowIndex++) {
                var row = sheet.createRow(rowIndex + 1);
                for (int columnIndex = 0; columnIndex < rows[rowIndex].length; columnIndex++) {
                    row.createCell(columnIndex).setCellValue(rows[rowIndex][columnIndex]);
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static Authentication authForRole(String role) {
        CurrentUser user = new CurrentUser(
                "blacklist-test-user", "blacklist.test", "Blacklist Test", Set.of(role));
        return UsernamePasswordAuthenticationToken.authenticated(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
