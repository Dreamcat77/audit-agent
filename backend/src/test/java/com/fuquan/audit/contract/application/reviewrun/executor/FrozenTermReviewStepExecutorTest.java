package com.fuquan.audit.contract.application.reviewrun.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.clause.ContractTermClauseAnalyzer;
import com.fuquan.audit.contract.application.reviewrun.FrozenEvidenceGrounder;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FrozenTermReviewStepExecutorTest {
    private static final UUID CONTRACT_FILE =
            UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID TENDER_FILE =
            UUID.fromString("40000000-0000-0000-0000-000000000002");

    @Test
    void mapsFrozenDateConditionAndComparisonWithExactParseTupleEvidence() {
        String contractText = "交付日期：2026年6月30日。合同在双方签字、盖章后生效。";
        String tenderText = "交付期限：2026年6月15日。采购文件签章后生效。";
        var contractMaterial = FrozenClauseExecutorTestSupport.material(
                1, CONTRACT_FILE, DocumentRole.CONTRACT_BODY, contractText);
        var tenderMaterial = FrozenClauseExecutorTestSupport.material(
                2, TENDER_FILE, DocumentRole.TENDER_DOCUMENT, tenderText);
        var execution = FrozenClauseExecutorTestSupport.execution(
                ContractReviewStepType.TERM, new BigDecimal("100.00"),
                List.of(contractMaterial, tenderMaterial));

        var result = executor().execute(execution.input(), execution.context());

        assertThat(result.outputResourceType()).isEqualTo("REVIEW_FINDING_SET");
        assertThat(result.outputResourceId()).isEqualTo(FrozenClauseExecutorTestSupport.ATTEMPT_ID);
        assertThat(result.findings()).hasSize(4);
        assertThat(result.findings()).extracting(value -> value.statusCode())
                .containsExactly("NORMAL", "NORMAL", "REVIEW", "NORMAL");
        assertThat(result.findings()).extracting(value -> value.riskLevel())
                .containsOnlyNulls();
        assertThat(result.findings().get(0).summary())
                .contains("交付日期", "2026-06-30", "2026年6月30日")
                .doesNotContain("type=", "normalizedDate=");
        assertThat(result.findings().get(1).summary())
                .contains("生效条件", "双方签字、盖章后生效")
                .doesNotContain("type=");
        assertThat(result.evidence()).hasSize(6);
        assertThat(result.evidence().subList(0, 2))
                .extracting(value -> value.fileId())
                .containsOnly(CONTRACT_FILE);
        assertThat(result.evidence().subList(0, 2))
                .extracting(value -> value.parseResultId())
                .containsOnly(contractMaterial.parseResultId());
        assertThat(result.evidence().subList(0, 2))
                .extracting(value -> value.evidenceRole())
                .containsOnly("PROBLEM");
        assertThat(result.evidence().subList(2, 6))
                .extracting(value -> value.fileId())
                .containsExactly(CONTRACT_FILE, TENDER_FILE, CONTRACT_FILE, TENDER_FILE);
        assertThat(result.evidence().subList(2, 6))
                .extracting(value -> value.parseResultId())
                .containsExactly(contractMaterial.parseResultId(), tenderMaterial.parseResultId(),
                        contractMaterial.parseResultId(), tenderMaterial.parseResultId());
        assertThat(result.evidence().subList(2, 6))
                .extracting(value -> value.evidenceRole())
                .containsOnly("COMPARISON");
        assertThat(result.evidence()).allSatisfy(value -> {
            assertThat(value.locatorAvailable()).isTrue();
            assertThat(value.validationStatus()).isEqualTo("VALIDATED");
            assertThat(value.startOffset()).isNotNull();
            assertThat(value.endOffset()).isGreaterThan(value.startOffset());
        });
        assertThat(result.typedComparisons()).filteredOn(comparison ->
                comparison.comparisonKey().equals("TERM_DATE:DELIVERY_DEADLINE")).singleElement().satisfies(comparison -> {
            assertThat(comparison.result()).isEqualTo("DIFFERENT");
            assertThat(comparison.values()).extracting(value -> value.normalizedDate())
                    .containsExactly("2026-06-30", "2026-06-15");
            assertThat(comparison.values()).allSatisfy(value ->
                    assertThat(value.evidenceIds()).isNotEmpty());
        });
        assertThat(result.typedComparisons()).filteredOn(comparison ->
                comparison.comparisonKey().equals("TERM_CONDITION:EFFECTIVE_EVENT"))
                .singleElement().satisfies(comparison -> {
                    assertThat(comparison.result()).isEqualTo("MATCH");
                    assertThat(comparison.values()).extracting(value -> value.effectiveCondition())
                            .allSatisfy(value -> assertThat(value).contains("生效"));
                    assertThat(comparison.values()).allSatisfy(value ->
                            assertThat(value.evidenceIds()).isNotEmpty());
                });
    }

    @Test
    void repeatedDateExcerptFailsClosedInsteadOfPersistingAmbiguousEvidence() {
        String repeated = "交付日期：2026年6月30日。交付日期：2026年6月30日。";
        var execution = FrozenClauseExecutorTestSupport.execution(
                ContractReviewStepType.TERM, new BigDecimal("100.00"), List.of(
                        FrozenClauseExecutorTestSupport.material(
                                1, CONTRACT_FILE, DocumentRole.CONTRACT_BODY, repeated)));

        assertThatThrownBy(() -> executor().execute(execution.input(), execution.context()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).errorCode())
                        .isEqualTo(BusinessErrorCode.CONFLICT));
    }

    @Test
    void persistsTotalAndExactPartyAllocationWithFrozenEvidence() {
        String text = "本合同一式陆份，甲乙双方各执叁份。";
        var material = FrozenClauseExecutorTestSupport.material(
                1, CONTRACT_FILE, DocumentRole.CONTRACT_BODY, text);
        var execution = FrozenClauseExecutorTestSupport.execution(
                ContractReviewStepType.TERM, new BigDecimal("100.00"), List.of(material));

        var result = executor().execute(execution.input(), execution.context());

        assertThat(result.findings()).filteredOn(f->f.findingType().equals("CONTRACT_COPIES")).singleElement().satisfies(finding -> {
            assertThat(finding.findingType()).isEqualTo("CONTRACT_COPIES");
            assertThat(finding.statusCode()).isEqualTo("INSUFFICIENT");
            assertThat(finding.summary()).isEqualTo("合同份数：原文约定一式 6 份。");
        });
        assertThat(result.findings()).filteredOn(f->f.findingType().equals("COPY_COUNT_REQUIREMENT_COMPARISON")).singleElement().satisfies(finding->{
            assertThat(finding.statusCode()).isEqualTo("NORMAL");assertThat(finding.summary()).contains("合计6份");
        });
        assertThat(result.evidence()).filteredOn(e->e.boundedExcerpt().equals("本合同一式陆份")).singleElement().satisfies(evidence -> {
            assertThat(evidence.fileId()).isEqualTo(CONTRACT_FILE);
            assertThat(evidence.boundedExcerpt()).isEqualTo("本合同一式陆份");
            assertThat(evidence.locatorAvailable()).isTrue();
        });
    }

    @Test
    void unsupportedFrozenAnalyzerPolicyFailsClosedBeforeRunningCurrentEngine() {
        var execution = FrozenClauseExecutorTestSupport.execution(
                ContractReviewStepType.TERM, new BigDecimal("100.00"), List.of(
                        FrozenClauseExecutorTestSupport.material(
                                1, CONTRACT_FILE, DocumentRole.CONTRACT_BODY, "frozen clause")),
                "term-analyzer-future-v2");

        assertThatThrownBy(() -> executor().execute(execution.input(), execution.context()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).errorCode())
                        .isEqualTo(BusinessErrorCode.CONFLICT));
    }

    @Test
    void mapsTemplateAndAttachmentChecksToDurableFindingsWithFrozenEvidence() {
        String contractText = "政府采购合同\n合同价款\n违约责任\n附件清单：技术规格书。";
        String tenderText = "【指定合同模板开始】\n合同价款\n违约责任\n争议解决\n【指定合同模板结束】";
        UUID attachmentFile = UUID.fromString("40000000-0000-0000-0000-000000000003");
        var execution = FrozenClauseExecutorTestSupport.execution(
                ContractReviewStepType.TERM, new BigDecimal("100.00"), List.of(
                        FrozenClauseExecutorTestSupport.material(
                                1, CONTRACT_FILE, DocumentRole.CONTRACT_BODY, contractText),
                        FrozenClauseExecutorTestSupport.material(
                                2, TENDER_FILE, DocumentRole.TENDER_DOCUMENT, tenderText),
                        FrozenClauseExecutorTestSupport.material(
                                3, attachmentFile, DocumentRole.SUPPLEMENT,
                                "技术规格书\n系统技术参数正文。", "技术规格书.pdf")));

        var result = executor().execute(execution.input(), execution.context());

        assertThat(result.findings()).extracting(value -> value.findingType())
                .containsExactly("TEMPLATE_STRUCTURE_COMPARISON", "ATTACHMENT_REFERENCE_MATCH");
        assertThat(result.findings()).extracting(value -> value.statusCode())
                .containsExactly("REVIEW", "NORMAL");
        assertThat(result.evidence()).extracting(value -> value.fileId())
                .contains(CONTRACT_FILE, TENDER_FILE, attachmentFile);
        assertThat(result.evidence()).allSatisfy(value -> {
            assertThat(value.locatorAvailable()).isTrue();
            assertThat(value.validationStatus()).isEqualTo("VALIDATED");
        });
    }

    private static FrozenTermReviewStepExecutor executor() {
        return new FrozenTermReviewStepExecutor(
                new ContractTermClauseAnalyzer(), new FrozenClauseMaterialAdapter(),
                new FrozenClauseResultMapper(
                        new FrozenEvidenceGrounder(), FrozenClauseExecutorTestSupport.CLOCK));
    }
}
