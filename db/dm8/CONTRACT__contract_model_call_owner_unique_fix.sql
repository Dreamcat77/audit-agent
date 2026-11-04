-- MIGRATION-NAMESPACE: CONTRACT
-- MIGRATION-ID: contract-model-call-owner-unique-fix
-- DEPENDS-ON: contract-fact-v2-audit
-- PURPOSE: make model-call uniqueness owner-aware on DM8 where NULL participates in UNIQUE conflicts
-- EXECUTION: CONTROLLED_MANUAL
-- IDEMPOTENCY: NOT_ASSUMED

-- The original pair of owner-specific constraints each contains the other
-- owner's nullable column. Representative DM8 treats that NULL as conflicting,
-- so the second fact extraction attempt can collide with the review-owner key.
-- The existing exact-one-owner check makes one composite key unambiguous.
ALTER TABLE CONTRACT_MODEL_CALL_AUDIT
    DROP CONSTRAINT UK_CONTRACT_MODEL_CALL_FACT;

ALTER TABLE CONTRACT_MODEL_CALL_AUDIT
    DROP CONSTRAINT UK_CONTRACT_MODEL_CALL_REVIEW;

ALTER TABLE CONTRACT_MODEL_CALL_AUDIT ADD
    CONSTRAINT UK_CONTRACT_MODEL_CALL_OWNER_CALL
    UNIQUE (FACT_EXTRACTION_ATTEMPT_ID, REVIEW_STEP_ATTEMPT_ID,
        ATTEMPT_NO, SCOPE, CALL_ORDINAL);
