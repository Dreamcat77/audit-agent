# 预算差异、采购报告与可续传上传（2026-10-16）

范围：预算疑似错支/漏项、差异标红与清单；采购已保存完整报告导出；预算、采购材料及项目材料的真正分片上传。保留页面结构与原业务入口。

## 行为

- 上传材料是预算比较的唯一数据依据。只在同批材料均读取完成、有明确可比项目及科目、存在对应申报/批复范围时提示疑似错支或漏项。失败、未知、缺少快照、缺少批复、项目/年度/部门不明或冲突不能当作确定差异。超支使用 BigDecimal 精确比较。
- 保留已有信息卡布局，差异科目与金额标红，增加紧凑差异清单；原 Excel 导出增加“差异清单”工作表。
- 采购结果增加“导出完整报告”，DOCX 覆盖预审、询比价、黑名单、招标合规、围串标、拆分采购六部分。读取已保存结果，不重新执行 AI；显示报告与当前报告不一致时返回 409。缺材料不表述为已通过审查，旧审批状态提示不进入导出内容。
- 8 MiB 起采用 4 MiB 分片，单文件上限沿用 500 MiB。分片及完整文件 SHA-256 校验；流式合并，不一次性把完整文件载入 Java 内存。
- 用户/业务类型/不可变上传操作组成隔离身份，清单和分片事实持久化 DM8；原文件通过既有 FileStoragePort 保存。小文件继续原上传入口。
- 后台合并使用带令牌的 120 秒租约，每 30 秒续租，完成事务后才清理临时分片。重复完成返回原业务结果；中断后保留原身份，既有业务幂等机制处理提交结果未知后的重试。旧令牌不能完成或释放新租约。
- 重新打开同一浏览器、同一用户并重新选择原文件可补传缺失分片。上传完成后的后台解析无需停留在窗口。关闭浏览器后不能继续读取本地未上传字节，清除浏览器记录会丢失本地操作索引。
- 未完成上传的临时分片目前保留以便续传，不自动过期删除；已完成清理失败可在完成请求重放时再次清理。未引入第二套文件存储或 Redis 事实源。

## 本地验证

Windows / Java 17.0.18：

```powershell
cd backend
.\mvnw.cmd clean verify
```

最终 `material-v24-final-verify.log`：2296 tests，0 failures，0 errors，2 skipped，BUILD SUCCESS。包括预算正反例、分片乱序/重复/缺失/篡改/未知存储、合并并发、过期租约与旧令牌、回调或完成事务失败的原操作重放、HTTP 身份与 CSRF、报告过期 ID 拒绝和 DOCX 内容结构。

```powershell
cd frontend
npm run build
node --experimental-strip-types --test src/features/procurement/materials/materialSha256.test.ts src/features/budget/budgetDifferences.test.ts src/features/procurement/review/procurementReviewWizardStaticContract.test.ts
```

构建通过，3 tests 通过。SHA-256 校验标准向量和跨块边界，普通 HTTP 环境不依赖 WebCrypto。

浏览器测试命令：

```text
node tools/build-procurement-browser.mjs frontend/dist .runtime/material-v24-browser-final
node tools/test-budget-material-background.mjs <chrome109 executable> .runtime/material-v24-browser-final chrome109-v24-final
node tools/test-budget-material-background.mjs <chromium executable> .runtime/material-v24-browser-final chromium-v24-final
```

Chrome 109.0.5412.0、Chromium 151.0.7922.34 均通过，无 pageerror。包含 12 文件、21 MiB 文件真实分片请求/丢失响应重放、文件夹拖入、页面离开刷新、历史查询/分页/返回、三种差异标红、Excel 两个工作表及 DOCX 下载入口。截图已检查，原页面卡片结构保留。浏览器测试使用合成 API，不能替代真实 DM8 端到端测试。

## 构建身份

- 后端 JAR SHA-256：`b1d994cb5958cb47b3c06f24957ebfb04ac8b4ed96c7c4640798d17d28f2e15b`
- 兼容前端归档 SHA-256：`5d8960cc52b954bfa8d0e25706d1bcf8f52baf5bed4b3e47b313aef8824b26ea`
- 分支：`fix/budget-home-split-risk-20261014`；本次未提交、推送 Git，也未制作甲方更新包。

## 远程验证

26 / Kylin Linux / Java 17 / 真实 DM8 与现有 AI 服务：

```text
MaterialTransferPlatformCheck check|apply|probe V054.sql
DM8_TRANSFER_CLOB_PART_DUPLICATE_EXPIRED_LEASE_TOKEN_FENCE_ROLLBACK=PASS

BudgetBackgroundRunner /home/admin/material-v24-20261016/probe.py
budget: bytes=83887943, parts=21, resumedMissingParts=20, restart=true, PASS
BUDGET_WRONG_OMITTED_EXCESS_DM8_REOPEN_AFTER_RESTART=PASS
procurement: bytes=9441495, parts=3, duplicate/replay verified, PASS
PROCUREMENT_INTAKE_DOCX_SIX_SECTIONS_SAVED_REPORT_NO_REGENERATION=PASS
project: bytes=83887943, parts=21, duplicate/replay verified, PASS
MATERIAL_TRANSFER_REAL_HTTP_DM8_RESTART_BUDGET_PROCUREMENT_PROJECT=PASS
SYNTHETIC_BUDGET_ACTOR_DISABLED_AUDIT_AND_TASKS_RETAINED
```

大 XLSX 用少量真实合成业务行及未压缩的二进制填充构造，验证约 80 MiB 上传与存储，不能据此声称验证了 80 MiB 文本语义分析或 500 MiB 上限。预算在第一个分片后真实重启后端，重新登录后仍只收到原第 0 片；乱序补传剩余 20 片。错误片、缺片完成、更换文件清单、匿名读取、过期报告 ID 均被拒绝；重复完成返回原业务结果。预算两份合成申报/批复形成三种差异，再次真实重启后 CLOB 结果完全一致。采购实际调用现有 AI，解析完成；DOCX 通过 ZIP/XML 结构和六个章节检查，导出后报告 ID 保持不变。

部署结果：

- release：`/opt/audit-agent/releases/material-v24-20261016.zUf8nY`
- recovery：`/opt/audit-agent/update-backups/material-v24-20261016.7xfzfV`
- previous：`/opt/audit-agent/releases/budget-history-v23-20261016.iZPLJ7`
- 配置哈希全部不变；未修改 AI 地址/密钥/名称、Nginx、Redis 配置。
- 入口 HTML 和 3 个直接引用静态资源经 HTTP 对照实际文件一致。
- 实际 curl 健康响应：`{"code":200,"message":"success","data":{"status":"UP","service":"audit-backend"}}`。
- 仅新增 V054 上传元数据表、索引及合成测试资料/审计，不修改既有业务资料。
- 回滚：锁定更新操作并切回上述 previous，再重启后端；保留 V054 表、文件存储和全部已保存结果。不要删除 AUDITDB 或文件根目录。

甲方 ARM、离线更新流程及其模型网关：NOT VERIFIED。

V054 只新增两张上传表和索引；已有对象漂移停止执行。首次平台预检发现实际 DM8 Catalog 使用 `INT` 而非检测器预期的 `INTEGER`，已按实测精确类型修正检查器，未修改原 DDL 或既有业务数据。增量表在代码回滚时保留。

## 主要文件

- `budget/application/BudgetUploadedMaterialAnalyzer.java` 与对应回归测试。
- `features/budget/BudgetFileResults.tsx`、`budgetDifferences.ts`。
- `procurement/application/ProcurementReportDocument.java`、`procurement/api/ProcurementReportExportController.java` 与对应测试。
- `common/material/transfer/`、`integration/dm8/Dm8MaterialTransferRepository.java`、本地内存适配器。
- `features/procurement/materials/materialSha256.ts`、`resumableMaterialUpload.ts` 及预算/采购上传适配。
- `V054__material_resumable_transfer.sql`、`MaterialTransferPlatformCheck.java`、`test-material-transfer26.py`。
