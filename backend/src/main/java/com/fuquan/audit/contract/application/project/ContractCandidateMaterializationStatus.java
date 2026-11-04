package com.fuquan.audit.contract.application.project;

public enum ContractCandidateMaterializationStatus {
    NOT_APPLICABLE,
    MATERIALIZED,
    REPLAYED,
    ALREADY_PROMOTED,
    WAITING_FOR_PROJECT_IDENTITY,
    WAITING_FOR_FACT_EXTRACTION
}
