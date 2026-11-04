-- MIGRATION-NAMESPACE: CONTRACT
-- MIGRATION-ID: project-file-word97-method
-- DEPENDS-ON: V017__project_file_parse_provenance.sql,CONTRACT__project_file_parse_result.sql
-- PURPOSE: additive DOC_TEXT allowance for original and immutable parse provenance
-- EXECUTION: CONTROLLED_OPERATOR_ONLY / NOT_APPLIED
-- IDEMPOTENCY: NOT_ASSUMED / reopen exact journal and catalog before resuming
-- No automatic Apply is provided. Keep every application/worker writer stopped.
-- Before EACH DDL: prove approved business schema, exact old/new definitions,
-- current full typed backup roundtrip/SHA, protected and business row fingerprints,
-- unchanged unrelated catalog, and a durable operation journal with next-step intent.
-- Historical reset receipts/empty backups are NOT substitutes. Preserve all data.
-- DM8 DDL may commit implicitly. On interruption/outcome uncertainty, reopen the
-- same journal, backup and actual catalog; NEVER blindly replay this whole file.
-- After EACH DDL prove exact next state and unchanged row/catalog fingerprints.
-- Temporary expanded guards must remain until the final named checks are proven.
-- This changes only the two method allowlists; column nullability is unchanged.

ALTER TABLE PROJECT_FILE ADD CONSTRAINT CK_PROJECT_FILE_PARSE_METHOD_DOC_GUARD CHECK (PARSE_METHOD IS NULL OR PARSE_METHOD IN ('PDF_TEXT','DOCX_TEXT','OCR','DOC_TEXT'));
ALTER TABLE PROJECT_FILE DROP CONSTRAINT CK_PROJECT_FILE_PARSE_METHOD;
ALTER TABLE PROJECT_FILE ADD CONSTRAINT CK_PROJECT_FILE_PARSE_METHOD CHECK (PARSE_METHOD IS NULL OR PARSE_METHOD IN ('PDF_TEXT','DOCX_TEXT','OCR','DOC_TEXT'));
ALTER TABLE PROJECT_FILE DROP CONSTRAINT CK_PROJECT_FILE_PARSE_METHOD_DOC_GUARD;

ALTER TABLE PROJECT_FILE_PARSE_RESULT ADD CONSTRAINT CK_PROJECT_FILE_PARSE_RESULT_METHOD_DOC_GUARD CHECK (PARSE_METHOD IN ('PDF_TEXT','DOCX_TEXT','OCR','DOC_TEXT'));
ALTER TABLE PROJECT_FILE_PARSE_RESULT DROP CONSTRAINT CK_PROJECT_FILE_PARSE_RESULT_METHOD;
ALTER TABLE PROJECT_FILE_PARSE_RESULT ADD CONSTRAINT CK_PROJECT_FILE_PARSE_RESULT_METHOD CHECK (PARSE_METHOD IN ('PDF_TEXT','DOCX_TEXT','OCR','DOC_TEXT'));
ALTER TABLE PROJECT_FILE_PARSE_RESULT DROP CONSTRAINT CK_PROJECT_FILE_PARSE_RESULT_METHOD_DOC_GUARD;
