package com.fuquan.audit.contract.application.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ContractAnalysisTableTest {
    ContractAnalysisTable.Table parse(String text){return ContractAnalysisTable.parse(ContractAnalysisEvidenceTest.source(text),text);}
    @Test void unrelatedFirstTableCannotBecomeWholeContractPrice(){
        var table=parse("差旅测算\n项目\t数量\t单价\t金额\n车费\t2\t10\t20\n合计\t\t\t20\n");
        assertThat(table.rows()).hasSize(1);assertThat(table.complete()).isFalse();
    }
    @Test void laterPriceTableIsPreferredButMultipleTablesDoNotProveCompleteness(){
        String rows="项目\t数量\t单价\t金额\n设备\t2\t10\t20\n合计\t\t\t20\n";
        var table=parse("差旅测算\n"+rows+"合同价款明细\n"+rows);
        assertThat(table.rows()).hasSize(1);assertThat(table.rows().get(0).evidence().start()).isGreaterThan(rows.length());
        assertThat(table.complete()).isFalse();
    }
    @Test void unitsAreAppliedOnceAndOffsetsRemainExact(){
        String text="合同价款明细\n名称\t数量\t单价（万元）\t金额（万元）\n设备\t2\t1元\t2元\n设备\t3\t1\t3\n合计\t\t\t3.0002\n";
        var table=parse(text);assertThat(table.complete()).isTrue();assertThat(table.rows()).hasSize(2);
        assertThat(table.rows().get(0).amount()).isEqualByComparingTo("2");
        assertThat(table.rows().get(1).amount()).isEqualByComparingTo("30000");
        for(var row:table.rows())assertThat(text.substring(row.evidence().start(),row.evidence().end())).isEqualTo(row.evidence().excerpt());
    }
    @ParameterizedTest @ValueSource(ints={12,48}) void repeatedNamesAreNotMerged(int count){
        String text="合同价款明细\n名称\t数量\t单价\t金额\n"+"设备\t2\t10\t20\n".repeat(count)+"合计\t\t\t"+(count*20)+"\n";
        var table=parse(text);assertThat(table.complete()).isTrue();assertThat(table.rows()).hasSize(count);
        assertThat(table.rows().stream().map(r->r.evidence().start()).distinct().count()).isEqualTo(count);
    }
    @Test void badRowOrMissingTotalNeverProvesFullTable(){
        assertThat(parse("合同价款明细\n名称\t数量\t单价\t金额\n设备\t不明\t10\t20\n合计\t\t\t20").complete()).isFalse();
        assertThat(parse("合同价款明细\n名称\t数量\t单价\t金额\n设备\t2\t10\t20").complete()).isFalse();
    }
}
