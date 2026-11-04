# Contract AI Table Redaction and Partial Degrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent table-shaped monetary fields from reaching Contract AI and retain only safe semantic hints when a bounded monetary retry is still unsafe.

**Architecture:** `ContractAiTextRedactor` recognizes monetary columns in pipe-delimited, tab-delimited, and the existing DOCX parser's newline-flattened cell stream, masking only those columns. FastAPI continues to retry the first monetary violation, then filters whole monetary hints on attempt two while preserving every hard-fail boundary. The frontend gives an explicit factual empty-hint presentation.

**Tech Stack:** Java 17, JUnit 5/AssertJ, Python 3.11, FastAPI/Pydantic/pytest, React/TypeScript node:test.

**Spec:** `docs/superpowers/specs/2026-09-01-contract-ai-stability-closeout-design.md` plus the approved 2026-09-01 production-evidence follow-up embodied by this plan.

## Global Constraints

- Do not modify `BoundedChunker`, the request-wide one-retry budget, or the context-aware `7×24`/`5×8` validator behavior.
- Do not add AI persistence, schema/migrations, or changes to F01/F02/F03, Budget, Project lifecycle, or deployment.
- Java keeps monetary/numeric authority in deterministic `BigDecimal` rules; AI output cannot override it.
- Partial degrade applies only to `MONETARY_VALUE` and `MONETARY_CALCULATION` on the second monetary attempt.
- `INVALID_TYPED_SCHEMA`, `INVALID_EVIDENCE_REF`, and `STRONG_VERDICT` continue to retry once and then return `MODEL_INVALID_RESULT`.
- Logs may contain only allowlisted request/runtime metadata; never source, prompt, model body, API key, or Authorization.

---

### Task 1: Table-aware Java monetary redaction

**Files:**
- Modify: `backend/src/test/java/com/fuquan/audit/contract/application/ai/ContractAiTextRedactorTest.java`
- Modify: `backend/src/test/java/com/fuquan/audit/contract/application/ai/ContractTextAnalysisUseCaseTest.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/ai/ContractAiTextRedactor.java`

**Interfaces:**
- Consumes: persisted parsed text, including `|`, tab, and newline-flattened DOCX table cells.
- Produces: `String redact(String source)` with monetary header/value cells replaced by `MASK` and non-monetary cells unchanged.

- [ ] **Step 1: Write failing redactor and use-case tests**

```java
String source = """
        标识 | 货物名称 | 数量 | 单价（元） | 申报金额（元）
        DESKTOP-01 | 台式计算机 | 100 | 5000.00 | 490000.00
        LAPTOP-01 | 便携式计算机 | 20 | 7000.00 | 140000.00
        """;
String redacted = new ContractAiTextRedactor().redact(source);
assertThat(redacted).contains("DESKTOP-01", "台式计算机", "100", "20");
assertThat(redacted).doesNotContain("5000.00", "490000.00", "单价（元）");
```

- [ ] **Step 2: Run focused Java tests and observe monetary cells leak under the current implementation**

Run: `backend/.\mvnw.cmd -Dtest=ContractAiTextRedactorTest,ContractTextAnalysisUseCaseTest test`

- [ ] **Step 3: Implement column-context masking**

Add exact monetary-header recognition with optional `（元）`, `(元)`, `（万元）`, or `(万元)`. Parse pipe/tab rows by stable column index and recognize the current DOCX parser's newline cell sequence using a bounded known-header run plus complete numeric monetary cells. Keep quantity, identifiers, dates, service hours, percentages, names, models, and codes intact.

- [ ] **Step 4: Run focused Java tests**

Run: `backend/.\mvnw.cmd -Dtest=ContractAiTextRedactorTest,ContractTextAnalysisUseCaseTest test`

- [ ] **Step 5: Commit**

```text
fix(contract): redact monetary table columns
```

### Task 2: Attempt-two monetary partial degrade

**Files:**
- Modify: `services/audit-ai/tests/test_model_routes.py`
- Modify: `services/audit-ai/tests/test_safe_logging.py`
- Modify: `services/audit-ai/src/audit_ai/model/router.py`
- Modify: `services/audit-ai/src/audit_ai/logging.py`

**Interfaces:**
- Consumes: a valid `ContractChunkResult` returned after a first monetary rejection and its one retry.
- Produces: the same typed result with unsafe monetary hints omitted, a fixed system summary only when the model summary is monetary, and `FILTERED` safe logs with numeric `filtered_hint_count`.

- [ ] **Step 1: Write failing route and logging tests**

```python
assert response.status_code == 200
assert [hint["title"] for hint in response.json()["data"]["hints"]] == safe_titles
assert unsafe_description not in response.text
assert filtered.audit_fields["outcome"] == "FILTERED"
assert filtered.audit_fields["filtered_hint_count"] == 1
```

Cover five safe hints plus one monetary hint, unsafe summary replacement, all-hints-filtered output, and unchanged 502 behavior for typed schema, evidence refs, and strong verdicts.

- [ ] **Step 2: Run focused Python tests and observe attempt-two monetary results return 502**

Run: `..\..\.runtime\venvs\audit-ai\Scripts\python.exe -m pytest tests/test_model_routes.py tests/test_safe_logging.py -q`

- [ ] **Step 3: Implement minimal result-level filtering**

Validate schema and evidence first, reject strong verdicts across the complete result, classify summary and each hint separately for monetary value/calculation, and filter only on attempt two reached from a monetary retry. Never rewrite or resend an unsafe hint.

- [ ] **Step 4: Run focused Python tests**

Run: `..\..\.runtime\venvs\audit-ai\Scripts\python.exe -m pytest tests/test_model_routes.py tests/test_safe_logging.py -q`

- [ ] **Step 5: Commit**

```text
fix(ai): retain safe hints after monetary retry
```

### Task 3: Empty-hint frontend presentation

**Files:**
- Modify: `frontend/src/features/contract/contractTextAnalysisPresentation.test.ts`
- Modify: `frontend/src/features/contract/contractTextAnalysisPresentation.ts`

**Interfaces:**
- Consumes: `available=true` Contract AI responses with zero displayable hints.
- Produces: a completed state with the factual detail `本轮 AI 未生成可展示的文字语义建议；金额与数值结果请查看精确校验。`.

- [ ] **Step 1: Write the failing presentation test**

```typescript
assert.equal(view.statusLabel, "分析完成");
assert.equal(view.detail, "本轮 AI 未生成可展示的文字语义建议；金额与数值结果请查看精确校验。");
assert.deepEqual(view.hints, []);
```

- [ ] **Step 2: Run the focused frontend test and observe the missing detail**

Run: `node --experimental-strip-types --test src/features/contract/contractTextAnalysisPresentation.test.ts`

- [ ] **Step 3: Add the zero-hint presentation branch and rerun the focused test**

- [ ] **Step 4: Commit**

```text
fix(contract): explain filtered semantic results
```

### Task 4: Full verification and delivery

**Files:**
- Verify only; apply corrections only when evidence identifies a scoped defect.

**Interfaces:**
- Consumes: Tasks 1-3 commits.
- Produces: fresh full-suite evidence, clean diff/security checks, and a pushed feature HEAD.

- [ ] **Step 1: Run Python 3.11 full tests**

Run: `..\..\.runtime\venvs\audit-ai\Scripts\python.exe -m pytest tests -q`

- [ ] **Step 2: Run Backend full verification**

Run: `backend/.\mvnw.cmd clean verify`

- [ ] **Step 3: Run every frontend test and production build**

Run all `*.test.ts` files with Node, then `npm.cmd run build`.

- [ ] **Step 4: Run repository checks**

Run `git diff --check`, scoped changed-diff secret scan, tracked-artifact scan, schema/chunker/persistence scope guards, and verify a clean worktree.

- [ ] **Step 5: Push only the current feature branch**

Run: `git push origin feature/ai-ocr-project-intake-demo`. Do not merge, tag, deploy, or probe 29.
