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

test("module users see only their own workspace and start there",()=>{
  const pages=["dashboard","project","procurement","procurement-tasks","budget-check","bidding","contract","risk","blacklist"];
  assert.deepEqual(visibleNavigationItems(["BUDGET_USER"],pages.map(id=>({id}))).map(x=>x.id),["budget-check"]);
  assert.deepEqual(visibleNavigationItems(["PROCUREMENT_USER"],pages.map(id=>({id}))).map(x=>x.id),["procurement","procurement-tasks","bidding","blacklist"]);
  assert.deepEqual(visibleNavigationItems(["CONTRACT_USER"],pages.map(id=>({id}))).map(x=>x.id),["project","contract"]);
  assert.equal(defaultPageForRoles(["BUDGET_USER"]),"budget-check");
  assert.equal(defaultPageForRoles(["PROCUREMENT_USER"]),"procurement");
  assert.equal(defaultPageForRoles(["CONTRACT_USER"]),"contract");
  assert.equal(canAccessPage(["SYSTEM_ADMIN","BUDGET_USER"],"dashboard"),true);
  assert.equal(canAccessPage(["BUDGET_USER","CONTRACT_USER"],"contract"),true);
});
