# 预算所选金额合计与分页验证（2026-10-16）

## 本次行为

新分析按用户明确勾选的材料记录累计申报金额，不再按项目身份、科目、版本、年度、部门或旧拆分规则排除记录。不同材料中的相同金额记录若被分别勾选，也分别计入；同一个材料明细不能在请求中重复提交。Java 使用 BigDecimal。

金额区间为小于 10000、10000 至 100000（两端包含）、大于 100000 元。任何所选金额未识别或为负数，只展示已识别小计及缺失提示，不伪造完整总额和区间。新快照版本为 SELECTED_SUM_V2；原有历史快照不重算、不更改旧记录含义。

移除“读取更早材料”。项目按名称/科目/材料搜索、每页最多 12 项，跨页保留勾选；历史按分析日期和名称筛选，每页 6 条。结果明细超过 12 项时分页。沿用每次最多 100 项的既有限制，界面明确提示。

主要修改：

- `backend/src/main/java/com/fuquan/audit/budget/application/BudgetMaterialSplitAnalysis.java`：限定新入口的简单合计和区间判断。
- `BudgetMaterialRiskService.java`、`BudgetMaterialRisk.java`、`BudgetMaterialRiskController.java`、`BudgetMaterialRiskRepository.java` 及 DM8/内存适配器：归属过滤后的游标分页、历史摘要分页/查询。
- `frontend/src/features/budget/f05/BudgetProjectRiskPanel.tsx`、`budgetRiskCategories.ts`：查询分页、跨页勾选、新旧结果展示。
- `BudgetMaterialSplitAnalysisTest.java`、`BudgetMaterialBackgroundIntegrationTest.java`、`tools/test-budget-material-background.mjs`、`tools/test-budget-material-split26.py`：金额、分页、隔离与持久化回归。

## 本地验证

环境：Windows，JDK 17.0.18，分支 `fix/budget-home-split-risk-20261014`。

- backend `./mvnw.cmd clean verify`：2282 tests，0 failures，0 errors，2 skipped；BUILD SUCCESS。日志 `.runtime/budget-simple-v22-verify.log`。
- frontend `npm run build` 成功；预算采购相关 Node 测试 49 项通过。日志 `.runtime/budget-simple-v22-frontend-build.log`、`.runtime/budget-simple-v22-frontend-tests.log`。
- `node tools/build-procurement-browser.mjs frontend/dist .runtime/budget-simple-v22-final-browser` 成功，保留原样式和 221 处颜色兼容回退。
- `node tools/test-budget-material-background.mjs <browser-executable> .runtime/budget-simple-v22-final-browser <label>`：Chrome 109.0.5412.0 与 Chromium 151.0.7922.34 均 exit 0，errors=[]。覆盖跨页勾选、项目搜索、历史搜索/日期/分页、结果重开，以及原预算/采购回归；此项使用模拟 API。
- 最终 Chrome 109 截图 `.runtime/budget-background-browser/chrome109-v22-final-search-pagination.png` 已查看，日期及列表完整显示。
- `git diff --check` 通过。

## 26 服务器真实 HTTP / DM8 验证

脚本 `.runtime/update-budget-simple26-v22.sh` 校验上个 release 和两份部署文件哈希，复制新 release，原子切换 current，仅重启后端，失败恢复旧链接。无数据库迁移、无生产数据修复。

- `BUDGET_SIMPLE_SUM_TEST26_DEPLOY=PASS`。
- release：`/opt/audit-agent/releases/budget-simple-v22-20261016.PTbgcn`。
- 回滚记录：`/opt/audit-agent/update-backups/budget-simple-v22-20261016.9hHIEv`；previous 指向先前 V21 release，configs.sha256 保留配置校验记录。
- Nginx HTTP 入口及 3 个直接引用资源与新 release 磁盘文件逐字节一致。
- `GET /api/v1/health`：200，`{"code":200,"message":"success","data":{"service":"audit-backend","status":"UP"}}`。
- 重启回归之后再次执行 `sha256sum --status -c <backup>/configs.sha256` exit 0；backend、AI 服务均 active。AI 地址/密钥/模型名、Nginx、环境文件不变。

`tools/test-budget-material-split26.py` 使用临时合成账号、6 份纯合成 XLSX、40 条记录，真实上传、解析并调用 DM8 实现的新接口，整轮 exit 0：

- 三档合计分别 9999.99、50000、200000；10000、100000 两个边界均归入中间档。
- 明确选定的副本和不同版本分别累加；跨年度金额直接相加。
- 游标遍历全部 40 条，无重复或遗漏；项目行和材料名称搜索均通过（包含 CLOB INSTR 查询）。
- 历史每页 6 条，第二页不重复；名称筛选、日期筛选通过。
- 同一操作 6 个并发请求返回同一快照；修改同一操作的范围返回 409；匿名读取返回 401。
- 后端重启并重新登录后，历史重开和原操作重放保持原结果。
- 临时合成账号最终禁用，合成审查事实和审计保留。

后端 JAR SHA-256：`eb29660d4a7cac5a3af67ecd0ef76a70d7cd98fa6db0bd05f37d20b672ca91fa`。

前端部署归档 SHA-256：`cbc99bc654b0303a89005bb01899126ae9b08e6b66f52eb83fba447e9bcfc6ff`。

甲方 ARM 现场、Chrome 109.0.5414.120 精确补丁版本与现场字体、大规模数据性能、非结构化模型抽取准确率：NOT VERIFIED。本次真实合成材料验证不等同于所有文件都能完整提取金额。三个区间是本次临时分类要求，不代表已确认的正式审批或风险制度。本次未提交/推送 Git，未生成甲方更新包。
