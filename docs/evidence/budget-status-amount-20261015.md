# 预算任务状态与 Excel 申报金额列修复（2026-10-15）

## 问题和结论

历史列表原先将任务 PARSED 显示为“已解析”。BudgetMaterialService 在读取表格后先持久化 PARSED，再在事务外执行规则及 AI 辅助核对，因此此状态不能证明分析完成。后台工作记录的 PARSED 是另一种语义，代表后台工作成功结束，不能与预算任务 PARSED 混用。

Excel 原实现接受任何包含“金额”的列，并由后面的列覆盖前面的列。本轮回归在旧实现上复现：申报 31,200 元被后面的批复参考 32,760 元替代；只有批复列的表格也被误接受为申报表。公式金额原先未求值。

结果中的 approvalAmount 则来自 BudgetReviewService.resolveApproval() 匹配活动 BUDGET_SUBJECT_MAPPING 的 budgetAmount，既不是文件中的参考金额，也不是由大模型生成金额。预算 Excel 使用 Apache POI 结构化读取，AI 用于辅助语义核对。本轮不将文件参考值当作正式系统批复，不新增字段、迁移或修改旧结果。

## 修改

- ApachePoiBudgetMaterialParser.java：每个候选表头单独识别；科目编码优先于名称；限定申报/申请、预算、通用金额列别名与优先级；排除批复参考、差额、单价等其他金额列；同优先级申报列歧义时拒绝；计算金额公式。
- ExcelBudgetMaterialParserTest.java：增加列顺序、参考列隔离、只有批复列拒绝、公式及重复申报列拒绝回归。
- budgetResultState.ts / .test.ts：统一状态和系统批复金额提示，零金额不视为缺失，未知状态不冒充失败或成功。
- BudgetResultHistory.tsx、BudgetFileResults.tsx：未完成时自动串行刷新，退出或切换查询后忽略迟到响应；错误如实提示。页面金额明确为“系统批复金额”，无匹配时说明原因。
- test-budget-material-background.mjs：增加历史 PROCESSING → PARSED → REVIEWED 和结果自动更新回归；保留原上传/筛选/分页/返回/结果隔离测试。

## 本机隔离验证

环境：Windows，JDK 17，独立 worktree，浏览器测试使用 HTTP 测试替身。

```powershell
# backend 目录；JAVA_HOME 指向已有 JDK 17
.\mvnw.cmd -Dtest=ExcelBudgetMaterialParserTest test
.\mvnw.cmd clean verify
# worktree 根目录
node --test frontend/src/features/budget/budgetResultState.test.ts frontend/src/features/budget/budgetProjectResults.test.ts
node tools/test-budget-material-background.mjs E:/audit-agent-new/.runtime/browser-compat-test/chrome-win/chrome.exe .runtime/budget-status-amount-v8-compatible budget-status-amount-v8-109
node tools/test-budget-material-background.mjs C:/Users/29339/AppData/Local/ms-playwright/chromium-1234/chrome-win64/chrome.exe .runtime/budget-status-amount-v8-compatible budget-status-amount-v8-151
git diff --check
```

修复前定向测试 4 项中 3 项失败；修复后 4 项全部通过。完整 verify：2204 tests，0 failures，0 errors，2 skipped，BUILD SUCCESS。前端 npm run build 通过，兼容构建保留 221 项颜色回退，状态/归集单测 8 项通过。Chrome 109.0.5412.0 与 Chromium 151.0.7922.34 浏览器回归均通过，无页面脚本错误；Chrome 109 结果截图已目视核查。首次新增浏览器断言因测试材料落在第二页超时，改为按名称筛选后两浏览器通过，没有放宽断言。

日志：`.runtime/budget-status-amount-v8-verify.log`。浏览器报告：`.runtime/budget-background-browser/budget-status-amount-v8-{109,151}.json` 及同前缀截图。

## 26 真实诊断与发布

SELECT-only 探针检查最近两次会议室仿真任务：任务 REVIEWED，工作 PARSED；每次 4 行，全部 UNMATCHED 且 approvalAmount 为空。活动系统科目映射共 3 条，样例四个科目的精确映射计数均为 0。只输出状态/计数，不输出文件内容、数据库凭据或业务记录，结束 rollback。结合保存的 UNMATCHED 可确认本次未找到适用批复记录，不能归因于缺表或 AI 漏提参考金额。

同一 XLSX 使用旧部署解析器离线读取：`FIXTURE_ROWS=4; DECLARED_COLUMN_CORRECT=false`。新包 JAR 在服务器独立目录解出自己的 class 和 lib 后执行相同探针：`FIXTURE_ROWS=4; DECLARED_COLUMN_CORRECT=true`。此探针不调用模型，不写业务数据。

补丁：`Budget_Fix_v8.tar.gz`，SHA-256：`a8acde74b75031b0baa1f2a187dbf1711bb1a738ad794b0024876647f5e0f6cd`。仅用于 26 当前 v7 基线，脚本精确锁定原 release；不能直接作为甲方通用更新包。

服务器外层 SHA-256、包内 9 项 manifest、`bash -n update.sh` 通过。脚本取得安装锁、复制原 release、覆盖新前端和 JAR，保存配置哈希及恢复路径，失败时恢复原 current 并重启后端。未执行数据库迁移、测试账号创建或材料上传。

- 当前 release：`/opt/audit-agent/releases/budget-fix-v8-20261015.Iajywj`
- 恢复记录：`/opt/audit-agent/update-backups/budget-fix-v8-20261015.ogAf42`
- 原 release：`/opt/audit-agent/releases/budget-feedback-v7-20261015.IATmx3`
- 发布返回 `BUDGET_STATUS_AMOUNT_UPDATE=PASS`；6 项前端 HTTP 资源哈希一致。
- 保留字体、Chrome 109 兼容脚本、AI 源码、AI 地址/密钥/模型名称及现有 Nginx、数据库、Redis 配置，配置哈希一致；只重启后端，AI PID 未变。
- 未登录预算历史和采购项目接口均 401，服务均 active。

健康响应：

```json
{"code":200,"message":"success","data":{"service":"audit-backend","status":"UP"}}
{"status":"UP","service":"audit-ai"}
```

26 真实模型全链路上传解析、甲方 ARM 麒麟实机及低于 Chrome 109 浏览器均 NOT VERIFIED。本轮没有改写历史结果，需要重新上传样例验证新的申报金额读取结果。
