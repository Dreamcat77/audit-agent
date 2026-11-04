# 预算解析完成后自动进入结果

本次修复限定为预算前端跳转。`BudgetCheckPage` 原先在当前批次处理结束后清空当前结果并设置首页；现在打开本次首个可用文件结果，保留同批文件选择。未完成的参考结果在结果页继续等待服务端持久化结果；`BudgetFileResults` 在参考结果完成后停止轮询。

保持原有 view epoch 与本次提交批次边界：进入历史、离开预算、重新选择资料或返回首页后，旧任务不得再次抢占当前页面；FAILED 不作为结果，UNKNOWN 不视为成功。采购审查原有 `onBatch` 在拿到报告时已经切换结果页，本次没有修改采购业务流程、布局或后端。

## 验证

Windows 本地，合成 API 浏览器回归：

```text
npm.cmd run build
node tools/build-procurement-browser.mjs frontend/dist .runtime/budget-auto-result-v25-final-browser
node tools/test-budget-material-background.mjs <Chrome109> .runtime/budget-auto-result-v25-final-browser chrome109-v25-final
node tools/test-budget-material-background.mjs <Chromium151> .runtime/budget-auto-result-v25-final-browser chromium-v25-final
node --experimental-strip-types --test frontend/src/features/budget/budgetAuditWorkspaceStaticContract.test.ts frontend/src/features/budget/budgetResultState.test.ts
```

构建通过。Chrome 109.0.5412.0 与 Chromium 151.0.7922.34 均通过，无 pageerror。3 个前端单元测试通过。实际断言覆盖：当前新批次自动打开首份结果、参考结果 PENDING 后自动更新、完成后停止轮询、部分失败仍保留两份成功材料并可切换、返回不自动重开、离开/刷新仍进首页、历史不被抢占、UNKNOWN 后恢复、全失败保留错误、原文件重选续传后进入结果。原预算历史、红色差异及导出、采购报告复制等回归通过。截图：`.runtime/budget-background-browser/chrome109-v25-final-auto-result.png`。

额外旧采购上传浏览器脚本 `tools/test-procurement-material-background.mjs` 在第 70 行表头数量断言处停止：脚本期待 10 列，当前既有表为 9 列；尚未进入上传用例。这与此次预算修改无关，不能将该旧脚本记为通过。采购工作区相关回归使用上述整组浏览器脚本。

完整后端 `mvnw.cmd clean verify` 使用既有 JDK17 重跑：2296 tests，0 failures，0 errors，2 skipped，BUILD SUCCESS。首次命令使用默认 JDK21 被工具链检查拒绝，未跳过检查。

兼容前端归档 SHA-256：`7cf495112247010575ff1e514c713513fb52060449a1f270b0f2ff762c9139d0`。

## 26 部署

前端 v25 已部署，未重启服务；后端 JAR 与原 release 逐字节一致，配置哈希全部保持不变。

- release：`/opt/audit-agent/releases/budget-auto-result-v25-20261016.TAad5d`
- recovery：`/opt/audit-agent/update-backups/budget-auto-result-v25-20261016.iP6kHj`
- previous：`/opt/audit-agent/releases/material-v24-20261016.zUf8nY`

实际输出 `BUDGET_AUTO_RESULT_TEST26_DEPLOY=PASS`，入口 HTML 与三个直接引用资源的 HTTP 内容和 release 文件逐字节匹配，backend/ai active。实际健康响应 `{"code":200,"message":"success","data":{"service":"audit-backend","status":"UP"}}`。

本次未在26重新提交真实业务上传；跳转行为由两种真实浏览器配合合成 API 验证。未提交 Git、未制作甲方更新包。

甲方 ARM 原生环境及其模型网关：NOT VERIFIED。合成 API 浏览器回归不能替代甲方真实解析验证。
