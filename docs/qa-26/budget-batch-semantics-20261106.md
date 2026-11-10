# 2026-11-06 预算整批审查与语义问题发现

## 处理行为

预算上传批次作为一个项目处理。保留不同文件的原文、金额口径及来源，把已有的科目/批复精确比对与整批语义审查结合，不再仅依赖固定标签或完整表格。

结果固定为五个区域：优先关注、总体预算与金额核对、预算科目与批复比对、审批与申报核对、支出用途与内容审查。有问题展开，正常内容折叠，空区域仍保留标题。提供问题复制、涉及文件、原文高亮及四张工作表的报告导出。

金额使用 BigDecimal。项目预算、申报合计、批复总额、最高限价分开；不同年度、分包、单价、历史参考不默认参与整体金额比对。100万元含边界提示额外审批，但不因达到门槛断言漏审或拆分违规。相同批次名称简称不同允许关联，不跨年度或不同科目强行配对。

模型读取有界原文，发现用途冲突、审批条件/顺序冲突等实质问题。每条意见核验来源 ID、逐字引用和定位；失败单元不抹掉其他已确认结果。复用 DM8 MATERIAL_EXTRACTION_LEDGER 保存成功单元，调用结果不确定时不盲目重发；未完整核对不能显示整批通过。GET 历史及原文只读，不启动模型。

## 主要文件

- 后端 `BudgetBatchReviewService`、`BudgetBatchAmounts`、`BudgetSourceText`、`BudgetUploadedMaterialReader`、`BudgetUploadedMaterialAnalyzer`、`BudgetMaterialBatchService`、`BudgetMaterialController`，及预算模型 HTTP Port/Adapter。
- AI `model/budget_review.py`、`model/gateway.py`、`model/material_fields.py`、`main.py`。
- 前端 `BudgetBatchReport.tsx`、`BudgetFileResults.tsx`、`budgetBatchResult.ts`、`api/types.ts`，及相应回归测试。

## 发现并修复

1. 同一小批次局部与综合重复调用导致同一问题重复：完整小批次只调用一次综合；已有精确科目差异提供给模型，避免重复输出。
2. 一次完整综合调用被误计为部分覆盖：覆盖统计纳入完整单次综合。
3. 模型返回的预算范围标记被共用接口删除：保留预算 subjectScope/amountScope，并补接口回归。
4. 真实模型虽提取120万元却省略整体金额标记：明确输出协议，要求结合相邻范围说明，否定年度分拆不能判作年度金额。最终真实模型验证通过。
5. 可选模型提取异常曾丢掉确定性金额：保留冻结原文与独立核实金额，语义覆盖保持部分状态。

## 本机验证

- JDK17，backend `./mvnw.cmd clean verify`：3591项，0失败，0错误，24跳过，BUILD SUCCESS。日志 `.runtime/budget-clean-verify.log`。
- frontend 收集全部 `src/**/*.test.ts` 后执行 `node --test`：865项通过；`npm run build:customer`通过。
- Chrome109共用原文弹窗浏览器测试：7项通过，包含12页、顶部/中部/底部、高亮及关闭重开。
- AI定向 `test_budget_batch_review.py test_case_review.py test_material_fields.py`：54项通过。
- AI扩展采购提取、单位口径、超时、隐私、分片等7个测试文件：80通过、1跳过。没有在26安装pytest，不把本机单测描述为远程单测。
- 本轮相关代码 `git diff --check`通过。

## 26真实服务验收

默认入口 release `budget-batch-20261106-r5`。

- JAR SHA256：`a295daac714330205ad53f2846262b6c88738e8d173abb23103bc1b2bd9c4585`。
- 前端 index SHA256：`6e8471bb5b1b64ba6fbdfc23674edff23b304ff109444ae999ad3a94eb1c15c0`。
- 已验证8001/8080/80实际HTTP健康、当前进程工作目录、前端发布文件哈希及配置保持不变。无DDL、清库或恢复JSON前置要求；等待实际活动任务结束后切换。

多轮共10种合成场景均有持久结果，重点核验：

- 申报/批复不同名称属于同批项目：准确显示超支2000元、未批科目及漏报科目。
- 纯正文用途由批准设备购置改为宴请：生成有原文依据的用途问题。
- 大写申报100万元、批复95万元：差5万元，并触发含边界的审批门槛提示。
- 年度10万元与三年整体30万元：不误报金额冲突。
- 明确先审批后付款却先付款：保留原文支持的审批问题。
- 损坏xlsx与有效12万元计划混传：整批收尾，保留有效金额，分析覆盖为部分。
- Word/xlsx/混合PDF/图片：均保存非空解析原文，可核实的金额保留；含重复表格的材料不能据此声称明细全部核对通过。
- 正常申报9000元、批复10000元：明细通过，无虚构警告。
- 非固定表述“经费盘子按人民币壹佰贰拾万元控制”并说明整体支出：120万元识别为整体金额，触发门槛。
- 当前整体120万元与旧项目300万元同时出现：仅120万元进入当前总预算。

最终r5三个批次分别为 `ce4426da-7467-4b45-bda2-f6930465a28d`、`39baf3f1-6c32-4407-a07c-812b0669d484`、`8229efab-0011-465b-a763-7de1d92667b0`，均REVIEWED，所有保存引用COMPLETED/COMPLETE。已验证重复GET结果稳定、可定位引用与冻结原文逐字相等。

Chrome109真实页面：五模块、3条科目问题、复制、原文高亮、四表导出、1440/900/390宽度无横向越界、两次跨账号原文读取404；预算账号页面选择文件到自动结果页，无人工必填项、无脚本错误。

最终r5又从历史列表查询“经费请示.txt”并打开最新结果，页面正确显示120万元门槛问题及来源文件。`browser-verification.json` 的 `nonFixedProseThresholdShown=true`、`status=PASS`。发布、业务、浏览器及上传证明和三张截图已复制到 `docs/qa-26/evidence/budget-batch-20261106/` 持久保存。

可执行场景脚本和结果位于 `.runtime/budget-batch-20261106/`：`acceptance-r5.py`、`check-results.py`、`browser.mjs`、`business-verification.json`、`browser-verification.json`、`upload-verification.json`、`deployment-r5.json`。其中只有本机合成测试材料，不含甲方正文；维护密码仅交互输入。

## 边界

甲方ARM64与真实客户材料本轮NOT VERIFIED，不能以合成验收保证所有扫描件或模型判断均正确。单次模型180秒、语义整批900秒/最多96单元，受限或失败时保留已确认内容，不声称全文通过。没有重做OCR断点恢复或完整模型策略冻结机制。本轮未提交Git、未制作甲方包。
