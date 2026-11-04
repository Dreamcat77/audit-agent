-- F10/F11: additive nullable column keeps previous releases' pre-review JSON readable.
ALTER TABLE PROCUREMENT_REVIEW_REPORT ADD REPORT_SNAPSHOT_JSON CLOB;
COMMENT ON COLUMN PROCUREMENT_REVIEW_REPORT.REPORT_SNAPSHOT_JSON IS '生成时完整报告及材料版本快照；旧报告不回填当前事实';
