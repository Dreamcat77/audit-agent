# 采购与预算取消文件名清单

本轮仅删除 `ProcurementMaterialUpload.tsx` 和 `BudgetCheckPage.tsx` 中各两行“成功文件”“失败文件”清单 JSX。保留数量汇总、布局、上传限制、后台批次、采购填充和预算结果切换。工作区既有修改保留，无提交或推送。

同步原有浏览器回归断言为清单不存在；打包工具增加可选名称和 README，默认参数保持原值。本轮仅支持已安装预算后台批量处理完整版的前端基线，未知入口拒绝覆盖。

Windows Node/JDK17：

```powershell
# frontend
$tests=rg --files src | Where-Object {$_ -match '\.test\.ts$'}
node --experimental-strip-types --test @tests
npm run build
# backend (JAVA_HOME 指向既有 JDK17)
.\mvnw.cmd clean verify
# repo
node tools/build-procurement-browser.mjs frontend/dist .runtime/material-counts-only-frontend
node tools/test-procurement-material-background.mjs E:/audit-agent-new/.runtime/browser-compat-test/chrome-win/chrome.exe .runtime/material-counts-only-frontend material-counts-only-procurement-109
node tools/test-budget-material-background.mjs E:/audit-agent-new/.runtime/browser-compat-test/chrome-win/chrome.exe .runtime/material-counts-only-frontend material-counts-only-budget-109
node tools/test-budget-material-background.mjs 'C:/Program Files/Google/Chrome/Application/chrome.exe' .runtime/material-counts-only-frontend material-counts-only-budget-current
python tools/build-procurement-simple-package.py .runtime/material-counts-only-frontend .runtime/material-counts-only-delivery .runtime/budget-background-frontend-final --name AuditAgent_Material_Counts_20261010 --readme docs/material-counts-only.md
git diff --check
```

前端722项全部通过，构建成功。JDK17 `clean verify` 完成，2182项、0失败、0错误、2 skipped、BUILD SUCCESS，日志 `.runtime/material-counts-only-backend-verify.log`。`git diff --check` 通过。Chrome109.0.5412.0采购/预算及Chrome155.0.8059.39预算浏览器测试通过，使用明确HTTP替身：12文件目录拖放（含21MB文件）、首文件失败不挡其余、丢失响应恢复、离页继续、刷新同操作ID、数量汇总及无成功失败文件清单，无页面错误。采购清空填充/迟到响应隔离/新材料提交、原表头字体继续通过，预算结果切换保留。截图经人工查看确认。报告分别在 `.runtime/async-material-browser/material-counts-only-procurement-109.json` 和 `.runtime/budget-background-browser/material-counts-only-budget-{109,current}.json`。

26保持当前 release `/opt/audit-agent/releases/budget-background-20261010.YqsRoH`，仅执行前端原子入口更新。归档SHA、manifest、脚本语法、所有前端资源HTTP SHA通过；健康接口200，返回 `{"code":200,"message":"success","data":{"status":"UP","service":"audit-backend"}}`，未登录采购和预算材料接口401。backend/ai/nginx均active。更新前后后端JAR、backend.env、audit-ai.env及Nginx配置SHA一致，未重启服务。日志 `/home/admin/batch12-20261010/material-counts-only-update.log`，入口备份 `/opt/audit-agent/frontend-backups/material-simple-20261010.lWfcik`。Nginx检查保留既有 server_name 冲突警告，语法测试成功。

桌面 `Audit_agent/AuditAgent_Material_Counts_20261010.tar.gz`，570319字节，SHA-256 `7a201ee6afdfa51bf7107e965b5310e5c5d105b126973e00562ce0ee547bbb89`。复制后源/桌面哈希一致，并有 `.sha256` 文件。保留上轮完整预算更新包，本轮是其后的仅前端补丁；不含环境、密钥或业务材料。

甲方ARM64实机浏览器、甲方模型和本轮交付回滚脚本执行：NOT VERIFIED。本轮没有改变解析链路，浏览器HTTP替身不构成真实模型解析验证。
