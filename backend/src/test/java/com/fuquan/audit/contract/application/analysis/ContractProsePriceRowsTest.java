package com.fuquan.audit.contract.application.analysis;

import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ContractProsePriceRowsTest {
    private static final String ROWS="费用明细：巡检服务数量10次，单价10000元，金额100000元；专项维护数量1项，单价30000元，金\n额30000元。";
    private static final String COMPLETE="\n以上为合同全部价款明细，明细合计人民币130000元。";
    @Test void completeProseRowsUseDecimalArithmeticAndActualWrappedEvidence(){
        String text="合同含税总价为人民币120000元，大写壹拾贰万元整。\n"+ROWS+COMPLETE;
        var s=ContractAnalysisEvidenceTest.source(text);var table=ContractAnalysisTable.parse(s,text);
        assertThat(table.complete()).isTrue();assertThat(table.rows()).hasSize(2);
        assertThat(table.rows()).allSatisfy(row->assertThat(text.substring(row.evidence().start(),row.evidence().end())).isEqualTo(row.evidence().excerpt()));
        var rules=new ContractAnalysisRules(new com.fuquan.audit.contract.application.project.extraction.ProjectFactExtractor(Clock.systemUTC()),new ContractAnalysisEvidence());
        var total=ContractAnalysisRulesTest.fact(s,text,"TOTAL_AMOUNT","120000元");
        var out=rules.evaluate(s,List.of(ContractAnalysisRulesTest.output(List.of(total))),text);
        assertThat(out.checks()).filteredOn(c->c.code().equals("F02_LINE_ITEM_ARITHMETIC")).hasSize(2).allSatisfy(c->assertThat(c.status()).isEqualTo("PASS"));
        assertThat(out.findings()).anySatisfy(f->{assertThat(f.code()).isEqualTo("F02_LINE_SUM_MATCH");assertThat(f.explanation()).contains("130000","120000","10000");});
    }
    @Test void partialOrUnparsedRowsNeverProveCompleteSum(){
        for(String text:List.of(ROWS,ROWS+"\n以上仅为部分明细。",ROWS.replace("专项维护数量1项，单价30000元，金\n额30000元","专项维护另行结算")+COMPLETE,ROWS+COMPLETE+"\n"+ROWS+COMPLETE)){
            assertThat(ContractAnalysisTable.parse(ContractAnalysisEvidenceTest.source(text),text).complete()).isFalse();
        }
    }
    @Test void historicalRowsAndAmbiguousCurrenciesAreNotRecalled(){
        for(String text:List.of("历史业绩\n"+ROWS+COMPLETE, "费用明细：维护数量1项，单价100元，金额100元美元。"+COMPLETE)){
            assertThat(ContractAnalysisTable.parse(ContractAnalysisEvidenceTest.source(text),text).rows()).isEmpty();
        }
    }
    @Test void fractionalQuantityAndTenThousandUnitRetainTheirMeaning(){
        String text="费用明细：技术服务数量1.5项，单价2万元，金额3万元。";
        assertThat(ContractAnalysisTable.parse(ContractAnalysisEvidenceTest.source(text),text).rows()).singleElement().satisfies(r->{assertThat(r.quantity()).isEqualByComparingTo("1.5");assertThat(r.price()).isEqualByComparingTo("20000");assertThat(r.amount()).isEqualByComparingTo("30000");});
    }
}
