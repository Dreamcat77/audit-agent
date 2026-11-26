package com.fuquan.audit.budget.application;
import static com.fuquan.audit.budget.application.BudgetUploadedFacts.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
class BudgetPurposeConsolidationTest {
    final UUID ruleId=UUID.randomUUID();
    Evidence evidence(String quote){return new Evidence(UUID.randomUUID(),"请示.docx","semantic","","p1",quote);}
    Evidence policy(String quote){return new Evidence(ruleId,"复函.pdf","semantic","","p2",quote);}
    Line line(String name,String amount){return new Line("测试项目",null,name,null,null,null,new BigDecimal(amount),BigDecimal.ZERO,"UNAPPROVED_SUBJECT","",List.of());}
    Assessment item(String title,Evidence... refs){return new Assessment("SEMANTIC_PURPOSE_SCOPE","WARNING",title,title,"核对批准用途",List.of(refs));}
    @Test void sameExplicitlyProhibitedExpenseAbsorbsCopiedAndWeakSingleFileAdvice(){
        var rule=policy("批准用途限于设备维护，不得列支宣传接待餐费。");
        var a=item("拟支出宣传接待餐费超出批准用途",rule,evidence("宣传接待餐费6000元，拟从本项目支出。"));
        var b=item("宣传接待餐费超出批准用途",rule,evidence("宣传接待餐费 | 6000 | 0"));
        var weak=item("拟将宣传接待餐费并入项目",evidence("拟将宣传接待餐费6000元并入本项目列支。"));
        var result=BudgetPurposeConsolidation.merge(List.of(a,b,weak),List.of(line("宣传接待餐费","6000")));
        assertThat(result).hasSize(1);assertThat(result.get(0).sources()).containsAll(a.sources()).containsAll(b.sources()).containsAll(weak.sources());
    }
    @Test void differentExpenseAmountPolicyOrAmbiguousApprovalMustRemainSeparate(){
        var rule=policy("不得列支宣传接待餐费。");
        var first=item("餐费用途",rule,evidence("宣传接待餐费6000元"));
        for(var other:List.of(item("另一科目",rule,evidence("设备维护费6000元")),
                item("另一金额",rule,evidence("宣传接待餐费7000元")),
                item("不同政策",evidence("另一批复不得列支宣传接待餐费。"),evidence("宣传接待餐费6000元")))) {
            assertThat(BudgetPurposeConsolidation.merge(List.of(first,other),List.of(line("宣传接待餐费","6000"),line("宣传接待餐费","7000"),line("设备维护费","6000")))).hasSize(2);
        }
        var second=item("另一制度",evidence("本单位制度禁止列支宣传接待餐费。"),evidence("宣传接待餐费6000元"));
        var weak=item("范围需关注",evidence("宣传接待餐费6000元拟从项目支出"));
        assertThat(BudgetPurposeConsolidation.merge(List.of(first,second,weak),List.of(line("宣传接待餐费","6000")))).hasSize(3);
        assertThat(BudgetPurposeConsolidation.merge(List.of(first,weak),List.of())).hasSize(2);
    }
}
