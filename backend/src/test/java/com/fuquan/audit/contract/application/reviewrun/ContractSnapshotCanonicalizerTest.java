package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuquan.audit.contract.domain.AwardNoticeReference;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.ContractLineItem;
import com.fuquan.audit.contract.domain.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContractSnapshotCanonicalizerTest {
    private final ContractSnapshotCanonicalizer canonicalizer = new ContractSnapshotCanonicalizer();
    private final UUID contractId = UUID.fromString("00000000-0000-0000-0000-000000000111");
    private final Instant now = Instant.parse("2026-09-15T00:00:00Z");

    @Test
    void normalizesUnicodeDecimalScaleAndLineOrdering() {
        var first = contract("100.00", List.of(line("Ｂ", 2, "5.00"), line("Ａ", 1, "20.000")));
        var second = contract("100", List.of(line("A", 1, "20"), line("B", 2, "5")));
        var a = canonicalizer.canonicalize(first);
        var b = canonicalizer.canonicalize(second);
        assertThat(a.canonicalJson()).isEqualTo(b.canonicalJson());
        assertThat(a.sha256()).isEqualTo(b.sha256()).matches("[0-9a-f]{64}");
        assertThat(a.canonicalJson()).contains("\"contractAmount\":\"100\"",
                "\"winningDate\":\"2026-09-15\"", "\"quantity\":\"1\"");
        assertThat(a.canonicalJson().indexOf("\"itemKey\":\"A\""))
                .isLessThan(a.canonicalJson().indexOf("\"itemKey\":\"B\""));
    }

    @Test
    void changingVersionOrAwardFactsChangesFrozenHash() {
        var original = canonicalizer.canonicalize(contract("100", List.of(line("A", 1, "20"))));
        var changedVersion = canonicalizer.canonicalize(withVersion(contract("100", List.of(line("A", 1, "20"))), 1));
        var changedAward = canonicalizer.canonicalize(withoutAward(contract("100", List.of(line("A", 1, "20")))));
        assertThat(changedVersion.sha256()).isNotEqualTo(original.sha256());
        assertThat(changedAward.sha256()).isNotEqualTo(original.sha256());
    }

    @Test
    void snapshotV2PreservesAmountOnlyBasisAndNullQuantityPrice() {
        ContractLineItem amountOnly = new ContractLineItem(
                UUID.fromString("00000000-0000-0000-0000-000000000119"), contractId,
                "SERVICE-1", "服务一", com.fuquan.audit.contract.domain.ContractLineItemBasis.AMOUNT_ONLY,
                null, null, Money.of(new BigDecimal("28000")), 1);

        var snapshot = canonicalizer.canonicalize(contract("28000", List.of(amountOnly)));

        assertThat(snapshot.canonicalJson()).contains(
                "\"schemaVersion\":2",
                "\"basis\":\"AMOUNT_ONLY\"",
                "\"quantity\":null",
                "\"unitPrice\":null",
                "\"declaredAmount\":\"28000\"");
    }

    private Contract contract(String amount, List<ContractLineItem> lines) {
        var award = new AwardNoticeReference(UUID.fromString("00000000-0000-0000-0000-000000000112"),
                contractId, " Ｎ－１ ", " Project ", " Supplier ", null, Money.of(new BigDecimal("100.00")),
                LocalDate.of(2026, 9, 15), null);
        return new Contract(contractId, " Ｃ－１ ", " Test Contract ", "GOODS", " Party A ", " Party B ",
                null, Money.of(new BigDecimal(amount)), null, "READY_FOR_REVIEW", 0, now, now,
                lines, award);
    }

    private ContractLineItem line(String key, int order, String price) {
        return new ContractLineItem(UUID.fromString("00000000-0000-0000-0000-00000000011" + order),
                contractId, key, "Line " + key, BigDecimal.ONE, new BigDecimal(price), Money.of(new BigDecimal(price)), order);
    }

    private Contract withVersion(Contract c, long version) {
        return new Contract(c.id(), c.contractNo(), c.contractName(), c.contractType(), c.partyA(), c.partyB(),
                c.partyBCreditCode(), c.contractAmount(), c.amountInWords(), c.status(), version, c.createdAt(),
                c.updatedAt(), c.lineItems(), c.awardNotice());
    }

    private Contract withoutAward(Contract c) {
        return new Contract(c.id(), c.contractNo(), c.contractName(), c.contractType(), c.partyA(), c.partyB(),
                c.partyBCreditCode(), c.contractAmount(), c.amountInWords(), c.status(), c.versionNo(), c.createdAt(),
                c.updatedAt(), c.lineItems(), null);
    }
}
