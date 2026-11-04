# 预算页面精简（2026-10-15）

## 最终行为

- 移除跨文件整体结果及其入口、自动打开逻辑。
- 移除“预算校验概览”和两个重复的流程入口；两个入口原先均跳到无材料时的空审查页面。
- 移除“解析与校验范围”旁的“查看历史记录”，保留页头唯一的“历史结果”入口。
- 上传及后台解析完成后留在当前页面，不强制跳转。通过历史列表打开单份材料结果并导出；历史的筛选、分页和逐级返回保留。
- 首页保留上传区、范围说明、年度/部门选择及原有拆分风险分析。

## 修改文件

- `frontend/src/app/BudgetCheckPage.tsx`
- `frontend/src/features/budget/BudgetHistoryResults.tsx`
- `frontend/src/features/budget/BudgetFileResults.tsx`：原结果组件收敛为仅接收一个 `taskId`，不再提供整体结果模式。
- `tools/test-budget-material-background.mjs`：回归转为检验移除入口后仍可上传、重入、通过历史找回单文件结果。

## 已执行验证

Windows，已有 Node、JDK 17、浏览器；未安装系统软件。

```text
frontend: npm run build
node tools/build-procurement-browser.mjs frontend/dist .runtime/budget-simple-v6-compatible
node tools/test-budget-material-background.mjs <Chrome109> .runtime/budget-simple-v6-compatible budget-simple-v6-109
node tools/test-budget-material-background.mjs <Chromium151> .runtime/budget-simple-v6-compatible budget-simple-v6-151
node tools/test-procurement-material-background.mjs <Chrome109> .runtime/budget-simple-v6-compatible budget-simple-v6-procurement-109
git diff --check
```

前端构建 PASS，颜色回退 221 项；Chrome **109.0.5412.0**、Chromium **151.0.7922.34** 的预算回归 PASS，无页面脚本错误，Chrome 109 首页截图已人工查看。采购 Chrome 109 回归 PASS，十个表头仍使用已有自带字体。

预算回归使用明确的 HTTP 测试替身，覆盖 12 份文件/文件夹拖放、21MB 文件、首文件失败后继续、丢失响应恢复、离页和刷新不重复上传、解析结束不跳转、无批量结果读取、下一批不删历史、单文件结果/导出隔离、失效/未完成状态如实展示、迟到响应不重开详情、历史筛选/分页/逐级返回。此测试不代表真实模型解析验收。

报告和截图：`.runtime/budget-background-browser/budget-simple-v6-{109,151}*`；采购报告：`.runtime/async-material-browser/budget-simple-v6-procurement-109.json`。

后端 JDK 17 `mvnw.cmd clean verify`：BUILD SUCCESS，2201 tests，0 failures，0 errors，2 skipped。日志：`.runtime/budget-simple-v6-backend-verify.log`。

## 测试机前端发布

前端补丁 `Budget_Simple_v6.tar.gz`；SHA-256：`49aab6d7477acb038ca9b0f821a6a1875262e3dace349ab828ed66d357719d71`。

服务器外层哈希、包内 8 项 manifest 和 `bash -n update.sh` 均 PASS；脚本锁定前一版 release 后执行前端切换，输出 `BUDGET_SIMPLE_UPDATE=PASS`。

- 当前 release：`/opt/audit-agent/releases/budget-simple-v6-20261015.68l2zn`
- 恢复记录：`/opt/audit-agent/update-backups/budget-simple-v6-20261015.jhlNLZ`
- 6 个 HTTP 前端资源哈希一致；字体与兼容运行脚本保持原字节。
- 后端/AI 进程号与切换前一致；后端、AI、Nginx 均 active，无服务重启。
- `curl http://127.0.0.1/api/v1/health`：`{"code":200,"message":"success","data":{"service":"audit-backend","status":"UP"}}`
- `curl http://127.0.0.1:8001/health`：`{"status":"UP","service":"audit-ai"}`
- 后端 JAR、AI 源码和配置哈希均校验未变；本次未修改数据库及缓存。

甲方 ARM 麒麟实机浏览器与低于 Chrome 109 的浏览器本轮 NOT VERIFIED；本轮不操作甲方服务器。
