# ADR-008: Contract ReviewSession and durable file ingestion

## Context

The existing direct F02 endpoint represents one deterministic rule execution and creates an
`AuditTask`. It does not represent the user-owned lifecycle of preparing source materials for a
new review. Reusing `AuditTask`, contract-level latest results, Redis, browser file bytes, or a
database BLOB for this lifecycle would merge different facts and make upload crash recovery or
replacement history ambiguous.

The approved Phase B boundary ends when a contract-owned review session reaches `READY`. OCR,
extraction, AI, processing workers, queues, session-driven F02 and session-scoped results are not
part of this decision.

## Decision

- Own `ContractReviewSession`, immutable `ReviewDocument` metadata and `MaterialBinding` in the
  `contract` module. Keep `review` independent of `contract` and do not change `AUDIT_TASK`.
- Record `ContractReviewSession.contractVersion` as the `CONTRACT.VERSION_NO` observed when the
  session is created. It is an audit snapshot, not a default future `AuditTask.sourceVersion`.
- Limit the session state machine to `PREPARING_MATERIALS -> READY`. Limit document ingestion to
  `UPLOAD_PENDING -> AVAILABLE|UPLOAD_FAILED`. `READY` is terminal in Phase B.
- Treat document metadata as append-only. Replacement creates a new document and atomically moves
  the current binding; removal deactivates the binding and never deletes the old document or object.
- Use a server-owned slot key. Contract body, award notice and bidding document use singleton
  slots. Tender and other documents use a kind plus server-generated UUID. Enforce one binding per
  `(SESSION_ID, SLOT_KEY)` and use composite document/binding foreign keys to prevent cross-session,
  cross-slot or cross-kind references.
- Keep `ContractReviewSession.VERSION_NO` as the aggregate concurrency token. Repository Ports
  expose explicit status/version CAS operations; local adapters use one atomic critical section and
  reproduce unique-slot and stale-version rejection.
- Keep the additive V005 lifecycle tombstone as schema and historical-data compatibility, not as a
  third review status or a current product operation. Normal contract/global reads exclude
  tombstoned rows, while direct identity lookup retains enough evidence for historical same-key
  outcome recovery. The current product exposes no ReviewSession delete API or frontend action.
  Existing internal CAS/repository behavior remains unreachable compatibility code and must not be
  advertised as an available workflow. The Phase B business state machine remains
  `PREPARING_MATERIALS -> READY`.
- A historical tombstone preserves the Session row, append-only ReviewDocument history,
  MaterialBinding, storage objects and operation audit. It never cascades or mutates `AUDIT_TASK`.
- Own `FileStoragePort` in contract Application. Its DTOs expose only opaque handles, hashes, sizes
  and streams, never a real filesystem path. The filesystem adapter keeps `.staging` and `objects`
  below one normalized writable root on the same `FileStore`.
- Stream uploads through a fixed 64 KiB buffer, calculate lowercase SHA-256 while staging, keep only
  a small signature header in memory, and accept only mutually consistent PDF/PNG/JPEG extension,
  MIME and magic bytes. Client filenames are sanitized metadata only.
- Promote only after the staging stream is closed. A new object uses same-`FileStore` atomic
  create-only hard-link publication (`Files.createLink(stableTarget, stagingSource)`). The adapter
  probes startup capability by publishing an absent target and then proving a different second
  source cannot replace it. An existing object is streamed and reused only when its size and
  SHA-256 match. Unsupported hard links, different `FileStore`s, or an inconclusive capability
  probe fail startup; there is no rename, copy/delete, or overwrite fallback.
- Use four short boundaries for upload:
  1. **START (`REQUIRES_NEW`)**: claim durable idempotency, reserve session version `N -> N+1`, create
     `UPLOAD_PENDING`, and append the START audit.
  2. **PROMOTE (outside a database transaction)**: atomically publish the staged object.
  3. **COMPLETE (`REQUIRES_NEW`)**: require the reserved version, advance `N+1 -> N+2`, mark the
     document available, CAS/create its binding, append success audit and mark idempotency succeeded.
  4. **FAILURE (`REQUIRES_NEW`)**: for an ordinary promote failure, release the reservation with
     `N+1 -> N+2`, mark the document failed, append failure audit and mark idempotency failed.
- Do not guess after an ambiguous COMPLETE or failed FAILURE closure. Re-read durable idempotency;
  return replay only for `SUCCEEDED`, terminal conflict for `FAILED`, and keep `IN_PROGRESS` for
  recovery when the outcome is not proven.
- Recover abandoned pending uploads from the durable document/result-resource identity, so recovery
  does not require the original browser key. A matching stable object completes the operation. A
  stale, provably missing or mismatched object closes failure. Unknown storage state remains
  `IN_PROGRESS`; recovery never overwrites `SUCCEEDED`. UNKNOWN carries only a safe diagnostic
  reason (`ACCESS_DENIED`, `FILESYSTEM_ERROR`, `IO_ERROR`, or `PROVIDER_ERROR`) and never a path,
  exception message, or Throwable; the reason does not alter the fail-closed business decision.
- Keep one browser-memory upload attempt bound to the selected `File` object reference. Its starting
  expected version and Idempotency-Key remain immutable through ambiguous/`IN_PROGRESS` retries even
  when server refresh advances `VERSION_NO`. Success clears it; terminal failure requires a new
  explicit operation. Browser refresh loses the File reference and must use server recovery rather
  than bind an old key to a newly selected file.
- Store formal session/document/binding/idempotency/audit facts in DM8. Redis/TongRDS is not used for
  this workflow and may be unavailable without losing these facts.
- Use the filesystem adapter only for one Java node or nodes sharing the same filesystem. A future
  multi-node deployment with node-local disks requires another `FileStoragePort` adapter.

## Consequences

The new session workspace can restore server-side material metadata and content after refresh,
retain replacement/removal history, reject stale writes, recover crashes between START, PROMOTE
and COMPLETE, and filter any historical V005 tombstone without destroying its evidence. Storage
objects are intentionally retained when a binding or session is removed; retention and archival
policy remain an operational follow-up.

The direct `/agent-review` F02 flow, its five rules, `AuditTask` lifecycle and contract-level latest
results are unchanged. A newly created session must not mount or display those old contract-level
results. A later phase must separately decide processing facts, extraction versioning, the real
`AuditTask.sourceVersion`, and any durable queue.

## Status

Accepted for Phase B and additive V005 schema/history compatibility. The prior public/user-visible
ReviewSession deletion decision is withdrawn; no current product route creates new tombstones.
Domain, local repositories,
storage behavior, HTTP/security, idempotency and recovery are locally automated-test verified.
**V004/V005 AND DM8 TRANSACTION BEHAVIOR ARE NOT VERIFIED ON REAL DM8.** Browser runtime smoke,
dev29/customer, TongWeb, TongRDS and TongHttpServer are also
**NOT VERIFIED** by this change.
