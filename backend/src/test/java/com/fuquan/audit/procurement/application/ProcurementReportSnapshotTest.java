package com.fuquan.audit.procurement.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.blacklist.application.BlacklistQueryService;
import com.fuquan.audit.procurement.application.port.*;
import com.fuquan.audit.procurement.domain.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProcurementReportSnapshotTest {
    final UUID id=UUID.randomUUID();
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final ProcurementProjectQueryService projects=mock(ProcurementProjectQueryService.class);
    final ProcurementAgentReviewSnapshotService agents=mock(ProcurementAgentReviewSnapshotService.class);
    final ProcurementQuoteCheckService quotes=mock(ProcurementQuoteCheckService.class);
    final BlacklistQueryService blacklist=mock(BlacklistQueryService.class);
    final ProcurementReviewReportRepository reports=mock(ProcurementReviewReportRepository.class);
    final ProcurementTenderDocumentRepository documents=mock(ProcurementTenderDocumentRepository.class);
    final ProcurementTenderFindingRepository findings=mock(ProcurementTenderFindingRepository.class);
    final ProcurementCollusionRepository collusion=mock(ProcurementCollusionRepository.class);
    final ProcurementReviewWorkflowService workflow=mock(ProcurementReviewWorkflowService.class);
    final ProcurementReviewReportService service=new ProcurementReviewReportService(projects,agents,quotes,blacklist,
            reports,documents,findings,collusion,workflow,json);
    ProcurementAgentReviewService.ReviewResult agent() {
        return new ProcurementAgentReviewService.ReviewResult(id,"P","原项目","INFO","原建议","原摘要",Instant.EPOCH,List.of());
    }
    ProcurementProject project(String name) {
        return new ProcurementProject(id,"P",name,"S","设备",new BigDecimal("123456.78"),null,"DRAFT",Instant.EPOCH);
    }
    ProcurementReviewReport metadata(String value) {
        try {return new ProcurementReviewReport(UUID.randomUUID(),id,"PCR-TEST","WARNING",Instant.EPOCH,"原结论",json.writeValueAsString(agent()),value);}
        catch(Exception failure){throw new RuntimeException(failure);}
    }
    @Test void nonQuoteMaterialCanProduceFrozenReferenceWithoutClaimingQuoteAnalysis() {
        var source=spreadsheet("NON_QUOTATION", ProcurementTenderDocumentStatus.AVAILABLE);
        when(projects.requireById(id)).thenReturn(project("原项目"));
        when(documents.findByProjectId(id)).thenReturn(List.of(source));
        var state=mock(ProcurementReviewWorkflowService.State.class);
        when(state.steps()).thenReturn(List.of());
        when(workflow.require(id)).thenReturn(state);
        when(agents.find(id)).thenReturn(Optional.of(agent()));
        when(blacklist.findAlertPage(id.toString(),null,null,null,1,1000)).thenReturn(
                new BlacklistQueryService.Page<>(List.of(),1,1000,0));
        var result=service.generateForMaterials(id,List.of(source.id()));
        assertThat(result.metadata()).isNotNull();
        assertThat(result.quoteCheck()).isNull();
        assertThat(result.overallConclusion()).contains("未执行比价","仅供参考");
        verify(workflow).recordVerifiedMaterialReport(eq(id),anyString());
        verify(workflow,never()).recordReportGeneratedIfStarted(any(),anyString());
        verify(reports).save(result.metadata());
    }

    @Test void scopedReportRejectsLiveProcessingButIncludesTerminalMaterialFailures() {
        var state=mock(ProcurementReviewWorkflowService.State.class);
        when(state.steps()).thenReturn(List.of());
        when(workflow.require(id)).thenReturn(state);
        when(projects.requireById(id)).thenReturn(project("原项目"));
        when(agents.find(id)).thenReturn(Optional.of(agent()));
        when(blacklist.findAlertPage(id.toString(),null,null,null,1,1000)).thenReturn(
                new BlacklistQueryService.Page<>(List.of(),1,1000,0));
        var pending=spreadsheet("QUOTE_PROCESSING",ProcurementTenderDocumentStatus.AVAILABLE);
        when(documents.findByProjectId(id)).thenReturn(List.of(pending));
        assertThatThrownBy(()->service.generateForMaterials(id,List.of(pending.id())))
                .isInstanceOf(com.fuquan.audit.common.exception.BusinessException.class);

        for (var marker:List.of("QUOTE_UNKNOWN","QUOTE_PARSE_FAILED","QUOTE_ANALYSIS_FAILED")) {
            var source=spreadsheet(marker, marker.equals("QUOTE_PARSE_FAILED")
                    ? ProcurementTenderDocumentStatus.PARSE_FAILED : ProcurementTenderDocumentStatus.AVAILABLE);
            when(documents.findByProjectId(id)).thenReturn(List.of(source));
            var partial=service.generateForMaterials(id,List.of(source.id()));
            assertThat(partial.metadata()).isNotNull();
            assertThat(partial.overallConclusion()).contains("未分析部分不代表没有风险");
        }
        assertThatThrownBy(()->service.generateForMaterials(id,List.of(UUID.randomUUID())))
                .hasMessageContaining("材料清单已变化");
        when(documents.findByProjectId(id)).thenReturn(List.of());
        assertThatThrownBy(()->service.generateForMaterials(id,List.of()))
                .isInstanceOf(com.fuquan.audit.common.exception.BusinessException.class);
        verify(reports,times(3)).save(any());
        verify(workflow,times(3)).recordVerifiedMaterialReport(eq(id),anyString());
    }

    @Test void oldReportForDifferentMaterialScopeIsNotReturnedAsCurrentBatchResult() throws Exception {
        var state=mock(ProcurementReviewWorkflowService.State.class);
        var reportStep=new com.fuquan.audit.procurement.domain.ProcurementReviewStep("REPORT",7,"审查报告",
                "COMPLETED",Instant.EPOCH,"old report");
        when(state.steps()).thenReturn(List.of(reportStep));
        when(workflow.require(id)).thenReturn(state);
        when(projects.requireById(id)).thenReturn(project("原项目"));
        when(agents.find(id)).thenReturn(Optional.of(agent()));
        when(blacklist.findAlertPage(id.toString(),null,null,null,1,1000)).thenReturn(
                new BlacklistQueryService.Page<>(List.of(),1,1000,0));
        var source=spreadsheet("NON_QUOTATION",ProcurementTenderDocumentStatus.AVAILABLE);
        when(documents.findByProjectId(id)).thenReturn(List.of(source));
        var old=service.generateForMaterials(id,List.of(source.id()));
        when(reports.findLatestByProjectId(id)).thenReturn(Optional.of(old.metadata()));
        var next=service.requireLatestForMaterials(id,List.of(source.id()));
        assertThat(next.metadata().id()).isEqualTo(old.metadata().id());

        var different=spreadsheet("NON_QUOTATION",ProcurementTenderDocumentStatus.AVAILABLE);
        when(documents.findByProjectId(id)).thenReturn(List.of(different));
        assertThatThrownBy(()->service.requireLatestForMaterials(id,List.of(different.id())))
                .isInstanceOfSatisfying(com.fuquan.audit.common.exception.BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(com.fuquan.audit.common.exception.BusinessErrorCode.CONFLICT))
                .hasMessageContaining("当前批次不同");
    }

    ProcurementTenderDocument spreadsheet(String marker,ProcurementTenderDocumentStatus status) {
        return new ProcurementTenderDocument(UUID.randomUUID(),id,"合成预算.xlsx","xlsx","application/octet-stream",1,
                "a".repeat(64),"synthetic",status,null,marker,null,null,null,null,"actor",Instant.EPOCH,Instant.EPOCH);
    }
    @Test void incompleteAiIsExplicitInFrozenReportEvenAfterLaterProviderRecovery() {
        var source=new ProcurementTenderDocument(UUID.randomUUID(),id,"合成招标.pdf","pdf","application/pdf",1,
                "a".repeat(64),"synthetic",ProcurementTenderDocumentStatus.AVAILABLE,null,
                "MATERIAL_PDF_REVIEWED_AI_TIMEOUT",null,null,null,null,"actor",Instant.EPOCH,Instant.EPOCH);
        when(projects.requireById(id)).thenReturn(project("原项目"));
        when(documents.findByProjectId(id)).thenReturn(List.of(source));
        when(agents.find(id)).thenReturn(Optional.of(agent()));
        when(blacklist.findAlertPage(id.toString(),null,null,null,1,1000)).thenReturn(
                new BlacklistQueryService.Page<>(List.of(),1,1000,0));
        var generated=service.generate(id);
        assertThat(generated.overallConclusion()).contains("AI 语义审查未完成","不能据此认定");
        when(reports.findLatestByProjectId(id)).thenReturn(Optional.of(generated.metadata()));
        clearInvocations(documents,agents,quotes,blacklist,collusion);
        when(documents.findByProjectId(id)).thenReturn(List.of());
        assertThat(service.requireLatest(id).overallConclusion()).isEqualTo(generated.overallConclusion());
        verifyNoInteractions(agents,quotes,blacklist,collusion);
        verify(documents,never()).findByProjectId(id);
    }
    @Test void fullSnapshotReopensWithoutCurrentChapterQueriesAndKeepsDecimalPrecision() throws Exception {
        var finding=new ProcurementTenderFinding(UUID.randomUUID(),id,UUID.randomUUID(),"F10-TEST","测试","品牌",
                "WARNING","原条款","p1",1,"原建议",Instant.EPOCH);
        var quote=ProcurementQuoteCheck.analyze(id.toString(),new BigDecimal("123456.78"),List.of(
            new ProcurementQuoteCheck.QuoteInput(1,"合成供应商",new BigDecimal("120000.01"),"设备")));
        var hit=com.fuquan.audit.blacklist.domain.BlacklistAlert.create(id.toString(),"合成供应商","合成供应商","EXACT",100);
        var assessment=new ProcurementCollusionAssessment(UUID.randomUUID(),id,60,"WARNING",new BigDecimal("0.0064"),
            new BigDecimal("0.9876"),1,1,List.of(new ProcurementCollusionAssessment.PairSimilarity(UUID.randomUUID(),UUID.randomUUID(),"甲","乙",new BigDecimal("0.9876"))),Instant.EPOCH);
        var split=new ProcurementSplitPurchaseAssessment(UUID.randomUUID(),id,"WARNING",40,List.of(
            new ProcurementSplitPurchaseAssessment.Finding("合成供应商",Instant.EPOCH,Instant.EPOCH.plusSeconds(1),
                new BigDecimal("123456.78"),new BigDecimal("100000.00"),"测试门槛",40,"WARNING",List.of(
                    new ProcurementSplitPurchaseAssessment.Record(id,"P","原项目",new BigDecimal("123456.78"),Instant.EPOCH)))),Instant.EPOCH);
        var data=new ProcurementReviewReportService.ReportData(null,project("原项目"),agent(),quote,List.of(hit),List.of(finding),assessment,split,"WARNING","原结论");
        var saved=metadata(json.writeValueAsString(new ProcurementReviewReportService.ReportSnapshot(1,data,List.of())));
        when(reports.findLatestByProjectId(id)).thenReturn(Optional.of(saved));
        when(projects.requireById(id)).thenReturn(project("现在已修改的项目"));
        var first=service.requireLatest(id);
        var second=service.requireLatest(id);
        assertThat(first).isEqualTo(second);
        assertThat(first.project().projectName()).isEqualTo("原项目");
        assertThat(first.project().budgetAmount()).isEqualByComparingTo("123456.78");
        assertThat(first.tenderFindings()).containsExactly(finding);
        assertThat(first.quoteCheck()).isEqualTo(quote);
        assertThat(first.blacklistAlerts()).containsExactly(hit);
        assertThat(first.collusionAssessment()).isEqualTo(assessment);
        assertThat(first.splitPurchaseAssessment()).isEqualTo(split);
        verifyNoInteractions(agents,quotes,blacklist,documents,findings,collusion);
    }
    @Test void legacyRemainsReadableButDoesNotInventUnsavedChapters() throws Exception {
        when(reports.findLatestByProjectId(id)).thenReturn(Optional.of(metadata(null)));
        when(projects.requireById(id)).thenReturn(project("当前项目"));
        var data=service.requireLatest(id);
        assertThat(data.agentReview()).isEqualTo(agent());
        assertThat(data.overallConclusion()).contains("旧版报告","不能还原","原结论");
        assertThat(data.project().budgetAmount()).isNull();
        assertThat(data.tenderFindings()).isEmpty();
        verifyNoInteractions(agents,quotes,blacklist,documents,findings,collusion);
    }
    @Test void corruptUnsupportedOrCrossProjectSnapshotNeverFallsBackToCurrentData() throws Exception {
        for(String value:List.of("{broken", "{\"snapshotVersion\":99}",
                json.writeValueAsString(new ProcurementReviewReportService.ReportSnapshot(1,
                    new ProcurementReviewReportService.ReportData(null,
                        new ProcurementProject(UUID.randomUUID(),"X","其他项目",null,null,null,null,null,Instant.EPOCH),
                        agent(),null,List.of(),List.of(),null,null,"WARNING","原结论"),List.of())))) {
            when(reports.findLatestByProjectId(id)).thenReturn(Optional.of(metadata(value)));
            assertThatThrownBy(()->service.requireLatest(id)).hasMessageContaining("快照无效");
        }
        verifyNoInteractions(agents,quotes,blacklist,documents,findings,collusion);
    }
}
