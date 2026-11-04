import type { ReviewOpinionMetadataDto } from "./reviewRunTypes.ts";

const SHA256 = /^[a-f0-9]{64}$/;
const RULE_SUBTYPES = ["ABSOLUTE_VALUE", "LABELLED_NUMBER", "ARITHMETIC", "RATE_COMPARISON", "UNSAFE_CONCLUSION"];

function invalid(): never {
  throw new Error("审核意见元数据结构不完整，暂时无法安全展示");
}

function record(value: unknown): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) invalid();
  return value as Record<string, unknown>;
}

function nonblank(value: unknown): value is string {
  return typeof value === "string" && value.trim().length > 0;
}

function hash(value: unknown): boolean {
  return typeof value === "string" && SHA256.test(value);
}

function integer(value: unknown, minimum: number): boolean {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= minimum;
}

/** Old projections omit both fields. Present metadata must be server-protocol compatible. */
export function assertReviewOpinionMetadata(value: unknown): asserts value is ReviewOpinionMetadataDto {
  const projection = record(value);
  if (projection.opinionVersion === 4
      && (!nonblank(projection.overallOpinion) || !("monetaryObservation" in projection))) invalid();
  if ("overallOpinion" in projection && !nonblank(projection.overallOpinion)) invalid();
  if (!("monetaryObservation" in projection)) return;
  const observation = record(projection.monetaryObservation);
  if (observation.protocol !== "contract-money-observation-v1"
      || typeof observation.mode !== "string" || !["STRICT", "OBSERVE"].includes(observation.mode)
      || !Array.isArray(observation.warnings) || !Array.isArray(observation.workWarnings)) invalid();
  if ("warningCount" in observation && !integer(observation.warningCount, 0)) invalid();
  for (const value of observation.warnings) {
    const warning = record(value);
    const summaryWithoutCategory = warning.category === null && typeof warning.fieldPath === "string"
      && /^(summary|summaries\[\d+\])$/.test(warning.fieldPath);
    if (!nonblank(warning.fieldPath) || !(nonblank(warning.category) || summaryWithoutCategory)
        || typeof warning.errorKind !== "string" || !["MONETARY_VALUE", "MONETARY_CALCULATION"].includes(warning.errorKind)
        || !hash(warning.textSha256)) invalid();
    if ("ruleSubtypes" in warning && (!Array.isArray(warning.ruleSubtypes)
        || warning.ruleSubtypes.some(value => typeof value !== "string" || !RULE_SUBTYPES.includes(value)))) invalid();
  }
  for (const value of observation.workWarnings) {
    const warning = record(value);
    if (!nonblank(warning.logicalKey) || !integer(warning.ordinal, 1)
        || typeof warning.purpose !== "string" || !["ANALYZE", "REPAIR", "SYNTHESIZE"].includes(warning.purpose)
        || !hash(warning.operationFingerprint) || !integer(warning.warningCount, 0)
        || !hash(warning.warningsSha256)) invalid();
  }
}
