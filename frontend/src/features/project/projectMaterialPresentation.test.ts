import assert from "node:assert/strict";
import { registerHooks } from "node:module";
import test from "node:test";

const hooks = registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith(".") && !/\.[a-z]+$/i.test(specifier)) return nextResolve(`${specifier}.ts`, context);
    return nextResolve(specifier, context);
  },
});

const { factTypeLabel, projectMaterialTabs } = await import("./projectMaterialPresentation.ts").finally(() => hooks.deregister());

test("persisted automatic role decisions enable formal material selection without accepting suggestions", () => {
  const file={id:"file-a",projectId:"project-a",originalFilename:"合同书.docx",mediaType:"application/docx",
    category:"CONTRACT_BODY",effectiveRole:"CONTRACT_BODY",effectiveRoleSource:"SYSTEM_AUTO_DECISION",
    roleDecisionStatus:"CONFIRMED",roleDecisionId:"decision-a",ingestionStatus:"AVAILABLE",parseStatus:"PARSED",
    effectiveIdentityStatus:"SAME_PROJECT",identityCurrent:true,excludedFromProjectUse:false,parsedText:"合同正文"};
  const item=(overrides:object)=>projectMaterialTabs([{...file,...overrides}] as never,"project-a").flatMap(t=>t.files)[0];
  assert.equal(item({}).reviewRole,"CONTRACT_BODY");
  assert.equal(item({}).semanticEligible,true);
  assert.equal(item({roleDecisionId:null}).reviewRole,"UNKNOWN");
  assert.equal(item({roleDecisionStatus:"PENDING"}).reviewRole,"UNKNOWN");
  assert.equal(item({effectiveRoleSource:"MACHINE_DETERMINISTIC"}).reviewRole,"UNKNOWN");
  assert.equal(item({effectiveIdentityStatus:"NOT_SAME_PROJECT"}).semanticEligible,false);
});

test("project material extraction fact types have human-readable labels", () => {
  assert.deepEqual([
    factTypeLabel("PURCHASER"),
    factTypeLabel("PROCUREMENT_AGENT"),
    factTypeLabel("BUDGET_AMOUNT"),
    factTypeLabel("CONTROL_PRICE"),
    factTypeLabel("PACKAGE_SCOPE"),
  ], [
    "采购人",
    "采购代理机构",
    "预算金额",
    "最高限价",
    "采购包范围",
  ]);
});
