package com.fuquan.audit.procurement.application;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fuquan.audit.procurement.application.port.*;
import com.fuquan.audit.procurement.domain.*;
import com.fuquan.audit.contract.application.port.FileStoragePort;
import com.fuquan.audit.contract.application.project.parse.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class ProcurementCollusionAutomaticTest {
    final UUID id=UUID.randomUUID();
    ProcurementCollusionDocument doc(String bidder) { return new ProcurementCollusionDocument(UUID.randomUUID(),id,bidder,null,null,
            bidder+".docx","docx","application/octet-stream",20,"a".repeat(64),"storage","办公设备统一安装验收培训售后保障".repeat(5),Instant.now()); }
    @Test void twoBiddersWithoutQuoteCanBeScoredWithoutInventingMissingCv() {
        var projects=mock(ProcurementProjectQueryService.class);var quotes=mock(ProcurementQuoteCheckService.class);
        var workflow=mock(ProcurementReviewWorkflowService.class);var repo=mock(ProcurementCollusionRepository.class);
        var tx=mock(ProcurementCollusionTransactionCoordinator.class);
        when(projects.requireById(id)).thenReturn(new ProcurementProject(id,"P1","办公采购",null,null,new BigDecimal("800000"),null,"DRAFT",Instant.now()));
        when(repo.findDocuments(id)).thenReturn(List.of(doc("甲"),doc("乙")));
        var service=new ProcurementCollusionReviewService(projects,quotes,workflow,repo,mock(FileStoragePort.class),
                mock(ProjectDocxTextParser.class),mock(ProjectPdfTextParser.class),Clock.systemUTC(),ProcurementCollusionProperties.defaults(),tx);
        var result=service.score(id);
        assertThat(result.assessment().quoteCoefficientOfVariation()).isNull();
        assertThat(result.assessment().riskScore()).isEqualTo(40);
        assertThat(result.assessment().duplicateIpGroups()).isZero();assertThat(result.assessment().duplicateHandlerGroups()).isZero();
        verify(quotes,never()).requireByBatchId(anyString());
        verify(tx).saveValidated(eq(id),eq(result.assessment()),any(),anyString(),anySet(),isNull());
    }
    @Test void unrelatedQuoteSuppliersDoNotContributeToTheBidderRiskScore() {
        var q=ProcurementQuoteCheck.analyze(id.toString(),new BigDecimal("100000"),List.of(
                new ProcurementQuoteCheck.QuoteInput(1,"甲",new BigDecimal("1000"),"电脑"),
                new ProcurementQuoteCheck.QuoteInput(2,"乙",new BigDecimal("1001"),"电脑"),
                new ProcurementQuoteCheck.QuoteInput(3,"丙",new BigDecimal("1002"),"电脑")));
        assertThat(ProcurementCollusionReviewService.matchingQuoteCv(q,List.of(doc("甲"),doc("乙"),doc("丙")))).isEqualByComparingTo(q.coefficientOfVariation());
        assertThat(ProcurementCollusionReviewService.matchingQuoteCv(q,List.of(doc("甲"),doc("乙"),doc("丁")))).isNull();
    }
    @Test void decimalBidTotalExtractionRejectsMissingOrConflictingFinalPrices() {
        assertThat(ProcurementBidQuotationExtractor.extract("投标报价为人民币 799,800.00 元，含全部税费。")).isEqualByComparingTo("799800");
        assertThat(ProcurementBidQuotationExtractor.extract("投标报价：799800元\n投标报价：799801元")).isNull();
        assertThat(ProcurementBidQuotationExtractor.extract("单价：800元，数量100；历史采购799800元")).isNull();
        assertThat(ProcurementBidQuotationExtractor.extract("投标报价：0元")).isNull();
        assertThat(ProcurementBidQuotationExtractor.extract("投标报价：人民币壹拾贰万叁仟肆佰伍拾陆元柒角捌分整"))
                .isEqualByComparingTo("123456.78");
        assertThat(ProcurementBidQuotationExtractor.extract("投标报价：120000元（大写：壹拾万元整）")).isNull();
        assertThat(ProcurementBidQuotationExtractor.extract("投标报价：壹元零伍分整，含税")).isEqualByComparingTo("1.05");
        assertThat(ProcurementBidQuotationExtractor.extract("投标报价：壹元贰分叁角")).isNull();
        assertThat(ProcurementBidQuotationExtractor.extract("投标报价：壹元整贰角")).isNull();
        assertThat(ProcurementBidQuotationExtractor.extract("投标报价：壹圓貳角參分正")).isEqualByComparingTo("1.23");
        assertThat(ProcurementBidQuotationExtractor.extract("投标报价：100元\n投标报价：0元")).isNull();
    }
    @Test void concurrentInputRevisionRejectsScoreBeforeAnyResultWrite() {
        var repo=mock(ProcurementCollusionRepository.class);var workflow=mock(ProcurementReviewWorkflowService.class);
        var locks=mock(ProcurementReviewWorkflowRepository.class);var quotes=mock(ProcurementQuoteCheckService.class);
        var current=doc("甲");when(repo.findDocuments(id)).thenReturn(List.of(current));
        var tx=new ProcurementCollusionTransactionCoordinator(repo,workflow,locks,quotes,null,null);
        var assessment=new ProcurementCollusionAssessment(UUID.randomUUID(),id,40,"WARNING",null,BigDecimal.ONE,0,0,List.of(),Instant.now());
        assertThatThrownBy(()->tx.saveValidated(id,assessment,null,"score",Set.of(UUID.randomUUID()),null)).hasMessageContaining("材料已更新");
        verify(repo,never()).saveAssessment(any());verify(repo,never()).saveSplitPurchaseAssessment(any());
    }
}
