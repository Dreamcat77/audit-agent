AuditAgent 架构绑定离线更新包

适用：包内 VERSION.txt 指定的麒麟 V10 SP3 2403 aarch64 既有安装。包内不含数据库密码、AI 密钥、Nginx 配置、业务数据或 RPM，不重装运行时。架构不匹配时正式更新会停止。

更新包已解压到新目录并完成SHA256核对后，现场只需运行一次：
  sudo bash update.sh

也可单独运行只读预检：
  sudo bash update.sh --check

此命令先运行只读预检，再继续发布；不要求另跑`--check`或提供恢复确认文件。会校验当前Nginx返回的前端资源、DM8结构、服务用户Python依赖和活动任务。正式更新先停Java/AI服务，再做增量DDL并切换新release；真实结构漂移、活动/未知任务状态或健康检查失败会停止并输出stage/reasonCode，不会自动修表或重放DDL。

本包按用户要求不自动备份数据库或业务文件，也不检查备份恢复记录。更新器会在`/opt/audit-agent/update-backups`保留旧应用文件用于应用级回滚；DDL成功后回滚应用不会撤销DDL。此次属于明确选择的无备份更新方式，请勿将应用文件副本当作数据库备份。真实Schema漂移、部分迁移状态、UNKNOWN或活动任务仍会停止更新，不会自动修表或重放DDL。

Nginx root 必须是 /opt/audit-agent/current/frontend/dist。现有 AI 网关地址、密钥、模型名继续从现场配置读取。失败时保留脚本输出的更新日志及恢复目录；人工应用回滚：sudo bash rollback.sh /opt/audit-agent/update-backups/v10.xxxxxxxx

完成后 Ctrl+F5，并分别用预算、采购、合同材料现场验证。模型网关白名单、协议兼容、客户 ARM OCR 和合同正式五步审查仍需现场验收。

新增自动案件：预算继续按上传批次输出；采购自动完成询比价、预审和F10；合同自动生成有依据的完整或部分报告。独立新增 CONTRACT_ANALYSIS_CASE 及 AUDIT_CASE、AUDIT_CASE_FILE、AUDIT_FACT、AUDIT_QUOTE_LINE，不清理或替换历史业务表。采购新自动入口目前为整批上传，不宣称支持旧上传入口的断点续传。黑名单、围串标、拆分采购在新自动案件中未执行，不表示全项合规。

如部署制度不允许无备份更新，请勿运行本包。应用回退不会撤销已成功执行的增量DDL。

内部99-audit-v10.conf及deploy/update-v10为兼容以前安装而保留的稳定管理标识，不代表本包仍是旧代码。应用提交和版本以VERSION.txt及manifest为准。
