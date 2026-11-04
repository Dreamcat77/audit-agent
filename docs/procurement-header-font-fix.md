# 采购表头离线字体补丁

针对采购需求列表十列表头中文细碎、模糊或显示不完整。原版表头使用 `font-mono`，在根字号 14px 下 `text-xs` 只有 10.5px；中文依赖操作系统回退字体。前一版 12px 系统字体补丁仍依赖客户端字体。本补丁使用随包提供的中文字体，并保留现有颜色、间距、列顺序和页面结构。

仅准确匹配采购需求列表十个标题的表格使用 `Audit Header SC`：离线本地 WOFF2、静态字重 500、12px 字号、18px 行高。相比已安装的表头补丁，字号行高不再改变；相比最初的 10.5px 表头，表头高度增加约 4px。中文字体仅包含这十个标题和 ASCII，共 134 字符、13,912 字节。其他文字使用原字体。今后增加表头文字应重建子集。

字体源为 Noto Sans SC，遵循 SIL OFL 1.1，已附许可证并为修改版更名。无需浏览器访问外网、无需安装系统字体、与服务器 ARM64/x86_64 架构无关。字体来源和 SHA-256 见 `SOURCE.md`。

## 甲方离线更新

把压缩包传到服务器 `/root/audit_agent`，在该目录执行：

```bash
tar -xzf AuditAgent_Header_Font_20261010.tar.gz
sudo bash AuditAgent_Header_Font_20261010/update.sh
```

如 HTTP 入口不是默认的本机 80 端口，显式传入检查地址，例如：

```bash
sudo bash AuditAgent_Header_Font_20261010/update.sh http://127.0.0.1:8080
```

脚本适用于既有 `/opt/audit-agent/current/frontend/dist` 离线部署。校验包清单、已知入口版本及原始资源哈希；只增加字体/CSS/表格定位脚本并原子替换入口。未知入口会拒绝更新，不覆盖现场改动。更新后检查全部新增资源与首页 HTTP 哈希、后端健康和未登录接口 401；失败自动恢复入口。重复执行安全。无需重启服务。

页面更新后客户端按 `Ctrl+F5`。如仍异常，检查浏览器开发工具 Network 的 `header-sc-500-*.woff2` 是否 200，以及表头计算字体是否 `Audit Header SC`。浏览器缩放先设为 100%，记录版本、缩放比例、系统显示缩放和截屏，以区分资源未更新、字体被浏览器策略阻止与系统字体栅格化问题。

脚本打印入口备份目录。如需回滚，使用打印的实际目录：

```bash
sudo bash AuditAgent_Header_Font_20261010/rollback.sh /opt/audit-agent/frontend-backups/header-font-20261010.实际后缀
```

回滚前校验备份和更新后入口哈希，若入口已被后续更新修改则拒绝覆盖。原资源和新增资源均保留。模型地址、密钥、模型名称、后端、数据库、Redis、Nginx 配置和业务数据不修改。

实际甲方麒麟 ARM64 浏览器、显卡驱动和系统缩放：`NOT VERIFIED`。本机浏览器测试与 26 服务器检查证据见 `docs/evidence/procurement-header-font-20261010.md`。
