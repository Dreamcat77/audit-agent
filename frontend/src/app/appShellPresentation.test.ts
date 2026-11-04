import assert from "node:assert/strict";
import test from "node:test";
import { presentRoleLabels } from "./appShellPresentation.ts";

test("shell role presentation maps supported backend role codes without exposing unknown enums", () => {
  assert.equal(presentRoleLabels(["SYSTEM_ADMIN"]), "系统管理员");
  assert.equal(presentRoleLabels(["PROCUREMENT_MANAGER"]), "采购管理员");
  assert.equal(presentRoleLabels(["AUDITOR"]), "审核人员");
  assert.equal(presentRoleLabels(["LEGAL"]), "法务审核人员");
  assert.equal(presentRoleLabels(["AUDITOR", "INTERNAL_ONLY", "AUDITOR", "LEGAL"]), "审核人员、法务审核人员");
  assert.equal(presentRoleLabels(["SYSTEM_ADMIN_INTERNAL"]), null);
  assert.equal(presentRoleLabels([]), null);
});
