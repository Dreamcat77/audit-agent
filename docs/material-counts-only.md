# 采购与预算材料界面更新

去掉采购和预算材料区的“成功文件”“失败文件”文件名清单。保留成功、失败、处理中数量，以及现有上传、后台解析、填充和预算结果切换功能。

本包仅含前端，适用于已安装 `AuditAgent_Budget_Background_20261010` 完整更新包的版本。请先安装该完整包，再解压本包，进入解压目录执行：

```bash
sudo bash update.sh
```

自定义 HTTP 端口可传入本机地址，例如 `sudo bash update.sh http://127.0.0.1:8088`。完成后客户端按 Ctrl+F5 刷新。

脚本校验原入口版本和资源，备份入口，原子替换入口并检查 HTTP 静态资源、健康接口和未登录 401。未知版本停止覆盖。不重启服务，不修改 Nginx、后端、AI 地址/密钥/模型名称、环境配置或业务数据。

回滚使用更新输出的入口备份路径：

```bash
sudo bash rollback.sh /opt/audit-agent/frontend-backups/material-simple-20261010.实际后缀
```

甲方 ARM64 浏览器实机效果仍为 NOT VERIFIED。
