package com.fuquan.audit.contract.application.analysis;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

class ContractAnalysisReportTest {
    @Test void resultAndHistoryUseProjectNameWithoutContractTitleFallbackOrWrites() {
        var scope=ContractAnalysisEvidenceTest.scope("项目名称：档案数字化维护服务项目\n合同名称：档案维护服务合同书");
        var e=new ContractAnalysisEvidence().locate(scope,scope.text()).orElseThrow();
        var out=new Output("CONTRACT","GENERAL",true,List.of(new Fact("CONTRACT_NAME","档案维护服务合同书","MODEL",List.of(e)),
                new Fact("PROJECT_NAME","档案数字化维护服务项目","MODEL",List.of(e))),List.of(),List.of(),List.of(),0,"synthetic-test","contract-case-v1");
        var now=Instant.parse("2026-10-29T00:00:00Z");
        var step=new Step("semantic/1",scope.source().fileId(),"SEMANTIC",0,scope.text().length(),"c".repeat(64),StepState.COMPLETED,now,now,out,null);
        var c=new ContractAnalysisCase(UUID.randomUUID(),UUID.randomUUID(),"owner","d".repeat(64),State.COMPLETED,1,null,null,1,now,now.plusSeconds(1800),List.of(scope.source()),List.of(step),null);
        var sources=mock(ContractAnalysisSources.class);
        when(sources.load(any(),any())).thenThrow(new IllegalStateException("unavailable"));
        when(sources.projectName(c)).thenReturn("采购委托协议书");
        var service=mock(ContractAnalysisService.class);
        var reports=new ContractAnalysisReport(service,sources,new ContractAnalysisRelations());
        var report=reports.project(c);
        assertThat(report.headline()).isEqualTo("档案数字化维护服务项目");
        assertThat(report.projectName()).isEqualTo(report.headline());
        assertThat(report.projectId()).isEqualTo(c.projectId());
        assertThat(reports.summary(c,"采购委托协议书").projectName()).isEqualTo(report.projectName());
        assertThat(ContractAnalysisReport.projectName(c,"人工修改的项目名",List.of())).isEqualTo("人工修改的项目名");
        verifyNoInteractions(service);
    }

    @Test void contractTitleAndFilenameAloneProduceAGeneratedProjectDisplayName() {
        var scope=ContractAnalysisEvidenceTest.scope("采购委托协议书");
        var e=new ContractAnalysisEvidence().locate(scope,scope.text()).orElseThrow();
        var out=new Output("CONTRACT","GENERAL",true,List.of(new Fact("CONTRACT_NAME","采购委托协议书","MODEL",List.of(e))),List.of(),List.of(),List.of(),0,"synthetic-test","contract-case-v1");
        var now=Instant.parse("2026-10-29T00:00:00Z");
        var step=new Step("semantic/1",scope.source().fileId(),"SEMANTIC",0,scope.text().length(),"c".repeat(64),StepState.COMPLETED,now,now,out,null);
        var c=new ContractAnalysisCase(UUID.randomUUID(),UUID.randomUUID(),"owner","d".repeat(64),State.COMPLETED,1,null,null,1,now,now.plusSeconds(1800),List.of(scope.source()),List.of(step),null);
        assertThat(ContractAnalysisReport.projectName(c,"采购委托协议书",List.of(scope.text())))
                .isEqualTo("自动整理项目-"+c.projectId().toString().substring(0,8));
    }

    @Test void twoSidedComparisonIsVisibleWithoutBecomingRiskOrSingleFileClause(){
        var a=ContractAnalysisEvidenceTest.scope("原合同：验收后支付65%");
        var b=ContractAnalysisEvidenceTest.scope("补充协议替换原合同付款条款：验收后支付40%，原约定不再适用");
        var validator=new ContractAnalysisEvidence();
        var comparison=new Clause("PAYMENT","补充协议明确替换付款安排，旧约定不再适用",List.of(validator.locate(a,a.text()).orElseThrow(),validator.locate(b,b.text()).orElseThrow()));
        var out=new Output("OTHER","GENERAL",true,List.of(),List.of(comparison),List.of(),List.of(),0,"synthetic-test","contract-case-v1");
        var now=Instant.parse("2026-10-27T00:00:00Z");
        var step=new Step("semantic/pair",a.source().fileId(),"CROSS_FILE",0,0,"c".repeat(64),StepState.COMPLETED,now,now,out,null);
        var c=new ContractAnalysisCase(UUID.randomUUID(),UUID.randomUUID(),"synthetic-owner","d".repeat(64),State.COMPLETED,1,null,null,1,now,now.plusSeconds(1800),List.of(a.source(),b.source()),List.of(step),null);
        var sources=mock(ContractAnalysisSources.class);
        when(sources.load(any(),any())).thenThrow(new IllegalStateException("test-only source temporarily unavailable"));
        var report=new ContractAnalysisReport(mock(ContractAnalysisService.class),sources,new ContractAnalysisRelations()).project(c);
        assertThat(report.comparisons()).containsExactly(comparison);
        assertThat(report.findings()).isEmpty();
        assertThat(report.documents()).allSatisfy(d->assertThat(d.clauses()).isEmpty());
        assertThat(report.checks()).allSatisfy(check->assertThat(check.status()).isEqualTo("NOT_EVALUATED"));
    }
}
