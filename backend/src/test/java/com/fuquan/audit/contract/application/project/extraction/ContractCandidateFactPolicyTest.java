package com.fuquan.audit.contract.application.project.extraction;

import com.fuquan.audit.contract.domain.project.CandidateAuthorityMode;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static com.fuquan.audit.contract.application.project.parse.ProjectDocumentParserTest.file;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ContractCandidateFactPolicyTest {
    private final ContractCandidateAssembler assembler = new ContractCandidateAssembler(fixedClock());
    private final ContractCandidateFactPolicy policy = new ContractCandidateFactPolicy();

    @Test
    void explicitModeExcludesPrimaryAwardsBeforeAggregatingAuthoritativeSupportAwards() {
        ProjectFile primary = file("contract.pdf", "application/pdf");
        ProjectFile support = sibling(primary, "award.pdf");
        ExtractionResult primaryAward = amount(primary, "AWARD_AMOUNT", null, "100.00", 1);
        ExtractionResult supportAward = amount(support, "AWARD_AMOUNT", null, "98.00", 2);

        ContractCandidateDerivedFacts derived = derive(primary, List.of(primaryAward), List.of(source(support, 2, supportAward)));

        assertThat(derived.awardAmount()).isEqualByComparingTo("98.00");
        assertThat(policy.authorizedFacts(primary, List.of(primaryAward), List.of(source(support, 2, supportAward)),
                CandidateAuthorityMode.EXPLICIT_SUPPORT_AUTHORITY)).containsExactly(supportAward);
    }

    @Test
    void distinctSupportAwardsConflictWhileNormalizedEqualValuesCollapseWithoutLosingEvidence() {
        ProjectFile primary = file("contract.pdf", "application/pdf");
        ProjectFile first = sibling(primary, "award-a.pdf");
        ProjectFile second = sibling(primary, "award-b.pdf");
        ExtractionResult ninetyEight = amount(first, "AWARD_AMOUNT", null, "98.0", 1);
        ExtractionResult ninetyNine = amount(second, "AWARD_AMOUNT", null, "99.00", 2);

        ContractCandidateDerivedFacts conflict = derive(primary, List.of(),
                List.of(source(first, 2, ninetyEight), source(second, 1, ninetyNine)));

        assertThat(conflict.awardAmount()).isNull();

        ExtractionResult equalScale = amount(second, "AWARD_AMOUNT", null, "98.00", 3);
        List<ContractCandidateFactPolicy.FactSource> equalSources = List.of(
                source(first, 2, ninetyEight), source(second, 1, equalScale));
        ContractCandidateDerivedFacts confirmed = derive(primary, List.of(), equalSources);

        assertThat(confirmed.awardAmount()).isEqualByComparingTo("98.00");
        assertThat(policy.authorizedFacts(primary, List.of(), equalSources,
                CandidateAuthorityMode.EXPLICIT_SUPPORT_AUTHORITY)).containsExactlyInAnyOrder(equalScale, ninetyEight);
    }

    @Test
    void explicitModeRejectsSupportPrimaryScalarsAndAllSupportLineItems() {
        ProjectFile primary = file("contract.pdf", "application/pdf");
        ProjectFile support = sibling(primary, "award.pdf");
        List<ExtractionResult> primaryFacts = List.of(
                text(primary, "CONTRACT_NO", null, "PRIMARY-1", 1),
                text(primary, "PARTY_B", null, "Primary Supplier", 2),
                amount(primary, "AMOUNT_NUMERIC", null, "100.00", 3),
                text(primary, "LINE_ITEM_KEY", "PRIMARY", "PRIMARY", 4),
                text(primary, "LINE_ITEM_NAME", "PRIMARY", "Primary line", 5));
        List<ExtractionResult> supportFacts = List.of(
                text(support, "CONTRACT_NO", null, "SUPPORT-NO", 6),
                text(support, "PARTY_B", null, "Support Supplier", 7),
                text(support, "CREDIT_CODE", null, "91310000SUPPORT", 8),
                amount(support, "AMOUNT_NUMERIC", null, "999.00", 9),
                text(support, "LINE_ITEM_KEY", "SUPPORT", "SUPPORT", 10),
                text(support, "LINE_ITEM_NAME", "SUPPORT", "Support line", 11),
                amount(support, "LINE_ITEM_QUANTITY", "SUPPORT", "7", 12),
                amount(support, "LINE_ITEM_UNIT_PRICE", "SUPPORT", "8", 13),
                amount(support, "LINE_ITEM_DECLARED_AMOUNT", "SUPPORT", "56", 14));

        ContractCandidateDerivedFacts derived = derive(primary, primaryFacts,
                List.of(source(support, 1, supportFacts.toArray(ExtractionResult[]::new))));

        assertThat(derived.contractNo()).isEqualTo("PRIMARY-1");
        assertThat(derived.partyB()).isEqualTo("Primary Supplier");
        assertThat(derived.contractAmount()).isEqualByComparingTo("100.00");
        assertThat(derived.lineItems()).singleElement().satisfies(line -> {
            assertThat(line.itemKey()).isEqualTo("PRIMARY");
            assertThat(line.itemName()).isEqualTo("Primary line");
        });
    }

    @Test
    void explicitModeAdmitsOnlyTheNonAwardPrimaryAllowListAndIgnoresPrimaryAwardFacts() {
        ProjectFile primary = file("contract.pdf", "application/pdf");
        List<ExtractionResult> primaryFacts = List.of(
                text(primary, "CONTRACT_NO", null, "C-1", 1),
                amount(primary, "AWARD_AMOUNT", null, "100.00", 2),
                text(primary, "AWARD_SUPPLIER", null, "Primary award supplier", 3),
                text(primary, "AWARD_SUPPLIER_CREDIT_CODE", null, "91310000PRIMARY", 4),
                text(primary, "UNRELATED", null, "must-not-leak", 5));

        ContractCandidateDerivedFacts derived = derive(primary, primaryFacts, List.of());

        assertThat(derived.contractNo()).isEqualTo("C-1");
        assertThat(derived.awardAmount()).isNull();
        assertThat(derived.awardSupplier()).isNull();
        assertThat(derived.awardSupplierCreditCode()).isNull();
    }

    @Test
    void explicitModeAdmitsEveryExactPrimaryAllowListFact() {
        ProjectFile primary = file("contract.pdf", "application/pdf");
        List<ExtractionResult> primaryFacts = List.of(
                text(primary, "CONTRACT_NO", null, "C-1", 1),
                text(primary, "CONTRACT_NAME", null, "Contract", 2),
                text(primary, "PARTY_A", null, "Buyer", 3),
                text(primary, "PARTY_B", null, "Supplier", 4),
                text(primary, "CREDIT_CODE", null, "91310000A", 5),
                amount(primary, "AMOUNT_NUMERIC", null, "100.00", 6),
                text(primary, "AMOUNT_UPPERCASE", null, "ONE HUNDRED", 7),
                text(primary, "LINE_ITEM_KEY", "ITEM", "ITEM", 8),
                text(primary, "LINE_ITEM_NAME", "ITEM", "Line", 9),
                amount(primary, "LINE_ITEM_QUANTITY", "ITEM", "2", 10),
                amount(primary, "LINE_ITEM_UNIT_PRICE", "ITEM", "50", 11),
                amount(primary, "LINE_ITEM_DECLARED_AMOUNT", "ITEM", "100", 12));

        ContractCandidateDerivedFacts derived = derive(primary, primaryFacts, List.of());

        assertThat(derived.contractNo()).isEqualTo("C-1");
        assertThat(derived.contractName()).isEqualTo("Contract");
        assertThat(derived.partyA()).isEqualTo("Buyer");
        assertThat(derived.partyB()).isEqualTo("Supplier");
        assertThat(derived.partyBCreditCode()).isEqualTo("91310000A");
        assertThat(derived.contractAmount()).isEqualByComparingTo("100.00");
        assertThat(derived.amountInWords()).isEqualTo("ONE HUNDRED");
        assertThat(derived.lineItems()).singleElement().satisfies(line -> {
            assertThat(line.itemKey()).isEqualTo("ITEM");
            assertThat(line.itemName()).isEqualTo("Line");
            assertThat(line.quantity()).isEqualByComparingTo("2");
            assertThat(line.unitPrice()).isEqualByComparingTo("50");
            assertThat(line.declaredAmount()).isEqualByComparingTo("100");
        });
    }

    @Test
    void explicitModeRejectsRecognizedPrimaryFactsFromAnotherSourceFile() {
        ProjectFile primary = file("contract.pdf", "application/pdf");
        ProjectFile foreign = sibling(primary, "foreign-contract.pdf");

        ContractCandidateDerivedFacts derived = derive(primary, List.of(
                text(foreign, "CONTRACT_NO", null, "FOREIGN-CONTRACT", 1),
                text(foreign, "LINE_ITEM_KEY", "FOREIGN", "FOREIGN", 2),
                text(foreign, "LINE_ITEM_NAME", "FOREIGN", "Foreign line", 3)), List.of());

        assertThat(derived.contractNo()).isNull();
        assertThat(derived.lineItems()).isEmpty();
    }

    @Test
    void explicitModeMapsEveryExactSupportAwardFact() {
        ProjectFile primary = file("contract.pdf", "application/pdf");
        ProjectFile support = sibling(primary, "award.pdf");

        ContractCandidateDerivedFacts derived = derive(primary, List.of(), List.of(source(support, 1,
                amount(support, "AWARD_AMOUNT", null, "98.00", 1),
                text(support, "AWARD_SUPPLIER", null, "Award Supplier", 2),
                text(support, "AWARD_SUPPLIER_CREDIT_CODE", null, "91310000B", 3))));

        assertThat(derived.awardAmount()).isEqualByComparingTo("98.00");
        assertThat(derived.awardSupplier()).isEqualTo("Award Supplier");
        assertThat(derived.awardSupplierCreditCode()).isEqualTo("91310000B");
    }

    @Test
    void supportNormalizedTextValuesCollapseOrConflictWithoutChangingOtherAwardFields() {
        ProjectFile primary = file("contract.pdf", "application/pdf");
        ProjectFile first = sibling(primary, "award-a.pdf");
        ProjectFile second = sibling(primary, "award-b.pdf");
        ContractCandidateDerivedFacts equal = derive(primary, List.of(), List.of(
                source(first, 1, normalizedText(first, "AWARD_SUPPLIER", "Supplier", "Supplier", 1),
                        normalizedText(first, "AWARD_SUPPLIER_CREDIT_CODE", "91310000B", "91310000B", 2)),
                source(second, 2, normalizedText(second, "AWARD_SUPPLIER", "SUPPLIER", "Supplier", 3),
                        normalizedText(second, "AWARD_SUPPLIER_CREDIT_CODE", "91310000B ", "91310000B", 4))));

        assertThat(equal.awardSupplier()).isEqualTo("Supplier");
        assertThat(equal.awardSupplierCreditCode()).isEqualTo("91310000B");

        ContractCandidateDerivedFacts conflict = derive(primary, List.of(), List.of(
                source(first, 1, text(first, "AWARD_SUPPLIER", null, "Supplier One", 5),
                        text(first, "AWARD_SUPPLIER_CREDIT_CODE", null, "91310000C", 6)),
                source(second, 2, text(second, "AWARD_SUPPLIER", null, "Supplier Two", 7),
                        text(second, "AWARD_SUPPLIER_CREDIT_CODE", null, "91310000D", 8))));

        assertThat(conflict.awardSupplier()).isNull();
        assertThat(conflict.awardSupplierCreditCode()).isNull();
    }

    @Test
    void supportFactSourceRequiresPositiveParseVersion() {
        ProjectFile primary = file("contract.pdf", "application/pdf");

        assertThatIllegalArgumentException().isThrownBy(() -> source(primary, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> source(primary, -1));
    }

    @Test
    void deterministicFactOrderingMakesReversedInputsProduceTheSameBusinessOutput() {
        ProjectFile primary = file("contract.pdf", "application/pdf");
        ProjectFile first = sibling(primary, "award-a.pdf");
        ProjectFile second = sibling(primary, "award-b.pdf");
        List<ExtractionResult> primaryFacts = List.of(
                text(primary, "LINE_ITEM_KEY", "SECOND", "SECOND", 1),
                text(primary, "LINE_ITEM_NAME", "SECOND", "Second", 2),
                text(primary, "LINE_ITEM_KEY", "FIRST", "FIRST", 3),
                text(primary, "LINE_ITEM_NAME", "FIRST", "First", 4),
                text(primary, "CONTRACT_NO", null, "C-1", 5));
        List<ContractCandidateFactPolicy.FactSource> sources = List.of(
                source(first, 2, amount(first, "AWARD_AMOUNT", null, "98.0", 6)),
                source(second, 1, amount(second, "AWARD_AMOUNT", null, "98.00", 7)));

        ContractCandidateDerivedFacts forward = derive(primary, primaryFacts, sources);
        ContractCandidateDerivedFacts reversed = derive(primary, reverseCopy(primaryFacts), reverseCopy(sources));

        assertThat(viewOf(reversed)).isEqualTo(viewOf(forward));
    }

    private ContractCandidateDerivedFacts derive(ProjectFile primary, List<ExtractionResult> primaryFacts,
            List<ContractCandidateFactPolicy.FactSource> supportSources) {
        return assembler.derive(UUID.randomUUID(), primary, primaryFacts, supportSources,
                CandidateAuthorityMode.EXPLICIT_SUPPORT_AUTHORITY);
    }

    private static ContractCandidateFactPolicy.FactSource source(ProjectFile file, int parseVersion,
            ExtractionResult... facts) {
        return new ContractCandidateFactPolicy.FactSource(DocumentRole.AWARD_NOTICE, file.id(), parseVersion,
                List.of(facts));
    }

    private static <T> List<T> reverseCopy(List<T> values) {
        List<T> reversed = new ArrayList<>(values);
        Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    private static CandidateBusinessView viewOf(ContractCandidateDerivedFacts derived) {
        return new CandidateBusinessView(derived.contractNo(), derived.contractName(), derived.partyA(), derived.partyB(),
                derived.partyBCreditCode(), derived.contractAmount(), derived.amountInWords(), derived.awardAmount(),
                derived.awardSupplier(), derived.awardSupplierCreditCode(), derived.lineItems().stream()
                        .map(line -> new LineBusinessView(line.itemKey(), line.itemName(), line.quantity(),
                                line.unitPrice(), line.declaredAmount(), line.sortNo()))
                        .toList());
    }

    private record CandidateBusinessView(String contractNo, String contractName, String partyA, String partyB,
            String partyBCreditCode, BigDecimal contractAmount, String amountInWords, BigDecimal awardAmount,
            String awardSupplier, String awardSupplierCreditCode, List<LineBusinessView> lineItems) { }

    private record LineBusinessView(String itemKey, String itemName, BigDecimal quantity, BigDecimal unitPrice,
            BigDecimal declaredAmount, int sortNo) { }

    private static ProjectFile sibling(ProjectFile primary, String filename) {
        return new ProjectFile(UUID.randomUUID(), primary.projectId(), primary.parentArchiveFileId(),
                primary.normalizedRelativePath(), filename, primary.mediaType(), primary.category(),
                primary.ingestionStatus(), primary.parseStatus(), primary.storageKey(), primary.sha256(),
                primary.sizeBytes(), primary.parsedText(), primary.safeReasonCode(), primary.createdAt(),
                primary.updatedAt(), primary.parseProvenance());
    }

    private static ExtractionResult text(ProjectFile source, String type, String group, String value, int index) {
        return fact(source, type, group, value, null, index);
    }

    private static ExtractionResult amount(ProjectFile source, String type, String group, String value, int index) {
        return fact(source, type, group, value, new BigDecimal(value), index);
    }

    private static ExtractionResult fact(ProjectFile source, String type, String group, String value,
            BigDecimal amount, int index) {
        return new ExtractionResult(UUID.randomUUID(), source.projectId(), source.id(), UUID.randomUUID(),
                String.format("%064x", index), "project-extraction-v1", type, group, value, value, amount,
                "bounded evidence", 1, null, "pdf/page:1/fact:" + index, fixedClock().instant());
    }

    private static ExtractionResult normalizedText(ProjectFile source, String type, String value,
            String normalized, int index) {
        return new ExtractionResult(UUID.randomUUID(), source.projectId(), source.id(), UUID.randomUUID(),
                String.format("%064x", index), "project-extraction-v1", type, null, value, normalized, null,
                "bounded evidence", 1, null, "pdf/page:1/fact:" + index, fixedClock().instant());
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC);
    }
}
