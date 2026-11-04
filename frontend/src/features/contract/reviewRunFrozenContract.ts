import type { Contract } from "./types.ts";
import type { ReviewRunIdentityDto } from "./reviewRunTypes.ts";

const INVALID = "冻结合同快照无法安全展示";

function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error(INVALID);
  return value as Record<string, unknown>;
}

function string(value: unknown): string {
  if (typeof value !== "string") throw new Error(INVALID);
  return value;
}

function nullableString(value: unknown): string | null {
  if (value === null) return null;
  return string(value);
}

function integer(value: unknown): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value)) throw new Error(INVALID);
  return value;
}

/** Conversion is display-only; all monetary decisions remain on Java BigDecimal. */
function decimal(value: unknown): number {
  if (typeof value !== "string" || !/^-?\d+(?:\.\d+)?$/.test(value)) throw new Error(INVALID);
  const amount = Number(value);
  if (!Number.isFinite(amount) || Math.abs(amount) > Number.MAX_SAFE_INTEGER) throw new Error(INVALID);
  return amount;
}

function nullableDecimal(value: unknown): number | null {
  return value === null ? null : decimal(value);
}

export function frozenContractFromReviewRun(run: ReviewRunIdentityDto): Contract {
  if (run.runSchemaVersion !== 1 && run.runSchemaVersion !== 2) throw new Error(INVALID);
  let decoded: unknown;
  try {
    decoded = JSON.parse(run.contractSnapshotCanonicalJson);
  } catch {
    throw new Error(INVALID);
  }
  const value = object(decoded);
  const snapshotSchemaVersion = integer(value.schemaVersion);
  if ((snapshotSchemaVersion !== 1 && snapshotSchemaVersion !== 2) || value.contractId !== run.contractId
      || value.versionNo !== run.contractVersion || !Array.isArray(value.lineItems)) throw new Error(INVALID);
  const lines = value.lineItems.map((raw) => {
    const line = object(raw);
    const basis = snapshotSchemaVersion === 1 ? "QUANTITY_PRICE" : string(line.basis);
    if (basis !== "QUANTITY_PRICE" && basis !== "AMOUNT_ONLY") throw new Error(INVALID);
    const quantity = snapshotSchemaVersion === 1 ? decimal(line.quantity) : nullableDecimal(line.quantity);
    const unitPrice = snapshotSchemaVersion === 1 ? decimal(line.unitPrice) : nullableDecimal(line.unitPrice);
    if ((basis === "QUANTITY_PRICE" && (quantity === null || unitPrice === null))
        || (basis === "AMOUNT_ONLY" && (quantity !== null || unitPrice !== null))) throw new Error(INVALID);
    return {
      id: string(line.id), itemKey: string(line.itemKey), itemName: string(line.itemName),
      basis, quantity, unitPrice,
      declaredAmount: decimal(line.declaredAmount), sortNo: integer(line.sortNo),
    };
  });
  const award = value.awardNotice === null ? null : object(value.awardNotice);
  return {
    id: string(value.contractId), contractNo: string(value.contractNo),
    contractName: string(value.contractName), contractType: string(value.contractType),
    partyA: string(value.partyA), partyB: string(value.partyB),
    partyBCreditCode: nullableString(value.partyBCreditCode),
    contractAmount: decimal(value.contractAmount), amountInWords: nullableString(value.amountInWords),
    status: string(value.status), versionNo: integer(value.versionNo),
    createdAt: string(value.createdAt), updatedAt: string(value.updatedAt),
    lineItems: lines,
    awardNotice: award ? {
      id: string(award.id), noticeNo: string(award.noticeNo),
      projectName: string(award.projectName), winningBidder: string(award.winningBidder),
      winningBidderCreditCode: nullableString(award.winningBidderCreditCode),
      winningAmount: decimal(award.winningAmount), winningDate: string(award.winningDate),
      sourceRef: nullableString(award.sourceRef),
    } : null,
  };
}
