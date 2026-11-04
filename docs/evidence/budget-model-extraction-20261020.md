# 预算材料部分提取及真实结果审核

范围：预算材料读取、真实模型提取、原文核验、批次归集、结果持久化和展示。基于 `fix/budget-procurement-ready-20261019` / `4efea2f`，本轮修改尚未提交、融合或发布。采购和合同不采用预算的部分放行策略。

## 实现边界

- 自然语言、非标准表头和扫描文字交给模型理解，已明确的标签和金额列保留原生精确读取。数量、单价等已知辅助列不重复提取科目金额；备注和未知列仍进入模型，不能漏掉其中的预算说明。
- 预算候选逐条核对原文，排除无法证明的字段，保留其余独立可靠明细。错误身份字段仍隔离对应模型行，不能把金额安到别的项目或科目。
- 同一物理行可用独立唯一引用消除重复短摘录歧义；扫描纵向表格以唯一科目锚点和有界原文范围定位，不能只凭模型 rowKey 拼接跨项目字段。相同金额出现在不同位置不会被当作同一证据删除。
- 金额角色、单位、数字边界仍核验；金额计算和差额比较使用 BigDecimal。项目控制总额、合计、单价不当作科目金额。
- `ExtractionIssue` 随 Snapshot 和提取 ledger 保存，旧 JSON 缺字段兼容空列表。检查点只保存核验后的候选和完整性警告；成功单元恢复不重新调用模型。UNKNOWN 仍不得盲目重发。
- 任一文件失败或存在未核实信息，本批不能据缺失推断错支/漏项，不能给出整体“未超过批复”的干净结论；有明确证据的超支仍展示。
- 年度仅归一化 `2026` / `2026年` / `2026年度` 这种等价写法，不混用不同年度。相同项目的两版不同批复继续报告冲突。
- UI 沿用既有页面布局和自然语言说明；技术失败不冒充材料不足，不把 UNKNOWN 或 FAILED 改成成功。

## 隔离真实模型实测

在 26 代表测试机的独立候选目录、回环端口运行 Python 候选服务。真实调用该环境已有模型，真实读取 Office 文件和已有 OCR；Java 使用候选 Reader / Analyzer / HTTP Adapter，ledger 为隔离内存实现，业务数据库写入为 0。正式 release 链接未替换，原 AI 健康检查 HTTP 200，候选服务验收后停止。

材料为明确的仿真数据。先取得真实模型响应，再使用这些已确认响应核对修订后的 Reader / Analyzer；没有为了重跑 Java 规则而重复发送模型请求。扫描复测复用本轮实际 OCR 的原文，重新调用模型；报告逐份标注原文和模型结果的复用情况。原文 SHA 核验，未手改模型字段、金额或最终结论。

`deliverables/Budget_Model_Review/预算实测结果.html` 是 14 组场景、17 份次材料的审核报告，可展开读取原文及已核验模型字段。对应 JSON 仅含仿真材料。结果包括：

- 自然语言两文件：申报 120000、批复 100000，提示超出 20000；来源同时关联两文件。
- 财务大写及角分：申报 123456.78、批复 100000，提示超出 23456.78。
- 同名科目不同项目：甲 9000 / 10000；乙 12000 / 10000，乙超出 2000，未串项目。
- 仅申请：保留 48000，说明缺少批复；无预算通知不编造金额。
- 两版批复：100000 与 150000 保留为冲突，不选择较大额度宣称通过。
- DOCX：168000、27200、16800 三项申报保留，年度和部门没有被相邻标签吞并。
- XLSX：四项完整读取；120000 对 107100 超出 12900，其余三项未超过对应额度，无重复核验警告。

### 尚未完整识别的真实观察

- 扫描 PDF 三项中保留 168000 和 16800 两项；27200 所在科目名称错位，仍未核实。不能称为三项完整提取通过。
- 自然段落 PDF 的一项费用名称被换行拆开，仍有信息未核实；项目总控制额度未强行分摊到各科目。这是剩余能力边界，不作为完整比对通过。
- 旧 XLS 两张工作表包含同样明细，尚不能证明是重复副本还是独立支出，保留不确定性而不直接相加。
- 混合 PDF 和图片 OCR 存在 O / 0 项目编号差异，未自动修正归属；重复页也不自动当作新增申报累加。
- DOC 和图片样本实际只有 31200 申报，没有科目批复；已按原始 fixture 生成代码核对，修正了最初测试清单误写的 32760 批复预期。未修改原材料和提取结果。

原四份行定位失败材料另做 DM8 只读重放，复用 COMPLETE 候选与原文件 fingerprint，不调用模型、不写业务。四份均保留部分信息，每份仍有一类未核实信息；其中可靠金额结果行数分别为 1、4、4、4。旧任务和旧结果未被改写。另两份缺少已确认模型结果，不盲目重发。

## 可复核命令和观察

- Windows / JDK 17：`cd backend; .\mvnw.cmd clean verify`，2437 tests、0 failures、0 errors、2 skipped，BUILD SUCCESS（04:21）。日志 `.runtime/budget-partial-clean-verify-final-accepted.log`。最新“备注仍交给模型”的修改包含在该次全量回归中。
- Python 3.11：`.runtime/material-python311/Scripts/python.exe -m pytest services/audit-ai/tests -q`，554 passed、1 skipped；现有 Starlette/httpx 弃用警告 1 条。日志 `.runtime/budget-partial-python-release.log`。
- 前端：在 `frontend` 运行既有全套 Node 测试，748 passed；`npm run build` 通过。日志 `.runtime/budget-partial-frontend-tests.log`、`.runtime/budget-partial-frontend-build.log`。
- `node tools/verify-budget-model-acceptance.mjs deliverables/Budget_Model_Review/actual-results.json`：列明金额、项目隔离、版本冲突、部分结果安全性断言 PASS；同时输出扫描 3 项只确认 2 项，不以断言通过冒充全部识别完整。
- `node tools/test-budget-model-results-view.mjs <Chrome109 executable> .runtime/budget-partial-browser-dist deliverables/Budget_Model_Review/actual-results.json .runtime/budget-model-review/browser-final`：Chrome 109.0.5412.0，17 份次实际结果渲染，页面异常 0。HTTP 为携带真实模型/Analyzer 结果的模拟 API，不是已部署后端的正式 HTTP E2E。
- 审核 HTML：Chrome 109 实际打开，14 节、17 份次，1440 宽度没有横向溢出，原文展开可用；产品截图与报告截图已查看。

NOT VERIFIED：甲方 ARM64 和本地模型、26 正式服务发布后的完整 HTTP/DM8 流程、所有未知文件格式的完整识别。剩余 OCR / PDF 识别问题如上，不能宣称系统已能无遗漏处理任意文件。本轮未打甲方更新包。

## 结果容器样式小改

`BudgetFileResults.tsx` 为新旧结果分支统一添加容器；`budget-results.css` 使用固定 sRGB 白底、浅边框、16px 圆角和响应式内边距，标题区添加分隔线。结果内容和行为不变。审核报告生成器同步增加文件结果边框。

`cd frontend; npm run build` 通过。兼容产物 `.runtime/budget-result-panel-dist` 经真实 Chrome 109.0.5412.0 验证，17 份次实际结果渲染和既有状态页检查均通过，page errors 为空。产品截图 `.runtime/budget-result-panel-browser/product-0.png` 已查看，标题、导出、说明和红色风险明细位于统一外框内。审核 HTML 的实际计算边框为 `1px solid rgb(219,226,234)`、圆角 `16px`。未发布到 26。

按仓库要求再次运行 `cd backend; .\mvnw.cmd clean verify`：BUILD SUCCESS（04:21），日志 `.runtime/budget-result-panel-verify.log`。

## 无结果与结果页面统一

`BudgetFileResults` 新旧数据分支共用文件图标、标题栏和外框。无明细时使用统一说明卡片，按处理中、读取不可用、任务已结束和暂无预算结果显示标题；具体原因优先使用既有安全原因说明，不重写 FAILED / UNKNOWN，也不把技术失败解释成材料不足。正常结果摘要使用同色说明底，风险卡片保留红色重点。历史返回按钮与页面按钮样式统一，返回逻辑不变。

Windows 前端构建通过，既有 748 tests 通过（`.runtime/budget-unified-results-tests.log`）。Chrome 109.0.5412.0 在 `.runtime/budget-unified-results-dist` 验证 17 份次真实结果与状态页，page errors 为空；390px 说明卡片无水平溢出，宽屏和窄屏截图均已查看。HTTP 仍为模拟 API，未更新 26。

本轮 Windows / JDK 17 再次执行 `cd backend; .\mvnw.cmd clean verify`：2437 tests、0 failures、0 errors、2 skipped，BUILD SUCCESS（04:24）。日志 `.runtime/budget-unified-results-verify.log`。
