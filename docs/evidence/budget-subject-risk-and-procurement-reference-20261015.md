# 预算科目风险区与采购参考结果（2026-10-15）

本轮在已有未提交工作基础上修改，保留先前预算材料解析、金额来源及历史查询改动。未提交或推送 Git，未生成甲方升级包。

## 界面与行为

- 删除预算上传首页的“解析与校验范围”说明区。
- 上传中展示文件名、实际处理阶段及加载圈；不估算百分比，不将状态待确认当作失败或成功。
- 当前页面主动上传的一批材料完成后，定位“预算项目风险分析”，展开这些材料所属科目。焦点仅保留于当前页面内存；手动收起、返回、刷新或重新进入不重复自动展开。其他材料失败不会拦住可用结果。
- 按原材料科目分类项目，电子、工程、装潢是初始示例分类；其他科目原名保留，缺少科目时明确待核对。不根据项目名称猜测科目，不制造项目。
- 选择真实已完成材料的项目行生成参考意见，右侧历史列表重新打开已保存快照。金额仍使用 BigDecimal；缺失、超批复、冲突及同项目不同申报版本分别提示，不将不同版本直接相加，不因多个同科目项目就认定拆分申报。
- 风险区不再提供“进入采购审查”跳转。
- 采购审查以参考结果为终点，删除可见提交审批步骤；新需求保存后进入结果页面。保留既有审计与自愿风险知悉记录，不删除后台历史审批事实或更改旧接口。
- 采购报告、各结果块及文件结果有复制按钮；支持浏览器 Clipboard API 和内网 HTTP 的传统复制方式。复制单块时排除其他块、按钮及复制状态文案。

## 持久化与安全

新增 V053 `BUDGET_MATERIAL_RISK_RESULT`，记录不可变结果 ID、所属用户、时间和完整结果快照。只读取 V052 已持久化的材料结果，不把材料 taskId 当成采购项目 ID，不依赖预置批复主数据。

分析写入与长期业务审计在同一事务中。相同操作 ID、相同选择范围可重放；改变范围返回 409。未确认请求结果时，浏览器保留原操作 ID；并发唯一键冲突在原事务回滚后从新事务重读，DM8 -6602 有限定于此适配器的分类处理，其他数据库错误继续失败。

`BudgetMaterialRiskPlatformCheck` 对已有字段、默认值、主键、约束、索引顺序进行校验；只允许创建完全缺失的目标表，不修复真实 Schema Drift。代码回滚保留 V053 历史数据。

## 验证证据

工作区：当前 `fix/budget-home-split-risk-20261014` worktree。

Java 17 环境在 backend 执行 `./mvnw.cmd clean verify`：2263 tests，0 failures，0 errors，2 skipped。

前端执行 `npm run build` 及以下回归：

```text
node --experimental-strip-types --test frontend/src/features/budget/*.test.ts frontend/src/features/budget/f05/*.test.ts frontend/src/features/procurement/materials/*.test.ts frontend/src/features/procurement/review/*test.ts
43 tests, 43 pass, 0 fail
```

Chrome 109 兼容资源由 `tools/build-procurement-browser.mjs` 生成，保留原布局 CSS 并追加颜色兼容回退。`tools/test-budget-material-background.mjs` 分别使用真实 Chrome 109.0.5412.0 与 Chromium 151.0.7922.34 执行，均通过且无 pageerror。测试包括 12 份材料、21 MB 文件、文件夹拖入、部分失败、丢响应、离开/刷新、仅本批一次展开、科目选择、历史重开、日期筛选与返回，以及六个采购结果块的实际剪贴板内容。浏览器的上传 API 使用模拟响应，此项验证交互和兼容性，不代表该模拟文件经过真实 OCR。

26 测试机真实 DM8：V053 增量创建和目录校验通过；真实 Repository 的 100 KB 级 CLOB、用户隔离、分页、唯一键分类、保存点与整体事务回滚通过，探针行全部回滚。

26 测试机真实 HTTP：`tools/test-budget-material-risk26.py` 上传两份合成 TXT，分别形成未超批复与超批复事实。6 个同 ID 并发分析请求全部返回 200、同一快照且历史只有一条；真实 DM8 查询确认结果和对应长期业务审计各一条。改变范围返回 409，未登录读取返回 401。重启后以同一用户新会话重开，结果指纹不变。合成测试账号已禁用，测试事实与审计保留。

26 HTTP 健康响应：

```json
{"code":200,"message":"success","data":{"status":"UP","service":"audit-backend"}}
```

HTTP 入口页面与 3 个直接引用资源的 SHA-256 均与发布目录一致。AI 服务、现有环境文件及 Nginx 配置不变，配置文件哈希核对通过。

当前测试发布：`budget-risk-v18-20261015.rlP0Rv`。回退记录：`budget-risk-v18-20261015.bAM81w`。

后端 JAR SHA-256：`353fc452528bf7130f6fa5c066cf95e1cb448d4921d230973e55348ab7a149ad`。

甲方 ARM 环境、甲方实际字体渲染和甲方模型网关：**NOT VERIFIED**。新风险参考分析基于已保存材料事实进行确定性核对，不声称新增了大模型风险判断或完整的法定拆分申报结论。
