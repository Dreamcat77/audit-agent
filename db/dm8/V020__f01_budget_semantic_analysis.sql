-- F01 category-attribution and budget semantic-analysis fields.
-- Apply after V019__f03_blacklist_operation_log.sql.
-- The category fields repair the historical F01 schema gap: current mappers
-- already read/write them, but V007/V014/V015 did not create them.

ALTER TABLE BUDGET_REVIEW_ITEM ADD CATEGORY_ATTRIBUTION_STATUS VARCHAR(16);
ALTER TABLE BUDGET_REVIEW_ITEM ADD CATEGORY_SUGGESTION VARCHAR(500);
ALTER TABLE BUDGET_REVIEW_ITEM ADD AI_SUGGESTED_CATEGORY VARCHAR(500);
ALTER TABLE BUDGET_REVIEW_ITEM ADD AI_HINT VARCHAR(2000);
