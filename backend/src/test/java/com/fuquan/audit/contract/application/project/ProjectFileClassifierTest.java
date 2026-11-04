package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectFileClassifierTest {
    @Test
    void explicitFilenameCategoryOverridesConflictingParentDirectory() {
        ProjectFileClassifier classifier = new ProjectFileClassifier();

        assertThat(classifier.classify("01_合同/智慧办公终端采购合同.docx"))
                .isEqualTo(ProjectFileCategory.CONTRACT_BODY);
        assertThat(classifier.classify("合同/采购合同.docx"))
                .isEqualTo(ProjectFileCategory.CONTRACT_BODY);
        assertThat(classifier.classify("03_招标文件/政府采购合同模板.docx"))
                .isEqualTo(ProjectFileCategory.CONTRACT_BODY);
        assertThat(classifier.classify("03_招标文件/采购文件.docx"))
                .isEqualTo(ProjectFileCategory.TENDER_DOCUMENT);
        assertThat(classifier.classify("02_中标通知书/中标通知书_XM.docx"))
                .isEqualTo(ProjectFileCategory.AWARD_NOTICE);
        assertThat(classifier.classify("04_投标文件/投标文件_供应商A.docx"))
                .isEqualTo(ProjectFileCategory.BID_RESPONSE);
        assertThat(classifier.classify("05_补充材料/附件说明.txt"))
                .isEqualTo(ProjectFileCategory.SUPPLEMENT);
        assertThat(classifier.classify("05_补充协议/协议正文.docx"))
                .isEqualTo(ProjectFileCategory.CONTRACT_BODY);
        assertThat(classifier.classify("03_投标文件/报价表.xlsx")).isEqualTo(ProjectFileCategory.BID_RESPONSE);
    }

    @Test
    void recognizesSupportedMaterialTypesFromFilename() {
        ProjectFileClassifier classifier = new ProjectFileClassifier();

        assertThat(classifier.classify("资料/智慧办公终端采购合同.docx"))
                .isEqualTo(ProjectFileCategory.CONTRACT_BODY);
        assertThat(classifier.classify("资料/合同书.pdf"))
                .isEqualTo(ProjectFileCategory.CONTRACT_BODY);
        assertThat(classifier.classify("资料/正式合同.pdf"))
                .isEqualTo(ProjectFileCategory.CONTRACT_BODY);
        assertThat(classifier.classify("资料/中标通知书.pdf"))
                .isEqualTo(ProjectFileCategory.AWARD_NOTICE);
        assertThat(classifier.classify("资料/成交通知书.pdf"))
                .isEqualTo(ProjectFileCategory.AWARD_NOTICE);
        assertThat(classifier.classify("资料/投标文件.pdf"))
                .isEqualTo(ProjectFileCategory.BID_RESPONSE);
        assertThat(classifier.classify("资料/响应文件.pdf"))
                .isEqualTo(ProjectFileCategory.BID_RESPONSE);
        assertThat(classifier.classify("资料/招标文件.pdf"))
                .isEqualTo(ProjectFileCategory.TENDER_DOCUMENT);
        assertThat(classifier.classify("资料/采购公告.pdf"))
                .isEqualTo(ProjectFileCategory.TENDER_DOCUMENT);
        assertThat(classifier.classify("资料/补充协议.pdf"))
                .isEqualTo(ProjectFileCategory.SUPPLEMENT);
        assertThat(classifier.classify("资料/澄清说明.pdf"))
                .isEqualTo(ProjectFileCategory.SUPPLEMENT);
        assertThat(classifier.classify("资料/变更协议.pdf"))
                .isEqualTo(ProjectFileCategory.SUPPLEMENT);
        assertThat(classifier.classify("资料/采购说明.docx"))
                .isEqualTo(ProjectFileCategory.UNKNOWN);
        assertThat(classifier.classify("资料/image.png")).isEqualTo(ProjectFileCategory.UNKNOWN);
    }

    @Test
    void fallsBackToParentDirectoryOnlyWhenFilenameHasNoCategorySignal() {
        ProjectFileClassifier classifier = new ProjectFileClassifier();

        assertThat(classifier.classify("03_招标文件/技术参数.docx"))
                .isEqualTo(ProjectFileCategory.TENDER_DOCUMENT);
        assertThat(classifier.classify("04_投标文件/报价清单.xlsx"))
                .isEqualTo(ProjectFileCategory.BID_RESPONSE);
        assertThat(classifier.classify("04_投标文件/附件.doc"))
                .isEqualTo(ProjectFileCategory.BID_RESPONSE);
        assertThat(classifier.classify("资料/附件.doc"))
                .isEqualTo(ProjectFileCategory.SUPPLEMENT);
        assertThat(classifier.classify("05_补充材料/说明.txt"))
                .isEqualTo(ProjectFileCategory.SUPPLEMENT);
        assertThat(classifier.classify("一般资料/说明.txt"))
                .isEqualTo(ProjectFileCategory.UNKNOWN);
    }

    @Test
    void englishSignalsRemainCaseInsensitive() {
        ProjectFileClassifier classifier = new ProjectFileClassifier();

        assertThat(classifier.classify("docs/CONTRACT.docx")).isEqualTo(ProjectFileCategory.CONTRACT_BODY);
        assertThat(classifier.classify("docs/Agreement.docx")).isEqualTo(ProjectFileCategory.CONTRACT_BODY);
        assertThat(classifier.classify("docs/AWARD.pdf")).isEqualTo(ProjectFileCategory.AWARD_NOTICE);
        assertThat(classifier.classify("docs/Winning.pdf")).isEqualTo(ProjectFileCategory.AWARD_NOTICE);
        assertThat(classifier.classify("docs/BID.docx")).isEqualTo(ProjectFileCategory.BID_RESPONSE);
        assertThat(classifier.classify("docs/Response.docx")).isEqualTo(ProjectFileCategory.BID_RESPONSE);
        assertThat(classifier.classify("docs/TENDER.pdf")).isEqualTo(ProjectFileCategory.TENDER_DOCUMENT);
        assertThat(classifier.classify("docs/Procurement.pdf")).isEqualTo(ProjectFileCategory.TENDER_DOCUMENT);
    }
}
