package com.fuquan.audit.budget.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.budget.application.port.*;
import com.fuquan.audit.budget.domain.*;
import com.fuquan.audit.common.application.port.*;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class BudgetAmountThresholdPolicyTest {
    private static final Instant NOW=Instant.parse("2026-11-17T00:00:00Z");
    private static final List<String> RETIRED=List.of("F05_50K","F05_200K","F05_2M");

    @Test void historicalBatchReadHidesOnlyRetiredChecksWithoutWritingStoredSnapshot() throws Exception {
        var checks=List.of(check("TOTAL_APPROVAL_THRESHOLD"),check("AUTO_SPLIT_APPLICATION"),
                check("TOTAL_VS_APPROVAL"),check("APPROVAL_PROCEDURE"),check("SEMANTIC_APPROVAL_CONDITION"));
        var total=new BudgetUploadedFacts.Total("declaredTotalAmount","TOTAL","2026",new BigDecimal("1980000"),List.of());
        var saved=new BudgetUploadedFacts.FileResult("COMPLETED","PARTIAL","原始报告",List.of(),checks,"项目甲",List.of(total),"PARTIAL");
        var json=new ObjectMapper();UUID id=UUID.randomUUID(),batch=UUID.randomUUID();
        String original=json.writeValueAsString(saved);
        var source=new BudgetMaterialBatch.Source(id,batch,null,original);
        var repository=mock(BudgetMaterialBatchRepository.class);
        when(repository.source(id)).thenReturn(Optional.of(source));when(repository.find(batch)).thenReturn(Optional.empty());
        var service=new BudgetMaterialBatchService(repository,null,null,null,null,json);
        var current=service.result(id);
        assertThat(current.assessments()).extracting(BudgetUploadedFacts.Assessment::code)
                .containsExactly("TOTAL_VS_APPROVAL","APPROVAL_PROCEDURE","SEMANTIC_APPROVAL_CONDITION");
        assertThat(current.totals()).containsExactly(total);assertThat(current.coverage()).isEqualTo("PARTIAL");
        assertThat(current.analysisCoverage()).isEqualTo("PARTIAL");assertThat(source.resultJson()).isEqualTo(original);
        verify(repository,never()).result(any(),any());
    }

    @Test void savedSelectedSumLosesRetiredBandButKeepsIdentityEvidenceAndUnknownAmounts() {
        var line=new BudgetUploadedFacts.Line("项目甲",null,"设备",null,"2026",null,
                new BigDecimal("2000000.01"),null,"INSUFFICIENT","原始意见",List.of());
        var candidate=new BudgetMaterialRisk.Candidate(new BudgetMaterialRisk.Selection(UUID.randomUUID(),0),"合成.xlsx",NOW.toString(),line);
        var oldAnalysis=new BudgetMaterialSplitAnalysis.Analysis("SELECTED_SUM_V3","WARNING",line.declaredAmount(),1,
                "达到100万元需额外审批",List.of(),List.of(),List.of(),"AT_OR_ABOVE_1M","达到或超过100万元",true);
        var saved=new BudgetMaterialRisk.Result(UUID.randomUUID(),"owner",NOW.toString(),List.of(candidate),List.of(oldAnalysis.summary()),oldAnalysis);
        var current=BudgetMaterialRiskService.current(saved);
        assertThat(current.id()).isEqualTo(saved.id());assertThat(current.createdAt()).isEqualTo(saved.createdAt());
        assertThat(current.projects()).isEqualTo(saved.projects());assertThat(current.splitAnalysis().totalAmount()).isEqualByComparingTo("2000000.01");
        assertThat(current.splitAnalysis().bandLabel()).isNull();assertThat(current.splitAnalysis().riskLevel()).isEqualTo("INFO");
        assertThat(current.opinions()).noneMatch(s->s.contains("门槛")||s.contains("额外审批"));
        assertThat(saved.splitAnalysis().bandCode()).isEqualTo("AT_OR_ABOVE_1M");
    }

    @Test void legacyServiceIgnoresBuiltInSeedsWhileRetainingExplicitlyConfiguredRules() {
        for(boolean custom:List.of(false,true)) {
            var demands=mock(ProcurementDemandRepository.class);var rules=mock(BudgetSplitRiskRuleRepository.class);
            var assessments=mock(BudgetSplitRiskAssessmentRepository.class);var idempotency=mock(IdempotencyPort.class);
            var auth=mock(AuthContext.class);var audit=mock(OperationAuditPort.class);
            var actor=new CurrentUser("owner","owner","测试",Set.of("FINANCE_REVIEWER"));
            when(auth.currentUser()).thenReturn(Optional.of(actor));
            var entries=List.of(demand("30000.00"),demand("30000.01"));
            when(demands.findAllByIds(any())).thenReturn(entries);
            var configured=new ArrayList<BudgetSplitRiskRule>();
            RETIRED.forEach(code->configured.add(rule(code)));if(custom)configured.add(rule("EXPLICIT_POLICY"));
            when(rules.findActive()).thenReturn(configured);
            when(idempotency.claim(any())).thenReturn(new IdempotencyClaim(true,mock(IdempotencyRecord.class)));
            var service=new BudgetSplitRiskService(demands,rules,assessments,new BudgetSplitRiskEvaluator(),idempotency,audit,auth,Clock.fixed(NOW,ZoneOffset.UTC));
            var response=service.check(new BudgetSplitRiskCommand(entries.stream().map(ProcurementDemand::id).toList(),UUID.randomUUID().toString(),null,null,null));
            assertThat(response.totalAmount()).isEqualByComparingTo("60000.01");
            if(custom)assertThat(response.records()).singleElement().satisfies(r->assertThat(r.ruleCode()).isEqualTo("EXPLICIT_POLICY"));
            else {assertThat(response.records()).isEmpty();assertThat(response.riskLevel()).isEqualTo("INFO");assertThat(response.riskScore()).isZero();}
            verify(assessments).create(any());
        }
    }

    @Test void historicalLegacyMetricsExcludeRetiredFindingsAndKeepExplicitRules() {
        var ids=List.of(UUID.randomUUID(),UUID.randomUUID());
        var retired=record("F05_50K",95,"CRITICAL",ids);var explicit=record("EXPLICIT_POLICY",65,"WARNING",ids);
        for(boolean custom:List.of(false,true)) {
            var saved=new BudgetSplitRiskAssessment(UUID.randomUUID(),ids,new BigDecimal("60000.01"),"CRITICAL",95,
                    new BigDecimal("100"),new BigDecimal("100"),new BigDecimal("50"),custom?List.of(retired,explicit):List.of(retired),"owner",NOW);
            var view=BudgetSplitRiskView.from(saved);
            assertThat(saved.records()).contains(retired);assertThat(view.totalAmount()).isEqualTo(saved.totalAmount());
            assertThat(view.createdAt()).isEqualTo(saved.createdAt());assertThat(view.supplierConcentration()).isEqualTo(saved.supplierConcentration());
            assertThat(view.riskLevel()).isEqualTo(custom?"WARNING":"INFO");assertThat(view.riskScore()).isEqualTo(custom?65:0);
            assertThat(view.splitRatio()).isEqualByComparingTo(custom?"100":"0");assertThat(view.timeConcentration()).isEqualByComparingTo(custom?"100":"0");
            assertThat(view.records()).noneMatch(r->RETIRED.contains(r.ruleCode()));
        }
    }

    private BudgetUploadedFacts.Assessment check(String code) {return new BudgetUploadedFacts.Assessment(code,"WARNING",code,"原始说明","原始依据",List.of());}
    private BudgetSplitRiskRule rule(String code) {return new BudgetSplitRiskRule(code,"测试规则",30,new BigDecimal("50000"),new BigDecimal("50000"),2,60,85,1);}
    private ProcurementDemand demand(String amount) {return new ProcurementDemand(UUID.randomUUID(),"项目甲","a","s","设备",new BigDecimal(amount),"合成供应商","APPROVED",NOW);}
    private BudgetSplitRiskRecord record(String code,int score,String level,List<UUID> ids) {return new BudgetSplitRiskRecord(code,"设备","合成供应商",new BigDecimal("60000.01"),2,score,level,NOW,NOW,ids);}
}
