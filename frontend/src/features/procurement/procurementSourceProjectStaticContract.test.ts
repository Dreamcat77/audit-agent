import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("new procurement demand accepts one material batch without exposing primary or supplementary roles", async () => {
  const app = await readFile(new URL("../../app/App.tsx", import.meta.url), "utf8");
  const api = await readFile(new URL("../../api/audit.ts", import.meta.url), "utf8");
  const upload = await readFile(new URL("./materials/ProcurementMaterialUpload.tsx", import.meta.url), "utf8");

  assert.doesNotMatch(app, /一次上传本次采购的全部材料/);
  assert.doesNotMatch(upload, /其他服务端材料记录|选择整个文件夹|listProcurementMaterials/);
  assert.doesNotMatch(app, /已接收 \{form\.sourceMaterialIds\.length\} 份采购材料/);
  assert.match(app, /listProcurementSourceProjects/);
  assert.match(app, /sourceProjectId: createForm\.sourceProjectId/);
  assert.match(app, /sourceMaterialIds: createForm\.sourceMaterialIds/);
  assert.match(app, /project\.sourceMaterialIds\?\.length/);
  assert.match(upload, /type="file" multiple/);
  assert.match(upload, /开始解析并填充/);
  assert.match(upload, /请先上传材料，再点击此按钮自动填写采购信息/);
  assert.match(upload, /500 MB/);
  assert.match(upload, /useMaterialBatch/);
  assert.doesNotMatch(upload, /1 至 10|总计不超过 40 MB/);
  assert.match(api, /\/api\/v1\/procurement\/projects\/source-projects/);
  assert.doesNotMatch(app, /主材料|补充材料|请选择主材料|本次关联材料/);
  assert.doesNotMatch(app, /projects\[0\]\?\.id/);
  assert.doesNotMatch(app, /procurementType: sourceProject\?\.procurementType/);
  assert.doesNotMatch(app, /budgetAmount: sourceProject\?\.budgetAmount/);
  assert.doesNotMatch(app, /sourceProjects\.length === 0 \? "暂无可选项目" : "请选择项目"/);
  assert.doesNotMatch(app, /暂无可选项目，请先到项目管理上传资料并完成项目确认/);
  assert.doesNotMatch(app, /当前保存附件名称；文件内容上传由文件存储模块后续接入/);
});
