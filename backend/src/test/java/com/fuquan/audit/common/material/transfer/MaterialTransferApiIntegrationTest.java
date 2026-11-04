package com.fuquan.audit.common.material.transfer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.fuquan.audit.common.material.transfer.MaterialTransfer.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.budget.application.BudgetMaterialWorker;
import com.fuquan.audit.security.domain.CurrentUser;
import com.fuquan.audit.procurement.application.*;
import com.fuquan.audit.procurement.domain.ProcurementReviewReport;
import java.util.*;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("local")
class MaterialTransferApiIntegrationTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;
    @MockitoBean BudgetMaterialWorker worker;
    @MockitoBean ProcurementReviewReportService reports;
    @MockitoBean ProcurementReportDocument documents;
    static UsernamePasswordAuthenticationToken actor(String id,String role){return new UsernamePasswordAuthenticationToken(new CurrentUser(id,id,"合成测试",Set.of(role)),null,List.of(new SimpleGrantedAuthority("ROLE_"+role)));}
    static String hash(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
    @Test void chunkHttpResumesAndCompletesExactlyOnceWithCsrfAndOwnerIsolation()throws Exception {
        byte[] content=new byte[CHUNK_BYTES+13];new Random(9).nextBytes(content);
        var spec=new Spec("合成预算.pdf","application/pdf",content.length,hash(content),List.of(hash(Arrays.copyOfRange(content,0,CHUNK_BYTES)),hash(Arrays.copyOfRange(content,CHUNK_BYTES,content.length))),null,"2026",null,null);
        String key=UUID.randomUUID().toString(),path="/api/v1/material-transfers/budget/"+key;var owner=actor(UUID.randomUUID().toString(),"FINANCE_REVIEWER");
        mvc.perform(post(path).contentType("application/json").content(json.writeValueAsBytes(spec)).with(authentication(owner))).andExpect(status().isForbidden());
        mvc.perform(post(path).contentType("application/json").content(json.writeValueAsBytes(spec)).with(authentication(actor("wrong-role","PROCUREMENT_APPLICANT"))).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post(path).contentType("application/json").content(json.writeValueAsBytes(spec)).with(authentication(owner)).with(csrf())).andExpect(status().isOk());
        mvc.perform(get(path).with(authentication(actor("other","SYSTEM_ADMIN")))).andExpect(status().isNotFound());
        mvc.perform(multipart(path+"/parts/0").file(new MockMultipartFile("file","part","application/octet-stream",Arrays.copyOfRange(content,0,CHUNK_BYTES))).with(r->{r.setMethod("PUT");return r;}).with(authentication(owner)).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.data.received[0]").value(0));
        mvc.perform(post(path+"/complete").with(authentication(owner)).with(csrf())).andExpect(status().isConflict());
        mvc.perform(get(path).with(authentication(owner))).andExpect(status().isOk()).andExpect(jsonPath("$.data.receivedBytes").value(CHUNK_BYTES));
        mvc.perform(multipart(path+"/parts/1").file(new MockMultipartFile("file","part","application/octet-stream",Arrays.copyOfRange(content,CHUNK_BYTES,content.length))).with(r->{r.setMethod("PUT");return r;}).with(authentication(owner)).with(csrf())).andExpect(status().isOk());
        String first=mvc.perform(post(path+"/complete").with(authentication(owner)).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("QUEUED")).andReturn().getResponse().getContentAsString();
        String replay=mvc.perform(post(path+"/complete").with(authentication(owner)).with(csrf())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(replay).path("data")).isEqualTo(json.readTree(first).path("data"));
        mvc.perform(get(path).with(authentication(owner))).andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }
    @Test void reportExportAuthorizesRoleRejectsStaleIdAndReturnsAttachment()throws Exception {
        UUID project=UUID.randomUUID(),id=UUID.randomUUID();var data=mock(ProcurementReviewReportService.ReportData.class);var metadata=mock(ProcurementReviewReport.class);
        when(data.metadata()).thenReturn(metadata);when(metadata.id()).thenReturn(id);when(reports.requireLatest(project)).thenReturn(data);when(documents.render(data)).thenReturn(new byte[]{80,75,3,4});
        String path="/api/v1/procurement/projects/"+project+"/review-report/export";
        mvc.perform(get(path).param("reportId",id.toString()).with(authentication(actor("applicant","PROCUREMENT_APPLICANT")))).andExpect(status().isForbidden());
        mvc.perform(get(path).param("reportId",UUID.randomUUID().toString()).with(authentication(actor("auditor","AUDITOR")))).andExpect(status().isConflict());
        verify(documents,never()).render(any());
        mvc.perform(get(path).param("reportId",id.toString()).with(authentication(actor("auditor","AUDITOR")))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.containsString(".docx"))).andExpect(content().bytes(new byte[]{80,75,3,4}));
    }
}
