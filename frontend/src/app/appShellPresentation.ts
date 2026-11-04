const ROLE_LABELS: Readonly<Record<string, string>> = {
  SYSTEM_ADMIN: "系统管理员",
  PROCUREMENT_MANAGER: "采购管理员",
  AUDITOR: "审核人员",
  LEGAL: "法务审核人员",
};

export function presentRoleLabels(roles: readonly string[]): string | null {
  const labels = roles
    .map((role) => ROLE_LABELS[role])
    .filter((label): label is string => Boolean(label))
    .filter((label, index, all) => all.indexOf(label) === index);
  return labels.length > 0 ? labels.join("、") : null;
}
