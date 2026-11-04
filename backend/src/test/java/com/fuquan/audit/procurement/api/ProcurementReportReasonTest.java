package com.fuquan.audit.procurement.api;

import static org.assertj.core.api.Assertions.assertThat;
import com.fuquan.audit.procurement.domain.ProcurementTenderFinding;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProcurementReportReasonTest {
    @Test void reportHttpProjectionRetainsAiReasonAndOriginalEvidence() throws Exception {
        var finding = new ProcurementTenderFinding(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "synthetic-consistency", "F10 条款一致性", "条款一致性", "WARNING",
                "交付期限为90天", "docx/body/paragraph:5", null, "请统一90天和30天的交付要求",
                "AI", "前文90天、后文30天，交付期限不一致", Instant.now());
        var response = ProcurementReviewReportController.TenderFindingInfo.from(finding);
        var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(response));
        assertThat(json.get("riskReason").asText()).contains("90天", "30天", "不一致");
        assertThat(json.get("remediationSuggestion").asText()).contains("统一");
        assertThat(json.get("matchedText").asText()).isEqualTo(finding.matchedText());
        assertThat(json.get("sourceLocator").asText()).isEqualTo(finding.sourceLocator());
    }
}
