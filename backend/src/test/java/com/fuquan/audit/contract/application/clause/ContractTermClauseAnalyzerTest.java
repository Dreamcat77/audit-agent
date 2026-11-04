package com.fuquan.audit.contract.application.clause;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import java.util.UUID;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ContractTermClauseAnalyzerTest {

    private static final UUID FILE_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");

    @ParameterizedTest
    @CsvSource({
            "合同生效日期为2026年09月01日。, 2026年09月01日",
            "合同生效日期为2026年9月1日。, 2026年9月1日",
            "生效日期 为 2026年09月01日。, 2026年09月01日",
            "生效日期：2026年09月01日, 2026年09月01日",
            "生效日期 : 2026年09月01日, 2026年09月01日",
            "生效日期2026年09月01日, 2026年09月01日"
    })
    void normalizesLabelledEffectiveDateWithSupportedConnectors(String text, String source) {
        ContractTermClauseReview result = new ContractTermClauseAnalyzer().analyze(List.of(
                new ContractClauseMaterial(FILE_ID, "合同.docx", text)));

        assertThat(result.dates()).singleElement().satisfies(item -> {
            assertThat(item.type()).isEqualTo("EFFECTIVE_DATE");
            assertThat(item.source()).isEqualTo(source);
            assertThat(item.normalizedDate()).isEqualTo("2026-09-01");
            assertThat(item.status()).isEqualTo("NORMAL");
        });
    }

    @Test
    void keepsBlankEffectiveDateWithChineseConnectorInsufficient() {
        ContractTermClauseReview result = new ContractTermClauseAnalyzer().analyze(List.of(
                new ContractClauseMaterial(FILE_ID, "合同.docx", "生效日期为 年 月 日")));

        assertThat(result.dates()).singleElement().satisfies(item -> {
            assertThat(item.type()).isEqualTo("EFFECTIVE_DATE");
            assertThat(item.source()).isNull();
            assertThat(item.normalizedDate()).isNull();
            assertThat(item.status()).isEqualTo("INSUFFICIENT");
            assertThat(item.excerpt()).isEqualTo("生效日期为 年 月 日");
            assertThat(item.locatorAvailable()).isTrue();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"生效日期为准。", "生效日期为双方签章之日。"})
    void doesNotInventFixedDateFromChineseConnectorWithoutCalendarDate(String text) {
        ContractTermClauseReview result = new ContractTermClauseAnalyzer().analyze(List.of(
                new ContractClauseMaterial(FILE_ID, "合同.docx", text)));

        assertThat(result.dates()).isEmpty();
    }

    @Test
    void normalizesSupportedDatesAndKeepsBlankDateInsufficient() {
        ContractTermClauseReview result = new ContractTermClauseAnalyzer().analyze(List.of(
                new ContractClauseMaterial(FILE_ID, "合同.docx", """
                        交付截止日期：2026-06-30。
                        生效日期：2026/06/30。
                        终止日期：2026年6月30日。
                        签约日期： 年 月 日。
                        """)));

        assertThat(result.dates()).extracting(ContractTermClauseReview.DateItem::normalizedDate)
                .contains("2026-06-30", "2026-06-30", "2026-06-30", null);
        assertThat(result.dates()).filteredOn(item -> item.type().equals("SIGNING_DATE"))
                .singleElement().satisfies(item -> {
                    assertThat(item.status()).isEqualTo("INSUFFICIENT");
                    assertThat(item.source()).isNull();
                    assertThat(item.excerpt()).isEqualTo("签约日期： 年 月 日");
                    assertThat(item.locatorAvailable()).isTrue();
                });
    }

    @Test
    void recognizesEventEffectiveAndConcreteTerminationConditionsWithoutLegalVerdicts() {
        ContractTermClauseReview result = new ContractTermClauseAnalyzer().analyze(List.of(
                new ContractClauseMaterial(FILE_ID, "合同.docx", """
                        本合同在双方签字、盖章后生效。
                        延期超过一个月，守约方可单方解除合同。
                        软件或服务不符合约定时，甲方可解除合同。
                        """)));

        assertThat(result.conditions()).extracting(ContractTermClauseReview.ConditionItem::type)
                .containsExactly("EFFECTIVE_EVENT", "TERMINATION", "TERMINATION");
        assertThat(result.conditions().get(0).description()).contains("双方签字、盖章后生效");
        assertThat(result.conditions().get(0).locatorAvailable()).isTrue();
        assertThat(result.conditions().get(0).excerpt()).contains("双方签字、盖章后生效");
        assertThat(result.conditions().get(1).description()).contains("延期超过一个月");
        assertThat(result.conditions().get(2).description()).contains("不符合约定");
        assertThat(result.toString()).doesNotContain("违法", "合法", "必须驳回");
    }

    @Test
    void recognizesChineseSigningDateAndSigningEffectiveAndNegotiatedTerminationPhrases() {
        ContractTermClauseReview result = new ContractTermClauseAnalyzer().analyze(List.of(
                new ContractClauseMaterial(FILE_ID, "合同.docx", """
                        签订日期2026年9月2日。
                        本合同经双方签章之日起生效。
                        双方协商一致可终止本合同。
                        """)));

        assertThat(result.dates()).singleElement().satisfies(item -> {
            assertThat(item.type()).isEqualTo("SIGNING_DATE");
            assertThat(item.normalizedDate()).isEqualTo("2026-09-02");
            assertThat(item.status()).isEqualTo("NORMAL");
        });
        assertThat(result.conditions()).extracting(ContractTermClauseReview.ConditionItem::type)
                .containsExactly("EFFECTIVE_EVENT", "TERMINATION");
    }

    @Test
    void extractsContractCopyCountAsAQuotedFactWithoutInventingAComplianceThreshold() {
        ContractTermClauseReview result = new ContractTermClauseAnalyzer().analyze(List.of(
                new ContractClauseMaterial(FILE_ID, "合同.docx", "本合同一式陆份，甲乙双方各执叁份。")));

        assertThat(result.conditions()).singleElement().satisfies(item -> {
            assertThat(item.type()).isEqualTo("CONTRACT_COPIES");
            assertThat(item.status()).isEqualTo("INSUFFICIENT");
            assertThat(item.description()).isEqualTo("6");
            assertThat(item.excerpt()).isEqualTo("本合同一式陆份");
        });
        assertThat(result.toString()).doesNotContain("合规", "不合规", "违法");
    }

    @Test
    void negativeSigningLanguageDoesNotClaimThatSigningAloneTriggersEffectiveness() {
        ContractTermClauseReview result = new ContractTermClauseAnalyzer().analyze(List.of(
                new ContractClauseMaterial(FILE_ID, "合同.docx", """
                        未经双方签字盖章，本合同不生效。
                        本合同未生效前，双方不得开始履约。
                        本合同生效后，相关文件无需另行签字或盖章。
                        """)));

        assertThat(result.conditions()).isEmpty();
    }

    @Test
    void locatableTermFactsRequireNonBlankExactExcerpts() {
        assertThatThrownBy(() -> new ContractTermClauseReview.DateItem(
                "SIGNING_DATE", null, null, "INSUFFICIENT", FILE_ID, null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("locatorAvailable");
        assertThatThrownBy(() -> new ContractTermClauseReview.ConditionItem(
                "EFFECTIVE_EVENT", "双方签章后生效", "NORMAL", FILE_ID, " ", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("locatorAvailable");
    }

    @Test
    void recognizesOnlyExplicitDeliveryAndCompletionDateLabels() {
        ContractTermClauseReview result = new ContractTermClauseAnalyzer().analyze(List.of(
                new ContractClauseMaterial(FILE_ID, "合同.docx", """
                        交付期限：2026年10月8日前完成部署、调试并提交验收材料。
                        交付日期：2026/10/09。
                        完成日期：2026-10-10。
                        文号中包含日期2026-11-12，但不是合同期限。
                        """)));

        assertThat(result.dates()).extracting(ContractTermClauseReview.DateItem::type)
                .containsExactly("DELIVERY_DEADLINE", "DELIVERY_DEADLINE", "DELIVERY_DEADLINE");
        assertThat(result.dates()).extracting(ContractTermClauseReview.DateItem::normalizedDate)
                .containsExactly("2026-10-08", "2026-10-09", "2026-10-10");
    }

    @Test
    void comparesContractDeliveryDateAgainstSelectedTenderAndBidMaterials() {
        UUID tenderFileId = UUID.fromString("10000000-0000-0000-0000-000000000012");
        UUID bidFileId = UUID.fromString("10000000-0000-0000-0000-000000000013");
        ContractTermClauseReview result = new ContractTermClauseAnalyzer().analyze(List.of(
                new ContractClauseMaterial(FILE_ID, "合同.docx", ProjectFileCategory.CONTRACT_BODY,
                        "交付日期：2026年6月30日。"),
                new ContractClauseMaterial(tenderFileId, "采购文件.docx", ProjectFileCategory.TENDER_DOCUMENT,
                        "交付期限：2026年6月15日。"),
                new ContractClauseMaterial(bidFileId, "响应文件.docx", ProjectFileCategory.BID_RESPONSE,
                        "完成日期：2026年6月15日。")));

        assertThat(result.dates()).singleElement().satisfies(item ->
                assertThat(item.sourceFileId()).isEqualTo(FILE_ID));
        assertThat(result.comparisons()).singleElement().satisfies(comparison -> {
            assertThat(comparison.status()).isEqualTo("REVIEW");
            assertThat(comparison.message()).contains("2026-06-30", "2026-06-15");
            assertThat(comparison.sources()).extracting(ClauseComparison.Source::fileId)
                    .containsExactly(FILE_ID, tenderFileId, bidFileId);
            assertThat(comparison.typedSides()).extracting(ClauseComparison.TypedSide::normalizedDate)
                    .containsExactly("2026-06-30", "2026-06-15", "2026-06-15");
        });
    }

    @Test
    void comparesOnlyAnExplicitTenderTemplateAgainstTheContractStructure() {
        UUID tenderFileId = UUID.fromString("10000000-0000-0000-0000-000000000022");
        ContractTermClauseReview result = new ContractTermClauseAnalyzer().analyze(List.of(
                new ContractClauseMaterial(FILE_ID, "合同.docx", ProjectFileCategory.CONTRACT_BODY,
                        "政府采购合同\n合同价款\n违约责任\n双方签章后生效。"),
                new ContractClauseMaterial(tenderFileId, "采购文件.docx", ProjectFileCategory.TENDER_DOCUMENT,
                        "【指定合同模板开始】\n合同价款\n违约责任\n争议解决\n合同生效\n【指定合同模板结束】")));

        assertThat(result.deterministicChecks().stream().filter(c -> c.type().equals("TEMPLATE_STRUCTURE_COMPARISON") || c.type().equals("ATTACHMENT_REFERENCE_MATCH")).toList()).singleElement().satisfies(check -> {
            assertThat(check.type()).isEqualTo("TEMPLATE_STRUCTURE_COMPARISON");
            assertThat(check.status()).isEqualTo("REVIEW");
            assertThat(check.summary()).contains("指定模板", "争议解决");
            assertThat(check.sources()).extracting(ContractTermClauseReview.CheckSource::fileId)
                    .containsExactly(FILE_ID, tenderFileId);
        });
    }

    @Test
    void templateWordsAndHeadingsWithoutAProvableTemplateBoundaryDoNotClaimComparison() {
        UUID tenderFileId = UUID.fromString("10000000-0000-0000-0000-000000000024");
        ContractTermClauseReview result = new ContractTermClauseAnalyzer().analyze(List.of(
                new ContractClauseMaterial(FILE_ID, "合同.docx", ProjectFileCategory.CONTRACT_BODY,
                        "政府采购合同\n合同价款\n违约责任"),
                new ContractClauseMaterial(tenderFileId, "采购文件.docx", ProjectFileCategory.TENDER_DOCUMENT,
                        "目录\n第三章 合同条款及格式\n合同价款\n违约责任\n争议解决\n后附合同模板供参考")));

        assertThat(result.deterministicChecks()).noneMatch(check ->
                check.type().equals("TEMPLATE_STRUCTURE_COMPARISON"));
    }

    @Test
    void explicitTemplateComparisonDetectsClauseOrderDifferences() {
        UUID tenderFileId = UUID.fromString("10000000-0000-0000-0000-000000000027");
        ContractTermClauseReview result = new ContractTermClauseAnalyzer().analyze(List.of(
                new ContractClauseMaterial(FILE_ID, "合同.docx", ProjectFileCategory.CONTRACT_BODY,
                        "政府采购合同\n争议解决\n合同价款\n违约责任"),
                new ContractClauseMaterial(tenderFileId, "采购文件.docx", ProjectFileCategory.TENDER_DOCUMENT,
                        "【指定合同模板开始】\n合同价款\n违约责任\n争议解决\n【指定合同模板结束】")));

        assertThat(result.deterministicChecks().stream().filter(c -> c.type().equals("TEMPLATE_STRUCTURE_COMPARISON") || c.type().equals("ATTACHMENT_REFERENCE_MATCH")).toList()).singleElement().satisfies(check -> {
            assertThat(check.type()).isEqualTo("TEMPLATE_STRUCTURE_COMPARISON");
            assertThat(check.status()).isEqualTo("REVIEW");
            assertThat(check.summary()).contains("条款顺序");
        });
    }

    @Test
    void independentAttachmentHeadingWithSubstantiveBodyCanProveAttachmentCompleteness() {
        UUID embeddedFileId = UUID.fromString("10000000-0000-0000-0000-000000000023");
        ContractTermClauseReview result = new ContractTermClauseAnalyzer().analyze(List.of(
                new ContractClauseMaterial(FILE_ID, "合同.docx", ProjectFileCategory.CONTRACT_BODY,
                        "附件清单：技术规格书、报价清单。"),
                new ContractClauseMaterial(embeddedFileId, "附件.docx", ProjectFileCategory.OTHER,
                        "技术规格书\n本附件记载系统技术参数。")));

        assertThat(result.deterministicChecks().stream().filter(c -> c.type().equals("ATTACHMENT_REFERENCE_MATCH")).toList()).extracting(
                ContractTermClauseReview.DeterministicCheck::type,
                ContractTermClauseReview.DeterministicCheck::status)
                .containsExactly(tuple("ATTACHMENT_REFERENCE_MATCH", "NORMAL"),
                        tuple("ATTACHMENT_REFERENCE_MATCH", "ATTENTION"));
        assertThat(result.deterministicChecks().get(0).sources())
                .extracting(ContractTermClauseReview.CheckSource::fileId)
                .containsExactly(FILE_ID, embeddedFileId);
        assertThat(result.deterministicChecks().get(1).summary()).contains("报价清单", "未能证明匹配");
    }

    @Test
    void exactIndependentFrozenAttachmentFilenameAndTypeProveTheReference() {
        UUID attachmentFileId = UUID.fromString("10000000-0000-0000-0000-000000000025");
        ContractTermClauseReview result = new ContractTermClauseAnalyzer().analyze(List.of(
                new ContractClauseMaterial(FILE_ID, "合同.docx", ProjectFileCategory.CONTRACT_BODY,
                        "附件清单：技术规格书。"),
                new ContractClauseMaterial(attachmentFileId, "技术规格书.pdf", ProjectFileCategory.SUPPLEMENT,
                        "技术规格书\n系统技术参数正文。")));

        assertThat(result.deterministicChecks().stream().filter(c -> c.type().equals("TEMPLATE_STRUCTURE_COMPARISON") || c.type().equals("ATTACHMENT_REFERENCE_MATCH")).toList()).singleElement().satisfies(check -> {
            assertThat(check.status()).isEqualTo("NORMAL");
            assertThat(check.sources()).extracting(ContractTermClauseReview.CheckSource::fileId)
                    .containsExactly(FILE_ID, attachmentFileId);
        });
    }

    @Test
    void sameFilenameWithOnlyOneTextLineDoesNotProveAttachmentCompleteness() {
        UUID attachmentFileId = UUID.fromString("10000000-0000-0000-0000-000000000028");
        ContractTermClauseReview result = new ContractTermClauseAnalyzer().analyze(List.of(
                new ContractClauseMaterial(FILE_ID, "合同.docx", ProjectFileCategory.CONTRACT_BODY,
                        "附件清单：技术规格书。"),
                new ContractClauseMaterial(attachmentFileId, "技术规格书.pdf", ProjectFileCategory.SUPPLEMENT,
                        "系统技术参数正文。")));

        assertThat(result.deterministicChecks().stream().filter(c -> c.type().equals("TEMPLATE_STRUCTURE_COMPARISON") || c.type().equals("ATTACHMENT_REFERENCE_MATCH")).toList()).singleElement().satisfies(check -> {
            assertThat(check.status()).isEqualTo("ATTENTION");
            assertThat(check.sources()).hasSize(1);
        });
    }

    @Test
    void multipleSameNameFrozenAttachmentsRemainAmbiguous() {
        UUID first = UUID.fromString("10000000-0000-0000-0000-000000000029");
        UUID second = UUID.fromString("10000000-0000-0000-0000-000000000030");
        ContractTermClauseReview result = new ContractTermClauseAnalyzer().analyze(List.of(
                new ContractClauseMaterial(FILE_ID, "合同.docx", ProjectFileCategory.CONTRACT_BODY,
                        "附件清单：技术规格书。"),
                new ContractClauseMaterial(first, "技术规格书.pdf", ProjectFileCategory.SUPPLEMENT,
                        "技术规格书\n版本一正文。"),
                new ContractClauseMaterial(second, "附件一：技术规格书.docx", ProjectFileCategory.SUPPLEMENT,
                        "技术规格书\n版本二正文。")));

        assertThat(result.deterministicChecks().stream().filter(c -> c.type().equals("TEMPLATE_STRUCTURE_COMPARISON") || c.type().equals("ATTACHMENT_REFERENCE_MATCH")).toList()).singleElement().satisfies(check -> {
            assertThat(check.status()).isEqualTo("ATTENTION");
            assertThat(check.summary()).contains("未能证明匹配");
            assertThat(check.sources()).hasSize(1);
        });
    }

    @Test
    void embeddedAttachmentHeadingWithoutBodyDoesNotProveTheAttachmentExists() {
        ContractTermClauseReview result = new ContractTermClauseAnalyzer().analyze(List.of(
                new ContractClauseMaterial(FILE_ID, "合同.docx", ProjectFileCategory.CONTRACT_BODY,
                        "附件清单：技术规格书。\n附件一：技术规格书")));

        assertThat(result.deterministicChecks().stream().filter(c -> c.type().equals("TEMPLATE_STRUCTURE_COMPARISON") || c.type().equals("ATTACHMENT_REFERENCE_MATCH")).toList()).singleElement().satisfies(check -> {
            assertThat(check.type()).isEqualTo("ATTACHMENT_REFERENCE_MATCH");
            assertThat(check.status()).isEqualTo("ATTENTION");
            assertThat(check.sources()).hasSize(1);
        });
    }

    @Test
    void persistsTypedEffectiveAndTerminationConditionsOnlyWhenSemanticallyComparable() {
        UUID tenderFileId = UUID.fromString("10000000-0000-0000-0000-000000000026");
        ContractTermClauseReview result = new ContractTermClauseAnalyzer().analyze(List.of(
                new ContractClauseMaterial(FILE_ID, "合同.docx", ProjectFileCategory.CONTRACT_BODY,
                        "本合同经双方签章后生效。延期超过一个月，甲方可解除合同。"),
                new ContractClauseMaterial(tenderFileId, "采购文件.docx", ProjectFileCategory.TENDER_DOCUMENT,
                        "双方签字盖章后合同生效。延期超过两个月，甲方可解除合同。")));

        assertThat(result.comparisons()).filteredOn(value ->
                value.key().equals("TERM_CONDITION:EFFECTIVE_EVENT")).singleElement().satisfies(comparison -> {
                    assertThat(comparison.status()).isEqualTo("NORMAL");
                    assertThat(comparison.typedSides()).extracting(ClauseComparison.TypedSide::state)
                            .containsExactly("KNOWN", "KNOWN");
                    assertThat(comparison.typedSides()).extracting(ClauseComparison.TypedSide::effectiveCondition)
                            .allSatisfy(value -> assertThat(value).contains("生效"));
                });
        assertThat(result.comparisons()).filteredOn(value ->
                value.key().equals("TERM_CONDITION:TERMINATION")).singleElement().satisfies(comparison -> {
                    assertThat(comparison.status()).isEqualTo("REVIEW");
                    assertThat(comparison.typedSides()).extracting(ClauseComparison.TypedSide::terminationCondition)
                            .allSatisfy(value -> assertThat(value).contains("解除"));
                });
    }

    @Test
    void explicitConditionNotApplicableRemainsTypedAndNotEvaluated() {
        UUID tenderFileId = UUID.fromString("10000000-0000-0000-0000-000000000027");
        ContractTermClauseReview result = new ContractTermClauseAnalyzer().analyze(List.of(
                new ContractClauseMaterial(FILE_ID, "合同.docx", ProjectFileCategory.CONTRACT_BODY,
                        "生效条件：不适用。"),
                new ContractClauseMaterial(tenderFileId, "采购文件.docx", ProjectFileCategory.TENDER_DOCUMENT,
                        "双方签字盖章后合同生效。")));

        assertThat(result.comparisons()).singleElement().satisfies(comparison -> {
            assertThat(comparison.key()).isEqualTo("TERM_CONDITION:EFFECTIVE_EVENT");
            assertThat(comparison.status()).isEqualTo("INSUFFICIENT");
            assertThat(comparison.typedSides()).extracting(ClauseComparison.TypedSide::state)
                    .containsExactly("NOT_APPLICABLE", "KNOWN");
        });
    }

    @Test
    void explicitEmbeddedAttachmentHeadingAfterTheDeclarationIsProvable() {
        ContractTermClauseReview result = new ContractTermClauseAnalyzer().analyze(List.of(
                new ContractClauseMaterial(FILE_ID, "合同.docx", ProjectFileCategory.CONTRACT_BODY,
                        "附件清单：技术规格书。\n正文结束。\n附件一：技术规格书\n系统技术参数如下。")));

        assertThat(result.deterministicChecks().stream().filter(c -> c.type().equals("TEMPLATE_STRUCTURE_COMPARISON") || c.type().equals("ATTACHMENT_REFERENCE_MATCH")).toList()).singleElement().satisfies(check -> {
            assertThat(check.status()).isEqualTo("NORMAL");
            assertThat(check.sources()).hasSize(2)
                    .extracting(ContractTermClauseReview.CheckSource::fileId)
                    .containsOnly(FILE_ID);
        });
    }

    @Test
    void sameFrozenSideWithDifferentDatesIsAConflictAndKeepsBothSources() {
        UUID tenderFileId = UUID.fromString("10000000-0000-0000-0000-000000000024");
        ContractTermClauseReview result = new ContractTermClauseAnalyzer().analyze(List.of(
                new ContractClauseMaterial(FILE_ID, "合同.docx", ProjectFileCategory.CONTRACT_BODY,
                        "交付日期：2026年6月30日。交付截止日期：2026年7月1日。"),
                new ContractClauseMaterial(tenderFileId, "采购文件.docx", ProjectFileCategory.TENDER_DOCUMENT,
                        "交付日期：2026年6月30日。")));

        assertThat(result.comparisons()).singleElement().satisfies(comparison -> {
            assertThat(comparison.status()).isEqualTo("REVIEW");
            assertThat(comparison.sources()).filteredOn(source -> source.fileId().equals(FILE_ID)).hasSize(2);
            assertThat(comparison.typedSides()).first().satisfies(side -> {
                assertThat(side.state()).isEqualTo("CONFLICT");
                assertThat(side.normalizedDate()).isNull();
                assertThat(side.dateType()).isEqualTo("DELIVERY_DEADLINE");
            });
        });
    }
}
