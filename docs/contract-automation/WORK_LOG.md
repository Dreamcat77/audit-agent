# 合同自动化改造持续执行记录

## 当前执行顺序（2026-10-27 用户最新指示）

1. 先完成合同零操作自动化改造 P0–P5，含隔离环境验收及检查点。
2. 再执行《26测试服务器三大业务模块全面测试、问题修复与持续优化》，完成预算、采购、合同及跨模块真实验收，修复后受控部署与复测。
3. 最后执行补充交付任务：整理并推送 GitHub、确认最终 26 版本、制作并校验甲方 ARM64 离线更新包。不在合同阶段提前推送或制包，不部署甲方正式环境。

三份原始任务分别位于用户附件 c00ffe46、7c13162c、8d5acfc3 的 `Pasted text.txt`。当前安全边界继续生效：不破坏原业务数据、不覆盖他人改动、不绕过认证、不在未证明备份安全的原业务数据库实验迁移。

## P0 已执行

- 核实真实 worktree、分支、HEAD 和 remote；HEAD 为 `35f95499c4d5efa475dffc3a3628222750124672`，分支 `fix/budget-procurement-ready-20261019`。
- 备份 143 个 tracked 修改和 4,447 个 untracked 文件；4,590 个副本逐文件 SHA-256 一致。路径和恢复步骤见 BACKUP_REPORT.md。全目录复制失败副本不作恢复依据。
- 读取实际 AGENTS.md、DEPLOYMENT-MEMORY.md、甲方离线手册、前次合同静态调查及采购阶段报告。
- JDK 17.0.18：在 backend 执行 `mvnw.cmd clean verify`，退出 1。441 个测试类，3,208 tests，9 failures，5 errors，24 skipped。失败属于改造前共享工作树基线；不是本轮引入。
- 前端 Node 24.14.0：按项目标准 `node --test` 跑 119 个测试文件，820 通过、1 失败。唯一失败是视觉测试缺少 AUDIT_VISUAL_TEST_BROWSER；设为已安装 Chrome 后该项独立重跑 1/1 通过。
- 前端 `npm run build` 通过，保留既有 chunk-size/dynamic-import 警告。
- Python 3.11.9 已有，但无 pytest；尚未完成 AI 基线。不能拿 Python 3.10 或 Mock 代替正式验证。

## 已知基线问题

合同自动分类测试 1 项 mock 交互断言；采购批次/API/分类/报告测试共 13 项，涉及阶段状态变化、TenderDocument mock 为空、材料分类结果以及报告 scope 查询。需按实际语义逐项核对，不可删除断言或笼统标为无关旧错。

## 当前未执行

数据库执行、真实模型/DM8 验收、Git 提交推送、26 部署以及新包制作均尚未执行。新增合同代码处于切片开发与验证中，不能当作已交付。

## 下一步

## P1 切片检查点

- 独立案件模型、来源引用、内存与 DM8 CAS 仓储、短事务业务审计、入案 API 已写入；不要求正式 CONTRACT 字段。
- 首轮 ContractAnalysisCaseTest 7/7 通过；追加 ContractAnalysisAdmissionTest 后 12/12 通过（JDK17，evidence/p1-admission-test.log）。覆盖并发独占、过期写回、UNKNOWN 不自动重发、owner 隔离、输入变更不污染旧案、解析版本冻结和纯读取历史。
- 第二切片第一次编译发现不存在的 UNAUTHORIZED 枚举，改为既有 UNAUTHENTICATED 后通过。
- Python 短路径基线 2013 passed / 14 skipped / 43 subtests passed；初次缓存10错由 Windows 临时路径过长导致，保持业务缓存不变后复验通过。

## P2–P4 进行中（未验收）

- 新增合同专用 AI 协议及独立模型操作标签，复用原 ModelGateway；原正式合同流程与开关不放宽。
- 新增限定范围的证据摘录定位、空白容差与原文偏移还原；字段需出现在引用中，跨文件冲突需双方证据。
- 新增复用 ProjectFactExtractor 的候选召回和精确 F02 operand kernel；明细合计仅在完整物理表可证明时执行。
- 新增持久 Worker，每个步骤先提交 RUNNING，事务外调用，再按 version/lease 写回；重启遗留 RUNNING 收为 UNKNOWN，不盲发同一调用。
- 当前尚需完成跨文件关联、有限补提取、报告投影、上传持久恢复及前端，然后隔离真实 DM8/模型与浏览器验收。功能开关尚未启用。

## P0–P5 本地阶段复核（2026-10-27）

- 合同自动分析代码、API、Worker、来源证据约束、报告投影和零操作前端入口已存在于当前工作树；合同新增回归覆盖已包含于下列执行结果。
- JDK 17 定向回归：`mvnw.cmd test -Dtest='*Contract*Test,*Project*Test,*Review*Test'`，**1714 tests，0 failures，0 errors，8 skipped，BUILD SUCCESS**。覆盖合同、项目文件处理、Review 和与该过滤模式匹配的采购 API/服务测试。
- 全仓 Java `mvnw.cmd clean verify`：**3271 tests，9 failures，5 errors，24 skipped**。14 项失败集合与改造前记录一致，含合同自动分类旧用例和既有采购测试；没有新增合同分析测试失败。全仓仍未达到 BUILD SUCCESS。
- 前端全量 Node 测试：**828 passed，0 failed**；生产构建通过（既有 chunk/dynamic import 警告）。
- Python AI 全量测试：**2019 passed，14 skipped，43 subtests passed**；合同分析 Prompt/Schema 与超时回归测试包含在内。
- 隔离真实 DM8 `AUTOCASE2` / `CONTRACT_CASE_QA`：基础隔离 schema 已按 424 条登记语句完成；合同分析 schema 3 条 DDL 首次执行、重复检查均通过，`businessDml=false`，原数据库未修改。此证据只证明隔离 schema 行为，不等价于甲方或 26 业务库迁移。
- 合成合同 DOCX 视觉渲染未执行：本机缺少 LibreOffice `soffice.exe`。Java Office 文本读取测试通过，但不能以此宣称已视觉检查生成的 DOCX 样本。
- 真实模型直连验收未通过：已有合成材料的请求得到 HTTP 504（约 60 秒），没有模型结果可评估；这不是成功的 AI 端到端验收。当前不把 `Mock/Fake` 测试描述成真实模型验证。
- 当前阶段结论：合同本地开发/回归证据可交接至第二阶段 26 三模块真实测试；合同真实模型 E2E 仍是未验证，需在用户规定的 26 阶段按真实环境权限继续验证。尚未 Git 提交/推送、部署 26 或制作离线包。

## P5 实际验收纠正与修复（2026-10-27，继续）

此前“P0–P5 本地阶段复核”中的阶段结论已过时：当前仍是合同 P5 隔离验收，未进入 26 三模块阶段。不得将真实模型未完成写成可以跳过的阶段条件。

- 真实模型恢复有效响应，单次合同预算150秒，已观察95–109秒响应；60秒504属于历史记录。
- 正常合同实测暴露将开票期限建议、上传范围外附件核实计为实际风险的问题。已用受限basis和程序侧范围保护区分风险/补充核对，保留原文证据标准，明确付款矛盾仍需报警。
- 浏览器上传“无编号无金额服务合同”实测得到PARTIAL且无条款，确认为模型整包Schema校验将一条非法项扩大为全部结果丢弃；不是通过测试。
- 现改为严格顶层/数组上限，逐项验证独立事实、条款和风险。非法项丢弃并计数，complete=false；全部项非法仍502，未知顶层及超限仍拒绝，Java继续核对每一条来源证据。日志仅记录项类别、序号和枚举错误类型，不输出正文或模型字段值。
- 本轮回归：Python合同12 passed；JDK17 ContractAnalysis*Test 68 tests / 0 failures / 0 errors。真实模型重跑进行中，尚未验收。
- 发现规则召回把封面标题、测试声明与显式合同名称同时展示，增加显式名称优先的受限修复及回归；等待编译验证。
- 隔离协调器session 54163，Vite session 99009；仅使用独立AUTOCASE2/CONTRACT_CASE_QA。26原应用/业务库、GitHub和离线包尚未执行更新。

## P5 续跑核验（2026-10-27 14:10 EDT）

- 实际读取本轮测试产物：前端830/830，Python AI/OCR 2026 passed / 14 skipped / 43 subtests passed；生产构建通过。
- 浏览器历史列表增加独立加载态；`browser-history-fixed-verification.json` 证明历史重开、原文查看和刷新后快照一致，未出现浏览器未捕获异常。
- Golden实测正常采购、服务、租赁、缺字段、付款比例冲突、中标金额及供应商冲突、大小写金额冲突已生成验证记录。通过与否以各verification中的failures数组为准，不能只看HTTP或终态。
- 倒置扫描件未通过：文件任务在OCR阶段连接拒绝，未进入模型，不是已证明的OCR文字识别质量问题。只读检查26发现现有真实AI/OCR监听127.0.0.1:8001；隔离协调器误用了不存在的18080入口。
- 修正隔离测试传输：仅由SSH exec代理请求26已有loopback OCR，保留原服务监听、Nginx和防火墙；配置独立本机19146入口。此处尚待重启隔离协调器后实际复测，不标为修复验证通过。
- 当前全仓Java最近记录为3277 tests / 8 failures / 5 errors / 24 skipped，均为既有采购测试集合；合同自动分类旧失败已在后续执行通过。完整工程尚未BUILD SUCCESS，后续不能遗漏这些失败。
- 保持用户规定阶段顺序。未进入26三模块全面验收，未推Git或制甲方包。

## P5 恢复执行：补充协议关联（2026-10-27 14:36 EDT）

- 恢复时核实工作树/分支/HEAD与共享修改，未 reset、clean 或覆盖既有改动。
- 复现证据：e2e-supplement-conflict-verification.json 原有 EXPECTED_RISK_MISSING_CROSS_FILE_CONFLICT；逐文件事实中“关联合同编号”被规则当成本协议自己的 CONTRACT_NO，缺少 CONTRACT_REFERENCE，跨文件步骤未启动。
- 规则召回现在区分主合同引用与协议自身编号；模型侧同样拒收“关联合同编号”冒充自身编号的事实，保留正确引用和其他合法结果。不通过相同批次/相同文件名猜关联。
- 新增反例覆盖错误合同引用不得按相同项目兜底、多个合同同号不得一对多误关联；模型事实类型误标不得成为自身合同编号。
- JDK17 定向回归：p5-reference-final-regression.log，77 tests / 0 failures / 0 errors，BUILD SUCCESS。
- 原交互终端失联但隔离进程仍运行；仅重启19141/19143的隔离验收进程，新协调器增加仅本机、随机token的测试控制入口以便中断恢复。26原业务服务未改动。token只在ignored私有目录，不进Git或验收日志。
- 真实模型补充协议冲突、有效变更、倒置扫描、损坏附件复测正在运行，尚未把这些场景标为通过。

## P5 续跑：跨文件上下文与真实扫描（2026-10-27）

- 补充协议关联后仍漏报的根因已复现：跨文件输入只收集事实/条款证据，遗漏仅被单文件风险引用的关键句。现在短文件使用有界全文，长文件优先保留已验证风险、条款及事实的原文范围，合并重叠范围，每文件上限5000字符。
- 使用专门跨文件比较Prompt，要求区分明确替换旧约定与互不抵扣的重复付款；不得重复单文件提取或凭不相关原文制造冲突。真实模型复测 supplement-conflict 已识别 CROSS_FILE_CONFLICT，35处报告证据逐一核对通过；没有弱化双方证据要求。
- 倒置扫描服务合同通过26已有真实OCR的受控传输，识别后进入真实模型，得到10个事实、8项条款，0项风险，23处证据一致；独立DM8案件 COMPLETED，verification.failures=[]。不把仅OCR成功当端到端通过。
- 后端定向93测试全部通过；包含新增非法JSON已知失败/传输超时未知/HTTP503保持原语义的适配器回归，以及异步文件任务测试等待同一持久任务完成的竞态修复。
- 正常无冲突跨文件任务不能只返回空findings：正补充有双方原文证据的具体比较条目与反例，不将空模型输出当检查通过。
- 损坏附件原样本是签名非法，正确在上传前拒绝，不是解析隔离测试。保留其原始拒绝样本，新增真实OOXML容器但正文XML损坏的样本，验证已接收损坏附件不影响其他合同。上传安全策略未放宽。
- 仍在第一阶段；下一步完成最新跨文件回归、故障注入、真实浏览器上传/历史及重启后恢复，之后才进入26三模块测试。

## P5 故障与Chrome109最终验收（2026-10-27，继续）

- HTTP503、外部调用未知、收到非法JSON、合法空结果四类故障均通过隔离HTTP/真实DM8验收，分别收为FAILED、UNKNOWN、FAILED、DATA_INSUFFICIENT；案件保留7项有效规则召回事实并PARTIAL收尾，重启后快照不变，没有额外FOCUSED重试。证据 `p5-fault-injection-verification.json`，明确realModel=false、faultInjection=true。
- Chrome109.0.5412.0生产包真实上传无编号无金额合同，生成COMPLETED报告，零业务字段输入，可查看原文、刷新和历史重开，pageErrors=[]。证据 `browser109-final-upload-verification.json`。截图另发现完成后旧接收通知仍称正在分析，已修正通知可见条件并增加状态回归；新版真实浏览器复测正在执行，不能用旧截图替代。
- 最新Python合同14/14通过，通知状态回归3/3通过，生产包重建通过；日志 `p5-latest-python.log`、`p5-terminal-notice-frontend.log`、`p5-terminal-notice-build.log`。
- 真实补充协议责任方复测仍在执行；新增独立进程 `reverify_saved.py` 重新从服务读取冻结原文，避免协调器缓存旧断言造成假通过。源文本仅暂存在ignored私有目录并逐项删除，不输出凭据。
- 阶段顺序保持合同验收→26三模块→补充交付。未推Git、未制包、未更新26原应用。
