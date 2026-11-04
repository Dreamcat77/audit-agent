# 本机合同测试日常启动

仅在指定集成工作树中使用。PowerShell 执行策略仅对当前子进程设置，不改变系统策略：

```powershell
Set-Location D:\code\audit_agent_1D\.worktrees\integration-pre-main-20261010
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/local/start-contract-test.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/local/status-contract-test.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/local/stop-contract-test.ps1
```

启动检查完整验收 SHA、构建/源码指纹、私有配置身份、真实 DM8/Redis/模型和两个合同 Worker，成功后打开浏览器。缺少完整验收回执会明确阻断，不能把当前 HEAD 自动认作已验收版本。停止仅针对本轮 PID ledger 固定进程，不停止 DM8、Redis或其他工作树。

## 尚未完整验收的候选

候选可以供浏览器检查，但不代表已完成语义审查或最终报告验收。仅复用负责人已构建的 manifest 身份，不从 HEAD 猜测运行版本、不自行 Build、不创建 validated pin：

```powershell
$contractCandidateManifest = Get-Content .runtime/contract-repair-test/build-manifest.json -Raw -Encoding UTF8 | ConvertFrom-Json
$contractCandidateSha = $contractCandidateManifest.sourceSha
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/local/contract-test.ps1 -Action Start -ExpectedSha $contractCandidateSha -PortableRedisConfig .runtime/contract-repair-test/redis/launch-receipt.json
if ($LASTEXITCODE -eq 0) { Start-Process 'http://127.0.0.1:5173/' }
```

此命令仍执行原私有配置 literal allowlist、源码/制品、DM8 Catalog、Redis、模型列表和两个 Worker 门禁；不自动上传或发起审核。源码与已构建候选不符、配置变化或外部依赖不可达时保持阻断，不能靠改 manifest 或模型地址绕过。查看和停止仍使用上面的 Status/Stop 命令。正式每日 Start 只有完整验收回执和 pin 都通过后才放行。

本机访问地址为 http://127.0.0.1:5173/ 。`Start-Process` 的参数必须保持代码块中的纯 URL；不要把 Markdown 链接语法粘进引号。

### R2：显式本机 OBSERVE 候选与切回 STRICT

OBSERVE 只对负责人核对过的合成项目生效，默认仍为 STRICT。它仅把合同语义金额文字门禁降为警告，不改变 F02、已确认金额、来源、类型、覆盖、fencing、UNKNOWN 或预算。旧 `AUDIT_MODEL_DEMO_RELAX_BREACH_MONETARY` 不是此功能，不能自行改为 true。

以下启动信息由负责人在真实构建和切换后写入忽略目录；不是私有凭据模板。使用之前先 Status。服务已运行时不能通过改父环境或重复 Start 切换模式，需按原 Stop 受控重启，数据库和文件不恢复、不清理：

```powershell
$contractManifest = Get-Content .runtime/contract-repair-test/build-manifest.json -Raw -Encoding UTF8 | ConvertFrom-Json
$contractObserve = Get-Content .runtime/contract-repair-test/report-quality-closeout-20261019/r2-observe/candidate-start.json -Raw -Encoding UTF8 | ConvertFrom-Json
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/local/contract-test.ps1 -Action Start -ExpectedSha $contractManifest.sourceSha -SemanticMoneyMode OBSERVE -SemanticObserveProjectIds ($contractObserve.projectIds -join ',') -SemanticObserveWindowPath $contractObserve.windowPath -PortableRedisConfig .runtime/contract-repair-test/redis/launch-receipt.json
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/local/status-contract-test.ps1
```

窗口文件由负责人在全部准备完成、首次正式新 Run 前独占创建一次，采用明确记录的保守提前起点；不能由每日 Start、重启、日期变化或改名重置。启动本身不发推理。窗口到期后 OBSERVE 新推理应拒绝；已持久结果仍可读取。延续联调须沿原窗口剩余额度，不自行覆盖或延长文件。页面、每项/综合复制和正常 HTML 都必须保留联调参考标识，不能将 OBSERVE 当严格验收。

切回严格模式不会改写旧 OBSERVE Run、警告或报告，也不会取消正在执行的外部请求。先确认当前 Run 没有在途/UNKNOWN；不确定时保留现场，不能把 Stop 当作请求未发出的证明。随后执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/local/stop-contract-test.ps1
$contractManifest = Get-Content .runtime/contract-repair-test/build-manifest.json -Raw -Encoding UTF8 | ConvertFrom-Json
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/local/contract-test.ps1 -Action Start -ExpectedSha $contractManifest.sourceSha -SemanticMoneyMode STRICT -PortableRedisConfig .runtime/contract-repair-test/redis/launch-receipt.json
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/local/status-contract-test.ps1
```

STRICT 启动不接受观察项目/窗口参数，且会清除继承的观察配置，避免静默宽松运行。Status 的模式取自已验证所有权的 PID ledger；业务页面/复制/HTML 的模式则来自各 Run 冻结且持久的策略，而不是当前环境。已验收每日 Start 仍要求原完整回执与 validated pin；OBSERVE 完成不能创建此 pin。

### 负责人专用：三块检查点重启验收

普通每日启动不启用此功能。仅显式候选冷启动可传 `-SemanticBoundaryProofPath`，目标必须是本工作树 `.runtime/contract-repair-test` 内、尚不存在的 `semantic-boundary-*.json`。已运行服务或 `-Validated` 模式会拒绝该参数，不能把普通运行误认作已启用屏障。

前三个逻辑 ANALYZE 的最新结果提交 VERIFIED，且没有 SENT/UNKNOWN/调用或时间预留时，Java 写入只含身份、指纹、预算和三个结果哈希的独立回执，然后在下一次预留/外呼前暂停。现有 heartbeat 继续。绝对停止点在进入时固定，不超过90秒或原计划截止前2秒；过期不会自动放行或重发。此回执不是DM8验收结论：负责人必须核对当前库内同一owner/fence、有效lease、零在途和结果hash，再通过原Stop停止。回执异常或中断保留已提交检查点，不伪造provider FAILED。

受控重启不再传该参数，等待正常lease恢复；必须证明同一Attempt/Plan、原deadline和预算、已验证块不再次调用。不要修改时钟、lease、数据库状态或复用旧失败计划预算。该辅助不改变模型策略、业务判断或既有两表结构。

## V2 最终八项与浏览器验收

集成负责人先完成正式 Run 和正式报告，再使用 `tools/local-test/v2-final-browser-acceptance.mjs` 核对现有结果。默认执行只检查本机依赖，不打开浏览器、不登录、不调用 HTTP 或模型：

```powershell
node tools/local-test/v2-final-browser-acceptance.mjs
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/local/contract-test-launchers.tests.ps1
node --test --test-isolation=none tools/local-test/test_v2_final_browser_acceptance.mjs
```

真实验收使用本轮清单中的实际 SHA、Project、Contract、Run、Report 身份；下列变量由负责人从本轮回执赋值，不替换成历史失败 Run 或旧报告。`$v2Scenario` 仅允许 `V2-01`～`V2-06`：

```powershell
node tools/local-test/v2-final-browser-acceptance.mjs --execute --expected-sha $finalCandidateSha --scenario $v2Scenario --project-id $projectId --contract-id $contractId --run-id $runId --report-id $reportId --clipboard native
node tools/local-test/v2-final-browser-acceptance.mjs --execute --expected-sha $finalCandidateSha --scenario $v2Scenario --project-id $projectId --contract-id $contractId --run-id $runId --report-id $reportId --clipboard denied
```

浏览器使用临时会话，负责人在可见窗口手动登录已授权测试账户，并在退出后再次手动登录。工具不接收密码参数、环境变量或文件。请求门禁只放行读取和登录/退出，禁止创建 Run、生成报告或其他业务写入。它核对同候选 build manifest、PID ledger、两个 Worker 与检查点开关；实际 PID、当前制品指纹、DM8 结果和模型账本仍由负责人独立验收。

原生模式逐一执行综合简版、综合完整明细、八项各自简版和详版，共 18 次真实复制，并通过系统剪贴板读取和 `Ctrl+V` 粘贴到临时文本框逐字核对，结束时尽力恢复原剪贴板。拒绝模式必须先证明浏览器原生权限确实 denied，再核对只读全选回退；不支持真实拒绝时记为未验证，不采用 Clipboard monkeypatch。两种模式分别留回执，回退通过不冒充原生复制通过。

工具仅接受已完成且绑定一致的八项投影和 AVAILABLE v3 正式报告，比较页面/复制/HTML 的同 Run 语义、实际下载字节，以及刷新和重登录后的不变性。成功产物保存在 `.runtime/contract-repair-test/v2-final-acceptance-20261017/<场景>-<Run>-<时间>-<模式>/`：八大项最终结果 Markdown/JSON、可复制简版、完整明细、正式报告 HTML 和浏览器回执。失败会记录明确的失败回执；只有完整落盘的 `browser-receipt.json` 才能作为通过依据，中途残留文件不表示验收成功。正文来自真实持久结果及正式报告 API，不读取 truth 文件。该浏览器回执本身不签发 `validated-sha.txt`，也不代替真实证据原文跳转、恢复、负载或 DM8 验收。

## 启动运行约束

便携 Redis 仅按本轮哈希固定的 loopback 配置启动，不安装系统服务、不清空缓存。睡眠或 WSL 退出后如 Redis 不可用，日常启动可重新启动该专用实例；Session 丢失只需重新登录，不改变 DM8 业务事实。

文件接收后异步排队；OCR 为 CPU1、MKLDNN 关闭、单并发。不要把 202 接收回执当作 OCR 或审查完成。

本轮新候选通过 `AUDIT_CONTRACT_SEMANTIC_CHECKPOINTS_ENABLED=true` 显式启用语义检查点。应用自身默认仍为 false，保留 legacy/local；候选脚本拒绝 false 或非布尔配置，Build manifest 记录开关与唯一 DDL 文件 SHA256。StartPreflight 必须逐一验证 `CONTRACT_SEMANTIC_PLAN`、`CONTRACT_SEMANTIC_UNIT` 的列、键、CHECK、FK 与索引，缺少任何一张表或定义不符均拒绝启动新候选。没有真实检查点验收的候选不能据此标成 semantic accepted。

Build 不得覆盖运行中的 canonical JAR：已有 Backend PID 或 8888 listener 会阻断。可以先在独立 `audit.build.directory` 验证；由集成负责人完成停写与备份后，再走公共 Build/Start。Start 的 ExpectedSha 必须来自已提交且干净的同工作树，源码/JAR/前端/Python 指纹均匹配，不能拿新 HEAD 冒充旧运行进程。Status 分别报告源码构建和 ledger 运行身份；Stop 仍按原 PID、创建时间、可执行文件及命令哈希验证所有权，不会因工作树已改动而把另一实例当成本轮服务。

## 本轮语义两表的受控维护

入口是 `tools/local-test/maintain-semantic-checkpoints-local.ps1`，唯一 DDL 是 `db/dm8/CONTRACT__contract_semantic_checkpoints.sql`。它只执行 PLAN 表、UNIT 表、索引三步，不执行 Word97 已完成的 DDL，也不调用 reset/restore。DryRun 是全库只读备份，包含账户、长期审计及业务事实，仅允许在集成负责人明确调度且全部应用停写后执行；准备脚本或离线测试不等于已备份、已迁移或已验收。

集成负责人使用已审阅、干净且精确的候选 SHA，依次安排以下操作；示例变量需取本轮实际回执，不能照抄历史 SHA 或历史备份目录：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/local-test/maintain-semantic-checkpoints-local.ps1 -Mode DryRun -ExpectedSourceSha $candidateSha -WritersStopped
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/local-test/maintain-semantic-checkpoints-local.ps1 -Mode Apply -ExpectedSourceSha $candidateSha -BackupDirectory $newBackup -WritersStopped -ApplyApproved
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/local-test/maintain-semantic-checkpoints-local.ps1 -Mode Verify -ExpectedSourceSha $candidateSha -BackupDirectory $newBackup
```

必须使用 DryRun 生成的同一 `.runtime/contract-repair-test/semantic-maintenance/<new-id>` 目录。目录仅当前用户与 SYSTEM 可访问，typed 归档逐表重开验证 hash、行数、精确值与列信息；日志绑定原始源码、DDL、数据库身份哈希和备份。真实身份白名单与环境仍由既有私有文件的 literal parser 读取，不输出账号、URL、密码或原始进程命令。

停写门禁同时检查本机进程与 DM8 双向 TCP socket 归属：其他工作树 Java backend、未知 Java JAR 或其他数据库客户端会拒绝操作，工具不会自动停止它们。应由负责人确认并协调相关实例；不能只停止本轮三个 listener 就认定同库无人写入。

DM8 DDL 可能隐式提交。超时或崩溃后保留同一备份及 hash-chain journal，重新读取 Catalog，先核实实际是旧 prefix 还是已提交的下一步；首次重开仅记录 RECONCILED 并停止，负责人复核后才继续同一目录。没有两张表各自的真实 `SEMANTIC_CATALOG=...EXACT_COLUMNS_KEYS_CHECKS_VERIFIED` 和最终索引回执，不得跳过门禁或发布完整 PASS。

恢复只允许审阅后的前向修复。不得 DROP 新表、清空检查点或把迁移前全库备份覆盖到迁移后实例；那会删除后续业务与审计事实。Apply/维护 Verify 在发现新表已有事实或旧表有变化时主动阻断，保留现场。恢复应用写入后，使用只读 runtime Catalog 检查，不把旧迁移快照的“所有行未变”校验当作在线数据恢复手段。FileStorage 继续使用既有受控存储及本轮独立回执，不由这个数据库工具覆盖或回滚文件。

清理工具：`tools/local-test/reset-review-test-data.ps1`。旧备份、生成 SQL、恢复说明和完整证据留在忽略的 `.runtime/contract-repair-test/`。不得恢复旧 51 条缺失文件引用、删除旧文件目录、关闭外键或通过删除新表绕过恢复目录门禁。每日启动不会再次清库。
# Semantic maintenance: one Root-approved JDT nonwriter proof

The semantic maintenance wrapper still rejects unknown Java JARs, hidden
`@argfile`/module launchers, application listeners, recorded services and other
DM8 client sockets. It does not stop an IDE. A single already identified VS Code
Java language server may be exempted only with Root's explicit approval of its
exact observed identity, not a general user allowlist.

Root may import **only function definitions** from the reviewed maintenance
wrapper/Word97 guard script, then call `Get-SemanticJdtNonwriterProof` with the
identified CIM process plus complete fresh CIM/TCP snapshots. Do not dot-source
the executable wrappers. The function reads the actual files/signature and
rechecks CIM, file hashes and all owned TCP states; it returns no raw command.
Root must inspect and approve the single returned proof before creating, with
create-new semantics and the existing restricted owner/SYSTEM ACL, exactly:

`.runtime/contract-repair-test/semantic-maintenance/jdt-nonwriter-proof.json`

The fixed schema is `SEMANTIC_JDT_NONWRITER_V1`. The executable must be in the
same exact Red Hat Java extension's `jre` directory as the Equinox launcher.
The exact JDT application/product flags, child/parent PID and creation times,
executable/launcher/command hashes, Microsoft-valid signed Code parent and zero
owned TCP connections are mandatory. Observation timestamps are not identity.

DryRun freezes this file's SHA256 into `writers-stopped.properties`. Every
maintenance stage rereads the approval and independently recollects the live
identity; drift, missing approval, unknown observations, changed process/file/
parent/signature or any child TCP connection fail closed. Changing this approval
after DryRun cannot authorize another identity against the same backup. No
automatic refresh or approval is provided. The JDBC helper's independent sole
DM8-client/socket proof remains mandatory.

Offline regression:

`powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/local-test/semantic-jdt-nonwriter.tests.ps1`
# 固定前驱 Catalog reader 修订（本机例外，不是通用 source 忽略）

仅用于 f69be8e 的语义首表已提交、原 journal 仍为 completed=0/intent=1 的本机备份。DM8 把 `IS NOT NULL` 显示为 `NOT (... IS NULL)`，并自动生成默认聚集及外键虚索引；reader 逐项证明其精确身份，SQL 本身不变。原 snapshot、writers proof、备份数据和 journal 前缀均不可改写。

修订候选须清洁提交、同一备份目录、同一 DDL，并通过离线反例和独立复审。以新完整 SHA 执行原维护工具：

1. `-Mode Verify -CatalogReaderRevisionApproved`：在原 lock 内核实精确 prefix=1、PLAN 空、旧 Catalog/所有旧行不变，签发 CREATE_NEW reader 回执后停止，零 DDL。门禁码为 `SEMANTIC_READER_REVISION_CREATED_REOPEN_RECONCILE_REQUIRED`。
2. 同目录、同 SHA 再执行 `-Mode Verify`：只追加 RECONCILED 后停止，零 DDL。不得把实际 prefix=0 当作可重放首表。
3. 同目录、同 SHA 执行 `-Mode Apply -WritersStopped -ApplyApproved`：仅执行剩余 UNIT/index，完整验证后再启动候选。

回执绑定原规范目录、snapshot/writers SHA、journal 前缀字节/hash/tip/sequence、固定旧 source/HEAD、修订 source/HEAD、DDL。任一变化、未知前驱、工具回退或扩大维护依赖变化均拒绝。不能换备份、覆盖回执、删表重来；回退应用也不自动删除新表或覆盖后续事实。
