const ROLE_LABELS: Readonly<Record<string, string>> = {
  SYSTEM_ADMIN: "系统管理员",
  PROCUREMENT_MANAGER: "采购管理员",
  AUDITOR: "审核人员",
  LEGAL: "法务审核人员",
  PROCUREMENT_USER: "采购",
  BUDGET_USER: "预算",
  CONTRACT_USER: "合同",
};

export function usesPersonalUploads(roles: readonly string[]): boolean {
  return !roles.includes("SYSTEM_ADMIN")
    && roles.some(role => ["PROCUREMENT_USER", "BUDGET_USER", "CONTRACT_USER"].includes(role));
}

const ROLE_PAGES: Readonly<Record<string, readonly string[]>> = {
  PROCUREMENT_USER: ["procurement", "procurement-tasks", "bidding", "blacklist"],
  BUDGET_USER: ["budget-check"],
  CONTRACT_USER: ["contract", "project"],
};

export function canAccessPage(roles: readonly string[], page: string): boolean {
  if (!usesPersonalUploads(roles)) return true;
  return roles.some(role => ROLE_PAGES[role]?.includes(page) ?? false);
}

export function defaultPageForRoles(roles: readonly string[]): string {
  if (!usesPersonalUploads(roles)) return "dashboard";
  for (const role of roles) {
    const firstPage = ROLE_PAGES[role]?.[0];
    if (firstPage) return firstPage;
  }
  return "dashboard";
}

export function visibleNavigationItems<T extends { id: string }>(roles: readonly string[], items: readonly T[]): T[] {
  return items.filter(item => canAccessPage(roles, item.id));
}

export function presentRoleLabels(roles: readonly string[]): string | null {
  const labels = roles
    .map((role) => ROLE_LABELS[role])
    .filter((label): label is string => Boolean(label))
    .filter((label, index, all) => all.indexOf(label) === index);
  return labels.length > 0 ? labels.join("、") : null;
}
