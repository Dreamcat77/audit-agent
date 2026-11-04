# AI / OCR quality strategy

## 1. Principle

AI/OCR 质量必须由同一套版本化 fixture、ground truth、指标、阈值和可重复命令证明，不能凭 UI
观感或“模型看起来更聪明”判断。真实客户材料是否可进入评测集受
[material test and debug strategy](material-test-and-debug-strategy.md) 约束。

## 2. Dataset structure

评测集至少分层：

- synthetic/public fixtures：可进入仓库，覆盖边界与 adversarial cases；
- customer-sanitized fixtures：甲方批准后进入隔离 artifact store，默认不进 Git；
- holdout acceptance set：禁止用于 prompt/规则调参；
- regression set：每个已修复问题加入最小可复现 fixture；
- stress set：大页数、表格、旋转、低 DPI、印章遮挡、空白页、混合项目和冲突字段。

每个 fixture manifest 记录：fixture ID、内容类别、ground-truth version、许可、SHA-256、页数、
期望角色/项目关系/typed facts/evidence、敏感级别及保留期。训练/调参集与 holdout 必须隔离。

## 3. Required metrics

### Document role

- accuracy；
- macro F1；
- confusion matrix；
- NEEDS_CONFIRMATION rate；
- 高置信错误率（重点 gate）。

### OCR

- page coverage；
- empty-page rate；
- selected CER（character error rate）；
- selected WER（word error rate）；
- page-order/rotation failure rate。

CER/WER 只在批准且有 ground truth 的选定页计算，不能把无法标注的样本默认为正确。

### Fact extraction

- exact match；
- normalized match；
- missing/false-positive rate；
- amount/date/name 等 typed-field 分项结果。

金额 normalized match 仍由 decimal normalization 与 `BigDecimal` deterministic comparison 计算，
不能让模型裁决金额相等。

### Evidence

- locator hit rate；
- unique locator rate；
- unsupported evidence rate；
- source ownership mismatch rate。

unsupported claim/evidence 必须计入失败，不能因展示文本“合理”而忽略。

### Cross-document

- precision；
- recall；
- conflict detection recall；
- mixed-project block recall；
- false merge rate（关键安全指标）。

### AI runtime correctness

- invalid schema rate；
- semantic retry rate；
- transport failure rate；
- unsupported-claim rate；
- correction success/failure rate；
- call-budget exhaustion rate。

### Performance

- p50；
- p95；
- max duration；
- pages/chars/chunks per request；
- timeout rate；
- resource profile（仅在批准环境采集）。

应分别报告 upload/parse/OCR/model/overall，不用一个总耗时掩盖瓶颈。

## 4. Evaluation protocol

```text
freeze fixture manifest + ground truth
  -> freeze application/parser/prompt/schema/provider configuration
  -> run deterministic preprocessing once per declared version
  -> run candidate A and B on identical inputs
  -> validate structure and server-owned evidence
  -> calculate the same metrics
  -> compare against absolute gates and baseline deltas
  -> retain sanitized report + command + environment
```

非确定性模型需预先规定重复次数、temperature/seed（若 provider 支持）与聚合方法。失败/timeout
不能从分母删除。质量报告必须列出 fixture exclusions 及原因。

## 5. Model and provider A/B

Qwen、DeepSeek、视觉模型、手写 OCR 或其他 provider 必须：

- 使用同一 fixture manifest 和 ground truth version；
- 使用相同输入边界、脱敏策略、输出 schema 和 evidence validation；
- 遵守相同 timeout/retry/call budget；
- 报告全部失败样本与性能分位数；
- 同时满足质量、安全、可运维性和成本约束。

仅当真实测试集证明当前能力不足，且候选在预注册指标上改善而不破坏安全 gate，才增加
`DOCUMENT_VISION`、`HANDWRITING_OCR` 或额外 reasoning provider。GPU 型号/数量、vLLM/SGLang
等是基础设施选项，不是质量结论。

## 6. Initial acceptance gates to calibrate

正式数值阈值必须由产品、审计与甲方基于真实风险批准；在此之前状态为
`BLOCKED_BY_CUSTOMER_INPUT`。无论阈值最终是多少，下列 hard failures 不应被平均分掩盖：

- 跨 Project 引用 evidence；
- 高置信自动错误 role 导致不可逆 promotion；
- mixed-project 未阻断而自动合并；
- 模型产生金额/供应商结论覆盖 Java deterministic fact；
- evidence locator 不存在却标记为 supported；
- 输出 schema 失败仍被保存为成功；
- 未授权客户正文进入日志、bundle 或测试仓库。

## 7. Regression report minimum

每份正式报告至少记录：

- command、commit、environment；
- fixture/ground-truth version 与 SHA-256；
- parser/OCR/model/provider/prompt/schema version；
- 所有必需指标、样本数和 confidence interval（适用时）；
- 与 accepted baseline 的 delta；
- failure case IDs，不含敏感正文；
- verdict：PASS / FAIL / NOT_VERIFIED；
- 已知限制和 CUSTOMER_INPUT_REQUIRED 项。

没有上述证据时只能写 `DESIGNED` 或 `NOT_VERIFIED`。
