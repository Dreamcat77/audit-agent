# Current issues and limits

- FIXED: ambiguous required AuditCaseService constructors.
- FIXED: frontend upload-progress option had no actual client transport; both failed tests now pass.
- FIXED: PPT page projection and real Excel row-part locators; synthetic page numbers removed.
- FIXED: Excel structure/raw/formula/cache metadata lost by the initial text-only projection; collected in the original parsing pass.
- FIXED: architecture guard read generated target-* build output; real source/resource checks retained, full verification pending.
- VERIFIED locally: targeted Java 76/0/0/4 skipped; frontend 832/832 and Vite build; Python 2029 passed/14 skipped/43 subtests.
- IN PROGRESS: clean verify r2; independent QA candidate switch and new real-model/browser cases.
- LIMIT: contract currently adapts the common result while retaining its own proven parser provider. Full engine extraction is not claimed.
- LIMIT: cross-domain persistent parse-result reuse is not implemented. No unsafe cross-owner cache was introduced.
- LIMIT: Office compatibility DOCX physical page/table fidelity and OCR model-version metadata remain explicitly limited.
- LIMIT: the procurement AutoCase upload path still excludes ZIP and uses a complete multipart batch; contract Project Intake ZIP remains supported. No claim of procurement resumable chunk upload.
- PROTECTED: original application port 80, original business database and environment configurations; no database migration or customer update authorized by this QA switch.
