# 智能采购自动审查二期验收报告

日期：2026-10-27。实际实施工作树：`E:\audit-agent-new\.runtime\budget-home-e17-20261014`。

## 验收结论

**新增自动询比价切片在真实、独立 DM8 实例上通过隔离验收。当前不满足进入剩余五个模块迁移阶段的条件。**

真实 curl/HTTP、自动 Worker、DM8、实际模型、原文证据、询比价计算和持久化查询已经贯通；随后又从真实 Chromium 页面上传同一批 12 份文件，完成第二轮实际模型验收。两轮均得到预算 222600 元、中标 212000 元和三家报价 212000／216400／219800 元，9 条明细核算一致。

仍有两个明确门槛：完整跨 schema 数据库备份及完整恢复未通过；全仓仍有一期已复现的 14 项历史失败。不得据本报告宣称生产部署完成、所有模块通过或完整灾难恢复可用。本阶段已停止实施，未继续开发其他五个模块。

## 1. 项目状态、源码基线和开关

外层 `E:\audit-agent-new` 不是 Git 根。真实工作树是 `E:\audit-agent-new\.runtime\budget-home-e17-20261014`；Git common dir 为 `E:\audit-agent-new\.runtime\integration-repo\.git`。分支 `fix/budget-procurement-ready-20261019`，HEAD `35f95499c4d5efa475dffc3a3628222750124672`，本阶段未提交或推送。

以 `E:\audit-agent-new\AUTOMATIC_PROCUREMENT_IMPLEMENTATION_REPORT.md` 和真实源码为基准，重新核对一期 22 个实现、测试、SQL及相关源码文件，二期改动前 SHA-256 全部一致。完整名称见 [phase1-22-files.json](E:/audit-agent-new/.runtime/budget-home-e17-20261014/docs/automatic-procurement-phase2-20261027/evidence/phase1-22-files.json)。这 22 个文件中本期修改 7 个，另外 15 个保留原内容；二期另增加 15 个验收源文件，不能把两批“22”混同。

二期初始 `git status --short` 为 198 条，最终为 201 条；短状态会折叠未跟踪目录，并非文件总数。既有修改保留，未 reset、强清理、删除分支或覆盖用户修改。初始状态保存在受限备份，最终状态见 [final-git-status.txt](E:/audit-agent-new/.runtime/budget-home-e17-20261014/docs/automatic-procurement-phase2-20261027/evidence/final-git-status.txt)；本期专属差异见 [phase2-only-source.diff](E:/audit-agent-new/.runtime/budget-home-e17-20261014/docs/automatic-procurement-phase2-20261027/evidence/phase2-only-source.diff)，不能将整个共享工作树相对 HEAD 的差异都算作本期工作。

Controller 和 Worker 均以 `audit.procurement.auto-case.enabled=true` 为创建条件，未配置时默认关闭。原服务开关和配置未修改。只在隔离验收进程显式开启；另实测关闭后 HTTP 404，重新开启后原案件内容完全一致，见 [feature-off-reopen.json](E:/audit-agent-new/.runtime/budget-home-e17-20261014/docs/automatic-procurement-phase2-20261027/evidence/feature-off-reopen.json)。旧路径 `D:\code\audit_agent_1D` 未作为本次实际实施目录。

## 2. 备份可靠性与独立恢复

一期源码备份 `E:\audit-agent-backups\audit-agent-new-20261026-235942` 及恢复副本 `E:\audit-agent-backups\restore-validation-audit-agent-new-20261027-003500` 仍存在。一期全量记录为 45417 文件、21196282816 字节；本期重新核对其 SHA 清单文件哈希为 `F99E792FC8122902FDCC889A1E1A217584A1FA33D1A30A647F758CE759312EAB`。该全量副本在一期改造前形成，不能单独代表一期新增代码；二期前另将一期 22 文件和报告备份到 `E:\audit-agent-backups\automatic-case-phase2-20261027-020846`，本期再次逐文件校验一致。恢复时应组合相应版本，不能只取旧全备。

原数据库按用户明确提供的测试环境处理，连接目标为该测试机本机数据库；应用连接账号不是 SYSDBA/SYSAUDITOR。原业务库只做读取、导出及短时 SHARE 锁保护，未执行业务 DML、迁移 DDL、提权或安全配置变更。

DM8 V8、dexp、dimp 构建均为 `03134284604-20260707-335949-20228`，JDBC 为 `8.1.3.140`。原账号具备 RESOURCE 和自身对象权限，但跨 schema 主表没有 DUMP 权限。

导出失败原因及实际结果：

1. 此构建显式指定 `FLASHBACK_SCN=FALSE` 仍触发 `-9803`。有效做法是完全省略该参数；没有启用或修改数据库闪回配置。
2. 先在独立 DM8 验证 SHARE 锁阻止并发写入、NOWAIT 返回 `-6409`，再对源 94 张应用表使用短时 SHARE 锁保护清单与原生导出，dexp exit 0。
3. 有效的**94 表数据导出**为 `consistent-source-default.dmp`，41012288 字节，SHA-256 `e153d7af75b66ed8daee99461f2bfc6578f9868821221c7fd919607311d65359`。原生文件和敏感日志保存在受限二期备份目录，不进入 Git。
4. 将该文件恢复到独立实例的 `AUTOCASE_RESTORE`，94 表、16422 行的列定义、行数及全量内容哈希全部一致。但 11 个外键引用 `SYSDBA.PROCUREMENT_DEMAND`；该主表不在应用 schema 内，恢复缺失主表，dimp exit 2、33 warnings。
5. 尝试将外部主表纳入同一受锁快照导出，因缺少 DUMP 权限报 `-2855`、exit 2。未提升权限，也未将这次失败或不完整文件标为有效完整备份。

结论：**94 表数据备份与独立数据恢复 VERIFIED；包含跨 schema 依赖和全部外键的完整数据库备份 NOT VERIFIED，完整恢复未通过。** 两者不能合并为“备份恢复成功”。源库全部 SHARE 锁已经释放；由于完整备份仍未完成，原业务库继续禁止本阶段写入。事实摘要见 [backup-verification-summary.json](E:/audit-agent-new/.runtime/budget-home-e17-20261014/docs/automatic-procurement-phase2-20261027/evidence/backup-verification-summary.json)；原始日志校验清单见 [protected-remote-evidence-manifest.json](E:/audit-agent-new/.runtime/budget-home-e17-20261014/docs/automatic-procurement-phase2-20261027/evidence/protected-remote-evidence-manifest.json)。

## 3. 隔离环境与验证边界

使用测试机已经安装的 DM8 工具新建 `AUTOCASE2` 独立实例、独立数据目录与端口 15287；新应用 schema 为 `AUTOCASE_APP`，恢复 schema 为 `AUTOCASE_RESTORE`。没有在原业务数据库内执行新增四表迁移，也未安装系统软件。

新实例曾配置 LISTEN_IP，但实际监听为 `*:15287`，不能宣称仅绑定 loopback。服务器禁用 SSH TCP forwarding；本次没有更改 sshd，而是使用本地 15288 TCP 代理直连新实例。隔离依据是独立实例、用户、数据目录和测试数据，不是 SSH 隧道或已经验证的网络访问控制。验收结束已停止此新实例。

Java 17 / Spring 的验收启动器使用 `local` 身份和会话 seam，同时注入真实 DM8 Repository、MyBatis、DataSourceTransactionManager 及真实 HTTP/Worker。长期操作审计在独立 DM8 辅助表 `AUTO_CASE_TEST_AUDIT` 持久化，和业务更新使用真实事务；这是一张测试日志辅助表，**不是第五张新增业务表**。生产身份 DM8 Adapter、正式 `AUDIT_OPERATION_LOG` 集成和 dev29/customer 完整部署链路不在本次 VERIFIED 范围。旧模块继续使用 local 测试适配器，页面旧项目列表不是本次真实业务库的数据。

## 4. 四表设计与本次修正

- `AUDIT_CASE`：案件、拥有者、不可变上传操作键、状态、版本、租约、快照。`(OWNER_ID,OPERATION_KEY)` 唯一；待执行索引覆盖状态/租约/创建时间，历史索引覆盖拥有者/创建时间。案件并不冒充 Project、Contract、ReviewSession 或 AuditTask。
- `AUDIT_CASE_FILE`：一个案件拥有多份文件，`(CASE_ID,FILE_ORDER)` 唯一；增加 `(CASE_ID,ID)` 组合唯一键，供子表严格关联。
- `AUDIT_FACT`：**唯一的事实值来源**，按文件和 FACT_ORDER 保存候选、原文定位、逐字摘录、验证状态及模型/提取版本。增加事实顺序、唯一约束和验证状态约束；通过 `(CASE_ID,FILE_ID)` 外键保证事实不能挂到另一案件的文件。
- `AUDIT_QUOTE_LINE`：报价明细投影，通过同样的组合外键关联文件；数量 `DECIMAL(18,4)`，单价/金额 `DECIMAL(18,2)`，正数量和非负金额 CHECK。多供应商通过各自报价文件及报告关联，已实际持久化 3 家、9 行。

一期文件快照与 AUDIT_FACT 重复保存事实的风险已修复：文件快照只保留无 facts 的文档、事实数量及 SHA 摘要；读取从 AUDIT_FACT 重组，检查顺序、数量、摘要和孤儿事实。稳定缺失或损坏时 fail closed，不把它当成空事实成功。文件全文/页段仍保存在文件 CLOB；案件文档清单只保留元数据，不再复制全文或事实。

报告快照及报价行仍是派生投影，由同一次受版本/租约保护的事务统一维护。并非所有 JSON 元数据都已彻底规范化；禁止外部独立修改这些派生数据，正式扩展仍需受控迁移。当前格式是尚未应用到原业务库的新迁移基线，不承诺兼容早期草案快照。

真实 DM8 下 CLOB 写入使用 ClobTypeHandler；已验证超过 32KB 的中文全文往返。事实冲突保留多候选，未验证事实保存为 UNVERIFIED；未知数据不转换为零、不参与金额计算。实际数据库测试验证冲突金额重开后仍冲突、UNKNOWN 文档及 UNVERIFIED 候选仍保留。

## 5. 迁移实际结果

`PROCUREMENT__automatic_case_v1.sql` 只新增上述四表、约束与索引，未修改或删除旧采购表、历史数据或审批流程。

通过隔离专用 `AutoCaseSchemaCheck` 执行：首次 `DDL_APPLIED=8`、`SCHEMA_VERIFIED tables=4`；再次执行 `DDL_APPLIED=0`。检查真实 Catalog 的列类型、长度/精度/scale、NOT NULL、主键 ID、组合唯一键、外键目标/列序、索引列序及 CHECK 启用状态和语义。人为禁用新表 CHECK 后检查器拒绝，再启用后检查通过；未删表重建。

最终已有案件和报价数据时再次运行，四表行数及全量内容哈希前后完全一致，见 [schema-repeat-data-integrity.json](E:/audit-agent-new/.runtime/budget-home-e17-20261014/docs/automatic-procurement-phase2-20261027/evidence/schema-repeat-data-integrity.json)。真实 Catalog 和案件行数见 [database-catalog-and-data.json](E:/audit-agent-new/.runtime/budget-home-e17-20261014/docs/automatic-procurement-phase2-20261027/evidence/database-catalog-and-data.json)。

重复安全性依赖这个检查器：全无表才执行，部分存在或未知漂移拒绝；**裸 SQL 不是可任意重放的 IF NOT EXISTS 脚本**。检查器目前是隔离验收工具，尚未集成正式客户制包/安装流程。未进行原业务库迁移或生产部署。关闭/恢复应用开关不回滚 DDL；未声称完成生产代码/DDL 回滚演练。

## 6. 真实 DM8 专项测试与并发读取修复

以隔离连接和 `AUDIT_CASE_TEST_DB_CONFIRM=ISOLATED_AUTOCASE2` 执行：

```powershell
.\mvnw.cmd -Dtest=Dm8AutoCaseRealIntegrationTest test
```

5 项实际执行、0 failures、0 errors、0 skipped，见 [real-dm8-junit.log](E:/audit-agent-new/.runtime/budget-home-e17-20261014/docs/automatic-procurement-phase2-20261027/evidence/real-dm8-junit.log)：中文大 CLOB 和单一事实源；多表事务回滚；两个竞争更新仅一个成功且旧写者不删除事实；跨案件外键拒绝和缺事实 fail closed；冲突/未知候选重开保留。测试只能连接本次隔离 schema，不能拿原库地址替换后直接运行。

验收发现 Worker 更新期间 HTTP 可能混读父子版本。已在诊断子记录损坏前核对案件快照版本，版本变化时有限重读；持续竞争返回 HTTP 409，保留案件/操作标识，不返回虚假结果。稳定数据损坏仍拒绝读取。已增加针对性仓储回归及真实 HTTP 409 场景，最终全仓中的相关测试通过。

## 7. 两类端到端证据分别报告

**Mock 模型 + 真实 DM8/HTTP/Worker：VERIFIED。** curl 自动上传 12 文件，不调用手工 process/scan 或业务启动接口，约 61 秒完成。案件 `2951c0d4-5154-3dbc-b8b9-0754d9ebff8d`。预算、中标、三家报价、9 条明细、证据与 operation 重开均通过。见 [dm8-simulated-upload.json](E:/audit-agent-new/.runtime/budget-home-e17-20261014/docs/automatic-procurement-phase2-20261027/evidence/dm8-simulated-upload.json)、[dm8-simulated-result.json](E:/audit-agent-new/.runtime/budget-home-e17-20261014/docs/automatic-procurement-phase2-20261027/evidence/dm8-simulated-result.json)。

**实际模型 + 真实 DM8/HTTP/Worker：VERIFIED。** 第一轮 curl 案件 `eee63216-0126-33fc-b02a-2eca6e6c5474`，约 395 秒完成；随后真实浏览器案件 `74c808d7-aaa3-313b-b59d-7c0fe400b055` 再次完整执行，同样通过。FastAPI 使用最终源码和真实模型配置；金额角色校验最终规则已重新回归。没有用固定 Mock 答案替代实际模型。见 [dm8-actual-result.json](E:/audit-agent-new/.runtime/budget-home-e17-20261014/docs/automatic-procurement-phase2-20261027/evidence/dm8-actual-result.json)、[browser-dm8-actual-result.json](E:/audit-agent-new/.runtime/budget-home-e17-20261014/docs/automatic-procurement-phase2-20261027/evidence/browser-dm8-actual-result.json)、[final-core-assertions.json](E:/audit-agent-new/.runtime/budget-home-e17-20261014/docs/automatic-procurement-phase2-20261027/evidence/final-core-assertions.json)。

真实链路为：认证/CSRF → 自动案件创建 → DM8 → 文件安全存储与解析 → FastAPI 模型结构化提取 → Java 原文/金额角色校验 → 自动 Worker → 既有确定性询比价计算 → DM8 → HTTP/页面读取。所有金额计算继续使用 BigDecimal，未改变既有评分/报价统计规则。

## 8. 最终 12 文件提取结果与证据

材料目录为 `D:\UserData\Desktop\审计结果测试材料_20261026\采购\批次01_完整项目多材料`，12 份、620764 字节。最终浏览器案件实际落库：AUDIT_CASE 1 行、AUDIT_CASE_FILE 12 行、AUDIT_FACT 107 行、AUDIT_QUOTE_LINE 9 行，执行 attempts=1、ROW_VERSION=53。107 条候选均为 SOURCE_VERIFIED；这表示原文关联/类型/角色检查通过，不等于 107 项合规结论。

- `01_办公终端及打印设备采购需求申请书.docx`：EXTRACTED，16 条事实，原生 PDF/DOCX 文本解析。
- `02_办公终端采购技术需求表.pdf`：EXTRACTED，0 条事实，原生 PDF/DOCX 文本解析。
- `03_办公设备采购实施计划.docx`：EXTRACTED，4 条事实，原生 PDF/DOCX 文本解析。
- `04_办公设备更新项目预算批复.pdf`：EXTRACTED，5 条事实，原生 PDF/DOCX 文本解析。
- `05_办公终端及打印设备采购招标文件.docx`：EXTRACTED，16 条事实，原生 PDF/DOCX 文本解析。
- `06_办公设备采购评审办法.pdf`：EXTRACTED，2 条事实，原生 PDF/DOCX 文本解析。
- `07_澄川星河办公设备有限公司报价单.pdf`：EXTRACTED，17 条事实，原生 PDF/DOCX 文本解析。
- `08_澄川明川数字设备有限公司报价单.pdf`：EXTRACTED，17 条事实，原生 PDF/DOCX 文本解析。
- `09_澄川启衡信息设备有限公司报价单.pdf`：EXTRACTED，17 条事实，原生 PDF/DOCX 文本解析。
- `10_办公设备采购文件澄清答复.docx`：EXTRACTED，4 条事实，原生 PDF/DOCX 文本解析。
- `11_办公设备采购中标通知书.docx`：EXTRACTED，6 条事实，原生 PDF/DOCX 文本解析。
- `12_办公设备采购履约验收方案.docx`：EXTRACTED，3 条事实，原生 PDF/DOCX 文本解析。

第 02 份全文读取和提取调用已经结束，但未返回当前采购事实 schema 可用的候选，事实数为 0；不能把 EXTRACTED 理解为已经完成技术要求审查。全批本次只执行询比价，其余五模块明确 NOT_EXECUTED。

全部文件的 HTTP 原文段/事实证据见 [browser-dm8-actual-evidence.json](E:/audit-agent-new/.runtime/budget-home-e17-20261014/docs/automatic-procurement-phase2-20261027/evidence/browser-dm8-actual-evidence.json)。第一轮实际模型案件在 Java 重启后内容完全一致，12 原文件经 HTTP 下载的 SHA-256 均与输入文件匹配，见 [dm8-actual-reopen-originals.json](E:/audit-agent-new/.runtime/budget-home-e17-20261014/docs/automatic-procurement-phase2-20261027/evidence/dm8-actual-reopen-originals.json)。本批 PDF 有原生文本，本次不构成真实 OCR 验收；29 OCR 新链路继续标记 REMOTE_E2E_PENDING_EXECUTION。

## 9. 询比价与报告结果

预算 222600 元；中标 212000 元。报价及明细：

- 澄川星河办公设备有限公司：212000 元；办公终端 40 × 4200 = 168000 元；多功能打印设备 4 × 6800 = 27200 元；安装调试及数据迁移 1 × 16800 = 16800 元。
- 澄川明川数字设备有限公司：216400 元；办公终端 40 × 4300 = 172000 元；多功能打印设备 4 × 6900 = 27600 元；安装调试及数据迁移 1 × 16800 = 16800 元。
- 澄川启衡信息设备有限公司：219800 元；办公终端 40 × 4350 = 174000 元；多功能打印设备 4 × 7000 = 28000 元；安装调试及数据迁移 1 × 17800 = 17800 元。

每家 3 条、合计 9 条；单行数量×单价、每家明细总额与报价总额均一致。报价均值 216066.67 元、标准差 3193.0480、变异系数 0.0148。三家均未超预算。既有统计规则得到 WARNING/报价较集中，这只是统计线索，不是围串标认定或正式中标推荐。

案件已经自动生成结构化报告并持久化、可经 HTTP 和页面查询。本期没有另行实现 Word/PDF 排版导出，也没有执行其余五模块；不能将其状态描述为“没有风险”。

## 10. 应用中断和重启恢复

真实停止并重新启动独立 Java 进程，未靠手工调用 Worker 推进：

- 已完成案件：重开内容与重启前一致；原文可下载。
- QUEUED：上传后在长扫描间隔下终止进程，新进程启动后自动发现并完成 12 文件案件。
- CALLING：在模型测试 gate 阻塞期间终止进程；仅在测试启动器注入 +1860 秒 Clock 偏移模拟 30 分钟租约到期，没有改 DM8 租约数据。新进程保留文档 UNKNOWN/EXECUTOR_LOST_EXTERNAL_OUTCOME_UNKNOWN，没有重放不确定模型调用，案件为 DATA_INSUFFICIENT，不冒充成功。

最后一种使用 Mock 模型和模拟时钟，不是实际等待 31 分钟，也不是对真实模型中断后的远端结果作保证。正常业务租约仍为 1800 秒。证据见 [restart-recovery.json](E:/audit-agent-new/.runtime/budget-home-e17-20261014/docs/automatic-procurement-phase2-20261027/evidence/restart-recovery.json)。最终 Catalog 快照中没有遗留 QUEUED/RUNNING/UPLOADING 案件。

## 11. 真实浏览器验收与前端修复

Playwright 驱动真实 Chromium、真实 DOM、真实 Session/CSRF 和 HTTP；并非仅 npm build。登录后从采购审查页选择 12 PDF/DOCX，完全未填写项目编号、预算或供应商。实际记录到 RUNNING→COMPLETED 以及已提取 0 至 12 的进度变化。

处理中刷新恢复同一案件；完成后自动显示三家报价及明细，查看逐字原文证据和下载原文件成功；完成后再次刷新恢复同一案件。另上传伪装 PDF，真实 HTTP 400，页面显示错误、保存原操作键；恢复查询断网时保留相同键且不显示成功结果。

本期修复了恢复查询异常被当成损坏 UI 状态吞掉、保存案件不在最近列表时无法重开，以及新上传失败仍显示旧案件成功报告的问题。没有改变正式采购表单或审批流程。

见 [browser-acceptance.json](E:/audit-agent-new/.runtime/budget-home-e17-20261014/docs/automatic-procurement-phase2-20261027/evidence/browser-acceptance.json)、[browser-processing.png](E:/audit-agent-new/.runtime/budget-home-e17-20261014/docs/automatic-procurement-phase2-20261027/evidence/browser-processing.png)、[browser-completed-evidence.png](E:/audit-agent-new/.runtime/budget-home-e17-20261014/docs/automatic-procurement-phase2-20261027/evidence/browser-completed-evidence.png)、[browser-original-evidence.png](E:/audit-agent-new/.runtime/budget-home-e17-20261014/docs/automatic-procurement-phase2-20261027/evidence/browser-original-evidence.png)、[browser-failed-upload.png](E:/audit-agent-new/.runtime/budget-home-e17-20261014/docs/automatic-procurement-phase2-20261027/evidence/browser-failed-upload.png)。页面未捕获到未处理 JavaScript 异常。

## 12. 最终测试结果与历史失败分类

最终执行 Java 17 下的完整命令（独立构建目录防止清理正在验收的类路径）：

```powershell
.\mvnw.cmd clean verify -Daudit.build.directory=<backend/target-phase2-final>
```

3196 项：3161 passed、9 failures、5 errors、21 skipped，耗时 6 分 30 秒，**BUILD FAILURE**。14 项失败名称集合与一期基线完全一致，没有新增自动案件失败；不能把编译成功写成全仓 verify 通过。见 [full-clean-verify.log](E:/audit-agent-new/.runtime/budget-home-e17-20261014/docs/automatic-procurement-phase2-20261027/evidence/full-clean-verify.log)、[final-verification-summary.json](E:/audit-agent-new/.runtime/budget-home-e17-20261014/docs/automatic-procurement-phase2-20261027/evidence/final-verification-summary.json)。

按主要原因互斥分类：

- A 新自动采购架构：0。
- B 共享解析/项目分类：4；AutomaticProjectFileClassificationServiceTest 1 项，ProcurementMaterialClassifierTest 3 项，涉及材料化交互与 BIDDER/QUOTATION/TENDER/OTHER 分类契约。
- C 原业务行为/预期：5；旧批次 API 1 项和旧批次 Service 4 项，涉及 READY/INCOMPLETE、COMPLETED_WITH_PLACEHOLDER、上传是否触发旧分析及报告交互。
- D 测试代码/Mock seam：5；旧批次 Service 4 项 NPE 的 recordFieldExtraction Mock 未提供返回源记录，旧报告快照 1 项缺少可重读的 Mock 报告。它们归属旧业务测试，仍需独立任务确认测试契约；没有发现可归因于真实 DM8 连接环境的这 5 项错误。

B/D 同时关联旧模块，不表示这些模块无风险。新增链路不调用上述旧分类器或旧批次编排；对实际共享 DocumentTextPort、金额解析/角色校验的影响，已由真实 12 文件回归覆盖。没有删除失败测试、改预期或改评分规则来制造通过。

自动案件 local/HTTP/事实策略/仓储共 18 项已执行通过，实际模型 JUnit 场景 1 项默认跳过；真实 DM8 5 项在全仓默认跳过，另在隔离环境单独启用且 5/5 通过。实际模型全链验收由上述 curl 和浏览器场景独立证明，不和默认跳过项混淆。

前端 `npm run build` 通过；有原有大 bundle 提示。Python 环境缺少 pytest，未安装系统软件；使用现有 Python 3.11 直接执行两个相关测试文件的 4 个无 fixture 测试函数，4/4 通过，见 [python-policy-tests.log](E:/audit-agent-new/.runtime/budget-home-e17-20261014/docs/automatic-procurement-phase2-20261027/evidence/python-policy-tests.log)。它不能替代 Python 全仓 pytest 验收，本期 Python 生产源码没有再修改。

## 13. 本次变更和剩余未验证项

修改的 7 个一期源码/SQL文件：

- `backend/src/main/java/com/fuquan/audit/integration/dm8/AutoCaseMapper.java`。
- `backend/src/main/java/com/fuquan/audit/integration/dm8/Dm8AuditCaseRepository.java`。
- `backend/src/main/java/com/fuquan/audit/procurement/autocase/AuditCaseController.java`。
- `backend/src/test/java/com/fuquan/audit/procurement/autocase/AutomaticCaseHttpTest.java`。
- `backend/src/test/java/com/fuquan/audit/procurement/autocase/Dm8AuditCaseRepositoryTest.java`。
- `db/dm8/PROCUREMENT__automatic_case_v1.sql`。
- `frontend/src/features/procurement/AutomaticCasePanel.tsx`。

另新增真实 DM8 五项测试、独立 HTTP/Worker/真实库验收启动器，以及 tools/automatic-case-phase2 下 13 个验收工具源文件。完整路径、二期前/后 SHA 与新增清单见 [phase2-changed-source-manifest.json](E:/audit-agent-new/.runtime/budget-home-e17-20261014/docs/automatic-procurement-phase2-20261027/evidence/phase2-changed-source-manifest.json)；差异见 [phase2-only-source.diff](E:/audit-agent-new/.runtime/budget-home-e17-20261014/docs/automatic-procurement-phase2-20261027/evidence/phase2-only-source.diff)。证据和报告不计入上述源码文件数。

未验证或未完成：完整跨 schema 备份/恢复；生产身份与正式审计适配器完整集成；客户制包迁移与代码/DDL 回滚实演；原业务库新增表落地；生产部署；真实扫描 OCR；TongWeb/TongRDS/TongHttpServer 兼容性；其余五模块自动执行。已有隔离测试通过不能替代这些事项。

## 14. 收尾与是否进入后续阶段

验收后本地 19121/19131/19132/15288 监听均已停止；只向本次新建、PID 与 ini 路径严格匹配的 DM8 进程发送 SIGTERM，独立数据目录和受限备份保留。原 `audit-ai.service`、`audit-backend.service`、`DmServiceAUDITDB.service` 实测仍在运行，未向原服务发送停止信号，见 [finalization.json](E:/audit-agent-new/.runtime/budget-home-e17-20261014/docs/automatic-procurement-phase2-20261027/evidence/finalization.json)、[local-finalization.json](E:/audit-agent-new/.runtime/budget-home-e17-20261014/docs/automatic-procurement-phase2-20261027/evidence/local-finalization.json)。

**当前结论：隔离真实 DM8 上的自动询比价技术切片验收通过；暂不进入剩余五模块迁移。** 先取得包含跨 schema 依赖的完整可恢复备份，再独立处理历史失败及正式环境部署/审计集成门槛。本次到此停止，无生产部署、无生产开关启用、无其他五模块开发。
