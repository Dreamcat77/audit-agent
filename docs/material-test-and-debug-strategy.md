# Material test and debug strategy

## 1. Goal

客户材料问题必须能在不默认带走敏感正文/原件的前提下定位。本策略定义 P0
`SANITIZED_DEBUG_BUNDLE`。当前已经实现受角色保护的 `METADATA_ONLY` bundle：请求、状态和
审计事实持久化到 DM8，artifact 写入既有 `FileStoragePort`，下载前验证大小与 SHA-256。
`CUSTOMER_POLICY_APPROVED_REDACTED` 仍 fail closed，尚未实现，也不代表已获得客户数据授权。

## 2. Default-deny content policy

默认 bundle 绝不包含：

- 原始合同全文或大段可复原正文；
- 原始 PDF、DOCX、图片或 ZIP；
- prompt；
- model raw response；
- password 或 password hash；
- cookie、session id、CSRF token、API token；
- private key、证书私钥或真实 secret；
- 完整 JDBC URL、内部拓扑、未脱敏用户名/地址。

日志和 bundle exporter 必须使用 allowlist，而不是先收集全部内容再尝试删敏感字段。生成过程本身
需要 Operation Audit；bundle 应带 schema version、生成时间、生成者和内容分类。

## 3. Safe manifest

默认允许的诊断字段如下。ID 只用于关联已授权系统内事实，不应包含显示名或正文。

### File identity and shape

- file SHA-256；
- MIME、extension、size；
- page count；
- archive entry count/depth/size summary；
- storage presence status，不包含真实 storage absolute path。

### Parse/OCR

- parse route 和 parse method；
- parser/OCR engine 与 version；
- duration、confidence；
- per-page character count；
- safe reason code；
- parser warning code。

### Document role

- role classification；
- confidence 和 candidate role codes；
- safe evidence locator（page/paragraph/span/hash），不携带原文 excerpt。

### Typed extraction

- extraction field name；
- field status（FOUND/MISSING/CONFLICT/INSUFFICIENT 等）；
- page/paragraph/locator；
- redacted value 或 salted/controlled hash，不默认输出原值。

### AI operation

- operation；
- provider capability、model、model version；
- duration、chunk count、retry count；
- schema validation status；
- safe failure/reason code；
- evidence locator validation counts；
- 不记录 prompt、input text、raw completion 或不可控 provider error body。

### Correlation

- requestId、traceId；
- projectId、fileId、reviewRunId；
- 若有关联 F02，只记录真实 AuditTask taskId；不得以其他 UUID 代替。

### A-mini P0 event correlation

默认 `INFO` 技术日志使用固定 schema version 和 allowlist 字段关联 Java 与 FastAPI。现场应按
同一个 requestId、projectId、fileId 和操作身份摘要顺序检查 `PROGRESSION_PRECHECK`、
`AUTHORITY_CHECK`、`FACT_SEGMENTATION`、`AI_HTTP_STARTED/COMPLETED`、
`FACT_RESULT_VALIDATION`、`DM8_COMPLETION` 与 `RECOVERY_TERMINAL`。其中：

- `GET .../contract-fact-extractions/latest` 的 409 是读取时 Authority 未满足，不能等同于用户主动
  `POST .../progression/resume` 的 409；
- FastAPI 200 只证明下游返回，不能单独证明 Java 校验和 DM8 complete 已提交；
- 没有 FastAPI 事件只能在 Java 明确记录 `NOT_CALLED` 时证明未外呼；日志缺失本身不是证据；
- `RECOVERY_OUTCOME_UNCERTAIN` 必须保留原操作身份，不得通过自动换 key 或重复点击掩盖；
- 日志不记录 segment 内容、canonical text、prompt、completion 或 provider error body。

## 4. Suggested bundle layout

```text
sanitized-debug-bundle/
  manifest.json
  files.json
  parse-events.json
  role-assessments.json
  extraction-status.json
  ai-operations.json
  README.txt
  checksums.sha256
```

`manifest.json` 至少记录 bundle schema、application commit、配置版本的非秘密 fingerprint、时间范围、
生成主体、已应用的脱敏策略和显式包含项。生成后再次做 secret/content scanner；扫描失败则 fail closed，
不得导出。

## 5. Reproduction levels

1. `L0 metadata-only`：默认；先判断类型、路由、时长、空页、reason code、schema/evidence failure。
2. `L1 redacted-text fixture`：仅在客户政策允许且自动/人工复核脱敏后，用于 parser/role/extraction 回归。
3. `L2 redacted visual crop/page`：仅用于 OCR 字形、印章遮挡、版面与扫描质量问题。
4. `L3 anonymized document fixture`：独立批准、最小必要、限定保留期和访问范围。

每一级都要有 case ID、授权范围、保留期、删除责任和可复现命令。不得因为 L0 无法复现就自动升级。

## 6. CUSTOMER POLICY REQUIRED

OCR 字形、印章遮挡、手写体、扫描噪声或复杂版面通常无法仅靠 metadata/text-only artifact 复现。
在收集以下任一内容前，必须取得甲方明确政策和逐案授权：

- 问题页局部截图；
- 自动脱敏后的页面；
- 局部 crop；
- 脱敏文本 fixture；
- 匿名化 PDF fixture。

政策至少要回答：允许的材料类别、自动脱敏规则、人工复核人、跨环境传输方式、访问主体、保留期、
销毁证明，以及是否允许进入版本控制。默认答案均为“不允许进入 Git”。

## 7. Onsite-first workflow

```text
现场复现
  -> 生成 L0 SANITIZED_DEBUG_BUNDLE
  -> safe reason code / route / version 定位
  -> 能否由合成 fixture 复现？
       yes -> 离线最小回归
       no  -> CUSTOMER POLICY REQUIRED
              -> approved L1/L2/L3 minimum artifact
  -> 在同一版本和同一质量指标上回归
  -> 记录修复、验证与 artifact 销毁/保留结论
```

客户原件优先留在现场受控边界。应用团队接收的应是能解决问题的最小 artifact，而不是整包业务材料。

## 8. Current implementation boundary and extension criteria

当前 bundle 为单个 canonical JSON manifest，不是下列 suggested layout 的多文件压缩包。它仅投影
ReviewRun、material、step 和 attempt 的 allowlist metadata，对 actor 与业务 hash 使用受控摘要，
并具有生成、重开、列表和下载审计。生成结果不确定时保留 `UNKNOWN`，不冒充 AVAILABLE。

后续若扩展 bundle 内容或批准 redacted level，仍需满足：

- exporter 的默认输出通过正文/secret adversarial tests；
- 每个字段有 sensitivity classification 与 schema；
- reason code 稳定、无堆栈/路径/原文泄漏；
- bundle 可与 application commit、parser/model version、request/reviewRun 关联；
- 未授权 artifact 被技术控制阻断；
- 有生成、下载、访问和删除的 durable audit；
- 用安全 fixture 给出可执行的 offline reproduction command。
