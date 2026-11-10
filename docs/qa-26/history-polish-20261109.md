# 历史页状态、分页与预算结果读取修正

2026-11-09，26 默认80入口，前端修订 `history-polish-20261109-r1`。

## 本次修改

- 三个历史页共同使用 `historyPresentation.ts` 的任务状态：进行中为“正在分析”，已有分析结果（包括PARTIAL/DATA_INSUFFICIENT/WAITING_CONFIRMATION）为“已完成”。不再将覆盖程度当作处理进度。FAILED/UNKNOWN仍区分为“已结束”，不把未取得结果的执行伪装成分析完成；未认识的新状态显示“状态待核实”。不改变后端状态或报告结论。
- 新增共用 `HistoryPagination`。预算保留总条数/总页数；采购按现有游标实现上一页、页码、下一页，不再采用累加的“更多历史结果”；合同保留原有页码查询。只有一页时也显示分页，禁用不可用按钮。未知总数的接口不伪造总页数。筛选后回到第1页，采购筛选/翻页的旧响应不能覆盖新页面，切换账号后清除本地游标。
- 合同删除修改名称入口及其UI状态/保存逻辑，项目名、关注数量、查看结果与八大项保留。
- 预算历史结果打开立即显示所选项目和材料数量，并滚动到详情开头；读取已有结果时显示“正在读取结果”及实际已读份数，不再误示为重新解析。
- 预算GET读取从4路改为6路有界并发（不改变OCR/模型并发）。批次全部读取完成后才展示整批结论；离开后AbortSignal取消正在等待的请求，不继续发送排队的文件读取。旧单任务回退复用已读取的结果，避免重复GET。

生产文件：

- `frontend/src/components/history/HistoryResults.tsx`
- `frontend/src/components/history/historyPresentation.ts`
- `frontend/src/styles/history-results.css`
- `frontend/src/features/budget/BudgetResultHistory.tsx`
- `frontend/src/features/budget/BudgetHistoryResults.tsx`
- `frontend/src/features/budget/BudgetFileResults.tsx`
- `frontend/src/features/procurement/AutomaticCasePanel.tsx`
- `frontend/src/features/contract/components/ContractAnalysisWorkspace.tsx`
- `frontend/src/api/audit.ts`

## 延迟排查证据

真实26、Chrome109、12份材料批次：修改前共1次批次GET＋12次结果GET，后者按4路分3轮读取，任何一份未返回都会阻止整个报告显示。当前正常网络测得点击至结果约259ms，单个请求18–35ms，未复现稳定的长时间后端延迟。因此不能声称已证明用户当时卡顿的唯一原因，也没有重跑OCR/模型来“刷新”历史结果。

附加250ms响应延迟的受控浏览器实验（服务器仍返回真实数据）：原版最后一个结果约1223ms返回，新版约910ms返回，请求从3轮减少为2轮。整页交互测量分别约1380/1421ms，受点击滚动/调度影响，并未证明整页耗时稳定降低。此次改进可确认的是更短的读取链路、立即反馈和退出取消，不据此夸大整页加速比例。

原始数据：`.runtime/history-polish-20261109/profile-before.json`、`profile-before-slow.json`、`profile-after-slow.json`。初次CDP慢网模拟未取得有效结果，改用对真实响应注入250ms延迟；未把失败的模拟当成服务器故障结论。

## 验证

- JDK17，在backend目录 `./mvnw.cmd clean verify`：3642项、0失败、0错误、24跳过，BUILD SUCCESS。
- 前端全部Node测试：`node --experimental-transform-types --test` 传入 `src/**/*.test.ts`，875项通过。
- `npm run build:customer`：通过，`CHROME109_COLORS_READY 220`。
- Chrome109：`node --test tests/history-results/history.browser.test.mjs tests/processing-status/processing-status.browser.test.mjs tests/evidence-viewer/evidence.browser.test.mjs`，31项通过。包括三模块一致状态、无改名入口、前后翻页与当前页禁用、筛选重置、迟到的旧分页响应、长名称/四屏宽、慢批次最多6路、未全部读取不得展示片面结论、退出取消且不再发送剩余6个请求、原文件证据高亮和弹窗边界。
- 26真实Chrome109：预算/采购/合同查询、日期、空结果、重置、分页区域、真实详情跳转通过；预算和合同实际前翻页通过，采购目前不足一页，上一页/下一页禁用正常，多页及返回上页由专项浏览器夹具验证。实际正在分析的采购任务保留，不中断也不强制当成已完成。合同八项仍8项，三模块原文件证据页、高亮和关闭重开通过。
- 1440/1024/768/375px实际页面均无横向溢出。合同没有修改名称，完成记录显示绿色“已完成”，处理中的记录保持蓝色“正在分析”。

实机/专项证据：`.runtime/history-polish-20261109/` 的 `live-history.json`、`browser-verification.json`、`browser-tests.log`、`frontend-tests.log`、`backend-verify.log`，以及 `pagination-budget.png`、`pagination-procurement.png`、`pagination-contract.png`。

## 发布

- 最终入口SHA256：`98013176d1aceb4bb8aa4b14157628648f6b643ef3fa479cca2c8422f37d8d99`。
- 默认80及全部7个资源HTTP哈希一致。
- 只发布前端，保留原JAR、AI源码、配置、服务PID，8001/8080健康UP。无DDL、无后台任务中断、无数据库业务写入、无Git推送或甲方包。
- 本地源码哈希与发布manifest核对，独立记录在同目录 `manifest.json`、`deployment.json`。甲方现场实机NOT VERIFIED。
