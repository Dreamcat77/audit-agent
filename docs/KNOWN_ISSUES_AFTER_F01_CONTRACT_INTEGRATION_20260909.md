# F01 + Contract 合并后已知问题清单（2026-09-09）

> 目的：允许先形成最新集成主干候选，不把已知但当前不阻塞 Contract/F01 基本运行的问题混入本次合并修复。本文是后续整改清单，不代表这些问题已经解决。

## 当前合并基线

- `origin/main`：`55a09f3c1ad248ac3d33a3e1e78800f8fadcf9ff`
- Contract/P4.1：`feature/contract-review-next-20260907@4385d1afeb94c54c3653703dc4dc10c7d57cdeaf`
- F01/F03 + blocker fix：`fix/f01-main-blockers@66e02fad9120fd9781da04adbf33d628136ecbc5`
- 合并提交：`ac35954601646bc5c46e49ddea2136164358f956`

## 已知问题（允许先合并，后续整改）

### KI-01 F01 重复确认 / resume 状态边界

当前 `confirmAndPrepare()` 会先持久化 confirmation log 并把任务置为可恢复状态，这是正确方向；但 `confirmExceptionInternal()` 对“confirmation 已存在且任务不再是 `WAITING_CONFIRMATION`”的重复请求存在状态表达风险。

风险场景：

- confirmation 已提交，task 为 `PARSED`，但 resume 尚未完成/进程中断；
- confirmation 已提交，resume 失败后 task 为 `FAILED`；
- 用户再次提交同一 confirmation。

当前逻辑需要进一步确认不会把上述状态误表示成 `COMPLETED`。

### KI-02 F01 缺少真实 Spring Proxy 事务集成证明

已有单元/结构测试证明 Service 不再声明长事务、Coordinator 持有短事务边界、单测 AI probe 观察到 transaction inactive；但仍缺一个最小 Spring transaction integration test 证明真实 proxy 下 initial/resume AI 均在业务写事务外。

### KI-03 Contract AI P4.3 尚未在 combined tree 上重跑

P4.1 已修复 evidence 1000 字符边界、R01 金额脱敏、request-wide total budget；P4.2 已确认 Qwen 262K context 会导致 CPU offload，16K/32K 可 100% GPU 驻留。正式 Qwen/DeepSeek 4.1/4.2/4.3 A/B 尚未在合并后的代码上执行。

### KI-04 Qwen OpenAI-compatible route 的 thinking 控制

已验证 Ollama native `/api/chat` 下 `think=false` 有效；此前 OpenAI-compatible route 对 `think=false` 无可观察效果。当前应用若继续使用 OpenAI-compatible route，Qwen 可能仍产生长 reasoning，影响 latency。context 应明确配置为 16K/32K，避免 262K 导致 CPU offload。

### KI-05 DM8 V020-V022 尚需批准环境执行 Gate

F01 integration 包含：

- `V020__f01_budget_semantic_analysis.sql`
- `V021__f01_ai_provenance.sql`
- `V022__f01_ai_node_results.sql`

Contract 当前分支没有新增 migration 编号，因此当前没有已知 migration 编号碰撞。正式发布前仍应在批准的隔离 DM8 执行并验证。

### KI-06 前端历史 F04 静态契约测试问题

Contract P2/P4 历史 gate 曾出现 frontend full `228/229`，唯一失败是既有 Budget/F04 static-contract test；combined tree 后续仍应重跑 full frontend tests，不能直接沿用旧 waiver。

## 后续建议验证

1. Python 3.11 `services/audit-ai` full pytest；
2. Backend `mvnw clean verify`；
3. Frontend full tests + build；
4. `git diff --check`；
5. backend health smoke；
6. Contract focused tests / R01 smoke；
7. F01 focused tests；
8. combined tree P4.3。

## 非本次合并范围

- F01 状态机重构；
- ReviewSession 重构；
- durable integrated ReviewRun；
- Contract F07-F15 扩面；
- Qwen adapter 切 native API；
- Prompt 调优；
- 新 migration；
- 生产部署 / 26 环境变更。
