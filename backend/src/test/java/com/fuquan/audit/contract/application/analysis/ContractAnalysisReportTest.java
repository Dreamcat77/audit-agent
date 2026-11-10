package com.fuquan.audit.contract.application.analysis;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

class ContractAnalysisReportTest {
    @Test void historicalRiskCountAlsoChecksTheFullDateWithoutWritingOrCallingModels(){
        String text="签署日期：2026年10月22日\n合同有效期至执行完毕止。";
        var scope=ContractAnalysisEvidenceTest.scope(text);var source=scope.source();
        var evidence=new ContractAnalysisEvidence().locate(scope,"签署日期：2026年10月22").orElseThrow();
        var finding=new Finding("MATERIAL_SCOPE_NOTE","WARNING","签署日期不完整","缺少具体日数","补全日期",List.of(evidence),"AI_REVIEW_SUGGESTION");
        var out=new Output("CONTRACT","GENERAL",true,List.of(),List.of(),List.of(),List.of(finding),0,"test","contract-case-v1");
        var now=Instant.now();var step=new Step("semantic/1",source.fileId(),"FRAGMENT",0,18,"c".repeat(64),StepState.COMPLETED,now,now,out,null);
        var c=new ContractAnalysisCase(UUID.randomUUID(),UUID.randomUUID(),"owner","d".repeat(64),State.COMPLETED,1,null,null,1,now,now.plusSeconds(1800),List.of(source),List.of(step),null);
        var parsed=mock(com.fuquan.audit.contract.domain.project.ProjectFileParseResult.class);
        when(parsed.canonicalText()).thenReturn(text);
        var sources=mock(ContractAnalysisSources.class);when(sources.load(c.projectId(),source)).thenReturn(parsed);
        var service=mock(ContractAnalysisService.class);var reports=new ContractAnalysisReport(service,sources,new ContractAnalysisRelations());
        assertThat(reports.summary(c).risks()).isZero();
        assertThat(c.steps().get(0).output().findings()).containsExactly(finding);
        verify(sources,times(1)).load(c.projectId(),source);verifyNoInteractions(service);
        when(sources.load(c.projectId(),source)).thenThrow(new IllegalStateException("unavailable"));
        assertThat(reports.summary(c).risks()).isEqualTo(1);
    }
    @Test void sameBlankIssueIsShownOnceButDifferentEventOrFileRemainsVisible(){
        var s=ContractAnalysisEvidenceTest.scope("逾期付款按货款金额的    向对方支付违约金。\n留置期限    天。");
        var g=new ContractAnalysisEvidence();var rate=g.locate(s,"逾期付款按货款金额的    向对方支付违约金。").orElseThrow();
        var duration=g.locate(s,"留置期限    天。").orElseThrow();
        var rule=new Finding("BREACH_TERMS_UNFILLED","WARNING","违约金比例未填写","比例留空","补齐比例",List.of(rate),"DETERMINISTIC_FORM");
        var ai=new Finding("BREACH_AMBIGUITY","WARNING",rule.title(),"支付违约金的比例留空","核对比例",List.of(rate),"AI_REVIEW_SUGGESTION");
        var extra=new Finding("BREACH_AMBIGUITY","WARNING",rule.title(),"比例与留置期限均留空","核对",List.of(rate,duration),"AI_REVIEW_SUGGESTION");
        var other=ContractAnalysisEvidenceTest.scope(s.text());var otherEvidence=g.locate(other,rate.excerpt()).orElseThrow();
        var otherFinding=new Finding(ai.code(),ai.level(),ai.title(),ai.explanation(),ai.suggestion(),List.of(otherEvidence),ai.origin());
        var out=new Output("CONTRACT","GENERAL",true,List.of(),List.of(),List.of(),List.of(ai,rule,extra,otherFinding),0,null,"test-v1");
        var now=Instant.now();var step=new Step("rule",s.source().fileId(),"RULES",0,s.text().length(),"d".repeat(64),StepState.COMPLETED,now,now,out,null);
        assertThat(ContractAnalysisReport.uniqueFindings(List.of(step))).containsExactly(rule,extra,otherFinding);
    }
    private static ContractAnalysisCase titledCase(String... titles){
        var now=Instant.parse("2026-11-04T00:00:00Z");var steps=new ArrayList<Step>();var sources=new ArrayList<Source>();
        for(String title:titles){var scope=ContractAnalysisEvidenceTest.scope(title);sources.add(scope.source());
            var evidence=new ContractAnalysisEvidence().locate(scope,title).orElseThrow();
            var output=new Output("CONTRACT","SERVICE",true,List.of(new Fact("CONTRACT_NAME",title,"RULE",List.of(evidence))),List.of(),List.of(),List.of(),0,null,"rule");
            steps.add(new Step("recall/"+scope.source().fileId(),scope.source().fileId(),"RECALL",0,title.length(),"c".repeat(64),StepState.COMPLETED,now,now,output,null));}
        return new ContractAnalysisCase(UUID.randomUUID(),UUID.randomUUID(),"owner","d".repeat(64),State.PARTIAL,1,null,null,1,now,now.plusSeconds(1800),sources,steps,null);
    }
    @Test void descriptiveVerifiedContractSubjectProvidesDisplayNameInReportAndHistoryOnly(){
        var c=titledCase("智能化内控模块维护及优化服务合同");
        assertThat(ContractAnalysisReport.projectName(c,"自动整理项目-原名",List.of())).isEqualTo("智能化内控模块维护及优化服务");
        assertThat(new ContractAnalysisReport(null,null,null).summary(c).projectName()).isEqualTo("智能化内控模块维护及优化服务");
        assertThat(ContractAnalysisReport.projectName(c,"用户指定项目",List.of())).isEqualTo("用户指定项目");
        assertThat(ContractAnalysisReport.projectName(c,null,List.of("项目名称：信息化改造项目"))).isEqualTo("信息化改造项目");
    }
    @Test void ambiguousContractSubjectsAndGenericDocumentNamesDoNotInventProjectIdentity(){
        for(var c:List.of(titledCase("合同书"),titledCase("采购委托协议书"),titledCase("甲合同","乙合同"),
                titledCase("东院信息化维护服务合同","西院档案管理服务合同"))){
            assertThat(ContractAnalysisReport.projectName(c,null,List.of())).startsWith("自动整理项目-");
        }
    }
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
