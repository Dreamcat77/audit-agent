# Candidate Cross-Material Authority Design

**Status:** Proposed for specification review

**Branch:** `feature/candidate-cross-material-authority-20260920`

**Baseline:** `9ef79de6d5d6fc53f9fe29699dd1934344b86106`

**Scope:** P1-B Candidate cross-material authority only

## 1. Problem

The current Project Intake path materializes one `ContractCandidate` from one authoritative
`CONTRACT_BODY` file:

```text
ProjectFile
  -> immutable ParseResult
  -> human RoleDecision
  -> human SAME_PROJECT IdentityDecision
  -> ContractCandidate materialization
  -> Candidate Confirmation
  -> Promotion
  -> Contract
  -> ReviewRun / frozen F02
```

This single-source model can already populate `AWARD_AMOUNT`, `AWARD_SUPPLIER`, and
`AWARD_SUPPLIER_CREDIT_CODE` when those facts happen to be extracted from the contract body.
It cannot safely use the same facts from a separately uploaded award notice. The existing
cross-file evidence fallback in `FindingSourceProjectionService` scans all project extraction
rows and relies on immutable `ProjectFile.category`; it does not prove that a particular
Candidate accepted that file, parse, role decision, and identity decision as authority.

P1-B adds a bounded, explicit, durable relationship between a DRAFT Candidate and one or more
authoritative `AWARD_NOTICE` files. It then rematerializes the same Candidate so the three
existing award facts can flow through the existing confirmation and promotion contracts into
the frozen Contract consumed by F02.

This is an extension of the existing Candidate lifecycle, not a replacement for it.

## 2. Current architecture

The design is grounded in these current properties:

- `ContractCandidateMaterializationTransactionCoordinator` materializes or reauthorizes one
  Candidate from the exact primary file, latest immutable parse, current human
  `CONTRACT_BODY` role decision, and current human `SAME_PROJECT` identity decision.
- `ContractCandidateAssembler` uses one primary `ProjectFile`, aggregates scalar values by
  distinct normalized value, emits `null` for ambiguity, and groups line items by `groupKey`.
- Candidate fields `sourceContractFileId`, `sourceParseResultId`, `sourceRoleDecisionId`,
  `sourceIdentityDecisionId`, `sourceAuthoritySource`, and `sourceFactFingerprint` describe only
  the primary contract-body authority.
- Primary extraction rows may be bound to a Candidate. Existing DM8 foreign keys require a
  Candidate-bound extraction row to share the Candidate's primary file and parse.
- `ContractCandidateConfirmation` stores a canonical human business payload and fences it with
  Candidate versions. Confirmation increments the Candidate version; promotion accepts only a
  confirmation whose result version equals the current Candidate version.
- Promotion maps the persisted `ConfirmedContractPayload` into the existing `Contract`, line
  items, and `AwardNoticeReference` structures.
- ReviewRun independently freezes its selected material manifest and Contract snapshot. It is
  not a continuation of Candidate extraction provenance.
- `ExtractionResult` already stores fact-level source provenance: file, parse, fact type,
  group, value, excerpt, and locator. Supporting facts do not need to be copied.
- Project candidate commands use `POST` command resources with a required `Idempotency-Key`.
- The DM8 Contract evolution line uses additive `CONTRACT__*.sql` files with
  `CONTROLLED_MANUAL`, explicit `DEPENDS-ON`, and catalog preflight.

## 3. Compatibility invariants

Compatibility is a hard boundary.

1. A Candidate with an empty support set uses the current single-`CONTRACT_BODY`
   materialization policy without semantic changes.
2. Existing Candidate IDs and the Candidate lifecycle remain unchanged.
3. Existing primary authority fields retain their current meaning and are never broadened to
   include supporting material.
4. `sourceFactFingerprint` remains the fingerprint of the primary contract-body authority and
   facts. It is not changed into a cross-material fingerprint.
5. Candidate Confirmation keeps its existing request payload, canonical schema version, and
   canonical business JSON.
6. Promotion keeps its existing endpoint, command, payload, and Contract mapping.
7. ReviewRun material selection, snapshots, frozen inputs, workers, and F02 rule semantics stay
   unchanged.
8. Historical Candidate rows require no backfill and remain readable. Existing promoted
   Contracts, ReviewRuns, findings, reports, and artifacts remain valid.
9. An unassigned award notice cannot affect Candidate structured facts, confirmation, or
   promotion merely because it belongs to the same Project or has an `AWARD_NOTICE` classifier
   category. In `PRIMARY_ONLY_COMPAT`, the current FindingSourceProjection legacy category
   fallback remains unchanged as evidence compatibility behavior only; it does not become
   Candidate fact authority.
10. No existing migration, including V001-V022 and
    `CONTRACT__contract_candidate_authority.sql`, is edited.
11. Finding provenance follows the same two-mode boundary: primary-only Candidates retain the
    existing projection behavior, while explicit-support Candidates use only exact assigned
    support authority.

## 4. Goals

- Allow a user to replace the complete `AWARD_NOTICE` support set of one DRAFT Candidate.
- Bind every selected support file to its exact current file hash, parse, human role decision,
  and human same-project identity decision.
- Aggregate only `AWARD_AMOUNT`, `AWARD_SUPPLIER`, and
  `AWARD_SUPPLIER_CREDIT_CODE` from an explicit non-empty support set.
- Rematerialize derived Candidate facts without changing Candidate identity or primary
  authority.
- Make support changes invalidate prior confirmation through the existing Candidate version
  fence.
- Preserve deterministic conflict behavior and require human confirmation for the final
  Contract payload.
- Give award evidence an exact, Candidate-owned provenance path in
  `EXPLICIT_SUPPORT_AUTHORITY` without changing the primary-only legacy projection path.
- Preserve durable idempotency, fail-closed recovery, and DM8 as the system of record.

## 5. Non-goals

P1-B does not introduce:

- `CandidateV2`, a parallel Candidate lifecycle, or a `CrossMaterialCandidateEngine`;
- automatic project-wide assignment of award notices;
- support roles other than `AWARD_NOTICE`;
- automatic extraction of award notice number, award project name, or winning date;
- a new Candidate confirmation payload or schema version;
- a new promotion command or Contract mapping;
- a new ReviewRun source or any change to ReviewRun material authority;
- a change to F02 verdicts, rules, tolerances, or P1-C semantics;
- AI authority for role, identity, facts, confirmation, promotion, or audit verdicts;
- re-binding supporting `ExtractionResult.CANDIDATE_ID` to the primary Candidate;
- changes to V001-V022 or any existing Contract migration;
- support for `TENDER_DOCUMENT`, `BID_RESPONSE`, or `SUPPLEMENT`.

## 6. Approaches considered

### 6.1 Chosen: explicit current support set on the existing Candidate

Add a Candidate-specific support-authority repository and table, extend the existing
materialization coordinator/assembler boundary, and use Candidate CAS for derived-fact
replacement. The table stores the current exact authority tuple for each selected award notice;
separate Candidate support-set metadata proves which cohort was used for rematerialization.

This is the smallest approach that preserves the current lifecycle, gives the server a precise
authority path, supports detach, and leaves primary provenance intact.

### 6.2 Rejected as new authority: dynamic project-wide award-notice scan

Scanning `findByProjectId()` and filtering by filename, category, or current role cannot express
which award notice belongs to which Candidate when a Project has multiple Candidates. It also
changes results silently when files are uploaded or reclassified. This is not authoritative and
cannot satisfy durable rediscovery or idempotency. P1-B therefore rejects it for Candidate facts
and explicit-support provenance. The existing primary-only FindingSourceProjection category
fallback is separately preserved as legacy compatibility behavior; P1-B does not harden or
promote that fallback into business authority.

### 6.3 Rejected: duplicate/rebind extraction facts or create a second Candidate model

Copying support facts would create two provenance truths. Rebinding a support extraction row to
the Candidate would violate the existing primary-file and primary-parse foreign-key invariants.
A new Candidate model or lifecycle would duplicate confirmation, promotion, and compatibility
logic. All three options are larger and less safe than an explicit association.

## 7. Authority model

### 7.1 Two explicit fact-policy modes

The policy has two named modes:

```text
support set empty
  -> PRIMARY_ONLY_COMPAT

support set non-empty
  -> EXPLICIT_SUPPORT_AUTHORITY
```

These are modes, not a precedence ranking. The design must never be described or implemented as
“AWARD_NOTICE always wins over CONTRACT_BODY.”

#### `PRIMARY_ONLY_COMPAT`

When the current support set is empty, materialization uses the existing primary-only behavior.
All facts currently recognized by `ContractCandidateAssembler`, including award facts already
extracted from the primary `CONTRACT_BODY`, retain their current behavior. Existing projects,
historical Candidates, tests, and demos therefore remain compatible.

#### `EXPLICIT_SUPPORT_AUTHORITY`

When the current support set is non-empty, the strict cross-material allow-list applies.
Primary `CONTRACT_BODY` award facts are excluded from the three award-field aggregations even if
they exist. Only facts from explicitly associated, currently authoritative `AWARD_NOTICE` files
may contribute those fields.

Replacing `[A, ...]` with `[]` rematerializes the Candidate under `PRIMARY_ONLY_COMPAT`; primary
contract-body award facts again become eligible through the legacy-compatible policy.

Every accepted support-set replacement is a Candidate derived-fact change. It increments the
Candidate version exactly once. Consequently any prior `ContractCandidateConfirmation` is stale
by its existing version fence, including both transitions between the two modes.

### 7.2 Primary vs support authority

#### Primary authority

The following fields continue to represent only the primary `CONTRACT_BODY`:

- `sourceContractFileId`
- `sourceParseResultId`
- `sourceRoleDecisionId`
- `sourceIdentityDecisionId`
- `sourceAuthoritySource`
- `sourceFactFingerprint`

P1-B never writes support identities into these fields. Primary authority remains the Candidate
identity anchor. A support-set replacement first proves that this primary authority is still
current; it does not reauthorize or replace it.

#### Supporting authority

A support association means exactly:

> This Candidate explicitly accepts this immutable file/parse plus these current human role and
> identity decisions as an `AWARD_NOTICE` source for the P1-B allow-list.

It does not mean that every Candidate in the Project accepts the file. The same support file may
be explicitly associated with multiple Candidates, with one independent durable association per
Candidate.

The browser never supplies trusted parse, role, identity, or fingerprint values. The backend
resolves and verifies them from `supportFileIds` at command time.

## 8. Role-to-fact allow-list

The allow-list is backend-owned and versioned in code with the materialization policy. Filename,
immutable classifier category, and mere text occurrence never grant fact authority.

| Mode | Authoritative role | Eligible facts |
|---|---|---|
| `PRIMARY_ONLY_COMPAT` | primary `CONTRACT_BODY` | Current assembler behavior, including current primary `AWARD_*` compatibility |
| `EXPLICIT_SUPPORT_AUTHORITY` | primary `CONTRACT_BODY` | `CONTRACT_NO`, `CONTRACT_NAME`, `PARTY_A`, `PARTY_B`, `CREDIT_CODE`, `AMOUNT_NUMERIC`, `AMOUNT_UPPERCASE`, `LINE_ITEM_KEY`, `LINE_ITEM_NAME`, `LINE_ITEM_QUANTITY`, `LINE_ITEM_UNIT_PRICE`, `LINE_ITEM_DECLARED_AMOUNT` |
| `EXPLICIT_SUPPORT_AUTHORITY` | associated `AWARD_NOTICE` | `AWARD_AMOUNT`, `AWARD_SUPPLIER`, `AWARD_SUPPLIER_CREDIT_CODE` |

An award notice containing a contract number, contract amount, party, or line item cannot
introduce or overwrite those Candidate fields. A contract body containing `AWARD_*` cannot
contribute those fields while the Candidate is in `EXPLICIT_SUPPORT_AUTHORITY` mode.

P1-B v1 recognizes no supporting role other than `AWARD_NOTICE`.

## 9. Support association domain model

Introduce the bounded domain concept `ContractCandidateSupportAuthority` with these durable
attributes:

| Attribute | Meaning |
|---|---|
| `supportAuthorityId` | Stable row identity |
| `projectId` | Ownership boundary |
| `candidateId` | Candidate that explicitly accepted the support |
| `sourceFileId` | Supporting ProjectFile |
| `fileSha256` | Exact file-content fence |
| `parseResultId` | Exact immutable parse |
| `parseVersion` | Parse-version fence |
| `projectVersionNo` | Project metadata version used by identity authority |
| `effectiveRole` | Fixed to `AWARD_NOTICE` in v1 |
| `roleDecisionId` | Exact current human role decision |
| `identityResolution` | Fixed to `CONFIRM_SAME_PROJECT` in v1 |
| `identityDecisionId` | Exact current human identity decision |
| `sourceFactFingerprint` | SHA-256 of the exact eligible support facts and source identities |
| `authorityVersion` | Support-set cohort version |
| `createdBy` | Actor that committed this current association |
| `createdAt` | Commit time |

### 9.1 Current-state model

The association table represents the current set, not an append-only copy of every prior set.
A replacement transaction removes the prior current rows and inserts the complete new set.
There is no `active` flag, `updatedAt`, or independent mutable row version:

- each row is immutable while it belongs to the current cohort;
- `authorityVersion` identifies the cohort;
- Candidate CAS serializes replacement;
- durable idempotency records and the business operation audit preserve command/result history;
- promoted Candidates reject replacement, so the associations used for promotion remain stable.

This avoids partially active sets and filtered-unique-index behavior. It also gives `[]` an
unambiguous representation: no current association rows. This current-state schema does not
promise that every historical support cohort can be reconstructed after later replacements.
Idempotency and operation audit prove commands and outcomes; they do not turn the current-set
table into an append-only association history.

### 9.2 Candidate support-set metadata

Add separate nullable Candidate metadata; do not reinterpret primary fields:

- `supportAuthorityVersionNo`
- `supportAuthorityFingerprint`

`supportAuthorityVersionNo` increments once for every newly committed replacement command,
including a replacement with `[]`. All non-empty current association rows carry that same
`authorityVersion`. It is an independent support-cohort counter, not an alias for Candidate
`versionNo`; confirmation and promotion may advance Candidate version without creating a new
support cohort.

`supportAuthorityFingerprint` is a SHA-256 hash over a version token, fact-policy mode, stable
sorted exact support file IDs and hashes, parse IDs and versions, role decisions, identity
decisions, Project versions, and eligible extraction facts/fingerprints. For an explicitly
cleared empty set, it hashes the `PRIMARY_ONLY_COMPAT` empty-set marker. Historical rows may have
both metadata fields null and are interpreted as an implicit empty support set.

The fingerprint proves which support cohort was used when the Candidate was rematerialized.
`sourceFactFingerprint` continues to prove only the primary source.
`supportAuthorityFingerprint` is Candidate authority metadata; it is not the idempotency command
fingerprint.

The Candidate detail read model exposes the current mode and support association projections;
it does not place supporting IDs inside `ConfirmedContractPayload`.

## 10. Support authority eligibility

For every requested support file, the backend must prove all of the following in the replacement
transaction:

1. The file exists under the path Project and belongs to that Project.
2. `ingestionStatus == AVAILABLE`.
3. `parseStatus == PARSED`.
4. The selected parse is the current latest immutable parse, belongs to the same project/file,
   matches the file SHA-256, and has non-empty valid parsed content under existing rules.
5. `EffectiveDocumentRoleResolver` returns `AWARD_NOTICE`.
6. The effective role source is `HUMAN_CONFIRMATION`.
7. The exact current role decision belongs to the project/file/hash/parse/version tuple and
   selected `AWARD_NOTICE`.
8. `EffectiveProjectIdentityResolver` returns `SAME_PROJECT` from a current human decision.
9. The exact current identity decision belongs to the current project metadata version and the
   same file/hash/parse/version tuple.
10. The primary Candidate authority is itself current, human-confirmed `CONTRACT_BODY`, and
    human-confirmed `SAME_PROJECT`.

Any failure rejects the entire command. A classifier category of `AWARD_NOTICE`, an AI proposal,
a stale human decision, or an `UNKNOWN` authority is insufficient.

Historical Candidates remain readable without support metadata. Opting a legacy DRAFT Candidate
into `EXPLICIT_SUPPORT_AUTHORITY` requires its primary authority to be provable under the current
primary-authority path; P1-B does not invent authority for an incomplete legacy tuple.

## 11. Explicit assignment command/API

Following current `ProjectController` command-resource conventions, define:

```http
POST /api/v1/projects/{projectId}/contract-candidates/{candidateId}/support-authority-replacements
Idempotency-Key: <required 1..128 existing-safe-character key>
Content-Type: application/json

{
  "expectedCandidateVersion": 3,
  "supportFileIds": ["uuid-a", "uuid-b"]
}
```

The request contains only:

- path `projectId` and `candidateId`;
- `expectedCandidateVersion`;
- `supportFileIds`.

Unknown authority fields are rejected rather than trusted. The server UUID-validates, de-duplicates,
and stable-sorts `supportFileIds`. Array order and duplicate entries do not affect command
identity or aggregation.

The response is a command result containing:

- `candidateId`;
- resulting `candidateVersion`;
- resulting `supportAuthorityVersion`;
- `authorityMode`;
- the server-projected current support associations;
- `replayed`.

The normal Candidate detail GET is extended additively with `authorityMode`, the current support
association projections, exact visible provenance evidence, and separately projected authorized
field states. Reload therefore reconstructs the UI from server state; browser selection is never
workflow truth.

The endpoint replaces the complete support set. It is not attach-only. `supportFileIds: []` is a
valid explicit request to restore `PRIMARY_ONLY_COMPAT`.

## 12. Transaction boundaries

The support replacement is one short DM8 transaction. It performs no OCR, AI, remote HTTP, or
filesystem work.

The stable command fingerprint is computed from the request and actor before mutable authority is
resolved. A read-only idempotency lookup may identify a prior operation, but durable result
reconciliation still follows the lock protocol below.

The lock protocol is fixed:

1. read the Candidate without a lock only to discover `sourceContractFileId`;
2. form the canonical union of that primary file ID and `supportFileIds`;
3. stable-sort all ProjectFile IDs lexically by canonical UUID text;
4. lock every ProjectFile in that order;
5. lock the Project;
6. lock the Candidate;
7. recheck that the Candidate still exists, retains the same primary source, is DRAFT, and has the
   expected version;
8. for a fresh operation, resolve and verify primary/support authority;
9. continue the command.

Step 7 is interpreted against idempotency state. A fresh operation requires every recheck to
pass. A previously successful operation uses the observed Candidate/result state to choose
`REPLAY` or `RESULT_SUPERSEDED`; it does not reapply the old command. An `IN_PROGRESS` or otherwise
unproven operation fails closed with the same key. A completed replay does not run step 8 against
today's role/parse/identity state.

For a fresh operation, the remaining transaction:

1. resolves every current support authority tuple server-side;
2. computes per-source eligible-fact fingerprints and the canonical
   `supportAuthorityFingerprint`;
3. claims durable idempotency using only the stable command fingerprint;
4. if the claim discovers a concurrent prior record, follows the same replay/superseded/unproven
   classification and performs no fresh mutation;
5. loads primary facts by the Candidate's exact primary file/parse tuple and support facts by each
   exact support file/parse tuple;
6. applies the mode-specific role-to-fact allow-list and deterministic ordering;
7. rematerializes derived Candidate fields and line items;
8. deletes the prior current support rows;
9. updates Candidate derived facts, support-set metadata, `versionNo`, and `updatedAt` by CAS;
10. inserts the complete new non-empty support cohort;
11. appends a sanitized business operation audit;
12. marks the idempotency record succeeded.

All effects commit or roll back together. A failure cannot leave a new support set paired with old
Candidate facts, or new Candidate facts paired with the old set. Supporting extraction rows stay
unchanged and unbound.

The operation audit records actor, project/candidate IDs, old/new mode, support counts, canonical
support file IDs, result versions, and safe fingerprints. It must not record source text,
excerpts, contract values, or filenames.

## 13. Candidate rematerialization

Extend the existing materialization coordinator and `ContractCandidateAssembler`; do not create a
new engine.

The repository boundary gains a CAS operation conceptually equivalent to:

```text
replaceDraftDerivedFactsIfVersionMatches(
  projectId,
  candidateId,
  expectedCandidateVersion,
  scalarFacts,
  lineItems,
  supportAuthorityVersion,
  supportAuthorityFingerprint,
  updatedAt
)
```

The CAS guarantees:

- Candidate ID is unchanged;
- project and primary source authority fields are unchanged;
- `promotionState` is `DRAFT`;
- the expected Candidate version matches;
- scalar derived facts are replaced, not patched;
- Candidate line items are replaced atomically;
- Candidate version increments exactly once;
- `updatedAt` advances.

### 13.1 Fact loading and ordering

Facts are loaded only from exact authority tuples:

```text
primary Candidate tuple
  -> exact primary file + parse ExtractionResult rows

current support associations
  -> exact support file + parse ExtractionResult rows
```

No Candidate fact-authority or explicit-support provenance query uses `findByProjectId()` followed
by category filtering. This restriction does not remove the legacy evidence-only fallback retained
under `PRIMARY_ONLY_COMPAT` in section 18.

Before assembly, authorized facts are sorted deterministically by role, source file UUID,
parse version, fact type, group key, locator, and extraction key. Input order cannot affect
Candidate output or fingerprints.

The assembler still receives the primary `ProjectFile`, because Candidate identity belongs to
the contract body. In v1, support facts cannot contain `LINE_ITEM_*`; therefore line-item
`groupKey` collisions cannot cross files. Existing primary line-item grouping remains unchanged.

### 13.2 Mode transitions

- Empty to non-empty: switch to `EXPLICIT_SUPPORT_AUTHORITY`; exclude primary `AWARD_*`, aggregate
  only support award facts, increment Candidate version.
- Non-empty to a different non-empty set: remain in explicit mode, replace all support-derived
  award facts, increment Candidate version.
- Non-empty to empty: switch to `PRIMARY_ONLY_COMPAT`, rerun the current primary-only policy,
  restore eligible primary award facts, increment Candidate version.
- Empty to empty with a newly accepted idempotency key: rematerialize under the compatible policy
  and increment Candidate version once. The UI should disable an unchanged save to avoid needless
  confirmation invalidation; an idempotent replay of the same key never increments again.

### 13.3 Visible provenance vs authorized aggregation

Candidate Detail maintains two explicit projections:

- **visible provenance evidence** may include exact primary and explicitly associated support
  extraction rows so a user can inspect where text and values came from;
- **authorized aggregation evidence** is the mode-specific allow-list subset used to calculate
  Candidate business fields, `fieldStates`, line-item field states, and conflicts.

`PRIMARY_ONLY_COMPAT` keeps the current primary evidence and field-state behavior.
`EXPLICIT_SUPPORT_AUTHORITY` uses primary non-award facts plus explicit support `AWARD_*` facts.
Primary `CONTRACT_BODY` `AWARD_*` extraction remains inspectable as provenance, but is excluded
before Candidate award aggregation and field-state calculation.

Therefore:

```text
primary CONTRACT_BODY AWARD_AMOUNT = 100
assigned AWARD_NOTICE AWARD_AMOUNT = 98
mode = EXPLICIT_SUPPORT_AUTHORITY

=> candidate.awardAmount = 98
=> fieldStates.AWARD_AMOUNT = CONFIRMED
=> not CONFLICT
```

Only authorized supporting award facts conflict with each other:

```text
assigned AWARD_NOTICE A AWARD_AMOUNT = 98
assigned AWARD_NOTICE B AWARD_AMOUNT = 99

=> candidate.awardAmount = null
=> fieldStates.AWARD_AMOUNT = CONFLICT
```

## 14. Conflict semantics

There is no automatic source winner.

For each eligible Candidate fact:

- zero authoritative values -> Candidate value `null`, field state `MISSING`;
- one distinct authoritative value -> use that value, field state `CONFIRMED`;
- more than one distinct authoritative value -> Candidate value `null`, field state `CONFLICT`.

Money equality uses `BigDecimal.compareTo`, preserving existing scale-insensitive money
semantics. Text uses the current extraction normalization and assembler distinct-value behavior.

In `EXPLICIT_SUPPORT_AUTHORITY`, primary and support award values are never compared because
primary award facts are ineligible. Conflict arises when two or more associated authoritative
award notices supply distinct values for the same `AWARD_*` fact. Equal values from multiple
notices collapse to one value while all source evidence remains visible.

Human Candidate Confirmation remains the only step that chooses or enters the final Contract
business value. P1-B does not auto-resolve a conflict.

## 15. Confirmation impact

**Confirmation business contract: UNCHANGED.**

The existing endpoint, request fields, `ConfirmedContractPayload`, canonical schema version, and
canonical business JSON do not gain support IDs or extraction identities.

**Confirmation authority validation: EXTENDED.**

Before accepting a confirmation, the enhanced authority validator proves:

- the primary authority is current under existing rules;
- the complete current support set matches Candidate support-set metadata;
- every support association still matches the latest parse, current human `AWARD_NOTICE` role
  decision, current human `SAME_PROJECT` identity decision, file hash, parse version, and project
  version;
- the stored support-set fingerprint recomputes exactly from the current associations and
  eligible facts;
- the Candidate version supplied by the user is current.

P1-B does not change `ContractCandidateConfirmation` durable schema, the confirmation command
fingerprint, or successful confirmation replay semantics. Candidate
`expectedCandidateVersion` already fences every support replacement because replacement advances
Candidate version. On the first execution of a new confirmation, the enhanced
`CandidateSourceAuthorityValidator` performs the complete current support-authority validation
above before the confirmation is committed.

A completed confirmation replay continues to be judged from its original immutable confirmation
tuple and canonical payload. It does not re-run current support-set validation and does not become
an idempotency mismatch because support role, parse, identity, or association state changed after
the original confirmation. Any later attempt to promote that old confirmation remains fenced by
the existing Candidate/result version comparison and current authority validation.

If any support authority is stale, confirmation returns a conflict and requires an explicit
support-set replacement/rematerialization followed by a new confirmation. Existing confirmation
rows are not deleted. A successful support replacement increments Candidate version, so any old
confirmation becomes stale through the existing version fence.

## 16. Promotion invariants

Promotion remains unchanged at its public and business boundaries:

- same route;
- same `expectedVersion` and `confirmationId` request;
- same `ConfirmedContractPayload` decoding;
- same Candidate-to-Contract mapping;
- same Contract, line-item, and `AwardNoticeReference` persistence.

Promotion continues to call `CandidateSourceAuthorityValidator`, whose internal validation is
extended to cover the current support set. Promotion does not need support counts, support file
IDs, or extraction IDs in its command or payload.

Promotion requires a confirmation whose result Candidate version equals the current Candidate
version. A support replacement or a stale support authority therefore prevents reuse of an old
confirmation. Once a Candidate is `PROMOTED`, support replacement is rejected and the promoted
Contract is not rematerialized or rewritten.

## 17. ReviewRun invariants

Candidate support authority is not ReviewRun material selection:

```text
Candidate Support Authority != ReviewRun Material Selection
```

P1-B does not change:

- `ReviewRunReadinessResolver`;
- `ContractReviewRunMaterial`;
- ReviewRun material manifests or input hashes;
- ReviewRun step registration or execution;
- `FrozenReviewInput`;
- frozen F02 execution.

The existing promoted Contract is the deterministic F02 business input. ReviewRun continues to
freeze its own material authority independently.

## 18. Finding provenance

Finding provenance obeys the same two explicit modes as Candidate fact aggregation. Legacy
evidence fallback and Candidate structured-fact authority are different concepts:

```text
Legacy evidence fallback != Candidate fact authority
```

### 18.1 `PRIMARY_ONLY_COMPAT`

When the support set is empty, `FindingSourceProjectionService` retains its current behavior
unchanged, including its legacy cross-file `AWARD_NOTICE` category fallback. Existing current and
historical Candidates may continue to project award evidence through the existing project-wide
extraction lookup, unbound-support-row checks, value matching, and immutable
`ProjectFile.category == AWARD_NOTICE` filter.

This path is preserved only for backward compatibility. It:

- does not make an unassigned file part of Candidate structured-fact authority;
- cannot change Candidate facts or field states;
- cannot change Candidate Confirmation or Promotion authority;
- does not create a `ContractCandidateSupportAuthority` association;
- does not opt the Candidate into `EXPLICIT_SUPPORT_AUTHORITY`;
- is not additionally hardened by P1-B.

P1-B does not remove, reinterpret, or fail closed this existing fallback merely because the new
support-authority schema/code is present. Removing or hardening it requires a separate explicit
hardening and migration decision; it cannot be presented as additive P1-B behavior.

### 18.2 `EXPLICIT_SUPPORT_AUTHORITY`

When the support set is non-empty, award-related Candidate/F02 source projection must stop using
the legacy project-wide category scan and use:

```text
ProjectContract
  -> promoted Candidate
  -> exact current ContractCandidateSupportAuthority
  -> exact sourceFileId + parseResultId
  -> ExtractionResult
  -> verified locator/excerpt
```

The projection validates the current support association, exact parse, human-confirmed
`AWARD_NOTICE` role, and human-confirmed `SAME_PROJECT` identity before using an extraction row.
It ignores project-wide category matches, unrelated or unassigned award notices, support assigned
only to another Candidate, and any classifier category presented as authority. Supporting
extraction rows remain `candidateId == null`; ownership is proven by the association, not by
illegal rebinding.

If multiple associated notices contribute the same canonical award value, projection retains all
exact matching sources in deterministic order rather than choosing a project-wide or arbitrary
winner. If the Candidate field is `CONFLICT`/`null`, P1-B does not invent a source selection for
the human-confirmed value; award evidence projection is unavailable with a safe conflict reason.

Primary contract and line-item evidence continues to use the existing Candidate-bound exact
primary path in both modes. Neither mode changes F02 verdict semantics.

## 19. Frontend behavior

Add a minimal “关联中标材料” section near Candidate detail/confirmation.

The UI:

- reads the assigned set from Candidate detail server data after every load or save;
- offers only current Project files that the existing server projection reports as parsed,
  `AWARD_NOTICE`, `HUMAN_CONFIRMATION`, `SAME_PROJECT`, and current;
- treats that filtering as UX only; the backend repeats every check;
- displays filename, current effective role, extracted award amount, extracted supplier, and
  field conflict state;
- submits only `expectedCandidateVersion` and selected file IDs with a retained idempotency key;
- explicitly saves a full replacement set;
- refreshes Candidate detail from the server after success or uncertain reconciliation;
- shows server-derived `MISSING`, `CONFIRMED`, or `CONFLICT` states;
- disables support editing for a promoted Candidate;
- leaves the current primary-only Candidate UI behavior unchanged when the support set is empty.

Unconfirmed or stale role/identity files may be shown elsewhere in Project Intake, but they are
not selectable as authoritative support. The browser does not send parse IDs, role decisions,
identity decisions, roles, or fingerprints.

The frontend retains one operation identity while a request outcome is uncertain. It does not
allocate a new key merely because the Candidate is reloaded. A proven successful replay returns
the same resulting Candidate version and support set; an integrity mismatch fails closed.

## 20. DM8 schema design

P1-B requires one new controlled additive migration. Its logical effects are described here;
this specification does not create SQL.

### 20.1 Candidate columns

Add nullable columns to `CONTRACT_CANDIDATE`:

- `SUPPORT_AUTHORITY_VERSION_NO BIGINT`
- `SUPPORT_AUTHORITY_FINGERPRINT VARCHAR(64)`

Add checks requiring the two columns to be both null or both non-null, a positive version when
non-null, and a 64-character fingerprint. Add an ownership/cohort unique key suitable for the
association foreign key, conceptually:

```text
(PROJECT_ID, ID, SUPPORT_AUTHORITY_VERSION_NO)
```

Historical null/null rows are valid and mean implicit empty support set under
`PRIMARY_ONLY_COMPAT`.

### 20.2 `CONTRACT_CANDIDATE_SUPPORT_AUTHORITY`

The new table contains the domain attributes in section 9 and enforces:

- primary key on `SUPPORT_AUTHORITY_ID`;
- candidate/cohort foreign key on
  `(PROJECT_ID, CANDIDATE_ID, AUTHORITY_VERSION_NO)`;
- project/file ownership foreign key on `(PROJECT_ID, SOURCE_FILE_ID)`;
- exact parse foreign key including parse result, project, file, SHA-256, and parse version;
- exact role-decision binding foreign key including decision, project, file, parse, SHA-256,
  parse version, and selected role;
- exact identity-decision binding foreign key including decision, project version, file, parse,
  SHA-256, parse version, and resolution;
- actor foreign key on `CREATED_BY`;
- `EFFECTIVE_ROLE = 'AWARD_NOTICE'` check;
- `IDENTITY_RESOLUTION = 'CONFIRM_SAME_PROJECT'` check;
- positive parse and authority versions;
- 64-character file and fact fingerprints;
- unique `(PROJECT_ID, CANDIDATE_ID, SOURCE_FILE_ID)` so one file appears once in one current set;
- no uniqueness on `SOURCE_FILE_ID` alone, allowing one support file to serve multiple Candidates.

Where the referenced parent tables do not yet expose a matching exact composite unique key, the
new migration adds the necessary unique constraints without changing or weakening existing
keys. It does not delete the existing Candidate/extraction foreign keys.

### 20.3 Indexes

At minimum, design indexes for:

- current set lookup by `(PROJECT_ID, CANDIDATE_ID, AUTHORITY_VERSION_NO, SOURCE_FILE_ID)`;
- reverse ownership lookup by `(PROJECT_ID, SOURCE_FILE_ID, CANDIDATE_ID)`;
- role decision lookup;
- identity decision lookup;
- exact file/parse evidence lookup.

Index definitions and column order must be confirmed by catalog preflight and representative DM8
query/concurrency verification during implementation.

### 20.4 Extraction ownership remains unchanged

`FK_PROJECT_EXTRACTION_CANDIDATE_SOURCE` and
`FK_PROJECT_EXTRACTION_CANDIDATE_PARSE` remain intact. A supporting extraction row is not assigned
the primary Candidate ID. Candidate support evidence is read by exact association plus exact file
and parse.

## 21. Migration compatibility

Proposed file:

```text
db/dm8/CONTRACT__contract_candidate_support_authority.sql
```

Proposed header contract:

```text
MIGRATION-NAMESPACE: CONTRACT
MIGRATION-ID: contract-candidate-support-authority
DEPENDS-ON: project-file-identity-assessment,contract-candidate-authority
EXECUTION: CONTROLLED_MANUAL
IDEMPOTENCY: NOT_ASSUMED
```

The migration is a new branch after the exact parse/role/identity and Candidate-authority
prerequisites. It does not require changing historical migration headers. The controlled
migration map and approved deployment sequence in `db/dm8/README.md` must be extended when the
SQL is implemented.

Before execution, catalog preflight classifies every proposed table, column, check, PK, UK, FK,
and index as `NOT_APPLIED`, `ALREADY_MATCHING`, `PARTIAL/SCHEMA_DRIFT`, or `UNKNOWN`. Only an
approved `NOT_APPLIED` plan may execute DDL. `ALREADY_MATCHING` is not reapplied;
`PARTIAL/SCHEMA_DRIFT` and `UNKNOWN` fail closed into a separate repair decision.

There is no historical data backfill. Existing rows retain null support metadata and no support
rows. Application reads interpret that state as `PRIMARY_ONLY_COMPAT`. Rollback never deletes
durable support rows or rewrites promoted Contracts.

## 22. Idempotency

Use the existing durable DM8 `IdempotencyPort` with a dedicated scope, for example
`CONTRACT_CANDIDATE_SUPPORT_REPLACE`.

### 22.1 Stable command fingerprint

The idempotency command fingerprint contains only stable user-operation identity:

- fingerprint schema/version token;
- actor ID;
- project ID;
- Candidate ID;
- expected Candidate version;
- stable sorted, de-duplicated support file ID set.

It must not contain file SHA-256, parse identity/version, role decision, identity decision,
Project version, extraction facts, fact fingerprints, or any other server state that can change
after the user issued the command.

### 22.2 Authority fingerprint

`supportAuthorityFingerprint` is separate from idempotency command identity. On the first
execution, after locks and fresh authority validation, the server calculates it from:

- authority mode;
- exact support file IDs and hashes;
- parse IDs and versions;
- role decisions;
- identity decisions and Project versions;
- eligible extraction facts and their fingerprints.

It is stored in Candidate support metadata and represented by the current association cohort. A
later authority change can make that cohort stale for confirmation/promotion, but it does not
change the identity of the already completed replacement command.

### 22.3 Replay classification

Required behavior:

- same key and same stable command fingerprint with a successful record: verify the durable
  Candidate/support current state still corresponds to the command result; if so, return that
  result without current authority revalidation and without another version increment;
- a current role, parse, identity, Project version, or fact change after that successful commit
  does not turn the old key into an idempotency mismatch while the original command result is
  still the current Candidate/support state;
- if the Candidate has advanced through a later successful business mutation, return
  `RESULT_SUPERSEDED` under the existing `CONFLICT` HTTP/business semantics; do not reapply the old
  support set and do not allocate a new operation for the same user action;
- if the record is `IN_PROGRESS`, its outcome is unproven, or its result resource/state cannot be
  proven, preserve the same key and fail closed;
- same key with a different actor, project, Candidate, expected Candidate version, or canonical
  support file ID set is an idempotency fingerprint conflict;
- rollback before commit -> no Candidate/support/audit success fact remains, and the uncommitted
  claim follows the repository's existing rollback semantics.

For a successful record, “result still corresponds” requires the recorded Candidate resource to
exist at the result version (`expectedCandidateVersion + 1`), retain the command's canonical
support file ID set, and contain an internally consistent current support cohort/fingerprint. A
higher Candidate version, `PROMOTED` state, or another later durable mutation is superseded, not
a license to replay the write. A lower/missing/inconsistent result is unproven and fails closed.

Because the schema stores only the current support set, replay guarantees are bounded to proving
the current durable command result. P1-B does not claim permanent reconstruction of every
historical support cohort.

## 23. Security

- Authentication and existing Project/Candidate authorization apply before command execution.
- All resources are loaded through the path Project ownership boundary.
- A foreign-project support ID is rejected through the scoped lookup; the response must not leak
  another Project's metadata.
- The server resolves every trusted authority field; client-supplied role, category, parse,
  decision, identity, or fingerprint values are rejected/ignored by not being part of the command
  contract.
- `UNKNOWN`, machine proposal, legacy classifier category, stale decision, missing parse, and
  non-human authority all fail closed.
- Operation audit and technical logs contain IDs, safe enum states, counts, versions, and reason
  codes only. They do not contain raw parsed text, excerpts, monetary values, customer names,
  prompts, model responses, or secrets.
- Supporting facts remain DM8 business facts. Redis/TongRDS is not used as authority or the
  idempotency source of truth.

## 24. Failure semantics

| Condition | Result |
|---|---|
| Malformed body, UUID, negative version, or invalid Idempotency-Key | `INVALID_ARGUMENT` / current 400 envelope |
| Project, Candidate, or project-scoped file not found | current `NOT_FOUND` semantics |
| Candidate version mismatch | `CONFLICT`; no writes |
| Candidate already promoted | `CONFLICT`; no writes |
| Primary authority stale | `CONFLICT`; no writes |
| Support parse/role/identity missing, stale, non-human, wrong role, or not same project | `CONFLICT`; no writes |
| Same idempotency key with different fingerprint | `CONFLICT`; original operation unchanged |
| Successful same-key retry and original result is still current | Replay original result; no authority revalidation or version increment |
| Successful same-key retry after a later Candidate mutation | `RESULT_SUPERSEDED` / `CONFLICT`; do not reapply old set |
| Concurrent CAS loss | `CONFLICT`; transaction rolls back |
| Multiple distinct eligible fact values | successful replacement; Candidate field `null` and state `CONFLICT` |
| No eligible support value for one award fact | successful replacement; field `null` and state `MISSING` |
| Database outcome cannot be proven | do not label success or ordinary validation failure; preserve key and fail closed |

No validation branch silently drops an invalid support file and proceeds with the remainder. The
replacement is all-or-nothing.

## 25. Concurrency

The transaction uses the fixed order defined in section 12: non-locking Candidate discovery,
then all primary/support ProjectFiles in stable UUID order, then Project, then Candidate. It must
never acquire Project before a requested support file. Current role, identity, Candidate
confirmation, and promotion flows already use File -> Project ordering; Project -> support file
would create a lock inversion.

Candidate `expectedCandidateVersion` and repository CAS are the final write fence. Two concurrent
different replacements can produce at most one committed Candidate version. The loser rolls back
its association changes, audit success, and idempotency success.

Before commit, all rows inserted into the association table are constrained to the new Candidate
support cohort. Exact composite foreign keys prevent cross-project, cross-file, cross-parse, and
decision-ID substitution. The implementation must verify on representative DM8 that lock and CAS
behavior matches these invariants; H2 or method names are not sufficient evidence.

A concurrent role/identity update either serializes on the shared locked source row or causes the
replacement's final current-authority recheck/CAS to fail. It cannot leave a committed association
that was known stale at commit time.

Adversarial concurrency verification must orchestrate a role/identity confirmation that holds a
support-file lock while requesting the Project lock, then start support replacement. Replacement
must wait on the file before taking Project, complete without deadlock after release, and produce
only one valid authority outcome. A second adversarial case submits overlapping support sets in
opposite request order and proves stable UUID lock sorting prevents file-to-file inversion.

## 26. Late and out-of-order authority

The final result is independent of confirmation order:

```text
primary Candidate first
  -> award notice becomes authoritative later
  -> user explicitly assigns it
  -> Candidate rematerializes
```

and:

```text
award notice authoritative first
  -> primary Candidate materializes later
  -> user explicitly assigns the notice
  -> same canonical Candidate facts
```

Role or identity confirmation of an award notice never auto-assigns it to any Candidate. The
explicit replacement command is always required.

## 27. Testing design

### 27.1 Domain/application

- Empty support set preserves the exact current primary-only Candidate behavior, including
  primary `AWARD_*` compatibility.
- An unassigned authoritative award notice is ignored by Candidate materialization.
- An assigned authoritative award notice contributes only the three allowed award facts.
- Award-notice contract number, contract amount, party, and line-item facts cannot overwrite or
  introduce primary fields.
- Primary `AWARD_*` is excluded in explicit mode and restored after replacing the support set
  with `[]`.
- In explicit mode, primary `AWARD_AMOUNT=100` plus one assigned support
  `AWARD_AMOUNT=98` produces Candidate `98` and field state `CONFIRMED`, not `CONFLICT`.
- Multiple award notices with the same normalized amount/text produce one value.
- Two assigned award notices with `AWARD_AMOUNT=98` and `99` produce `null` plus `CONFLICT`.
- Visible primary award provenance remains readable in explicit mode but cannot influence award
  business fields, field states, or conflict calculation.
- Stale parse, stale role decision, stale identity decision, wrong role, non-human role,
  non-same-project identity, unavailable file, and foreign Project each reject the whole command.
- A promoted Candidate rejects support replacement.
- Candidate ID and primary authority fields remain unchanged.
- Every newly committed replacement increments Candidate version exactly once; replay does not.
- A prior confirmation becomes stale after support replacement.
- Different role/identity confirmation order yields the same final Candidate after explicit
  assignment.
- One support file can be explicitly associated with two Candidates without implicit assignment.
- Canonical file-ID sorting makes request order irrelevant.

### 27.2 DM8/repository/transaction

- Candidate/project/file ownership constraints reject cross-owner rows.
- Exact parse/hash/version tuple constraints reject mismatches.
- Exact role and identity decision tuple constraints reject substituted decisions.
- Effective role and identity checks allow only `AWARD_NOTICE` and `CONFIRM_SAME_PROJECT`.
- Unique Candidate/file rejects duplicate current associations while allowing the same file under
  another Candidate.
- Existing primary extraction foreign keys remain present and effective.
- Supporting extraction rows remain unbound and are read through association plus exact parse.
- Candidate CAS replacement updates facts, lines, cohort, and version atomically.
- Injected failures after delete, Candidate CAS, association insert, audit, and idempotency steps
  roll back the complete transaction.
- Concurrent replacements produce one winner and no mixed support cohort.
- A support-file role/identity confirmation holding File then requesting Project cannot deadlock
  with replacement; replacement locks all sorted files before Project.
- Overlapping replacement requests with reversed input order still acquire file locks in the same
  canonical order.
- The stable command fingerprint is unchanged when file hash/parse/role/identity/facts change
  after a successful operation; `supportAuthorityFingerprint` changes only on a later fresh
  replacement.
- Successful old-key replay returns the original current result without a version increment even
  when current authority has since changed.
- Successful old-key replay after a later Candidate mutation returns `RESULT_SUPERSEDED` and does
  not restore the old support set.
- `IN_PROGRESS`/unproven replay preserves the exact key and performs no fresh mutation.
- Representative DM8 verification covers row locking, composite FKs, null historical Candidate
  metadata, and delete/update/insert ordering.

### 27.3 Confirmation/promotion

- Current primary and support authorities permit confirmation.
- Any stale support authority blocks confirmation.
- Confirmation command/API/payload/schema stay unchanged.
- A first confirmation execution validates the complete current support authority.
- A completed confirmation replay uses its original immutable confirmation tuple and canonical
  payload without current support revalidation or schema changes.
- Support replacement after confirmation makes that confirmation unusable for promotion.
- Promotion API and payload remain unchanged.
- Existing primary-only confirmation/promotion regressions remain green.
- Promoted Candidate support state is immutable.

### 27.4 Finding source projection

- **Case A:** a current or historical `PRIMARY_ONLY_COMPAT` Candidate with existing unassigned
  `AWARD_NOTICE` evidence retains the current legacy cross-file category-fallback projection.
- **Case B:** after the same Candidate enters `EXPLICIT_SUPPORT_AUTHORITY`, award evidence resolves
  only through its exact current Candidate support association and parse.
- **Case C:** in explicit mode, unrelated/unassigned award notices and support assigned only to
  another Candidate are ignored even when their immutable classifier category is
  `AWARD_NOTICE`.
- Stale explicit support parse/role/identity makes projection unavailable rather than falling
  back to a project-wide category match.
- The preserved primary-only fallback never changes Candidate facts, field states, confirmation,
  or promotion and never creates a support association.
- Primary contract and line-item evidence behavior remains unchanged.
- F02 verdicts are unchanged by provenance projection availability.

### 27.5 Frontend

- Eligible current `AWARD_NOTICE` + `SAME_PROJECT` files are shown.
- Unparsed, unconfirmed, stale, or non-same-project files are not selectable.
- Save sends only expected Candidate version and file IDs, plus the retained idempotency key.
- Reload reconstructs selection from server support associations.
- Duplicate/ordering differences do not change displayed state.
- Multiple-value conflict is displayed.
- Explicit mode may display primary award provenance, but its field state is computed only from
  assigned support award facts; primary `100` plus support `98` displays `98 / CONFIRMED`.
- Empty support set retains existing primary-only presentation.
- Promoted Candidate support editing is disabled.
- Uncertain outcome retains the same operation key until server result is proven.

### 27.6 Compatibility and historical rows

- Historical Candidate rows with null support metadata read as `PRIMARY_ONLY_COMPAT`.
- No migration backfill is required.
- Existing promoted Contract and ReviewRun fixtures reopen unchanged.
- Existing primary-only/historical award evidence projection retains its legacy category fallback.
- Existing primary-only F02 regression remains green.
- Existing single-contract and multiple-Candidate behavior remains unchanged unless a support set
  is explicitly saved.

## 28. Browser/full-chain acceptance

Use a new safe Project with one contract body and one award notice:

```text
CONTRACT_BODY upload/parse
  -> human role confirmation
  -> human SAME_PROJECT identity confirmation
  -> Candidate materialized in PRIMARY_ONLY_COMPAT

AWARD_NOTICE upload/parse
  -> human role confirmation
  -> human SAME_PROJECT identity confirmation
  -> explicit Candidate support-set save
  -> Candidate rematerialized in EXPLICIT_SUPPORT_AUTHORITY
  -> award amount/supplier/credit code visible with exact evidence
  -> human Candidate confirmation
  -> unchanged promotion
  -> Contract contains AwardNoticeReference
  -> unchanged ReviewRun
  -> frozen F02
```

Acceptance requires:

- browser request contains only version and support file IDs;
- Candidate ID and primary authority fields remain unchanged;
- Candidate and support-set versions advance as designed;
- Candidate detail reload returns the same assigned set and exact evidence;
- a prior confirmation cannot survive a later support change;
- promotion persists the human-confirmed award reference through the existing mapping;
- ReviewRun material selection remains independent;
- F02 award checks have the promoted upstream award facts and are not `NOT_EVALUATED` merely
  because Candidate materialization lacked award data;
- P1-C/F02 rule outcomes and tolerances remain unchanged;
- browser refresh and backend restart rediscover Candidate support state from DM8.

Also verify the inverse flow by replacing the set with `[]`: the same Candidate returns to
`PRIMARY_ONLY_COMPAT`, primary-only award facts are restored, version advances, and the former
confirmation is stale.

## 29. Rollout and rollback

Rollout order:

1. Perform read-only catalog preflight for all new/altered schema effects.
2. Approve backup, execution, stop, and rollback procedures for the controlled migration.
3. Apply the new migration before enabling P1-B writes.
4. Deploy backend read compatibility first: null support metadata reads as empty/primary-only.
5. Enable the replacement command and extended validation/provenance paths.
6. Enable the frontend association UI only after the backend endpoint is available.
7. Run focused, full backend/frontend/FastAPI regressions and representative DM8 concurrency
   verification.
8. Run the browser/full-chain acceptance with sanitized evidence.

Safe rollback disables new support-set writes and hides the UI while preserving read support for
already persisted associations. It does not delete the new table/columns, clear support rows,
rewrite Candidate facts, roll back promoted Contracts, or invalidate completed ReviewRuns. Any
data/schema repair is a separately approved controlled operation.

## 30. Non-regression matrix

| Case | Input/state | Required result |
|---|---|---|
| A | Only `CONTRACT_BODY`; no support rows | Exact current primary-only behavior, including current primary award-fact compatibility |
| B | `PRIMARY_ONLY_COMPAT` Candidate plus existing unassigned `AWARD_NOTICE` evidence | Candidate/confirmation/promotion/F02 facts remain primary-only; current legacy category-fallback evidence projection remains unchanged and does not become fact authority |
| C | Same Candidate enters non-empty explicit support mode | Candidate gains only allowed support award facts; award evidence uses only exact assigned support authority |
| D | Explicit mode also has unrelated/unassigned `AWARD_NOTICE` | Unrelated/unassigned material is ignored for Candidate facts and award evidence even if classifier category matches |
| E | Assigned award notice later has stale parse/role/identity | Confirmation and promotion fail closed; explicit award evidence does not fall back to project-wide category scan |
| F | Replace `[A, ...]` with `[]` | Same Candidate returns to primary-only compatible facts and legacy evidence projection; version increments; old confirmation stale |
| G | Historical Candidate row with no support metadata | Readable without backfill; existing evidence projection and old promotion/Contract/ReviewRun remain valid |
| H | Candidate already `PROMOTED` | Support set cannot change; Contract and ReviewRun are not rewritten |

## 31. Design acceptance checklist

- [x] No `CandidateV2` or parallel lifecycle.
- [x] No reinterpretation of primary Candidate source fields or `sourceFactFingerprint`.
- [x] No automatic project-wide award assignment.
- [x] Supporting role is `AWARD_NOTICE` only in v1.
- [x] `PRIMARY_ONLY_COMPAT` and `EXPLICIT_SUPPORT_AUTHORITY` are modes, not precedence.
- [x] Empty support set preserves current behavior, including primary award facts.
- [x] Primary-only current/historical Candidates preserve the existing legacy cross-file
  `AWARD_NOTICE` category evidence fallback.
- [x] The legacy evidence fallback is not Candidate structured-fact authority and P1-B does not
  harden or remove it.
- [x] Non-empty support set excludes primary award facts and admits only support `AWARD_*`.
- [x] Explicit-mode award evidence uses exact assigned support authority and never project-wide
  category fallback.
- [x] Both mode transitions rematerialize facts and increment Candidate version.
- [x] Conflicts remain visible and human-resolved.
- [x] Support associations bind exact file/parse/role/identity authority.
- [x] Candidate Confirmation business payload/schema is unchanged; authority validation is
  extended.
- [x] Promotion API/payload/mapping is unchanged.
- [x] ReviewRun authority and material model are unchanged.
- [x] F02 verdict semantics are unchanged.
- [x] Supporting extraction rows are not rebound or duplicated.
- [x] Schema is additive; no existing migration is edited.
- [x] Historical Candidate rows require no backfill.
- [x] The design contains no unresolved placeholder.

## 32. Approval boundary

This document is a design specification only. It does not authorize production code, tests, SQL,
migration execution, deployment, merge, or push. After specification review and approval, a
separate implementation-plan stage is required before implementation begins.
