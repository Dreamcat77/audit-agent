# 预算与采购可靠性优化：26 部署及真实流程验收

用户授权更新现有 26 测试环境并测试预算、采购。基线提交为
`e17a1d3eca75c402c371f2adb58d0d59676f4b84`，包含当前工作区已验证改动，
没有提交或推送 Git，也没有修改合同模块业务代码。

## 部署内容与边界

最终运行目录：`/opt/audit-agent/releases/reliability-20261019.h355x7`。
上一个版本及配置恢复记录：`/opt/audit-agent/update-backups/reliability-20261019.OjCGgl`。
首次更新前版本的恢复记录：`/opt/audit-agent/update-backups/reliability-20261019.1mytED`。

最终后端 JAR SHA-256：
`b3fde74344c439c1c780c9efcf5cead00336c0f396630fd3aa19af9af0566250`。
最终部署归档 SHA-256：
`c98c1fb2dc24527568f2350221ad6d396fcdc0298f049e89198977f8414222af`。

使用现有 JDK 17、Python 3.11、DM8、Redis、Nginx 与模型配置，没有安装或升级运行依赖。
V055 提取台账及 V056 报告快照列按受控流程新增，随后检查均为 SCHEMA_MATCHING；
V052、V053、V054 亦匹配。已有对象不自动修复漂移。
切换前检查预算、采购活动队列为零；保留数据和审计，原子切换 current，备有失败恢复。
现有前端逐文件保留，环境文件、AI 地址/密钥/模型名称、systemd、Nginx 的配置哈希不变。

## 实测发现和修复

长叙述预算材料中，“科目名称：办公设备。”的句号被本地标签读取器算进科目名，
而模型返回“办公设备”，造成科目冲突、金额未能归并。没有绕过原文校验或宽松合并科目，
而是将中文句号识别为标签值的句子边界；原文证据保留，金额小数点不受影响。

修改 `BudgetUploadedMaterialReader.java`，增加两个对抗回归：
一是同一科目的标签与叙述金额正确归并；二是句号分隔不同项目时仍不串项目，
`100.01` 等小数完整保留。修复后真实接口的长叙述材料得到申报 120000、批复 100000、
OVER_APPROVAL，差额 20000。

首次采购全对象比较发现唯一差异为报告 metadata 的生成时间：Java 返回纳秒，
DM8 TIMESTAMP 保存微秒；字段路径比较确认没有业务内容变化。
验收脚本仅容许该字段的亚微秒精度差，所有其他字段严格相等；
数据库重开后的多次读取及重启前后指纹仍要求完全一致。

## 已执行测试

本机 Windows / JDK 17：

```powershell
# backend 目录，进程 JAVA_HOME 指向现有 JDK 17
.\mvnw.cmd clean verify
.\mvnw.cmd '-Dtest=BudgetUploadedMaterialReaderTest' test
git diff --check
```

修复后全量构建：2389 tests，0 failures，0 errors，2 skipped，BUILD SUCCESS。
两项跳过是未提供原始合同材料路径的既有刻画测试，不属于预算或采购。
随后强化非空断言，读取器定向测试 15 项全部通过。
日志为 `.runtime/optimization-deploy26-fixed-verify.log` 和
`.runtime/optimization-deploy26-reader-test.log`。

26 正式 HTTP / 当前模型 / DM8 流程：

- 预算核心：中文大写金额、1 分钱差额、缺少批复、批复版本冲突、跨项目隔离，
  结果符合测试预期；重新登录读取一致。
- 采购正常材料：0 条招标风险，未把数值型科目编码误认成不合规问题。
- 采购风险材料：检出品牌、地域、资质及排他限制，指出 90 天与 30 天的交付矛盾；
  三份报价均保留，合成共用 IP/经办人等条件下围串标评分 100，
  结论没有捏造黑名单命中；AI 状态 AVAILABLE。
- 正常和风险采购均通过重新登录重开及六章节 DOCX 导出。
- 修正版 12 份混合上传（6 DOCX、4 XLSX、2 TXT），每份 120000 / 100000 均正确。
  2598 字长叙述材料使用 2 个持久单元、2 次调用，确认耗时累计 13913 ms；
  与两份采购报告共 15 份结果在新会话及后端重启后指纹完全不变。
  真实 DM8 检查预算/采购台账 COMPLETE、累计预算持久化；两份完整报告 CLOB 与原预审快照相符，
  材料版本、招标、报价、围串标章节均保留。
- 约 80 MiB 预算材料，21 片，首片后重启，只补传缺少的 20 片；
  重复片幂等、缺片拒绝、错误哈希拒绝、不可变规格变更拒绝、匿名请求拒绝均通过。
  文件使用合法 XLSX 加合成填充，证明传输能力，不代表 80 MiB 有效文字已交模型处理。
- 同批申报/批复产生 OVER_APPROVAL、UNAPPROVED_SUBJECT、MISSING_DECLARATION；
  再次重启后 DM8 结果完全相等。
- 约 9 MiB 采购 XLSX，3 片上传后解析成功；完整报告六章节、导出不重生成、
  错误 reportId 返回 409；采购项目材料约 80 MiB / 21 片亦通过。

大文件传输回归在本轮首次更新后执行；随后句子边界修复仅改预算标签读取器，
最终版本重测混合上传、叙述金额、持久台账、报告重开、后端重启及浏览器流程。
最终活动预算/采购任务数均为 0，部署包 manifest 再次通过。
本次安全验收日志归档保存在 `.runtime/optimization-deploy26/reliability-acceptance-20261019.tar.gz`，
其中不含环境文件或材料原文。

真实调用命令（所有凭据仅在进程环境与匿名管道中传递，不写入证据）：

```bash
java -cp "$AUDIT_TEST_CP_PATH" BudgetBackgroundRunner "$package/review_probe.py" budget-core
java -cp "$AUDIT_TEST_CP_PATH" BudgetBackgroundRunner "$package/review_probe.py" procurement
java -cp "$AUDIT_TEST_CP_PATH" BudgetBackgroundRunner "$package/transfer_probe.py"
java -cp "$AUDIT_TEST_CP_PATH" BudgetBackgroundRunner "$package/acceptance/tools/test-deployed-reliability26.py"
java -cp "$AUDIT_TEST_CP_PATH" MaterialDeploymentAcceptanceCheck "$package"
```

Chrome 109.0.5412.0 真实浏览器访问现有前端：预算上传自动跳转结果、差异标红与 XLSX 导出、
刷新回首页、按文件名查历史并重开；采购自然语言结果、无技术定位串、无提交审批、
复制状态切换及 DOCX 导出全部通过，pageerror 为 0。
首次更新后还完成严重风险确认并重开；最终修正版再次回归上述预算与采购流程，
确认按钮已按保存状态隐藏。结果与截图位于 `.runtime/optimization-browser26/`。

最终真实健康响应：

```json
{"code":200,"message":"success","data":{"service":"audit-backend","status":"UP"}}
{"status":"UP","service":"audit-ai"}
```

运行 JAR 哈希与构建产物一致，三个服务 active；最终配置及前端与更新前逐文件比对相同。
安装器亦核对首页及其引用的 3 项资源 HTTP 字节、未登录采购接口 401。
后续回滚须保留 V055/V056 和历史记录，排空任务后协调匹配的后端与 AI 版本；
不要删表、清台账或用旧程序重发未知操作。

本轮只使用明确生成的合成文件。测试临时账号结束后禁用，保留业务事实和审计。
没有读取客户真实材料或导出密钥。甲方 ARM64、甲方本地模型与网关白名单：NOT VERIFIED。
