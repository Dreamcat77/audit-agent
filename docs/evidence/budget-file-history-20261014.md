# 预算历史结果按文件查看（2026-10-14）

本次只调整历史入口：恢复每页 20 份材料的列表，按文件打开对应结果。
文件详情显示材料名称；核对意见和导出仅读取所选 taskId 的持久化结果。
返回历史列表保留当前页码。当前上传批次的整体项目参考结果保持原行为。
不再打开历史入口就抓取所有文件详情并跨文件归集。

修改：`BudgetCheckPage.tsx`、`BudgetHistoryResults.tsx`、`BudgetProjectResults.tsx`、
`tools/test-budget-material-background.mjs`。本轮无后端、数据库或运行配置修改。
Git diff 已检查，已有上一轮预算整体结果相关修改保留，未提交或推送。

## 本机验证

- `backend/`: JDK 17.0.18，`./mvnw.cmd clean verify`，BUILD SUCCESS；
  2198 tests，0 failures，0 errors，2 skipped。
- `frontend/`: `node node_modules/vite/bin/vite.js build`，PASS。
- `node tools/build-procurement-browser.mjs frontend/dist .runtime/budget-history-compat-v1`，PASS；
  保留原 CSS 前缀及 Chrome 109 颜色回退，221 项回退。
- `node --experimental-strip-types --test frontend/src/features/budget/budgetProjectResults.test.ts frontend/src/features/budget/budgetAuditWorkspaceStaticContract.test.ts`，7 PASS。
- `node tools/test-budget-material-background.mjs <browser> .runtime/budget-history-compat-v1 <label>`：
  Chrome 109.0.5412.0 与 Chromium 151.0.7922.34 均 PASS，0 page errors。
  合成接口测试覆盖 136 条历史分页、打开列表不读取所有详情、所选文件单独展示和导出、
  单份文件含多个项目、返回保留第二页、迟到响应不能重新打开详情、清除浏览器记录后仍能查看历史、
  FAILED/PARSED/UNKNOWN 如实展示及列表请求失败不能冒充空历史。
  同时回归 12 文件文件夹拖入、21 MB 文件、失败隔离、响应丢失恢复和批次整体项目归集。
- `node tools/test-procurement-material-background.mjs <chrome109> .runtime/budget-history-compat-v1 history-procurement109`，PASS；
  采购文件夹拖入、大文件、失败隔离及原表头字体正常。
- `git diff --check`、更新脚本 `bash -n`：PASS。
- 浏览器报告及截图：`.runtime/budget-background-browser/history109*`、`history-modern*`。

## 26 代表环境部署

前端补丁 `Budget_History_v1.tar.gz` SHA-256：
`34c817a5607938a8713bf99a84abc6b7da768b08a3dabd8f19046b53e3aa7f2b`。
部署前校验压缩包、全部 manifest 项，随后执行补丁 `bash update.sh`：PASS。

当前 release：`/opt/audit-agent/releases/budget-history-v1-20261014.aFaNuV`。
恢复记录：`/opt/audit-agent/update-backups/budget-history-v1-20261014.hP289d`。
原 release 和原静态资源保留。

6 个前端文件逐个由 HTTP 获取并与包内 SHA-256 对照：全部一致。
`curl /api/v1/health`：HTTP 200，`{"code":200,"message":"success","data":{"status":"UP","service":"audit-backend"}}`。
AI `/health`：`{"status":"UP","service":"audit-ai"}`。
未登录预算历史和采购项目接口：均 HTTP 401。
后端、AI、Nginx 均 active，后端与 AI PID 未改变，无服务重启。
字体及兼容 shim、后端 JAR、AI 源码和运行配置哈希保持一致。
没有执行数据库迁移或业务数据写入；本轮未作新的登录态真实 DM8 历史交互验证。
后端 JAR 保留上一轮真实 DM8 验证版本，SHA-256：
`c3da1eda9ee0312105e228ea268caaab7113caaecf7516d8e38090fdb5b7360f`。

甲方离线 ARM 环境及其浏览器本机字体渲染：NOT VERIFIED。
本补丁只适用于 26 已核对的上一版本，不是甲方通用更新包。
