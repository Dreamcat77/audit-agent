# 部署记录

2026-10-27开始，本阶段尚未切换原应用或部署新三模块实例。

历史记录显示原活动release为auto-intake-20261026.qw7KXB，另有采购专用隔离试点。须以此次只读实测为准，不能直接当作当前值。

原业务库完整跨Schema备份恢复仍未证明；在证明前不执行原库迁移。允许继续独立测试实例和新Schema验证，所有配置/文件存储/Session需隔离并保留原应用。

## 实际独立部署

隔离AUTOCASE2/QA26_FULL2新Schema 753条DDL全部ACK，pending=null；独立storage、Redis Session namespace和真实认证。18082为独立Web，19151/19153仅loopback。原业务库、原服务与Nginx配置未改。

r2成品SHA aa66961c01430abc6598db6334f541353b2daf1a30bf6b7678830a05a5a53231。首次因RuntimeDirectory被systemd清理，nginx -t失败，自动回退r1健康通过。修复QA切换器后在验证已解包文件/恢复记录一致的前提下重试，r2切换、健康、HTTP入口及静态资源SHA通过；现场配置哈希不变、原release文件未变。

r3成品SHA 39ddc4c61e82f220d9ffdfb55f22bae31ebe747f0c533dfda234d268a8e4f917，80文件；修复采购上传目录与选材范围混淆。尚未切换；必须等r2活动任务结束、再通过只读quiescence后才切换，禁止中断在途模型任务。

## 第三阶段最终交付（2026-10-27）

应用源码4253bb5及制包工具11c217d已推Git；r5继续运行于独立18082，未切原80。测试账号交接只修改独立QA fixture，保留owner/角色并写审计；新登录23份冻结结果一致。最终只读检查8表活动数0、六服务active、原配置/文件不变。Update_v11.tar.gz与.sha256已放桌面Audit_agent，158文件成品逐manifest通过；5个Shell/62个Python最终包脚本Linux静态检查通过。甲方正式环境未更新，ARM64现场执行NOT VERIFIED。实际命令和SHA见../delivery/DELIVERY_RECEIPT.md。

## 第二阶段最终收口（2026-10-27）

r5独立18082真实模型/DM8/Chrome109最终验收通过，原80入口保持原版本。821项TS测试；Java3333/0/0/24 skipped；Python2029 passed/14 skipped。新预算金额段与r3采购正常/矛盾组通过。最新服务合同如实PARTIAL，22处原文核对，无伪造完整结论。最终8表活动任务0，23份冻结结果重开不变，6服务active。进入第三阶段Git/甲方包，尚未宣称其完成。详见FINAL_REPORT.md。
