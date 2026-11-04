# 正式更新前的本地恢复确认

本次新增案件表只能在数据库及业务文件备份已经完成、恢复演练已经验证后执行。更新脚本不会替代DBA进行备份或恢复，也不会读取业务正文。原业务库恢复尚未确认时，只运行`sudo bash update.sh --check`，不要执行正式更新。

在甲方受控目录保存两个非空备份归档（数据库、业务文件），由现场DBA在独立环境验证数据库恢复、文件恢复及相应版本可读取历史记录。不要在生产库试验恢复。之后才创建`/root/audit-recovery.json`，权限`0600`、所有者root。真实路径及备份内容只留现场。

记录结构如下（示例值必须替换为现场实际值，不能直接复制当作已验证）：

```json
{
  "format": "audit-agent-recovery-v1",
  "previousRelease": "/opt/audit-agent/releases/现场当前版本",
  "databaseRestoreTested": true,
  "filesRestoreTested": true,
  "artifacts": [
    {"kind": "database", "path": "/root/受控目录/数据库备份归档", "sha256": "实际64位SHA256"},
    {"kind": "files", "path": "/root/受控目录/业务文件备份归档", "sha256": "实际64位SHA256"}
  ]
}
```

`previousRelease`使用`readlink -f /opt/audit-agent/current`的结果；SHA使用`sha256sum`。两份备份及确认文件须为root所有的普通文件，禁止符号链接及组/其他用户访问。记录中不得写密码、密钥或正文。

```bash
sudo chmod 600 /root/audit-recovery.json
sudo bash update.sh --check
sudo bash update.sh --backup-record /root/audit-recovery.json
```

脚本验证文件存在、权限、哈希、当前版本范围，以及操作员声明。`BACKUP_RECORD_VALIDATED=PASS restore_claim=OPERATOR_ATTESTED`只表示记录和哈希核对通过，不能被解释为脚本执行过恢复演练。没有实际恢复演练，不得把两个确认字段写成true。

发现真实Schema漂移或迁移部分完成时停止，不自动改列、删除同义词、刷库或重复执行未知SQL。成功DDL会保留，即使应用回滚也不会回滚数据库。恢复到旧代码后新案件表及结果保留；旧代码可能无法展示新案件，恢复新版本后可继续读取。
