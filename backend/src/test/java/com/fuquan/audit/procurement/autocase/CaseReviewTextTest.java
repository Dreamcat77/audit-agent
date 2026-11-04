package com.fuquan.audit.procurement.autocase;
import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class CaseReviewTextTest {
    @Test void historicIdentityWordingIsReadableWithoutChangingSourceBusinessValues() {
        var check=new AuditCase.ReviewCheck("IDENTITY_projectName","CONFLICT","projectName：projectNo测试服务；缺少该字段的文件不作为冲突依据",List.of());
        assertThat(CaseReviewText.explanation(check)).isEqualTo("项目名称：projectNo测试服务；未载明此信息的材料不参与本项比对");
        assertThat(check.explanation()).startsWith("projectName：");
        var unknown=new AuditCase.ReviewCheck("IDENTITY_purchaserName","UNKNOWN","purchaserName：",List.of());
        assertThat(CaseReviewText.explanation(unknown)).contains("采购单位").doesNotContain("purchaserName","字段");
        var evidence=new AuditCase.ReviewCheck("BUSINESS_CONFLICT","CONFLICT","原文服务名称为projectName",List.of());
        assertThat(CaseReviewText.explanation(evidence)).isEqualTo(evidence.explanation());
    }
    @Test void htmlExportUsesSameReadableHistoricCheckAndFindingWithoutChangingStoredReport() {
        String old="projectNo：SYN-26；缺少该字段的文件不作为冲突依据";
        var source=new AuditCase.SourceEvidence("s",java.util.UUID.randomUUID(),"招标文件.pdf","a".repeat(64),"page:1","原始依据projectNo");
        var check=new AuditCase.ReviewCheck("IDENTITY_projectNo","CONFLICT",old,List.of(source));
        var finding=new AuditCase.ReviewFinding("IDENTITY_projectNo","WARNING","材料编号需核对",old,"核对版本",List.of(source),"DETERMINISTIC");
        var module=new AuditCase.ModuleReview("COMPLETED","digest","v1",null,null,null,List.of(),List.of(),List.of(check),List.of(finding),0,null);
        var report=new AuditCase.Report("DATA_INSUFFICIENT",null,null,List.of(),null,List.of(),java.util.Map.of(),java.util.Map.of("AGENT_PRECHECK",module),"合成项目");
        var value=new AuditCase(java.util.UUID.randomUUID(),"test",java.util.UUID.randomUUID(),"a".repeat(64),"COMPLETED",0,"lease",java.time.Instant.EPOCH,0,java.time.Instant.EPOCH,List.of(),report);
        String html=CaseReportHtml.render(value);
        assertThat(html).contains("项目编号：SYN-26","未载明此信息的材料不参与本项比对","原始依据projectNo").doesNotContain("缺少该字段","projectNo：SYN-26");
        assertThat(value.report().reviews().get("AGENT_PRECHECK").findings().get(0).explanation()).isEqualTo(old);
    }
}
