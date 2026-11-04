# 预算结果重点提示与真实金额对照验证

本轮限定为预算结果展示与打开结果时的滚动位置。保留原有布局、文件历史和导出，不修改后端金额判定。

## 问题与调整

用户截图中的设备超支和维修疑似漏项缺少视觉重点。重新读取测试服务器 v25 HTTP 资源，用真实保存的仿真结果在 Chrome 109 渲染，已有差异清单和红色样式；未复现截图全部白卡，不能认定截图问题一定由缓存引起。

本次让结果更容易阅读：顶部显示“优先核对以下 N 项”；各科目增加明确状态和结论，例如“超出批复 20,000.00 元”，再给出简短核对建议。疑似错支、漏项分别说明本批材料中未找到对应批复、申报，不直接认定违规。金额冲突、无批复、无法确认金额继续保持待核对状态。差异卡片采用局部普通 sRGB 红色边线、浅红底和状态标签，避免依赖旧浏览器不支持的颜色表达式。

额外回归发现旧滚动容器查询在当前 PortalShell 中不存在，结果打开时滚动复位无效。查询增加实际 `.portal-main` 容器，自动打开结果和切换结果时滚到顶部。回归先缩小浏览器高度并确实滚动上传页，再验证完成后 scrollTop 为 0。

涉及文件：`BudgetCheckPage.tsx`、`BudgetFileResults.tsx`、`budgetDifferences.ts`、`budgetDifferences.test.ts`、`styles/budget-results.css`、`styles/index.css`、`tools/test-budget-material-background.mjs`、`tools/test-budget-real-result-view.mjs`。工作区既有改动保留，未提交 Git。

## 真实 HTTP / DM8 验证

在 26 测试服务器通过现有测试 runner 创建临时仿真账号，调用真实批次创建、材料上传、结果查询接口；任务和结果由正式 DM8 适配器持久化。结束后账号禁用，仿真任务和操作审计保留。

执行：`sudo bash /home/admin/budget-focus-v26-20261016/run.sh`。输入仅为仿真材料：桌面原始申报与批复 XLSX，以及构造的 TXT 边界资料。

七组均 PASS：

- 原始两份 XLSX：设备 120000 / 100000，超支 20000；培训疑似错支；维修疑似漏项；耗材未超批复。
- 缺批复：INSUFFICIENT，不冒充错支。
- 申报等于批复：WITHIN_APPROVAL。
- 超出 0.01 元：OVER_APPROVAL。
- 同一科目批复金额冲突：CONFLICT。
- 项目不同：INSUFFICIENT，不拼接为同一项目。
- 批复为 0、申报为 1：OVER_APPROVAL。

实际输出 `REAL_HTTP_DM8_BUDGET_FOCUS_SEVEN_CASES=PASS`，各组 HTTP 200，结果已保存；最终 `SYNTHETIC_BUDGET_ACTOR_DISABLED_AUDIT_AND_TASKS_RETAINED`。

本轮主要验证结构化材料与金额对照，不代表所有文件格式的 OCR / 模型提取均在本轮重测。

## 本地构建与浏览器验证

```text
cd frontend
npm.cmd run build
cd ..
node tools/build-procurement-browser.mjs frontend/dist .runtime/budget-focus-v26-ready-browser
node --test frontend/src/features/budget/budgetDifferences.test.ts frontend/src/features/budget/budgetResultState.test.ts
node tools/test-budget-material-background.mjs <Chrome109> .runtime/budget-focus-v26-ready-browser chrome109-v26-ready
node tools/test-budget-material-background.mjs <Chromium151> .runtime/budget-focus-v26-ready-browser chromium151-v26-ready
node tools/test-budget-real-result-view.mjs <Chrome109> .runtime/budget-focus-v26-ready-browser ready-chrome109
node tools/test-budget-real-result-view.mjs <Chromium151> .runtime/budget-focus-v26-ready-browser ready-chromium151
git diff --check
```

全部通过。Chrome 109.0.5412.0 与 Chromium 151.0.7922.34 无 pageerror。金额/状态单元测试 4 项通过。整组浏览器测试覆盖合成 12 文件、部分失败隔离、未知状态、离开/刷新不抢页面、历史过滤分页、自动打开及滚动复位、导出和采购复制等既有回归。

真实结果展示脚本使用 26 七组测试中保存的两份 XLSX 响应，合成身份和 API 路由，验证实际后端 payload 与前端的配合；这不是实时登录上传的完整浏览器 E2E。差异卡背景实测 rgb(254, 242, 242)，状态字色 rgb(185, 28, 28)，正常卡白底，两份结果各有两项差异，设备明确显示超出 20,000.00 元。

后端在既有 JDK17 下执行 `backend/mvnw.cmd clean verify`：2296 tests，0 failures，0 errors，2 skipped，BUILD SUCCESS。日志 `.runtime/budget-focus-v26-verify.log`。

## 26 前端部署

兼容前端 tar SHA-256：`acd5469e78b774cc8767bc719aa4835f20add9532338588e14d4e3dfd8eb1c38`。

- release：`/opt/audit-agent/releases/budget-focus-v26-20261016.xGoOpi`
- recovery：`/opt/audit-agent/update-backups/budget-focus-v26-20261016.XFA9IC`
- previous：`/opt/audit-agent/releases/budget-auto-result-v25-20261016.TAad5d`

锁定前一 release、校验归档并复制 release 后原子切换前端；后端 JAR 逐字节一致，配置哈希不变，服务未重启。实际输出 `BUDGET_FOCUS_TEST26_DEPLOY=PASS`。入口 HTML 与三个直接引用资源的 HTTP 内容逐字节匹配。健康响应：

```json
{"code":200,"message":"success","data":{"service":"audit-backend","status":"UP"}}
```

部署后两种浏览器再执行真实 payload 展示脚本，静态资源取自测试服务器 HTTP，样式与金额断言再次通过。截图位于 `.runtime/budget-focus-browser/deployed-chrome109-0.png` 与 `deployed-chrome109-1.png`。

甲方 ARM 环境、甲方浏览器具体补丁版本及其模型网关：NOT VERIFIED。本次未制作甲方升级包。
