# 预算返回与全部历史结果

用户明确要求“返回”和全部历史结果，沿用现有页面样式。修改 `frontend/src/app/BudgetCheckPage.tsx`：结果/历史页顶部返回上传页，关闭自动打开结果并隔离迟到读取、审查和确认响应；新批仅自动选新操作；支持不在当前浏览器批次中的真实服务器taskId。新增 `frontend/src/features/budget/BudgetResultHistory.tsx`：调用已有 `listBudgetValidationRecords`，每页20条，上下翻页，不传年度、部门或本批ID过滤；历史读取失败显示错误，未知状态不当作失败，失败或尚未审查记录不显示通过结论。结果页显示材料名、创建时间和已保存提示，预算结果明细组件按taskId重建，避免确认状态跨任务。

没有修改后端、数据库结构、AI配置、Nginx配置或解析规则；材料清单继续隐藏。任务、明细和历史查询继续使用既有DM8事实源。打包工具改为重复执行校验新版本自身资源，并支持上一轮预算完整前端和数量汇总前端两个基线。工作区既有改动保留，无提交推送。

Windows JDK17 / Node：

```powershell
# frontend
$tests=rg --files src | Where-Object {$_ -match '\.test\.ts$'}
node --experimental-strip-types --test @tests
npm run build
# backend (已有JAVA_HOME=JDK17)
.\mvnw.cmd clean verify
# repo
node tools/build-procurement-browser.mjs frontend/dist .runtime/budget-history-frontend-final
node tools/test-budget-material-background.mjs E:/audit-agent-new/.runtime/browser-compat-test/chrome-win/chrome.exe .runtime/budget-history-frontend-final budget-history-final-109
node tools/test-budget-material-background.mjs 'C:/Program Files/Google/Chrome/Application/chrome.exe' .runtime/budget-history-frontend-final budget-history-final-current
node tools/test-procurement-material-background.mjs E:/audit-agent-new/.runtime/browser-compat-test/chrome-win/chrome.exe .runtime/budget-history-frontend-final budget-history-final-procurement-109
python tools/build-procurement-simple-package.py .runtime/budget-history-frontend-final .runtime/budget-history-delivery .runtime/material-counts-only-frontend .runtime/budget-background-frontend-final --name AuditAgent_Budget_History_20261010 --readme docs/budget-result-navigation.md
git diff --check
```

前端722项通过，最终生产构建通过，日志 `.runtime/budget-history-front-tests.log` / `budget-history-front-build-final.log`。后端2182项、0失败、0错误、2 skipped、BUILD SUCCESS，日志 `.runtime/budget-history-backend-verify.log`。diff检查通过。

Chrome109.0.5412.0和Chrome155.0.8059.39真实构建与明确HTTP替身：原12文件目录拖放、21MB上传、首失败继续、丢失响应恢复、离页/刷新同ID仍通过。新增返回上传、迟到结果不能跳回、第二批不删除旧服务器记录、36记录两页全部历史、跨年度/部门/旧批次结果读取、清空localStorage后仍通过服务器发现历史、历史请求失败不显示空历史、FAILED不显示通过、PARSED无明细不假装审查完成、UNKNOWN不显示FAILED，全部通过，无pageerror。采购原字体/目录/清空/迟到隔离回归通过。报告和截图在 `.runtime/budget-background-browser/budget-history-final-{109,current}*.json/png` 与 `.runtime/async-material-browser/budget-history-final-procurement-109.json`；页面截图已查看，顶部返回/历史入口和原布局保留。

26真实Kylin/DM8只读：从现有后端环境注入连接，Java SELECT-only检查返回 `REAL_DM8_SAVED_BUDGET_HISTORY=PASS tasks=211 reviewItems=522 tasksWithResults=175`，不输出材料内容/任务ID/凭据，没有数据写入。命令 `/opt/java/current/bin/java --class-path '/home/admin/batch12-20261010/jar/BOOT-INF/lib/*' /home/admin/batch12-20261010/BudgetHistoryReadCheck.java`，日志 `/home/admin/batch12-20261010/budget-history-db-read.log`。

26当前 `/opt/audit-agent/releases/budget-background-20261010.YqsRoH` 仅更新前端入口。归档SHA、manifest、shell语法、前端逐资源HTTP hash、未登录401、健康200通过。重复运行更新也通过。原JAR、backend.env、audit-ai.env、nginx.conf SHA不变，backend/ai/nginx均active，没有重启。真实curl `/api/v1/health` 返回 `{"code":200,"message":"success","data":{"status":"UP","service":"audit-backend"}}`；`/api/v1/budget/validation-records` 未登录401。新入口 SHA `5a203eafd70d448129db3725e63267d4c4dfea32491a93f1bb7742013d368ebd`。日志 `/home/admin/batch12-20261010/budget-history-update.log` / `budget-history-repeat-update.log`。原入口恢复目录 `/opt/audit-agent/frontend-backups/material-simple-20261010.Pbr2QF`；重复执行备份i7fJA3是新入口，恢复旧版应使用Pbr2QF。Nginx保留既有server_name警告，语法测试成功。

桌面 `Audit_agent/AuditAgent_Budget_History_20261010.tar.gz`，1709476字节，SHA-256 `c79cb7c6bb6c75f5b3bf85a5bfd44af5c624d67fc3013c5d3c6c019e34ec84fb`，源/桌面哈希一致，附.sha256；旧更新包保留。本包仅静态前端和更新脚本、无真实数据/环境/密钥。

甲方ARM64浏览器、本轮真实登录后的历史HTTP页面读取、实际交付回滚执行：NOT VERIFIED。现有历史接口未改；浏览器HTTP替身及真实DM8只读结果不冒充甲方端到端验证。
