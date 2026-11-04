Schema_Fix_v1 — 原 v10 更新目录的最小检查器补丁

仅支持未经改动的 Audit_Update_v10 正式包。
应用 commit 保持 0dd7bd5496aaaec487da35cbd2b710f761de37b7。
仅替换部署检查器及其测试、补充限定表达式解析类，重新登记版本 v10.1 和文件哈希。
不连接数据库，不修改业务程序、迁移 SQL、模型配置、Nginx，不重启服务。

支持的等价识别严格限定：
CONTRACT_SEMANTIC_PLAN.CK_SEM_PLAN_BUDGET
CONTRACT_SEMANTIC_UNIT.CK_SEM_UNIT_COUNTER
识别整数加法、比较式与 AND 的冗余括号。列名、数值、比较符变化仍阻止升级。
甲方真实 SEARCH_CONDITION 尚未取得；最终以现场 --check 为准。

在 /root/audit_agent 下解压到独立目录，然后执行：
sudo bash Schema_Fix_v1/apply.sh /root/audit_agent/Audit_Update_v10
cd /root/audit_agent/Audit_Update_v10
sudo bash update.sh --check
只有 CHECK=PASS 后才执行：sudo bash update.sh

如已应用，重复执行只校验文件完整性，不重复替换。
原文件备份在更新目录的 .schema-fix-v1.* 子目录。
替换过程普通写入失败时自动恢复；断电或强制中断时保留备份供人工检查，不能跳过清单校验。
若报 SCHEMA_BLOCKED，不删除表、不重刷 SQL、不绕过检查；保留具体 reasonCode 并核对只读 Catalog。
原 v10 升级留下的增量 DDL 应保留，不能删除业务表或恢复数据库旧快照。
