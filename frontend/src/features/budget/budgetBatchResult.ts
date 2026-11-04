import type { BudgetMaterialReference } from "../../api/types";

export function mergeBudgetMaterialReferences(references: readonly BudgetMaterialReference[]) {
  if (!references.length) return null;
  const lines = new Map<string, BudgetMaterialReference["lines"][number]>();
  for (const line of references.flatMap(reference => reference.lines)) {
    const sourceKey = line.sources.map(source =>
      JSON.stringify([source.taskId, source.field, source.locator, source.value]))
      .sort();
    const key = JSON.stringify([line.project, line.subject, line.subjectCode, line.year, line.department,
      line.declaredAmount, line.approvalAmount, line.status, line.opinion, sourceKey]);
    if (!lines.has(key)) lines.set(key, line);
  }
  return {
    status: references.some(reference => reference.status === "PENDING") ? "PENDING" as const : "COMPLETED" as const,
    coverage: references.some(reference => reference.coverage === "PARTIAL") ? "PARTIAL" as const
      : references.every(reference => reference.coverage === "COMPLETE") ? "COMPLETE" as const : null,
    summary: [...new Set(references.map(reference => reference.summary).filter(Boolean))].join("；"),
    assessments: [...new Map(references.flatMap(reference => reference.assessments ?? [])
      .map(item => [JSON.stringify([item.code,item.explanation,item.sources]),item])).values()],
    lines: [...lines.values()],
  };
}

export function groupBudgetMaterialLines(lines: BudgetMaterialReference['lines']) {
  const groups = new Map<string, { key: string; project: string | null; year: string | null;
    department: string | null; lines: BudgetMaterialReference['lines'] }>();
  const priority = (status: string) => ['OVER_APPROVAL', 'UNAPPROVED_SUBJECT', 'MISSING_DECLARATION',
    'CONFLICT', 'INVALID_AMOUNT', 'INSUFFICIENT', 'WITHIN_APPROVAL'].indexOf(status);
  for (const line of lines) {
    const key = JSON.stringify([line.project, line.year, line.department]);
    let group = groups.get(key);
    if (!group) {
      group = { key, project: line.project, year: line.year, department: line.department, lines: [] };
      groups.set(key, group);
    }
    group.lines.push(line);
  }
  return [...groups.values()].map(group => ({ ...group, lines: group.lines.sort((a, b) =>
    (priority(a.status) < 0 ? 5 : priority(a.status)) - (priority(b.status) < 0 ? 5 : priority(b.status))) }));
}
