import assert from "node:assert/strict";
import test from "node:test";
import { canAccessPage, defaultPageForRoles, presentRoleLabels, usesPersonalUploads, visibleNavigationItems } from "./appShellPresentation.ts";

test("shell role presentation maps supported backend role codes without exposing unknown enums", () => {
  assert.equal(presentRoleLabels(["SYSTEM_ADMIN"]), "系统管理员");
  assert.equal(presentRoleLabels(["PROCUREMENT_MANAGER"]), "采购管理员");
  assert.equal(presentRoleLabels(["AUDITOR"]), "审核人员");
  assert.equal(presentRoleLabels(["LEGAL"]), "法务审核人员");
  assert.equal(presentRoleLabels(["AUDITOR", "INTERNAL_ONLY", "AUDITOR", "LEGAL"]), "审核人员、法务审核人员");
  assert.equal(presentRoleLabels(["SYSTEM_ADMIN_INTERNAL"]), null);
  assert.equal(presentRoleLabels([]), null);
});

test("personal roles show job names and remain personal when combined with older roles",()=>{
  assert.equal(presentRoleLabels(["PROCUREMENT_USER","BUDGET_USER","CONTRACT_USER"]),"采购、预算、合同");
  for(const role of ["PROCUREMENT_USER","BUDGET_USER","CONTRACT_USER"])
    assert.equal(usesPersonalUploads([role,"AUDITOR","SYSTEM_ADMIN"]),false);
  assert.equal(usesPersonalUploads(["AUDITOR"]),false);
});

test("all module users can open the shared workbench while keeping module page access isolated",()=>{
  const pages=["dashboard","project","procurement","procurement-tasks","budget-check","bidding","contract","risk","blacklist"];
  assert.deepEqual(visibleNavigationItems(["BUDGET_USER"],pages.map(id=>({id}))).map(x=>x.id),["dashboard","budget-check","blacklist"]);
  assert.deepEqual(visibleNavigationItems(["PROCUREMENT_USER"],pages.map(id=>({id}))).map(x=>x.id),["dashboard","procurement","procurement-tasks","bidding","blacklist"]);
  assert.deepEqual(visibleNavigationItems(["CONTRACT_USER"],pages.map(id=>({id}))).map(x=>x.id),["dashboard","project","contract","blacklist"]);
  assert.equal(defaultPageForRoles(["BUDGET_USER"]),"dashboard");
  assert.equal(defaultPageForRoles(["PROCUREMENT_USER"]),"dashboard");
  assert.equal(defaultPageForRoles(["CONTRACT_USER"]),"dashboard");
  assert.equal(canAccessPage(["BUDGET_USER"],"procurement"),false);
  assert.equal(canAccessPage(["BUDGET_USER"],"contract"),false);
  assert.equal(canAccessPage(["BUDGET_USER"],"dashboard"),true);
  assert.equal(canAccessPage(["PROCUREMENT_USER"],"blacklist"),true);
  assert.equal(canAccessPage(["CONTRACT_USER"],"blacklist"),true);
  assert.equal(canAccessPage(["SYSTEM_ADMIN"],"blacklist"),true);
  assert.equal(canAccessPage(["SYSTEM_ADMIN","BUDGET_USER"],"dashboard"),true);
  assert.equal(canAccessPage(["BUDGET_USER","CONTRACT_USER"],"contract"),true);
});
