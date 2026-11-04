import type { ProcurementSourceProject } from '../../../api/types';

/** Only a matching cited project identity may be combined automatically. */
export function canCombineProcurementMaterials(first: ProcurementSourceProject, next: ProcurementSourceProject) {
  const projectNo = (value: ProcurementSourceProject) => value.projectNo?.trim() || value.fields?.projectNo?.trim();
  const name = (value: ProcurementSourceProject) => value.fields?.projectName?.trim() || value.name?.trim();
  const firstNo = projectNo(first), nextNo = projectNo(next);
  if (firstNo && nextNo) return firstNo === nextNo;
  const firstName = name(first), nextName = name(next);
  return !firstName || !nextName || firstName === nextName;
}

/** A new source clears old suggestions, including values missing in the new material. */
export function procurementMaterialForm(source: ProcurementSourceProject) {
  const fields = source.fields;
  const value = (name: keyof NonNullable<typeof fields>) => fields?.conflicts.includes(name)
    ? '' : fields?.[name] == null ? '' : String(fields[name]);
  return {
    sourceProjectId: source.id,
    sourceMaterialIds: [source.id],
    projectName: fields?.conflicts.includes('projectName') ? '' : fields?.projectName ?? source.name ?? '',
    departmentName: value('departmentName'), procurementType: value('procurementType'),
    budgetAmount: value('budgetAmount'), subjectCode: value('subjectCode'), subjectName: value('subjectName'),
    procurementMethod: value('procurementMethod'), requirementDescription: value('requirementDescription'),
    applicantName: value('applicantName'), attachmentFile: null,
    materialAutoValues: Object.fromEntries(['projectName', 'departmentName', 'procurementType', 'budgetAmount', 'subjectCode', 'subjectName', 'procurementMethod', 'applicantName', 'requirementDescription'].map(name => [name, name === 'projectName' ? fields?.projectName ?? source.name ?? '' : value(name as keyof NonNullable<typeof fields>)])) as Record<string, string> | undefined,
    materialConflicts: [...(fields?.conflicts ?? [])] as string[] | undefined,
  };
}

/** Keep user-confirmed values, fill missing fields from every parsed material, and retain all source identities. */
export function mergeProcurementMaterialForm(
  current: Omit<ReturnType<typeof procurementMaterialForm>, 'materialAutoValues' | 'materialConflicts'> & {
    materialAutoValues?: Record<string, string>; materialConflicts?: string[];
  }, source: ProcurementSourceProject,
) {
  const incoming = procurementMaterialForm(source);
  const auto = { ...current.materialAutoValues };
  const conflicts = new Set(current.materialConflicts);
  const fill = (field: string, value: string, suggestion: string) => {
    const previous = current.materialAutoValues?.[field];
    // A changed/cleared input is a user choice. A previous auto-fill is not.
    if (previous === undefined || value !== previous) {
      conflicts.delete(field);
      return value.trim() || previous !== undefined ? value : suggestion;
    }
    if (incoming.materialConflicts?.includes(field) || (value.trim() && suggestion.trim() && value !== suggestion)) {
      conflicts.add(field); auto[field] = ''; return '';
    }
    if (conflicts.has(field)) return value;
    auto[field] = value || suggestion;
    return auto[field];
  };
  const joinedRequirements = !incoming.requirementDescription.trim()
    || current.requirementDescription.includes(incoming.requirementDescription)
    ? current.requirementDescription
    : current.requirementDescription.trim()
      ? `${current.requirementDescription.trim()}\n${incoming.requirementDescription.trim()}`
      : incoming.requirementDescription;
  const requirements = current.materialAutoValues?.requirementDescription !== current.requirementDescription
    ? current.requirementDescription
    : joinedRequirements.length <= 1000 ? joinedRequirements
    : `以下为部分原文摘录，完整需求以所选材料为准：\n${Array.from(joinedRequirements).slice(0,800).join('')}`;
  auto.requirementDescription = requirements;
  return {
    ...current,
    sourceProjectId: current.sourceProjectId || source.id,
    sourceMaterialIds: [...new Set([...current.sourceMaterialIds, source.id])],
    projectName: fill('projectName', current.projectName, incoming.projectName),
    departmentName: fill('departmentName', current.departmentName, incoming.departmentName),
    procurementType: fill('procurementType', current.procurementType, incoming.procurementType),
    budgetAmount: fill('budgetAmount', current.budgetAmount, incoming.budgetAmount),
    subjectCode: fill('subjectCode', current.subjectCode, incoming.subjectCode),
    subjectName: fill('subjectName', current.subjectName, incoming.subjectName),
    procurementMethod: fill('procurementMethod', current.procurementMethod, incoming.procurementMethod),
    requirementDescription: requirements,
    applicantName: fill('applicantName', current.applicantName, incoming.applicantName),
    materialAutoValues: auto,
    materialConflicts: [...conflicts],
  };
}
