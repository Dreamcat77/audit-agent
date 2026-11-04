# 中断恢复入口

## 2026-10-27 最新阶段（优先于下文历史记录）

合同独立切片隔离验收已通过，当前进入第二阶段26三模块全面测试。15组真实模型Golden、488处来源引用重读、四类故障收口及Chrome109真实上传/刷新均通过。详见 TEST_REPORT.md。下一步遵循 `docs/qa-26/RESUME.md`；未推Git、未更新26原服务、未制作甲方包。下文旧session和“仍处第一阶段”均为历史。

日期：2026-10-27。

真实工作树：`E:\audit-agent-new\.runtime\budget-home-e17-20261014`。
基线 HEAD：`35f95499c4d5efa475dffc3a3628222750124672`；分支 `fix/budget-procurement-ready-20261019`。

先读取 WORK_LOG.md、BACKUP_REPORT.md，再检查实际 `git status`。共享树有既存合同、采购、预算变更，不可 reset/clean 或用旧 HEAD 覆盖。

执行顺序：**合同改造与隔离验收 → 26 三模块测试修复 → 补充交付（GitHub、26 最终版本、ARM64 包）**。仍处于第一阶段合同隔离端到端验收，未进入第二阶段。不得在模型或浏览器合同验收未通过时将第一阶段标为完成。

已完成 P0 状态备份和 Java/前端/AI 基线。Java 基线 3,208 tests / 9 failures / 5 errors / 24 skipped；前端 820/821（缺浏览器配置），视觉项补配置后独立 1/1 通过，生产构建通过。Python 短路径全量复验 2013 passed / 14 skipped / 43 subtests passed；初次10项失败为 Windows 临时路径过长，不改业务缓存规避。

P1–P4 合同切片已实现，P5 验收和修复进行中。合同/项目/Review 定向 Java 回归 1714 tests / 0 failures / 0 errors / 8 skipped；先前全仓 `clean verify` 3271 tests / 9 failures / 5 errors / 24 skipped。旧自动分类用例已在定向复跑通过，不能将先前14项结果直接描述为当前最终失败集合。前端先前 828/828、Python AI 2019 passed / 14 skipped / 43 subtests passed；新增建议分级回归 Java 67/67、Python 9/9、前端2/2通过。隔离真实 DM8 `AUTOCASE2` 的合同案件表首次建表与重复预检通过，原业务库未修改。

真实模型已恢复有效输出（约95–109秒，模型单次预算150秒）；此前60秒超时为已保存的历史失败。真实正常采购/服务样本暴露“完善建议、附件范围未核实被当作风险”的质量问题，已加入basis区分与程序侧附件范围保护，并在前端分开展示，真实复测待完成。隔离服务合同案件已完成并经后端重启从DM8重读：12事实、8条款、5核对。合成DOCX视觉渲染因缺LibreOffice未执行，浏览器真实端到端正在准备。合同分析功能开关未在26原应用启用，未推Git、部署26或制包。

下一条检查命令（仓库根）：

```powershell
git status --short --branch
Get-Content docs/contract-automation/WORK_LOG.md
Get-Content docs/contract-automation/evidence/frontend-visual-baseline.log -Tail 15
```

下一步先完成合同真实模型/隔离DM8/浏览器多场景验收，修复实际结果；合同验收通过后才进入第二阶段。隔离验收端口19141(Java)、19143(AI)、19144(Vite)、19145(测试桥)、15288(DB代理)；仅连接独立AUTOCASE2的CONTRACT_CASE_QA。交互协调器session 54163正在运行，已加载scenario/browser命令；执行命令使用回车`\r`。Vite session 99009。不要覆盖用户本地5173或26原应用服务。

26 历史 release 记录见 DEPLOYMENT-MEMORY.md；本轮尚未只读核实其当前值。不能将旧记录作为此次验收。

### 当前续跑入口（优先于前文旧session）

2026-10-27最新：仍为合同P5隔离验收。协调器session61562，控制入口127.0.0.1:19147，仅本机随机token；使用ignored目录中的token，不打印。
`python tools/contract-automation/control.py` 可执行 ai、backend actual、scenario <id>、platform。运行场景时不要并发重启。
正在运行session85733：补充协议与明确有效变更；新supplement-conflict已通过，后一个仍需读最终结果。
定向Java93/93通过；更宽合同回归session22439在执行，日志p5-contract-final-regression.log。
倒置扫描完整真实OCR+模型+独立DM8已通过；损坏附件新样本已更新golden路径，需运行partial-file。
源码中刚新增跨文件有双方依据的正常比较条目/反例，尚未在运行Java/AI进程加载；当前进程仅包含此前专门CROSS Prompt（无clauses版本）。先完成当前场景，再编译/重启后复测。
新故障脚本tools/contract-automation/fault_acceptance.py待执行；分别验证503、未知超时、非法JSON、空响应，必须标记faultInjection=true和realModel=false。
严格阶段顺序不变，未进入26全面测试、未推Git、未打ARM包。
