package com.fuquan.audit.budget.application;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.fuquan.audit.budget.application.BudgetUploadedFacts.*;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.*;

class BudgetBatchChecksTest {
    @Test void wordHeadingNameAndCodeInDifferentParagraphsStillScopePolicyAndPurposes() {
        var declaration=file("申报.docx",row("n","projectName","设备更新"),row("c","projectCode","P001"),
                row("d","subjectName","设备","declaredAmount","120000元","spendingPurpose","旅游"));
        var approval=file("批复.docx",row("an","projectName","设备更新"),row("ac","projectCode","P001"),
                row("ad","subjectName","设备","approvedPurpose","购置电脑"));
        var policy=file("制度.docx",row("p","policyClause","十万元以上应先经财务审核，再经分管领导审批。",
                "requiredApprovalStep","财务审核","requiredApprovalStep","分管领导审批","approvalThreshold","十万元","thresholdCondition","以上"));
        var finance=file("审核.docx",row("fn","projectName","设备更新"),row("fc","projectCode","P001"),row("f","actualApprovalStep","财务审核"),row("fd","approvalDate","2026-10-29"));
        var leader=file("审批.docx",row("ln","projectName","设备更新"),row("lc","projectCode","P001"),row("l","actualApprovalStep","分管领导审批"),row("ld","approvalDate","2026-10-28"));
        var results=new BudgetUploadedMaterialAnalyzer().analyze(List.of(declaration,approval,policy,finance,leader));
        assertThat(results.values().stream().flatMap(r->r.assessments().stream()).map(Assessment::code)).contains("APPROVAL_PROCEDURE","APPROVAL_SEQUENCE","SPENDING_PURPOSE_DIFFERENCE");
        var conflicting=file("多项目.docx",row("a","projectName","设备更新"),row("b","projectName","其他项目"),row("c","projectCode","P002"),row("r","actualApprovalStep","分管领导审批"));
        assertThat(new BudgetUploadedMaterialAnalyzer().analyze(List.of(declaration,policy,conflicting)).get(policy.taskId()).assessments())
                .filteredOn(a->a.code().equals("APPROVAL_PROCEDURE")).allSatisfy(a->assertThat(a.level()).isEqualTo("WARNING"));
    }
    @Test void multipleStepsInOneGroundedClauseStillCheckDatesAndDoNotMixAnotherClause() {
        var declaration=file("申报.docx",row("d","projectName","设备更新","subjectName","设备","declaredAmount","120000元"));
        var policy=file("制度.docx",row("p","policyClause","第八条：十万元以上，应先经财务审核，再经分管领导审批。",
                "requiredApprovalStep","财务审核","requiredApprovalStep","分管领导审批","approvalThreshold","十万元","thresholdCondition","以上"));
        var finance=file("财务审核表.docx",row("f","actualApprovalStep","财务审核","approvalDate","2026年10月29日"));
        var leader=file("领导审批表.docx",row("l","actualApprovalStep","分管领导审批","approvalDate","2026年10月28日"));
        var results=new BudgetUploadedMaterialAnalyzer().analyze(List.of(declaration,policy,finance,leader));
        assertThat(results.get(policy.taskId()).assessments()).filteredOn(a->a.code().equals("APPROVAL_PROCEDURE")).hasSize(2);
        assertThat(results.get(policy.taskId()).assessments()).anySatisfy(a->{assertThat(a.code()).isEqualTo("APPROVAL_SEQUENCE");assertThat(a.explanation()).contains("晚于");});
        var ambiguous=file("歧义制度.docx",row("a","policyClause","先经财务审核，再经分管领导审批。",
                "policyClause","另一个项目适用其他条款。","requiredApprovalStep","财务审核","requiredApprovalStep","分管领导审批"));
        assertThat(new BudgetUploadedMaterialAnalyzer().analyze(List.of(declaration,ambiguous)).get(ambiguous.taskId()).assessments()).isEmpty();
    }
    @Test void labelledPurposesAndApprovalDatesStayWithTheirSourceRecords() throws Exception {
        var reader=new BudgetUploadedMaterialReader(null,(a,b,c,d)->new Result(List.of(),"test","test"),null);
        var document=new com.fuquan.audit.common.material.application.port.DocumentTextPort.Result(List.of(
                new Segment("p1","项目名称：设备更新\n科目名称：设备；申报金额：120000元；支出用途：组织旅游。"),
                new Segment("p2","已完成审批环节：财务审核\n审批日期：2026年10月29日")),"WORD","sha");
        var snapshot=reader.extract(document,"id");
        assertThat(snapshot.rows()).anySatisfy(r->{assertThat(r.facts()).extracting(Fact::field).contains("subjectName","declaredAmount","spendingPurpose");});
        assertThat(snapshot.rows().stream().flatMap(r->r.facts().stream())).anySatisfy(f->{assertThat(f.field()).isEqualTo("approvalDate");assertThat(f.value()).isEqualTo("2026年10月29日");});
    }
    private Row row(String id,String... pairs) {
        var facts=new ArrayList<Fact>();for(int i=0;i<pairs.length;i+=2)facts.add(new Fact(pairs[i],pairs[i+1],id,pairs[i]+"："+pairs[i+1]));
        return new Row(id,facts);
    }
    private Input file(String name,Row... rows) {return new Input(UUID.randomUUID(),name,"PARSED",new Snapshot(List.of(rows)));}
    @Test void paragraphSeparatedProcedureFactsRetainClauseAndDateAndDetectReversedOrder() {
        var declaration=file("申报.docx",row("d","projectName","设备更新","subjectName","设备","declaredAmount","120000元"));
        var policy=file("制度.docx",row("p1","policyClause","第八条先财务审核再分管领导审批"),
                row("p2","requiredApprovalStep","分管领导审批"),row("p3","requiredPreviousStep","财务审核"));
        var finance=file("财务审核表.docx",row("f1","actualApprovalStep","财务审核"),row("f2","approvalDate","2026-10-29"));
        var leader=file("领导审批表.docx",row("l1","actualApprovalStep","分管领导审批"),row("l2","approvalDate","2026-10-28"));
        var result=new BudgetUploadedMaterialAnalyzer().analyze(List.of(declaration,policy,finance,leader));
        assertThat(result.get(policy.taskId()).assessments()).anySatisfy(f->{
            assertThat(f.code()).isEqualTo("APPROVAL_SEQUENCE");assertThat(f.explanation()).contains("晚于");
            assertThat(f.sources()).extracting(Evidence::taskId).contains(finance.taskId(),leader.taskId());
        });
    }
    @Test void realReaderKeepsNewPolicyFieldsAndNeverSilentlyDropsThem() throws Exception {
        String source="预算管理办法\n第八条十万元以上须经分管领导审批。";
        var facts=List.of(new Fact("policyClause","第八条十万元以上须经分管领导审批。","p1","第八条十万元以上须经分管领导审批。"),
                new Fact("requiredApprovalStep","分管领导审批","p1","第八条十万元以上须经分管领导审批。"),
                new Fact("approvalThreshold","十万元","p1","第八条十万元以上须经分管领导审批。"),
                new Fact("thresholdCondition","以上","p1","第八条十万元以上须经分管领导审批。"));
        var reader=new BudgetUploadedMaterialReader(null,(a,b,c,d)->new Result(List.of(new Row("policy",facts)),"test","test"),null);
        var snapshot=reader.extract(new com.fuquan.audit.common.material.application.port.DocumentTextPort.Result(List.of(new Segment("p1",source)),"WORD","sha"),"test");
        assertThat(snapshot.issues()).isEmpty();
        assertThat(snapshot.rows().stream().flatMap(r->r.facts().stream()).map(Fact::field)).contains("requiredApprovalStep","approvalThreshold","thresholdCondition","policyClause");
    }
    @Test void automaticallyFindsSameProjectSmallApplicationsAndSeparatesDifferentProjects() {
        var a=file("申报1.docx",row("1","projectName","办公终端更新","subjectName","设备","declaredAmount","600000元"));
        var b=file("申报2.pdf",row("2","projectName","办公终端更新","subjectName","安装","declaredAmount","400000.01元"));
        var c=file("别的工程.pdf",row("3","projectName","屋面修缮","subjectName","工程","declaredAmount","600000元"));
        var results=new BudgetUploadedMaterialAnalyzer().analyze(List.of(a,b,c));
        assertThat(results.get(a.taskId()).assessments()).singleElement().satisfies(f->{assertThat(f.code()).isEqualTo("AUTO_SPLIT_APPLICATION");assertThat(f.explanation()).contains("1000000.01", "额外审批");assertThat(f.sources()).noneMatch(s->s.taskId().equals(c.taskId()));});
        assertThat(results.get(c.taskId()).assessments()).isEmpty();
    }
    @Test void automaticSplitDoesNotWarnBelowOneMillionOrAtOldReferenceBands() {
        for(String[] amounts:List.of(new String[]{"6000","7000"},new String[]{"60000","70000"},new String[]{"600000","399999.99"})) {
            var a=file("申报1.docx",row("1","projectName","办公终端更新","subjectName","设备","declaredAmount",amounts[0]+"元"));
            var b=file("申报2.pdf",row("2","projectName","办公终端更新","subjectName","安装","declaredAmount",amounts[1]+"元"));
            assertThat(new BudgetUploadedMaterialAnalyzer().analyze(List.of(a,b)).values().stream().flatMap(v->v.assessments().stream()))
                    .noneMatch(f->f.code().equals("AUTO_SPLIT_APPLICATION"));
        }
    }
    @Test void automaticSplitWarnsAtExactlyOneMillion() {
        var a=file("申报1.docx",row("1","projectName","办公终端更新","subjectName","设备","declaredAmount","600000元"));
        var b=file("申报2.pdf",row("2","projectName","办公终端更新","subjectName","安装","declaredAmount","400000.00元"));
        assertThat(new BudgetUploadedMaterialAnalyzer().analyze(List.of(a,b)).get(a.taskId()).assessments())
                .anySatisfy(f->{assertThat(f.code()).isEqualTo("AUTO_SPLIT_APPLICATION");assertThat(f.explanation()).contains("额外审批");});
    }
    @Test void approvalEvidenceIsComparedWithUploadedPolicyAndNotAnInventedDemoRule() {
        var declaration=file("申报.docx",row("d","projectName","终端更新","subjectName","设备","declaredAmount","120000元"));
        var policy=file("预算管理办法.docx",row("p","requiredApprovalStep","分管领导审批","policyClause","第八条：十万元以上的预算申报须经分管领导审批。","approvalThreshold","十万元","thresholdCondition","以上"));
        var results=new BudgetUploadedMaterialAnalyzer().analyze(List.of(declaration,policy));
        assertThat(results.get(policy.taskId()).assessments()).singleElement().satisfies(f->{assertThat(f.level()).isEqualTo("WARNING");assertThat(f.basis()).contains("第八条");});
        var actual=file("审批表.docx",row("a","actualApprovalStep","分管领导审批"));
        assertThat(new BudgetUploadedMaterialAnalyzer().analyze(List.of(declaration,policy,actual)).get(policy.taskId()).assessments()).singleElement().satisfies(f->assertThat(f.level()).isEqualTo("INFO"));
        assertThat(new BudgetUploadedMaterialAnalyzer().analyze(List.of(declaration)).get(declaration.taskId()).assessments()).isEmpty();
    }
    @Test void anotherProjectsApprovalDoesNotDischargeThisProjectsRequirement() {
        var policy=file("制度.docx",row("p","requiredApprovalStep","分管领导审批","policyClause","项目预算应经分管领导审批"));
        var a=file("A申报.pdf",row("a","projectName","办公终端","subjectName","设备","declaredAmount","120000元"));
        var b=file("B审批.pdf",row("b","projectName","屋面工程","actualApprovalStep","分管领导审批"));
        var result=new BudgetUploadedMaterialAnalyzer().analyze(List.of(policy,a,b));
        assertThat(result.get(a.taskId()).assessments()).anySatisfy(f->{assertThat(f.code()).isEqualTo("APPROVAL_PROCEDURE");assertThat(f.level()).isEqualTo("WARNING");});
        assertThat(result.get(b.taskId()).assessments()).noneMatch(f->f.explanation().startsWith("办公终端"));
    }
    @Test void multiSubjectBudgetIsSummedForUploadedApprovalThresholdWithoutMixingVersions() {
        var a=file("申报.docx",row("a1","projectName","更新工程","subjectName","设备","declaredAmount","60000元"),
                row("a2","projectName","更新工程","subjectName","安装","declaredAmount","60000元"));
        var policy=file("制度.docx",row("p","requiredApprovalStep","领导审批","policyClause","超过十万元须领导审批","approvalThreshold","100000元","thresholdCondition","超过"));
        var result=new BudgetUploadedMaterialAnalyzer().analyze(List.of(a,policy));
        assertThat(result.get(policy.taskId()).assessments()).anyMatch(f->f.code().equals("APPROVAL_PROCEDURE"));
        var version=file("旧版申报.docx",row("v","projectName","更新工程","subjectName","设备","declaredAmount","50000元"));
        assertThat(new BudgetUploadedMaterialAnalyzer().analyze(List.of(a,version,policy)).get(policy.taskId()).assessments()).noneMatch(f->f.code().equals("APPROVAL_PROCEDURE"));
        var low=file("低额.docx",row("l","projectName","更新工程","subjectName","设备","declaredAmount","5000元"));
        var lowPolicy=file("小额制度.docx",row("p2","requiredApprovalStep","财务审核","policyClause","不超过一万元须财务审核","approvalThreshold","10000元","thresholdCondition","不超过"));
        assertThat(new BudgetUploadedMaterialAnalyzer().analyze(List.of(low,lowPolicy)).get(lowPolicy.taskId()).assessments()).anyMatch(f->f.code().equals("APPROVAL_PROCEDURE"));
    }
    @Test void purposeComparisonNeedsTheSameProjectAndSubjectAndRetainsBothSourceMaterials() {
        var a=file("申报.pdf",row("a","projectName","更新工程","subjectName","设备","declaredAmount","5000元","spendingPurpose","组织旅游"));
        var b=file("批复.pdf",row("b","projectName","更新工程","subjectName","设备","approvalAmount","5000元","approvedPurpose","采购办公电脑"));
        var other=file("别的批复.pdf",row("c","projectName","其他工程","subjectName","设备","approvedPurpose","采购家具"));
        var result=new BudgetUploadedMaterialAnalyzer().analyze(List.of(a,b,other));
        assertThat(result.get(a.taskId()).assessments()).filteredOn(f->f.code().equals("SPENDING_PURPOSE_DIFFERENCE")).singleElement().satisfies(f->{
            assertThat(f.explanation()).contains("组织旅游","采购办公电脑").doesNotContain("采购家具");
            assertThat(f.sources()).extracting(Evidence::taskId).contains(a.taskId(),b.taskId()).doesNotContain(other.taskId());
        });
    }
}
