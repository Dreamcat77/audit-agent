# 预算与采购检查（2026-10-12）

用户限定本轮业务检查范围为预算、采购，以及二者使用的公共文件、AI/OCR、诊断和持久化边界。保留此前已完成的代码和合同诊断改动；合同业务不属于本轮专项审查。未修改页面样式、AI 地址/密钥/模型名称、Nginx 配置或部署环境；没有执行生产数据写入或服务重启。

## 发现与修复

1. 采购材料 worker 停机中断可能被收口为 FAILED/UNKNOWN，使待处理材料不能在重启后自动发现。`MaterialIntakeService.processInternal` 在处理前及终态提交前检查线程中断，保留 PROCESSING 和原租约；到期后由既有扫描器重新打开同一材料。`MaterialIntakeServiceTest` 增加三项回归，覆盖 claim 后中断、模型异常并中断、模型成功返回但线程中断。先运行测试复现三项失败，修复后三项通过；没有增加隐式重试已确定失败文件的行为。
2. 前端根依赖强制安装 Windows 专用 Rollup 包，阻碍其他平台源码安装。移除该直接依赖，使用 Rollup 自身的平台可选依赖。Linux/ARM64 原生源码构建本轮 NOT VERIFIED。
3. npm audit 报告 react-router、Vite 和预算导出所用 SheetJS 的三项 high 风险。升级至 react-router 7.18.4、Vite 6.4.4、官方 CDN 的 xlsx 0.20.3；锁文件固定 URL 和完整性值。现有预算功能只调用 SheetJS 导出，不在浏览器解析上传 Excel。升级后 npm audit 报告零已知漏洞，这不等于证明软件不存在其他漏洞。
4. 三个浏览器回归工具原来从仓库外 node_modules 加载 Playwright，干净检出不能复现。通过 frontend/package.json 解析依赖，并声明 playwright-core 1.62.1。浏览器可执行文件由参数指定，不安装系统软件。预算浏览器回归追加实际点击导出、下载工作簿并验证中文科目和金额的检查。

采购/预算后台队列、租约与过期发现、陈旧提交隔离、逐文件处理、操作身份、权限/CSRF、目录拖放、离页继续、刷新恢复、预算返回/全部历史与迟到响应隔离的代码及回归均已核对。本轮没有发现其他阻止发布的可复现问题；不作“绝对没有 bug”的结论。

## 本轮执行证据

环境：Windows x64，现有 JDK 17.0.18、Node 24.14.0、Python 3.11 测试虚拟环境。

```powershell
# backend；JAVA_HOME 指向现有 JDK 17
.\mvnw.cmd clean verify
# frontend
$tests=rg --files src | Where-Object {$_ -match '\.test\.ts$'}
node --experimental-strip-types --test @tests
npm run build
npm audit --json
# services/audit-ai；已有测试 venv，src 加入 PYTHONPATH
.venv-test/Scripts/python.exe -m pytest tests
# repo；浏览器路径使用本机现有可执行文件
node tools/build-procurement-browser.mjs frontend/dist .runtime/module-review-20261012-frontend-final
node tools/test-budget-material-background.mjs <chrome109.exe> .runtime/module-review-20261012-frontend-final review-budget-final-109-20261012
node tools/test-budget-material-background.mjs <chrome-current.exe> .runtime/module-review-20261012-frontend-final review-budget-final-current-20261012
node tools/test-procurement-material-background.mjs <chrome109.exe> .runtime/module-review-20261012-frontend-final review-procurement-final-109-20261012
git diff --check
```

观察结果：后端 2185 tests，0 failures、0 errors、2 skipped，BUILD SUCCESS；前端 722/722；Python 453/453（现有 Starlette httpx 弃用提示一条）；生产前端构建成功（仍有大 bundle 提示）；npm audit 全部风险计数为零。采购中断 red 为 19 tests/3 failures，green 为 19/19。原始日志均在忽略目录 `.runtime/module-review-20261012-*`，不提交业务日志或运行文件。

真实浏览器运行构建产物，HTTP 使用明确测试替身：Chrome 109.0.5412.0、155.0.8059.39 预算均通过；Chrome 109 采购通过。12 份目录材料包含 21 MB 文件，第一份失败不阻止其余，丢失上传响应后保持同一身份，关闭窗口/切换模块后继续，刷新可重新发现。预算验证 36 条历史分页、清空浏览器存储后服务器历史仍可打开、迟到结果不能跳回、UNKNOWN 不冒充 FAILED、未审查/失败不冒充通过；实际导出中文与 123.45 金额通过。采购验证清空中迟到响应不重新填充、下一需求仅关联新材料、全部十个表头加载自带字体且无外部资源请求。无页面脚本错误。

独立 local JAR 使用 127.0.0.1:18087，文件存储放置于本仓库忽略的运行目录；完成后已停止。真实 `curl.exe -i` 响应：

```text
GET /api/v1/health -> HTTP 200，存在 X-Request-Id
{"code":200,"message":"success","data":{"service":"audit-backend","status":"UP"}}
GET /api/v1/budget/validation-records（匿名）-> HTTP 401，存在 X-Request-Id
GET /api/v1/procurement/materials（匿名）-> HTTP 401，存在 X-Request-Id
{"code":401,"message":"authentication required","data":null}
```

## 外部验证边界与提交

本轮未连接真实 DM8/Redis 或甲方本地模型，未实施远程部署。既有未修改的队列 SQL、迁移及预算历史真实 DM8 证据见 `procurement-background-materials-20261010.md`、`budget-background-materials-20261010.md`、`budget-result-navigation-20261010.md`；其日期和执行环境保持明确，不代替本轮中断修复的真实平台验证。甲方 ARM64 原生构建/运行、定制浏览器、本地模型：NOT VERIFIED。测试通过不能保证任何输入或任何模型都返回有效业务字段。

源码通过独立 feature 分支发布，不直接覆盖 main 或其他开发分支。提交包含此前预算/采购后台处理、历史入口、字体和公共诊断源码；保留既有合同诊断及其回归，不宣称本轮已做合同专项审查。只提交代码、测试、迁移、源码工具、模板/文档和获许可字体；数据库导出、业务文件、环境配置、离线归档和原始日志均不纳入 Git。
