package com.fuquan.audit.contract.application.project;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public record ConfirmedContractPayload(
        String contractNo,
        String contractName,
        String contractType,
        String partyA,
        String partyB,
        String partyBCreditCode,
        BigDecimal contractAmount,
        String amountInWords,
        List<ConfirmedLineItem> lineItems,
        ConfirmedAwardNotice awardNotice) {

    public ConfirmedContractPayload {
        contractNo = required(contractNo, "contractNo");
        contractName = required(contractName, "contractName");
        contractType = required(contractType, "contractType");
        partyA = required(partyA, "partyA");
        partyB = required(partyB, "partyB");
        partyBCreditCode = nullable(partyBCreditCode);
        Objects.requireNonNull(contractAmount, "contractAmount must not be null");
        if (contractAmount.signum() < 0) throw new IllegalArgumentException("contractAmount must be non-negative");
        amountInWords = nullable(amountInWords);
        lineItems = lineItems == null ? List.of() : List.copyOf(lineItems);
        java.util.Set<String> itemKeys = new java.util.HashSet<>();
        for (ConfirmedLineItem lineItem : lineItems) {
            if (!itemKeys.add(lineItem.itemKey())) {
                throw new IllegalArgumentException("duplicate line itemKey");
            }
        }
    }

    public record ConfirmedLineItem(String itemKey, String itemName,
                                    com.fuquan.audit.contract.domain.ContractLineItemBasis basis,
                                    BigDecimal quantity,
                                    BigDecimal unitPrice, BigDecimal declaredAmount, int sortNo) {
        public ConfirmedLineItem {
            itemKey = required(itemKey, "itemKey");
            itemName = required(itemName, "itemName");
            Objects.requireNonNull(basis, "basis must not be null");
            Objects.requireNonNull(declaredAmount, "declaredAmount must not be null");
            if (basis == com.fuquan.audit.contract.domain.ContractLineItemBasis.QUANTITY_PRICE
                    && (quantity == null || unitPrice == null)) {
                throw new IllegalArgumentException("quantity-price line requires quantity and unit price");
            }
            if (basis == com.fuquan.audit.contract.domain.ContractLineItemBasis.AMOUNT_ONLY
                    && (quantity != null || unitPrice != null)) {
                throw new IllegalArgumentException("amount-only line cannot contain quantity or unit price");
            }
            if ((quantity != null && quantity.signum() < 0) || (unitPrice != null && unitPrice.signum() < 0)
                    || declaredAmount.signum() < 0 || sortNo < 0) {
                throw new IllegalArgumentException("line item values must be non-negative");
            }
        }

        public ConfirmedLineItem(String itemKey, String itemName, BigDecimal quantity,
                BigDecimal unitPrice, BigDecimal declaredAmount, int sortNo) {
            this(itemKey, itemName,
                    com.fuquan.audit.contract.domain.ContractLineItemBasis.QUANTITY_PRICE,
                    quantity, unitPrice, declaredAmount, sortNo);
        }
    }

    public record ConfirmedAwardNotice(String noticeNo, String projectName, String winningBidder,
                                       String winningBidderCreditCode, BigDecimal winningAmount,
                                       LocalDate winningDate, String sourceRef) {
        public ConfirmedAwardNotice {
            noticeNo = required(noticeNo, "noticeNo");
            projectName = required(projectName, "projectName");
            winningBidder = required(winningBidder, "winningBidder");
            winningBidderCreditCode = nullable(winningBidderCreditCode);
            Objects.requireNonNull(winningAmount, "winningAmount must not be null");
            Objects.requireNonNull(winningDate, "winningDate must not be null");
            if (winningAmount.signum() < 0) throw new IllegalArgumentException("winningAmount must be non-negative");
            sourceRef = nullable(sourceRef);
        }
    }

    private static String required(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return trimmed;
    }

    private static String nullable(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
