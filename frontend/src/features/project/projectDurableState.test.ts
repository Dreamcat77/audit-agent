import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import test from "node:test";
import * as projectDetailViewModel from "./projectDetailViewModel.ts";
import type { CandidateAuthorityMode, CandidateDetail, ContractCandidate, ProjectFile } from "./types";

const { candidateNavigation, candidateWorkspaceMode, canSelectNewUpload,
  candidateFactGroups, formalContractForCandidate, provenPromotedContractId } = projectDetailViewModel;

test("support authority data types retain legacy null metadata and server-authorized field states", () => {
  const modes: CandidateAuthorityMode[] = ["PRIMARY_ONLY_COMPAT", "EXPLICIT_SUPPORT_AUTHORITY"];
  const historicalCandidate: ContractCandidate = {
    id: "candidate-1", projectId: "project-1", sourceContractFileId: "file-1", contractNo: null,
    contractName: null, contractType: null, partyA: null, partyB: null, partyBCreditCode: null,
    contractAmount: null, amountInWords: null, awardAmount: null, awardSupplier: null,
    awardSupplierCreditCode: null, lineItems: [{ id: "line-1", projectId: "project-1", candidateId: "candidate-1",
      itemKey: "ITEM", itemName: "Item", quantity: null, unitPrice: null, declaredAmount: null, sortNo: 1 }],
    promotionState: "DRAFT", versionNo: 0, promotedContractId: null, promotedConfirmationId: null,
    promotedBy: null, promotedAt: null, createdAt: "2026-09-20T00:00:00Z", updatedAt: "2026-09-20T00:00:00Z",
  };
  const sourceFile: ProjectFile = {
    id: "file-1", projectId: "project-1", parentArchiveFileId: null, normalizedRelativePath: null,
    originalFilename: "contract.pdf", mediaType: "application/pdf", category: "CONTRACT_BODY",
    ingestionStatus: "AVAILABLE", parseStatus: "PARSED", sizeBytes: 1, parsedText: "contract",
    parseMethod: "PDF_TEXT", parseEngine: "PDFBox", parseConfidence: 1, pageCount: 1, parseDurationMs: 1,
    safeReasonCode: null, createdAt: "2026-09-20T00:00:00Z", updatedAt: "2026-09-20T00:00:00Z",
    progression: { stage: "READY", blocker: null, availableActions: [], candidateId: "candidate-1", contractId: null },
  };
  const historicalDetail: CandidateDetail = {
    candidate: historicalCandidate, sourceFile, evidence: [], fieldStates: { AWARD_AMOUNT: "CONFLICT", AWARD_SUPPLIER: "MISSING" },
    lineItemFieldStates: { ITEM: { LINE_ITEM_NAME: "CONFIRMED" } },
  };
  const authorizedProjection: Pick<CandidateDetail, "authorityMode" | "supportAuthorityVersionNo"
    | "supportAuthorityFingerprint" | "supportAuthorities"> = {
    authorityMode: modes[0], supportAuthorityVersionNo: null, supportAuthorityFingerprint: null,
    supportAuthorities: [],
  };
  const detail: CandidateDetail = { ...historicalDetail, ...authorizedProjection,
    candidate: { ...historicalCandidate, supportAuthorityVersionNo: null, supportAuthorityFingerprint: null } };
  for (const version of [historicalDetail, detail]) {
    const groups = candidateFactGroups(version);
    assert.deepEqual(groups.scalarStates, { AWARD_AMOUNT: "CONFLICT", AWARD_SUPPLIER: "MISSING" });
    assert.deepEqual(groups.lineItems[0].fieldStates, { LINE_ITEM_NAME: "CONFIRMED" });
    assert.equal(groups.lineItems[0].itemName, "Item");
    assert.equal(candidateWorkspaceMode(version.candidate, null), "CONFIRMATION");
  }
});

test("old Candidate Detail payload keeps the primary-only path after optional authority fields are added", async () => {
  const oldCandidateDetailPayload = {
    candidate: {
      id: "candidate-legacy", projectId: "project-1", sourceContractFileId: "file-contract",
      sourceParseResultId: "parse-contract", sourceRoleDecisionId: "role-contract",
      sourceIdentityDecisionId: "identity-contract", contractNo: "C-LEGACY",
      contractName: "Legacy Contract", contractType: "GOODS", partyA: "Buyer", partyB: "Supplier",
      partyBCreditCode: "91350000LEGACY", contractAmount: 100, amountInWords: "ONE HUNDRED",
      awardAmount: 98, awardSupplier: "Award Supplier", awardSupplierCreditCode: "91350000AWARD",
      lineItems: [], promotionState: "DRAFT", versionNo: 4, promotedContractId: null,
      promotedConfirmationId: null, promotedBy: null, promotedAt: null,
      createdAt: "2026-09-20T00:00:00Z", updatedAt: "2026-09-20T00:00:00Z",
    },
    sourceFile: { id: "file-contract", projectId: "project-1", parentArchiveFileId: null,
      normalizedRelativePath: null, originalFilename: "contract.pdf", mediaType: "application/pdf",
      category: "CONTRACT_BODY", ingestionStatus: "AVAILABLE", parseStatus: "PARSED", sizeBytes: 1,
      parsedText: "legacy", parseMethod: "PDF_TEXT", parseEngine: "PDFBox", parseConfidence: 1,
      pageCount: 1, parseDurationMs: 1, safeReasonCode: null, createdAt: "2026-09-20T00:00:00Z",
      updatedAt: "2026-09-20T00:00:00Z", progression: { stage: "READY", blocker: null,
        availableActions: [], candidateId: "candidate-legacy", contractId: null } },
    evidence: [], fieldStates: { AWARD_AMOUNT: "CONFIRMED" }, lineItemFieldStates: {},
  };
  const types = readFileSync(new URL("./types.ts", import.meta.url), "utf8");
  const candidateDetailMatch = /export\s+interface\s+CandidateDetail\s*\{([\s\S]*?)\n\}/.exec(types);
  assert.ok(candidateDetailMatch, "types.ts must expose one bounded CandidateDetail interface block");
  const candidateDetailBlock = candidateDetailMatch[1];

  assert.match(candidateDetailBlock, /^\s*authorityMode\?\s*:\s*CandidateAuthorityMode\s*;?\s*$/m);
  assert.match(candidateDetailBlock, /^\s*supportAuthorityVersionNo\?\s*:\s*(?:number\s*\|\s*null)\s*;?\s*$/m);
  assert.match(candidateDetailBlock, /^\s*supportAuthorityFingerprint\?\s*:\s*(?:string\s*\|\s*null)\s*;?\s*$/m);
  assert.match(candidateDetailBlock, /^\s*supportAuthorities\?\s*:\s*CandidateSupportAssociation\s*\[\s*\]\s*;?\s*$/m);
  assert.deepEqual(candidateFactGroups(oldCandidateDetailPayload), {
    scalarStates: { AWARD_AMOUNT: "CONFIRMED" }, lineItems: [],
  });
  assert.equal(candidateWorkspaceMode(oldCandidateDetailPayload.candidate, null), "CONFIRMATION");
  const presentationModuleUrl = new URL("./candidateSupportAuthorityPresentation.ts", import.meta.url);
  assert.ok(existsSync(presentationModuleUrl),
    "Task 17 must provide candidateSupportAuthorityPresentation.ts before historical support state can default");
  const presentationModule = await import(presentationModuleUrl.href);
  assert.equal(typeof presentationModule.primaryOnlySupportAuthorityState, "function",
    "candidateSupportAuthorityPresentation must export primaryOnlySupportAuthorityState");
  assert.deepEqual(presentationModule.primaryOnlySupportAuthorityState(oldCandidateDetailPayload), {
    authorityMode: "PRIMARY_ONLY_COMPAT",
    supportAuthorityVersionNo: null,
    supportAuthorityFingerprint: null,
    supportAuthorities: [],
  });
});

test("F02 is enabled only by matching refreshed candidate and ProjectContract facts", () => {
  const candidate = { candidate: { id: "candidate-1", projectId: "project-1",
    promotionState: "PROMOTED", promotedContractId: "contract-1" } };
  const project = { project: { id: "project-1" }, candidates: [], files: [], extractionResults: [],
    associatedContracts: [], intakeStatus: "CANDIDATES_AVAILABLE", failedFileCount: 0, unsupportedFileCount: 0 };
  assert.equal(provenPromotedContractId(project, candidate), null);
  assert.equal(provenPromotedContractId({ ...project, associatedContracts: [{ projectId: "project-2",
    candidateId: "candidate-1", contractId: "contract-1" }] }, candidate), null);
  assert.equal(provenPromotedContractId({ ...project, associatedContracts: [{ projectId: "project-1",
    candidateId: "candidate-1", contractId: "contract-1" }] }, candidate), "contract-1");
});

test("a promoted candidate is presented with the durable formal Contract summary instead of an editable confirmation", () => {
  assert.equal(typeof formalContractForCandidate, "function");
  assert.equal(typeof candidateWorkspaceMode, "function");
  const formal = {
    projectId: "project-1", candidateId: "candidate-1", contractId: "contract-1",
    contractNo: "FORMAL-1", contractName: "Formal Contract", contractType: "GOODS",
    partyA: "Buyer", partyB: "Supplier", partyBCreditCode: null, contractAmount: 100,
    amountInWords: "ONE HUNDRED", status: "READY_FOR_REVIEW", versionNo: 0,
  };
  const detail = { project: { id: "project-1" }, associatedContracts: [formal] };
  const promoted = { id: "candidate-1", projectId: "project-1", promotionState: "PROMOTED" };
  assert.equal(formalContractForCandidate(detail, "candidate-1"), formal);
  assert.equal(candidateWorkspaceMode(promoted, formal), "EXTRACTION_AND_FORMAL");
  assert.equal(candidateWorkspaceMode({ ...promoted, promotionState: "DRAFT" }, null), "CONFIRMATION");
  assert.equal(candidateWorkspaceMode(promoted, null), "EXTRACTION_ONLY");
});

test("multiple candidates stay selectable while uncertain upload identity cannot be replaced", () => {
  assert.deepEqual(candidateNavigation([
    { id: "candidate-1", contractNo: "C-1", contractName: null, promotionState: "DRAFT" },
    { id: "candidate-2", contractNo: null, contractName: "Second", promotionState: "PROMOTED" },
  ], "candidate-2"), [
    { id: "candidate-1", label: "C-1", active: false, promotionLabel: "系统整理中" },
    { id: "candidate-2", label: "Second", active: true, promotionLabel: "已生成正式合同" },
  ]);
  assert.equal(canSelectNewUpload(false, false), true);
  assert.equal(canSelectNewUpload(false, true), false);
  assert.equal(canSelectNewUpload(true, false), false);
});
