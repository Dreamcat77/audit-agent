const DEMO_VISIBLE_PAGE_IDS = [
  "dashboard",
  "budget-check",
  "procurement",
  "contract",
  "blacklist",
  "risk",
] as const;

export function demoVisibleNavigationItems<T extends { id: string }>(items: readonly T[]): T[] {
  const byId = new Map(items.map((item) => [item.id, item]));
  return DEMO_VISIBLE_PAGE_IDS.flatMap((id) => {
    const item = byId.get(id);
    return item ? [item] : [];
  });
}
