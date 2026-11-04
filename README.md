# 内审系统智能体

本仓库是 Java 17 / Spring Boot 模块化单体、React/Vite 前端和独立 FastAPI AI/OCR
服务的共享基线。当前 P1-A/P1-B/P1-C 已完成源码基线为
`b3615b8adf81989c83f7135de1a7061df3636399`；本 pre-merge hardening 分支从该提交派生，
尚未合并 `main`。`main@dd0d02c…` 是 2026-09-14 的历史审计基线。
源码与可执行验证优先于历史文档。

## 当前架构

```text
Browser / React / Vite
  -> same-origin /api
  -> Nginx（部署层）
  -> Java 17 / Spring Boot modular monolith
       -> DM8（System of Record）
       -> Redis / Spring Session（会话与可丢失缓存）
       -> controlled filesystem（文件内容）
       -> FastAPI
            -> PaddleOCR
            -> OpenAI-compatible model provider
```

Java 内部依赖方向是 `API -> Application -> Domain`，Application 定义 Port，
Infrastructure Adapter 实现 Port。业务代码不感知 Nginx、TongHttpServer、Redis/TongRDS
或具体模型厂商。

以下身份永远不能互换：

`Project != ProjectFile != ContractCandidate != Contract != ReviewRun != AuditTask`。
`ContractReviewSession` 另有材料准备职责，不等同于上述任何身份。

DM8 是业务事实源。Redis/TongRDS 不是业务事实源；Python 原生依赖不嵌入 Java；人民币金额
使用 Java `BigDecimal` / `Money` 的数值比较语义。

## 当前功能边界

| 能力 | 当前状态 | 说明 |
|---|---|---|
| F01 预算审批与材料审查 | IMPLEMENTED | Java 与 DM8 Adapter/migration 已存在；客户 DM8 状态另行验证 |
| F02 合同确定性审查 | IMPLEMENTED | 五条 Java 规则、DM8 幂等任务/结果、失败收口与 durable finding acknowledgement |
| F03 黑名单 | IMPLEMENTED | 主数据、规则、告警与操作记录已有 API、Adapter 和 V018/V019 |
| F04 预算审批程序 | PARTIAL | `PM_DEMO_20260828` 规则集和持久化路径存在，正式甲方规则待输入 |
| Project Intake / 安全 ZIP | IMPLEMENTED | 不可变 ParseResult、角色与项目归属评估/人工决定、Candidate、可选中标依据、确认与 Promotion |
| OCR | IMPLEMENTED | PDFBox usefulness gate 后回退 OCR；图片直接 OCR；Java 经 FastAPI 调 PaddleOCR |
| Contract Semantic AI | IMPLEMENTED | 非权威语义 finding/evidence 随 ReviewRun 持久化；不能覆盖 F02 金额结论 |
| 违约、期限/解除审查 | IMPLEMENTED | Java 分析结果随 ReviewRun 持久化；客户专属规则另待验收 |
| Durable integrated ReviewRun | IMPLEMENTED | DM8 run、不可变 Contract/材料快照、step/attempt/result、server worker、`/latest` 与刷新/重启重开 |
| 审查收口 | IMPLEMENTED | finding 持久确认、版本化报告 metadata/artifact、仅脱敏 metadata 的 debug bundle |

本 pre-merge hardening 分支增加历史 AuditTask→精确 ReviewRun 重开、冻结事实展示、
通知书“成交”字段的有限确定性兼容，以及报告中的冻结证据展示。原始材料的
Candidate→Confirmation→Promotion→ReviewRun 全链验收仍在
[收口台账](docs/evidence/contract-review-release-ledger-20260922.md) 跟踪；
不得将只读解析测试或合成 Contract 回归写成该全链通过。

完整状态矩阵和判定依据见 [当前架构](docs/architecture.md) 与
[源码基线](docs/source-baseline.md)。UI 或 API 的存在本身不代表持久化完成。

## Project 材料链

```text
Project Intake
  -> ProjectFile
  -> safe content-addressed storage
  -> PDFBox / POI / OCR -> immutable ParseResult + ExtractionResult
  -> deterministic role assessment -> bounded AI role fallback（需要时）
  -> human RoleDecision -> project identity assessment -> human IdentityDecision
  -> ContractCandidate materialization
  -> optional Candidate-specific AWARD_NOTICE support authority
  -> human Contract Confirmation -> Promotion -> formal Contract
  -> durable ReviewRun -> F02 / semantic / breach / term results
```

根上传接受 ZIP、PDF、DOCX、PNG、JPG/JPEG。ZIP 内安全 allowlist 还包括 DOC、XLS、XLSX、
TXT 和 ZIP，但这不等于具备对应 parser：当前实际可解析的只有 PDF、DOCX、PNG、JPG/JPEG。
真实甲方模板和更广材料语料仍待验证。当前抽取器识别“中标金额/中标供应商”，
P1-B 验收材料使用“成交金额/成交供应商”；非空 AWARD_* 权威测试因此使用明确标记的派生合成 fixture。
该词汇兼容缺口仍是后续独立任务，不得把合成事实说成原材料自动抽取结果。

## 安全与运行

- 认证使用服务器端 Session Cookie 和 CSRF，不使用 JWT。
- `local` 使用内存 Session/Adapter；`local-dev`、`dev29`、`customer` 使用 Redis Spring Session，
  但用户、审计和业务事实仍写 DM8。
- 所有凭据、主机和真实路径通过环境变量注入；仓库只保留无秘密模板。
- `local-dev` / `dev29` / `customer` 的长期操作审计必须开启，否则启动失败。
- 正式关系数据库只支持 DM8；单元测试可以使用 Mock/Fake Repository。

## 本地验证

后端完整门禁：

```powershell
cd backend
.\mvnw.cmd clean verify
```

前端构建：

```powershell
cd frontend
npm.cmd run build
```

本地启动前按 [账号初始化](docs/runbook/account-bootstrap.md) 通过环境变量提供 Spring
Security delegating password hash。不要把原始密码、Hash、Token 或真实连接信息写进 Git。

## 文档入口

- [文档索引](docs/README.md)
- [当前架构](docs/architecture.md)
- [源码与环境基线](docs/source-baseline.md)
- [兼容性矩阵](docs/compatibility-matrix.md)
- [当前路线图](docs/roadmap.md)
- [材料测试与脱敏调试策略](docs/material-test-and-debug-strategy.md)
- [AI/OCR 质量策略](docs/ai-ocr-quality-strategy.md)
- [当前客户部署运行手册](docs/runbook/customer-deployment-current.md)
- [DM8 migration map](db/dm8/README.md)
- [静态 OpenAPI](docs/openapi.yaml)

## 验证语言

`VERIFIED` 必须同时说明命令、环境和观察结果。migration 文件存在不等于已部署到客户 DM8；
DNS、NAT 或证书存在不等于公网可访问；本地 Fake 成功不等于真实 AI/OCR 已验证。无法在本次
受控范围验证的外部状态统一标为 `NOT_VERIFIED`。

当前状态分别记录为 `IMPLEMENTED`、`VERIFIED_LOCAL`、`VERIFIED_INTEGRATION` 和
`NOT_VERIFIED_CUSTOMER`。P1-B/P1-C 在已验证集成 DM8 的证据见
[Candidate authority 验证](docs/evidence/candidate-cross-material-authority-verification-20260920.md) 与
[F02 completeness 验证](docs/evidence/phase1c-f02-structured-data-completeness-20260921.md)；
它们不构成客户部署验收。
