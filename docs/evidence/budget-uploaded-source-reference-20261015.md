# 预算材料驱动参考结果验收

业务输入仅为用户上传材料。预置预算映射、供应商、项目配置不再是新后台上传链路形成预算参考结果的前提。DM8 保存不可变批次成员清单、提取依据和结果快照；没有新增“材料批复金额”业务列，也不向映射主数据写入提取金额。旧正式校验入口和历史记录保持原语义。

## 实现边界

- `BudgetUploadedMaterialReader` 复用完整办公文档/OCR 读取端口，直接读取有明确表头的 Excel 和带明确标签的材料；其他段落经有界 AI 字段提取，逐项核验原文和定位符。仅批复、部分科目信息也保留。
- `BudgetUploadedMaterialAnalyzer` 在 Java 中用 `BigDecimal` 精确核对材料中的申报和批复，不计算正式审批结论。不从数据库主数据补金额。
- 跨文件只在项目与科目身份明确且年度/部门无冲突时关联；没有项目身份时不跨文件猜配。缺失、冲突、单位/精度异常均明确提示，不自动判通过。
- 文档中的独立标题可在明确且不矛盾时作用于本文件；不同源定位符不因模型自行给出的相同行号而拼接。重复相同事实不叠加金额，冲突版本不择一。
- 模型即使引用了真实原文，也可能把项目总预算误标为科目申报金额。只对最短引用全文精确匹配“项目总预算/预算金额/单价/合计”等标签及原始值的事实排除金额角色，原始事实仍保存。含多个金额角色的长段落不会宽松排除；真实申报金额冲突仍返回冲突。八种标签的正向测试及含歧义段落的反向测试覆盖这一边界。
- `BudgetMaterialBatchService`、短事务协调器和 V052 使用现有后台文件任务；上传成员清单、文件提取完成、批次结果持久化均有恢复路径。过期 claim 可重新认领，旧 token 不能提交；不在 GET 或模型 HTTP 调用期间持有业务事务。
- 页面保留现有布局和 Chrome 109 颜色/字体兼容。结果显示“材料批复金额”，使用自然语言说明；历史按文件打开，保留关联来源材料。批次未完成时自动读取结果，不提前展示最终成功。
- 本轮未修改模型网关地址、密钥、模型名称、Nginx、Redis 配置；未推送 Git。

## 本地验证

工作区：当前 worktree；Windows，JDK 17.0.18。运行时 Java 21 没有用于构建。

```powershell
# backend，JAVA_HOME 指向既有 JDK 17
.\mvnw.cmd clean verify
# frontend
npm run build
# worktree root，既有隔离 Python 环境
.runtime/office-python-test/Scripts/python.exe -m pytest services/audit-ai/tests -q
node --experimental-strip-types --test frontend/src/features/budget/*.test.ts frontend/src/features/procurement/materials/*.test.ts
git diff --check
```

观测结果：后端 2258 tests，0 failures，0 errors，2 skipped，BUILD SUCCESS。Python 467 passed；前端相关测试 39 passed；前端构建和 diff 空白检查通过。预算定向测试 42 项（分析器 30、读取器 5、后台接口 7）涵盖同文件双金额、跨文件关联、零金额、万元换算、金额冲突、项目/年度/部门隔离、段落标题、缺失材料、错误引用、金额标签错配、不可变成员和快照、失败隔离、过期 lease fencing。

本轮最终代码构建日志 `.runtime/budget-upload-final3-verify.log`；Python 日志 `.runtime/budget-upload-python-final.log`；前端日志 `.runtime/budget-upload-frontend-final.log`。

浏览器命令：

```text
node tools/build-procurement-browser.mjs frontend/dist .runtime/budget-upload-compatible-r2
node tools/test-budget-material-background.mjs <Chrome109 executable> .runtime/budget-upload-compatible-r2 budget-upload-109
node tools/test-budget-material-background.mjs <Chromium151 executable> .runtime/budget-upload-compatible-r2 budget-upload-151
```

Chrome 109.0.5412.0、Chromium 151.0.7922.34 均通过，无页面脚本错误。覆盖先登记后上传、12 文件/大文件/目录拖入、HTTP 响应丢失保持原身份、离页刷新恢复、完成自动打开、批次等待结果自动更新、历史筛选与返回、按文件导出。保留原 CSS 前缀与 221 项兼容颜色回退；109 上传材料结果截图已目视检查。

首次完整测试发现迁移文件白名单需登记 V052；已补充预期的精确文件名，没有放宽其他文件匹配。浏览器测试发现登记请求被重复 JSON 编码，已修复为 HTTP 客户端接受的对象形式。Python 提示词旧断言已随“允许不完整批复材料”的契约更新，反向依据核验保持生效。

## 代表性 DM8 平台

测试服务器沿用现有 DM8、JDK 17 和 AI/OCR venv。V052 只增加两张工作流/依据表和索引。使用 `BudgetUploadedBatchPlatformCheck check` 确认为 NOT_APPLIED 后受控 apply，再校验实际字段、主键、外键目标、CHECK 和索引顺序。

真实 repository probe 观测：

```text
V052=ALREADY_MATCHING
DM8_REAL_REPOSITORY_CLOB_MEMBERSHIP_PENDING_LEASE_FENCING_ATOMIC_ROLLBACK=PASS; SYNTHETIC_ROWS=ROLLED_BACK
```

该 probe 直接执行真实 CLOB 参数绑定、未上传成员等待、ready 查询、过期 lease 重认领、旧 token 拒绝、savepoint 回滚结果与完成状态，以及整个合成事务回滚；没有用内存仓库结果冒充 DM8 验收。

## 端到端执行状态

首轮真实 17 文件批次全部完成读取，包含 XLSX/XLS、DOCX/DOC、文本 PDF/混合扫描 PDF、PPT/PPTX、TXT/UTF-16/CSV/RTF、PNG/JPG/BMP/TIFF 和大 DOCX。首轮整组金额验收失败，暴露了独立段落关联不全以及模型将 200000 元项目总预算错归为申报金额的问题；没有把 PARSED 等同于结果准确。

修复后，用最终分析器对 DM8 保存的同一批真实提取快照只读复验：15 个文件至少形成一项 31200 元申报与 32760 元批复的正确关联；PNG/BMP 两份因 OCR 项目/科目标识矛盾维持 CONFLICT，金额不强配。混合 PDF/TIFF 也保留各自存在歧义的行，不代表整份材料完全无歧义。全部结果均未把 200000 元项目总预算选作申报金额。此项验证仅证明最终分析器处理已保存事实的结果，不冒充重新上传全 17 文件成功。

最终版本在测试服务器执行：

```bash
sudo bash /home/admin/budget-upload-v15-20261015/run-budget-upload-http26.sh formats-smoke
sudo bash /home/admin/budget-upload-v15-20261015/run-budget-batch-reopen26.sh
bash /home/admin/budget-upload-v15-20261015/verify-budget-final26.sh
```

五批共 13 份仿真文件的真实 HTTP →后台读取/OCR→字段提取→DM8→结果查询验收全部符合预期：

- 四种格式重新上传：XLSX、DOCX、PDF、JPG 全部 PARSED；各文件有正确的 31200/32760 元参考结果。含总预算与申报的样例验证了限定金额角色处理；PDF/JPG 确实经过当前读取/OCR链路。
- 两文件分列申报与批复：结果关联两个真实任务的材料依据。
- 仅批复、仅科目与一个损坏 XLSX：前两份保留信息并显示依据不足；损坏文件 FAILED 不阻断同批结果。
- 不同批复版本：全部显示 CONFLICT，不自动选择 32760 或 40000 元。
- 自然语言仿真材料：经真实模型提取双金额并保存；参考结果没有被标作正式审批通过。

最终观测 `BUDGET_UPLOADED_MATERIAL_REAL_HTTP_AI_DM8=PASS; MODE=formats-smoke`。后端重启后，由另一个临时认证会话重新读取这五批 13 份结果，所有 SHA-256 结果指纹一致：`backendRestartReopen=PASS; resultFingerprints=UNCHANGED`。测试账号执行结束均禁用，仿真任务和业务审计保留；没有改写旧结果快照。

本地运行记录 `.runtime/budget-upload-final-http26.log` 和 `.runtime/budget-upload-final-reopen26.log`。测试服务器当前 release 为 `/opt/audit-agent/releases/budget-upload-v17-20261015.7fC28f`，回退记录 `/opt/audit-agent/update-backups/budget-upload-v17-20261015.paiPyX`。最终 JDK 17 JAR SHA-256：`52f6ed376fd8faf8aa3310f9e637e7254092201e9b2a125a868b7c31539c95cb`。

最终服务 `audit-backend` / `audit-ai` 均 active；前端目录全部 25 个文件的本地与 HTTP SHA-256 一致，配置文件哈希未变化。真实 HTTP 健康响应：

```json
{"code":200,"message":"success","data":{"service":"audit-backend","status":"UP"}}
```

甲方 ARM 麒麟原生环境、本地模型网关及低于 Chrome 109 的浏览器：NOT VERIFIED。测试服务器的通过不代表甲方白名单或模型协议问题已经修复。
