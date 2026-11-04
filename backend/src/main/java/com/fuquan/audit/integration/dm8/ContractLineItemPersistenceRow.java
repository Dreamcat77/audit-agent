package com.fuquan.audit.integration.dm8;

import java.math.BigDecimal;

public record ContractLineItemPersistenceRow(
        String id,
        String contractId,
        String itemKey,
        String itemName,
        String basis,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal declaredAmount,
        int sortNo) {
    public ContractLineItemPersistenceRow(String id, String contractId, String itemKey, String itemName,
            BigDecimal quantity, BigDecimal unitPrice, BigDecimal declaredAmount, int sortNo) {
        this(id, contractId, itemKey, itemName, "QUANTITY_PRICE", quantity, unitPrice, declaredAmount, sortNo);
    }
}
