# 预算历史筛选与默认首页（2026-10-14）

需求：历史按上传年月日和材料名称模糊查询，美化列表及分页；进入预算模块默认显示首页。

实现：复用合同页 ChineseDateInput、筛选栏布局及采购列表分页按钮风格。
增加材料名称关键字、上传开始/结束日期、查询和重置，上传时间列、状态标签、查看结果按钮、页码按钮。
日期按 UTC+08:00 上传日，起日包含、止日包含；SQL 使用次日零点排他上界，避免遗漏止日记录。
名称大小写不敏感，使用参数绑定 INSTR 查询，`%` 和 `_` 按字面字符处理。
查询在 DM8 分页前执行，列表和总数使用相同条件；同时间记录以 ID 补充排序。
打开文件再返回保留筛选及页码。空结果、读取失败和非法日期范围分别提示。
预算模块首次进入、切换回来、刷新均显示首页，已有后台任务和 DM8 历史不清空。
用户当次主动上传完成仍可显示整体参考结果。

主要修改：BudgetResultHistory、BudgetCheckPage、ChineseDateInput（可选无障碍名称）、API 类型，
BudgetController、BudgetReviewQueryService、BudgetHistoryFilter、预算任务 Repository 与 DM8 Mapper。
历史结果仍按文件查看。无表结构变更、无模型设置或 Nginx 配置修改。
此前工作树内的预算整体结果修改保留；本轮未提交或推送。

## 验证证据

- JDK 17.0.18：`backend/./mvnw.cmd clean verify`，2201 tests，0 failures，0 errors，2 skipped，BUILD SUCCESS。
  日志 `.runtime/history-filter-backend-final.log`。
  新测试覆盖日期边界、同日止界、名称大小写与字面通配符、筛选前分页、稳定排序、反向日期和超长名称拒绝、API 时间字段。
- `frontend/`: `node node_modules/vite/bin/vite.js build`，PASS。
- `node tools/build-procurement-browser.mjs frontend/dist .runtime/budget-history-filter-compat-v3`，PASS；
  保留原 CSS 前缀、字体和 Chrome 109 currentColor shim，221 项颜色回退。
- `node tools/test-budget-material-background.mjs <browser> .runtime/budget-history-filter-compat-v3 <label>`：
  Chrome 109.0.5412.0 与 Chromium 151.0.7922.34 PASS，0 page errors。
  136 条历史，联合筛选、查询回到第一页、重置、非法范围不发请求、详情返回保留条件、空结果、错误提示、分页和单文件导出隔离；
  同时覆盖进入/返回预算模块及刷新显示首页、12 文件文件夹拖入、21 MB 大文件、失败隔离和后台结果恢复。
  报告 `.runtime/budget-background-browser/filter109-v3.json`、`filter-modern-v3.json`；
  筛选栏和底部分页截图 `filter109-pagination-history-filters.png`、`filter109-pagination-history-pagination.png`。
- `node tools/test-procurement-material-background.mjs <chrome109> .runtime/budget-history-filter-compat-v3 filter-procurement109`，PASS，0 errors。
- 分组/工作区 Node 测试 7 PASS，`git diff --check` 与部署脚本 `bash -n` PASS。

## 26 真实平台预检

候选 JAR SHA-256：`75dc81344605a31522b827da866f361462e54aa77b371ddaed30a00e9c667ddb`。
运行临时独立 dev29 服务，绑定 loopback 18085，关闭预算/采购后台 worker，独立 Redis Session namespace。
在原后端工作目录启动以读取一致的外部配置；第一次临时启动因工作目录配置差异未通过预检，
修正临时工作目录后通过，没有执行数据库修复。
HTTP 检查脚本对比全部历史：名称、上传日、联合条件、每页 2 条、总数、顺序、无匹配、非法范围 HTTP 422 和非法日期 HTTP 400：PASS。
输出 `REAL_HTTP_DM8_HISTORY_FILTERS=PASS`。不读取/输出解析正文，不写业务材料或审查事实。
使用单独随机测试账号，密码仅经匿名管道，测试后账号 DISABLED；临时服务正常退出。
日志目录 `/home/admin/budget-history-filter-isolated-20261014.GcWwaN`。

26 补丁 `Budget_History_v2.tar.gz` SHA-256：
`970ceca5491a0d370f101d654de75a231b71c7f247536e49ec84e0be572b039e`。
这是针对 26 先前版本的内部补丁，不是甲方通用安装包。

## 26 部署结果

服务器压缩包 SHA-256 与本机一致；解包后 manifest 全部通过，脚本语法通过。
更新脚本执行 `BUDGET_HISTORY_FILTER_UPDATE=PASS`，当前 release：
`/opt/audit-agent/releases/budget-history-v2-20261014.EuM0Pm`；恢复记录：
`/opt/audit-agent/update-backups/budget-history-v2-20261014.HvSCMD`。
6 个前端文件逐个经 Nginx HTTP 读取并与包内 SHA-256 对照一致。
正式 8080 服务再次运行只读历史筛选检查：`REAL_HTTP_DM8_HISTORY_FILTERS=PASS`，测试身份已 DISABLED。
后端、AI、Nginx 均 active；后端 `/api/v1/health` 和 AI `/health` 为 UP；
未登录预算历史与采购项目接口均 HTTP 401。正式后端 JAR SHA-256 与候选一致。
后端因更新 JAR 正常重启；AI PID 保持不变。部署脚本确认字体、AI 源码、数据库、Redis、Nginx
与 systemd 配置哈希不变；无 Schema 迁移，无业务材料或审查事实写入。

甲方离线 ARM 环境和其实际浏览器本机字体渲染：NOT VERIFIED。

## 单日期筛选调整

根据 2026-10-14 反馈，筛选栏改为“上传日期、材料名称、查询、重置”。只显示一个上传日期，
前端仍将所选日期作为相同的 createdFrom/createdTo 发送，因此服务端按当天完整范围查询。
材料名称输入框桌面宽度约 320px，移动端自适应全宽；不再显示结束日期。

前端重新构建并执行 Chrome 109.0.5412.0、Chromium 151.0.7922.34 自动化：PASS，0 page errors；
检查单日期查询、筛选顺序、名称框宽度、筛选分页/重置、详情返回保留条件和预算默认首页。
采购 Chrome 109 上传回归 PASS，Node 测试 7 PASS。JDK 17 `mvnw clean verify`：
2201 tests，0 failures，0 errors，2 skipped，BUILD SUCCESS。

26 前端补丁 `Budget_History_v3.tar.gz` SHA-256：
`62bd4b81073e13e676915a9b0fd9425eb4484750893b4b140fd1553d7b1e4720`。
manifest、脚本语法和 6 个 Nginx HTTP 资源哈希均通过。当前 release：
`/opt/audit-agent/releases/budget-history-v3-20261014.fMcrv2`；恢复记录：
`/opt/audit-agent/update-backups/budget-history-v3-20261014.RQSDLl`。
后端、AI、Nginx 均 active，健康检查 UP；后端和 AI PID 未变化，无服务重启。
字体、后端、AI、数据库、Redis、Nginx 和运行配置未修改。

## 历史详情返回层级修复

顶部“返回”现在按页面层级执行：历史文件详情先返回历史列表，再次点击才返回预算首页。
文件详情选择状态提升到预算页面层，历史列表组件保持挂载，因此筛选条件和页码不会丢失；
详情内的“返回历史列表”入口继续保留，行为一致。

Chrome 109.0.5412.0 与 Chromium 151.0.7922.34 自动化均 PASS，明确验证两次连续返回及筛选状态保留；
采购 Chrome 109 回归 PASS，Node 测试 7 PASS。JDK 17 `mvnw clean verify`：
2201 tests，0 failures，0 errors，2 skipped，BUILD SUCCESS。

26 前端补丁 `Budget_History_v4.tar.gz` SHA-256：
`661a07030330a0645a05cddcc1afdeada0757f0253ff87ff419ae249603c7786`。
manifest、脚本语法和 6 个 Nginx HTTP 资源哈希均通过。当前 release：
`/opt/audit-agent/releases/budget-history-v4-20261014.bh0x00`；恢复记录：
`/opt/audit-agent/update-backups/budget-history-v4-20261014.DnM9y0`。
后端、AI、Nginx 均 active，健康检查 UP；后端和 AI PID 未变化，无服务重启。
字体、后端、AI、数据库、Redis、Nginx 和运行配置未修改。
