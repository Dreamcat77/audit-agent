package com.fuquan.audit.procurement.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fuquan.audit.procurement.domain.ProcurementTenderFinding;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProcurementConclusionTest {
    @Test
    void conclusionNamesOnlyObservedRisksAndDoesNotInventBlacklistOrBudgetProblems() {
        var agent = mock(ProcurementAgentReviewService.ReviewResult.class);
        when(agent.riskLevel()).thenReturn("INFO");
        var tender = mock(ProcurementTenderFinding.class);
        when(tender.riskLevel()).thenReturn("CRITICAL");
        when(tender.riskCategory()).thenReturn("指定品牌");
        assertThat(ProcurementReviewReportService.buildConclusion(agent, null, List.of(), List.of(tender), null, null))
                .contains("招标文件合规审查（指定品牌）", "供工作参考")
                .doesNotContain("黑名单", "预算", "询比价", "暂停");
        assertThat(ProcurementReviewReportService.buildConclusion(agent, null, List.of(), List.of(), null, null))
                .contains("未提供材料或数据不足");
    }
}
