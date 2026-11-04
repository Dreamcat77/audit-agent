package com.fuquan.audit.contract.domain;

import com.fuquan.audit.review.domain.RiskLevel;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

final class F02RuleTestFixtures {

    static final UUID CONTRACT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

    static Contract contract(
            String amount,
            String words,
            List<ContractLineItem> items,
            AwardNoticeReference award,
            String partyB,
            String partyBCode) {
        Instant now = Instant.parse("2026-08-17T00:00:00Z");
        return new Contract(
                CONTRACT_ID, "C-1", "Contract", "PROCUREMENT", "Buyer", partyB, partyBCode,
                Money.of(new BigDecimal(amount)), words, "READY", 2, now, now, items, award);
    }

    static ContractLineItem item(String key, String quantity, String unitPrice, String declared) {
        return new ContractLineItem(
                UUID.nameUUIDFromBytes(key.getBytes()), CONTRACT_ID, key, key,
                new BigDecimal(quantity), new BigDecimal(unitPrice), Money.of(new BigDecimal(declared)), 1);
    }

    static AwardNoticeReference award(String amount, String supplier, String creditCode) {
        return new AwardNoticeReference(
                UUID.fromString("20000000-0000-0000-0000-000000000001"), CONTRACT_ID,
                "A-1", "Project", supplier, creditCode, Money.of(new BigDecimal(amount)),
                LocalDate.of(2026, 8, 1), "test");
    }

    static ContractRuleConfiguration config(String code) {
        return new ContractRuleConfiguration(
                code, code, true, RiskLevel.RED, true, 7, "test", Instant.parse("2026-08-17T00:00:00Z"));
    }

    private F02RuleTestFixtures() {
    }
}
