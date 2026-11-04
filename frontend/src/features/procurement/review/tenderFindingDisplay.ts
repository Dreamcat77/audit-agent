type DisplayFinding = {
  riskCategory: string;
  riskLevel: string;
  matchedText: string;
  sourceLocator: string;
  remediationSuggestion?: string | null;
  riskReason?: string | null;
};

/** Keep technical evidence identifiers in saved records, out of explanatory prose. */
export function tenderExplanationText(text?: string | null): string {
  return (text ?? '').replace(/\b(?:docx|doc|pdf|text|excel|pptx?|rtf)\/[a-zA-Z0-9_.:/-]+/g, '原文条款');
}

/**
 * Collapse repeated findings for the same cited text. Different rules may
 * legitimately point at one sentence; users should see that sentence once,
 * with the categories and advice combined. The saved records are untouched.
 */
export function uniqueTenderDisplayFindings<T extends DisplayFinding>(findings: readonly T[]): T[] {
  const merged = new Map<string, T>();
  for (const finding of findings) {
    const exactKey = JSON.stringify([finding.matchedText, finding.sourceLocator]);
    const text = finding.matchedText.replace(/\s+/g, '');
    const contained = [...merged.entries()].find(([, prior]) => {
      if (prior.sourceLocator !== finding.sourceLocator || prior.riskCategory !== finding.riskCategory
          || (prior.remediationSuggestion ?? '') !== tenderExplanationText(finding.remediationSuggestion)) return false;
      const other = prior.matchedText.replace(/\s+/g, '');
      // OCR rules can quote different context windows around the same clause.
      // Only collapse long, fully contained excerpts; retain short matches,
      // conflicting values and separate locations as independent findings.
      return Math.min(text.length, other.length) >= 32 && (text.includes(other) || other.includes(text));
    });
    const key = merged.has(exactKey) ? exactKey : contained?.[0] ?? exactKey;
    const existing = merged.get(key);
    if (!existing) {
      merged.set(key, {...finding,
        ...(finding.remediationSuggestion ? {remediationSuggestion:tenderExplanationText(finding.remediationSuggestion)} : {}),
        ...(finding.riskReason ? {riskReason:tenderExplanationText(finding.riskReason)} : {}),
      });
      continue;
    }
    const categories = [...new Set(`${existing.riskCategory}、${finding.riskCategory}`.split('、').filter(Boolean))];
    const suggestions = [...new Set([existing.remediationSuggestion, tenderExplanationText(finding.remediationSuggestion)].filter(Boolean))];
    const reasons = [...new Set([existing.riskReason ? tenderExplanationText(existing.riskReason) : '', finding.riskReason ? tenderExplanationText(finding.riskReason) : ''].filter(Boolean))];
    const rank = (level: string) => level === 'CRITICAL' ? 3 : level === 'WARNING' ? 2 : 1;
    merged.set(key, {...existing,
      matchedText: finding.matchedText.length > existing.matchedText.length ? finding.matchedText : existing.matchedText,
      riskCategory: categories.join('、'),
      riskLevel: rank(finding.riskLevel) > rank(existing.riskLevel) ? finding.riskLevel : existing.riskLevel,
      remediationSuggestion: suggestions.join('；'),
      ...(reasons.length ? {riskReason: reasons.join('；')} : {}),
    });
  }
  return [...merged.values()];
}
