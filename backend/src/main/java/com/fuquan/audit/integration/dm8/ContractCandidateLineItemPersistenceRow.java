package com.fuquan.audit.integration.dm8;

import java.math.BigDecimal;

public record ContractCandidateLineItemPersistenceRow(
        String id, String projectId, String candidateId, String itemKey, String itemName,
        BigDecimal quantity, BigDecimal unitPrice, BigDecimal declaredAmount, int sortNo) {
}
