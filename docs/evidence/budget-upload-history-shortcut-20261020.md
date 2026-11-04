# 预算上传区历史结果快捷入口

在上传区“开始智能解析”旁新增“查看历史结果”，复用现有 `openHistory`。不新增页面、接口或数据库状态，不修改原有自动跳转结果行为和布局。

从现有上传批次快照判断展示：未选择新文件、当前没有正在上传/等待上传/排队/处理的材料，并且至少存在一个有服务端记录的已结束上传结果时显示。成功结果和未生成结果的说明均可经历史入口查阅。首次进入没有上传记录时不显示，选择下一批材料时隐藏；服务端未证实保存的失败上传不单独触发按钮。此入口不把 UNKNOWN 或读取结束标为分析成功。

代码修改：`frontend/src/app/BudgetCheckPage.tsx`；现有 `tools/test-budget-processing-outcome-view.mjs` 增加首次隐藏、批次结束后显示、进入历史列表、返回仍可用、选择新文件隐藏的断言，并支持加载 26 的实际静态资源。其余工作树改动保持原样，未提交 Git。

## 验证

`cd frontend; npm run build` 成功，既有 bundle 大小警告保留。兼容构建 `node tools/build-procurement-browser.mjs frontend/dist .runtime/budget-history-shortcut-v3` 成功，保留 221 项 Chrome 109 颜色回退及字体/currentColor shim。`git diff --check` 通过。

Chrome 109.0.5412.0 与 Chromium 151.0.7922.34 执行 `tools/test-budget-processing-outcome-view.mjs` 均通过，页面错误为空；包含原有状态解释和自动跳转回归。Chrome 109 加载 26 的真实页面资源后重跑同组检查通过。浏览器测试 API 模拟已有样本响应，不是新的 DM8/模型 E2E 验收。

JDK 17.0.18 下 `cd backend; .\mvnw.cmd clean verify`：2446 tests，0 failures、0 errors、2 skipped，BUILD SUCCESS，5 分 8 秒。日志 `.runtime/budget-history-shortcut-verify.log`。该后端产物没有部署。

## 26 部署

包 SHA-256：`8a9041275fb06f17a0a8e8f2d3dc8f00708e8f89f3347695cb1e1cd31bee6885`。固定上一版入口 `67b2aaebb3250db10357463482e01b8faf1e3cb8dc5554a72bac43f5091d033d` 和 release 校验通过；7 项 manifest、Shell 语法、`update.sh --check`、`update.sh` 均 PASS。

原 release `material-timeout-20261019.8Ert0b` 保留，恢复目录 `/opt/audit-agent/frontend-backups/budget-history-shortcut-v3-20261020.KHQhIC`。入口及全部静态资源 HTTP 哈希匹配，预算历史/采购项目未登录接口 401。真实后端健康 `code=200, status=UP`、AI 健康 `status=UP`。后端/AI/Nginx PID 前后 80644/79080/132，无重启、数据库操作或环境/Nginx 配置修改。
