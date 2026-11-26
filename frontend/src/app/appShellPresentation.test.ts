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
  assert.equal(presentRoleLabels(["PROCUREMENT_USER","BUDGET_USER","CONTRACT_USER"]),"普通用户");
  for(const role of ["PROCUREMENT_USER","BUDGET_USER","CONTRACT_USER"])
    assert.equal(usesPersonalUploads([role,"AUDITOR","SYSTEM_ADMIN"]),false);
  assert.equal(usesPersonalUploads(["AUDITOR"]),true);
});

test("all ordinary accounts use all business pages; only personnel management is admin-only",()=>{
  const pages=["dashboard","project","procurement","procurement-tasks","budget-check","bidding","contract","risk","blacklist"];
  for(const role of ["BUDGET_USER","PROCUREMENT_USER","CONTRACT_USER","AUDITOR"]) {
    assert.deepEqual(visibleNavigationItems([role],pages.map(id=>({id}))).map(x=>x.id),pages);
    assert.equal(defaultPageForRoles([role]),"dashboard");
    assert.equal(canAccessPage([role],"system"),false);
    assert.equal(usesPersonalUploads([role]),true);
  }
  assert.equal(canAccessPage(["SYSTEM_ADMIN"],"system"),true);
  assert.equal(usesPersonalUploads(["SYSTEM_ADMIN","CONTRACT_USER"]),false);
});
