package com.fuquan.audit.procurement.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuquan.audit.security.domain.CurrentUser;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Set;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
// The shared local fixture's quote is mutable; never leak it into another API suite.
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ProcurementQuoteCheckApiIntegrationTest {

    private static final String PROJECT_ID = "00000000-0000-0000-0000-000000000101";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void uploadAndHistoryReturnRealAnalysisResult() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "quotes.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                workbookBytes());

        mockMvc.perform(multipart("/api/v1/procurement/quote-checks/upload")
                        .file(file)
                        .param("batchId", PROJECT_ID)
                        .with(authentication(authForRole("AUDITOR")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.batchId").value(PROJECT_ID))
                .andExpect(jsonPath("$.data.budgetAmount").value(150000.00))
                .andExpect(jsonPath("$.data.riskLevel").value("WARNING"))
                .andExpect(jsonPath("$.data.quotes[0].overBudget").value(true))
                .andExpect(jsonPath("$.data.suspiciousSuppliers[0].supplierName").value("供应商A"));

        mockMvc.perform(get("/api/v1/procurement/quote-checks/" + PROJECT_ID)
                        .with(authentication(authForRole("AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coefficientOfVariation").exists())
                .andExpect(jsonPath("$.data.quotes").isArray())
                .andExpect(jsonPath("$.data.suspiciousSuppliers").isArray());
    }

    @Test
    void anonymousUploadIs401() throws Exception {
        mockMvc.perform(multipart("/api/v1/procurement/quote-checks/upload")
                        .file(new MockMultipartFile("file", "quotes.xlsx", "application/octet-stream", new byte[0]))
                        .param("batchId", "API-F06-2")
                        .with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void emptyUploadIsRejectedAfterAuthentication() throws Exception {
        mockMvc.perform(multipart("/api/v1/procurement/quote-checks/upload")
                        .file(new MockMultipartFile("file", "quotes.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                new byte[0]))
                        .param("batchId", "API-F06-empty")
                        .with(authentication(authForRole("AUDITOR")))
                        .with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422));
    }

    private static byte[] workbookBytes() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("询比价");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("供应商名称");
            header.createCell(1).setCellValue("报价金额");
            header.createCell(2).setCellValue("产品描述");
            String[][] rows = {
                    {"供应商A", "180000", "办公设备"},
                    {"供应商B", "150000", "办公设备"}
            };
            for (int i = 0; i < rows.length; i++) {
                var row = sheet.createRow(i + 1);
                for (int j = 0; j < rows[i].length; j++) row.createCell(j).setCellValue(rows[i][j]);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static Authentication authForRole(String role) {
        CurrentUser user = new CurrentUser(
                "f06-test-user", "f06.test", "F06 Test", Set.of(role));
        return UsernamePasswordAuthenticationToken.authenticated(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
