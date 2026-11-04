# 三阶段最终交付回执（2026-10-27）

执行顺序为合同自动化、26三模块实际测试修复、Git及甲方离线交付。第二阶段证据见`../qa-26/FINAL_REPORT.md`，实现和限制见`DELIVERY_REPORT.md`。没有自动关机，也没有更新甲方正式环境。

## Git与实际运行版本

- 分支：`feat/contract-auto-delivery-20261027`，远程仓库`ztysew2/audit-agent`。
- 运行时代码：`4253bb5f27d513d04483bab448050d230dbb908d`；部署与制包代码：`11c217d9f1fed5a30943af8913e99ea2fdf16aa8`。两者均实际推送并经`git ls-remote --heads`验证。最终交付文档与QA交接工具为后续提交，不改变包内APP_COMMIT。
- 26验收入口：`http://192.168.0.26:18082/`。release为`/opt/audit-agent/pilots/qa26-20261027-r5`；JAR SHA256为`c5ebe922d4bc241e7c2505668042fe4f4ced8992a8d3d97de727032b0f1ede69`。
- 使用用户此前提供的`test_admin`及相应测试密码登录。只交接独立QA的原primary身份，owner ID与角色保持；23份冻结历史结果新登录重开一致。未转移原业务数据或变更原业务账号。密码不写本回执或Git。
- 原80端口仍为原版本，原业务库完整恢复未证明，因此未切换原业务入口。原服务、配置及文件的最终只读保护检查通过。

## 最终包

桌面只新增两个文件，保留旧版本：

- `D:/UserData/Desktop/Audit_agent/Update_v11.tar.gz`
- `D:/UserData/Desktop/Audit_agent/Update_v11.tar.gz.sha256`

包大小84,372,416字节，158文件，根目录直接包含`update.sh`；APP_COMMIT锁定`11c217d9f1fed5a30943af8913e99ea2fdf16aa8`。

SHA256：

```text
acb6b917131e0586604f7c5b6c5d06b68a93f10b8bf32ff4d7975473d8751be8
```

桌面复制后哈希与成品一致。最终tar独立解压，manifest逐文件校验、无未登记文件、22个Java17 class以及前后端验收产物一致性通过。最终包脚本在26 Linux执行5项bash语法、62项Python AST检查通过。真实DM8首次/重复/DDL后代码回退及漂移反例、UNKNOWN任务门槛和恢复确认守卫证据见`PACKAGE_TESTS.md`；未把这些当作ARM64现场升级成功。

## 现场使用

将两个文件放到甲方服务器受控目录后，使用全新解压目录，不覆盖旧v10解压目录：

```bash
sha256sum -c Update_v11.tar.gz.sha256
mkdir Update_v11
tar -xzf Update_v11.tar.gz -C Update_v11
cd Update_v11
sudo bash update.sh --check
```

`--check`只读。正式更新涉及新增表，只在甲方内部完成数据库与业务文件备份恢复验证，并按包内`CUSTOMER-RECOVERY.md`建立真实的root0600恢复记录后执行：

```bash
sudo bash update.sh --backup-record /root/audit-recovery.json
```

不能为通过守卫虚填恢复成功。真实Schema漂移或UNKNOWN停止，不自动修表、清库或重放未知DDL；应用回滚不撤销DDL。保留现场模型地址、密钥、模型名、Nginx上传配置、数据库/Redis和业务文件。Nginx应通过`current`指向现行静态资源。更新后浏览器Ctrl+F5，并实际验证模型、OCR与材料结果。

## 验收边界

- 合同开发、三模块限定场景及独立26部署：VERIFIED；Java3333项、Python2029项、前端821项TS及2项UUID测试通过。详细skipped及范围见主报告。
- GitHub源码推送：VERIFIED；最终文档提交以分支头和提交历史为准。
- ARM64包：PACKAGE_BUILT_AND_STATICALLY_VERIFIED；麒麟ARM64实际`update.sh --check`、正式升级及客户真实材料仍NOT VERIFIED。
- 新采购自动入口尚未接入旧通用分片续传，黑名单/围串标/拆分采购未执行；不宣称全部采购子模块自动覆盖。
- 合同允许有依据的部分报告。个别模型描述未通过证据核对时如实PARTIAL，不伪装完整无风险。未知格式、复杂正文或客户真实负载的100%准确率未验证。
