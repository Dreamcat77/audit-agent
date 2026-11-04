# 采购参考结果界面整理验证

## 变更

- 删除采购详情的两步流程栏及“审查中”状态标记，列表去除审批状态筛选、状态列及审批周期统计。
- 新预审不再因采购项目未提交或未审批而产生风险；旧预审及报告仅在读取展示时排除精确匹配的 `PROJECT_STATUS` 项，不修改历史业务事实、报告原文和风险确认记录。
- 风险概述说明所属板块及具体问题，保留真实风险。历史汇总等级无法由明细解释时，保留等级并说明历史明细不足，不默认为无风险。
- 严重风险确认仍针对原始已保存报告；复制和材料上传流程保留。

## 本地验证

环境：Windows、JDK 17.0.18，当前工作分支 `fix/budget-home-split-risk-20261014`。

- backend：`./mvnw.cmd clean verify`，BUILD SUCCESS；2265 tests，0 failures，0 errors，2 skipped。
- frontend：`npm run build` 成功；前端 Node 回归 49 tests，49 pass，0 fail。
- `git diff --check` 通过。
- `node tools/build-procurement-browser.mjs frontend/dist .runtime/procurement-reference-v20-browser` 成功。
- `node tools/test-budget-material-background.mjs <browser-executable> .runtime/procurement-reference-v20-browser <label>`：Chrome 109.0.5412.0 与 Chromium 151.0.7922.34 均通过，浏览器错误列表为空。覆盖移除步骤栏和状态、历史状态风险过滤、具体风险说明、复制及风险确认。浏览器场景使用模拟业务 HTTP，不代表模型/OCR E2E。

本地详细输出：`.runtime/procurement-reference-v20-verify.log`、`.runtime/procurement-reference-v20-frontend-tests.log`、`.runtime/budget-background-browser/chrome109-v20.json` 及对应 Chromium 结果。

## 测试服务器部署

2026-10-16，使用 `.runtime/update-procurement-reference26-v20.sh` 执行受校验和保护的部署，保留旧版本和回滚记录，仅重启后端。

- 部署：`PROCUREMENT_REFERENCE_TEST26_DEPLOY=PASS`。
- 入口版本：`/opt/audit-agent/releases/procurement-v20-20261016.2DxnXc`。
- 回滚记录：`/opt/audit-agent/update-backups/procurement-v20-20261016.ZKPz4H`。
- HTTP：`GET /api/v1/health` 返回 200，`{"code":200,"message":"success","data":{"status":"UP","service":"audit-backend"}}`。
- 首页及 3 个直接引用静态资源与部署文件逐字节相符。
- `CONFIG_HASHES=UNCHANGED`，AI 服务保持运行；未更改模型地址、密钥、名称、Nginx 或真实环境配置。
- `.runtime/run-procurement-reference-probe26.sh`：真实登录并只读查询采购 API，13 份既有预审结果未返回 `PROJECT_STATUS`。临时测试账号随后禁用，保留安全审计；未重新分析材料，未修改业务数据或表结构。

后端 JAR SHA-256：`c7a3b43140f55df7ff8be8ef09e5854ea759a11d33232e3f51a80350f7bdd26d`。

前端部署压缩文件 SHA-256：`382fe5339b4ba606191c7d7233e9026a355a8f6876b7fc6b6b813d1ad7a01053`。

甲方 ARM 环境、现场字体显示及甲方模型调用：NOT VERIFIED。本次未提交或推送 Git，也未制作甲方升级包。
