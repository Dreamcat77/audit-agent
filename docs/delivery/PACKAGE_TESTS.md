# Update_v11部署工具验收

- 工具Java17编译通过，22个class均major61。CustomerContractSchema、CustomerReadiness、ContractAnalysisSchema与真实DM8实验版本class哈希一致；schema.json SHA256为00b20ef024c1b2493ea55b4513d4c55eb35de143bd66f9180d4aa3ad310ee834。
- 26独立DM8实例AUTOCASE2，QA_UPDATE_V11仅用于迁移实验。第一次空Schema执行11条DDL成功；重复check/apply为0条且不生成重复执行journal；代码回退后预检识别已执行DDL。
- Catalog真实数据对比反例3项、实际临时Schema约束禁用/默认值/FK列顺序漂移3项和部分组状态反例1项均拒绝。故障注入的更改仅在独立空Schema执行并恢复；生产升级器没有自动修复能力。
- 完整QA26_FULL2既有Schema只读预检通过，原业务Schema未参与实验。
- 新任务门槛真实DM8七种active/UNKNOWN状态均拒绝，两种COMPLETED状态允许，合成fixture事务回滚。证据../qa-26/evidence/remote/customer-v11-readiness-verification.json。
- 恢复记录守卫：纯合成root0600文件1正向/5反向通过；这不是实际数据库备份/恢复验证。旧drop-in首次/重复更新、恢复和篡改5项Linux沙箱测试通过。
- 44项限定语义数字约束反例和11项新案件约束表达测试通过；Fake测试只作补充，不替代以上真实DM8结果。
- ARM64实际update.sh --check、完整更新/回滚及客户真实业务E2E均NOT VERIFIED。原库恢复未确认时正式更新停止；不以本地构建或26 x86_64结果冒充甲方升级成功。
- 从封包最终tar独立解压内容逐文件复制67个脚本，在26 Linux核对每个复制文件SHA后执行：5个Shell脚本bash -n、62个Python文件ast.parse，全部通过。证据../qa-26/evidence/remote/package-v11-syntax-verification.json；这是静态语法验证，不是ARM64 update.sh --check执行。
