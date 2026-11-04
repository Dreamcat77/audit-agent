-- Additive only. NULL on historical rows means no deadline was recorded by the old version.
ALTER TABLE MATERIAL_EXTRACTION_LEDGER ADD DEADLINE_AT TIMESTAMP;
