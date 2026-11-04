# 采购材料后台批量解析验收（2026-10-10）

本任务修改材料上传API/服务、DM8队列查询及租约更新、后台worker、材料字段合并、Python分段结果聚合、前端窗口外上传管理、目录拖放和V050约束迁移。保留此前详细诊断、表头字体和批量材料关联能力；没有提交或推送现有工作区修改。

本地回归：Windows，现有JDK17.0.18，Node24.14.0，Python3.11虚拟环境。

```powershell
# 在backend目录；JAVA_HOME使用现有JDK17
.\mvnw.cmd clean verify
# 在frontend目录
$tests = rg --files src | Where-Object { $_ -match '\.test\.ts$' }
node --experimental-strip-types --test @tests
npm run build
# Python完整测试使用现有测试依赖路径及services/audit-ai/src作为PYTHONPATH
python -m pytest services/audit-ai/tests
```

后端2174项，0 failures，0 errors，2 skipped，BUILD SUCCESS；前端716项通过；最终Python453项通过。原始日志分别位于`.runtime/async-material-backend-verify-final3.log`、`.runtime/async-material-frontend-tests-final4.log`、`.runtime/async-material-python-empty-final.log`。`git diff --check`通过。

浏览器实际操作：Chrome109.0.5412.0、Chrome155.0.8059.39，均在Windows本地运行真实构建，API使用明确的测试替身，不冒充真实模型验收。

```powershell
node tools/build-procurement-browser.mjs frontend/dist .runtime/async-material-frontend-final4
node tools/test-procurement-material-background.mjs E:/audit-agent-new/.runtime/browser-compat-test/chrome-win/chrome.exe .runtime/async-material-frontend-final4 109-delivery-font
```

12份嵌套目录文件拖放通过，包含21MB文件；第一份失败后其他继续；模拟保存成功但丢失HTTP响应，原操作仍可恢复；关闭窗口和切换模块后继续上传；刷新后11完成/1失败，上传操作仍是12个，没有重新生成ID。无页面脚本错误、无外部资源请求。Chrome109通过CDP确认全部10个表头实际使用自带Audit Header SC，字号12px/行高18px。证据位于`.runtime/async-material-browser/`。原CSS字节前缀保留，仅追加221条颜色回退。

代表性真实平台：测试机26，麒麟V10 x86_64，BiSheng JDK17.0.19，现有DM8、Redis、Python/AI服务。V050用受控迁移工具执行，31行前后保持一致；V048已为100，14行不变。最终r8迁移复核时材料43行不变。JDBC真实语义检查在未提交的合成行上执行并全部rollback：QUEUED领取一次、有效租约不可重复领取、续租令牌校验、过期发现、重新领取后旧提交/续租被拒、FAILED不被扫描器隐式重试。输出：

```text
REAL_DM8_QUEUE_CLAIM_RENEW_EXPIRED_DISCOVERY_STALE_COMPLETION_FAILED_NOT_RETRIED_ROLLBACK=PASS
```

离线更新脚本在同一Linux平台以隔离目录/命令替身执行回归，覆盖成功、手动回滚、HTTP入口错误、服务重启错误、DDL错误、包篡改、不安全链接、配置被修改、存在后续release。输出：

```text
BACKGROUND_UPDATE_SUCCESS_ROLLBACK_HTTP_SERVICE_SCHEMA_FAILURE_TAMPER_LINK_CONFIG_LATER_RELEASE=PASS
```

最终部署路径`/opt/audit-agent/releases/0b64c63-material-background-20261010-r11`，增加精确空对象处理、输入段数限制和输出截断细分；后端JAR与r8相同，此次只重启AI服务。原后端环境、AI环境及Nginx配置SHA-256核对通过；三个服务active。真实HTTP响应：

```text
GET /api/v1/health -> 200
{"code":200,"message":"success","data":{"status":"UP","service":"audit-backend"}}
GET AI /health -> 200
{"status":"UP","service":"audit-ai"}
GET /api/v1/procurement/materials（未登录） -> 401
```

前端入口HTTP哈希与最终release一致。JAR SHA-256：`d862802287a181654da1598f3ba6347706277afd4a915f1064200bb43dc17417`。

真实12份异步HTTP/模型验证：首版合成测试发现损坏XML的POIXMLException被误标存储UNKNOWN，已修复为DOCUMENT_CONTAINER_INVALID并加入相邻正常文件继续解析测试；大文件合成器的Content-Type声明也已修正。r8真实批次12份全部上传入队，最大28,420,985字节，上传耗时0.33秒；退出并重新登录后结果可查询。损坏文件FAILED/DOCUMENT_CONTAINER_INVALID，10份正常文件PARSED，长文件FAILED/AI_EXTRACTION_UNAVAILABLE，其余文件未被阻断。因此r8不能声称11份正常文件全部解析成功。

长文件日志定位到18段中的第4段：模型HTTP200，prompt_tokens1773、completion_tokens1536，finish_reason=LENGTH，JSON约4020字符截断为UNTERMINATED_STRING。不是额度耗尽证据，也不是没有采购字段就提前跳过。r9网关保留finish_reason并仅对明确截断段进行有限细分，保留原定位符和末尾内容，不提交部分响应；新测试覆盖真实HTTP替身截断、有效但不完整JSON、单段尾部、不重试401/429、最小段限制及容量释放。真实同类长文件复测已实际在第4段记录MATERIAL_CHUNK_SPLIT/outcome=RETRY_SMALLER。

仅对截断段重试的r9同类长文件仍达到15分钟预算：25个短段落虽不足3200字符，也会引发输出膨胀。r10提前限制每次最多6段并保留全篇扫描，76段实际执行耗时153688毫秒；最后一段模型返回{}，此前严格schema要求rows，因此整文件仍失败。独立真实调用仅输出校验类型/位置，确认错误为rows缺失，无输出业务内容。r11只接受精确空对象作为本段无字段，继续扫描，不把整篇无字段标为成功；未知非空对象、rows=null等仍拒绝。旧版本失败记录和材料完整保留，不伪装为成功。

最终r11真实复测通过：28,420,985字节DOCX入队后在新登录会话查询，76段全部扫描，最终合并为1行/14项有原文依据的事实，Java保存PARSED。材料ID为73dbc771-2752-4475-b866-36aebec23a19。输出`REAL_DM8_LARGE27MB_OUTPUT_TRUNCATION_RECOVERY=PASS`，合成测试账号已停用。服务器证据：`/home/admin/batch12-20261010/async-large-empty-final.log`及`results-async-large.json`。此结果验证一份合成长材料，不保证任意真实文件都能提取有效字段；失败仍逐文件显示。

最终交付包`.runtime/material-background-delivery-final-v4/AuditAgent_Procurement_Background_20261010.tar.gz`，70,934,112字节。SHA-256：`46021b823153ad08ddd0b231a246267df0778d22b9e91b61f2172facdb7ccf02`，服务器与本机一致。包仅包含应用代码、迁移和诊断工具，没有生产业务数据、环境文件、模型Key或SSH凭据；旧material-background-delivery、final、v2、v3版本仅为验证中间产物，勿交付。

甲方ARM64原生机器、实际定制浏览器及本地模型：NOT VERIFIED。当地需执行包内update.sh并通过其真实数据库/HTTP校验；26结果不能证明甲方模型接口、认证、额度或输入输出限制符合要求。更新后客户端Ctrl+F5。
