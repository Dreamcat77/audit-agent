/** Local/integration fixture mapping only; this is not a production business rule. */
export const LOCAL_CONTRACT_SOURCE_MAP: Readonly<Record<string, string>> = {
  "CT-2024-0089": "00000000-0000-0000-0000-000000000101",
  "CT-2024-0094": "00000000-0000-0000-0000-000000000102",
  "CT-2026-0101": "00000000-0000-0000-0000-000000000201",
  "CT-2026-0102": "00000000-0000-0000-0000-000000000202",
  "CT-2026-0103": "00000000-0000-0000-0000-000000000203",
  "CT-2026-0104": "00000000-0000-0000-0000-000000000204",
};

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function resolveContractApiId(contractId: string): string | null {
  const normalizedId = contractId.trim();
  if (UUID_PATTERN.test(normalizedId)) return normalizedId;
  return LOCAL_CONTRACT_SOURCE_MAP[normalizedId] ?? null;
}
