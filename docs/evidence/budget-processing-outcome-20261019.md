# 预算处理状态与无结果说明

本文保留上一轮状态修正及当时的诊断记录。后续部分提取、真实模型结果和只读重放的最新观察见 `budget-model-extraction-20261020.md`；下文早期的四份拒绝结果不是最新验收结论。

范围：预算上传、历史列表、结果说明及原因保存。基于 `fix/budget-procurement-ready-20261019` 的 `4efea2f`。本轮修改尚未部署到 26，未打甲方更新包，未改写旧记录，未重发未知模型请求。

## 已确认原因

代表测试机后台日志与截图同一批材料对应：四份在 `BudgetUploadedMaterialReader.physicalLine` 被原文行定位校验拦截，另两份在 Python 侧返回 `MATERIAL_AI_UNGROUNDED`。这六份都不能解释成“材料不足”。诊断只读取事件、关联 ID、状态、错误码与代码位置，不导出文件正文、模型响应或密钥。

独立只读探针 `BudgetEvidenceReplayCheck` 复用已持久化的 COMPLETE 提取结果，校验提取策略、文件 SHA 与语义源组成的完整 fingerprint，不把 ledger SOURCE_HASH 误当作原文件 SHA。四份仍因重复引用无法唯一定位而拒绝，两份没有已确认完整结果而跳过。观察结果：`passed=0 skipped=2 rejected=4 modelCalls=0 businessWrites=0`。未宣称这批旧结果已恢复。

## 更改

- `BudgetProcessingOutcome` 只允许固定原因码映射为中文说明，不返回异常原文。原因存入既有 ERROR_DETAIL，任务/历史 API 返回安全说明，无新增数据库字段。
- 界面区分“解析中”“已完成”“已结束”。FAILED/UNKNOWN 显示“已结束 / 未生成分析结果 / 查看说明”，底层状态和禁止盲目重试规则保留。未知枚举不冒充完成。
- 已结束无结果的材料自动进入说明页；一批全部无结果也可查看说明，不显示成功提示、不带入旧风险分析选择。
- 原文引用跨行时，仅在字段值能唯一落到一条原文行时收窄证据；金额角色仍核对整条原文。重复引用、跨项目拼接、金额角色互换仍拒绝。此修正不等于已解决上述四份的重复引用问题。
- 模型未确认异常保留 cause，便于提取安全错误类别；没有改变 UNKNOWN 的持久化含义或自动重发请求。

## 验证

- Windows / JDK17：`cd backend; .\mvnw.cmd clean verify`，2424 tests，0 failures，0 errors，2 skipped，BUILD SUCCESS。日志 `.runtime/budget-outcome-clean-verify.log`。
- `cd frontend; node --experimental-strip-types --test <src/**/*.test.ts 展开的文件列表>`，748 passed。日志 `.runtime/budget-outcome-frontend-tests-final.log`。
- `cd frontend; npm run build`，通过。`node tools/build-procurement-browser.mjs frontend/dist .runtime/budget-outcome-browser-dist-v4`，原 CSS/布局保留，兼容资源生成通过。
- `node tools/test-budget-processing-outcome-view.mjs <Chrome109 executable> .runtime/budget-outcome-browser-dist-v4`：真实 Chrome109.0.5412.0、本地静态产物、模拟 HTTP。历史状态、具体说明、材料缺少批复与技术异常区分、整批无结果自动跳转且无成功提示，全部 PASS，page errors 为空。截图已查看。全套前端测试依赖 frontend 工作目录；从仓库根目录直接运行会因测试内相对路径报错，不作为产品故障或通过证据。
- 代表测试机只读 `BudgetEvidenceReplayCheck <六个关联任务 ID>`：无模型调用、无业务写入；四份重复引用仍拒绝，两份无确认结果跳过，见前述限制。独立工具与 class 置于隔离目录，不替换运行 class。

尚未验证：本轮产品修改的 26 正式 HTTP/DM8 发布链路、旧记录原因补齐、甲方 ARM64 与本地模型。本轮不包含 Git 融合或甲方打包。
