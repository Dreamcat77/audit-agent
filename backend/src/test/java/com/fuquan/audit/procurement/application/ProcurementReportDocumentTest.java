package com.fuquan.audit.procurement.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fuquan.audit.procurement.domain.*;
import java.io.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;

class ProcurementReportDocumentTest {
    public static ProcurementReviewReportService.ReportData fixture() {
        var data=mock(ProcurementReviewReportService.ReportData.class);
        var metadata=mock(ProcurementReviewReport.class);var project=mock(ProcurementProject.class);
        when(data.metadata()).thenReturn(metadata);when(data.project()).thenReturn(project);
        when(metadata.id()).thenReturn(UUID.randomUUID());when(metadata.reportNumber()).thenReturn("SYNTHETIC-REPORT");when(metadata.generatedTime()).thenReturn(Instant.parse("2026-10-16T00:00:00Z"));
        when(project.projectCode()).thenReturn("SYNTHETIC");when(project.projectName()).thenReturn("仿真项目<&>中文");when(project.budgetAmount()).thenReturn(new BigDecimal("10000.01"));
        when(data.overallConclusion()).thenReturn("材料尚不完整");when(data.blacklistAlerts()).thenReturn(List.of());when(data.tenderFindings()).thenReturn(List.of());
        return data;
    }
    @Test void exportsAllSixSectionsAndMissingEvidenceWithoutInventingSuccess()throws Exception {
        var data=fixture();byte[] bytes=new ProcurementReportDocument().render(data);
        try(var doc=new XWPFDocument(new ByteArrayInputStream(bytes))){
            String text=String.join("\n",doc.getParagraphs().stream().map(XWPFParagraph::getText).toList());
            assertThat(text).contains("仿真项目<&>中文","10000.01 元","一、智能体预审","二、询比价及报价异常","三、供应商黑名单比对","四、招标文件合规审查","五、围串标辅助识别","六、拆分采购辅助识别","未完成判断","不能据此排除风险","不代表已通过合规审查").doesNotContain("提交审批");
        }
    }
    @Test void preservesFindingEvidenceAndSuppressesObsoleteApprovalStatus()throws Exception {
        var data=fixture();var agent=mock(ProcurementAgentReviewService.ReviewResult.class);var item=mock(ProcurementAgentReviewService.ReviewItem.class);
        when(item.checkType()).thenReturn("PROJECT_STATUS");when(agent.items()).thenReturn(List.of(item));when(agent.aiSummary()).thenReturn("LEGACY_APPROVAL_STATUS");when(data.agentReview()).thenReturn(agent);when(data.overallConclusion()).thenReturn("LEGACY_APPROVAL_STATUS");
        var finding=mock(ProcurementTenderFinding.class);when(finding.riskLevel()).thenReturn("CRITICAL");when(finding.matchedText()).thenReturn("原文<仿真>&中文");when(finding.sourceLocator()).thenReturn("第3页第2段");when(finding.legalBasis()).thenReturn("仿真法规依据");when(data.tenderFindings()).thenReturn(List.of(finding));
        try(var doc=new XWPFDocument(new ByteArrayInputStream(new ProcurementReportDocument().render(data)))){
            assertThat(String.join("\n",doc.getParagraphs().stream().map(XWPFParagraph::getText).toList())).contains("原文<仿真>&中文","第3页第2段","仿真法规依据","严重风险").doesNotContain("LEGACY_APPROVAL_STATUS");
        }
    }
}
