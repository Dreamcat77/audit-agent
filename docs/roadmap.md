# Current roadmap

本路线图以已完成的 P1-A/P1-B/P1-C 源码基线
`b3615b8adf81989c83f7135de1a7061df3636399` 为起点；本 pre-merge hardening
分支只修正已知问题和当前文档，不启动新 Phase。`main@dd0d02c…` 是 2026-09-14
历史审计基线，不代表当前分支能力。实现状态以源码、测试和对应环境的 evidence 为准。

## 已实现的纵向链路

| 能力 | 当前源码状态 | 验证边界 |
|---|---|---|
| Project Intake / OCR | 安全上传与 ZIP、解析 provenance、不可变 ParseResult、typed ExtractionResult、恢复 | 本地及既有 P1-B 集成环境；客户材料覆盖仍有限 |
| 角色与项目归属 | 确定性角色评估、有界 AI fallback、人工 RoleDecision、IdentityDecision | 建议不替代人工业务决定 |
| Candidate 到 Contract | Candidate 专属可选 AWARD_NOTICE support authority、人工 Confirmation、Promotion、ProjectContract | 正式 Contract 是已确认业务事实；材料提供 provenance |
| F02 | 五条确定性规则、DM8 任务/结果与幂等、持久确认 | 缺真实业务事实时仍允许 `NOT_EVALUATED`；金额用 Java `BigDecimal` |
| ReviewRun | 不可变 Contract/材料快照、step/attempt、server worker、F02 与 semantic/breach/term 持久结果、刷新及进程重启重开 | P1-C 在 P1-B 集成 DM8 验证了完整 F02 快照/重开；客户部署未验证 |
| 审查收口 | sanitized metadata-only debug bundle、非 F02 finding 确认、版本化报告 | 客户保留策略和正式报告模板待确认 |

已有证据见 [source baseline](source-baseline.md)、[P1-B evidence](evidence/candidate-cross-material-authority-verification-20260920.md)
和 [P1-C evidence](evidence/phase1c-f02-structured-data-completeness-20260921.md)。
这些不是公网、客户 DM8、TongWeb/TongRDS/TongHttpServer 或客户最终验收的证明。

## 原路线图里程碑对照

2026-09-14 的路线图曾按 P0-A 材料角色/项目归属、P0-B durable ReviewRun、
P0-C 脱敏调试工件、P0-D AI/OCR 质量回归、P0-E 客户 schema drift，以及 P1
异步执行和报告排序。当前状态如下；原有设计约束仍见
[architecture](architecture.md) 和 [material testing strategy](material-test-and-debug-strategy.md)。

| 原里程碑 | 当前结论 | 保留的约束或待办 |
|---|---|---|
| P0-A 角色/归属判断 | 已实现 assessment 与人工 RoleDecision/IdentityDecision | 未知材料和混合项目不能由文件名或 AI 自动定权 |
| P0-B 集成 ReviewRun | 已实现冻结输入、durable step/result 和重开 | F02 引用真实 AuditTask；UNKNOWN 独立于 FAILED/MISSING |
| P0-C 脱敏调试工件 | 已实现 metadata-only bundle | 不包含原文、prompt、raw response 或 secret |
| P0-D AI/OCR 质量回归 | 持续扩充 | 需要真实材料的版本化 fixture、ground truth 和统一指标 |
| P0-E 客户 schema drift | 未在客户库验证 | 先只读 preflight，再另立受控 repair/迁移计划 |
| P1 durable async / 报告 | server worker、版本化报告已实现 | 真正队列产品和客户签发验收仍是独立决策 |

## 当前待办与边界

### 材料与规则质量

- 获取获批准的真实合同、采购/招标、投标和中标/成交材料，建立脱敏 ground truth、
  版本化 fixture 与评测阈值。用户文件名和目录不可被当作固定模板。
- 本 hardening 分支为 `ProjectFactExtractor` 增加了通知书中无冒号的“成交金额/成交供应商”
  字段行和键值表提取，并排除候选人语境。01—05 原始通知书在只读解析测试中已产出
  金额及供应商事实；这尚未证明角色/项目归属、Candidate、人工确认、Promotion 或浏览器
  全链验收。P1-B 的非空 AWARD_* 集成验证仍是明确标记的派生合成 fixture。
  更广语义表达属于已批准详细设计、尚未获准实施的变更 A。
- 扩展 page/paragraph/span evidence locator 的材料覆盖及 parser/version 迁移语义；
  AI 只能提供有界语义线索，不能覆盖 F02 金额和最终 Contract 权威。
- F01/F03/F04 保持当前实现及回归；F04 正式审批阈值、层级和规则版本仍待客户输入。
  F09/F15、附件完整性、资格数据源等后续业务不属于本 hardening。

### 环境和交付

- 对客户 DM8 先做只读 catalog/history preflight，再针对 V001—V022 与受控
  `CONTRACT__*.sql` 分别制定获批准的迁移、备份和回滚计划。仓库没有自动 migration
  runner；文件存在不表示客户库已经部署。
- 保留现有 JAR-first 边界；目标 TongWeb、TongRDS、TongHttpServer 和客户网络/公网
  必须在对应平台上分别验证，不从本地或 P1-B 集成结果外推。
- 真正消息队列、provider routing、视觉模型和更广 AI/OCR 评测是独立后续决策。
  当前 server worker 的 durable claim 由 DM8 支撑，Redis 不承担业务事实。

## CUSTOMER_INPUT_REQUIRED

正式模板与样本、材料归属规则、附件完整性、违约和解除阈值、资格数据源与访问政策、
允许输出的脱敏工件、F04 正式规则、客户 AI/GPU/provider 能力及运维责任边界。
缺少业务确认的项目保持 `BLOCKED_BY_CUSTOMER_INPUT`，不能用 demo fixture 代替。
