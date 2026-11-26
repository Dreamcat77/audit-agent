const ROLE_LABELS: Readonly<Record<string, string>> = {
  SYSTEM_ADMIN: "系统管理员",
  PROCUREMENT_MANAGER: "采购管理员",
  AUDITOR: "审核人员",
  LEGAL: "法务审核人员",
  PROCUREMENT_USER: "普通用户",
  BUDGET_USER: "普通用户",
  CONTRACT_USER: "普通用户",
};

export function usesPersonalUploads(roles: readonly string[]): boolean {
  return !roles.includes("SYSTEM_ADMIN");
}

export function canAccessPage(roles: readonly string[], page: string): boolean {
  return page !== "system" || roles.includes("SYSTEM_ADMIN");
}

export function defaultPageForRoles(_roles: readonly string[]): string {
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
