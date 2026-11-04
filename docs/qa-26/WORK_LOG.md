# 26三模块测试工作日志

## 第三阶段交付收口（2026-10-27）

应用4253bb5和制包工具11c217d已推送至feat/contract-auto-delivery-20261027且ls-remote一致。封包Update_v11.tar.gz为84,372,416字节，SHA256 acb6b917131e0586604f7c5b6c5d06b68a93f10b8bf32ff4d7975473d8751be8；manifest、158文件、22个Java17 class、已验收前后端一致性全部通过。最终包5个Shell及62个Python脚本在26 Linux完成静态校验。

独立QA测试账号交接保留owner/角色，真实DM8事务审计通过；错误Schema/URL、其他账号冲突及未知fixture四项反例拒绝。交接后23份冻结报告新登录只读重开一致、Chrome109三页面巡检无运行错误；该巡检不重新调用模型。8张表活动任务0，原及QA六服务active。原80和原库保持；甲方ARM实际执行仍NOT VERIFIED。桌面仅新增包和.sha256，最终交付文档/验证元数据另行精确提交，未收集密码或原始响应。

## r3有界续跑检查点

r3专项已结束failures=[]：采购单位与预算角色正确，缺失误述已消除；报价计算完整，材料不完备如实PARTIAL。6路重放4个200同case/2个容量409，无重复资源或冻结内容变化；跨owner拒绝及更换manifest409通过。当前进入新12文件正常/冲突采购组，之后服务合同、r4前端和Chrome109。

2026-10-27续跑：预算24格式已完成22项且无断言失败，含90/180/270度扫描金额；采购13份办公格式全部通过，扫描组仍在处理。r2活动任务不重启。新增QA专用advance_r3.py：仅在两个24样本整组终态且无失败后调用quiescence受保护的切换器，随后执行冻结报告重开、r3招标预算/大写报价专项、全新12文件正常/矛盾采购及服务合同；任一阶段非零立即停止。每组operation和结果使用revision后缀，不以旧冻结报告冒充新模型验收。

维护SSH session2716；续跑PID595783，状态位于qa26-stage2-20261027/advance-r3-state.json。尚未开始Git推送或甲方制包。

2026-10-27。按用户顺序：合同隔离验收→本阶段26全面测试修复→补充交付。当前进入本阶段P0。

第一阶段证据见 `../contract-automation/TEST_REPORT.md`：15组合成实际模型/隔离DM8样本、488处原文引用、四类注入故障、Chrome109真实上传及历史恢复通过。不能据此声明26部署或全仓通过。

当前工作树 `E:\audit-agent-new\.runtime\budget-home-e17-20261014`，分支 `fix/budget-procurement-ready-20261019`，HEAD `35f95499c4d5efa475dffc3a3628222750124672`，含共享未提交修改。未推Git或制包。源码保护备份和既有变更范围见合同BACKUP_REPORT。

首先核实现有26活动release/服务/配置，建立新恢复记录；原业务库跨Schema完整恢复未证明前不执行试验迁移，优先独立实例完整Schema测试。同步复现13项既有采购测试失败，不能因属于历史失败而忽略。

26只读库存及部署备份已完成，原release仍为auto-intake-20261026.qw7KXB；260文件解包重读SHA一致、源文件未变化，备份不包含数据库数据。Java采购定向46/46通过，全仓clean verify进行中。隔离空库计划发现V007/V008四处重复ADD，保留QA26_FULL失败现场，修正计划后在QA26_FULL2重新执行；绝不对原业务Schema重放。

## r2修复部署与复测

最终JDK17全仓3330 tests、0 failures/errors、24 skipped；Python2027 passed/14 skipped；前端818/818与生产构建通过。r2成品80文件，SHA aa66961c01430abc6598db6334f541353b2daf1a30bf6b7678830a05a5a53231。

第一次切换因systemd停止web会删除RuntimeDirectory，nginx -t缺pid父目录失败，自动回退r1且健康恢复。改进仅独立QA切换脚本：按admin实际UID/GID安全重建运行目录；重试严格验证已提取文件、原恢复记录和配置字节，不覆盖恢复记录。第二次切换成功，真实DM8 131条历史分页、同时间/精度、owner隔离测试通过且fixture事务回滚；前后活动任务0，原服务/文件/配置不变。

r2部署后新登录只读重开23份r1报告，指纹全部一致。多格式/OCR、预算科目末尾标点和仅招标预算+中文大写报价专项继续真实模型复测。尚未推Git或制包。
# 2026-10-27 第二阶段实际验收续跑

- 全仓JDK17 clean verify通过：3296 tests，0 failures/errors，24 skipped；Python2027 passed/14 skipped；前端TS816/816与MJS10项通过，生产构建通过。
- 新Schema QA26_FULL2从空库753条DDL全部ACK，无pending；QA26_FULL第一次重复ADD的失败现场保留。原业务库仅只读结构快照。
- 当前候选qa26-r1归档80个文件，SHA-256 44f8ff5127dc6a5ab2ed245ea14c8dfbedc5c28c6669d37d27cbdbcb2aa7af0f；独立Java/AI health正常。Web配置权限与三个默认temp目录已修正；仅独立服务受影响。
- normal/over/uppercase三个预算批次真实模型+DM8验收通过，包括跨文件金额比较、大写人民币及跨owner隔离。其余预算、采购12文件开始执行。实测未完成前不宣称三模块已验收；尚未推Git或制包。


## 最终收口（2026-10-27）

r5独立18082真实模型/DM8/Chrome109最终验收通过，原80入口保持原版本。821项TS测试；Java3333/0/0/24 skipped；Python2029 passed/14 skipped。新预算金额段与r3采购正常/矛盾组通过。最新服务合同如实PARTIAL，22处原文核对，无伪造完整结论。最终8表活动任务0，23份冻结结果重开不变，6服务active。进入第三阶段Git/甲方包，尚未宣称其完成。详见FINAL_REPORT.md。
