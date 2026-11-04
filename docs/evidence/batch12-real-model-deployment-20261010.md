# 12 份大材料真实链路与 26 更新证据（2026-10-10）

26 测试机已更新前端、Java 后端和独立 Python AI 服务，并保留原 release。此次验证使用虚构材料、真实 DM8、Redis Session 和当前真实模型网关。甲方的 ARM64 离线环境与本地模型仍为 `NOT VERIFIED`，不能将本次结果等同于甲方验收。

## 交付版本与配置保护

- 工作树基于 `0b64c63`，含本任务和已有未提交变更；不是已经提交的 Git release。
- 当前目录：`/opt/audit-agent/releases/0b64c63-batch12-20261010-r3`。
- 当前 JAR SHA-256：`baf1dfda1ac19cf2a8514d1258808a7e189573f9d5afb970875a0e6bda236ccd`。
- 前端入口资源：`/assets/index-BNztzUbu.js`。
- `/etc/audit-agent/backend.env` 与 `/home/admin/audit_ai_runtime/audit-ai.env` 部署前后 `sha256sum -c` 均为 `OK`。模型地址、密钥和名称未修改。
- Nginx 配置没有修改。500 MB 的完整代理上传仍为 `NOT VERIFIED`；实际验证上限不能由代码配置推断。
- 原 release `/opt/audit-agent/releases/f489128-20261008-procurement-multi-material` 以及中间 release 均保留。回滚应用时切换 `current` 指向经过核实的原目录并重启 `audit-ai`、`audit-backend`；两项扩宽迁移保留，不通过缩列、删表或删文件回滚。

## 材料和执行方式

生成 36 份材料，每个模块 12 份，总量约 58 MB。采购、合同为 DOCX，前 8 份约 2.8 万字符，后 4 份约 5.6～9.2 万字符，第 12 份含有效 BMP 图片，文件约 27 MB。正文包含明确编号的技术要求、项目身份与尾部标记。预算为 XLSX，每份 80 行，科目名称约 132 字符、384 UTF-8 字节。未使用真实甲方材料，未测试扫描 PDF/OCR，不能据此声称扫描件链路通过。

生成器、Java 测试账号 runner、HTTP runner、模型探针及受控迁移工具保留在忽略目录 `.runtime/`。服务器执行目录为 `/home/admin/batch12-20261010`，账号随机密码仅通过匿名管道传递，不输出到文件。测试身份结束后停用，相关测试业务与审计记录保留，不删除既有数据。

实际执行包含以下命令；runner 从受保护环境加载数据库凭据，命令和输出不包含凭据：

```bash
cd /home/admin/batch12-20261010
bash run-batch12.sh final
bash run-batch12.sh budget-retry
bash run-batch12.sh procurement-retry
BATCH12_HTTP_BASE=http://127.0.0.1 bash run-batch12.sh acceptance-final
bash run-batch12.sh contract-single
/opt/python/venvs/audit-ai/bin/python batch12-model-probe.py
```

重新执行会新增明确标记的虚构测试记录，不是只读诊断。原材料失败重试复用其材料 ID。执行结果保存在同目录 `results-*.json`，仅记录允许的状态、耗时、计数及标识，不记录模型文本或配置。

## 观察结果与修复

采购：12 份材料全部 `PARSED`，包含 27 MB DOCX。此前长文本直接超过 48,000 字符，现扩大受控文本预算至 192,000，按包含 JSON 和定位符开销的约 3,200 字符输入分块。明确编号的技术要求逐字保留并附原定位符，其余内容仍调用模型；此样本不能证明任意 19.2 万字无结构文本均能在时限内完成。模型提取输出限制 1,536 token，检测 `LENGTH`，拒绝不完整输出。总处理预算 900 秒、Java 读取时限 965 秒、lease 1,200 秒。容器/单 entry/展开限制调整后保留路径穿越及压缩炸弹保护。

生产同型 DM8 的 V047 顺序约束原为 1～10，与代码允许 100 份不一致。受控应用 `V048__procurement_material_order_100.sql` 后，12 份关联创建 HTTP 200，重新读取列表 HTTP 200、关联数 12。真实 DM8 查询确认主材料只有 1 份、顺序 1～12；将第 12 份改为顺序 101 被数据库拒绝并回滚。读取原材料记录确认 `PARSED=12`、尾部证据 `TAIL_EVIDENCE=12`。经过 Nginx 再上传 27,300,386 字节 DOCX，HTTP 200、`PARSED`，耗时 32.83 秒。

预算：12 份 XLSX 上传解析通过；原真实 DM8 `SUBJECT_NAME` 的 200 字节列无法存储长中文名称。受控应用 `V049__budget_subject_name_utf8_capacity.sql`，两个科目名称列扩至 800 字节，原 12 个任务重试 review 和 AI 均 HTTP 200，AI `available=true`。当前业务规则发现异常后等待人工确认，因此这 12 个任务每次 AI 只有当前持久化的 1 项；不等同于每份 80 项完整业务审批通过。Python 预算模型输入按完整 item JSON 分块，每批最多 8 项，要求返回精确覆盖该批编号；遗漏、重复和跨批编号均失败。80 项的独立真实模型压力测试完成：输入描述合计 10,773 字符，HTTP 200、code 200，返回 80 项、80 个不同编号，446.14 秒，10 次批次调用均成功。该探针不写 DM8，不经过 Nginx，不能替代业务审批验收；尤其不能证明耗时超过现有 650 秒代理读取时限的请求也能通过。

合同：12 份上传均成功，文件 `AVAILABLE/PARSED`，全文合计 524,836 字符持久化。12 份联合分析触发现有 6,000 字符 × 20 块输入保护；修复后 Nginx 入口真实 HTTP 413、业务 code 413，明确提示分批选择并保留文件，未将失败伪装为成功。单份约 2.8 万字分析 HTTP 200、code 200、`available=true`、30 条提示，461.48 秒。12 份联合完整语义分析仍不支持；没有扩大或截断全文来隐瞒此边界。

上述真实操作没有出现缺表错误，但本次不是对全部 schema 的完整比对。已确认数据库约束、中文列宽以及模型输入/输出限制都可能导致用户看到失败。采购尾部证据保留只证明该样本处理完整，不证明 AI 语义结论正确；金额规则仍由确定性逻辑负责。

## 修改和回归

主要修改为：`MaterialIntakeService`、`MaterialFieldNormalizer`、`FastApiMaterialFieldAdapter`、`MaterialParsingConfiguration`、`ContractTextAnalysisController`；Python `model/material_fields.py`、`model/router.py`、`model/gateway.py`、`main.py`；DM8 V048、V049 及 README。部署还包含既有的前端字体兼容、独立文件上传队列和文件夹拖入变更，未改变模型配置。

本地 Windows / JDK 17 / Python 3.11 的最终验证：

```powershell
# backend 目录，JAVA_HOME 指向现有 JDK 17
.\mvnw.cmd clean verify
# services/audit-ai 目录，PYTHONPATH 包含 .runtime/batch12-test-deps
python -m pytest -q
# frontend 目录
node --test --experimental-strip-types "src/**/*.test.ts"
npm run build
# 仓库根目录
git diff --check
```

后端 `2157 tests, 0 failures, 0 errors, 2 skipped`，`BUILD SUCCESS`；Python `433 passed`；前端 `708 passed`，构建成功；diff 检查通过。具体日志为 `.runtime/batch12-backend-final-verify.log`、`batch12-ai-final-full.log`、`batch12-front-full.log`、`batch12-front-build.log`。回归覆盖超过旧 20 MB 的容器、路径穿越/压缩炸弹、保留尾部定位符、无字段不得伪成功、预算条目原子分块及错误编号、合同超限 413。

26 真实平台为 Kylin Linux Advanced Server V10 / x86_64，BiSheng OpenJDK 17.0.19、Python 3.11.16。运行 `/opt/java/current/bin/java` 与 `/opt/python/venvs/audit-ai/bin/python`，通过 protected 环境进行真实 DM8 连接；以下为重新读取证据：

```bash
set -a
source /etc/audit-agent/backend.env
set +a
cd /home/admin/batch12-20261010
/opt/java/current/bin/java -cp 'classes:jar/BOOT-INF/lib/*' Batch12DbVerify results-procurement-retry.json
/opt/java/current/bin/java -cp 'classes:jar/BOOT-INF/lib/*' Batch12StateCheck results-final.json
```

观察到 `REAL_DM8_LINKS12_PRIMARY1_ORDER1_TO12=PASS`、`REAL_DM8_ORDER101_REJECTED_AND_ROLLED_BACK=PASS`、`DM8_REOPENED_EVIDENCE_AND_TEST_ACTORS_DISABLED=PASS`、`SYNTHETIC_ACTIVE_TEST_ACCOUNTS=0`。真实 Session 登录验证使用现有 Redis；无需改生产缓存或 Python 核心 OCR 依赖。

部署后外部入口检查：

```bash
curl -sS --max-time 10 -w '\nHTTP=%{http_code}\n' http://127.0.0.1/api/v1/health
curl -sS --max-time 10 -w '\nHTTP=%{http_code}\n' http://127.0.0.1/api/v1/procurement/projects
systemctl is-active audit-backend audit-ai nginx
```

实际响应分别为 `{"code":200,"message":"success","data":{"service":"audit-backend","status":"UP"}}` / HTTP 200，`{"code":401,"message":"authentication required","data":null}` / HTTP 401；三个服务均 `active`。
