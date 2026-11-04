# 上传预算材料的项目拆分门槛分析

## 需求和实现

预算首页的风险分析需要对所选小额申报核算累计金额，说明触及的门槛、涉及项目及内容。此前 `BudgetMaterialRiskService` 只输出逐项批复比较意见，未执行累计门槛分析。既有 `BudgetSplitRiskEvaluator` 则使用采购申报聚合，不能将上传材料 task ID 冒充采购 ID 直接调用。

本次增加 `BudgetMaterialSplitAnalysis`，使用上传材料的项目、科目、年度、部门、申报金额及来源快照，通过既有 `BudgetSplitRiskRuleRepository` 读取启用规则。用户已同意先沿用现有 5 万、20 万、200 万三档验证，正式适用门槛及对应审批、报告制度待确认；未新增或修改规则数据。

- 单项严格低于对应 individual limit、条数达到最低要求且累计金额大于或等于 aggregate threshold 时，输出门槛提示及涉及记录。
- 金额使用 BigDecimal；相同项目、科目、年度、部门和金额的重复记录只计一次，不同版本金额冲突的记录排除。编码/名称可唯一对应的副本也不会重复累计；同名多编码且无法确认身份的记录排除。
- 不同年度、部门及范围未知的记录分别核算，不用未知范围桥接已知范围。
- 材料未确认完整申报日期，不能用上传时间证明规则的时间窗口；提示只表示可能需合并核算，不认定已经违规或给出虚构的正式审批要求。未触发门槛也不宣称已排除所有拆分风险。
- 风险及规则版本、门槛、涉及材料存入既有 DM8 JSON 快照，复用原有幂等、权限和审计。旧快照不重新解释、不覆写，页面提示其尚未包含累计分析。
- `BudgetProjectRiskPanel.tsx` 展示累计门槛、一般风险、涉及申报内容和来源材料，移除该区域的重复逐项批复比较。原单文件批复核对页面继续提供单项结果。

主要代码：`backend/src/main/java/com/fuquan/audit/budget/application/BudgetMaterialSplitAnalysis.java`、`BudgetMaterialRisk.java`、`BudgetMaterialRiskService.java`；前端 `frontend/src/features/budget/f05/BudgetProjectRiskPanel.tsx`、`budgetRiskCategories.ts`。

## 验证

环境：Windows、JDK 17.0.18，工作分支 `fix/budget-home-split-risk-20261014`。

- backend `./mvnw.cmd clean verify`：2276 tests、0 failures、0 errors、2 skipped，BUILD SUCCESS。其后补充三档规则组合的测试，`./mvnw.cmd -Dtest=BudgetMaterialSplitAnalysisTest test`：11 tests 全通过。
- frontend `npm run build` 成功；预算、采购相关 Node 回归 49 tests 全通过。
- `git diff --check` 通过。
- `node tools/build-procurement-browser.mjs frontend/dist .runtime/budget-split-v21-browser` 成功，保留 Chrome 109 的原有 CSS 及 221 处颜色兼容回退。
- `node tools/test-budget-material-background.mjs <browser-executable> .runtime/budget-split-v21-browser <label>`：Chrome 109.0.5412.0、Chromium 151.0.7922.34 均通过，errors=[]。覆盖具体累计金额/门槛/两条涉及记录、历史重开、旧结果提示、未知响应复用同一操作 ID，以及原预算/采购回归。此浏览器测试使用模拟 API。
- Chrome 109 结果区域截图：`.runtime/budget-background-browser/chrome109-v21-visual-risk-history.png`，已查看，结果及两条涉及记录完整显示。

## 测试服务器真实 HTTP / DM8

部署脚本 `.runtime/update-budget-split26-v21.sh` 校验包哈希和前一版本，保留旧 release 及回滚记录，仅重启后端；无数据库迁移、无业务数据修复、无环境或模型配置修改。

- `BUDGET_SPLIT_TEST26_DEPLOY=PASS`。
- 当前 release：`/opt/audit-agent/releases/budget-split-v21-20261016.EZBIep`。
- 回滚记录：`/opt/audit-agent/update-backups/budget-split-v21-20261016.BxaJCN`。
- 入口及 3 个直接引用前端资源与磁盘文件逐字节一致。
- HTTP `GET /api/v1/health`：200，`{"code":200,"message":"success","data":{"status":"UP","service":"audit-backend"}}`。
- 部署配置哈希保持不变。AI 地址、密钥、名称、Nginx、环境文件未改动。

`tools/test-budget-material-split26.py` 最终一轮使用 5 份全合成 XLSX、10 条材料明细，通过真实上传和解析接口，验证结果如下：

```
uploadedBudgetSplitRealHttp=PASS
configuredThresholdsVerified=3
duplicateNotAdded=true
versionConflictExcluded=true
differentYearsSeparated=true
concurrentIdenticalSnapshots=6
immutableRuleEvidenceReopen=PASS
restartReopen=PASS
rangeDriftHttp=409
```

三组分别为 3万+2万、8万+12万、80万+120万。并发同一操作只有一份历史结果；后端重启并重新登录后，DM8 保存的规则、金额、涉及记录与原结果相同，原操作重放也未变化。临时合成账号已禁用，安全审计及合成测试记录保留。首轮验证脚本曾因函数名覆盖 Python 模块而中断，修正测试脚本后完成上述整轮验证，产品代码未因此变更。

后端 JAR SHA-256：`b03ba786ece6305bcd2f379bb46340461b22914c84dfb8e48fc17bd003aacaa0`。

前端部署归档 SHA-256：`5fb19d0f47d6a4459178780480b2cfb46f1ca1de97967d4cf61abfccd61dd59e`。

甲方正式门槛、ARM 部署、现场字体以及模型语义提取 E2E：NOT VERIFIED。本次验证材料为明确标记字段的 XLSX，不能据此宣称各种非结构化材料都已准确识别项目关联或申报日期。本次未提交或推送 Git，未生成甲方更新包。
