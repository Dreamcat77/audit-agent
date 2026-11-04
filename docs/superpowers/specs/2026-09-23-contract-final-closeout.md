# 合同审核最终能力收口增量设计

**基线：** `feature/contract-final-closeout-20260923 @ e86e6dc59409e0d7e3785882f5b4e1c87e0f07df`

**状态：** 继承 2026-09-23 统一收口 spec/plan 与保护性 checkpoint。本文件只定义最终新增的 A/B/C、八项剩余确定性缺口和最终原件验收；C01-C04、页面信息架构、Candidate authority、Confirmation/Promotion、ReviewRun、F02、ACK/report/artifact、权限与原文定位均保持原设计。

## 1. 不变量

- `ParsedDocument/OCR -> deterministic facts + model candidates -> Java validation -> Candidate -> Human Confirmation -> Promotion -> Contract`。
- 模型只提出候选事实。模型不能创建或修改 Candidate、Confirmation、Contract、ReviewRun 或确定性结果。
- ProjectFile SHA、parseResultId/version、canonicalText SHA、locator、excerpt/value/context 任一不能验证时，模型候选不得发布为 `ExtractionResult`。
- Formal Contract 是业务事实；ReviewRun snapshot 是 exact run 的冻结输入；历史读取不回查 live Candidate。
- 金额使用 `BigDecimal`，零容差用 `compareTo`；不得以浮点、AI 或容差替代 F02。
- 旧 Contract、snapshot v1、finding v1、旧 run 和旧报告保持可读；不回填或重写历史数据。
- 页面继续是左材料、右八项；默认折叠、一次展开一项。新增结果只进入现有 exact-run 投影。

## 2. A：Contract Fact Extraction v1

### 2.1 协议

新增独立 FastAPI 能力 `POST /ai/v1/agent/contract-fact-candidates`，不得复用 `CONTRACT_TEXT_ANALYSIS`。请求只携带一个 immutable parse 的受限 segments：

- `projectId/fileId/fileSha256`
- `parseResultId/parseVersion/canonicalTextSha256`
- 每个 segment 的稳定 ID、sourceLocator、textSha256、code-point start/end 和 bounded text
- schema/prompt policy 由服务端固定

响应 schema `contract-fact-candidates-v1` 只允许：

- `CONTRACT_NO`, `CONTRACT_NAME`, `PARTY_A`, `PARTY_B`, `PARTY_B_CREDIT_CODE`
- `CONTRACT_AMOUNT`, `AMOUNT_IN_WORDS`
- `LINE_ITEM_NAME`, `LINE_ITEM_QUANTITY`, `LINE_ITEM_UNIT_PRICE`, `LINE_ITEM_DECLARED_AMOUNT`
- `AWARD_AMOUNT`, `AWARD_SUPPLIER`, `AWARD_SUPPLIER_CREDIT_CODE`

每个候选包含稳定 candidate key、可选 line group key、typed value、segmentId、sourceLocator、exact excerpt 与 excerpt offsets、context code、confidence。字段之外的模型输出、未知 context、跨 segment locator、无法 exact-match 的 excerpt 或非法 typed value全部拒绝。

### 2.2 Java 发布规则

Java 在当前 file/parse authority fence 内校验：

1. 请求和当前 `ProjectFile`、immutable parse tuple 完全一致；
2. locator、segment hash、excerpt hash、offset 和 canonical text 一致；
3. 金额、信用代码、文本和 line group 通过 typed validator；
4. award facts 只接受已确认的 AWARD_NOTICE / final-award context；wrong-project 或候选人语义不得发布；
5. deterministic 与 AI 候选都保留，冲突显式进入人工确认，不自动覆盖；
6. 通过验证的候选才转换为 `ExtractionResult`，其 `extractionVersion` 固定为 `contract-fact-ai-v1`。

现有 `ContractCandidateAssembler` 继续是 Candidate 组装入口。它按事实类型、来源角色和冲突规则消费已发布 facts，不直接调用模型。

### 2.3 持久化

`PROJECT_EXTRACTION_RESULT` 能保存已验证的事实、parse tuple 与 locator，但不能保存一次模型操作的请求指纹、模型身份、原候选状态和验证拒绝原因。为了满足版本/来源与重启后可审计，最小结构为：

- `PROJECT_FACT_EXTRACTION_ATTEMPT`：operation identity、immutable input fingerprint、schema/prompt/model provenance、状态、调用计数和时间；
- `PROJECT_FACT_EXTRACTION_CANDIDATE`：原候选 typed payload、source tuple、validation status/reason；
- publication 通过 candidate row 的 `PUBLISHED_EXTRACTION_ID` 指向既有 `PROJECT_EXTRACTION_RESULT`，不创建第二套业务事实。

这是 additive、受控、人工执行的 Contract namespace migration。若没有该结构授权，A 可以完成协议/验证测试，但不能宣称 durable A 已完成或执行真实外发后的发布。

## 3. B：正式金额分项 v2

### 3.1 Domain 与 API

新增 `ContractLineItemBasis`：

- `QUANTITY_PRICE`：quantity、unitPrice、declaredAmount 均非空；
- `AMOUNT_ONLY`：quantity、unitPrice 必须为 null，declaredAmount 非空。

Candidate 当前 nullable 事实保持；Confirmation payload 增加 basis，schema v2 canonical JSON 明确写入 basis 和 nullable quantity/unitPrice。API 缺 basis 的旧请求按旧 schema 解释为 `QUANTITY_PRICE`，但新 amount-only 请求必须显式 `AMOUNT_ONLY`。

### 3.2 DM8

新增 `CONTRACT__contract_final_closeout.sql` 的 B 部分：

- `CONTRACT_LINE_ITEM.BASIS VARCHAR(32)`，历史行回填 `QUANTITY_PRICE` 后设为 NOT NULL；
- `QUANTITY`、`UNIT_PRICE` 改为 NULL；
- 新 check constraint 保证两个 basis 的 null/non-null 组合和金额非负。

不改 V003；不改 Candidate 表；不自动执行 migration。

### 3.3 Snapshot 与 F02

- snapshot schema v2 增加 line `basis`，quantity/unitPrice 可 null；decoder 同时读 v1/v2，v1 默认为 `QUANTITY_PRICE`；新 run 只写 v2。
- `F02_LINE_ITEM_ARITHMETIC` 对 QUANTITY_PRICE 正常 PASS/FAIL；每个 AMOUNT_ONLY 行返回独立 NOT_EVALUATED，reason 明确为无数量/单价依据。
- `F02_LINE_SUM_MATCH` 仍汇总所有 declaredAmount；AMOUNT_ONLY 不阻断 line sum。
- 一个 amount-only 行不得影响 amount words、award amount 或 award supplier。
- Finding/evidence 对 amount-only 行只投影真实 declared amount 和可靠 locator，不伪造数量/单价来源。

## 4. C：BREACH/TERM typed bilateral v2

### 4.1 Typed comparison

每条 comparison 以 stable `comparisonKey` 标识同一责任/期限场景，包含 comparison type、result 与双方值。每侧状态为：

- `KNOWN`
- `NOT_EXTRACTED`
- `NOT_APPLICABLE`
- `CONFLICT`

BREACH typed fields 限定为 responsibleParty、calculationBasis、rate、rateUnit、frequency、period、capRate、capAmount、terminationThreshold。TERM 限定为 dateType、normalizedDate、termStart、duration、effectiveCondition、terminationCondition。每个 KNOWN/CONFLICT value 必须关联 exact frozen evidence；未知状态不得伪造 evidence。

结果仅为 `MATCH`、`DIFFERENT`、`NOT_EVALUATED`、`CONFLICT`。不可比、缺一侧或场景无法对应时为 NOT_EVALUATED；不输出违法/合法判断。

### 4.2 持久化/API

既有 `CONTRACT_REVIEW_FINDING.summary` 长度和语义只适合人类摘要，不能可靠保存 typed bilateral payload。最小 additive 结构：

- `CONTRACT_REVIEW_CLAUSE_COMPARISON`：finding owner、stable key、kind、result、schema version；
- `CONTRACT_REVIEW_CLAUSE_VALUE`：comparison + side + state + typed field/value；
- `CONTRACT_REVIEW_CLAUSE_VALUE_EVIDENCE`：value 指向现有 frozen `CONTRACT_REVIEW_EVIDENCE`。

API 在 finding 下新增可选 `typedComparison` v2；旧 finding v1 返回 null。新 v2 若 typed rows/evidence 不完整必须 fail closed，不能降级为 v1 成功。

该结构是 C 的必要持久化。没有结构授权时不得把 JSON 塞进 summary，也不能把 C 标记完成。

## 5. 八项剩余最小能力

- 第 2 项：只在 exact run 冻结材料包含明确指定模板及模板对照结果时为 COVERED；否则 NOT_COVERED。
- 第 5 项：基础身份核对与外部主体资格分开；外部源未接为 `WAITING_EXTERNAL`。
- 第 6 项：新增确定性正文附件引用提取与 frozen material name/type 最小匹配。引用存在但无材料为 ATTENTION；没有可靠引用时 NOT_EVALUATED；材料清单本身不等于完整性通过。
- 第 7 项：生效条件与合同份数分别显示。合同份数只提取原文事实；没有权威标准时不判 FAIL。
- 第 8 项：只展示现有持久 finding；没有已验证规则源时不生成法律条号。

## 6. 并发、幂等与调用边界

- A 的 operation identity 绑定 actor/project/file/SHA/parse tuple/schema/prompt/model 和 input fingerprint；同 key 重试恢复原结果。
- 模型调用在数据库事务和文件锁之外。短事务 claim，远程计算，短事务校验发布。
- GET、页面 mount、展开、复制、刷新和历史重开均不得调用模型或创建事实。
- A 的模型失败不删除 deterministic facts；状态保持机器可读且可恢复。

## 7. 验收

- A：协议拒绝伪 locator、错 SHA/parse、错项目 award、非法金额、未知字段；验证通过才发布且重启后可重读。
- B：amount-only 四行保留 null 数量/单价和 67000 declared sum；算术逐行 NOT_EVALUATED，line sum 可 PASS；v1/v2 snapshot 共存。
- C：04 双方 typed 差异、单侧未知、冲突和 frozen evidence 真实持久化；旧 v1 共存。
- 01—06 真实原件在同一 final candidate 上跑正常登录、上传、准备、确认、Promotion、ReviewRun、八项、定位、刷新、新浏览器、后端重启和历史权限。

## 8. 2026-09-25 fixed-review remediation addendum

This addendum narrows the remediation of review baseline
`6fea0feafa207e65755d82afbd4e26fdade2e8df`. It does not change the page
information architecture, Candidate authority, Confirmation/Promotion semantics,
or the ReviewRun lifecycle.

### 8.1 Approved compatible corrections

- Persisted `ATTENTION`, `REVIEW`, and unknown finding states fail closed in the
  eight-category projection and remain visible in copied opinions.
- Recovery of an A-mini inner operation uses the original actor, operation key,
  input fingerprint, file/SHA/parse/role/identity tuple, and provider provenance.
  A durable inner success may complete a missing outer stage without another
  provider call. Unknown outcomes retain the original key.
- Numeric candidates are grounded against the exact frozen excerpt and nearby
  server text. Signs, percentage/per-mille suffixes, amount labels, units, and
  type-specific decimal scales are validated independently of model context labels.
- F02 evidence first verifies exact frozen locators. One unavailable comparison
  side no longer deletes the other side's reliable evidence. Equivalent facts at
  the same frozen span are collapsed; conflicting spans remain ambiguous. Facts
  published after `ContractReviewRun.requestedAt` cannot change that run's source
  projection.
- Template comparison requires an explicit bounded template section. Attachment
  coverage requires every reference to have a unique readable frozen match;
  title-only, one-line, or ambiguous same-name candidates remain attention.
  A parsed copy count remains an insufficient fact until an authoritative copy
  requirement is also persisted.
- The Java semantic adapter consumes only the existing allowlisted FastAPI error
  codes and maps them to endpoint-local safe reasons. The asynchronous worker
  preserves executor-proven failure and outcome-unknown reasons on the durable
  attempt/step instead of replacing them with a generic worker reason. Error
  messages and response bodies are not persisted.

These corrections use existing tables and compatible DTOs.

### 8.2 Contract fact protocol v2 — approved for implementation

Keep `/ai/v1/agent/contract-fact-candidates` v1 readable for compatibility and
add a versioned v2 internal Java-to-FastAPI protocol. The v2 request carries the
same immutable project/file/SHA/parse/role/identity authority plus a bounded,
server-owned evidence catalog. Each evidence item has an opaque `evidenceId`,
exact original slice, hash, and code-point range and is no longer than the Java
publication limit. The model selects `evidenceId` and returns typed values; it
does not return page numbers, source locators, excerpts, or offsets.

FastAPI resolves selected IDs back to exact server slices and returns:

- accepted candidates with server-derived locator/excerpt/offset;
- rejected provider row summaries with stable provider ordinal, stage, and safe
  reason code;
- batch status (`EMPTY`, `PARTIAL`, `COMPLETE`, or `FAILED`) and scope coverage;
- per-transport/repair metrics containing only identity, status, safe reason,
  duration, finish reason, and provider usage counts when supplied.

Malformed top-level envelopes and input identity mismatches still reject the
whole response. A well-formed batch may keep valid rows while durably recording
invalid rows. No prompt, original text, model output, credential, or token value
is stored in the audit rows.

Extraction calls use explicit `CORE`, `LINE_ITEMS`, or `AWARD` scope and fixed
input/output budgets. Empty successful output is distinct from provider or
protocol failure. A partial line group cannot be described as a complete line
set or used to establish a complete line sum.

### 8.3 Minimal additive DM8 migration — approved for controlled integration execution

One new controlled Contract migration is proposed:

1. widen `PROJECT_EXTRACTION_RESULT.VALUE_AMOUNT` from `DECIMAL(18,2)` to
   `DECIMAL(20,4)`;
2. widen `CONTRACT_CANDIDATE_LINE_ITEM.UNIT_PRICE` from `DECIMAL(18,2)` to
   `DECIMAL(20,4)`;
3. add `PROJECT_FACT_EXTRACTION_REJECTION`, owned by
   `PROJECT_FACT_EXTRACTION_ATTEMPT`, for provider ordinal, rejection stage,
   reason code, safe field-shape/hash, and timestamp;
4. add `CONTRACT_MODEL_CALL_AUDIT`, owned by exactly one A-mini attempt or one
   ReviewStep attempt, for call ordinal/phase, request fingerprint,
   provider/model identity, outcome, safe reason, duration, finish reason, and
   optional provider token counts.

Amounts that represent currency remain limited to two meaningful decimal places
by Java validation. Quantity and unit price may use four. The widening prevents
DM8 from silently rounding valid quantity/unit-price evidence while preserving
the existing `BigDecimal` and `compareTo` rules.

No existing migration is edited or rerun. The user has authorized this new
migration only on the already verified integration DM8, after a fresh catalog,
backup, authority-constraint, and worker-risk preflight. Any changed preflight,
partial application, or destructive risk stops execution. Customer/production
DDL remains outside the authorization.
