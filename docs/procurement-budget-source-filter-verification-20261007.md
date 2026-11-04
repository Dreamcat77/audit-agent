# 预算、采购文案及来源过滤验收记录

验证日期：2026-10-07。功能提交：`9c71aaf`。

## 修改与备份

预算、采购页面、校验说明、预审提示和采购需求导出中的用户文案由“字段”改为“信息”。技术属性、接口、数据库列名及合同等其他页面保持原有契约。

采购专用来源查询在排序取前 N 条之前排除已经被未删除采购需求关联的解析记录，覆盖所有采购状态及既有来源关联。依据真实来源 ID 过滤，不以项目名称或文件名推断关联。同名且来源不同的未使用记录仍可选择；没有删除历史重复记录。已逻辑删除的需求不再占用来源。

解析完成后前端自动查询可选来源，只有仍可选的记录才填入表单。旧页面提交同一来源时后端拒绝重复添加；创建事务先以 DM8 `FOR UPDATE` 锁定来源，再检查已有采购需求并保存需求与关联。此锁在数据库事务内生效，不只依赖 JVM 锁。local 采购内存适配器同步保护读写并保持相同重复拒绝语义。

修改前基线为 `78f3eb1`，备份为仓库内 `.runtime/pre-source-filter-20261008.bundle`。Git 差异检查及新增差异敏感信息扫描通过。本轮没有数据库迁移或阈值调整。

修改路径：

- `frontend/src/app/App.tsx`，`frontend/src/features/procurement/materials/ProcurementMaterialUpload.tsx`。
- `backend/src/main/java/com/fuquan/audit/budget/{api/BudgetController,application/BudgetReviewService}.java`。
- 采购预审、Excel 导出、local 项目及来源适配器。
- DM8 项目、来源、材料适配器及对应两个 Mapper 和 XML。
- 采购材料 AI 未连接适配器的提示文案。
- 对应预算/采购测试，新增 `ProcurementSourceAvailabilityTest`。

## 自动化回归：通过

Windows / JDK 17：

```powershell
cd backend
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot'
.\mvnw.cmd clean verify
```

最终结果为 2147 tests，0 failures，0 errors，2 skipped，`BUILD SUCCESS`。其中预算包 110 项、采购包 137 项、公共采购材料解析包 14 项；DM8 适配器相关测试包含在其余测试中。日志：`.runtime/source-filter-backend-final.log`。

```powershell
cd frontend
node --test --experimental-strip-types 'src/**/*.test.ts'
npm.cmd run build
```

698/698 通过，Vite 生产构建通过。仍有既有大包提示。日志：`.runtime/source-filter-front-tests.log`、`.runtime/source-filter-build.log`。

```powershell
cd services/audit-ai
.\.venv-test\Scripts\python.exe -m pytest -q
```

使用既有测试虚拟环境，423/423 通过。存在既有 Starlette TestClient 废弃提示，没有为此改依赖。系统 Python 缺少 FastAPI 的初次尝试没有作为通过证据。日志：`.runtime/source-filter-ai-full.log`。

覆盖业务流程：材料解析及信息归一化、缺失与冲突信息、固定幂等身份、UNKNOWN 恢复、分批上传不触发分析、上传文件重开、分析清单变化拒绝、统一分析及自动报告、同材料报告重试、严重风险确认与权限、三方报价、招标规则及 AI 降级、围串标辅助规则、预算拆分边界及持久化接口。新回归验证过滤先于 limit、各种需求状态、同名不同来源、删除后释放来源，以及 32 线程竞争创建只成功一次，其他 31 次拒绝。

## 代表性平台与负载验证

26 部署平台，真实 DM8 和 Redis，Java 17。程序、命令和日志保留在服务器 `/home/admin/source-filter-check`，本地驱动位于 `.runtime/source-filter-check`。驱动使用现有测试管理员建立短期验收 Session，结束后删除并验证其消失，没有输出 Cookie、密码或连接串，没有模拟正常登录成功。

执行 `sudo bash /home/admin/source-filter-check/verify.sh http://127.0.0.1`，经过已部署 Nginx、后端认证授权、Redis Session 及 DM8。循环访问采购来源、采购列表、预算校验记录三个 GET 接口，验证 HTTP 200 和业务响应 code 200。

最终生产入口阶梯读取负载（并发 2/8/16/32）：

- 2：40 请求，138.33 req/s，P95 30.68 ms。
- 8：160 请求，400.92 req/s，P95 33.26 ms。
- 16：320 请求，602.27 req/s，P95 50.28 ms。
- 32：640 请求，884.85 req/s，P95 78.33 ms，最大 102.74 ms。
- 持续 20 秒、16 并发、每轮间隔 50 ms：5646 请求，281.53 req/s，P95 12.79 ms，最大 25.79 ms。

上述 HTTP 合计 6806 请求，失败 0。日志：`production-final.log`。此前隔离验收实例 HTTP 压力也通过；不将其误标为正式入口结果。

同配置验收进程对真实 DM8 执行来源和采购列表组合读取，共 1740 次组合操作：32 并发下 2972.30 次组合/s，P95 18.64 ms，最大 25.69 ms。它是服务层测试，不等同于 HTTP 指标。Hikari 实测 max=10、minIdle=10、connectionTimeout=30000 ms，峰值 active=10、pending=23，结束后 active=0、pending=0。未发生连接获取超时。

Hikari 数值来自验收进程 MXBean，不能当作生产 JVM 的实时采样。生产 JVM 已通过 `jcmd <MainPID> Thread.print` 检查：无 Java 死锁、一个 Hikari housekeeper、检查时没有残留采购招标 AI worker。压力后存在 33 个空闲 HTTP worker，这是容器保留工作线程，不据此判定泄漏。生产 JMX 连接池实时指标、数小时泄漏/老化试验未执行。

真实 DM8：已使用来源 2 条均从下拉消失，可选来源 2 条；使用两个独立 JDBC 连接验证来源行锁阻塞竞争者，回滚释放后竞争者继续。重复来源创建被拒绝，采购需求总行数前后相同。没有创建或删除正式业务数据。此处验证真实锁和已用来源拒绝，不宣称在正式库完成两个新建写事务竞争提交；新建竞争回归使用 local 内存适配器。

发布后以独立进程执行既有验收驱动 `acceptance.sh reopen`，真实 DM8 中原正常验收项目的 5 份材料和报告重新打开成功，报告 ID 保持原值。只读取，没有重新分析或生成新报告。服务器日志：`/home/admin/source-filter-check/reopen.log`。

预算计算压力：`BudgetLoad` 对每次 200 条合成采购记录、20 个项目执行金额规则，2/8/16/32 并发分别完成 100/400/800/1600 次分析，全部结果与串行基准一致。32 并发 4334.72 analyses/s，P95 16.52 ms。BigDecimal 金额及风险记录一致，无死锁；没有向 DM8 写入这些记录。该数据集与持续时长有限，不能外推任意规模或生产容量 SLA。

## 真实 AI：单次通过，双并发未通过

使用前轮提供的正常、风险采购申请单 DOCX，在验收进程读取材料后通过适配器调用已有真实 AI 提取接口；验证结果通过既有来源证据归一化，不持久化业务记录。该测试经过 Python 提取接口的既有限流，直接调用适配器，不能算作包含 Java 上传限流的完整上传压力测试。Java 材料限流及模拟 AI 超时/取消/资源释放另有回归证据，未更改 AI 模型、超时或并发配置。

- 首次双并发：两个调用均约 60 秒后失败。
- 单次正常申请单：约 45.93 秒，10/10 项信息、0 冲突，通过。
- 再次双并发：正常申请单约 33.61 秒取得 10/10 项信息；另一个返回 HTTP 502，`MATERIAL_AI_FAILED`，整轮约 60.44 秒，失败 1。

命令：`sudo bash /home/admin/source-filter-check/verify.sh ai`；单次命令：`sudo bash /home/admin/source-filter-check/verify.sh ai-single single`。日志：`ai.log`、`ai-single.log`、`ai-concurrent-final.log`。单次日志中的 `REAL_AI_TWO_CONCURRENT` 是驱动共享标签，实际单次命令只提交一个文件。

这证明 AI 服务当前能提取完整信息，但双并发稳定性验收未通过。约 60 秒失败与已存在的模型读取超时预算相符，502 原因码属于模型提取失败；平台日志未提供更细的上游故障原因，不能断言 Token 用尽或已准确定位所有超时根因。没有通过增加超时或线程掩盖问题。模型侧并发容量和延迟仍需专项处理，不能将本轮结论表述为“全部功能和 AI 压测都没有问题”。

真实 OCR 压力、大文件/混合扫描件的全链路吞吐、多实例材料分析互斥及长时间稳定性均 NOT VERIFIED。现有材料处理限流为单实例，不能外推分布式容量。

## 发布与回滚

发布目录：`/opt/audit-agent/releases/9c71aaf-20261008-source-filter`；上一目录：`/opt/audit-agent/releases/78f3eb1-20261008-extraction-button`。SHA-256 包校验通过后原子切换 current，重启后端，保留旧版回滚路径。AI 服务代码和部署配置沿用旧版，没有数据库 DDL、环境变量、缓存或 Nginx 配置改动。

后端 JAR SHA-256 本地与部署文件一致：`065ce4d510dc0d4e69be35044aaa432c38cb9ea71ba702948ead67296de6d9b3`。

实际 curl 健康响应：

```json
{"code":200,"message":"success","data":{"status":"UP","service":"audit-backend"}}
```

站点入口及新 JS `/assets/index-w5Zv7OkL.js` HTTP 200；backend、AI、Nginx active。浏览器站点可打开登录页；没有业务账号密码，因此未通过人工登录补拍采购对话框截图。已通过真实认证 HTTP 验证来源过滤及生产构建验证 UI 代码。

功能提交已普通推送至现有 feature 分支，没有强制推送。
