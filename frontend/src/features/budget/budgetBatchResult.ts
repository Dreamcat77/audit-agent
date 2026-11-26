import type { BudgetMaterialReference } from "../../api/types";

const textKey=(value:unknown)=>typeof value==='string'?value.normalize('NFKC').replace(/[\s\u200b\ufeff]+/g,'').trim():value;
function mergeSources<T>(a:readonly T[],b:readonly T[]):T[]{return [...new Map([...a,...b].map(v=>[JSON.stringify(v),v])).values()];}

function mergeLineFields<T extends BudgetMaterialReference["lines"][number]>(existing: T, incoming: T): T {
  const prefer = <V>(left: V | null | undefined, right: V | null | undefined) =>
    left === null || left === undefined || (typeof left === 'string' && !left.trim()) ? right : left;
  return {
    ...existing,
    project: prefer(existing.project, incoming.project),
    projectCode: prefer(existing.projectCode, incoming.projectCode),
    subject: prefer(existing.subject, incoming.subject),
    subjectCode: prefer(existing.subjectCode, incoming.subjectCode),
    year: prefer(existing.year, incoming.year),
    department: prefer(existing.department, incoming.department),
    declaredAmount: prefer(existing.declaredAmount, incoming.declaredAmount),
    approvalAmount: prefer(existing.approvalAmount, incoming.approvalAmount),
    opinion: prefer(existing.opinion, incoming.opinion),
    sources: mergeSources(existing.sources, incoming.sources),
  } as T;
}

export function mergeBudgetMaterialReferences(references: readonly BudgetMaterialReference[]) {
  if (!references.length) return null;
  const projectName=references.find(r=>r.projectName&&r.projectName!=='预算材料分析')?.projectName??null;
  const lines = new Map<string, BudgetMaterialReference["lines"][number]>();
  const origins=new Map<string,Set<number>>();
  references.forEach((reference,referenceIndex) => {
    const sameReference=new Set<string>();
    for (const raw of reference.lines) {
    const line=projectName?{...raw,project:projectName}:raw;
    const sourceKey = line.sources.map(source =>
      JSON.stringify([source.taskId, source.field, source.locator, source.value]))
      .sort();
    // Preserve actual line identities: repeated expense rows are not repeated metadata.
    const metadata=line.sources.every(s=>['projectName','projectCode','departmentName','budgetYear'].includes(s.field));
    // A subject code is the stable identity across a batch. Project/year/department
    // often appear in only one uploaded view, so they enrich a row instead of
    // splitting identical rows into separate display entries.
    const baseKey = line.subjectCode
      ? JSON.stringify(['code', textKey(line.subjectCode), line.declaredAmount, line.approvalAmount,
        line.status, textKey(line.opinion)])
      : JSON.stringify(['text', ...[line.project, line.subject, line.year, line.department].map(textKey),
        line.declaredAmount, line.approvalAmount, line.status, textKey(line.opinion)]);
    // Each uploaded document can repeat the same row. Merge that repetition
    // across files while retaining genuinely repeated rows inside one file.
    let key = JSON.stringify([baseKey, sameReference.has(baseKey)&&!metadata?sourceKey:null]);
    const moneyKey=(item:typeof line)=>JSON.stringify(item.sources.filter(s=>s.field==='declaredAmount'||s.field==='approvalAmount')
      .map(s=>JSON.stringify([s.taskId,s.field,s.locator,s.value])).sort());
    const compatible=(a:unknown,b:unknown)=>a==null||a===''||b==null||b===''||textKey(a)===textKey(b);
    if(!lines.has(key)&&moneyKey(line)!=='[]'){
      const prior=[...lines.entries()].find(([k,old])=>!origins.get(k)?.has(referenceIndex)
        &&textKey(old.subject)===textKey(line.subject)&&compatible(old.subjectCode,line.subjectCode)
        &&compatible(old.project,line.project)&&compatible(old.projectCode,line.projectCode)
        &&compatible(old.year,line.year)&&compatible(old.department,line.department)
        &&old.declaredAmount===line.declaredAmount&&old.approvalAmount===line.approvalAmount
        &&old.status===line.status&&textKey(old.opinion)===textKey(line.opinion)&&moneyKey(old)===moneyKey(line));
      if(prior)key=prior[0];
    }
    sameReference.add(baseKey);
    const old=lines.get(key);
    lines.set(key, old ? mergeLineFields(old, line) : line);
    if(!origins.has(key))origins.set(key,new Set());origins.get(key)!.add(referenceIndex);
    }
  });
  const totals=new Map<string,NonNullable<BudgetMaterialReference['totals']>[number]>();
  for(const total of references.flatMap(r=>r.totals??[])){
    // Preserve source scopes for package/unknown totals; equal numbers alone cannot merge them.
    const key=JSON.stringify([total.role,total.scope,total.year,total.amount,
      total.scope==='TOTAL'||total.scope==='ANNUAL'?null:total.sources.map(s=>[s.taskId,s.locator]).sort()]);
    const old=totals.get(key);totals.set(key,old?{...old,sources:mergeSources(old.sources,total.sources)}:total);
  }
  const assessments=new Map<string,NonNullable<BudgetMaterialReference['assessments']>[number]>();
  for(const item of references.flatMap(r=>r.assessments??[])){
    const key=JSON.stringify([item.code,item.level,...[item.title,item.explanation,item.basis].map(textKey)]);
    const old=assessments.get(key);assessments.set(key,old?{...old,sources:mergeSources(old.sources,item.sources)}:item);
  }
  return {
    projectName,
    analysisCoverage:references.some(r=>r.analysisCoverage==='PARTIAL')?'PARTIAL' as const:references.every(r=>r.analysisCoverage==='COMPLETE')?'COMPLETE' as const:null,
    totals:[...totals.values()],
    status: references.some(reference => reference.status === "PENDING") ? "PENDING" as const : "COMPLETED" as const,
    coverage: references.some(reference => reference.coverage === "PARTIAL") ? "PARTIAL" as const
      : references.every(reference => reference.coverage === "COMPLETE") ? "COMPLETE" as const : null,
    summary: [...new Set(references.map(reference => reference.summary).filter(Boolean))].join("；"),
    assessments: [...assessments.values()],
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
