# 26 独立 QA 部署记录

2026-10-29 UTC，本轮已实际部署 `qa26-20261027-r6`，入口 `http://<TEST_SERVER>:18082`。

- 候选包 SHA-256：`e47deadc55e71480f5635e950b08a5640021b28fa0554d441193a66339c9022e`，80个文件。
- 构建来源：HEAD `a0a9cce974027810e44002dd1d316892ef0f0897` 加当前工作树；成品manifest记录三个源码目录逐文件SHA和Java全量验证日志SHA。GitHub最终提交仍待整理。
- 部署前和停止QA worker后均通过8表活动任务静默检查。
- 新JAR真实DM8历史分页/精度/owner隔离探针：131 rows，PASS；探针事务回滚，无DDL。
- Java、AI健康检查、Web入口与所引用静态资源SHA校验成功。
- 原80当前release逐文件SHA保持不变，原业务库无DDL/DML，现场数据库/AI配置保持不变。
- 检查点：`/home/admin/audit-agent-backups/unified-material-20261028-r1`，保留QA r5 release与私有配置备份、切换恢复记录。

真实浏览器原12文件验收与有限回归队列已经启动。部署成功仅表示健康/版本/保护检查通过，业务验收结果另记。
