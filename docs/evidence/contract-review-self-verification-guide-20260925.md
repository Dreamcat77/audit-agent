# 合同审核统一收口本人复验说明（2026-09-25）

状态：SERVICES AND DATA RETAINED / OVERALL PARTIAL

## 1. 候选与构建身份

- branch：feature/contract-final-closeout-20260923
- HEAD：e86e6dc59409e0d7e3785882f5b4e1c87e0f07df
- CODE_ROOT：D:\code\audit_agent_1D\.worktrees\contract-final-closeout-20260923
- Backend JAR SHA-256：085954318B987B014D6F6D702901E234C7EEBE9555EA05DCE26995625185C4F9
- Frontend bundle：index-BYklJkJy.js
- Frontend bundle SHA-256：918B6ADF4ABC90CCFF010DBAD212BF625856DB695E110BC5FF0B431B5D567A59
- 当前是未提交 WIP。复验时同时记录 git status、artifact hash 和当前 diff，不能只按 HEAD 判断代码身份。

## 2. 当前入口与环境

- Frontend：http://127.0.0.1:5173
- Backend：http://127.0.0.1:8888
- FastAPI：http://127.0.0.1:8001
- DM8：Phase 1B 已迁移的集成 DM8 8。
- Redis session namespace：audit-agent:contract-final-closeout:20260923
- FILE_STORAGE_ROOT：CODE_ROOT\.runtime\final-closeout\integration\storage
- AI_TEMP_ROOT：CODE_ROOT\.runtime\final-closeout\integration\ai-temp
- LOG_ROOT：CODE_ROOT\.runtime\final-closeout\integration\logs
- 原件目录：D:\code\audit-agent-demo\智慧维修系统_合同审查测试材料_20260917，只读。

使用已提供的测试应用账号从正常登录页面建立会话。凭据没有写入本说明、源码、配置、trace 或日志。

## 3. 当前进程

| 服务 | PID | 端口 |
| --- | --- | --- |
| Backend | 26524 | 8888 |
| Frontend | 26804 | 5173 |
| FastAPI | 2692（pids.json 启动台账 9164） | 8001 |
| Browser 1 | 5012 | CDP 9223 |
| Browser 2 | 14904 | CDP 9224 |

进程和端口可能在人工复验前变化。必须以 .runtime\final-closeout\integration\pids.json、实际 command line、CODE_ROOT 和 netstat 四者重新核对，不能只按 PID 操作。

## 4. 启动与停止命令

从 CODE_ROOT 执行全套启动：

powershell.exe -NoProfile -ExecutionPolicy Bypass -File .runtime\final-closeout\private\start-integration.ps1

fresh build 后只启动 Backend：

powershell.exe -NoProfile -ExecutionPolicy Bypass -File .runtime\final-closeout\private\start-backend-after-verify.ps1

启动两个隔离浏览器：

powershell.exe -NoProfile -ExecutionPolicy Bypass -File .runtime\final-closeout\private\start-browser.ps1

powershell.exe -NoProfile -ExecutionPolicy Bypass -File .runtime\final-closeout\private\start-second-browser.ps1

启动前必须重新核对 reference env SHA、provider identity、worker claim/lease predicate与端口占用。当前服务已经保留时不要重复启动。

停止方式：

1. 读取 .runtime\final-closeout\integration\pids.json。
2. 用 Get-CimInstance Win32_Process 核对 PID 的 executable、command line 和 CODE_ROOT。
3. 用 netstat 核对 PID 与端口。
4. 只对身份完全匹配的单个 PID执行 Stop-Process -Id 目标PID。
5. 浏览器同样只停止身份匹配的 5012/14904。
6. 不执行全局 Java/Python/Node kill；停止服务不删除 storage、日志或业务数据。

## 5. 01—06 固定对象

| 套件 | Project 名称 | ProjectId | ContractId | exact runId |
| --- | --- | --- | --- | --- |
| 01 | FINAL-CLOSEOUT-01-20260923130141 | b2c7241d-f992-4e6b-bf02-dc660a1e9835 | ce72edc2-a956-4b5c-9409-0790cf3b48e3 | 515ffc90-90d5-4ee6-87ea-9f16b87e456f |
| 02 | FINAL-CLOSEOUT-02-20260923142721 | 4f4db840-4345-490b-bbb7-0e2babc245c7 | 161f879a-c3dc-438c-a074-9de285f41ee1 | 3c075904-b86f-41ad-94cc-699f74b82f03 |
| 03 | FINAL-CLOSEOUT-03-20260923144543 | ff3a481e-acb9-443d-b7a5-a743005b648b | d1480490-a706-436a-bc93-285926c91743 | 0242c3f1-82f0-449c-8b29-31d7006cd3a0 |
| 04 | FINAL-CLOSEOUT-04-20260923145450 | 5cd5f4e4-1879-4c43-b8c6-6cfac719a830 | 826070d0-209b-45fd-9108-2908ef3a2df6 | 6ae1fe62-09e9-4a94-9bf7-c367b11bbaff |
| 05 | FINAL-CLOSEOUT-05-20260923145918 | baa8f5aa-a30b-4295-a97f-b78f93403466 | 8efa6216-f830-40e3-a86d-6c3195880681 | e9ed545b-2cbb-44ff-9314-9c34869bd2b5 |
| 06 | FINAL-CLOSEOUT-06-20260923150445 | a6246333-3cf0-4b6a-8647-e7f7ea9084a3 | c387e176-dba2-4a03-a478-93bd7f575e7a | 70470251-3e07-4189-a3b7-45cf8cd64ac1 |

02 还有一个保留的前置失败 Project e8ddd6e3-9dbf-451f-a55c-5ef744679605。它因原合同号重复而未形成 Contract/run，供验证 Promotion 事务回滚，不应删除或修改。

## 6. 双入口、定位与复制复验

1. 登录后从合同审核中心的待处理/项目入口打开上表任一 Project。
2. 进入合同审核工作区，核对 URL 或页面状态中的 exact runId 与上表一致。
3. 再从审核记录入口打开同一 run，确认 runId、frozen Contract 和材料 tuple 未变化。
4. 刷新页面；刷新、展开类别和复制意见不得产生新的 ReviewRun。
5. 右侧确认八项固定顺序，默认全部折叠，一次只展开一个一级类别。
6. 01 展开量化或风险项，分别点击合同、响应、采购来源；左侧应切到对应 frozen file，并对可靠 locator 滚动/高亮。
7. F02 人工正式事实若显示 SOURCE_UNAVAILABLE，应只说明暂无法精确定位，不得伪造页码。
8. 复制本项意见和综合意见；内容应来自持久结果，未覆盖范围应明确写出。
9. 在第二浏览器登录后按历史入口打开同一 run，确认 exact run 和冻结材料不变。
10. 后端重启后重复步骤 2、3；不得生成第二个 AuditTask 或重新调用模型。

## 7. 业务重点

- 01：成交金额与供应商 PASS；四条 AMOUNT_ONLY 行算术为业务性 NOT_EVALUATED；line sum PASS。
- 02：F02 成交金额 FAIL，合同 69,000 对通知 67,000；其余金额大写、行合计、供应商匹配。
- 03：服务条款差异应在语义/条款结果中保留。
- 04：违约责任差异应突出，不能被“资料不足”统一覆盖。
- 05：校园访客预约系统通知必须保持 excluded；最终 run 只含 4 份同项目材料。
- 06：扫描通知 parse method 应为 OCR，engine 应为 PADDLEOCR_3.7.0。
- 六套正式 run 都应为 COMPLETED。若读取到不同 runId 或 mutable live file，应停止并记录为回归。

## 8. 自动提取与人工字段

本轮自动能力是原件解析、OCR、deterministic/AI role assessment 和候选线索。A-mini 正式结构化事实抽取仍未启用。

六套均人工核对或补齐：contractNo、contractType、contractAmount、amountInWords、lineItems、awardNotice。lineItems 为四条 AMOUNT_ONLY，不伪造数量/单价。为共享库唯一约束添加的 E2E contractNo 后缀属于人工测试修正。

## 9. 本轮新验证与仍未验证

本轮新记录验证：

- 01—06 原件上传、解析，06 OCR。
- Candidate/Confirmation/Promotion 与正式 Contract。
- 六个 exact ReviewRun、F02、语义/违约/期限结果。
- 八项页面、复制意见和代表性跨文件来源点击。
- refresh、第二浏览器、backend restart 后 exact-run reopen。
- 匿名 401。
- DM8 写入、读取、重启后持久化。
- focused/full automated gates 和 fresh build identity。

仍未验证：

- 第二个低权限账号的跨用户 owner isolation 真实浏览器链。
- OA 集成、客户部署和客户模型集成。
- A-mini 正式事实抽取。
- C typed 双方值持久协议。
- 第 2/5/6/7 类超出现有输出范围的完整业务覆盖。

## 10. 证据绝对路径

- D:\code\audit_agent_1D\.worktrees\contract-final-closeout-20260923\docs\evidence\contract-eight-category-acceptance-20260925.md
- D:\code\audit_agent_1D\.worktrees\contract-final-closeout-20260923\docs\evidence\contract-evidence-navigation-regression-20260925.md
- D:\code\audit_agent_1D\.worktrees\contract-final-closeout-20260923\docs\evidence\contract-review-integration-operation-sheet-20260923.md
- D:\code\audit_agent_1D\.worktrees\contract-final-closeout-20260923\docs\evidence\contract-review-unified-closeout-problem-matrix-20260923.md
- D:\code\audit_agent_1D\.worktrees\contract-final-closeout-20260923\docs\evidence\contract-review-release-ledger-20260922.md

总体口径保持 PARTIAL；不要把 01—06 通过扩写为 A-mini/C/OA/客户环境已完成。
