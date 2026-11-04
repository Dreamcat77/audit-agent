package com.fuquan.audit.integration.dm8;

import java.math.BigDecimal;

public record ContractReviewClauseValuePersistenceRow(String valueId, String comparisonId, String runId,
        String sideKey, String sourceFileId, String state, String scenarioKey,
        String responsibleParty, String calculationBasis, BigDecimal rateValue, String rateUnit,
        String frequency, String periodValue, BigDecimal capRate, BigDecimal capAmount,
        String terminationThreshold, String dateType, String normalizedDate, String termStart,
        String durationValue, String effectiveCondition, String terminationCondition,
        String evidenceId) { }
