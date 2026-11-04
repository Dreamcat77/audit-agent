package com.fuquan.audit.integration.dm8;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record ContractAwardNoticePersistenceRow(
        String id,
        String contractId,
        String noticeNo,
        String projectName,
        String winningBidder,
        String winningBidderCreditCode,
        BigDecimal winningAmount,
        LocalDate winningDate,
        String sourceRef,
        LocalDateTime createdAt) {
}
