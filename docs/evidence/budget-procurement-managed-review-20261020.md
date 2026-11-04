# 预算、采购一轮托管检查（2026-10-20）

本轮沿用 `fix/budget-procurement-ready-20261019`，HEAD `4efea2f` 加既有未提交候选改动。仅预算、采购及其读取、模型与展示链路。未将先前验证当成本轮证据；未修改合同或部署配置。以下三个产品修复尚在工作树，未发布至测试机或甲方，未提交或融合。

## 本轮修复

1. `BudgetFileResults.tsx`：读取结果的一次临时网络或服务错误会永久停止轮询并清空已显示内容。现在保留上一次快照，提示无法更新，按 3 秒起步、最多 30 秒的退避间隔重试 GET；不重新发起上传或模型任务。权限、认证及资源不存在等错误不自动重试；离开页面后停止；完成后停止。
2. `BudgetCheckPage.tsx`：部分成功批次原先按上传顺序打开结果，第一份失败时先打开失败说明。现在优先打开可用结果，未生成结果的材料说明仍可选择查看。全批没有可用结果时仍显示真实说明，不冒充成功。
3. 同页两个异步回调（文件结果完成、风险候选就绪）原先会覆盖批次的部分失败说明。现在只允许将明确的“正在整理”提示更新为完成；保留部分结束和不确定上传的说明，并以批次 phase 与任务状态共同判断是否全部完成。

维护了两个既有浏览器回归脚本：采用当前“查看说明”、9 列表头和“保存并查看结果”入口，断言不提交审批；采购脚本补充真实前端分片协议模拟及丢失分片 ACK 的同身份恢复。没有为适应旧断言恢复已删除的界面或审批流程。

## 本地验证

- Windows / JDK 17：`cd backend; .\mvnw.cmd clean verify`。2446 tests，0 failures，0 errors，2 skipped，BUILD SUCCESS，05:03。日志 `.runtime/managed-review-20261020-backend.log`。本轮未修改 Java 产品代码。
- Python 3.11：`.runtime/material-python311/Scripts/python.exe -m pytest services/audit-ai/tests -q`。554 passed，1 skipped，1 条既有 Starlette/httpx 弃用警告。日志 `.runtime/managed-review-20261020-python.log`。
- 前端全部 Node 测试使用 `AUDIT_VISUAL_TEST_BROWSER` 指定已有浏览器；749 passed。日志 `.runtime/managed-review-20261020-frontend-final3.log`。最初未提供该变量造成视觉测试前置条件失败，补齐运行环境后通过，未修改该测试。
- `cd frontend; npm run build` 通过。随后 `node tools/build-procurement-browser.mjs frontend/dist .runtime/managed-review-20261020-final3-dist` 生成兼容产物，保留原布局、字体与 221 项颜色回退。构建有既有大 JS chunk 警告。
- Chrome 109.0.5412.0：`node tools/test-budget-material-background.mjs <chrome109> .runtime/managed-review-20261020-final3-dist managed109-final3`。覆盖 12 份文件、21 MiB 文件分片、ACK 丢失、部分失败优先打开成功结果且不误报整批成功、后台完成不打断历史、历史分页查询及返回、金额风险历史、预算导出、采购六块复制及确认审计、刷新恢复身份。结果 `.runtime/managed-review-20261020-background-final3.log`。
- Chrome 109：`tools/test-procurement-material-background.mjs` 针对本轮兼容产物验证 12 份材料、22,020,096 字节分片、文件夹拖放、单文件失败隔离、离开对话框继续上传、刷新同身份恢复、清空时迟到响应不回填、保存时不提交审批。PASS，pageErrors 与外部资源请求均为空。日志 `.runtime/managed-review-20261020-procurement-browser-done.log`。
- `tools/test-budget-processing-outcome-view.mjs` 在 Chrome109 与 Chromium151 验证读取 503→恢复→再次503→最终结果、保留快照、完成停止轮询、403不重试、离页迟到请求不重开、全失败显示说明、历史快捷入口。pageErrors 为空。日志 `.runtime/managed-review-20261020-outcomes-final109.log`、`.runtime/managed-review-20261020-outcomes-accepted2-151.log`。

以上浏览器流程使用合成 API；不将其计为正式 DM8/模型端到端。

## 代表测试机真实结果

正式 release 仍是 `/opt/audit-agent/releases/material-timeout-20261019.8Ert0b`。本轮没有切换 release 或重启生产服务。

采购使用既有 `BudgetBackgroundRunner` 和 `review_probe.py procurement` 走正式 HTTP→模型→DM8：

- 正常招标风险 0 条，未虚构风险。
- 风险样本检出 6 条，包含品牌、地域、资质、排他和交付期限冲突；三份报价及围串标评分符合预期。
- 六章节 DOCX 报告导出、重新登录重开与持久化结果一致。
- 合成 run `ebeb0fae`；临时测试身份结束后已禁用，审计与合成结果保留。日志 `.runtime/managed-review-20261020/procurement.log`。

预算在新隔离目录的回环端口运行本工作树候选 Python，Java 使用候选 class 和真实模型、正式既有 OCR。`tools/prepare-managed-review.mjs`、`tools/run-managed-review26.sh`、`tools/verify-managed-review.mjs` 可复核准备、执行和判定。9 组、12 份材料重新执行实际读取及所需模型调用，没有重放模型或 OCR 原文；损坏文件在读取阶段拒绝：

- 财务大写含零角分：10003.05 对 10000，超出 3.05。
- 同名不同项目编号：分别保留 48000 申报和 60000 批复，不跨项目比较。
- 完整材料：照明维护 15000 对 12000，超支 3000；绿化 3000 无批复，提示疑似错支；消防 5000 无申报，提示疑似漏项。
- 同编号不同正文名称：84000 对 80000，超出 4000。
- 长文首尾：48000 对 45000，超出 3000。
- Word 表格八项 31200 对 32760 均保留。
- Excel 两张同样工作表保留两份原始 31200 申报，不凭金额相同判为副本，也不擅自加成 62400；无法证明独立支出时保留不确定性，未宣称比对通过。
- 扫描 PDF 经真实 OCR 约 98 秒取得文字，保留 31200 申报；原文无科目批复，没有使用项目总预算 200000 冒充批复。有重复身份引用未唯一定位，仍明确标记依据不完整。
- 损坏 DOCX 明确拒绝，无有效预算明细。

验证器输出业务 5/5、格式/拒绝与不确定性 4/4。它表示各项预定安全断言通过，不表示所有文件都完整无歧义。

结果 `.runtime/managed-review-20261020/actual-results.json`，断言 `business-checks.json`、`format-checks.json`。12 份结果在 Chrome109 实际渲染，pageErrors 为空，截图已人工查看（`browser109/`）。该 UI 使用真实结果的模拟传输。

隔离预算 ledger 使用内存，业务数据库写入为 0；不能据此声称候选预算已完成正式 HTTP/DM8 持久化、重启恢复验收。隔离进程已停止，端口关闭。测试结束正式预算/采购活动材料任务均为 0。

实测 curl 健康响应：

```json
{"code":200,"message":"success","data":{"service":"audit-backend","status":"UP"}}
{"status":"UP","service":"audit-ai"}
```

## 验收边界

本轮新修复未部署；候选预算正式 HTTP/DM8 全链路、甲方 ARM64 和本地模型仍为 NOT VERIFIED。扫描吞吐、低清晰度和歧义材料仍有边界；不能承诺任意扫描件无遗漏。没有制作甲方升级包、推送 Git 或修改既有业务材料。
