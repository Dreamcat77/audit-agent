export interface BudgetApprovalProgramInitialLoaders<Review, Rules, History> {
  loadReview: () => Promise<Review>;
  loadRules: () => Promise<Rules>;
  loadHistory: () => Promise<History>;
}

export interface BudgetApprovalProgramInitialLoadHandlers<Review, Rules, History> {
  onCoreLoaded: (review: Review, rules: Rules) => void;
  onCoreError: (reason: unknown) => void;
  onHistoryLoaded: (history: History) => void;
  onHistoryWarning: (reason: unknown) => void;
}

export function startBudgetApprovalProgramInitialLoad<Review, Rules, History>(
  loaders: BudgetApprovalProgramInitialLoaders<Review, Rules, History>,
  handlers: BudgetApprovalProgramInitialLoadHandlers<Review, Rules, History>,
): Promise<void> {
  const coreLoad = Promise.all([
    Promise.resolve().then(loaders.loadReview),
    Promise.resolve().then(loaders.loadRules),
  ]).then(([review, rules]) => handlers.onCoreLoaded(review, rules))
    .catch((reason) => handlers.onCoreError(reason));
  const historyLoad = Promise.resolve().then(loaders.loadHistory)
    .then((history) => handlers.onHistoryLoaded(history))
    .catch((reason) => handlers.onHistoryWarning(reason));

  return Promise.all([coreLoad, historyLoad]).then(() => undefined);
}
