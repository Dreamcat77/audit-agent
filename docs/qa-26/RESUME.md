# 最新恢复入口（优先于以下历史）

三阶段已依次完成至可交付边界：合同自动化 → 26三模块真实测试/修复 → Git推送及v11离线制包。分支feat/contract-auto-delivery-20261027；应用及制包源码提交11c217d9f1fed5a30943af8913e99ea2fdf16aa8已实际推送。最终交付文档另行提交，不改变包内APP_COMMIT。

r5已在26独立18082部署并通过Chrome109、真实模型/DM8和最终只读保护检查。测试账号沿用用户已提供的test_admin凭据，仅移交独立QA身份且owner/角色不变；交接后23份冻结结果新登录只读重开一致。原80端口及原业务库未变；原库完整恢复未证明，不能切换原业务实例。

桌面D:/UserData/Desktop/Audit_agent仅新增Update_v11.tar.gz及.sha256。包SHA256：acb6b917131e0586604f7c5b6c5d06b68a93f10b8bf32ff4d7975473d8751be8。158文件、manifest及Java17 class核对通过；最终包提取的5个Shell脚本在Linux bash -n通过，62个Python文件AST通过。麒麟ARM64现场执行仍NOT VERIFIED；正式更新前按CUSTOMER-RECOVERY.md完成真实内部恢复验证。最终状态见docs/delivery/DELIVERY_REPORT.md及DELIVERY_RECEIPT.md。下面均为历史检查点，不代表当前未完成任务。

# 恢复入口

## 当前检查点（优先于下文历史）

最新续跑：r2预算24样本整组结束，failures=0；采购office13全部通过、scans已EXTRACTED8份，未结束不可重启。SSH2716；advance_r3.py后台PID595783等待两组整组终态后，受quiescence保护切r3→只读重开→feature-r3→新采购normal/conflict-r3→合同normal-service-r3，任何失败立即停止；进度文件qa26-stage2-20261027/advance-r3-state.json。

又发现QA26-011：采购证据展开区有原始字段代码、locator及模型原因码。仅前端改中文展示、保持事实/原文和存储不变；820/820 TS测试和生产构建通过。r4候选SHA feb0ab61812ea14c4fcd1071e65cd121e8fad90aa8a92c4165d61e97229497ce，已上传/home/admin/qa26-r4.tar.gz；与r3逐文件比对只改变前端JS及index，backend JAR仍c5ebe922d4bc241e7c2505668042fe4f4ced8992a8d3d97de727032b0f1ede69。

finish_ui.py后台PID600802只在advance-r3最终PASS后自动受保护切r4并重开旧报告；失败则退出，不绕过任何门槛。状态finish-ui-state.json。已修browser_driver.py可--revision r4，并从admin600公开验证元数据读取r3caseId；最新browser.mjs新增内部代码/locator不得出现在普通页面断言。等finish-ui PASS后运行实际Chrome109 accept；SSH密码通过getpass仅内存，不写命令行或日志。export_evidence.py已上传，可导出合成验证元数据tar，不含raw/凭据。阶段二仍未完成，未推Git或制甲方包。

r3最新检查点：实际复验发现QA26-009（模型把F10未选入正文的报价误称未上传），已做系统范围说明＋完整上传目录与选材分离＋反证拒绝，不只是Prompt。v2审查协议、v3字段Prompt。最终Java3333/0/0/24 skipped，Python2029 passed/14 skipped/43 subtests；r3候选80文件，SHA 39ddc4c61e82f220d9ffdfb55f22bae31ebe747f0c533dfda234d268a8e4f917，已上传/home/admin/qa26-r3.tar.gz，尚未部署。remote_update.py已改用新JAR提取libs，不依赖上一个release临时工具目录；已上传。feature_suite.py新增--revision r3，修正材料不完备时允许真实PARTIAL，同时检查系统拥有范围及采购单位事实。

r2 formats-budget后台578105、formats-procurement后台579294仍在运行，不可重启；最新预算已完成TIFF前全部办公格式，采购office13份完成11份，表格DOCX在第二次受控补充提取，26MB DOCX仍STORED。源码/产物改动未进入运行服务，等全部终态及quiescence再用新脚本切r3。r2 feature原始失败快照保留，不通过改断言掩盖真实误述；r3必须新operation测试。

Chrome109 r2本地session20451已因6分钟等待队列超时退出，不能标通过；服务不是失败，浏览器批次3d019bec-6683-4d25-a967-d3f4f801bf0c已持久保留。browser.mjs等待上限改30分钟，待队列空后再真实验收。SSH2716仍可用。r3本地构建和Pythonsession均已结束。尚未推Git或制作甲方包，阶段二未完成。

最新续跑：r2已成功切换独立18082，SHA aa66961c01430abc6598db6334f541353b2daf1a30bf6b7678830a05a5a53231。第一次因systemd停止web删除/run/audit-qa26导致nginx -t失败，自动回退r1通过；已限定重建admin运行目录并验证原恢复记录/已解包文件后安全重试。原release/服务/配置/数据库未变，真实DM8131条历史测试通过、8张任务表均0后切换。

最终本地JDK17 clean verify 3330 tests/0 failures/0 errors/24 skipped；Python2027 passed/14 skipped；前端818/818和生产构建通过。r2部署后23份旧冻结报告在新登录下只读重开指纹全部一致，未触发模型。

当前SSH维护session2716。r2多格式预算、招标预算/大写报价专项、missing-item新批次已经后台启动，日志在qa26-stage2-20261027/{formats-budget-r2,feature-r2,budget-missing-r2}.log。Chrome109新版验收本地session20451运行中。合同第一阶段及r1实际验收均保留。尚未开始Git推送/甲方制包。

续跑最新：r1合同15组已全部结束，11 COMPLETED/4 PARTIAL，471处原文核对、failures=[]；采购normal/conflict两组12份分别173/175处引用核对通过；预算5/6通过，missing-item科目末尾标点误报QA26-003源码已修复。Chrome109真实生产三模块流程通过，证据browser109-accept-verification.json。

修复待r2：采购自动入口16格式/500MB与reader一致、size/empty安全400；历史游标分页超100可发现；明确拒绝不锁死新上传；末尾标点；任何文件明确预算可用、最高限价不冒充预算；自动案件SocketException不重发。新增实际JDK接收POST丢响应只1次反例通过。源码改变后最终clean verify session63507运行中，日志r2-release-java-verify.log；此前3328项clean verify已通过但不含最后传输修复，不能直接作为最终包门槛。

SSH当前session17035，旧30055因SFTP权限退出。private配置和results为root600，不直接SFTP读取；合成公开证据需先复制到admin600的/home/admin/qa26-public-evidence，再get。不要复制真实凭据。Python路径/opt/python/venvs/audit-ai/bin/python，root PATH无python。

已准备tools/qa26/remote_update.py、Qa26Quiescence.java、Qa26HistoryPlatformProbe.java：仅更新独立18082入口，原release/配置/库不动；升级前后零活动任务，真实DM8 131条历史回滚式查询验证、manifest/HTTP assets验证和失败回退。已SFTP到/home/admin，尚未运行。最终build_candidate r2使用--verify-log r2-release-java-verify.log，勿用旧基线日志。

后续tools/qa26/format_suite.py和feature_suite.py已准备远端，尚未启动。format包含16格式、26MB DOCX、混合PDF、90/180/270扫描和DOCX内倒置图片；remote无python-docx，已用本机生成embedded fixture并复制到format-fixtures-r2，extras跳过已有DOCX。r2更新后跑formats及仅招标预算+3报价（中文大写）专项；复跑Chrome109（已加明确拒绝后可重新上传和坏附件不假成功断言）。15组r1旧快照也要重启后按相同operation重读验证。

第二阶段真实三模块验收运行中。独立候选 `qa26-20261027-r1` 已在26启动，Web18082、Java19151、AI19153，独立AUTOCASE2/QA26_FULL2、storage和Session namespace；原业务release、配置和数据库不变。备份见evidence/p0-backup-summary.json，候选归档SHA见evidence/candidate-r1.json。

JDK17全仓clean verify：3296 tests、0 failures、0 errors、24 skipped；Python全量2027 passed；前端816个TS测试和10个MJS通过，生产构建通过。此为本地结果，不能替代真实业务验收。

SSH维护session30055可用；stdin JSON支持command/sudo/put/get，回车发送，不输出任何私有账号。预算normal、over、uppercase真实HTTP/模型/DM8已通过；其他预算场景在后台PID545609，日志qa26-stage2-20261027/budget-r1.log。采购normal后台PID546310，日志procurement-r1.log。勿重复提交新operation key；tools/qa26/acceptance.py会持久化恢复同一操作。

新测试入口启动暴露nginx配置root600、默认fastcgi temp目录权限问题，已仅修正独立配置所有者与三个temp路径，原nginx未变；修复同步remote_install.py。fixtures归档46文件哈希/路径校验后解包。尚未完成采购、合同整组复验、Chrome109生产浏览器验收和异常恢复。下一步读后台日志并审核原始合成报告，再按失败原因修复；三模块通过后才进入Git/甲方包阶段。

用户顺序固定：合同→26全面测试→补充交付；当前进入第二阶段P0。

实际仓库 `E:\audit-agent-new\.runtime\budget-home-e17-20261014`；分支fix/budget-procurement-ready-20261019；HEAD35f95499c4d5efa475dffc3a3628222750124672。有大量共享未提交修改，不可reset/clean/全部无差别stage。

先读本目录WORK_LOG/ISSUES、`DEPLOYMENT-MEMORY.md`和客户部署手册；使用JDK17。命令与证据目录为本目录evidence（后续创建）。

当前合同隔离协调器session61562持有SSH和随机凭据，控制入口19147。合同验收已完成，仍保留服务以便安全查询。不能在模型运行中并发重启。原26应用未更新。

下一步：复现13项采购测试，核实26环境并备份，设计/部署独立完整Schema三模块候选；真实回归通过后才进入Git/甲方包阶段。原业务库恢复未验证，勿对其执行试验DDL。
