package com.fuquan.audit.budget.application;
import static com.fuquan.audit.budget.application.BudgetUploadedFacts.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class BudgetAssessmentMergeTest {
    @Test void samePrerequisiteAndSameEntryDateAcrossTwoMaterialsMergeWithoutSwallowingOtherDates(){
        UUID task=UUID.randomUUID(),scan=UUID.randomUUID();
        var rule=new Evidence(task,"复函.pdf","semantic","","p1","须在人员进场实施前完成现场安全方案审查，不得先实施后补办。");
        var request=new Evidence(task,"请示.docx","semantic","","p2","现场安全方案审查尚未办理，已于2027年1月8日安排人员进场实施，拟后续补办。");
        var record=new Evidence(scan,"实施.pdf","semantic","","p3","现场安全方案审查尚未办理。\n实施记录：2027年1月8日已进场，已安排设备故障处理。\n后续拟补办现场安全方案审查手续。");
        var a=new Assessment("SEMANTIC_APPROVAL_CONDITION","WARNING","未审查先实施","前置条件冲突","核对",List.of(rule,request));
        var b=new Assessment(a.code(),a.level(),"审批倒置","尚未审查已进场","核对",List.of(record));
        var result=BudgetBatchReviewService.mergeAssessments(List.of(a,b));
        assertThat(result).hasSize(1);assertThat(result.get(0).sources()).containsExactlyInAnyOrder(rule,request,record);
        for(String changed:List.of(record.excerpt().replace("1月8日","1月9日"),record.excerpt().replace("现场安全方案审查","财务审核"),
                record.excerpt().replace("2027年1月8日", "当日"))) {
            var other=new Evidence(scan,"实施.pdf","semantic","","p3",changed);
            assertThat(BudgetBatchReviewService.mergeAssessments(List.of(a,new Assessment(b.code(),b.level(),b.title(),b.explanation(),b.basis(),List.of(other))))).hasSize(2);
        }
    }
    @Test void prerequisiteUsingMustMergesSameRecordButDifferentRuleSurvives(){
        UUID task=UUID.randomUUID();
        var rule=new Evidence(task,"复函.pdf","semantic","","p1","须在人员进场实施前完成现场安全方案审查，不得先实施后补办。");
        var pending=new Evidence(task,"记录.docx","semantic","","p2","现场安全方案审查尚未办理。");
        var a=new Assessment("SEMANTIC_APPROVAL_CONDITION","WARNING","前置审查未办","未完成安全审查","核对",List.of(rule,pending));
        var b=new Assessment(a.code(),a.level(),"先实施后审批","人员已进场","核对",List.of(rule,pending));
        var other=new Evidence(task,"复函.pdf","semantic","","p3","须在合同签订前完成财务审核。");
        var c=new Assessment(a.code(),a.level(),"其他前置审查","未完成","核对",List.of(other,pending));
        var result=BudgetBatchReviewService.mergeAssessments(List.of(a,b,c));
        assertThat(result).hasSize(2);
        assertThat(result.get(0).sources()).containsExactlyInAnyOrder(rule,pending);
    }
    @Test void sameUnfinishedApprovalWithNestedEvidenceMergesButAnotherStepSurvives(){
        UUID task=UUID.randomUUID();
        var rule=new Evidence(task,"审批.docx","semantic","","rule","采购前应专项审批");
        var pending=new Evidence(task,"审批.docx","semantic","","step1","专项审批 尚未办理");
        var started=new Evidence(task,"审批.docx","semantic","","purchase","采购实施 已启动");
        var another=new Evidence(task,"审批.docx","semantic","","step2","财务审批 尚未办理");
        var a=new Assessment("SEMANTIC_APPROVAL_CONDITION","WARNING","未审批","尚未办理","建议补办",List.of(rule,pending));
        var b=new Assessment("SEMANTIC_APPROVAL_CONDITION","WARNING","先实施后审批","已启动采购","建议核对",List.of(rule,pending,started));
        var c=new Assessment("SEMANTIC_APPROVAL_CONDITION","WARNING","另一审批","未财务审批","建议补办",List.of(rule,another));
        var result=BudgetBatchReviewService.mergeAssessments(List.of(a,b,c));
        assertThat(result).hasSize(2);assertThat(result.get(0).title()).isEqualTo(b.title());
        assertThat(result.get(0).sources()).containsExactlyInAnyOrder(rule,pending,started);
    }
    @Test void nestedQuotesMergeOnlySameApprovalWhileDifferentRuleSurvives(){
        UUID task=UUID.randomUUID();
        var rule=new Evidence(task,"审批.docx","semantic","","p1/record:0:60","采购前应专项审批");
        var required=new Evidence(task,"审批.docx","requiredApprovalStep","专项审批","p1/record:0:59","采购前应专项审批");
        var pending=new Evidence(task,"审批.docx","semantic","","p2/record:0:30","专项审批 尚未办理");
        var whole=new Evidence(task,"审批.docx","semantic","","p3/record:0:40","预算批复 已办理 核定180万元");
        var shortQuote=new Evidence(task,"审批.docx","semantic","","p3/record:0:39","核定180万元");
        var started=new Evidence(task,"审批.docx","semantic","","p4","采购已启动");
        var anotherBudget=new Evidence(task,"审批.docx","semantic","","anotherBudget","项目批复180万元");
        var a=new Assessment("SEMANTIC_APPROVAL_CONDITION","WARNING","未审批","未办","补办",List.of(rule,pending,anotherBudget));
        var b=new Assessment("SEMANTIC_APPROVAL_CONDITION","WARNING","先实施","已启动","补办",List.of(rule,pending,whole,started));
        var fallback=new Assessment("APPROVAL_PROCEDURE","WARNING","未看到完成记录","未见","按制度",List.of(required));
        var another=new Assessment("APPROVAL_PROCEDURE","WARNING","其他审批","未见","按制度",List.of(
                new Evidence(task,"审批.docx","requiredApprovalStep","财务审批","p5","应财务审批")));
        var result=BudgetBatchReviewService.mergeAssessments(List.of(a,b,fallback,another));
        assertThat(result).hasSize(2);assertThat(result).extracting(Assessment::title).containsExactly("先实施","其他审批");
        assertThat(result.get(0).sources()).contains(whole,anotherBudget,started);
    }
    @Test void repeatedIssueRetainsEverySourceButSameSourceCanHaveDifferentIssues(){
        var a=new Evidence(UUID.randomUUID(),"申请.pdf","declaredAmount","100","p1","申请100");
        var b=new Evidence(UUID.randomUUID(),"批复.pdf","approvalAmount","90","p1","批复90");
        var first=new Assessment("PURPOSE","WARNING","用途不同","申请用途与批复不同","按批复执行",List.of(a));
        var second=new Assessment("PURPOSE","WARNING","用途不同","申请用途与批复不同","按批复执行",List.of(b));
        var other=new Assessment("PURPOSE","WARNING","另一项用途","另一项用途不同","按批复执行",List.of(a));
        var result=BudgetBatchReviewService.mergeAssessments(List.of(first,second,first,other));
        assertThat(result).hasSize(2);
        assertThat(result.get(0).sources()).containsExactly(a,b);
        assertThat(result.get(1)).isEqualTo(other);
    }
}
