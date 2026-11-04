# 合同审核统一收口集成环境操作单（2026-09-23）

状态：EXECUTED / SERVICES AND TEST DATA RETAINED

本操作单只适用于本轮隔离测试。它不授权 DDL、清库、FLUSHDB、修改历史 owner、覆盖旧文件对象、停止未知进程、客户环境部署或把未批准材料发送给模型。

## 1. 固定身份

| 项目 | 实际值 |
| --- | --- |
| CODE_ROOT | D:\code\audit_agent_1D\.worktrees\contract-final-closeout-20260923 |
| branch | feature/contract-final-closeout-20260923 |
| HEAD | e86e6dc59409e0d7e3785882f5b4e1c87e0f07df |
| WIP | 未提交；实际运行身份由 JAR/bundle hash 加当前 diff 共同确定 |
| reference config（只读） | D:\code\audit_agent_1D\.worktrees\ai-ocr-project-intake-demo\.runtime\local-demo.env.integration.ps1 |
| reference config SHA-256 | 1221AA61449609CD2DA399F0103F71292A751CBB72F5A99B7A65CC70008FB3D5 |
| originals（只读） | D:\code\audit-agent-demo\智慧维修系统_合同审查测试材料_20260917 |

参考配置只作为固定配置输入。当前应用从 CODE_ROOT 启动，没有把参考脚本的旧 ROOT 当当前源码目录，也没有修改该脚本或旧 demo worktree。

## 2. 隔离写入位置

| 用途 | 固定值 |
| --- | --- |
| FILE_STORAGE_ROOT | CODE_ROOT\.runtime\final-closeout\integration\storage |
| AI_TEMP_ROOT | CODE_ROOT\.runtime\final-closeout\integration\ai-temp |
| LOG_ROOT | CODE_ROOT\.runtime\final-closeout\integration\logs |
| PRIVATE_RUNTIME_ROOT | CODE_ROOT\.runtime\final-closeout\private |
| Redis Session namespace | audit-agent:contract-final-closeout:20260923 |

上述目录、01—06 测试数据和日志按用户要求保留。旧 demo storage、原始材料和冻结 worktree 未修改。

## 3. 非敏感环境身份

- DM8：Phase 1B 已迁移并验证的 DM8 8 集成库。本次没有重跑 migration，没有 DDL、清库或历史修复。
- Redis：参考配置的集成 Redis，使用上节独立 session namespace；没有 FLUSHDB。
- Java profile：local-dev,local-ai。
- Backend：8888。
- Frontend：5173。
- 本机 FastAPI：8001；Java AI/OCR base URL 均指向此 loopback 服务。
- 私网 provider：host、port、service/API shape 与 reference config 一致；会话内模型覆盖为书面批准的 audit-qwen-27b-16k:latest。
- 原件外发范围只包含本轮 01—06 非敏感测试材料；没有发送凭据、Token、数据库内容或其他项目文件。

仓库 evidence 不记录私网 IP、数据库/Redis 密码、应用密码、Cookie、Token、API key 或完整认证请求体。

## 4. Worker 启动前门禁

启动 enabled worker 前按 production predicate 对 DM8 做了只读核查：

- claimable attempts：0。
- expired attempts：0。
- RUNNING attempts：0。
- valid leases：0。
- 候选端口无既有监听。
- 没有发现连接同库并可自动接管任务的本仓库 Java/FastAPI/frontend 进程、服务或计划任务。
- DM8 与 Redis 可达。

该门禁只说明启动时点没有旧任务接管风险。没有修改、重试或清理历史 task/attempt。

## 5. 实际进程台账

| 服务 | PID | code/build identity | 端口 | 日志 |
| --- | --- | --- | --- | --- |
| Backend | 29356 | fresh JAR SHA-256 085954318B987B014D6F6D702901E234C7EEBE9555EA05DCE26995625185C4F9 | 8888 | .runtime\final-closeout\integration\logs\backend-final.*.log |
| Frontend | 26804 | 当前 worktree Vite source；生产 bundle index-BYklJkJy.js hash 918B6ADF4ABC90CCFF010DBAD212BF625856DB695E110BC5FF0B431B5D567A59 | 5173 | .runtime\final-closeout\integration\logs\frontend.*.log |
| FastAPI | ledger 9164；实际 Uvicorn 2692 | 当前 worktree services/audit-ai source；full suite 346/346 | 8001 | .runtime\final-closeout\integration\logs\fastapi.*.log |
| Browser 1 | 5012 | 隔离测试 profile | CDP 9223 | runtime browser profile |
| Browser 2 | 14904 | 第二隔离 profile | CDP 9224 | runtime browser profile |

Backend 曾为 clean verify 有控制地停止当前台账进程，随后从 fresh JAR 重启为 PID 26524。重启后 health 与 set05 exact-run GET 均通过。

## 6. Provider 与调用结果

书面批准的 exact model ID 为 audit-qwen-27b-16k:latest。启动前重新核对 metadata、host/port/service/API shape；身份一致后才开放 01—06 外发。模型覆盖只存在于本轮 PowerShell 会话，没有修改仓库默认配置、reference env 或客户配置。

DM8 只读计数证明：

- MACHINE_DETERMINISTIC role assessment：31 COMPLETED。
- MACHINE_AI role assessment：8 COMPLETED，精确模型均为批准身份。
- ReviewRun semantic provider operation：6 COMPLETED，精确模型均为批准身份。
- 持久化可证明的 private provider inference operation：14。
- OCR：1，PADDLEOCR_3.7.0。
- A-mini contract fact extraction：0。
- metadata preflight：2 次，不计入 inference operation。

## 7. 原件链路

通过正常登录和业务 API/浏览器创建隔离数据：

正常登录 → 上传只读原件字节 → 解析/OCR → role/identity → Candidate → Human Confirmation → Promotion → exact ReviewRun → 八项结果 → 来源点击 → 复制意见 → 历史重开。

01—06 的 Project/Contract/runId 见验收记录和本人复验说明。六个正式 run 均 COMPLETED；02 真实金额冲突由 F02 FAIL 捕获；05 异项目通知被排除；06 扫描通知经 OCR。刷新、第二浏览器和后端重启没有创建新 run 或改变 frozen input。

## 8. 启动与停止

本轮保留的受控脚本位于 PRIVATE_RUNTIME_ROOT：

- start-integration.ps1：从 reference config 读取固定键，在当前会话覆盖 CODE_ROOT、隔离路径、session namespace 和批准模型后启动三服务。
- start-backend-after-verify.ps1：从 fresh JAR 单独恢复 Backend。
- start-browser.ps1 与 start-second-browser.ps1：启动两个隔离浏览器 profile。
- pids.json：当前 Backend/Frontend/FastAPI 台账。

复验时先核对 reference env hash、端口、DM8 worker predicate 和现有 PID 命令身份，再执行：

powershell.exe -NoProfile -ExecutionPolicy Bypass -File .runtime\final-closeout\private\start-integration.ps1

若三服务已经按本操作单监听，不得重复启动。停止时先读取 pids.json，并核对 PID 的 executable、command line、CODE_ROOT 和监听端口；只对匹配的单个 PID 执行 Stop-Process -Id。浏览器也只停止 5012/14904 且身份仍匹配的进程。禁止全局 kill Java/Python/Node。

## 9. 保留和禁止事项

- 保留当前 worktree、隔离 storage/temp/log、六套成功测试对象、02 前置失败对象和浏览器 profile。
- 不自动删除、清库、改 owner、回填旧 run、覆盖历史文件或重放 Phase 1B migration。
- 不 push、不 merge、不部署客户环境。
- 参考配置、原件目录和旧 demo worktree 保持只读。

## 10. Final-closeout v2 受控迁移补充（执行前记录）

后续书面指令已批准 A-mini 与 typed BREACH/TERM v2 的实现及本轮集成 DM8 验证。本节只覆盖两份 additive、非幂等、人工受控 migration：

- `db/dm8/CONTRACT__contract_final_closeout_fact_extraction.sql`（5 statements）；
- `db/dm8/CONTRACT__contract_final_closeout_typed_clause.sql`（4 statements）。

不重跑 B/amount-only 或任何 Phase 1B 历史 migration。执行前必须再次运行只读 `probe`，证明 DM8 major 8、5 个目标表均不存在、前置表/约束齐全，并且 claimable/expired/RUNNING/valid lease 均为 0。迁移工具逐 statement 输出非敏感结果；任一部分存在即 fail closed，禁止在 partial state 上重放。

本次是新表/新索引的空结构增加，不修改既有表或历史行。执行前的可恢复基线由 catalog probe、既有数据库备份制度和目标表不存在共同证明。已在忽略目录记录逆向操作单：

`D:\code\audit_agent_1D\.worktrees\contract-final-closeout-20260923\.runtime\final-closeout\private\rollback-final-closeout-v2.sql`

SHA-256：`C43724DFF960177DEC4BD807C8659A6CB202F9D7CF9C83241BC1298B460AFB3B`

该逆向操作按依赖顺序仅包含：先删除 value-evidence、value、comparison，再删除 fact candidate、attempt。它只供明确回退决定时人工执行，本轮不自动执行。若 migration 中途失败，停止应用启动并报告实际 catalog；不得自动删除 partial schema 或重跑。迁移成功后运行只读 `verify`，要求 5 个目标表全部存在且初始 row count 为 0。
