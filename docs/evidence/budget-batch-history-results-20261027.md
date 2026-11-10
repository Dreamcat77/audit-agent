# 预算历史按上传批次展示及结果整理

本次把预算历史入口从单个材料任务改为上传批次。批次归属继续取自既有 `BUDGET_MATERIAL_BATCH` / `BUDGET_MATERIAL_SOURCE`，不按相同时间或相似文件名猜测批次，也不把批次当成正式 Project。无批次的早期任务仅在有上传操作记录证明用户归属时保留单文件入口；无法证明归属的历史行不出现在新增列表中。

## 行为和修改范围

- 新增 `GET /api/v1/budget/material-uploads/batches`，按批次分页，支持材料名称和业务时区日期筛选；命中一份成员材料时返回整个批次。
- 新增 `GET /api/v1/budget/material-uploads/batches/{id}`，读取完整成员清单；兼容以旧任务 ID 定位其已持久化批次。不同用户不能打开该批次。
- 当前上传结果和历史详情共用批次结果页；同批多文件相同依据的结果只展示一次，不累加重复金额。项目、年度和部门不同的记录分别展示；金额或意见不同的记录保留。
- 页面先显示材料数、科目数和需核对项，再显示差异清单、项目分组与科目金额对照；可展开本批材料状态及原有处理说明，保留来源文件和整批 Excel 导出。
- 保留 FAILED、UNKNOWN、部分完成和未上传状态之间的区别；处理中读取只轮询查询，不重提解析、不改变上传操作身份。
- 不改金额规则、OCR/AI 决策、数据库 schema、Redis、Nginx 或现场配置。

后端修改：`BudgetMaterialController.java`、`BudgetMaterialBatchService.java`、`BudgetMaterialBatchRepository.java`、`InMemoryBudgetMaterialBatchRepository.java`、`Dm8BudgetMaterialBatchRepository.java`，以及两份批次回归测试。

前端修改：`api/audit.ts`、`api/types.ts`、`BudgetCheckPage.tsx`、`BudgetHistoryResults.tsx`、`BudgetResultHistory.tsx`、`BudgetFileResults.tsx`、`budgetBatchResult.ts` 及测试、`budgetResultState.ts` 及测试、`styles/budget-results.css`。

可重复验证工具：`tools/test-budget-batch-history-local.py`、`tools/test-budget-batch-history.mjs`、`tools/BudgetBatchHistoryReadOnlyProbe.java`。浏览器工具只允许连接本地后端，只创建合成测试材料；密码由离线工具生成 delegating hash，明文只在父子进程内存及标准输入传递，不写文件或日志。

## 后端验证

共享目录已有大量其他任务的未提交修改。直接共享目录校验先出现合同/采购测试失败，随后又遇到无关采购源码的重复方法/缺少 import 编译错误；没有为通过本次测试去修改这些源码。

隔离目录 `.runtime/budget-batch-history-validation-20261027` 使用基线 `35f95499c4d5efa475dffc3a3628222750124672` 的版本化源码、文档和测试夹具，只覆盖本次七份后端源码/测试文件。该目录与用户工作树分开，不是可直接替换测试机当前全部功能的发布包。

已安装 JDK 17.0.18 下，在该目录的 `backend` 执行：

```powershell
.\mvnw.cmd clean verify
```

2026-10-27 观察结果：`BUILD SUCCESS`；3144 tests、0 failures、0 errors、15 skipped。新批次状态/归属测试 5 项，后台批次 HTTP 集成测试 15 项均通过。首轮隔离目录因未复制仓库级文档/质量夹具失败；补齐基线夹具后重新完整执行，以上为最终成功结果。

日志：隔离目录的 `batch-history-verify.log`；JUnit 报告位于 `backend/target/surefire-reports`。

## 前端和真实本地 HTTP

```powershell
cd frontend
node --test src/features/budget/*.test.ts
npm run build
```

观察结果：22 tests passed；Vite production build 成功，存在原有 chunk-size / dynamic-import warning。

在仓库根目录设置已安装 JDK 17 后执行：

```powershell
python tools/test-budget-batch-history-local.py E:/audit-agent-new/.runtime/budget-batch-history-validation-20261027/backend
```

观察结果：`LOCAL_BACKEND_HEALTH=UP`，浏览器报告 `status=PASS`、`errors=[]`，Chrome 155.0.8059.85。

这里使用真实 Spring HTTP、CSRF、登录和后台处理，local profile 的内存 Repository / Fake 外部适配器；没有用浏览器 API mock 代替批次查询。两份合成 TXT 组成一个批次，另有一批损坏 XLSX。确认两条历史记录、成员名筛选返回整个批次、同一项目两条科目去重、超支项优先、两份来源材料状态、单一返回入口、整批重新打开、Excel 主表两条/差异表一条、失败批次提示及 390px 无横向溢出。

本地页面证据位于 `.runtime/budget-batch-history-browser-20261027/`：`batch_history_desktop.png`、`batch_results_desktop.png`、`batch_results_mobile.png`、`batch_results.xlsx`、`browser-check.json`。这些是合成材料的本地截图，不是测试机部署后截图。

## 测试环境只读验证

对用户指定测试环境执行真实 `curl --silent --show-error --max-time 15 http://<TEST_HOST>/api/v1/health`，返回：

```json
{"code":200,"message":"success","data":{"service":"audit-backend","status":"UP"}}
```

经已有受控 SSH 通道，在测试机 JDK 17 / 实际 DM8 上编译并执行新增适配器及 `BudgetBatchHistoryReadOnlyProbe`。复用现场注入的数据库环境变量；连接设置 read-only，只执行 SELECT，不输出材料名称、正文或凭据。

观察结果：

```text
BUDGET_BATCH_HISTORY_DM8_READ_ONLY=PASS total=46 firstPage=2 secondPage=2 syntheticMatches=12 writes=0
```

检查包含实际 UNION/CAST/ROW_NUMBER/INSTR SQL、分页不重复、材料名与日期组合筛选、无匹配材料名及不存在用户的空列表，并逐条核对返回批次/早期任务的所有者。

## 交付边界

`git diff --check` 通过。未覆盖或清理其他任务的未提交源码，未提交或推送 Git。

本次未更换测试机后端 JAR 或前端静态资源。测试机新批次 API 与页面整链部署验收为 `NOT VERIFIED`；真实 DM8 读取上述验证通过，不等同于已部署。Redis、OCR/模型链路和国产服务器兼容本次未改变，也未重新验证。
