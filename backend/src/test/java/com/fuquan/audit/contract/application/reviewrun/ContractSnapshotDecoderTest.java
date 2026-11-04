package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.domain.AwardNoticeReference;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.ContractLineItem;
import com.fuquan.audit.contract.domain.Money;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContractSnapshotDecoderTest {
    private static final UUID CONTRACT_ID = UUID.fromString("11000000-0000-0000-0000-000000000001");
    private static final UUID LINE_ID = UUID.fromString("11000000-0000-0000-0000-000000000002");
    private static final UUID AWARD_ID = UUID.fromString("11000000-0000-0000-0000-000000000003");
    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");
    private final ContractSnapshotCanonicalizer canonicalizer = new ContractSnapshotCanonicalizer();
    private final ContractSnapshotDecoder decoder = new ContractSnapshotDecoder(canonicalizer);

    @Test
    void decodesFrozenContractWithoutCurrentRepositoryAndPreservesExactBusinessIdsAndAmounts() {
        var snapshot = canonicalizer.canonicalize(contract());
        Contract decoded = decoder.decodeAndVerify(run(snapshot.canonicalJson(), snapshot.sha256()));
        assertThat(decoded.id()).isEqualTo(CONTRACT_ID);
        assertThat(decoded.versionNo()).isEqualTo(7);
        assertThat(decoded.contractAmount().amount()).isEqualByComparingTo(new BigDecimal("100.25"));
        assertThat(decoded.lineItems()).extracting(ContractLineItem::id).containsExactly(LINE_ID);
        assertThat(decoded.lineItems().get(0).contractId()).isEqualTo(CONTRACT_ID);
        assertThat(decoded.lineItems().get(0).quantity()).isEqualByComparingTo(new BigDecimal("2.5"));
        assertThat(decoded.awardNotice().id()).isEqualTo(AWARD_ID);
        assertThat(decoded.awardNotice().contractId()).isEqualTo(CONTRACT_ID);
        assertThat(decoded.awardNotice().winningAmount().amount())
                .isEqualByComparingTo(new BigDecimal("100.25"));
    }

    @Test
    void rejectsHashMismatchBeforeParsing() {
        String malformed = "not-json";
        assertConflict(run(malformed, "a".repeat(64)));
    }

    @Test
    void rejectsUnknownSchemaEvenWhenItsHashIsCorrect() {
        String json = canonicalizer.canonicalize(contract()).canonicalJson()
                .replace("\"schemaVersion\":2", "\"schemaVersion\":99");
        assertConflict(run(json, ContractSnapshotCanonicalizer.sha256(json)));
    }

    @Test
    void reopensAmountOnlySnapshotV2AndLegacyQuantityPriceSnapshotV1() {
        ContractLineItem amountOnly = new ContractLineItem(LINE_ID, CONTRACT_ID, "A", "服务",
                com.fuquan.audit.contract.domain.ContractLineItemBasis.AMOUNT_ONLY,
                null, null, Money.of(new BigDecimal("100.25")), 1);
        Contract v2 = new Contract(CONTRACT_ID, "C-1", "合同", "SERVICES", "甲方", "乙方", null,
                Money.of(new BigDecimal("100.25")), null, "READY_FOR_REVIEW", 7,
                NOW, NOW, List.of(amountOnly), contract().awardNotice());
        var snapshotV2 = canonicalizer.canonicalize(v2);
        Contract reopenedV2 = decoder.decodeAndVerify(run(snapshotV2.canonicalJson(), snapshotV2.sha256()));
        assertThat(reopenedV2.lineItems().get(0).basis().name()).isEqualTo("AMOUNT_ONLY");
        assertThat(reopenedV2.lineItems().get(0).quantity()).isNull();
        assertThat(reopenedV2.lineItems().get(0).unitPrice()).isNull();

        var snapshotV1 = canonicalizer.canonicalizeLegacyV1(contract());
        Contract reopenedV1 = decoder.decodeAndVerify(run(snapshotV1.canonicalJson(), snapshotV1.sha256()));
        assertThat(reopenedV1.lineItems().get(0).basis().name()).isEqualTo("QUANTITY_PRICE");
    }

    @Test
    void preservesFourDecimalQuantityAndUnitPriceAcrossFrozenSnapshotRoundTrip() {
        ContractLineItem precise = new ContractLineItem(LINE_ID, CONTRACT_ID, "P", "精密服务",
                new BigDecimal("100.0000"), new BigDecimal("2.3456"),
                Money.of(new BigDecimal("234.56")), 1);
        Contract source = new Contract(CONTRACT_ID, "C-1", "合同", "SERVICES", "甲方", "乙方", null,
                Money.of(new BigDecimal("234.56")), null, "READY_FOR_REVIEW", 7,
                NOW, NOW, List.of(precise), contract().awardNotice());

        var snapshot = canonicalizer.canonicalize(source);
        Contract reopened = decoder.decodeAndVerify(run(snapshot.canonicalJson(), snapshot.sha256()));

        assertThat(reopened.lineItems()).singleElement().satisfies(line -> {
            assertThat(line.quantity()).isEqualByComparingTo("100.0000");
            assertThat(line.unitPrice()).isEqualByComparingTo("2.3456");
            assertThat(line.declaredAmount().amount()).isEqualByComparingTo("234.56");
        });
    }

    @Test
    void rejectsMalformedOrExtendedSnapshotWithCorrectHash() {
        String malformed = "{\"schemaVersion\":1";
        assertConflict(run(malformed, ContractSnapshotCanonicalizer.sha256(malformed)));
        String original = canonicalizer.canonicalize(contract()).canonicalJson();
        String extended = original.substring(0, original.length() - 1) + ",\"trustedAmount\":\"1\"}";
        assertConflict(run(extended, ContractSnapshotCanonicalizer.sha256(extended)));
    }

    @Test
    void rejectsSnapshotContractIdDifferentFromFrozenRunContractId() {
        String original = canonicalizer.canonicalize(contract()).canonicalJson();
        String foreign = UUID.fromString("11000000-0000-0000-0000-000000000004").toString();
        String differentContract = original.replace("\"contractId\":\"" + CONTRACT_ID + "\"",
                "\"contractId\":\"" + foreign + "\"");
        assertConflict(run(differentContract, ContractSnapshotCanonicalizer.sha256(differentContract)));
    }

    private void assertConflict(ContractReviewRun run) {
        assertThatThrownBy(() -> decoder.decodeAndVerify(run))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(BusinessErrorCode.CONFLICT));
    }

    private static Contract contract() {
        var line = new ContractLineItem(LINE_ID, CONTRACT_ID, "A", "设备", new BigDecimal("2.50"),
                new BigDecimal("40.10"), Money.of(new BigDecimal("100.25")), 1);
        var award = new AwardNoticeReference(AWARD_ID, CONTRACT_ID, "N-1", "项目", "供应商", null,
                Money.of(new BigDecimal("100.25")), LocalDate.of(2026, 9, 15), null);
        return new Contract(CONTRACT_ID, "C-1", "合同", "GOODS", "甲方", "乙方", null,
                Money.of(new BigDecimal("100.25")), null, "READY_FOR_REVIEW", 7,
                NOW, NOW, List.of(line), award);
    }

    private static ContractReviewRun run(String json, String sha) {
        return new ContractReviewRun(UUID.fromString("11000000-0000-0000-0000-000000000005"),
                UUID.fromString("11000000-0000-0000-0000-000000000006"), CONTRACT_ID, 7,
                null, null, 1, json, sha, "profile-v1", "rule-v1", "model-v1", "b".repeat(64),
                "actor-1", ContractReviewRunStatus.PENDING, NOW, null, null, null, null, null, 0);
    }
}
