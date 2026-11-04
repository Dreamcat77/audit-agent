# V2 output capacity, semantic diagnosis and failed audit repair

Status: **REPAIR CANDIDATE / NOT VALIDATED**. Baseline: `9772c2b4c6c403ea598421e16b8acfede075ca9a`, branch `integration/pre-main-20261010`. The commit containing this report identifies the new source candidate; the ignored candidate-build receipt records its full SHA and isolated artifact hash.

No reset, duplicate upload, replacement Run, historical-state rewrite, formal Step Retry, endpoint/model switch, remote push or service stop was performed. No validated pin was generated. This report supersedes neither old immutable evidence nor original failed attempts.

## Evidence levels

`CODE_REVIEWED` means source review; `LOCAL_TESTED` means executable local tests; `DM8_VERIFIED`, `MODEL_VERIFIED` and `BROWSER_VERIFIED` apply only to their explicitly stated scope. An offline truth-file comparison is never business E2E acceptance.

| Item | CODE_REVIEWED | LOCAL_TESTED | DM8_VERIFIED | MODEL_VERIFIED | BROWSER_VERIFIED |
|---|---|---|---|---|---|
| A-mini output planning / row grounding | Yes | RED/GREEN; actual V2 12/48 source coverage | Old attempts/history unchanged only; new publication NOT VERIFIED | Partial table-row probes below; full current-protocol document NOT VERIFIED | NOT VERIFIED for this repair |
| Dense semantic execution | Yes | Full-source/catalog identity, bounded calls/time, UNKNOWN fail-closed | No checkpoint schema/write implemented | One controlled ANALYZE HTTP/JSON result only; full flow BLOCKED | BLOCKED |
| Failed model-call audit closure | Yes | Actual Worker/coordinator, typed gateway seam, success/FAILED/UNKNOWN/timeout/lease adversaries | Live constraints/history readonly verified; real transactional writes/rollback NOT VERIFIED | No production failed Step retried | NOT VERIFIED |
| Genuine Word97 | Yes | Actual OLE/HWPF, root/ZIP, evidence and packaged child JVM | Both old CHECKs reject DOC_TEXT; migration NOT_APPLIED | No model acceptance claimed | NOT VERIFIED |
| V2 formal F02 | Yes | Real five Java rules/application service with source-derived **test-only confirmed-input adapter** | In-memory test repositories only | NOT VERIFIED | NOT VERIFIED |
| Daily Start/Status/Stop | Yes | Existing guards, owned-helper stop, new DOC startup gate | Readonly Plan and state checks only | Original startup model gate retained | Final daily Start acceptance BLOCKED |

## A: finite output planning and complete row identity

Changes are in `ContractFactBatchPlanner`, `ContractFactSegmenter`, the existing transaction coordinator/FastAPI adapter and Python fact protocol/validator; no second extraction/storage pipeline was added.

- Keep the actual provider limit at 4096 tokens and existing A-mini reasoning setting. Reserve 512 output units and bound batch count at 128 per scope; at most 32 evidence segments of 240 codepoints per request. Estimates are conservative planning units, **not a guarantee of model tokenization or semantic completeness**. The original 900-second total budget still applies.
- Scan the entire source, including unlabelled text and the last row. Actual V2 source has 13,575 / 21,388 codepoints and 186 / 260 physical-row segments; the old 256-segment cap rejected the latter. New document cap is 512 segments within the unchanged 30,720-codepoint bound. Oversize input fails rather than truncates.
- Full-source plans for the real 12-row / 48-row fixtures use CORE 7 / 9 batches and LINE_ITEMS 10 / 63 batches. Each LINE_ITEMS batch has at most five of the target detail rows. Every immutable segment remains present in order in every applicable scope.
- Compact rows expand server-side into the same four typed facts, grounded to one complete physical row. Exact explicit source headers provide `lineItemColumns`; column order is transmitted and included in the input fingerprint. Unknown/ambiguous headers and fragmented rows use the existing legacy path. Strict grouped thousands separators are accepted without rewriting source values; malformed grouping, units and sign variants do not acquire compact authority.
- Require the full original name cell and the corresponding quantity/unitPrice/declaredAmount cells, not unordered numeric membership. No amount calculation or AI amount decision is added. Same physical row fragments merge by server row identity; same-name distinct physical rows remain separate. Decimal scale does not create false contradictions.
- The same mapped-cell checks apply to legacy candidate responses in Python, before ambiguity filtering; one mismatched mapped fact makes the batch FAILED with zero candidates. Java independently rejects a mapped wrong-column or partial-name response. Unmapped legacy evidence keeps its existing compatibility path.
- A provider `finish_reason=LENGTH` fails before JSON parsing/publication, including apparently valid JSON. It does not repeat the identical request as a JSON repair. Any FAILED/UNKNOWN batch prevents the whole attempt from publishing accepted business facts. Original failed attempts/call audits remain untouched.
- Prompt provenance is `contract-fact-prompt-v2.3`; schema remains the existing v2 boundary with optional server-owned column metadata. Legacy candidate responses remain supported. All fact/context enum mappings are explicitly supplied; the old incomplete context instructions caused typed rejection in a real synthetic probe.

RED/GREEN includes output-capacity tables, provider LENGTH, enum context mapping, scale equivalence, swapped numeric cells, partial names, unknown/permuted headers, real V2 header aliases, grouped numbers, same-name rows, fragments and whole-attempt failure atomicity. The real V2 fixture tests assert mapped 12/12 and 48/48 rows, including the final row, not just a synthetic header.

### Limited real model probes (not application publication)

All use the original approved model/endpoint, an unchanged `/models` gate, zero automatic retry and no DB/API upload writes. Receipts contain metadata/hashes only. V2 package inputs are fictional fixture table rows; diagnostic UUIDs are not represented as real DM8 parse/attempt identities.

- An unchanged three-row legacy request returned HTTP 200 but failed all 12 context enum validations. After explicit enum mapping, a three-row synthetic request produced 12 grounded facts, HTTP 200/STOP, 62.031 seconds, 1649 completion tokens.
- Under compact v2.2, the actual 12-row table passed all batches, as did the first 45 rows of the 48-row table (including distinct same-name rows). The last three rows returned HTTP 200/STOP but failed an independent exact-cell oracle. The probe stopped immediately. Original responses were not persisted; the precise old field mismatch is UNKNOWN, not retroactively inferred.
- After the stricter full-cell/header protocol, **one** v2.3 tail-only call passed all three rows/12 fields: request `70a69b93-a120-48bb-ba28-9a27bcdc69e6`, 4511 request bytes, HTTP 200/STOP, 20.578 seconds, 159 completion tokens, no mismatch fields, 4096-token limit unchanged. No whole-table rerun was made.
- The mixed-version observations are **not** a claim that all 48 rows passed under the final protocol, nor that a complete source document was extracted/persisted successfully. Full current-protocol A-mini DM8 acceptance remains NOT VERIFIED.

## B: semantic findings and remaining recovery work

Original failed Run `51e0d8ed-6136-4fd7-b824-0eea70f08f4a`, Step `ee72fde9-50a8-3ca4-9b7c-1e90d6b4456d`, Attempts 1/2 remain unchanged. No Attempt 3 or alternative Run was created.

Both original failures were **ANALYZE 1/2**, before synthesis/correction: first input 18,435 bytes, observed HTTP 500 durations 123.279 and 120.530 seconds. Original semantic transport is JSON_OBJECT / prompt-only structured instructions, temperature zero, **no explicit max_tokens or reasoning_effort**. A-mini's transport parameters have not been copied into semantics, nor have production prompts/read timeouts been changed.

The application received HTTP 500 from the approved provider endpoint. Which internal worker, gateway or proxy first generated it remains **UNKNOWN_NO_SERVER_LOGS**. Similar duration does not prove a timeout component. There is no authorized SSH access to the previously mentioned remote machine and no request was issued from it. Equal-byte synthetic success does not prove realistic long-contract capacity.

Implemented a small opt-in execution planner that regroups **whole existing evidence blocks** without changing source spans/catalog IDs. Production defaults remain unchanged; smaller batches are not enabled in the running service. A 144-clause/three-material synthetic fixture is 6018 codepoints / 16,962 source bytes; controlled planning yields three batches of 2250/2232/2250 characters. Offline tests prove complete per-file SHA coverage, immutable evidence, bounded batch count, global 900-second budget, global retry limit and preservation of observed audit prefix on failure/UNKNOWN. UNKNOWN is never automatically replayed.

One controlled ANALYZE probe returned HTTP 200/STOP in 93.563 seconds (10,335 request bytes, 2343 completion tokens), request `16dc55b1-5b57-440d-8e5a-b623bcf3377f`. This proves only HTTP/JSON processing of the first synthetic batch, **not** complete typed analysis/synthesis, recovery, original source success or permission to retry the failed Step.

### Minimal durable-resume design — NOT IMPLEMENTED

Safe per-batch result recovery still requires a DM8, Step/Attempt-owned execution manifest: immutable ordered source/catalog fingerprint and planner version; per-batch lease/dispatch identity and bounded validated result; persisted global budget/retry counters; synthesis consuming exactly the complete proven batch set. UNKNOWN dispatch must be reconciled before replay. Refresh/new HTTP requests must not create a fresh operation identity or reset budgets. Do not use Redis/Python files as the sole fact source or change evidence IDs to fit a smaller chunk.

The current experimental planner preserves source/evidence and fail-closed audit prefixes, but it **does not persist reusable batch results**; a fresh request starts at batch one. Therefore Task B is partial, not a repaired/validated production semantic executor. Full dense synthetic flow and durable-resume implementation/DM8 tests are still required before formal Step Retry.

External follow-up: provider maintainers should correlate UTC `2026-10-15 02:44–02:49`, request `37c950c8-45e1-41dd-80cc-57b865d89062`, calls `6d95b803-d023-4fe4-9dc0-25b9948a40b0` and `b3f1ea5f-315a-4816-abdf-c82abb33f012`; identify the earliest 500 and each worker/gateway/proxy deadline/cancellation. Do not send raw contract or credentials with that request.

## C: retain observed audits during failed Worker closure

`FrozenSemanticReviewStepExecutor` now retains available model-call observations through unavailable/invalid mapped results and typed/cause-chain transport failures. Worker forwards them to a short REQUIRES_NEW close transaction. The coordinator validates original Attempt ownership, attemptNo, external fingerprint, audit IDs/ordinals and semantic-only shape **before** state changes, then performs active-lease CAS before inserting calls and closing Step/Run/idempotency/business audit together.

Tests exercise success, provider 5xx FAILED, known pre-dispatch budget-exhaustion FAILED, READ_TIMEOUT/transport-unknown UNKNOWN, prefix retention, no observations, stale/expired/reclaimed leases, concurrent duplicate closure, ownership/fingerprint/ordinal/ID rejection and local result validation failure. Known pre-dispatch budget exhaustion alone does not imply an escaped request; earlier uncertain transport still takes precedence.

This fixes lost observation propagation; it is **not the HTTP 500 root cause**. A null providerRequestId remains null when the provider supplies none; no UUID is substituted. The original two failed attempts still have zero durable call rows: historical records were not backfilled. Owner-aware unique constraints are enabled on live DM8; actual transactional write/rollback behavior for this new path remains NOT VERIFIED.

Independent review also found and repaired the synchronous API path dropping the new UNKNOWN exception's observations. It now forwards calls into the existing UNKNOWN coordinator's REQUIRES_NEW transaction, with strict synchronous/no-worker-lease and owner/fingerprint/ordinal/CAS guards. UNKNOWN reason and original IN_PROGRESS idempotency identity remain unchanged. Worker-owned attempts cannot be closed through this alternate synchronous path. No-observation closure creates no fabricated call.

## D: genuine Word97 and F02 regression scope

Original OLE/HWPF Word97 `.doc` is handled by the existing root/ZIP intake and FileStorage/ParseResult pipeline, with DOC_TEXT provenance. It is not renamed DOCX. POI scratchpad uses the existing 4.1.2 baseline. Child JVM isolation enforces 128 MiB heap, 15-second deadline, bounded input/output and two-process capacity. Encryption, macros/embedded content, unsupported/malformed streams and fake extensions fail closed. Tests cover real V2 Word97 root/ZIP, original file hash/evidence order, hostile child timeout/output/heap and packaged Boot JAR launching.

Live DM8's two method CHECKs exclude DOC_TEXT. Additive operator SQL, readonly Plan/Verify, strict identity/catalog checks and public startup gates are supplied. **NOT_APPLIED**: the user requires writers to stay running, and no current migration backup/maintenance window exists. See [migration and recovery](../ops/local-word97-migration.md). Do not claim live DOC persistence acceptance until controlled migration and HTTP/DM8 checks pass.

`V2FormalF02RegressionTest` runs native source parsing and the actual ContractReviewService/five BigDecimal rules with in-memory persistence, using a clearly named test-only confirmed-input adapter. Twelve rows produce 16 findings; 48 rows produce 52; distinct same-name source rows retain independent arithmetic findings. The actual cross-file 69,000 versus 67,000 case fails the award comparison with exactly 2,000 difference. Missing structured basis yields NOT_EVALUATED; this is not a claim that F02 detects every missing non-money attachment.

The generic deterministic ProjectFactExtractor still does not recognise all V2 table/header grammar. The test adapter is not a production extraction fix; truth JSON supplies comparison expectations only, never review operands. No fixture was promoted or reviewed through a new live Run for these tests.

## Reproducible verification and evidence

Environment: canonical Windows integration worktree, JDK 17, approved Python 3.11 environment, existing frontend dependencies. Use isolated Maven output; never clean/replace the running `backend/target` JAR.

```powershell
# From backend; supply real readonly V2/Word97 fixture paths to enable those tests.
.\mvnw.cmd "-Daudit.build.directory=$isolatedBuild" `
  "-Daudit.v2.fixtureRoot=$v2Root" "-Daudit.word97.fixture=$word97Root" clean verify
# After package, test the newly built JAR, not an older fixture JAR.
.\mvnw.cmd "-Daudit.build.directory=$isolatedBuild" `
  "-Daudit.word97.fixture=$word97Root" "-Daudit.word97.packagedJar=$newJar" `
  '-Dtest=ProjectDocProcessTest' test
# From repo; approved interpreter and controlled temporary directories required.
& $approvedPython -m pytest services/audit-ai/tests -q -p no:cacheprovider --basetemp=$controlledTemp
# From frontend; retain normal per-file isolation.
$tests=@(rg --files src -g '*.test.ts'); node --test @tests
node node_modules/vite/bin/vite.js build --outDir $isolatedFrontendDist
```

Fresh full Maven `clean verify`: **2312 tests, zero failures/errors, two existing opt-in original-material characterization skips; BUILD SUCCESS**. Real V2/Word97 fixtures were enabled, not skipped. Fresh Python: **538 passed**, zero failures/skips; one upstream Starlette deprecation warning. Frontend: **750 passed**, zero failures/skips; Vite build passed with existing large-chunk warning. Migration harness: 10 passed; AST/private/source/build guards: 17 passed; actual Windows owned-helper process test passed after the sandbox CIM restriction was handled, without stopping product services. Exact candidate artifact/source hashes and skipped case names are recorded in the stage receipt.

Packaged Word97 smoke against the newly created full-suite JAR: five passed, zero failures/errors/skips. After committing, build a separate isolated artifact from that exact clean SHA and repeat this smoke; record its hash in the ignored receipt. Do not run the official in-place Build over an artifact still used by the preserved processes.

Ignored local evidence: `.runtime/contract-repair-test/repair-20261015/` (Maven/frontend logs, V2 capacity counts, model probe receipts, readonly state and candidate build receipt) and `.runtime/semantic-bounded-task-b/mapped-legacy-full-report.xml`. No private configuration, credential, raw prompt, contract body or runtime artifact belongs in Git.

Readonly final state proves original Run/Step Attempt 2 unchanged, no Attempt 3; old V2 fact attempt remains FAILED/accepted=0 and its 15 audit projections unchanged; 33 processing jobs remain 32 SUCCEEDED/1 FAILED with no automatic retry. Existing sealed evidence hashes are unchanged. Existing Java/FastAPI/frontend process identities and health HTTP 200 remain available at the local browser URL.

The original processes still run their original loaded backend/Python candidate, not this newly built repair JAR. No hot replacement or manifest relabeling was used. New sources/builds are handed off separately. Formal original Step Retry, five-step success, eight conclusions/copy/HTML/history/relogin and final daily Start acceptance remain BLOCKED/NOT VERIFIED. Do not generate `validated-sha.txt` from these partial results.
