package com.fuquan.audit.contract.api.project;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fuquan.audit.contract.application.closeout.*;
import com.fuquan.audit.contract.application.closeout.port.ReportRendererPort;
import com.fuquan.audit.contract.domain.closeout.ContractReviewReport;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("local")
class ContractReviewCloseoutApiTest {
    @Autowired MockMvc mvc;
    @MockBean ContractReviewAcknowledgementService acknowledgements;
    @MockBean ContractReviewReportService reports;
    final UUID project = UUID.randomUUID(), contract = UUID.randomUUID(), run = UUID.randomUUID(), finding = UUID.randomUUID(), report = UUID.randomUUID();
    String base() { return "/api/v1/projects/" + project + "/contracts/" + contract + "/review-runs/" + run; }
    @Test void writesRequireSessionCsrfAndIdempotencyKey() throws Exception {
        mvc.perform(post(base() + "/reports").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(base() + "/reports").with(user("tester").roles("AUDITOR")))
                .andExpect(status().isForbidden());
        mvc.perform(post(base() + "/reports").with(user("tester").roles("AUDITOR")).with(csrf()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(reports);
    }
    @Test void forgedActorAndExtraReportFieldsFailBeforeService() throws Exception {
        mvc.perform(post(base() + "/findings/" + finding + "/acknowledgements").with(user("tester").roles("AUDITOR"))
                .with(csrf()).header("Idempotency-Key", "ack").contentType(MediaType.APPLICATION_JSON)
                .content("{\"findingVersionNo\":0,\"acknowledgedBy\":\"forged\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(base() + "/reports").with(user("tester").roles("AUDITOR")).with(csrf())
                .header("Idempotency-Key", "report").contentType(MediaType.APPLICATION_JSON).content("{\"actor\":\"forged\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(acknowledgements, reports);
    }
    @Test void readsAreKeyFreeAndReportDtoHidesStorageAndSnapshot() throws Exception {
        when(reports.get(project, contract, run, report)).thenReturn(report());
        mvc.perform(get(base() + "/reports/" + report).with(user("tester").roles("LEGAL")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.reportId").value(report.toString()))
                .andExpect(jsonPath("$.data.artifactStorageKey").doesNotExist())
                .andExpect(jsonPath("$.data.snapshotJson").doesNotExist());
        verify(reports, never()).generate(any(), any(), any(), any(), any());
    }
    @Test void unrelatedRoleCannotGenerateOrReadReports() throws Exception {
        mvc.perform(get(base() + "/reports").with(user("tester").roles("OTHER"))).andExpect(status().isForbidden());
        mvc.perform(post(base() + "/reports").with(user("tester").roles("OTHER")).with(csrf())
                .header("Idempotency-Key", "report")).andExpect(status().isForbidden());
        verifyNoInteractions(reports);
    }
    @Test void attachmentDownloadUsesSafeHeadersAndGenerationReturnsDurableIdentity() throws Exception {
        when(reports.generate(eq(project), eq(contract), eq(run), eq("report"), any())).thenReturn(report());
        mvc.perform(post(base() + "/reports").with(user("tester").roles("PROCUREMENT_MANAGER")).with(csrf())
                .header("Idempotency-Key", "report").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.reportId").value(report.toString()));
        when(reports.download(eq(project), eq(contract), eq(run), eq(report), any()))
                .thenReturn(new ReportRendererPort.RenderedReport("<html>合成报告</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/html; charset=utf-8"));
        mvc.perform(get(base() + "/reports/" + report + "/artifact").with(user("tester").roles("AUDITOR")))
                .andExpect(status().isOk()).andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", "sandbox; default-src 'none'"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=contract-review-report.html"));
    }
    ContractReviewReport report() {
        return new ContractReviewReport(report, run, 1, 1, ContractReviewReport.Status.AVAILABLE,
                "a".repeat(64), "b".repeat(64), "c".repeat(64), "{}", "d".repeat(64), "text/html; charset=utf-8", 20L,
                "e".repeat(64), "actor", Instant.EPOCH, Instant.EPOCH, null, null, null, "request", "trace", 2);
    }
}
