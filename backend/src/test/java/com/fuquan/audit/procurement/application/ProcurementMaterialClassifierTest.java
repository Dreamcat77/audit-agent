package com.fuquan.audit.procurement.application;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
class ProcurementMaterialClassifierTest {
    @Test void renamedFilesAreClassifiedFromCoverTitlesWithoutReadingBodyInstructionsAsTitles() {
        assertThat(ProcurementMaterialClassifier.classify("material.docx", "文件解析结果\n2026年设备采购项目投标文件\n投标人：甲公司").kind()).isEqualTo("BIDDER");
        assertThat(ProcurementMaterialClassifier.classify("material.docx", "某单位\n设备采购项目招标文件\n投标人应提交投标文件\n投标人：甲公司").kind()).isEqualTo("TENDER");
    }
    @Test void tenderInstructionsMentioningBidDocumentsDoNotBecomeABidderFile() {
        assertThat(ProcurementMaterialClassifier.classify("招标文件.docx","招标文件\n投标人须提交投标文件\n投标人：某公司").kind()).isEqualTo("TENDER");
    }
    @Test void explicitFieldsAreGroundedAndUploaderIpIsNeverInvented() {
        var result=ProcurementMaterialClassifier.classify("响应文件.pdf","响应文件\n投标人名称：甲方科技有限公司\n授权代表：张某\n投标IP地址：10.1.2.3");
        assertThat(result.kind()).isEqualTo("BIDDER");assertThat(result.bidderName()).isEqualTo("甲方科技有限公司");
        assertThat(result.ipAddress()).isEqualTo("10.1.2.3");assertThat(result.handlerName()).isEqualTo("张某");
    }
    @Test void contradictoryIdentitiesAndTemplateBlanksRequireConfirmation() {
        assertThat(ProcurementMaterialClassifier.classify("投标文件.docx","投标人：甲\n投标人：乙").kind()).isEqualTo("BIDDER_UNCONFIRMED");
        assertThat(ProcurementMaterialClassifier.classify("投标文件.docx","投标人：____\n其他供应商为甲公司").bidderName()).isNull();
    }
    @Test void ordinaryCompanyMentionAndGenericNetworkIpAreNotBidderMetadata() {
        var result=ProcurementMaterialClassifier.classify("投标文件.docx","技术参数由甲公司提供\nIP地址：10.1.2.3");
        assertThat(result.bidderName()).isNull();assertThat(result.ipAddress()).isNull();
    }
    @Test void scannedQuotationUsesExplicitSupplierAndNeverTheProjectOrBudget() {
        var result=ProcurementMaterialClassifier.classify("横置材料.jpg", "供应商报价记录\n项目名称：办公设备采购\n预算金额：100000元\n供应商：甲公司\n含税报价总额：118000元。");
        assertThat(result.kind()).isEqualTo("QUOTATION");
        assertThat(result.bidderName()).isNull(); // Supplier extraction is separate from formal bidder registration.
        assertThat(ProcurementBidQuotationExtractor.extract("含税报价总额：118000.00元。")).isEqualByComparingTo("118000.00");
    }
    @Test void quotationTemplatesAndConflictingSuppliersStayUnconfirmed() {
        assertThat(ProcurementMaterialClassifier.classify("报价单.pdf", "供应商：____\n报价总额：118000元").kind()).isEqualTo("QUOTATION");
        assertThat(ProcurementMaterialClassifier.classify("报价单.pdf", "供应商：甲公司\n报价单位：乙公司").bidderName()).isNull();
        assertThat(ProcurementMaterialClassifier.classify("报价单.pdf", "项目名称：甲公司项目\n报价总额：118000元").bidderName()).isNull();
    }
    @Test void tenderContainingQuotationTemplatesDoesNotBecomeSupplierEvidence() {
        assertThat(ProcurementMaterialClassifier.classify("招标文件.docx", "招标文件\n附件：供应商报价单\n供应商：甲公司").kind()).isEqualTo("TENDER");
        assertThat(ProcurementMaterialClassifier.classify("技术说明.docx", "技术说明\n供应商：甲公司\n说明报价单的填写方式").kind()).isEqualTo("OTHER");
    }
}
