# 合同自动化改造源码备份记录

时间：2026-10-27（运行环境本地时区）

备份对象是当前共享工作树的未提交状态；它含有合同、采购、预算、公共解析/AI 和已有交付成果。没有 reset、clean、checkout 或覆盖操作。

| 项目 | 备份前实测值 |
| --- | --- |
| Git 根目录 | `E:\audit-agent-new\.runtime\budget-home-e17-20261014` |
| 分支 | `fix/budget-procurement-ready-20261019` |
| HEAD | `35f95499c4d5efa475dffc3a3628222750124672` |
| 已跟踪改动 | 143 个文件 |
| 未跟踪文件 | 4,447 个文件 |
| 实际复制并校验 | 4,590 个文件，0 个哈希不匹配 |
| 备份目录 | `E:\audit-agent-new\_protected-backups\contract-automation-20261027\state-files` |
| 文件清单 | `E:\audit-agent-new\_protected-backups\contract-automation-20261027\changed-files.sha256` |
| 跟踪文件差异补丁 | `E:\audit-agent-new\_protected-backups\contract-automation-20261027\working-tree.patch` |
| 补丁 SHA-256 | `e9198b891bbd9e1d1820e5900248dc7084aabd9fb1af0588c58fc95d58165871` |
| 清单条目 | 4,590 |

每个未删除的 tracked/untracked 文件在复制前计算 SHA-256，备份副本再计算一次并逐项比较；结果一致后才记录为复制成功。初始 Git 状态另存为 `initial-git-status.txt`。备份目录 ACL 已关闭继承，只授权当前 Windows 用户完全控制。

## 范围与限制

精确状态副本包含 Git 报告的所有已跟踪改动和未跟踪文件，包括现存测试证据、构建输出和离线包。` .deploy26-private` 等受 ACL 保护且被 Git 忽略的本机私有部署目录没有读取或复制，也没有被修改。早先一次全目录复制因该访问限制中止；该不完整副本不作为恢复依据。`.git` 对象库由原工作树保留，备份另行记录完整 HEAD 和 tracked diff。

当前没有对 DM8 执行 DDL/DML。已有阶段报告说明，原业务库跨 schema 的完整导出/恢复未获证明；因此后续只允许隔离数据库验证。26 服务器在确认其环境、数据库备份及迁移可恢复性前不执行数据库变更。

## 恢复步骤

1. 不覆盖当前工作树。新建隔离 worktree/目录，并检出记录的 HEAD `35f95499c4d5efa475dffc3a3628222750124672`。
2. 先在副本上校验 `changed-files.sha256` 中的文件，再按相对路径将 `state-files` 叠加到新 worktree；清单中的 `working-tree.patch` 只作为 tracked diff 的额外复核材料，不需要再对已叠加文件重复应用。
3. 对叠加结果重新计算 SHA-256，并与清单比较；核对 `initial-git-status.txt` 后再继续。
4. 该备份不是 DM8 数据备份，也不能用于回滚已经成功执行的 DDL。数据库回退必须依据现场实际 Catalog 和单独验证的恢复方案。

备份已在复制后做逐文件哈希核验；尚未在另一位置执行完整恢复演练，故恢复流程为已记录、未演练。
