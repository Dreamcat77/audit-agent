# 现场合同问题优化与26验收（2026-11-09）

当前26运行 `contract-customer-20261109-r4`。本轮使用获准仿真材料和合成材料，走正式上传、持久处理、八项报告及Chrome 109页面；未连接甲方内网。OA接口未接入，也不作为处理前提。没有新增人工分类、项目确认或合同填表步骤。

## 改动及实际结果

- **项目名称和主体**：支持跨行、表格及非固定项目名称。角色标题、单位名称占位和空栏不再冒充签约主体；有原文定义的全称/简称归并，主管、盖章备注参与清理。历史业绩不能覆盖当前合同，保密等配套协议主体单独保留。处室与机关名称不同本身不能证明主体无资格；真实多方签约和真实条款冲突仍保留。
- **日期及原文**：空签约日期定位到实际空栏，不再被另一处完整日期掩盖。修复日期检查被通用“日期与终止条件”标题误过滤的问题。原文引用继续使用冻结文件、范围及UTF-16位置，未改写历史正文。
- **金额**：按局部价款及金额角色配对大小写，Java BigDecimal核算。10000/壹万通过，另一处20000/叁万报差10000；500元保证金不混入合同价，也不进入该问题的高亮。中标供应商上下文不再被“供应商”短标签截断；新上传合同+中标通知书正式提取13项事实、11条条款，中标供应商/中标金额核对均PASS。
- **重复及误报**：同值事实合并来源；问题按业务范围和证据归并，保留不同事件、责任方和金额。OCR乱码疑点不能直接认定合同条款有错。材料已内嵌附件时，不采纳单块模型“本批未上传附件”的断言；附件覆盖仍由实际冻结材料核对。
- **读取及观测**：Word按真实DOC、OOXML、RTF或HTML容器选择解析器，HTML不访问外部资源。共享读取器增加OCR排队计数，合同增加候选/接受/原文核验拒绝计数。诊断导出采用白名单，不导出正文、名称、金额、模型原始回答或凭据。

主要源文件包括 `ContractPartyName`、`ClientPartyMatcher`、`ContractFactConsolidation`、`ContractAmountPairs`、`ContractAnalysisRules`、`ContractAnalysisSemantics`、`ContractFindingSupport`、`ContractAnalysisEvidence`、`ContractAnalysisReport`、`ContractResultConsolidation`、`ProjectNameResolver`、`WordContainerFormat`、`OfficeDocumentTextReader`及Python `contract_analysis.py`、`client_party.py`。新增 `ContractCustomerRegressionTest` 等反例回归；本轮保留现有前端，未重排八项页面。

## 26业务验收

执行 `.runtime/contract-customer-20261109/accept.py r2`、`accept.py r3 local-prices aliases`、`accept-r4.py r4 award-supplier`，通过维护会话将应用凭据仅在内存传递。每次上传保留操作身份和回执，不重复发送结果不明的请求。结果由正式API读取，再用 `refresh-final.py` 只读核对最新投影，执行 `audit-results.py final-audit` 检查业务预期。

15批覆盖正常配套材料、空乙方、多处金额、简称备注、日期空栏、历史业绩、配套协议、角色占位、4页四向扫描、12页混合扫描、真实仿真DOCX、HTML导出DOC及相同合同三次处理。最终15批均可重开八项报告；14批八项有内容，简短HTML材料5项有内容、其余槽位保留。全部已发布引用逐字及位置核验通过，历史case快照前后不变。三次重复的项目、甲乙方、合同金额和F02结论一致，模型措辞及条款数量仍可不同。

最终复查中5批为PARTIAL、10批为COMPLETED。PARTIAL包含已丢弃的不可靠模型摘录，保留已核实内容；八项可展示不等于全文审查完成。中标供应商专项在最终r4新上传，COMPLETED、8项均有内容、71处引用通过。没有通过改历史步骤状态、重发UNKNOWN、删数据或清库取得通过。

业务计数证据见 [business-verification.json](evidence/customer-contract-20261109/business-verification.json)、[projection-verification.json](evidence/customer-contract-20261109/projection-verification.json) 及 [award-verification.json](evidence/customer-contract-20261109/award-verification.json)。测试正文和原始模型回答仅留受控测试runtime，未复制到这些证据中。

## OCR、构建及浏览器

- 26实际日志观察到4个OCR_CALL重叠；每个进程1线程，MKLDNN关闭。4页四向扫描读取27.828秒，12页混合扫描72.360秒；16个扫描页均返回文字，包含合同金额、主体、末页及附录。模型确实继续产生事实和条款，不以OCR成功代替模型验收。性能数据来自合成样本，不能外推到现场复杂36页材料。
- Windows JDK17，`backend/ .\mvnw.cmd clean verify`：3617项，0失败、0错误、24跳过；最终完整构建耗时6分16秒。现场专项20项通过。26 Python3.11相关回归300通过、12跳过；本机focused193通过、expanded84通过4跳过，诊断测试分别14及13通过。未安装/升级服务虚拟环境依赖。
- Chrome109实际连接26，8项正常折叠、风险展开；结果页没有上传入口；原文高亮仅覆盖目标价款；1000/720/560三种视口高度的正文不越过标题；关闭重开、历史重开无脚本错误。复制使用浏览器原生execCommand成功并取得含金额/文件名的选中文本；headless未验证桌面操作系统剪贴板。截图见 [结果页](evidence/customer-contract-20261109/browser109-result.png) 和 [原文弹窗](evidence/customer-contract-20261109/browser109-evidence.png)。

完整计数见 [build-and-regression.json](evidence/customer-contract-20261109/build-and-regression.json)、[browser-verification.json](evidence/customer-contract-20261109/browser-verification.json) 及 [sanitized-metrics.json](evidence/customer-contract-20261109/sanitized-metrics.json)。

## 发布及边界

26目录 `/opt/audit-agent/releases/contract-customer-20261109-r4`；JAR SHA256 `dd885e7a61ee1cc9315f84a4dda6edbdfc71768bae538aacaffd2e309e0ff1ee`；前端入口SHA256 `6e8471bb5b1b64ba6fbdfc23674edff23b304ff109444ae999ad3a94eb1c15c0`。真实DM8活动任务为0再切换，终态UNKNOWN的不可领取子任务不重发，对抗SQL检查通过。无DDL；数据库、Redis、AI、Nginx及现有主体配置保留。8080/8001/80健康、实际进程目录、发布文件哈希、全部前端资源校验通过。

维护脚本必须使用 `/opt/python/venvs/audit-ai/bin/python`。一次错误使用系统旧Python的候选切换已自动回滚，随后r2/r3/r4使用正确运行时完成发布；不要替换系统Python或升级现有OCR依赖。此次为26增量发布，不是甲方更新包。

尚未验证/未实现的边界：甲方ARM64、现场模型和复杂36页实物 **NOT VERIFIED**；通用付款乱码的自动区域复读未新增，仅复用既有金额精度复读并对不可靠意见降级；模型处理仍受既有单worker限制，多批同时上传总排队可达27—32分钟，本轮没有扩大模型并发，不能把页面OCR耗时当完整报告耗时。二进制DOC的现场具体失败样本仍需诊断，当前新增真实容器兼容不代表所有受保护/损坏文件可读取。

可直接复测的纯合成材料位于 [test-materials/contract-customer-20261109](../../test-materials/contract-customer-20261109/README.md)，每个子目录为一次批次。未提交Git、未制作甲方包、未关机。
