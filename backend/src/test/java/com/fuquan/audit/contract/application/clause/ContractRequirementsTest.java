package com.fuquan.audit.contract.application.clause;

import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ContractRequirementsTest {
    private ContractClauseMaterial file(String name,ProjectFileCategory type,String text) {
        return new ContractClauseMaterial(UUID.randomUUID(),name,type,text);
    }
    @Test void naturalTemplateChapterFindsMissingAndReorderedCustomClausesButIgnoresNextChapter() {
        var contract=file("合同书.docx",ProjectFileCategory.CONTRACT_BODY,"政府采购合同\n第一条 合同价款\n总价为100000元。\n第二条 采购内容\n采购办公终端设备。\n第三条 争议解决\n协商不成依法起诉。");
        var tender=file("招标文件.pdf",ProjectFileCategory.TENDER_DOCUMENT,"第三章 合同条款及格式\n第一条 采购内容\n设备应当符合列明的技术规格。\n第二条 合同价款\n价税合计按中标金额签订。\n第三条 数据安全\n乙方不得对外泄露采购人信息。\n第四条 争议解决\n双方应当协商处理合同争议。\n第四章 投标文件格式\n第一条 报价声明\n不得成为合同结构要求。");
        var result=new ContractTermClauseAnalyzer().analyze(List.of(contract,tender));
        assertThat(result.deterministicChecks()).filteredOn(c->c.type().equals("TEMPLATE_STRUCTURE_COMPARISON")).singleElement().satisfies(c->{
            assertThat(c.status()).isEqualTo("REVIEW");assertThat(c.summary()).contains("数据安全","顺序").doesNotContain("报价声明");
        });
        var toc=file("目录.pdf",ProjectFileCategory.TENDER_DOCUMENT,"目录\n第三章 合同条款及格式\n第一条 合同价款\n第二条 数据安全\n第四章 投标格式");
        assertThat(new ContractTermClauseAnalyzer().analyze(List.of(contract,toc)).deterministicChecks()).noneMatch(c->c.type().equals("TEMPLATE_STRUCTURE_COMPARISON"));
    }
    @Test void financialChineseCopiesAreReconciledWithoutCountingRepeatedSamePartyTwice() {
        var c=file("合同.docx",ProjectFileCategory.CONTRACT_BODY,"本合同一式肆份，甲方执贰份，乙方执叁份。甲方执贰份。");
        assertThat(new ContractTermClauseAnalyzer().analyze(List.of(c)).deterministicChecks()).filteredOn(x->x.type().equals("COPY_COUNT_REQUIREMENT_COMPARISON"))
                .singleElement().satisfies(x->{assertThat(x.status()).isEqualTo("REVIEW");assertThat(x.summary()).contains("5","4","不一致");});
    }
    @Test void combinedPartyCopiesAndInlineTemplateClausesAreSupported() {
        var contract=file("合同.docx",ProjectFileCategory.CONTRACT_BODY,"合同书\n第一条 合同价款。合同总价为十万元，按实际履约支付。\n第二条 采购内容。办公设备供货安装调试并验收。\n本合同一式肆份，甲乙双方各执贰份。");
        var tender=file("招标.pdf",ProjectFileCategory.TENDER_DOCUMENT,"第三章 合同格式\n第一条 采购内容。设备必须按照技术清单供货并安装。\n第二条 合同价款。合同总价按中标价签订并依约支付。\n第三条 数据安全。乙方应当对采购人的资料保密。");
        var checks=new ContractTermClauseAnalyzer().analyze(List.of(contract,tender)).deterministicChecks();
        assertThat(checks).anySatisfy(c->{assertThat(c.type()).isEqualTo("COPY_COUNT_REQUIREMENT_COMPARISON");assertThat(c.status()).isEqualTo("NORMAL");assertThat(c.summary()).contains("合计4");});
        assertThat(checks).anySatisfy(c->{assertThat(c.type()).isEqualTo("TEMPLATE_STRUCTURE_COMPARISON");assertThat(c.summary()).contains("顺序","数据安全");});
        assertThat(checks).noneMatch(c->c.type().equals("SIGN_DATE_COMPLETENESS"));
    }
    @Test void chineseRateUsesActualElapsedDaysNeverAConditionalTerminationThreshold() {
        var analyzer=new BreachClauseAnalyzer();
        var c=file("合同.docx",ProjectFileCategory.CONTRACT_BODY,"乙方逾期交付，每日按合同金额千分之一支付违约金，实际逾期交付陆拾日，累计最高不超过合同金额百分之五。");
        assertThat(analyzer.analyze(new BigDecimal("100000"),List.of(c)).items()).singleElement().satisfies(i->{
            assertThat(i.correspondingAmount()).isEqualByComparingTo("100");assertThat(i.actualPenaltyAmount()).isEqualByComparingTo("6000");
            assertThat(i.capAmount()).isEqualByComparingTo("5000");assertThat(i.warning()).contains("超过约定上限");
        });
        var condition=file("合同.docx",ProjectFileCategory.CONTRACT_BODY,"乙方逾期交付，每日按合同金额万分之五支付违约金，逾期超过六十日甲方可解除合同。");
        assertThat(analyzer.analyze(new BigDecimal("100000"),List.of(condition)).items()).singleElement().satisfies(i->{
            assertThat(i.correspondingAmount()).isEqualByComparingTo("50");assertThat(i.actualPenaltyAmount()).isNull();
        });
    }
    @Test void malformedAndChineseDatesAreCheckedAndAttachmentIsFoundByBodyTitle() {
        var c=file("合同.docx",ProjectFileCategory.CONTRACT_BODY,"采购合同\n签署日期：二〇二六年十月二十九日\n生效日期：2026.10.30\n终止日期：2026-02-30\n附件清单：技术规格书。");
        var annex=file("附件扫描件.pdf",ProjectFileCategory.UNKNOWN,"技术规格书\n本项目终端设备应配置内存十六GB，并提供三年质保。");
        var review=new ContractTermClauseAnalyzer().analyze(List.of(c,annex));
        assertThat(review.dates()).extracting(ContractTermClauseReview.DateItem::normalizedDate).contains("2026-10-29","2026-10-30",null);
        assertThat(review.deterministicChecks()).anyMatch(x->x.type().equals("DATE_FORMAT_COMPLETENESS")&&x.status().equals("REVIEW"));
        assertThat(review.deterministicChecks()).anyMatch(x->x.type().equals("ATTACHMENT_REFERENCE_MATCH")&&x.status().equals("NORMAL"));
    }
}
