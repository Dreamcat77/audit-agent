# 三模块语义与页面回归（2026-10-29）

本轮检查预算、采购、合同的上传、解析、模型事实、确定性金额核算、批次报告、历史重读及 Chrome 109 页面。仅使用合成办公材料；没有取得甲方真实材料准确率的验收证据。

## 已完成的修复

- 采购响应文件/报价函：不再仅因材料类别没有叫“报价单”而丢弃已经通过原文核对的供应商与报价。中标通知书仍不计作独立供应商报价。
- 预算、最高限价和报价分开保存和显示；跨项目冲突不套用别的项目的预算或限价。报价超过限价时，优先关注摘要也必须提示，不能同时宣称暂无明确问题。
- 否定语义：不把“不得指定品牌”“无需投标前设立本地机构”等禁止限制的条款反过来当成排他性要求；真实指定品牌、注册地限制仍需检出。
- 预算可选字段：模型把明确“申请人/经办人”错标为部门时，仅舍弃该错误角色，不因此丢弃其他已核实金额。旧版 XLS 跨工作表重复记录曾导致金额被清空，现保留已核实的单条申报31200元、批复32760元及两份来源；不擅自相加、不判定合计通过，明确说明重复记录尚待核实。
- 合同：识别“服务期自……起至……止”的期限区间；按合同总价款计算违约金并将同一分号语境内的实际逾期期间关联，120000 元×每日千分之一×60 日=7200 元，与上限6000元比较。
- 附件：单文件模型不得宣称整批没有某附件；确定性规则使用本批关联材料核对内文附件引用，分别测试附件存在与缺失。
- 合同自动八大项保留，金额不一致归入量化审查；正式审核与问题查看确认保留、折叠显示。确认不发送不支持的确定性结果接口；普通 HTTP 下不依赖 crypto.subtle。
- 页面：精简预算重复摘要；采购结果页不显示上传区；历史查询错误限于历史页面，离开历史页即停止该查询；复制按钮保持单个“已复制”。
- OCR：源代码发布曾遗漏离线方向识别权重，已从26既有安装恢复并验证。部署工具保留已安装的模型文件，避免下一次更新再次丢失。

## 已执行验证

- Java17：在 backend 运行 `.\mvnw.cmd clean verify`，3419项，0失败、0错误、24跳过，BUILD SUCCESS；日志 `.runtime/v19r3-final-verify.log`。
- 前端：840项 TypeScript 测试 + 10项 UUID/浏览器调试工具测试通过；`VITE_CONTRACT_ANALYSIS_ENABLED=true npm run build:customer`，Chrome109颜色兼容处理222项。
- AI Python：`pytest -q --basetemp=E:/audit-agent-new/.runtime/pyqa19`，2016通过、15跳过、43子测试通过。最初5项受Windows长路径影响，缩短临时路径后通过；不冒称是模型业务失败。
- 离线模型保留：`python -m unittest discover -s tools/qa26 -p test_preserve_ocr_models.py`，2项通过，覆盖重复执行和外部模型安装。
- 26修复后17批全部通过基本业务断言，证据 `evidence/v19/r1-17batch-results.json`。包括文本Word、原生PDF、跨文件批复、XLSX万元、无金额、错支/漏项、拆分风险、报价预算/限价分离、报价同义表达、真实与否定限制、64页PDF、倒置扫描、损坏附件、不完整合同与金额/份数不一致。原始13批问题记录保留于 `initial-results.json`。
- 随后10个新批次加强业务断言，9批通过，旧XLS暴露金额丢失；修复后另上传新批次通过，见 `r2-10batch-results.json` 和 `legacy-xls-final-results.json`。合同验证实际差额10000元、违约金120元/日、累计7200元、上限6000元，以及附件存在/缺失相反场景。未修改旧报告冒充通过。
- 旧格式DOC、RTF、CSV、PPTX金额提取通过；旧XLS最终通过金额保留与不确定合计的断言。三份倒置/混合扫描/内嵌扫描材料均走通OCR与真实模型，见 `legacy-format-first-results.json`、`legacy-xls-final-results.json` 和 `ocr-recovered-results.json`。
- 真实Chromium109.0.5412.0最终通过上传/自动跳转、预算Excel导出、采购HTML导出、复制状态、合同八大项、历史正式问题确认刷新持久化及1024/1440宽度检查，0页面脚本错误；见 `browser-acceptance.json`、`browser-upload-flows.json` 及对应截图。正式确认刷新用的是既有正式审核记录，不冒称该记录由本轮新上传建档。
- 切换QA候选后，四份合同报告和一份采购报告重读保持不变，见 `reopen-after-switch.json`。
- 补充一个全新业务编号的三文件合同批次，实际自动创建1份正式合同，三个FORMAL_LINK均COMPLETED，八项有内容。真实Chrome109打开该批次后自动启动正式五步，MATERIAL_VALIDATION/F02_DETERMINISTIC/SEMANTIC_AI/BREACH/TERM全部COMPLETED；BREACH/TERM使用v5。问题确认并刷新保留，0脚本错误。证据 `fresh-formal-contract.json`、`fresh-formal-run.json`、`fresh-formal-browser.json`。自动分析本身为PARTIAL，代表部分分析单元有局限，不冒称全文均已核验或业务风险全部通过。
- 分析负载期间，26 QA后端、AI健康和网页各10次请求均200，实测最大耗时7/6/2毫秒；见 `evidence/v19/load-health.json`。

## 26默认入口部署与复验

26默认入口已切换至 `v19.material-20261029-r3`。无DDL，现有配置与Nginx保留，backend/AI/Nginx均active；健康HTTP200。发布证据 `evidence/v19/default-deployment.json`。

- JAR SHA256：`ad7db3c7344a1f8c8890e768b017107b6d25fb9aaa2a97e9dac95b9027bdba85`。
- index SHA256：`0e5dca8f4f162610640d7cb92b9c4a9157b2939afdb2e38fe3f55dd55d36ab17`。
- 默认80入口真实登录、index及3个JS/CSS资源字节一致；倒置PNG新上传经过OCR→真实模型，正确提取2200000元预算。只有需求材料没有报价，批次为DATA_INSUFFICIENT，不能将此解释为OCR失败。证据 `default-http-ocr.json`。
- 默认入口真实Chrome109再次验证预算批次历史、采购免填表、合同自动入口及八大项，0脚本错误，见 `default-browser.json`。

## 验证边界

本轮使用26的真实DM8、真实配置模型与OCR，但仅为合成办公材料测试。甲方ARM实际部署、真实客户文件准确率及任意未知文件均为 NOT VERIFIED。跳过项不算通过。业务材料不足、重复申报无法确认时保留可证明的结果，不制造金额或通过结论。本轮未制作甲方离线包、未提交或推送Git。
