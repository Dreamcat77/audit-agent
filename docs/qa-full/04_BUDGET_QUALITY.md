# 04 Budget Quality

状态：已在 26 QA 入口对合成预算材料执行 5 种批次场景，测试账号新建记录保留在测试环境。外部模型网关返回 HTTP 502，4 种依赖模型的场景无法评价字段抽取/语义准确率；Excel 结构化场景独立解析通过。

| 场景 | 实测结果 | 判定 |
|---|---|---|
| 超预算、金额大写、批复漏项（DOCX/TXT） | 任务均终态 UNKNOWN，0 条预算明细；模型依赖失败 | BLOCKED_EXTERNAL_MODEL |
| XLSX 申报及批复 | 任务 PARSED、结果 COMPLETED，1 条预算明细；校验器预期断言通过 | PASS（仅该合成结构化样例） |
| 有效材料 + 损坏附件 | 有效材料 UNKNOWN，损坏附件 FAILED，均无明细；因模型网关失败，无法判断部分成功隔离行为 | NOT VERIFIED |

证据：QA26 `acceptance.py budget ... --revision r6`，运行目录 `/home/admin/audit-agent-backups/qa26-run-20261111-r9/results/`。模型失败的原因为已观测的网关 502 / CONNECT_TIMEOUT；不能将语义场景结果归因为金额规则或 OCR。未测试用户真实文件，未计算准确率。

源码入口包括 `BudgetMaterialController`、`BudgetController`、`BudgetSemanticAnalysisController`、`BudgetApprovalProgramController`、`BudgetSplitRiskController` 与 `BudgetMaterialRiskController`。上传/批次、F01确定性审批校验、语义抽取、拆分风险和风险预估是不同链路。

后续评测项：当前项目名称/编号、预算金额与角色、年度、科目、数量×单价、审批材料、超预算/错支/漏项、拆分线索、跨文件冲突、错误/历史数字干扰、证据来源绑定。预算金额不得与报价、历史金额或实支混淆；金额确定性计算须以 Java BigDecimal 为准。

标准答案状态：无已确认专家标注集。缺失真值项记为探索性测试，不计算 Precision/Recall/F1。真实模型端点已调用，但本轮返回 502/连接超时。
