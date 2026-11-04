import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import test from "node:test";

const moduleUrl = new URL("./candidateSupportAuthorityPresentation.ts", import.meta.url);
async function presentation() {
  assert.ok(existsSync(moduleUrl), "Task 17 support eligibility/presentation is missing");
  return import(moduleUrl.href);
}

const file = { id: "award-a", projectId: "project-1", originalFilename: "中标通知.pdf",
  category: "OTHER", ingestionStatus: "AVAILABLE", parseStatus: "PARSED",
  effectiveRole: "AWARD_NOTICE", effectiveRoleSource: "HUMAN_CONFIRMATION",
  identityDecisionStatus: "CONFIRMED", effectiveIdentityStatus: "SAME_PROJECT",
  identityCurrent: true, excludedFromProjectUse: false };
const detail = { candidate: { id: "candidate-1", projectId: "project-1", versionNo: 4,
  promotionState: "DRAFT", awardAmount: 98, awardSupplier: "供应商甲" },
  fieldStates: { AWARD_AMOUNT: "CONFIRMED", AWARD_SUPPLIER: "MISSING" }, evidence: [],
  supportAuthorities: [] };

test("only current server-projected human award and same-project authority is selectable", async () => {
  const { eligibleCandidateSupportFiles } = await presentation();
  assert.deepEqual(eligibleCandidateSupportFiles([file], "project-1"), [file]);
  for (const patch of [
    { projectId: "foreign" }, { ingestionStatus: "UPLOAD_PENDING" }, { ingestionStatus: "UPLOAD_FAILED" },
    { parseStatus: "PENDING" }, { parseStatus: "FAILED" }, { parseStatus: "UNSUPPORTED" },
    { parseStatus: "NOT_APPLICABLE" }, { effectiveRole: "CONTRACT_BODY" }, { effectiveRole: "UNKNOWN" },
    { effectiveRole: undefined, category: "AWARD_NOTICE", suggestedRole: "AWARD_NOTICE" },
    { effectiveRoleSource: "LEGACY_EFFECTIVE" }, { effectiveRoleSource: "NO_AUTHORITATIVE_ROLE" },
    { effectiveRoleSource: undefined }, { identityDecisionStatus: "NEEDS_CONFIRMATION" },
    { identityDecisionStatus: undefined }, { effectiveIdentityStatus: "LIKELY_SAME_PROJECT" },
    { effectiveIdentityStatus: "NOT_SAME_PROJECT" }, { effectiveIdentityStatus: "CONFLICT" },
    { effectiveIdentityStatus: undefined }, { identityCurrent: false }, { identityCurrent: undefined },
    { excludedFromProjectUse: true }, { excludedFromProjectUse: undefined },
  ]) assert.deepEqual(eligibleCandidateSupportFiles([{ ...file, ...patch }], "project-1"), [], JSON.stringify(patch));
});

test("historical primary-only projection preserves server values and disables unchanged empty save", async () => {
  const { primaryOnlySupportAuthorityState, candidateSupportAuthorityPresentation } = await presentation();
  const legacy = { ...detail, supportAuthorities: undefined };
  assert.deepEqual(primaryOnlySupportAuthorityState(legacy), { authorityMode: "PRIMARY_ONLY_COMPAT",
    supportAuthorityVersionNo: null, supportAuthorityFingerprint: null, supportAuthorities: [] });
  const view = candidateSupportAuthorityPresentation(legacy, [file], []);
  assert.deepEqual(view.assignedIds, []);
  assert.equal(view.canSave, false);
  assert.equal(view.canClear, false);
  assert.equal(view.awardAmount, 98);
  assert.equal(view.awardSupplier, "供应商甲");
  assert.equal(view.amountState, "CONFIRMED");
  assert.equal(view.supplierState, "MISSING");
});

test("full-set selection is canonical; promoted and invalid selections never permit saving", async () => {
  const { candidateSupportAuthorityPresentation: present } = await presentation();
  const files = [file, { ...file, id: "award-b" }];
  const view = present(detail, files, ["award-b", "award-a", "award-b"]);
  assert.deepEqual(view.selectedIds, ["award-a", "award-b"]);
  assert.equal(view.canSave, true);
  assert.equal(view.canClear, true);
  const unchanged = { ...detail, supportAuthorities: [{ sourceFileId: "award-b" }, { sourceFileId: "award-a" }] };
  assert.equal(present(unchanged, files, ["award-a", "award-b"]).canSave, false);
  assert.equal(present(detail, files, ["foreign"]).canSave, false);
  const promoted = present({ ...detail, candidate: { ...detail.candidate, promotionState: "PROMOTED" } }, files, ["award-a"]);
  assert.equal(promoted.editable, false);
  assert.equal(promoted.canSave, false);
  assert.equal(promoted.canClear, false);
});

test("assigned stale/missing files remain visible for removal and conflict states stay server-owned", async () => {
  const { candidateSupportAuthorityPresentation: present } = await presentation();
  const current = { ...detail, fieldStates: { AWARD_AMOUNT: "CONFLICT", AWARD_SUPPLIER: "CONFIRMED" },
    candidate: { ...detail.candidate, awardAmount: null },
    supportAuthorities: [{ sourceFileId: "award-a", originalFilename: "中标通知.pdf", current: false },
      { sourceFileId: "missing", originalFilename: "旧通知.pdf", current: false }] };
  const view = present(current, [{ ...file, identityCurrent: false }], ["award-a", "missing"]);
  assert.deepEqual(view.assignedIds, ["award-a", "missing"]);
  assert.equal(view.rows.length, 2);
  assert.equal(view.rows[0].eligible, false);
  assert.equal(view.rows[0].current, false);
  assert.equal(view.rows[1].filename, "旧通知.pdf");
  assert.equal(view.awardAmount, null);
  assert.equal(view.amountState, "CONFLICT");
  assert.equal(present(current, [file], []).canSave, true);
});
