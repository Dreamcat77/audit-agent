# 合同审核 final-closeout 现场冻结交接单（2026-09-24）

状态：`SITE_STATE_SAVED / BUSINESS_SERVICES_STOPPED / FINAL_01_06_RERUN_NOT_COMPLETED`

本文件只记录非敏感身份与持久化状态。未记录账号密码、数据库密码、API key、Token、Cookie、Session 内容或原件正文。

## 1. 代码与构建身份

| 项目 | 现场值 |
| --- | --- |
| branch | `feature/contract-final-closeout-20260923` |
| code checkpoint | `c963f7b59e2e3c8934d5cb43cf7d992a86c81c43` |
| worktree | `D:\code\audit_agent_1D\.worktrees\contract-final-closeout-20260923` |
| Backend code tree | `bceb1d20f382744ec53b67918b1717e8af204bf8` |
| FastAPI code tree | `04015550c3d881c950febe229aad1fe52421567f` |
| Frontend code tree | `7b84c11ac56f409938b9b1cae7578fa0545ce5b5` |
| DM8 migration tree | `b4bd5586cc36e092fc15cd10baa6ff92e917e204` |
| Backend JAR SHA-256 | `634A73167A44723AB507F964F5A85E36045CBA9AE2E887FBB763626A46FFA6AC` |
| Frontend bundle aggregate SHA-256 | `5BE261B008E873A44A4BC50E800215175749F4557243F0E98AB36F54AC1B4149` |
| FastAPI tracked source aggregate SHA-256 | `1B65DDF84545ADBF09B991C7FB126B20C00CAF4E393AD966BE553CC9818EB731` |

Frontend bundle 聚合身份覆盖 `dist/index.html`、`dist/assets/index-BPGoCOb6.css` 和 `dist/assets/index-Ce73lWl_.js` 的相对路径及逐文件 SHA-256。Backend `clean verify` 在冻结前完成：1872 tests，0 failure，0 error，2 skipped。FastAPI full suite：353 passed，1 个既有 deprecation warning。随后前端门禁遇到 sandbox `spawn EPERM`，解除 sandbox 的重跑被现场冻结指令中止；没有新增业务操作，且本次最后修复没有改前端源码。

## 2. 运行现场与服务状态

PID ledger 位于 `.runtime\final-closeout\integration\pids.json`，保留以下上次启动身份：

| 服务 | ledger PID | 端口 | 当前状态 |
| --- | ---: | ---: | --- |
| Backend | 21328 | 8888 | 已停止；无 listener |
| FastAPI | 27976 | 8001 | 已停止；无 listener |
| Frontend | 11680 | 5173 | 已停止；无 listener |

ledger 的 `codeRoot` 均为本 worktree。现场冻结时三个 PID 均已退出，8888、8001、5173 均无监听，因此没有对未知 Java/Python/Node 进程执行停止操作，也没有启动服务复验。

## 3. DM8 与 migration 状态

- 数据库逻辑身份：Phase 1B 已核实的 integration DM8，major version 8；未记录连接串或账号。
- B/AMOUNT_ONLY migration：`APPLIED`，行约束检查 `invalidRows=0`；本次冻结未重跑。
- A-mini fact extraction migration：`APPLIED`。
- C typed BREACH/TERM migration：`APPLIED`。
- A/C 的 5 张新增表全部存在：
  - `PROJECT_FACT_EXTRACTION_ATTEMPT`，23 rows；
  - `PROJECT_FACT_EXTRACTION_CANDIDATE`，14 rows；
  - `CONTRACT_REVIEW_CLAUSE_COMPARISON`，30 rows；
  - `CONTRACT_REVIEW_CLAUSE_VALUE`，130 rows；
  - `CONTRACT_REVIEW_CLAUSE_VALUE_EVIDENCE`，59 rows。
- Candidate authority 的 parse binding unique constraint 仍启用，列身份与 preflight 一致。
- worker 风险四项：`claimable=0`、`expired=0`、`running=0`、`validLease=0`。
- 冻结阶段只执行 catalog/业务结果只读查询；没有 migration、DDL、ReviewRun 或 DM8 写入测试。

## 4. Redis、文件仓与日志

| 项目 | 现场值 |
| --- | --- |
| Redis session namespace | `audit-agent:contract-final-closeout:d743a15` |
| storage | `D:\code\audit_agent_1D\.worktrees\contract-final-closeout-20260923\.runtime\final-closeout\integration\storage` |
| ai-temp | `D:\code\audit_agent_1D\.worktrees\contract-final-closeout-20260923\.runtime\final-closeout\integration\ai-temp` |
| logs | `D:\code\audit_agent_1D\.worktrees\contract-final-closeout-20260923\.runtime\final-closeout\integration\logs` |

storage 当前保留 3 个顶层对象，logs 保留 20 个文件，ai-temp 当前为空。未执行 Redis 清理、`FLUSHDB`、文件删除、日志删除、数据删除或 migration 回滚。

## 5. Provider 与 A-mini 配置

- provider 逻辑身份：`OPENAI_COMPATIBLE_ENDPOINT_SHA256:b771b7f040d2f39faab937adea0f6ddc7f91a0331aa705ebc96b843b1ee02b50`。
- reference env SHA-256：`1221AA61449609CD2DA399F0103F71292A751CBB72F5A99B7A65CC70008FB3D5`；reference 文件未修改。
- 精确模型：`audit-qwen-27b-16k:latest`，仅在本轮 PowerShell 会话覆盖。
- A-mini：`reasoning_effort=none`，`max_tokens=4096`，`temperature=0`，严格 JSON object。
- retry/repair：只对第一次 `INVALID_PROVIDER_JSON` 做一次全量重新生成，最多 2 次 transport attempt；其他 provider 错误 fail closed。候选 schema 任一非法行或 evidence/locator 任一非法行会使整次响应失败，不做部分成功。Java 继续核验 project/file SHA/parse tuple、locator、excerpt、value/context，并由 Candidate → Human Confirmation → Promotion 决定正式事实。

## 6. 01—06 保留对象与提取状态

以下都是冻结前已经产生并保留的对象。本次现场冻结没有创建新对象或修改旧对象。

| 套件 | ProjectId | CandidateId | ContractId | exact runId | run status | A-mini 自动发布字段 | 人工补齐字段 | provider 失败/安全原因 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 01 一致基线 | `c44d7280-e476-4214-9d27-4a4262c71906` | `83d4b69d-990a-4583-8f23-908d8e2e313d` | `9036fc67-520f-43ec-8d92-2f604d76af3e` | `93aff767-fa0a-46f6-b6f7-20f45475dc44` | COMPLETED | `CONTRACT_NAME`、`AWARD_AMOUNT`、`AWARD_SUPPLIER` | contractNo、contractType、contractAmount、amountInWords、4 条 AMOUNT_ONLY lineItems、noticeNo、projectName、winningDate | CONTRACT_BODY 1 个失败 attempt/5 calls，`UNAVAILABLE`，随后 1 call 成功；AWARD 1 call 成功，2 accepted/5 rejected |
| 02 金额差异 | `65bf1443-1780-4a31-809a-17e0c8b7c17f` | `f98cf266-6e37-41d0-9b17-5a01a7d5c161` | `dddf3c2f-9301-4c8c-9a3a-33bccb66d138` | `1fdc9ffc-3037-471f-bf6a-999056c90ffd` | COMPLETED | `CONTRACT_NAME` | contractNo、contractType、contractAmount、amountInWords、4 条 AMOUNT_ONLY lineItems、完整 awardNotice | AWARD 1 个失败 attempt/1 call，`UNAVAILABLE`；CONTRACT_BODY 1 call 成功 |
| 03 服务差异 | `04da03a3-aab8-4a79-bc2e-5dba6ed45d63` | `4858deb8-951b-435d-86d7-33fd63db745f` | `ffd2608e-14ba-4e41-a767-144a35f3c374` | `859e8ddd-a8c6-4505-8f02-82f15970fb5d` | FAILED（仅 SEMANTIC_AI） | `CONTRACT_NAME` | contractNo、contractType、contractAmount、amountInWords、4 条 AMOUNT_ONLY lineItems、完整 awardNotice | AWARD 1 个失败 attempt/1 call，`UNAVAILABLE`；CONTRACT_BODY 1 call 成功 |
| 04 条款差异 | `1905715e-b1e9-404c-9bcf-67c401b5b478` | `6ce542bc-adc2-45d4-bb47-47640726d741` | `1a56c8c8-8f47-42bb-ac31-c196013afbca` | `12d07883-45a3-48b3-88eb-668950f048a2` | FAILED（仅 SEMANTIC_AI） | `CONTRACT_NAME` | contractNo、contractType、contractAmount、amountInWords、4 条 AMOUNT_ONLY lineItems、完整 awardNotice | AWARD 1 个失败 attempt/1 call，`UNAVAILABLE`；CONTRACT_BODY 1 call 成功 |
| 05 异项目通知 | `d9aa1d5f-baf1-4ddb-b3ca-d7945d920295` | `7b955573-e355-4c6c-aec7-59ea461b47b1` | `f784894a-9015-414b-91f0-b009eb01eead` | `5f1cbc75-549a-4dee-a754-f8d8b67153ef` | COMPLETED | `CONTRACT_NAME` | contractNo、contractType、contractAmount、amountInWords、4 条 AMOUNT_ONLY lineItems、完整 awardNotice | AWARD 1 个失败 attempt/1 call，`UNAVAILABLE`；CONTRACT_BODY 1 call 成功 |
| 06 扫描/空白材料 | `156fc9b8-8f24-407d-a07f-b158d0d5dcdd` | `442e3382-c1bb-43ee-9766-0322ad789acd` | `a233a30b-28b4-4ee0-9ccd-59a1f55ae520` | `393ef405-b0a2-4574-b606-e00776e842c3` | COMPLETED | `CONTRACT_NAME` | contractNo、contractType、contractAmount、amountInWords、4 条 AMOUNT_ONLY lineItems、完整 awardNotice | AWARD 1 个失败 attempt/1 call，`UNAVAILABLE`；CONTRACT_BODY 1 call 成功 |

`UNAVAILABLE` 是持久化的安全原因，不扩写为未持久化的 provider 内部原因。自动发布字段与人工补齐字段分开记录，人工值没有计入自动提取成功。

## 7. 特殊现场结果

- 03 `SEMANTIC_AI`：最终 `FAILED`，共有 2 个 attempts；F02、BREACH、TERM、MATERIAL_VALIDATION 已完成。旧 run 不再重试。
- 04 `SEMANTIC_AI`：最终 `FAILED`，1 个 attempt；F02、BREACH、TERM、MATERIAL_VALIDATION 已完成。旧 run 不再重试。
- 05：异项目通知已确认 `NOT_SAME_PROJECT` 并排除；exact run 只冻结 4 份同项目材料，没有把异项目材料带入审核。
- 06：扫描通知 parse method 为 `OCR`，engine 为 `PADDLEOCR_3.7.0`，OCR 结果持久化；A-mini 合同正文发布了 `CONTRACT_NAME`，award 候选因安全失败未发布。
- 六套 F02 均已持久化。02 的成交金额为预期 FAIL；四条 amount-only 行的逐行算术保持业务性 NOT_EVALUATED，line sum 可计算。

## 8. 当前未完成事项

- **未完成针对本代码 checkpoint 的新一轮最终 01—06 全量复验。** 现场冻结指令到达后没有启动任何新 E2E、A-mini 请求、ReviewRun、migration、DM8 写入或浏览器业务操作。
- 最后一轮源码修复后的真实运行链尚未重新启动；冻结前只完成 backend/FastAPI 自动化门禁和 DM8 只读核对。
- 最后一轮独立 reviewer 复核被现场冻结中断；此前 recovery/UI 独立复审无 Critical/Important。
- 03/04 的 SEMANTIC_AI 保持上述失败状态；没有第三次 retry。
- 第二低权限账号的跨用户 owner 浏览器 E2E、OA、客户正式模型、外部主体资格仍未验证。
- 现场保留的 01—06 应作为下次 VPN/现场复验入口，不得修改 owner、回填旧 run 或清理数据。

## 9. 恢复入口

在其他机器从 `c963f7b59e2e3c8934d5cb43cf7d992a86c81c43` 恢复代码开发；现场交接 evidence 的最终分支 HEAD 以远端 `feature/contract-final-closeout-20260923` 为准。下次现场/VPN 先只读核对 reference env hash、DM8 worker 四项、五张表、PID/端口和 storage，再从本文件第 6 节的 6 个 ProjectId 与 exact runId 继续。不得自动重试 03/04 的旧 SEMANTIC_AI attempt。
