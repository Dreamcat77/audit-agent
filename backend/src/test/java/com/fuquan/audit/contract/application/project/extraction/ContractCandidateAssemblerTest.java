package com.fuquan.audit.contract.application.project.extraction;

import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.CandidatePromotionState;
import com.fuquan.audit.contract.domain.project.CandidateAuthorityMode;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static com.fuquan.audit.contract.application.project.parse.ProjectDocumentParserTest.file;
import static org.assertj.core.api.Assertions.assertThat;

class ContractCandidateAssemblerTest {
    @Test
    void canonicalLineOrderUsesNumericOffsetsAcrossDigitBoundariesNotLexicalUuidLocators() {
        ProjectFile source = file("contract.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        String parse = "57000000-0000-0000-0000-000000000003";
        var candidate = new ContractCandidateAssembler(fixedClock()).assemble(source.projectId(), source, List.of(
                canonicalLine(source, parse, "L1000", 1000), canonicalLine(source, parse, "L100", 100),
                canonicalLine(source, parse, "L999", 999), canonicalLine(source, parse, "L90", 90)));
        assertThat(candidate.lineItems()).extracting(line -> line.itemName())
                .containsExactly("L90", "L100", "L999", "L1000");
        assertThat(candidate.lineItems()).extracting(line -> line.sortNo()).containsExactly(0, 1, 2, 3);
    }

    private static ExtractionResult canonicalLine(ProjectFile source, String parse, String group, int start) {
        return new ExtractionResult(UUID.randomUUID(), source.projectId(), source.id(), UUID.fromString(parse),
                null, "%064x".formatted(start), "contract-source-table-v1", "LINE_ITEM_NAME", group,
                group, group, null, group, null, null,
                "parse-result:" + parse + ":canonical-codepoint:" + start + "-" + (start + 4), fixedClock().instant());
    }

    @Test
    void procurementRoleAndPackageFactsCannotPopulateContractCandidateFields() {
        ProjectFile source = file("procurement.pdf", "application/pdf");
        List<ExtractionResult> facts = List.of(
                text(source, "PURCHASER", null, "Buyer University", 1),
                text(source, "PROCUREMENT_AGENT", null, "Tender Agent", 2),
                amount(source, "BUDGET_AMOUNT", "PACKAGE:A", "250000", 3),
                amount(source, "CONTROL_PRICE", "PACKAGE:A", "240000", 4),
                text(source, "PACKAGE_SCOPE", "PACKAGE:A", "Imported equipment", 5));

        ContractCandidate candidate = new ContractCandidateAssembler(fixedClock())
                .assemble(source.projectId(), source, facts);

        assertThat(candidate.contractNo()).isNull();
        assertThat(candidate.contractName()).isNull();
        assertThat(candidate.contractType()).isNull();
        assertThat(candidate.partyA()).isNull();
        assertThat(candidate.partyB()).isNull();
        assertThat(candidate.partyBCreditCode()).isNull();
        assertThat(candidate.contractAmount()).isNull();
        assertThat(candidate.amountInWords()).isNull();
        assertThat(candidate.awardAmount()).isNull();
        assertThat(candidate.awardSupplier()).isNull();
        assertThat(candidate.awardSupplierCreditCode()).isNull();
        assertThat(candidate.lineItems()).isEmpty();
    }

    @Test
    void assemblesNullableCandidateAndRealLineItemsWithoutHidingConflicts() {
        ProjectFile source = file("contract.pdf", "application/pdf");
        List<ExtractionResult> facts = List.of(
                text(source, "CONTRACT_NO", null, "C-1", 1),
                text(source, "CONTRACT_NO", null, "C-2", 2),
                text(source, "CONTRACT_NAME", null, "Equipment", 3),
                text(source, "PARTY_A", null, "Buyer", 4),
                text(source, "PARTY_B", null, "Seller", 5),
                amount(source, "AMOUNT_NUMERIC", null, "120.00", 6),
                text(source, "AMOUNT_UPPERCASE", null, "ONE HUNDRED TWENTY", 7),
                text(source, "LINE_ITEM_KEY", "ITEM-1", "ITEM-1", 8),
                text(source, "LINE_ITEM_NAME", "ITEM-1", "Server", 9),
                amount(source, "LINE_ITEM_QUANTITY", "ITEM-1", "2", 10),
                amount(source, "LINE_ITEM_UNIT_PRICE", "ITEM-1", "60.00", 11),
                amount(source, "LINE_ITEM_DECLARED_AMOUNT", "ITEM-1", "120.00", 12));

        ContractCandidate candidate = new ContractCandidateAssembler(fixedClock())
                .assemble(source.projectId(), source, facts);

        assertThat(candidate.contractNo()).isNull();
        assertThat(candidate.contractName()).isEqualTo("Equipment");
        assertThat(candidate.partyA()).isEqualTo("Buyer");
        assertThat(candidate.partyB()).isEqualTo("Seller");
        assertThat(candidate.contractAmount()).isEqualByComparingTo("120.00");
        assertThat(candidate.amountInWords()).isEqualTo("ONE HUNDRED TWENTY");
        assertThat(candidate.promotionState()).isEqualTo(CandidatePromotionState.DRAFT);
        assertThat(candidate.versionNo()).isZero();
        assertThat(candidate.promotedContractId()).isNull();
        assertThat(candidate.promotedBy()).isNull();
        assertThat(candidate.promotedAt()).isNull();
        assertThat(candidate.lineItems()).singleElement().satisfies(line -> {
            assertThat(line.itemKey()).isEqualTo("ITEM-1");
            assertThat(line.itemName()).isEqualTo("Server");
            assertThat(line.quantity()).isEqualByComparingTo("2");
            assertThat(line.unitPrice()).isEqualByComparingTo("60.00");
            assertThat(line.declaredAmount()).isEqualByComparingTo("120.00");
            assertThat(line.sortNo()).isZero();
        });
    }

    @Test
    void primaryOnlyCompatibilityProjectionPreservesEveryCandidateFieldAndLineItem() {
        ProjectFile source = file("contract.pdf", "application/pdf");
        List<ExtractionResult> facts = List.of(
                text(source, "CONTRACT_NO", null, "C-1", 1),
                text(source, "CONTRACT_NAME", null, "Equipment", 2),
                text(source, "PARTY_A", null, "Buyer", 3),
                text(source, "PARTY_B", null, "Seller", 4),
                text(source, "CREDIT_CODE", null, "91310000A", 5),
                amount(source, "AMOUNT_NUMERIC", null, "120.00", 6),
                text(source, "AMOUNT_UPPERCASE", null, "ONE HUNDRED TWENTY", 7),
                amount(source, "AWARD_AMOUNT", null, "118.00", 8),
                text(source, "AWARD_SUPPLIER", null, "Award Seller", 9),
                text(source, "AWARD_SUPPLIER_CREDIT_CODE", null, "91310000B", 10),
                text(source, "LINE_ITEM_KEY", "ITEM-1", "ITEM-1", 11),
                text(source, "LINE_ITEM_NAME", "ITEM-1", "Server", 12),
                amount(source, "LINE_ITEM_QUANTITY", "ITEM-1", "2", 13),
                amount(source, "LINE_ITEM_UNIT_PRICE", "ITEM-1", "60.00", 14),
                amount(source, "LINE_ITEM_DECLARED_AMOUNT", "ITEM-1", "120.00", 15));

        ContractCandidate legacy = new ContractCandidateAssembler(fixedClock())
                .assemble(source.projectId(), source, facts);
        ContractCandidateDerivedFacts projection = new ContractCandidateAssembler(fixedClock())
                .derive(UUID.randomUUID(), source, facts, List.of(), CandidateAuthorityMode.PRIMARY_ONLY_COMPAT);

        assertThat(legacy.contractNo()).isEqualTo("C-1");
        assertThat(legacy.contractName()).isEqualTo("Equipment");
        assertThat(legacy.contractType()).isNull();
        assertThat(legacy.partyA()).isEqualTo("Buyer");
        assertThat(legacy.partyB()).isEqualTo("Seller");
        assertThat(legacy.partyBCreditCode()).isEqualTo("91310000A");
        assertThat(legacy.contractAmount()).isEqualByComparingTo("120.00");
        assertThat(legacy.amountInWords()).isEqualTo("ONE HUNDRED TWENTY");
        assertThat(legacy.awardAmount()).isEqualByComparingTo("118.00");
        assertThat(legacy.awardSupplier()).isEqualTo("Award Seller");
        assertThat(legacy.awardSupplierCreditCode()).isEqualTo("91310000B");
        assertThat(legacy.lineItems()).singleElement().satisfies(line -> {
            assertThat(line.itemKey()).isEqualTo("ITEM-1");
            assertThat(line.itemName()).isEqualTo("Server");
            assertThat(line.quantity()).isEqualByComparingTo("2");
            assertThat(line.unitPrice()).isEqualByComparingTo("60.00");
            assertThat(line.declaredAmount()).isEqualByComparingTo("120.00");
            assertThat(line.sortNo()).isZero();
        });
        assertThat(projection.contractNo()).isEqualTo(legacy.contractNo());
        assertThat(projection.contractName()).isEqualTo(legacy.contractName());
        assertThat(projection.contractType()).isEqualTo(legacy.contractType());
        assertThat(projection.partyA()).isEqualTo(legacy.partyA());
        assertThat(projection.partyB()).isEqualTo(legacy.partyB());
        assertThat(projection.partyBCreditCode()).isEqualTo(legacy.partyBCreditCode());
        assertThat(projection.contractAmount()).isEqualByComparingTo(legacy.contractAmount());
        assertThat(projection.amountInWords()).isEqualTo(legacy.amountInWords());
        assertThat(projection.awardAmount()).isEqualByComparingTo(legacy.awardAmount());
        assertThat(projection.awardSupplier()).isEqualTo(legacy.awardSupplier());
        assertThat(projection.awardSupplierCreditCode()).isEqualTo(legacy.awardSupplierCreditCode());
        assertThat(projection.lineItems()).singleElement().satisfies(line -> {
            assertThat(line.itemKey()).isEqualTo("ITEM-1");
            assertThat(line.itemName()).isEqualTo("Server");
            assertThat(line.quantity()).isEqualByComparingTo("2");
            assertThat(line.unitPrice()).isEqualByComparingTo("60.00");
            assertThat(line.declaredAmount()).isEqualByComparingTo("120.00");
            assertThat(line.sortNo()).isZero();
        });
    }

    @Test
    void preservesFourDecimalQuantityAndUnitPriceInCandidateAssembly() {
        ProjectFile source = file("contract.pdf", "application/pdf");
        List<ExtractionResult> facts = List.of(
                text(source, "LINE_ITEM_NAME", "ITEM-P", "精密服务", 1),
                amount(source, "LINE_ITEM_QUANTITY", "ITEM-P", "100.0000", 2),
                amount(source, "LINE_ITEM_UNIT_PRICE", "ITEM-P", "2.3456", 3),
                amount(source, "LINE_ITEM_DECLARED_AMOUNT", "ITEM-P", "234.56", 4));

        ContractCandidate candidate = new ContractCandidateAssembler(fixedClock())
                .assemble(source.projectId(), source, facts);

        assertThat(candidate.lineItems()).singleElement().satisfies(line -> {
            assertThat(line.quantity()).isEqualByComparingTo("100.0000");
            assertThat(line.unitPrice()).isEqualByComparingTo("2.3456");
            assertThat(line.declaredAmount()).isEqualByComparingTo("234.56");
        });
    }

    @Test
    void primaryOnlyCompatibilityKeepsLegacyForeignSourceFactsAndExtraLineItemGroupsInInputOrder() {
        ProjectFile source = file("contract.pdf", "application/pdf");
        ProjectFile legacyFactSource = new ProjectFile(UUID.randomUUID(), source.projectId(), source.parentArchiveFileId(),
                source.normalizedRelativePath(), source.originalFilename(), source.mediaType(), source.category(),
                source.ingestionStatus(), source.parseStatus(), source.storageKey(), source.sha256(), source.sizeBytes(),
                source.parsedText(), source.safeReasonCode(), source.createdAt(), source.updatedAt(), source.parseProvenance());
        List<ExtractionResult> facts = List.of(
                text(legacyFactSource, "CONTRACT_NO", null, "LEGACY-FOREIGN-SOURCE", 4),
                text(legacyFactSource, "LINE_ITEM_LEGACY_NOTE", "LEGACY", "legacy row", 1),
                text(legacyFactSource, "LINE_ITEM_KEY", "CURRENT", "CURRENT", 2),
                text(legacyFactSource, "LINE_ITEM_NAME", "CURRENT", "Current row", 3));

        ContractCandidate candidate = new ContractCandidateAssembler(fixedClock())
                .assemble(source.projectId(), source, facts);

        assertThat(candidate.contractNo()).isEqualTo("LEGACY-FOREIGN-SOURCE");
        assertThat(candidate.lineItems()).hasSize(2);
        assertThat(candidate.lineItems().get(0).itemKey()).isNull();
        assertThat(candidate.lineItems().get(0).itemName()).isNull();
        assertThat(candidate.lineItems().get(0).sortNo()).isZero();
        assertThat(candidate.lineItems().get(1).itemKey()).isEqualTo("CURRENT");
        assertThat(candidate.lineItems().get(1).itemName()).isEqualTo("Current row");
        assertThat(candidate.lineItems().get(1).sortNo()).isEqualTo(1);
    }

    private static ExtractionResult text(ProjectFile source, String type, String group, String value, int index) {
        return fact(source, type, group, value, null, index);
    }

    private static ExtractionResult amount(ProjectFile source, String type, String group, String value, int index) {
        return fact(source, type, group, value, new BigDecimal(value), index);
    }

    private static ExtractionResult fact(ProjectFile source, String type, String group, String value,
                                         BigDecimal amount, int index) {
        return new ExtractionResult(UUID.randomUUID(), source.projectId(), source.id(), null,
                String.format("%064x", index), "project-extraction-v1", type, group, value, value,
                amount, "bounded evidence", 1, null, "pdf/page:1/fact:" + index, fixedClock().instant());
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC);
    }
}
