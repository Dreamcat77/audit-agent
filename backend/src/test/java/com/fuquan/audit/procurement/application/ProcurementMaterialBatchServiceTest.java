package com.fuquan.audit.procurement.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fuquan.audit.procurement.application.port.*;
import com.fuquan.audit.procurement.domain.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

class ProcurementMaterialBatchServiceTest {
    @Test void budgetAndQuoteCandidatesCannotSwapExplicitAmountRoles() {
        var budget = new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact(
                "budgetAmount", "500000", "page:1", "预算金额：500000元");
        var falseQuote = new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact(
                "quoteAmount", "500000", "page:1", "预算金额：500000元");
        var quote = new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact(
                "quoteAmount", "480000", "page:2", "投标报价：480000元");
        var falseBudget = new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact(
                "budgetAmount", "480000", "page:2", "投标报价：480000元");
        assertThat(ProcurementMaterialBatchService.amountRoleConsistent(budget)).isTrue();
        assertThat(ProcurementMaterialBatchService.amountRoleConsistent(quote)).isTrue();
        assertThat(ProcurementMaterialBatchService.amountRoleConsistent(falseQuote)).isFalse();
        assertThat(ProcurementMaterialBatchService.amountRoleConsistent(falseBudget)).isFalse();
        var mixed = "预算金额：500000元；投标报价：480000元";
        assertThat(ProcurementMaterialBatchService.amountRoleConsistent(new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact(
                "quoteAmount", "480000", "page:1", mixed))).isTrue();
        assertThat(ProcurementMaterialBatchService.amountRoleConsistent(new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact(
                "budgetAmount", "500000", "page:1", mixed))).isTrue();
        assertThat(ProcurementMaterialBatchService.amountRoleConsistent(new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact(
                "quoteAmount", "500000", "page:1", mixed))).isFalse();
        assertThat(ProcurementMaterialBatchService.amountRoleConsistent(new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact(
                "budgetAmount", "480000", "page:1", mixed))).isFalse();
        var falseCeiling = new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact(
                "ceilingAmount", "100", "page:1", "项目预算金额：100元");
        assertThat(ProcurementMaterialBatchService.amountRoleConsistent(falseCeiling)).isFalse();
    }

    @Test void tenderingPartiesRequireTheirExplicitSourceRole() {
        var purchaser = new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact(
                "purchaserName", "市政服务中心", "page:1", "采购人：市政服务中心");
        var mistakenWinner = new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact(
                "purchaserName", "中标公司", "page:1", "中标人：中标公司");
        var wrongOrganizationFromNearbyLabel = new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact(
                "purchaserName", "中标公司", "page:1", "采购人：市政服务中心；中标人：中标公司");
        var agency = new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact(
                "agencyName", "远达招标代理有限公司", "page:1", "招标代理机构：远达招标代理有限公司");
        assertThat(ProcurementMaterialBatchService.amountRoleConsistent(purchaser)).isTrue();
        assertThat(ProcurementMaterialBatchService.amountRoleConsistent(agency)).isTrue();
        assertThat(ProcurementMaterialBatchService.amountRoleConsistent(mistakenWinner)).isFalse();
        assertThat(ProcurementMaterialBatchService.amountRoleConsistent(wrongOrganizationFromNearbyLabel)).isFalse();
    }

    com.fuquan.audit.common.material.domain.MaterialIntake intake(String filename) {
        return new com.fuquan.audit.common.material.domain.MaterialIntake(UUID.randomUUID(),"actor","operation",filename,
            null,3,"hash","b".repeat(64),"PARSED",null,Instant.EPOCH,null,null,null,Instant.EPOCH,Instant.EPOCH);
    }
    @Test void intakeRetryCompletesOriginalScopeAndPreservesLaterAppendedMaterial() {
        var a=intake("需求.docx"); var b=intake("招标.docx");
        var first=ready("需求.docx","MATERIAL_APACHE_POI","需求");
        var second=ready("招标.docx","MATERIAL_APACHE_POI","招标");
        var appended=ready("补充.pdf","MATERIAL_PDF","补充材料");
        when(documents.reuseIntake(id,a,"actor",transactions)).thenReturn(first);
        when(documents.reuseIntake(id,b,"actor",transactions)).thenReturn(second);
        when(documents.currentMaterials(id)).thenReturn(List.of(first,second,appended));
        assertThat(service.reuseIntakes(id,List.of(a,b),"actor")).containsExactly(first.id(),second.id(),appended.id());
        verify(documents).reuseIntake(id,b,"actor",transactions);
        verifyNoInteractions(scans,sheets,quotes,reports);
    }
    @Test void hiddenOrMissingOriginalCannotBeAnalyzedAsACompleteSubset() {
        var a=intake("同名.docx"); var b=intake("同名.docx");
        var first=ready("同名.docx","MATERIAL_APACHE_POI","第一份");
        var second=ready("同名.docx","MATERIAL_APACHE_POI","第二份");
        when(documents.reuseIntake(id,a,"actor",transactions)).thenReturn(first);
        when(documents.reuseIntake(id,b,"actor",transactions)).thenReturn(second);
        when(documents.currentMaterials(id)).thenReturn(List.of(second));
        assertThatThrownBy(()->service.reuseIntakes(id,List.of(a,b),"actor")).hasMessageContaining("已停止分析");
        verifyNoInteractions(scans,sheets,quotes,reports);
    }
    @Test void modelFailureKeepsRulesButNeverClaimsCompletedAiOnAnalysisOrReopen() {
        for(var status:List.of("AI_TIMEOUT","AI_UNAVAILABLE","AI_INVALID_RESPONSE")) {
            var source=ready("招标.docx","MATERIAL_APACHE_POI_REVIEWED_"+status,"招标文件\n项目名称：仿真采购");
            when(documents.currentMaterials(id)).thenReturn(List.of(source));
            when(scans.scan(id,source.id())).thenReturn(new ProcurementTenderRuleScanService.ScanResult(id,source.id(),0,
                    List.of(),ProcurementTenderRuleScanService.AiStatus.valueOf(status)));
            var analyzed=service.analyzeSaved(id,List.of(source.id()),"actor",true).files().get(0);
            var reopened=service.reopen(id).files().get(0);
            assertThat(analyzed.status()).isEqualTo("INCOMPLETE");
            assertThat(analyzed.message()).contains("AI 语义审查尚未完成");
            assertThat(reopened.status()).isEqualTo("INCOMPLETE");
            assertThat(reopened.scan().aiStatus()).isEqualTo(ProcurementTenderRuleScanService.AiStatus.valueOf(status));
        }
    }
    @Test void explicitAnalysisRetriesSavedAiFailuresButReusesOnlyAvailableResults() {
        when(reports.requireLatestForMaterials(eq(id),anyList())).thenThrow(new com.fuquan.audit.common.exception.BusinessException(
            com.fuquan.audit.common.exception.BusinessErrorCode.NOT_FOUND));
        for(var status:List.of("AI_TIMEOUT","AI_UNAVAILABLE","AI_INVALID_RESPONSE")) {
            var source=ready("招标.docx","MATERIAL_APACHE_POI_REVIEWED_"+status,"招标文件\n项目名称：仿真采购");
            when(documents.currentMaterials(id)).thenReturn(List.of(source));
            service.analyzeSaved(id,List.of(source.id()),"actor",true);
            verify(scans).scan(id,source.id());
        }
        var successful=ready("招标.docx","MATERIAL_APACHE_POI_REVIEWED_AVAILABLE","招标文件\n项目名称：仿真采购");
        when(documents.currentMaterials(id)).thenReturn(List.of(successful));
        when(scans.findFindings(id,successful.id())).thenReturn(List.of());
        service.analyzeSaved(id,List.of(successful.id()),"actor",true);
        verify(scans,never()).scan(id,successful.id());
        assertThat(service.reopen(id).files()).hasSize(1);
        verify(scans,times(3)).scan(any(),any());
    }
    final UUID id = UUID.randomUUID();
    final ProcurementTenderDocumentService documents = mock(ProcurementTenderDocumentService.class);
    final ProcurementTenderRuleScanService scans = mock(ProcurementTenderRuleScanService.class);
    final ProcurementReviewMaterialService sheets = mock(ProcurementReviewMaterialService.class);
    final ProcurementCollusionReviewService collusion = mock(ProcurementCollusionReviewService.class);
    final ProcurementCollusionRepository bidders = mock(ProcurementCollusionRepository.class);
    final ProcurementTenderDocumentRepository sources = mock(ProcurementTenderDocumentRepository.class);
    final ProcurementQuoteCheckService quotes = mock(ProcurementQuoteCheckService.class);
    final ProcurementReviewBlacklistService blacklist = mock(ProcurementReviewBlacklistService.class);
    final ProcurementReviewWorkflowService workflow = mock(ProcurementReviewWorkflowService.class);
    final ProcurementMaterialTransactions transactions = mock(ProcurementMaterialTransactions.class);
    final ProcurementReviewReportService reports = mock(ProcurementReviewReportService.class);
    final ProcurementMaterialBatchService service = new ProcurementMaterialBatchService(documents,scans,sheets,collusion,
            bidders,sources,quotes,blacklist,workflow,transactions,Clock.systemUTC(),reports);
    @BeforeEach void useRealRepositoryContractsByDefault() {
        when(reports.requireLatestForMaterials(eq(id),anyList())).thenThrow(new com.fuquan.audit.common.exception.BusinessException(
                com.fuquan.audit.common.exception.BusinessErrorCode.NOT_FOUND));
        when(documents.recordFieldExtraction(eq(id),any(),anyList(),any())).thenAnswer(call -> {
            var original = documents.currentMaterials(id).stream().filter(d -> d.id().equals(call.getArgument(1)))
                    .findFirst().orElseThrow();
            var parsed = original.parseResult();
            return new ProcurementTenderDocumentService.TenderDocumentResult(original.id(),original.projectId(),
                    original.fileName(),original.fileExtension(),original.contentType(),original.fileSize(),original.sha256(),
                    original.status(),original.parseMethod(),original.parseEngine(),original.parseConfidence(),original.pageCount(),
                    original.parseError(),new ProcurementTenderParseResult(parsed.fullText(),parsed.segments(),
                            call.getArgument(2),call.getArgument(3)),original.createdTime(),original.updatedTime());
        });
    }
    ProcurementMaterialBatchService.Upload upload(String name) {
        return new ProcurementMaterialBatchService.Upload(name,null,3,()->new ByteArrayInputStream(new byte[]{1,2,3}));
    }
    @Test void invalidAndUnauthorizedFilesAreIsolatedBeforeTheirStorageSideEffects() {
        var result = service.stage(id,List.of(upload("材料.zip"),upload("报价.xlsx")),"actor",false);
        assertThat(result.files()).hasSize(2);
        assertThat(result.files()).extracting(ProcurementMaterialBatchService.FileResult::message).anyMatch(message -> message.contains("权限"));
        verify(documents,never()).uploadMaterial(any(),any(),any(),any(),any(),any());
        verify(documents,never()).storeSpreadsheet(any(),any(),any(),any(),any(),any());
        verifyNoInteractions(transactions,sheets);
    }
    @Test void acceptsMoreThanTenFilesAndMoreThanOldByteLimitsButRetainsPerFileBound() {
        var max = new ProcurementMaterialBatchService.Upload("a.pdf",null,500L*1024*1024,()->null);
        assertThatCode(()->ProcurementMaterialBatchService.validate(Collections.nCopies(25,max),true)).doesNotThrowAnyException();
        assertThatCode(()->ProcurementMaterialBatchService.validateFile(max,true)).doesNotThrowAnyException();
        var huge = new ProcurementMaterialBatchService.Upload("a.pdf",null,500L*1024*1024+1,()->null);
        assertThatThrownBy(()->ProcurementMaterialBatchService.validateFile(huge,true)).hasMessageContaining("500 MB");
    }
    @Test void reviewEntryMatchesOfficeIntakeTypesIncludingScannedImagesAndRejectsArchives() {
        for(var extension:List.of("doc","docx","xls","xlsx","pdf","ppt","pptx","txt","csv","rtf","png","jpg","jpeg","bmp","tif","tiff"))
            assertThatCode(()->ProcurementMaterialBatchService.validateFile(upload("材料."+extension),true)).doesNotThrowAnyException();
        assertThatThrownBy(()->ProcurementMaterialBatchService.validateFile(upload("材料.zip"),true))
                .hasMessageContaining("压缩包");
    }
    @Test void failedFirstStageStillStoresAllLaterFilesAndReopensSavedFacts() {
        var saved = ready("有效.pdf","MATERIAL_APACHE_POI","招标文件");
        when(documents.currentMaterials(id)).thenReturn(List.of(saved));
        var bad = new ProcurementMaterialBatchService.Upload("损坏.pdf",null,3,()->{throw new IOException("read failed");});
        var uploads = new ArrayList<ProcurementMaterialBatchService.Upload>();
        uploads.add(bad);
        for(int i=0;i<12;i++) uploads.add(upload("有效"+i+".pdf"));
        var result = service.stage(id,uploads,"actor",true);
        verify(documents,times(12)).uploadMaterial(eq(id),anyString(),isNull(),any(),eq("actor"),eq(transactions));
        assertThat(result.files()).extracting(ProcurementMaterialBatchService.FileResult::fileName).contains("有效.pdf","损坏.pdf");
        assertThat(result.files().stream().filter(f->f.fileName().equals("损坏.pdf")).findFirst().orElseThrow().document()).isNull();
    }
    @Test void oneUnreadableFileDoesNotDiscardAnotherFileResult() throws Exception {
        var bad = new ProcurementMaterialBatchService.Upload("a.pdf",null,3,()->{throw new IOException("broken");});
        var source = new ProcurementTenderDocumentService.TenderDocumentResult(UUID.randomUUID(),id,"b.xlsx","xlsx",null,
                3,"hash","UPLOADING",null,null,null,null,null,null,Instant.now(),Instant.now());
        when(documents.storeSpreadsheet(eq(id),eq("b.xlsx"),isNull(),any(),eq("actor"),eq(transactions))).thenReturn(source);
        when(documents.currentMaterials(id)).thenReturn(List.of(source));
        when(sheets.analyzeSaved(eq(id),eq(source.id()),eq("actor"))).thenReturn(
                new ProcurementReviewMaterialService.Result("NOT_APPLICABLE","未识别报价",null,null,null));
        var result = service.upload(id,List.of(bad,upload("b.xlsx")),"actor",true);
        assertThat(result.files()).extracting(ProcurementMaterialBatchService.FileResult::status).containsExactly("NOT_APPLICABLE","INCOMPLETE");
        verifyNoInteractions(collusion);
        verify(blacklist,never()).check(any());
        verify(reports).generateForMaterials(id,List.of(source.id()));
    }
    @Test void unreadableSavedSpreadsheetDoesNotStopOtherFilesAndStillGeneratesPartialReport() throws Exception {
        var bad=ready("损坏.xlsx","QUOTE_STAGED",null);
        var good=ready("采购说明.docx","MATERIAL_APACHE_POI","招标文件\n项目名称：仿真采购");
        when(documents.currentMaterials(id)).thenReturn(List.of(bad,good));
        when(documents.requireMaterial(id,bad.id())).thenReturn(bad);
        when(reports.requireLatestForMaterials(eq(id),anyList())).thenThrow(new com.fuquan.audit.common.exception.BusinessException(
                com.fuquan.audit.common.exception.BusinessErrorCode.NOT_FOUND));
        var report=mock(ProcurementReviewReportService.ReportData.class);
        when(reports.generateForMaterials(id,List.of(bad.id(),good.id()))).thenReturn(report);
        when(sheets.analyzeSaved(id,bad.id(),"actor")).thenThrow(new IOException("private file content must not appear"));
        var result=service.analyzeSaved(id,List.of(bad.id(),good.id()),"actor",true);
        assertThat(result.files()).extracting(ProcurementMaterialBatchService.FileResult::status).contains("PARSE_FAILED");
        assertThat(result.files().get(0).message()).doesNotContain("private file");
        assertThat(result.report()).isSameAs(report);
        assertThat(result.notices()).anyMatch(notice->notice.contains("报告已生成"));
        verify(scans).scan(id,good.id());
        verify(reports).generateForMaterials(id,List.of(bad.id(),good.id()));
    }
    @Test void sameProjectRejectsConcurrentBatchThenReleasesCapacityAfterFailure() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var blocked = new ProcurementMaterialBatchService.Upload("a.pdf",null,3,()-> {
            entered.countDown(); try { if(!release.await(5,TimeUnit.SECONDS)) throw new IOException("timeout"); }
            catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IOException(ex);}
            throw new IOException("broken");
        });
        var pool = Executors.newSingleThreadExecutor();
        try {
            var first=pool.submit(()->service.upload(id,List.of(blocked),"actor",true));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->service.upload(id,List.of(upload("a.pdf")),"actor",true)).hasMessageContaining("正在处理");
            assertThatThrownBy(()->service.stage(id,List.of(upload("a.pdf")),"actor",true)).hasMessageContaining("正在处理");
            assertThatThrownBy(()->service.analyzeSaved(id,List.of(UUID.randomUUID()),"actor",true)).hasMessageContaining("正在处理");
            release.countDown(); assertThat(first.get(5,TimeUnit.SECONDS).files().get(0).status()).isEqualTo("INCOMPLETE");
            assertThat(service.upload(id,List.of(blocked),"actor",true).files()).hasSize(1);
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void reopeningNeverTriggersAnalysisOrCreatesWorkflow() {
        when(documents.currentMaterials(id)).thenReturn(List.of());
        assertThat(service.reopen(id).files()).isEmpty();
        verifyNoInteractions(scans,sheets,collusion,workflow,transactions);
        verify(blacklist).findStoredResult(id);
        verify(blacklist,never()).check(any());
    }
    @Test void uploadedBatchAutomaticallyAnalyzesAndPersistsReportBeforeReturning() {
        var source = ready("招标.docx", "MATERIAL_APACHE_POI", "招标文件\n项目名称：办公设备采购");
        when(documents.uploadMaterial(eq(id),eq("招标.docx"),isNull(),any(),eq("actor"),eq(transactions))).thenReturn(source);
        when(documents.currentMaterials(id)).thenReturn(List.of(source));
        var result = service.stage(id,List.of(upload("招标.docx")),"actor",true);
        assertThat(result.files()).hasSize(1);
        assertThat(result.files().get(0).status()).isEqualTo("INCOMPLETE");
        assertThat(service.reopen(id).files().get(0).document().id()).isEqualTo(source.id());
        verifyNoInteractions(sheets,collusion);
        verify(scans).scan(id,source.id());
        verify(reports).generateForMaterials(id,List.of(source.id()));
        verify(blacklist,never()).check(any());
    }
    @Test void uploadedSpreadsheetIsSavedBeforeAutomaticallyAnalyzingItsDurableIdentity() throws Exception {
        var source=ready("报价.xlsx","QUOTE_PENDING",null);
        when(documents.storeSpreadsheet(eq(id),eq("报价.xlsx"),isNull(),any(),eq("actor"),eq(transactions))).thenReturn(source);
        when(documents.currentMaterials(id)).thenReturn(List.of(source));
        when(sheets.analyzeSaved(id,source.id(),"actor")).thenReturn(
                new ProcurementReviewMaterialService.Result("NON_QUOTATION","此文件不包含报价",source,null,null));
        assertThat(service.stage(id,List.of(upload("报价.xlsx")),"actor",true).files().get(0).status()).isEqualTo("NON_QUOTATION");
        verify(documents).recordSpreadsheet(id,source.id(),"QUOTE_STAGED");
        verify(sheets).analyzeSaved(id,source.id(),"actor");
        verify(reports).generateForMaterials(id,List.of(source.id()));
        verifyNoInteractions(collusion);
    }
    @Test void reopenPreservesUnknownAndProcessingWithoutDependingOnThisJvmActiveSet() {
        for (var marker : Map.of("QUOTE_UNKNOWN","ANALYSIS_UNCONFIRMED","QUOTE_PROCESSING","PROCESSING").entrySet()) {
            when(documents.currentMaterials(id)).thenReturn(List.of(ready("报价.xlsx",marker.getKey(),null)));
            assertThat(service.reopen(id).files().get(0).status()).isEqualTo(marker.getValue());
        }
        verifyNoInteractions(sheets,scans,transactions);
    }
    @Test void activeParseDoesNotPublishACompletedReportOrCallTheModel() {
        var source=ready("材料.docx","QUOTE_PENDING",null);
        when(documents.currentMaterials(id)).thenReturn(List.of(source));
        assertThat(service.analyzeSaved(id,List.of(source.id()),"actor",true).files().get(0).status()).isEqualTo("PROCESSING");
        verify(reports,never()).generateForMaterials(any(),any());
        verifyNoInteractions(scans,sheets);
    }
    @Test void persistenceFailureIsNotSilentlyTurnedIntoSuccessfulFieldExtraction() {
        var source=ready("招标.docx","MATERIAL_APACHE_POI","招标文件");
        when(documents.currentMaterials(id)).thenReturn(List.of(source));
        doThrow(new IllegalStateException("storage unavailable")).when(documents).recordFieldExtraction(any(),any(),any(),any());
        assertThatThrownBy(()->service.analyzeSaved(id,List.of(source.id()),"actor",true))
                .isInstanceOf(IllegalStateException.class).hasMessage("storage unavailable");
        verify(documents,times(1)).recordFieldExtraction(any(),any(),any(),any());
        verify(reports,never()).generateForMaterials(any(),any());
        verifyNoInteractions(scans);
    }
    @Test void applicantCannotStartDocumentQuoteAnalysisThroughTheUploadRoute() {
        var source=ready("报价单.docx","MATERIAL_APACHE_POI","报价单\n供应商：甲公司\n报价金额：100元");
        when(documents.currentMaterials(id)).thenReturn(List.of(source));
        service.stage(id,List.of(upload("报价单.docx")),"actor",false);
        verify(quotes,never()).analyzeMaterialQuoteBatch(any(),any(),any());
        verify(quotes,never()).analyzeSourceTotals(any(),any());
    }
    @Test void rejectsChangedOrDuplicateScopeBeforeAnyAnalysis() {
        var source=ready("招标.docx","MATERIAL_APACHE_POI","招标文件");
        when(documents.currentMaterials(id)).thenReturn(List.of(source));
        assertThatThrownBy(()->service.analyzeSaved(id,List.of(UUID.randomUUID()),"actor",true)).hasMessageContaining("清单已变化");
        assertThatThrownBy(()->service.analyzeSaved(id,List.of(source.id(),source.id()),"actor",true)).hasMessageContaining("上传采购材料");
        verifyNoInteractions(scans,sheets,reports,transactions,workflow);
    }
    @Test void oneExplicitAnalysisProducesReportAndRetryReopensSameReport() {
        var source=ready("招标.docx","MATERIAL_APACHE_POI","招标文件\n项目名称：办公设备采购");
        when(documents.currentMaterials(id)).thenReturn(List.of(source));
        when(reports.requireLatestForMaterials(eq(id),anyList())).thenThrow(new com.fuquan.audit.common.exception.BusinessException(
                com.fuquan.audit.common.exception.BusinessErrorCode.NOT_FOUND));
        var report=mock(ProcurementReviewReportService.ReportData.class);
        when(reports.generateForMaterials(id,List.of(source.id()))).thenReturn(report);
        assertThat(service.analyzeSaved(id,List.of(source.id()),"actor",true).report()).isSameAs(report);
        verify(scans).scan(id,source.id());
        verify(reports).generateForMaterials(id,List.of(source.id()));
        doReturn(report).when(reports).requireLatestForMaterials(eq(id),anyList());
        assertThat(service.analyzeSaved(id,List.of(source.id()),"actor",true).report()).isSameAs(report);
        verify(scans,times(1)).scan(any(),any());
        verify(reports,times(1)).generateForMaterials(any(),any());
    }
    @Test void unreadableMaterialStillReturnsAReportWithoutInventingFacts() {
        var source=ready("损坏.docx","MATERIAL_PENDING",null);
        when(documents.currentMaterials(id)).thenReturn(List.of(source));
        when(reports.requireLatestForMaterials(eq(id),anyList())).thenThrow(new com.fuquan.audit.common.exception.BusinessException(
                com.fuquan.audit.common.exception.BusinessErrorCode.NOT_FOUND));
        var report=mock(ProcurementReviewReportService.ReportData.class);
        when(reports.generateForMaterials(id,List.of(source.id()))).thenReturn(report);
        var result=service.analyzeSaved(id,List.of(source.id()),"actor",true);
        assertThat(result.report()).isSameAs(report);
        assertThat(result.files().get(0).status()).isEqualTo("PARSE_INCOMPLETE");
        verify(reports).generateForMaterials(id,List.of(source.id()));
        verify(scans,never()).scan(any(),any());
    }
    @Test void explicitAnalysisRetainsPreviouslyConfirmedBidderWithoutRequestingConfirmationAgain() {
        var source=ready("投标文件.docx","MATERIAL_APACHE_POI","投标文件\n技术响应与交付方案");
        when(documents.currentMaterials(id)).thenReturn(List.of(source));
        when(bidders.findDocuments(id)).thenReturn(List.of(new ProcurementCollusionDocument(source.id(),id,"已确认供应商",
                null,null,source.fileName(),"docx",null,3,"hash","storage",source.parseResult().fullText(),Instant.now())));
        when(reports.requireLatestForMaterials(eq(id),anyList())).thenThrow(new com.fuquan.audit.common.exception.BusinessException(
                com.fuquan.audit.common.exception.BusinessErrorCode.NOT_FOUND));
        var result=service.analyzeSaved(id,List.of(source.id()),"actor",true);
        assertThat(result.files().get(0).status()).isEqualTo("BIDDER_RECOGNIZED");
        verify(transactions,never()).registerBidder(any(),any());
        verifyNoInteractions(scans);
    }
    private ProcurementTenderDocumentService.TenderDocumentResult ready(String name,String engine,String text) {
        var parsed = text==null ? null : new ProcurementTenderParseResult(text,List.of());
        return new ProcurementTenderDocumentService.TenderDocumentResult(UUID.randomUUID(),id,name,
                name.endsWith("xlsx")?"xlsx":"docx",null,3,"hash","QUOTE_PENDING".equals(engine)?"UPLOADING":"AVAILABLE",null,engine,null,null,null,parsed,Instant.now(),Instant.now());
    }
    @Test void duplicateVersionsOfSameBidderNeverSatisfyTwoBidderRequirement() {
        var a = doc("供应商甲",Instant.parse("2026-10-01T00:00:00Z"));
        var b = doc(" 供应商甲 ",Instant.parse("2026-10-02T00:00:00Z"));
        assertThat(ProcurementCollusionReviewService.scoringDocuments(List.of(a,b))).containsExactly(b);
    }
    @Test void byteIdenticalFilesFromDifferentBiddersRemainComparableEvidence() {
        assertThat(ProcurementCollusionReviewService.scoringDocuments(List.of(doc("甲",Instant.now()),doc("乙",Instant.now())))).hasSize(2);
    }
    @Test void boundedBidderCountRejectsExpensiveWorkRatherThanSilentlyTruncating() {
        var all=java.util.stream.IntStream.range(0,21).mapToObj(i->doc("供应商"+i,Instant.now())).toList();
        assertThatThrownBy(()->ProcurementCollusionReviewService.scoringDocuments(all)).hasMessageContaining("20 家");
    }
    private ProcurementCollusionDocument doc(String name,Instant time) {
        return new ProcurementCollusionDocument(UUID.randomUUID(),id,name,null,null,"a.docx","docx","application/octet-stream",
                3,"a".repeat(64),"storage","投标内容",time);
    }
}
