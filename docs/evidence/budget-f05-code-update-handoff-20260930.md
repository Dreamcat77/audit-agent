# 预算模块 F05 代码更新交接（2026-09-30）

## 1. 交接目的

本文供后续会话继续维护预算模块 F05“项目拆分风险提示”能力时使用，记录本轮实际代码、验证、Git 和部署状态。

本轮严格限定在预算模块 F05，没有修改同事负责的合同、采购审查、黑名单、系统管理或 AI/OCR 业务。后续会话也不得仅因顺手优化而扩大修改范围。

## 2. 需求依据与产品口径

需求文件提出：预算材料审查应对“同一项目被拆分为多个小额项目申报”的行为自动触发风险提示，并输出风险等级和涉及内容。

排期表将该能力定义为：

- 编号：F05；
- 名称：项目拆分风险提示；
- 模块：预算管理；
- 包含金额拆分识别、风险评分、风险等级、记录与配置持久化、前端风险页面、接口联调和边界测试。

产品界面不直接展示生硬的“F05 项目拆分风险提示”标题。本轮统一为“预算项目拆分风险分析”，并避免使用“测试”“演示”等不适合即将上线软件的文案。

## 3. Git 状态

- 工作树：`E:\audit-agent-new\.runtime\integration-repo`
- 分支：`feature/backend-b-budget-procurement-f05-20260930`
- 当前功能提交：`fc8c10e fix: align F05 split-risk analysis with requirements`
- 远端：`origin/feature/backend-b-budget-procurement-f05-20260930`
- 本轮提交完成后工作树为 clean。

后续会话开始时必须重新执行：

```powershell
git status --short
git branch --show-current
git log -1 --oneline
```

不要覆盖用户或其他开发人员的新改动，不要执行强制推送、`git reset --hard` 或删除分支。

## 4. 本轮修改文件

仅修改以下 6 个 F05 文件：

1. `backend/src/main/java/com/fuquan/audit/budget/application/BudgetSplitRiskEvaluator.java`
2. `backend/src/main/java/com/fuquan/audit/budget/application/BudgetSplitRiskService.java`
3. `backend/src/test/java/com/fuquan/audit/budget/application/BudgetSplitRiskEvaluatorTest.java`
4. `frontend/src/features/budget/f05/BudgetSplitRiskPanel.tsx`
5. `frontend/src/features/budget/f05/budgetSplitRiskOperation.ts`
6. `frontend/src/features/budget/budgetAuditWorkspaceStaticContract.test.ts`

不得把本轮范围描述成采购模块改造；F05 属于预算管理。

## 5. 核心逻辑变化

### 5.1 分组语义

旧实现按“预算科目 + 供应商”分组，但采购申报创建阶段供应商允许为空。多个空供应商会被错误归为一组，与“同一项目拆分”需求不一致。

当前实现按以下字段进行确定性分组：

- 相同预算科目；
- 规范化后的相同项目名称（去除空白并统一大小写）；
- 已配置规则的时间窗口。

供应商信息不再决定是否属于同一拆分项目，仅用于供应商集中度和辅助评分。只有组内所有记录存在同一供应商时，结果才展示该供应商；不会虚构或补写供应商。

当前业务模型没有可跨多条采购申报复用的正式“上游项目 ID”，所以暂时使用严格规范化的项目名称作为保守关联键。不得擅自加入模糊匹配、AI 相似度匹配或跨项目推断。若以后取得正式项目标识，应另立任务迁移关联语义。

### 5.2 数据完整性

以下数据缺失时，后端拒绝分析，前端同时禁止选择并显示原因：

- 有效金额；
- 预算科目；
- 项目名称；
- 记录时间。

旧实现会静默过滤无效记录，可能把数据问题表现为“无风险”；当前实现改为 fail closed。

### 5.3 前端产品文案

主要文案调整：

- 标题：`预算项目拆分风险分析`；
- 操作：`执行风险分析`；
- 未命中：`本次选定范围未触发已配置规则`；
- 命中：`发现 N 组需关注记录`；
- 不向用户暴露内部规则编码；
- 不把缺失供应商描述为真实供应商信息；
- 不使用“无风险”“正常”等过度结论。

### 5.4 真实持久化链路

F05 不是前端 Mock。当前链路为：

1. 前端读取真实采购申报记录；
2. `POST /api/v1/budget/split-risk/check` 发起分析；
3. 后端从 DM8 读取采购数据和已启用规则；
4. 执行确定性金额与时间窗口规则；
5. 分析结果、幂等结果和业务操作审计写入 DM8；
6. 前端保存分析记录号；
7. 刷新后通过 `GET /api/v1/budget/split-risk/assessments/{assessmentId}` 重新打开持久化结果。

客户端幂等键在结果不确定或处理中保持不变，不得刷新时自动生成新键。

## 6. 未改变的内容

本轮没有：

- 修改既有金额阈值、评分权重或时间窗口配置；
- 新增或执行数据库迁移；
- 修改 Redis、AI、OCR、登录、权限或 Session 配置；
- 修改合同、采购审查、黑名单或其他同事模块；
- 创建虚假采购项目或修改现有采购申报；
- 向 Git 写入数据库口令、SSH 口令、API Key 或真实环境配置。

后续若甲方要求删减页面或变更阈值，必须取得明确口径后单独修改，不能根据界面观感自行猜测。

## 7. 自动化验证证据

### 7.1 后端

环境：Windows，JDK 17。

执行：

```powershell
cd E:\audit-agent-new\.runtime\integration-repo\backend
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot"
.\mvnw.cmd clean verify
```

结果：

- Tests run：2068；
- Failures：0；
- Errors：0；
- Skipped：2；
- `BUILD SUCCESS`。

F05 定向单元测试和 API 集成测试也已通过，覆盖同项目命中、不同项目不合并、时间边界、阈值边界、缺失供应商和无效金额拒绝等场景。

### 7.2 前端

执行：

```powershell
cd E:\audit-agent-new\.runtime\integration-repo\frontend
node --test --experimental-strip-types "src/**/*.test.ts"
npm.cmd run build
```

结果：

- 前端测试 691/691 通过；
- Vite 生产构建通过；
- 仍存在既有单包体积超过 500 kB 的警告，本轮未跨范围处理代码分包。

### 7.3 Git 检查

- `git diff --check` 通过；
- 本轮差异敏感信息扫描通过；
- 提交只包含第 4 节列出的 6 个文件。

## 8. 服务器部署状态

- 访问地址：`http://192.168.0.26/`
- 当前发布目录：`/opt/audit-agent/releases/fc8c10e-20260930-f05-requirement-alignment`
- 回滚目录：`/opt/audit-agent/releases/799bdbb-20260930-1236-portal-css-fix`
- 当前后端提交：`fc8c10e`
- 后端服务：`audit-backend.service`，active；
- AI 服务：`audit-ai.service`，active；
- Nginx：active；
- `GET /api/v1/health`：HTTP 200；
- 站点入口：HTTP 200；
- 部署后的当前 JS 包包含新标题，旧标题不存在。

此次发布没有改数据库、环境变量、systemd unit、Nginx 配置、Redis 或 AI 服务代码。

## 9. 真实 DM8 最小验证

使用已有账号和现有 DM8 采购记录进行了以下验证，但本文不记录任何账号或密码：

- 登录：HTTP 200；
- 当前用户：HTTP 200；
- 采购项目列表：HTTP 200；
- 当前读取 7 条采购申报，7 条具备参与分析所需字段；
- F05 分析写入：HTTP 200；
- 根据分析记录号回读：HTTP 200；
- 写入响应与回读响应的全部业务字段一致。

唯一差异是 DM8 将 `createdAt` 从 Java 纳秒精度舍入到数据库微秒精度，观测差值为 302 纳秒。分析编号、金额、风险等级、评分、命中记录和采购记录 ID 均未因此变化。这属于数据库时间精度表现，不是结果丢失。

为避免污染即将上线的数据，本轮没有人为创建“必然命中”的虚假项目。因此：

- 真实 DM8 写入与重开链路：`VERIFIED`；
- 自动化正向命中和边界逻辑：`VERIFIED`；
- 当前服务器现有数据上的专用正向风险样本：`NOT VERIFIED`。

后续如需真实正向验收，应由业务方指定可使用的非敏感验收数据或批准新增验收数据，不能私自造数。

## 10. 下个会话建议顺序

1. 重新读取仓库 `AGENTS.md` 并确认实际工作树、分支和 `git status`。
2. 打开服务器页面，确认甲方认可“预算项目拆分风险分析”标题和当前字段布局。
3. 如甲方提供正式项目唯一标识，先做领域和数据来源确认，再考虑替换项目名称关联键。
4. 如甲方要求删减，先获得明确的保留项清单；不要提前删除规则、字段或持久化数据。
5. 如需真实正向案例，先取得可写数据授权，保留分析记录号和脱敏结果证据。
6. 任何新修改完成前再次运行后端 `clean verify`、前端全量测试和生产构建。

## 11. 禁止事项

- 不要修改同事负责的合同或采购审查代码来“顺便统一”；
- 不要在前端加入“测试”“演示”“Mock”等上线文案；
- 不要把 `INFO` 或未命中规则解释成绝对无风险；
- 不要用 AI、浮点数或动态脚本替代确定性金额规则；
- 不要将 Redis 作为分析结果事实源；
- 不要在 Git、文档或日志中记录真实口令、Token、Cookie 或完整连接串；
- 不要未经批准执行 DM8 DDL、造数、删除历史分析记录或改变现有阈值。
