-- DM8 instances initialized with byte semantics need four bytes per Unicode
-- character. Preserve up to 200-character subject names without truncation.
-- Controlled widening only; verify current definitions before applying.
ALTER TABLE BUDGET_REVIEW_ITEM MODIFY SUBJECT_NAME VARCHAR(800);
ALTER TABLE BUDGET_REVIEW_ITEM MODIFY APPROVAL_SUBJECT_NAME VARCHAR(800);
