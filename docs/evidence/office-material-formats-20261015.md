# 预算、采购常见办公格式与读取完整性（2026-10-15）

## 范围

用户要求两个材料入口支持常见办公格式，并用不同格式、大小的材料检查上传到分析全链路。压缩包不在此次支持范围。保留页面布局、历史和后台任务语义，不变更 AI 地址/密钥/模型名称、业务规则或数据库 schema。

## 已定位问题与修改

- 预算 Excel 原来只读第一个工作表。现遍历所有工作表，允许封面，读取更靠后的表头、公式及金额单位，防止参考金额覆盖申报金额；无法读取的预算表不静默跳过。图片工作簿转完整 OCR/字段提取路径。
- 预算 PDF 原来只以末尾金额正则识别明细，扫描件不支持。正式入口现在使用完整文档读取加原文依据约束的 AI 字段提取；Excel 的明确表格仍使用确定性解析。AI 不作金额决策，BigDecimal 转换和规则保留。
- 采购 PDF 原来由全文是否可用决定是否 OCR，容易遗漏单独扫描页或图文混排页。现按页检查，保留文字层并对缺字/含图页 OCR，包含 inline image；校验每次单页 OCR 的分页覆盖。
- Word 提取包括正文顺序、嵌套表格、文本框、页眉页脚、脚注尾注及图片 OCR；支持旧版 DOC。PPT/PPTX 遍历幻灯片、组合形状、表格、备注和图片；Excel 通用文本读取遍历每张表、每列、公式和图片；多帧 TIFF 按帧读取。
- 两个入口格式统一为 DOC/DOCX、XLS/XLSX、PDF、PPT/PPTX、TXT/CSV/RTF、PNG/JPG/JPEG/BMP/TIF/TIFF。文本支持 UTF-8、带 BOM UTF-16、严格 GB18030 回退。扩展名不代替内容签名验证。
- 统一 DocumentTextPort，复用既有文件存储、OCR/AI Port、DOCX 容器安全校验；未新建存储/ZIP 子系统。单文件仍有 500 MB 上传上限、192000 字符提取预算、100 页/工作表/图像单元上限及像素保护；超限明确失败，不截断后成功。RTF 的嵌入图片/对象及无法支持的 Office 对象拒绝处理，不伪称完整。
- 每段受控切分保留全部字符，避免拆坏 UTF-16 代理对。预算本次处理复用首次字段提取，避免重复调用模型；进程恢复仍从持久化原文件重开，数量不一致时停止。
- Python 材料字段服务新增 BUDGET 字段契约，逐块调用，截断时只拆分失败块；预算不同块的模型本地 rowKey 不合并；返回的每个值及摘录必须属于对应原文定位符，缺科目/申报金额、冲突或无依据时拒绝。
- 诊断仅保留阶段、计数和固定原因代码，不记录原文、模型正文或密钥。

## 验证命令与证据

工作目录为 `.runtime/budget-home-e17-20261014` 独立 worktree，保留先前预算修改，无 Git 发布动作。

```powershell
# backend，已有 JDK 17
.\mvnw.cmd clean verify
# frontend
npm run build
# 根目录
node --test frontend/src/features/budget/budgetMaterialBatch.test.ts frontend/src/features/procurement/materials/officeMaterialTypes.test.ts frontend/src/features/procurement/materials/procurementUploadQueue.test.ts frontend/src/features/budget/budgetResultState.test.ts frontend/src/features/budget/budgetProjectResults.test.ts
node tools/build-procurement-browser.mjs frontend/dist .runtime/office-formats-compatible
node tools/test-budget-material-background.mjs E:/audit-agent-new/.runtime/browser-compat-test/chrome-win/chrome.exe .runtime/office-formats-compatible office-formats-109
node tools/test-budget-material-background.mjs C:/Users/29339/AppData/Local/ms-playwright/chromium-1234/chrome-win64/chrome.exe .runtime/office-formats-compatible office-formats-151
# services/audit-ai，隔离测试 venv，不改服务器 OCR venv
..\..\.runtime\office-python-test\Scripts\python.exe -m pytest -q
```

前端构建、16 项相关单测、Chrome 109.0.5412.0 和 Chromium 151.0.7922.34 回归通过；保留 221 项颜色回退，无页面脚本错误。Python 460 项通过；本机隔离 venv 使用 bundled Python 3.12，仅验证纯应用测试；生产 Python 3.11 必须另做真实验证。

初版后端 `clean verify`：2218 项、0 failures、0 errors、2 skipped，BUILD SUCCESS。补充 Word 预算表格回归后 2219 项、0 failures、0 errors、2 skipped，日志为 worktree `.runtime/office-table-release-r2-verify.log`。段落边界补充后的最终验证另行记录。`git diff --check` 通过。

`OfficeDocumentTextReaderTest` 对两种 Excel、两种 PPT、图文 Word、混合 PDF、中文编码、长文本末尾、UTF-16 代理对、多帧 TIFF、OCR 漏页及各类拒绝情况做断言。新增 BudgetOfficeMaterialParserTest 验证字段依据、缺行信息不成为部分成功；PreparedMaterial 回归验证身份和数量不一致必须失败。

`tools/OfficeFormatFixtures.java` 生成 18 份正常材料、损坏和超限材料，另通过已有本机 Word/WPS 保存真正二进制 DOC；仅生成仿真内容。大 DOCX 约 25 MB，采用非压缩填充区单独检验上传体积，不冒充 25 MB 语义正文。另含多工作表 Excel、3 页混合 PDF、2 帧 TIFF、不同中文编码、长文本及末尾标记。

本机 `OfficeCoverageProbe` 对 18 份格式读取通过，所有文字格式保留预设末尾/金额标记；OCR 本机使用测试替身，不能据此宣称 OCR 质量。

26 独立真实 OCR 先验：通过现有 Python 3.11 服务上传合成 PNG，HTTP 200、1 页、222 字符，末尾标记存在；首次加载耗时约 79 秒。没有调整 OCR 核心依赖或模型配置。

26 新 reader 隔离执行另确认：大 DOCX、长文本、UTF-16 文本和三页混合 PDF 均保留尾标记。混合 PDF 读取 3 页、2 次 OCR、4 段、967 字符，耗时约 214 秒（单次 OCR 约 102 / 111 秒）；本轮独立 probe 在这四项完成后停止，其他格式由真实上传验收覆盖，不能称该 probe 全部 18 项通过。

26 测试更新已执行，外层 SHA-256 为 `4e57fc1af5dbd67f9ffb5b45aed5253bba1f6a9af1762166d7ce96df27b59a2e`。当前 release：`/opt/audit-agent/releases/office-formats-v9-20261015.hndqoW`；恢复记录：`/opt/audit-agent/update-backups/office-formats-v9-20261015.3wnF0p`。更新脚本验证 6 项前端 HTTP 哈希、服务 active、健康接口及未登录预算/采购 401；既有配置及字体哈希保持不变，无 schema migration。

真实入口验收由 `tools/test-office-formats26.py` 通过 Nginx、登录/CSRF、后台任务、真实 OCR/模型及 DM8 重开路径执行。使用临时随机仿真账号，finally 禁用；只上传合成文件。金额和预期明细数按样本断言，异常样本必须终止为失败或拒绝。完整结果待本轮运行结束记录。

## 真实验证追加定位

- 26 的预算字段提取可用，但后续语义分析默认思考模式连续收到上游 HTTP 500。相同仿真语义请求作受控对照：原参数 HTTP 500 / 115 字节；仅关闭思考后 HTTP 200 / 617 字节。未输出上游原文或任何配置值。预算结构化分析现与材料提取一样使用 `reasoning_effort=none`，仍保留 JSON、2048 输出和已有失败处理；没有改模型地址、凭据或名称。甲方参数兼容性仍 NOT VERIFIED。
- BMP / PNG 样本曾被金额标签核对拒绝，未保存错误金额；另一次等价 OCR 文本提取生成了一条无申报金额的元数据行。将预算提示与采购字段提示彻底分离，要求每条预算行同时具有科目和申报金额，排除元数据行、项目总预算和参考批复值。保留 Java 原文核验及不完整行 fail-closed。
- 使用生产 Python 3.11、隔离的新源码和同一仿真 OCR 结果，两种定位符均返回一条完整明细、申报金额正确；新语义调用亦成功。`SYNTHETIC_V11_PYTHON311_MODEL=PASS`。这是代码/模型隔离验证，还需新版实际上传复测。
- Word 预算表格为每行保留来自该表的列名，避免后续模型块丢失表头；仅对列数一致、首行含明确科目及申报/申请金额的非嵌套表应用，其他内容保留原读取路径，不猜字段。两张列顺序相反的 20 行表回归断言 40 行金额标签和末行均存在。新增 8 行 Word 表用于真实入口复测。
- Java / Python 均优先在换行处划分受限文本块，保留每个字符和尾部，不把普通明细行从中间拆开；超长无换行内容仍按预算受控切分。长单行、长多行及末尾回归覆盖该行为。
- Python 在预算专用提示及关闭思考的阶段全套测试 463 项通过；追加换行边界回归后的最终数量另记。

## 最终补丁与复测

包含段落边界修复的最终 Java `clean verify`（JDK 17.0.18）2219 项、0 failures、0 errors、2 skipped，BUILD SUCCESS；日志 `.runtime/office-final-release-verify.log`。Python 全量 464 项通过，日志 `.runtime/office-ai-final2-pytest.log`（本机隔离 Python 3.12）；真实 Python 3.11 模型调用证据见上。

初轮预算 20 次上传结束：14 份正常材料成功；BMP、PNG、TIFF、TXT 四份在提取结果校验失败；损坏与超限两份按预期失败。原测试账号已确认禁用，原报告 `results-all.json` 保留。没有将这轮记为通过，也未重写原失败记录。

补丁 v12 的 SHA-256 为 `d4a309e8d8a5bb45e9f90cfff38d289c717e57412cfab7822d055d168493ffd3`，已在队列结束后更新 26。release：`/opt/audit-agent/releases/office-formats-v12-20261015.axV0t3`；恢复记录：`/opt/audit-agent/update-backups/office-formats-v12-20261015.SyOW3f`。同样通过 6 项前端 HTTP 哈希、配置/字体保持、服务、健康及鉴权入口检查。

新增 Word 长表后最终样本为 19 份正常文件、2 份异常文件。复测报告使用独立 `results-final-v12.json`，预算额外检查实际语义结果可用；采购检查 10 类明确字段均保有证据，长文本至少 120 条技术要求证据，避免仅凭 HTTP 200 或项目名存在判定完整。

## 最终实测受外部网络阻塞，未完成

2026-10-15，v12 最终整组上传期间，原模型端点连续出现 `CONNECT_TIMEOUT`，独立 TCP 探测亦为 `TimeoutError`。端点指纹保持一致，未改变模型地址、Key 或名称；这与先前上游返回 HTTP 500 属于不同阶段的故障，不得将本轮正常文件的失败判为读取代码通过或失败的最终证据。

只读诊断在 26 和本机分别执行，实际结果：

- 26 的 AI、后端服务均为 active，AI 本地端口可建立 TCP 连接。
- 26 到自身默认网关两次 ICMP 均收到回应，网卡收发错误和丢弃计数为零；目标经默认网关发送，无额外策略路由。
- 26 到原模型端口多次连接超时；目标 SSH 端口和 ICMP 同样无回应。本机到模型端口亦超时。这些现象不单独证明目标关机。
- 26 网卡上仅采集目标模型端口的 TCP 报头，观察到出站 SYN，没有观察到返回报文；未采集请求正文、认证或材料内容。
- 26 未发现可用的 iptables/nft 命令，不能据此推断宿主机、上游网络或目标防火墙规则。
- 原因目前限定为模型端点的网络可达性问题；目标离线、网络路径丢包、访问策略或目标防火墙仍待模型机器/网络侧验证。尚未进入 HTTP、凭据校验或模型输出解析阶段，不能归因为 Token 耗尽或字段过长。

确认进程身份后，仅向 Python 测试子进程发送 SIGINT，使父 Java runner 执行 finally；终端实际输出 `SYNTHETIC_TEST_IDENTITY_DISABLED`，随后父子进程均已退出，服务仍 active。未删除测试记录、文件或业务数据。`results-final-v12.json` 为中断快照，不是最终通过报告。采购本轮尚未执行，预算/采购完整新版端到端验收保持 `REMOTE_E2E_PENDING_EXECUTION`，待原端点恢复后重跑。

## 端点恢复后的实测与共享容量问题

用户通知模型恢复后，独立探测确认 TCP 可连，模型列表 HTTP 200 且包含配置模型；最小生成 HTTP 200、约 1.06 秒、有 message.content、finish_reason=stop。模型设置未变。恢复早期实际材料请求仍有 `READ_TIMEOUT`，与之前 `CONNECT_TIMEOUT` 区分记录。

`results-recovery-v12-budget-docx.json`：普通 Word 预算全链路 PASS，1 行、申报金额正确、1 行语义 available，DM8 重读一致。`results-recovery-v12-procurement-docx.json`：普通 Word 采购全链路 PASS，10 类字段有依据。两份报告由真实登录/上传/后台处理/查询产生，runner 均输出临时账号禁用确认。

v12 剩余预算样本中已实际验证 25 MB 上传体积 Word、三页混合 PDF、8 行 Word 表和 UTF-16 文本成功，8 行表金额及 8 次语义结果全部正确。长文本首次实际请求在 60 秒等待上游后超时；同一仿真原文第一段的 900/1800 字受控调用随后约 11.25/7.71 秒成功。不能把输入长度当成已证实的唯一根因。

同时运行预算与采购实测后，发现独立的真实代码缺陷：Java 预算 worker 1 个、采购 worker 2 个，但共用 Python 材料 endpoint 只有 2 个名额。第三个请求收到 `MATERIAL_AI_BUSY` / 503 后被保存为终止失败，短小 CSV/DOC 亦受影响；这是容量争用，不是文档内容缺失。保留 v12 原报告，不覆盖历史失败记录。

FastApiMaterialFieldAdapter 作为共享 singleton 添加公平的 2 槽 Semaphore，在打开 HTTP 前有限等待，持久任务及 lease heartbeat 继续工作；中断恢复中断标记且不发送 HTTP，所有取得的名额在 finally 释放。未放宽 Python fail-fast、模型 JSON 或原文依据校验，未变更数据库事实源。单进程协调用于当前模块化单体部署，不代表多实例集群全局容量控制。

新增真实本机 HTTP 并发测试验证跨模块第三个调用不发送第三个 HTTP、前面 502 后名额释放、等待中断不泄漏名额且保留中断。2 项通过；后端全量验证及修复后服务器复测另记。

共享容量修复后的 JDK 17 全量 `clean verify`：2221 项、0 failures、0 errors、2 skipped，BUILD SUCCESS；`.runtime/office-capacity-final-verify.log`。v13 已在队列结束后部署至 26，前端 6 项 HTTP 哈希、健康、鉴权、配置保留检查通过。`results-capacity-v13-budget.json` 中长文本、BMP、CSV、DOC 四份全部通过，金额及语义 available 检查通过；临时账号禁用。

`results-capacity-v13-procurement-text.json`：13 份正常文字格式中 11 份通过，损坏和超限材料如预期拒绝/失败。长文本虽然 PARSED，但明确编号技术要求 120 条只留下 5 条，完整性断言拒绝本次验收；DOC 另有一次上游等待超时。没有使用 HTTP 200 或 PARSED 掩盖完整性失败。

定位长文本缺失：编号要求的既有确定性保留逻辑只匹配整段，TXT 中多条要求在同一源段时仍交给模型压缩提取。修复为逐行识别明确编号要求；身份字段所在行仍交给模型，不猜项目；保留逐字原文及原 locator。其余内容只按原文中连续片段发送，避免删除要求行后人为拼接跨行摘录。新增单段 120 行、CRLF、尾部、含身份行反向测试；Python 全量 466 项通过，1 项既有 Starlette warning，日志 `.runtime/office-ai-requirements-final-pytest.log`。

v14（后端与 v13 相同，仅追加上述 Python 修补）SHA-256：`a2ab39ec75641a43dfe74f82b412e5fce503946d5063a490af5f68f74c9791f8`。26 部署 release：`/opt/audit-agent/releases/office-formats-v14-20261015.NUv1yU`，恢复记录：`/opt/audit-agent/update-backups/office-formats-v14-20261015.1J4lMc`。外层校验、12 项 manifest、Shell 语法、6 项前端 HTTP 哈希、双服务健康/active、未登录 401、配置/字体保持检查通过；未迁移 schema。真实后端健康 HTTP 200：`{"code":200,"message":"success","data":{"service":"audit-backend","status":"UP"}}`，AI：`{"status":"UP","service":"audit-ai"}`。

采购剩余七份（长 TXT、DOC、BMP、JPG、PNG、TIFF、混合 PDF）采用新报告 `results-requirements-v14-procurement.json` 复测：7 份全部通过字段依据、10 类字段覆盖、长文本要求覆盖和 DM8 重读断言，0 失败。runner 输出 `REAL_OFFICE_UPLOAD_MODEL_DM8=PASS` 和 `SYNTHETIC_TEST_IDENTITY_DISABLED`。

v14 长 TXT 已通过 DM8 重读断言，10 类采购字段均有依据、requirementDescription 证据 122 条（含全部 120 条明确编号要求）。原始文本仍完整留在既有证据路径；这不代表任意无结构要求都能由模型零遗漏识别。新增逐行保留只处理明确标签，不猜测含身份的行。

## 服务器验收汇总

服务器只读聚合命令：`sudo /opt/python/venvs/audit-ai/bin/python /home/admin/office-formats-v9-20261015/aggregate-office-recovery26.py`。实际输出 `AGGREGATE_REAL_OFFICE_UPLOAD_MODEL_DM8=PASS`，预算 19/19、采购 19/19 正常样本各有通过全部断言的真实记录，无遗漏样本；两类异常材料也分别如预期拒绝/失败。验收依据为修复前后不同轮次报告的逐样本集合，不是声称同一版本单轮 42 次全部通过。原始失败记录均保留；v13/v14 分别对容量争用及要求漏检做针对复测。

样本含 16 个扩展名中的 14 类独立格式（JPG/JPEG、TIF/TIFF 为同格式别名），覆盖 DOC、DOCX、XLS、XLSX、PDF、PPT、PPTX、TXT、CSV、RTF、PNG、JPG、BMP、TIFF；另有多表、混合扫描、多帧、中文编码、长表、长文本及 25 MB 上传体积样本。不能以 25 MB 文件或 4637 字文本冒充 500 MB / 192000 字上限规模已实测。

所有本轮 runner 已退出并输出仿真账号禁用确认。最终 26 当前 release 为 v14；配置哈希重新核对一致、AI/后端均 active，两个健康 HTTP 响应均 UP。代码未提交或推送 Git，没有发布甲方升级包；甲方环境验收仍 NOT VERIFIED。

## 当前边界

任意材料的字符识别和模型字段提取不可能由有限样例证明零遗漏。上述机制针对页/表/字符被技术链路跳过提供保护；模糊扫描、加密、损坏、嵌入对象和超过资源预算均需如实提示。真实网关白名单和甲方模型能力不由此变更替代。甲方 ARM 麒麟及更低浏览器仍须现场验收。
