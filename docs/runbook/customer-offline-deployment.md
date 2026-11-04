# 甲方离线部署与数据库升级手册

更新日期：2026-10-22。适用于预算、采购、合同及公共 AI/OCR 的麒麟 V10 SP3（2403）aarch64 离线环境。

## 当前更新包 Update_v24（2026-10-30）

`E:/audit-agent-new/deliverables/Update_v24.tar.gz`，99113696字节，SHA256 `b55dd21f181b916ca87f45bf76b220726e9c7b36a5a8d542c4686d7cbd635867`。

解压到新目录，进入含 `update.sh` 的目录只执行 `sudo bash update.sh`，成功看 `UPDATE=PASS`，浏览器Ctrl+F5。无需恢复JSON、不自动备份数据库/业务文件、不手动修改Nginx。安装脚本/schema/SQL/检查器与v15字节相同，无新增DDL或数据库门槛；保留现场配置、业务数据与旧应用。以下旧版本章节保留作历史，不是本轮执行步骤。

预算、采购、合同共用四方向OCR、轻量模型与低置信复读，三个入口最多512页。随包提供方向和轻量识别离线模型，不要求现场下载或升级Python/Paddle。OCR HTTP最低1900秒、共享读取总时限4小时；新合同批次6小时。字符限制和模型预算仍有限，采购专用逐页重启恢复不冒称预算/合同也具备。

本地Java3440项0失败0错误24跳过，Python候选2049通过14跳过；最终包完整性、Java17、脚本、静态字体与模型权限/哈希通过。甲方ARM64实际执行和真实模型结果仍待现场验证，26网关当前不可用。具体范围见 `docs/qa-26/v24-three-module-scans.md`。

## 当前更新包 Update_v20（2026-10-29）

更新包：`E:/audit-agent-new/deliverables/Update_v20.tar.gz`，84577306字节，SHA256：`7eb033bdb7deeed8aef0b12a24204bc38cd41222cfca10dd9fdf44c90ac115b1`。

解压到新目录，进入含update.sh的目录，一次执行：

```bash
sudo bash update.sh
```

看到`UPDATE=PASS`后浏览器Ctrl+F5。无需恢复JSON、人工准备备份或手工改Nginx；不自动备份数据库/业务文件。旧应用暂存用于代码恢复，代码回退不等于DDL回滚。

三模块结果页统一项目抬头和固定审查模块；合同八大项、正式五步及问题确认保留；改进含税金额和最高投标限价识别。包使用26已验收v20-r3同字节业务产物，包含离线OCR方向模型，保留甲方模型配置、数据库、Redis、Nginx与业务数据。SQL/schema/Java17检查器及安装脚本与v15相同，本次没有新增数据库判断或DDL。

26真实DM8/模型/OCR及Chrome109上传、结果、历史、导出、复制和确认已验证，默认80入口已切换并复验；Java3422项0失败/错误24跳过、前端843通过、Python2016通过15跳过43子测试。最终包完整性、LF、Java17、字体资源引用、权限和SHA检查通过。甲方ARM64实际执行、原材料准确率仍需现场验证。详见`docs/qa-26/v20-result-layout-validation.md`及包内`TEST-EVIDENCE.md`。未提交Git。

## 历史更新包 Update_v15（2026-10-29）

下载 `Update_v15.tar.gz` 并解压到新目录，进入含 `update.sh` 的目录执行：

```bash
sudo bash update.sh
```

成功标志为 `UPDATE=PASS`，然后浏览器 `Ctrl+F5`。自动切换应用并重启后端和AI；不需恢复记录、不需手动重启Nginx。现场模型地址/密钥/名称、数据库、Redis、Nginx及业务文件保留。无新增DDL或部署判断，部署器和数据库工具沿用v14；不自动备份数据库或业务文件，旧应用暂存仅用于代码恢复。

包大小88,657,409字节，SHA-256为 `0e8d57e70e737a5c63ddd49e3c92545ca6390842be07e39293a3091a73accce4`。内容与26已验收v15-r1产物一致，含统一解析、合同八项和Chrome109累计修复。最终包清单、脚本、Java17、字体引用及静态权限检查通过；甲方ARM64实际运行仍需现场验证。以下旧版本记录仅作历史参考。

## 2026-10-29：采购/PDF/合同现场诊断

先解压`FieldDiag_v1.tar.gz`，在其上一级目录执行：

```bash
sudo bash FieldDiag_v1/run.sh --model-probe
```

自动读取运行服务配置，收集当天及前后一天的采购/合同记录。没有重新上传、升级、迁移、重启、清库或恢复JSON步骤。最终把`OUTPUT=`对应的`FieldDiag-日期-时间.tar.gz`发回即可；`DIAG=COLLECTED`表示收集完成，各阶段是否成功在各自JSON里记录，不把不可见/超时说成“材料本身没有”。

`--model-probe`会把本地已保存采购文本的小段交给当前配置的模型，最多2次串行调用、每次180秒，导出结果只保留固定字段/关键词统计，不包含内容或实际金额；省略该参数不调用模型。禁止擅自切换到未授权模型地址。源文本、原文件和模型原始回答均不进入导出包，临时片段在私有目录处理后清理。

检查预算等固定线索是否存在于保存文本、字段是否已提取、原文核对/审查覆盖情况、OCR旋转及耗时、合同材料类型和建档/正式审核状态，并核对运行JAR/前端/AI代码哈希。最多抽查4份PDF、每份256页原生文字、35秒；扫描图片真实朝向仍需结合已有OCR日志。26实测通过不等于甲方原文件准确率已验证，不能仅凭文件名宣称八项审核通过。

## Update_v14（2026-10-29）

26默认入口已使用相同业务产物完成更新，日志`UPDATE=PASS`，backend/AI active，登录/健康/分析历史HTTP200。26使用独立x86目标测试副本；交付给甲方的tar仍为ARM64，不可混用。

新目录解压`Update_v14.tar.gz`，执行`sudo bash update.sh`，成功以`UPDATE=PASS`为准，浏览器Ctrl+F5。包SHA-256：`1ce10d2155010d4a89e0431bf285ca48fa6a772a11fdb3d8e31503ac5f65ab2f`。继承v13a旧静态资源403修复，SQL/schema/检查器及部署脚本未新增改变；不要求恢复JSON，不自动改表或重放已执行DDL。

本次是合同自动分析业务修复：长文本分段预算、输出截断、逐项保留可信模型结果、原文事实连接自动建档与八大项。正式材料选择必须识别持久化系统自动确认，不能只接人工角色；不得把模型建议值当决定。26合成合同已自动建档、五步审核、八大项HTML及确认重复/重读通过；模型结果部分不可信时仍可生成部分报告，不能把PARTIAL改成全部覆盖。甲方真实材料准确率、OCR逐页完整性及ARM升级继续待现场复验。

## 2026-10-29：旧前端403不能阻止更新

v13现场在HTTP预检遍历旧前端全部文件时遇到403停止，尚未切换应用。旧目录可能含字体/UI补丁遗留资源；旧文件不可访问不等于新包不可部署，也不能仅据403断言具体权限问题。v13a改为旧入口差异只记录告警、不枚举旧目录资源；保留鉴权检查。新版本切换后继续严格验证入口和新资源的HTTP真实内容，失败时报告具体相对资源路径并沿用恢复流程。

使用新解压目录内的一条命令`sudo bash update.sh`。不要求手工修改Nginx、递归chmod整个安装目录、创建恢复记录或重复执行SQL。本修订不修改数据库检查器/迁移、应用JAR或业务数据，也不代表合同模型识别质量问题已解决。最终以`UPDATE=PASS`及现场页面/业务验证为准，不能在预检通过时宣称部署完成。

## 这次问题的结论

上一轮数据库升级并非账号密码错误。数据库已连接，增量 DDL 已执行并写入 INTENT/ACK；代码在最终结构复核阶段因为把 DM8 的等价 CHECK 表达式当成字符串差异而停止。`ROLLBACK_HEALTH=PASS` 只表示旧应用恢复健康，不能表示 DDL 被回滚。

已确认的误判类型包括：

```text
<>                         与 !=
IS NOT NULL                与 NOT (… IS NULL)
A + B <= LIMIT             与 (A + B) <= LIMIT
```

这些差异在已核实的范围内不改变语义。检查器必须按限定语法比较语义，不能把所有 CHECK 直接跳过，也不能把所有括号粗暴删除。真正的列、常量、运算符、布尔关系或优先级变化仍必须停止升级。

## 数据库判定原则

只检查本次程序或迁移真正依赖的不变量。以下情况必须阻断：

- 连接、所有者或必要权限无法证明；同名表/视图/同义词目标不明确。
- 必要列的类型、精度、宽度、可空性、默认值不兼容。
- 必要主键、唯一键、外键、索引顺序或约束启用状态真实漂移。
- CHECK 的实际业务条件改变，或 DM8 返回的写法无法在限定范围内证明等价。
- 迁移处于部分完成、活动任务状态未知、清单/版本不可信。

以下情况不能无理由阻断：

- 空白、关键字大小写、冗余括号和已验证等价运算符写法。
- 表已经匹配但迁移历史没有登记。
- 与本次代码无关的旧表、额外普通索引或数据库自动生成的辅助对象。
- 与材料解析无关的审批规则未配置。

不能证明安全时应停止并输出 `table`、`constraintOrColumn`、`stage`、`reasonCode`、`dbCode`，不能猜成密码错误，也不能自动修表。

## 制包前的强制验证

每次新包必须在交付前完成并记录：

1. 固定应用提交、检查器源码/class、迁移文件和前端/Python 产物的 SHA-256。
2. `backend` 执行 `./mvnw.cmd clean verify`，并运行受影响模块回归。
3. 在隔离的真实 DM8 上测试“表不存在 → 建表/建约束 → 读取真实 Catalog → 最终复核”。Fake JDBC 或 H2 只能补充单元测试，不能替代这一步。
4. 对已经匹配的数据库重复运行，确认不重建表、不导入 seed、不执行重复 DDL。
5. 模拟“DDL 已成功、最终复核失败、应用代码回退”，再用新检查器识别已存在结构；不得重放原 `plan.sql`。
6. 测试权限不足、禁用约束、缺列、改常量/列/比较符、缺条件、优先级变化和 UNKNOWN 状态，确认 fail-closed。
7. 重新编译 Java 17 class（major 61），重建内层 manifest 和外层 SHA-256；从最终 tar.gz 解压后再验包。
8. 测试补丁重复应用、目标文件被改动、补丁被篡改及中途写入失败恢复。

未能连接真实 DM8/ARM 时必须标记 `NOT VERIFIED`，不能把本地构建或脚本生成说成现场升级成功。

## 现场更新步骤

### Update_v12 单命令部署约定

用户明确要求减少重复操作。后续对应新包采用一条正式命令`sudo bash update.sh`：脚本一次性先运行既有架构、服务、Nginx/HTTP资源、AI Python依赖、DM8必要Schema和活动任务预检，全部通过后在同一次运行中继续发布。用户不需要先单独`--check`，也不需要创建`/root/audit-recovery.json`。保留`--check`供需要只读查看的人使用。

该简化只移除外置备份确认文件，不放宽包完整性、DM8真实结构/未知状态、活动任务或发布健康检查。它也不会暗中制作数据库/业务文件备份：更新器会暂存旧应用文件，但增量DDL提交后不能用应用回退撤销。README必须在单命令旁明确此限制；不能用虚假“备份确认”代替。

### v11 恢复记录阻断的现场经验（2026-10-27）

现场日志显示：数据库预检和任务静默检查均通过；正式更新停在`stage=BACKUP`，未开始新release、DDL、版本切换或服务重启。`BACKUP_RECORD_UNREADABLE`的脱敏诊断返回`record: MISSING_OR_UNREACHABLE`，根因是指定的`/root/audit-recovery.json`不存在或root无法读取。

重要：`sudo bash update.sh --backup-record /root/audit-recovery.json`只读取并校验现有记录，绝不会创建它。先按包内`CUSTOMER-RECOVERY.md`将真实恢复确认JSON放到该绝对路径；JSON需引用已完成实际恢复验证的数据库和业务文件备份。不要通过复制模板、虚填`true`或放宽检查绕过。

首次接包先做一次只读预检。正式更新命令自身会重新执行预检；如果预检已通过、只因恢复记录缺失而停止，放好有效记录后**直接重跑正式更新命令即可**，不必另跑第二次`--check`。先用一条快速只读检查确认记录确实存在：

```bash
sudo test -r /root/audit-recovery.json && echo RECOVERY_RECORD_READABLE
```

命令成功后只执行一次正式更新：`sudo bash update.sh --backup-record /root/audit-recovery.json`。若快速检查失败，表示记录仍不存在/不可读，不要再运行更新。若正式更新仍报`BACKUP_RECORD_UNREADABLE`，只需把脱敏Python诊断中的`record`、`database_backup`、`files_backup`状态发回；不要发送JSON、真实路径、文件名、SHA或备份内容。当前客户机器SSH从本工作环境连接超时；未读取或更改其文件，也未执行更新。

已有环境复用 JDK17、DM8、Redis、Nginx 和 Python venv。不重新安装 RPM，不使用 `--allowerasing` 或 `--skip-broken`，不初始化 AUDITDB，不删除业务表或历史任务。

先检查：

```bash
uname -m
cat /etc/kylin-release
readlink -f /opt/audit-agent/current
systemctl is-active audit-backend audit-ai nginx
sudo nginx -t
```

完整包解压到新的空目录并校验包SHA后，按新包README操作。对于v12，正式部署只需要以下一条命令；命令内部先跑只读预检，预检失败就停止，通过后在同一次运行中继续更新：

```bash
sudo bash update.sh
```

另需只读检查时可用`sudo bash update.sh --check`。正式更新结束必须看到`UPDATE=PASS`，并继续健康检查、HTTP资源校验和预算/采购/合同业务验收。

如果更新失败，保留本次日志和恢复目录。先查询 DM8 实际结构再决定下一步；不要因为代码回退就认为 DDL 已撤销，不要手工再执行 `plan.sql`。

当前 v10 检查器补丁的现场命令：

```bash
cd /root/audit_agent
sudo bash Schema_Fix_v1/apply.sh /root/audit_agent/Audit_Update_v10
cd /root/audit_agent/Audit_Update_v10
sudo bash update.sh --check && sudo bash update.sh
```

看到 `PATCH=PASS` 或 `PATCH=ALREADY_APPLIED` 后才继续。补丁只替换检查器和清单，不修改数据库、业务数据、AI 地址/密钥/模型名称、Redis 或 Nginx。

## 配置、Nginx 与模型验收边界

Nginx root 跟随 `/opt/audit-agent/current/frontend/dist`，不能固定某个旧 release。多个 release 同时存在是正常的，由 `current` 决定运行版本。配置修改先备份，再 `sudo nginx -t && sudo systemctl reload nginx`。

模型网关白名单是独立问题。HTTP 200 不能代表模型成功；需分别验证 Java→AI、AI→模型网关、响应结构和业务结果。`code=10016`/“不在可访问 IP 列表内”应由模型平台确认实际看到的源 IP/NAT，不通过改数据库解决。

业务验收至少覆盖：原生文字、扫描件/旋转图片、Word/Excel/PDF、长材料、多文件和金额大写。预算、采购、合同均需验证结果持久化、历史读取和刷新/重启后的可恢复性。

## 安全日志边界

外发诊断只包含版本、阶段、耗时、文件数量/大小汇总、关联 ID、HTTP 状态、模型输入输出长度和安全 reasonCode。不得导出原文、OCR 内容、prompt、原始模型返回、材料正文、账号、密码、Token、完整环境文件或业务数据库转储。

## 现场材料问题诊断

预算/采购/合同材料问题先按同一个时间窗口导出 backend 和 AI 两份日志；不要只看 HTTP 200。`HTTP 200` 只说明收到响应，必须继续看 `PIPELINE_STAGE_END` 的 `outcome`、`reasonCode`、字符数、段数和耗时。

```bash
sudo journalctl -u audit-backend.service --since "2026-10-24 10:00:00" \
  --until "2026-10-24 10:20:00" -o short-iso --no-pager --all \
  > backend-material-diagnostic.log
sudo journalctl -u audit-ai.service --since "2026-10-24 10:00:00" \
  --until "2026-10-24 10:20:00" -o short-iso --no-pager --all \
  > ai-material-diagnostic.log
```

先在 backend 日志找到对应的 `requestId` 或材料 UUID，再分别过滤：

```bash
rg 'MATERIAL_PROCESS|DOCUMENT_PARSE|MODEL_FIELD_EXTRACTION|FIELD_NORMALIZATION|DB_COMPLETE_COMMIT|TENDER_DOCUMENT_PARSE|TENDER_OCR|F10_TENDER_REVIEW|F10_AI_REVIEW' backend-material-diagnostic.log
rg 'MATERIAL_CHUNKS_READY|MATERIAL_CHUNK_START|MATERIAL_CHUNK_EMPTY|MATERIAL_FACT_EXCLUDED|MATERIAL_RESULT_READY|MODEL_GATEWAY|OCR_' ai-material-diagnostic.log

# 合同事实提取只导出脱敏的计划、批次、耗时、候选/证据计数，不导出正文或模型返回
rg 'FACT_EXTRACTION_PLAN|DM8_COMPLETION|FACT_SEGMENT' backend-material-diagnostic.log

# OCR 方向和扫描页质量指标只包含数值，适合判断旋转/纠偏是否生效
rg 'OCR_ORIENTATION_SELECTED|OCR_PAGE_TEXT_EMPTY|OCR_END' ai-material-diagnostic.log
```

常见结论按阶段判断：

* `DOCUMENT_PARSE` 失败或 `OCR_PAGE_TEXT_EMPTY`：文件读取/OCR 问题，尚未进入模型；检查文件格式、页数、旋转日志和 OCR 是否返回有效字符。
* `MODEL_FIELD_EXTRACTION` 为 `NO_GROUNDED_FIELDS`、AI 日志出现 `MATERIAL_CHUNK_EMPTY`：文本已读到模型，但模型没有返回可定位字段；不要当作数据库故障。
* `MATERIAL_FACT_EXCLUDED` 表示单个模型候选没有通过原文定位或角色校验，系统会丢弃该候选并保留同一材料中其他已核实字段；这不是整份材料失败。常见脱敏原因码为 `MATERIAL_AI_UNGROUNDED`、`EVIDENCE_UNVERIFIED` 和 `FIELD_ROLE_MISMATCH`。
* `FACT_EXTRACTION_PLAN` 的 `plannedBatchCount`、`submittedCodePointCount` 和 `totalBudgetMs` 用于判断合同是否接近输入或总时限上限；`DM8_COMPLETION` 的 `modelCallDurationMs`、`candidateCount`、`evidenceRejectedCount` 用于区分模型慢、模型无结果和证据校验拒绝。
* `OCR_ORIENTATION_SELECTED` 的 `rotationDegrees`、`deskewMillidegrees`、`orientationQualityMilli` 和 `classifierConfidenceMilli` 用于现场判断图片方向和纠偏效果；日志不含 OCR 原文。
* `MATERIAL_AI_CALL_TIMEOUT`、`MATERIAL_AI_CALL_OUTCOME_UNKNOWN`：模型调用结果未能确认，不自动重发；先按 requestId 查网关和白名单。
* `FIELDS_SAVED`：采购材料中的预算、供应商、投标报价等字段已通过原文定位并保存；`evidenceRejectedCount` 只表示模型候选被证据校验剔除。
* `F10_AI_REVIEW` 为 `AI_INVALID_RESPONSE` 或 `AI_TIMEOUT`：规则审查结果可以保留，但 AI 语义结果不能标记为完成。

合同分段日志中的 `maxDocumentCodePoints`、`maxPhysicalSegments`、`maxBatchSegments` 是三个不同限制；不能把 `maxBatchSegments` 当成整份合同的上限。

## 当前交付记录

- 原 v10 应用提交：`0dd7bd5496aaaec487da35b2d710f761de37b7`。
- 原 v10 包 SHA-256：`72e31b1daaf9c44c85ff1e7c78eaa2bc250a72d02f587b9012546c514d8a7339`。
- `Schema_Fix_v1.tar.gz` SHA-256：`de94f041e33b9a07157cf1296ba2c53c2ec2848adf6c84fdff26222a076b7f1a`。
- 本地后端：3139 tests，0 failures，0 errors，15 skipped，BUILD SUCCESS。
- 补丁最终包：平铺/manifest/LF/Java17/安装恢复测试通过；真实 DM8 首次建表和补丁后甲方 `UPDATE=PASS` 尚未验证，记为 `NOT VERIFIED`。

每次新包交付后更新本节，不能用旧 v9/e17 文档代表当前版本。

## 26 三模块合成验收（2026-10-27 CST）

- 部署版本：`auto-intake-20261026`，应用基线 `35f95499c4d5efa475dffc3a3628222750124672` 加隔离候选工作树变更；候选归档 SHA-256：`3a5db9976895b4e5d8ef8172936aa20acc9f0d2d52b929e433fa6a0540be974a`。
- 26 release：`/opt/audit-agent/releases/auto-intake-20261026.qw7KXB`。部署前实测旧 JAR SHA-256 `3821ab5a660df343af1a05160ecf7eb3f36613c096161a6f262866cd41052f30`、入口 SHA-256 `cbea230866a44ae5905c28b96a210875cc17d2d81b8ae622a8c7c728253a6a7f`，均作为精确回退/漂移保护值。新 JAR SHA-256 `ce76b6d0d12f0c098aa7bd9955b081abb420643049a5da4ed23d134aab720f4f`，前端入口 SHA-256 `08e07b799b7c62e341fb882290ba2e93afd2d7871a2ea0023b5d85a563bae644`。
- 26 `--check`：活动任务数为 0；V052/V053 已匹配，V054–V057 schema 匹配；合并合同 schema 校验通过、计划语句数 0；合同自动权限 schema 计划语句数 0；Python 3.11 运行时源检查通过。DM8 隔离临时表验证首次检查、重复检查、模拟代码回退后识别、真实漂移拒绝均通过，临时表已删除。
- 部署结果：`THREE_MODULE_DEPLOY26=PASS`；backend、AI、Nginx 均 active；backend/AI health 均 UP；HTTP 静态入口和引用资源均匹配；现场配置哈希未变化。测试结束再次确认预算、采购、合同及材料提取活动任务均为 0。
- 本地验证：JDK 17 `backend/mvnw.cmd clean verify`：3163 tests，0 failures，0 errors，15 skipped；前端 Node 测试 806/806，生产构建成功（存在既有 chunk-size/dynamic-import 警告）；AI/OCR 全量 pytest 1981 passed、15 skipped、43 subtests passed（本次代码未再改动 AI 源码）。`git diff --check` 通过。
- 26 预算批次：12 份 DOCX/XLSX/TXT 混合材料和长自然语言材料均完成；结果在 backend 重启后由新会话重新读取，13 个结果指纹未变。
- 26 预算风险/拆分：累计金额三档及边界、跨年度明确选择、40 条候选分页、历史检索/分页、6 路同一操作并发幂等、非法范围变更 409 均通过。
- 26 采购：两份合成材料保存后自动生成审查报告；品牌限制风险被识别，未重复上传；重复生成、报告读取和新会话重开保持一致。
- 26 文件格式：预算与采购各跑 21 个合成样本（19 个有效格式样本、2 个预期失败/拒绝样本），有效样本解析及业务断言均通过；覆盖 DOC/DOCX、PDF、PPT/PPTX、XLS/XLSX、文本/CSV/RTF、PNG/JPG/BMP/TIFF 和约 250 MB DOCX。损坏与超限场景分别按预期结束。
- 26 合同：合成合同由系统分类、项目归属并自动建档，五步审核 COMPLETED；报告生成并持久化为 AVAILABLE，报告清单读取和 artifact 下载通过（14,025 字节）。初次合成脚本漏了必需的合同类型字段，系统按规则保留候选、不错误晋级；补齐字段后正式链路通过。失败、UNKNOWN 和 PARTIALLY_COMPLETED 的报告范围/防误报仍由本地 `ReportSnapshotTest` 回归覆盖；26 现场此次直接审核为 COMPLETED，未在现场伪造模型或数据库故障来制造失败终态。
- 全部测试材料均为专门生成的合成数据；未读取或导出甲方业务材料正文、OCR 原文、prompt 或模型原始响应。现场真实材料准确率及甲方真实工作负载仍需另行验证。

## Update_v11新增案件升级

包文件使用短名Update_v11.tar.gz，根直接update.sh。正式更新先在甲方内部验证数据库和业务文件备份恢复；按照包内CUSTOMER-RECOVERY.md创建root0600确认记录。只读检查仍为sudo bash update.sh --check；正式更新为sudo bash update.sh --backup-record /root/audit-recovery.json。脚本只核对确认记录/哈希，恢复演练由现场完成，不允许为了通过而虚填确认。

新增CONTRACT_ANALYSIS_CASE及AUDIT_CASE/AUDIT_CASE_FILE/AUDIT_FACT/AUDIT_QUOTE_LINE在隔离真实DM8验证首次11条DDL、重复0条、代码回退后识别、实际禁用约束/改默认值/改外键顺序拒绝。升级器不自动修表，旧DDL成功回执仍保留。内部update-v10管理路径为兼容旧安装保留，实际版本以VERSION.txt为准。新版--backend-jar参数为必需，避免混用旧target产物。
