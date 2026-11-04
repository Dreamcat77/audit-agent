-- F01 AI semantic-analysis provenance.
-- Apply after V020__f01_budget_semantic_analysis.sql.
-- These fields are advisory metadata only and never participate in F01
-- deterministic status, risk, or confirmation decisions.

ALTER TABLE BUDGET_REVIEW_ITEM ADD AI_MODEL VARCHAR(128);
ALTER TABLE BUDGET_REVIEW_ITEM ADD AI_DURATION_MS BIGINT;
