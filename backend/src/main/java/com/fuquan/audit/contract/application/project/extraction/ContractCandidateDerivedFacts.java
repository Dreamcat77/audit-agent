package com.fuquan.audit.contract.application.project.extraction;

import com.fuquan.audit.contract.domain.project.ContractCandidateLineItem;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Replaceable Candidate business facts; primary source authority remains on the aggregate. */
public record ContractCandidateDerivedFacts(
        String contractNo,
        String contractName,
        String contractType,
        String partyA,
        String partyB,
        String partyBCreditCode,
        BigDecimal contractAmount,
        String amountInWords,
        BigDecimal awardAmount,
        String awardSupplier,
        String awardSupplierCreditCode,
        List<ContractCandidateLineItem> lineItems) {

    public ContractCandidateDerivedFacts {
        lineItems = List.copyOf(Objects.requireNonNull(lineItems, "lineItems must not be null"));
    }
}
