# 采购材料界面与账号隔离修订（2026-10-10）

删除新建需求标题下说明、整个文件夹选择按钮及其他服务端材料记录入口；保留本批状态、文件夹拖入、窗口外处理、单文件失败隔离和原界面布局/表头字体。上传组件不再轮询历史材料接口。

服务端材料ID查询和同步/异步重试改为本人归属校验。采购来源Port、内存和DM8适配器传递actorId，列表在分页前过滤账号；所有需求主材料及补充材料在写入前通过认证用户的归属校验。管理员也不能通过上传工作区接口读取他人私有材料。已保存需求的审批/审查业务权限未在本次重做。

浏览器只持久化文件恢复元数据、操作标识、材料ID、状态和提示，不缓存字段或原文证据。当前账号进入新版时清理旧批次缓存内解析内容，重新向认证接口读取；存储写入失败仍保留原操作身份并停止新上传。原文件、DM8结果和备份没有被清除；本次不实现文件销毁、全系统数据分级或服务器加密，不宣称机密系统合规。

本轮修改文件：`ProcurementMaterialUpload.tsx`、`App.tsx`、`procurementMaterialBatch.ts`及测试；`MaterialIntakeService`、`ProcurementMaterialController`、`ProcurementSourceProjectService/Port`、内存/DM8来源适配器、`MaterialIntakeMapper.java/xml`及API/应用回归；相关交付说明和包构建工具。工作区既有修改保留，没有提交/推送。

Windows，已有JDK17.0.18和Node24：

```powershell
# backend目录
.\mvnw.cmd clean verify
# frontend目录
$tests = rg --files src | Where-Object { $_ -match '\.test\.ts$' }
node --experimental-strip-types --test @tests
npm run build
# 仓库根目录；兼容构建输出必须不存在
node tools/build-procurement-browser.mjs frontend/dist .runtime/material-privacy-frontend-final
node tools/test-procurement-material-background.mjs E:/audit-agent-new/.runtime/browser-compat-test/chrome-win/chrome.exe .runtime/material-privacy-frontend-final privacy-no-history-109
```

前端717项PASS；Chrome109.0.5412.0和Chrome155.0.8059.39测试12个文件（包括21MB文件）、递归文件夹拖放、首文件失败继续、丢失上传响应、关闭窗口/换模块和刷新同一批身份均通过；10个表头实际使用自带字体。三处红框内容不存在，无外部资源请求和页面错误；追加Chrome109断言确认历史材料接口请求数为0。API在此浏览器验收中为测试替身。

后端定向`ProcurementMaterialApiIntegrationTest`四项PASS；包含本人查询成功、其他申请人/采购管理/审计员/管理员详情及重试404、列表和操作恢复不泄露、主材料及补充材料关联422。日志`.runtime/material-privacy-backend-target.log`。完整`clean verify`为2175项、0 failures、0 errors、2 skipped，BUILD SUCCESS，耗时3:32，日志`.runtime/material-privacy-backend-verify-final2.log`。前端日志`.runtime/material-privacy-frontend-tests-final2.log`及构建日志`.runtime/material-privacy-frontend-build-final2.log`。`git diff --check`通过。

代码更新包`.runtime/material-privacy-delivery-v1/AuditAgent_Procurement_Privacy_20261010.tar.gz`，70,935,899字节，SHA-256 `d1d3db9e32a840808f3532150644450eefaced38e657e76be20049f3f9160808`。不含真实环境配置、模型Key、业务材料、数据库备份或测试凭据。

26真实环境：从r11更新到`0b64c63-material-privacy-20261010-r12`。服务器重新解压并验证manifest、包哈希和脚本语法；V048/V050约束已匹配，没有修改业务数据/约束。后端、AI、Nginx均active。真实curl健康响应`{"code":200,"message":"success","data":{"service":"audit-backend","status":"UP"}}`；未登录材料接口401；HTTP首页哈希等于新前端入口。更新前后后端环境、AI环境及Nginx配置SHA-256一致。

真实DM8及HTTP：`.runtime/MaterialPrivacyRunner.java`创建两个独立合成管理员及各自一条PARSED合成材料，匿名管道传临时凭据给`.runtime/material-privacy-http.py`。通过真实会话验证本人详情200且不返回storageKey、他人详情/重试404、他人操作恢复null、历史仅本人、来源列表keyword+limit=1仅本人、他人主材料和补充材料关联422。输出`REAL_DM8_HTTP_PRIVACY_OWNER200_FOREIGN404_RETRY404_PRIMARY422_SUPPLEMENT422_LIST_OWNER_ONLY_LIMIT1=PASS`。仅移除本次两条明确合成测试材料，两个新测试账号停用；没有删除用户材料。服务器日志`/home/admin/batch12-20261010/privacy-http-result.log`。此前一次SSH传输中断，重连后执行并读取完整成功结果，未把中断当成功。

甲方ARM64机器、原生浏览器和本地大模型：NOT VERIFIED。模型地址、密钥、名称和Nginx配置不属于本次修改。
