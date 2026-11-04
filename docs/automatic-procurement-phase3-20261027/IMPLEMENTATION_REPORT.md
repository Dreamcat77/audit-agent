# 智能采购自动审查第三阶段实施报告

日期：2026-10-27。阶段范围：AuditCase 自动接入智能体预审、F10 招标文件合规；保留询比价。黑名单、围串标、拆分采购未迁移、未执行。

## 1. 交付结论与验证级别

**本阶段代码实现、隔离真实 DM8 集成、真实模型端到端、真实 Chromium 交互验收均已执行并通过本报告列出的目标场景。没有部署生产，没有开启原环境自动审查入口。全仓回归仍存在原有14项失败，不能称全仓测试通过。**

最终12文件案件 `dd79a596-5f85-3c67-b304-57b54962f07a` 经浏览器上传，自动完成解析、结构化提取、确定性检查、预审、F10、询比价和 DM8 持久化，三模块状态均为 `COMPLETED`。重启后查询内容完全一致。

另以两份明确标注的合成矛盾 DOCX 验证真实跨文件风险：案件 `7fb732bc-55ad-3127-91c3-b940882a7d30` 自动识别付款比例冲突，风险和两份逐字原文已在浏览器展示。该案件资料不足，整体为 `DATA_INSUFFICIENT`、两个语义模块为 `PARTIAL`，没有生成虚假的询比价结果或全通过结论。

验证级别分别为：

- 代码实现：完成；本轮19个新增、9个修改源码/测试/验收工具文件，共28个。
- Mock测试：完成，见第10节；不替代真实模型验收。
- 隔离DM8：真实 JDBC、事务、CLOB、Worker及恢复场景已执行；不代表原业务库迁移。
- 真实模型：`audit-qwen-27b-16k:latest`；完整12文件案件和两文件矛盾案件均实测。
- 前端：真实 Chromium DOM、HTTP、上传、刷新、证据、失败状态均实测；不以 build 代替。
- 正式环境部署：**未执行**；完整跨 Schema 数据库备份与完整恢复：**NOT VERIFIED**。

`COMPLETED`仅表示本轮选定目标执行结束，不证明采购全部合法，也不证明未执行模块没有风险。样本两个新增模块各0条风险，询比价保留既有“报价集中”统计线索，不能将其解释为围串标认定。

## 2. 实际工作树、源码保护与恢复依据

本次依据一期实施报告、二期验收报告、实际 AGENTS.md 和当前源码实施。

- 实际工作树：`E:\audit-agent-new\.runtime\budget-home-e17-20261014`。
- Git common dir：`E:\audit-agent-new\.runtime\integration-repo\.git`。
- 分支：`fix/budget-procurement-ready-20261019`；HEAD：`35f95499c4d5efa475dffc3a3628222750124672`。
- 外层 `E:\audit-agent-new` 非 Git 根；AGENTS 中历史 D 盘路径不是本轮实际实现位置。
- 三期前备份：`E:\audit-agent-new\.backups\automatic-case-phase3-20261027-040540`。
- 已保存 `source/`、`source-manifest.json`、`initial-git-status.txt`、`initial-tracked.diff`，覆盖二期新增/修改源码与当前共享工作树内容。开始前二期交付的22个源码文件哈希全部匹配。
- 备份4728个文件、1,323,070,453字节；本轮未修改源码以外的4719个原备份文件最终哈希保持一致，0意外修改、0删除。
- 未执行 reset、强清理、强推、提交或覆盖历史未提交改动。本轮差异必须以 `phase3-vs-phase2-source.diff` 为准，不能把共享工作树相对 HEAD 的全部差异算成本轮工作。

源码恢复依据可用：按备份 manifest 在新目录恢复/核验，再选择性恢复本轮9个修改文件及处理19个新增文件。**本阶段没有对当前工作树执行恢复覆盖演练**；源码备份可用不等于完整数据库灾难恢复通过。备份包含既有未提交工作，不能通过简单 checkout HEAD 恢复二期状态。

证据：`evidence/source-protection-summary.json`、`phase3-source-manifest.json`、`phase3-vs-phase2-source.diff`、`final-git-status.txt`。前后SHA-256逐文件保存在manifest。

## 3. 数据库与环境边界

本轮 **0 DDL变更、0新业务表、0原业务库写入**。`PROCUREMENT__automatic_case_v1.sql`及其他DM8脚本与三期前哈希一致。

复用二期独立 `AUTOCASE2` 实例、`AUTOCASE_APP` schema及四张表：`AUDIT_CASE`、`AUDIT_CASE_FILE`、`AUDIT_FACT`、`AUDIT_QUOTE_LINE`。模块scope、状态、输入摘要、检查、发现和引用保存在既有案件报告 CLOB。`AUDIT_FACT`仍为结构化事实唯一权威来源，语义报告中的证据摘录是带文件SHA及locator的派生快照，不新增第二套可独立写入的事实表。

二期数据库结论维持原样：94表数据导出及独立数据恢复曾校验通过；跨Schema依赖主表导出因权限不足而失败，完整外键恢复未通过。该部分导出不得称为完整备份。本轮未修改权限、安全配置或向原业务库试验迁移。

隔离验收使用local受控账号/会话 seam，真实DM8 Repository、MyBatis、事务与Worker；操作审计写独立测试辅助表。正式身份DM8适配器、正式审计表、Redis Session和dev29/customer整套部署均不在本轮通过范围。F10规则使用现有内置catalog，不代表原业务库自定义规则验收。

数据库实例隔离不等于已验证网络访问控制：沿用二期独立实例及本地TCP代理，未修改sshd、防火墙或源库配置。验收结束已停止独立实例及本轮Java/AI/前端/代理进程，保留独立数据目录。原 `audit-ai.service`、`audit-backend.service`、`DmServiceAUDITDB.service` 仍运行且未被发送停止信号，见 `environment-finalization.json`。

Controller和Worker仍仅在 `audit.procurement.auto-case.enabled=true` 时创建，未配置默认关闭；只在隔离harness显式开启。无生产部署或生产开关调整。

## 4. 共享文件分类历史失败调查

先复现并检查 `AutomaticProjectFileClassificationServiceTest` 和 `ProcurementMaterialClassifierTest`，共4项历史失败：

- 前者1项是已有候选物化调用与Mock“无剩余交互”断言不一致。
- 后者3项涉及旧 `BIDDER/BIDDER_UNCONFIRMED/QUOTATION/TENDER/OTHER` 语义期待与当前分类行为不一致。

AuditCase自动链路不调用这两个旧分类器。它复用统一文件接入/Office解析，以原文标题及可信documentType进行目标选材；缺结构化事实不阻止读取原文。真实12文件验收中02/05/06/10及需求文件全部进入正确F10 scope。因此没有为通过旧测试改变预期或扩大修改旧项目工作流。

上述判断不证明旧流程无缺陷；4项仍保留，需另任务按旧流程需求处理。共享文件解析器本轮没有改动，F10旧规则匹配仅提取为 `TenderRuleMatcher` 并保持原trim/regex行为。

## 5. 输入输出契约与跨文件处理

统一的是案件文件、事实、证据和执行结果，不是一个包办所有模块的大Agent。

`CaseReviewAiPort.Request`包含 `caseId/module/inputDigest/version/documents/evidence/facts/checks/ruleSignals`。只传入已验证事实、目标选定原文和服务器建立的证据目录。不同模块自行选择需要的上下文。

模型返回 `complete/findings/observations`，引用只能为已提供的 `evidenceIds`。Java验证后重建 `SourceEvidence(id,documentId,fileName,sha256,locator,excerpt)`，持久化 `ModuleReview`：状态、版本、模型、开始/结束、scope、coverageGaps、checks、findings、rejectedCitations和errorCode。

证据片段上限1600字符、每文档12000字符、总原文54000字符。原文在模型输入中只出现一次，checks仅引用ID，避免重复发送。PRECHECK最终输入压缩前72084字符、模型实际上下文32995字符；F10分别22172/13610字符。截断和材料不足显式记录coverageGap；超限失败关闭，不伪称已覆盖。

证据ID存在、属于案件、逐字片段匹配，是必要的技术验证；**不能单靠字面引用匹配证明模型推理、法律结论或现实事实绝对正确**。本轮另外检查了技术、金额、评分、付款和计划/实际的业务语义。

### 智能体预审

真实输入：12份材料原文共已解析的相关片段、已验证核心事实、预算/数量/供应商报价及成交依据、确定性核对结果。

确定性检查包括项目名称/编号/采购单位、预算角色、同名采购数量、报价预算、中标供应商和报价金额对应。空缺编号不计冲突；金额使用既有BigDecimal/数值比较，预算、报价、成交和支付角色分离。日期、计划与实际完成、重要依据由带原文引用的模型观察补充。

最终输出5类语义观察：身份一致、预算与中标区别、40台终端/4台打印设备/1项安装调试、日期顺序、材料范围；0条新增风险、0拒绝引用。原模型“材料未见缺失”的原始措辞保留在证据，但仅能理解为本轮选材覆盖，不能据此认定正式评审、合同、实际验收及付款已经完成。

### F10招标文件合规

自动选取01需求、02技术、05招标、06评审、10澄清，共5份；用户无需再次上传或点击扫描。复用既有F10规则仓储/匹配能力，使用独立案件语义适配器，不改原正式项目模块入口。

真实输出4类观察、0条新增风险：

- 技术需求可见内存、存储、打印与验收方法，允许等效，不因出现品牌否定条款而误报限制性条件。
- 招标文件与评审办法均为价格30、技术45、服务20、方案5，合计100，评分方法口径一致。
- 澄清付款比例按合同含税总价计算，预算上限约束报价，不将预算当付款基数。
- 仅说明所选分析范围尚不能证明正式评审记录、合同或后续程序，不能断言现实中不存在，更不能据此判违法。

## 6. 第02份技术需求表处理

`02_办公终端采购技术需求表.pdf`解析成功，513字符完整纳入F10，`sourceChars=suppliedChars=513`；最终结构化事实仍为0。

原因不能一概归于Schema：当前核心fact schema偏向项目/金额/报价，技术参数并非固定业务字段；该文件仍含项目抬头，因此核心事实抽取也存在漏提取。**本轮没有声称0事实缺陷已经完全消除。**

采取“结构化核心事实+原文语义分析+可验证证据”：无需增加memorySize/storageSize字段，模型已将内存不低于16GB、固态硬盘不低于512GB与05招标要求比较，结果和逐字来源在真实浏览器展开可见。02文件不再因0事实而失去分析上下文。截图：`browser-f10-semantic-analysis.png`。

## 7. 最终12文件提取与询比价

真实浏览器上传案件：`dd79a596-5f85-3c67-b304-57b54962f07a`；12份均 `EXTRACTED`，共107事实，三供应商9条明细。逐文件事实数：

- `01_办公终端及打印设备采购需求申请书.docx`：16条。
- `02_办公终端采购技术需求表.pdf`：0条。
- `03_办公设备采购实施计划.docx`：4条。
- `04_办公设备更新项目预算批复.pdf`：5条。
- `05_办公终端及打印设备采购招标文件.docx`：16条。
- `06_办公设备采购评审办法.pdf`：2条。
- `07_澄川星河办公设备有限公司报价单.pdf`：17条。
- `08_澄川明川数字设备有限公司报价单.pdf`：17条。
- `09_澄川启衡信息设备有限公司报价单.pdf`：17条。
- `10_办公设备采购文件澄清答复.docx`：4条。
- `11_办公设备采购中标通知书.docx`：6条。
- `12_办公设备采购履约验收方案.docx`：3条。

核心结果：

- 项目编号：`FZ-CG-2026-001`。
- 预算上限222600元，中标金额212000元；没有将验收方案当已完成验收或已付款。
- 办公终端40台、打印设备4台；安装调试及数据迁移1项。
- 澄川星河办公设备有限公司212000元：168000+27200+16800。
- 澄川明川数字设备有限公司216400元：172000+27600+16800。
- 澄川启衡信息设备有限公司219800元：174000+28000+17800。
- 每家3行均 `lineValidation=VERIFIED`，均未超过预算；均值216066.67元，变异系数0.0148。保留原评分/统计规则，不把最低报价等同综合评分中标决策。
- `QUOTE/AGENT_PRECHECK/TENDER_COMPLIANCE=COMPLETED`；其余三个模块 `NOT_EXECUTED`。

最终结果、原文、完整scope和语义核验分别见 `browser-dm8-actual-result.json`、`browser-dm8-actual-evidence.json`、`browser-dm8-actual-semantic-validation.json`。66处输出引用已逐项校验文件SHA、locator、逐字excerpt与上传源文件一致，全部通过，两个模块拒绝引用数均0。本样本source scope无截断gap；没有额外验证供应商内部token级截断行为。

## 8. 跨文件分析实际案例

正常12文件中，系统将“需求数量→技术规格→招标技术/服务要求→评审评分→澄清付款基数”自动关联，正确区分预算与成交、计划与实际、允许等效与排他条件，没有为展示效果编造风险。

另执行真实模型对抗验证：复制模板生成两份合成DOCX，不改用户原材料。招标约定30%/65%/5%；澄清改成50%/40%/10%，又声明原条款有效、无优先顺序、两套并行。

完整链路案件 `7fb732bc-55ad-3127-91c3-b940882a7d30`：两个模块各生成一条 `PAYMENT_CONFLICT` 警示。F10标题“招标文件与澄清答复付款比例冲突”，建议核实效力并明确唯一付款比例；没有认定违法。

证据均为 `docx/body/node:2/part:2`：

- 招标：“货物到货初验后支付合同价30%；终验后65%；质保期满5%。本付款条款继续有效。”
- 澄清：“同一采购事项：到货初验后支付合同价50%；终验后40%；质保期满10%。原招标文件的付款条款继续有效，未规定本答复优先适用，两套比例并行执行。”

引用分别与HTTP证据接口的文件SHA/原文段校验通过，真实浏览器显示风险、建议及两份来源。因只上传两文件，缺预算/报价/技术/评审等，整体 `DATA_INSUFFICIENT`、语义模块 `PARTIAL`。见 `actual-http-conflict-result.json`、`browser-cross-file-finding.json/png`。

`actual-model-conflict-fixture.json`是另一项直接AI API小型对抗测试，不与上述完整HTTP+DM8案件或12文件样本混同。

## 9. 自动执行、异常与恢复

上传后原Worker自动执行文件解析/提取及三个目标模块。模块调用前独立事务提交 `PENDING→RUNNING`，调用结果后再持久化；单模块异常保留已有确定性结果，继续其他模块。整案状态按实际覆盖汇总，不以询比价完成冒充全部完成。

真实DM8+真实Java强杀恢复案件 `4562c28f-005a-36b4-9ade-34c140a44a83`：在PRECHECK的RUNNING已持久化后终止进程，重启并使用受控测试时钟使租约过期。未知模型结果保留 `UNKNOWN/EXECUTOR_LOST_MODEL_OUTCOME_UNKNOWN`，不盲重放；F10与询比价继续完成，整案 `PARTIAL`，浏览器可见。**这项故障注入的模型为Mock gate，数据库和进程是真实的**，不是模型服务真实崩溃测试。

真实模型12文件完成案件另经Java重启重开，模块与结果完全相同，见 `browser-dm8-actual-restart-reopen.json`。UNKNOWN不等于失败、缺失或完成；本轮未提供对不确定外部结果的无条件自动重试。

对抗单测包括：某文件提取失败仍可读取已解析原文、单模块失败、模型超时、缺字段/缺材料、两份身份矛盾、错误/外部引用、单文件冒充跨文件冲突、上下文截断、旧报告JSON兼容、调用前持久化、恢复不重放。它们属于Mock/单测范围，不能全部描述为真实模型故障验收。

## 10. 测试命令、环境和观察结果

所有相对命令以实际工作树为根；Maven在backend执行，JDK17。数据库/模型/认证信息仅运行时注入，不写入报告或Git。

1. 全仓：`.\mvnw.cmd clean verify "-Daudit.build.directory=target-phase3-final"`。实际3208项，3172通过、9失败、5错误、22跳过；退出为失败。与二期失败集合完全相同，0新增失败。详见 `full-java-verify.log`、`final-verification-summary.json`。
2. 新模块：`CaseReviewEngineTest`8项、`CaseReviewWorkflowTest`3项通过；自动案件相关单元/HTTP回归无新增失败。默认全仓执行跳过带开关的真实DM8测试，不能据全仓日志宣称已运行真实DM8。
3. 真实DM8单独执行：`.\mvnw.cmd "-Daudit.build.directory=target-phase3" "-Dtest=Dm8AutoCaseRealIntegrationTest,CaseReviewDm8RealIntegrationTest" test`，启用独立数据库确认及运行时连接信息。6项通过、0跳过；涵盖既有五项及新模块CLOB/scope/引用/UNKNOWN往返与事务回滚。见 `real-dm8-junit.log`。
4. Python：本地Python3.11虚拟环境无pytest，采用runpy直接执行 `test_case_review.py`5个及 `test_automatic_procurement_fields.py`2个无fixture测试函数，共7项通过。不是全Python suite。见 `python-targeted-tests.log`。
5. 前端：frontend下 `npm run build`通过；仍有原有chunk体积/动态导入warning，非浏览器验收替代品。见 `frontend-build.log`。
6. 打包：`.\mvnw.cmd -q "-Daudit.build.directory=target-phase3-final" -DskipTests package`退出0，JAR 81623263字节；这是跳过测试的打包，不能冒充测试通过。SHA见 `package-build-outcome.json`。补录时一次PowerShell未引用-D参数导致参数拆分，失败日志保留；改用参数数组后成功，业务代码未修改。
7. HTTP/curl：`tools/automatic-case-phase3/acceptance_runner.py`的scenario使用真实curl上传12文件，保存 `dm8-mock-upload/result`与首轮真实 `dm8-actual-upload/result`；随后浏览器完整重跑最终案件通过。响应与最终JSON见证据索引。
8. 浏览器：`node tools/automatic-case-phase3/browser-acceptance.mjs`，配置经stdin注入；`tools/automatic-case-phase3/scenarios.py`执行真实DM8重启、语义/引用核验与真实矛盾上传。不可向原业务库直接运行这些验收脚本。

Python可复执行方式（设置PYTHONPATH为services/audit-ai/src后，在已安装FastAPI/httpx的Python3.11环境运行）：

```python
import runpy
for path in ['services/audit-ai/tests/test_case_review.py',
             'services/audit-ai/tests/test_automatic_procurement_fields.py']:
    namespace = runpy.run_path(path)
    for name, fn in namespace.items():
        if name.startswith('test_') and callable(fn):
            fn()
```

Mock真实DM8完整案件 `cfef2335-8830-36dc-8f1c-ff75b5f56c83`三模块完成，模型明确为 `MOCK_CASE_REVIEW`。最终真实案件模型明确为 `audit-qwen-27b-16k:latest`，两者文件和结果分别保留。

### 真实模型首轮失败与修复

首轮 `89c032c1-9421-3fa3-8ca2-c3de83e1ff00`完成12文件、107事实与询比价，但两个新模块被通用60秒读取超时截断，案件准确显示PARTIAL/UNKNOWN。失败结果 `dm8-actual-result.json` 及旧配置浏览器结果保留，未改写成通过。

修复为案件独立读取超时默认150秒（上限150），Java模块超时默认180秒，输出最多4096 tokens；其他模块通用60秒配置不变。移除checks重复原文，case context超过80000字符失败关闭。最终源码再跑完整真实浏览器12文件以及两文件矛盾链路通过，不只验证只读探针。

## 11. 前端零操作验收

真实Playwright Chromium执行并保存截图与JSON：

- 一次选12份PDF/DOCX，未填写业务字段，自动建立案件并分析。
- 展示真实已解析/提取进度及独立模块状态，处理中刷新仍恢复原案件。
- 完成自动展示三家报价、预审/F10观察和原文证据，可下载原文件。
- 刷新完成页、重启后查询均可恢复；02技术原文16GB/512GB已展开检查。
- 无效签名HTTP400；不确定上传及网络失败保留原操作key，旧成功不会冒充新上传成功。
- PARTIAL/UNKNOWN保持明显状态；未执行三个模块明确标记，分析完成不等于全部合规。
- 正常/矛盾/部分完成各浏览器验收 `pageErrors=[]`。

前端以关键事实与有证据风险为主，详细正向观察/scope折叠；无需逐个点击启动模块。见 `browser-acceptance.json`、`browser-semantic-review.json`、`browser-partial-module.json`及对应PNG。

## 12. 历史失败变化

二期14项→本轮14项，失败测试标识集合完全一致，0新增；没有删除测试或调整预期来制造通过。A新增自动架构0、B分类4、C原业务5、D测试代码/fixture等问题5。D为本轮初步归类，不代表原业务已无缺陷。

- B / failure：`com.fuquan.audit.contract.application.project.AutomaticProjectFileClassificationServiceTest#terminalFactAttemptsDoNotResendAndStillAdvanceToAvailableMaterialFacts`。
- C / failure：`com.fuquan.audit.procurement.api.ProcurementMaterialBatchApiIntegrationTest#incrementalUploadsWaitForExplicitUnifiedAnalysisAndAutomaticallyPersistOneReport`。
- C / failure：`com.fuquan.audit.procurement.application.ProcurementMaterialBatchServiceTest#stagedSpreadsheetIsSavedAsPendingAnalysisAndCannotRunComparisonOnUpload`。
- D / error：`com.fuquan.audit.procurement.application.ProcurementMaterialBatchServiceTest#explicitAnalysisRetainsPreviouslyConfirmedBidderWithoutRequestingConfirmationAgain`。
- C / failure：`com.fuquan.audit.procurement.application.ProcurementMaterialBatchServiceTest#oneUnreadableFileDoesNotDiscardAnotherFileResult`。
- C / failure：`com.fuquan.audit.procurement.application.ProcurementMaterialBatchServiceTest#stagedUploadsAreRediscoverableWithoutRunningRiskAnalysisOrGeneratingReport`。
- D / error：`com.fuquan.audit.procurement.application.ProcurementMaterialBatchServiceTest#oneExplicitAnalysisProducesReportAndRetryReopensSameReport`。
- D / error：`com.fuquan.audit.procurement.application.ProcurementMaterialBatchServiceTest#explicitAnalysisRetriesSavedAiFailuresButReusesOnlyAvailableResults`。
- C / failure：`com.fuquan.audit.procurement.application.ProcurementMaterialBatchServiceTest#unreadableMaterialStillReturnsAReportWithoutInventingFacts`。
- D / error：`com.fuquan.audit.procurement.application.ProcurementMaterialBatchServiceTest#unreadableSavedSpreadsheetDoesNotStopOtherFilesAndStillGeneratesPartialReport`。
- B / failure：`com.fuquan.audit.procurement.application.ProcurementMaterialClassifierTest#quotationTemplatesAndConflictingSuppliersStayUnconfirmed`。
- B / failure：`com.fuquan.audit.procurement.application.ProcurementMaterialClassifierTest#scannedQuotationUsesExplicitSupplierAndNeverTheProjectOrBudget`。
- B / failure：`com.fuquan.audit.procurement.application.ProcurementMaterialClassifierTest#tenderContainingQuotationTemplatesDoesNotBecomeSupplierEvidence`。
- D / error：`com.fuquan.audit.procurement.application.ProcurementReportSnapshotTest#oldReportForDifferentMaterialScopeIsNotReturnedAsCurrentBatchResult`。

全仓仍非绿色，因此不能据本报告作无条件合并/生产发布结论。

## 13. 本轮修改清单

以下28个实现/测试/工具文件相对三期前备份计数；证据和报告文档另计。新增19、修改9。逐文件before/after SHA-256见 `phase3-source-manifest.json`，完整本轮diff见 `phase3-vs-phase2-source.diff`。

- 新增：`backend/src/main/java/com/fuquan/audit/integration/ai/fake/UnavailableCaseReviewAdapter.java`。
- 新增：`backend/src/main/java/com/fuquan/audit/integration/ai/fastapi/FastApiCaseReviewAdapter.java`。
- 修改：`backend/src/main/java/com/fuquan/audit/procurement/application/ProcurementTenderRuleScanService.java`。
- 新增：`backend/src/main/java/com/fuquan/audit/procurement/application/TenderRuleMatcher.java`。
- 修改：`backend/src/main/java/com/fuquan/audit/procurement/autocase/AuditCase.java`。
- 修改：`backend/src/main/java/com/fuquan/audit/procurement/autocase/AuditCaseService.java`。
- 新增：`backend/src/main/java/com/fuquan/audit/procurement/autocase/CaseReviewAiPort.java`。
- 新增：`backend/src/main/java/com/fuquan/audit/procurement/autocase/CaseReviewContext.java`。
- 新增：`backend/src/main/java/com/fuquan/audit/procurement/autocase/CaseReviewEngine.java`。
- 修改：`backend/src/test/java/com/fuquan/audit/procurement/autocase/AuditCaseIntegrationTest.java`。
- 修改：`backend/src/test/java/com/fuquan/audit/procurement/autocase/AutomaticCaseHttpTest.java`。
- 新增：`backend/src/test/java/com/fuquan/audit/procurement/autocase/AutomaticCasePhase3Harness.java`。
- 新增：`backend/src/test/java/com/fuquan/audit/procurement/autocase/CaseReviewDm8RealIntegrationTest.java`。
- 新增：`backend/src/test/java/com/fuquan/audit/procurement/autocase/CaseReviewEngineTest.java`。
- 新增：`backend/src/test/java/com/fuquan/audit/procurement/autocase/CaseReviewFixtures.java`。
- 新增：`backend/src/test/java/com/fuquan/audit/procurement/autocase/CaseReviewWorkflowTest.java`。
- 修改：`frontend/src/features/procurement/AutomaticCasePanel.tsx`。
- 修改：`services/audit-ai/src/audit_ai/config.py`。
- 修改：`services/audit-ai/src/audit_ai/main.py`。
- 新增：`services/audit-ai/src/audit_ai/model/case_review.py`。
- 修改：`services/audit-ai/src/audit_ai/model/gateway.py`。
- 新增：`services/audit-ai/tests/test_case_review.py`。
- 新增：`tools/automatic-case-phase3/CaseContextProbe.java`。
- 新增：`tools/automatic-case-phase3/acceptance_runner.py`。
- 新增：`tools/automatic-case-phase3/browser-acceptance.mjs`。
- 新增：`tools/automatic-case-phase3/finalize_evidence.py`。
- 新增：`tools/automatic-case-phase3/remote_reopen.py`。
- 新增：`tools/automatic-case-phase3/scenarios.py`。

报告、证据索引与SHA清单不计入上述28个源码文件。SQL零修改，未重写原六模块，也未迁移剩余模块。

## 14. 已知限制与风险

- 原业务库完整跨Schema备份/完整恢复仍NOT VERIFIED，继续禁止未经安全确认的原库迁移；本轮隔离通过不解除此限制。
- 14项历史回归失败未消除；02核心事实仍0、部分身份字段可能漏提取，不能宣称提取覆盖完美。
- 证据校验验证引用归属和原文一致性，不能代替专业采购判断；样本0风险不等于全部合规。
- 模型关于“未见缺失”的宽泛措辞需结合scope理解；本轮材料不能证明现实已完成评审、签约、验收或付款，未给出违法定性。
- 本轮12文件为原生PDF/DOCX，未新增扫描OCR实测；正式认证、正式审计表、Redis Session、原库自定义规则及完整部署不在本轮真实验收范围。
- 有界上下文、字符上限和timeout保护已实现；供应商内部token处理未额外验证。大批/长文可能PARTIAL或失败关闭，不提供无限上下文保证。
- 不确定外部模型结果不会盲重放，保留UNKNOWN；尚无对所有未知结果的人工复核/受控重试产品流程。
- Python仅7项针对性回归；前端现有chunk体积warning和Starlette测试客户端弃用warning保留，未为本轮大范围升级依赖。
- 仅单独测试启动器组合真实DM8/模型与local身份seam；不等于正式profile已部署或国产应用服务器已验证。

## 15. 交付位置与停止范围

主报告位于本目录 `IMPLEMENTATION_REPORT.md`；外层可发现副本为 `E:\audit-agent-new\AUTOMATIC_PROCUREMENT_PHASE3_IMPLEMENTATION_REPORT.md`。证据索引 `EVIDENCE_INDEX.md`，全证据SHA清单 `DELIVERY_SHA256.json`。

本阶段完成预审与F10接入并保留询比价后停止。未继续黑名单、围串标、拆分采购，未部署生产。当前结论只适用于已记录的隔离场景；原业务库迁移、生产发布和全仓绿色仍有明确门槛。
