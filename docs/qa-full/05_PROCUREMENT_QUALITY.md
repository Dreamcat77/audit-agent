# 05 Procurement Quality

状态：已在 26 QA 入口分别上传合成正常采购批次和跨文件付款冲突批次。两批共 23 个文件，文件状态均 UNKNOWN；TENDER_COMPLIANCE 与 AGENT_PRECHECK 均 UNKNOWN，QUOTE/BLACKLIST 为 DATA_INSUFFICIENT，0 个供应商报价、0 条证据可供断言。两个场景分别产生 `DATA_INSUFFICIENT` 终态，验收失败项包括预期金额、报价行和跨文件冲突均未生成。

本轮日志确认模型接口返回 HTTP 502（与合同/预算场景一致）；由于记录中的解析文本 hash 缺失且文档状态 UNKNOWN，当前证据不足以判断采购内容解析是否先于模型调用失败。采购字段和规则质量为 BLOCKED_EXTERNAL_MODEL / NOT VERIFIED，不能把缺失的分析结果作为业务规则正确或错误的证据。

证据：QA26 `acceptance.py procurement normal conflict --revision r6`，运行目录 `/home/admin/audit-agent-backups/qa26-run-20261111-r9/results/`。使用合成数据，未触碰黑名单主数据或审批配置，未计算准确率。

采购存在材料批次、单材料、独立 AuditCase、F10 招标合规、F11 拆分/围串标辅助识别、报价比较、黑名单和审批报告等多条流程。不得把它们当成统一分析结果；后续记录每条 E2E 的入口、文件、解析范围、模型调用、规则版本和持久化结果。

后续评测项：招标/投标/报价材料分类；项目名/编号、采购方式和供应商；资格条件、评分办法、招标条款；预算/报价角色识别和金额比较；多供应商隔离；跨文件冲突；黑名单正反例；拆分线索；证据定位。风险线索不能变成违法事实，不能编造供应商和采购条件。

专家真值集未确认；模型调用已执行但返回 HTTP 502，不能给出准确率。
