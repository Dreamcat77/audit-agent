# 采购材料批量接收部署与验证记录（2026-10-08）

## 范围

- 仅调整采购模块的新建采购需求材料接收流程。
- 支持一次选择并上传 1 至 10 份材料，单文件不超过 20 MB、总计不超过 40 MB。
- 用户不再区分“主材料/补充材料”；系统逐份调用既有材料解析能力，并综合填充缺失字段，已由用户确认的非空字段不会被后续材料覆盖。
- 采购需求与全部材料关系持久化到 DM8；保留 V046 单材料关联作为回滚兼容读路径。
- 前端沿用既有弹窗、表单网格、颜色与按钮样式，只把材料角色选择区改为只读的已接收材料清单。

## 代码

- `fe00f91 feat: support multiple procurement materials`
- `00ddc6f fix: treat procurement uploads as one material batch`
- `f489128 fix: map procurement material identities from dm8`

部署中真实 DM8 回滚式验收发现 `SOURCE_MATERIAL_IDS` 未显式映射到持久化投影，已在 `f489128` 修复并增加静态回归断言。第一次验收事务已回滚，没有留下临时采购数据。

## 本地验证

环境：Windows，JDK 17。

- `backend/.\mvnw.cmd clean verify`
  - 2149 tests
  - 0 failures
  - 0 errors
  - 2 skipped
- `node --test --experimental-strip-types "src/**/*.test.ts"`
  - 699 tests
  - 0 failures
- `npm run build`
  - 成功
  - 仅保留既有大 chunk 警告

## DM8 迁移

服务器：`<CUSTOMER_HOST>`（历史证据已脱敏）。

- 迁移：`db/dm8/V047__procurement_multi_material_link.sql`
- 迁移前 V046 关联行：2
- 重复 `MATERIAL_ID`：0
- 新表初始迁移行：2
- 迁移后旧关联缺失：0
- 新表：`PROCUREMENT_DEMAND_MATERIAL`
- 迁移前快照：`/opt/audit-agent/backups/procurement-multi-material-20261008`
  - `procurement-material-link.csv`
  - `pre-migration-columns.csv`
  - `applied-migration.sql`

迁移为新增表和数据复制。应用回滚时保留 V047 表，旧版本继续读取 V046 表，不删除已迁移数据。

## 发布

- 当前版本：`/opt/audit-agent/releases/f489128-20261008-procurement-multi-material`
- 回滚基线：`/opt/audit-agent/releases/00ddc6f-20261008-procurement-multi-material`
- 更早业务基线：`/opt/audit-agent/releases/9c71aaf-20261008-source-filter`
- 后端 JAR SHA-256：`84dbec57222a97920d0cbcb7b35c497769051abf96a3ebd37e80c1e32dd64ac0`
- `audit-backend`、`audit-ai`、`nginx`：`active`
- 后端启动后 ERROR/Exception 计数：0

## 上线验证

- Java 后端直连健康检查：HTTP 200
- Nginx 健康检查：HTTP 200
- 前端根页面：HTTP 200
- 前端生产资源：HTTP 200，资源为 `/assets/index-CHNAXTWG.js`
- 新批量材料文案存在；已删除的主/补材料选择文案不存在
- 未登录访问采购材料接口：HTTP 401
- `test_admin` 登录：业务码 200
- 登录后可选材料接口：业务码 200，返回 2 条可用解析材料
- 登录后采购项目列表：业务码 200，当前页返回 13 条

真实 DM8 回滚式业务验收：

- 在一个事务中使用 2 份现有可用材料创建临时采购需求。
- `PROCUREMENT_DEMAND_MATERIAL` 写入 2 条关联。
- 查询投影返回相同的 2 个材料 ID 和原始顺序。
- 兼容 `IS_PRIMARY` 标记恰好 1 条，仅供旧字段兼容，不暴露给用户选择。
- 事务回滚后 `PROCUREMENT_DEMAND` 保持 24 条，`PROCUREMENT_DEMAND_MATERIAL` 保持 2 条。

## 语义边界

新建需求阶段会逐份解析上传材料并综合表单字段，用户无需指定材料角色。创建后的招标文件合规审查、围串标/拆分采购辅助识别仍沿用采购模块既有的显式分析流程；本次没有扩大到通用智能体、RAG 或 AI 金额决策。
