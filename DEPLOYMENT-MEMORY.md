# 当前部署记忆入口（2026-10-22）

## 2026-11-09：现场合同问题优化（26 contract-customer-r4）

- 当前26为 `/opt/audit-agent/releases/contract-customer-20261109-r4`，取代budget-batch-20261106-r7及本轮临时r2/r3。JAR SHA256 `dd885e7a61ee1cc9315f84a4dda6edbdfc71768bae538aacaffd2e309e0ff1ee`；前端入口SHA256 `6e8471bb5b1b64ba6fbdfc23674edff23b304ff109444ae999ad3a94eb1c15c0`。不回切旧客户包源码；本轮无DDL、清库或新增部署前置条件，配置与四路OCR/每进程1线程/MKLDNN关闭保留。
- 修复项目名称跨行、主体占位/明确简称、主合同与配套协议/历史业绩范围、金额局部配对、空日期原文、重复事实及OCR疑点误报警。中标供应商必须保留“中标/成交”完整上下文，不能先截成“供应商”。实际配套协议主体名称不同不等于主体资格缺陷，仍保留名称及来源。Word按真实容器读取，OA未接入、不作前置条件。
- Windows Java17 clean verify3617项0失败0错误24跳过；26 Python300通过12跳过。15批最终只读重开与业务断言通过，历史case未变，均保留8槽位；其中5批PARTIAL，不能宣称全文完成。最终r4新增合同+中标通知书专项8项有内容、13事实11条款、中标供应商和金额核对PASS。Chrome109折叠/复制原生命令/精确高亮/三种视口/历史重开通过，headless未验证桌面OS剪贴板。
- 真实DM8活动任务/终态UNKNOWN对抗SQL、运行目录和JAR哈希、HTTP健康及全部静态资源通过。26维护脚本用 `/opt/python/venvs/audit-ai/bin/python`，系统旧Python不具备相同API；一次错误候选已自动回滚，未改系统Python。后续不要用系统python3运行本轮维护脚本。
- OCR实际峰值4路；4页/12页旋转扫描读取27.828/72.360秒，均继续进入模型。多批模型仍单worker排队，本轮未消除总排队问题；通用乱码区域复读未新增。甲方ARM64及现场文件NOT VERIFIED。不提交Git、不制甲方包、不关机。详见 `docs/qa-26/customer-contract-optimization-20261109.md` 及其脱敏证据，纯合成批次在 `test-materials/contract-customer-20261109/`。

## 2026-11-05：合同日期分段误报与引用结论校核（26 evidence-r1）

- 当前26入口 `contract-evidence-20261105-r1`，JAR SHA256 `6df90486fea3c08e8e07646bd597c53dc2db4d63ef0ed7edd999f574dbea57ad`；从 procurement-useful-r4 增量更新，前端入口SHA保持 `7949441e46f67cca6e0e7da0103d66b35d5e76ef37160c47af1c0bdaf95fbb130`。无DDL，原配置、主体范围、账号归属及4路OCR/单进程1线程/MKLDNN关闭保留。
- 新分段在既有160字符重叠区优先句子/行边界；已冻结范围/ID/摘要不改。日期不完整结论对照完整冻结原文和引用所在位置；完整年月日数值不因丢单位字误报。空白、2月30日及另一签署位置留空仍保留。事件型有效期至执行/履行完毕是结束节点；合同编号不证明缺少日期或终止条件。真实条件含糊可继续指出，不能只删一类标题。
- 旧报告及列表日期/终止风险数量只读校核；不改历史步骤、模型响应、持久化UNKNOWN，不在GET调用模型。八大项保留。Java17最终clean verify3572项0失败0错误24跳过；26共享Python372通过。Chrome109重开用户旧记录3条意见变1条，两条误报消除；原案件JSON前后不变、引用逐字核对通过、浏览器无异常。新批次真实模型两段有效，11条条款、8分类、1条有依据的金额差异，两条误报未复现；第一段3条候选未过引用核对，最终PARTIAL，不能报全文审查完成。新首段2385字，旧任务2400字范围不变。
- 详见 `docs/qa-26/contract-finding-grounding-20261105.md`。不提交Git、不制甲方包；现场未验证。

## 2026-11-05：采购按现有材料出结果（26 procurement-useful-r4）

- 当前26默认入口为 `procurement-useful-20261105-r4`，JAR SHA256 `f45ec2cfd385c9c8d3201d5a032a0ec07660bef1b3244708fd6289131021e4e6`，入口SHA256 `7949441e46f67cca6e0e7da0103d66b35d5e76ef37160c47af1c0bdaf95fbb130`。保留主体配置、三角色上传隔离、Chrome109字体/颜色及OCR4并发/单进程1线程/MKLDNN关闭。无DDL、清库或新增部署前置条件。
- 用户目标是现有材料有什么给什么。有效事实独立展示，缺少其他阶段材料本身不作风险；原文证实的评分/限制/范围问题仍保留。报价、预算、成交及年度/分包/单价分清。相同冻结采购操作中已提交ACK可只读核验后展示，UNKNOWN调用不重发、不宣称全文完成；预算/合同原有检查点语义不改。超时清理内容不恢复。
- 公共金额支持带明确货币单位的阿拉伯金额“元整”；采购表格金额按同页明确单位核对，拒绝单位冲突和报价冒充预算。长文按具体工期/付款等主题选取，防止目录占用分析空间。材料要点、复制和HTML一致，内部“系统checks / Java checks”只读转换为“系统核算结果”，原文引用不改。
- Java17 clean verify3562项0失败/0错误24跳过；前端859通过和customer构建成功；26共享Python372通过。Chrome109五组真实模型/生产审查及最终只读重开、复制、HTML、无页面异常均通过。10份旧历史快照与原配置未变，临时QA账号停用。用户长PDF复用361段已冻结正文及14/33已确认提取单元，独立QA副本走生产语义审查，未重新全量OCR/提取，原案件与UNKNOWN台账不改。
- 详细证据见 `docs/qa-26/procurement-available-results-20261105.md`。甲方现场本轮NOT VERIFIED；未推送Git，未制甲方包。不能以本轮部分结果验收宣称所有长文件全文审查完成。

## 2026-11-05：主体常见写法扩展（26 identity-variants-r1）

- 当前26运行`/opt/audit-agent/releases/identity-variants-20261105-r1`，取代本日`git-identity-20261105-r3`；工作树和分支不变。JAR SHA256 `a3a1d6b756329b0bdbac989535643971558a16a420845792982b0331f3c29d0a`。前端入口哈希保持`8d3e25d85c4170898188c70c9382d8e69316588f7a0dfb22f2e6a570acae7bd9`。
- 主体识别支持已配置简称展开、机关/处室组合、括号注释、窄OCR字形及换行、本文件有明确定义的简称。Java/Python共用79例，不用任意相似度或跨文件自动学别名。功能角色不强认甲方；历史合同、其他机关/公司、冲突主体不得误认。
- 模型最终出站边界同步遮蔽扩展名称和当前请求内有明确来源的简称，等长可见代号及证据还原继续保留。身份策略升为`contextual-client-identity-v3`，不混用旧材料检查点；旧报告不重算。无数据库结构变化，无新人工步骤，保留所有原环境配置及OCR4并发/1线程/MKLDNN关闭。
- Java17 clean verify3539项0失败/错误24跳过；26待发布目录共享Python回归370 passed。真实DM8静默检查、10份历史快照、实际运行哈希/目录、三个health/index HTTP200通过。
- 生产JAR+真实DM8配置17组判定通过；3次真实模型调用出站无受保护名称，精确引用恢复通过。乙方处室全称取得6事实5条款，功能采购人取得6事实4条款，分别11/10处精确引用。此轮为AI路由真实模型/内部服务HTTP及Java配置探针，无浏览器上传和持久八项新验收；甲方现场NOT VERIFIED。保留此前预算叙述分组未验收的限制，详见`docs/qa-26/client-identity-variants-20261105.md`。未提交Git，未制客户包。

## 2026-11-05：融合Git新历史、服务对象身份及审查立场（26 r3）

- 远端main重建历史，最新功能分支`feat/client-review-perspective`提交41c60c5e。当前维护分支`integrate/github-latest-20261105`，工作目录保持`E:/audit-agent-new/.runtime/budget-home-e17-20261014`；累计本地修复已三方融合，仍未提交/推送。不要切回旧65464825覆盖当前工作区；原始未提交改动备份在Git忽略的`.runtime/git-integration-20261105/`。
- 上传角色保留已部署V058，服务对象身份表使用V059。真实DM8已建表，重复执行和名称唯一、种类、启用值约束行为测试通过，探针事务回滚；按用户确认配置一个正式名及三个简称，审计只记录数量。旧分支若以V058创建同表，不因改名重放CREATE或清空配置。实际名称不得写入Git/诊断导出。配置变更后重启Java/Python，不混用旧进程快照。
- 默认入口现为`/opt/audit-agent/releases/git-identity-20261105-r3`，JAR SHA256 `b19bf219d3f962f60bf9eb643455acaf154cf4727132304c97d6fcb7eb72d84a`，前端入口SHA256 `8d3e25d85c4170898188c70c9382d8e69316588f7a0dfb22f2e6a570acae7bd9`。新版本实际AI env启用`AUDIT_CLIENT_IDENTITY_REQUIRED=true`；现场网关/Token/模型名、DB/Redis/Nginx及四路OCR每进程1线程/MKLDNN关闭均保留。DM8十份历史快照哈希不变，三个HTTP入口200。
- 真实模型发现：Unicode私有区脱敏代号被当前模型返回为空格，会把单位误当空白并损失引用。已改为可见等长代号，保留码点和UTF-16长度、原文空白及嵌套JSON。排除与原材料碰撞；最多128次构造，无安全代号则发送前停止，不放行原名；不吞掉嵌套脱敏失败。材料临时缓存指纹包括身份配置和策略版本，避免复用旧策略结果。
- Java17 clean verify3538项0失败/错误24跳过；前端858项、Chrome109证据浏览器5项及customer构建通过；最终26 Python相关224项通过。候选真实模型四次出站无受保护名称泄漏、逐字引用还原通过，运行JAR+真实DM8的正式名及简称12项角色判断通过。完整页面甲方/乙方场景均生成8分类、6事实、7条款；乙方为委托付款人时正确分析付款及服务方义务。其余三模块页面验收最终结果记于下述QA文档。
- r3还修复身份中间件对材料策略GET强行解析JSON的融合错误；预算/采购会先访问该接口，带身份头的无正文GET必须成功，无头/坏头仍拒绝。请求内加入检查点身份范围，不能在路由初始化时固定为空。
- 独立模型探针与正式任务重叠期间出现150秒READ_TIMEOUT；原步骤保留UNKNOWN并生成部分结果，不盲目重发原调用、不改旧结果。另建独立测试批次串行核查，乙方场景145.7秒成功。不要据此宣称模型延迟问题已彻底消失。
- 最终Chrome109实际上传验证：合同甲/乙方均8分类、6事实、7条款；仅正文提及保持中性，PARTIAL中14条发布引用逐字核对通过。采购预算220万/限价200万/报价230万，差额10万/30万正确，HTML200；单报价不作围串标结论。预算读取5行且持久结果完成，但非表格多科目归并仍不准确，未正确输出样例10万超支，预算语义验收未通过，不能隐去这一限制或宣称三模块全部业务通过。
- 身份配置、部署及真实验收见`docs/qa-26/git-integration-20261105.md`和`docs/runbook/client-review-identity.md`。本轮不制作甲方包、不提交Git；甲方实际环境仍NOT VERIFIED。


## 2026-11-05：采购按政府采购业务链条理解（26 r6）

- 本次历史发布入口为 `/opt/audit-agent/releases/procurement-semantics-20261105-r6`，现行入口以上方最新记录为准；JAR SHA256 `45c5676701031c619ca293bf27939e6d8b3d3a4397132e71406a2ed1c7061607`，前端入口 SHA256 `8353b86a6d21af40d4c062b2e638f9146ad5f6ec9dba5742a58fc6997aca7e9e`。基于当前共享代码增量发布，不按旧甲方包恢复；无DDL、业务删除或已有配置改写，四路OCR/每进程1线程/MKLDNN关闭保持。
- 一批材料仍是一项目；模型分清需求/资金、采购文件及澄清、供应商响应与报价、评审成交。当前项目与历史业绩、采购人/代理/供应商、总价/年度/分包/单价分别处理。金额始终BigDecimal；模型不计算金额，也不能把预算超额重复解释成成交金额差异。
- 旧F10关键词只召回线索，不直接发布严重风险。允许等效品牌、政策资格和供应商自主品牌不能机械报排他；实际限制、主观评分、报价漏含配件耗材应指明条款、文件、影响与可执行整改。品牌限制不能改成另一份品牌名单；地域评分不能只建议补证明。
- 有界正文选择保留原文引用与覆盖范围，不把本模块没看到的材料当整批未上传。不要删除“覆盖不足”事实或把PARTIAL/UNKNOWN冒称全文审核完成；能显示已核实的内容。旧快照新可选元信息省略null序列化，真实DM8十条历史快照切换后SHA一致。
- Java17 clean verify3526项，0失败/错误、24跳过；前端849项通过、customer构建及Chrome109处理通过；26候选Python/共享OCR/预算/合同相关回归203通过。十组合成采购材料22份的模型报告按业务语义和原文引用验收，r3/r5/r6执行范围分别记于 `docs/qa-26/procurement-business-semantics-20261105.md`。甲方ARM64及现场模型未在本轮验证；不生成甲方包、不提交Git。


## 2026-11-04：OCR 资源并行与真实长 PDF 验收

- 26 最新默认为 `ocr-pdf-20261104-r1`，JAR `0083e4041d212646906ceeb1af31a56f1987402efcce4d3ba3577936550ca4ce`。保留 personal-roles-r2 的前端、账号归属限制及既有合同修复；未改前端、未做 DDL、未提交 Git、未制作甲方包。
- 三模块共用 PDF 页面并发和独立 OCR 进程池。CPU threads 仍为 1、MKLDNN 关闭；26 配置 `AUDIT_OCR_PAGE_CONCURRENCY=4`、`AUDIT_OCR_WORKER_PROCESSES=4`，实际池为 4。按 affinity/cgroup/可用内存和规划预算在启动时选择实际槽位；预算不是 OS 内存硬限制。其他环境默认 1，甲方 ARM64 尚未性能验证，不直接照搬 26 四路。
- 用户提供的 274 页真实混排 PDF 完整读取 956.329 秒，87 页 OCR、187695 字符、覆盖缺口 0、顺序正确。六扫描页单/双/四路为430.334/289.440/215.795秒，文字哈希相同；不能把样本49.9%提速比例当成整份基线。整份 OCR 进程峰值约9.1GiB，456次忙时查询正常。最终版本检查点恢复0.825秒，结果完全一致；预算/合同未因此新增逐页DM8恢复。
- 真实模型代表性正文抽测6段4991字得到8条有原文依据的信息，不等于整份采购报告验收。预算/采购/合同业务读取适配器在候选及默认入口均经真实OCR通过。最终Java clean verify3489项0失败0错误24跳过；Linux相关Python112通过12跳过。Windows旧POSIX假设5失败已在Linux对应测试通过，不能报Windows全绿。
- OCR槽位排队由120秒改为1900秒，防止长页使其他文件提前排队失败；模型时限独立不变。按完成顺序提交检查点、按页码输出，临时输入必须等待实际执行结束后删除，未知结果不自动重发。
- 只在新release环境文件追加OCR资源配置；既有模型/DB/Redis/Nginx配置及旧release保持。无新增数据库部署门槛。维护shell的Java默认编码可能为ANSI_X3.4-1968，独立UTF-8 Java中文探针必须显式 `-Dfile.encoding=UTF-8`，防止把探针字面量乱码误判为OCR漏字。
- 配置说明 `docs/ocr-resource-configuration.md`，真实命令/边界/脱敏证据 `docs/qa-26/ocr-resource-20261104.md`。原文、真实模型结果仅留26私有文件，不进入导出诊断与Git。

## 2026-11-04：合同模板空白内容不能冒充事实

- 用户明确要求：只有“乙方（承揽方）：”等称谓且名称留空时，显示“乙方：无”，并列出“乙方名称未填写”；不能把称谓当主体，不能因模板不完整停止其他条款分析。
- `ContractPartyName` 共用名称判定接入自动分析证据校验、规则组织提取、模型候选校验、事实发布及自动建档；称谓、签章/联系栏标题和待填占位符不保存成主体。单位骨架不作为金额大写；零元及“拾万元整”等有效金额保留。
- 新的原文留空检查提供主体名称、合同总价、付款比例、违约比例、保用期限的具体问题和精确证据。只对实际留空位置作判断，不把“模型未提取”当成“没有”；首页空白而签章页已填写的名称保留，同批其他文件的名称不能补充成本文件的事实。主体问题归入八大项的主体资格项，不凭空判定合同无效。
- 本地Java17 `backend/mvnw.cmd clean verify` 3461项、0失败/错误、24跳过；随后对空格/联系栏及有效金额边界作最终增量回归 `-Dtest=ContractPartyNameTest,ContractAnalysis*Test,ProjectFactExtractorTest,ContractFactCandidateValidatorTest,AutomaticProjectFileClassificationServiceTest verify`，173项、0失败/错误/跳过；Python合同测试38项通过。
- 26最终发布 `/opt/audit-agent/releases/contract-template-20261104-r3`，JAR SHA256 `7d6f906b85fcc55ea904816ea4abfcf5fbc470308cab0309264c437792c6d769`。无DDL；前端和配置原值保留。本次不制作甲方包、不提交Git。
- 部署曾被3条历史PENDING审核子记录误挡；真实DM8查明其父审核均为UNKNOWN，执行器只领取父PENDING/RUNNING的子记录。专用26检查器按实际领取条件修正；RUNNING、父活动状态或孤儿/未知父状态仍阻止切换，DM8只读反例验证通过。保留历史数据，不把UNKNOWN改为FAILED、不重发未知调用、不修改旧部署工具。
- 原文件两轮新批次真实模型已运行。第二轮确认乙方/金额空栏不发布事实、真实甲方保留、六类留空问题可见，取得11条有效条款并保留八项；法院名称只在争议条款中，不再成为关联合同。规则与模型同标题且证据被规则完整包含的同一问题仅显示一次，不合并不同文件或有额外证据的问题。
- r3只读重开第二轮历史结果，合同八项、精确证据、1440/1024布局、Chrome109无脚本错误通过；持久化步骤与r2完全一致，GET没有创建模型任务。脱敏证据 `.runtime/template-contract-20261104-r3/verification.json`。最终为PARTIAL：3222字已读取，11条有依据条款；严格整段覆盖指标reviewedChars=0（两段各有模型候选未通过核对），不能宣称全文模型审核完成。原文及含原文私有报告不得进入Git或诊断导出。甲方现场仍NOT VERIFIED。

## 2026-11-03：取消v24对齐，合同一批一个项目

- 用户撤销“按Update_v24统一”，保留最新Git基线65464825。尝试中的v24源码恢复已全部撤销，从未把v24部署到26；不要再次按旧包覆盖最新代码。当前维护工作树为`E:/audit-agent-new/.runtime/budget-home-e17-20261014`。
- 合同自动分析入口每次明确选择上传的一批材料创建一个项目，所有文件共用该项目。不同批次不按文件名推断关联，不被旧上传记录阻挡；发送中的重复触发共用原操作。保留旧操作标识与已有数据，不清库。删除顶部上传说明/旧批次提示，保留八大项和正式审核确认。
- 26后端仍为`deploy26-20261103-r1`，前端补丁`contract-batch-20261103-r2`；仅先发布新静态资源、最后原子切换入口，未重启服务或改配置/数据库。服务器`FRONTEND-DEPLOYMENT.json`记录源码差异和实际静态哈希，不把补丁冒称基线Git提交。
- Java17 clean verify3445项、0失败/错误、24跳过；前端848项全部通过，customer构建包含Chrome109兼容。本地候选静态资源配26真实API、发布后默认入口各实测两批三份文件：创建HTTP200、文件接收202、同批同项目/次批新项目、旧记录不阻挡、自动结果页八项、历史重开及1440/1024/390布局通过。202只证明接纳，本轮模型分析质量NOT VERIFIED。
- 验证说明`docs/qa-26/contract-batch-20261103.md`，证据`docs/qa-26/evidence/contract-batch-20261103/`。未制作甲方更新包，未提交Git。

## 2026-11-04：合同真实 DOCX 处理复核（26 r2）

- 根因：正文读取正常，但模型把有效 JSON 包在 Markdown 代码框中，网关直接解析失败并丢弃回答；项目名称回退规则不接受以“服务合同”结尾的标题；Word 表格行列拆散，合同分析也未识别“工作量/含税经费”表头。分段分析曾把“当前片段没有条款”误报为整份合同缺失。
- 修复：仅剥除完整模型响应外围的一层 JSON 代码框，JSON 结构仍严格校验；从唯一且有正文依据的合同标题生成显示用项目名，不写入项目主数据，也不用于跨文件关联；保留 Word 表格行列及金额原文偏移；识别工作量/含税金额并核对；修正分段缺项、附件引用、空白签订日期和重复日期提示。占位甲方等空内容不发布为事实。
- 26 当前版本 `/opt/audit-agent/releases/contract-json-20261104-r2`，JAR SHA-256 `6fd8510d25d0f8ec9d5783bb7fba8a05354cca27401704f11a417041dc433d51`；部署时无数据库迁移，前端入口和现场配置保持原值。backend、AI、Nginx 已复核为 active。
- 对一份真实合同 DOCX 新建独立验证批次，旧记录未修改。Chrome 109 页面显示项目名；正文读取5648字，模型引用核对覆盖3408字，20条款、8个结果分类、2项风险。合同大小写金额一致、4项工作量金额乘算及合计、合同份数核对通过；空签订日期被提示；未上传中标材料，因此中标金额/供应商比对未执行。报告状态为 `PARTIAL`，不可宣称全文审查完成；未通过原文核对的模型候选不作为事实。
- 本地验证：Java 17 `mvnw clean verify` 3453项、0失败/错误、24跳过；合同相关 Python 测试38项通过。Python 全量在当前 Windows 环境有5项文件权限/临时缓存测试不适用；剔除这些平台限制项后2026通过、15跳过、另5项跳过，43个子测试通过。26真实模型调用和Chrome109重开/布局测试通过；甲方现场模型准确率仍 `NOT VERIFIED`。
- 脱敏证据位于 `.runtime/contract-real-document-20261104-r2/verification.json`。正文、文件名、原始请求和模型原始回答不得复制到 Git、部署包或诊断导出。原始探针临时文件须在测试后清理。

## 2026-10-30：ScanDiag_v2预算四页扫描件诊断

- 甲方预算模块约17点四页PDF有预算金额未提取。先取证，不在无原任务证据时断言根因。新包 `E:/audit-agent-new/deliverables/ScanDiag_v2.tar.gz`，一条 `tar -xzf ScanDiag_v2.tar.gz && sudo bash ScanDiag_v2/run.sh --ocr-probe --model-probe`。当天及前后各一天、最近100份预算任务；支持--day和--limit 300。
- 预算FACTS_JSON仅已提取信息及引用，不是全OCR。脚本保留提取核验拒绝原因、最终金额存在性、日志、分段；最近3份四页PDF串行独立OCR复读，最多2次现场BUDGET模型局部对照。正文/文件名/实际金额/原模型回答不导出，未知不当缺失。不重启、不更新、不写DB/业务结果。
- 最终包13成员，SHA `77916d562620c2f0884a30563d2ddde0b982abc2fdade1ff0b1efd7151ceb366`。33项诊断测试、Java17编译隐私自检、backend clean verify3445项0失败0错误24跳过。26最终包真实DM8预算6份无查询错误、八成员归档、四页四方向HTTP OCR均有字且预算金额线索存在。现场根因/模型质量仍待回包。
- 说明 `docs/qa-26/scan-diag-v2-budget.md`，验证 `evidence/scan-diag-v2/`。使用已有Python3.11，不安装依赖，不提交Git。

## 2026-10-30：预算100万元门槛（含边界）

- 用户最终确认100万元本身也触发额外审批。新分析版本SELECTED_SUM_V3，低于100万元/达到或超过100万元；BigDecimal累计，不增加所选项目过滤。自动同项目小额累计同步，上传制度独立校验，历史结果不重写。
- 26默认 `/opt/audit-agent/releases/budget-1m-20261030-r1`，JAR `2fa4065714196da5263e4b807d8a7e02549c1e13a12244a595c3edfcf449115c`，index `96e84220e81f3e71226374322d77d7d64ecf274f8992ad1cf291c60d8199b643`。运行进程目录、HTTP静态资源及正式JAR六项边界通过，配置保留，无DDL。
- Java17 clean verify3445项0失败0错误24跳过，前端846项及customer构建通过。证据 `docs/qa-26/budget-100w-approval.md` / `evidence/budget-1m/`。本次未改AI/OCR，网关质量未重测。
- 26系统python3是3.7.9；维护脚本使用已有 `/opt/python/venvs/audit-ai/bin/python`，不安装/替换系统Python。第一次暂存未切服务即遇到脚本版本差异，修正后完成切换。
- Chrome109.0.5412.0真实勾选→分析→历史重开HTTP200、0页面脚本错误；已识别金额195200元的真实接口返回BELOW_1M，重读一致。缺失金额不被误判为低档。详见browser-http.json。
- 仅更新26及工作区，未提交Git、未制作新甲方包；原Update_v24不含本次门槛调整，后续制包使用本次最终源码与新名称。

## 2026-10-30：Update_v24 三模块扫描件同步

- 用户要求同步预算、合同并制作现场包。三个模块共享方向/短标题/轻量OCR/低置信复读/OCR HTTP1900秒修复；预算与合同PDF页上限补至512，共享读取OCR总时限4小时。字符上限仍预算192000、采购768000、合同1000000。采购逐页DM8重启恢复是独有能力，预算/合同本次没有接入，不能一并宣称。
- 新合同批次总时限6小时，为扫描预留；未上传完成仍按原短窗口收尾，重放保留原deadline。旧案件不重置时间、不修改历史身份。
- Java17最终clean verify3440项0失败0错误24跳过。新增预算162页末页大写金额比较、合同240页末页读取、513页拒绝与上传/重放时限对抗测试。Python源码与2049通过14跳过候选同字节；前端使用v23 Chrome109已构建产物，无新布局变动。
- 新包 `E:/audit-agent-new/deliverables/Update_v24.tar.gz`，99113696字节，SHA256 `b55dd21f181b916ca87f45bf76b220726e9c7b36a5a8d542c4686d7cbd635867`。最终包202项、完整性/篡改拒绝、权限、Java17/LF/语法、字体与9份模型文件哈希通过。新增离线权重文件由归档0755目录/0644文件保证非root服务可读；安装脚本/schema/SQL/检查器与v15字节一致，无新增DDL/部署判定。
- 一条 `sudo bash update.sh`，成功UPDATE=PASS后Ctrl+F5。无需恢复JSON，不自动备份DB/业务文件，保留现场配置、旧应用与数据。甲方ARM64及真实模型结果NOT VERIFIED，未提交Git。26后续验收记录以docs/qa-26/v24-three-module-scans.md及evidence/v24为准。
- 最终26默认升级 `/opt/audit-agent/releases/v24-20261030-r1`，JAR `3c99b26f1458637aec6d144834f62166c4a7c5290c8d6fdba52e893d910019ad` / index `b54dfe4800fb01f66b7740753f9b2cfdd30c4dcc4a2c2680446fcb124fc679c86`与包一致。真实预算/合同消费者→HTTP/Paddle倒置/侧向读取及金额比较在QA和默认均通过，7项静态资源HTTP哈希通过；模型NOT EXECUTED，不冒充报告质量验收。无DDL/配置更改。

## 2026-10-30：扫描长文件修复（覆盖下节诊断时的旧默认值）

- ScanDiag 已证实采购 162 页被旧 100 页上限拒绝，两页文件卡在现场 OCR HTTP 120 秒，不能解释成材料没有金额。新采购上限 512 页 / 768000 字符，文件仍 500 MB；预算上限本轮未扩大。
- 采购 PDF 页检查点写已有 AUDIT_CASE_FILE CLOB，无 DDL、不清历史、不新增 schema/恢复记录门槛。页完成后短事务保存并续租，重启复用；UNKNOWN 模型调用不盲目重发。长正文按稳定窗口提取，不截尾，后续上下文优先事实证据。
- OCR 专用 HTTP 最少 1900000ms，自动纠正旧 120000ms，用户更长值保留；AI 超时独立。轻量离线权重 PP-OCRv5_mobile_det + PP-OCRv4_mobile_rec 必须随后续更新包携带，保留方向模型及原精度缓存。不升级 Python/Paddle/OpenCV，不让现场上传时下载模型。CPU1、MKLDNN关闭不变。
- 本地 Java3435项0失败0错误24跳过，Python2049通过14跳过。26真实 DM8 检查点/CAS/回滚验证通过。实测及最终默认部署状态见 docs/qa-26/scan-repair-20261030.md 和对应 verification.json；本轮网关 CONNECT_TIMEOUT，用户确认不可用，真实模型结果 NOT VERIFIED，不得以 OCR 通过代替。
- 最终默认26已切换 `/opt/audit-agent/releases/scan-20261030-r1`，实际Java/AI进程路径与候选哈希通过，前端保持v22。162页少文字四方向扫描PDF全部读完（1074.625秒、页码连续、尾页预算存在）；默认入口Java→HTTP→OCR四页中断恢复通过，48次忙时健康/页面查询HTTP200。无DDL/配置修改、未制甲方包、未提交Git。

## 2026-10-30：ScanDiag_v1 采购扫描PDF现场取证

- 新增独立只读诊断包ScanDiag_v1.tar.gz，不更新/重启、不修改业务数据或放大处理上限。现场执行：tar -xzf ScanDiag_v1.tar.gz && sudo bash ScanDiag_v1/run.sh。不需要重传材料；只回传OUTPUT诊断包，绝不回传PDF/正文/原始日志/凭据。
- 当前采购读取默认100页/192000字符；约162页可能在模型前触发PAGE_LIMIT_EXCEEDED，但现场是否覆盖及两页失败必须分别用证据确认。不能把无结果解释为材料没有信息，不能只提高页数就声称长扫描件已处理完整。
- 工具按文件关联数据库保存文字、页码覆盖、模型分段进度/累计耗时及技术原因；原PDF最多8份/512页原生信息检查，不重跑OCR，默认不调用模型。读失败、原生探测失败、模型未知和部分分段均与真正没有事实区分。运行类默认值不冒充完整生效配置。
- 本地27项工具测试、Java17隐私自测、最终包清单及shell语法通过；完整采集测试使用Fake数据库/systemd，PDF是真生成文件。全工程clean verify3427项0失败0错误24跳过。真实现场脚本和甲方文件仍NOT VERIFIED。工具与证据见docs/qa-26/scan-diag-v1.md。未提交Git。

## 2026-10-30：v23 采购项目金额名称

- 采购自动分析结果页顶部和HTML导出统一为“预算金额”“最高限价”，沿用整批材料归集额度；本次不改提取、去重、项目隔离或冲突处理逻辑。
- Windows Java17 clean verify：3427项、0失败、0错误、24跳过；前端846项通过；Chrome109专用构建通过，资源assets/update-v23。AI源码与v20同字节，沿用既有测试记录，本次未重跑Python。
- 26的SSH与HTTP连接超时，本轮没有上传或部署v23，远程浏览器与新模型结果NOT VERIFIED；最后已验证远程版本仍为v22.material-20261029-r1。不要把本地accepted清单或包生成当成远端验收。
- Update_v23.tar.gz位于E:/audit-agent-new/deliverables，84,575,040字节，SHA256 1584044aa0da65e9b43dac517735ac05ddcdeb29405c1479e3f016e8e011f9fa。最终包清单、篡改拒绝、Java17、LF/bash语法、静态资源、权限、OCR权重哈希通过；安装脚本/schema/SQL/检查器沿用v15，无新增DDL或备份证明要求。解压新目录执行sudo bash update.sh，UPDATE=PASS后Ctrl+F5。未提交Git。
- 证据：docs/qa-26/v23-procurement-amount-labels.md及evidence/v23/package-verification.json。26恢复后再用v23部署辅助脚本及同目录preserve_ocr_models.py验证和更新默认入口。

## 2026-10-29：v22 采购历史筛选与结果折叠

- 26默认入口已升级 `v22.material-20261029-r1`，JAR `3f728e50518c35b8837707e5b9a2cea3337d6c6321210aebc9ffeeb62e592a4b`，index `be33d0929cfca0a212a8283ef20f3ab4e0e0afedf2e85f869e3a5f391ff51a2d`；前端资源 `assets/update-v22`。无DDL，现场配置、数据、Nginx与离线权重保留。
- 采购历史查询红字已定位为前端把空日期序列化为 `from=undefined&to=undefined` 导致400；不是已证实的脏数据。只发送有效条件，移除早期列表入口，保留历史业务数据。不能以此为由清库。
- 三模块无问题项默认折叠，明确问题优先展开；只有实际正常核对用浅绿色，UNKNOWN/材料不足不标通过。合同八大项、正式审核和确认保留。采购旧报告系统描述中的属性名/“字段”中文化，页面、复制和HTML导出一致，原文证据和业务名称不改。
- JDK17 clean verify 3427项0失败0错误24跳过；前端846项全部通过；Chrome109隔离8批与默认9批重开、筛选、开合、复制/导出及1440/1024/390布局验证通过。AI源码与v20同字节，沿用2016通过/15跳过证据；模型端口仍不可达，本轮未验新的推理质量。证据 `docs/qa-26/v22-history-result-cleanup.md`。
- 制作新名Update_v22更新包，沿用v15安装脚本/schema/SQL/检查器字节，不加恢复JSON、备份证明或无关数据库判定。26暂存脚本需同目录携带 `tools/qa26/preserve_ocr_models.py`；缺少时应在切换前停止并保留当前服务，不能遗漏方向权重。甲方标准包已有离线模型，不新增这个辅助脚本依赖。未提交Git。
- 最终包 `E:/audit-agent-new/deliverables/Update_v22.tar.gz`，84,577,687字节，SHA-256 `8a7615288659ef9b8f7b969ba5fd55239d1d53096a489bffa58d4660c78e33d0`；最终解包197项清单、篡改拒绝、Java17、LF/bash语法、本地JS/CSS/字体引用、权限和OCR模型哈希全部通过。证据 `docs/qa-26/evidence/v22/package-verification.json`。解压新目录后执行 `sudo bash update.sh`，成功 `UPDATE=PASS`，浏览器Ctrl+F5。无新增迁移，保留现场配置和数据。

## 2026-10-29：v21 托管测试、采购反面示例误报

- 26当前默认`v21.material-20261029-r1`，JAR `6f1a18636252687562b2b49af906d84a851efc64577df98d7851bed2bc3b1cd2`，前端index仍`ebb5a5d08ecc5c8e42a1aa8f9784921472f7e2f41a1f01c942cce61ba1e6aee9`。仅采购`TenderRuleMatcher.class`较v20变更，前端/AI无新增修改。无DDL，配置/Nginx/3份离线OCR权重保留。隔离规则验收后更新默认入口，默认Chrome109重开9批布局和记录通过。证据入口`docs/qa-26/v21-hosted-testing.md`。
- 明确“禁止采用的反面示例”的引文不再产生F10规则问题；引文之外的真实限制仍命中。真26两批验证0/1条地域问题，持久化结果重读一致。最终Java17 clean verify3425项、0失败/错误、24跳过；跳过项不算通过。独立侧向90°PNG实际OCR HTTP200/38.19秒，中文大写申报和批复金额均识别，原文件不变。
- 本轮9批17份的模型质量验收未通过：26到既有模型端口TCP5秒超时，同时间22次模型HTTP均CONNECT_TIMEOUT，本机连接也失败。不能将这些空结果解释成材料没有预算、报价或附件，也不能用历史重开/八项占位通过冒充模型结果通过。模型连接恢复后用新批次重测金额边界、跨项目隔离、附件、正式建档及五步；旧UNKNOWN结果不盲目重发或覆盖。
- 未提交/推送Git，未制作新的甲方包；既有Update_v20不包含本次v21规则修复。后续制包须使用最终验收版本、新包名，并重新检查实际字节及离线模型。

## 2026-10-29：v20 三模块结果页、金额语义与更新包

- 当前26默认版本为`v20.material-20261029-r3`。默认80入口JAR/index及3份入口静态资源字节核对、真实Chrome109登录、三模块历史及合同八项通过；默认入口新倒置PNG实际OCR→模型提取预算2200000元。无DDL，原配置/Nginx/业务数据及3份离线方向模型保留。证据入口`docs/qa-26/v20-result-layout-validation.md`。
- 包：`E:/audit-agent-new/deliverables/Update_v20.tar.gz`，84577306字节，SHA256 `7eb033bdb7deeed8aef0b12a24204bc38cd41222cfca10dd9fdf44c90ac115b1`。解压到新目录，进入有update.sh的目录，执行`sudo bash update.sh`；成功以`UPDATE=PASS`为准，完成后Ctrl+F5。未提交或推送Git。
- 使用26验收同字节JAR、前端和AI源码。JAR `a399e6d875862309e14f878564da6f4a74848be18016a899cf28ae0514d9f102`；index `ebb5a5d08ecc5c8e42a1aa8f9784921472f7e2f41a1f01c942cce61ba1e6aee9`。代码来自已测试工作区快照，不冒称全部已有Git提交，清单见包内`RELEASE-SOURCE.json`/`source.sha256`。
- 共用结果卡片：项目名称→重点问题→固定模块→来源。合同8项始终有位置，摘要折叠明细，正式五步和问题确认保留；预算固定3分区，采购预算/限价/中标金额固定位置。内容不足保留简短空模块，不制造结果。复制及历史重开保持报告一致。
- 真模型曾提取了`人民币叁拾陆万元整（含税）`和`最高投标限价350000元`，Java分别因金额注记和限定角色词拒绝；现修复并通过新批次360000预算/350000限价/355000报价/5000保证金验证。只允许明确税基注记，不吞税率、其他金额或任意括号；不把保证金当报价、限价当预算。初始失败证据保留。
- Java clean verify3422项0失败/错误24跳过；前端843通过、customer构建通过；Python2016通过15跳过43子测试。Chrome109上传/自动结果、Excel/HTML、复制、历史筛选、正式确认、1024/390无横向溢出通过。新唯一编号合同自动建档与正式5步COMPLETED；自动分析PARTIAL仍按部分范围解释。
- 安装脚本、SQL、schema和检查器与v15逐字节相同，无新增恢复JSON、备份前置或数据库判定。原DM8首次/重复/DDL后代码恢复/漂移反例证据按相同字节基线沿用；不是本轮重新执行迁移。最终包解包检查清单/篡改拒绝、LF/bash语法、Java17 class、字体/静态引用、权限、SHA均通过；Python依赖清单与既有版本仅换行差异，依赖内容未变。甲方ARM64实际执行与原材料准确率仍NOT VERIFIED。

## 2026-10-29：v19 三模块多场景回归与26默认入口

- 当前26默认版本为 `v19.material-20261029-r3`，backend/AI/Nginx active，默认入口HTTP资源字节与已验产物一致，无DDL，既有配置和Nginx保留。证据入口 `docs/qa-26/v19-three-module-validation.md`，原始结果在 `docs/qa-26/evidence/v19/`。本轮没有甲方包、Git提交或推送。
- Java17最终clean verify：3419项，0失败/错误，24跳过；前端850项通过并完成customer构建；AI Python2016通过/15跳过/43子测试。Chrome109真实页面上传、自动结果、导出、复制、历史、1024/1440宽度和默认入口再次验证，0脚本错误。跳过项不算通过。
- 采购预算/最高限价/报价分别保存和计算；响应文件/报价函中的已核实报价不能因文件类别漏掉；中标金额不当作独立报价；“不得指定/无需本地设立”等否定语义不能反判。确定性报价超限时，优先关注摘要不得同时显示暂无明确问题。结果页不再残留上传框或旧历史请求错误。
- 预算模型把人员错标为部门时只舍弃该可选错误角色。XLS跨工作表重复申报保留单条已识别金额和全部来源，不盲目相加或判通过；不能为避免重复而把可证明的金额清空。
- 合同服务期限区间、违约金总价款基数/同一事件逾期日数、批次附件存在/缺失均有相反场景回归。单文件模型不得断言整批材料缺失。正式BREACH/TERM策略升级v5，旧未完成Run不得静默用新策略续跑；旧完成报告可读。新唯一编号批次实际自动建档，真实Chrome109启动正式五步全部COMPLETED，并确认问题后刷新持久化，见 `fresh-formal-contract.json`、`fresh-formal-run.json`、`fresh-formal-browser.json`。
- **离线OCR权重必须随更新保留**：v16/v17源代码发布曾遗漏 `services/audit-ai/models/PP-LCNet_x1_0_doc_ori`，导致健康200但真实OCR初始化失败。本轮从既有v15安装恢复3份权重并核对SHA；制包/部署复用 `tools/qa26/preserve_ocr_models.py`，不可只复制src。默认80入口倒置PNG实际OCR→模型提取2200000元通过，不能只用health宣称扫描件可用。不得自动下载替代现场离线模型。
- 合成测试通过不代表甲方真实材料准确率已验收；甲方ARM部署和原材料仍NOT VERIFIED。初始失败、XLS第二轮失败及修复后新批次证据均保留，不修改旧报告制造通过。

## 2026-10-29：v17 合同项目名称与结果页精简

- 26 默认版本为 `v17.material-20261029-r1`，健康与配置保留检查通过，无 DDL。Java clean verify 3405 项无失败，前端 837 项通过；Chrome 109 真实历史结果验证通过，证据在 `docs/qa-26/evidence/v17/`。
- 合同结果顶部只展示本批项目名称；`PROJECT_NAME` 与 `CONTRACT_NAME` 分开，禁止以合同名称兜底项目名。采购委托协议书、合同书、招标文件及办公文件名均不得当作项目名称；明确名称缺失时生成名称。历史结果的显示投影也使用此规则，不要求用户重新上传，不在 GET 中修改业务数据。
- 已完成结果不再显示顶部“分析完成/本次处理范围”等说明；删除重复项目卡片、未建档时的空正式审核区。自动分析八大项保留，真实正式审核记录与确认在八大项后折叠展示。
- 本轮未制作甲方离线包或提交 Git。后续制包需包含这次完整前后端改动，不能只覆盖样式而遗漏报告项目名字段。

## 2026-10-29：三模块 v16 与前端默认入口

- 26 默认入口已切换到 `v16.material-20261029-r4`，部署健康、现有配置和 Nginx 保留、真实登录及历史查询均通过；没有执行 DDL。证据见 `docs/qa-26/v16-implementation-validation.md` 和 `docs/qa-26/evidence/v16/`。当前尚未制作 v16 甲方离线包，不能把旧 v15 包当作本轮代码。
- 合同自动分析应为默认入口，不能再依赖遗漏时回到旧页面的 `VITE_CONTRACT_ANALYSIS_ENABLED` 构建变量。显式旧数据源仅保留兼容测试和历史入口，八大项/正式审核/确认继续保留。
- 采购主入口为批次免填表上传和结果；旧采购列表仅从历史入口访问，不重新叠到自动处理页面；预算按批次组织结果。
- 交付前端使用 `npm run build:customer`，该命令包含 Chrome 109 颜色兼容及现有字体处理。必须真实验证最终入口，不能只检查 Vite 构建成功。
- 项目名称用于展示时优先实际提取值，缺失才生成；名称不是跨项目金额关联授权。预算项目名/编号分散在 Word 段落时可在唯一作用域内关联，多个项目混排不能串联。
- 本轮 Java clean verify 3399 项无失败，前端 837 项、Python 2016 项通过；真实26预算、采购、合同重读和Chrome109结果另有证据。跳过项及甲方原材料准确率不算通过。

## FieldDiag_v1 现场先取证（2026-10-29）

- 现场回包`FieldDiag-20261029-142559.tar.gz`已分析：JAR、交付ARM包内前端index以及四个AI源码哈希均匹配v14。不能用较早x86测试副本目录的index哈希误判现场仍为旧前端。
- 采购1批6份，4份UNKNOWN/EXTRACTION_OUTCOME_UNPROVEN且保存文本0字；其中64页PDF原生文字40359字，第3/5页有预算/货币线索。该批综合预审仅收到另2份材料7911字，招标合规模块scopeFiles=0。此批不是触及12000/54000审查上限；不能把读取失败说成材料没有预算。
- 现场显式`AUDIT_OCR_READ_TIMEOUT_MS=120000`；28日日志有120秒DOWNSTREAM_TIMEOUT，29日单页OCR有126秒完成，存在超时配置不匹配。29日某请求88次OCR累计约218秒，仅输出162字，需优化图片OCR开销。已有90/180/270旋转记录，不能说旋转模块未启用，也不能据此保证每页转正准确。
- 最新合同7份均READY；合同标题文件8242字且CONTRACT_BODY/CONFIRMED，生成DRAFT候选但合同名称/类型/双方为空；另有委托协议候选缺金额/类型。项目无正式合同、正式Run；现有八大项UI绑定正式建档成功，这是不展示的直接条件。模型分析已存在的双方事实没有全部进入候选，需细查发布/物化，不能说文件未识别或字段原文不存在。较早两轮另有4096 token LENGTH截断；最新一轮没有FAILED步骤，须区分时间，不能混作最新故障。
- 用v14真实编译class本地复现：公开例句金额`2200000人民币`金额解析失败；`预算总金额为人民币…元`、`预算价为…元`金额可解析但CaseFactPolicy角色正则拒绝。正常`预算为…元`通过。此为确定的语义/格式适配缺口，不等于已证明现场每个舍弃字段都由这一个原因造成。
- 诊断工具v1自身有缺陷：按时间正序收满80000条，约78091条为HTTP请求事件，后端导出停在28日23:48而未覆盖29日。后续诊断应优先最新业务事件并记录过滤/截断，不要求用户重新上传材料；当前现场4份采购UNKNOWN各自精确异常尚不能从这份后端日志证明。固定词表也遗漏部分枚举/动态MODEL_HTTP原因码，OTHER_OR_UNAVAILABLE不能当作真实业务结论。

- 采购持续“证据不足”、PDF旋转/OCR慢、合同文件已命名合同书但无八大项时，先运行 `sudo bash FieldDiag_v1/run.sh --model-probe`，不要求重新上传材料。包位于 `E:/audit-agent-new/deliverables/FieldDiag_v1.tar.gz`，SHA-256 `1146996124cf0ec8f4724f3c60c4676d868b446fe81dbb32a1b5d83db1506e08`。
- 独立诊断工具：DM8仅SELECT/readOnly并rollback，不部署、不迁移、不重启；自动读取运行服务配置。当前/前后一天、每模块最近20批，可用`--day`/`--limit`指定。版本哈希只作诊断，不能作为升级拦截条件。
- 仅本地读取正文/原文件；导出固定公开关键词命中统计、字段类型与数量、候选必需字段是否存在、模型/引用状态、PDF原生文字及旋转统计、正式审核/报告状态、运行版本哈希。禁止导出正文、文件名、实际金额、单位/人员名称、原始模型回答、地址或密钥；不允许模型自由返回的字眼直接进入导出包。
- `--model-probe`最多2次串行调用当前配置模型，优先选预算附近原文，每次180秒、整体390秒；不重试、不补写业务结果。沿用AI服务自身完整环境，避免诊断命令误继承SSH代理。省略选项不调用模型。PDF最多4份/每份256页/35秒，只读原生文字，不重跑OCR；图片真实朝向需结合已有OCR日志，不能拿PDF旋转标签代替判断。
- 已在26真实DM8读取9批采购/12批合同，无查询错误；4份PDF读取、旋转/OCR日志、候选字段/正式审核状态可导出；真实模型探测预算、限价、采购人等返回并通过探测的逐字定位统计。探测不是整份文件正式验收，不能据此认定甲方真实材料已解决。
- 本地13项脱敏/恶意字段/跨定位符/启动参数回归及Java17特征自检通过；v14隔离发布基线再次`mvnw.cmd clean verify`：3351 tests、0 failures/errors、24 skipped。最终包manifest、LF、Java17 class需从tar解压复核。不提交Git。
- 发现采购综合审查已有每文件12000字/整批54000字上下文范围，须结合现场导出的sourceChars/suppliedChars与字段提取记录定位；不能凭此直接判定字段提取丢失的根因。

## Update_v14 合同链路修复与验收（2026-10-29）

- 26默认入口已实际切换至`v14.lAxRaaKs`，升级日志`UPDATE=PASS`及`HTTP_INDEX_ASSETS_AUTH=PASS`，backend/AI均active。测试账号登录、健康、合同分析历史均HTTP200；独立QA原服务已恢复。此项是26 x86_64测试副本，甲方包继续锁定ARM64，不能描述为甲方升级完成。

- 包：`E:/audit-agent-new/deliverables/Update_v14.tar.gz`，SHA-256 `1ce10d2155010d4a89e0431bf285ca48fa6a772a11fdb3d8e31503ac5f65ab2f`。基于隔离源码构建，实际文件见包内`RELEASE-SOURCE.json`/`source.sha256`；不含工作区未完成的统一解析器/上传改造，不提交Git。
- 修复独立合同分析固定24次预算不足、输出LENGTH截断、限定同义字段和超长引用；2400字/160字重叠，按输入分配预算且总上限256。仅明确输出超限可以继续拆小；未知请求不自动重发。
- 接入同份冻结原文事实发布及原有审计自动建档，不凭文件名生成合同数据。前端必须接受已有decisionId且CONFIRMED的`SYSTEM_AUTO_DECISION`；此前仅接受人工/旧角色，导致自动创建合同却选不到材料、隐藏八大项。不能用机器suggestion替代持久化决定。
- Java17 clean verify 3351 tests、0 failures/errors、24 skipped；Python2013 passed/15 skipped；前端831 passed及生产构建。真实Chromium109中回放26模型/DM8结果，八项标题、字体、按钮、项目失败保留结果均验证，无重复启动审核。
- 26独立真实模型/DM8：合同10项事实、自动建档、正式五步完成；补充材料/伪合同文件名生成报告但不错误建档。确认重复/重读保持同一记录，HTML导出AVAILABLE含八大项；精确已验JAR重启后报告不变。部分金额来源/附件/外部资格/违约量化仍有限制，八项显示不等于全部通过。
- 10252字长文生成报告并建档、规划步骤均收口，但部分模型候选不满足原文核验，因此PARTIAL，完整核验覆盖1292/10252；不得宣传全文准确率通过。高度重复的七文件填充样本有3份压缩比例安全拒绝，仅算失败隔离反例。甲方原7份正文未取得，现场准确率NOT VERIFIED。
- 部署沿用v13a的一条`sudo bash update.sh`。schema/SQL/所有迁移检查器class及部署脚本与v13a相同，无新增DDL、恢复JSON门槛或数据库种子。最终tar解包manifest、Java17 class、LF/bash语法/入口资源检查通过。甲方ARM实际升级仍NOT VERIFIED。

## v13 现场403复盘与 v13a 部署约定（2026-10-29）

- `F:/xlog.txt`：v13在HTTP预检读取旧release静态文件时收到403，以HTTP_ASSET_MISMATCH退出；尚未停止服务、迁移或切换。未记录资源路径，不能断定具体文件或权限原因。
- 修复原则：更新是替换旧前端，禁止遍历旧目录的每个历史文件并以其不可访问阻止修复。旧入口不一致仅告警；鉴权边界仍检查。新release切换后仍严格比对入口和资源HTTP字节，资源失败输出相对路径，沿用既有失败回退。
- 新包独立命名`Update_v13a.tar.gz`，应用JAR/前端/AI/SQL/schema/Java检查器与v13完全相同；只改common.sh和update-core.sh及说明/证据。没有新增DB条件，不把这个部署修复称为合同业务修复。命令仍为`sudo bash update.sh`。
- 最终包`E:/audit-agent-new/deliverables/Update_v13a.tar.gz`，SHA-256 `9ceea92d0a7f729464ec445aa603df955c2d805eead6bc65229333a70046ab1d`；诊断包`ContractDiag_v4.tar.gz`，SHA-256 `fb57ab6627454f0f67fa27509dba5eb8f285c7993d9235b034b573acc0d88eab`。最终解包manifest、LF脚本、Java17 class及原应用产物相同字节复核通过。隔离源码本轮再次clean verify：3339 tests，0 failures/errors，24 skipped，BUILD SUCCESS。未提交Git。
- 26 Linux隔离目录真实HTTP回归8项通过，包括旧403不拦截、新403/内容不符拒绝、鉴权异常拒绝；既有重复部署/回退5项在现有AI Python3.11运行通过。甲方ARM正式升级仍NOT VERIFIED。
- ContractDiag_v3现场元数据：目标7份文件均有已持久化文本，自动分析案件为PARTIAL；旧分类记录缺失由新自动分析主动隔离旧分类/建档流程导致，不能误判成OCR失败。旧分类器的文件名修复不能证明独立自动分析已接入。
- 现场批次按5500字/重叠250字至少需30段，固定24次调用不足以全覆盖；另有3个MODEL_HTTP_502阶段及39条格式拒绝日志。502来自Java调用AI服务，不能直接归因网关HTTP502。v3脚本漏导格式字段/错误类型，v4仅补充固定安全字段，继续不导出正文/文件名/模型返回。

## Update_v13（2026-10-28）

- 包：`E:/audit-agent-new/deliverables/Update_v13.tar.gz`，SHA-256 `6695b2f3cada4870155027acfba1ccca4f9bab89956dbc51053a83be66a81be1`。基于 `a0a9cce974027810e44002dd1d316892ef0f0897` 和限定工作区改动隔离构建，范围见包内 RELEASE-SOURCE.json；不含未完成的共用解析器/上传字节进度改造，不提交 Git。
- 适用甲方 v12a ARM64 既有安装。解压新目录后只执行 `sudo bash update.sh`；无需恢复记录、不自动备份数据库/业务文件。沿用 v12a 部署器/Schema/关键检查器 class 的相同字节，无新增迁移/额外数据库判定。最终解包 manifest、Java17 class major61 和入口资源路径复核通过。
- 前端必须显式 `VITE_CONTRACT_ANALYSIS_ENABLED=true` 构建，否则会回到旧合同入口；资源使用 `assets/update-v13/`，累计中文字体和 Chrome109 颜色兼容由最终生产 CSS 重新生成，不能覆盖回旧 CSS。静态资源目录0755、文件0644。
- 合同：保留自动分析入口的八大项/问题确认/项目摘要/问题来源与复制；增加历史项目名称和日期筛选及名称修改。正式审核 Hook 必须先初始化再读取；项目详情读取失败不得解引用空 detail。文件名类别优先于目录名，仅为分类线索；通用“附件”仍允许目录补足类型；高置信正文优先，不凭名称制造合同证据或正式数据。
- 隔离源码完整 Maven verify：3339 tests，0 failures，0 errors，24 skipped，BUILD SUCCESS；生产前端构建通过，840项非视觉Node测试通过。既有开发服务器登录视觉测试超时，未算通过；最终生产资源另经真实 Chromium109.0.5412.0验证登录、筛选、改名、正式合同渲染、详情失败保留结果、按钮和字体，0 pageerror。
- 26独立临时dev29服务运行同一JAR，真实DM8认证HTTP200、项目名称/日期组合筛选、无匹配查询、专用合成验收项目改名持久化与报告不变通过；验证后已停止临时服务，原backend/AI/Nginx均active，默认入口未切换。此次未调用模型，不能声称最新业务模型全链路通过。甲方ARM/实际升级/模型与OCR仍NOT VERIFIED。

开始甲方打包、数据库预检或部署前，先读 `docs/runbook/customer-offline-deployment.md`。

- 当前原 v10 应用提交：`0dd7bd5496aaaec487da35cbd2b710f761de37b7`。
- `Schema_Fix_v1` 只修复部署检查器对限定 CHECK 等价表达式的误判，业务产物和数据库数据不变。
- V016 与合同 CHECK 曾因 DM8 的等价运算符、NULL 写法和冗余括号被误报；不得再按表达式字符串全等判断，也不得把所有 CHECK 放宽。
- 制包前必须验证真实 DM8 首次建表、重复执行、DDL 后回退再检查及真实漂移 fail-closed。
- 代码回退不撤销已执行 DDL；不刷库、不删表、不清历史、不重放已执行 SQL。
- 现场 AI 地址/密钥/模型名、Nginx、数据库/Redis 和业务数据必须保留。模型网关白名单与数据库问题分别处理。
- 本地测试通过不等于甲方部署成功；现场未验证内容必须标记 `NOT VERIFIED`。

## FontFix_v3（2026-10-23）

- 用途：只给采购列表“采购类型”列增加 Chrome 109 可用的本地中文字体；采用静态资源叠加，不换整套前端。
- 适用基线：v10 release4 前端入口 SHA-256 `cbea230866a44ae5905c28b96a210875cc17d2d81b8ae622a8c7c728253a6a7f`；脚本同时检查原静态资源哈希，匹配失败必须停止。
- 包：`deliverables/FontFix_v3.tar.gz`；SHA-256 `a791180ceac9b333581e585e636e838e147c674b16f88aadcae7bb34f8719a94`。
- Chrome 109：合成采购数据“服务/货物/工程”在 1920/DPR1、1536/DPR1.25、1280/DPR1.5、960/DPR2 均使用本地字体、无裁切、无外网请求；补丁包叠加到 v10 前端后复验通过。
- 更新不重启服务、不改 Nginx 配置、后端、AI、数据库或 Redis；成功后浏览器 Ctrl+F5。脚本支持 `--check`、HTTP 失败恢复入口和显式回退。
- 甲方服务器预检、实际更新和浏览器显示：`NOT VERIFIED`。若 `--check` 的入口/资源指纹不匹配，不要绕过检查；先收集实际 release 入口哈希再制作匹配包。
- 代码提交 `9623b795a72e5faf84508cdc4567353d85ea0389` 在本地分支；截至本记录 GitHub 推送因连接 `github.com:443` 失败，尚未同步远程。

每次新包交付后更新当前提交、包 SHA-256、现场结果和未验证项。

## FontFix_v4（2026-10-23）

- 原因：前一轮 FontFix_v3 只携带采购类型列选择器，构建时遗漏了早期已验证的统计标签、状态/预审标签和材料预览选择器，导致后续前端更新后这些位置回退。
- 修复：FontFix_v4 追加同一份本地 `Audit UI CJK` 字体并恢复 v1+v2 全部限定选择器；不替换 JS/CSS，不改后端、AI、数据库、Redis 或 Nginx。
- 包：`deliverables/FontFix_v4.tar.gz`；SHA-256：`bd080f6f248f542a43309618319e985657cb6addf883fb641bdcc54b9d52cd97`。
- 入口预检同时接受原 v10 入口和已安装 FontFix_v3 入口；匹配失败必须停止，不绕过哈希校验。
- Chrome 109 本地验证：3 组窗口/DPR、全部五组字体选择器、无裁切、无外网请求；甲方现场尚未验证（`NOT VERIFIED`）。

## 现场材料诊断与解析加固（2026-10-24）

- 现场日志需用同一时间窗口导出 `audit-backend`、`audit-ai`，按 `requestId`/材料 UUID 关联；不收集材料正文、OCR 原文、prompt、模型原始响应或数据库转储。详细阶段说明见 `docs/runbook/customer-offline-deployment.md` 的“现场材料问题诊断”。
- OCR 默认 HTTP read timeout 为 1,900,000 ms，略长于 Python 隔离 OCR worker 的 1,800 秒截止时间；部署现场需检查 systemd 是否通过 `AUDIT_OCR_READ_TIMEOUT_MS` 覆盖了该默认值。该值只适用于 OCR，不能作为模型单次调用时限。
- OCR 日志现在区分 PDF 原生文本、是否需 OCR、每个 OCR 页是否为空、旋转/纠偏角及最终字符数量。扫描页 OCR 必需但返回空文本时失败为 `OCR_PAGE_TEXT_EMPTY`；混合 PDF 的装饰图片 OCR 为空不丢弃已有原生文字。
- 采购日志新增材料字段提取、招标文本/OCR、F10 规则与 AI 审查的阶段/耗时/数量/安全原因码。投标/招标材料的 AI 字段候选只在来源原文可定位后保存；预算金额与报价金额角色冲突时拒绝候选，不将预算金额冒充投标报价。模型字段补充失败不会覆盖已经解析的材料正文和规则审查结果。
- 合同分段日志分别输出 `maxDocumentCodePoints`、`maxPhysicalSegments`、`maxBatchSegments` 和 `maxSegmentCodePoints`，不能把单批段数当作文档上限。
- 本轮最终本地验证：JDK 17 下 `backend/mvnw.cmd clean verify` 为 3144 tests、0 failures、0 errors、15 skipped；受影响采购/材料测试也通过。Python 3.11 物料字段与 OCR 定向测试为 56 passed、2 skipped；OCR 进程协议单测首次因 Windows 文件锁清理失败，单项重跑通过。前端 `npm run build` 通过，仅有既有 chunk size/import warning。
- 采购普通材料若模型没有返回可验证字段，已完成原文读取并保存空字段投影，不再伪装为技术解析失败；真正的文件读取失败、OCR 失败、模型超时或调用结果未知仍按失败/未知收口。候选字段逐项核验，单字段证据/角色不符只丢弃该字段并记脱敏原因码，不影响其他可信字段；预算金额、最高限价、投标报价及采购人/代理机构仍必须通过原文角色证据后才保存。
- 本轮仍未部署 26、未连接甲方、未生成更新包、未验证真实 PaddleOCR/模型网关调用；这些必须标记 `NOT VERIFIED`，不能把本地测试描述为现场可用。

## 26 三模块合成验收（2026-10-27 CST）

- 部署版本：`auto-intake-20261026`，应用基线 `35f95499c4d5efa475dffc3a3628222750124672` 加隔离候选工作树变更；候选归档 SHA-256：`3a5db9976895b4e5d8ef8172936aa20acc9f0d2d52b929e433fa6a0540be974a`。
- 26 release：`/opt/audit-agent/releases/auto-intake-20261026.qw7KXB`。部署前实测旧 JAR SHA-256 `3821ab5a660df343af1a05160ecf7eb3f36613c096161a6f262866cd41052f30`、入口 SHA-256 `cbea230866a44ae5905c28b96a210875cc17d2d81b8ae622a8c7c728253a6a7f`，均作为精确回退/漂移保护值。新 JAR SHA-256 `ce76b6d0d12f0c098aa7bd9955b081abb420643049a5da4ed23d134aab720f4f`，前端入口 SHA-256 `08e07b799b7c62e341fb882290ba2e93afd2d7871a2ea0023b5d85a563bae644`。
- 26 `--check`：活动任务数为 0；V052/V053 已匹配，V054–V057 schema 匹配；合并合同 schema 校验通过、计划语句数 0；合同自动权限 schema 计划语句数 0；Python 3.11 运行时源检查通过。DM8 隔离临时表验证首次检查、重复检查、模拟代码回退后识别、真实漂移拒绝均通过，临时表已删除。
- 部署结果：`THREE_MODULE_DEPLOY26=PASS`；backend、AI、Nginx 均 active；backend/AI health 均 UP；HTTP 静态入口和引用资源均匹配；现场配置哈希未变化。测试结束再次确认预算、采购、合同及材料提取活动任务均为 0。
- 本地验证：JDK 17 `backend/mvnw.cmd clean verify`：3163 tests，0 failures，0 errors，15 skipped；前端 Node 测试 806/806，生产构建成功（存在既有 chunk-size/dynamic-import 警告）；AI/OCR 全量 pytest 1981 passed、15 skipped、43 subtests passed（本次代码未再改动 AI 源码）。`git diff --check` 通过。
- 26 预算批次：12 份 DOCX/XLSX/TXT 混合材料和长自然语言材料均完成；结果在 backend 重启后由新会话重新读取，13 个结果指纹未变。
- 26 预算风险/拆分：累计金额三档及边界、跨年度明确选择、40 条候选分页、历史检索/分页、6 路同一操作并发幂等、非法范围变更 409 均通过。
- 26 采购：两份合成材料保存后自动生成审查报告；品牌限制风险被识别，未重复上传；重复生成、报告读取和新会话重开保持一致。
- 26 文件格式：预算与采购各跑 21 个合成样本（19 个有效格式样本、2 个预期失败/拒绝样本），有效样本解析及业务断言均通过；覆盖 DOC/DOCX、PDF、PPT/PPTX、XLS/XLSX、文本/CSV/RTF、PNG/JPG/BMP/TIFF 和约 250 MB DOCX。损坏与超限场景分别按预期结束。
- 26 合同：合成合同由系统分类、项目归属并自动建档，五步审核 COMPLETED；报告生成并持久化为 AVAILABLE，报告清单读取和 artifact 下载通过（14,025 字节）。初次合成脚本漏了必需的合同类型字段，系统按规则保留候选、不错误晋级；补齐字段后正式链路通过。失败、UNKNOWN 和 PARTIALLY_COMPLETED 的报告范围/防误报仍由本地 `ReportSnapshotTest` 回归覆盖；26 现场此次直接审核为 COMPLETED，未在现场伪造模型或数据库故障来制造失败终态。
- 全部测试材料均为专门生成的合成数据；未读取或导出甲方业务材料正文、OCR 原文、prompt 或模型原始响应。现场真实材料准确率及甲方真实工作负载仍需另行验证。

## 2026-10-27 新自动案件交付记忆

- 分支feat/contract-auto-delivery-20261027保留累计Chrome109字体修复；预算/采购/合同最终真实验收入口为26独立18082，原80及原业务库未替换。不能将独立验收描述为默认入口升级。
- 离线制包必须显式传入已验收backend JAR路径，不再读取可能过时的backend/target；新采购四表和独立合同案件表必须包含在schema.json增量组中。启用新worker之前先验证这些表。
- 新五表采用限定CHECK语法，主键同名不是必要条件；DM8自动命名的inline主键按真实列及启用状态核对，不宽松跳过约束。首次/重复/代码回退后检查和实际漂移反例已在独立DM8验证，详见docs/delivery/PACKAGE_TESTS.md。
- HTTP非安全上下文下Chrome109没有crypto.randomUUID，上传操作统一使用已有安全createOperationKey/getRandomValues备用路径，不能改用Math.random；未决操作键必须保持不变。
- 正式更新需要现场数据库及文件备份恢复完成后的本地root0600恢复记录；--check不要求该记录并保持只读。记录及备份不进包、不进Git、不外发。记录校验不能冒充实际恢复验证。
- v11现场实际原因：`BACKUP_RECORD_UNREADABLE`诊断为`record: MISSING_OR_UNREACHABLE`。`--backup-record /root/audit-recovery.json`只校验现有文件，不会自动生成恢复记录；因此正式命令前先用`test -r /root/audit-recovery.json`检查是否已放置。记录必须来自已完成的DB和文件恢复验证，禁止虚填true或移除安全门槛。详见部署手册“v11 恢复记录阻断的现场经验”。该现场从本机SSH连接超时，实际修复/正式更新尚未验证。
- v12更新方式按用户明确要求收敛为一条`sudo bash update.sh`，不要求单独恢复记录，也不自动制作DB/业务文件备份。包保留包校验、真实DM8 Schema/部分迁移/UNKNOWN检查、活动任务门槛和失败停止；不能为“一次成功”跳过这些保护。部署器只暂存应用文件，DDL成功后应用回退不回滚DDL。包README必须在命令旁明确这个限制。旧v11不修改；版本使用Update_v12并重新生成manifest及SHA。
- 新采购自动入口尚未接入旧通用分片续传；新入口的黑名单/围串标/拆分采购未执行。不得把这些说成自动全项覆盖。

## UIFix_v3（2026-10-28）

- 用户要求每次修改更新包都使用不同文件名；使用递增/新修订名并生成对应 SHA，不覆盖之前已交付文件。本次修订包文件名：`UIFix_v3c.tar.gz`。
- 空白页根因复盘：`umask 077` 下使用 `cp` 创建新增静态文件会产生 `0600` 权限，Nginx worker 无法读取 JS/CSS；字体补丁 CSS 还曾引用错误文件名。UIFix_v3c 改为 `install -m 0644` 写静态资源，并修复字体 URL；v3a/v3b 不再用于部署。
- 包：`deliverables/UIFix_v3c.tar.gz`；SHA-256：`39d46d4b036de0f2097d5b3958182bef4abdb46dc5c951a91c1ddc0d0ae93531`。因现场入口不等于原 v12a 指纹，用户要求直接覆盖；更新器只校验包自身 manifest，然后直接覆盖 `/opt/audit-agent/current/frontend/dist/index.html` 和 `assets/uifix-v3/` 同名资源，不再因入口/旧资源 SHA、Nginx 或健康检查阻断，不制作回退副本、不重启服务。静态资源用 `install -m 0644` 落盘，不能在 `umask 077` 下用 `cp` 新建为 0600。Chrome 156 本机包预览：登录页正常、无资源 4xx 和 JS pageerror；认证 API 未连接属预期。Chrome 109 与甲方现场仍待验证。
- 合同自动分析结果页顶部显示项目摘要；正式八大项和问题确认只呈现与当前分析批次材料集合完全一致的持久化 ReviewRun。材料集合不匹配时不展示旧批次结论、不继续旧 Run。保留 UIFix_v2 已验收的 Chrome 109 字体/颜色补丁。
- 包基于 `a0a9cce974027810e44002dd1d316892ef0f0897` 加未提交工作树改动构建；包内明确标记 `WORKTREE_CHANGES=INCLUDED`。本地前端 `node --test`：844 passed；生产构建通过，存在既有 chunk-size/dynamic-import 警告；`git diff --check`、16 项包内 manifest、外层 SHA 与无包套包检查通过。
- 本机浏览器自动化使用 Chrome 156。Chrome 109 本轮未实机验证；26/甲方服务器未部署，`sudo bash update.sh --check` 与正式更新均 `NOT VERIFIED`。更新只改静态前端，不重启服务；现场执行仍须先核对 v12a 入口哈希。


## 2026-10-29 共用解析与八项展示部署补充

- 三个模块共用 material-parse-v4；合同八项展示与正式建档解耦。不要回退到各自旧解析器或删除正式审核/确认入口。
- 本次无数据库迁移，不新增 schema 判定、恢复 JSON 或备份证明前置要求；保留现场配置与业务文件。Excel 只读读取修复不需要 chmod 其他用户的 /tmp/poifiles。
- Vite 原始 dist 不能直接交付 Chrome109。继续经过 tools/build-procurement-browser.mjs 颜色兼容处理，并保留累计字体覆盖；从最终入口逐项验证脚本、CSS、字体及按钮。新静态资产目录使用 assets/update-v15，不能因同名相同字体文件阻断覆盖。
- 隔离发布源码、合并后源码均完成 clean verify；26与最终静态构建证据见 docs/unified-material-refactor/v15。发布状态以该目录最终验收记录为准，不把暂存或独立 QA 端口验收当成默认80端口升级。
- 最终26默认80入口已切换至 `/opt/audit-agent/releases/v15.material-20261029-r1`，backend/AI运行进程工作目录均核对到该目录。JAR SHA-256 为 `148c7768ac51ab778abd909d555e30874433064c5bfdb6b41ffa86e61944f0c9`，入口 SHA-256 为 `b58008636f69c2d00fd8a985ac5b99ab173cdff9717a5c335d9fc9ddb7ce30d2`；本地可复现候选归档为 `.runtime/v15-release-payload-r1.tar.gz`。未执行Schema变更，后端/AI配置哈希未变，v14原release仍保留。
- 最终 `clean verify`：隔离发布源码3376测试、整合源码3377测试，均0失败0错误24跳过；前端835测试、Chrome109真实生产构建回放，以及默认26登录、实际上传不完备合同、模型返回及重启后历史重开通过。QA真实模型验证预算PDF/DOCX/XLSX、采购预算/三报价/超预算、倒置/侧向及混合扫描、完整合同自动建档八项、缺字段和坏附件、正式五步/问题确认/八项HTML报告。详见 `docs/unified-material-refactor/v15/final-verification.json`。
- 八项的非合同材料标题必须称“材料名称”，不能把中标通知书显示成“合同名称”。跨文件输出仍存在证据未接受的PARTIAL/UNKNOWN部分，不得因能出八项就宣称每个模型判断已核实。甲方真实材料准确率和ARM64现场仍未验证。本轮未提交或推送Git。

## Update_v15 甲方更新包（2026-10-29）

- 交付 `E:/audit-agent-new/deliverables/Update_v15.tar.gz`，88,657,409字节；SHA-256 `0e8d57e70e737a5c63ddd49e3c92545ca6390842be07e39293a3091a73accce4`。解压进入含update.sh的目录，只运行 `sudo bash update.sh`，看到 `UPDATE=PASS` 后浏览器Ctrl+F5。
- 使用上节26验收的同一JAR、Chrome109-ready前端和AI源码，携带原有OCR方向模型；不携带JDK、Python、数据库等全量安装介质。保留现场配置、密钥和数据，无恢复JSON前置要求，不自动制作数据库/业务文件备份。脚本保留旧应用用于失败恢复。
- schema.json、SQL、检查器源文件/class和部署脚本与v14逐文件哈希一致，无新增迁移或判定；不因旧UI入口/资源不可读单独挡住修复。已有真实DM8首次/重复/DDL后回退/漂移反例是未变化基线证据，不冒称本轮新迁移实验。
- 从最终tar重新解压验证194项清单、包SHA、Java17 class、Shell LF和bash语法、Python语法、入口及字体引用、所有归档目录0755/文件0644（脚本0755）通过。日志 `.runtime/build-update-v15.log`。甲方ARM64现场执行仍为NOT VERIFIED；本轮未提交Git。

## 2026-10-30 最新代码同步 26 与 GitHub

- 最新验证入口为默认服务，release `git-sync-20261030-r1`；保留配置、数据与 OCR 权重，无数据库迁移，不制作新离线包。
- 本轮全量：Java 3445 tests/0失败/0错误/24跳过，前端846 passed，Python2049 passed/14 skipped/43 subtests；真实Chrome109预算分析及历史重开、采购/合同历史查询通过。
- 发布的JAR、前端入口和AI源文件按哈希核对，详见 `docs/qa-26/git-sync-20261030.md`。真实模型网关不可用，客户扫描件准确性仍待现场诊断，不宣称本轮已经验证。

## 2026-11-04 采购、预算、合同个人上传角色

- 三角色为 PROCUREMENT_USER / BUDGET_USER / CONTRACT_USER，显示采购/预算/合同；均可使用三个业务工具。文件、结果、原件及报告按账号 ID 隔离，同角色不同账号互不可见，混合旧角色不能绕过。管理员通过“角色授权”为已有账号追加角色，目标账号重新登录生效。
- 26 默认入口已更新至 personal-roles-20261104-r1。JAR SHA-256 a6b2e969411ba5797b32aa85219c13e0c6f1a63e215de0ad5463cc6ad05943a9，index SHA-256 b0e7885d4b3c5ccd378e6818d551d0413073e984dbe4b29cc7ac4e534c8769b3。保留原配置、历史数据和累计 Chrome109 字体/颜色兼容，不清库。
- 新增 V058__personal_upload_roles.sql 仅插入现有 SYS_ROLE 参考记录，无 DDL。真实 DM8 重复执行通过；以后制包纳入这三条参考数据即可，不新增表结构比较、备份证明或恢复 JSON 阻断。
- JDK17 clean verify 3484 tests/0失败/0错误/24跳过，前端849通过及customer构建成功。26 Chrome109四账号12份合成材料处理、84次跨账号读取拒绝、12次跨账号确认拒绝、同角色隔离、刷新历史、原件和HTML导出、管理员授权幂等/权限验证均通过。预算4/4 PARSED、合同4/4 COMPLETED且八项可读；采购使用合同材料，4/4给出材料不足报告，非报价审查通过。临时账号已停用，审计与结果保留。
- 详细证据见 docs/qa-26/personal-upload-roles-20261104.md。本轮未制作甲方包、未提交Git；甲方现场 NOT VERIFIED。

## 2026-11-04 角色维护方式收敛（替代上一节管理员授权方案）

- 用户明确要求：采购、预算、合同三个角色及账号关系在后端数据库维护，不增加管理员分配页面或快捷接口。已移除本轮新增 UploadRoleAssignmentPage、PersonalUploadRoleController 和导航；保留三角色、后台处理权限和按账号隔离。以后不要自动恢复这个授权页面，也不扩建用户管理。
- 维护现有 SYS_ROLE / SYS_USER_ROLE；角色关系修改后重新登录。示例SQL在 docs/personal-upload-roles.md；不改历史归属、不清理数据、不新增表或部署阻断。
- 26 当前版本 personal-roles-20261104-r2，JAR d769c9c8be2132a28c7bbc8ec100c1df513e94873de2bc2da9603d445b803e49，index b2b4c3b06cced2ee3d7085be7adfea4494fdddd6273d5e33d9e57f36a655ef4c。配置、历史数据、Chrome109修复保留。
- clean verify：3483/0失败/0错误/24跳过；前端849通过及customer构建成功。Chrome109管理员无授权入口、快捷接口404，四账号历史/八项/原件/报告及84次跨账号读取、12次跨账号确认拒绝复验通过；复用已保存结果未重跑模型，临时账号已停用。详见 docs/qa-26/personal-upload-roles-r2-20261104.md。本轮无甲方包、无Git提交；甲方现场 NOT VERIFIED。


## 2026-11-04 合同材料语义归属与 OCR 误报修复

- 26 当前 release 为 contract-semantics-20261104-r4，JAR c9f3e9a47ddd5ff9a97ef0da0c6f5e4ff95fc401796f1d319e25d39dc1edbd2c，前端入口 6d18781c4431ee0a22a408d0ed42eca6fd13946929f860e0556a052901d634c7。保留个人上传权限、Chrome109适配、现场配置和四路OCR；无DDL、清库或恢复证明前置要求。
- 同批次属于一个项目，不表示历史业绩合同属于当前合同。投标、预算、中标、合同金额分清；采购人、代理、投标人与签约甲乙方分清。语义过滤必须同时覆盖规则召回、模型引用核验、事实发布及报告，不能只隐藏页面。
- 主体全半角和空白等价可合并并保留来源；不模糊合并不同公司，不强制只留第一位。明确共同签约时保留真实多方；共同各执份数仅按原文证明的不同签约方人数累计，并保留人数及份数两类来源证据。未知人数不猜算。
- 文件名、头部及章节只作有界模型背景，不作当前分段引用。项目背景从冻结原文取，不能用可变展示名称影响事实。表头单位与单元格数值可用BigDecimal核验精确等价，不能容忍单位或数字子串变化，更不能接受模型重排或编造引用。
- OCR大写金额疑似错字需从原像素复读，不按猜字修改金额；识别疑点不等于已证实合同错误。服务期限至某日应识别结束节点；无效及暂定日期不能当有效日期。
- 最终Java17 clean verify 3511项，0失败、0错误、24跳过；167项定向回归通过；26 Linux相关Python89项通过。默认入口Chrome109三组正式上传通过，真实服务合同八项均有内容；多方签约无份数/期限误报。全量构建的1927个应用文件与运行包逐字节一致。
- 274页历史任务未重新全量调用模型，仍保留真实PARTIAL状态；旧持久步骤指纹不变。只读展示修正不得伪造全文完成。甲方ARM64现场NOT VERIFIED。本轮无Git提交、无甲方包；详细证据见 docs/qa-26/contract-semantics-20261104.md。


## 2026-11-05 采购结果与原文查看

- 默认26：procurement-results-20261105-r1，静态修订 procurement-results-20261105-ui3。JAR 7e64c2ccdbe0c45810823e9ea6f7e1c04c0c7c4c76eeeae46a07190e69be55c9；入口 a8c62140e0ba781bee9d9dad12174f0a77f317b2348adc371c3f6000eedc667ab。无DDL，配置与四路OCR保留。
- 采购顶部不再显示三个金额槽位；先问题项，材料要点置末尾折叠；保留金额与证据。原文查看共用合同高亮弹窗，只按冻结来源哈希、原位置、唯一逐字引用定位，不能用其他页相同词语冒充。正文14、条目15、标题16、来源13px。
- v7金额结论区分印列合计与系统加总、年度与整体范围；API/HTML统一过滤无预算或未完整核算时的财务通过结论。一般付款约定不直接等于具体比例冲突。原始报告及UNKNOWN提取账本不覆盖、不自动重发。
- Java3575/0失败/0错误/24跳过，前端863、26 Python372、共用Chrome109原文浏览器5项通过。默认入口Chrome109旧/新实际批次、原文2/3处高亮、复制互斥、焦点恢复、三宽度、HTML200、历史重开通过。
- 最后仅前端改动时发现合同活动任务，改为验证JAR/AI源未变后静态资产先落盘、入口原子替换，不重启服务、不干扰活动任务。别因UI补丁强制等待无关后台作业结束。
- 真模型新验证批次00223281-1d5b-3982-a424-2f85f5d2d30f：保留计价范围关注与实际履约说明，不再报一般/具体付款冲突。复用已保存部分原文，不冒称274页全文提取完成。详见docs/qa-26/procurement-results-ui-20261105.md。本轮无Git提交、无甲方包，ARM64现场NOT VERIFIED。


## 2026-11-05 三模块结果页隐藏上传入口

- 合同自动分析按 selected 状态隐藏顶部上传材料（包括结果读取中及分析中），返回分析记录后恢复。采购 !current && !historyOpen、预算 !historyOpen && activeStep===0 原有条件已满足，不改业务流程。
- 26 静态修订 result-upload-entry-20261105-ui1，index SHA 396f025450b0ee3fac2b01b97b34327403c5ca84803556d24a2232087daa4ad4。仅静态资源更新，不重启服务、不改数据库。
- Chrome109合同/采购结果无文件输入且返回后恢复通过；预算历史无上传入口、返回首页恢复通过。当前账号无预算历史记录，预算结果实机未验证，源码守卫已检查。前端863测试/customer构建及JDK17 clean verify3575项0失败0错误24跳过通过。证据 .runtime/result-upload-entry-20261105/。未提交Git、未制甲方包。

## 2026-11-06 个人账号功能一致与原文弹窗布局

- 个人角色 PROCUREMENT_USER / BUDGET_USER / CONTRACT_USER 只区分上传数据归属，不裁剪业务菜单、工作台工具或统计。test_admin、caigou、yusuan、hetong 均使用相同六个业务入口。不得通过追加 SYSTEM_ADMIN 修复页面缺项，不恢复管理员授权页面。
- 放开工具入口时，任务统计/风险读取在分页和计数前按项目持久化上传者筛选；黑名单报警的查询、分页、导出、明细和处理按采购批次归属限制。共享黑名单参考数据及规则保持共享，不修改历史归属，不清库，无新迁移。
- 原文弹窗的标题和正文必须是 flex 纵向兄弟区域。Header 不收缩，Body flex:1/min-height:0/overflow-y:auto；定位高亮只更新 Body.scrollTop，禁止 scrollIntoView 带动外层滚动。不能恢复单一滚动面板+sticky 标题的旧布局。
- 默认26已切换 account-parity-20261106-r1，JAR d9ffca7ebe8c1761ecaf35217ac433db166ca74c2dd1d50d35a05901068446f2，index cefb3fb2005c6b5852680c91624a2ccfcc78f4b2998ae9482281bd450002f057。等待实际采购任务自然结束后切换，仅重启后端；配置、AI服务、历史数据保留。发布文件哈希及8001/8080/80健康验证通过。
- JDK17 clean verify 3581项/0失败/0错误/24跳过，前端863项及customer构建、Chrome109原文弹窗7项通过。26 Chrome109四账号六页、统计/黑名单查询与导出HTTP200；三账号9份合成材料完成：预算3/3 PARSED、合同3/3 COMPLETED且八项可读、采购3/3使用合同材料生成材料不足报告。三模块本人历史、结果重开、原件/报告下载通过；42次跨账号读取/6次跨账号确认全部404。证据 verification.json=PASS，详见 docs/qa-26/account-parity-20261106.md。
- 26原文弹窗真实保存结果3处高亮，三视口高度/顶部中部底部/关闭重开/焦点恢复通过。证据 .runtime/account-parity-20261106/。本轮无Git提交、无甲方包；ARM64现场NOT VERIFIED。

## 2026-11-06 预算整批语义审查

- 默认26为 `budget-batch-20261106-r5`。JAR `a295daac714330205ad53f2846262b6c88738e8d173abb23103bc1b2bd9c4585`，index `6e8471bb5b1b64ba6fbdfc23674edff23b304ff109444ae999ad3a94eb1c15c0`。配置、数据、账号隔离、Chrome109、原文弹窗及OCR资源设置保留；无DDL，不新增恢复证明或备份阻断。发布前等待预算批次等活动任务自然结束，不能只检查单文件任务。
- 一批为一个项目，结果按五个固定业务模块归并；原文及精确引用冻结。项目预算、申报合计、批复总额、限价、年度/整体、历史参考分开。金额依旧BigDecimal，模型只作原文语义理解及有依据的问题建议。100万元含边界只提示额外审批，不武断认定漏审/拆分违规。
- 预算共用提取接口必须保留subjectScope/amountScope；提示协议也必须明确要求输出范围，不能以“提到了金额”代替整体金额识别。小批次一次综合不重复调用；部分失败不抹去有效结果、不显示整批通过。复用现有提取账本，无新增表。
- Java17 clean verify3591项，0失败/0错误/24跳过；前端865项、customer构建及共用Chrome109弹窗7项通过。AI定向54项、扩展80项通过（另1跳过）。26多轮10类合成场景包含超支错支漏项、用途冲突、审批条件、大写100万元边界、年度区分、损坏混传、多格式、非固定表述120万元及历史300万元排除，业务断言PASS。Chrome109上传自动结果、复制/高亮/四表导出/窄屏/跨账号原文404通过。
- 详细证据与边界见 `docs/qa-26/budget-batch-semantics-20261106.md` 和 `.runtime/budget-batch-20261106/`。本轮未提交Git、未制甲方包；甲方ARM64与实际客户材料NOT VERIFIED。不得以本次合成测试宣称任意长扫描件已全文核对或语义结果必然正确。

## 2026-11-09 预算追加场景复查

- 默认26更新为 `budget-batch-20261106-r7`，JAR `4f7d5fb78f6d617c9f66276cfab6ef8ee329052948f35d15dd29c77b2fa780b5`，index仍为 `6e8471bb5b1b64ba6fbdfc23674edff23b304ff109444ae999ad3a94eb1c15c0`。无DDL，配置/数据/OCR/账号隔离保留，DM8活动任务归零后切换；8001/8080/80、工作目录及发布哈希验证通过。
- 历史金额明确归属必须贯通规则与模型合并，不能只在提示中排除；“旧设备”或“不是历史”不是历史标签。审批完成助词规范仅作用在审批动作之前，不删除否定词。单长文件多段也需有界综合，不能按文件数判断是否需要跨段理解。
- 审查协议v4：编号冲突必须有两处完整编号证据；扫描O/0易混淆不发布已证实业务错误，但不覆盖原文，也不隐藏其他真实不同编号。拒绝模型候选保留PARTIAL，不能冒称全文通过；历史持久结果不改写。
- backend Java17 clean verify3596项，0失败/0错误/24跳过；Python定向68项通过。26两轮17类23份合成矩阵、最终6批7份真实模型复测、Chrome109正常折叠/金额归属/原文高亮/三宽度通过。旋转90/180度均提取20万元整体预算及31200元申报，长文15448字能关联批准与实际用途。
- 证据及具体边界见 `docs/qa-26/budget-extra-scenarios-20261106.md` 和 `docs/qa-26/evidence/budget-extra-20261106/`。本轮无Git提交、无甲方包；客户真实材料和ARM64现场NOT VERIFIED，不增加部署阻断。

## 2026-11-09 原文件证据预览

- 默认26发布为 `evidence-original-20261109-r2`，JAR `d840b3e23246670ea1c7c3e6259da79bc99e4cc06e89d5c988671b71d2abf2d8`，入口 `2bda2d8dc1169828d962f056ed3c8d10d25b9ddb5877798f2f14efbaa4453242`。实际DM8活动任务归零后切换，重启前再次检查；无DDL，现有配置、业务数据及四路单线程OCR保留。健康、运行目录和全部静态资源哈希通过。
- 三模块查看原文默认展示原PDF/图片真实像素与坐标覆盖层，可切换解析文字；无可靠坐标只展示原页，不猜测高亮。坐标是既有FileStoragePort中的派生不可变索引，绑定文档/原件/冻结文本身份。历史结果无索引不自动重做OCR；Word暂不支持原页，仍可查看解析文字。不得为此加数据库迁移或旧更新包依赖。
- OCR低置信金额/日期最多复读两处/页，不一致内容不作为已证实业务缺陷；不是所有识别错误都能检测。原页GET只读、验证权限与原件SHA，临时渲染并发2、限制像素，不调用模型。日志只记数量、阶段与状态，不记正文。
- Java17 clean verify3642项，0失败/0错误/24跳过；Python全量2229通过17跳过，图像几何专项49通过，26原生专项66通过12跳过。26真实OCR四方向4页27区域38.99秒；三模块13页定位与Chrome109原页/文字/重开/两视口边界通过，异账号6接口404。
- 采购章节误排除修复：否定/要求句不是历史章节标题。新批次383/383字送入模型，当前报价230万元与预算220万元/限价210万元比较正确，历史380万元排除，品牌/地域/授权风险有实际意见。单家询比价不足、合同PARTIAL及预算合并PDF的既有语义边界如实记录，不能把坐标验收等同所有审查结论正确。
- 详细记录 `docs/qa-26/original-evidence-20261109.md`，合成样本 `test-materials/original-evidence-20261109/`。甲方ARM64和客户真实文件NOT VERIFIED。本轮未提交Git、未制作甲方更新包、未关机。

## 2026-11-09 GitHub前端融合遗漏修正

- 原融合截至41c60c5e，漏了远端feat/client-review-perspective的104a6c90和0f61b868。已三方融合15个文件，保留本地预算批次报告、合同八项、采购问题优先/去顶部金额和三模块原文件高亮。不能将远端较旧文件直接覆盖本地后续业务成果。
- 26默认80静态修订github-ui-20261109-r1，入口SHA256 ad893a25fd920ce55a9526c82eba24261ece5cfa1dfd3b170a435447c50790ad；release仍evidence-original-20261109-r2，backend JAR及AI不变。资产先落盘、入口原子替换，旧资产保留；进程PID/配置/AI源码哈希核对未变，无DDL或服务重启。
- Java17 clean verify3642项/0失败/0错误/24跳过；前端865项、Chrome109组件16项与customer构建通过。Chrome109实际连接26验证三模块既有真实结果/原页高亮/文字/重开/弹窗边界、合同八项；只读GET模拟处理中三模块统一透明无边框状态，未完成结果和材料操作隐藏。没有重新调用模型，不能冒称本轮重验语义结果。证据docs/qa-26/github-ui-sync-20261109.md。
- 后续“融合GitHub并部署”：先刷新远端并列出上次截止提交后的差异；清单分别记录baseCommit、integratedRemoteCommit、实际源码哈希、入口SHA。部署后核对26默认80的实际HTML及全部资产，再实机看对应功能。不能以拉取/构建成功代替部署成功，不能只凭后端baseCommit判断前端版本。本轮未提交Git、未制甲方包。

## 2026-11-09 关键原文片段

- 三模块相关原文默认展示短证据，每段上限220字符，按对应问题选择原引用中的连续句子；完整引用核对成功后才派生短句偏移。不要恢复默认整页大面积标黄，也不要用模型重写当作原文。“查看原页”保留真实像素及短句位置高亮；完整引用/解析文字仍可展开核对。原报告与原引用不改。
- 26静态修订concise-evidence-20261109-r1，index SHA256 49a7f66ac0cbf01b858a866b44e91051bf175067fcceacbff5933d708ecbd643。默认80资源逐项验证；backend/AI/配置及PID保持，无DDL或重启。
- Java17 clean verify3642项0失败0错误24跳过；前端870项、customer构建、Chrome109浏览器17项通过。26三模块短句/原页高亮/文字/重开/八项/边界及用户长投标文件529/341字引用缩短实测通过。没有重跑模型，取句是展示启发式，不能宣称已重新审核历史结论。证据docs/qa-26/concise-evidence-20261109.md。无Git提交、无甲方包。

## 2026-11-09 原文交互收敛：只给原文件证据页

- 替代上一节的默认短句及多模式切换。三模块查看原文直接展示证据所在原文件页面和关键位置高亮，不再提供关键片段/原文件/解析文字/完整引用选择。仅实际证据页可翻页，例如2→9，不得补齐中间页或默认浏览整份材料。无证据页不猜第一页；Word等原页尚不可预览的格式仍自动回退短文字，没有新增转换器。
- 26静态修订evidence-pages-20261109-r1，index SHA256 5eb1fc66148cba1f54e8dd7ca2a752e2421a3ae29a3032b7a0f05e39751bfe37。原backend/AI/配置/PID保持，无DDL或重启。默认80资产哈希、健康核对通过。
- Java17 clean verify3642项0失败0错误24跳过；前端872项、customer构建、Chrome109浏览器19项通过。26三模块直接原页、高亮、重开、标题边界、合同八项及274页投标文件仅9/11证据页实机通过。详见docs/qa-26/evidence-pages-20261109.md。本轮无模型重跑、Git提交或甲方包。

## 2026-11-09 三模块历史页方案C统一

- 使用公共HistoryFilterBar/HistoryList/HistoryRow/HistoryFooter及history-results.css，统一日期、关键词、查询/重置和紧凑纵向列表；不增加装饰图标，不改变顶部导航。预算保留材料名称查询/批次/年度/部门/20条分页，采购保留项目材料查询及游标分页，合同保留项目名/关注数量/改名和15条分页。输入与应用条件分开，重复查询可重试，采购旧分页返回不能混入新筛选。
- 26默认80修订history-ui-20261109-r2，入口SHA256 a5820e3b0b6f1cc2787a462ceb7690d6162addb0d56de3cd4466e167455b6f68。仅静态更新，原JAR/AI/配置/PID保持，无DDL和重启。不要重新部署旧前端覆盖此修订，也不要恢复表格/双列采购卡片。
- JDK17 clean verify3642项0失败0错误24跳过，前端872项、customer构建和Chrome109浏览器29项通过。26真实三页筛选/重置/跳转、预算合同分页、四宽度同边距及无横溢、合同八项/原文件证据高亮重开通过。采购实际8条无游标，多页场景由专项夹具验证。详见docs/qa-26/history-ui-20261109.md。本轮无Git提交、无甲方包，甲方实机NOT VERIFIED。

## 2026-11-09 历史页状态与分页修正

- 替代上一节状态和合同改名要求：三模块历史记录统一“正在分析/已完成”，PARTIAL/DATA_INSUFFICIENT是结果覆盖差异，不能显示为任务“证据不足”。真正FAILED/UNKNOWN显示“已结束”，保留内部状态和报告，不伪造分析成功。合同不再提供修改名称。
- 共用HistoryPagination，采购游标改页式浏览（上一页/数字/下一页），只有一页也要显示，未知总数不虚构。筛选/账号变化重置游标，阻断迟到响应，当前页按钮禁用避免卡死。预算结果读取6路有界GET、即时项目/进度、退出取消，不得把读取历史改成重跑模型或增加OCR线程。
- 26默认80修订history-polish-20261109-r1，入口SHA256 98013176d1aceb4bb8aa4b14157628648f6b643ef3fa479cca2c8422f37d8d99。仅静态，配置/JAR/AI/PID保留，无DDL/重启。Java3642项0失败0错误24跳过、前端875项、Chrome109浏览器31项及customer构建通过，真实三模块/分页/八项/原页高亮通过。当前活动采购任务不打断。
- 预算12份批次旧版13个GET、4路3轮；正常网络约259ms，未复现稳定高延迟。250ms受控响应延迟下新版6路2轮缩短网络关键路径，但整页耗时无稳定改善证据，不夸大提速。详见docs/qa-26/history-polish-20261109.md。无Git提交或甲方包，甲方实机NOT VERIFIED。

## 2026-11-09 Word与Excel完整原文

- 替代旧“Office自动回退短引文”：三模块共用OfficeEvidencePreview。DOCX读取原件排版页、DOC用既有POI原生HTML布局，Excel读取原工作簿完整证据工作表；仅证据页/工作表可切换，不恢复模式选择和全文件浏览。Word不是Office固定分页像素，Excel不是打印纸分页，不得冒称完全一致；复杂浮动、图表、超大文件边界见QA。PDF/图片原像素展示保留。
- 冻结引文偏移校验及原件唯一文字匹配后才高亮；不改OCR错字/标点/金额，重复或对不上不随意标第一处。Excel必须保留sheet/row结构定位，无定位只允许全工作簿唯一匹配。浏览器隔离原件样式/滚动，禁止脚本/外链/远程资源，本地中文字体保留；关闭取消读取，无新模型/OCR调用。原件接口保留原账号隔离和SHA核对，不增加DB迁移。
- 26发布office-evidence-20261109-r1，静态修订office-evidence-20261109-r2。JAR9988415b91b84ca5f5865804b9a37a9e8c4e2bab82a3c224269fbd18fcbc6cf2，入口36d1de3b61d7044984fb2499b8b42dd71755a5c67cbe6d5e6b40121725761a4a。真实DM8任务归零且停backend后复查再切换，AI未重启，配置/数据/AI源码保留；默认80全部9资产哈希通过。GitHub融合截止0f61b868保留，不回退三模块历史页和统一状态。
- Java17 clean verify3644项0失败0错误24跳过、前端875项、Chrome109三套36项通过；最终行号列宽修正后原文18项复测通过。26合同Word、预算Word、采购Excel和预算Excel四个真实结果路径上下文/高亮/边界通过；6个跨账号接口404；三模块PDF/合同八项/重开回归通过。详见docs/qa-26/office-evidence-20261109.md。未重跑历史模型，甲方ARM64/真实客户材料NOT VERIFIED；本轮无Git提交或甲方包。

## 2026-11-10 三模块托管测试复查

- 26默认80 Chrome109真实历史/结果页面回归：预算、采购、合同筛选/重置/分页控件/结果打开，四个分辨率无横溢；结果页上传框隐藏、状态文案正确，合同八大项保留。三模块原文证据和跨账号404复测通过。
- 本轮无产品缺陷复现，无代码发布或后端重启；不为测试而改逻辑。采购真实历史共10条，因此本轮未验证真实跨页结果；前端分页交互由此前专项夹具覆盖。既有测试Excel的采购状态DATA_INSUFFICIENT、预算REVIEWED；状态差异不影响报告打开，也不代表采购审查结论充分。
- 证据见docs/qa-26/office-evidence-20261109.md的2026-11-10节、`.runtime/history-polish-20261109/live-history.json`、`result-workflow.json`。本轮不重跑模型、不清理数据、不做客户包；甲方实机NOT VERIFIED。
