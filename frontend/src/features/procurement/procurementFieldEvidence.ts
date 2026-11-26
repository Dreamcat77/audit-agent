export type ExtractedMaterialFact = {
  field: string;
  value: string;
  excerpt: string;
  locator: string;
};

export type ExtractedValueEvidence = {
  value: string;
  excerpts: string[];
};

/** Keep distinct source values legible while consolidating duplicate mentions. */
export function fieldEvidence(facts: readonly ExtractedMaterialFact[], field: string): ExtractedValueEvidence[] {
  const grouped = new Map<string, Set<string>>();
  for (const fact of facts) {
    if (fact.field !== field || !fact.value.trim()) continue;
    const excerpts = grouped.get(fact.value) ?? new Set<string>();
    if (fact.excerpt.trim()) excerpts.add(fact.excerpt.trim());
    grouped.set(fact.value, excerpts);
  }
  return [...grouped].map(([value, excerpts]) => ({ value, excerpts: [...excerpts] }));
}
