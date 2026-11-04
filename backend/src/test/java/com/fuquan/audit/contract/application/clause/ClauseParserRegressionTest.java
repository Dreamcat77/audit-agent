package com.fuquan.audit.contract.application.clause;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;

class ClauseParserRegressionTest {
    private static final UUID CONTRACT = new UUID(0, 1);
    private static final UUID TENDER = new UUID(0, 2);
    private static final UUID ATTACHMENT = new UUID(0, 3);

    @ParameterizedTest
    @ValueSource(strings = {"每延期满一周", "每满一周", "每逾期满一周"})
    void fullWeekFrequencyIsNotSilentlyConvertedToOnce(String frequency) {
        String text = "乙方逾期交付，" + frequency + "应按合同金额3%支付违约金；累计上限为合同金额15%。";
        var result = new BreachClauseAnalyzer().analyze(new BigDecimal("10000"), List.of(contract(text)));
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.frequency()).isEqualTo("WEEK");
            assertThat(item.correspondingAmount()).isEqualByComparingTo("300.00");
            assertThat(item.excerpt()).isEqualTo(text);
        });
    }

    @Test
    void contractPriceIsAnExplicitContractBasisButLineItemAmountIsNot() {
        var result = new BreachClauseAnalyzer().analyze(new BigDecimal("10000"), List.of(contract(
                "软件不符合约定，乙方应按合同价款30%支付违约金。\n逾期交付，按分项金额3%支付违约金。")));
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).calculationBasis()).isEqualTo("CONTRACT_AMOUNT");
        assertThat(result.items().get(0).correspondingAmount()).isEqualByComparingTo("3000.00");
        assertThat(result.items().get(1).calculationBasis()).isEqualTo("LINE_ITEM_AMOUNT");
        assertThat(result.items().get(1).correspondingAmount()).isNull();
        assertThat(result.items().get(0).warning()).isNull(); // one-off liability has no required cumulative cap
    }

    @Test
    void anUnsupportedTwoWeekFrequencyRemainsUnknownInsteadOfOnce() {
        var result = new BreachClauseAnalyzer().analyze(new BigDecimal("10000"), List.of(contract(
                "乙方逾期交付，每延期满两周按合同金额3%支付违约金。")));
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.frequency()).isNull();
            assertThat(item.correspondingAmount()).isNull();
            assertThat(item.warning()).contains("频次");
        });
    }

    @Test
    void equalCapsDoNotHideExplicitlyUnresolvedAppliedFrequency() {
        String text = "乙方逾期交付，每延期满两周按合同金额3%支付违约金；累计上限为合同金额15%。";
        var result = new BreachClauseAnalyzer().analyze(new BigDecimal("10000"), List.of(contract(text),
                new ContractClauseMaterial(TENDER, "采购文件.docx", ProjectFileCategory.TENDER_DOCUMENT, text)));
        assertThat(result.comparisons()).singleElement().satisfies(c -> {
            assertThat(c.status()).isEqualTo("INSUFFICIENT");
            assertThat(c.typedSides()).allSatisfy(side -> {
                assertThat(side.rate()).isEqualByComparingTo("3");
                assertThat(side.capRate()).isEqualByComparingTo("15");
                assertThat(side.frequency()).isNull();
            });
            assertThat(c.sources()).hasSize(2);
        });
    }

    @Test
    void detailedClauseCannotSubsumeAnExplicitlyUnresolvedSummaryFrequency() {
        String partial = "逾期交付，每延期满两周按3%支付违约金；累计上限15%。";
        String detail = "逾期交付，每周按合同金额3%支付违约金；累计上限为合同金额15%。";
        var tender = new ContractClauseMaterial(TENDER, "采购文件.docx", ProjectFileCategory.TENDER_DOCUMENT,
                partial + "\n" + detail);
        var result = new BreachClauseAnalyzer().analyze(new BigDecimal("10000"), List.of(contract(detail),
                tender));
        assertThat(result.comparisons()).singleElement().satisfies(c -> {
            assertThat(c.status()).isEqualTo("INSUFFICIENT");
            assertThat(c.typedSides().get(1).state()).isEqualTo("KNOWN");
            assertThat(c.typedSides().get(1).rate()).isEqualByComparingTo("3");
            assertThat(c.typedSides().get(1).capRate()).isEqualByComparingTo("15");
            assertThat(c.typedSides().get(1).calculationBasis()).isEqualTo("OTHER_OR_UNCLEAR");
            assertThat(c.typedSides().get(1).frequency()).isNull();
            assertThat(c.sources()).filteredOn(source -> source.fileId().equals(TENDER)).hasSize(2);
        });
        var originalItems = new BreachClauseAnalyzer().analyze(new BigDecimal("10000"), List.of(tender)).items();
        assertThat(originalItems).filteredOn(item -> item.frequency() == null).singleElement().satisfies(item -> {
            assertThat(item.warning()).contains("频次");
            assertThat(item.correspondingAmount()).isNull();
            assertThat(item.excerpt()).isEqualTo(partial);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"逾期交付累计违约金上限为合同金额15%。",
            "逾期交付，每周按合同金额3%支付违约金；累计上限为合同金额15%。"})
    void anAbsentAppliedRateDoesNotDisableAnOtherwiseExplicitCapComparison(String first) {
        var result = new BreachClauseAnalyzer().analyze(new BigDecimal("10000"), List.of(contract(first),
                new ContractClauseMaterial(TENDER, "采购文件.docx", ProjectFileCategory.TENDER_DOCUMENT,
                        "逾期交付累计违约金上限为合同金额15%。")));
        assertThat(result.comparisons()).singleElement().satisfies(c -> {
            assertThat(c.status()).isEqualTo("NORMAL");
            assertThat(c.typedSides()).allSatisfy(side -> {
                assertThat(side.state()).isEqualTo("KNOWN");
                assertThat(side.capRate()).isEqualByComparingTo("15");
            });
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "逾期交付，每周按合同金额4%支付违约金；累计上限为合同金额15%。",
            "逾期交付，每周按合同金额3%支付违约金；累计上限为合同金额16%。",
            "逾期交付，每周按合同金额3%支付违约金；累计上限为合同金额15%；延期超过五周可解除合同。"})
    void unresolvedSummaryDoesNotHideKnownRateCapOrPeriodConflicts(String different) {
        String partial = "逾期交付，每延期满两周按3%支付违约金；累计上限15%。";
        String detail = "逾期交付，每周按合同金额3%支付违约金；累计上限为合同金额15%；延期超过四周可解除合同。";
        var result = new BreachClauseAnalyzer().analyze(new BigDecimal("10000"), List.of(contract(detail),
                new ContractClauseMaterial(TENDER, "采购文件.docx", ProjectFileCategory.TENDER_DOCUMENT,
                        partial + "\n" + detail + "\n" + different)));
        assertThat(result.comparisons()).singleElement().satisfies(c -> {
            assertThat(c.status()).isEqualTo("REVIEW");
            assertThat(c.typedSides().get(1).state()).isEqualTo("CONFLICT");
            assertThat(c.sources()).filteredOn(source -> source.fileId().equals(TENDER)).hasSize(3);
        });
    }

    @Test
    void cumulativeSummaryPercentageIsNotASecondAppliedRateAndDoesNotInventItsBasis() {
        var materials = List.of(contract("延期违约金2%/周，累计10%。"),
                new ContractClauseMaterial(TENDER, "采购文件.docx", ProjectFileCategory.TENDER_DOCUMENT,
                        "延期违约金2%/周，累计10%。"));
        var result = new BreachClauseAnalyzer().analyze(new BigDecimal("10000"), materials);
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.rate()).isEqualByComparingTo("2");
            assertThat(item.capRate()).isEqualByComparingTo("10");
            assertThat(item.calculationBasis()).isEqualTo("OTHER_OR_UNCLEAR");
            assertThat(item.correspondingAmount()).isNull();
            assertThat(item.status()).isEqualTo("INSUFFICIENT");
        });
        assertThat(result.comparisons()).singleElement().satisfies(c ->
                assertThat(c.status()).isEqualTo("INSUFFICIENT"));
    }

    @Test
    void compatiblePartialSummaryAndDetailedClauseKeepBothSourcesWithoutFalseConflict() {
        String detail = "乙方逾期交付，每延期一周应按合同金额2%支付违约金；累计上限为合同金额10%；延期超过四周，甲方有权解除合同。";
        String summary = "乙方逾期交付违约金2%/周，累计10%。乙方延期交付超过四周可解除合同。";
        var result = new BreachClauseAnalyzer().analyze(new BigDecimal("10000"), List.of(contract(detail),
                new ContractClauseMaterial(TENDER, "响应文件.docx", ProjectFileCategory.BID_RESPONSE, summary + "\n" + detail)));
        assertThat(result.comparisons()).singleElement().satisfies(c -> {
            assertThat(c.status()).isEqualTo("NORMAL");
            assertThat(c.typedSides()).allSatisfy(side -> {
                assertThat(side.state()).isEqualTo("KNOWN");
                assertThat(side.rate()).isEqualByComparingTo("2");
                assertThat(side.capRate()).isEqualByComparingTo("10");
                assertThat(side.calculationBasis()).isEqualTo("CONTRACT_AMOUNT");
            });
            assertThat(c.sources()).filteredOn(source -> source.fileId().equals(TENDER)).hasSize(2);
        });
    }

    @Test
    void actualTwoAppliedRatesAndContradictoryKnownRatesStillRemainConflicts() {
        for (String conflicting : List.of(
                "逾期交付，每周按合同金额2%支付违约金，另按合同金额3%支付违约金；累计上限合同金额10%。",
                "逾期交付，每周按合同金额2%支付违约金；累计上限合同金额10%。\n逾期交付，每周按合同金额3%支付违约金；累计上限合同金额10%。")) {
            var result = new BreachClauseAnalyzer().analyze(new BigDecimal("10000"), List.of(contract(conflicting),
                    new ContractClauseMaterial(TENDER, "采购文件.docx", ProjectFileCategory.TENDER_DOCUMENT,
                            "逾期交付，每周按合同金额2%支付违约金；累计上限合同金额10%。")));
            assertThat(result.items()).hasSize(2);
            assertThat(result.comparisons()).singleElement().satisfies(c -> {
                assertThat(c.status()).isEqualTo("REVIEW");
                assertThat(c.typedSides().get(0).state()).isEqualTo("CONFLICT");
            });
        }
    }

    @Test
    void aReportedCumulativePaidAmountDoesNotBecomeAContractualCap() {
        var result = new BreachClauseAnalyzer().analyze(new BigDecimal("10000"), List.of(contract(
                "逾期交付，每周按合同金额2%支付违约金，累计已支付合同金额10%。")));
        assertThat(result.items()).allSatisfy(item -> assertThat(item.capRate()).isNull());
    }

    @Test
    void aDetailedClauseInAnotherFileCannotFillTheSummaryBasis() {
        var result = new BreachClauseAnalyzer().analyze(new BigDecimal("10000"), List.of(
                contract("逾期交付违约金2%/周，累计10%。"),
                new ContractClauseMaterial(TENDER, "采购文件.docx", ProjectFileCategory.TENDER_DOCUMENT,
                        "逾期交付，每周按合同金额2%支付违约金；累计上限为合同金额10%。")));
        assertThat(result.comparisons()).singleElement().satisfies(c -> {
            assertThat(c.status()).isEqualTo("INSUFFICIENT");
            assertThat(c.typedSides().get(0).calculationBasis()).isEqualTo("OTHER_OR_UNCLEAR");
            assertThat(c.typedSides().get(1).calculationBasis()).isEqualTo("CONTRACT_AMOUNT");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "逾期交付，每周按合同金额2%支付违约金；累计上限为合同金额12%。",
            "逾期交付，每周按合同金额2%支付违约金；累计上限为合同金额10%；延期超过五周，甲方有权解除合同。"})
    void differentDetailedCapOrTerminationThresholdCannotBeDeduplicated(String different) {
        String original = "逾期交付，每周按合同金额2%支付违约金；累计上限为合同金额10%；延期超过四周，甲方有权解除合同。";
        var result = new BreachClauseAnalyzer().analyze(new BigDecimal("10000"), List.of(
                contract(original + "\n" + different),
                new ContractClauseMaterial(TENDER, "采购文件.docx", ProjectFileCategory.TENDER_DOCUMENT, original)));
        assertThat(result.comparisons()).singleElement().satisfies(c -> {
            assertThat(c.status()).isEqualTo("REVIEW");
            assertThat(c.typedSides().get(0).state()).isEqualTo("CONFLICT");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"附件一《技术参数表》、附件九《联调记录表》。", "附件一：技术参数表；附件九：联调记录表。",
            "附件一：《技术参数表》——已随包提供；附件九：《联调记录表》——待另行提交。"})
    void boundedNumberedAttachmentSectionProducesGroundedMatchesAndMissingReferences(String declaration) {
        String text = "第九条 合同生效、附件清单\n9.1 双方签字盖章后生效。\n9.2 " + declaration
                + "\n第十条 其他事项\n附件九：联调记录表未提交，不得由此推定已经提供。";
        var result = new ContractTermClauseAnalyzer().analyze(List.of(contract(text),
                new ContractClauseMaterial(ATTACHMENT, "06_附件一_技术参数表.docx", ProjectFileCategory.SUPPLEMENT,
                        "技术参数表\n参数和验收测试记录。")));
        assertThat(result.deterministicChecks()).hasSize(2);
        assertThat(result.deterministicChecks().get(0)).satisfies(check -> {
            assertThat(check.type()).isEqualTo("ATTACHMENT_REFERENCE_MATCH");
            assertThat(check.status()).isEqualTo("NORMAL");
            assertThat(check.sources()).extracting(ContractTermClauseReview.CheckSource::fileId)
                    .containsExactly(CONTRACT, ATTACHMENT);
        });
        assertThat(result.deterministicChecks().get(1)).satisfies(check -> {
            assertThat(check.status()).isEqualTo("ATTENTION");
            assertThat(check.sources()).singleElement().satisfies(source -> {
                assertThat(source.fileId()).isEqualTo(CONTRACT);
                assertThat(source.excerpt()).contains("附件九").doesNotContain("第十条", "未提交");
                assertThat(text).contains(source.excerpt());
            });
        });
    }

    @Test
    void attachmentReferenceOutsideABoundedListAndTableOfContentsDoNotClaimCoverage() {
        var result = new ContractTermClauseAnalyzer().analyze(List.of(contract(
                "目录\n第九条 附件清单……12\n第十条 其他事项……13\n附件一《技术参数表》仅为举例，不构成附件清单。")));
        assertThat(result.deterministicChecks()).isEmpty();
    }

    @Test
    void anAttachmentListInTheMiddleOfAChapterTitleStillHasABoundedDeclaration() {
        var result = new ContractTermClauseAnalyzer().analyze(List.of(contract(
                "第九条 附件清单及合同生效\n附件九《联调记录表》。\n第十条 其他事项")));
        assertThat(result.deterministicChecks()).singleElement().satisfies(check -> {
            assertThat(check.status()).isEqualTo("ATTENTION");
            assertThat(check.sources()).singleElement().satisfies(source ->
                    assertThat(source.excerpt()).isEqualTo("附件九《联调记录表》。"));
        });
    }

    @Test
    void repeatedListReferencesAreDeduplicatedAndSameNameAttachmentsRemainAmbiguous() {
        var result = new ContractTermClauseAnalyzer().analyze(List.of(contract(
                "第九条 附件清单\n附件一《技术参数表》、附件一《技术参数表》。"),
                new ContractClauseMaterial(ATTACHMENT, "06_附件一_技术参数表.docx", ProjectFileCategory.SUPPLEMENT,
                        "技术参数表\n第一版本正文。"),
                new ContractClauseMaterial(new UUID(0, 4), "07_附件二_技术参数表.pdf", ProjectFileCategory.SUPPLEMENT,
                        "技术参数表\n第二版本正文。")));
        assertThat(result.deterministicChecks()).singleElement().satisfies(check -> {
            assertThat(check.status()).isEqualTo("ATTENTION");
            assertThat(check.sources()).hasSize(1);
        });
    }

    @Test
    void unknownAttachmentWithSubstantiveBodyCanProveReferenceButAHeadingAloneCannot() {
        for (var category : List.of(ProjectFileCategory.UNKNOWN, ProjectFileCategory.SUPPLEMENT)) {
            var result = new ContractTermClauseAnalyzer().analyze(List.of(contract(
                    "第九条 附件清单\n附件一《技术参数表》。"),
                    new ContractClauseMaterial(ATTACHMENT, "06_附件一_技术参数表.docx", category,
                            category == ProjectFileCategory.UNKNOWN ? "技术参数表\n参数与测试记录正文。" : "技术参数表")));
            assertThat(result.deterministicChecks()).singleElement().satisfies(check ->
                    assertThat(check.status()).isEqualTo(category == ProjectFileCategory.UNKNOWN ? "NORMAL" : "ATTENTION"));
        }
    }

    @Test
    void uniqueExplicitAchievementDeliveryDateIsExtractedWithoutConfusingSignatureDates() {
        String text = "双方拟约定全部成果于2028 年 3 月 6 日完成交付。\n甲方签字盖章 日期：2028年1月1日。";
        var result = new ContractTermClauseAnalyzer().analyze(List.of(contract(text)));
        assertThat(result.dates()).singleElement().satisfies(item -> {
            assertThat(item.type()).isEqualTo("DELIVERY_DEADLINE");
            assertThat(item.normalizedDate()).isEqualTo("2028-03-06");
            assertThat(item.excerpt()).contains("成果", "完成交付");
            assertThat(text).contains(item.excerpt());
        });
    }

    @Test
    void aDateOnThePreviousLineDoesNotMakeAnExplicitDeliveryClauseAmbiguous() {
        var result = new ContractTermClauseAnalyzer().analyze(List.of(contract(
                "签约日期：2028年1月1日\n成果于2028年3月6日完成交付。")));
        assertThat(result.dates()).extracting(ContractTermClauseReview.DateItem::normalizedDate)
                .containsExactly("2028-01-01", "2028-03-06");
    }

    @Test
    void aNegatedDeliveryObligationIsNotAnExplicitDeliveryDeadline() {
        var result = new ContractTermClauseAnalyzer().analyze(List.of(contract(
                "不要求成果于2028年3月6日完成交付。")));
        assertThat(result.dates()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"成果于2028年3月6日或2028年4月6日完成交付。", "成果于2028年3月6日预计可交付。",
            "成果于2028年3月6日签署，实际交付另行约定。", "目录\n成果于2028年3月6日完成交付……12\n第一章 正文",
            "成果于2028年3月6日生效。", "成果于年 月 日完成交付。"})
    void ambiguousUnfilledSignatureEffectiveAndContentsDatesDoNotBecomeDeliveryDates(String text) {
        var result = new ContractTermClauseAnalyzer().analyze(List.of(contract(text)));
        assertThat(result.dates()).isEmpty();
    }

    @Test
    void unrelatedSelectedAttachmentsDoNotBecomeMandatoryClauseComparisonSides() {
        String breach = "逾期交付，每周按合同金额3%支付违约金；累计上限合同金额15%。";
        String term = "交付日期：2028年3月6日。";
        var materials = List.of(contract(breach + term),
                new ContractClauseMaterial(TENDER, "采购文件.docx", ProjectFileCategory.TENDER_DOCUMENT, breach + term),
                new ContractClauseMaterial(ATTACHMENT, "参数表.docx", ProjectFileCategory.SUPPLEMENT, "参数表\n测试参数正文。"));
        assertThat(new BreachClauseAnalyzer().analyze(new BigDecimal("10000"), materials).comparisons())
                .singleElement().satisfies(c -> {
                    assertThat(c.status()).isEqualTo("NORMAL");
                    assertThat(c.typedSides()).hasSize(2);
                });
        assertThat(new ContractTermClauseAnalyzer().analyze(materials).comparisons())
                .singleElement().satisfies(c -> {
                    assertThat(c.status()).isEqualTo("NORMAL");
                    assertThat(c.typedSides()).hasSize(2);
                });
    }

    @Test
    void missingRequiredTenderFieldStillRemainsInsufficient() {
        var materials = List.of(contract("交付日期：2028年3月6日。"),
                new ContractClauseMaterial(TENDER, "采购文件.docx", ProjectFileCategory.TENDER_DOCUMENT, "未填写期限。"));
        assertThat(new ContractTermClauseAnalyzer().analyze(materials).comparisons()).singleElement()
                .satisfies(c -> assertThat(c.status()).isEqualTo("INSUFFICIENT"));
    }

    private static ContractClauseMaterial contract(String text) {
        return new ContractClauseMaterial(CONTRACT, "合同.docx", ProjectFileCategory.CONTRACT_BODY, text);
    }

    @Test void naturalServiceRangeIsAnEndDateRatherThanAMissingTermination() {
        String text="签订日期：2026年10月29日；服务期自2026年11月1日起至2027年10月31日止。";
        var result=new ContractTermClauseAnalyzer().analyze(List.of(contract(text)));
        assertThat(result.dates()).anySatisfy(d->{assertThat(d.type()).isEqualTo("TERMINATION_DATE");assertThat(d.normalizedDate()).isEqualTo("2027-10-31");});
        assertThat(result.deterministicChecks()).noneMatch(c->c.type().equals("TERMINATION_COMPLETENESS"));
        assertThat(new ContractTermClauseAnalyzer().analyze(List.of(contract("例如服务期自2026年11月1日至2027年10月31日止。"))).dates()).isEmpty();
    }
    @Test void inlineAttachmentReferenceUsesActualBatchContentsAndKeepsAMissingCounterexample() {
        var body=contract("附件一《服务标准》为合同组成部分。");
        var annex=new ContractClauseMaterial(ATTACHMENT,"服务标准.docx",ProjectFileCategory.OTHER,"服务标准\n每月维护设备，故障四小时内响应。");
        assertThat(new ContractTermClauseAnalyzer().analyze(List.of(body,annex)).deterministicChecks()).singleElement().satisfies(c->{
            assertThat(c.type()).isEqualTo("ATTACHMENT_REFERENCE_MATCH");assertThat(c.status()).isEqualTo("NORMAL");assertThat(c.sources()).hasSize(2);
        });
        assertThat(new ContractTermClauseAnalyzer().analyze(List.of(body)).deterministicChecks()).singleElement().satisfies(c->assertThat(c.status()).isEqualTo("ATTENTION"));
        assertThat(new ContractTermClauseAnalyzer().analyze(List.of(contract("其他文件可能提到附件一《服务标准》，请按实际情况核对。"))).deterministicChecks()).isEmpty();
    }
    @Test void totalContractPriceIsAValidBreachCalculationBase() {
        var result=new BreachClauseAnalyzer().analyze(new BigDecimal("120000"),List.of(contract(
                "乙方逾期交付的，每日按合同总价款千分之一支付违约金，累计不超过总价款百分之五；目前已逾期60日。")));
        assertThat(result.items()).anySatisfy(i->{assertThat(i.correspondingAmount()).isEqualByComparingTo("120");assertThat(i.capAmount()).isEqualByComparingTo("6000");assertThat(i.actualPenaltyAmount()).isEqualByComparingTo("7200");});
    }
}
