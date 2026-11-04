# 预算结果页样式简化

范围仅为预算结果展示样式：删除单独的“材料分析结果 / 历史记录”标题卡片；保留一个白色结果面板、文件名、分隔线及结果内容。文件及状态图标缩小并去除底框，去除阴影，缩小圆角与留白。异常标红和金额、处理状态、原错误说明、导出及右上角返回逻辑保留。未修改采购、合同、预算提取或模型业务代码。

修改：`frontend/src/features/budget/BudgetHistoryResults.tsx`、`frontend/src/styles/budget-results.css`。工作树其余变更原样保留，没有提交 Git。

## 页面验证

- `cd frontend; npm run build` 成功，现有 chunk 大小警告保留。
- `node tools/build-procurement-browser.mjs frontend/dist .runtime/budget-simple-style-v2`，221 项 Chrome 109 颜色回退，原 CSS 和字体/currentColor shim 保留。
- Chrome 109.0.5412.0，`tools/test-budget-processing-outcome-view.mjs`：处理中、无批复、技术失败及未确认结果解释正常；全部结束后的自动跳转通过，页面异常为空。
- Chrome 109，`tools/test-budget-model-results-view.mjs`：1440px 8 份格式结果、390px 9 份业务结果通过；结果容器没有横向溢出；每份详情一个返回按钮。
- 加载 26 的真实页面资源并模拟传输已有模型结果：1440px 8 份结果通过，页面异常为空。报告 `.runtime/budget-simple-style26-chrome109/browser-check.json`。
- 桌面/窄屏状态说明截图已人工查看；不存在原有标题卡片或说明框套框。
- `git diff --check` 通过。
- JDK 17.0.18，`cd backend; .\mvnw.cmd clean verify`：2446 tests，0 failures、0 errors、2 skipped，BUILD SUCCESS，4 分 52 秒。日志 `.runtime/budget-simple-style-verify.log`。本次没有发布该后端构建产物。

页面测试使用仿真 API 数据，不能视为新的上传/模型/数据库 E2E 验收。甲方 ARM64 本机字体渲染 NOT VERIFIED。

## 26 部署

包 SHA-256：`e01d0eb3fc56f3ed75ec8d0238bcca1880ed57859f828f0a6e9a79022d1e0abd`。

执行前校验固定 release 和上一版 index SHA `6f2ddd574e5179b35b9b52f6a97ddcbb39357a4c45850073d87e7593baf71423`、包 SHA、7 项 manifest、Shell 语法及静态资源同名冲突。`update.sh --check`、`update.sh` 均 PASS。新增静态资源后原子替换入口，旧入口和静态资源保留。

原 release 保持 `material-timeout-20261019.8Ert0b`，恢复目录 `/opt/audit-agent/frontend-backups/budget-simple-v2-20261020.nrwQBf`。后端/AI/Nginx PID 前后分别为 80644、79080、132，无服务重启。未写数据库，没有修改运行环境和 Nginx 配置；既有 server_name 警告仍保留，Nginx 语法通过。

入口和全部静态文件经 HTTP 获取后与包内哈希相同；未登录预算历史和采购项目接口均 401。后端 `/api/v1/health` 返回 `code=200, status=UP`，AI `/health` 返回 `status=UP`。
