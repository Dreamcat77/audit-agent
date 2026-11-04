FontFix_v3：Chrome 109 采购类型列中文字体兼容补丁

范围：只更新前端静态资源，使采购列表“采购类型”列使用本地 Audit UI CJK 字体。保留现有表头字体修复，不修改业务逻辑、后端、AI、数据库、Redis、Nginx 配置或服务进程。

现场执行：
  sha256sum -c FontFix_v3.tar.gz.sha256
  mkdir FontFix_v3
  tar -xzf FontFix_v3.tar.gz -C FontFix_v3
  cd FontFix_v3
  sudo bash update.sh --check
  sudo bash update.sh

若系统只按 Host 匹配 Nginx 虚拟主机：
  sudo env AUDIT_FONT_HTTP_HOST=服务器地址 bash update.sh --check
  sudo env AUDIT_FONT_HTTP_HOST=服务器地址 bash update.sh

预检会检查包完整性、当前 release、入口与全部原资源版本指纹、Nginx 语法、健康接口和HTTP入口。入口或原资源不是本包对应版本时会停止，不强制覆盖。正式更新会备份当前 index.html，静态资源采用追加方式，入口原子替换；HTTP校验失败自动恢复入口。

成功后浏览器执行 Ctrl+F5，无需重启服务。正式更新输出的备份目录可用于回退：
  sudo bash rollback.sh /opt/audit-agent/frontend-backups/font-fix-v3.xxxxx

本包只适用于当前入口及资源哈希与本包基准一致的 v10 release。入口/资源不匹配时请停止，不要手动绕过检查。

Chrome 109 本机验证覆盖“服务、货物、工程”和 4 组窗口/DPR；真实甲方服务器执行结果仍需现场验证。
