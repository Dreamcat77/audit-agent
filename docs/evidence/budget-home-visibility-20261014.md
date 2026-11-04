# 预算拆分风险分析只在主页显示

基于融合提交 `e17a1d3eca75c402c371f2adb58d0d59676f4b84`，工作分支 `fix/budget-home-split-risk-20261014`。

原问题：BudgetSplitRiskPanel 放在 BudgetCheckPage 公共尾部，预算主页、流程页、材料结果和历史结果页面均显示，与当前页面的操作目标不一致。

实现：在公共尾部使用原生 HTML hidden 属性，仅在 `!historyOpen && activeStep === 0` 的主页显示分析区。保持组件挂载，切换预算内部页面不会清空已经选择的采购分支或正在进行的拆分风险分析状态。未改该分析区、材料上传及结果界面的样式或业务接口。

修改文件：

- frontend/src/app/BudgetCheckPage.tsx：限定分析区可见范围。
- tools/test-budget-material-background.mjs：现有浏览器回归增加主页/流程/结果/历史及返回导航断言，保存实际 Chrome 109 截图。
- tools/build-procurement-browser.mjs：读取兼容脚本时统一 CRLF 到 LF，使 Windows Git 检出与 GitHub/Linux 构建使用相同的兼容脚本字节及文件名；脚本行为不变。

验证命令及实际结果（Windows / JDK 17.0.18）：

```text
backend/mvnw.cmd clean verify
  BUILD SUCCESS；2194 tests，0 failures、0 errors、2 skipped。
node frontend/node_modules/vite/bin/vite.js build
  PASS。
node tools/build-procurement-browser.mjs frontend/dist .runtime/frontend-compatible-lf
  PASS；保留原 CSS 前缀，221 个既有 Chrome 109 色彩回退。
node --experimental-strip-types --test frontend/src/features/budget/budgetAuditWorkspaceStaticContract.test.ts
  1 test PASS。
node tools/test-budget-material-background.mjs <Chrome109> .runtime/frontend-compatible-lf home109
  Chrome 109.0.5412.0 PASS，splitRiskOnlyOnHome=true，splitRiskHiddenOnWorkflowHistoryAndResults=true，errors=[]。
node tools/test-budget-material-background.mjs <Chromium151> .runtime/frontend-compatible-lf home151
  Chromium 151.0.7922.34 PASS，同样验证主页与流程/历史/结果隔离及返回。
node tools/test-procurement-material-background.mjs <Chrome109> .runtime/frontend-compatible-lf home109
  PASS；tenHeadersCustomFont=true，errors=[]，external=[]。
git diff --check
  PASS。
```

上述浏览器测试使用合成 API 数据验证前端，不代表现场 OCR 或模型端到端验证。覆盖 12 文件、失败隔离、文件夹拖入、离开页面及重载、任务身份、历史查询和导出等原有回归。

最终包内全部 CSS、woff2 字体及 Chrome 109 currentColor 兼容脚本，与此次改动前 26 的已部署前端逐字节一致。源码和构建没有引入新字体、颜色或新浏览器 API。人工查看 Chrome 109 主页分析区与历史页截图通过。

26 更新：前端包 `Budget_Home_v1.tar.gz`，SHA-256 `e43867784d97f0ac4b884b67c3fac12ec1621a865581e881b0ef6804d07979ec`。

实际执行 `bash update.sh` exit 0，`BUDGET_HOME_FRONTEND_UPDATE=PASS`。当前 release `/opt/audit-agent/releases/budget-home-20261014.0Wqh83`，恢复记录 `/opt/audit-agent/update-backups/budget-home-20261014.VN3zKA`，原 release 保留。

全部 6 个前端文件经 Nginx HTTP 校验 SHA-256 一致。后端、AI、Nginx active，后端/AI PID 未变化，无服务重启；后台 JAR、AI 源码、原环境和 Nginx 配置保持一致。预算历史和采购项目匿名接口均 401。

实际 HTTP 健康响应：

```json
{"code":200,"message":"success","data":{"status":"UP","service":"audit-backend"}}
```

AI 健康：`{"status":"UP","service":"audit-ai"}`；从 Windows 访问测试环境前端 HTTP 200。

浏览器兼容范围：本轮实际验证 Chrome 109.0.5412.0 / Chromium 151.0.7922.34（Windows）；低于 109 的浏览器及甲方 ARM 麒麟实际浏览器 NOT VERIFIED。本轮仅更新 26，未操作甲方服务器。
