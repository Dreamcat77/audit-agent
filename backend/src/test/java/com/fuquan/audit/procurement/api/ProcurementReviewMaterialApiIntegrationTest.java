package com.fuquan.audit.procurement.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fuquan.audit.security.domain.CurrentUser;
import java.io.ByteArrayOutputStream;
import java.util.*;
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

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("local")
class ProcurementReviewMaterialApiIntegrationTest {
    private static final String PROJECT = "00000000-0000-0000-0000-000000000101";
    private static final String PATH = "/api/v1/procurement/projects/" + PROJECT + "/review-materials/upload";
    @Autowired MockMvc mvc;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.fuquan.audit.common.material.application.port.MaterialFieldAiPort ai;
    @org.junit.jupiter.api.BeforeEach void groundedModel() {
        org.mockito.Mockito.when(ai.extract(org.mockito.ArgumentMatchers.eq("PROCUREMENT_REVIEW"),
                org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyList()))
            .thenAnswer(call -> {
                java.util.List<com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Segment> segments=call.getArgument(3);
                var rows=segments.stream().filter(s->s.text().contains("办公项目")).map(s->{
                    var matcher=java.util.regex.Pattern.compile("办公项目[0-9]+").matcher(s.text());matcher.find();String name=matcher.group();
                    return new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Row(s.locator(),List.of(
                        new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact("projectName",name,s.locator(),name)));
                }).toList();
                return new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Result(rows,"TEST_MODEL","review-v1");
            });
    }
    private static Authentication actor(String role) {
        return new UsernamePasswordAuthenticationToken(new CurrentUser("material-routing-test", "routing.test", "Routing Test", Set.of(role)), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
    private static MockMultipartFile file(boolean quotation) throws Exception {
        try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("材料"); var header = sheet.createRow(0);
            header.createCell(0).setCellValue(quotation ? "供应商名称" : "项目名称");
            header.createCell(1).setCellValue(quotation ? "报价金额" : "需求数量");
            header.createCell(2).setCellValue("产品描述");
            for (int i = 1; i <= 3; i++) {
                var row = sheet.createRow(i); row.createCell(0).setCellValue((quotation ? "供应商" : "办公项目") + i);
                row.createCell(1).setCellValue(String.valueOf(90000 + 10000 * i)); row.createCell(2).setCellValue("同一完整采购范围");
            }
            book.write(output);
            return new MockMultipartFile("file", "采购材料.xlsx", "application/octet-stream", output.toByteArray());
        }
    }
    @Test void genericUploadRecognizesQuotationAndReopensPersistedAdvice() throws Exception {
        mvc.perform(multipart(PATH).file(file(true)).with(authentication(actor("AUDITOR"))).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("QUOTE_ANALYZED"))
                .andExpect(jsonPath("$.data.quote.comparison.recommendedAmount").value(100000))
                .andExpect(jsonPath("$.data.document.parseEngine").value("QUOTE_ANALYZED"));
        mvc.perform(get("/api/v1/procurement/quote-checks/" + PROJECT).with(authentication(actor("AUDITOR"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.comparison.recommendedAmount").value(100000));
    }
    @Test void missingQuotationInformationIsANeutralNonAnalysisResult() throws Exception {
        mvc.perform(multipart(PATH).file(file(false)).with(authentication(actor("AUDITOR"))).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("NON_QUOTATION"))
                .andExpect(jsonPath("$.data.quote").isEmpty())
                .andExpect(jsonPath("$.data.document.parseResult.extractedFields.length()").value(3));
    }
    @Test void authenticationAndExistingQuotePermissionsArePreserved() throws Exception {
        mvc.perform(multipart(PATH).file(file(true)).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(multipart(PATH).file(file(true)).with(authentication(actor("PROCUREMENT_APPLICANT"))).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(multipart(PATH).file(file(true)).with(authentication(actor("AUDITOR"))))
                .andExpect(status().isForbidden());
    }
}
