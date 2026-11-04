# PM44 AI Semantic Final Alignment Plan

## PM44 FREEZE

- 合同智能审核固定分成两块：精确校验由 Java deterministic 负责，智能语义审查由一次真实 Qwen semantic review 负责。
- 第 4 步只由该 AI 请求的状态决定；4.1、4.2、4.3 是同一结果按 category 展示的业务分区，不是三个独立审核引擎或进度分支。
- Java Breach helper 在本轮结果中只提供比例、频率、基数、上限及 `BigDecimal` 金额 enrichment；Java Term helper 只提供明确日期 extraction/normalization。其既有 semantic status/comparison 字段不参与 AI finding 或进度，也不覆盖 AI 风险结论。
- AI 可以理解百分比、千分比、日期、期限、时长和非金额阈值；绝对货币金额、价格、预算、差额、金额算术和货币结果继续脱敏或过滤。
- F02 五条规则、Project/OCR/上传/Dashboard/筛选/材料完整性、证据归属和高亮 fail-safe 边界保持不变。

## Verified gaps at `995d6e3`

1. Python、Java 与 TypeScript category 契约缺少 `CONTRACT_STRUCTURE`、`BREACH_LIABILITY`、`TERM_TERMINATION`，Qwen prompt 也未覆盖相应业务维度。
2. `ContractAiTextRedactor` 的 labelled plain-number 分支会把 `合同金额1%` 误脱敏；Python monetary validator 也会把金额语境中的百分比误判为金额计算。
3. Python 对多个 chunk 只拼接安全结果，没有跨 chunk 语义综合，不能可靠发现位于不同 chunk 的跨材料冲突。
4. React pipeline 仍用 AI/Breach/Term 三个 branch 共同决定第 4 步；Workspace 仍把两个 Java helper 面板作为独立语义结论。

## Implementation slices

### 1. Typed semantic contract and numeric safety

- 先加 Python category/alias、百分比允许、绝对金额过滤和 prompt regression 红测。
- 先加 Java enum/adapter 与 redactor 红测；用明确的绝对金额边界保留 `%`、`％`、`‰`、期限和时长。
- 扩展 Qwen system/retry prompt，禁止 mask token excerpt，同时保留宽容 schema/per-hint filtering。

### 2. Bounded multi-chunk synthesis

- 先用跨 chunk 合同/招标冲突 fixture 证明当前简单拼接不具备跨文件比较能力。
- 多 chunk 时增加一次 final synthesis，只输入已经归一化的 summary/hint/fileId/excerpt，不重喂 raw provider output。
- 保持一个 request-wide retry；最大 provider call 数固定为 `chunk_count + 2`（各 chunk、最多一次 retry、一次 synthesis），单 chunk 仍最多 2 次。

### 3. Single-AI frontend authority

- `textAnalysis.hints` 分为：4.1 常规类别、4.2 `BREACH_LIABILITY`、4.3 `TERM_TERMINATION`。
- Pipeline 只保留五个顶层 key，不再暴露 clause branch status；第 4 步仅看 AI loading/success/failure，第 5 步等待 F02 与 AI terminal。
- helper 继续并行取得，但只按 verified evidence fileId/excerpt 安全匹配到对应 AI hint；匹配不到则不展示量化/日期 enrichment。
- 复用现有 evidenceRefs/evidenceItems、真实文件打开、NFKC unique locator 与 fail-safe 提示。

### 4. Verification and delivery

- 运行聚焦 Backend/Python/Frontend 红绿测试、OCR/Dashboard/补充资料/筛选/高亮回归。
- 使用 JDK 17 `clean verify`、项目 Python 3.11 全量 pytest、前端全量 Node tests 与 build。
- 代码审查、`git diff --check`、scope/secret/artifact scan 后独立 commit，非强制推送当前 feature branch，并核对本地/远端 SHA 与 clean worktree。
