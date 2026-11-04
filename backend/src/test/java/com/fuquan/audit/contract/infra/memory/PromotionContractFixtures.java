package com.fuquan.audit.contract.infra.memory;

import com.fuquan.audit.contract.domain.AwardNoticeReference;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.ContractLineItem;
import com.fuquan.audit.contract.domain.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class PromotionContractFixtures {
    private PromotionContractFixtures() { }

    public static Contract contract(UUID id, String contractNo) {
        Instant now = Instant.parse("2026-08-26T00:00:00Z");
        ContractLineItem line = new ContractLineItem(UUID.randomUUID(), id, "ITEM-1", "Server",
                new BigDecimal("2"), new BigDecimal("50.00"), Money.of(new BigDecimal("100.00")), 1);
        AwardNoticeReference award = new AwardNoticeReference(UUID.randomUUID(), id, "AWARD-1", "Project",
                "Supplier", "913100000000000001", Money.of(new BigDecimal("100.00")),
                LocalDate.of(2026, 8, 20), null);
        return new Contract(id, contractNo, "Confirmed Contract", "GOODS", "Buyer", "Supplier",
                "913100000000000001", Money.of(new BigDecimal("100.00")), "壹佰元整",
                "READY_FOR_REVIEW", 0, now, now, List.of(line), award);
    }
}
