# 预算、采购可靠性优化（2026-10-19）

## 范围与版本

工作分支 `fix/budget-home-split-risk-20261014`，工作区基于
`e17a1d3eca75c402c371f2adb58d0d59676f4b84`，包含此前尚未提交的预算、采购工作。
本证据只说明本轮可靠性改动；不代表所有工作区改动均属于本轮。
本轮没有修改前端布局、合同业务、模型配置、Nginx 或正式业务数据。

## 已实施

1. **字段依据与归属**：预算物理行保持独立；模型 rowKey 不能授权拼接不同项目的金额。
   对跨行或重复而无法定位的摘录停止提取。申请/批复金额按原文所在行核对角色，不能
   裁掉标签后把批复金额充当申请金额。采购同样核对预算、合同、中标金额及部门/申请人的明确标签。
   金额仍走既有 BigDecimal/中文大写金额转换；不会往预算批复主数据补造事实。
2. **持久分段提取**：`DurableMaterialExtractionService` 将原文规划为有界单元，
   `MATERIAL_EXTRACTION_LEDGER` 保存源文件/文本指纹、策略指纹、下一单元、累计调用/耗时和已确认候选。
   新路径使用 `MaterialUnitAiPort`，传输失败不自动重发。每个单元先提交 CALLING，
   校验响应后提交确认，再请求下一单元。已确认单元在恢复时复用，完整结果可在模型不可用时重开。
   并发 CAS、调用 token、防迟到提交和累计预算均有回归。
3. **采购反证核对**：F10 针对支持的话题，从全文选取明确的例外、同等产品、补充变更等原文，
   与原风险候选逐项核对；候选身份、定位和摘录必须匹配。未知话题不随意引用其它例外。
   多候选的 chunk 总数已修正；任意候选失败都不能发布半份综合结果。
   超预算/上下文不截尾后假装完整。局部材料不能授权“全文缺失”结论。
   这是有限话题的反证核对，不是任意法律语义的完备证明；AI 不能删除或降低确定性规则风险。
4. **采购历史报告冻结**：新列 `REPORT_SNAPSHOT_JSON` 保存生成时的项目、预审、报价、
   黑名单、招标、围串标、拆分采购结果和材料版本。重开/导出只读快照，不取今天的数据替代历史。
   原 `AGENT_REVIEW_JSON` 格式不变，旧报告不回填当前事实；仅展示确实保存的旧章节，并明确其余历史无法还原。
   使用原始 JSON token 反序列化 BigDecimal，避免 JSON tree 规范化破坏小数位。
5. **失败与完成区分**：模型超时、不可用、无效响应时，规则结果继续保留，上传/重开状态为
   INCOMPLETE，文字说明 AI 语义审查尚未完成。允许生成的参考报告也冻结这一限制说明，
   不会因模型后来恢复而改写历史结论。业务风险与模型执行失败分开表达。

## 自动化与真实平台验证

本机：Windows，JDK 17.0.18，现有 Python 3.12 测试虚拟环境。

```powershell
# backend 目录，进程 JAVA_HOME 指向现有 JDK 17
.\mvnw.cmd clean verify

# 工作区根目录；短临时路径避免 Windows 路径上限干扰恢复文件测试
.\.runtime\office-python-test\Scripts\python.exe -m pytest services/audit-ai/tests -q --basetemp=E:/audit-agent-new/.runtime/optpytest-final5
git diff --check
```

最终后端：BUILD SUCCESS；2387 tests，0 failures，0 errors，2 skipped。
两项跳过均为合同原始材料刻画测试（未提供 `audit.original.materials.root`）；不属于预算、采购。
完整日志 `.runtime/optimization-backend-verified.log`，构建耗时 03:50。
Python：545 passed，1 skipped（Windows 符号链接权限），1 个现有依赖弃用警告。
完整日志 `.runtime/optimization-python-verified2.log`。
定向 Java：30 tests，0 failures，0 errors；之后执行完整 clean verify。
随后将既有规则合并回归加强为 AI 返回 INFO 的反证场景，19 项定向测试全部通过，
确定性规则仍保留 CRITICAL；日志 `.runtime/optimization-java-info-regression.log`。
后端 JAR SHA-256：`1bb9fd4a390c8fb6d44d526b72c626b71cda76b5934d27f52895819d3304a780`。
本轮前端零修改，未重新声称完成 Chrome 109 端到端验收。

代表性测试服务器：使用现有 JDK 17、Python 3.11 和 DM8，另开隔离测试目录和 loopback AI 子进程。
模型配置仅从现有进程读入内存，未输出或改写配置。全部输入为合成文字。

真实 DM8 执行：

```bash
java -cp 'classes:jar/BOOT-INF/classes:jar/BOOT-INF/lib/*' MaterialExtractionPlatformCheck isolated-probe db/dm8/V055__material_extraction_ledger.sql
java -cp 'classes:jar/BOOT-INF/classes:jar/BOOT-INF/lib/*' ProcurementReportSnapshotPlatformCheck isolated-probe db/dm8/V056__procurement_full_report_snapshot.sql backend/src/main/resources/mappers/dm8/ProcurementReviewReportMapper.xml
```

观测：

```text
DM8_EXTRACTION_CLOB_DUPLICATE_REOPEN_CUMULATIVE_BUDGET_UNKNOWN_FENCING=PASS
ISOLATED_DM8_EXTRACTION_PROBE=PASS TEMPORARY_TABLE_REMOVED=YES APPLICATION_SCHEMA_UNCHANGED=YES
DM8_REPORT_REAL_MYBATIS_MAPPER_TWO_CLOBS_ROUNDTRIP=PASS
DM8_REPORT_ADDITIVE_COLUMN_LEGACY_BYTES_CLOB_ROUNDTRIP=PASS TEMPORARY_TABLE_REMOVED=YES
```

DM8 的唯一键冲突可能归为 DataIntegrityViolationException，已按持久 winner 记录存在性处理；
不能证明 winner 时仍抛出原始异常，没有吞掉任意完整性错误。

真实模型 HTTP：`tools/test-material-reliability26.py`，独立服务健康使用真实 curl，返回
`{"status":"UP","service":"audit-ai"}`。预算大写申报/批复金额与采购预算/部门/申请人提取均返回
HTTP 200，并通过原文定位、摘录、字段及调用计数检查。同一设备例外、其它设备例外两组
F10 核对均返回 HTTP 200；其它设备的例外未消除原设备品牌风险。
最后一次复测覆盖最终反证选择代码；隔离子进程已退出，正式后端与 AI 服务均为 active。
这些是合成材料的代表性模型验证，不是甲方模型或全部办公文件全量验收。

## 发布与恢复边界

- V055 增加提取台账；V056 仅增加报告 nullable CLOB。生产程序不自动执行 DDL。
  部署前运行两个 PlatformCheck 的 check 模式确认现状；缺失对象可按受控发布执行 apply。
  已有对象漂移立即停止，不自动修表。先完成新增 schema、匹配版本的 Python 服务，再启用新后端。
- `check` 输出 NOT_APPLIED 表示需要迁移，不是已具备新后端的运行条件。
- 已确认分段可恢复；未确认 CALLING 不等于 FAILED。重开时超过 1100 秒可将其围栏为 UNKNOWN，
  旧执行者不能迟到提交。UNKNOWN 需要人工核对，不能换操作 ID、清台账或重新上传绕过。
- 每个不可变材料操作最多 512 次处理尝试、900000 ms 累计已确认耗时；重启不重置。
  发生未知调用后禁止继续发请求，因此不会通过重复重试突破预算。
- 原文核心字符的 Unicode 完整性、证据保真与业务信息全部成功提取是不同的保证。
  超范围、证据不明、模型无效输出会明确停止，不承诺任意文件均可解析。
- 持久台账恢复覆盖预算/采购的字段提取单元；F10 反证核对仍是请求内有界操作，
  不宣称具备整条采购审查的跨重启 checkpoint。
- 上述隔离验证阶段未对正式 schema 应用 V055/V056，未切换现有应用版本。
  用户随后授权更新 26 测试服务器；后续部署、现场发现的句号边界修复及正式 HTTP/浏览器回归
  见 `budget-procurement-reliability-deploy26-20261019.md`。甲方 ARM64/本地模型仍为 NOT VERIFIED。
- 回滚保留新增表/列及历史；先排空新提取任务，协调匹配服务版本，不能用旧程序重跑未知操作。
