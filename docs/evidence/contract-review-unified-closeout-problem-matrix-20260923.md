# 合同审核工具化统一收口问题矩阵（2026-09-23）

基线 HEAD：e86e6dc59409e0d7e3785882f5b4e1c87e0f07df

工作区：D:\code\audit_agent_1D\.worktrees\contract-final-closeout-20260923

本矩阵区分源码、自动化和真实环境证据。没有 fresh evidence 的项目不标记 VERIFIED。

| 编号 | 问题 | 处理与证据 | 当前状态 |
| --- | --- | --- | --- |
| C01 | blocker 与页面状态矛盾 | 继承已完成修复；blocker 优先显示状态和原因。没有重做该项 | GREEN |
| C02 | 前一步成功、下一步失败后无法接续 | 精确输入 fingerprint、durable result replay、actor/scope/command identity、响应丢失重放、并发和 GET 无副作用测试保留；focused recovery 86/86 | GREEN |
| C03 | Project/file/parse/role/identity 变化后旧恢复命令污染新输入 | 输入身份变化 fail closed；旧响应不能污染新项目；单文件恢复不扫描兄弟文件 | GREEN |
| C04 | Candidate 不存在/加载失败时页面静默 | 继承 ABSENT/ERROR 分支和 generation fence；没有重做该项 | GREEN |
| C05 | 历史 run 依赖 mutable live material | exact run 使用 frozen Contract/material tuple；刷新、第二浏览器、后端重启后重开通过 | VERIFIED |
| C06 | 正式 Contract 事实不足 | 不填假值；六套由人工 Confirmation 明确补齐；A-mini 仍未启用 | PARTIAL / BUSINESS PROCESS |
| C07 | HTTP 200 被误当业务成功 | 01—06 均检查 durable Contract、step、result、run 状态和重启后读取 | VERIFIED |
| RCV-01 | retained create key 在首个 409 后阻断已变化的正确命令 | TDD 复现；先 reconcile exact retained command，只有确定拒绝且 active pointer 不变才清 key；fresh command 再用新 key | GREEN |
| RCV-02 | latest run discovery 可能短路 retained command；active pointer 可能被覆盖 | 独立 reviewer 报告两个 Important；唯一 fix round 增加 exact operation/pointer identity 约束；复审无 Critical/Important | GREEN |
| M05 | 05 的异项目通知虽人工排除，仍被前端选择进 run | 将 effectiveIdentityStatus、identityCurrent、excludedFromProjectUse 纳入 material eligibility；19/19 focused；真实 final run 只有 4 材料 | VERIFIED |
| W2 | 用户页暴露 F02/Semantic/Breach/Term 技术块 | 固定左材料右八项、默认折叠、一次一项、三级层次、单项/综合复制；真实浏览器通过 | VERIFIED |
| W2-2 | 无模板时错误宣称草本结构对标完成 | 第 2 类保持未覆盖 | GREEN |
| W2-5 | 身份一致被误写成主体资格合格 | 第 5 类只展示身份基础核对 | GREEN |
| W2-6 | 用 scope/material list 推断附件完整性 | 没有附件引用/匹配结果时第 6 类保持未覆盖 | GREEN |
| W2-7 | 生效条件与份数混为一个完成状态 | 两个范围分别标记 | GREEN |
| L-F02 | F02 source projection 未进入 exact run | V2 finding/relation 写入、读取和 recover 使用稳定 checkResult identity；每 run 8 finding/8 relation；旧格式兼容 | VERIFIED WITH SOURCE LIMIT |
| L-F02-LOCATOR | 人工正式 Contract 事实无可靠原文 locator | 真实 run evidence=0 并明确 SOURCE_UNAVAILABLE；不伪造 excerpt/page/offset | INTENDED LIMIT |
| L-Semantic | 语义/违约/期限来源定位 | L01—L12 自动化通过；01 合同/响应/采购三个真实点击选中正确 frozen file 且 highlightCount=1 | VERIFIED |
| F02-B | amount-only 分项不能真实保留未知数量/单价 | 已批准最小 B 模型：basis=AMOUNT_ONLY，quantity/unitPrice 为 null，declaredAmount 保留；DM8 schema 在 E2E 前已验证 | VERIFIED |
| F02-B-SUM | amount-only 行集合完整性 | 本轮 Confirmation 明确四行并计算 line sum；通用行集完整性事实仍未完整建模，不能外推所有合同 | KNOWN LIMIT |
| F02-02 | 金额冲突材料应出现真实 FAIL | 02 正式合同 69,000 对通知 67,000，F02_AWARD_AMOUNT_MATCH=FAIL | VERIFIED |
| ENV-01 | reference env ROOT 指向旧代码 | 当前服务从本 worktree 启动，路径变量在测试会话覆盖；reference env 未修改 | VERIFIED |
| ENV-02 | 旧 storage 被当前服务触碰 | 使用 .runtime\final-closeout\integration 独立 storage/temp/log | VERIFIED |
| ENV-03 | enabled worker 可能认领同库旧任务 | 启动前按真实 predicate 只读检查 claimable/expired/running/lease 均为 0；没有清理旧任务 | VERIFIED AT START |
| ENV-04 | provider 身份与授权不一致 | 用户书面批准 audit-qwen-27b-16k:latest；host/port/service/API identity 复核一致后才外发 | VERIFIED |
| OCR-06 | 扫描通知必须走真实 OCR | 06 parse method=OCR，engine=PADDLEOCR_3.7.0；关键线索可读 | VERIFIED |
| SEC-01 | 匿名或非 owner 读取历史 run | 匿名 exact GET=401；owner/ACK/report 自动化通过；无第二低权限测试账号 | PARTIAL |
| A-mini | AI 成交候选事实协议 | 代码中保留受控、默认禁用的候选接口/配置实验，但本轮未启用、未持久发布正式事实、调用数 0 | DEFERRED |
| B | amount-only line item | 最小模型和已批准 schema 已实际使用；本轮续跑没有 DDL | IMPLEMENTED |
| C | typed bilateral BREACH/TERM | 未获本轮结构实施授权，没有新增持久 typed 协议 | DEFERRED |
| OA | OA 集成 | 无环境和授权 | NOT VERIFIED |

## 自动化证据

- Backend clean verify：1831 tests，0 failures，0 errors，2 skipped。
- Frontend default isolation：641/641。
- Frontend test-isolation=none：641/641。
- Frontend production build：2407 modules，成功；保留大 chunk warning。
- FastAPI full suite：346/346，1 deprecation warning。
- C02/C03/review create recovery focused：86/86。
- 05 material identity focused：19/19。
- git diff --check：PASS。

## 真实环境证据

- 六个正式 Project/Contract/exact run 全部 COMPLETED；FAILED 0，UNKNOWN 0。
- 02 保留一次 Promotion 唯一约束回滚对象；05 保留一次 READINESS_REJECTED create，二者均没有产生额外 run。
- 01—06 使用原始只读文件。06 有一条 OCR；八条 AI role assessment和六条 semantic provider operation 持久完成。
- F02 每个 run 有 8 check results；02 award amount FAIL，其余对应规则结果见验收报告。
- refresh、第二浏览器、backend restart 后 exact-run reopen 通过。
- 服务、数据和 storage 保留供人工复验。

## 未完成范围

- 第二低权限账号的真实跨用户 owner 拒绝。
- A-mini 正式事实抽取与 C typed 双方值。
- 第 2/5/6/7 类超出现有持久结果的完整业务覆盖。
- OA、客户部署和客户模型环境。
- 当前 WIP 尚未提交、push 或 merge。
