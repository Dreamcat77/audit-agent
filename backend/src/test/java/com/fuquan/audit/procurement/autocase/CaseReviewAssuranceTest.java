package com.fuquan.audit.procurement.autocase;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static com.fuquan.audit.procurement.autocase.AuditCase.*;
import static org.assertj.core.api.Assertions.*;

class CaseReviewAssuranceTest {
    private static Report report(BigDecimal budget,BigDecimal ceiling,String validation) {
        var offer=new Offer("仿真供应商",new BigDecimal("2200000"),UUID.randomUUID(),List.of(),validation);
        return new Report("PARTIAL",budget,null,List.of(offer),null,List.of(),Map.of(),Map.of(),"测试采购项目",ceiling);
    }
    @Test void printedPriceCannotEstablishBudgetOrRecalculatedLineSum() {
        var r=report(null,null,"UNKNOWN");
        for(String text:List.of("系统核算结果显示明细加总与总价相符，未显示超预算或限价异常。",
                "系统核算结果显示金额内部逻辑一致，未发现报价与明细加总不符的情况。",
                "未发现超预算。","没有超过预算。","未超过最高限价。"))
            assertThat(CaseReviewAssurance.supported(text,r)).as(text).isFalse();
        for(String text:List.of("开标一览表与分项目明细表印列合计均为220万元。",
                "尚未核对预算，不能据此证明未超预算。","表列总价一致不代表明细加总与总价一致。",
                "付款50%，验收后40%，质保后10%。","明细加总与总价不一致，需要核对。"))
            assertThat(CaseReviewAssurance.supported(text,r)).as(text).isTrue();
    }
    @Test void provenChecksAllowAssuranceButConflictingOrMixedSuppliersDoNot() {
        var r=report(new BigDecimal("2300000"),new BigDecimal("2250000"),"VERIFIED");
        assertThat(CaseReviewAssurance.supported("明细加总与总价相符，未显示超预算或限价异常。",r)).isTrue();
        assertThat(CaseReviewAssurance.supported("未显示超预算或限价异常。",report(new BigDecimal("2100000"),new BigDecimal("2100000"),"CONFLICT"))).isFalse();
        assertThat(CaseReviewAssurance.supported("未发现超预算。",new Report("PARTIAL",new BigDecimal("2300000"),null,List.of(),null,List.of(),Map.of()))).isFalse();
    }
    @Test void historyProjectionKeepsIndependentFindingsFactsAndAuditIdentityWithoutMutation() {
        var r=report(null,null,"UNKNOWN");
        var issue=new ReviewFinding("QUOTE_SCOPE","WARNING","年度报价口径需核对","年度与整体范围不同","核对包含期限",List.of(),"MODEL");
        var before=new ModuleReview("COMPLETED","digest","v6","model",Instant.EPOCH,Instant.EPOCH,List.of(),List.of(),List.of(
                new ReviewCheck("BUDGET_VS_AWARD","SOURCE_GROUNDED_OBSERVATION","未显示超预算或限价异常。",List.of()),
                new ReviewCheck("DATE_AND_PLAN","SOURCE_GROUNDED_OBSERVATION","180天建设、三年运维。",List.of())),List.of(issue),0,null);
        r=r.review("TENDER_COMPLIANCE",before);var projected=CaseReviewAssurance.project(r);
        assertThat(projected.reviews().get("TENDER_COMPLIANCE").checks()).hasSize(1);
        assertThat(projected.reviews().get("TENDER_COMPLIANCE").findings()).containsExactly(issue);
        assertThat(projected.modules().get("TENDER_COMPLIANCE")).isEqualTo("PARTIAL");
        assertThat(projected.offers()).isEqualTo(r.offers());assertThat(r.reviews().get("TENDER_COMPLIANCE")).isEqualTo(before);
        assertThat(CaseReviewAssurance.project(projected)).isEqualTo(projected);
        assertThat(CaseReviewAssurance.project(null)).isNull();
    }
}
