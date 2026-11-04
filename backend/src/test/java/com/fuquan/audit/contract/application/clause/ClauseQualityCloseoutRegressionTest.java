package com.fuquan.audit.contract.application.clause;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClauseQualityCloseoutRegressionTest {
    private static final UUID CONTRACT = new UUID(0, 901);
    private static final UUID TENDER = new UUID(0, 902);

    @ParameterizedTest
    @ValueSource(strings = {
            "乙方于2029年8月17日前完成部署、配置及系统交付。",
            "乙方应于2029-08-17前完成系统部署及交付工作。",
            "本项目须在2029 年 8 月 17 日之前完成部署并交付。",
            "全部成果于2029年8月17日前完成部署、调试和初步交付，并留存交付记录。",
            "成果于2029年8月17日前完成部署并交付采购人指定的验收场地以便开展后续联调工作；"})
    void extractsExplicitDeploymentDeliveryDeadlineFromItsOwnClause(String text) {
        var result = new ContractTermClauseAnalyzer().analyze(List.of(contract(text)));
        assertThat(result.dates()).singleElement().satisfies(date -> {
            assertThat(date.type()).isEqualTo("DELIVERY_DEADLINE");
            assertThat(date.normalizedDate()).isEqualTo("2029-08-17");
            assertThat(text).contains(date.excerpt());
            assertThat(date.excerpt()).contains("交付");
        });
    }

    @Test
    void recognizesADeploymentAcceptanceCompletionDateWithTheActionBeforeTheDate() {
        String text = "项目部署、培训及验收应于2029年8月17日前完成。";
        var result = new ContractTermClauseAnalyzer().analyze(List.of(contract(text)));
        assertThat(result.dates()).singleElement().satisfies(date -> {
            assertThat(date.type()).isEqualTo("DELIVERY_DEADLINE");
            assertThat(date.normalizedDate()).isEqualTo("2029-08-17");
            assertThat(text).contains(date.excerpt());
        });
    }

    @Test
    void aResultDeploymentDeadlineUsesItsOwnClauseWithoutBorrowingTheNextDeliveryClause() {
        String dateClause = "全部成果于2030年9月18日前完成部署、配置和联调；";
        var result = new ContractTermClauseAnalyzer().analyze(List.of(contract(dateClause
                + "交付地点由采购人提前书面指定。")));
        assertThat(result.dates()).singleElement().satisfies(date -> {
            assertThat(date.type()).isEqualTo("DELIVERY_DEADLINE");
            assertThat(date.normalizedDate()).isEqualTo("2030-09-18");
            assertThat(date.excerpt()).isEqualTo(dateClause);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"不要求全部成果于2030年9月18日前完成部署、配置和联调；",
            "全部成果于2030年9月18日前完成部署，具体期限另行约定；"})
    void aNegatedOrUnconfirmedResultDeploymentDateRemainsUnextracted(String text) {
        assertThat(new ContractTermClauseAnalyzer().analyze(List.of(contract(text))).dates()).isEmpty();
    }

    @Test
    void comparesDeploymentDeadlineAgainstTheOtherFrozenMaterialRatherThanReportingNoExtraction() {
        var result = new ContractTermClauseAnalyzer().analyze(List.of(
                contract("乙方于2029年8月17日前完成部署、配置及系统交付。"),
                tender("交付日期：2029年8月18日。")));
        assertThat(result.comparisons()).singleElement().satisfies(comparison -> {
            assertThat(comparison.status()).isEqualTo("REVIEW");
            assertThat(comparison.typedSides()).extracting(ClauseComparison.TypedSide::normalizedDate)
                    .containsExactly("2029-08-17", "2029-08-18");
            assertThat(comparison.sources()).extracting(ClauseComparison.Source::fileId)
                    .containsExactly(CONTRACT, TENDER);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "不要求乙方于2029年8月17日前完成部署并交付。",
            "乙方不得于2029年8月17日前完成部署并交付。",
            "乙方于2029年8月17日前仅完成部署，交付日期另行约定。",
            "乙方于2029年8月17日或2029年8月18日前完成部署并交付。",
            "目录\n于2029年8月17日前完成部署并交付……12\n第一章 正文"})
    void negatedAmbiguousAndContentsTextCannotCreateADeploymentDeliveryDeadline(String text) {
        assertThat(new ContractTermClauseAnalyzer().analyze(List.of(contract(text))).dates()).isEmpty();
    }

    @Test
    void oneOffMajorDefectDoesNotBorrowDelayTerminationOrRequireAnInventedCumulativeCap() {
        String text = "第八章 违约责任、补救和合同解除\n"
                + "乙方逾期交付，每周按合同金额2%支付违约金；累计上限为合同金额10%。\n"
                + "乙方连续延期交付超过4周且经书面催告仍未履行的，甲方可解除合同。\n"
                + "系统存在重大缺陷，乙方应一次性按合同金额20%支付违约金。";
        var result = new BreachClauseAnalyzer().analyze(new BigDecimal("73000"), List.of(contract(text)));
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0)).satisfies(delay -> {
            assertThat(delay.rate()).isEqualByComparingTo("2");
            assertThat(delay.frequency()).isEqualTo("WEEK");
            assertThat(delay.capRate()).isEqualByComparingTo("10");
            assertThat(delay.terminationCondition()).contains("超过4周").doesNotContain("第八章");
        });
        assertThat(result.items().get(1)).satisfies(defect -> {
            assertThat(defect.scenario()).isEqualTo("软件/服务不符合约定");
            assertThat(defect.rate()).isEqualByComparingTo("20");
            assertThat(defect.frequency()).isEqualTo("ONCE");
            assertThat(defect.correspondingAmount()).isEqualByComparingTo("14600.00");
            assertThat(defect.capRate()).isNull();
            assertThat(defect.terminationCondition()).isNull();
            assertThat(defect.warning()).isNull();
            assertThat(defect.status()).isEqualTo("NORMAL");
        });
    }

    @Test
    void aHeadingAndANegatedRemedyCannotBecomeTerminationConditions() {
        var result = new BreachClauseAnalyzer().analyze(new BigDecimal("73000"), List.of(contract(
                "第八章 重大缺陷、违约责任和合同解除\n"
                        + "重大缺陷责任按合同金额20%一次性支付违约金。\n"
                        + "发生重大缺陷时甲方不得解除合同。")));
        assertThat(result.items()).singleElement().satisfies(item ->
                assertThat(item.terminationCondition()).isNull());
    }

    @Test
    void differentResponsiblePartiesCannotPublishAMatchingBreachComparison() {
        var result = new BreachClauseAnalyzer().analyze(new BigDecimal("73000"), List.of(
                contract("软件不符合约定，甲方应按合同金额20%一次性支付违约金。"),
                tender("软件不符合约定，乙方应按合同金额20%一次性支付违约金。")));
        assertThat(result.comparisons()).singleElement().satisfies(comparison -> {
            assertThat(comparison.status()).isEqualTo("REVIEW");
            assertThat(comparison.typedSides()).extracting(ClauseComparison.TypedSide::responsibleParty)
                    .containsExactly("甲方", "乙方");
        });
    }

    @Test
    void equalCapsDoNotHideDifferentAppliedDelayRates() {
        var result = new BreachClauseAnalyzer().analyze(new BigDecimal("73000"), List.of(
                contract("乙方逾期交付，每周按合同金额2%支付违约金；累计上限为合同金额10%。"),
                tender("乙方逾期交付，每周按合同金额3%支付违约金；累计上限为合同金额10%。")));
        assertThat(result.comparisons()).singleElement().satisfies(comparison ->
                assertThat(comparison.status()).isEqualTo("REVIEW"));
    }

    @Test
    void aSeverelyNonconformingSubjectKeepsTheSameQualityEventAcrossMaterials() {
        var result = new BreachClauseAnalyzer().analyze(new BigDecimal("73000"), List.of(
                contract("软件存在重大缺陷，乙方应按合同金额20%一次性支付违约金。"),
                tender("标的严重不符合约定，乙方应按合同金额20%一次性承担违约责任。")));
        assertThat(result.comparisons()).singleElement().satisfies(comparison -> {
            assertThat(comparison.key()).isEqualTo("BREACH_TYPED:软件/服务不符合约定");
            assertThat(comparison.status()).isEqualTo("NORMAL");
        });
    }

    @Test
    void aTermHeadingAndNegatedRemedyAreNotPositiveTerminationConditions() {
        var result = new ContractTermClauseAnalyzer().analyze(List.of(contract(
                "第八章 甲方可以解除的事项\n连续延期超过4周，甲方不得解除合同。")));
        assertThat(result.conditions()).isEmpty();
    }

    @Test
    void equalRatesAndCapsCannotHideDifferentTerminationThresholds() {
        String rate = "乙方逾期交付，每周按合同金额2%支付违约金；累计上限为合同金额10%。";
        var result = new BreachClauseAnalyzer().analyze(new BigDecimal("73000"), List.of(
                contract(rate + "乙方延期交付超过4周，甲方可解除合同。"),
                tender(rate + "乙方延期交付超过5周，甲方可解除合同。")));
        assertThat(result.comparisons()).singleElement().satisfies(comparison ->
                assertThat(comparison.status()).isEqualTo("REVIEW"));
    }

    @Test
    void aNamedEarlierPartyDoesNotReplaceTheExplicitPartyPayingThePenalty() {
        var result = new BreachClauseAnalyzer().analyze(new BigDecimal("73000"), List.of(
                contract("软件不符合约定，甲方验收后，乙方应按合同金额20%一次性支付违约金。"),
                tender("软件不符合约定，乙方应按合同金额20%一次性支付违约金。")));
        assertThat(result.comparisons()).singleElement().satisfies(comparison -> {
            assertThat(comparison.status()).isEqualTo("NORMAL");
            assertThat(comparison.typedSides()).extracting(ClauseComparison.TypedSide::responsibleParty)
                    .containsExactly("乙方", "乙方");
        });
    }

    @Test
    void numericAndChineseThresholdsCompareByTheDurationInsteadOfTheirSpelling() {
        String rate = "乙方逾期交付，每周按合同金额2%支付违约金；累计上限为合同金额10%。";
        var materials = List.of(contract(rate + "乙方延期交付超过4周，甲方可解除合同。"),
                tender(rate + "乙方延期交付超过四周，甲方可解除合同。"));
        assertThat(new BreachClauseAnalyzer().analyze(new BigDecimal("73000"), materials).comparisons())
                .singleElement().satisfies(comparison -> assertThat(comparison.status()).isEqualTo("NORMAL"));
        assertThat(new ContractTermClauseAnalyzer().analyze(materials).comparisons())
                .singleElement().satisfies(comparison -> assertThat(comparison.status()).isEqualTo("NORMAL"));
    }

    @Test
    void aOneOffUnknownBasisExplainsTheMissingBasisWithoutDemandingACumulativeCap() {
        var result = new BreachClauseAnalyzer().analyze(new BigDecimal("73000"), List.of(
                contract("重大缺陷按20%一次性承担违约责任。"),
                tender("重大缺陷按20%一次性承担违约责任。")));
        assertThat(result.comparisons()).singleElement().satisfies(comparison -> {
            assertThat(comparison.status()).isEqualTo("INSUFFICIENT");
            assertThat(comparison.message()).contains("基数").doesNotContain("未提取上限");
        });
    }

    @Test
    void matchingTerminationThresholdDoesNotClaimThatEveryNoticeAndRemedyConditionIsEquivalent() {
        String contractCondition = "乙方延期超过4周且经书面催告仍未履行的，甲方可解除合同。";
        String tenderCondition = "乙方延期超过四周，甲方可解除合同。";
        var result = new ContractTermClauseAnalyzer().analyze(List.of(contract(contractCondition),
                tender(tenderCondition)));
        assertThat(result.comparisons()).singleElement().satisfies(comparison -> {
            assertThat(comparison.status()).isEqualTo("NORMAL");
            assertThat(comparison.message()).contains("触发类型", "阈值", "仍需核对")
                    .doesNotContain("终止条件一致");
            assertThat(comparison.typedSides()).extracting(ClauseComparison.TypedSide::terminationCondition)
                    .containsExactly(contractCondition, tenderCondition);
        });
    }

    private static ContractClauseMaterial contract(String text) {
        return new ContractClauseMaterial(CONTRACT, "合同.docx", ProjectFileCategory.CONTRACT_BODY, text);
    }

    private static ContractClauseMaterial tender(String text) {
        return new ContractClauseMaterial(TENDER, "采购文件.docx", ProjectFileCategory.TENDER_DOCUMENT, text);
    }
}
