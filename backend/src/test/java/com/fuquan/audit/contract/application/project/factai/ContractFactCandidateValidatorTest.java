package com.fuquan.audit.contract.application.project.factai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Candidate;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.ContextCode;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.FactType;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Provenance;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Request;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Result;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Segment;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContractFactCandidateValidatorTest {
    private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-000000000100");
    private static final UUID FILE = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID PARSE = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final String TEXT = "Total = 67000.00x";
    private static final Provenance PROVENANCE = new Provenance("OPENAI_COMPATIBLE", "fact-model-test",
            "NOT_DECLARED_BY_PROVIDER", "contract-fact-prompt-v1", "contract-fact-candidates-v1");
    private final ContractFactCandidateValidator validator = new ContractFactCandidateValidator(PROVENANCE);

    @Test
    void projectsOnlyExactValidatedCandidateIntoExistingExtractionResultAuthority() {
        var validation = validator.validate(current(DocumentRole.CONTRACT_BODY, true), request(),
                result(amountCandidate()), Instant.parse("2026-09-23T10:00:00Z"));

        assertThat(validation.decisions()).singleElement().satisfies(decision -> {
            assertThat(decision.status()).isEqualTo(ContractFactCandidateValidator.Status.ACCEPTED);
            assertThat(decision.reason()).isEqualTo(ContractFactCandidateValidator.Reason.ACCEPTED);
        });
        assertThat(validation.publications()).singleElement().satisfies(fact -> {
            assertThat(fact.projectId()).isEqualTo(PROJECT);
            assertThat(fact.sourceFileId()).isEqualTo(FILE);
            assertThat(fact.parseResultId()).isEqualTo(PARSE);
            assertThat(fact.extractionVersion()).isEqualTo("contract-fact-ai-v1");
            assertThat(fact.factType()).isEqualTo("AMOUNT_NUMERIC");
            assertThat(fact.valueAmount()).isEqualByComparingTo("67000.00");
            assertThat(fact.sourceText()).isEqualTo("67000.00");
            assertThat(fact.sourceLocator()).isEqualTo(
                    "parse-result:%s:canonical-codepoint:8-16".formatted(PARSE));
        });
    }

    @Test
    void immutableFileAndParseIdentityMismatchFailsClosedBeforePublication() {
        Request request = request();
        Request changed = new Request(request.projectId(), request.fileId(), "f".repeat(64),
                request.parseResultId(), request.parseVersion(), request.canonicalTextSha256(),
                request.segments());

        assertThatThrownBy(() -> validator.validate(current(DocumentRole.CONTRACT_BODY, true), changed,
                result(amountCandidate()), Instant.now()))
                .isInstanceOf(ContractFactValidationException.class)
                .extracting("reason")
                .isEqualTo(ContractFactCandidateValidator.Reason.INPUT_AUTHORITY_MISMATCH);
    }

    @Test
    void awardCandidateRequiresConfirmedAwardRoleAndSameProjectIdentity() {
        Candidate award = new Candidate("cfc-v1-" + "d".repeat(64), FactType.AWARD_AMOUNT, null,
                null, new BigDecimal("67000.00"), "segment-1", segment().sourceLocator(),
                "67000.00", 8, 16, ContextCode.AWARD_FINAL_RESULT, new BigDecimal("0.95"));

        for (ContractFactCandidateValidator.CurrentInput authority : List.of(
                current(DocumentRole.CONTRACT_BODY, true), current(DocumentRole.AWARD_NOTICE, false))) {
            var validation = validator.validate(authority, request(), result(award), Instant.now());
            assertThat(validation.publications()).isEmpty();
            assertThat(validation.decisions()).singleElement().satisfies(decision -> {
                assertThat(decision.status()).isEqualTo(ContractFactCandidateValidator.Status.REJECTED);
                assertThat(decision.reason()).isIn(
                        ContractFactCandidateValidator.Reason.ROLE_NOT_AUTHORIZED,
                        ContractFactCandidateValidator.Reason.PROJECT_IDENTITY_NOT_CONFIRMED);
            });
        }
    }

    @Test
    void decimalMustBeGroundedInTheExactFrozenExcerpt() {
        Candidate invented = new Candidate("cfc-v1-" + "e".repeat(64), FactType.CONTRACT_AMOUNT, null,
                null, new BigDecimal("69000.00"), "segment-1", segment().sourceLocator(),
                "67000.00", 8, 16, ContextCode.CONTRACT_AMOUNT, new BigDecimal("0.90"));

        var validation = validator.validate(current(DocumentRole.CONTRACT_BODY, true), request(),
                result(invented), Instant.now());

        assertThat(validation.publications()).isEmpty();
        assertThat(validation.decisions()).singleElement().extracting("reason")
                .isEqualTo(ContractFactCandidateValidator.Reason.TYPED_VALUE_NOT_GROUNDED);
    }

    @Test
    void decimalWithWanSuffixIsGroundedUsingNormalizedExactExcerpt() {
        String text = "Contract amount 6.7\u4e07 yuan";
        Segment segment = new Segment("segment-wan",
                "parse-result:%s:canonical-codepoint:0-%d".formatted(PARSE, text.length()),
                sha256(text), 0, text.length(), text);
        Request request = new Request(PROJECT, FILE, "a".repeat(64), PARSE, 3,
                sha256(text), List.of(segment));
        Candidate candidate = new Candidate("cfc-v1-" + "a".repeat(64), FactType.CONTRACT_AMOUNT, null,
                null, new BigDecimal("67000"), segment.segmentId(), segment.sourceLocator(),
                "6.7\u4e07", 16, 20, ContextCode.CONTRACT_AMOUNT, new BigDecimal("0.91"));
        var current = new ContractFactCandidateValidator.CurrentInput(PROJECT, FILE, "a".repeat(64),
                PARSE, 3, sha256(text), text, DocumentRole.CONTRACT_BODY, true);

        var validation = validator.validate(current, request, result(candidate), Instant.now());

        assertThat(validation.publications()).singleElement()
                .extracting("valueAmount").isEqualTo(new BigDecimal("67000"));
    }

    @Test
    void contractAmountRejectsNegativePercentageAndBudgetNumbersEvenWhenDigitsMatch() {
        assertRejectedAmount("合同总金额：-100.00元", "100.00", new BigDecimal("100.00"));
        assertRejectedAmount("合同金额调整比例：10%", "10", new BigDecimal("10"));
        assertRejectedAmount("项目预算金额：100元，实际合同总金额：90元", "100", new BigDecimal("100"));
    }

    @Test
    void contractAmountKeepsSignedUnitAndFieldRelationshipForAValidCurrencyValue() {
        String text = "人民币合同总金额：67,000.00元";
        Segment segment = segment(text, "valid-contract-amount");
        int start = text.indexOf("67,000.00");
        Candidate candidate = new Candidate("cfc-v1-" + "6".repeat(64), FactType.CONTRACT_AMOUNT,
                null, null, new BigDecimal("67000.00"), segment.segmentId(), segment.sourceLocator(),
                "67,000.00", start, start + "67,000.00".length(), ContextCode.CONTRACT_AMOUNT,
                new BigDecimal("0.95"));

        var validation = validator.validate(current(text, DocumentRole.CONTRACT_BODY, true),
                request(text, segment), result(candidate), Instant.now());

        assertThat(validation.publications()).singleElement()
                .extracting("valueAmount").isEqualTo(new BigDecimal("67000.00"));
    }

    @Test
    void decimalScaleIsLimitedByFactTypeWithoutLosingFourDecimalUnitPrice() {
        String amountText = "合同总金额：1.2345元";
        Segment amountSegment = segment(amountText, "amount-scale");
        int amountStart = amountText.indexOf("1.2345");
        Candidate amount = new Candidate("cfc-v1-" + "3".repeat(64), FactType.CONTRACT_AMOUNT,
                null, null, new BigDecimal("1.2345"), amountSegment.segmentId(), amountSegment.sourceLocator(),
                "1.2345", amountStart, amountStart + 6, ContextCode.CONTRACT_AMOUNT,
                new BigDecimal("0.95"));

        var rejected = validator.validate(current(amountText, DocumentRole.CONTRACT_BODY, true),
                request(amountText, amountSegment), result(amount), Instant.now());

        assertThat(rejected.publications()).isEmpty();
        assertThat(rejected.decisions()).singleElement().extracting("reason")
                .isEqualTo(ContractFactCandidateValidator.Reason.TYPED_VALUE_NOT_GROUNDED);

        String unitText = "服务A 单价：2.3456元";
        Segment unitSegment = segment(unitText, "unit-price-scale");
        int unitStart = unitText.indexOf("2.3456");
        Candidate unitPrice = new Candidate("cfc-v1-" + "4".repeat(64), FactType.LINE_ITEM_UNIT_PRICE,
                "line:" + "8".repeat(64), null, new BigDecimal("2.3456"), unitSegment.segmentId(),
                unitSegment.sourceLocator(), "2.3456", unitStart, unitStart + 6,
                ContextCode.CONTRACT_LINE_ITEM, new BigDecimal("0.95"));

        var accepted = validator.validate(current(unitText, DocumentRole.CONTRACT_BODY, true),
                request(unitText, unitSegment), result(unitPrice), Instant.now());

        assertThat(accepted.publications()).singleElement().extracting("valueAmount")
                .isEqualTo(new BigDecimal("2.3456"));
    }

    @Test
    void finalValidatorRejectsFactTypeAndTypedValueChannelMismatch() {
        Candidate invalid = new Candidate("cfc-v1-" + "b".repeat(64), FactType.CONTRACT_NO, null,
                null, new BigDecimal("67000.00"), "segment-1", segment().sourceLocator(),
                "67000.00", 8, 16, ContextCode.CONTRACT_HEADER, new BigDecimal("0.90"));

        var validation = validator.validate(current(DocumentRole.CONTRACT_BODY, true), request(),
                result(invalid), Instant.now());

        assertThat(validation.publications()).isEmpty();
        assertThat(validation.decisions()).singleElement().extracting("reason")
                .isEqualTo(ContractFactCandidateValidator.Reason.TYPED_VALUE_NOT_GROUNDED);
    }

    @Test
    void awardFinalContextMustBeProvenByServerOwnedNearbyLabels() {
        String text = "第一中标候选人：示例供应商，投标报价67000.00元";
        Segment segment = segment(text, "candidate-ranking");
        Request request = request(text, segment);
        int start = text.indexOf("67000.00");
        Candidate candidate = new Candidate("cfc-v1-" + "f".repeat(64), FactType.AWARD_AMOUNT, null,
                null, new BigDecimal("67000.00"), segment.segmentId(), segment.sourceLocator(),
                "67000.00", start, start + 8, ContextCode.AWARD_FINAL_RESULT, new BigDecimal("0.95"));
        var current = current(text, DocumentRole.AWARD_NOTICE, true);

        var validation = validator.validate(current, request, result(candidate), Instant.now());

        assertThat(validation.publications()).isEmpty();
        assertThat(validation.decisions()).singleElement().extracting("reason")
                .isEqualTo(ContractFactCandidateValidator.Reason.CONTEXT_INVALID);
    }

    @Test
    void awardFinalAmountWithExactFinalLabelCanBePublished() {
        String text = "成交金额：67000.00元";
        Segment segment = segment(text, "award-final");
        Request request = request(text, segment);
        int start = text.indexOf("67000.00");
        Candidate candidate = new Candidate("cfc-v1-" + "9".repeat(64), FactType.AWARD_AMOUNT, null,
                null, new BigDecimal("67000.00"), segment.segmentId(), segment.sourceLocator(),
                "67000.00", start, start + 8, ContextCode.AWARD_FINAL_RESULT, new BigDecimal("0.95"));

        var validation = validator.validate(current(text, DocumentRole.AWARD_NOTICE, true), request,
                result(candidate), Instant.now());

        assertThat(validation.publications()).singleElement()
                .extracting("factType").isEqualTo("AWARD_AMOUNT");
    }

    @Test
    void narrowsMultibyteModelExcerptToTheExactDm8SafeEvidenceWindow() {
        String label = "\u6210\u4ea4\u91d1\u989d\uff1a";
        String text = "\u524d".repeat(70) + label + "67000.00\u5143" + "\u540e".repeat(70);
        Segment segment = segment(text, "wide-award-evidence");
        int start = text.indexOf("67000.00");
        Candidate candidate = new Candidate("cfc-v1-" + "2".repeat(64), FactType.AWARD_AMOUNT,
                null, null, new BigDecimal("67000.00"), segment.segmentId(), segment.sourceLocator(),
                text, 0, text.codePointCount(0, text.length()), ContextCode.AWARD_FINAL_RESULT,
                new BigDecimal("0.95"));

        var validation = validator.validate(current(text, DocumentRole.AWARD_NOTICE, true),
                request(text, segment), result(candidate), Instant.now());

        assertThat(text.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(240);
        assertThat(validation.publications()).singleElement().satisfies(publication -> {
            assertThat(publication.sourceText().getBytes(StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(240);
            assertThat(publication.sourceText()).contains(label, "67000.00");
            String offsets = publication.sourceLocator().substring(
                    publication.sourceLocator().lastIndexOf(':') + 1);
            String[] bounds = offsets.split("-");
            assertThat(codePointSlice(text, Integer.parseInt(bounds[0]), Integer.parseInt(bounds[1])))
                    .isEqualTo(publication.sourceText());
            assertThat(Integer.parseInt(bounds[0])).isLessThanOrEqualTo(start);
        });
    }

    @Test
    void narrowsMultibyteSupplierExcerptWithoutChangingTheTypedValue() {
        String label = "\u6210\u4ea4\u4f9b\u5e94\u5546\uff1a";
        String supplier = "\u793a\u4f8b\u79d1\u6280\u6709\u9650\u516c\u53f8";
        String text = "\u524d".repeat(70) + label + supplier + "\u540e".repeat(70);
        Segment segment = segment(text, "wide-award-supplier-evidence");
        Candidate candidate = new Candidate("cfc-v1-" + "1".repeat(64), FactType.AWARD_SUPPLIER,
                null, supplier, null, segment.segmentId(), segment.sourceLocator(), text, 0,
                text.codePointCount(0, text.length()), ContextCode.AWARD_FINAL_RESULT,
                new BigDecimal("0.95"));

        var validation = validator.validate(current(text, DocumentRole.AWARD_NOTICE, true),
                request(text, segment), result(candidate), Instant.now());

        assertThat(validation.publications()).singleElement().satisfies(publication -> {
            assertThat(publication.valueText()).isEqualTo(supplier);
            assertThat(publication.sourceText()).contains(label, supplier);
            assertThat(publication.sourceText().getBytes(StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(240);
        });
    }

    @Test
    void serverDerivedLineGroupKeepsMultipleRowsInOneSegmentAddressable() {
        String text = "line A 200; line B 150";
        Segment segment = segment(text, "two-lines");
        Request request = request(text, segment);
        int start = text.indexOf("200");
        Candidate candidate = new Candidate("cfc-v1-" + "8".repeat(64),
                FactType.LINE_ITEM_DECLARED_AMOUNT, "line:" + "7".repeat(64), null,
                new BigDecimal("200"), segment.segmentId(), segment.sourceLocator(), "200",
                start, start + 3, ContextCode.CONTRACT_LINE_ITEM, new BigDecimal("0.95"));

        var validation = validator.validate(current(text, DocumentRole.CONTRACT_BODY, true),
                request, result(candidate), Instant.now());

        assertThat(validation.publications()).singleElement()
                .extracting("groupKey").isEqualTo("line:" + "7".repeat(64));
    }

    private static Candidate amountCandidate() {
        return new Candidate("cfc-v1-" + "c".repeat(64), FactType.CONTRACT_AMOUNT, null,
                null, new BigDecimal("67000.00"), "segment-1", segment().sourceLocator(),
                "67000.00", 8, 16, ContextCode.CONTRACT_AMOUNT, new BigDecimal("0.96"));
    }

    @Test
    void accepts240CodepointExcerptWith241Utf16UnitsAndPublishesDm8SafeEvidence() {
        String text = "a".repeat(229) + "𠀀" + "合同金额67000元";
        Segment segment = segment(text, "unicode-boundary");
        Candidate candidate = new Candidate("cfc-v1-" + "1".repeat(64), FactType.CONTRACT_AMOUNT,
                null, null, new BigDecimal("67000"), segment.segmentId(), segment.sourceLocator(),
                text, 0, 240, ContextCode.CONTRACT_AMOUNT, new BigDecimal("0.95"));
        var validation = validator.validate(current(text, DocumentRole.CONTRACT_BODY, true),
                request(text, segment), result(candidate), Instant.now());
        assertThat(text.length()).isEqualTo(241);
        assertThat(validation.decisions()).singleElement().satisfies(decision ->
                assertThat(decision.status()).isEqualTo(ContractFactCandidateValidator.Status.ACCEPTED));
        assertThat(validation.publications()).singleElement().satisfies(publication -> {
            assertThat(publication.valueAmount()).isEqualByComparingTo("67000");
            assertThat(publication.sourceText().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(240);
            assertThat(publication.sourceText()).contains("合同金额67000元");
        });
    }

    private void assertRejectedAmount(String text, String excerpt, BigDecimal value) {
        Segment segment = segment(text, "invalid-contract-amount");
        int start = text.indexOf(excerpt);
        Candidate candidate = new Candidate("cfc-v1-" + "5".repeat(64), FactType.CONTRACT_AMOUNT,
                null, null, value, segment.segmentId(), segment.sourceLocator(), excerpt,
                start, start + excerpt.codePointCount(0, excerpt.length()), ContextCode.CONTRACT_AMOUNT,
                new BigDecimal("0.95"));

        var validation = validator.validate(current(text, DocumentRole.CONTRACT_BODY, true),
                request(text, segment), result(candidate), Instant.now());

        assertThat(validation.publications()).isEmpty();
        assertThat(validation.decisions()).singleElement().extracting("reason")
                .isEqualTo(ContractFactCandidateValidator.Reason.TYPED_VALUE_NOT_GROUNDED);
    }

    private static Result result(Candidate candidate) {
        return new Result(List.of(candidate), PROVENANCE, 9);
    }

    private static Request request() {
        return new Request(PROJECT, FILE, "a".repeat(64), PARSE, 3, sha256(TEXT), List.of(segment()));
    }

    private static Segment segment() {
        return new Segment("segment-1",
                "parse-result:%s:canonical-codepoint:0-17".formatted(PARSE), sha256(TEXT), 0, 17, TEXT);
    }

    private static Segment segment(String text, String id) {
        return new Segment(id,
                "parse-result:%s:canonical-codepoint:0-%d".formatted(PARSE, text.codePointCount(0, text.length())),
                sha256(text), 0, text.codePointCount(0, text.length()), text);
    }

    private static Request request(String text, Segment segment) {
        return new Request(PROJECT, FILE, "a".repeat(64), PARSE, 3,
                sha256(text), List.of(segment));
    }

    private static ContractFactCandidateValidator.CurrentInput current(
            String text, DocumentRole role, boolean sameProject) {
        return new ContractFactCandidateValidator.CurrentInput(PROJECT, FILE, "a".repeat(64), PARSE, 3,
                sha256(text), text, role, sameProject);
    }

    private static ContractFactCandidateValidator.CurrentInput current(DocumentRole role, boolean sameProject) {
        return new ContractFactCandidateValidator.CurrentInput(PROJECT, FILE, "a".repeat(64), PARSE, 3,
                sha256(TEXT), TEXT, role, sameProject);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String codePointSlice(String value, int start, int end) {
        return value.substring(value.offsetByCodePoints(0, start), value.offsetByCodePoints(0, end));
    }
}
