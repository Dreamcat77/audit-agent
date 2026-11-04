-- MIGRATION-NAMESPACE: CONTRACT
-- MIGRATION-ID: contract-final-closeout
-- DEPENDS-ON: V003__f02_contract_review.sql,contract-candidate-confirmation,review-run-results
-- PURPOSE: formal amount-only Contract lines for the approved final closeout
-- EXECUTION: CONTROLLED_MANUAL
-- IDEMPOTENCY: NOT_ASSUMED

-- Historical rows were created under the quantity-price-only invariant. The
-- default preserves that meaning while new writes state their basis explicitly.
ALTER TABLE CONTRACT_LINE_ITEM ADD BASIS VARCHAR(32) DEFAULT 'QUANTITY_PRICE' NOT NULL;

-- Replace the historical all-three-non-null check with the two approved shapes.
ALTER TABLE CONTRACT_LINE_ITEM DROP CONSTRAINT CK_CONTRACT_LINE_VALUES;
ALTER TABLE CONTRACT_LINE_ITEM MODIFY QUANTITY DECIMAL(18,4) NULL;
ALTER TABLE CONTRACT_LINE_ITEM MODIFY UNIT_PRICE DECIMAL(18,4) NULL;
ALTER TABLE CONTRACT_LINE_ITEM ADD
    CONSTRAINT CK_CONTRACT_LINE_BASIS CHECK (
        DECLARED_AMOUNT >= 0
        AND BASIS IN ('QUANTITY_PRICE', 'AMOUNT_ONLY')
        AND (
            (BASIS = 'QUANTITY_PRICE' AND QUANTITY IS NOT NULL AND UNIT_PRICE IS NOT NULL
                AND QUANTITY >= 0 AND UNIT_PRICE >= 0)
            OR
            (BASIS = 'AMOUNT_ONLY' AND QUANTITY IS NULL AND UNIT_PRICE IS NULL)
        )
    );

COMMENT ON COLUMN CONTRACT_LINE_ITEM.BASIS IS
    'QUANTITY_PRICE uses quantity and unit price; AMOUNT_ONLY preserves a declared amount only.';
COMMENT ON COLUMN CONTRACT_LINE_ITEM.QUANTITY IS
    'Quantity is NULL for AMOUNT_ONLY and must never be fabricated as 1 or 0.';
COMMENT ON COLUMN CONTRACT_LINE_ITEM.UNIT_PRICE IS
    'Unit price is NULL for AMOUNT_ONLY and must never be inferred from declared amount.';
