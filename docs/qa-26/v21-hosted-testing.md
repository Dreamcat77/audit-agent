# v21 托管测试记录

## 本轮范围与当前结论

2026-10-29 在26默认80入口、实际Chrome 109.0.5412.0，按上传→接纳→后台处理→历史重开→结果页的流程，上传9批17份新合成材料。页面接纳后切换模块继续上传，不等待整批模型分析完成。材料包含Word表格、Excel批复、CSV万元、90°扫描PNG、PDF招标文件、不同项目混传、报价一分钱边界、反面示例、合同金额冲突和相对期限/缺附件。

**本轮模型业务结果未通过，不能作为模型准确率验收。** 采购确定性规则修复已独立验收并部署，26当前默认版本为`v21.material-20261029-r1`。未提交或推送Git，未制作新甲方包。

## 模型连接故障

- AI服务运行环境配置存在、没有环境代理；有到模型主机的路由，但TCP连接5秒超时。本机对同一主机端口的连接也未成功。
- 自远端2026-10-30 01:00开始的22条模型HTTP完成事件全部为CONNECT_TIMEOUT，单次约5010毫秒；涉及预算字段提取、采购字段/综合审查、合同分析。
- 这不是模型推理180秒超时、输出结构拒绝或材料缺少金额的证据。请求未获得正常模型响应，不能凭这些空结果判断原文没有金额。
- Excel确定性批复读取成功；混合批次Word申报未取得模型结果，因此无法完成两份材料的金额配对。
- 已请求恢复模型主机/110网段网络。恢复后必须用新回归批次验证；保留本次失败报告，不重写旧结果制造通过，不盲目重发UNKNOWN操作。

证据：`evidence/v21/network.json`、`evidence/v21/model-observations.json`、`evidence/v21/initial/*-result.json`。测试样本与持久化回执位于仓库`.runtime/v21-fixtures/specs.json`及`.runtime/v21-receipts.json`。

## 独立复现并修复的问题

采购F10规则将“禁止采用的反面示例：‘仅限本地注册企业参与投标’”误当成实际资格限制。真实业务返回中的规则检查与finding均能证明误报，见`initial/proc-policy-counterexample-result.json`。

修复`TenderRuleMatcher.java`：仅排除被明确标记为禁止采用的示例引文，不排除引文之外的实际约束。补充`TenderRuleNegationTest.java`，覆盖禁止示例不命中、同句/后句真正限制仍命中、普通示例不能自动豁免及既有否定/双重否定。新增反例先失败，再修复通过定向测试。最终Java17 `backend/mvnw.cmd clean verify` BUILD SUCCESS：3424项，0失败、0错误、24跳过，耗时5分28秒；其中TenderRuleNegationTest 4项通过。见`evidence/v21/java-verify.json`；完整本机日志`.runtime/v21-java-verify.log`。跳过项不算通过。

继续测试后增加`CaseReviewEngineTest.forbiddenExampleDoesNotCreateDurableRuleFindingButOperativeRestrictionDoes`，验证批次编排中不会持久化禁止示例的问题项，实际限制仍产生问题。最终再次Java17 clean verify：3425项、0失败/错误、24跳过，BUILD SUCCESS，耗时7分51秒；见`evidence/v21/java-final-verify.json`和`.runtime/v21b-java-verify.log`。

26隔离18082入口真实Chrome109上传两份新TXT招标文件：禁止示例0条地域问题，禁止示例后追加实际本地注册限制1条问题；均能从历史重开，报告GET保持一致。模型仍UNKNOWN，独立规则断言通过不代表模型语义验收。证据`evidence/v21/rule-candidate/results.json`及两份完整业务返回/截图。

与已交付Update_v20包内JAR比较，`BOOT-INF/classes`仅`TenderRuleMatcher.class`变更；前端index与AI源码基线相同。`evidence/v21/class-diff-v20.json`记录实际差异。候选JAR为`6f1a18636252687562b2b49af906d84a851efc64577df98d7851bed2bc3b1cd2`。

26侧向90°PNG直接请求真实OCR，HTTP200、38.19秒、1页99字，`贰拾叁万元`和`贰拾贰万伍仟元`均存在，原文件SHA不变。此项只确认读取和方向处理，不代表模型金额提取通过。证据`evidence/v21/ocr-check.json`。

确认隔离规则验证、OCR及变更范围后更新26默认入口至`v21.material-20261029-r1`：增量DDL为0，原配置/Nginx与3份离线OCR权重保留，后端/AI健康200，实际JAR/index哈希匹配。默认入口Chrome109重开全部9批，固定分区和合同八项保留、0脚本错误/横向溢出；仅验证历史读取和布局，不重算旧报告。见`evidence/v21/default-deployment.json`及`default-reopen/results.json`。模型端口复查仍连接超时，因此不以此宣布三模块分析质量全部通过，不制作新的甲方更新包。

## 模型不可用时的界面验证

`python .runtime/v21-user-flow.py --layout-only`：9批均能结束处理，从历史打开结果页；预算固定3分区、采购固定结果区、合同8项位置保留；采购结果中无重复上传框；0浏览器脚本错误、0横向溢出。

这里的PASS仅表示异常收尾、历史导航和报告布局通过；报价为空、预算配对未完成、正式合同未建档及附件分析未完成仍是本轮业务验收失败。证据`evidence/v21/outage-ui/results.json`及截图。

初次测试脚本曾误抓旧预算批次的幂等prepare响应；已通过历史API找回实际CSV批次，不重复上传。另一次合同上传选文件发生在前一次上传尚未解除disabled时；脚本现等待文件输入启用与历史页稳定。这两处仅为测试脚本修正，不当作产品修复。

## 网络恢复后的验收清单

1. Word申报42500.25/17500与Excel批复40000/20000分别配对；CSV万元转12800/12000；侧向扫描大写金额230000/225000。
2. 不同预算项目不得因同科目串成40000/50000配对。
3. 采购预算100000、限价98000、报价99999.99/100000/100000.01分离，预算比较精确到一分钱；保证金不混入报价。
4. 禁止采用的反面示例不误报；真实限制仍检出；跨项目预算不套到另一项目报价。
5. 合同总额500000、明细490000、中标480000形成有据差异；完整证据自动建档及正式五步审核；另一份无编号/金额合同仍保留八项、不编造正式数据，识别已上传/缺失附件及相对期限。
6. 恢复模型连接后，在已部署修复版上使用新批次复测1–5，并保存完整结果。甲方ARM和真实材料准确率仍NOT VERIFIED。
