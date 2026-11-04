package com.fuquan.audit.budget.api;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.budget.application.*;
import com.fuquan.audit.budget.application.port.*;
import com.fuquan.audit.budget.domain.*;
import com.fuquan.audit.security.domain.CurrentUser;
import java.io.*;
import java.time.Instant;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class BudgetMaterialBackgroundIntegrationTest {
    @Test void sharedOcrBusyRequeuesSameOperationAndUnknownCannotAutomaticallyReplay() throws Exception {
        var u=user(UUID.randomUUID().toString());var job=upload("仿真忙时.xlsx",workbook(),u,UUID.randomUUID().toString());
        org.mockito.Mockito.doThrow(new com.fuquan.audit.review.application.port.OcrCapacityUnavailableException())
            .doThrow(new com.fuquan.audit.review.application.port.OcrOutcomeUnprovenException(new IOException()))
            .when(uploadedReader).read(org.mockito.ArgumentMatchers.argThat(t->t!=null&&t.id().equals(job.id())));
        service.process(job.id(),u,t->{});
        var queued=jobs.find(job.id()).orElseThrow();
        assertThat(queued.status()).isEqualTo("QUEUED");assertThat(queued.operationKey()).isEqualTo(job.operationKey());
        assertThat(tasks.findById(job.id()).orElseThrow().status()).isEqualTo("QUEUED");
        assertThat(batchRepository.source(job.id()).orElseThrow().factsJson()).isNull();
        assertThat(batches.result(job.id()).status()).isEqualTo("PENDING");
        service.process(job.id(),u,t->{});
        assertThat(jobs.find(job.id()).orElseThrow().status()).isEqualTo("UNKNOWN");
        assertThat(tasks.findById(job.id()).orElseThrow().status()).isEqualTo("UNKNOWN");
        service.process(job.id(),u,t->{});
        org.mockito.Mockito.verify(uploadedReader,org.mockito.Mockito.times(2)).read(org.mockito.ArgumentMatchers.argThat(t->t!=null&&t.id().equals(job.id())));
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired BudgetMaterialService service;
    @Autowired BudgetMaterialTransactions tx;
    @Autowired BudgetMaterialJobRepository jobs;
    @Autowired BudgetReviewTaskRepository tasks;
    @Autowired BudgetReviewService review;
    @Autowired BudgetMaterialBatchService batches;
    @Autowired BudgetMaterialBatchTransactions batchTx;
    @Autowired BudgetMaterialBatchRepository batchRepository;
    @Autowired BudgetMaterialRiskRepository riskRepository;
    @MockitoBean BudgetMaterialWorker worker;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean BudgetUploadedMaterialReader uploadedReader;
    @Autowired BudgetMaterialStoragePort storage;
    @Test void verifiedPartialContentAndWarningAreSavedAndReopenedWithoutPretendingCleanComparison() throws Exception {
        var u=user(UUID.randomUUID().toString());var job=upload("仿真部分提取.xlsx",workbook(),u,UUID.randomUUID().toString());
        var facts=new BudgetUploadedFacts.Snapshot(List.of(new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Row("row",List.of(
            new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact("subjectName","设备","row","设备"),
            new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact("declaredAmount","50元","row","申请50元"),
            new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact("approvalAmount","100元","row","批复100元")))),
            List.of(new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.ExtractionIssue("declaredAmount","row","EVIDENCE_UNVERIFIED")));
        org.mockito.Mockito.doReturn(facts).when(uploadedReader).read(org.mockito.ArgumentMatchers.argThat(t->t!=null&&t.id().equals(job.id())));
        service.process(job.id(),u,t->{});
        assertThat(tasks.findById(job.id()).orElseThrow().status()).isEqualTo("REVIEWED");
        assertThat(batches.snapshot(job.id()).issues()).isEqualTo(facts.issues());
        var saved=batches.result(job.id());
        assertThat(saved.lines().get(0).status()).isEqualTo("INSUFFICIENT");
        assertThat(saved.lines().get(0).declaredAmount()).isEqualByComparingTo("50");
        mvc.perform(get("/api/v1/budget/reviews/{id}",job.id()).with(authentication(auth(u))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.task.status").value("REVIEWED"))
            .andExpect(jsonPath("$.data.materialReference.summary").value(org.hamcrest.Matchers.containsString("未能与原文核实")))
            .andExpect(jsonPath("$.data.materialReference.lines[0].status").value("INSUFFICIENT"))
            .andExpect(jsonPath("$.data.allPassed").value(false));
        service.processPendingBatch();assertThat(batches.result(job.id())).isEqualTo(saved);
    }
    @Test void endedMaterialExplainsGroundingProblemWithoutFalsifyingCompletionOrExposingException() throws Exception {
        var u=user(UUID.randomUUID().toString());var job=upload("合成依据校验.xlsx",workbook(),u,UUID.randomUUID().toString());
        org.mockito.Mockito.doThrow(new IOException("BUDGET_AI_ROW_AMBIGUOUS")).when(uploadedReader)
            .read(org.mockito.ArgumentMatchers.argThat(task->task!=null&&task.id().equals(job.id())));
        service.process(job.id(),u,token->{});
        assertThat(tasks.findById(job.id()).orElseThrow().status()).isEqualTo("FAILED");
        assertThat(tasks.findById(job.id()).orElseThrow().errorDetail()).isEqualTo("BUDGET_AI_ROW_AMBIGUOUS");
        mvc.perform(get("/api/v1/budget/reviews/{id}",job.id()).with(authentication(auth(u))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.task.status").value("FAILED"))
            .andExpect(jsonPath("$.data.task.errorDetail").value(org.hamcrest.Matchers.containsString("具体条目")))
            .andExpect(jsonPath("$.data.materialReference.lines").isEmpty());
        mvc.perform(get("/api/v1/budget/validation-records").param("batchId",job.id().toString()).with(authentication(auth(u))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.records[0].processingExplanation").value(org.hamcrest.Matchers.containsString("具体条目")));
    }
    @Test void timeoutClosesTaskCleansFileExcludesHistoryAndContinuesNextMaterial() throws Exception {
        var u=user(UUID.randomUUID().toString());var bytes=workbook();
        var failed=upload("合成超时.xlsx",bytes,u,UUID.randomUUID().toString());
        var reference=tasks.findById(failed.id()).orElseThrow().tempFilePath();
        org.mockito.Mockito.doThrow(new com.fuquan.audit.common.material.application.port.MaterialExtractionTerminated("MATERIAL_CALL_TIMEOUT"))
            .doCallRealMethod().when(uploadedReader).read(org.mockito.ArgumentMatchers.any());
        service.process(failed.id(),u,t->{});
        var task=tasks.findById(failed.id()).orElseThrow();
        assertThat(task.status()).isEqualTo("FAILED");assertThat(task.errorDetail()).isEqualTo("MATERIAL_CALL_TIMEOUT");
        assertThat(task.tempFilePath()).isNull();assertThat(storage.exists(reference)).isFalse();
        assertThat(tasks.findHistoryPage(failed.id().toString(),null,null,new BudgetHistoryFilter(null,null,null),0,10)).isEmpty();
        mvc.perform(get("/api/v1/budget/material-uploads/{id}",failed.id()).with(authentication(auth(u))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.errorCode").value("MATERIAL_CALL_TIMEOUT"));
        var next=upload("合成继续.xlsx",bytes,u,UUID.randomUUID().toString());service.process(next.id(),u,t->{});
        assertThat(tasks.findById(next.id()).orElseThrow().status()).isEqualTo("REVIEWED");
    }
    static CurrentUser user(String id) {return new CurrentUser(id,id,"合成测试",Set.of("FINANCE_REVIEWER"));}
    static UsernamePasswordAuthenticationToken auth(CurrentUser u) {return new UsernamePasswordAuthenticationToken(u,null,List.of(new SimpleGrantedAuthority("ROLE_FINANCE_REVIEWER")));}
    static byte[] workbook() throws Exception {
        try(var w=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var s=w.createSheet("合成预算");var h=s.createRow(0);String[] headers={"科目编码","科目名称","申报金额","供应商名称","项目编号"};
            for(int i=0;i<headers.length;i++) h.createCell(i).setCellValue(headers[i]);
            var r=s.createRow(1);String[] values={"SYNTHETIC","虚构待确认科目","100.00","虚构测试供应商","SYNTHETIC-BUDGET"};
            for(int i=0;i<values.length;i++)r.createCell(i).setCellValue(values[i]);w.write(out);return out.toByteArray();
        }
    }
    BudgetMaterialJob upload(String name,byte[] bytes,CurrentUser u,String key) throws Exception {
        return service.upload(name,"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",bytes.length,new ByteArrayInputStream(bytes),"2026","PROCUREMENT",key,u);
    }
    @Test void durableUploadReturnsBeforeParsingReopensSameIdentityAndEnforcesOwnership() throws Exception {
        var bytes=workbook();var u=user(UUID.randomUUID().toString());String key=UUID.randomUUID().toString();
        var response=mvc.perform(multipart("/api/v1/budget/material-uploads").file(new MockMultipartFile("file","合成预算.xlsx","application/octet-stream",bytes))
            .param("budgetYear","2026").param("departmentCode","PROCUREMENT").header("Idempotency-Key",key).with(authentication(auth(u))).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("QUEUED")).andExpect(jsonPath("$.data.task.parsedLineCount").value(0)).andReturn();
        UUID id=UUID.fromString(json.readTree(response.getResponse().getContentAsString()).path("data").path("id").asText());
        assertThat(upload("合成预算.xlsx",bytes,u,key).id()).isEqualTo(id);
        assertThatThrownBy(()->upload("合成预算.xlsx",new byte[]{1,2,3},u,key)).isInstanceOf(com.fuquan.audit.common.exception.BusinessException.class);
        mvc.perform(get("/api/v1/budget/material-uploads/{id}",id).with(authentication(auth(user("other"))))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/budget/material-uploads/operations/{key}",key).with(authentication(auth(user("other"))))).andExpect(jsonPath("$.data").isEmpty());
        assertThatThrownBy(()->review.review(id)).hasMessageContaining("后台");
        service.process(id,u,token->{});
        mvc.perform(get("/api/v1/budget/material-uploads/operations/{key}",key).with(authentication(auth(u))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id.toString())).andExpect(jsonPath("$.data.status").value("PARSED"));
        assertThat(tasks.findById(id).orElseThrow().status()).isEqualTo("REVIEWED");
        assertThat(review.findItems(id).get(0).abnormalReasons()).isNotEmpty();
    }
    @Test void oneCorruptFileAndOnePendingConfirmationDoNotStopOtherFiles() throws Exception {
        var u=user(UUID.randomUUID().toString());
        var bad=upload("损坏.xlsx",new byte[]{1,2,3},u,UUID.randomUUID().toString());
        var first=upload("待确认.xlsx",workbook(),u,UUID.randomUUID().toString());
        var second=upload("下一份.xlsx",workbook(),u,UUID.randomUUID().toString());
        for(var j:List.of(bad,first,second))service.process(j.id(),u,token->{});
        assertThat(jobs.find(bad.id()).orElseThrow().status()).isEqualTo("FAILED");
        assertThat(jobs.find(first.id()).orElseThrow().status()).isEqualTo("PARSED");
        assertThat(jobs.find(second.id()).orElseThrow().status()).isEqualTo("PARSED");
        assertThat(tasks.findById(second.id()).orElseThrow().parsedLineCount()).isEqualTo(1);
    }
    @Test void expiredLeaseIsRediscoveredAndStaleResultsCannotOverwriteNewOwner() throws Exception {
        var u=user(UUID.randomUUID().toString());var j=upload("恢复.xlsx",workbook(),u,UUID.randomUUID().toString());
        Instant now=Instant.now();String old=UUID.randomUUID().toString(),next=UUID.randomUUID().toString();
        assertThat(tx.claim(j.id(),old,now.minusSeconds(10),now.minusSeconds(1))).isTrue();
        assertThat(jobs.pending(now,1000)).extracting(BudgetMaterialJob::id).contains(j.id());
        assertThat(tx.claim(j.id(),next,now,now.plusSeconds(1200))).isTrue();
        assertThat(tx.claim(j.id(),old,now,now.plusSeconds(1200))).isFalse();
        var stale=tasks.findById(j.id()).orElseThrow().withStatus("FAILED",0,"stale");
        assertThatThrownBy(()->tx.parsed(stale,old)).isInstanceOf(IllegalStateException.class);
        assertThat(tasks.findById(j.id()).orElseThrow().status()).isEqualTo("PROCESSING");
        assertThat(jobs.renew(j.id(),old,now.plusSeconds(1400))).isFalse();
        tx.finish(j.id(),next,"FAILED",u);
        assertThat(tx.claim(j.id(),old,now.plusSeconds(1500),now.plusSeconds(2700))).isFalse();
    }
    @Test void asynchronousUploadStillRequiresCsrfAndKey() throws Exception {
        var f=new MockMultipartFile("file","预算.xlsx","application/octet-stream",workbook());var u=auth(user("security-fixture"));
        mvc.perform(multipart("/api/v1/budget/material-uploads").file(f).with(authentication(u)).header("Idempotency-Key",UUID.randomUUID())).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/v1/budget/material-uploads").file(f).with(authentication(u)).with(csrf())).andExpect(status().isBadRequest());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"30000,20000,50000,BELOW_1M,INFO", "600000,400000,1000000,AT_OR_ABOVE_1M,WARNING", "600000,400000.01,1000000.01,AT_OR_ABOVE_1M,WARNING"})
    void selectedAmountsPersistTheNewBandAndEvidenceSnapshot(String firstAmount,String secondAmount,String total,String band,String level) throws Exception {
        var u=user(UUID.randomUUID().toString());var ids=new ArrayList<UUID>();
        for(int i=0;i<2;i++) {
            byte[] bytes;
            try(var w=new XSSFWorkbook(new ByteArrayInputStream(workbook()));var out=new ByteArrayOutputStream()) {
                w.getSheetAt(0).getRow(1).getCell(2).setCellValue(i==0?firstAmount:secondAmount);
                w.getSheetAt(0).getRow(1).getCell(4).setCellValue("SYNTHETIC-SPLIT-"+i);
                w.write(out);bytes=out.toByteArray();
            }
            var job=upload("合成拆分-"+i+".xlsx",bytes,u,UUID.randomUUID().toString());
            service.process(job.id(),u,token->{});ids.add(job.id());
        }
        UUID id=UUID.randomUUID();var body=Map.of("id",id,"selections",ids.stream().map(task->Map.of("taskId",task,"lineIndex",0)).toList());
        var first=mvc.perform(post("/api/v1/budget/material-risk/analyses").contentType("application/json")
                .content(json.writeValueAsString(body)).with(authentication(auth(u))).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.splitAnalysis.countedItems").value(2))
                .andExpect(jsonPath("$.data.splitAnalysis.totalAmount").value(org.hamcrest.Matchers.comparesEqualTo(new java.math.BigDecimal(total)),java.math.BigDecimal.class))
                .andExpect(jsonPath("$.data.splitAnalysis.bandCode").value(band))
                .andExpect(jsonPath("$.data.splitAnalysis.version").value("SELECTED_SUM_V3"))
                .andExpect(jsonPath("$.data.splitAnalysis.riskLevel").value(level))
                .andExpect(jsonPath("$.data.splitAnalysis.complete").value(true))
                .andReturn().getResponse().getContentAsString();
        var saved=mvc.perform(get("/api/v1/budget/material-risk/analyses/{id}",id).with(authentication(auth(u))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(saved)).isEqualTo(json.readTree(first));
    }
    @Test void materialRiskUsesOnlyOwnedCompletedSnapshotsAndReopensImmutableHistory() throws Exception {
        var u=user(UUID.randomUUID().toString());var job=upload("风险分析.xlsx",workbook(),u,UUID.randomUUID().toString());
        UUID id=UUID.randomUUID();var body=Map.of("id",id,"selections",List.of(Map.of("taskId",job.id(),"lineIndex",0)));
        mvc.perform(post("/api/v1/budget/material-risk/analyses").contentType("application/json").content(json.writeValueAsString(body)).with(authentication(auth(u))).with(csrf())).andExpect(status().isConflict());
        service.process(job.id(),u,token->{});
        var first=mvc.perform(post("/api/v1/budget/material-risk/analyses").contentType("application/json").content(json.writeValueAsString(body)).with(authentication(auth(u))).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.data.projects[0].fileName").value("风险分析.xlsx")).andReturn().getResponse().getContentAsString();
        var replay=mvc.perform(post("/api/v1/budget/material-risk/analyses").contentType("application/json").content(json.writeValueAsString(body)).with(authentication(auth(u))).with(csrf())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(first)).isEqualTo(json.readTree(replay));
        mvc.perform(get("/api/v1/budget/material-risk/analyses/{id}",id).with(authentication(auth(u)))).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id.toString()));
        mvc.perform(get("/api/v1/budget/material-risk/analyses").with(authentication(auth(u)))).andExpect(jsonPath("$.data[0].id").value(id.toString()));
        var other=auth(user(UUID.randomUUID().toString()));
        mvc.perform(get("/api/v1/budget/material-risk/analyses/{id}",id).with(authentication(other))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/budget/material-risk/candidates").param("taskIds",job.id().toString()).with(authentication(other))).andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.hasMore").value(false));
        var drift=Map.of("id",id,"selections",List.of(Map.of("taskId",job.id(),"lineIndex",1)));
        mvc.perform(post("/api/v1/budget/material-risk/analyses").contentType("application/json").content(json.writeValueAsString(drift)).with(authentication(auth(u))).with(csrf())).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/budget/material-risk/analyses").contentType("application/json").content(json.writeValueAsString(body)).with(authentication(auth(u)))).andExpect(status().isForbidden());
    }
    @Test void candidateCursorPagesAreBoundedSearchableAndDoNotShiftWhenNewFilesArrive() throws Exception {
        var u=user(UUID.randomUUID().toString());byte[] bytes;
        try(var w=new XSSFWorkbook(new ByteArrayInputStream(workbook()));var out=new ByteArrayOutputStream()) {
            var sheet=w.getSheetAt(0);
            for(int row=1;row<=26;row++) {
                var cells=sheet.createRow(row);
                String[] values={"SYN-"+row,"电子设备-"+row,"100","合成供应商","SYNTHETIC-"+row};
                for(int col=0;col<values.length;col++)cells.createCell(col).setCellValue(values[col]);
            }
            w.write(out);bytes=out.toByteArray();
        }
        var job=upload("合成%_分页.xlsx",bytes,u,UUID.randomUUID().toString());service.process(job.id(),u,token->{});
        var first=json.readTree(mvc.perform(get("/api/v1/budget/material-risk/browse").with(authentication(auth(u))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(12)).andReturn().getResponse().getContentAsString()).path("data");
        var seen=new HashSet<String>();for(var item:first.path("items"))seen.add(item.path("source").toString());
        var added=upload("之后新上传.xlsx",workbook(),u,UUID.randomUUID().toString());service.process(added.id(),u,token->{});
        String cursor=first.path("nextCursor").asText();
        while(cursor!=null) {
            var page=json.readTree(mvc.perform(get("/api/v1/budget/material-risk/browse").param("cursor",cursor).with(authentication(auth(u))))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
            assertThat(page.path("items").size()).isLessThanOrEqualTo(12);
            for(var item:page.path("items")) {assertThat(item.path("source").path("taskId").asText()).isEqualTo(job.id().toString());assertThat(seen.add(item.path("source").toString())).isTrue();}
            cursor=page.path("nextCursor").isNull()?null:page.path("nextCursor").asText();
        }
        assertThat(seen).hasSize(26);
        mvc.perform(get("/api/v1/budget/material-risk/browse").param("keyword","电子设备-26").with(authentication(auth(u))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));
        mvc.perform(get("/api/v1/budget/material-risk/browse").param("keyword","合成%_").with(authentication(auth(u))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(12));
        mvc.perform(get("/api/v1/budget/material-risk/browse").with(authentication(auth(user("other-browse")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.nextCursor").isEmpty());
        mvc.perform(get("/api/v1/budget/material-risk/browse").param("cursor","bad-cursor").with(authentication(auth(u)))).andExpect(status().isBadRequest());
    }
    @Test void historySearchPaginatesAllOwnedSnapshotsWithBusinessDateAndLiteralKeyword() throws Exception {
        var u=user(UUID.randomUUID().toString());
        var candidate=new BudgetMaterialRisk.Candidate(new BudgetMaterialRisk.Selection(UUID.randomUUID(),0),"合成%_历史.xlsx","2026-10-15T16:00:00Z",
                new BudgetUploadedFacts.Line("合成项目",null,"电子",null,null,null,new java.math.BigDecimal("10000"),null,"INSUFFICIENT","",List.of()));
        for(int i=0;i<13;i++)riskRepository.insert(new BudgetMaterialRisk.Result(UUID.randomUUID(),u.userId(),"2026-10-15T16:00:00Z",List.of(candidate),List.of(),BudgetMaterialSplitAnalysis.analyzeSelected(List.of(candidate))));
        riskRepository.insert(new BudgetMaterialRisk.Result(UUID.randomUUID(),"other-history-owner","2026-10-15T16:00:00Z",List.of(candidate),List.of()));
        for(int page=1;page<=3;page++)mvc.perform(get("/api/v1/budget/material-risk/history").param("page",String.valueOf(page))
                .param("date","2026-10-16").param("keyword","合成%_").with(authentication(auth(u))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(13))
                .andExpect(jsonPath("$.data.items.length()").value(page<3?6:1));
        mvc.perform(get("/api/v1/budget/material-risk/history").param("date","2026-10-15").with(authentication(auth(u))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/v1/budget/material-risk/history").param("keyword","' OR 1=1").with(authentication(auth(u))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/v1/budget/material-risk/history").param("page","0").with(authentication(auth(u)))).andExpect(status().isBadRequest());
    }
    @Test void shutdownInterruptionLeavesClaimedWorkRecoverableInsteadOfMarkingFailure() throws Exception {
        var u=user(UUID.randomUUID().toString());var job=upload("中断恢复.xlsx",workbook(),u,UUID.randomUUID().toString());
        try {service.process(job.id(),u,token->Thread.currentThread().interrupt());}
        finally {Thread.interrupted();}
        assertThat(jobs.find(job.id()).orElseThrow().status()).isEqualTo("PROCESSING");
        Instant expired=Instant.now().plusSeconds(1201);
        assertThat(jobs.pending(expired,1000)).extracting(BudgetMaterialJob::id).contains(job.id());
        assertThat(tx.claim(job.id(),UUID.randomUUID().toString(),expired,expired.plusSeconds(1200))).isTrue();
    }
    @Test void explicitBatchWaitsForAllMembersAndPersistsCrossFileResultsWithoutMappings() throws Exception {
        var u=user(UUID.randomUUID().toString());var batchId=UUID.randomUUID();var a=UUID.randomUUID();var b=UUID.randomUUID();
        byte[] declaration="项目编号：SYNTHETIC-P；科目名称：测试设备；申报金额：31200元".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] approval="项目编号：SYNTHETIC-P；科目名称：测试设备；批复参考金额：32760元".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var members=List.of(new BudgetMaterialBatch.Member(a,"回归批次_申报材料_ONLY.txt",declaration.length),new BudgetMaterialBatch.Member(b,"回归批次_批复材料_ONLY.txt",approval.length));
        batches.register(batchId,members,null,null,u);
        assertThat(batches.register(batchId,members,null,null,u).id()).isEqualTo(batchId);
        assertThatThrownBy(()->batches.register(batchId,List.of(members.get(0)),null,null,u)).hasMessageContaining("清单");
        assertThatThrownBy(()->batches.register(batchId,members,null,null,user("other"))).isInstanceOf(com.fuquan.audit.common.exception.BusinessException.class);
        var one=service.upload("回归批次_申报材料_ONLY.txt","text/plain",declaration.length,new ByteArrayInputStream(declaration),null,null,a.toString(),u,batchId);
        service.process(one.id(),u,t->{});
        assertThat(tasks.findById(one.id()).orElseThrow().status()).isEqualTo("PARSED");
        assertThat(batches.result(one.id()).status()).isEqualTo("PENDING");
        assertThat(batchRepository.pending(Instant.now(),1000)).extracting(BudgetMaterialBatch::id).doesNotContain(batchId);
        var two=service.upload("回归批次_批复材料_ONLY.txt","text/plain",approval.length,new ByteArrayInputStream(approval),null,null,b.toString(),u,batchId);
        service.process(two.id(),u,t->{});
        var result=batches.result(one.id());assertThat(result.status()).isEqualTo("COMPLETED");assertThat(result.lines()).hasSize(1);
        assertThat(result.lines().get(0).approvalAmount()).isEqualByComparingTo("32760");assertThat(result.lines().get(0).status()).isEqualTo("WITHIN_APPROVAL");
        assertThat(result.lines().get(0).sources()).extracting(BudgetUploadedFacts.Evidence::taskId).contains(one.id(),two.id());
        mvc.perform(get("/api/v1/budget/material-uploads/batches/{id}",one.id()).with(authentication(auth(u))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.batchId").value(batchId.toString()))
            .andExpect(jsonPath("$.data.files.length()").value(2))
            .andExpect(jsonPath("$.data.files[0].taskId").isNotEmpty());
        mvc.perform(get("/api/v1/budget/material-uploads/batches").param("fileName","申报材料_ONLY")
                .with(authentication(auth(u))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.records.length()").value(1))
            .andExpect(jsonPath("$.data.records[0].batchId").value(batchId.toString()))
            .andExpect(jsonPath("$.data.records[0].fileCount").value(2))
            .andExpect(jsonPath("$.data.records[0].previewFileNames.length()").value(2))
            .andExpect(jsonPath("$.data.records[0].status").value("REVIEWED"));
        mvc.perform(get("/api/v1/budget/material-uploads/batches").param("fileName","申报材料_ONLY").with(authentication(auth(user("other-history-owner")))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.records").isEmpty());
        UUID legacyId=UUID.randomUUID();Instant legacyTime=Instant.now().plusSeconds(1);
        tasks.save(new BudgetReviewTask(legacyId,"合成旧版单文件.xlsx","application/octet-stream",16,null,
                "REVIEWED",1,null,"2026","PROCUREMENT",legacyTime,legacyTime));
        jobs.insert(new BudgetMaterialJob(legacyId,u.userId(),UUID.randomUUID().toString(),"0".repeat(64),
                "PARSED",null,null,legacyTime));
        UUID otherLegacyId=UUID.randomUUID();
        tasks.save(new BudgetReviewTask(otherLegacyId,"合成旧版单文件_其他用户.xlsx","application/octet-stream",16,null,
                "REVIEWED",1,null,"2026","PROCUREMENT",legacyTime,legacyTime));
        jobs.insert(new BudgetMaterialJob(otherLegacyId,"other-history-owner",UUID.randomUUID().toString(),"0".repeat(64),
                "PARSED",null,null,legacyTime));
        mvc.perform(get("/api/v1/budget/material-uploads/batches").param("size","10").param("fileName","合成旧版单文件")
                .with(authentication(auth(u))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.records.length()").value(1))
            .andExpect(jsonPath("$.data.records[0].taskId").value(legacyId.toString()))
            .andExpect(jsonPath("$.data.records[0].fileCount").value(1))
            .andExpect(jsonPath("$.data.records[0].previewFileNames[0]").value("合成旧版单文件.xlsx"));
        mvc.perform(get("/api/v1/budget/material-uploads/batches").param("size","10").param("fileName","合成旧版单文件")
                .with(authentication(auth(user("other-history-owner")))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.records[0].taskId").value(otherLegacyId.toString()));
        mvc.perform(get("/api/v1/budget/material-uploads/batches/{id}",one.id()).with(authentication(auth(user("other")))))
            .andExpect(status().isNotFound());
        assertThat(review.findItems(one.id()).get(0).approvalAmount()).isEqualByComparingTo("32760");
        mvc.perform(get("/api/v1/budget/reviews/{id}",one.id()).with(authentication(auth(u))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.materialReference.lines[0].approvalAmount").value(32760))
            .andExpect(jsonPath("$.data.allPassed").value(false));
        batches.process(batchId);assertThat(batches.result(one.id())).isEqualTo(result);
        assertThatThrownBy(()->batchRepository.extracted(one.id(),"changed")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->service.upload("替换.txt","text/plain",declaration.length,new ByteArrayInputStream(declaration),null,null,a.toString(),u,batchId)).hasMessageContaining("清单");
    }
    @Test void failedFileDoesNotBlockBatchAndExpiredBatchClaimFencesStaleCompletion() throws Exception {
        var u=user(UUID.randomUUID().toString());var id=UUID.randomUUID();var ka=UUID.randomUUID();var kb=UUID.randomUUID();
        byte[] good="科目名称：测试设备；申报金额：100元".getBytes(java.nio.charset.StandardCharsets.UTF_8);byte[] bad={1,2,3};
        batches.register(id,List.of(new BudgetMaterialBatch.Member(ka,"好.txt",good.length),new BudgetMaterialBatch.Member(kb,"坏.xlsx",bad.length)),null,null,u);
        var a=service.upload("好.txt","text/plain",good.length,new ByteArrayInputStream(good),null,null,ka.toString(),u,id);
        var b=service.upload("坏.xlsx","application/octet-stream",bad.length,new ByteArrayInputStream(bad),null,null,kb.toString(),u,id);
        Instant now=Instant.now();assertThat(batchTx.claim(id,"expired",now.minusSeconds(10),now.minusSeconds(1))).isTrue();
        assertThat(batchTx.claim(id,"current",now,now.plusSeconds(1200))).isTrue();
        assertThatThrownBy(()->batchTx.complete(id,"expired",Map.of(),Map.of())).hasMessageContaining("lease lost");
        assertThat(batches.result(a.id()).status()).isEqualTo("PENDING");
        service.process(b.id(),u,t->{});service.process(a.id(),u,t->{});
        // The unexpired claim survives; a later worker rediscovers the full persisted sources.
        assertThat(batchRepository.pending(now.plusSeconds(1201),1000)).extracting(BudgetMaterialBatch::id).contains(id);
        assertThat(batchTx.claim(id,"recovered",now.plusSeconds(1201),now.minusSeconds(1))).isTrue();
        batches.process(id);
        assertThat(batches.result(a.id()).summary()).contains("依据尚不完整");assertThat(batches.result(a.id()).lines().get(0).status()).isEqualTo("INSUFFICIENT");
        assertThat(jobs.find(b.id()).orElseThrow().status()).isEqualTo("FAILED");
    }
}
