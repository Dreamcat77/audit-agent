-- MIGRATION-NAMESPACE: CONTRACT
-- MIGRATION-ID: contract-fact-batches
-- DEPENDS-ON: contract-fact-v2-audit
-- PURPOSE: Retain unknown model outcomes in existing immutable fact-call audit
-- EXECUTION: CONTROLLED_MANUAL
-- IDEMPOTENCY: NOT_ASSUMED

-- Existing positive ordinals remain valid. New application ordinals are global
-- across requests, so existing unique indexes also cover all bounded batches.
-- Verify the actual constraint definition before applying; never edit old audits.
ALTER TABLE CONTRACT_MODEL_CALL_AUDIT DROP CONSTRAINT CK_CONTRACT_MODEL_CALL_FACT_SCOPE;
ALTER TABLE CONTRACT_MODEL_CALL_AUDIT ADD CONSTRAINT CK_CONTRACT_MODEL_CALL_FACT_SCOPE CHECK (
    (FACT_EXTRACTION_ATTEMPT_ID IS NOT NULL
        AND SCOPE IN ('CORE','LINE_ITEMS','AWARD')
        AND BATCH_STATUS IN ('EMPTY','PARTIAL','COMPLETE','FAILED','UNKNOWN'))
    OR
    (REVIEW_STEP_ATTEMPT_ID IS NOT NULL AND SCOPE IS NULL AND BATCH_STATUS IS NULL)
);
