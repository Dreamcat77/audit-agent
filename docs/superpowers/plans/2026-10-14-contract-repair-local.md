# Local Contract Repair Implementation Plan

> Execution: native coordination and focused independent domain agents, with no worktree creation and no concurrent DM8 mutations. Continue without confirmation between tasks, as expressly authorized by the user.

**Goal:** Reset only local test business instances and deliver durable async contract intake, bounded OCR/long facts, consistent eight-item opinions, and tested daily local scripts.

**Architecture:** Reuse FileStorage, short transaction coordinators, DM8 ports, immutable parse/run facts and current human authority. Add narrow jobs and versioned opinions without expanding business scope.

**Tech Stack:** Java 17/Spring Boot/MyBatis/DM8, existing Python 3.11/FastAPI/PaddleOCR, React/Vite/Node.

**Spec:** `docs/superpowers/specs/2026-10-14-contract-repair-local-design.md`

## Global constraints

- Only the specified existing integration tree is writable; private legacy config is literal allowlist input, never executed or logged.
- Protected authentication, configuration, supplier master and login audit stay identical during reset. No historical object directory is removed.
- No FK disable, cascading wipe, destructive migration, remote push or customer environment.
- BigDecimal money, immutable uncertain operation keys, UNKNOWN fail closed, no long OCR/model database transactions.

## Review focus

- Protected foreign-key dependents or unclassified tables block deletion.
- Unknown backup/publication/commit state cannot be reported as successful or retried under a new key.
- Late lease owners cannot publish parse results over a newer attempt.
- Unicode paragraph boundaries and cross-batch evidence must not lose tail facts.
- Unsupported rules and pending external checks never become a normal/compliant opinion.

## Tasks

### 1. Reset tooling and local catalog

- [ ] Implement and adversarially test JDBC catalog classification, FK order, typed backup/restore and protected fingerprint gates in `tools/local-test`.
- [ ] Inspect actual tables, constraints, triggers, counts and idempotency resource identities; produce ignored inventory and auditable SQL.
- [ ] Back up, validate DryRun, Apply in one transaction, verify protected fingerprints and zero business instances.
- [ ] Verify fresh user-created storage and set its pointer through target-tree tooling.

### 2. W4 bounded fact extraction (independent ownership: factai, contract-fact adapter/protocol and Python fact/gateway files)

- [ ] RED: tail coverage at 7679/7680/7681/8235 codepoints, paragraph fragmentation, scope/batch audit collision and business refusal classification.
- [ ] Implement complete bounded batches with exact evidence/row/scope authority, unique audit ordinals and compatible recovery.
- [ ] Run focused Java/Python tests; hand off migration catalog requirements, without applying migrations.

### 3. W5 opinions/report (independent ownership: closeout/report, business category/opinion UI helpers)

- [ ] RED: honest incomplete/unsupported status, typed amounts, item/overall/report consistency, old frozen report and clipboard rejection.
- [ ] Implement deterministic versioned shared projection and manual copy fallback.
- [ ] Run focused Java/frontend tests and hand off exact report version/API needs.

### 4. W1–W2 durable intake and parsing

- [ ] RED: short acceptance while parser blocked, ZIP children independent, same-key replay, busy OCR queued, stale lease, restart/open recovery and mixed page/DOCX order.
- [ ] Implement DM8 job/attempt repository, transaction CAS, polling worker, GET/retry state and backward-compatible acceptance route using existing ingestion/storage/archive logic.
- [ ] Preserve OCR capacity one with bounded admission/time budget; implement per-page PDF routing and document-order parsing.
- [ ] Verify real DM8 additive catalog and Windows storage semantics after backup.

### 5. W3 frontend recovery

- [ ] RED: accepted async upload does not lock navigation; GET restores after remount; uncertain operation identity cannot silently reset; two project queues stay separate.
- [ ] Adapt upload operation and supplement helpers to acceptance/state APIs; show honest queue/parse/manual confirmation statuses.
- [ ] Run actual frontend behavioral tests and build.

### 6. W6 runtime/acceptance and daily scripts

- [ ] Fix literal AST reading in target-tree runner, discover/launch scoped existing Redis and verify allowed synthetic model protocol.
- [ ] Run Maven clean verify, supported Python full pytest, existing frontend full tests and build.
- [ ] Local commit, exact-SHA build, start both workers, verify three-service E2E, queue/restart/slow OCR/A-mini/report behavior.
- [ ] Produce reusable start/status/stop wrappers, backup restoration instructions and truthful acceptance evidence; write validated SHA only when every required gate passed.
- [ ] Final independent code review and regression fixes; leave services running and give browser handoff.

## 20261016 补充：P2 两表检查点实施与本机维护

本轮用户明确批准的新增结构仅为执行计划和工作单元两表；不修改已有表，不增加第三表，不改历史 Run/Attempt/报告，不把失败/UNKNOWN 直接改成成功。参照 [设计补充](../specs/2026-10-14-contract-repair-local-design.md#20261016-补充p2-语义执行计划与工作单元持久层) 与唯一 [两表 DDL](../../../db/dm8/CONTRACT__contract_semantic_checkpoints.sql)。本子包仅提供源码、DDL 与离线测试；Apply 和真实长链路另由本机已授权维护窗口执行，不能因文件已生成而勾选部署验收。

### 持久层与执行集成

- [ ] 核对实际 Catalog 中父 Run/Step/Attempt 主键、列类型、FK 和新表不存在；固定源 SHA、策略 manifest、应用制品与 DDL SHA256。
- [ ] 通过 `SemanticCheckpointCoordinator.openOrClaim/reserveAndMarkSent/completeUnit/markUnknown/markComplete` 集成；外呼在短事务之外，下一次外呼前必须保存上次单元结果。
- [ ] 计划按 Step 唯一，保留 ORIGINAL/CURRENT_ATTEMPT_ID；跨显式新 Attempt 不重置输入/策略、deadline、调用/修正/时间 used/reserved。不同 input/evidence/provider/model/prompt/schema/planner 不能复用。
- [ ] 领取只允许无 SENT/UNKNOWN；未确认 SENT 持久化 UNKNOWN，预留不能清零或自动重发。三个已验证分析块重启后只读恢复，不新增模型调用、不复制旧调用审计到新 Attempt。
- [ ] 验证 Run→Plan→Attempt 锁顺序、父 RUNNING/live lease/currentAttemptNo、owner/fence/version CAS；heartbeat 只为相同当前 owner/fence 延续 Plan 租约，新 owner 必须领取新 fence。
- [ ] 完成结果与现有调用审计在同事务提交；重复相同完成不双扣/双记，不同 JSON/hash/usage/provider 身份拒绝；审计写失败时预算和结果整体回滚。
- [ ] 验证 COMPLETE 后正式 Step 发布前崩溃的转 owner/fence 恢复：不重调模型、不改 final checkpoint hash 或预算。明确 FAILED 只允许真实旧父 Attempt 已 FAILED 后的受控新 Attempt 以新 executionNo 有限修正；同 Attempt FAILED 和任何 UNKNOWN 都不能自动重发，保护历史 Run 的禁重试约束不变。
- [ ] ANALYZE、REPAIR、SYNTHESIZE 使用同 Plan 总预算。REPAIR 同 logical key 新 executionNo；只有所有必需单元最新 VERIFIED、最后 synthesis checkpoint JSON hash 一致且无预留/UNKNOWN、原预算有效才 COMPLETE。
- [ ] 最终 typed result 由原 Step 完成事务发布；私有 checkpoint JSON 不直接当业务结果，旧 Step/报告不覆盖。

### 停写、备份、DryRun、Apply 与 Catalog 验证

1. 明确本机维护窗口与写入边界，保存正在运行的源/制品/配置身份、当前队列与租约。暂停自己的写入 worker/入口；若无法获得停写窗口，保留 NOT APPLIED，继续离线验证，不覆盖运行中 JAR。
2. 使用现有本机 DM8 业务连接和已批准备份工具，导出父表、业务/审计/保护对象 Catalog 与可恢复数据、CLOB/BLOB、行数及 SHA256；保护历史 Run/Attempt/报告和 FileStorage 内容。校验备份可读、计数与摘要，保留准确回滚范围，不创建第三个数据库状态表。
3. DryRun 只核对唯一 DDL 的两张新表、FK/唯一/CHECK/索引、标识符/类型/权限、无已有表 ALTER/DROP/DELETE、新表不存在、无未解释漂移；记录 DDL SHA 和计划 SQL。不把 DryRun 当作迁移已执行。
4. Apply 只执行批准版本的两个 CREATE TABLE 及其约束/索引。不得假设 DM8 DDL 可整体事务回滚；任一步结果不明先检查 Catalog，不能盲目重放或 DROP 清场。保存每条执行回执及失败边界。
5. 验证实际 `USER_TABLES/USER_TAB_COLUMNS/USER_CONSTRAINTS/USER_CONS_COLUMNS/USER_INDEXES`（按实际 Catalog 名称/权限适配）：两表精确字段类型、CLOB、Step 唯一、logical key/executionNo 唯一、父 FK、状态/预算 CHECK 和索引。比较旧表结构及保护数据摘要，确认没有第三表或旧结构漂移。
6. 在授权本机合成测试范围验证真实 CLOB/UTC/hash、同 Step 并发领取、唯一约束 NULL 行为、旧 fence 拒绝、租约续期、事务回滚、前三块重启不重调、SENT→UNKNOWN 不重发、总预算不洗、最终发布原子性。仅当实际命令/环境/观察结果齐备才标 VERIFIED。
7. 应用回退切回已备份候选/策略并停用新执行入口；保留两表及所有历史检查点和调用审计，不 DROP、不清库、不整库恢复覆盖维护后产生的业务。需要数据级恢复时另行核对精确受影响对象及新写入，不能把备份存在视为任意覆盖授权。

### 本子包验证证据要求

- [ ] 隔离 Maven 输出目录 `.runtime/contract-repair-test/closeout-20261016/p2-persistence-target`；保留 `p2-persistence-*` 的 RED/GREEN、最终 `clean verify` 与完整测试计数，避免共享运行中 target。
- [ ] 离线 fake 仓库测试覆盖并发、CAS、预算、未知结果、父租约续期、重复完成及审计回滚；Mapper 测试核对真实生成 SQL/CLOB/UTC 映射。离线结果不能替代真实 DM8 语义验证。
- [ ] 验证 `audit.contract.semantic-checkpoints.enabled` / `AUDIT_CONTRACT_SEMANTIC_CHECKPOINTS_ENABLED` 默认 false；仅真实 DM8 profiles 显式 true 注册执行器，启动前 SELECT-only Catalog preflight 核对列/键/精确父 FK/CHECK/VALID 索引。缺表、错误 FK、禁用 CHECK、类型宽度不符、不可读均拒启，CHECK 的 POSITION=NULL 和实际格式需用代表性 DM8 回执确认；不得绕过门禁自动建表。
- [ ] 统一状态只更新本轮既有 closure；未执行 Apply、真实 HTTP/模型、重启恢复或目标平台检查的条目明确 NOT VERIFIED，不伪造 validated pin。

### 统一收尾的执行顺序和兼容门禁

先完成 R1～R7 每项确定缺陷的 RED/GREEN 和受影响回归，再以相同候选统一验证 Java `clean verify`、Python 3.11 全集、前端全集/构建。R6 新 Run 冻结 BREACH/TERM v2；V201 新验证 Run 只复用已确认正式合同和八份材料，不重复确认、Promotion 或上传，不改旧失败 Run/Attempt。新验证操作在发送前 fsync 固定幂等键和请求摘要；未知只读发现，不换 key 冒充新操作。

受控切换前分别备份原运行 JAR/身份回执/源码归档与批准 FileStorage，停写后再作一致性快照。两表维护工具须同时通过清洁提交、JAR/PID 所有权、实际进程/同库连接、受限备份可恢复性、精确 CHECK 字面量与 Catalog 门禁；`@argfile` 等不透明启动方式不能被当成无关进程。备份准备不是已停写证明，DryRun 不是已 Apply，SQL 回滚探针不是正式业务 E2E。

候选切换后只发有原总预算的真实语义链路；第一个明确 500 停止该场景，不自动重试。按实际证据更新既有 closure 的 CODE_REVIEWED / LOCAL_TESTED / DM8_VERIFIED / MODEL_VERIFIED / BROWSER_VERIFIED 层级。全部真实链路和每日启动门禁未通过时，保留准确候选身份及阻断项，不生成 validated-sha。

首表创建后的真实 DM8 CHECK 展示差异补充：先以实库原表达式 RED/GREEN 修正两端 reader，不更改已批准 SQL；对逻辑、字面量、身份变异继续拒绝。固定前驱 reader 修订必须离线覆盖未知 source/DDL/HEAD/目录/原前缀/回执篡改和未授权均零 DDL，工具回退拒绝，合法恢复先停再续且首表执行次数不增加。原备份目录不变，显式 Verify 签发独立回执并停止 → 再 Verify 只 RECONCILED 并停止 → 最后 Apply 剩余两条并作完整 Catalog/旧数据验证。原 f69be8e 源码和运行备份不动，新候选构建仍只在已停写后进行。
### 2026-10-16 实测缺陷的有界后续执行

1. 保留已失败的新 V201 验证 Run 和首块 LENGTH 审计；只读核对 Unit/Audit 具体调用指纹与 Attempt 冻结指纹的层级差异。诊断 RED/GREEN 覆盖新策略合法重开、错配/重复、SENT/UNKNOWN、非语义三步及历史兼容，不修改原事实。
2. OCR 的线程池实测健康门禁失败独立保留。先补全预测窗口采样，再以合成保 GIL、真实 Windows own-child/Job/descendant、取消/临时文件、IPC/期限和 UNKNOWN 不重放反例验证进程边界；不把内存 ASGI 结果当真实 Paddle/TCP 通过。
3. 只加安全 provider metadata，模型/Prompt/生成长度/时限不变。未知的历史可见内容与推理用量继续 UNKNOWN；代码/策略不兼容时不重试旧 Plan、不借新 Run 洗预算。正式下一次语义执行需先证明针对性生成策略及完整覆盖，而非因诊断可读就重放失败调用。
4. 新候选统一完整测试和独立审查后，以当前真实运行制品及当前数据库事实作新备份、停止已证明归属的本机服务、再次备份并校验。无新 DDL；禁止覆盖被进程占用的 JAR。新 SHA 构建并启动后核对三个真实 listener PID、JAR/Python 输入及双 Worker，GET 原失败诊断且模型计数不增加，再进行一次原存储文件的纯 OCR 适配器负载验收。
5. 八项/复制/历史重开使用原始 HTTP wire 与真实浏览器验证；精确金额账本的 Decimal canonicalization 不得被当成 HTTP wire，以免制造 DTO 假象。未完成语义的草稿展示与最终完整报告验收分列；不生成 validated-sha。

6. 补启用的代表性真实 fixture 和只读 DM8 回归，逐项解释 canonical suite 的跳过门禁，重叠测试不相加冒充新增覆盖。只读 DM8 调度回归暴露的 Worker 交接竞态按同一 R3 收口：先 RED 证明 Future 完成后的接纳间隙、取消不退出、并发扫描、队列取消、关闭及旧令牌迟到，再以单线程/有界交接/领取前令牌 CAS 修复。完成独立审查、局部及只读 DM8 GREEN 后，再统一完整测试、清洁提交和受控制品切换。保留实际运行候选的 JAR、源码、存储和数据库备份，新增修复不能覆盖仍运行制品；没有新 DDL，不重复迁移。

7. 09aa3e7 运行观察和真实 Spring 锁存器 RED 已定位 Review 定时方法同步等待会阻塞同一调度器的文件扫描。只为既有 WorkerScanner 增加私有单线程、有界接纳和明确关闭，不定义全局 TaskScheduler、不改变其他业务调度并发。先验证文件探针在语义在途仍运行及释放后正控制，再覆盖忙碌 tick、异常释放、关闭/未开始取消和无重复 claim。保持当前服务运行做离线修复；源码冻结审查及受影响回归通过后统一完整测试与既有备份/停写/精确 SHA 切换，不能用这个离线探针冒充真实多项目 OCR/模型 E2E。

8. 独立审查确认无人读取的 FutureTask 会静默保存 Error，须先保留 class-only 日志缺失的 RED，再补窄日志并重抛 Error；验证私有 message/cause/栈不外泄、Future 异常不被改成成功、接纳可释放。此技术诊断不写业务 FAILED、不新增 Error 恢复策略；与调度隔离作为同一增量审查、回归和发布。

9. 新的同拓扑容量反例在 8 ANALYZE+S、11 次总调用计划中，仅一次离线假调用即可复现已完成 STOP 响应被检查点输出上限误转 UNKNOWN。先 RED，再用原 262144 字节硬界内的失败回执保存精确身份和实际 usage，Java 完成失败事务且不调下一块；覆盖前块原样保留、REPAIR/最终合并输出、未知调用不降级和诊断分类。与 Scanner 统一发布，不先切一个中间候选；不增加真实模型请求、不更改生成参数或已存在失败记录。

### 2026-10-17 真实综合容量收口

- [x] 保留两次 V201 正式执行及其不同失败身份；第二次的八个逻辑分析块均已持久验证，综合尚未发送。原保护 Run 不重试，已耗尽的场景额度不绕过。
- [x] 原冻结加载器、生产校验与真实检查点私有管道零外呼重放：精确复现综合输入容量门禁；公共 DTO 排除了内部证据 ID，不能用 DTO 再解析的局部尺寸代替真实综合尺寸。
- [x] RED/GREEN：完整 partialResults、显式目录列/行与精确文件身份注册表，独立逆解相等；未知 ID、同名不同身份、尾部、Unicode、不可压缩超限均有反例。仅新增明确综合子协议，原容量/预算不增加。
- [x] 真实检查点在私有 RAM 中做新编码局部验证；不把旧计划改成新策略、不生成正式结果，不冒称 MODEL_VERIFIED。
- [x] 诊断终止原因优先于早期已成功修正的调用，审计原样保留；容量与本地边界停止不再被安全映射成 UNKNOWN。
- [ ] 独立复核、完整测试、备份及受控制品切换；继续其他未耗尽额度的 V2 真实业务操作，材料语义或人工字段授权不足则单独保留限制。
- [ ] 新编码真实综合、八项、报告、浏览器及最终每日启动验收。未完成项保持 NOT VERIFIED；不生成 validated-sha。

### 2026-10-18 正式报告金额快照完整性回归

新授权的 V201 验证已完成五步及有界语义链路；正式报告首次生成仍失败。只读实库重放定位到 JSON tree 读取把冻结 BigDecimal 转为浮点或去掉 scale，导致 findings 再序列化的字节哈希变化，并非模型或渲染容量失败。

- [x] RED：金额零值、尾零、高精度及科学计数表示的冻结字节回环和真实 typed comparison 快照失败；金额篡改仍拒绝发布。
- [x] GREEN：仅让 closeout JSON reader 保留精确小数及 scale；不改原快照、已存哈希、完整性比较或历史报告状态。
- [x] 实库失败快照零写入重放：三类身份一致，原候选与修复后渲染字节完全相同；失败报告保留。
- [ ] 完整测试、现有备份/停写/新 SHA 构建启动后，通过同一已完成 Run 的正常接口生成新报告版本；不再次调用模型，不重写失败版本。
- [ ] 实测八项简版/详版、原生复制、HTML 下载及刷新/重新登录/历史重开后，继续其余 V2 场景；不提前生成 validated-sha。

### 2026-10-18 V202 全局/局部材料范围修复

- [x] 原失败检查点只读脱敏取证：实际 16 ANALYZE+S、19 次调用上限，仅用两次后证据修正失败停止；全局有正文，局部修正错报正文缺失且空锚点。保留原 Run/Plan/Attempt，不直接改状态。
- [x] 实际生产规划/校验/请求构造的 RED/GREEN：全链路 ANALYZE/SYNTHESIZE 和证据修正携带服务端全局/当前范围；不兼容或空证据修正继续拒绝，来源和限额不变。
- [x] Python 完整本地测试 714 项通过；原运行 39 份 Python 源码在改动前已逐字节备份并校验原聚合哈希。
- [ ] 独立只读审查、完整候选验证、既有备份停写和新 SHA 切换。无 DDL，旧报告与检查点保留。
- [ ] V202 仅使用剩余的一次真实修复后复验额度；V203 从尚未使用的首轮额度继续，均到正式报告与浏览器结果，不以 HTTP200 或分析块完成代替最终验收。

独立审查的两项 Important 均纳入同一修复：

- [x] 原文同形证据标记的两项 RED（未知 ID 崩溃、跨块真实 ID 冒用）转 GREEN：在原分段循环中同时规划不可变文本和实际成员，不从正文反解析授权；原文本、分段与顺序不变。
- [x] 真实修正合并路径的三项 RED（null、缺省、仅比较锚点）证明空 INSUFFICIENT 被错误放行；显式区分未声明缺失与已验证全局缺失，问题锚点或合法缺失锚点不可省略。合法可见不足、真实缺失和 INFO 路径保持 GREEN。
- [x] 九项局部测试及完整 Python 720 项通过；第一次完整运行因既有 pytest 临时目录 ACL 产生 2 失败/24 setup 错误，原日志保留，未变更全局安全或删目录；使用新专用临时目录的原断言完整重跑 720 PASS。前端原完整 771 PASS，真实 Edge 可视化依赖已显式指定。

这是原有证据安全契约的收紧实现，不删意见、补造锚点或放宽语义规则。正式复验只能绑定新源码/策略，不把先前 HTTP200/STOP 或失败修正视为新结果。

### 2026-10-18 权威 Schema 与全局缺失约束对齐

V202 修复后第二次运行的首块证据修正通过，但第 4 块缺少可接受的必检违约类别，5 次调用后停止；V202 两次额度已用尽，不第三次执行。V203 首轮的两次 HTTP200/STOP 仍声明全局已有合同正文缺失，合法修正失败后停止。原结果及审计保留，不能清除错误类别或空锚点使其成功。

- [x] 原始失败包仅在私有 RAM 中只读分类；V202 48 行正式 F02 的 52 检查全部 PASS，V203 12 行及 2000 元跨文件差异形成真实 15 PASS/1 FAIL，均逐行核对三项冻结来源证据。全量语义均仍 FAILED，不以金额检查通过代替整 Run 成功。
- [x] 实际请求 RED 证明权威 Prompt Schema 仍列出全局已存在类别作为 unavailableMaterialCategory 选项；仅收紧该字段枚举，完整保留九类意见、其他字段、原材料与证据、生成/时限/调用上限和严格类型/证据/安全校验。
- [x] ANALYZE、SYNTHESIZE 及三种既有修正 Schema：全局都存在时仅允许 null；未知全局不推断缺失；无共享 Schema 变异。15 项定向及完整 Python 726 项通过；独立只读审查覆盖 5 阶段×32 组合，无 Critical/Important/Minor。
- [ ] 既有停写备份、清洁新 SHA 构建与真实运行核验后，V203 只使用剩余一次因果修复复验；不复用旧策略检查点，不再试验探针，不重开任何旧计划。模型是否遵循新约束仍须真实完整链路验证，不能仅凭 Schema 或 HTTP200 宣告修复。
- [ ] V204/V205 仅有通用文件编号，最新映射授权范围未覆盖；业务语义范围明确前不擅自映射。V206 通知书无可证明唯一日期及当前人工归属，资料不足不能编造。
