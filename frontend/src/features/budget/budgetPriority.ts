import type {BudgetMaterialReference,BudgetSourceEvidence} from '../../api/types';
import {budgetAttention,budgetDifference} from './budgetDifferences.ts';

type Assessment=NonNullable<BudgetMaterialReference['assessments']>[number];

export function budgetPriority(reference:BudgetMaterialReference):Assessment[]{
  const lines=reference.lines.filter(l=>!!budgetDifference(l.status)||l.status==='CONFLICT').map(l=>({code:'LINE_'+l.status,level:'WARNING',
    title:[l.subject??l.subjectCode,budgetDifference(l.status)??'材料记载不一致'].filter(Boolean).join(' · '),explanation:budgetAttention(l).headline,
    basis:l.opinion,sources:l.sources}));
  const assessments=(reference.assessments??[]).filter(a=>a.level==='WARNING'||a.level==='CRITICAL');
  const mergedAssessments=new Set<number>();
  const combined:Assessment[]=[];
  const baseLocator=(locator:string)=>locator.replace(/\/(?:line:\d+|record:\d+:\d+)$/,'');
  const sameEvidence=(a:BudgetSourceEvidence,b:BudgetSourceEvidence)=>a.taskId===b.taskId
    &&baseLocator(a.locator)===baseLocator(b.locator)&&a.excerpt===b.excerpt;
  const mergeSources=(sources:BudgetSourceEvidence[])=>{
    const unique=new Map<string,BudgetSourceEvidence>();
    for(const source of sources){
      const key=`${source.taskId}|${baseLocator(source.locator)}|${source.excerpt}`;
      const previous=unique.get(key);
      if(!previous||source.start!=null&&source.end!=null&&(previous.start==null||previous.end==null))unique.set(key,source);
    }
    return [...unique.values()];
  };
  for(const line of lines){
    const match=assessments.findIndex((assessment,index)=>!mergedAssessments.has(index)
      &&assessment.code==='SEMANTIC_PURPOSE_SCOPE'
      &&assessment.sources.some(source=>line.sources.some(item=>sameEvidence(item,source))));
    if(match>=0){
      const assessment=assessments[match];mergedAssessments.add(match);
      combined.push({...assessment,sources:mergeSources([...assessment.sources,...line.sources])});
    }else combined.push(line);
  }
  assessments.forEach((assessment,index)=>{if(!mergedAssessments.has(index))combined.push(assessment);});
  return combined.filter((item,index,all)=>all.findIndex(a=>a.code===item.code&&a.title===item.title&&a.explanation===item.explanation)===index);
}
