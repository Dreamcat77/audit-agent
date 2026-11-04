# 预算进度、固定科目分类与采购结果操作回归

本次在已有未提交工作上完成局部前端修改，未提交或推送 Git。26 仅替换前端发布资源，没有更新后端 JAR、AI 代码、模型配置或数据库。

## 修改

- BudgetCheckPage：实际文件阶段、文件名及加载圈改为宽屏两列，窄屏一列；长文件名省略并保留完整 title。不添加百分比或把未知状态当作成功。
- budgetRiskCategories / BudgetProjectRiskPanel：固定电子、工程、装潢、其他、科目待核对五类，任意新增原始科目均不增加分类块。保留原始科目字段及真实来源记录。删除查看材料结果跳转，历史结果入口仍保留。
- ResultCopyButton / ProcurementReviewSteps / ProcurementTenderReviewPanel：正文区域显式标记，复制时排除块标题、描述、报告编号、风险摘要头、按钮和输入控件；保留正文中的金额与来源依据。全页面共享最近复制状态，另一块复制时立即重置前一按钮，原按钮可再次复制；异步旧调用不能重新标记已复制。
- ProcurementReviewWizard / ProcurementReviewSteps：在招标结果严重风险旁增加确认入口，复用原有报告级风险知悉弹窗、GET/POST critical-risk-confirmation 接口和长期操作审计。此操作确认本报告严重风险，并非单条风险消除或审批通过；未新建表、未改变原后端确认语义。

## 解析失败诊断

26 journalctl 只读检查服务器时间 2026-10-16 10:23 至 10:35，同一批 BUDGET/BACKGROUND_EXECUTION 结束事件共 12 个，9 SUCCESS、3 FAILED。未读取或导出材料正文、模型原始响应、口令或 Key。

1. 任务 7b3c5767-7c35-435b-950c-33efcbe45cdd：Java MATERIAL_AI_FAILED / HTTP 502，60377 ms。对应 AI 请求 budget-material-7b3c5767-7c35-435b-950c-33efcbe45cdd：模型 HTTP READ_TIMEOUT，约 60078 ms，输入 user_chars=669，input_bytes=3568，read_timeout_ms=60000。此日志能确认读取超时，无法确认模型网关当时内部排队/推理或网络的具体原因，也没有证据表明输入过长、余额不足或表缺失。
2. 任务 f149bd03-c981-4027-948c-d5e27c0d1717：MATERIAL_AI_UNGROUNDED / HTTP 502，88864 ms。前一次模型响应失败后切分，后续响应 STOP、820 completion tokens，但 RESULT_VALIDATION/UNGROUNDED_EVIDENCE 拒绝。
3. 任务 f9d0993b-4f56-46bc-9515-547c6fdf76e5：MATERIAL_AI_UNGROUNDED / HTTP 502，27639 ms。模型响应 STOP、699 completion tokens，随后同样被原文依据校验拒绝。

代码中此校验合并检查：允许字段、来源定位是否存在、摘录是否在原文中、提取值是否在摘录中。现有日志不包含上述分支的独立原因码，无法据此判定具体是哪项不一致；可能涉及模型改写或格式差异，不直接认定为编造，也没有放松安全校验。失败材料不会终止同批其他材料处理。这些诊断不代表解析失败已修复。

## 执行验证

工作区：fix/budget-home-split-risk-20261014。

- JDK 17.0.18，backend 执行 ./mvnw.cmd clean verify：BUILD SUCCESS；2263 tests，0 failures，0 errors，2 skipped。包含原 ProcurementCriticalRiskConfirmationServiceTest 四项：操作人/时间/服务端内容持久化接口、并发重放和竞争实例保护。完整日志在 .runtime/budget-ui-followup-verify.log。
- 前端 npm run build：通过。
- node --experimental-strip-types --test frontend/src/features/budget/*.test.ts frontend/src/features/budget/f05/*.test.ts frontend/src/features/procurement/materials/*.test.ts frontend/src/features/procurement/review/*test.ts：44 pass、0 fail。新增 500 个任意科目仍只有五类的反向回归。
- git diff --check：通过。
- node tools/build-procurement-browser.mjs frontend/dist .runtime/budget-ui-v19-browser：保留原布局 CSS，并追加 221 项 Chrome 109 颜色兼容回退。
- tools/test-budget-material-background.mjs：真实 Chrome 109.0.5412.0 与 Chromium 151.0.7922.34 均通过，无 pageerror。验证双列几何位置、窄屏单列、文件夹拖入、12 文件及 21 MB 文件、失败隔离、丢响应、未知状态、历史查询/筛选/返回/导出、仅当前批次展开、六块正文复制、Clipboard 与 HTTP 传统复制、再次复制前一块、仅一个已复制、严重风险确认请求及重新打开。
- 浏览器测试中的业务 API 为模拟响应，确认重新打开验证的是 UI/API 接线。既有后端确认服务测试通过，但本轮没有在真实 DM8 写入新的风险确认事实，真实新增确认持久化：NOT VERIFIED。

## 26 发布验证

执行 .runtime/update-budget-ui26-v19.sh，经 SHA-256 和精确旧发布目录检查后复制发布并原子切换，异常自动恢复旧链接。未重启服务。

发布：budget-ui-v19-20261016.FWCs8P。
回退记录：budget-ui-v19-20261016.O9urIR，含 previous.txt 和配置/JAR 校验清单。
前端归档 SHA-256：36ed8f89248fd937a630890113959da3c2375711ce0fd388bca36bb4bbd84069。

HTTP 首页与三个直接引用资源和发布文件逐字节一致，CONFIG_HASHES=UNCHANGED，BACKEND_JAR=UNCHANGED，后端/AI 服务均 active。

实际 HTTP 健康响应：

```json
{"code":200,"message":"success","data":{"service":"audit-backend","status":"UP"}}
```

甲方 ARM 环境及甲方浏览器实际字体、模型网关：NOT VERIFIED。
