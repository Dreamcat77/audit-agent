# 预算上传反馈与完成后结果跳转

## 行为与范围

本轮仅修改预算页和浏览器回归脚本，保留本工作区既有预算改动。

- 选择文件后提示数量及下一步；上传、解析分析过程中显示简短状态。
- 当前页面发起的本批材料全部状态确定后，有可用结果就自动打开单份材料结果。多个可用结果通过“查看材料”下拉切换，不混合不同文件。
- 部分失败仍显示可用结果；全失败显示失败提示；UNKNOWN 保持待确认，不判为成功。
- 离页、返回、选择下一批或进入历史会取消当前页面的跳转意图，后台任务继续；重新进入预算首页不自动打开旧文件。
- 重新选择原文件恢复不确定的文件夹上传时，继续跟随原上传操作 ID；无法确认身份时给出提示。
- 保留历史列表、日期和名称筛选、分页、逐级返回，以及既有字体和 Chrome 109 兼容资源。

修改文件：`frontend/src/app/BudgetCheckPage.tsx`、`tools/test-budget-material-background.mjs`。已检查 Git diff，未覆盖无关改动；`git diff --check` PASS。

## 本地验证

Windows，现有 Node、JDK 17 和浏览器，未安装系统软件。

```text
cd frontend && npm run build
node tools/build-procurement-browser.mjs frontend/dist .runtime/budget-feedback-v7-final-compatible
node tools/test-budget-material-background.mjs E:/audit-agent-new/.runtime/browser-compat-test/chrome-win/chrome.exe .runtime/budget-feedback-v7-final-compatible budget-feedback-v7-final-109
node tools/test-budget-material-background.mjs C:/Users/29339/AppData/Local/ms-playwright/chromium-1234/chrome-win64/chrome.exe .runtime/budget-feedback-v7-final-compatible budget-feedback-v7-final-151
backend: JDK17 mvnw.cmd clean verify
```

前端构建 PASS；221 项颜色回退保留。Chrome 109.0.5412.0 和 Chromium 151.0.7922.34 浏览器回归 PASS，无页面脚本错误；Chrome 109 自动结果页面截图已目视核查。

回归使用 HTTP 测试替身，覆盖 12 文件、文件夹拖放、21MB 文件、单文件失败继续、丢失响应恢复、离页刷新、历史筛选分页返回、逐文件结果及导出隔离，并覆盖本轮上传提示、自动打开结果、部分失败、UNKNOWN、全失败、历史不被打断和原文件夹身份恢复。它不证明真实模型解析能力。

后端 BUILD SUCCESS：2201 tests，0 failures，0 errors，2 skipped。日志：`.runtime/budget-feedback-v7-backend-verify.log`。本轮后端未改动。

浏览器报告与截图：`.runtime/budget-background-browser/budget-feedback-v7-final-{109,151}*`。

## 26 测试机发布

补丁：`Budget_Feedback_v7.tar.gz`。SHA-256：`42cf370f7377853dff0fca2b936f490ab2f60faa74796611d1aea79823f3be91`。

服务器外层 SHA-256、包内 8 项 manifest、`bash -n update.sh` 均 PASS。脚本锁定上一版 release，取得安装锁后复制既有 release 并覆盖前端，验证成功后输出 `BUDGET_FEEDBACK_UPDATE=PASS`。

- 当前 release：`/opt/audit-agent/releases/budget-feedback-v7-20261015.IATmx3`
- 恢复记录：`/opt/audit-agent/update-backups/budget-feedback-v7-20261015.c5n2PU`
- 原 release：`/opt/audit-agent/releases/budget-simple-v6-20261015.68l2zn`
- 6 项 HTTP 前端资源哈希一致；字体和兼容运行脚本保持原字节。
- 后端 JAR、AI 源码及配置哈希未变；数据库、Redis、Nginx 配置未改，未重启服务，后端与 AI PID 未变。
- `audit-backend`、`audit-ai`、`nginx` 均 active。
- 未登录预算历史与采购项目接口均返回 401。

真实健康响应：

```json
{"code":200,"message":"success","data":{"service":"audit-backend","status":"UP"}}
{"status":"UP","service":"audit-ai"}
```

本轮未上传测试材料到真实模型。26 真实模型端到端解析、甲方 ARM 麒麟实机及低于 Chrome 109 的浏览器均 NOT VERIFIED。

## 仿真测试材料

桌面新增采购 30 份、合同 31 份业务文件，各附使用说明和测试对照。包含 PDF、DOCX、真正二进制 DOC、PNG/JPG、无文字层扫描件、倾斜复印件与 13 页长材料。采购含完整 12 份同项目材料及 F10/F11 线索；合同含正常案例及五类金额/供应商差错。

单位与业务数据均虚构，签章留空。已核验 DOC OLE 文件头、DOCX 结构、PDF 文字层、预设金额关系，并将 PDF 及 Word/DOC 导出 PDF 共 100 页栅格化目视检查。Word 使用已有 Word/WPS COM 导 PDF；标准 render_docx.py 因缺少 bundled LibreOffice 未能执行，未自行安装。

复制至桌面新的日期子目录，保留原文件；全部 65 个业务及说明文件的复制前后 SHA-256 一致。旧 DOC 是格式边界测试，不表示现有上传入口支持 DOC；具体范围见每套说明。尚未将本次仿真材料上传系统进行 E2E。
