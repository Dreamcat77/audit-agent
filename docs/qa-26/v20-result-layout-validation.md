# v20 三模块结果页与用户流程验证

2026-10-29。仅使用合成办公材料，在26真实DM8、实际配置模型、OCR及Chromium109.0.5412.0验证。甲方真实材料准确率和ARM64现场执行仍为NOT VERIFIED。

## 结果页调整

- 三模块共用项目抬头、编号分区、重点问题卡片和来源材料样式。优先显示实际项目名，不以采购委托协议书等材料标题充当项目名。
- 预算固定保留差异清单、预算科目与批复比对、风险与审批核对；采购固定保留优先关注、报价比对、招标文件合规性、黑名单、跨材料一致性。
- 合同固定八大项，使用双列摘要卡，展开查看明细、来源和复制；保留正式五步与问题确认，不把页面再次变成逐文件报告。
- 无内容也保留模块位置，使用简短占位；不编造金额、通过结论或正式合同。来源材料明细折叠放末尾。确定的报价超限在优先关注中突出显示。
- 预算复制操作使用顺序标记，旧异步复制不能覆盖较新按钮状态。Chrome109采用本地字体、明确sRGB和受支持CSS。

## 本轮发现并修复的业务问题

新采购PDF明确写有预算36万元和最高投标限价35万元，模型实际已提取，但两个字段被Java标为UNVERIFIED：预算值包含“（含税）”，金额解析未接受；“最高投标限价”未被限价角色校验接受。真实证据在初始`proc-semantic-results.json`及对应字段记录。旧失败结果保留，没有更改其持久化报告。

修复只移除金额末尾明确的含税/不含税/税前/税后注记，接受明确的投标/响应限价表述。仍拒绝另加金额、含税另一个金额、税率、大小写不一致、保证金冒充限价、限价冒充预算。使用BigDecimal精确核算，不交给模型做加减法。

## 已执行检查

- Java17：`backend/.\mvnw.cmd clean verify`，3422项、0失败、0错误、24跳过，BUILD SUCCESS；`.runtime/v20-java-final-verify.log`。
- 前端：在frontend执行`node --experimental-transform-types --test "src/**/*.test.ts"`，843通过、0失败；`AUDIT_VITE_BUILD_ASSETS_DIR=assets/update-v20 VITE_CONTRACT_ANALYSIS_ENABLED=true npm run build:customer`成功，Chrome109兼容颜色处理222项。日志`.runtime/v20-frontend-final-tests.log`与`v20-frontend-final-build.log`。
- AI Python：在services/audit-ai执行Python3.11 `-m pytest -q --basetemp=E:/audit-agent-new/.runtime/pyqa20`，2016通过、15跳过、43子测试通过。有一条既有测试客户端弃用警告。最初错误cwd导致导入失败，纠正cwd后全量通过，不将其描述为产品故障。
- `python -m unittest discover -s tools/qa26 -p test_preserve_ocr_models.py`，2项通过。3份离线方向模型SHA与26候选和包内基线逐项核对。

## 真实用户流程与结果

使用真实Chrome109从页面选择文件上传，API仅查询、保存证据和断言，没有用API上传冒充页面测试。

- 新预算经费说明DOCX：中文大写申报120000、批复100000，差异20000；真实项目名称、差异红色标记、Excel导出通过。无金额会议纪要保持三个空模块且不造金额。初始记录`evidence/v20/user-flow-results.json`。
- 修复后新采购批次（需求PDF+报价DOCX）：预算360000、最高限价350000、报价355000、保证金5000。正确显示未超预算但超过限价5000，保证金不参与报价，否定限制条款不当作真实排他要求。`evidence/v20/final/user-flow-results.json`及`proc-semantic-results.json`。
- 新倒置PNG实际走OFFICE_TEXT_OCR→真实模型，提取预算2200000。该材料本身没有报价，报价模块保留空位，不将报价数据不足误称OCR失败。`evidence/v20/final/proc-inverted-scan-results.json`。
- 无报价工作说明仍有报告和固定采购模块，不编造预算、供应商或报价。
- 不完整合同：八项位置齐全、有依据条款保留，不伪造编号/金额建档。完整三文件合同批次实际自动建档1份，八项有内容；自动分析为PARTIAL，不能宣称所有判断均已核验。
- 新正式Run `1626e9a2-2081-423d-80b9-5e91086ffd10`的MATERIAL_VALIDATION/F02_DETERMINISTIC/SEMANTIC_AI/BREACH/TERM全部COMPLETED，问题确认刷新持久化。`evidence/v20/fresh-formal-run.json`和`fresh-formal-browser.json`。
- 最终r3真实浏览器回归：预算上传→自动结果→Excel→历史无匹配/重置；采购预算与限价分离、实际及否定条款、HTML导出、单个已复制状态、结果页无上传框；合同八项、展开、历史、正式确认。`evidence/v20/regression/browser-acceptance.json`，0脚本错误。
- 最终r3历史重开：采购两批和合同报告重读前后一致，没有POST/PUT/PATCH/DELETE新处理操作；1024和390宽度无横向溢出、复制可用。`evidence/v20/reopen/result.json`。早期测试脚本对已确认按钮/已在历史页的等待发生竞争，修正脚本后复验通过；原截图保留，不冒称业务代码错误。

## 发布与包边界

最终候选为`v20-20261029-r3`，26默认发布为`v20.material-20261029-r3`；部署、默认入口真实资源及浏览器复验以`evidence/v20/default-deployment.json`、`default-http.json`、`default-browser.json`为证据。现有配置、Nginx和业务数据保留，无DDL。

默认入口补充新批次验证：真实Chrome109上传倒置PNG、自动生成结果，实际提取预算2200000元；`evidence/v20/default-live/user-flow-results.json`，0脚本错误。

最终JAR SHA256：`a399e6d875862309e14f878564da6f4a74848be18016a899cf28ae0514d9f102`。
最终前端index SHA256：`ebb5a5d08ecc5c8e42a1aa8f9784921472f7e2f41a1f01c942cce61ba1e6aee9`。

Update_v20使用相同JAR、前端与AI源码，包含离线OCR权重。数据库SQL/schema/检查器Java17 class和安装脚本与Update_v15逐字节相同，没有新增数据库判断。真实DM8首次/重复/DDL后代码回退/漂移反例证据沿用原字节基线`docs/delivery/PACKAGE_TESTS.md`及包内`evidence/schema-platform-baseline.md`，不是声称本轮又执行了一遍迁移。包的最终清单、篡改拒绝、LF、bash语法、Java17、字体与资产引用、权限、外层SHA检查见`evidence/v20/package-verification.json`。

最终包`E:/audit-agent-new/deliverables/Update_v20.tar.gz`，84577306字节，SHA256 `7eb033bdb7deeed8aef0b12a24204bc38cd41222cfca10dd9fdf44c90ac115b1`；最终验包通过，见`evidence/v20/package-verification.json`。

本轮没有提交或推送Git。合成材料通过不能保证任意甲方文件、逐页OCR和所有语义判断准确；未核实单元仍显示部分结果，不伪造完整性。甲方ARM64包执行与原材料准确率须现场复验。
