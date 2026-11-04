# 采购材料界面简化与清空验收（2026-10-10）

按用户要求删除刷新进度、重试、逐文件原因/记录ID、已选择文件清单及已接收材料清单，材料区只显示本批成功、失败和处理中数量。未知结果单独显示确认中，避免把UNKNOWN当失败。移除上述材料区内部滚动条，进度继续自动查询。原文件拖放、后台处理、字段自动填充和关联保存保留。

新建页面“保存草稿”替换为“清空填充”，按钮可在上传或解析过程中使用。它清空文件选择、材料关联、批次状态和全部字段；重新挂载上传组件，使先前的目录读取回调失效。批次generation隔离旧上传/查询/重试响应，停止旧批次未发送文件；旧操作元数据和身份在本人浏览器恢复记录中保留，不自动展示、不缓存字段或原文证据。显式空操作列表防止旧sessionStorage记录再次迁回。清空失败时保留当前操作并提示。原服务端材料和后台任务仍保留，本次不实现业务材料销毁。

新建表单所有提交（含Enter）走既有审批确认；编辑已保存草稿仍使用保存修改。没有新增其他用户操作。工作区未提交改动全部保留，没有提交或推送。

修改：`frontend/src/app/App.tsx`、`ProcurementMaterialUpload.tsx`、`procurementMaterialBatch.ts`、`procurementMaterialBatchStore.ts`及相关测试；`tools/test-procurement-material-background.mjs`、`tools/build-procurement-simple-package.py`、更新说明。`git diff --check`通过。

Windows，现有JDK17.0.18及Node24：

```powershell
# backend目录
.\mvnw.cmd clean verify
# frontend目录
$tests = rg --files src | Where-Object { $_ -match '\.test\.ts$' }
node --experimental-strip-types --test @tests
npm run build
# 仓库根目录
node tools/build-procurement-browser.mjs frontend/dist .runtime/material-clear-frontend-final
node tools/test-procurement-material-background.mjs E:/audit-agent-new/.runtime/browser-compat-test/chrome-win/chrome.exe .runtime/material-clear-frontend-final clear-final2-109
node tools/test-procurement-material-background.mjs 'C:/Program Files/Google/Chrome/Application/chrome.exe' .runtime/material-clear-frontend-final clear-final2-current
```

后端2175项、0 failures、0 errors、2 skipped、BUILD SUCCESS，日志`.runtime/material-simple-backend-verify.log`。本轮仅修改前端，Java代码没有在此门禁后变化。前端最终720项全部通过，日志`.runtime/material-clear-frontend-tests-final.log`；包含上传期间清空/旧响应不能填回、旧poll返回不能持久化旧结果、清空存储失败保留身份。构建成功，日志`.runtime/material-clear-frontend-build-final.log`。

Chrome109.0.5412.0及Chrome155.0.8059.39真实构建、明确HTTP替身：12文件递归目录拖放、21MB文件、首文件失败继续、上传响应丢失后恢复、关窗/换模块和刷新原批次身份通过。10个表头实际使用自带中文字体，无页面错误/外部请求。界面不存在刷新、重试、已接收清单、文件原因/ID；材料区内部滚动容器数为0。

最终浏览器测试补充：成功结果填入真实表单；清空后所有字段/当前批次为空；预置旧sessionStorage身份后刷新依然空白；一个旧上传返回被延迟时清空，旧待发送文件未上传；新文件解析后Enter提交审批，HTTP payload的sourceProjectId和sourceMaterialIds只有新文件。无错误。报告及截图`.runtime/async-material-browser/clear-final2-109.json`和对应PNG、`clear-final2-current.json`。

代码包`.runtime/material-simple-delivery-final-v2/AuditAgent_Procurement_Simple_20261010.tar.gz`，1,699,134字节，SHA-256 `3dc97790ba6dd670a2d85502991afc74c1ea6f3dd660191bdbbf28ae0320cec9`。仅前端和入口更新/回滚脚本，复用之前已验证的版本检查、原子入口替换和资源HTTP检查；支持后台解析最终版、隐私版及本轮26中间界面版本。未知版本停止覆盖，原资源保留供回滚。没有配置文件、业务数据或凭据。

26真实更新：current仍为`0b64c63-material-privacy-20261010-r12`，只新增静态资源并替换前端入口，无服务重启。26没有离线安装marker，因此服务器专用适配只跳过marker检查；甲方交付脚本保留该检查。服务器解压校验manifest/包哈希/脚本语法通过，全部静态资源HTTP哈希匹配、未登录采购来源401、健康响应`{"code":200,"message":"success","data":{"service":"audit-backend","status":"UP"}}`。backend/ai/nginx均active；更新前后后端环境、AI环境、Nginx和后端JAR哈希一致。日志`/home/admin/batch12-20261010/simple-final2-update-result.log`，原入口备份`/opt/audit-agent/frontend-backups/material-simple-20261010.y3TB4M`。

甲方ARM64实际浏览器：NOT VERIFIED。本轮不测试或更改甲方本地模型配置。

2026-10-10追加微调：用户要求稍列成功/失败文件，上传组件仅在数量汇总下新增两条自然换行文本，使用本批原始文件名；成功组只含PARSED，失败组只含FAILED。没有原因、ID、重试、刷新、额外滚动框或其他业务操作。当前汇总、自动进度、布局和清空流程保留。

前端720项通过，构建成功（`.runtime/material-names-frontend-tests.log`、`material-names-frontend-build.log`）。兼容构建命令`node tools/build-procurement-browser.mjs frontend/dist .runtime/material-names-frontend-final`。浏览器测试`node tools/test-procurement-material-background.mjs <browser.exe> .runtime/material-names-frontend-final names-final-109`及`names-final-current`分别使用既有Chrome109.0.5412.0和Chrome155.0.8059.39，明确HTTP替身；准确列出11份成功和1份失败原始文件名，清空后两组消失，新需求只显示新文件名。原12文件目录拖放、21MB文件、失败隔离、丢失响应恢复、后台继续、旧请求不填回和新需求提交关联均通过；内部滚动容器0、页面错误0、外部请求0。截图/报告位于`.runtime/async-material-browser/names-final-109-batch-restored.png`、`names-final-109.json`及current对应文件。

最终交付使用v3：`.runtime/material-simple-delivery-final-v3/AuditAgent_Procurement_Simple_20261010.tar.gz`，2,264,334字节，SHA-256 `8e8d66a044a4b236c96b950dabf96bcc487ea3ada095a845acdeda3f17002cba`。支持之前后台解析、隐私版及26当前简化清空版；归档内容逐文件校验通过，不含配置、数据或凭据。v2仍保留作历史证据，不含此次文件名微调。

追加微调后再次执行规定后端门禁：默认JDK21被版本检查拒绝，切换到已安装JDK17.0.18后，在backend执行`.\mvnw.cmd clean verify`成功（`.runtime/material-names-backend-verify-jdk17.log`，BUILD SUCCESS，耗时3分38秒）。未安装新工具；本次没有修改或部署后端代码。

26已更新v3：归档SHA、manifest、更新/回滚脚本语法及资源HTTP哈希全部通过；未登录采购来源401，真实`curl -fsS http://127.0.0.1/api/v1/health`返回`{"code":200,"message":"success","data":{"service":"audit-backend","status":"UP"}}`，三项服务active。后端/AI环境、Nginx配置及后端JAR与原基线SHA一致；只更新前端，无重启。日志`/home/admin/batch12-20261010/simple-final3-update-result.log`，入口备份`/opt/audit-agent/frontend-backups/material-simple-20261010.fR8r2b`。甲方实际ARM64浏览器仍为NOT VERIFIED。
