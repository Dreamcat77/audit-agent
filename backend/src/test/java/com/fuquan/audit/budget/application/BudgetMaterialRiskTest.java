package com.fuquan.audit.budget.application;
import static org.assertj.core.api.Assertions.*;
import com.fuquan.audit.budget.application.BudgetMaterialRisk.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
class BudgetMaterialRiskTest {
    Candidate candidate(String amount,String status){return new Candidate(new Selection(UUID.randomUUID(),0),"synthetic.xlsx","2026-10-15T00:00:00Z",new BudgetUploadedFacts.Line("项目甲",null,"电子",null,"2026","部门",amount==null?null:new BigDecimal(amount),new BigDecimal("100"),status,"材料核对意见",List.of()));}
    @Test void missingOrConflictingInformationNeverBecomesAllClear(){var result=BudgetMaterialRisk.opinions(List.of(candidate(null,"CONFLICT"),candidate("50","INSUFFICIENT")));assertThat(result).anyMatch(s->s.contains("信息缺失"));assertThat(result).noneMatch(s->s.contains("均未超过"));}
    @Test void duplicateVersionsAreNotAddedAndDifferentScalesAreEqual(){assertThat(BudgetMaterialRisk.opinions(List.of(candidate("10.0","WITHIN_APPROVAL"),candidate("10.00","WITHIN_APPROVAL")))).noneMatch(s->s.contains("不能直接相加"));assertThat(BudgetMaterialRisk.opinions(List.of(candidate("10","WITHIN_APPROVAL"),candidate("20","WITHIN_APPROVAL")))).anyMatch(s->s.contains("不能直接相加"));}
    @Test void excessAndUnknownTimeCannotProveSplitPurchase(){var result=BudgetMaterialRisk.opinions(List.of(candidate("100.01","OVER_APPROVAL")));assertThat(result).anyMatch(s->s.contains("超过材料中的"));assertThat(result).anyMatch(s->s.contains("不能仅凭"));assertThat(result).noneMatch(s->s.contains("均未超过"));}
}
