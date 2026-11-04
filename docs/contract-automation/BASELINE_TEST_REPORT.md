# 合同改造前测试基线

日期：2026-10-27。环境：Windows、本地共享工作树；基线 HEAD `35f95499c4d5efa475dffc3a3628222750124672` 加 BACKUP_REPORT 记录的既存未提交修改。本轮业务代码写入前已实际运行以下基线，不能与 HEAD 的历史测试数字混用。

## Java

命令：在 backend，显式 JAVA_HOME=JDK 17.0.18 后执行 `mvnw.cmd clean verify`。

结果：441 个测试类，3,208 tests，9 failures，5 errors，24 skipped，构建未通过。

失败集合：

- AutomaticProjectFileClassificationServiceTest：terminalFactAttemptsDoNotResendAndStillAdvanceToAvailableMaterialFacts（Mock 仍收到候选物化交互）。
- ProcurementMaterialBatchApiIntegrationTest：incrementalUploadsWaitForExplicitUnifiedAnalysisAndAutomaticallyPersistOneReport（READY 与 INCOMPLETE）。
- ProcurementMaterialBatchServiceTest：stagedSpreadsheetIsSavedAsPendingAnalysisAndCannotRunComparisonOnUpload（READY 与 COMPLETED_WITH_PLACEHOLDER）。
- 同类 explicitAnalysisRetainsPreviouslyConfirmedBidderWithoutRequestingConfirmationAgain、oneExplicitAnalysisProducesReportAndRetryReopensSameReport、explicitAnalysisRetriesSavedAiFailuresButReusesOnlyAvailableResults、unreadableSavedSpreadsheetDoesNotStopOtherFilesAndStillGeneratesPartialReport（TenderDocumentResult mock 为空）。
- 同类 oneUnreadableFileDoesNotDiscardAnotherFileResult（预期 NOT_APPLICABLE 未返回）、stagedUploadsAreRediscoverableWithoutRunningRiskAnalysisOrGeneratingReport（存在额外 Mock 交互）、unreadableMaterialStillReturnsAReportWithoutInventingFacts（PARSE_INCOMPLETE 与 COMPLETED_WITH_PLACEHOLDER）。
- ProcurementMaterialClassifierTest：quotationTemplatesAndConflictingSuppliersStayUnconfirmed、scannedQuotationUsesExplicitSupplierAndNeverTheProjectOrBudget、tenderContainingQuotationTemplatesDoesNotBecomeSupplierEvidence（分类语义差异）。
- ProcurementReportSnapshotTest：oldReportForDifferentMaterialScopeIsNotReturnedAsCurrentBatchResult（旧 scope 报告未查到）。

这 14 项是本次改造前实际复现，不意味着全部无关或可忽略。需按真实产品语义逐项修复/更新测试，不删除失败断言掩盖缺陷。源 Surefire 报告目录为 backend/target/surefire-reports，后续重跑可能覆盖，应以本记录和留存基线清单为准。

## 前端

- Node 24.14.0，从 frontend 跑 `node --test`，119 个文件：820 passed、1 failed。
- 唯一失败是 LoginPage.visual.test.mjs 没有设置 AUDIT_VISUAL_TEST_BROWSER。使用已安装 Chrome 路径后独立重跑：1 passed、0 failed，17.59 秒。
- `npm run build`：通过；既有 dynamic-import 和大 chunk 警告保留。当前没有独立的全仓 TypeScript 类型检查基线，不可把 Vite 构建描述为类型检查全部通过。
- 最初从错误工作目录/附加 test-isolation 参数运行失败，不算产品基线，已由标准命令纠正。

日志在 evidence/frontend-node-baseline-standard.log、frontend-visual-baseline.log、frontend-build-baseline.log。

## Python / AI / OCR

在本仓库 `.test-runtime/contract-auto-python` 创建独立 Python 3.11.9 venv，按项目 requirements-test.txt 安装测试依赖；没有变更系统 Python、既有 AI venv 或服务器 OCR 环境。

首次使用很深的 basetemp 路径出现 10 个检查点缓存写入/恢复失败（2,004 passed、13 skipped）。保持源码不变，将 basetemp 缩短后重跑全部：

```powershell
<isolated-python> -m pytest tests -q -p no:cacheprovider --basetemp=E:/audit-agent-new/.test-contract-baseline
```

最终结果：**2,013 passed、14 skipped、43 subtests passed**，59.66 秒，1 条上游 Starlette/httpx 弃用警告。短路径消除了缓存文件名的 Windows 路径长度影响。详细 JUnit 和日志为 evidence/python-baseline-shortpath.xml/.log。跳过项不代表真实 PaddleOCR/模型/外部环境通过。

## 真实环境边界

本基线没有调用真实模型，没有执行 DM8 迁移，没有部署 26，不能用 Mock/本地通过替代端到端验收。原业务库完整跨 schema 备份/恢复仍需核查；在未证明安全前只允许隔离数据库实验。
