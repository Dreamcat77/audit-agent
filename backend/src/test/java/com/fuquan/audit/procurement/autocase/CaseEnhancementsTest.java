package com.fuquan.audit.procurement.autocase;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.fuquan.audit.procurement.autocase.AuditCase.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.blacklist.application.port.*;
import com.fuquan.audit.blacklist.domain.BlacklistMatchResult;
import com.fuquan.audit.common.application.OperationAuditService;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Segment;
import com.fuquan.audit.security.domain.CurrentUser;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CaseEnhancementsTest {
    final CurrentUser user=new CurrentUser("auditor","auditor","测试员",Set.of("AUDITOR"));
    final Clock clock=Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"),ZoneOffset.UTC);
    Fact fact(String type,String value,String quote){return new Fact("r",type,value,"TEXT","p1",quote,"SOURCE_VERIFIED","TEST","v1");}
    Document supplier(String name) {return new Document(UUID.randomUUID(),"报价单.docx",10,"a".repeat(64),"key","EXTRACTED",
            List.of(new Segment("p1","供应商："+name)),List.of(fact("supplierName",name,"供应商："+name)),"WORD",null,1);}
    @Test void exactHitBlocksButFuzzyHitNeverBecomesExactAndUsesNoFakeProjectId() {
        var port=mock(BlacklistCheckPort.class);var audit=mock(OperationAuditService.class);
        when(port.checkBatch(anyList())).thenAnswer(call->{
            List<BlacklistCheckPort.CheckCommand> commands=call.getArgument(0);
            assertThat(commands).allSatisfy(c->assertThat(c.procurementId()).isNull());
            return commands.stream().map(c->new BlacklistCheckPort.CheckOutcome(c.batchId(),c.supplierName(),null,true,
                    List.of(c.supplierName().equals("甲公司")?BlacklistMatchResult.exact("甲公司","甲公司"):BlacklistMatchResult.fuzzy("乙公司","乙公司集团",90)),null)).toList();
        });
        var result=new CaseBlacklistReview(port,clock,audit).check(UUID.randomUUID(),List.of(supplier("甲公司"),supplier("乙公司")),user);
        assertThat(result.checks()).extracting(ReviewCheck::status).containsExactlyInAnyOrder("BLOCKED","REVIEW");
        assertThat(result.findings()).extracting(ReviewFinding::level).containsExactlyInAnyOrder("CRITICAL","WARNING");
    }
    @Test void staleExecutorCannotCreateAlarmsOrNotificationFacts() {
        var repository=mock(AuditCaseRepository.class);var audit=mock(OperationAuditService.class);var reviewer=mock(CaseBlacklistReview.class);
        var value=new AuditCase(UUID.randomUUID(),user.userId(),UUID.randomUUID(),"fp","RUNNING",8,"old-token",clock.instant(),1,clock.instant(),List.of(),null);
        when(repository.replace(eq(value),any())).thenReturn(false);
        assertThatThrownBy(()->new AuditCaseTransactions(repository,audit).blacklist(value,List.of(),null,reviewer,user,clock.instant()))
                .isInstanceOf(org.springframework.dao.ConcurrencyFailureException.class);
        verifyNoInteractions(reviewer,audit);
    }
    @Test void financialChineseQuantitiesAndAmountsProduceAnExactVerifiedQuote() {
        var original=supplier("甲公司");
        var quote=new Document(original.id(),original.name(),original.size(),original.sha256(),original.storageKey(),original.status(),original.segments(),
                List.of(fact("documentType","报价单","报价单"),fact("supplierName","甲公司","供应商甲公司"),fact("quoteAmount","贰佰元整","总报价贰佰元整"),
                    fact("itemName","设备","设备"),fact("quantity","贰台","贰台"),fact("unitPrice","壹佰元整","壹佰元整"),fact("lineAmount","贰佰元整","贰佰元整")),"WORD",null,1);
        assertThat(CaseFactPolicy.report(UUID.randomUUID(),List.of(quote)).offers()).singleElement().satisfies(o->{
            assertThat(o.amount()).isEqualByComparingTo("200");assertThat(o.lineValidation()).isEqualTo("VERIFIED");
            assertThat(o.lines()).singleElement().satisfies(l->assertThat(l.quantity()).isEqualByComparingTo("2"));
        });
    }
    @Test void htmlEscapesDocumentAndModelContentAndExactQuoteComparisonWorksForOneSupplier() throws Exception {
        var doc=supplier("<img src=x onerror=alert(1)>");
        var offer=new Offer(doc.facts().get(0).value(),new BigDecimal("10000.01"),doc.id(),List.of(),"DATA_INSUFFICIENT");
        var report=new Report("PARTIAL",new BigDecimal("10000.00"),null,List.of(offer),null,List.of(),Map.of(),Map.of(),null,new BigDecimal("9999.99"));
        var value=new AuditCase(UUID.randomUUID(),user.userId(),UUID.randomUUID(),"fp","PARTIAL",1,"token",clock.instant(),1,clock.instant(),List.of(doc),report);
        assertThat(report.quoteComparisons()).singleElement().satisfies(c->{assertThat(c.status()).isEqualTo("OVER_BUDGET");assertThat(c.difference()).isEqualByComparingTo("0.01");});
        assertThat(CaseReportHtml.render(value)).contains("&lt;img", "超过材料预算", "最高限价", "9999.99").doesNotContain("<img");
        var json=new ObjectMapper().findAndRegisterModules();
        assertThat(json.readValue(json.writeValueAsString(report),Report.class)).isEqualTo(report);
    }
    @Test void conflictingProjectNumbersCannotApplyAnotherProjectsBudgetToAnOffer() {
        var a=supplier("甲公司");
        var quote=new Document(a.id(),a.name(),a.size(),a.sha256(),a.storageKey(),a.status(),a.segments(),
                List.of(fact("projectNo","A","编号A"),fact("documentType","报价单","报价单"),fact("supplierName","甲公司","供应商甲公司"),fact("quoteAmount","12000元","报价12000元")),a.parseMethod(),null,1);
        var budget=new Document(UUID.randomUUID(),"预算.pdf",10,"b".repeat(64),"key","EXTRACTED",List.of(),
                List.of(fact("projectNo","B","编号B"),fact("budgetAmount","10000元","预算10000元")),"PDF",null,1);
        var report=CaseFactPolicy.report(UUID.randomUUID(),List.of(quote,budget));
        assertThat(report.budget()).isNull();assertThat(report.quoteComparisons()).allMatch(c->c.status().equals("NOT_EVALUATED"));
    }
}
