# 预算历史详情单一返回入口及前端部署

2026-10-20，预算历史详情移除内部“返回历史列表”，保留页面右上角“返回”。现有页面层级继续负责返回历史列表，再返回预算首页。预算结果卡片、详情标题和已有 Chrome 109 颜色/字体兼容方案保留。

涉及源码：`frontend/src/features/budget/BudgetHistoryResults.tsx`、`frontend/src/styles/budget-results.css`。候选前端还包含此前已授权的预算结果展示修改；本次没有发布工作树中的后端或 Python 候选代码。原有未提交修改保留，没有提交或推送 Git。

## 构建及页面验证

`cd frontend; npm run build` 成功。`node tools/build-procurement-browser.mjs frontend/dist .runtime/budget-history-back-single-v1` 成功，221 项颜色回退，原 CSS 前缀和 currentColor shim 保留。普通 Vite 目录未直接部署。

`tools/test-budget-model-results-view.mjs` 新增详情页恰有一个“返回”、不存在“返回历史列表”的断言；支持 `BUDGET_REVIEW_BASE_URL` 加载部署后的静态资源。API 在测试中以既有真实模型/分析器样本结果模拟，不是正式上传、DM8 持久化或模型调用 E2E。

加载 26 的实际 HTTP 页面资源后：

- Chrome 109.0.5412.0，1440px，8 份格式结果和 9 份业务结果，页面异常为空；每份详情只有一个返回入口，点击后可继续打开历史列表的下一份结果。
- Chrome 109.0.5412.0，390px，8 份结果通过，结果容器无横向溢出。
- Chromium 151.0.7922.34，1440px，8 份结果通过，页面异常为空。

报告：`.runtime/budget-history-front26-chrome109/browser-check.json`、`.runtime/budget-history-front26-chrome109-six/browser-check.json`、`.runtime/budget-history-front26-chrome109-mobile/browser-check.json`、`.runtime/budget-history-front26-modern/browser-check.json`。

旧版 `test-budget-material-background.mjs` 的四个内部返回选择器同步为右上角返回。完整脚本执行到历史失败材料时，仍按旧文案找“查看结果”，而现界面使用“查看说明”，因而未完成。采购旧脚本预期 10 列，现基线为 9 列，断言未通过；本次没有修改采购页面以迁就该断言。两项完整回归不计为通过。

`git diff --check` 通过。甲方 ARM64 浏览器实际字体环境 NOT VERIFIED。

JDK 17.0.18 下 `cd backend; .\mvnw.cmd clean verify`：2446 tests，0 failures，0 errors，2 skipped，BUILD SUCCESS，5 分 3 秒。日志 `.runtime/budget-history-return-verify-jdk17.log`。本机默认 JDK 21 的首次执行被 enforcer 拒绝，改用已安装 JDK 17 后完整执行成功；未安装或修改系统 Java。该构建产物没有部署到 26。

## 26 部署

静态补丁包 SHA-256：`7d0d1f970aafc51edb63f11b1fad7fb77c4f5bed2db4bd7fafcd11db51a996d5`。

部署前固定校验 release、原入口 SHA、包内 8 项 manifest、脚本语法及同名静态资源不冲突。入口基线为 `eb8c2a641a2c6ee838636591905dd6dd66a04b22c5687824f6455a747a6624a9`。

首次验包发现 manifest 使用 CRLF，预检按 PACKAGE_HASH 停止，没有写入线上资源；本机构建改为 LF 后重新上传、验证通过，才执行更新。

26 执行 `sudo bash update.sh --check` 输出 `BUDGET_HISTORY_FRONTEND_PREFLIGHT=PASS`；执行 `sudo bash update.sh` 输出 `BUDGET_HISTORY_FRONTEND_UPDATE=PASS`。

release 保持 `/opt/audit-agent/releases/material-timeout-20261019.8Ert0b`，原入口备份在 `/opt/audit-agent/frontend-backups/budget-history-back-single-20261020.PQIZXL`。新增带哈希静态资源后原子替换 index；后端、AI、Nginx 未重启。没有执行数据库 SQL，没有改环境文件或 Nginx 配置。Nginx 有既有 server_name 冲突 warning，语法检查通过。

入口与全部 5 项资源 HTTP 字节哈希匹配包内文件。未登录预算历史、采购项目接口均 401。真实健康响应：

```json
{"code":200,"message":"success","data":{"service":"audit-backend","status":"UP"}}
{"status":"UP","service":"audit-ai"}
```

后端 JAR SHA 与上一部署包一致：`0d2de22946174ecd3ae45df5118cdc7a1b058cbb93b4a829c3ae21dd3b73c383`。服务 active，启动时间早于本次更新。
