export interface IntelligentReviewBranches {
  startSemanticReview: () => void | Promise<void>;
  startDeterministicReview: () => void | Promise<void>;
}

export interface IntelligentReviewBranchResults {
  semantic: PromiseSettledResult<void>;
  deterministic: PromiseSettledResult<void>;
}

/** Starts both real operations independently so one rejection cannot suppress the other call. */
export async function startIntelligentReviewBranches({
  startSemanticReview,
  startDeterministicReview,
}: IntelligentReviewBranches): Promise<IntelligentReviewBranchResults> {
  const [semantic, deterministic] = await Promise.allSettled([
    Promise.resolve().then(startSemanticReview),
    Promise.resolve().then(startDeterministicReview),
  ]);
  return { semantic, deterministic };
}
