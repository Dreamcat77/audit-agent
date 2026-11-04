import type { ContractReviewCategoryId } from "./eightCategoryReviewProjection.ts";

export function nextOpenCategory(current: ContractReviewCategoryId | null,
  selected: ContractReviewCategoryId): ContractReviewCategoryId | null {
  return current === selected ? null : selected;
}
