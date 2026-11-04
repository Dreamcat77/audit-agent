# 合同审核工具化统一收口验收记录（2026-09-25）

状态：PARTIAL

本记录只陈述当前 worktree 的 fresh evidence。自动化 GREEN、HTTP 200 和页面外观均不替代原件业务验收。

## 1. 候选与构建身份

| 项目 | 实际值 |
| --- | --- |
| branch | feature/contract-final-closeout-20260923 |
| HEAD | e86e6dc59409e0d7e3785882f5b4e1c87e0f07df |
| worktree | D:\code\audit_agent_1D\.worktrees\contract-final-closeout-20260923 |
| 状态 | 未提交 WIP；未 reset、stash、push 或 merge |
| Backend JAR | audit-backend-0.1.0-SNAPSHOT.jar；SHA-256 085954318B987B014D6F6D702901E234C7EEBE9555EA05DCE26995625185C4F9 |
| Frontend bundle | index-BYklJkJy.js；SHA-256 918B6ADF4ABC90CCFF010DBAD212BF625856DB695E110BC5FF0B431B5D567A59 |
| reference env | SHA-256 1221AA61449609CD2DA399F0103F71292A751CBB72F5A99B7A65CC70008FB3D5 |
| DDL | 本次续跑没有执行 DDL；已批准的 B amount-only schema 在 E2E 前已存在并经 DM8 catalog 验证 |

## 2. 实际运行身份

| 服务 | 代码身份 | 监听 |
| --- | --- | --- |
| Backend | 当前 worktree 的上述 fresh JAR，PID 26524 | 8888 |
| Frontend | 当前 worktree Vite dev server，PID 26804；生产 bundle 另经 build 验证 | 5173 |
| FastAPI | 当前 worktree services/audit-ai source；Uvicorn PID 2692，启动台账 PID 9164 | 8001 |
| Browser 1 / 2 | 两个独立 authenticated browser profile | CDP 9223 / 9224 |

DM8 使用 Phase 1B 已迁移并验证的集成 DM8；Redis 使用独立 session namespace audit-agent:contract-final-closeout:20260923。storage、AI temp 与日志均位于本 worktree 的 .runtime\final-closeout\integration。参考 env、旧 demo storage、冻结 worktree和原始材料均未修改。服务、测试数据与隔离 storage 保留用于复验。

## 3. 01—06 原件业务验收

| 套件 | Project | Contract | exact ReviewRun | 结果 |
| --- | --- | --- | --- | --- |
| 01 一致基线 | b2c7241d-f992-4e6b-bf02-dc660a1e9835 | ce72edc2-a956-4b5c-9409-0790cf3b48e3 | 515ffc90-90d5-4ee6-87ea-9f16b87e456f | COMPLETED |
| 02 金额差异 | 4f4db840-4345-490b-bbb7-0e2babc245c7 | 161f879a-c3dc-438c-a074-9de285f41ee1 | 3c075904-b86f-41ad-94cc-699f74b82f03 | COMPLETED |
| 03 服务差异 | ff3a481e-acb9-443d-b7a5-a743005b648b | d1480490-a706-436a-bc93-285926c91743 | 0242c3f1-82f0-449c-8b29-31d7006cd3a0 | COMPLETED |
| 04 条款差异 | 5cd5f4e4-1879-4c43-b8c6-6cfac719a830 | 826070d0-209b-45fd-9108-2908ef3a2df6 | 6ae1fe62-09e9-4a94-9bf7-c367b11bbaff | COMPLETED |
| 05 异项目通知 | baa8f5aa-a30b-4295-a97f-b78f93403466 | 8efa6216-f830-40e3-a86d-6c3195880681 | e9ed545b-2cbb-44ff-9314-9c34869bd2b5 | COMPLETED |
| 06 扫描/资料不完整 | a6246333-3cf0-4b6a-8647-e7f7ea9084a3 | c387e176-dba2-4a03-a478-93bd7f575e7a | 70470251-3e07-4189-a3b7-45cf8cd64ac1 | COMPLETED |

六个正式 run 均完成全部步骤。ReviewRun 计数：SUCCESS/COMPLETED 6，FAILED 0，UNKNOWN 0。

02 另保留一个前置失败对象 e8ddd6e3-9dbf-451f-a55c-5ef744679605：原合同号与 01 触发全局唯一约束，Promotion 回滚且没有产生 Contract/ReviewRun。此记录未删除或改写。之后仅对正式确认合同号加入 E2E 唯一后缀。05 首次 create 因旧 5 文件选择包含异项目通知而获得 READINESS_REJECTED，没有创建 run；修复后 fresh key 与正确 4 文件选择只创建上表一个 run。

## 4. F02 五规则与真实差异

| 套件 | 金额大写 | 行项目乘法 | 行合计 | 成交金额 | 成交供应商 |
| --- | --- | --- | --- | --- | --- |
| 01 | PASS | 4 行 NOT_EVALUATED | PASS | PASS | PASS |
| 02 | PASS | 4 行 NOT_EVALUATED | PASS | FAIL：合同 69,000，通知 67,000 | PASS |
| 03 | PASS | 4 行 NOT_EVALUATED | PASS | PASS | PASS |
| 04 | PASS | 4 行 NOT_EVALUATED | PASS | PASS | PASS |
| 05 | PASS | 4 行 NOT_EVALUATED | PASS | PASS | PASS |
| 06 | PASS | 4 行 NOT_EVALUATED | PASS | PASS | PASS |

每个 run 持久化 8 条 F02 AuditCheckResult：五条规则中的逐行算术展开为 4 条结果，总数不被压成固定五条。行项目为批准的 AMOUNT_ONLY 正式事实，数量和单价真实未知，因此逐行 NOT_EVALUATED 是业务数据缺失，不是 mapper/snapshot 漏字段。行声明金额完整且冻结后，LINE_SUM_MATCH 可计算。
每个 run 同时持久化 8 条 F02 navigation finding 和 8 条稳定 relation。F02 结论权威仍为 AuditTask/AuditCheckResult。当前人工确认的正式 Contract 事实没有可靠原文 locator，因此 evidence 数为 0，并以 SOURCE_UNAVAILABLE 明示；没有伪造 excerpt、页码或 offset。

## 5. 自动提取与人工确认

- 01—05 的 DOCX/PDF 文本解析成功；06 的扫描通知使用 OCR，parse method 为 OCR，engine 为 PADDLEOCR_3.7.0，并识别到项目编号、供应商、67,000 和日期等候选线索。
- 原件自动解析/确定性线索可支撑合同名称、主体、项目编号和部分金额文本，但 A-mini 结构化事实协议仍未启用，不能把这些线索写成自动完成正式 Contract。
- 六套正式 Confirmation 均由测试人员核对或补齐 contractNo、contractType、contractAmount、amountInWords、lineItems、awardNotice。
- 四条 line item 均按 AMOUNT_ONLY 确认；没有伪造 quantity/unitPrice。
- 为满足共享集成库的合同号唯一约束，成功套件的 contractNo 使用 E2E 唯一后缀。这是人工修正，未冒充原件自动提取。
- 05 的校园访客预约系统通知人工确认 NOT_SAME_PROJECT 并排除；正式 ReviewRun 只冻结 4 份同项目材料。
- 原始 01—06 文件按只读字节上传；没有修改、重命名或制作派生材料冒充原件。

## 6. 八项页面覆盖

| 顺序 | 类别 | fresh 结果 |
| --- | --- | --- |
| 1 | 合同条款量化一致性审查 | 已接入全部 F02 持久结果；01 页面显示关注 4，02 的金额 FAIL 可见 |
| 2 | 合同草本结构对标审查 | 无指定模板时明确资料不足/未覆盖，不宣称完成 |
| 3 | 违约责任条款量化审查 | 接入同 run BREACH 结果；04 的真实差异保留 |
| 4 | 合同期限与终止条件格式审查 | 接入同 run TERM 结果 |
| 5 | 合同主体资格审查 | 只显示身份基础核对，保持等待外部资格证据 |
| 6 | 附件清单完整性审查 | 没有附件引用/匹配结果时保持未覆盖 |
| 7 | 合同生效条件与份数审查 | 两个范围分别标记，不用一侧结果覆盖另一侧 |
| 8 | 合同条款风险提示 | 使用同 run 持久语义结果；复制不触发新模型请求 |

真实浏览器验证了左材料/右八项、默认折叠、一次展开一项、问题突出、复制本项意见与复制综合意见。刷新、展开、定位和复制没有新建 ReviewRun。

## 7. 定位、历史与权限

- L01—L12 自动化回归全通过。
- 01 真实浏览器分别点击合同、响应、采购来源，均打开正确 frozen file tab，并观察到 highlightCount=1。
- exact run 通过项目入口、历史入口、刷新、第二浏览器和后端重启后保持同一 runId 与 frozen input。
- 05 修复证明 excluded foreign material 不进入 ReviewRun snapshot。
- 匿名 exact-run GET 返回 401。
- 没有经授权的第二个低权限账号，因此跨用户 owner isolation 的真实 E2E 为 NOT VERIFIED；后端 owner/ACK/report 自动化回归通过。未修改 owner 或放宽权限。

## 8. Provider 调用计数

对七个本轮隔离 Project 和六个正式 run 的 DM8 只读汇总：

- deterministic role assessments：31 条 COMPLETED。
- private provider role assessments：8 条 COMPLETED，模型身份均为批准的 audit-qwen-27b-16k:latest。
- ReviewRun semantic provider operations：6 条 COMPLETED，模型身份相同。
- 因此持久化可证明的 private provider inference operations 共 14 条。
- OCR operations：1 条，PADDLEOCR_3.7.0。
- A-mini contract fact extraction：0 条；该能力未启用。
- provider metadata 预检曾执行 2 次，不计入 inference operations。

## 9. 自动化门禁

| 门禁 | fresh 结果 |
| --- | --- |
| Backend clean verify | 1831 tests；0 failures；0 errors；2 skipped；BUILD SUCCESS |
| Frontend default runner | 641/641 passed |
| Frontend shared runner | 641/641 passed，test-isolation=none |
| Frontend build | Vite 6.3.5；2407 modules；成功；仅保留大 chunk warning |
| FastAPI full suite | 346/346 passed；1 deprecation warning |
| C02/C03 focused | 86/86 passed |
| material identity focused | 19/19 passed |
| git diff --check | PASS；只有工作树 CRLF 提示，不存在 whitespace error |
| DM8 | 正常业务写入、持久化 GET、重启后 exact-run 读取与 catalog 验证通过；没有重跑 Phase 1B migration |

FastAPI 在普通 sandbox 的首次 full-suite 运行有 2 failed/3 errors，根因是 Windows 对测试临时目录的 PermissionError；相同代码在允许访问该测试目录的重跑中 346/346 通过。该首次运行不计为产品失败，但保留在事实记录中。

Backend 首次与 Frontend/FastAPI 并发 clean verify 时有 1 项 role proposal 测试得到不同来源；同一方法 1/1、同一类 4/4、随后不并发的 clean verify 1831/1831 均通过，期间未修改生产代码或测试。该不可复现门禁波动保留为测试稳定性记录。

## 10. 最终分项状态

| 口径 | 状态 |
| --- | --- |
| CODE_REVIEW | PASS；两个独立 reviewer 在唯一一次 Important fix round 后无未解决 Critical/Important |
| UI_REQUIREMENTS | PASS |
| PREPARATION_FLOW | PASS；含真实 05 排除异项目材料与 retained create 恢复 |
| MATERIAL_EXTRACTION | PARTIAL；真实解析/OCR通过，A-mini 正式事实抽取仍未实施 |
| EIGHT_CATEGORY_COVERAGE | PARTIAL；页面与现有结果接入完成，第 2/5/6/7 保留真实未覆盖范围 |
| EVIDENCE_NAVIGATION | PASS WITH HONEST LIMIT；已有 locator 可定位，F02 人工正式事实无 locator 时明确 unavailable |
| HISTORY_SECURITY | PARTIAL；exact run/restart/匿名 401 通过，第二低权限用户未验 |
| ORIGINAL_E2E | PASS FOR 01—06 WITH DOCUMENTED HUMAN CONFIRMATION |
| OA_INTEGRATION | NOT VERIFIED / DEFERRED |
| CUSTOMER_MODEL_INTEGRATION | NOT APPLICABLE；本轮只验证已批准私网测试 provider |

## 11. 剩余 BLOCKED / DEFERRED

- A-mini：详细设计存在，但结构实施未获本轮授权，保持 DEFERRED。
- B：amount-only 最小模型及已批准 DM8 变更已用于本轮；完整行集完整性业务事实仍是已知限制，不能从四行自动推断所有合同都完整。
- C：BREACH/TERM typed 双方值持久协议未获本轮结构实施授权，保持 DEFERRED。
- 第二低权限账号的真实 owner isolation、OA 集成、客户环境部署与客户模型集成均未执行。
- 未提交 WIP 仍需人工检查并决定提交范围；本轮不 push、不 merge。

结论：PARTIAL。原件 01—06、真实 OCR、ReviewRun、八项页面、F02、定位与历史重开形成了 fresh evidence；剩余业务覆盖和授权边界必须继续如实保留。
