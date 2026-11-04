# 合同分析记录的项目名称查询

## 问题与处理

列表显示的项目名称可能来自已取得原文依据的 `PROJECT_NAME` 分析事实，数据库 `PROJECT.NAME` 此时仍可能为“自动整理项目…”临时名。此前 DM8 查询只匹配 `PROJECT.NAME`，因此页面上可见的“智能化内控模块维护及优化服务”，输入“智能化”却返回空列表。

展示与查询现在共用 `ContractAnalysisProjectNames`：先使用有效的人工/既有名称，再采用分析事实中的项目名称，最后使用同一生成名称兜底。列表查询不读取整份原文、不进行模型调用、不修改项目元数据，也不在整个 JSON 正文里匹配关键词。关键词按字面包含匹配，并统一大小写与全半角。

DM8 先按当前用户、上传时间范围与原有稳定排序分批读取快照及项目名，每批最多 100 行；匹配展示名称后再对匹配结果分页，找到所需页后停止。无关键词时直接在数据库分页。快照与项目名同批返回，避免为每个候选额外查询一次快照。内存实现遵循同一名称与分页规则。

该方案无需迁移历史数据。名称搜索在未命中或请求较后页时，可能遍历当前用户、所选日期内的全部候选；大规模历史记录应另行测量延迟，再考虑维护可索引的展示名称投影，不能改为仅搜索当前页。

## 验证

`ContractAnalysisHistorySearchTest` 覆盖：

- 数据库临时名与实际展示名不一致时，前缀和中间片段均可命中。
- 人工改名后使用新名称，不匹配旧分析名称或其他快照文字。
- 匹配记录出现在第 100 个候选之后，仍能找到；匹配后分页不漏项。
- 用户隔离和上传日期的左闭右开区间。
- `%`、`_` 按普通字符处理，大小写、全半角及首尾空白归一化。
- 生成兜底名称查询，以及 MyBatis 参数绑定与 CLOB 映射。

针对性测试命令：

```sh
./mvnw -Dtest=ContractAnalysisHistorySearchTest,ContractAnalysisReportTest,ContractAnalysisAdmissionTest test
```

2026-11-04 云环境针对性测试 16 项通过；完整 `clean verify` 共 3457 项，3432 项通过、24 项跳过、1 项既有失败：`CandidateCrossMaterialAuthorityArchitectureGuardTest.keepsSupportRecoveryEndpointLocalAndMachineClassified`，仍为未改动的前端 `client.ts` 固定哈希不匹配。

另在隔离的 `local` 配置启动真实 HTTP 服务，仅插入两条临时合成记录；通过 `curl` 完成 Session/CSRF 登录与列表查询。未筛选返回两条；“智能化”返回一条，名称为“智能化内控模块维护及优化服务”；“模块维护”命中同一条；日期不匹配时返回空列表。该验证使用内存仓储，不代表 DM8。

DM8 仓储已用模拟 Mapper 和真实 MyBatis SQL 构建器验证；实际 DM8 上的新批量快照查询 **NOT VERIFIED**。部署前需在代表性 DM8 上核对 CLOB 读取、分页、用户/日期条件及查询耗时，不能将本地内存或模拟结果当作正式数据库验证。
