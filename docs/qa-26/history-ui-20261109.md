# 三模块历史结果页统一 UI 验收

日期：2026-11-09。范围：历史列表及查询交互，不修改审查分析、数据库、权限或接口。26 默认 HTTP 80 已验证，静态版本 `history-ui-20261109-r2`。

## 修改文件与公共结构

- `frontend/src/components/history/HistoryResults.tsx`：公共筛选栏、列表、行、分页页脚；日期显示和状态颜色映射。各模块仍持有自己的查询条件、数据和操作。
- `frontend/src/styles/history-results.css`：统一页面宽度1440px、边距24/20/16px、标题20px、项目名15px、辅助信息12–13px、输入框40px、按钮38px、白色列表及浅色分割线。普通两行记录78px，带文件名的三行记录85px，长名称和小屏自适应。无新增装饰图标，现有顶部导航未改。
- `frontend/src/features/budget/BudgetResultHistory.tsx`：表格改纵向列表；保留批次/单任务打开方式、材料数量与文件名、年度和部门、每页20条及总数。项目名称优先显示，缺少时沿用材料名。查询仍为真实接口支持的材料名称和上传日期，不伪装成项目名称查询。
- `frontend/src/app/BudgetCheckPage.tsx`：历史页标题和外层尺寸对齐，保留列表到批次结果的导航及返回行为。
- `frontend/src/features/procurement/AutomaticCasePanel.tsx`：卡片网格改纵向列表；保留项目名、文件名、数量、时间、状态、游标分页。输入条件点击查询后才应用；保留旧请求/旧分页返回时的隔离，防止切换筛选后混入旧记录。
- `frontend/src/app/App.tsx`：只增加采购外层样式标识，公共 CSS 在历史页去掉重复内边距。上传和详情的原外层间距保留。
- `frontend/src/features/contract/components/ContractAnalysisWorkspace.tsx`：接入相同筛选与列表；保留项目改名及其原幂等保存逻辑、关注数量、每页15条和结果打开。相同条件可重新查询；列表读取失败不再伪装成空记录。详情、八大项、正式审核、原文件证据功能保持。
- `frontend/tests/history-results/index.html`、`main.tsx`、`history.browser.test.mjs`：真实业务组件的浏览器回归夹具。仅测试拦截 API，部署页面始终使用实际接口和真实数据。

日期筛选保留现有单日接口语义；采购发送本地日界限对应的 UTC 起止时间，合同保留时区偏移参数。未新增日期范围接口或重构后端。

## 可执行检查与结果

工作目录：`E:/audit-agent-new/.runtime/budget-home-e17-20261014`。

1. JDK17，`backend` 目录执行 `./mvnw.cmd clean verify`：BUILD SUCCESS，3642项，0失败、0错误、24跳过。日志 `.runtime/history-ui-20261109/backend-verify.log`。
2. `frontend` 执行 `node --experimental-transform-types --test`，传入全部 `src/**/*.test.ts`：872项通过、0失败。日志 `frontend-tests.log`。
3. `frontend` 执行 `npm run build:customer`：通过，`CHROME109_COLORS_READY 220`。保留现有大包体积提醒，不影响构建。
4. 以 `AUDIT_BROWSER_EXECUTABLE` 指向本地 Chrome109，执行 `node --test tests/history-results/history.browser.test.mjs tests/processing-status/processing-status.browser.test.mjs tests/evidence-viewer/evidence.browser.test.mjs`：29项全部通过。日志 `final-browser-tests.log`。
5. `git diff --check --` 本轮生产文件：通过，无空白错误。

历史专项覆盖：草稿不发查询、点击查询及重复查询、上传日期、重置、空结果、失败后恢复、分页、正确记录选择、旧分页响应不能覆盖新筛选、完成/处理/失败颜色、超长名称、四种屏宽以及无行内装饰图标。

## 26 真实页面验收

通过真实应用登录，Chrome109 实际打开预算、采购、合同历史页。未用模拟数据替代服务器数据，未新增上传、模型调用或改写已有结果。

- 三页的真实关键词查询、单日查询、远期日期空结果、重置和准确跳转详情均通过。
- 材料数量与查询响应核对一致。预算真实分页、合同真实分页通过。采购当前共8条，没有下一游标，正确不显示更多按钮；多页游标及迟到响应在专项浏览器夹具中验证。
- 1440/1024/768/375四种视口的输入框均40px，标题均20px，页面标题左右边距一致；文档宽度不超过视口，无横向溢出。
- 合同修改名称入口和取消操作实测正常，取消不改动数据；保存继续复用原实现。本轮未改写已有项目名。
- 三模块真实原文件证据页、高亮、弹窗标题边界、关闭重开通过；合同八大项仍为8项。

证据目录：`.runtime/history-ui-20261109/`。`live-history.json` 保存真实页面断言，`browser-verification.json` 保存原文件证据回归。

实机截图：

- [预算历史页](../../.runtime/history-ui-20261109/live-budget.png)
- [采购历史页](../../.runtime/history-ui-20261109/live-procurement.png)
- [合同历史页](../../.runtime/history-ui-20261109/live-contract.png)
- [合同375px](../../.runtime/history-ui-20261109/live-contract-375.png)

## 发布核对

发布到现有 `evidence-original-20261109-r2` 的前端目录，资源先落盘、入口原子替换，保留旧资源。无DDL、无后端/OCR重启。

- UI修订：`history-ui-20261109-r2`。
- 最终入口SHA256：`a5820e3b0b6f1cc2787a462ceb7690d6162addb0d56de3cd4466e167455b6f68`。
- 默认80入口及全部7个文件的HTTP内容SHA256匹配。
- 后端JAR：`d840b3e23246670ea1c7c3e6259da79bc99e4cc06e89d5c988671b71d2abf2d8`，保持未变。
- 配置、AI源码、服务PID保持，8001/8080健康均UP。完整凭据不写入脚本、日志或报告。
- `.runtime/history-ui-20261109/manifest.json` 保存全部前端源码及产物哈希；`deployment.json` 保存发布核对。`isolated.diff` 为相对于本轮开始时的隔离差异，未覆盖已有未提交工作。

## 未解决事项与边界

本轮范围内未发现阻断使用的问题。甲方现场浏览器实机及ARM64环境 NOT VERIFIED；26 已用真实 Chrome109 验证。预算仍按材料名称查询，采购按项目/材料名称、合同按项目名称查询，属于保留的后端业务差异。未宣称本轮重新验收模型/OCR语义准确率；它们没有改动。未执行Git提交/推送，也未制作甲方更新包。
