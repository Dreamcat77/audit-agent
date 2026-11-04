# 预算、采购材料超时处理验证（2026-10-19）

状态：本地构建和代表性 DM8 隔离验证通过；后续已部署到代表测试机并完成真实链路验收，见 [发布记录](budget-procurement-release-20261019.md)。甲方环境 NOT VERIFIED。

## 实际行为与边界

- 材料字段提取的模型读取等待默认 180 秒，可配置为 1–180 秒。合同和其他 AI 路由保持原有超时策略。
- 预算、采购共享材料模型并发 1；入场等待上限 1800 秒。在拿到名额前不认领模型调用，排队不会先消耗新材料的模型执行预算。
- 分段模型提取阶段每份材料限 900 秒，截止时间写入 DM8，重开不会重置。上传、文档读取/OCR 和排队另计，不能把它描述成上传开始后的全链路 15 分钟硬上限。
- HTTP read timeout 是传输读取等待上限，不能证明上游模型已停止。截止时间前后均检查，DM8 提交还需匹配执行令牌和截止时间，迟到响应不得发布。
- 已确认的调用、材料总预算、排队超时使本地材料任务结束为 FAILED；擦除未发布候选，不生成半份业务结果，其余文件继续。调用账本保留 UNKNOWN 与安全超时原因，禁止自动重发未知的上游操作。
- 终态事务提交后，只删除该失败任务的独有暂存文件。清理失败每 30 秒补偿；成功材料、报告和证据不删除。无结果的超时任务不进入正常历史结果列表，但其 ID/操作查询和最少审计仍保留。
- 既有 UNKNOWN 记录未批量修改或删除。不得仅因“看起来很久”就推断其上游调用失败。

## 主要文件

- `common/material/application/DurableMaterialExtractionService.java`、`MaterialExtractionLedger.java`：持久化截止时间、累计预算、未知调用与迟到提交拦截。
- `integration/ai/fastapi/FastApiMaterialFieldAdapter.java`、Python `model/gateway.py`、`model/material_fields.py`：共享并发、180 秒材料读取等待、白名单超时类型、清除未发布检查点。
- `BudgetMaterialService` / `BudgetMaterialTransactions` 与 `MaterialIntakeService` / 对应 DM8 mapper：终态收口、提交后文件清理、补偿发现、历史过滤和禁止复活。
- `db/dm8/V057__material_extraction_deadline.sql`、`tools/MaterialDeadlineMigration.java`：只增加 nullable TIMESTAMP；同名列有漂移立即停止。
- 前端仅补充三种材料超时的中文说明，没有增加界面操作。

## 实测

Windows、JDK 17，执行目录 `backend`：

```powershell
.\mvnw.cmd clean verify
```

结果：BUILD SUCCESS，2418 tests，0 failures，0 errors，2 skipped。日志 `.runtime/material-timeout-clean-verify-final.log`。

Python 3.11，执行目录 `services/audit-ai`：

```powershell
$env:PYTHONPATH='src'
& ../../.runtime/material-python311/Scripts/python.exe -m pytest -q --basetemp=E:/audit-agent-new/.runtime/mtpy-20261019-02
```

结果：551 passed，1 skipped（Windows 符号链接权限），1 warning。日志 `.runtime/material-timeout-python-final.log`。

前端，执行目录 `frontend`：

```powershell
$tests=@(rg --files src -g '*.test.ts')
node --experimental-strip-types --test $tests
npm run build
```

结果：748 passed，构建通过。Chrome 109 颜色兼容资源构建通过；真实 Chrome 109、Mock API 页面冒烟通过：预算首页和采购首页可打开，0 runtime errors。该冒烟不代表甲方 AI/OCR 端到端已验证。

真实 DM8、JDK 17，使用业务测试账号，只创建随机命名隔离表，运行：

```text
MaterialExtractionPlatformCheck isolated-probe db/dm8/V055__material_extraction_ledger.sql
```

观察结果：

```text
DM8_EXTRACTION_CLOB_DUPLICATE_REOPEN_CUMULATIVE_BUDGET_UNKNOWN_FENCING=PASS
DM8_DEADLINE_MIGRATION_CHECK_APPLY_IDEMPOTENCY_DRIFT=PASS
ISOLATED_DM8_EXTRACTION_PROBE=PASS TEMPORARY_TABLE_REMOVED=YES APPLICATION_SCHEMA_UNCHANGED=YES
```

验证了迁移只读检查、加列、重复执行幂等、错误字段类型拒绝；截止时间不能重置、超期限拒绝 ACK、错误 token 拒绝终止、匹配终止清空候选并拦截迟到提交。隔离表已删除，正式表未执行 V057。

其他反向验证包括：任意带 timeout 字样的错误不得冒充确认超时；未知调用不能重发；总时限跨重启保持；清理失败可重发现；路径越界不能删除；成功文件保留；前一文件超时不阻塞后一文件。

## 后续部署条件

先运行 V055 结构只读核对，再用 `MaterialDeadlineMigration check/apply/check` 执行 V057，遇结构漂移停止。之后才能启动配套后端与 AI 服务，执行健康、静态资源和预算/采购实际材料验收。不要只替换 Python 文件或只调环境变量，就宣称本次生命周期方案已生效。
